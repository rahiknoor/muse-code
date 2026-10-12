// GapWatchReplay.java — audit gap D2.
//
// Replays the dcf-gap-watch alerting logic against historical daily closes
// and diffs the result against the actual alert log, catching:
//   - MISSED crossings: the logic says "alert" but no alert was recorded (FAIL)
//   - FALSE alerts:    an alert was recorded but the logic shows no crossing (WARN)
//
// The threshold/buy-zone logic mirrors DcfGapPipeline.java exactly:
//   MoS = (dcf - price) / dcf; skip dcf <= 0 (a gap is meaningless there)
//   and tickers with no price (never invented).
//   Alert on a fresh required-margin crossing (no prior alert, or the prior
//   alerted MoS was below the required margin), or a 10pp+ widening since
//   the last alert. Required margins come from margin_requirements.json
//   (default 20%); the state map is replay-local and starts empty, exactly
//   as the real job starts with a missing dcf_gap_state.json.
//
// The alert log replayed is ~/workspace/scheduler_state/job_outcomes/dcf-gap-watch.log
// (succeeded/delivered=yes lines carrying "N MoS alerts delivered (T1 T2 ...)").
//
// Coverage caveats (also printed as INFO on --verify):
//   * Replay window is bounded to the last 60 days (flag: --since N).
//   * The job only started on 2026-09-27; MISSED crossings are evaluated
//     only within the alert log's coverage window. Replay alerts that
//     predate the job are reported as INFO (pre-job history), never FAIL.
//   * The real job uses the price CACHE (a morning snapshot per run), while
//     this replay uses daily closes from Yahoo via the shared
//     HistoricalPrice helper. Source/timing differences can explain
//     borderline divergences — hence FALSE is WARN, not FAIL.
//   * DCF values are history-aware: per-ticker value timelines are rebuilt
//     from dcf_csv/proposals/changelog.md (APPROVED ours/TICKER: old ->
//     new lines), so a DCF that changed mid-window (e.g. QXO $5.04 ->
//     $20.97 on 2026-10-03) is applied at its real effective dates. The
//     old side of each ticker's earliest transition is pinned to cover all
//     pre-changelog dates (a documented backward extension of a recorded
//     value, never an invented number).
//   * Only weekday (Mon-Fri) runs are simulated, matching the cron schedule.
//   * Weekday dates that fall on market holidays reuse the next trading
//     day's close (HistoricalPrice walk-forward behavior).
//
// Modes: --self-test | --verify [--since N]
// --verify ends via Finding.split -> Verify.report (exit 1 on any FAIL).
// Audit-write guard: REPORT only — this tool writes nothing.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GapWatchReplay {

    static final String HOME = home();
    static final String DCF_PRIMARY = HOME + "/workspace/dcf_values.json";
    static final String DCF_FALLBACK = HOME + "/workspace/dcf_csv/our_dcf.json";
    static final String MARGIN_REQ = HOME + "/workspace/dcf_csv/margin_requirements.json";
    static final String ALERT_LOG = HOME + "/workspace/scheduler_state/job_outcomes/dcf-gap-watch.log";
    static final String HIST_PRICE = HOME + "/workspace/stock_watch/HistoricalPrice.java";
    static final String CHECK_ID = "D2";

    // Mirrored from DcfGapPipeline: re-alert when MoS widened 10pp+ since last alert.
    static final double WIDEN_TRIGGER = 0.10;
    static final double DEFAULT_MARGIN = 0.20;
    static final int DEFAULT_SINCE_DAYS = 60;
    // Match window (weekdays) between a replay alert and a logged alert.
    static final int MATCH_TOLERANCE_DAYS = 2;

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** One alert: ticker crossed into (or widened inside) its buy zone on date. */
    static final class Alert {
        final LocalDate date;
        final String ticker;
        final double mos;
        Alert(LocalDate date, String ticker, double mos) {
            this.date = date; this.ticker = ticker; this.mos = mos;
        }
    }

    // =====================================================================
    // Replay (pure: prices + dcf + margin requirements in, alerts out)
    // =====================================================================

    /** Mirror of DcfGapPipeline's alerting step over the given weekday dates
     *  (ascending). prices: ticker -> (date -> close).
     *
     *  <p>{@code stateReset}: the replay state is cleared on the first day
     *  at or after this date. The real job's state starts empty on its first
     *  run — so for a faithful comparison the replay state must also start
     *  empty when the alert log's coverage begins (the log's first line),
     *  not at the arbitrary window start. Alerts computed before the reset
     *  are kept for the pre-job INFO history; only post-reset alerts are
     *  diffed against the log. Null = no reset (no log coverage). */
    static List<Alert> simulate(Map<String, Double> dcf,
                                Map<String, TreeMap<LocalDate, Double>> dcfHist,
                                Map<String, Double> marginReq,
                                Map<String, Map<LocalDate, Double>> prices,
                                List<LocalDate> weekdays,
                                LocalDate stateReset) {
        List<Alert> alerts = new ArrayList<>();
        Map<String, Double> state = new LinkedHashMap<>();
        boolean resetDone = stateReset == null;
        for (LocalDate day : weekdays) {
            if (!resetDone && !day.isBefore(stateReset)) {
                state.clear();          // mirror the job's initial empty state
                resetDone = true;
            }
            for (Map.Entry<String, Double> e : dcf.entrySet()) {
                String t = e.getKey();
                double d = dcfOn(dcfHist, dcf, t, day);
                if (d <= 0) continue;                       // pipeline skips dcf<=0
                Map<LocalDate, Double> series = prices.get(t);
                if (series == null) continue;
                Double close = series.get(day);
                if (close == null) continue;                // never invent a price
                double mos = (d - close) / d;
                double req = marginReq.getOrDefault(t, DEFAULT_MARGIN);
                if (mos < req) continue;
                Double last = state.get(t);
                boolean freshCross = last == null || last < req;
                boolean widened = last != null && (mos - last) >= WIDEN_TRIGGER;
                if (freshCross || widened) {
                    alerts.add(new Alert(day, t, mos));
                    state.put(t, mos);
                }
            }
        }
        return alerts;
    }

    // =====================================================================
    // Alert-log parsing
    // =====================================================================

    static final Pattern LOG_LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}) \\d{2}:\\d{2} ET \\| dcf-gap-watch \\|.*?\\|"
                    + " outcome=(\\w+) \\| delivered=(\\w+) \\| notes=(.*)$");
    static final Pattern ALERTS_DELIVERED = Pattern.compile(
            "(\\d+) MoS alerts? delivered \\(([A-Za-z. ]+)\\)");
    // Newer job notes formats (2026-10-05+): the MoS alerts no longer go
    // through the "N MoS alerts delivered (TICKERS)" phrasing, so the replay
    // must recognize them or it hallucinates missed alerts. Added 2026-10-11
    // after a false QXO "missed alert" on 2026-10-05.
    static final Pattern NEW_BUY_ZONE = Pattern.compile(
            "([A-Za-z][A-Za-z.]*) newly in buy zone");
    static final Pattern BUY_ZONE_ALERT = Pattern.compile(
            "([A-Za-z][A-Za-z.]*) buy-zone alert");

    /** Parsed log: alert events plus the coverage window (first/last job line). */
    static final class LogReplay {
        final List<Alert> loggedAlerts = new ArrayList<>();
        LocalDate firstLine = null;
        LocalDate lastLine = null;
    }

    static LogReplay parseAlertLog(String text) {
        LogReplay out = new LogReplay();
        for (String line : text.split("\n")) {
            Matcher m = LOG_LINE.matcher(line.strip());
            if (!m.matches()) continue;
            LocalDate date = LocalDate.parse(m.group(1), DAY);
            if (out.firstLine == null || date.isBefore(out.firstLine)) out.firstLine = date;
            if (out.lastLine == null || date.isAfter(out.lastLine)) out.lastLine = date;
            if (!"succeeded".equals(m.group(2)) || !"yes".equals(m.group(3))) continue;
            Matcher a = ALERTS_DELIVERED.matcher(m.group(4));
            if (a.find()) {
                for (String t : a.group(2).trim().split("\\s+")) {
                    if (!t.isBlank()) out.loggedAlerts.add(new Alert(date, t, Double.NaN));
                }
            }
            // Newer formats: "QXO newly in buy zone (MoS 42.49%)" and
            // "QCOM buy-zone alert + MSFT thesis-move alert ...".
            Matcher n1 = NEW_BUY_ZONE.matcher(m.group(4));
            while (n1.find()) {
                out.loggedAlerts.add(new Alert(date, n1.group(1), Double.NaN));
            }
            Matcher n2 = BUY_ZONE_ALERT.matcher(m.group(4));
            while (n2.find()) {
                out.loggedAlerts.add(new Alert(date, n2.group(1), Double.NaN));
            }
        }
        return out;
    }

    // =====================================================================
    // Diff: replay vs log
    // =====================================================================

    /** True when two alerts are for the same ticker within the tolerance window. */
    static boolean matches(Alert a, Alert b) {
        if (!a.ticker.equals(b.ticker)) return false;
        long gap = Math.abs(a.date.toEpochDay() - b.date.toEpochDay());
        return gap <= MATCH_TOLERANCE_DAYS;
    }

    static List<Finding> compare(List<Alert> replay, LogReplay log, LocalDate windowEnd) {
        List<Finding> findings = new ArrayList<>();
        LocalDate coverageStart = log.firstLine;

        for (Alert r : replay) {
            if (coverageStart != null && r.date.isBefore(coverageStart)) {
                findings.add(Finding.info(CHECK_ID, r.ticker, "pre_job_history",
                        "alert log starts " + coverageStart, "replay alert " + r.date,
                        ALERT_LOG,
                        "none — the job did not exist yet; not a miss"));
                continue;
            }
            if (coverageStart == null) {
                findings.add(Finding.info(CHECK_ID, r.ticker, "no_log_coverage",
                        "no alert log to compare against", "replay alert " + r.date,
                        ALERT_LOG, "none — replay only; restore the log to enable diffing"));
                continue;
            }
            boolean seen = log.loggedAlerts.stream().anyMatch(l -> matches(r, l));
            if (seen) continue;
            if (!r.date.isBefore(windowEnd)) {
                // The replay's last simulated day: the next scheduled job run
                // has not happened yet, so a crossing there is not a miss —
                // the coming run may still catch it.
                findings.add(Finding.info(CHECK_ID, r.ticker, "pending_next_run",
                        "next scheduled run may still alert",
                        "replay alert " + r.date + " (MoS "
                                + String.format("%.4f", r.mos) + ") with no log entry yet",
                        ALERT_LOG,
                        "re-run this verifier after the next scheduled gap-watch run"));
                continue;
            }
            findings.add(Finding.fail(CHECK_ID, r.ticker, "missed_gap_alert",
                    "alert on " + r.date + " (replay MoS " + String.format("%.4f", r.mos) + ")",
                    "no alert in " + ALERT_LOG + " within +/-" + MATCH_TOLERANCE_DAYS + " weekdays",
                    ALERT_LOG + " (coverage " + log.firstLine + " .. " + log.lastLine + ")",
                    "investigate why the gap-watch run did not alert; the replay"
                            + " state starts empty like the real job's first run"));
        }
        for (Alert l : log.loggedAlerts) {
            boolean seen = replay.stream().anyMatch(r -> matches(r, l));
            if (!seen) {
                findings.add(Finding.warn(CHECK_ID, l.ticker, "false_gap_alert",
                        "replay shows a buy-zone crossing near " + l.date,
                        "alert recorded " + l.date + " with no replay crossing",
                        ALERT_LOG,
                        "MANUAL REVIEW: likely a price-source difference (the real job"
                                + " uses the price-cache snapshot, the replay uses daily"
                                + " closes) or a DCF move inside the window; confirm from"
                                + " the run record before dismissing"));
            }
        }
        return findings;
    }

    // =====================================================================
    // --verify: real inputs
    // =====================================================================

    static void verify(int sinceDays) throws Exception {
        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        // 1. DCF backend (mirror the pipeline: primary then fallback).
        String dcfPath = Files.exists(Paths.get(DCF_PRIMARY)) ? DCF_PRIMARY : DCF_FALLBACK;
        Map<String, Double> dcf = readDcf(dcfPath);
        infos.add("DCF backend: " + dcfPath + " (" + dcf.size() + " tickers)");

        // 1b. DCF value history from the proposals changelog (history-aware
        // replay: each date uses the value in effect then).
        Map<String, TreeMap<LocalDate, Double>> dcfHist = readDcfHistory();
        infos.add("DCF history: " + dcfHist.size() + " ticker(s) with changelog timelines");

        // 2. Margin requirements (mirror the pipeline: default 20%).
        Map<String, Double> marginReq = readMarginReq();

        // 3. Alert log.
        LogReplay log;
        if (Files.exists(Paths.get(ALERT_LOG))) {
            log = parseAlertLog(Files.readString(Paths.get(ALERT_LOG), StandardCharsets.UTF_8));
            infos.add("alert log: " + ALERT_LOG + " — coverage "
                    + log.firstLine + " .. " + log.lastLine + ", "
                    + log.loggedAlerts.size() + " alert event(s)");
        } else {
            log = new LogReplay();
            infos.add("alert log missing at " + ALERT_LOG
                    + " — replay runs, but MISSED analysis is skipped (no coverage)");
        }

        // 4. Weekday window (last N days, ending yesterday — today's 08:46
        //    run has not fired at the time this verifier runs pre-market).
        LocalDate end = LocalDate.now().minusDays(1);
        LocalDate start = end.minusDays(sinceDays - 1);
        List<LocalDate> weekdays = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY
                    && d.getDayOfWeek() != DayOfWeek.SUNDAY) weekdays.add(d);
        }
        infos.add("replay window: " + start + " .. " + end
                + " (" + weekdays.size() + " weekdays)");

        // 5. Historical closes via the shared HistoricalPrice helper — one
        //    call per ticker fetches the whole range at once.
        Map<String, Map<LocalDate, Double>> prices = new LinkedHashMap<>();
        List<String> dateArgs = new ArrayList<>();
        for (LocalDate d : weekdays) dateArgs.add(d.format(DAY));
        for (String t : dcf.keySet()) {
            if (dcf.get(t) <= 0) continue;
            Map<LocalDate, Double> series = fetchCloses(t, dateArgs);
            if (!series.isEmpty()) prices.put(t, series);
        }
        infos.add("price series fetched: " + prices.size() + " ticker(s) via HistoricalPrice");

        // 6. Simulate, diff, report. The replay state is reset to empty at the
        //    log's coverage start (mirror of the job's initial empty state);
        //    windowEnd bounds the MISSED check (end-of-window crossings may
        //    still be caught by the next scheduled run).
        List<Alert> replay = simulate(dcf, dcfHist, marginReq, prices, weekdays, log.firstLine);
        infos.add("replay produced " + replay.size() + " alert event(s)"
                + (log.firstLine == null ? "" : "; state reset at coverage start "
                + log.firstLine + " (mirror of the job's initial empty state)"));
        List<Finding> findings = compare(replay, log, end);
        findings.add(0, Finding.info(CHECK_ID, "-", "caveats",
                "see file header",
                "weekday-only replay; prices=daily closes (job uses cache snapshots);"
                        + " DCF history-aware via changelog; 60d bound",
                ALERT_LOG, "none"));
        Finding.split(findings, infos, failures);
        Verify.report(
                "VERIFY OK (GapWatchReplay): replay matches the alert log"
                        + " (no missed crossings, no unmatched alerts).",
                "VERIFY FAILED (GapWatchReplay): " + failures.size() + " problem(s) found:",
                infos, failures);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Double> readDcf(String path) throws Exception {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, Object> doc = (Map<String, Object>) Json.parse(
                Files.readString(Paths.get(path), StandardCharsets.UTF_8));
        Object values = doc.get("values");
        Map<String, Object> vs = values instanceof Map ? (Map<String, Object>) values : doc;
        for (Map.Entry<String, Object> e : vs.entrySet()) {
            if (e.getKey().startsWith("_")) continue;
            Object v = e.getValue();
            if (v instanceof Number n) out.put(e.getKey(), n.doubleValue());
            else if (v instanceof Map m && ((Map<String, Object>) m).get("value") instanceof Number n2)
                out.put(e.getKey(), n2.doubleValue());
        }
        return out;
    }

    static final String CHANGELOG = HOME + "/workspace/dcf_csv/proposals/changelog.md";
    static final Pattern CHANGELOG_DATE = Pattern.compile("^## (\\d{4}-\\d{2}-\\d{2})T");
    static final Pattern CHANGELOG_APPROVED = Pattern.compile("APPROVED");
    static final Pattern CHANGELOG_VALUE = Pattern.compile(
            "^- ours/([A-Z.]+): ([0-9.]+) -> ([0-9.]+)");

    /**
     * Rebuilds per-ticker DCF value timelines from the proposals changelog.
     * Returns ticker -> (date -> value in effect from that date). Only
     * APPROVED proposals move the blessed value; REJECTED lines are ignored.
     * A date with no earlier entry falls back to the current backend value
     * at lookup time (see dcfOn).
     */
    static Map<String, TreeMap<LocalDate, Double>> readDcfHistory() throws Exception {
        Map<String, TreeMap<LocalDate, Double>> out = new LinkedHashMap<>();
        Path p = Paths.get(CHANGELOG);
        if (!Files.exists(p)) return out;
        LocalDate curDate = null;
        boolean approved = false;
        // First-seen old value per ticker: the "old" side of the earliest
        // transition is the value in effect before the changelog's first
        // change for that ticker. It is pinned at LocalDate.MIN so dcfOn
        // uses a recorded historical value (not the current one) for all
        // pre-changelog dates. This is a documented backward extension of
        // a recorded value, never an invented number.
        Map<String, Double> firstOld = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            Matcher dm = CHANGELOG_DATE.matcher(line);
            if (dm.find()) {
                curDate = LocalDate.parse(dm.group(1));
                approved = false;
                continue;
            }
            if (line.contains("APPROVED")) { approved = true; continue; }
            if (line.contains("REJECTED")) { approved = false; continue; }
            if (approved && curDate != null) {
                Matcher vm = CHANGELOG_VALUE.matcher(line);
                if (vm.find()) {
                    String ticker = vm.group(1);
                    double ov = Double.parseDouble(vm.group(2));
                    double nv = Double.parseDouble(vm.group(3));
                    TreeMap<LocalDate, Double> tl =
                            out.computeIfAbsent(ticker, k -> new TreeMap<>());
                    tl.put(curDate, nv);
                    firstOld.putIfAbsent(ticker, ov);
                }
            }
        }
        for (Map.Entry<String, Double> e : firstOld.entrySet()) {
            out.get(e.getKey()).put(LocalDate.MIN, e.getValue());
        }
        return out;
    }

    /**
     * DCF value in effect for a ticker on a date: the latest history entry
     * on or before the date, else the current backend value (fallback).
     */
    static double dcfOn(Map<String, TreeMap<LocalDate, Double>> hist,
                        Map<String, Double> current,
                        String ticker, LocalDate date) {
        TreeMap<LocalDate, Double> tl = hist.get(ticker);
        if (tl != null) {
            Map.Entry<LocalDate, Double> e = tl.floorEntry(date);
            if (e != null) return e.getValue();
        }
        return current.getOrDefault(ticker, 0.0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Double> readMarginReq() throws Exception {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            Map<String, Object> doc = (Map<String, Object>) Json.parse(
                    Files.readString(Paths.get(MARGIN_REQ), StandardCharsets.UTF_8));
            Object tickers = doc.get("tickers");
            if (tickers instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) tickers).entrySet()) {
                    if (e.getValue() instanceof Number n) out.put(e.getKey(), n.doubleValue());
                }
            }
        } catch (Exception ignored) {
            // absent file -> pipeline default 20% for all
        }
        return out;
    }

    /** Yahoo wants BRK-B style symbols; mirror the '.' -> '-' mapping that
     *  batch_quotes.py.bak --history-events= documents (the plain --history=
     *  mode HistoricalPrice uses does not apply it, so dotted tickers would
     *  otherwise replay with no prices at all). */
    static String yahooSymbol(String ticker) {
        return ticker.replace(".", "-");
    }

    /** One HistoricalPrice call per ticker; its internal range fetch covers
     *  all requested dates in a single HTTP round-trip. */
    static Map<LocalDate, Double> fetchCloses(String ticker, List<String> dateArgs) {
        Map<LocalDate, Double> out = new TreeMap<>();
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin());
            cmd.add("--class-path");
            cmd.add(HOME + "/workspace/java-shared/classes");
            cmd.add(HIST_PRICE);
            cmd.add(yahooSymbol(ticker));
            cmd.addAll(dateArgs);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String body = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return out; }
            // {"TICKER": {"2026-08-03": {"date_used": "...", "close": 123.45}, ...}}
            Matcher m = Pattern.compile("\"(\\d{4}-\\d{2}-\\d{2})\"\\s*:\\s*\\{"
                    + "\"date_used\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"close\"\\s*:\\s*(\\d+\\.?\\d*)\\}")
                    .matcher(body);
            while (m.find()) {
                out.put(LocalDate.parse(m.group(1), DAY), Double.parseDouble(m.group(2)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String javaBin() {
        String jh = System.getenv("JAVA_HOME");
        return (jh != null && !jh.isEmpty()) ? jh + "/bin/java" : "java";
    }

    // =====================================================================
    // Self-test
    // =====================================================================

    static void selfTest() {
        LocalDate d1 = LocalDate.of(2026, 9, 21); // Mon
        LocalDate d2 = LocalDate.of(2026, 9, 22);
        LocalDate d3 = LocalDate.of(2026, 9, 23);
        List<LocalDate> days = List.of(d1, d2, d3);

        Map<String, Double> dcf = Map.of("AAA", 100.0, "CCC", 100.0, "IREN", -5.0);
        Map<String, Double> margin = Map.of();

        Map<String, Map<LocalDate, Double>> prices = new LinkedHashMap<>();
        // AAA crosses the 20% line on d2 (price 75 -> MoS 0.25) and stays in.
        prices.put("AAA", Map.of(d1, 95.0, d2, 75.0, d3, 74.0));
        // CCC crosses on d3 with no log entry -> MISSED.
        prices.put("CCC", Map.of(d1, 95.0, d2, 95.0, d3, 70.0));

        List<Alert> replay = simulate(dcf, Map.of(), margin, prices, days, null);
        SelfTest.checkEq("replay: AAA alerts once (fresh cross only)", 1L,
                replay.stream().filter(a -> a.ticker.equals("AAA")).count());
        SelfTest.checkEq("replay: CCC alerts once", 1L,
                replay.stream().filter(a -> a.ticker.equals("CCC")).count());
        SelfTest.check("replay: dcf<=0 ticker never alerts",
                replay.stream().noneMatch(a -> a.ticker.equals("IREN")));

        // 10pp widening re-alerts: drop AAA's close so MoS widens 10pp+ on d3.
        prices.put("AAA", Map.of(d1, 95.0, d2, 79.0, d3, 68.0)); // 0.21 -> 0.32
        List<Alert> replay2 = simulate(dcf, Map.of(), margin, prices, days, null);
        SelfTest.checkEq("replay: 10pp widening re-alerts", 2L,
                replay2.stream().filter(a -> a.ticker.equals("AAA")).count());

        // State reset: with reset at d2, the pre-reset d1 alert is forgotten
        // and the still-in-zone ticker re-alerts on d2 (mirror of the job's
        // initial empty state at its first run).
        prices.put("AAA", Map.of(d1, 75.0, d2, 75.0, d3, 75.0));
        List<Alert> replay3 = simulate(dcf, Map.of(), margin, prices, days, null);
        List<Alert> replay4 = simulate(dcf, Map.of(), margin, prices, days, d2);
        SelfTest.checkEq("replay: no reset — one fresh cross only", 1L,
                replay3.stream().filter(a -> a.ticker.equals("AAA")).count());
        SelfTest.checkEq("replay: reset at d2 — re-alerts after the reset", 2L,
                replay4.stream().filter(a -> a.ticker.equals("AAA")).count());
        SelfTest.check("replay: reset re-alert lands on the reset day",
                replay4.stream().anyMatch(a -> a.ticker.equals("AAA")
                        && a.date.equals(d2)));

        // History-aware DCF: DDD's DCF jumps from 50 to 100 on d2. With a
        // constant-100 replay, d1's price of 35 would alert (MoS 0.65); with
        // history, d1 uses DCF 50 (price 35 < 50, no alert — price above DCF
        // means negative MoS... actually (50-35)/50 = 0.30, still alerts).
        // Use price 45: constant-DCF MoS = (100-45)/100 = 0.55 alerts on d1;
        // history DCF 50 gives (50-45)/50 = 0.10, no alert on d1. d2 onward
        // uses 100 and alerts.
        Map<String, TreeMap<LocalDate, Double>> hist = new LinkedHashMap<>();
        TreeMap<LocalDate, Double> dddHist = new TreeMap<>();
        dddHist.put(d1, 50.0);
        dddHist.put(d2, 100.0);
        hist.put("DDD", dddHist);
        Map<String, Double> dcfH = Map.of("DDD", 100.0);
        Map<String, Map<LocalDate, Double>> pricesH = new LinkedHashMap<>();
        pricesH.put("DDD", Map.of(d1, 45.0, d2, 45.0, d3, 45.0));
        List<Alert> replayH = simulate(dcfH, hist, margin, pricesH, days, null);
        SelfTest.checkEq("replay: history-aware DCF suppresses d1 phantom alert", 1L,
                replayH.stream().filter(a -> a.ticker.equals("DDD")).count());
        SelfTest.check("replay: history-aware alert lands on d2 (post-change)",
                replayH.stream().anyMatch(a -> a.ticker.equals("DDD")
                        && a.date.equals(d2)));
        // dcfOn unit checks.
        SelfTest.checkEq("dcfOn: date before first entry falls back to current",
                100.0, dcfOn(hist, dcfH, "DDD", LocalDate.of(2026, 9, 20)));
        SelfTest.checkEq("dcfOn: exact change date uses new value",
                100.0, dcfOn(hist, dcfH, "DDD", d2));
        SelfTest.checkEq("dcfOn: unknown ticker falls back to current",
                0.0, dcfOn(hist, dcfH, "ZZZ", d1));
        // LocalDate.MIN pinning (as readDcfHistory does): the old side of
        // the earliest transition covers all pre-changelog dates.
        TreeMap<LocalDate, Double> pinned = new TreeMap<>();
        pinned.put(LocalDate.MIN, 50.0);
        pinned.put(d2, 100.0);
        Map<String, TreeMap<LocalDate, Double>> histP = Map.of("DDD", pinned);
        SelfTest.checkEq("dcfOn: pinned old value covers pre-changelog dates",
                50.0, dcfOn(histP, dcfH, "DDD", LocalDate.of(2026, 8, 5)));

        // Diff: log has AAA d2 (matched) and BBB d2 (false); coverage covers d1..d2.
        // windowEnd d4 sits after the last replay alert, so CCC's d3 alert is
        // a genuine MISSED, not a pending-next-run.
        LocalDate d4 = LocalDate.of(2026, 9, 24);
        String logText = "2026-09-21 08:46 ET | dcf-gap-watch | 2026-09-21 | 08:46 ET"
                + " | outcome=succeeded | delivered=yes"
                + " | notes=1 MoS alerts delivered (BBB)\n"
                + "2026-09-22 08:46 ET | dcf-gap-watch | 2026-09-22 | 08:46 ET"
                + " | outcome=succeeded | delivered=yes"
                + " | notes=2 MoS alerts delivered (AAA BBB)\n";
        LogReplay log = parseAlertLog(logText);
        SelfTest.checkEq("log parse: 3 alert events", 3, log.loggedAlerts.size());
        SelfTest.checkEq("log parse: coverage start", d1, log.firstLine);

        // Newer job notes formats must parse too (2026-10-11 fix).
        String logText2 = "2026-10-05 08:46 ET | dcf-gap-watch | 2026-10-05 | 08:46 ET"
                + " | outcome=succeeded | delivered=yes"
                + " | notes=QXO newly in buy zone (MoS 42.49%); 4 thesis alerts (CRM, V)\n"
                + "2026-10-06 08:46 ET | dcf-gap-watch | 2026-10-06 | 08:46 ET"
                + " | outcome=succeeded | delivered=yes"
                + " | notes=gap alert JPM newly in buy zone (MoS 20.29% vs 20% req); thesis quiet\n"
                + "2026-10-09 08:46 ET | dcf-gap-watch | 2026-10-09 | 08:46 ET"
                + " | outcome=succeeded | delivered=yes"
                + " | notes=QCOM buy-zone alert + MSFT thesis-move alert + 2 proposal nudges\n";
        LogReplay log2 = parseAlertLog(logText2);
        SelfTest.checkEq("log parse: 3 new-format buy-zone alerts", 3, log2.loggedAlerts.size());
        SelfTest.check("log parse: QXO new-format alert on 2026-10-05",
                log2.loggedAlerts.stream().anyMatch(x -> "QXO".equals(x.ticker)
                        && x.date.equals(LocalDate.of(2026, 10, 5))));
        SelfTest.check("log parse: thesis-move alert is not a gap alert",
                log2.loggedAlerts.stream().noneMatch(x -> "MSFT".equals(x.ticker)));

        List<Finding> f = compare(replay, log, d4);
        SelfTest.check("diff: CCC missed crossing is FAIL",
                f.stream().anyMatch(x -> x.severity == Finding.Severity.FAIL
                        && "missed_gap_alert".equals(x.field) && "CCC".equals(x.ticker)));
        SelfTest.check("diff: BBB unmatched alert is WARN (not FAIL)",
                f.stream().anyMatch(x -> x.severity == Finding.Severity.WARN
                        && "false_gap_alert".equals(x.field) && "BBB".equals(x.ticker))
                        && f.stream().noneMatch(x -> x.severity == Finding.Severity.FAIL
                        && "BBB".equals(x.ticker)));
        SelfTest.check("diff: matched AAA alert raises nothing",
                f.stream().noneMatch(x -> "AAA".equals(x.ticker)));

        // Pre-job replay alerts -> INFO, never FAIL.
        String lateLog = "2026-09-24 08:46 ET | dcf-gap-watch | 2026-09-24 | 08:46 ET"
                + " | outcome=succeeded | delivered=no | notes=no new MoS qualifiers\n";
        LogReplay late = parseAlertLog(lateLog);
        List<Finding> f2 = compare(replay, late, d4);
        SelfTest.check("diff: pre-coverage replay alerts are INFO not FAIL",
                f2.stream().noneMatch(x -> x.severity == Finding.Severity.FAIL)
                        && f2.stream().anyMatch(x -> x.severity == Finding.Severity.INFO
                        && "pre_job_history".equals(x.field)));

        // End-of-window replay alert with no log entry yet -> pending_next_run
        // INFO, not a MISSED FAIL (the next scheduled run may still catch it).
        List<Finding> f3 = compare(replay, log, d3);
        SelfTest.check("diff: last-day crossing is pending_next_run INFO not FAIL",
                f3.stream().noneMatch(x -> x.severity == Finding.Severity.FAIL
                        && "CCC".equals(x.ticker))
                        && f3.stream().anyMatch(x -> x.severity == Finding.Severity.INFO
                        && "pending_next_run".equals(x.field) && "CCC".equals(x.ticker)));

        // No log coverage at all -> INFO only, never FAIL.
        List<Finding> f4 = compare(replay, new LogReplay(), d4);
        SelfTest.check("diff: missing log -> no_log_coverage INFO, no FAIL",
                f4.stream().noneMatch(x -> x.severity == Finding.Severity.FAIL)
                        && f4.stream().anyMatch(x -> x.severity == Finding.Severity.INFO
                        && "no_log_coverage".equals(x.field)));

        // Yahoo symbol mapping for dotted tickers (BRK.B -> BRK-B).
        SelfTest.checkEq("yahoo symbol maps dots to dashes", "BRK-B", yahooSymbol("BRK.B"));
        SelfTest.checkEq("yahoo symbol leaves plain tickers", "NVDA", yahooSymbol("NVDA"));

        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        int since = DEFAULT_SINCE_DAYS;
        String mode = "";
        for (int i = 0; i < args.length; i++) {
            if ("--since".equals(args[i]) && i + 1 < args.length) {
                since = Integer.parseInt(args[++i]);
            } else if (mode.isEmpty()) {
                mode = args[i];
            }
        }
        switch (mode) {
            case "--self-test": selfTest(); break;
            case "--verify": verify(since); break;
            default:
                System.err.println("Usage: java GapWatchReplay.java --self-test | --verify [--since N]");
                System.exit(2);
        }
    }

    // =====================================================================
    // Minimal JSON reader (parse only) — see GrowthOutlookHonesty.java for why.
    // =====================================================================
    static final class Json {
        private final String s;
        private int pos;
        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.skipWs();
            Object v = j.readValue();
            j.skipWs();
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        private Object readValue() {
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
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("bad literal");
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
                if (s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':'");
                pos++;
                skipWs();
                m.put(key, readValue());
                skipWs();
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
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
