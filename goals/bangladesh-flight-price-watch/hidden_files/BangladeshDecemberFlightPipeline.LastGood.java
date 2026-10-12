import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
 * One-command pipeline for the "flight-price-bangladesh-watch-december" cron job
 * (daily ~10:16 AM ET). Never books anything; alerts are informational.
 *
 * <p>Tracked itinerary: 2 adult round trips JFK->DAC 2026-12-03 -> 2026-12-17
 * on Emirates (EK204/EK582/EK583/EK205) plus 1 adult one-way DAC->JFK 2026-12-17
 * on Emirates (EK583/EK205, the same return flight). The watch compares the
 * combined total against the best total seen; a drop of at least
 * alert_threshold_usd ($50) below the best seen is a meaningful drop.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --run} — run the two duffel searches, match the tracked
 *       flights, compare the total, print the verdict JSON, and update
 *       checked_at/last_check_status in baseline.json. It does NOT lower
 *       best_seen_total_usd — the agent delivers the chat alert FIRST, then
 *       calls {@code --record-alert}, so a fare drop can never be recorded
 *       unreported.</li>
 *   <li>{@code --dry-run} — searches + compare, print the verdict, write
 *       nothing. Safe for testing.</li>
 *   <li>{@code --record-alert <total> <rt> <ow>} — called by the agent AFTER
 *       delivering an alert; atomically lowers best_seen_total_usd.</li>
 *   <li>{@code --verify} — health check over the current real state.</li>
 *   <li>{@code --self-test} — unit checks (uses shared SelfTest helpers).</li>
 * </ul>
 *
 * <p>Testing: set PIPELINE_STATE_DIR to a /tmp copy of the flight-watch
 * directory; --run then reads/writes baseline.json and the search outputs
 * there instead of the real hidden_files.
 */
public class BangladeshDecemberFlightPipeline {

    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String JOB_ID = "flight-price-bangladesh-watch-december";

    static final String[] RT_ARGS = {"duffel", "search",
            "--origin", "JFK", "--destination", "DAC",
            "--departure-date", "2026-12-03", "--return-date", "2026-12-17",
            "--adults", "2", "--airlines", "EK",
            "--sort", "price", "--limit", "300"};
    static final String[] OW_ARGS = {"duffel", "search",
            "--origin", "DAC", "--destination", "JFK",
            "--departure-date", "2026-12-17",
            "--adults", "1", "--airlines", "EK",
            "--sort", "price", "--limit", "300"};
    static final List<String> RT_FLIGHTS = List.of("EK204", "EK582", "EK583", "EK205");
    static final List<String> OW_FLIGHTS = List.of("EK583", "EK205");

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String stateDir() {
        String o = System.getenv("PIPELINE_STATE_DIR");
        if (o != null && !o.isEmpty()) return o;
        return homeDir() + "/workspace/goals/bangladesh-flight-price-watch/hidden_files/flight-watch-december";
    }

