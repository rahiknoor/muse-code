// TranscriptionRecheck.java — audit gap C2: drift between a recollected
// website-values file and the stored raw file in dcf_csv/website_values/.
//
// The browser leg that RE-COLLECTS the values rides inside the monthly
// analyst-target-refresh job (alongside A4); this tool is the DIFF only.
// Report-only per the audit-write guard: it never writes the stored files.
// When a recollection differs, the recollected file is kept ADD-then-verify:
// it lives alongside the stored file until manual review approves the swap
// (delete-first is banned) — see dcf_csv/website_values/RECOLLECTION_PROTOCOL.md.
//
// Recollected input follows the existing website_values/<site>.json schema:
//   {site, display, metric, method, collected (YYYY-MM-DD),
//    values: {TICKER: {value, date, note, url}}}
//
// Tolerance (justified in the work notes): max(1% of |stored value|, $0.05).
// A same-page re-read matches to the cent; transcription errors and
// metric-row mixups (e.g. grabbing the headline instead of the disciplined
// row) move values by far more. Anything inside tolerance is INFO;
// anything beyond is FAIL for manual review, shown side by side.
//
// Usage:
//   java TranscriptionRecheck.java --self-test
//   java TranscriptionRecheck.java --verify --recollected <file.json>
// (the site is read from the file's "site" field and diffed against
// website_values/<site>.json)
//
// Launch with the shared sources visible:
//   java --class-path ~/workspace/java-shared/classes TranscriptionRecheck.java --verify --recollected ...
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class TranscriptionRecheck {

    static final String VALUES_DIR = "workspace/dcf_csv/website_values";
    /** GuruFocus is excluded from the re-collection program (premium wall). */
    static final String EXCLUDED_SITE = "gurufocus";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length == 3 && args[0].equals("--verify") && args[1].equals("--recollected")) {
            verify(args[2]);
            return;
        }
        System.err.println("usage: java TranscriptionRecheck.java --self-test | --verify --recollected <file.json>");
        System.exit(2);
    }

    // ---- verify ------------------------------------------------------------
    static void verify(String recollectedArg) throws IOException {
        Path home = Paths.get(System.getenv("HOME"));
        Path recollectedPath = Paths.get(recollectedArg);
        if (!Files.isRegularFile(recollectedPath)) {
            System.err.println("FATAL: recollected file not found: " + recollectedPath);
            System.exit(2);
        }
        J.O recollected = (J.O) J.parse(Files.readString(recollectedPath, StandardCharsets.UTF_8));
        String site = str(recollected, "site");
        if (site.isEmpty()) {
            System.err.println("FATAL: recollected file has no \"site\" field: " + recollectedPath);
            System.exit(2);
        }

        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();

        if (site.equals(EXCLUDED_SITE)) {
            findings.add(Finding.fail("C2", "-", "site.excluded",
                    "site in the re-collection program",
                    "site '" + site + "' — excluded: values unverifiable behind the premium wall",
                    "recollected: " + recollectedPath,
                    "do not collect this site; remove the recollected file"));
        } else {
            Path storedPath = home.resolve(VALUES_DIR + "/" + site + ".json");
            if (!Files.isRegularFile(storedPath)) {
                findings.add(Finding.fail("C2", "-", "site.unknown",
                        "a stored raw file website_values/" + site + ".json",
                        "no stored file for site '" + site + "'",
                        "recollected: " + recollectedPath,
                        "collect this site for the first time per RECOLLECTION_PROTOCOL.md, "
                                + "or fix the recollected file's \"site\" field"));
            } else {
                J.O stored = (J.O) J.parse(Files.readString(storedPath, StandardCharsets.UTF_8));
                findings.addAll(diffSite(stored, recollected, site,
                        storedPath.toString(), recollectedPath.toString()));
            }
        }

        int failCount = 0;
        for (Finding f : findings) if (f.severity == Finding.Severity.FAIL) failCount++;
        infos.add("recollection check for site '" + site + "': "
                + findings.size() + " finding(s), " + failCount + " FAIL");
        Finding.split(findings, infos, failures);
        Verify.report(
                "VERIFY OK (TranscriptionRecheck): recollection matches the stored file within tolerance.",
                "VERIFY FAILED (TranscriptionRecheck): " + failures.size() + " problem(s) found:",
                infos, failures);
    }

    // ---- the diff ----------------------------------------------------------
    // Pure function: --self-test exercises the same code with synthetic
    // stored/recollected objects.
    static List<Finding> diffSite(J.O stored, J.O recollected, String site,
                                  String storedLabel, String recollectedLabel) {
        List<Finding> out = new ArrayList<>();
        String evidenceBase = "stored: " + storedLabel + " (collected " + str(stored, "collected") + ")"
                + "; recollected: " + recollectedLabel + " (collected " + str(recollected, "collected") + ")";

        // Metric discipline: the recollected file must name the SAME metric
        // the stored file was collected under. A changed metric string means
        // the collector may have read the wrong row (e.g. the headline
        // instead of the disciplined DCF row).
        String storedMetric = str(stored, "metric");
        String recollectedMetric = str(recollected, "metric");
        if (!storedMetric.isEmpty() && !recollectedMetric.equals(storedMetric)) {
            out.add(Finding.fail("C2", "-", "metric.discipline",
                    "metric '" + storedMetric + "'",
                    "metric '" + recollectedMetric + "'",
                    evidenceBase,
                    "confirm the recollection used the disciplined metric row per "
                            + "RECOLLECTION_PROTOCOL.md; if the site renamed its metric, "
                            + "bless the change by updating the stored file's metric field"));
        }

        // Stale recollection: older than what is already stored.
        String storedCollected = str(stored, "collected");
        String recollectedCollected = str(recollected, "collected");
        if (!storedCollected.isEmpty() && !recollectedCollected.isEmpty()
                && recollectedCollected.compareTo(storedCollected) < 0) {
            out.add(Finding.warn("C2", "-", "recollection.stale",
                    "recollection collected on/after " + storedCollected,
                    "recollection collected " + recollectedCollected,
                    evidenceBase,
                    "re-collect with a fresh browser pass; an older recollection cannot "
                            + "supersede the stored file"));
        }

        J.O storedVals = obj(stored, "values");
        J.O recollectedVals = obj(recollected, "values");
        Set<String> tickers = new LinkedHashSet<>();
        tickers.addAll(storedVals.m.keySet());
        tickers.addAll(recollectedVals.m.keySet());

        for (String t : tickers) {
            J.O sCell = cell(storedVals, t);
            J.O rCell = cell(recollectedVals, t);
            if (sCell == null && rCell == null) continue;
            if (rCell == null) {
                out.add(Finding.warn("C2", t, "ticker.not_recollected",
                        "ticker re-collected", "ticker absent from the recollection",
                        evidenceBase,
                        "re-collect this ticker or record why it is unavailable in the "
                                + "recollected file's note field"));
                continue;
            }
            if (sCell == null) {
                out.add(Finding.warn("C2", t, "ticker.unexpected",
                        "ticker present in the stored file",
                        "ticker '" + t + "' only in the recollection",
                        evidenceBase,
                        "confirm the ticker belongs to the roster; if it is a new holding, "
                                + "bless it by adding it to the stored file"));
                continue;
            }
            Double sVal = num(sCell, "value");
            Double rVal = num(rCell, "value");
            if (sVal != null && rVal != null) {
                double delta = Math.abs(rVal - sVal);
                double tol = tolerance(sVal);
                if (delta > tol) {
                    out.add(Finding.fail("C2", t, "value.drift",
                            "stored " + fmt(sVal), "recollected " + fmt(rVal)
                                    + " (delta " + fmt(delta) + " > tol " + fmt(tol) + ")",
                            evidenceBase + "; note: '" + str(rCell, "note") + "'",
                            "MANUAL REVIEW: confirm the recollected page used the disciplined "
                                    + "metric row (RECOLLECTION_PROTOCOL.md) — drift may be a "
                                    + "metric-row error or a genuine site model update. Do NOT "
                                    + "auto-adopt; replace website_values/" + site + ".json only "
                                    + "after review (add-then-verify), then run "
                                    + "WebsiteConsensus --build"));
                } else if (delta > 0) {
                    out.add(Finding.info("C2", t, "value.minor_drift",
                            "stored " + fmt(sVal), "recollected " + fmt(rVal)
                                    + " (within tol " + fmt(tol) + ")",
                            evidenceBase,
                            "adopt the recollected value at the coordinator's discretion; "
                                    + "run WebsiteConsensus --build after updating the stored file"));
                }
            } else if (sVal == null && rVal != null) {
                out.add(Finding.warn("C2", t, "value.appeared",
                        "stored null (no value published)", "recollected " + fmt(rVal),
                        evidenceBase + "; note: '" + str(rCell, "note") + "'",
                        "the site now publishes a value — adopt it after confirming the "
                                + "metric row, then run WebsiteConsensus --build"));
            } else if (sVal != null && rVal == null) {
                out.add(Finding.warn("C2", t, "value.disappeared",
                        "stored " + fmt(sVal), "recollected null (no value published)",
                        evidenceBase + "; note: '" + str(rCell, "note") + "'",
                        "check whether the page now gates the value (paywall/login) — "
                                + "do not null the stored value on a collection failure"));
            }
            // URL moved while the value held: the canonical page may have moved.
            String sUrl = str(sCell, "url");
            String rUrl = str(rCell, "url");
            if (!sUrl.isEmpty() && !rUrl.isEmpty() && !sUrl.equals(rUrl)) {
                out.add(Finding.warn("C2", t, "url.changed",
                        sUrl, rUrl,
                        evidenceBase,
                        "confirm the new URL is the same disciplined page; update the "
                                + "stored file's url after review"));
            }
        }
        return out;
    }

    /** max(1% of |stored|, $0.05) — see the header comment for justification. */
    static double tolerance(double storedVal) {
        return Math.max(0.01 * Math.abs(storedVal), 0.05);
    }

    static String fmt(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 100.0) / 100.0);
    }

    // ---- self test ---------------------------------------------------------
    static void selfTest() {
        // Base stored file: two tickers, one with a value, one null.
        String storedJson = "{\"site\":\"simplywallst\",\"display\":\"Simply Wall St\","
                + "\"metric\":\"Cash Flow Value\",\"collected\":\"2026-09-28\","
                + "\"values\":{\"AAA\":{\"value\":100.00,\"date\":\"2026-09-28\",\"note\":\"\","
                + "\"url\":\"https://example.com/aaa\"},"
                + "\"BBB\":{\"value\":null,\"date\":null,\"note\":\"no value published\","
                + "\"url\":\"https://example.com/bbb\"}}}";
        String sameRecollected = "{\"site\":\"simplywallst\",\"display\":\"Simply Wall St\","
                + "\"metric\":\"Cash Flow Value\",\"collected\":\"2026-10-28\","
                + "\"values\":{\"AAA\":{\"value\":100.00,\"date\":\"2026-10-28\",\"note\":\"\","
                + "\"url\":\"https://example.com/aaa\"},"
                + "\"BBB\":{\"value\":null,\"date\":null,\"note\":\"still none\","
                + "\"url\":\"https://example.com/bbb\"}}}";

        List<Finding> f = diffSite((J.O) J.parse(storedJson), (J.O) J.parse(sameRecollected),
                "simplywallst", "S", "R");
        SelfTest.checkEq("identical recollection: no findings", 0, f.size());

        // Drift beyond tolerance -> FAIL with side-by-side values.
        String drifted = sameRecollected.replace("\"value\":100.00", "\"value\":112.50");
        List<Finding> f2 = diffSite((J.O) J.parse(storedJson), (J.O) J.parse(drifted),
                "simplywallst", "S", "R");
        SelfTest.check("drift: anyFail", Finding.anyFail(f2));
        SelfTest.checkEq("drift: one finding", 1, f2.size());
        SelfTest.checkEq("drift: field", "value.drift", f2.get(0).field);
        SelfTest.checkEq("drift: ticker", "AAA", f2.get(0).ticker);
        SelfTest.checkContains("drift: shows stored side", f2.get(0).expected, "100");
        SelfTest.checkContains("drift: shows recollected side", f2.get(0).actual, "112.5");

        // Within tolerance (0.5% on 100 -> tol 1.00) -> INFO, no FAIL.
        String minor = sameRecollected.replace("\"value\":100.00", "\"value\":100.40");
        List<Finding> f3 = diffSite((J.O) J.parse(storedJson), (J.O) J.parse(minor),
                "simplywallst", "S", "R");
        SelfTest.check("minor drift: no FAIL", !Finding.anyFail(f3));
        SelfTest.checkEq("minor drift: one INFO", 1, f3.size());
        SelfTest.checkEq("minor drift: severity INFO",
                Finding.Severity.INFO, f3.get(0).severity);

        // Sub-dollar floor: stored 0.30, tol = max(0.003, 0.05) = 0.05.
        SelfTest.checkEq("tolerance floor on 0.30", 0.05, tolerance(0.30));
        SelfTest.checkEq("tolerance relative on 700", 7.0, tolerance(700.0));
        SelfTest.checkEq("tolerance on negative", 0.442, Math.round(tolerance(-44.2) * 1000.0) / 1000.0);

        // Metric change -> FAIL (metric-discipline guard).
        String wrongMetric = sameRecollected.replace("\"metric\":\"Cash Flow Value\"",
                "\"metric\":\"Intrinsic Value\"");
        List<Finding> f4 = diffSite((J.O) J.parse(storedJson), (J.O) J.parse(wrongMetric),
                "simplywallst", "S", "R");
        SelfTest.check("metric change: anyFail", Finding.anyFail(f4));
        boolean hasMetric = false;
        for (Finding x : f4) if (x.field.equals("metric.discipline")) hasMetric = true;
        SelfTest.check("metric change: metric.discipline FAIL", hasMetric);

        // Value appeared (stored null -> value) -> WARN.
        String appeared = sameRecollected.replace(
                "\"BBB\":{\"value\":null,\"date\":null,\"note\":\"still none\"",
                "\"BBB\":{\"value\":42.00,\"date\":\"2026-10-28\",\"note\":\"\"");
        List<Finding> f5 = diffSite((J.O) J.parse(storedJson), (J.O) J.parse(appeared),
                "simplywallst", "S", "R");
        SelfTest.check("appeared: no FAIL", !Finding.anyFail(f5));
        boolean hasAppeared = false;
        for (Finding x : f5) {
            if (x.field.equals("value.appeared") && x.severity == Finding.Severity.WARN) hasAppeared = true;
        }
        SelfTest.check("appeared: WARN present", hasAppeared);

        // Value disappeared (stored value -> null) -> WARN.
        String disappeared = sameRecollected.replace(
                "\"AAA\":{\"value\":100.00,\"date\":\"2026-10-28\",\"note\":\"\"",
                "\"AAA\":{\"value\":null,\"date\":null,\"note\":\"page gated\"");
        List<Finding> f6 = diffSite((J.O) J.parse(storedJson), (J.O) J.parse(disappeared),
                "simplywallst", "S", "R");
        boolean hasDisappeared = false;
        for (Finding x : f6) {
            if (x.field.equals("value.disappeared") && x.severity == Finding.Severity.WARN) hasDisappeared = true;
        }
        SelfTest.check("disappeared: WARN present", hasDisappeared && !Finding.anyFail(f6));

        // Stale recollection date -> WARN.
        String stale = sameRecollected.replace("\"collected\":\"2026-10-28\"", "\"collected\":\"2026-09-01\"");
        List<Finding> f7 = diffSite((J.O) J.parse(storedJson), (J.O) J.parse(stale),
                "simplywallst", "S", "R");
        boolean hasStale = false;
        for (Finding x : f7) if (x.field.equals("recollection.stale")) hasStale = true;
        SelfTest.check("stale recollection: WARN present", hasStale);

        // Ticker missing from the recollection -> WARN; unknown ticker -> WARN.
        String missingTicker = "{\"site\":\"simplywallst\",\"metric\":\"Cash Flow Value\","
                + "\"collected\":\"2026-10-28\",\"values\":{\"AAA\":{\"value\":100.00,\"url\":\"https://example.com/aaa\"},"
                + "\"ZZZ\":{\"value\":10.00,\"url\":\"https://example.com/zzz\"}}}";
        List<Finding> f8 = diffSite((J.O) J.parse(storedJson), (J.O) J.parse(missingTicker),
                "simplywallst", "S", "R");
        boolean hasMissing = false, hasUnexpected = false;
        for (Finding x : f8) {
            if (x.field.equals("ticker.not_recollected") && x.ticker.equals("BBB")) hasMissing = true;
            if (x.field.equals("ticker.unexpected") && x.ticker.equals("ZZZ")) hasUnexpected = true;
        }
        SelfTest.check("missing ticker: WARN", hasMissing);
        SelfTest.check("unexpected ticker: WARN", hasUnexpected);
        SelfTest.check("ticker roster issues: no FAIL", !Finding.anyFail(f8));

        // URL change with same value -> WARN.
        String moved = sameRecollected.replace("https://example.com/aaa", "https://example.com/aaa-new");
        List<Finding> f9 = diffSite((J.O) J.parse(storedJson), (J.O) J.parse(moved),
                "simplywallst", "S", "R");
        boolean hasUrl = false;
        for (Finding x : f9) if (x.field.equals("url.changed")) hasUrl = true;
        SelfTest.check("url change: WARN", hasUrl && !Finding.anyFail(f9));

        SelfTest.finish();
    }

    // ---- small JSON helpers ------------------------------------------------
    static J.O obj(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.O) ? (J.O) v : new J.O();
    }

    static J.O cell(J.O vals, String ticker) {
        J.V v = vals.m.get(ticker);
        return (v instanceof J.O) ? (J.O) v : null;
    }

    static String str(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.S) ? ((J.S) v).s : "";
    }

    static Double num(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.N) ? ((J.N) v).d : null;
    }

    // ---- minimal JSON (same pattern as WebsiteConsensus.J) ------------------
    static final class J {
        abstract static class V { }
        static final class O extends V { final LinkedHashMap<String, V> m = new LinkedHashMap<>(); }
        static final class A extends V { final ArrayList<V> l = new ArrayList<>(); }
        static final class S extends V { final String s; S(String s) { this.s = s; } }
        static final class N extends V { final double d; final String lit; N(double d, String lit) { this.d = d; this.lit = lit; } }
        static final class B extends V { final boolean b; B(boolean b) { this.b = b; } }
        static final class Nil extends V { }

        static V parse(String t) {
            P p = new P(t);
            V v = p.val();
            p.ws();
            if (p.i != t.length()) throw new IllegalArgumentException("trailing characters at " + p.i);
            return v;
        }

        static final class P {
            final String t; int i;
            P(String t) { this.t = t; }
            void ws() {
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
                }
            }
            V val() {
                ws();
                if (i >= t.length()) throw new IllegalArgumentException("unexpected end of input");
                char c = t.charAt(i);
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return new S(str());
                if (c == 't') { exp("true"); return new B(true); }
                if (c == 'f') { exp("false"); return new B(false); }
                if (c == 'n') { exp("null"); return new Nil(); }
                return num();
            }
            void exp(String s) {
                if (t.startsWith(s, i)) i += s.length();
                else throw new IllegalArgumentException("expected '" + s + "' at " + i);
            }
            O obj() {
                O o = new O(); i++; ws();
                if (i < t.length() && t.charAt(i) == '}') { i++; return o; }
                while (true) {
                    ws();
                    if (i >= t.length() || t.charAt(i) != '"') throw new IllegalArgumentException("expected key at " + i);
                    String k = str(); ws();
                    if (i >= t.length() || t.charAt(i) != ':') throw new IllegalArgumentException("expected ':' at " + i);
                    i++;
                    o.m.put(k, val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == '}') { i++; break; }
                    throw new IllegalArgumentException("expected ',' or '}' at " + i);
                }
                return o;
            }
            A arr() {
                A a = new A(); i++; ws();
                if (i < t.length() && t.charAt(i) == ']') { i++; return a; }
                while (true) {
                    a.l.add(val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == ']') { i++; break; }
                    throw new IllegalArgumentException("expected ',' or ']' at " + i);
                }
                return a;
            }
            String str() {
                StringBuilder sb = new StringBuilder(); i++;
                while (true) {
                    if (i >= t.length()) throw new IllegalArgumentException("unterminated string");
                    char c = t.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        if (i >= t.length()) throw new IllegalArgumentException("bad escape");
                        char e = t.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                if (i + 4 > t.length()) throw new IllegalArgumentException("bad \\u escape");
                                sb.append((char) Integer.parseInt(t.substring(i, i + 4), 16)); i += 4;
                                break;
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else sb.append(c);
                }
                return sb.toString();
            }
            V num() {
                int s = i;
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                    else break;
                }
                String lit = t.substring(s, i);
                try { return new N(Double.parseDouble(lit), lit); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("bad number at " + s); }
            }
        }
    }
}
