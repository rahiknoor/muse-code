import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
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
import java.util.regex.Pattern;

/**
 * One-command pipeline for the "terre-d-hermes-price-watch" cron job
 * (Mondays 9:46 AM ET). Never buys anything; alerts are informational.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --run} — run meta-catalog-search → keep only 200 mL listings
 *       from authorized retailers (hermes.com, nordstrom.com, sephora.com) →
 *       take the lowest price → compare against baseline.json:
 *       <ul>
 *         <li>ALERT (price &lt; baseline): print the alert JSON for the agent
 *             to deliver. The pipeline records the decision but does NOT
 *             lower the baseline price itself — the agent delivers the chat
 *             message FIRST, then updates the baseline price, so a price drop
 *             can never be recorded without being reported.</li>
 *         <li>NO-ALERT: stay silent; only checked_at/status are updated.</li>
 *         <li>SEARCH FAILED / no qualifying product: stay silent; the failure
 *             is recorded, the baseline price is untouched, no retry.</li>
 *       </ul></li>
 *   <li>{@code --dry-run} — search + filter + compare, print the decision,
 *       write nothing. Safe for testing.</li>
 *   <li>{@code --verify} — health check over the current real state.</li>
 * </ul>
 *
 * <p>Testing: set PIPELINE_STATE_DIR to a /tmp copy of the price-watch
 * directory; --run then reads/writes baseline.json and the search output
 * there instead of the real hidden_files.
 */
public class PerfumePricePipeline {

    static final String HOME = homeDir();
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String JOB_ID = "terre-d-hermes-price-watch";
    // Authorized retailers only — grey-market discount sites are ignored.
    static final List<String> AUTHORIZED_DOMAINS = List.of("hermes.com", "nordstrom.com", "sephora.com");
    // Name must mention the 200 mL size: "200ml", "200 ml", "6.7" or "6.76" oz.
    static final Pattern SIZE_RE = Pattern.compile("200\\s?ml|6\\.76|6\\.7", Pattern.CASE_INSENSITIVE);

