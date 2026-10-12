import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One-command pipeline for the "stock-watch-daily-closes" cron job
 * (weekdays 7:30 AM ET). This job is the SOLE WRITER of price_cache.json
 * and market_caps.json — no other job may write them.
 *
 * <p>Modes (pick exactly one):
 * <ul>
 *   <li>{@code --run} — full end-to-end run: market check (quiet exit when
 *       closed) → batch quote fetch (BatchQuotes.java first, Python fallback)
 *       → PriceCache --stamp → MarketCaps --refresh-cache → print a JSON
 *       delivery report on stdout.</li>
 *   <li>{@code --dry-run} — same as --run but read-only: skips the market
 *       check, fetches WITHOUT --write-cache, and never touches the cache
 *       files. Safe to run any time for testing.</li>
 *   <li>{@code --verify} — fast health check over the CURRENT real state.
 *       Prints the checks and exits non-zero naming the first problem.</li>
 * </ul>
 *
 * <p>Exit codes: 0 = success (including a quiet market-closed exit);
 * non-zero = fail loud, with the reason on stderr.
 *
 * <p>Run it with the workspace JDK, e.g.:
 * <pre>
 * export JAVA_HOME=~/workspace/java/jdk-21; export PATH=$JAVA_HOME/bin:$PATH
 * LC_ALL=C.UTF-8 java ~/workspace/stock_watch/DailyClosesPipeline.java --run
 * </pre>
 */
public class DailyClosesPipeline {

    // ---- Paths. $HOME is used on purpose: Java's user.home is /root here. ----
    static final String HOME = homeDir();
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String SW = HOME + "/workspace/stock_watch";
    static final String WATCHLIST = SW + "/watchlist.json";
    static final String RESEARCH_WATCHLIST = SW + "/research_watchlist.json";
    static final String CACHE = SW + "/price_cache.json";
    static final String CAPS = SW + "/market_caps.json";
    static final String COMPLETED = HOME + "/workspace/scheduler_state/completed_runs.json";
    static final String JOB_ID = "stock-watch-daily-closes";

    /** Run-record dir: override with PIPELINE_STATE_DIR in tests so --run never
     *  touches real state. The caches themselves are always the real files
     *  (--run writes them in production; --dry-run/--verify are read-only). */
    static String stateDir() {
        String o = System.getenv("PIPELINE_STATE_DIR");
        return (o != null && !o.isEmpty()) ? o : SW;
    }

