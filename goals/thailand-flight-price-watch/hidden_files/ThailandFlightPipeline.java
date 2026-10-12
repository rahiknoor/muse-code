import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One-command pipeline for the "flight-price-thailand-watch" cron job
 * (daily 8:46 AM ET). Never books anything; alerts are informational.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --run} — read the baseline (tracked itinerary windows) → run
 *       the three duffel searches → cheapest offer per window → compare each
 *       against its baseline (meaningful drop = 10%+ OR $100+ cheaper):
 *       <ul>
 *         <li>ALERT: print the alert JSON for the agent to deliver. The
 *             pipeline records the decision but does NOT lower baseline
 *             prices — the agent delivers the chat message FIRST, then updates
 *             the baselines, so a fare drop can never be recorded unreported.</li>
 *         <li>NO-ALERT: stay silent; only checked_at/status are updated.</li>
 *         <li>SEARCH FAILED (any window): stay silent; the failure is recorded,
 *             baselines untouched, no retry this run.</li>
 *       </ul></li>
 *   <li>{@code --dry-run} — searches + compare, print the decision, write
 *       nothing. Safe for testing.</li>
 *   <li>{@code --verify} — health check over the current real state.</li>
 * </ul>
 *
 * <p>Testing: set PIPELINE_STATE_DIR to a /tmp copy of the flight-watch
 * directory; --run then reads/writes baseline.json and the search outputs
 * there instead of the real hidden_files.
 */
public class ThailandFlightPipeline {

    static final String HOME = homeDir();
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String JOB_ID = "flight-price-thailand-watch";
    static final double DROP_PCT = 0.10;   // meaningful drop: 10%+ ...
    static final double DROP_USD = 100.0;  // ... OR $100+ cheaper than baseline

