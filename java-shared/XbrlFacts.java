import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One shared tidy-table reader for SEC EDGAR companyfacts (XBRL) JSON.
 *
 * <p>Replaces the three hand-rolled companyfacts navigations in
 * {@code dcf_csv/SecNlmAudit.java} (xbrlCheck), {@code dcf_csv/DcfBacktest.java}
 * (fact), and the filing watch's {@code FilingDiff.java} (headlineFact et al).
 * Each caller keeps its own fact-SELECTION rule (they differ on purpose:
 * no-peek earliest-filed vs latest form+filed match); this helper only
 * flattens the JSON into tidy rows: one {@link Fact} per fact entry.
 *
 * <p>Usage:
 * <pre>
 *   List&lt;XbrlFacts.Fact&gt; facts = XbrlFacts.load(pathToCompanyfactsJson);
 *   List&lt;XbrlFacts.Fact&gt; rev = XbrlFacts.forConcepts(facts, "us-gaap",
 *       "Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet");
 * </pre>
 *
 * <p>Self-contained: embeds a minimal JSON parser (no shared JSON lib exists in
 * java-shared). Only entries with a numeric {@code val} become rows.
 */
public class XbrlFacts {

    /** One tidy fact row: concept, value, period, unit, decimals, provenance. */
    public record Fact(
            String taxonomy,   // e.g. "us-gaap", "ifrs-full"
            String concept,    // e.g. "Revenues"
            String unit,       // e.g. "USD", "shares"
            String start,      // duration start, null for instant facts
            String end,        // period end
            String filed,      // filing date
            String form,       // e.g. "10-K", "10-Q"
            String frame,      // e.g. "CY2024", "CQ3Y2024"
            String accn,       // accession number
            String fy,         // fiscal year label, may be numeric
            String fp,         // fiscal period: "FY", "Q1" ...
            double val,
            Integer decimals) {}

