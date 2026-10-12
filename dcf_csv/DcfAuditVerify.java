import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DcfAuditVerify.java — idea #44 health-check verifier for the
 * {@code dcf-assumption-audit} cron (the quarterly adversarial DCF audit).
 *
 * <p>Runs as the FINAL step of the job body. It re-checks the persisted state
 * the audit was supposed to leave behind — it never changes any reported
 * value, never writes state, and never sends messages. Exit 0 means the audit
 * left a healthy state; exit 1 names every problem found, loudly.
 *
 * <p>What --verify checks, for the audit date being verified (default: the
 * latest date with audit rows in the changelog; override with --date):
 * <ol>
 *   <li><b>Coverage</b> — assumption_changelog.md has exactly one row per
 *       in-scope ticker (every ticker with an {@code ours} or
 *       {@code ours_fixed} value in scoreboard.json), in the 7-column format
 *       date|ticker|change|IV old→new|confidence|sensitivity|trigger.</li>
 *   <li><b>REVISE rows</b> — every revision carries (a) a VerifyBase PASS
 *       record in {@code dcf_csv/verifybase_gate.log} or a logged HOLD
 *       section (Rahik's cross-check rule), (b) a dual-track note
 *       ("independently derived from the filing" — our inputs are never
 *       copied from NotebookLM), and (c) a filed proposal in
 *       {@code dcf_csv/proposals/pending} or {@code proposals/approved}
 *       whose value and assumptions match the row (the audit NEVER writes
 *       blessed values directly — Rahik 2026-09-27; approved proposals apply
 *       through the guard and publish on the next rebuild).</li>
 *   <li><b>Dashboard</b> — the Atlas valuation dashboard was regenerated at
 *       or after the audit date.</li>
 *   <li><b>Shared guards</b> — runs {@code FilingWatchVerify.java --shared}:
 *       the derived-file input guard, stamps, our_dcf.json-vs-master
 *       consistency, NotebookLM provenance, and mtime sanity.</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   java DcfAuditVerify.java --verify                 # verify the latest audit batch
 *   java DcfAuditVerify.java --verify --date 2026-10-15   # verify a specific batch
 * </pre>
 *
 * <p>Exit 0 = VERIFY OK. Exit 1 = VERIFY FAILED (problems listed on stdout).
 * Exit 2 = usage error. Honors {@code DCF_WORKSPACE_ROOT} for tests; dates
 * are in America/New_York, matching the changelog.
 *
 * <p>Written in plain Java 17 so Rahik can read it himself.
 */
public class DcfAuditVerify {

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
        String dateOverride = null;
        boolean verify = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--verify")) {
                verify = true;
            } else if (args[i].equals("--date") && i + 1 < args.length) {
                dateOverride = args[++i];
            } else {
                verify = false;
                break;
            }
        }
        if (!verify || (dateOverride != null && !dateOverride.matches("\\d{4}-\\d{2}-\\d{2}"))) {
            System.out.println("DcfAuditVerify.java — idea #44 verifier for dcf-assumption-audit.");
            System.out.println("  java DcfAuditVerify.java --verify [--date YYYY-MM-DD]");
            System.exit(2);
            return;
        }
        Report r = new Report();
        verify(r, dateOverride);
        Verify.report(
                "VERIFY OK (DcfAuditVerify): " + r.checks + " checks passed, no problems found.",
                "VERIFY FAILED (DcfAuditVerify): " + r.failures.size() + " problem(s) found:",
                r.infos, r.failures);
    }

    static void verify(Report r, String dateOverride) throws Exception {
        String root = workspaceRoot();
        Set<String> scope = auditScope(r, root);
        List<ChangeRow> auditRows = readChangelogRows(r, root);
        // The audit date being verified: explicit --date, else the latest
        // date with audit rows (when run as the final step, that is today).
        String auditDate = dateOverride;
        if (auditDate == null) {
            TreeSet<String> dates = new TreeSet<>();
            for (ChangeRow row : auditRows) {
                dates.add(row.date);
            }
            if (dates.isEmpty()) {
                r.info("no audit rows recorded in assumption_changelog.md yet — "
                        + "nothing to verify.");
                return;
            }
            auditDate = dates.last();
        }
        List<ChangeRow> batch = new ArrayList<>();
        for (ChangeRow row : auditRows) {
            if (row.date.equals(auditDate)) {
                batch.add(row);
            }
        }
        if (batch.isEmpty()) {
            r.info("no audit rows for " + auditDate + " — nothing to verify.");
            return;
        }
        r.info("verifying audit batch of " + auditDate + " (" + batch.size() + " rows).");
        checkCoverage(r, scope, batch, auditDate);
        checkRows(r, batch, auditDate);
        checkDashboard(r, auditDate);
        checkSharedGuards(r);
    }

    // ------------------------------------------------------------------
    // Scope: every ticker with an ours or ours_fixed value in scoreboard.json
    // ------------------------------------------------------------------

    /** The audit's SCOPE rule: every ticker with an ours/ours_fixed value. */
    static Set<String> auditScope(Report r, String root) throws Exception {
        r.check();
        Set<String> scope = new TreeSet<>();
        Path p = Paths.get(root + "/valuation_scoreboard/scoreboard.json");
        Json.Val v = parseJsonFile(p, r, "[scope] scoreboard.json is not valid JSON");
        if (!(v instanceof Json.Obj)) {
            return scope;
        }
        List<Json.Val> entries = ((Json.Obj) v).getList("entries");
        if (entries == null) {
            r.fail("[scope] scoreboard.json has no 'entries' list.");
            return scope;
        }
        for (Json.Val e : entries) {
            if (!(e instanceof Json.Obj)) {
                continue;
            }
            Json.Obj o = (Json.Obj) e;
            String source = o.optStr("source", "");
            if (source.equals("ours") || source.equals("ours_fixed")) {
                String t = o.optStr("ticker", "").toUpperCase();
                if (!t.isEmpty()) {
                    scope.add(t);
                }
            }
        }
        if (scope.isEmpty()) {
            r.fail("[scope] no tickers with ours/ours_fixed values in scoreboard.json — "
                    + "the audit would have nothing to cover.");
        } else {
            r.info("audit scope: " + scope.size() + " tickers with ours/ours_fixed values.");
        }
        return scope;
    }

    // ------------------------------------------------------------------
    // Changelog rows (same 7-column table as the filing watch writes)
    // ------------------------------------------------------------------

    /** One markdown table row of assumption_changelog.md. */
    static class ChangeRow {
        String date, ticker, what, iv, confidence, sensitivity, trigger;

        boolean isRevision() {
            String w = what.toLowerCase();
            return w.contains("revis")
                    || (what.contains("\u2192") && !w.contains("no change"));
        }

        boolean isAuditRow() {
            return trigger.toLowerCase().contains("dcf audit");
        }
    }

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
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                cells.add(parts[i].trim());
            }
            if (cells.get(0).equalsIgnoreCase("Date")) {
                continue;
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
            if (!row.isAuditRow()) {
                continue; // filing-watch rows are FilingWatchVerify's business
            }
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 1. Coverage: one row per in-scope ticker
    // ------------------------------------------------------------------

    static void checkCoverage(Report r, Set<String> scope, List<ChangeRow> batch, String auditDate) {
        r.check();
        Map<String, Integer> perTicker = new TreeMap<>();
        for (ChangeRow row : batch) {
            perTicker.put(row.ticker, perTicker.getOrDefault(row.ticker, 0) + 1);
            if (!scope.contains(row.ticker)) {
                r.info("audit row for out-of-scope ticker " + row.ticker
                        + " on " + auditDate + " (not in ours/ours_fixed scope).");
            }
        }
        List<String> missing = new ArrayList<>();
        for (String t : scope) {
            if (!perTicker.containsKey(t)) {
                missing.add(t);
            }
        }
        if (!missing.isEmpty()) {
            r.fail("[coverage] audit batch of " + auditDate + " is missing rows for "
                    + missing.size() + " in-scope ticker(s): " + String.join(", ", missing)
                    + " — the audit must append one row per ticker.");
        }
        List<String> dupes = new ArrayList<>();
        for (Map.Entry<String, Integer> e : perTicker.entrySet()) {
            if (e.getValue() > 1) {
                dupes.add(e.getKey() + " x" + e.getValue());
            }
        }
        if (!dupes.isEmpty()) {
            r.fail("[coverage] duplicate audit rows for " + auditDate + ": "
                    + String.join(", ", dupes) + " — exactly one row per ticker.");
        }
    }

    // ------------------------------------------------------------------
    // 2. Row format + REVISE-row checks
    // ------------------------------------------------------------------

    static void checkRows(Report r, List<ChangeRow> batch, String auditDate) throws Exception {
        r.check();
        String root = workspaceRoot();
        Map<String, String> passes = readGateLog(root);
        Map<String, Boolean> holds = readHoldSections(root);
        for (ChangeRow row : batch) {
            String t = row.ticker;
            // Format: date|ticker|change|IV old→new|confidence|sensitivity|trigger.
            if (row.what.isEmpty() || row.iv.isEmpty() || row.confidence.isEmpty()
                    || row.sensitivity.isEmpty()) {
                r.fail("[format] " + t + " (" + auditDate + "): row has an empty column — "
                        + "expected date|ticker|change|IV old→new|confidence|sensitivity|trigger "
                        + "all filled.");
            }
            if (!row.trigger.toLowerCase().contains("verdict")) {
                r.fail("[format] " + t + " (" + auditDate + "): trigger column does not record "
                        + "my verdict (defend/revise) — the audit's verdict trail is missing.");
            }
            if (!row.isRevision()) {
                continue;
            }
            // (a) Cross-check gate trail: VerifyBase pass or logged HOLD.
            String key = auditDate + "|" + t;
            if (!passes.containsKey(key) && !holds.containsKey(key)) {
                r.fail("[cross-check] " + t + " (" + auditDate + "): REVISE recorded but no "
                        + "VerifyBase PASS record in dcf_csv/verifybase_gate.log and no BASE "
                        + "CROSS-CHECK HELD section — the idea #32 gate has no trail.");
            }
            // (b) Dual-track note: our inputs are independently derived, never
            // copied from NotebookLM's answers.
            if (!row.what.toLowerCase().contains("independent")
                    && !row.trigger.toLowerCase().contains("independent")) {
                r.fail("[dual-track] " + t + " (" + auditDate + "): REVISE row has no "
                        + "dual-track note — state 'independently derived from the filing' so "
                        + "the verifier can confirm our inputs were not copied from NotebookLM.");
            }
            // (c) Filed proposal: the audit files proposals; it never writes
            // blessed values directly. The row must name the proposal and the
            // filed proposal must agree with the row.
            checkProposal(r, root, row, auditDate);
        }
    }

    // ------------------------------------------------------------------
    // 2b. Filed proposal check (the audit files proposals; it never writes)
    // ------------------------------------------------------------------

    /**
     * A REVISE row must name its filed proposal (prop-YYYYMMDD-NNN in the
     * change or trigger column). The proposal file must exist under
     * proposals/pending or proposals/approved, target column "ours" for this
     * ticker, carry the full assumptions JSON, and its new value must match
     * the row's "new" intrinsic value.
     */
    static void checkProposal(Report r, String root, ChangeRow row, String auditDate) {
        r.check();
        String t = row.ticker;
        Pattern idPat = Pattern.compile("prop-\\d{8}-\\d{3}");
        Matcher m = idPat.matcher(row.what + " " + row.trigger);
        if (!m.find()) {
            r.fail("[proposal] " + t + " (" + auditDate + "): REVISE recorded but no "
                    + "proposal id (prop-YYYYMMDD-NNN) in the row — the audit must file "
                    + "a proposal via Proposals.java instead of writing.");
            return;
        }
        String id = m.group();
        Path p = null;
        for (String dir : new String[]{"pending", "approved"}) {
            Path cand = Paths.get(root + "/dcf_csv/proposals/" + dir + "/" + id + ".json");
            if (Files.exists(cand)) {
                p = cand;
                break;
            }
        }
        if (p == null) {
            r.fail("[proposal] " + t + " (" + auditDate + "): proposal " + id
                    + " is not filed under dcf_csv/proposals/pending or proposals/approved.");
            return;
        }
        Json.Val v;
        try {
            v = Json.parse(readFile(p));
        } catch (Exception e) {
            r.fail("[proposal] " + t + " (" + auditDate + "): proposal " + id
                    + " is not valid JSON (" + e.getMessage() + ").");
            return;
        }
        if (!(v instanceof Json.Obj)) {
            r.fail("[proposal] " + t + " (" + auditDate + "): proposal " + id
                    + " is not a JSON object.");
            return;
        }
        Json.Obj o = (Json.Obj) v;
        if (!"ours".equals(o.optStr("column", ""))) {
            r.fail("[proposal] " + t + " (" + auditDate + "): proposal " + id
                    + " targets column '" + o.optStr("column", "?") + "', expected 'ours'.");
        }
        if (!t.equals(o.optStr("ticker", "").toUpperCase())) {
            r.fail("[proposal] " + t + " (" + auditDate + "): proposal " + id
                    + " is for ticker '" + o.optStr("ticker", "?") + "'.");
        }
        Json.Val asm = o.get("assumptions");
        if (asm == null || asm instanceof Json.Null) {
            r.fail("[proposal] " + t + " (" + auditDate + "): proposal " + id
                    + " carries no assumptions JSON — an ours proposal without its "
                    + "assumptions is rejected at file time.");
        }
        // The row's new IV must equal the proposal's new value.
        Double rowNew = parseNewIv(row.iv);
        Json.Val nv = o.get("new_value");
        double propNew = (nv instanceof Json.Num) ? ((Json.Num) nv).d : Double.NaN;
        if (rowNew == null) {
            r.fail("[proposal] " + t + " (" + auditDate + "): cannot parse the row's "
                    + "new intrinsic value from '" + abbrev(row.iv, 60) + "'.");
        } else if (Double.isNaN(propNew) || Math.abs(rowNew - propNew) > 0.005) {
            r.fail("[proposal] " + t + " (" + auditDate + "): row's new IV "
                    + rowNew + " does not match proposal " + id + "'s new value "
                    + (Double.isNaN(propNew) ? "(missing)" : propNew) + ".");
        }
    }

    /** Parse the number after the last "→" in an "old → new" IV cell. */
    static Double parseNewIv(String iv) {
        if (iv == null) {
            return null;
        }
        int arrow = iv.lastIndexOf('\u2192');
        String tail = (arrow >= 0 ? iv.substring(arrow + 1) : iv).replaceAll("[^0-9.]", "");
        if (tail.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(tail);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 3. Dashboard regenerated at/after the audit date
    // ------------------------------------------------------------------

    static void checkDashboard(Report r, String auditDate) throws Exception {
        r.check();
        String root = workspaceRoot();
        Path p = Paths.get(root + "/atlas/Valuation Dashboard.md");
        if (!Files.exists(p)) {
            r.fail("[dashboard] atlas/Valuation Dashboard.md is missing — STEP 6 did not run.");
            return;
        }
        LocalDate mtime = Instant.ofEpochMilli(
                Files.getLastModifiedTime(p).toMillis()).atZone(ET).toLocalDate();
        LocalDate audit = LocalDate.parse(auditDate);
        LocalDate today = LocalDate.parse(todayStr());
        if (mtime.isBefore(audit)) {
            r.fail("[dashboard] Valuation Dashboard.md was last modified " + mtime
                    + ", before the audit date " + auditDate + " — STEP 6 (dashboard "
                    + "regeneration) did not run for this audit.");
        } else if (mtime.isAfter(today)) {
            r.fail("[dashboard] Valuation Dashboard.md mtime " + mtime + " is in the future.");
        }
    }

    // ------------------------------------------------------------------
    // 5. Shared guards via FilingWatchVerify --shared
    // ------------------------------------------------------------------

    /** Reuse the filing-watch verifier's guard checks (single source of truth). */
    static void checkSharedGuards(Report r) throws Exception {
        r.check();
        String root = workspaceRoot();
        List<String> cmd = List.of(javaBin(), root + "/dcf_csv/FilingWatchVerify.java", "--shared");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = proc.waitFor();
        if (exit != 0) {
            r.fail("[shared] FilingWatchVerify --shared failed (exit " + exit + "):\n"
                    + indent(out));
        }
    }

    static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            sb.append("      ").append(line).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Gate log + HOLD sections (same files VerifyBase writes)
    // ------------------------------------------------------------------

    /** Parse verifybase_gate.log into "date|TICKER" -> verdict. */
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

    /** Find "## YYYY-MM-DD — BASE CROSS-CHECK HELD: TICKER" sections. */
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

    // ------------------------------------------------------------------
    // Csv: minimal reader (quotes + embedded newlines), mirroring the one in
    // RecomputeColumns.java. Kept inline so this file runs standalone.
    // ------------------------------------------------------------------

    static class Csv {
        static List<List<String>> read(String text) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            boolean fieldStarted = false;
            int i = 0, n = text.length();
            while (i < n) {
                char c = text.charAt(i);
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < n && text.charAt(i + 1) == '"') {
                            field.append('"');
                            i += 2;
                        } else {
                            inQuotes = false;
                            i++;
                        }
                    } else {
                        field.append(c);
                        i++;
                    }
                } else if (c == '"') {
                    inQuotes = true;
                    fieldStarted = true;
                    i++;
                } else if (c == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    i++;
                } else if (c == '\r' || c == '\n') {
                    row.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    rows.add(row);
                    row = new ArrayList<>();
                    if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                        i += 2;
                    } else {
                        i++;
                    }
                } else {
                    field.append(c);
                    fieldStarted = true;
                    i++;
                }
            }
            if (fieldStarted || !row.isEmpty()) {
                row.add(field.toString());
                rows.add(row);
            }
            return rows;
        }
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
