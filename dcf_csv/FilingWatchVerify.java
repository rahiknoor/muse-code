import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FilingWatchVerify.java — idea #44 health-check verifier for the
 * {@code sec-filing-watch-dcf-updates} cron (the weekday 07:46 ET filing watch).
 *
 * <p>Runs as the FINAL step of the job body. It re-checks the persisted state
 * the run was supposed to leave behind — it never changes any reported value,
 * never writes state, and never sends messages. Exit 0 means the run left a
 * healthy state; exit 1 names every problem found, loudly.
 *
 * <p>What --verify checks:
 * <ol>
 *   <li><b>Watch state</b> — {@code sec_filings/filing_watch_state.json} parses
 *       as JSON, lists every company with a 10-digit CIK, dir, ticker, name, a
 *       non-empty {@code last_seen} filing history, and the NotebookLM URL.</li>
 *   <li><b>Rate state</b> — {@code sec_filings/filing_rate_state.json} parses;
 *       every entry present has a valid {@code last_checked} date no older
 *       than 14 days (the adaptive-polling policy's "no company goes more
 *       than ~2 weeks unchecked" invariant).</li>
 *   <li><b>Today's input changes</b> — for every assumption_changelog.md table
 *       row dated today that records a revision: the ticker's assumption record
 *       in {@code workbook_data.json} was updated (numeric base present and
 *       the src note cites the filing form + date, or today's date), AND there
 *       is a VerifyBase PASS record for it in {@code dcf_csv/verifybase_gate.log}
 *       or a logged HOLD section in the changelog (Rahik's cross-check rule:
 *       no base ships without the gate).</li>
 *   <li><b>Gate bypass</b> — a HOLD logged for a ticker today plus a revision
 *       row for the same ticker today means the gate was bypassed: fail.</li>
 *   <li><b>Derived-file guard intact</b> — {@code DerivedFiles.java
 *       --check-inputs} still passes on the genuine inputs (idea #40 not
 *       weakened).</li>
 *   <li><b>Stamps</b> — every text derived file still carries its
 *       "do not read as input" stamp.</li>
 *   <li><b>No stale revert</b> — {@code our_dcf.json} values still match the
 *       master's fixed IVs; every non-null NotebookLM value has a verified
 *       batch record; no derived file is newer than the master without an
 *       input change or a rebuild today (the hand-edit signal).</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   java FilingWatchVerify.java --verify   # full check (final step of the cron body)
 *   java FilingWatchVerify.java --shared   # only checks 5-7, reused by DcfAuditVerify
 * </pre>
 *
 * <p>Exit 0 = VERIFY OK. Exit 1 = VERIFY FAILED (problems listed on stdout).
 * Exit 2 = usage error. Honors {@code DCF_WORKSPACE_ROOT} for tests; dates
 * are in America/New_York, matching the changelog.
 *
 * <p>Written in plain Java 17 so Rahik can read it himself.
 */
public class FilingWatchVerify {

    /** Rahik's timezone — the changelog dates and cron schedules are ET. */
    static final ZoneId ET = ZoneId.of("America/New_York");

    static String todayStr() {
        return LocalDate.now(ET).toString();
    }

    /**
     * Home directory. Never {@code System.getProperty("user.home")} here: on
     * this machine it returns /root while $HOME is /home/hatch.
     */
    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /** Workspace root. Overridable with DCF_WORKSPACE_ROOT for tests. */
    static String workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        return (e != null && !e.isEmpty()) ? e : homeDir() + "/workspace";
    }

    /** java binary: $JAVA_HOME first (the cron bodies export it), else the known install. */
    static String javaBin() {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isEmpty()) {
            return jh + "/bin/java";
        }
        return homeDir() + "/workspace/java/jdk-21/bin/java";
    }

    /** Collects failures, notes, and a check count for the final verdict. */
    static class Report {
        final List<String> failures = new ArrayList<>();
        final List<String> infos = new ArrayList<>();
        int checks = 0;

        void check() {
            checks++;
        }

        void fail(String problem) {
            failures.add(problem);
        }

        void info(String note) {
            infos.add(note);
        }

        boolean ok() {
            return failures.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        boolean full = args.length == 1 && args[0].equals("--verify");
        boolean shared = args.length == 1 && args[0].equals("--shared");
        if (!full && !shared) {
            System.out.println("FilingWatchVerify.java — idea #44 verifier for sec-filing-watch-dcf-updates.");
            System.out.println("  java FilingWatchVerify.java --verify   full check (cron final step)");
            System.out.println("  java FilingWatchVerify.java --shared   guard/stamp/consistency checks only");
            System.exit(2);
            return;
        }
        Report r = new Report();
        if (full) {
            fullVerify(r);
        } else {
            sharedVerify(r, null);
        }
        Verify.report(
                "VERIFY OK (FilingWatchVerify): " + r.checks + " checks passed, no problems found.",
                "VERIFY FAILED (FilingWatchVerify): " + r.failures.size() + " problem(s) found:",
                r.infos, r.failures);
    }

    /** Full verification: run-specific checks, then the shared guards. */
    static void fullVerify(Report r) throws Exception {
        String root = workspaceRoot();
        String today = todayStr();
        checkWatchState(r);
        checkRateState(r);
        List<ChangeRow> rows = readChangelogRows(r, root);
        List<ChangeRow> todaysRevisions = new ArrayList<>();
        for (ChangeRow row : rows) {
            if (row.date.equals(today) && row.isRevision()) {
                todaysRevisions.add(row);
            }
        }
        if (todaysRevisions.isEmpty()) {
            r.info("no DCF input changes recorded today (" + today
                    + ") — revision checks skipped.");
        } else {
            checkRevisions(r, todaysRevisions, today);
        }
        checkGateBypass(r, rows, today);
        sharedVerify(r, todaysRevisions);
    }

    /**
     * Shared guards (also used by DcfAuditVerify via --shared): the derived-file
     * input guard, stamp presence, value consistency, and mtime sanity.
     *
     * @param changedToday revision rows dated today, or null when unknown —
     *                     a non-empty list excuses derived files newer than the
     *                     master (they were legitimately regenerated).
     */
    static void sharedVerify(Report r, List<ChangeRow> changedToday) throws Exception {
        checkDerivedGuard(r);
        checkStamps(r);
        checkOurDcfConsistency(r);
        checkNotebookLmProvenance(r);
        checkDerivedMtimes(r, changedToday != null && !changedToday.isEmpty());
    }

    // ------------------------------------------------------------------
    // 1. Watch state: filing_watch_state.json
    // ------------------------------------------------------------------

    /** The filing watch iterates this file — it must be valid and complete. */
    static void checkWatchState(Report r) throws Exception {
        r.check();
        String root = workspaceRoot();
        Path p = Paths.get(root + "/sec_filings/filing_watch_state.json");
        Json.Obj state = (Json.Obj) parseJsonFile(p, r,
                "[state] filing_watch_state.json is not valid JSON");
        if (state == null) {
            return;
        }
        Json.Obj companies = state.getObj("companies");
        if (companies == null || companies.map.isEmpty()) {
            r.fail("[state] filing_watch_state.json has no companies — the watch would check nothing.");
            return;
        }
        String notebookUrl = state.optStr("notebook_url", "");
        if (notebookUrl.isBlank()) {
            r.fail("[state] filing_watch_state.json is missing notebook_url — NotebookLM uploads would have no target.");
        }
        Pattern cik = Pattern.compile("^\\d{10}$");
        Pattern date = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
        int bad = 0;
        for (Map.Entry<String, Json.Val> e : companies.map.entrySet()) {
            String key = e.getKey();
            if (!(e.getValue() instanceof Json.Obj)) {
                r.fail("[state] company '" + key + "' is not a JSON object.");
                bad++;
                continue;
            }
            Json.Obj c = (Json.Obj) e.getValue();
            if (!cik.matcher(c.optStr("cik", "")).matches()) {
                r.fail("[state] company '" + key + "' has a bad CIK ('"
                        + c.optStr("cik", "") + "') — must be 10 digits with leading zeros.");
                bad++;
            }
            for (String f : new String[]{"dir", "ticker", "name"}) {
                if (c.optStr(f, "").isBlank()) {
                    r.fail("[state] company '" + key + "' is missing '" + f + "'.");
                    bad++;
                }
            }
            List<Json.Val> seen = c.getList("last_seen");
            if (seen == null || seen.isEmpty()) {
                r.fail("[state] company '" + key + "' has an empty last_seen filing history.");
                bad++;
            } else {
                for (Json.Val v : seen) {
                    if (!(v instanceof Json.Obj)
                            || ((Json.Obj) v).optStr("form", "").isBlank()
                            || !date.matcher(((Json.Obj) v).optStr("filing_date", "")).matches()) {
                        r.fail("[state] company '" + key
                                + "' has a last_seen entry missing form or a valid filing_date.");
                        bad++;
                        break;
                    }
                }
            }
            if (bad > 8) {
                r.fail("[state] ...stopping the per-company listing after 8 problems.");
                break;
            }
        }
        if (bad == 0) {
            r.info("watch state: " + companies.map.size() + " companies, all with valid CIKs and filing history.");
        }
    }

    // ------------------------------------------------------------------
    // 2. Rate state: filing_rate_state.json
    // ------------------------------------------------------------------

    /**
     * The adaptive-polling policy maintains per-ticker last_checked entries.
     * Hard invariant from the policy: no company goes more than ~2 weeks
     * unchecked. An empty file only earns an INFO (the clean 2026-09-27 state
     * starts empty and populates as runs check companies).
     */
    static void checkRateState(Report r) throws Exception {
        r.check();
        String root = workspaceRoot();
        Path p = Paths.get(root + "/sec_filings/filing_rate_state.json");
        Json.Obj state = (Json.Obj) parseJsonFile(p, r,
                "[state] filing_rate_state.json is not valid JSON");
        if (state == null) {
            return;
        }
        if (state.map.isEmpty()) {
            r.info("filing_rate_state.json is empty — no last_checked entries yet; "
                    + "the adaptive-polling policy populates it as companies are checked.");
            return;
        }
        LocalDate today = LocalDate.parse(todayStr());
        Pattern date = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
        for (Map.Entry<String, Json.Val> e : state.map.entrySet()) {
            String ticker = e.getKey();
            if (!(e.getValue() instanceof Json.Obj)) {
                r.fail("[rate] entry '" + ticker + "' is not a JSON object.");
                continue;
            }
            Json.Obj o = (Json.Obj) e.getValue();
            String lc = o.optStr("last_checked", "");
            if (!date.matcher(lc).matches()) {
                r.fail("[rate] '" + ticker + "' has no valid last_checked date ('" + lc + "').");
                continue;
            }
            LocalDate d = LocalDate.parse(lc);
            if (d.isAfter(today)) {
                r.fail("[rate] '" + ticker + "' last_checked is in the future (" + lc + ").");
            } else if (d.isBefore(today.minusDays(14))) {
                r.fail("[rate] '" + ticker + "' last_checked " + lc
                        + " is over 14 days ago — the policy's safety net was missed.");
            }
        }
    }

    // ------------------------------------------------------------------
    // Changelog rows
    // ------------------------------------------------------------------

    /** One markdown table row of assumption_changelog.md. */
    static class ChangeRow {
        String date, ticker, what, iv, confidence, sensitivity, trigger;

        /** A revision actually moved an input (vs "No change — defended"). */
        boolean isRevision() {
            String w = what.toLowerCase();
            return w.contains("revis")
                    || (what.contains("\u2192") && !w.contains("no change"));
        }
    }

    /**
     * Parse the changelog's markdown table. Skips the header, the separator
     * row, and the narrative "##" sections (only "|" lines are rows).
     */
    static List<ChangeRow> readChangelogRows(Report r, String root) throws Exception {
        r.check();
        List<ChangeRow> rows = new ArrayList<>();
        Path p = Paths.get(root + "/valuation_scoreboard/assumption_changelog.md");
        if (!Files.exists(p)) {
            r.fail("[changelog] assumption_changelog.md is missing.");
            return rows;
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (!line.startsWith("|")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 9) {
                continue; // not a full 7-column row
            }
            List<String> cells = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                cells.add(parts[i].trim());
            }
            if (cells.get(0).equalsIgnoreCase("Date")) {
                continue; // header row
            }
            boolean separator = true;
            for (String c : cells) {
                if (!c.matches("^[\\-:]+$")) {
                    separator = false;
                    break;
                }
            }
            if (separator) {
                continue;
            }
            ChangeRow row = new ChangeRow();
            row.date = cells.get(0);
            row.ticker = cells.get(1).toUpperCase();
            row.what = cells.get(2);
            row.iv = cells.get(3);
            row.confidence = cells.get(4);
            row.sensitivity = cells.get(5);
            row.trigger = cells.get(6);
            if (parts.length > 9) {
                // A literal "|" inside the trigger would add columns; rejoin them.
                StringBuilder extra = new StringBuilder(row.trigger);
                for (int i = 8; i < parts.length - 1; i++) {
                    extra.append('|').append(parts[i].trim());
                }
                row.trigger = extra.toString();
            }
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 3. Today's revisions: workbook updated + gate evidence
    // ------------------------------------------------------------------

    /**
     * Rahik's cross-check rule: any DCF base written has a VerifyBase pass
     * record or a logged HOLD, and the workbook assumption record reflects
     * the new inputs in the same run (ideas #27-28: no stale labels).
     */
    static void checkRevisions(Report r, List<ChangeRow> revisions, String today) throws Exception {
        r.check();
        String root = workspaceRoot();
        // The master: companies is a LIST of {ticker, ..., assumptions:{...}}.
        Map<String, Json.Obj> assumptions = new TreeMap<>();
        Path master = Paths.get(root + "/your_files/dcf-valuations/.src/workbook_data.json");
        Json.Obj data = (Json.Obj) parseJsonFile(master, r,
                "[inputs] workbook_data.json is not valid JSON");
        if (data != null) {
            for (Json.Val v : data.getList("companies")) {
                if (v instanceof Json.Obj) {
                    Json.Obj c = (Json.Obj) v;
                    Json.Obj a = c.getObj("assumptions");
                    if (a != null) {
                        assumptions.put(c.optStr("ticker", "").toUpperCase(), a);
                    }
                }
            }
        }
        // VerifyBase pass records, keyed "YYYY-MM-DD|TICKER".
        Map<String, String> passes = readGateLog(root);
        // HOLD sections in the changelog, keyed the same way.
        Map<String, Boolean> holds = readHoldSections(root);
        Pattern filingRef = Pattern.compile("(10-K|10-Q|20-F|6-K)\\b[^|\\d]{0,40}?(\\d{4}-\\d{2}-\\d{2})");
        for (ChangeRow row : revisions) {
            String t = row.ticker;
            Json.Obj a = assumptions.get(t);
            if (a == null || !(a.get("base") instanceof Json.Num)) {
                r.fail("[inputs] " + t + ": changelog records a revision today but "
                        + "workbook_data.json has no assumption record with a numeric base for it "
                        + "— the record was not updated in the same run.");
            } else {
                // The src note must cite the new inputs' provenance: the filing
                // form + date from the changelog trigger, or at least today's date.
                String src = a.optStr("src", "");
                Matcher m = filingRef.matcher(row.trigger);
                boolean cited;
                String need;
                if (m.find()) {
                    String form = m.group(1);
                    String fdate = m.group(2);
                    cited = src.toLowerCase().contains(form.toLowerCase())
                            && src.contains(fdate);
                    need = "filing " + form + " " + fdate + " from the changelog trigger";
                } else {
                    cited = src.contains(today);
                    need = "today's date (" + today + ")";
                }
                if (!cited) {
                    r.fail("[inputs] " + t + ": assumption record exists but its src note "
                            + "does not cite " + need + " — no evidence it was updated this run. "
                            + "src note: '" + abbrev(src, 120) + "'");
                }
            }
            String key = today + "|" + t;
            if (!passes.containsKey(key) && !holds.containsKey(key)) {
                r.fail("[cross-check] " + t + ": revision recorded today but no VerifyBase "
                        + "PASS record in dcf_csv/verifybase_gate.log and no BASE CROSS-CHECK "
                        + "HELD section in the changelog — the idea #32 gate has no trail.");
            }
        }
    }

    /** HOLD logged for a ticker today while a revision row was also written: the gate was bypassed. */
    static void checkGateBypass(Report r, List<ChangeRow> rows, String today) throws Exception {
        r.check();
        Map<String, Boolean> holds = readHoldSections(workspaceRoot());
        for (ChangeRow row : rows) {
            if (row.date.equals(today) && row.isRevision()
                    && holds.containsKey(today + "|" + row.ticker)) {
                r.fail("[gate-bypass] " + row.ticker + ": a BASE CROSS-CHECK HOLD was logged "
                        + "today AND a revision row was written today — a held base may have "
                        + "shipped anyway. Investigate before trusting today's values.");
            }
        }
    }

    /** Parse verifybase_gate.log into "date|TICKER" -> verdict ("PASS HIGH", ...). */
    static Map<String, String> readGateLog(String root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        Path p = Paths.get(root + "/dcf_csv/verifybase_gate.log");
        if (!Files.exists(p)) {
            return out;
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String[] f = line.split("\\|", -1);
            if (f.length < 3) {
                continue;
            }
            String date = f[0].contains("T") ? f[0].substring(0, f[0].indexOf('T')) : f[0];
            out.put(date + "|" + f[1].trim().toUpperCase(), f[2].trim());
        }
        return out;
    }

    /** Find "## YYYY-MM-DD — BASE CROSS-CHECK HELD: TICKER" sections in the changelog. */
    static Map<String, Boolean> readHoldSections(String root) throws Exception {
        Map<String, Boolean> out = new TreeMap<>();
        Path p = Paths.get(root + "/valuation_scoreboard/assumption_changelog.md");
        if (!Files.exists(p)) {
            return out;
        }
        Pattern hold = Pattern.compile(
                "^##\\s+(\\d{4}-\\d{2}-\\d{2})\\s+\u2014\\s+BASE CROSS-CHECK HELD:\\s*([A-Za-z.]+)");
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            Matcher m = hold.matcher(line);
            if (m.find()) {
                out.put(m.group(1) + "|" + m.group(2).toUpperCase(), true);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 5. Derived-file input guard intact (idea #40)
    // ------------------------------------------------------------------

    /**
     * Re-runs the idea #40 guard on the genuine inputs. If a worker (or a
     * future edit) broke the guard itself, this fails — the guard must never
     * be silently weakened.
     */
    static void checkDerivedGuard(Report r) throws Exception {
        r.check();
        String root = workspaceRoot();
        List<String> inputs = new ArrayList<>();
        for (String rel : new String[]{
                "your_files/dcf-valuations/.src/workbook_data.json",
                "sec_filings/dcf_inputs_all.json",
                "stock_watch/price_cache.json"}) {
            Path p = Paths.get(root + "/" + rel);
            if (!Files.exists(p)) {
                r.fail("[guard] genuine input missing: " + rel);
            } else {
                inputs.add(p.toString());
            }
        }
        if (inputs.isEmpty()) {
            return;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin());
        cmd.add(root + "/dcf_csv/DerivedFiles.java");
        cmd.add("--check-inputs");
        cmd.addAll(inputs);
        ProcResult pr = runJava(cmd);
        if (pr.exit != 0) {
            r.fail("[guard] DerivedFiles.java --check-inputs refused the genuine inputs "
                    + "(exit " + pr.exit + "): " + abbrev(pr.output, 300));
        }
    }

    // ------------------------------------------------------------------
    // 6. Derived-file stamps present
    // ------------------------------------------------------------------

    /** The derived registry is read live from DerivedFiles.java --list (single source of truth). */
    static List<String> derivedRegistry(String root) throws Exception {
        List<String> cmd = List.of(javaBin(), root + "/dcf_csv/DerivedFiles.java", "--list");
        ProcResult pr = runJava(cmd);
        List<String> out = new ArrayList<>();
        for (String line : pr.output.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("atlas/Stocks/")) {
                continue; // prefix-matched notes, not individual files
            }
            out.add(t);
        }
        return out;
    }

    /**
     * Every text derived file must still carry its stamp. A hand-edited or
     * replaced derived file is the exact failure idea #40 guards against.
     */
    static void checkStamps(Report r) throws Exception {
        r.check();
        String root = workspaceRoot();
        for (String rel : derivedRegistry(root)) {
            Path p = Paths.get(root + "/" + rel);
            if (!Files.exists(p)) {
                r.info("derived file not present, skipped: " + rel);
                continue;
            }
            String name = rel.toLowerCase();
            if (name.endsWith(".xlsx") || name.endsWith(".pdf")) {
                continue; // binary products; covered by the mtime check
            }
            // Stamps sit at the top of some files (CSVs, JSON extracts) and at
            // the bottom of others (scoreboard.json, the dashboard), so check
            // the whole file — every text derived file here is small.
            String content = readFile(p);
            if (!content.contains("do not read as input") && !content.contains("\"_derived\"")) {
                r.fail("[stamp] derived file lost its stamp: " + rel
                        + " — it may have been hand-edited or replaced outside RebuildAll.");
            }
        }
    }

    // ------------------------------------------------------------------
    // 7a. our_dcf.json still matches the master (stale-revert detector)
    // ------------------------------------------------------------------

    /**
     * our_dcf.json is extracted FROM workbook_data.json. If a run (or a hand
     * edit) wrote values back from the derived file, the numbers drift apart —
     * the silent stale-revert bug of 2026-09-27. Compare every ticker.
     */
    static void checkOurDcfConsistency(Report r) throws Exception {
        r.check();
        String root = workspaceRoot();
        Json.Obj derived = (Json.Obj) parseJsonFile(
                Paths.get(root + "/dcf_csv/our_dcf.json"), r,
                "[stale-revert] our_dcf.json is not valid JSON");
        Json.Obj data = (Json.Obj) parseJsonFile(
                Paths.get(root + "/your_files/dcf-valuations/.src/workbook_data.json"), r,
                "[stale-revert] workbook_data.json is not valid JSON");
        if (derived == null || data == null) {
            return;
        }
        Map<String, Double> master = new TreeMap<>();
        for (Json.Val v : data.getList("companies")) {
            if (v instanceof Json.Obj) {
                Json.Obj c = (Json.Obj) v;
                Json.Val f = c.get("fixed");
                if (f instanceof Json.Num) {
                    master.put(c.optStr("ticker", "").toUpperCase(), ((Json.Num) f).d);
                }
            }
        }
        Json.Obj values = derived.getObj("values");
        if (values == null) {
            r.fail("[stale-revert] our_dcf.json has no 'values' object.");
            return;
        }
        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, Json.Val> e : values.map.entrySet()) {
            String t = e.getKey().toUpperCase();
            if (!(e.getValue() instanceof Json.Obj)) {
                continue;
            }
            Json.Val v = ((Json.Obj) e.getValue()).get("value");
            if (!(v instanceof Json.Num)) {
                continue; // null = no DCF for this ticker; nothing to compare
            }
            Double m = master.get(t);
            if (m == null) {
                mismatched.add(t + " (no master fixed value)");
            } else if (Math.abs(((Json.Num) v).d - m) > 0.011) {
                mismatched.add(t + " (derived " + ((Json.Num) v).d + " vs master " + m + ")");
            }
            if (mismatched.size() >= 10) {
                break;
            }
        }
        if (!mismatched.isEmpty()) {
            r.fail("[stale-revert] our_dcf.json disagrees with workbook_data.json fixed IVs: "
                    + String.join("; ", mismatched)
                    + " — a derived file may have been used as an input (idea #40).");
        }
    }

    // ------------------------------------------------------------------
    // 7b. NotebookLM provenance: every published value has a verified batch record
    // ------------------------------------------------------------------

    /**
     * Fail-closed provenance (idea #40 approval condition): only tickers in the
     * verified 2026-09-26 batch file may carry a NotebookLM value. A value
     * with no batch record must never be presented.
     */
    static void checkNotebookLmProvenance(Report r) throws Exception {
        r.check();
        String root = workspaceRoot();
        checkBatchProvenance(r, root,
                "/goals/quarterly-sec-filing-watch-with-dcf-updates"
                        + "/hidden_files/notebooklm_dcf_batch_2026-09-26.json",
                "/dcf_csv/notebooklm_values.json",
                "notebooklm_values.json");
        checkBatchProvenance(r, root,
                "/goals/quarterly-sec-filing-watch-with-dcf-updates"
                        + "/hidden_files/notebooklm_forward_batch_2026-09-28.json",
                "/dcf_csv/notebooklm_forward.json",
                "notebooklm_forward.json");
    }

    /**
     * Fail-closed provenance for one (batch file, derived values) pair.
     * Every numeric value in the derived file must have a matching entry in
     * the verified batch file's values map.
     */
    static void checkBatchProvenance(Report r, String root, String batchRel,
            String derivedRel, String label) throws Exception {
        Path batchPath = Paths.get(root + batchRel);
        Path derivedPath = Paths.get(root + derivedRel);
        if (!Files.exists(batchPath)) {
            r.fail("[provenance] verified batch file is missing: " + batchPath);
            return;
        }
        if (!Files.exists(derivedPath)) {
            // Forward backend does not exist until the first forward rebuild.
            return;
        }
        Json.Obj batch = (Json.Obj) parseJsonFile(batchPath, r,
                "[provenance] verified batch file is not valid JSON");
        Json.Obj derived = (Json.Obj) parseJsonFile(derivedPath, r,
                "[provenance] " + label + " is not valid JSON");
        if (batch == null || derived == null) {
            return;
        }
        Json.Obj batchValues = batch.getObj("values");
        Json.Obj values = derived.getObj("values");
        if (batchValues == null || values == null) {
            r.fail("[provenance] batch file or " + label + " has no 'values' object.");
            return;
        }
        List<String> unverified = new ArrayList<>();
        for (Map.Entry<String, Json.Val> e : values.map.entrySet()) {
            String t = e.getKey().toUpperCase();
            if (!(e.getValue() instanceof Json.Obj)) {
                continue;
            }
            if (((Json.Obj) e.getValue()).get("value") instanceof Json.Num
                    && !batchValues.map.containsKey(e.getKey())
                    && !batchValues.map.containsKey(t)) {
                unverified.add(t);
            }
        }
        if (!unverified.isEmpty()) {
            r.fail("[provenance] " + label + " publishes values with no verified "
                    + "batch record: " + String.join(", ", unverified)
                    + " — the fail-closed provenance guard was bypassed.");
        }
    }

    // ------------------------------------------------------------------
    // 7c. mtime sanity: derived files regenerate FROM the master
    // ------------------------------------------------------------------

    /**
     * A derived file newer than the master means it was regenerated or edited
     * after the master last changed. That is legitimate when this run changed
     * an input (the changelog row excuses the comparison CSV refresh), when a
     * rebuild ran today (the JSON extracts carry asof == today), or when the
     * file carries the propagation signature: regenerated within
     * PROPAGATION_WINDOW_MS after the master write, the way RebuildAll does it
     * (master first, derived files seconds/minutes later). The window excuse is
     * calendar-day independent — on 2026-10-01 the old "rebuild today"
     * day-boundary excuse false-flagged six files every morning after a
     * rebuild day, even though all six were regenerated 53 seconds after the
     * master in the legitimate propagation pass. Anything else smells like a
     * hand-edit: fail loud.
     */
    static final long PROPAGATION_WINDOW_MS = 30L * 60 * 1000;

    static void checkDerivedMtimes(Report r, boolean changedToday) throws Exception {
        r.check();
        String root = workspaceRoot();
        Path master = Paths.get(root + "/your_files/dcf-valuations/.src/workbook_data.json");
        if (!Files.exists(master)) {
            r.fail("[mtime] master file missing: " + master);
            return;
        }
        FileTime masterTime = Files.getLastModifiedTime(master);
        String today = todayStr();
        boolean rebuildToday = asofIsToday(root + "/dcf_csv/our_dcf.json", today)
                || asofIsToday(root + "/dcf_csv/notebooklm_values.json", today)
                || asofIsToday(root + "/dcf_csv/notebooklm_forward.json", today);
        for (String rel : derivedRegistry(root)) {
            Path p = Paths.get(root + "/" + rel);
            if (!Files.exists(p)) {
                continue;
            }
            long skewMs = Files.getLastModifiedTime(p).toMillis() - masterTime.toMillis();
            // Propagation signature: RebuildAll writes the master, then
            // regenerates every derived file within minutes. A file inside
            // that post-master window was built FROM the current master no
            // matter which calendar day the rebuild ran.
            boolean propagationSignature = skewMs >= 0 && skewMs <= PROPAGATION_WINDOW_MS;
            if (skewMs > 0 && !changedToday && !rebuildToday && !propagationSignature) {
                r.fail("[mtime] derived file is newer than the master with no input change, "
                        + "rebuild today, or propagation signature: " + rel + " — possible "
                        + "hand-edit (derived files regenerate FROM workbook_data.json, "
                        + "never the reverse).");
            }
        }
    }

    /** True when the JSON file's "asof" field equals today (a rebuild ran today). */
    static boolean asofIsToday(String path, String today) {
        try {
            Json.Val v = Json.parse(readFile(Paths.get(path)));
            if (v instanceof Json.Obj) {
                return today.equals(((Json.Obj) v).optStr("asof", ""));
            }
        } catch (Exception ignored) {
            // unreadable -> not evidence of a rebuild; the stamp check reports it
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    static String readFile(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Parse a JSON file; on failure record the given problem and return null. */
    static Json.Val parseJsonFile(Path p, Report r, String problem) {
        try {
            return Json.parse(readFile(p));
        } catch (Exception e) {
            r.fail(problem + " (" + e.getMessage() + "): " + p);
            return null;
        }
    }

    static String abbrev(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    /** Result of running a java single-file program. */
    static class ProcResult {
        int exit;
        String output;
    }

    /** Run a `java X.java ...` command with a UTF-8 locale, capturing output. */
    static ProcResult runJava(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = proc.waitFor();
        ProcResult pr = new ProcResult();
        pr.exit = exit;
        pr.output = out;
        return pr;
    }

    // ------------------------------------------------------------------
    // Json: minimal JSON parser (objects keep insertion order).
    // Mirrors the parser in VerifyBase.java; kept inline so this file runs
    // standalone with single-file source launch.
    // ------------------------------------------------------------------

    static class Json {
        abstract static class Val { }

        static class Obj extends Val {
            final LinkedHashMap<String, Val> map = new LinkedHashMap<>();

            Val get(String k) {
                return map.get(k);
            }

            Obj getObj(String k) {
                Val v = map.get(k);
                return v instanceof Obj ? (Obj) v : null;
            }

            @SuppressWarnings("unchecked")
            List<Val> getList(String k) {
                Val v = map.get(k);
                return v instanceof Arr ? ((Arr) v).items : null;
            }

            String optStr(String k, String dflt) {
                Val v = map.get(k);
                return v instanceof Str ? ((Str) v).v : dflt;
            }
        }

        static class Arr extends Val {
            final List<Val> items = new ArrayList<>();
        }

        static class Str extends Val {
            final String v;
            Str(String v) { this.v = v; }
        }

        static class Num extends Val {
            final double d;
            Num(double d) { this.d = d; }
        }

        static class Bool extends Val {
            final boolean v;
            Bool(boolean v) { this.v = v; }
        }

        static class Null extends Val { }

        static Val parse(String text) {
            Parser p = new Parser(text);
            Val v = p.value();
            p.ws();
            if (p.pos != text.length()) {
                throw new IllegalArgumentException("trailing data at " + p.pos);
            }
            return v;
        }

        static class Parser {
            final String t;
            int pos;
            Parser(String t) { this.t = t; }

            void ws() {
                while (pos < t.length()) {
                    char c = t.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            Val value() {
                ws();
                if (pos >= t.length()) {
                    throw new IllegalArgumentException("unexpected end of input");
                }
                char c = t.charAt(pos);
                switch (c) {
                    case '{': return obj();
                    case '[': return arr();
                    case '"': return new Str(str());
                    case 't': expect("true"); return new Bool(true);
                    case 'f': expect("false"); return new Bool(false);
                    case 'n': expect("null"); return new Null();
                    default: return num();
                }
            }

            Obj obj() {
                Obj o = new Obj();
                pos++;
                ws();
                if (pos < t.length() && t.charAt(pos) == '}') {
                    pos++;
                    return o;
                }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (t.charAt(pos) != ':') {
                        throw new IllegalArgumentException("expected : at " + pos);
                    }
                    pos++;
                    o.map.put(k, value());
                    ws();
                    char ch = t.charAt(pos);
                    if (ch == ',') {
                        pos++;
                    } else if (ch == '}') {
                        pos++;
                        return o;
                    } else {
                        throw new IllegalArgumentException("expected , or } at " + pos);
                    }
                }
            }

            Arr arr() {
                Arr a = new Arr();
                pos++;
                ws();
                if (pos < t.length() && t.charAt(pos) == ']') {
                    pos++;
                    return a;
                }
                while (true) {
                    a.items.add(value());
                    ws();
                    char ch = t.charAt(pos);
                    if (ch == ',') {
                        pos++;
                    } else if (ch == ']') {
                        pos++;
                        return a;
                    } else {
                        throw new IllegalArgumentException("expected , or ] at " + pos);
                    }
                }
            }

            String str() {
                pos++;
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = t.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        char e = t.charAt(pos++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u': {
                                int cp = Integer.parseInt(t.substring(pos, pos + 4), 16);
                                pos += 4;
                                sb.append((char) cp);
                                break;
                            }
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }

            Val num() {
                int start = pos;
                while (pos < t.length() && "-+0123456789.eE".indexOf(t.charAt(pos)) >= 0) {
                    pos++;
                }
                String lit = t.substring(start, pos);
                if (lit.indexOf('.') < 0 && lit.indexOf('e') < 0 && lit.indexOf('E') < 0) {
                    try {
                        return new Num(Long.parseLong(lit));
                    } catch (NumberFormatException e) {
                        return new Num(Double.parseDouble(lit));
                    }
                }
                return new Num(Double.parseDouble(lit));
            }

            void expect(String word) {
                if (!t.startsWith(word, pos)) {
                    throw new IllegalArgumentException("expected " + word + " at " + pos);
                }
                pos += word.length();
            }
        }
    }
}
