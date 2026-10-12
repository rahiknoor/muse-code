// HitRatePipeline.java
//
// One-command pipeline for the weekly `parkev-hit-rate-check` cron
// (idea #43), with a built-in health-check verifier (idea #44).
//
// Covers BOTH creator trackers:
//   ~/workspace/creator_tracker/parkev_targets.json  -> parkev_hit_rate.md
//   ~/workspace/creator_tracker/jeremy_targets.json  -> jeremy_hit_rate.md
//
// Usage:
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/creator_tracker/HitRatePipeline.java --run [--dry-run] [--root DIR] [--today YYYY-MM-DD]
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/creator_tracker/HitRatePipeline.java --verify [--root DIR]
//
// --run:    find due 30/60/90-day windows -> look up anniversary prices via
//           stock_watch/HistoricalPrice.java -> append check records ->
//           resolve finished entries -> rewrite both scorecards ->
//           print a JSON summary on stdout for the delivery step.
// --dry-run: do everything except writing tracker/scorecard files.
// --root:   test hook — replaces $HOME as the filesystem root so tests can
//           run against /tmp copies of the state files. NEVER point it at
//           the real home during a test.
// --today:  test hook — reference date (default: today in America/New_York).
// --verify: read-only health check. Fails loud (non-zero exit + message) when:
//           * a tracker file is not valid JSON / has a bad shape,
//           * any recorded check lacks price_source (cross-check rule) or a
//             required field,
//           * the same entry has two checks for the same window,
//           * a "resolved" entry is missing a window check,
//           * a scorecard's open/resolved row counts disagree with its tracker.
//
// Numbers honesty: every price comes from HistoricalPrice.java (Yahoo chart
// data). Nothing is invented; a window with no retrievable price is left due
// and reported as skipped.

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;

public class HitRatePipeline {

    // Home directory: honor $HOME first. The JVM's "user.home" can point at
    // /root while $HOME is /home/hatch in this runtime, so user.home would
    // resolve ~/workspace to the wrong place.
    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final int[] WINDOWS = {30, 60, 90};
    static final String PRICE_SOURCE = "HistoricalPrice.java (yahoo-chart)";

    // One row per creator tracker this pipeline maintains.
    static final String[][] CREATORS = {
        {"parkev_targets.json", "parkev_hit_rate.md", "Parkev Tatevosian"},
        {"jeremy_targets.json", "jeremy_hit_rate.md", "Jeremy Lefebvre"},
    };

    static Path workspaceRoot = Path.of(homeDir(), "workspace");
    static LocalDate today = LocalDate.now(ET);
    static boolean dryRun = false;