    /**
     * Load a companyfacts JSON file into tidy rows, in file order, across all
     * taxonomies. Entries without a numeric {@code val} are skipped.
     */
    public static List<Fact> load(Path p) throws IOException {
        Object root = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
        if (!(root instanceof Map)) throw new IOException("companyfacts root is not an object");
        Object factsObj = ((Map<?, ?>) root).get("facts");
        if (!(factsObj instanceof Map)) throw new IOException("companyfacts has no facts block");
        List<Fact> out = new ArrayList<>();
        for (Map.Entry<?, ?> taxEntry : ((Map<?, ?>) factsObj).entrySet()) {
            String taxonomy = String.valueOf(taxEntry.getKey());
            if (!(taxEntry.getValue() instanceof Map)) continue;
            for (Map.Entry<?, ?> conceptEntry : ((Map<?, ?>) taxEntry.getValue()).entrySet()) {
                String concept = String.valueOf(conceptEntry.getKey());
                if (!(conceptEntry.getValue() instanceof Map)) continue;
                Object unitsObj = ((Map<?, ?>) conceptEntry.getValue()).get("units");
                if (!(unitsObj instanceof Map)) continue;
                for (Map.Entry<?, ?> unitEntry : ((Map<?, ?>) unitsObj).entrySet()) {
                    String unit = String.valueOf(unitEntry.getKey());
                    if (!(unitEntry.getValue() instanceof List)) continue;
                    for (Object e : (List<?>) unitEntry.getValue()) {
                        if (!(e instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) e;
                        Object v = m.get("val");
                        if (!(v instanceof Number)) continue; // tidy table: numeric vals only
                        out.add(new Fact(
                                taxonomy, concept, unit,
                                str(m.get("start")), str(m.get("end")),
                                str(m.get("filed")), str(m.get("form")),
                                str(m.get("frame")), str(m.get("accn")),
                                str(m.get("fy")), str(m.get("fp")),
                                ((Number) v).doubleValue(), intOrNull(m.get("decimals"))));
                    }
                }
            }
        }
        return out;
    }

    /** Rows for one concept in one taxonomy, in file order. */
    public static List<Fact> forConcept(List<Fact> facts, String taxonomy, String concept) {
        List<Fact> out = new ArrayList<>();
        for (Fact f : facts)
            if (f.taxonomy().equals(taxonomy) && f.concept().equals(concept)) out.add(f);
        return out;
    }

    /**
     * Rows for the first concept (in fallback order) that has any rows.
     * Mirrors the tag-fallback loops in the original callers.
     */
    public static List<Fact> forConcepts(List<Fact> facts, String taxonomy, String... concepts) {
        for (String c : concepts) {
            List<Fact> got = forConcept(facts, taxonomy, c);
            if (!got.isEmpty()) return got;
        }
        return List.of();
    }

    private static String str(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        return String.valueOf(o); // fy can arrive numeric
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) return (int) n.doubleValue();
        return null;
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser (objects -> LinkedHashMap, arrays -> ArrayList).
    // ------------------------------------------------------------------
    static final class Json {
        static Object parse(String text) {
            Parser p = new Parser(text);
            Object v = p.value();
            p.ws();
            if (p.pos != text.length()) throw new IllegalArgumentException("trailing data at " + p.pos);
            return v;
        }

        private static final class Parser {
            final String t; int pos;
            Parser(String t) { this.t = t; }
            void ws() { while (pos < t.length() && " \t\n\r".indexOf(t.charAt(pos)) >= 0) pos++; }
            char peek() {
                if (pos >= t.length()) throw new IllegalArgumentException("unexpected end");
                return t.charAt(pos);
            }
            Object value() {
                ws();
                char c = peek();
                return switch (c) {
                    case '{' -> obj();
                    case '[' -> arr();
                    case '"' -> str();
                    case 't' -> lit("true", Boolean.TRUE);
                    case 'f' -> lit("false", Boolean.FALSE);
                    case 'n' -> lit("null", null);
                    default -> num();
                };
            }
            Object lit(String s, Object v) {
                if (!t.startsWith(s, pos)) throw new IllegalArgumentException("bad literal at " + pos);
                pos += s.length();
                return v;
            }
            Map<String, Object> obj() {
                pos++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek() == '}') { pos++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (peek() != ':') throw new IllegalArgumentException("expected : at " + pos);
                    pos++;
                    m.put(k, value());
                    ws();
                    char c = peek();
                    if (c == ',') { pos++; continue; }
                    if (c == '}') { pos++; return m; }
                    throw new IllegalArgumentException("expected , or } at " + pos);
                }
            }
            List<Object> arr() {
                pos++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek() == ']') { pos++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    char c = peek();
                    if (c == ',') { pos++; continue; }
                    if (c == ']') { pos++; return l; }
                    throw new IllegalArgumentException("expected , or ] at " + pos);
                }
            }
            String str() {
                pos++;
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (pos >= t.length()) throw new IllegalArgumentException("unterminated string");
                    char c = t.charAt(pos++);
                    if (c == '"') return sb.toString();
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
                            case 'u':
                                sb.append((char) Integer.parseInt(t.substring(pos, pos + 4), 16));
                                pos += 4;
                                break;
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else sb.append(c);
                }
            }
            Object num() {
                int s = pos;
                while (pos < t.length() && "-+0123456789.eE".indexOf(t.charAt(pos)) >= 0) pos++;
                String n = t.substring(s, pos);
                try { return Long.parseLong(n); }
                catch (NumberFormatException e) { return Double.parseDouble(n); }
            }
        }
    }

    // ------------------------------------------------------------------
    // Self-test: synthetic companyfacts covering us-gaap + ifrs-full,
    // duration + instant, numeric + non-numeric val.
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }
        System.err.println("usage: XbrlFacts.java --self-test | (library only)");
        System.exit(2);
    }

    static void selfTest() throws Exception {
        String json =
            "{\"cik\":\"0001652044\",\"facts\":{\"us-gaap\":{\"Revenues\":{\"units\":{\"USD\":[{\"start\":\"2024-01-01\","
            + "\"end\":\"2024-12-31\",\"val\":350000000000,\"accn\":\"a1\",\"fy\":\"2024\",\"fp\":\"FY\",\"form\":\"10-K\","
            + "\"filed\":\"2025-02-05\",\"frame\":\"CY2024\",\"decimals\":-6},{\"start\":\"2023-01-01\",\"end\":\"2023-12-31\","
            + "\"val\":\"restated\",\"accn\":\"a0\",\"fy\":\"2023\",\"fp\":\"FY\",\"form\":\"10-K\",\"filed\":\"2024-02-01\","
            + "\"frame\":\"CY2023\"}],\"EUR\":[{\"end\":\"2024-12-31\",\"val\":1,\"form\":\"10-K\",\"filed\":\"2025-02-05\"}"
            + "]}},\"CashAndCashEquivalentsAtCarryingValue\":{\"units\":{\"USD\":[{\"end\":\"2024-12-31\",\"val\":96000000000,"
            + "\"accn\":\"a1\",\"fy\":\"2024\",\"fp\":\"FY\",\"form\":\"10-K\",\"filed\":\"2025-02-05\"}]}}},\"ifrs-full\":{\"Revenue\":{\"units\":{\"USD\":[{\"start\":\"2024-01-01\","
            + "\"end\":\"2024-12-31\",\"val\":99000,\"form\":\"20-F\",\"filed\":\"2025-03-01\"}]}}}}}";
        Path tmp = Files.createTempFile("companyfacts", ".json");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);

        List<Fact> facts = load(tmp);
        SelfTest.checkEq("row count (non-numeric val skipped)", 4, facts.size());

        List<Fact> rev = forConcept(facts, "us-gaap", "Revenues");
        SelfTest.checkEq("us-gaap Revenues rows (USD + EUR)", 2, rev.size());
        Fact r = rev.get(0);
        SelfTest.checkEq("taxonomy", "us-gaap", r.taxonomy());
        SelfTest.checkEq("unit", "USD", r.unit());
        SelfTest.checkEq("start", "2024-01-01", r.start());
        SelfTest.checkEq("end", "2024-12-31", r.end());
        SelfTest.checkEq("val", 3.5e11, r.val());
        SelfTest.checkEq("decimals", -6, r.decimals());
        SelfTest.checkEq("accn", "a1", r.accn());

        // Instant fact: start is null.
        Fact cash = forConcept(facts, "us-gaap", "CashAndCashEquivalentsAtCarryingValue").get(0);
        SelfTest.check("instant start null", cash.start() == null);
        SelfTest.check("instant decimals null", cash.decimals() == null);

        // Fallback order: first concept with rows wins.
        List<Fact> fb = forConcepts(facts, "us-gaap", "Nope", "Revenues");
        SelfTest.checkEq("fallback picks Revenues", 2, fb.size());
        SelfTest.checkEq("fallback empty", 0, forConcepts(facts, "us-gaap", "Nope").size());

        // Cross-taxonomy: ifrs-full visible too.
        SelfTest.checkEq("ifrs rows", 1, forConcept(facts, "ifrs-full", "Revenue").size());

        // Missing facts block -> IOException, not a silent empty list.
        Path bad = Files.createTempFile("bad", ".json");
        Files.writeString(bad, "{\"cik\":\"1\"}", StandardCharsets.UTF_8);
        boolean threw = false;
        try { load(bad); } catch (IOException e) { threw = true; }
        SelfTest.check("missing facts block throws", threw);

        Files.deleteIfExists(tmp);
        Files.deleteIfExists(bad);
        SelfTest.finish();
    }
}
