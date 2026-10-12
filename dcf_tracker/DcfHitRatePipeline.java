// DcfHitRatePipeline.java
//
// Idea #45: DCF hit-rate tracker. Scores MY methodology-fixed DCF intrinsic
// values against actual price moves over time — the same concept as the
// Parkev creator tracker, but grading my calls instead of a creator's.
// A public scoreboard that keeps me honest.
//
// Files:
//   ~/workspace/dcf_tracker/muse_dcf_targets.json  (append-only call log)
//   ~/workspace/dcf_tracker/dcf_hit_rate.md        (human-readable scorecard)
//
// Usage:
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/dcf_tracker/DcfHitRatePipeline.java --seed [--root DIR] [--today YYYY-MM-DD]
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/dcf_tracker/DcfHitRatePipeline.java --run [--dry-run] [--root DIR] [--today YYYY-MM-DD]
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/dcf_tracker/DcfHitRatePipeline.java --verify [--root DIR]
//
// --seed:   build the baseline call log from dcf_csv/our_dcf.json (asof
//           2026-09-27): one open entry per company, price_at_call from the
//           shared price cache (PriceCache.java). Only methodology-fixed
//           values are ever logged — the retired original-model values are
//           history and are never scored.
// --run:    (1) append a new entry for any ticker whose our_dcf.json value
//           changed since its last logged call (each call is graded from its
//           own call date, so old calls keep scoring their original value);
//           (2) find due 30/60/90-day windows; (3) price anniversaries via
//           stock_watch/HistoricalPrice.java; (4) record checks with the
//           convergence verdict; (5) resolve finished entries; (6) rewrite
//           the scorecard; (7) print a JSON delivery summary on stdout.
// --dry-run: do everything except writing tracker/scorecard files.
// --root:   test hook — replaces $HOME as the filesystem root so tests can
//           run against /tmp copies of the state files. NEVER point it at
//           the real home during a test.
// --today:  test hook — reference date (default: today in America/New_York).
// --verify: read-only health check. Fails loud (non-zero exit + message)
//           when the tracker is malformed, a check lacks price_source, a
//           window is recorded twice, the recorded hit/miss verdict does not
//           match the recomputed convergence math, a non-fixed-methodology
//           entry is present, or a scorecard's row counts disagree with its
//           tracker.
//
// THE HIT RULE (why it differs from Parkev's):
// Parkev's tracker uses "hit = price at or above target". That fits a
// buy-call target but NOT a DCF intrinsic value: for any company where my
// DCF sits below the market price, "at or above" would hand a free hit
// every time (AMZN at ~$220 is trivially at or above my $31.04). A DCF is a
// claim about fair value, so the honest test is CONVERGENCE: did the market
// move toward my number?
//   hit  = |actual_price - dcf_value| < |price_at_call - dcf_value|
//   miss = otherwise (gap widened, or did not narrow).
// This grades every call identically whether my DCF is above or below the
// market price.
//
// Numbers honesty: price_at_call comes from the shared price cache
// (PriceCache.java --get; the 7:30 AM job owns all writes — this pipeline
// never fetches live prices). Anniversary prices come from
// HistoricalPrice.java (Yahoo chart data), which walks forward past
// weekends and holidays itself. A window with no retrievable price is left
// due and reported as skipped — nothing is invented.

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;

public class DcfHitRatePipeline {

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
    static final String CACHE_SOURCE = "PriceCache.java (price_cache.json)";
    static final String METHODOLOGY = "methodology-fixed (mid-year discounting + growth fade)";
    // A DCF change smaller than half a cent is rounding noise, not a new call.
    static final double REVISION_EPSILON = 0.005;

    static final String TRACKER_NAME = "muse_dcf_targets.json";
    static final String SCORECARD_NAME = "dcf_hit_rate.md";

    static Path workspaceRoot = Path.of(homeDir(), "workspace");
    static LocalDate today = LocalDate.now(ET);
    static boolean dryRun = false;