    static String stateDir() {
        String o = System.getenv("PIPELINE_STATE_DIR");
        if (o != null && !o.isEmpty()) return o;
        return HOME + "/workspace/goals/thailand-flight-price-watch/hidden_files/flight-watch";
    }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "--run": run(false); break;
            case "--dry-run": run(true); break;
            case "--verify": verify(); break;
            default:
                System.err.println("Usage: java ThailandFlightPipeline.java --run | --dry-run | --verify");
                System.exit(2);
        }
    }

    // =====================================================================
    // --run / --dry-run
    // =====================================================================

    static void run(boolean dryRun) throws Exception {
        long runStart = System.currentTimeMillis();
        String nowIso = etNow();
        Path dir = Paths.get(stateDir());
        String baselineFile = dir.resolve("baseline.json").toString();

        // 1. Read the baseline: route, tracked windows, per-window baselines.
        Map<String, Object> baseline = asMap(Json.parse(readFile(baselineFile)));
        String[] route = parseRoute(str(baseline.get("route"))); // e.g. "JFK -> BKK"
        List<Map<String, String>> windows = trackedWindows(baseline);
        Map<String, Map<String, Object>> baselinesByDep = baselinesByDeparture(baseline);
        if (windows.size() != 3)
            fail("expected 3 tracked windows, found " + windows.size());

        // 2. Run the three duffel searches, one file per window.
        String[] tags = {"nov", "jan", "feb"};
        List<WindowResult> results = new ArrayList<>();
        boolean anyFailed = false;
        for (int i = 0; i < windows.size(); i++) {
            Map<String, String> w = windows.get(i);
            String searchFile = dir.resolve("last_search_" + tags[i] + ".json").toString();
            WindowResult wr = searchWindow(route[0], route[1], w, searchFile);
            wr.tag = tags[i];
            results.add(wr);
            if (!wr.ok) anyFailed = true;
        }

        String status;
        String note;
        if (anyFailed) {
            // FAILURE CASE — any search failed or returned no offers.
            status = "search_failed";
            note = describeFailures(results);
        } else {
            // 3. Compare each window's cheapest against its baseline.
            List<WindowResult> qualifying = new ArrayList<>();
            for (WindowResult wr : results) {
                Map<String, Object> bl = baselinesByDep.get(wr.departure);
                // A window missing from the baseline has no baseline price (0);
                // evaluateWindow treats that as "no baseline" -> never meaningful.
                evaluateWindow(wr, bl == null ? 0 : num(bl.get("cheapest_price_usd")));
                if (wr.meaningful) qualifying.add(wr);
            }
            if (!qualifying.isEmpty()) {
                status = "alert_sent";
                note = describeAlert(qualifying);
            } else {
                status = "no_alert";
                note = describeNoAlert(results);
            }
        }

        // 4. Bookkeeping. On alert the pipeline records the decision but does
        //    NOT change baseline prices — the agent lowers them only after
        //    delivering the chat message (a drop is never recorded unreported).
        if (!dryRun) {
            baseline.put("checked_at", nowIso);
            baseline.put("last_check_status", status);
            baseline.put("last_check_note", note);
            atomicWrite(baselineFile, Json.dumps(baseline));
            writeLastRun(runStart, status, note, results);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", dryRun ? "dry_run:" + status : status);
        out.put("job", JOB_ID);
        out.put("route", route[0] + " -> " + route[1]);
        List<Object> winOut = new ArrayList<>();
        for (WindowResult wr : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("window", wr.windowLabel);
            m.put("outcome", wr.ok ? (wr.meaningful ? "alert_sent" : "no_alert") : "search_failed");
            if (wr.ok) {
                m.put("cheapest_price_usd", wr.cheapest.price);
                m.put("airline", wr.cheapest.airline);
                m.put("stops", wr.cheapest.stops);
                m.put("duration", wr.cheapest.duration);
                m.put("baseline_price_usd", wr.baselineBefore);
                m.put("drop_pct", String.format(Locale.US, "%.1f%%", wr.dropPct * 100));
                m.put("drop_usd", Math.round(wr.dropUsd * 100.0) / 100.0);
            } else {
                m.put("error", wr.error);
            }
            winOut.add(m);
        }
        out.put("windows", winOut);
        if ("alert_sent".equals(status)) {
            out.put("alert", "one or more windows dropped 10%+ or $100+ vs baseline — deliver the alert message, "
                    + "THEN for each qualifying window set cheapest_price_usd/airline/stops in baseline.json, "
                    + "update active_baseline_* if it is now the cheapest window overall, "
                    + "keep last_check_status=alert_sent");
        }
        out.put("note", note);
        System.out.println(Json.dumps(out));
    }

    // ---- window handling ----

    /**
     * Compare one window's cheapest offer against its baseline price.
     * Alert rule (from the standing job def): a drop counts as meaningful
     * when it is >= 10% OR >= $100 in absolute terms.
     * A missing/non-positive baseline means "no baseline" -> never meaningful.
     */
    static void evaluateWindow(WindowResult wr, double basePrice) {
        wr.baselineBefore = basePrice;
        if (!wr.ok || !(basePrice > 0)) {
            wr.dropPct = 0;
            wr.dropUsd = 0;
            wr.meaningful = false;
            return;
        }
        double dropPct = (basePrice - wr.cheapest.price) / basePrice;
        double dropUsd = basePrice - wr.cheapest.price;
        wr.dropPct = dropPct;
        wr.dropUsd = dropUsd;
        wr.meaningful = dropPct >= DROP_PCT || dropUsd >= DROP_USD;
    }

    static class WindowResult {
        String tag = "";
        String windowLabel = "";
        String departure = "";
        boolean ok = false;
        String error = "";
        Cheapest cheapest;
        double baselineBefore = Double.NaN;
        double dropPct = 0;
        double dropUsd = 0;
        boolean meaningful = false;
    }

    static class Cheapest {
        double price;
        String airline;
        String stops;
        String duration;
    }

    static List<Map<String, String>> trackedWindows(Map<String, Object> baseline) {
        List<Map<String, String>> out = new ArrayList<>();
        Map<String, Object> ti = asMap(baseline.get("tracked_itinerary"));
        for (Object w : asList(ti == null ? null : ti.get("windows"))) {
            Map<String, Object> m = asMap(w);
            if (m == null) continue;
            Map<String, String> e = new LinkedHashMap<>();
            e.put("departure_date", str(m.get("departure_date")));
            e.put("return_date", str(m.get("return_date")));
            out.add(e);
        }
        return out;
    }

    /** baselines[] entries keyed by departure date ("2026-11-18 to 2026-12-12"). */
    static Map<String, Map<String, Object>> baselinesByDeparture(Map<String, Object> baseline) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Object b : asList(baseline.get("baselines"))) {
            Map<String, Object> m = asMap(b);
            if (m == null) continue;
            String window = str(m.get("window"));
            String dep = window.contains(" to ") ? window.split(" to ")[0].strip() : window;
            out.put(dep, m);
        }
        return out;
    }

    static String[] parseRoute(String route) {
        if (route != null && route.contains("->")) {
            String[] parts = route.split("->");
            return new String[]{parts[0].strip(), parts[1].strip()};
        }
        return new String[]{"JFK", "BKK"}; // default per the job's standing instructions
    }

    /** Run one duffel search and extract its cheapest offer. */
    static WindowResult searchWindow(String origin, String dest, Map<String, String> w, String searchFile) {
        WindowResult wr = new WindowResult();
        wr.departure = w.get("departure_date");
        wr.windowLabel = wr.departure + " to " + w.get("return_date");
        ExecResult s = exec(List.of("duffel", "search",
                "--origin", origin, "--destination", dest,
                "--departure-date", w.get("departure_date"),
                "--return-date", w.get("return_date"),
                "--adults", "1", "--limit", "30"), 300_000);
        // The CLI redirects into the file itself; emulate with a shell redirect.
        try {
            Files.writeString(Paths.get(searchFile), s.stdout, StandardCharsets.UTF_8);
        } catch (IOException e) {
            wr.error = "could not write search output: " + e.getMessage();
            return wr;
        }
        if (s.timedOut) { wr.error = "duffel search timed out"; return wr; }
        if (s.exit != 0) { wr.error = "duffel exit=" + s.exit + ": " + firstLine(s.stderr); return wr; }
        try {
            Map<String, Object> doc = asMap(Json.parse(s.stdout));
            List<Object> offers = asList(doc.get("offers"));
            if (offers.isEmpty()) { wr.error = "no offers returned"; return wr; }
            // Offers come back sorted by price — the first is the cheapest.
            Map<String, Object> o = asMap(offers.get(0));
            Cheapest c = new Cheapest();
            c.price = Double.parseDouble(str(o.get("price")));
            c.airline = str(o.get("airline"));
            c.duration = str(o.get("duration"));
            c.stops = describeStops(o);
            wr.cheapest = c;
            wr.ok = true;
        } catch (Exception e) {
            wr.error = "unparseable search output: " + e.getMessage();
        }
        return wr;
    }

    /** "1 stop each way" style summary from the offer's slices/segments. */
    static String describeStops(Map<String, Object> offer) {
        List<Integer> perSlice = new ArrayList<>();
        for (Object sl : asList(offer.get("slices"))) {
            Map<String, Object> slice = asMap(sl);
            if (slice == null) continue;
            perSlice.add(Math.max(0, asList(slice.get("segments")).size() - 1));
        }
        if (perSlice.isEmpty()) return "not stated";
        if (perSlice.stream().allMatch(n -> n == 0)) return "nonstop";
        if (perSlice.size() == 2 && perSlice.get(0) == 1 && perSlice.get(1) == 1) return "1 stop each way";
        int total = perSlice.stream().mapToInt(Integer::intValue).sum();
        return total + (total == 1 ? " stop" : " stops") + " total";
    }

    static String describeFailures(List<WindowResult> results) {
        List<String> parts = new ArrayList<>();
        for (WindowResult wr : results)
            if (!wr.ok) parts.add(wr.windowLabel + ": " + wr.error);
        return "Search failed for " + parts.size() + " window(s): " + String.join("; ", parts)
                + ". No retry this run; the next daily run will try again.";
    }

    static String describeNoAlert(List<WindowResult> results) {
        List<String> parts = new ArrayList<>();
        for (WindowResult wr : results)
            parts.add(String.format(Locale.US, "%s cheapest $%.2f %s (%s) vs baseline $%.2f",
                    wr.windowLabel, wr.cheapest.price, wr.cheapest.airline, wr.cheapest.stops, wr.baselineBefore));
        return "No window met the 10% or $100 drop threshold. " + String.join("; ", parts) + ".";
    }

    static String describeAlert(List<WindowResult> qualifying) {
        List<String> parts = new ArrayList<>();
        for (WindowResult wr : qualifying)
            parts.add(String.format(Locale.US, "%s now $%.2f %s (%s, %s) — was $%.2f (%.1f%% / $%.2f lower)",
                    wr.windowLabel, wr.cheapest.price, wr.cheapest.airline, wr.cheapest.stops,
                    wr.cheapest.duration, wr.baselineBefore, wr.dropPct * 100, wr.dropUsd));
        return "Meaningful drop in " + qualifying.size() + " window(s): " + String.join("; ", parts) + ".";
    }

    static void writeLastRun(long runStart, String status, String note, List<WindowResult> results) throws IOException {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("job", JOB_ID);
        rec.put("run_start_epoch_ms", runStart);
        rec.put("run_start_et", etNow());
        rec.put("status", status);
        rec.put("note", note);
        List<Object> wins = new ArrayList<>();
        for (WindowResult wr : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("window", wr.windowLabel);
            m.put("departure_date", wr.departure);
            m.put("outcome", wr.ok ? (wr.meaningful ? "alert_sent" : "no_alert") : "search_failed");
            m.put("baseline_before", Double.isNaN(wr.baselineBefore) ? null : wr.baselineBefore);
            if (wr.ok) {
                m.put("cheapest_price", wr.cheapest.price);
                m.put("cheapest_airline", wr.cheapest.airline);
                m.put("cheapest_stops", wr.cheapest.stops);
                m.put("cheapest_duration", wr.cheapest.duration);
            } else {
                m.put("error", wr.error);
            }
            wins.add(m);
        }
        rec.put("windows", wins);
        atomicWrite(Paths.get(stateDir(), "last_run.json").toString(), Json.dumps(rec));
    }

    // =====================================================================
    // --verify
    // =====================================================================

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        String baselineFile = Paths.get(stateDir(), "baseline.json").toString();
        Map<String, Object> rec = readLastRun();

        if (rec == null)
            failVerify(JOB_ID,
                    List.of("no last-run record at " + stateDir() + "/last_run.json"
                            + " — --run did not complete or did not write its record"),
                    null);
        String status = str(rec.get("status"));

        // 1. All three windows must have a recorded outcome.
        List<Object> wins = asList(rec.get("windows"));
        if (wins.size() != 3)
            problems.add("expected 3 recorded window outcomes, found " + wins.size());
        for (Object w : wins) {
            Map<String, Object> m = asMap(w);
            if (m == null) continue;
            String oc = str(m.get("outcome"));
            if (!oc.equals("alert_sent") && !oc.equals("no_alert") && !oc.equals("search_failed"))
                problems.add("window " + m.get("window") + " has no valid recorded outcome ('" + oc + "')");
        }

        // 2. Baseline-price invariant: prices may ONLY change on alert_sent
        //    (and then must equal the alerted prices); on no_alert or
        //    search_failed they must be untouched.
        Map<String, Object> baseline;
        try {
            baseline = asMap(Json.parse(readFile(baselineFile)));
        } catch (Exception e) {
            problems.add("baseline.json missing/unparseable: " + e.getMessage());
            baseline = null;
        }
        if (baseline != null) {
            Map<String, Map<String, Object>> byDep = baselinesByDeparture(baseline);
            String fileStatus = str(baseline.get("last_check_status"));
            for (Object w : wins) {
                Map<String, Object> m = asMap(w);
                if (m == null) continue;
                String dep = str(m.get("departure_date"));
                Map<String, Object> bl = byDep.get(dep);
                if (bl == null) {
                    problems.add("baseline.json has no entry for window " + m.get("window"));
                    continue;
                }
                double current = num(bl.get("cheapest_price_usd"));
                double before = num(m.get("baseline_before"));
                String oc = str(m.get("outcome"));
                if ("alert_sent".equals(oc)) {
                    double alerted = num(m.get("cheapest_price"));
                    if (!"alert_sent".equals(fileStatus))
                        problems.add("window " + dep + " alerted but baseline.json last_check_status is '"
                                + fileStatus + "'");
                    if (Math.abs(current - alerted) > 0.01)
                        problems.add("window " + dep + " alerted at $" + alerted
                                + " but baseline still shows $" + current
                                + " — the agent must update baselines AFTER delivering the alert");
                } else if (!Double.isNaN(before) && Math.abs(current - before) > 0.01) {
                    problems.add("window " + dep + " baseline changed $" + before + " -> $" + current
                            + " without an alert_sent outcome (forbidden)");
                }
            }
        }

        // 3. Recompute each window's cheapest from its search file and compare
        //    digits with the run record (numbers-copied-exactly rule).
        String[] tags = {"nov", "jan", "feb"};
        for (int i = 0; i < Math.min(wins.size(), tags.length); i++) {
            Map<String, Object> m = asMap(wins.get(i));
            if (m == null || "search_failed".equals(str(m.get("outcome")))) continue;
            String sf = Paths.get(stateDir(), "last_search_" + tags[i] + ".json").toString();
            try {
                Map<String, Object> doc = asMap(Json.parse(readFile(sf)));
                List<Object> offers = asList(doc.get("offers"));
                if (offers.isEmpty()) {
                    problems.add(tags[i] + ": search file has no offers but the run recorded " + m.get("outcome"));
                    continue;
                }
                double recomputed = Double.parseDouble(str(asMap(offers.get(0)).get("price")));
                double recorded = num(m.get("cheapest_price"));
                if (Math.abs(recomputed - recorded) > 0.001)
                    problems.add(tags[i] + ": cheapest digits differ — record $" + recorded
                            + " vs recomputed $" + recomputed);
            } catch (Exception e) {
                problems.add(tags[i] + ": search file missing/unparseable: " + e.getMessage());
            }
        }

        if (!problems.isEmpty())
            failVerify(JOB_ID, problems, str(rec.get("run_start_et")));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("job", JOB_ID);
        ok.put("last_run_status", status);
        ok.put("windows_recorded", wins.size());
        System.out.println(Json.dumps(ok));
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

    static Map<String, Object> readLastRun() {
        try {
            return asMap(Json.parse(readFile(Paths.get(stateDir(), "last_run.json").toString())));
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

    /**
     * Run a command with stdout captured to a STRING (returned in
     * ExecResult.stdout). Callers that need the output in a file write it
     * themselves — this keeps the plumbing explicit and testable.
     */
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

    static String etNow() {
        return ZonedDateTime.now(ET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    static void fail(String msg) {
        System.err.println("PIPELINE FAILED (" + JOB_ID + "): " + msg);
        System.out.println(Json.dumps(Map.of("status", "failed", "job", JOB_ID, "error", msg)));
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

    static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(str(o)); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /* =====================================================================
     * Minimal JSON reader/writer, embedded so this file runs standalone via
     * `java ThailandFlightPipeline.java` (single-file launch cannot link
     * other files — the same pattern BatchQuotes.java uses).
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
