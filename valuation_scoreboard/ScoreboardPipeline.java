// ScoreboardPipeline.java
//
// One-command pipeline for the monthly `valuation-scoreboard-check` cron
// (idea #43), with a built-in health-check verifier (idea #44).
//
// --run: scan the published value backends for new/changed values ->
//        dedupe-append them to ~/workspace/valuation_scoreboard/scoreboard.json ->
//        run 6/12-month anniversary checks -> rewrite scoreboard.md ->
//        Atlas sync (valuation dashboard + vault zip + upload) ->
//        print a JSON summary on stdout for the delivery step.
//
// Value sources scanned (nothing is invented — unsourced = skipped):
//   dcf_csv/our_dcf.json          (Muse DCF, fixed methodology) -> source "ours"
//   dcf_csv/notebooklm_values.json (verified NotebookLM DCF; nulls skipped) -> "notebooklm"
//   dcf_csv/analyst_targets.json  (consensus price targets) -> "analyst"
//   dcf_csv/creator_values.json   (latest creator call per ticker, valuation
//                                  types only) -> "creators"
//   valuation_scoreboard/chat_values.json (STAGING file: the cron body has the
//     agent check the Stock Research side chat comparison table and
//     research_notes/notebooklm_pending/*.md — things only a reader can judge —
//     and stage genuinely new values here; the pipeline consumes them with
//     provenance and clears the file)
//
// Note on the derived-file guard (idea #40): the dcf_csv backends carry
// "derived from workbook_data.json via RebuildAll — do not edit, do not read
// as input". This pipeline reads them only as the PUBLICATION RECORD of
// values (what the scoreboard logs), never as model inputs for a rebuild,
// which is what the guard is about.
//
// Usage:
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/valuation_scoreboard/ScoreboardPipeline.java --run \
//        [--dry-run] [--root DIR] [--today YYYY-MM-DD] [--no-atlas-sync]
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/valuation_scoreboard/ScoreboardPipeline.java --verify [--root DIR]
//
// --dry-run:      compute everything, write nothing.
// --root:         test hook — replaces $HOME as the filesystem root.
// --today:        test hook — reference date (default: today in America/New_York).
// --no-atlas-sync: skip the dashboard/zip/upload step (tests).
// --verify:       read-only health check. Fails loud when:
//                 * an entry lacks source_ref (cross-check rule) or has a
//                   duplicate id / duplicate (ticker,source,type,value),
//                 * a check lacks price_source (cross-check rule) or a required
//                   field, has a bad/duplicate window, or its check_date is
//                   not within ±2 weeks of its anniversary,
//                 * scoreboard.md's trust table or logged-values row count
//                   disagrees with scoreboard.json.

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class ScoreboardPipeline {

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String PRICE_SOURCE = "HistoricalPrice.java (yahoo-chart)";

    // Anniversary windows: ~6 and ~12 months, each ±2 weeks.
    static final String[] WINDOW_NAMES = {"6mo", "12mo"};
    static final int[] WINDOW_MONTHS = {6, 12};
    static final int WINDOW_SLACK_DAYS = 14;

    // Creator value types that are valuation calls (same set as
    // BuildCreatorValues.CHART_TYPES). Personal buy prices are not scored.
    static final java.util.Set<String> VALUATION_TYPES =
            java.util.Set.of("intrinsic_value", "fair_value", "price_target");

    // Trust-table row order and labels (labels match the existing scoreboard.md).
    static final String[][] SOURCES = {
        {"ours", "ours (Muse DCF, fixed methodology)"},
        {"ours_retired", "ours_retired (original model, retired 2026-09-27)"},
        {"notebooklm", "notebooklm"},
        {"creators", "creators"},
        {"analyst", "analyst"},
    };

    static Path workspaceRoot = Path.of(homeDir(), "workspace");
    static LocalDate today = LocalDate.now(ET);
    static boolean dryRun = false;
    static boolean noAtlasSync = false;

    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run": mode = "run"; break;
                case "--verify": mode = "verify"; break;
                case "--dry-run": dryRun = true; break;
                case "--no-atlas-sync": noAtlasSync = true; break;
                case "--root": workspaceRoot = Path.of(args[++i], "workspace"); break;
                case "--today": today = LocalDate.parse(args[++i], DAY); break;
                default: break;
            }
        }
        if (mode == null) {
            System.out.println("Usage: ScoreboardPipeline.java --run [--dry-run] [--root DIR] [--today YYYY-MM-DD] [--no-atlas-sync]");
            System.out.println("       ScoreboardPipeline.java --verify [--root DIR]");
            System.exit(2);
        }
        if (mode.equals("run")) run();
        else verify();
    }

    // ------------------------------------------------------------------ run

    static void run() throws Exception {
        Path sbDir = workspaceRoot.resolve("valuation_scoreboard");
        Path sbFile = sbDir.resolve("scoreboard.json");
        Path mdFile = sbDir.resolve("scoreboard.md");
        Path stagingFile = sbDir.resolve("chat_values.json");

        JSONObject sb = new JSONObject(Files.readString(sbFile, StandardCharsets.UTF_8));
        JSONArray entries = sb.getJSONArray("entries");
        Map<String, String> companies = buildCompanyMap(entries);

        // Dedupe key: an identical (ticker, source, type, value) is never logged twice.
        java.util.Set<String> seen = new TreeSet<>();
        for (int i = 0; i < entries.length(); i++) seen.add(dedupeKey(entries.getJSONObject(i)));

        JSONArray newEntries = new JSONArray();
        // Step 1: scan the file-based value sources.
        for (JSONObject cand : scanBackends(companies)) {
            if (seen.add(dedupeKey(cand))) {
                entries.put(finalizeEntry(cand, entries));
                newEntries.put(entries.getJSONObject(entries.length() - 1));
            }
        }
        // Step 2: consume the agent-staged chat/notebook values (if any).
        int staged = 0;
        JSONArray staging = readStaging(stagingFile);
        for (int i = 0; i < staging.length(); i++) {
            JSONObject cand = staging.getJSONObject(i);
            // Cross-check rule: a staged value without provenance is refused.
            if (cand.optString("source_ref", "").isBlank()) {
                System.err.println("REFUSING staged value for " + cand.optString("ticker", "?")
                        + ": no source_ref (provenance required)");
                System.exit(1);
            }
            if (cand.optString("ticker", "").isBlank() || !cand.has("value")) {
                System.err.println("REFUSING staged entry: missing ticker or value: " + cand);
                System.exit(1);
            }
            cand.put("company", cand.optString("company",
                    companies.getOrDefault(cand.getString("ticker"), cand.getString("ticker"))));
            cand.put("date", cand.optString("date", today.format(DAY)));
            if (seen.add(dedupeKey(cand))) {
                entries.put(finalizeEntry(cand, entries));
                newEntries.put(entries.getJSONObject(entries.length() - 1));
                staged++;
            }
        }

        // Step 3: anniversary checks for entries ~6/~12 months old (±2 weeks)
        // with no recorded check for that window.
        JSONArray newChecks = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            LocalDate entryDate = LocalDate.parse(e.getString("date"), DAY);
            for (int w = 0; w < WINDOW_NAMES.length; w++) {
                if (hasCheck(e, WINDOW_NAMES[w])) continue;
                LocalDate anniversary = entryDate.plusMonths(WINDOW_MONTHS[w]);
                long off = Math.abs(ChronoUnit.DAYS.between(anniversary, today));
                if (off > WINDOW_SLACK_DAYS) continue;
                String verdict = runAnniversaryCheck(e, WINDOW_NAMES[w], anniversary);
                if (verdict != null) {
                    JSONObject rec = new JSONObject();
                    rec.put("ticker", e.getString("ticker"));
                    rec.put("source", e.getString("source"));
                    rec.put("window", WINDOW_NAMES[w]);
                    rec.put("verdict", verdict);
                    newChecks.put(rec);
                }
            }
        }

        boolean changed = newEntries.length() > 0 || newChecks.length() > 0;
        if (!dryRun) {
            sb.put("updated", today.format(DAY));
            Files.writeString(sbFile, sb.toString(2) + "\n", StandardCharsets.UTF_8);
            writeScoreboardMd(mdFile, entries);
            if (staging.length() > 0) Files.writeString(stagingFile, "[]\n", StandardCharsets.UTF_8);
        }

        // Step 4: Atlas sync — only when something actually changed.
        JSONObject syncOut = new JSONObject();
        if (changed && !dryRun && !noAtlasSync) {
            syncOut = atlasSync();
        } else {
            syncOut.put("ran", false);
            syncOut.put("reason", !changed ? "no new values or anniversary checks"
                    : dryRun ? "dry-run" : "--no-atlas-sync");
        }

        JSONObject summary = new JSONObject();
        summary.put("run_date", today.format(DAY));
        summary.put("dry_run", dryRun);
        summary.put("new_entries", summarizeEntries(newEntries));
        summary.put("anniversary_checks", newChecks);
        summary.put("staging_consumed", staged);
        summary.put("trust_ranking", trustRanking(entries));
        summary.put("atlas_sync", syncOut);
        System.out.println(summary.toString(2));
    }

    static String dedupeKey(JSONObject e) {
        return e.getString("ticker") + "|" + e.getString("source") + "|"
                + e.getString("value_type") + "|" + e.getDouble("value");
    }

    // Assign the next per-ticker id (vs-<ticker>-<NNN>) and the checks array.
    static JSONObject finalizeEntry(JSONObject cand, JSONArray entries) {
        String t = cand.getString("ticker").toLowerCase(Locale.US).replace(".", "");
        int max = 0;
        for (int i = 0; i < entries.length(); i++) {
            String id = entries.getJSONObject(i).optString("id", "");
            if (id.startsWith("vs-" + t + "-")) {
                try { max = Math.max(max, Integer.parseInt(id.substring(id.lastIndexOf('-') + 1))); }
                catch (NumberFormatException ignored) {}
            }
        }
        cand.put("id", String.format("vs-%s-%03d", t, max + 1));
        if (!cand.has("checks")) cand.put("checks", new JSONArray());
        return cand;
    }

    // Scan the four JSON backends; every candidate carries a source_ref.
    static List<JSONObject> scanBackends(Map<String, String> companies) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        Path csv = workspaceRoot.resolve("dcf_csv");

        JSONObject ours = readJson(csv.resolve("our_dcf.json"));
        String oursAsof = ours.optString("asof", today.format(DAY));
        JSONObject oursVals = ours.getJSONObject("values");
        for (String t : oursVals.keySet()) {
            JSONObject v = oursVals.getJSONObject(t);
            if (v.isNull("value")) continue;
            out.add(candidate(t, companies, "ours", "intrinsic_value", v.getDouble("value"),
                    "our_dcf.json (Muse DCF, fixed methodology, asof " + oursAsof + ")"));
        }

        JSONObject nblm = readJson(csv.resolve("notebooklm_values.json"));
        String nblmAsof = nblm.optString("asof", today.format(DAY));
        JSONObject nblmVals = nblm.getJSONObject("values");
        for (String t : nblmVals.keySet()) {
            JSONObject v = nblmVals.getJSONObject(t);
            if (v.isNull("value")) continue; // unverified values stay out
            out.add(candidate(t, companies, "notebooklm", "intrinsic_value", v.getDouble("value"),
                    "notebooklm_values.json (verified NotebookLM DCF, asof " + nblmAsof + ")"));
        }

        JSONObject analyst = readJson(csv.resolve("analyst_targets.json"));
        for (String t : analyst.keySet()) {
            JSONObject v = analyst.getJSONObject(t);
            if (v.isNull("target")) continue;
            out.add(candidate(t, companies, "analyst", "price_target", v.getDouble("target"),
                    "analyst_targets.json — " + v.optString("source", "consensus")
                    + " (asof " + v.optString("asof", today.format(DAY)) + ")"));
        }

        JSONObject creators = readJson(csv.resolve("creator_values.json"));
        JSONObject latest = creators.optJSONObject("latest");
        if (latest != null) {
            for (String t : latest.keySet()) {
                JSONObject v = latest.getJSONObject(t);
                String type = v.optString("type", "");
                if (!VALUATION_TYPES.contains(type) || v.isNull("value")) continue;
                out.add(candidate(t, companies, "creators", type, v.getDouble("value"),
                        "creator_values.json — " + v.optString("creator", "creator")
                        + ": " + type + " " + v.getDouble("value")
                        + " (" + v.optString("call_date", "date not recorded") + ")"));
            }
        }
        return out;
    }

    static JSONObject candidate(String ticker, Map<String, String> companies,
                                String source, String valueType, double value, String sourceRef) {
        JSONObject o = new JSONObject();
        o.put("date", today.format(DAY));
        o.put("ticker", ticker);
        o.put("company", companies.getOrDefault(ticker, ticker));
        o.put("source", source);
        o.put("value_type", valueType);
        o.put("value", value);
        o.put("source_ref", sourceRef);
        return o;
    }

    static JSONObject readJson(Path f) throws Exception {
        return new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
    }

    static JSONArray readStaging(Path f) throws Exception {
        if (!Files.exists(f)) return new JSONArray();
        String text = Files.readString(f, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new JSONArray();
        return new JSONArray(text);
    }

    // Ticker -> company name, from the scoreboard's own history first, then
    // the comparison CSV (Company,Ticker columns), then the creator trackers.
    // Falls back to the ticker itself — never an invented name.
    static Map<String, String> buildCompanyMap(JSONArray entries) throws Exception {
        Map<String, String> m = new TreeMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            m.putIfAbsent(e.getString("ticker"), e.optString("company", e.getString("ticker")));
        }
        Path csvFile = workspaceRoot.resolve("dcf_csv").resolve("dcf-comparison.csv");
        if (Files.exists(csvFile)) {
            for (String line : Files.readAllLines(csvFile, StandardCharsets.UTF_8)) {
                // Company names may contain commas inside quotes; the ticker
                // column never does, so split on '","' boundaries loosely:
                // find the ,"TICKER", pattern instead.
                Matcher tm = Pattern.compile(",\"([A-Z][A-Z0-9.\\-]*)\",").matcher(line);
                if (tm.find()) {
                    String ticker = tm.group(1);
                    String company = line.substring(0, tm.start()).replaceAll("^\"|\"$", "").trim();
                    if (!company.isEmpty() && !"Company".equals(company))
                        m.putIfAbsent(ticker, company);
                }
            }
        }
        Path trackerDir = workspaceRoot.resolve("creator_tracker");
        if (Files.isDirectory(trackerDir)) {
            try (var stream = Files.newDirectoryStream(trackerDir, "*_targets.json")) {
                for (Path p : stream) {
                    try {
                        String text = Files.readString(p, StandardCharsets.UTF_8).trim();
                        JSONArray es = text.startsWith("[") ? new JSONArray(text)
                                : new JSONObject(text).getJSONArray("entries");
                        for (int i = 0; i < es.length(); i++) {
                            JSONObject e = es.getJSONObject(i);
                            String t = e.optString("ticker", "");
                            if (!t.isEmpty()) m.putIfAbsent(t, e.optString("company", t));
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return m;
    }

    static boolean hasCheck(JSONObject entry, String window) {
        JSONArray checks = entry.optJSONArray("checks");
        if (checks == null) return false;
        for (int i = 0; i < checks.length(); i++) {
            if (window.equals(checks.getJSONObject(i).optString("window"))) return true;
        }
        return false;
    }

    // Look up the anniversary price and append the check record.
    // Returns the verdict, or null when no price was retrievable (the window
    // stays due for the next run — nothing is recorded without a price).
    static String runAnniversaryCheck(JSONObject entry, String windowName, LocalDate anniversary)
            throws Exception {
        String ticker = entry.getString("ticker");
        Map<String, Double> prices = lookupPrice(ticker, anniversary.format(DAY));
        Double actual = prices.get(anniversary.format(DAY));
        if (actual == null) {
            System.err.println("WARNING: no price for " + ticker + " on/after "
                    + anniversary.format(DAY) + " — window stays due");
            return null;
        }
        double value = entry.getDouble("value");
        String type = entry.getString("value_type");
        String verdict;
        if ("price_target".equals(type)) {
            double dev = Math.abs(actual - value) / value;
            verdict = dev <= 0.10 ? "hit" : dev <= 0.12 ? "near-miss" : "miss";
        } else { // intrinsic_value / fair_value
            verdict = actual >= value ? "hit" : actual >= value * 0.98 ? "near-miss" : "miss";
        }
        JSONObject check = new JSONObject();
        check.put("window", windowName);
        check.put("check_date", today.format(DAY));
        check.put("anniversary_date", anniversary.format(DAY));
        check.put("actual_price", Math.round(actual * 100.0) / 100.0);
        check.put("verdict", verdict);
        // Provenance (idea #44 cross-check rule).
        check.put("price_source", PRICE_SOURCE);
        entry.getJSONArray("checks").put(check);
        return verdict;
    }

    static Map<String, Double> lookupPrice(String ticker, String date) throws Exception {
        String javaBin = javaBinary();
        Path helper = workspaceRoot.resolve("stock_watch").resolve("HistoricalPrice.java");
        Process p = new ProcessBuilder(javaBin, helper.toString(), ticker, date)
                .redirectErrorStream(true).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            System.err.println("WARNING: HistoricalPrice.java failed for " + ticker + ": " + stdout);
            return Map.of();
        }
        JSONObject perDate = new JSONObject(stdout).getJSONObject(ticker.toUpperCase());
        JSONObject rec = perDate.optJSONObject(date);
        Map<String, Double> out = new LinkedHashMap<>();
        if (rec != null && !rec.isNull("close")) out.put(date, rec.getDouble("close"));
        return out;
    }

    static String javaBinary() {
        // Canonical JDK for this workspace (see ~/TOOLS.md).
        Path inWorkspace = Path.of(homeDir(), "workspace", "java", "jdk-21", "bin", "java");
        if (Files.isExecutable(inWorkspace)) return inWorkspace.toString();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isEmpty()) return jh + "/bin/java";
        return "java";
    }

    // Regenerate the Atlas valuation dashboard, re-zip the vault, upload it.
    // Returns details for the delivery step (URL + expiry for the chat message).
    static JSONObject atlasSync() throws Exception {
        JSONObject out = new JSONObject();
        out.put("ran", true);
        String javaBin = javaBinary();
        Path atlas = workspaceRoot.resolve("atlas");

        ProcessBuilder pb = new ProcessBuilder(javaBin, workspaceRoot.resolve("atlas-scripts")
                .resolve("GenerateValuationDashboard.java").toString());
        pb.directory(workspaceRoot.toFile());
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String dashOut = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        out.put("dashboard_ok", p.waitFor() == 0);
        if (p.waitFor() != 0) System.err.println("Dashboard regen output: " + dashOut);

        Process zp = new ProcessBuilder("zip", "-r", "atlas-vault.zip", "atlas")
                .directory(workspaceRoot.toFile()).redirectErrorStream(true).start();
        String zipOut = new String(zp.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        out.put("zip_ok", zp.waitFor() == 0);
        if (zp.waitFor() != 0) { System.err.println("zip output: " + zipOut); }

        Process up = new ProcessBuilder("/opt/hatch/bin/remote-storage", "upload-file",
                "--path", workspaceRoot.resolve("atlas-vault.zip").toString())
                .redirectErrorStream(true).start();
        String upOut = new String(up.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int upRc = up.waitFor();
        out.put("upload_ok", upRc == 0);
        Matcher um = Pattern.compile("https?://\\S+").matcher(upOut);
        out.put("upload_url", um.find() ? um.group() : JSONObject.NULL);
        Matcher em = Pattern.compile("(?i)expires_at[\"'\\s:=]+([^\\s\"']+)").matcher(upOut);
        out.put("upload_expires_at", em.find() ? em.group(1) : JSONObject.NULL);
        if (upRc != 0) System.err.println("upload output: " + upOut);
        return out;
    }

    static JSONArray summarizeEntries(JSONArray newEntries) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < newEntries.length(); i++) {
            JSONObject e = newEntries.getJSONObject(i);
            JSONObject s = new JSONObject();
            s.put("id", e.getString("id"));
            s.put("ticker", e.getString("ticker"));
            s.put("source", e.getString("source"));
            s.put("value_type", e.getString("value_type"));
            s.put("value", e.getDouble("value"));
            a.put(s);
        }
        return a;
    }

    static JSONObject trustRanking(JSONArray entries) {
        JSONObject o = new JSONObject();
        for (String[] s : SOURCES) {
            int logged = 0, res6 = 0, hit6 = 0, res12 = 0, hit12 = 0;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                if (!s[0].equals(e.getString("source"))) continue;
                logged++;
                JSONArray checks = e.optJSONArray("checks");
                if (checks == null) continue;
                for (int j = 0; j < checks.length(); j++) {
                    JSONObject c = checks.getJSONObject(j);
                    boolean hit = "hit".equals(c.optString("verdict"));
                    if ("6mo".equals(c.optString("window"))) { res6++; if (hit) hit6++; }
                    if ("12mo".equals(c.optString("window"))) { res12++; if (hit) hit12++; }
                }
            }
            JSONObject r = new JSONObject();
            r.put("logged", logged);
            r.put("resolved_6mo", res6); r.put("hits_6mo", hit6);
            r.put("resolved_12mo", res12); r.put("hits_12mo", hit12);
            o.put(s[0], r);
        }
        return o;
    }

    // ---------------------------------------------------------------- scoreboard.md

    // Rebuild scoreboard.md from scoreboard.json. The title + intro above
    // "## Trust ranking" are preserved; the tables below are regenerated so
    // the human-readable file can never drift from the JSON.
    static void writeScoreboardMd(Path mdFile, JSONArray entries) throws Exception {
        String existing = Files.exists(mdFile)
                ? Files.readString(mdFile, StandardCharsets.UTF_8) : "# Valuation Scoreboard\n";
        String header = existing;
        int h = existing.indexOf("\n## Trust ranking");
        if (h >= 0) header = existing.substring(0, h);
        header = header.stripTrailing() + "\n";

        StringBuilder sb = new StringBuilder(header);
        sb.append("\n## Trust ranking by source (updated by monthly check)\n\n");
        sb.append("| Source | Calls logged | 6-mo resolved | 6-mo hit rate | 12-mo resolved | 12-mo hit rate |\n");
        sb.append("|---|---|---|---|---|---|\n");
        JSONObject ranking = trustRanking(entries);
        for (String[] s : SOURCES) {
            JSONObject r = ranking.getJSONObject(s[0]);
            sb.append("| ").append(s[1])
              .append(" | ").append(r.getInt("logged"))
              .append(" | ").append(r.getInt("resolved_6mo"))
              .append(" | ").append(hitRateCell(r.getInt("hits_6mo"), r.getInt("resolved_6mo")))
              .append(" | ").append(r.getInt("resolved_12mo"))
              .append(" | ").append(hitRateCell(r.getInt("hits_12mo"), r.getInt("resolved_12mo")))
              .append(" |\n");
        }
        sb.append("\nHit = actual price at/above a stated intrinsic value (or within ±10% of a price target) at the 6/12-month mark. First resolutions due ~March 2027.\n");
        sb.append("\n## Logged values\n\n");
        sb.append("| Date | Ticker | Source | Type | Value | 6-mo check | 12-mo check |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            sb.append("| ").append(e.getString("date"))
              .append(" | ").append(e.getString("ticker"))
              .append(" | ").append(e.getString("source"))
              .append(" | ").append(e.getString("value_type"))
              .append(" | ").append(money(e.getDouble("value")))
              .append(" | ").append(checkCell(e, "6mo"))
              .append(" | ").append(checkCell(e, "12mo"))
              .append(" |\n");
        }
        sb.append("\nSources: `dcf_csv/our_dcf.json`, `dcf_csv/notebooklm_values.json`, "
                + "`dcf_csv/analyst_targets.json`, `dcf_csv/creator_values.json` "
                + "(publication record of values, via RebuildAll); the Stock Research side chat "
                + "comparison table and `research_notes/notebooklm_pending/*.md` via "
                + "`chat_values.json` staging. Input changes that move these values are audited "
                + "in `assumption_changelog.md`.\n");
        Files.writeString(mdFile, sb.toString(), StandardCharsets.UTF_8);
    }

    static String hitRateCell(int hits, int resolved) {
        if (resolved == 0) return "—";
        return String.format(Locale.US, "%.1f%% (%d/%d)", hits * 100.0 / resolved, hits, resolved);
    }

    static String checkCell(JSONObject e, String window) {
        JSONArray checks = e.optJSONArray("checks");
        if (checks != null) {
            for (int j = 0; j < checks.length(); j++) {
                JSONObject c = checks.getJSONObject(j);
                if (window.equals(c.optString("window"))) {
                    String mark = switch (c.optString("verdict")) {
                        case "hit" -> "✅"; case "miss" -> "❌"; default -> "➖";
                    };
                    return mark + " " + money(c.getDouble("actual_price"))
                            + " (" + c.getString("verdict") + ")";
                }
            }
        }
        return "—";
    }

    static String money(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v))
            return "$" + String.format(Locale.US, "%,d", (long) v);
        return "$" + String.format(Locale.US, "%,.2f", v);
    }

    // ---------------------------------------------------------------- verify

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        Path sbDir = workspaceRoot.resolve("valuation_scoreboard");
        Path sbFile = sbDir.resolve("scoreboard.json");
        Path mdFile = sbDir.resolve("scoreboard.md");

        JSONObject sb;
        try {
            sb = new JSONObject(Files.readString(sbFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail("scoreboard.json is not valid JSON: " + e.getMessage());
            return;
        }
        JSONArray entries;
        try {
            entries = sb.getJSONArray("entries");
        } catch (Exception e) {
            fail("scoreboard.json has no 'entries' array");
            return;
        }

        java.util.Set<String> ids = new TreeSet<>();
        java.util.Set<String> keys = new TreeSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) { problems.add("entry #" + i + " is not an object"); continue; }
            String id = e.optString("id", "entry #" + i);
            if (!ids.add(e.optString("id", "")))
                problems.add(id + ": duplicate id '" + e.optString("id") + "'");
            // Cross-check rule: every entry must carry provenance.
            if (e.optString("source_ref", "").isBlank())
                problems.add(id + ": missing source_ref (provenance required)");
            for (String f : new String[]{"date", "ticker", "company", "source", "value_type", "value"})
                if (!e.has(f) || e.isNull(f)) problems.add(id + ": missing '" + f + "'");
            String key;
            try { key = dedupeKey(e); }
            catch (Exception ex) { problems.add(id + ": cannot build dedupe key"); continue; }
            if (!keys.add(key)) problems.add(id + ": duplicate (ticker,source,type,value)");
            try { LocalDate.parse(e.getString("date"), DAY); }
            catch (Exception ex) { problems.add(id + ": bad date '" + e.optString("date") + "'"); }

            JSONArray checks = e.optJSONArray("checks");
            if (checks == null) { problems.add(id + ": missing 'checks' array"); continue; }
            java.util.Set<String> seenW = new TreeSet<>();
            for (int j = 0; j < checks.length(); j++) {
                JSONObject c = checks.optJSONObject(j);
                if (c == null) { problems.add(id + ": check #" + j + " is not an object"); continue; }
                String w = c.optString("window", "");
                if (!"6mo".equals(w) && !"12mo".equals(w))
                    problems.add(id + ": check has bad window '" + w + "'");
                if (!seenW.add(w)) problems.add(id + ": duplicate " + w + " check");
                // Cross-check rule: every check names its price source.
                if (c.optString("price_source", "").isBlank())
                    problems.add(id + ": " + w + " check has no price_source (provenance required)");
                for (String f : new String[]{"check_date", "anniversary_date", "actual_price", "verdict"})
                    if (!c.has(f) || c.isNull(f)) problems.add(id + ": " + w + " check missing '" + f + "'");
                // The ±2 weeks anniversary rule: the check must have run
                // within 14 days of the anniversary date.
                try {
                    LocalDate cd = LocalDate.parse(c.getString("check_date"), DAY);
                    LocalDate ad = LocalDate.parse(c.getString("anniversary_date"), DAY);
                    if (Math.abs(ChronoUnit.DAYS.between(ad, cd)) > WINDOW_SLACK_DAYS)
                        problems.add(id + ": " + w + " check_date " + c.getString("check_date")
                                + " is more than 2 weeks from anniversary "
                                + c.getString("anniversary_date"));
                    LocalDate ed = LocalDate.parse(e.getString("date"), DAY);
                    int months = "6mo".equals(w) ? 6 : 12;
                    LocalDate expect = ed.plusMonths(months);
                    if (Math.abs(ChronoUnit.DAYS.between(expect, ad)) > WINDOW_SLACK_DAYS)
                        problems.add(id + ": " + w + " anniversary " + c.getString("anniversary_date")
                                + " is not ~" + months + " months after entry date " + e.getString("date"));
                } catch (Exception ex) {
                    problems.add(id + ": " + w + " check has unparseable dates");
                }
                String v = c.optString("verdict", "");
                if (!"hit".equals(v) && !"miss".equals(v) && !"near-miss".equals(v))
                    problems.add(id + ": " + w + " check has bad verdict '" + v + "'");
            }
        }

        // scoreboard.md must agree with scoreboard.json.
        try {
            String md = Files.readString(mdFile, StandardCharsets.UTF_8);
            verifyMdTrustTable(md, entries, problems);
            int rows = countLoggedValueRows(md);
            if (rows != entries.length())
                problems.add("scoreboard.md lists " + rows + " logged values but scoreboard.json has "
                        + entries.length());
        } catch (Exception e) {
            problems.add("scoreboard.md unreadable: " + e.getMessage());
        }

        if (!problems.isEmpty()) {
            System.err.println("VERIFY FAILED:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(1);
        }
        System.out.println("VERIFY OK: " + entries.length()
                + " entries, all with provenance; scoreboard.md is consistent.");
    }

    static void verifyMdTrustTable(String md, JSONArray entries, List<String> problems) {
        JSONObject ranking = trustRanking(entries);
        for (String[] s : SOURCES) {
            JSONObject r = ranking.getJSONObject(s[0]);
            String want = "| " + s[1] + " | " + r.getInt("logged") + " | " + r.getInt("resolved_6mo")
                    + " | " + hitRateCell(r.getInt("hits_6mo"), r.getInt("resolved_6mo"))
                    + " | " + r.getInt("resolved_12mo")
                    + " | " + hitRateCell(r.getInt("hits_12mo"), r.getInt("resolved_12mo")) + " |";
            boolean found = false;
            for (String line : md.split("\n")) {
                if (line.trim().equals(want)) { found = true; break; }
            }
            if (!found) problems.add("scoreboard.md trust-table row for '" + s[0]
                    + "' does not match scoreboard.json (want: " + want + ")");
        }
    }

    static int countLoggedValueRows(String md) {
        int n = 0;
        boolean inSection = false, headerSeen = false;
        for (String line : md.split("\n")) {
            if (line.startsWith("## ")) {
                if (inSection) break;
                if (line.trim().equals("## Logged values")) { inSection = true; headerSeen = false; }
                continue;
            }
            if (!inSection) continue;
            String t = line.trim();
            if (!t.startsWith("|") || t.contains("---")) continue;
            if (!headerSeen) { headerSeen = true; continue; }
            n++;
        }
        return n;
    }

    static void fail(String msg) {
        System.err.println("VERIFY FAILED:\n  - " + msg);
        System.exit(1);
    }
}