    static final NumberFormat MONEY = NumberFormat.getNumberInstance(Locale.US);

    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": mode = "seed"; break;
                case "--run": mode = "run"; break;
                case "--verify": mode = "verify"; break;
                case "--dry-run": dryRun = true; break;
                case "--root": workspaceRoot = Path.of(args[++i], "workspace"); break;
                case "--today": today = LocalDate.parse(args[++i], DAY); break;
                default: break;
            }
        }
        if (mode == null) {
            System.out.println("Usage: DcfHitRatePipeline.java --seed|--run|--verify [--dry-run] [--root DIR] [--today YYYY-MM-DD]");
            System.exit(2);
        }
        switch (mode) {
            case "seed": seed(); break;
            case "run": run(); break;
            case "verify": verify(); break;
        }
    }

    static Path trackerFile() {
        return workspaceRoot.resolve("dcf_tracker").resolve(TRACKER_NAME);
    }

    static Path scorecardFile() {
        return workspaceRoot.resolve("dcf_tracker").resolve(SCORECARD_NAME);
    }

    // ------------------------------------------------------------------ seed

    // Build the baseline call log: one open entry per company from
    // dcf_csv/our_dcf.json, price_at_call from the shared price cache.
    static void seed() throws Exception {
        Path dcfJson = workspaceRoot.resolve("dcf_csv").resolve("our_dcf.json");
        JSONObject dcf = new JSONObject(Files.readString(dcfJson, StandardCharsets.UTF_8));
        JSONObject values = dcf.getJSONObject("values");
        String asof = dcf.optString("asof", today.format(DAY));

        Map<String, String> companyNames = readCompanyNames();

        // One PriceCache call for every ticker (the #39 single-writer rule:
        // readers read the cache; the 7:30 AM job owns all writes).
        List<String> tickers = new ArrayList<>();
        for (String t : values.keySet()) tickers.add(t);
        java.util.Collections.sort(tickers);
        Map<String, CachePrice> cache = lookupCachePrices(tickers);

        JSONArray entries = new JSONArray();
        JSONArray skipped = new JSONArray();
        for (String ticker : tickers) {
            double dcfValue = values.getJSONObject(ticker).getDouble("value");
            CachePrice cp = cache.get(ticker);
            if (cp == null || cp.price == null) {
                JSONObject s = new JSONObject();
                s.put("ticker", ticker);
                s.put("reason", "no price in shared cache — entry NOT seeded; will seed on next run");
                skipped.put(s);
                continue;
            }
            JSONObject e = newEntry(ticker,
                    companyNames.getOrDefault(ticker, ticker),
                    dcfValue, cp.price, cp.asof);
            entries.put(e);
        }

        JSONObject tracker = new JSONObject();
        tracker.put("created", today.format(DAY));
        tracker.put("description", "Append-only log of my methodology-fixed DCF intrinsic values, one entry per "
                + "call (idea #45). A monthly cron checks each call against the actual price at 30/60/90 days. "
                + "Hit = the market price moved TOWARD my DCF (the price gap narrowed) — see dcf_hit_rate.md for "
                + "why this convergence rule is used instead of the creator tracker's at-or-above rule. "
                + "Only methodology-fixed values are ever logged; the retired original-model values are history "
                + "and are never scored. Baseline batch seeded " + today.format(DAY)
                + " from dcf_csv/our_dcf.json (asof " + asof + ").");
        tracker.put("entries", entries);

        if (!dryRun) {
            Files.createDirectories(trackerFile().getParent());
            Files.writeString(trackerFile(), tracker.toString(2) + "\n", StandardCharsets.UTF_8);
            writeScorecard(entries);
        }

        JSONObject summary = new JSONObject();
        summary.put("mode", "seed");
        summary.put("run_date", today.format(DAY));
        summary.put("dry_run", dryRun);
        summary.put("entries_seeded", entries.length());
        summary.put("skipped_no_cache_price", skipped);
        System.out.println(summary.toString(2));
    }

    static JSONObject newEntry(String ticker, String company, double dcfValue,
                               double priceAtCall, String priceAsof) {
        JSONObject e = new JSONObject();
        e.put("id", "mdcf-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + ticker.replace(".", ""));
        e.put("ticker", ticker);
        e.put("company", company);
        e.put("dcf_value", dcfValue);
        e.put("methodology", METHODOLOGY);
        e.put("call_date", today.format(DAY));
        e.put("price_at_call", priceAtCall);
        e.put("price_at_call_asof", priceAsof);
        e.put("price_at_call_source", CACHE_SOURCE);
        e.put("status", "open");
        e.put("checks", new JSONArray());
        return e;
    }

    // Company names live in the comparison CSV (first two columns).
    static Map<String, String> readCompanyNames() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Path csv = workspaceRoot.resolve("dcf_csv").resolve("dcf-comparison.csv");
        if (!Files.exists(csv)) return out;
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("\"Valuation Comparison") || t.startsWith("All source")
                    || t.startsWith("Company,")) continue;
            // Minimal CSV split handling quotes: Company,"Market close...,Ticker,...
            List<String> cols = splitCsv(t);
            if (cols.size() >= 2 && cols.get(1).matches("[A-Z.]{1,7}")) {
                out.put(cols.get(1), cols.get(0));
            }
        }
        return out;
    }

    static List<String> splitCsv(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { inQ = !inQ; continue; }
            if (c == ',' && !inQ) { cols.add(cur.toString().trim()); cur.setLength(0); continue; }
            cur.append(c);
        }
        cols.add(cur.toString().trim());
        return cols;
    }

    static class CachePrice {
        final Double price; // null = missing
        final String asof;
        CachePrice(Double p, String a) { price = p; asof = a; }
    }

    // One PriceCache.java --get call for all tickers. Prints
    // "TICKER|price|fetched_at|source" lines (plus "#" comment lines).
    static Map<String, CachePrice> lookupCachePrices(List<String> tickers) throws Exception {
        Map<String, CachePrice> out = new LinkedHashMap<>();
        if (tickers.isEmpty()) return out;
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        cmd.add(workspaceRoot.resolve("stock_watch").resolve("PriceCache.java").toString());
        cmd.add("--get");
        cmd.add(String.join(",", tickers));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            System.err.println("WARNING: PriceCache.java --get failed: " + stdout);
            return out;
        }
        for (String line : stdout.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] parts = t.split("\\|", -1);
            if (parts.length < 2) continue;
            String ticker = parts[0].trim();
            if (parts[1].trim().equals("MISSING") || parts[1].trim().isEmpty()) {
                out.put(ticker, new CachePrice(null, ""));
            } else {
                String asof = parts.length > 2 ? parts[2].trim() : "";
                out.put(ticker, new CachePrice(Double.parseDouble(parts[1].trim()), asof));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------- run

    static void run() throws Exception {
        JSONObject tracker = readTracker();
        JSONArray entries = tracker.getJSONArray("entries");

        // Step 0: append a new entry for any ticker whose current DCF value
        // differs from its most recently logged call. Each call is graded
        // from its own call date, so old calls keep scoring their original
        // value while the new value gets its own fresh 30/60/90-day clock.
        JSONArray newRevisions = appendRevisionEntries(entries);

        // Step 1: find due windows, grouped by ticker so each ticker needs
        // only one HistoricalPrice call no matter how many windows are due.
        Map<String, List<DueWindow>> dueByTicker = new TreeMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (!"open".equals(e.optString("status", ""))) continue;
            LocalDate callDate = LocalDate.parse(e.getString("call_date"), DAY);
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
                    // No trading data: leave the window due for next month,
                    // report it as skipped so the run is honest about it.
                    JSONObject s = new JSONObject();
                    s.put("id", dw.entry.getString("id"));
                    s.put("ticker", ticker);
                    s.put("window_days", dw.window);
                    s.put("reason", "no price data within 14 trading days after anniversary");
                    skipped.put(s);
                    continue;
                }
                double dcf = dw.entry.getDouble("dcf_value");
                double priceAtCall = dw.entry.getDouble("price_at_call");
                double gapCall = Math.abs(priceAtCall - dcf);
                double gapCheck = Math.abs(pp.close - dcf);
                // THE HIT RULE: convergence. The market moved toward my DCF.
                String verdict = gapCheck < gapCall ? "hit" : "miss";
                double pctVsDcf = (pp.close - dcf) / dcf * 100.0;

                JSONObject check = new JSONObject();
                check.put("check_date", today.format(DAY));
                check.put("window_days", dw.window);
                check.put("anniversary_date", dw.anniversary.format(DAY));
                check.put("anniversary_date_used", pp.dateUsed.format(DAY));
                check.put("actual_price", round2(pp.close));
                check.put("gap_at_call", round2(gapCall));
                check.put("gap_at_check", round2(gapCheck));
                check.put("pct_vs_dcf", round2(pctVsDcf));
                check.put("hit_or_miss", verdict);
                // Provenance (cross-check rule): every check names where its
                // price came from. A check without this is refused by --verify.
                check.put("price_source", PRICE_SOURCE);
                dw.entry.getJSONArray("checks").put(check);

                if (allWindowsChecked(dw.entry)) dw.entry.put("status", "resolved");

                JSONObject n = new JSONObject();
                n.put("id", dw.entry.getString("id"));
                n.put("ticker", ticker);
                n.put("window_days", dw.window);
                n.put("dcf_value", dcf);
                n.put("actual_price", round2(pp.close));
                n.put("gap_at_call", round2(gapCall));
                n.put("gap_at_check", round2(gapCheck));
                n.put("pct_vs_dcf", round2(pctVsDcf));
                n.put("hit_or_miss", verdict);
                newChecks.put(n);
            }
        }

        // Step 3: write the tracker back (canonical 2-space JSON), then
        // Step 4: regenerate the scorecard from the tracker.
        if (!dryRun) {
            writeTracker(tracker);
            writeScorecard(entries);
        }

        JSONObject summary = new JSONObject();
        summary.put("mode", "run");
        summary.put("run_date", today.format(DAY));
        summary.put("dry_run", dryRun);
        summary.put("new_revision_entries", newRevisions);
        summary.put("new_checks", newChecks);
        summary.put("skipped_no_price", skipped);
        summary.put("hit_rates", hitRates(entries));
        summary.put("open_count", countByStatus(entries, "open"));
        summary.put("resolved_count", countByStatus(entries, "resolved"));
        summary.put("next_due_date", nextDueDate(entries));
        System.out.println(summary.toString(2));
    }

    // Compare our_dcf.json against the most recent entry per ticker; log a
    // new call wherever the value moved beyond rounding noise.
    static JSONArray appendRevisionEntries(JSONArray entries) throws Exception {
        JSONArray added = new JSONArray();
        Path dcfJson = workspaceRoot.resolve("dcf_csv").resolve("our_dcf.json");
        if (!Files.exists(dcfJson)) return added;
        JSONObject dcf = new JSONObject(Files.readString(dcfJson, StandardCharsets.UTF_8));
        JSONObject values = dcf.getJSONObject("values");

        Map<String, String> companyNames = readCompanyNames();
        // Latest entry per ticker = last one in file order with that ticker.
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            latest.put(e.getString("ticker"), e);
        }

        List<String> changedTickers = new ArrayList<>();
        Map<String, Double> newValues = new LinkedHashMap<>();
        for (String ticker : values.keySet()) {
            double now = values.getJSONObject(ticker).getDouble("value");
            JSONObject prev = latest.get(ticker);
            if (prev == null) {
                changedTickers.add(ticker);
                newValues.put(ticker, now);
            } else if (Math.abs(prev.getDouble("dcf_value") - now) > REVISION_EPSILON) {
                changedTickers.add(ticker);
                newValues.put(ticker, now);
            }
        }
        if (changedTickers.isEmpty()) return added;

        Map<String, CachePrice> cache = lookupCachePrices(changedTickers);
        for (String ticker : changedTickers) {
            CachePrice cp = cache.get(ticker);
            double now = newValues.get(ticker);
            JSONObject prev = latest.get(ticker);
            if (cp == null || cp.price == null) {
                JSONObject s = new JSONObject();
                s.put("ticker", ticker);
                s.put("reason", "DCF changed but no price in shared cache — revision NOT logged");
                added.put(s);
                continue;
            }
            JSONObject e = newEntry(ticker,
                    companyNames.getOrDefault(ticker,
                            prev != null ? prev.optString("company", ticker) : ticker),
                    now, cp.price, cp.asof);
            if (prev != null) e.put("supersedes", prev.getString("id"));
            entries.put(e);
            JSONObject a = new JSONObject();
            a.put("id", e.getString("id"));
            a.put("ticker", ticker);
            a.put("old_value", prev != null ? prev.getDouble("dcf_value") : JSONObject.NULL);
            a.put("new_value", now);
            a.put("price_at_call", cp.price);
            added.put(a);
        }
        return added;
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

    static JSONObject readTracker() throws Exception {
        String text = Files.readString(trackerFile(), StandardCharsets.UTF_8).trim();
        return new JSONObject(text);
    }

    static void writeTracker(JSONObject tracker) throws Exception {
        Files.writeString(trackerFile(), tracker.toString(2) + "\n", StandardCharsets.UTF_8);
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
        Path helper = workspaceRoot.resolve("stock_watch").resolve("HistoricalPrice.java");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
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
            LocalDate used = LocalDate.parse(rec.getString("date_used"), DAY);
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
        // Whole-dollar values read "$1,494"; fractional ones "$64.50".
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
            LocalDate callDate = LocalDate.parse(e.getString("call_date"), DAY);
            for (int w : WINDOWS) {
                if (hasWindowCheck(e, w)) continue;
                LocalDate due = callDate.plusDays(w);
                if (!due.isBefore(today) && (next == null || due.isBefore(next))) next = due;
            }
        }
        return next == null ? "none" : next.format(DAY);
    }

    // -------------------------------------------------------------- scorecard

    // Regenerate the scorecard from the tracker. The file's existing header
    // (title, intro, methodology — everything before the first "## Summary"
    // heading) is preserved verbatim; the data sections below it are rebuilt
    // so they can never drift from the tracker JSON.
    static void writeScorecard(JSONArray entries) throws Exception {
        String existing = Files.exists(scorecardFile())
                ? Files.readString(scorecardFile(), StandardCharsets.UTF_8) : "";
        String header = existing;
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
            ("resolved".equals(e.optString("status", "")) ? resolved : open).add(e);
        }
        if (resolved.isEmpty()) {
            sb.append("_None yet — the first 30-day checks come due 30 days after the baseline batch._\n");
        } else {
            sb.append("| ID | Ticker | My DCF | 30d | 60d | 90d |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            for (JSONObject e : resolved) {
                sb.append("| ").append(e.getString("id"))
                  .append(" | ").append(e.getString("ticker"))
                  .append(" | ").append(money(e.getDouble("dcf_value")));
                for (int w : WINDOWS) {
                    JSONObject c = findCheck(e, w);
                    if (c == null) { sb.append(" | —"); continue; }
                    String mark = "hit".equals(c.optString("hit_or_miss")) ? "\u2705" : "\u274C";
                    sb.append(" | ").append(mark).append(" ")
                      .append(money(c.getDouble("actual_price")))
                      .append(" (gap ").append(money(c.getDouble("gap_at_call")))
                      .append(" → ").append(money(c.getDouble("gap_at_check"))).append(")");
                }
                sb.append(" |\n");
            }
        }

        sb.append("\n## Open calls awaiting checks\n\n");
        if (open.isEmpty()) {
            sb.append("_None — every logged call is resolved._\n");
        } else {
            sb.append("| ID | Ticker | Company | My DCF | Price at call | Call date | 30d due | 60d due | 90d due |\n");
            sb.append("|---|---|---|---|---|---|---|---|---|\n");
            for (JSONObject e : open) {
                LocalDate callDate = LocalDate.parse(e.getString("call_date"), DAY);
                sb.append("| ").append(e.getString("id"))
                  .append(" | ").append(e.getString("ticker"))
                  .append(" | ").append(e.getString("company"))
                  .append(" | ").append(money(e.getDouble("dcf_value")))
                  .append(" | ").append(money(e.getDouble("price_at_call")))
                  .append(" | ").append(callDate.format(DAY))
                  .append(" | ").append(callDate.plusDays(30).format(DAY))
                  .append(" | ").append(callDate.plusDays(60).format(DAY))
                  .append(" | ").append(callDate.plusDays(90).format(DAY))
                  .append(" |\n");
            }
        }
        sb.append("\n_Last updated: ").append(today.format(DAY))
          .append(" (DcfHitRatePipeline)._\n");
        Files.writeString(scorecardFile(), sb.toString(), StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------------- verify

    // Read-only health check. Any problem -> message on stderr and non-zero
    // exit so the cron fails loud instead of reporting bad numbers.
    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        JSONObject tracker;
        try {
            tracker = readTracker();
        } catch (Exception e) {
            System.err.println("VERIFY FAILED:\n  - tracker file is not valid JSON: " + e.getMessage());
            System.exit(1);
            return;
        }
        JSONArray entries;
        try {
            entries = tracker.getJSONArray("entries");
        } catch (Exception e) {
            System.err.println("VERIFY FAILED:\n  - tracker has no 'entries' array");
            System.exit(1);
            return;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e;
            try {
                e = entries.getJSONObject(i);
            } catch (Exception ex) {
                problems.add("entry #" + i + " is not a JSON object");
                continue;
            }
            String id = e.optString("id", "entry #" + i);
            for (String f : new String[]{"ticker", "company", "dcf_value", "methodology",
                    "call_date", "price_at_call", "price_at_call_source", "status"}) {
                if (!e.has(f) || e.isNull(f))
                    problems.add(id + ": missing required field '" + f + "'");
            }
            // Only methodology-fixed values may be scored — the retired
            // original-model values are history, never graded.
            String meth = e.optString("methodology", "");
            if (!meth.startsWith("methodology-fixed"))
                problems.add(id + ": methodology is not methodology-fixed: '" + meth + "'");
            if (e.optString("price_at_call_source", "").isBlank())
                problems.add(id + ": price_at_call has no source (provenance required)");
            JSONArray checks = e.optJSONArray("checks");
            if (checks == null) {
                problems.add(id + ": missing 'checks' array");
                continue;
            }
            java.util.Set<Integer> seenWindows = new java.util.HashSet<>();
            for (int j = 0; j < checks.length(); j++) {
                JSONObject ch = checks.optJSONObject(j);
                if (ch == null) {
                    problems.add(id + ": check #" + j + " is not an object");
                    continue;
                }
                int w = ch.optInt("window_days", -1);
                if (w != 30 && w != 60 && w != 90)
                    problems.add(id + ": check has bad window_days=" + w);
                if (!seenWindows.add(w))
                    problems.add(id + ": duplicate check for window " + w + "d");
                if (ch.optString("price_source", "").isBlank())
                    problems.add(id + ": window " + w + "d check has no price_source (provenance required)");
                for (String f : new String[]{"check_date", "anniversary_date", "actual_price",
                        "gap_at_call", "gap_at_check", "pct_vs_dcf", "hit_or_miss"})
                    if (!ch.has(f) || ch.isNull(f))
                        problems.add(id + ": window " + w + "d check missing '" + f + "'");
                String v = ch.optString("hit_or_miss", "");
                if (!v.equals("hit") && !v.equals("miss"))
                    problems.add(id + ": window " + w + "d check has bad hit_or_miss='" + v + "'");
                // Recompute the convergence verdict from the recorded numbers
                // — the stored verdict must match the math, or the scorecard
                // is lying.
                if (e.has("dcf_value") && !e.isNull("dcf_value")
                        && e.has("price_at_call") && !e.isNull("price_at_call")
                        && ch.has("actual_price") && !ch.isNull("actual_price")) {
                    double dcf = e.getDouble("dcf_value");
                    double gapCall = Math.abs(e.getDouble("price_at_call") - dcf);
                    double gapCheck = Math.abs(ch.getDouble("actual_price") - dcf);
                    String expected = gapCheck < gapCall ? "hit" : "miss";
                    if (!expected.equals(v))
                        problems.add(id + ": window " + w + "d verdict is '" + v
                                + "' but recomputed convergence says '" + expected + "'");
                }
            }
            if ("resolved".equals(e.optString("status", "")) && !allWindowsChecked(e))
                problems.add(id + ": status is 'resolved' but a window check is missing");
        }
        // Scorecard row counts must agree with the tracker.
        try {
            String md = Files.readString(scorecardFile(), StandardCharsets.UTF_8);
            int openRows = countDataRows(md, "## Open calls awaiting checks");
            int resolvedRows = countDataRows(md, "## Resolved calls");
            int openN = countByStatus(entries, "open");
            int resolvedN = countByStatus(entries, "resolved");
            if (openRows != openN)
                problems.add("scorecard lists " + openRows + " open calls but tracker has " + openN);
            if (resolvedRows != resolvedN)
                problems.add("scorecard lists " + resolvedRows + " resolved calls but tracker has " + resolvedN);
        } catch (Exception ex) {
            problems.add("scorecard file unreadable: " + ex.getMessage());
        }
        if (!problems.isEmpty()) {
            System.err.println("VERIFY FAILED:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(1);
        }
        System.out.println("VERIFY OK: tracker and scorecard are consistent.");
    }

    // Count markdown table data rows in the section starting at `heading`:
    // lines starting with '|' that are neither the header row nor the
    // '|---|---|' separator. A "_None ..._" placeholder counts as zero.
    static int countDataRows(String md, String heading) {
        int n = 0;
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