    static String stateDir() {
        String o = System.getenv("PIPELINE_STATE_DIR");
        if (o != null && !o.isEmpty()) return o;
        return HOME + "/workspace/goals/terre-d-herm-s-200-ml-price-watch/hidden_files/price-watch";
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
                System.err.println("Usage: java PerfumePricePipeline.java --run | --dry-run | --verify");
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
        String searchFile = dir.resolve("last_search.json").toString();
        String baselineFile = dir.resolve("baseline.json").toString();

        // 1. Run the catalog search exactly once.
        ExecResult s = exec(List.of("meta-catalog-search",
                "--query", "Terre d'Hermes eau de toilette 200ml",
                "--brand", "Hermes", "-n", "20",
                "--out", searchFile), 180_000);

        SearchResult found = parseSearch(searchFile, s);
        Map<String, Object> baseline = readBaseline(baselineFile);
        double baselinePrice = num(baseline.get("baseline_price_usd"));

        String status;
        String note;
        Double newPrice = null;
        String merchant = null;

        if (!found.ok) {
            // FAILURE CASE — search failed or no qualifying product.
            status = "search_failed";
            note = found.error;
        } else if (found.bestPrice < baselinePrice) {
            status = "alert_sent";
            newPrice = found.bestPrice;
            merchant = found.bestMerchant;
            note = String.format(Locale.US,
                    "Price drop: $%.2f at %s (baseline was $%.2f).", newPrice, merchant, baselinePrice);
        } else {
            status = "no_alert";
            note = String.format(Locale.US,
                    "Lowest authorized 200 mL price $%.2f at %s — at/above baseline $%.2f.",
                    found.bestPrice, found.bestMerchant, baselinePrice);
        }

        // 2. Bookkeeping. On alert the pipeline records the decision but does
        //    NOT change baseline_price_usd — the agent lowers it only after
        //    delivering the chat message (a drop is never recorded unreported).
        if (!dryRun) {
            baseline.put("checked_at", nowIso);
            baseline.put("last_check_status", status);
            baseline.put("last_check_note", note);
            if ("alert_sent".equals(status)) {
                baseline.put("alert_price_usd", newPrice);
                baseline.put("alert_merchant", merchant);
            }
            atomicWrite(baselineFile, Json.dumps(baseline));
            writeLastRun(runStart, status, note, baselinePrice, newPrice, merchant, searchFile);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", dryRun ? "dry_run:" + status : status);
        out.put("job", JOB_ID);
        out.put("baseline_price_usd", baselinePrice);
        if (found.ok) {
            out.put("current_price_usd", found.bestPrice);
            out.put("current_merchant", found.bestMerchant);
            out.put("products_considered", found.considered);
        } else {
            out.put("error", found.error);
        }
        if ("alert_sent".equals(status)) {
            out.put("alert", "current price is below baseline — deliver the alert message, "
                    + "THEN set baseline_price_usd to " + newPrice + ", record the merchant, "
                    + "keep last_check_status=alert_sent");
        }
        out.put("note", note);
        System.out.println(Json.dumps(out));
    }

    static class SearchResult {
        boolean ok = false;
        String error = "";
        double bestPrice = 0;
        String bestMerchant = "";
        int considered = 0;
    }

    /** Parse the search output (read-only) and apply the filter rules. */
    static SearchResult parseSearch(String searchFile, ExecResult s) {
        SearchResult r = new SearchResult();
        if (s.timedOut) { r.error = "meta-catalog-search timed out"; return r; }
        if (s.exit != 0) { r.error = "meta-catalog-search exit=" + s.exit + ": " + firstLine(s.stderr); return r; }
        Map<String, Object> doc;
        try {
            doc = asMap(Json.parse(readFile(searchFile)));
        } catch (Exception e) {
            r.error = "search output missing/unparseable: " + e.getMessage();
            return r;
        }
        List<Object> products = asList(doc.get("products"));
        double best = Double.POSITIVE_INFINITY;
        String bestM = "";
        int kept = 0;
        for (Object p : products) {
            Map<String, Object> prod = asMap(p);
            if (prod == null) continue;
            String name = str(prod.get("name"));
            String url = str(prod.get("url"));
            // BOTH filters must pass: 200 mL size mentioned AND authorized domain.
            if (!SIZE_RE.matcher(name).find()) continue;
            if (!authorizedDomain(url)) continue;
            kept++;
            String priceStr = str(prod.get("sale_price"));
            if (priceStr.isBlank() || "null".equals(priceStr)) priceStr = str(prod.get("price"));
            double price = parsePrice(priceStr);
            if (price <= 0) continue;
            if (price < best) {
                best = price;
                bestM = merchantName(url);
            }
        }
        r.considered = kept;
        if (kept == 0 || best == Double.POSITIVE_INFINITY) {
            r.error = "no qualifying product: kept " + kept + " of " + products.size()
                    + " (need 200 mL name match + hermes.com/nordstrom.com/sephora.com)";
            return r;
        }
        r.ok = true;
        r.bestPrice = best;
        r.bestMerchant = bestM;
        return r;
    }

    static boolean authorizedDomain(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            for (String d : AUTHORIZED_DOMAINS)
                if (host.equals(d) || host.endsWith("." + d)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static String merchantName(String url) {
        try {
            String host = new URI(url).getHost().toLowerCase(Locale.US);
            for (String d : AUTHORIZED_DOMAINS)
                if (host.equals(d) || host.endsWith("." + d)) return d;
            return host;
        } catch (Exception e) {
            return url;
        }
    }

    /** "$204.00" or "$141" -> 204.0; unparseable -> -1. */
    static double parsePrice(String s) {
        if (s == null) return -1;
        String digits = s.replaceAll("[^0-9.]", "");
        if (digits.isBlank()) return -1;
        try {
            return Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static Map<String, Object> readBaseline(String baselineFile) throws IOException {
        return asMap(Json.parse(readFile(baselineFile)));
    }

    static void writeLastRun(long runStart, String status, String note, double baselineBefore,
                             Double newPrice, String merchant, String searchFile) throws IOException {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("job", JOB_ID);
        rec.put("run_start_epoch_ms", runStart);
        rec.put("run_start_et", etNow());
        rec.put("status", status);
        rec.put("note", note);
        rec.put("baseline_before", baselineBefore);
        if (newPrice != null) rec.put("new_price", newPrice);
        if (merchant != null) rec.put("merchant", merchant);
        rec.put("search_file", searchFile);
        atomicWrite(Paths.get(stateDir(), "last_run.json").toString(), Json.dumps(rec));
    }

    // =====================================================================
    // --verify
    // =====================================================================

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        String baselineFile = Paths.get(stateDir(), "baseline.json").toString();
        // Shared last-run check (see Verify.checkLastRun): without a record
        // there is no baseline invariant to check — NOT_INITIALIZED is INFO
        // and passes; REGRESSION fails loud.
        Verify.LastRunState lrs = Verify.checkLastRun(JOB_ID,
                Paths.get(stateDir(), "last_run.json"),
                HOME + "/workspace/scheduler_state/completed_runs.json", infos, problems);
        if (lrs != Verify.LastRunState.PRESENT) {
            if (!problems.isEmpty())
                failVerify(JOB_ID, problems, null);
            for (String info : infos) System.out.println("INFO: " + info);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", "ok");
            ok.put("job", JOB_ID);
            ok.put("detail", "not yet initialized — first scheduled run pending");
            System.out.println(Json.dumps(ok));
            return;
        }
        Map<String, Object> rec = readLastRun();
        if (rec == null) {
            problems.add("last-run record at " + stateDir() + "/last_run.json"
                    + " exists but does not parse — --run did not write a valid record");
            failVerify(JOB_ID, problems, null);
        }
        String status = str(rec.get("status"));
        double before = num(rec.get("baseline_before"));

        // 1. The search output file must exist and parse (provenance for any
        //    baseline write).
        String searchFile = str(rec.get("search_file"));
        boolean searchParses = false;
        int productCount = 0;
        try {
            Map<String, Object> doc = asMap(Json.parse(readFile(searchFile)));
            List<Object> products = asList(doc.get("products"));
            productCount = products.size();
            searchParses = true;
        } catch (Exception e) {
            problems.add("search output file missing/unparseable: " + searchFile + " (" + e.getMessage() + ")");
        }

        // 2. Baseline-price invariant: the price may ONLY have changed when the
        //    run sent an alert (and then it must equal the alerted price).
        Map<String, Object> baseline;
        try {
            baseline = readBaseline(baselineFile);
        } catch (Exception e) {
            problems.add("baseline.json missing/unparseable: " + e.getMessage());
            baseline = null;
        }
        if (baseline != null) {
            double current = num(baseline.get("baseline_price_usd"));
            String fileStatus = str(baseline.get("last_check_status"));
            if ("alert_sent".equals(status)) {
                double alerted = num(rec.get("new_price"));
                if (!fileStatus.equals("alert_sent"))
                    problems.add("run decided alert_sent but baseline.json last_check_status is '" + fileStatus + "'");
                if (Math.abs(current - alerted) > 0.001)
                    problems.add("run alerted at $" + alerted + " but baseline_price_usd is $" + current
                            + " — the agent must lower the baseline AFTER delivering the alert");
                if (!(alerted < before))
                    problems.add("alert price $" + alerted + " is not below the previous baseline $" + before);
            } else {
                // no_alert or search_failed: the price must be untouched.
                if (Math.abs(current - before) > 0.001)
                    problems.add("baseline_price_usd changed from $" + before + " to $" + current
                            + " without an alert_sent run (forbidden)");
                if (!fileStatus.equals(status) && !"ok".equals(fileStatus))
                    problems.add("baseline.json last_check_status '" + fileStatus
                            + "' does not match the run's '" + status + "'");
            }
        }

        if (!problems.isEmpty())
            failVerify(JOB_ID, problems, str(rec.get("run_start_et")));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("job", JOB_ID);
        ok.put("last_run_status", status);
        ok.put("search_products", productCount);
        ok.put("search_parses", searchParses);
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
     * `java PerfumePricePipeline.java` (single-file launch cannot link other
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
