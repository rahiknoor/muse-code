import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One-command pipeline for the two 6%-drop-alert cron jobs:
 * stock-drop-alerts-meta-nflx-sofi (market hours) and
 * stock-drop-alerts-off-hours (weekday overnights/holidays).
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --run --mode=market-hours|off-hours} — market-window gate
 *       (quiet exit outside the job's window) → one batch quote fetch
 *       (BatchQuotes.java first, Python fallback; NEVER --write-cache) →
 *       list tickers down 6%+ as JSON for the agent to deliver.</li>
 *   <li>{@code --dry-run --mode=...} — same but skips the window gate and
 *       never writes anything. Safe for testing.</li>
 *   <li>{@code --verify} — health check over the current real state.</li>
 * </ul>
 *
 * <p>The pipeline never sends chat messages; it prints alert candidates.
 * It never writes price_cache.json — that is the critical guard, checked
 * both during --run and by --verify (the 7:30 AM closes job owns the cache).
 */
public class DropAlertPipeline {

    static final String HOME = homeDir();
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String SW = HOME + "/workspace/stock_watch";
    static final String WATCHLIST = SW + "/watchlist.json";
    static final String CACHE = SW + "/price_cache.json";
    static final String GUARDS = SW + "/AlertGuards.java";
    static final String THRESHOLDS_FILE = SW + "/alert_thresholds.json";
    /** Baseline anchor for the volatility-adjusted thresholds (AlertGuards.java
     *  documents the formula). Used only when the thresholds file is missing
     *  or a ticker has no entry — alerts never go silent for lack of data. */
    static final double DROP_THRESHOLD_PCT = -6.0;

    /** Run-record location: override with PIPELINE_STATE_DIR in tests so --run
     *  never touches real state. The price cache is always the real file
     *  (this pipeline only ever reads it). */
    static String stateDir() {
        String o = System.getenv("PIPELINE_STATE_DIR");
        return (o != null && !o.isEmpty()) ? o : SW;
    }

    static String lastRunFile() { return stateDir() + "/drop_alert_last_run.json"; }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String javaBin() {
        String jh = System.getenv("JAVA_HOME");
        return (jh != null && !jh.isEmpty()) ? jh + "/bin/java" : "java";
    }

    public static void main(String[] args) throws Exception {
        String mode = null, runMode = null;
        for (String a : args) {
            if (a.equals("--run") || a.equals("--dry-run") || a.equals("--verify") || a.equals("--self-test")) mode = a;
            if (a.startsWith("--mode=")) runMode = a.substring("--mode=".length());
        }
        switch (mode == null ? "" : mode) {
            case "--run":
                requireMode(runMode);
                run(runMode, false);
                break;
            case "--dry-run":
                requireMode(runMode);
                run(runMode, true);
                break;
            case "--verify":
                verify();
                break;
            case "--self-test":
                selfTest();
                break;
            default:
                System.err.println("Usage: java DropAlertPipeline.java --run|--dry-run --mode=market-hours|off-hours | --verify | --self-test");
                System.exit(2);
        }
    }

    static void requireMode(String m) {
        if (!"market-hours".equals(m) && !"off-hours".equals(m)) {
            System.err.println("--mode=market-hours|off-hours is required");
            System.exit(2);
        }
    }

    // =====================================================================
    // --run / --dry-run
    // =====================================================================

    static void run(String jobMode, boolean dryRun) throws Exception {
        long runStart = System.currentTimeMillis();
        List<String> tickers = readTickers();

        // 1. Window gate — each job only checks inside its own window.
        //    Dry-run skips the gate so the fetch path can be tested anytime.
        if (!dryRun) {
            String quietReason = windowGate(jobMode);
            if (quietReason != null) {
                writeLastRun(runStart, jobMode, "quiet_exit", quietReason, tickers, List.of(), -1, -1);
                System.out.println(Json.dumps(Map.of(
                        "status", "quiet_exit", "mode", jobMode, "reason", quietReason)));
                return;
            }
        }

        // 2. Snapshot the cache mtime BEFORE the fetch — the guard below
        //    proves this run never wrote price_cache.json.
        long cacheBefore = fileMtime(CACHE);

        // 3. One batch fetch, read-only (no --write-cache, ever).
        FetchOut fetch = fetchQuotes();
        if (!fetch.ok) fail(jobMode, "quote fetch failed via BatchQuotes.java, batch_quotes.py.bak, and YahooQuotes.java: " + fetch.error);

        // 4. CRITICAL GUARD: the cache must be byte-identical in mtime terms —
        //    this job is read-only; the 7:30 AM closes job owns the cache.
        long cacheAfter = fileMtime(CACHE);
        if (cacheAfter != cacheBefore)
            fail(jobMode, "price_cache.json was modified during this run (mtime changed) —"
                    + " drop-alert runs must NEVER write the cache");

        // 5. Volatility-adjusted thresholds: refresh once a day (in --run only;
        //    dry-run never writes), then load per-ticker thresholds. Missing
        //    file or missing ticker -> flat -6% baseline (alerts never go
        //    silent for lack of threshold data).
        Map<String, Double> thresholds = loadAlertThresholds(dryRun);
        String thresholdsAsof = thresholdsAsof();

        // 6. Flag tickers down past their per-ticker threshold, then apply
        //    the split/dividend guard: a drop mechanically caused by a split
        //    or ex-dividend date is logged and stays silent — it is not a
        //    real drop. The guard only ever suppresses; it never creates
        //    alerts, and it fails open (no suppression) when data is missing.
        List<Map<String, Object>> alerts = new ArrayList<>();
        List<Map<String, Object>> suppressed = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (String t : tickers) {
            Map<String, Object> q = fetch.quotes.get(t);
            if (q == null || q.get("change_pct") == null) continue; // uncovered this run
            checked.add(t);
            double pct = ((Number) q.get("change_pct")).doubleValue();
            double thr = thresholds.getOrDefault(t, -DROP_THRESHOLD_PCT);
            if (pct <= -thr) {
                Map<String, Object> guard = corporateActionCheck(t, str(q.get("session_date")), pct);
                if (Boolean.TRUE.equals(guard.get("suppressed"))) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("ticker", t);
                    s.put("price", q.get("price"));
                    s.put("change_pct", q.get("change_pct"));
                    s.put("session_date", q.get("session_date"));
                    s.put("threshold_pct", thr);
                    s.put("suppressed_as", guard.get("kind"));
                    s.put("reason", guard.get("detail"));
                    suppressed.add(s);
                } else {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("ticker", t);
                    a.put("price", q.get("price"));
                    a.put("change_pct", q.get("change_pct"));
                    a.put("session_date", q.get("session_date"));
                    a.put("threshold_pct", thr);
                    a.put("corporate_action_check", guard.get("detail"));
                    alerts.add(a);
                }
            }
        }

        // Dry-run never writes: no state changes at all.
        if (!dryRun)
            writeLastRun(runStart, jobMode, "ok", null, tickers, checked,
                    cacheBefore, cacheAfter);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", dryRun ? "dry_run" : "ok");
        out.put("mode", jobMode);
        out.put("asof", fetch.asof);
        out.put("quotes_source", fetch.source);
        out.put("checked", checked.size());
        out.put("thresholds_asof", thresholdsAsof);
        out.put("alerts", alerts);
        out.put("suppressed", suppressed);
        System.out.println(Json.dumps(out));
    }

    /**
     * Returns a quiet-exit reason, or null when this run should proceed.
     * market-hours: only inside the 06:00-16:00 ET Mon-Fri active window.
     * off-hours: only OUTSIDE that window, on weekdays (never weekends).
     */
    static String windowGate(String jobMode) {
        ExecResult cal = exec(List.of(javaBin(), SW + "/MarketCalendar.java", "--is-open"), 60_000);
        boolean marketOpen = cal.exit == 0;
        DayOfWeek dow = LocalDate.now(ET).getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        if ("market-hours".equals(jobMode)) {
            return marketOpen ? null : "outside market-hours window (" + firstLine(cal.stdout + cal.stderr) + ")";
        }
        // off-hours
        if (marketOpen) return "market is open — the market-hours job covers this window";
        if (weekend) return "weekend — no checks on weekends";
        return null; // weekday overnight or holiday: proceed
    }

    static FetchOut fetchQuotes() {
        List<FetchOut> tried = new ArrayList<>();
        // Each stage runs only if every previous stage failed — the Yahoo
        // failover (last) only runs when both CNBC paths fail.
        tried.add(parseFetch(exec(List.of(javaBin(), SW + "/BatchQuotes.java"), 180_000)));
        if (!tried.get(0).ok)
            tried.add(parseFetch(exec(List.of("python3", SW + "/batch_quotes.py.bak"), 180_000)));
        if (tried.stream().noneMatch(f -> f.ok))
            // Tertiary failover: Yahoo Finance chart API (Rahik approved 2026-09-28;
            // Stooq verified dead). --class-path: YahooQuotes references the shared
            // SelfTest harness, so the shared classes must be on the class path
            // (same pattern as EarningsGuidance).
            tried.add(parseFetch(exec(List.of(javaBin(), "--class-path",
                    HOME + "/workspace/java-shared/classes", SW + "/YahooQuotes.java"), 420_000)));
        FetchOut winner = firstOk(tried);
        if (!winner.ok)
            winner.error = "cnbc-java: " + tried.get(0).error
                    + (tried.size() > 1 ? " | cnbc-python: " + tried.get(1).error : "")
                    + (tried.size() > 2 ? " | yahoo: " + tried.get(2).error : "");
        return winner;
    }

    /**
     * Pure chain decision over already-fetched results: the first ok result
     * wins (priority order = list order); otherwise the last, which carries
     * the combined error. The real fetchQuotes() above routes through this.
     */
    static FetchOut firstOk(List<FetchOut> tried) {
        for (FetchOut f : tried) if (f.ok) return f;
        return tried.get(tried.size() - 1);
    }

    static FetchOut parseFetch(ExecResult r) {
        FetchOut f = new FetchOut();
        if (r.timedOut) { f.error = "timed out"; return f; }
        if (r.exit != 0 && r.exit != 2) { f.error = "exit=" + r.exit + " stderr=" + firstLine(r.stderr); return f; }
        Map<String, Object> doc;
        try {
            doc = asMap(Json.parse(r.stdout));
        } catch (Exception e) {
            f.error = "unparseable output: " + e.getMessage();
            return f;
        }
        if (doc == null) { f.error = "top-level JSON is not an object"; return f; }
        Map<String, Object> quotes = asMap(doc.get("quotes"));
        if (quotes == null || quotes.isEmpty()) { f.error = "no quotes in output"; return f; }
        for (Map.Entry<String, Object> e : quotes.entrySet()) {
            Map<String, Object> q = asMap(e.getValue());
            if (q != null) f.quotes.put(e.getKey(), q);
        }
        if (f.quotes.isEmpty()) { f.error = "quotes present but none parsed"; return f; }
        f.ok = true;
        f.asof = str(doc.get("asof"));
        f.source = str(doc.get("source"));
        return f;
    }

    static class FetchOut {
        boolean ok = false;
        String error = "";
        String asof = "";
        String source = "";
        Map<String, Map<String, Object>> quotes = new LinkedHashMap<>();
    }

    /**
     * Volatility-adjusted thresholds, refreshed at most once a day.
     * In --run: when alert_thresholds.json is missing or its asof is not
     * today (ET), run AlertGuards --refresh-thresholds first (best effort —
     * a refresh failure falls back to the flat -6% baseline, never blocks
     * the run). In --dry-run: read-only, use whatever file exists.
     * Returns ticker -> threshold_pct (positive number, e.g. 9.5).
     */
    static Map<String, Double> loadAlertThresholds(boolean dryRun) {
        if (!dryRun) {
            String asof = thresholdsAsof();
            String today = LocalDate.now(ET).format(DateTimeFormatter.ISO_DATE);
            if (!today.equals(asof)) {
                ExecResult r = exec(List.of(javaBin(), GUARDS, "--refresh-thresholds"), 600_000);
                if (r.exit != 0)
                    System.err.println("WARNING: threshold refresh failed (" + firstLine(r.stderr)
                            + ") — falling back to flat -6% baseline");
            }
        }
        Map<String, Double> m = new LinkedHashMap<>();
        try {
            Map<String, Object> doc = asMap(Json.parse(readFile(THRESHOLDS_FILE)));
            Map<String, Object> th = asMap(doc.get("thresholds"));
            if (th != null) {
                for (Map.Entry<String, Object> e : th.entrySet()) {
                    Map<String, Object> rec = asMap(e.getValue());
                    if (rec != null && rec.get("threshold_pct") instanceof Number)
                        m.put(e.getKey(), ((Number) rec.get("threshold_pct")).doubleValue());
                }
            }
        } catch (Exception e) {
            System.err.println("WARNING: could not read " + THRESHOLDS_FILE + " (" + e.getMessage()
                    + ") — falling back to flat -6% baseline");
        }
        return m;
    }

    static String thresholdsAsof() {
        try {
            return str(asMap(Json.parse(readFile(THRESHOLDS_FILE))).get("asof"));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Split/dividend guard for one alert candidate. Shells out to
     * AlertGuards --check; returns {suppressed, kind, detail}. Any failure
     * fails open (no suppression) so a real drop is never silenced by a
     * broken guard.
     */
    static Map<String, Object> corporateActionCheck(String ticker, String sessionDate, double dropPct) {
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("suppressed", false);
        open.put("kind", null);
        open.put("detail", "guard unavailable — not suppressed");
        ExecResult r = exec(List.of(javaBin(), GUARDS, "--check", ticker,
                "--session-date=" + sessionDate, "--drop-pct=" + dropPct), 120_000);
        if (r.timedOut || r.exit != 0) {
            open.put("detail", "guard timed out/failed — not suppressed");
            return open;
        }
        try {
            Map<String, Object> doc = asMap(Json.parse(r.stdout));
            if (doc != null && doc.containsKey("suppressed")) return doc;
        } catch (Exception e) {
            open.put("detail", "guard output unparseable — not suppressed");
        }
        return open;
    }

    static List<String> readTickers() throws IOException {
        Map<String, Object> wl = asMap(Json.parse(readFile(WATCHLIST)));
        List<String> tickers = new ArrayList<>();
        for (Object t : asList(wl.get("tickers"))) tickers.add(String.valueOf(t));
        if (tickers.isEmpty()) throw new IllegalStateException("watchlist.json has no tickers");
        return tickers;
    }

    static void writeLastRun(long runStart, String jobMode, String status, String reason,
                             List<String> watchlist, List<String> checked,
                             long cacheBefore, long cacheAfter) throws IOException {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("mode", jobMode);
        rec.put("run_start_epoch_ms", runStart);
        rec.put("run_start_et", etNow());
        rec.put("status", status);
        if (reason != null) rec.put("reason", reason);
        rec.put("watchlist", watchlist);
        rec.put("checked", checked);
        if (cacheBefore >= 0) rec.put("cache_mtime_before_ms", cacheBefore);
        if (cacheAfter >= 0) rec.put("cache_mtime_after_ms", cacheAfter);
        atomicWrite(lastRunFile(), Json.dumps(rec));
    }

    // =====================================================================
    // --verify
    // =====================================================================

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        Map<String, Object> rec = readLastRun();
        if (rec == null) {
            problems.add("no last-run record at " + lastRunFile() + " — run --run once first");
            report(problems, null);
            return;
        }
        String status = str(rec.get("status"));
        if ("quiet_exit".equals(status)) {
            // A quiet exit must not have touched the cache; nothing else to check.
            System.out.println(Json.dumps(Map.of("status", "ok", "mode", str(rec.get("mode")),
                    "note", "quiet exit: " + str(rec.get("reason")))));
            return;
        }

        List<String> watchlist = readTickers();
        List<Object> checkedRaw = asList(rec.get("checked"));
        List<String> checked = new ArrayList<>();
        for (Object c : checkedRaw) checked.add(String.valueOf(c));

        // 1. Coverage: every watchlist ticker was checked.
        if (checked.size() != watchlist.size())
            problems.add("coverage " + checked.size() + " != watchlist size " + watchlist.size()
                    + " — every ticker must be checked each run");
        for (String t : watchlist)
            if (!checked.contains(t)) problems.add("ticker " + t + " was not checked this run");

        // 2. No invented prices: the checked list must be a subset of the
        //    watchlist (tickers can only come from the fetch of the roster).
        for (String c : checked)
            if (!watchlist.contains(c)) problems.add("checked ticker " + c + " is not in watchlist.json (invented?)");

        // 3. CRITICAL: price_cache.json was NOT written by this run — its mtime
        //    must predate the run start (the 7:30 AM closes job owns it).
        long runStart = num(rec.get("run_start_epoch_ms"));
        long cacheMtime = fileMtime(CACHE);
        Object before = rec.get("cache_mtime_before_ms");
        Object after = rec.get("cache_mtime_after_ms");
        if (before != null && after != null && num(before) != num(after))
            problems.add("cache mtime changed during the run: this job wrote price_cache.json (forbidden)");
        if (cacheMtime >= runStart)
            problems.add("price_cache.json mtime is not older than this run's start —"
                    + " the cache may have been written by this run (forbidden)");

        report(problems, rec);
    }

    static void report(List<String> problems, Map<String, Object> rec) {
        String mode = rec == null ? "" : str(rec.get("mode"));
        if (!problems.isEmpty())
            failVerify(cronJobId(mode), problems, rec == null ? null : str(rec.get("run_start_et")));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("mode", rec == null ? "" : str(rec.get("mode")));
        ok.put("coverage", rec == null ? 0 : asList(rec.get("checked")).size());
        ok.put("cache_untouched", true);
        System.out.println(Json.dumps(ok));
    }

    static Map<String, Object> readLastRun() {
        try {
            return asMap(Json.parse(readFile(lastRunFile())));
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // --self-test: failover chain (shared SelfTest helpers)
    // =====================================================================

    static void selfTest() {
        // 1. Primary (CNBC) failure is detected -> fallback triggers.
        ExecResult cnbcFail = new ExecResult();
        cnbcFail.exit = 2;
        cnbcFail.stdout = "{\"ok\":false,\"asof\":\"2026-09-28 01:00 ET\",\"source\":\"cnbc-batch\","
                + "\"quotes\":{},\"errors\":[\"NVDA\"]}";
        FetchOut f = parseFetch(cnbcFail);
        SelfTest.check("cnbc primary failure detected (ok=false)", !f.ok);

        // 2. Yahoo failover output is accepted by the pipeline unchanged.
        ExecResult yahooOk = new ExecResult();
        yahooOk.exit = 0;
        yahooOk.stdout = "{\"ok\":true,\"asof\":\"2026-09-28 01:00 ET\",\"source\":\"yahoo-chart-failover\","
                + "\"quotes\":{\"NVDA\":{\"price\":225.07,\"change\":2.8,\"change_pct\":1.2597,"
                + "\"open\":225.13,\"high\":226.94,\"low\":223.13,\"previous_close\":222.27,"
                + "\"session_date\":\"2026-09-26\",\"currency\":\"USD\"}},\"errors\":[]}";
        FetchOut h = parseFetch(yahooOk);
        SelfTest.check("yahoo failover doc accepted", h.ok);
        SelfTest.checkEq("yahoo source recorded", "yahoo-chart-failover", h.source);
        SelfTest.check("yahoo quote parsed with change_pct",
                h.quotes.containsKey("NVDA") && h.quotes.get("NVDA").get("change_pct") != null);

        // 3. Chain priority via the real firstOk(): first ok wins, in list order.
        FetchOut primary = new FetchOut(); primary.ok = true; primary.source = "cnbc-batch";
        FetchOut secondary = new FetchOut(); secondary.ok = true; secondary.source = "cnbc-batch-py";
        FetchOut tertiary = new FetchOut(); tertiary.ok = true; tertiary.source = "yahoo-chart-failover";
        SelfTest.checkEq("primary ok -> primary wins",
                "cnbc-batch", firstOk(List.of(primary, secondary, tertiary)).source);
        FetchOut pFail = new FetchOut(); pFail.ok = false; pFail.source = "cnbc-batch";
        SelfTest.checkEq("primary fails -> secondary wins",
                "cnbc-batch-py", firstOk(List.of(pFail, secondary, tertiary)).source);
        FetchOut sFail = new FetchOut(); sFail.ok = false; sFail.source = "cnbc-batch-py";
        SelfTest.checkEq("primary+secondary fail -> yahoo wins (fallback triggers)",
                "yahoo-chart-failover", firstOk(List.of(pFail, sFail, tertiary)).source);

        // 4. All fail -> last returned (carries the combined error); nothing fabricated.
        FetchOut yFail = new FetchOut(); yFail.ok = false; yFail.source = "yahoo-chart-failover";
        FetchOut last = firstOk(List.of(pFail, sFail, yFail));
        SelfTest.check("all fail -> last returned", last == yFail);
        SelfTest.check("all fail -> no quotes fabricated", last.quotes.isEmpty());

        SelfTest.finish();
    }

    // =====================================================================
    // Small utilities
    // =====================================================================

    static class ExecResult {
        int exit = -1;
        String stdout = "";
        String stderr = "";
        boolean timedOut = false;
    }

    static ExecResult exec(List<String> cmd, long timeoutMs) {
        ExecResult r = new ExecResult();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LC_ALL", "C.UTF-8");
            pb.redirectErrorStream(false);
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t1 = new Thread(() -> { try { p.getInputStream().transferTo(out); } catch (IOException ignored) { } });
            t1.start();
            Thread t2 = new Thread(() -> { try { p.getErrorStream().transferTo(err); } catch (IOException ignored) { } });
            t2.start();
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            t1.join(5000);
            t2.join(5000);
            if (!done) {
                p.destroyForcibly();
                r.timedOut = true;
                return r;
            }
            r.exit = p.exitValue();
            r.stdout = out.toString(StandardCharsets.UTF_8);
            r.stderr = err.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            r.stderr = e.toString();
        }
        return r;
    }

    static String readFile(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    static void atomicWrite(String path, String content) throws IOException {
        Path target = Paths.get(path);
        Files.createDirectories(target.getParent());
        Path tmp = Paths.get(path + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static long fileMtime(String path) {
        try {
            return Files.getLastModifiedTime(Paths.get(path)).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    static String etNow() {
        return ZonedDateTime.now(ET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    static void fail(String jobMode, String msg) {
        System.err.println("PIPELINE FAILED (drop-alerts/" + jobMode + "): " + msg);
        System.out.println(Json.dumps(Map.of("status", "failed", "mode", jobMode, "error", msg)));
        System.exit(1);
    }

    /** The two drop-alert crons share this pipeline; the outcome log is per cron. */
    static String cronJobId(String jobMode) {
        if ("off-hours".equals(jobMode)) return "stock-drop-alerts-off-hours";
        return "stock-drop-alerts-meta-nflx-sofi"; // market-hours (default)
    }

    /**
     * --verify failure path: fail LOUDLY and record the failure in the shared
     * outcome log (the task requires verification failures to be loud AND
     * recorded). PIPELINE_OUTCOME_DIR overrides the log directory in tests;
     * production uses ~/workspace/scheduler_state/job_outcomes. (Never Java
     * user.home — it resolves to /root in this sandbox; $HOME is the real home.)
     */
    static void failVerify(String jobId, List<String> problems, String runStartEt) {
        String dir = System.getenv("PIPELINE_OUTCOME_DIR");
        if (dir == null || dir.isEmpty())
            dir = System.getenv("HOME") + "/workspace/scheduler_state/job_outcomes";
        try {
            Files.createDirectories(Paths.get(dir));
            String stamp;
            if (runStartEt != null && runStartEt.length() >= 16) {
                stamp = runStartEt.substring(0, 10) + " " + runStartEt.substring(11, 16) + " ET";
            } else {
                stamp = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " ET";
            }
            String line = stamp + " | " + jobId + " | " + stamp.substring(0, 10) + " | "
                    + stamp.substring(11, 16) + " ET"
                    + " | outcome=failed | delivered=no | notes=verify failed: " + problems.get(0) + "\n";
            Files.writeString(Paths.get(dir, jobId + ".log"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("WARNING: could not append to outcome log: " + e.getMessage());
        }
        System.err.println("VERIFY FAILED (" + jobId + "):");
        for (String p : problems) System.err.println("  - " + p);
        System.exit(1);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : List.of();
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(str(o)); } catch (NumberFormatException e) { return -1; }
    }

    /* =====================================================================
     * Minimal JSON reader/writer, embedded so this file runs standalone via
     * `java DropAlertPipeline.java` (single-file launch cannot link other
     * files — the same pattern BatchQuotes.java and PriceCache.java use).
     * ===================================================================== */
    static final class Json {
        private final String s;
        private int pos;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.skipWs();
            Object v = j.readValue();
            j.skipWs();
            if (j.pos != j.s.length()) throw new IllegalArgumentException("trailing characters after JSON document");
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        private Object readValue() {
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return readNumber();
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("expected '" + lit + "'");
            pos += lit.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':'");
                pos++;
                skipWs();
                m.put(key, readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' or '}'");
            }
        }

        private List<Object> readArray() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected ',' or ']'");
            }
        }

        private String readString() {
            if (s.charAt(pos) != '"') throw new IllegalArgumentException("expected string");
            pos++;
            StringBuilder b = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: b.append(e); break;
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Double readNumber() {
            int start = pos;
            while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number at position " + start);
            }
        }

        static String dumps(Object v) {
            if (v == null) return "null";
            if (v instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
            if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
            if (v instanceof Map<?, ?> m) {
                StringBuilder b = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(",");
                    first = false;
                    b.append(dumps(String.valueOf(e.getKey()))).append(":").append(dumps(e.getValue()));
                }
                return b.append("}").toString();
            }
            if (v instanceof List<?> l) {
                StringBuilder b = new StringBuilder("[");
                boolean first = true;
                for (Object e : l) {
                    if (!first) b.append(",");
                    first = false;
                    b.append(dumps(e));
                }
                return b.append("]").toString();
            }
            return dumps(String.valueOf(v));
        }
    }
}