    static String etNow() {
        return ZonedDateTime.now(ET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "--run": run(false); break;
            case "--dry-run": run(true); break;
            case "--record-alert":
                if (args.length != 4) { System.err.println("usage: --record-alert <total> <rt> <ow>"); System.exit(2); }
                recordAlert(Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                break;
            case "--verify": verify(); break;
            case "--self-test": selfTest(); break;
            default:
                System.err.println("Usage: java BangladeshDecemberFlightPipeline.java --run | --dry-run | --record-alert <t> <rt> <ow> | --verify | --self-test");
                System.exit(2);
        }
    }

    // =====================================================================
    // --run / --dry-run
    // =====================================================================

    static void run(boolean dryRun) throws Exception {
        Path dir = Paths.get(stateDir());
        if (!dryRun) Files.createDirectories(dir);
        Map<String, Object> baseline = asMap(Json.parse(readFile(dir.resolve("baseline.json").toString())));
        double bestSeen = num(baseline.get("best_seen_total_usd"));
        double threshold = baseline.containsKey("alert_threshold_usd") ? num(baseline.get("alert_threshold_usd")) : 50.0;

        // 1. Run the two searches.
        SearchResult rt = search(RT_ARGS, "search-rt.json", dryRun ? null : dir);
        sleepQuiet(15000); // politeness gap between searches
        SearchResult ow = search(OW_ARGS, "search-ow.json", dryRun ? null : dir);

        String nowIso = etNow();
        Map<String, Object> verdict = new LinkedHashMap<>();
        String status;
        String note;

        if (!rt.ok || !ow.ok || rt.matchedPrice == null || ow.matchedPrice == null) {
            status = "search_failed";
            note = "rt: " + rt.error() + "; ow: " + ow.error();
            verdict.put("outcome", "search_failed");
            verdict.put("error", note);
        } else {
            double total = round2(rt.matchedPrice + ow.matchedPrice);
            double drop = round2(bestSeen - total);
            verdict.put("rt_price_usd", rt.matchedPrice);
            verdict.put("ow_price_usd", ow.matchedPrice);
            verdict.put("total_usd", total);
            verdict.put("best_seen_total_usd", bestSeen);
            verdict.put("drop_usd", drop);
            verdict.put("threshold_usd", threshold);
            verdict.put("rt_flights", new ArrayList<>(RT_FLIGHTS));
            verdict.put("ow_flights", new ArrayList<>(OW_FLIGHTS));
            if (drop >= threshold) {
                status = "alert_pending";
                verdict.put("outcome", "alert");
                note = String.format(Locale.US,
                        "total $%.2f is $%.2f below best seen $%.2f (RT $%.2f + OW $%.2f)",
                        total, drop, bestSeen, rt.matchedPrice, ow.matchedPrice);
            } else {
                status = "no_alert";
                verdict.put("outcome", "no_alert");
                note = String.format(Locale.US,
                        "total $%.2f vs best seen $%.2f (drop $%.2f < threshold $%.2f)",
                        total, bestSeen, drop, threshold);
            }
        }
        verdict.put("checked_at", nowIso);
        System.out.println(Json.dumps(verdict));

        if (!dryRun) {
            baseline.put("checked_at", nowIso);
            baseline.put("last_check_status", status);
            baseline.put("last_check_note", note);
            atomicWrite(dir.resolve("baseline.json").toString(), Json.dumps(baseline));
        }
    }

    /** Called by the agent AFTER it delivers an alert message. */
    static void recordAlert(double total, double rt, double ow) throws Exception {
        Path dir = Paths.get(stateDir());
        Map<String, Object> baseline = asMap(Json.parse(readFile(dir.resolve("baseline.json").toString())));
        baseline.put("best_seen_total_usd", round2(total));
        baseline.put("last_alert_total_usd", round2(total));
        Map<String, Object> rtMap = asMap(baseline.get("rt"));
        rtMap.put("baseline_price_usd", round2(rt));
        Map<String, Object> owMap = asMap(baseline.get("ow"));
        owMap.put("baseline_price_usd", round2(ow));
        baseline.put("baseline_total_usd", round2(total));
        baseline.put("last_check_status", "alert_sent");
        baseline.put("checked_at", etNow());
        baseline.put("last_check_note", String.format(Locale.US,
                "alert delivered: total $%.2f (RT $%.2f + OW $%.2f)", total, rt, ow));
        atomicWrite(dir.resolve("baseline.json").toString(), Json.dumps(baseline));
        System.out.println("recorded alert: best_seen_total_usd=" + String.format(Locale.US, "%.2f", total));
    }

    // =====================================================================
    // search + offer matching
    // =====================================================================

    static final class SearchResult {
        boolean ok;
        Double matchedPrice; // null when the tracked flights were not found
        String error = "";
        String error() { return error.isEmpty() ? (ok ? "tracked flights not found" : "search failed") : error; }
    }

    static SearchResult search(String[] argv, String fileName, Path outDir) {
        SearchResult r = new SearchResult();
        ExecResult s = exec(List.of(argv), 180);
        if (s.timedOut) { r.error = "duffel search timed out"; return r; }
        if (s.exit != 0) { r.error = "duffel exit=" + s.exit + ": " + firstLine(s.stderr); return r; }
        try {
            if (outDir != null) Files.write(outDir.resolve(fileName), s.stdout.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> doc = asMap(Json.parse(s.stdout));
            if (Boolean.FALSE.equals(doc.get("ok"))) { r.error = "duffel: " + doc.get("error"); return r; }
            List<Object> offers = asList(doc.get("offers"));
            r.ok = true;
            r.matchedPrice = matchItinerary(offers, fileName.startsWith("search-rt") ? RT_FLIGHTS : OW_FLIGHTS);
            if (r.matchedPrice == null) r.error = "tracked flights not in results";
            return r;
        } catch (Exception e) {
            String head = s.stdout == null ? "" : s.stdout.substring(0, Math.min(200, s.stdout.length()));
            r.error = "parse error: " + e.getMessage() + " [stdout head: " + head.replace("\n", "\\n") + "]";
            return r;
        }
    }

    /** Find the cheapest offer whose legs contain ALL required flights.
     *  The duffel CLI nests legs under slices[].segments[]; a top-level
     *  "segments" array is also accepted defensively. */
    static Double matchItinerary(List<Object> offers, List<String> required) {
        Double best = null;
        for (Object o : offers) {
            Map<String, Object> offer = asMap(o);
            List<String> flights = new ArrayList<>();
            collectFlights(offer.get("segments"), flights);
            if (offer.get("slices") instanceof List) {
                for (Object sl : asList(offer.get("slices")))
                    collectFlights(asMap(sl).get("segments"), flights);
            }
            if (flights.containsAll(required)) {
                double price = Double.parseDouble(str(offer.get("price")));
                if (best == null || price < best) best = price;
            }
        }
        return best == null ? null : round2(best);
    }

    static void collectFlights(Object segs, List<String> flights) {
        if (!(segs instanceof List)) return;
        for (Object seg : asList(segs))
            flights.add(normFlight(str(asMap(seg).get("flight"))));
    }

    /** "EK0204" -> "EK204" so CLI zero-padding never breaks matching. */
    static String normFlight(String f) {
        if (f == null) return "";
        f = f.trim().toUpperCase(Locale.US);
        int i = 0;
        while (i < f.length() && !Character.isDigit(f.charAt(i))) i++;
        int j = i;
        while (j < f.length() && f.charAt(j) == '0') j++;
        if (j > i && j < f.length()) return f.substring(0, i) + f.substring(j);
        return f;
    }

    // =====================================================================
    // --verify
    // =====================================================================

    static void verify() throws Exception {
        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Path dir = Paths.get(stateDir());
        Path base = dir.resolve("baseline.json");
        if (!Files.exists(base)) {
            Verify.report("", "VERIFY FAILED (BangladeshDecemberFlightPipeline): baseline.json missing",
                    infos, List.of("baseline.json not found at " + base));
            return;
        }
        Map<String, Object> b;
        try {
            b = asMap(Json.parse(readFile(base.toString())));
        } catch (Exception e) {
            Verify.report("", "VERIFY FAILED (BangladeshDecemberFlightPipeline): baseline.json unparseable",
                    infos, List.of("parse error: " + e.getMessage()));
            return;
        }
        for (String k : new String[]{"itinerary", "rt", "ow", "best_seen_total_usd", "alert_threshold_usd", "last_check_status"})
            if (!b.containsKey(k)) failures.add("baseline.json missing key: " + k);
        double best = num(b.get("best_seen_total_usd"));
        double thr = num(b.get("alert_threshold_usd"));
        if (!(best > 0)) failures.add("best_seen_total_usd not positive: " + best);
        if (!(thr > 0)) failures.add("alert_threshold_usd not positive: " + thr);
        String status = str(b.get("last_check_status"));
        List<String> known = List.of("baseline_set", "no_alert", "alert_pending", "alert_sent", "search_failed");
        if (!known.contains(status)) failures.add("unknown last_check_status: " + status);
        if ("alert_pending".equals(status))
            failures.add("alert_pending left unrecorded — the agent must deliver the alert then call --record-alert");
        if ("alert_sent".equals(status)) {
            Object lat = b.get("last_alert_total_usd");
            if (lat == null || Math.abs(num(lat) - best) > 0.005)
                failures.add("alert_sent but best_seen_total_usd != last_alert_total_usd");
        }
        infos.add("best_seen_total_usd=" + String.format(Locale.US, "%.2f", best)
                + " threshold=" + String.format(Locale.US, "%.2f", thr) + " status=" + status);
        // Recompute check: when fresh search files exist, the matched total must agree.
        Path rtF = dir.resolve("search-rt.json"), owF = dir.resolve("search-ow.json");
        if (Files.exists(rtF) && Files.exists(owF)) {
            try {
                Double rtP = matchItinerary(asList(asMap(Json.parse(readFile(rtF.toString()))).get("offers")), RT_FLIGHTS);
                Double owP = matchItinerary(asList(asMap(Json.parse(readFile(owF.toString()))).get("offers")), OW_FLIGHTS);
                if (rtP != null && owP != null) {
                    double total = round2(rtP + owP);
                    infos.add("recomputed total from latest search files: $" + String.format(Locale.US, "%.2f", total));
                    if ("no_alert".equals(status) && total < best - thr - 0.005)
                        failures.add("search files show an unreported alert-level drop: $" + String.format(Locale.US, "%.2f", total));
                } else {
                    infos.add("tracked flights not present in latest search files (recompute skipped)");
                }
            } catch (Exception e) {
                infos.add("recompute skipped: " + e.getMessage());
            }
        }
        Verify.report("VERIFY OK (BangladeshDecemberFlightPipeline): state healthy.",
                "VERIFY FAILED (BangladeshDecemberFlightPipeline):", infos, failures);
    }

    // =====================================================================
    // --self-test
    // =====================================================================

    static void selfTest() {
        // flight normalization
        SelfTest.checkEq("norm strips zero padding", "EK204", normFlight("EK0204"));
        SelfTest.checkEq("norm keeps short code", "EK205", normFlight("EK205"));
        SelfTest.checkEq("norm already clean", "EK583", normFlight("EK583"));
        SelfTest.checkEq("norm null safe", "", normFlight(null));

        // itinerary matching on synthetic offers (real CLI shape: slices[].segments[],
        // zero-padded flight codes like the live output)
        String seg4 = "[{\"flight\":\"EK0204\"},{\"flight\":\"EK0582\"},{\"flight\":\"EK583\"},{\"flight\":\"EK205\"}]";
        String seg3 = "[{\"flight\":\"EK0204\"},{\"flight\":\"EK0582\"},{\"flight\":\"EK583\"}]";
        String offersJson = "{"
                + "\"offers\":["
                + " {\"price\":\"2500.00\",\"slices\":[{\"segments\":" + seg4 + "}]},"
                + " {\"price\":\"2400.00\",\"slices\":[{\"segments\":" + seg3 + "}]},"
                + " {\"price\":\"2300.00\",\"slices\":[{\"segments\":" + seg4 + "}]},"
                + " {\"price\":\"2600.00\",\"segments\":" + seg4 + "}"
                + "]}";
        List<Object> offers = asList(asMap(Json.parse(offersJson)).get("offers"));
        SelfTest.checkEq("matches full itinerary, cheapest wins", 2300.00, matchItinerary(offers, RT_FLIGHTS));
        SelfTest.checkEq("single full offer matches", 2500.00,
                matchItinerary(List.of(offers.get(0)), RT_FLIGHTS));
        SelfTest.check("missing flight returns null", matchItinerary(List.of(offers.get(1)), RT_FLIGHTS) == null);
        SelfTest.checkEq("top-level segments fallback matches", 2600.00,
                matchItinerary(List.of(offers.get(3)), RT_FLIGHTS));
        SelfTest.checkEq("one-way itinerary matches", 630.00, matchItinerary(
                List.of(asMap(Map.of("price", "630.00", "segments",
                        List.of(Map.of("flight", "EK583"), Map.of("flight", "EK205"))))), OW_FLIGHTS));

        // threshold boundary: drop exactly at threshold alerts; a cent under does not
        double best = 3186.47, thr = 50.0;
        SelfTest.check("drop exactly 50 alerts", round2(best - 3136.47) >= thr);
        SelfTest.check("drop 49.99 does not alert", !(round2(best - 3136.48) >= thr));

        // verdict JSON shape
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("outcome", "no_alert");
        v.put("total_usd", 3186.47);
        String dumped = Json.dumps(v);
        SelfTest.checkContains("verdict has outcome", dumped, "\"outcome\":\"no_alert\"");
        SelfTest.check("json round trip", Json.parse(dumped) instanceof Map);

        SelfTest.finish();
    }

    // =====================================================================
    // small helpers
    // =====================================================================

    static final class ExecResult {
        int exit; boolean timedOut; String stdout = ""; String stderr = "";
    }

    static ExecResult exec(List<String> argv, int timeoutSecs) {
        ExecResult r = new ExecResult();
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            Thread t1 = pump(p.getInputStream(), out), t2 = pump(p.getErrorStream(), err);
            if (!p.waitFor(timeoutSecs, TimeUnit.SECONDS)) {
                r.timedOut = true;
                p.destroyForcibly();
            } else {
                r.exit = p.exitValue();
            }
            t1.join(5000); t2.join(5000);
            r.stdout = out.toString(StandardCharsets.UTF_8);
            r.stderr = err.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            r.exit = -1;
            r.stderr = e.toString();
        }
        return r;
    }

    static Thread pump(java.io.InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try { in.transferTo(out); } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    static String readFile(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    static void atomicWrite(String path, String content) throws IOException {
        Path p = Paths.get(path);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static double round2(double d) { return Math.round(d * 100.0) / 100.0; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("expected object, got " + (o == null ? "null" : o.getClass()));
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        if (o instanceof List) return (List<Object>) o;
        throw new IllegalArgumentException("expected array, got " + (o == null ? "null" : o.getClass()));
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(str(o));
    }

    /* =====================================================================
     * Minimal JSON reader/writer, embedded so this file runs standalone via
     * `java BangladeshDecemberFlightPipeline.java` (single-file launch cannot link
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