    static final NumberFormat MONEY = NumberFormat.getNumberInstance(Locale.US);

    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run": mode = "run"; break;
                case "--verify": mode = "verify"; break;
                case "--dry-run": dryRun = true; break;
                case "--root": workspaceRoot = Path.of(args[++i], "workspace"); break;
                case "--today": today = LocalDate.parse(args[++i], DAY); break;
                default: break;
            }
        }
        if (mode == null) {
            System.out.println("Usage: HitRatePipeline.java --run [--dry-run] [--root DIR] [--today YYYY-MM-DD]");
            System.out.println("       HitRatePipeline.java --verify [--root DIR]");
            System.exit(2);
        }
        if (mode.equals("run")) run();
        else verify();
    }

    // ------------------------------------------------------------------ run

    static void run() throws Exception {
        JSONObject summary = new JSONObject();
        JSONArray creatorsOut = new JSONArray();

        for (String[] c : CREATORS) {
            Path trackerFile = workspaceRoot.resolve("creator_tracker").resolve(c[0]);
            Path scorecardFile = workspaceRoot.resolve("creator_tracker").resolve(c[1]);
            JSONObject one = processTracker(trackerFile, scorecardFile, c[2]);
            creatorsOut.put(one);
        }
        summary.put("run_date", today.format(DAY));
        summary.put("dry_run", dryRun);
        summary.put("creators", creatorsOut);
        System.out.println(summary.toString(2));
    }

    // Run the full check for one creator: find due windows, price them,
    // record checks, resolve entries, rewrite the scorecard.
    static JSONObject processTracker(Path trackerFile, Path scorecardFile, String creatorName)
            throws Exception {
        JSONObject out = new JSONObject();
        out.put("creator", creatorName);

        TrackerData td = readTracker(trackerFile);
        JSONArray entries = td.entries;

        // Step 1: find due windows, grouped by ticker so each ticker needs
        // only one HistoricalPrice call no matter how many windows are due.
        Map<String, List<DueWindow>> dueByTicker = new TreeMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (!"open".equals(e.optString("status", ""))) continue;
            if (e.isNull("target_value")) {
                // Buy calls with no stated per-share price (e.g. jl-003, jl-010)
                // can never be scored against an anniversary price — skip them
                // instead of crashing on getDouble("target_value") when a
                // window comes due. The verifier documents this contract.
                continue;
            }
            LocalDate callDate;
            try {
                callDate = LocalDate.parse(e.optString("call_date", ""), DAY);
            } catch (DateTimeParseException bad) {
                // Legacy entries with unknown/unparseable call dates can never
                // have a due window — skip them instead of crashing the run.
                continue;
            }
            long elapsed = ChronoUnit.DAYS.between(callDate, today);
            for (int w : WINDOWS) {
                if (elapsed >= w && !hasWindowCheck(e, w)) {
                    String ticker = e.getString("ticker");
                    dueByTicker.computeIfAbsent(ticker, k -> new ArrayList<>())
                               .add(new DueWindow(e, w, callDate.plusDays(w)));
                }
            }
        }

        // Step 2: price every due anniversary via the shared helper.
        JSONArray newChecks = new JSONArray();
        JSONArray skipped = new JSONArray();
        for (Map.Entry<String, List<DueWindow>> kv : dueByTicker.entrySet()) {
            String ticker = kv.getKey();
            List<String> dates = new ArrayList<>();
            for (DueWindow dw : kv.getValue()) dates.add(dw.anniversary.format(DAY));
            Map<String, PricePoint> prices = lookupPrices(ticker, dates);

            for (DueWindow dw : kv.getValue()) {
                PricePoint pp = prices.get(dw.anniversary.format(DAY));
                if (pp == null || pp.close == null) {
                    // No trading data: leave the window due for next week,
                    // report it as skipped so the run is honest about it.
                    JSONObject s = new JSONObject();
                    s.put("id", dw.entry.getString("id"));
                    s.put("ticker", ticker);
                    s.put("window_days", dw.window);
                    s.put("reason", "no price data within 14 trading days after anniversary");
                    skipped.put(s);
                    continue;
                }
                double target = dw.entry.getDouble("target_value");
                double pct = (pp.close - target) / target * 100.0;
                String verdict = pp.close >= target ? "hit" : "miss";

                JSONObject check = new JSONObject();
                check.put("check_date", today.format(DAY));
                check.put("window_days", dw.window);
                check.put("anniversary_date", dw.anniversary.format(DAY));
                check.put("anniversary_date_used", pp.dateUsed.format(DAY));
                check.put("actual_price", round2(pp.close));
                check.put("pct_vs_target", round2(pct));
                check.put("hit_or_miss", verdict);
                // Provenance (idea #44 cross-check rule): every check names
                // where its price came from. A check without this is refused
                // by --verify.
                check.put("price_source", PRICE_SOURCE);
                dw.entry.getJSONArray("checks").put(check);

                if (allWindowsChecked(dw.entry)) dw.entry.put("status", "resolved");

                JSONObject n = new JSONObject();
                n.put("id", dw.entry.getString("id"));
                n.put("ticker", ticker);
                n.put("window_days", dw.window);
                n.put("target_value", target);
                n.put("actual_price", round2(pp.close));
                n.put("pct_vs_target", round2(pct));
                n.put("hit_or_miss", verdict);
                newChecks.put(n);
            }
        }

        // Step 3: write the tracker back (canonical 2-space JSON), preserving
        // the file's original shape (object-with-entries vs bare array) and
        // any top-level metadata keys.
        if (!dryRun) {
            writeTracker(trackerFile, td);
            // Step 4: regenerate the scorecard from the tracker.
            writeScorecard(scorecardFile, entries, creatorName);
        }

        out.put("new_checks", newChecks);
        out.put("skipped_no_price", skipped);
        out.put("hit_rates", hitRates(entries));
        out.put("open_count", countByStatus(entries, "open"));
        out.put("resolved_count", countByStatus(entries, "resolved"));
        out.put("next_due_date", nextDueDate(entries));
        return out;
    }

    // ---------------------------------------------------------------- helpers

    static class DueWindow {
        final JSONObject entry;
        final int window;
        final LocalDate anniversary;
        DueWindow(JSONObject e, int w, LocalDate a) { entry = e; window = w; anniversary = a; }
    }

    static class PricePoint {
        final LocalDate dateUsed;
        final Double close; // null = no data
        PricePoint(LocalDate d, Double c) { dateUsed = d; close = c; }
    }

    // The tracker files come in two shapes: parkev's is an object with an
    // "entries" array plus metadata keys; jeremy's is currently a bare [].
    // ReadTracker remembers which shape it saw so the rewrite preserves it.
    static class TrackerData {
        boolean bareArray;
        JSONObject obj;   // set when !bareArray
        JSONArray entries;
    }

    static TrackerData readTracker(Path f) throws Exception {
        String text = Files.readString(f, StandardCharsets.UTF_8).trim();
        TrackerData td = new TrackerData();
        if (text.startsWith("[")) {
            td.bareArray = true;
            td.entries = new JSONArray(text);
        } else {
            td.bareArray = false;
            td.obj = new JSONObject(text);
            td.entries = td.obj.getJSONArray("entries");
        }
        return td;
    }

    static void writeTracker(Path f, TrackerData td) throws Exception {
        String out = td.bareArray ? td.entries.toString(2) : td.obj.toString(2);
        Files.writeString(f, out + "\n", StandardCharsets.UTF_8);
    }

    static boolean hasWindowCheck(JSONObject entry, int window) {
        JSONArray checks = entry.optJSONArray("checks");
        if (checks == null) return false;
        for (int i = 0; i < checks.length(); i++) {
            if (checks.getJSONObject(i).optInt("window_days", -1) == window) return true;
        }
        return false;
    }

    static boolean allWindowsChecked(JSONObject entry) {
        for (int w : WINDOWS) if (!hasWindowCheck(entry, w)) return false;
        return true;
    }

    // One HistoricalPrice.java call per ticker, all due dates at once.
    static Map<String, PricePoint> lookupPrices(String ticker, List<String> dates) throws Exception {
        Map<String, PricePoint> out = new LinkedHashMap<>();
        String javaBin = javaBinary();
        Path helper = workspaceRoot.resolve("stock_watch").resolve("HistoricalPrice.java");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add(helper.toString());
        cmd.add(ticker);
        cmd.addAll(dates);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            System.err.println("WARNING: HistoricalPrice.java failed for " + ticker + ": " + stdout);
            return out;
        }
        JSONObject root = new JSONObject(stdout);
        JSONObject perDate = root.getJSONObject(ticker.toUpperCase());
        for (String d : dates) {
            JSONObject rec = perDate.optJSONObject(d);
            if (rec == null) continue;
            // HistoricalPrice emits {"date_used": null, "close": null} when a
            // date has no trading data (2026-10-11: crashed the --run with
            // getString on null). Skip it like any other no-data date — the
            // window stays due and is reported as skipped below.
            String usedStr = rec.optString("date_used", null);
            if (usedStr == null || usedStr.isBlank()) continue;
            LocalDate used = LocalDate.parse(usedStr, DAY);
            Double close = rec.isNull("close") ? null : rec.getDouble("close");
            out.put(d, new PricePoint(used, close));
        }
        return out;
    }

    static String javaBinary() {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isEmpty()) return jh + "/bin/java";
        return "java";
    }

    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    static String money(double v) {
        // Whole-dollar targets read "$1,494"; fractional ones "$64.50".
        if (v == Math.floor(v) && !Double.isInfinite(v)) return "$" + MONEY.format((long) v);
        return "$" + String.format(Locale.US, "%,.2f", v);
    }

    static String pct1(double v) {
        return String.format(Locale.US, "%+.1f%%", v);
    }

    static int countByStatus(JSONArray entries, String status) {
        int n = 0;
        for (int i = 0; i < entries.length(); i++) {
            if (status.equals(entries.getJSONObject(i).optString("status", ""))) n++;
        }
        return n;
    }

    static JSONObject hitRates(JSONArray entries) {
        JSONObject o = new JSONObject();
        for (int w : WINDOWS) {
            int resolved = 0, hits = 0;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject c = findCheck(entries.getJSONObject(i), w);
                if (c != null) {
                    resolved++;
                    if ("hit".equals(c.optString("hit_or_miss"))) hits++;
                }
            }
            JSONObject r = new JSONObject();
            r.put("resolved", resolved);
            r.put("hits", hits);
            r.put("hit_rate", resolved == 0 ? JSONObject.NULL : round2(hits * 100.0 / resolved));
            o.put(w + "d", r);
        }
        return o;
    }

    static JSONObject findCheck(JSONObject entry, int window) {
        JSONArray checks = entry.optJSONArray("checks");
        if (checks == null) return null;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject c = checks.getJSONObject(i);
            if (c.optInt("window_days", -1) == window) return c;
        }
        return null;
    }

    static String nextDueDate(JSONArray entries) {
        LocalDate next = null;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (!"open".equals(e.optString("status", ""))) continue;
            if (e.isNull("target_value")) continue; // unscorable — --run skips these too
            LocalDate callDate;
            try {
                callDate = LocalDate.parse(e.optString("call_date", ""), DAY);
            } catch (DateTimeParseException bad) {
                continue; // legacy entry with unknown call date — no due window
            }
            for (int w : WINDOWS) {
                if (hasWindowCheck(e, w)) continue;
                LocalDate due = callDate.plusDays(w);
                if (!due.isBefore(today) && (next == null || due.isBefore(next))) next = due;
            }
        }
        return next == null ? "none" : next.format(DAY);
    }

    // ---------------------------------------------------------------- scorecard

    // Regenerate the scorecard from the tracker. The file's existing header
    // (title, intro, methodology / clip-channel rule — everything before the
    // first "## " heading) is preserved verbatim; the data sections below it
    // are rebuilt so they can never drift from the tracker JSON.
    static void writeScorecard(Path scorecardFile, JSONArray entries, String creatorName)
            throws Exception {
        String existing = Files.exists(scorecardFile)
                ? Files.readString(scorecardFile, StandardCharsets.UTF_8) : "";
        String header = existing;
        // Split right before the first DATA section ("## Summary"). Anything
        // above it — title, intro, methodology, the clip-channel rule — is
        // stable prose and is preserved verbatim.
        int h = existing.indexOf("\n## Summary");
        if (h < 0) h = existing.indexOf("\n## ");
        if (h >= 0) header = existing.substring(0, h);
        header = header.stripTrailing() + "\n";

        StringBuilder sb = new StringBuilder(header);
        sb.append("\n## Summary\n\n");
        sb.append("| Metric | 30-day | 60-day | 90-day |\n");
        sb.append("|---|---|---|---|\n");
        JSONObject rates = hitRates(entries);
        int logged = entries.length();
        sb.append("| Calls logged | ").append(logged)
          .append(" | ").append(logged).append(" | ").append(logged).append(" |\n");
        sb.append("| Resolved |");
        for (int w : WINDOWS) sb.append(" ").append(rates.getJSONObject(w + "d").getInt("resolved")).append(" |");
        sb.append("\n| Hit rate |");
        for (int w : WINDOWS) {
            JSONObject r = rates.getJSONObject(w + "d");
            if (r.isNull("hit_rate")) sb.append(" — |");
            else sb.append(" ").append(String.format(Locale.US, "%.1f%% (%d/%d)",
                    r.getDouble("hit_rate"), r.getInt("hits"), r.getInt("resolved"))).append(" |");
        }
        sb.append("\n\n## Resolved calls\n\n");

        List<JSONObject> resolved = new ArrayList<>(), open = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            String st = e.optString("status", "");
            // 'title-derived' placeholders are coverage markers, not calls, and
            // legacy entries with no numeric target can't be scored — both stay
            // out of the resolved/open scorecard sections.
            if (e.isNull("target_value")) continue;
            if ("resolved".equals(st)) {
                resolved.add(e);
            } else if ("open".equals(st)) {
                open.add(e);
            }
        }
        if (resolved.isEmpty()) {
            sb.append("_None yet._\n");
        } else {
            sb.append("| ID | Ticker | Type | Target | 30d | 60d | 90d |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            for (JSONObject e : resolved) {
                sb.append("| ").append(e.getString("id"))
                  .append(" | ").append(e.getString("ticker"))
                  .append(" | ").append(e.getString("target_type").replace('_', ' '))
                  .append(" | ").append(money(e.getDouble("target_value")));
                for (int w : WINDOWS) {
                    JSONObject c = findCheck(e, w);
                    if (c == null) { sb.append(" | —"); continue; }
                    String mark = "hit".equals(c.optString("hit_or_miss")) ? "\u2705" : "\u274C";
                    sb.append(" | ").append(mark).append(" ")
                      .append(money(c.getDouble("actual_price")))
                      .append(" (").append(pct1(c.getDouble("pct_vs_target"))).append(")");
                }
                sb.append(" |\n");
            }
        }

        sb.append("\n## Open calls awaiting checks\n\n");
        if (open.isEmpty()) {
            sb.append("_None — every logged call is resolved._\n");
        } else {
            sb.append("| ID | Ticker | Type | Target | Price at call | Call date | 30d due | 60d due | 90d due |\n");
            sb.append("|---|---|---|---|---|---|---|---|---|\n");
            for (JSONObject e : open) {
                LocalDate callDate;
                try {
                    callDate = LocalDate.parse(e.optString("call_date", ""), DAY);
                } catch (DateTimeParseException bad) {
                    callDate = null; // legacy entry with unknown call date
                }
                sb.append("| ").append(e.getString("id"))
                  .append(" | ").append(e.getString("ticker"))
                  .append(" | ").append(e.getString("target_type").replace('_', ' '))
                  .append(" | ").append(money(e.getDouble("target_value")))
                  .append(" | ").append(e.has("price_at_call") && !e.isNull("price_at_call")
                          ? money(e.getDouble("price_at_call")) : "—")
                  .append(" | ").append(callDate == null ? "—" : callDate.format(DAY))
                  .append(" | ").append(callDate == null ? "—" : callDate.plusDays(30).format(DAY))
                  .append(" | ").append(callDate == null ? "—" : callDate.plusDays(60).format(DAY))
                  .append(" | ").append(callDate == null ? "—" : callDate.plusDays(90).format(DAY))
                  .append(" |\n");
            }
        }
        sb.append("\n_Last updated: ").append(today.format(DAY))
          .append(" (HitRatePipeline)._\n");
        Files.writeString(scorecardFile, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- verify

    // Read-only health check (idea #44). Any problem -> message on stderr and
    // non-zero exit so the cron fails loud instead of reporting bad numbers.
    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String[] c : CREATORS) {
            Path trackerFile = workspaceRoot.resolve("creator_tracker").resolve(c[0]);
            Path scorecardFile = workspaceRoot.resolve("creator_tracker").resolve(c[1]);
            verifyTracker(trackerFile, c[2], problems);
            verifyScorecard(trackerFile, scorecardFile, c[2], problems);
        }
        if (!problems.isEmpty()) {
            System.err.println("VERIFY FAILED:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(1);
        }
        System.out.println("VERIFY OK: both trackers and scorecards are consistent.");
    }

    static void verifyTracker(Path trackerFile, String creatorName, List<String> problems) {
        TrackerData td;
        try {
            td = readTracker(trackerFile);
        } catch (Exception e) {
            problems.add(creatorName + ": tracker file is not valid JSON: " + e.getMessage());
            return;
        }
        JSONArray entries = td.entries;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e;
            try {
                e = entries.getJSONObject(i);
            } catch (Exception ex) {
                problems.add(creatorName + ": entry #" + i + " is not a JSON object");
                continue;
            }
            String id = e.optString("id", "entry #" + i);
            if ("title-derived".equals(e.optString("status", ""))) {
                // Coverage placeholder from the backfill (transcript pending,
                // not a creator-stated call) — --run skips these too, so the
                // call-data required fields do not apply. Id presence above
                // is the only structural check a placeholder needs.
                continue;
            }
            for (String f : new String[]{"ticker", "call_date", "target_type", "status"}) {
                if (!e.has(f) || e.isNull(f))
                    problems.add(creatorName + " " + id + ": missing required field '" + f + "'");
            }
            // target_value is intentionally OPTIONAL: buy calls with no stated
            // per-share price (e.g. Jeremy jl-003, jl-010) are unscorable, not
            // broken — the scorecard writer excludes them from its sections by
            // design, --run skips them, and verifyScorecard counts the same
            // scoreable population. Never invent a target_value for them.
            if (!e.isNull("target_value") && !(e.get("target_value") instanceof Number))
                problems.add(creatorName + " " + id + ": 'target_value' is not a number");
            JSONArray checks = e.optJSONArray("checks");
            if (checks == null) {
                problems.add(creatorName + " " + id + ": missing 'checks' array");
                continue;
            }
            java.util.Set<Integer> seenWindows = new java.util.HashSet<>();
            for (int j = 0; j < checks.length(); j++) {
                JSONObject ch = checks.optJSONObject(j);
                if (ch == null) {
                    problems.add(creatorName + " " + id + ": check #" + j + " is not an object");
                    continue;
                }
                int w = ch.optInt("window_days", -1);
                if (w != 30 && w != 60 && w != 90)
                    problems.add(creatorName + " " + id + ": check has bad window_days=" + w);
                if (!seenWindows.add(w))
                    problems.add(creatorName + " " + id + ": duplicate check for window " + w + "d");
                // Cross-check rule: every check must name its price source.
                if (ch.optString("price_source", "").isBlank())
                    problems.add(creatorName + " " + id + ": window " + w
                            + "d check has no price_source (provenance required)");
                for (String f : new String[]{"check_date", "anniversary_date", "actual_price",
                        "pct_vs_target", "hit_or_miss"})
                    if (!ch.has(f) || ch.isNull(f))
                        problems.add(creatorName + " " + id + ": window " + w
                                + "d check missing '" + f + "'");
            }
            if ("resolved".equals(e.optString("status", "")) && !allWindowsChecked(e))
                problems.add(creatorName + " " + id + ": status is 'resolved' but a window check is missing");
        }
    }

    static void verifyScorecard(Path trackerFile, Path scorecardFile, String creatorName,
                                List<String> problems) {
        TrackerData td;
        try {
            td = readTracker(trackerFile);
        } catch (Exception e) {
            return; // already reported by verifyTracker
        }
        int openN = countScoreableByStatus(td.entries, "open");
        int resolvedN = countScoreableByStatus(td.entries, "resolved");
        String md;
        try {
            md = Files.readString(scorecardFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            problems.add(creatorName + ": scorecard file unreadable: " + e.getMessage());
            return;
        }
        int openRows = countDataRows(md, "## Open calls awaiting checks");
        int resolvedRows = countDataRows(md, "## Resolved calls");
        if (openRows != openN)
            problems.add(creatorName + ": scorecard lists " + openRows
                    + " open calls but tracker has " + openN);
        if (resolvedRows != resolvedN)
            problems.add(creatorName + ": scorecard lists " + resolvedRows
                    + " resolved calls but tracker has " + resolvedN);
    }

    // Mirror of the scorecard writer's deliberate exclusion: entries with no
    // numeric target can't be scored and stay out of the scorecard's
    // resolved/open sections, so the verifier must count the same
    // scoreable population the writer writes — never all-status counts.
    static int countScoreableByStatus(JSONArray entries, String status) {
        int n = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (e.isNull("target_value")) continue;
            if (status.equals(e.optString("status", ""))) n++;
        }
        return n;
    }

    // Count markdown table data rows in the section starting at `heading`:    // lines starting with '|' that are neither the header row nor the
    // '|---|---|' separator. A "_None yet._" placeholder counts as zero.
    static int countDataRows(String md, String heading) {        int n = 0;
        boolean inSection = false, headerSeen = false;
        for (String line : md.split("\n")) {
            if (line.startsWith("## ")) {
                if (inSection) break;
                if (line.trim().equals(heading)) { inSection = true; headerSeen = false; }
                continue;
            }
            if (!inSection) continue;
            String t = line.trim();
            if (!t.startsWith("|")) continue;
            if (t.contains("---")) continue;
            if (!headerSeen) { headerSeen = true; continue; } // column header row
            n++;
        }
        return n;
    }
}