    static String lastRunFile() { return stateDir() + "/daily_closes_last_run.json"; }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String javaBin() {
        String jh = System.getenv("JAVA_HOME");
        return (jh != null && !jh.isEmpty()) ? jh + "/bin/java" : "java";
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "--run": run(false); break;
            case "--dry-run": run(true); break;
            case "--verify": verify(); break;
            case "--self-test": selfTest(); break;
            default:
                System.err.println("Usage: java DailyClosesPipeline.java --run | --dry-run | --verify | --self-test");
                System.exit(2);
        }
    }

    // =====================================================================
    // --run / --dry-run
    // =====================================================================

    static void run(boolean dryRun) throws Exception {
        long runStart = System.currentTimeMillis();
        List<String> tickers = readTickers();          // portfolio: the chat report
        List<String> research = readResearchTickers(); // research-only: cache coverage

        // 1. Market check — quiet exit when closed (dry-run skips the gate so
        //    the fetch path can be tested on weekends).
        if (!dryRun) {
            ExecResult cal = exec(List.of(javaBin(), SW + "/MarketCalendar.java", "--is-open"), 60_000);
            if (cal.exit != 0) {
                String reason = "market closed (" + firstLine(cal.stdout + cal.stderr) + ")";
                writeLastRun(runStart, "quiet_exit", reason, null, null,
                        tickers.size(), research.size(), 0, false);
                System.out.println(Json.dumps(Map.of(
                        "status", "quiet_exit", "reason", reason, "job", JOB_ID)));
                return; // exit 0: a quiet exit is a successful run, not a failure
            }
        }

        // 2. Batch fetch. Java first (single HTTP request for portfolio +
        //    research tickers), Python fallback for the sandbox egress-proxy quirk.
        FetchOut fetch = fetchQuotes(!dryRun);
        if (!fetch.ok) {
            fail("quote fetch failed via both BatchQuotes.java and batch_quotes.py.bak: " + fetch.error);
        }

        // 3. Stamp every cache entry with its own source + fetched_at.
        // 4. Refresh the market-cap cache (this job is its sole writer too).
        //    Dry-run is read-only: both write-producing commands are skipped.
        ExecResult stamp = null, caps = null;
        if (!dryRun) {
            stamp = exec(List.of(javaBin(), SW + "/PriceCache.java", "--stamp"), 60_000);
            caps = exec(List.of(javaBin(), SW + "/MarketCaps.java", "--refresh-cache"), 120_000);
        }

        List<String> missing = new ArrayList<>(tickers);
        missing.removeAll(fetch.quotes.keySet());
        List<String> researchFetched = new ArrayList<>(research);
        researchFetched.retainAll(fetch.quotes.keySet());

        // Dry-run never writes: no cache changes, no run record.
        if (!dryRun)
            writeLastRun(runStart, "ok", null, fetch.asof, fetch.source,
                    tickers.size(), research.size(), fetch.quotes.size(), true);

        // 5. Delivery report — the cron body copies these exact digits.
        //    The chat report is portfolio-only; research tickers get cache
        //    coverage (and a fetched count here) but no report rows.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", dryRun ? "dry_run" : "ok");
        out.put("job", JOB_ID);
        out.put("asof", fetch.asof);
        out.put("quotes_source", fetch.source);
        out.put("stamp", dryRun ? "skipped (dry-run)" : firstLine(stamp.stdout));
        out.put("market_caps", dryRun ? "skipped (dry-run)" : firstLine(caps.stdout));
        out.put("research_tickers", research.size());
        out.put("research_fetched", researchFetched.size());
        List<Object> prices = new ArrayList<>();
        for (String t : tickers) {
            Map<String, Object> q = fetch.quotes.get(t);
            if (q == null) continue; // agent may browser-fallback the missing ones
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", t);
            row.put("price", q.get("price"));
            row.put("change", q.get("change"));
            row.put("change_pct", q.get("change_pct"));
            row.put("session_date", q.get("session_date"));
            prices.add(row);
        }
        out.put("prices", prices);
        out.put("missing_tickers", missing);
        System.out.println(Json.dumps(out));
    }

    /** One batch HTTP request for the portfolio + research tickers; never writes the cache unless asked. */
    static FetchOut fetchQuotes(boolean writeCache) {
        List<String> javaCmd = new ArrayList<>(List.of(javaBin(), SW + "/BatchQuotes.java"));
        if (writeCache) javaCmd.add("--write-cache");
        ExecResult r = exec(javaCmd, 180_000);
        FetchOut f = parseFetch(r);
        if (f.ok) return f;
        // Python fallback (same flags) — works around the Java HTTPS egress block.
        List<String> pyCmd = new ArrayList<>(List.of("python3", SW + "/batch_quotes.py.bak"));
        if (writeCache) pyCmd.add("--write-cache");
        ExecResult p = exec(pyCmd, 180_000);
        FetchOut g = parseFetch(p);
        if (!g.ok) g.error = "java: " + f.error + " | python: " + g.error;
        return g;
    }

    static FetchOut parseFetch(ExecResult r) {
        FetchOut f = new FetchOut();
        if (r.timedOut) { f.error = "timed out"; return f; }
        if (r.exit != 0 && r.exit != 2) { f.error = "exit=" + r.exit + " stderr=" + firstLine(r.stderr); return f; }
        Object root;
        try {
            root = Json.parse(r.stdout);
        } catch (Exception e) {
            f.error = "unparseable output: " + e.getMessage();
            return f;
        }
        Map<String, Object> doc = asMap(root);
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

    static List<String> readTickers() throws IOException {
        Map<String, Object> wl = asMap(Json.parse(readFile(WATCHLIST)));
        List<String> tickers = new ArrayList<>();
        for (Object t : asList(wl.get("tickers"))) tickers.add(String.valueOf(t));
        if (tickers.isEmpty()) throw new IllegalStateException("watchlist.json has no tickers");
        return tickers;
    }

    /** Research-only tickers (researched but not owned): extra cache coverage,
     *  never in the portfolio chat report. Missing file = none; a present but
     *  unparseable file still fails loud. */
    static List<String> readResearchTickers() throws IOException {
        return readResearchTickers(RESEARCH_WATCHLIST);
    }

    static List<String> readResearchTickers(String path) throws IOException {
        if (!Files.exists(Paths.get(path))) return new ArrayList<>();
        Map<String, Object> wl = asMap(Json.parse(readFile(path)));
        List<String> tickers = new ArrayList<>();
        for (Object t : asList(wl.get("tickers"))) tickers.add(String.valueOf(t));
        return tickers;
    }

    /** Self-test: run with --class-path ~/workspace/java-shared/classes. */
    static void selfTest() throws IOException {
        Path dir = Files.createTempDirectory("dcp-test");
        Path rf = dir.resolve("research_watchlist.json");
        // Missing research file = no research tickers (portfolio-only mode).
        SelfTest.checkEq("research-missing-is-empty", List.of(),
                readResearchTickers(dir.resolve("nope.json").toString()));
        // Present file is honored verbatim.
        Files.writeString(rf, "{\"tickers\": [\"AAPL\", \"TSLA\"]}");
        SelfTest.checkEq("research-present-honored", List.of("AAPL", "TSLA"),
                readResearchTickers(rf.toString()));
        // Live research file parses (currently empty) and never leaks into
        // the portfolio report universe.
        List<String> live = readResearchTickers();
        List<String> portfolio = readTickers();
        SelfTest.check("research-live-no-portfolio-overlap",
                live.stream().noneMatch(portfolio::contains));
        SelfTest.finish();
    }

    static void writeLastRun(long runStart, String status, String reason, String asof,
                             String source, int watchlistSize, int researchSize,
                             int fetched, boolean wroteCaches) throws IOException {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("job", JOB_ID);
        rec.put("run_start_epoch_ms", runStart);
        rec.put("run_start_et", etNow());
        rec.put("status", status);
        if (reason != null) rec.put("reason", reason);
        if (asof != null) rec.put("fetch_asof", asof);
        if (source != null) rec.put("fetch_source", source);
        rec.put("watchlist_size", watchlistSize);
        rec.put("research_size", researchSize);
        rec.put("tickers_fetched", fetched);
        rec.put("wrote_caches", wroteCaches);
        try {
            rec.put("cache_mtime_ms", Files.getLastModifiedTime(Paths.get(CACHE)).toMillis());
            rec.put("caps_mtime_ms", Files.getLastModifiedTime(Paths.get(CAPS)).toMillis());
        } catch (IOException ignored) { }
        atomicWrite(lastRunFile(), Json.dumps(rec));
    }

    // =====================================================================
    // --verify : fast health check over the current real state
    // =====================================================================

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        List<String> tickers = readTickers();           // portfolio: report + cache
        List<String> research = readResearchTickers();  // research-only: cache only
        // Cache checks cover the union; the chat report stays portfolio-only.
        List<String> cacheTickers = new ArrayList<>(tickers);
        for (String r : research) if (!cacheTickers.contains(r)) cacheTickers.add(r);
        // Shared last-run check: a missing record is INFO (not yet
        // initialized) unless completed_runs.json proves the job ran before
        // (then it is a regression). Record-field checks run only on PRESENT.
        Verify.LastRunState lrs =
                Verify.checkLastRun(JOB_ID, Paths.get(lastRunFile()), COMPLETED, infos, problems);
        Map<String, Object> rec =
                (lrs == Verify.LastRunState.PRESENT) ? readLastRun() : null;
        if (lrs == Verify.LastRunState.PRESENT && rec == null)
            problems.add("last-run record at " + lastRunFile()
                    + " exists but does not parse — --run did not write a valid record");

        Map<String, Object> cache = asMap(Json.parse(readFile(CACHE)));
        // Both shapes are accepted, matching PriceCache.java: {"prices": {...}}
        // (current writers) and {"quotes": {...}} (older files).
        Map<String, Object> quotes = asMap(cache.get("prices"));
        if (quotes == null) quotes = asMap(cache.get("quotes"));
        if (quotes == null) quotes = Map.of();

        // 1. Every portfolio + research ticker present in the cache,
        //    each with price + provenance.
        for (String t : cacheTickers) {
            Map<String, Object> q = asMap(quotes.get(t));
            if (q == null) { problems.add("price_cache.json missing ticker " + t); continue; }
            if (q.get("price") == null) problems.add("price_cache.json " + t + " has no price");
            if (blank(str(q.get("source")))) problems.add("price_cache.json " + t + " missing source (provenance)");
            if (blank(str(q.get("fetched_at")))) problems.add("price_cache.json " + t + " missing fetched_at (provenance)");
        }

        // 2. The cache must hold a real trading session: every quote's
        //    session_date must parse to a weekday, and written_date must be
        //    recent. (A manual weekend rewrite of Friday's session is
        //    legitimate — the session date is what makes it a trading day.)
        String written = str(cache.get("written_date"));
        LocalDate wd = parseDate(written);
        if (wd == null) {
            problems.add("price_cache.json written_date unparseable: '" + written + "'");
        } else if (wd.isBefore(LocalDate.now(ET).minusDays(5))) {
            problems.add("price_cache.json written_date " + wd + " is older than 5 days (stale)");
        }
        int sessioned = 0;
        for (String t : cacheTickers) {
            Map<String, Object> q = asMap(quotes.get(t));
            if (q == null) continue;
            LocalDate sd = parseSessionDate(str(q.get("session_date")));
            if (sd == null) {
                problems.add("price_cache.json " + t + " session_date unparseable: '" + q.get("session_date") + "'");
                continue;
            }
            sessioned++;
            switch (sd.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> problems.add("price_cache.json " + t
                        + " session_date " + sd + " is a weekend (not a trading session)");
                default -> { }
            }
        }
        if (sessioned == 0) problems.add("price_cache.json has no parseable session dates");

        // 3. Market-cap cache: all tickers present and fresh.
        Map<String, Object> capsDoc = asMap(Json.parse(readFile(CAPS)));
        Map<String, Object> caps = asMap(capsDoc.get("caps"));
        if (caps == null) {
            problems.add("market_caps.json has no caps map");
        } else {
            for (String t : cacheTickers)
                if (!caps.containsKey(t)) problems.add("market_caps.json missing ticker " + t);
            LocalDate cd = parseDate(str(capsDoc.get("written_date")));
            if (cd == null) problems.add("market_caps.json written_date unparseable");
            else if (cd.isBefore(LocalDate.now(ET).minusDays(5)))
                problems.add("market_caps.json written_date " + cd + " is older than 5 days (stale)");
        }

        // 4. Sole-writer sanity: the cache must have been (re)written by this
        //    job's own run — its mtime falls inside this run's window — which
        //    no other writer can fake without also faking the run record.
        if (rec != null && "ok".equals(rec.get("status"))) {
            long runStart = num(rec.get("run_start_epoch_ms"));
            long mtime = fileMtime(CACHE);
            if (mtime < runStart - 120_000)
                problems.add("price_cache.json mtime predates this run's start: another writer may have touched it"
                        + " (single-writer rule: only " + JOB_ID + " may write)");
            if (!Boolean.TRUE.equals(rec.get("wrote_caches")))
                problems.add("last run record says caches were not written");
        } else if (rec != null && "quiet_exit".equals(rec.get("status"))) {
            long runStart = num(rec.get("run_start_epoch_ms"));
            long mtime = fileMtime(CACHE);
            if (mtime >= runStart)
                problems.add("quiet-exit run still rewrote price_cache.json (market-closed runs must not write)");
        }
        // No else: a missing record was already handled by the shared
        // checkLastRun above (INFO when not initialized, failure on regression).

        if (!problems.isEmpty())
            failVerify(JOB_ID, problems, rec == null ? null : str(rec.get("run_start_et")));
        for (String info : infos) System.out.println("INFO: " + info);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("job", JOB_ID);
        ok.put("tickers_checked", tickers.size());
        ok.put("research_checked", research.size());
        ok.put("cache_written_date", written);
        System.out.println(Json.dumps(ok));
    }

    /**
     * --verify failure path: fail LOUDLY and record the failure in the shared
     * outcome log (the task requires verification failures to be loud AND
     * recorded). The agent's own outcome line was written before --verify ran;
     * this appends the failure so a broken verification can never pass
     * silently. PIPELINE_OUTCOME_DIR overrides the log directory in tests;
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

    static Map<String, Object> readLastRun() {
        try {
            return asMap(Json.parse(readFile(lastRunFile())));
        } catch (Exception e) {
            return null;
        }
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

    /** Run a command, capturing stdout and stderr separately. */
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

    /** Write-temp-then-move so a crash never leaves a half-written JSON file. */
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

    static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.strip().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    /** Batch-quote session dates look like "09/25/26 EDT". */
    static LocalDate parseSessionDate(String s) {
        try {
            String d = s.strip().split(" ")[0];
            return LocalDate.parse(d, DateTimeFormatter.ofPattern("MM/dd/yy"));
        } catch (Exception e) {
            return null;
        }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }

    static void fail(String msg) {
        System.err.println("PIPELINE FAILED (" + JOB_ID + "): " + msg);
        System.out.println(Json.dumps(Map.of("status", "failed", "error", msg, "job", JOB_ID)));
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
     * `java DailyClosesPipeline.java` (single-file launch cannot link other
     * files — the same pattern BatchQuotes.java and PriceCache.java use).
     * Objects -> LinkedHashMap (insertion order kept), arrays -> ArrayList,
     * numbers -> Double, plus strings, booleans, null.
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
            pos++; // {
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
            pos++; // [
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

        /** Serialize Maps, Lists, Strings, Numbers, Booleans, null. */
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
