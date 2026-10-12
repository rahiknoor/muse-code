// OptionalityGap.java — validator for the optionality-gap narrative backend.
//
// The metric is narrative context shown ALONGSIDE the DCF, never inside it:
// per-company optional bets (unlaunched products, expansion projects, new
// ventures, platform options) that the filing-grounded DCF doesn't capture.
// Honest about what it is: narrative context, not a second valuation.
// Not a blessed column — the proposal/audit/blessed guard does not apply
// (same standing as website_consensus.json).
//
// Backend: ~/workspace/dcf_csv/optionality_gap.json
//   { "asof": "YYYY-MM-DD",
//     "method": "<disclaimer: narrative only, never blended into DCF>",
//     "flags": ["material","moderate","limited","none"],
//     "entries": { "NVDA": {"flag": "material", "text": "..."}, ... } }
//
// Usage:
//   java OptionalityGap.java --self-test   (synthetic backend checks)
//   java OptionalityGap.java --validate    (checks the live backend file)
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OptionalityGap {

    static final String[] TICKERS = {
        "AMD","AMZN","AVGO","BRK.B","CELH","CRM","CVX","GOOG","GRAB","INTC",
        "IREN","JNJ","JPM","LLY","MA","META","MSFT","NFLX","NOW","NVDA",
        "ORCL","PFE","PLTR","QCOM","RH","SOFI","TSLA","UNH","V","WH","XOM"
    };
    static final Set<String> FLAGS = Set.of("material", "moderate", "limited", "none");
    static final int MAX_TEXT = 600; // v1: a short note, not an essay

    /** Minimal JSON parser: objects -> LinkedHashMap, arrays -> ArrayList,
     *  strings -> String. Numbers/bools/null rejected (backend is text-only). */
    static final class Json {
        private final String s;
        private int pos;
        private Json(String s) { this.s = s; }
        static Object parse(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.skipWs();
            if (j.pos != j.s.length()) throw new IllegalArgumentException("Trailing JSON at " + j.pos);
            return v;
        }
        private void skipWs() {
            while (pos < s.length() && " \t\n\r".indexOf(s.charAt(pos)) >= 0) pos++;
        }
        private Object value() {
            skipWs();
            char c = s.charAt(pos);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            throw new IllegalArgumentException("Non-text JSON value at " + pos);
        }
        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; skipWs();
            if (s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = str();
                skipWs(); pos++; // :
                m.put(key, value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Bad JSON at " + pos);
            }
        }
        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            pos++; skipWs();
            if (s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Bad JSON at " + pos);
            }
        }
        private String str() {
            StringBuilder b = new StringBuilder();
            pos++; // opening quote
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("Bad escape at " + pos);
                    }
                } else b.append(c);
            }
        }
    }

    static Path homeDir() {
        String home = System.getenv("HOME");
        return Paths.get((home == null || home.isEmpty())
                ? System.getProperty("user.home") : home);
    }

    /** Returns a list of problems; empty means valid. */
    @SuppressWarnings("unchecked")
    static List<String> validate(Object root) {
        List<String> problems = new ArrayList<>();
        if (!(root instanceof Map)) {
            problems.add("root is not an object");
            return problems;
        }
        Map<String, Object> m = (Map<String, Object>) root;
        Object method = m.get("method");
        if (!(method instanceof String) || ((String) method).isEmpty()) {
            problems.add("missing method disclaimer");
        } else {
            String ms = ((String) method).toLowerCase();
            if (!ms.contains("narrative") || !ms.contains("never")) {
                problems.add("method disclaimer must say narrative-only and never-blended");
            }
        }
        Object entries = m.get("entries");
        if (!(entries instanceof Map)) {
            problems.add("missing entries object");
            return problems;
        }
        Map<String, Object> em = (Map<String, Object>) entries;
        for (String t : TICKERS) {
            Object e = em.get(t);
            if (!(e instanceof Map)) { problems.add(t + ": missing entry"); continue; }
            Map<String, Object> eo = (Map<String, Object>) e;
            Object flag = eo.get("flag");
            if (!(flag instanceof String) || !FLAGS.contains(flag)) {
                problems.add(t + ": bad flag " + flag);
            }
            Object text = eo.get("text");
            if (!(text instanceof String) || ((String) text).isBlank()) {
                problems.add(t + ": empty text");
            } else if (((String) text).length() > MAX_TEXT) {
                problems.add(t + ": text exceeds " + MAX_TEXT + " chars");
            }
            for (String k : eo.keySet()) {
                if (!k.equals("flag") && !k.equals("text")) {
                    problems.add(t + ": unexpected key '" + k
                            + "' (no numeric valuation fields allowed)");
                }
            }
        }
        for (String k : em.keySet()) {
            boolean known = false;
            for (String t : TICKERS) if (t.equals(k)) { known = true; break; }
            if (!known) problems.add("unknown ticker entry: " + k);
        }
        return problems;
    }

    static String goodBackend() {
        StringBuilder b = new StringBuilder(
                "{\"asof\":\"2026-09-28\",\"method\":\"Narrative context only, shown alongside the DCF and never inside it.\",\"entries\":{");
        for (int i = 0; i < TICKERS.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(TICKERS[i]).append("\":{\"flag\":\"moderate\",\"text\":\"Note for ")
             .append(TICKERS[i]).append(".\"}");
        }
        return b.append("}}").toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length == 1 && args[0].equals("--validate")) {
            Path p = homeDir().resolve("workspace/dcf_csv/optionality_gap.json");
            List<String> problems = validate(Json.parse(
                    Files.readString(p, StandardCharsets.UTF_8)));
            if (problems.isEmpty()) {
                System.out.println("VERIFY OK: optionality_gap.json valid (" + TICKERS.length + " entries)");
            } else {
                problems.forEach(x -> System.out.println("PROBLEM: " + x));
                System.out.println("VERIFY FAILED");
                System.exit(1);
            }
            return;
        }
        System.err.println("usage: java OptionalityGap.java --self-test | --validate");
        System.exit(2);
    }

    static void selfTest() {
        SelfTest.reset();
        SelfTest.check("valid backend passes",
                validate(Json.parse(goodBackend())).isEmpty());
        String missing = goodBackend().replace("\"NVDA\":{\"flag\":\"moderate\",\"text\":\"Note for NVDA.\"},", "");
        SelfTest.check("missing ticker fails",
                validate(Json.parse(missing)).stream().anyMatch(x -> x.startsWith("NVDA")));
        String badFlag = goodBackend().replace(
                "\"NVDA\":{\"flag\":\"moderate\"", "\"NVDA\":{\"flag\":\"enormous\"");
        SelfTest.check("bad flag fails",
                validate(Json.parse(badFlag)).stream().anyMatch(x -> x.contains("bad flag")));
        String emptyText = goodBackend().replace(
                "\"text\":\"Note for NVDA.\"", "\"text\":\"\"");
        SelfTest.check("empty text fails",
                validate(Json.parse(emptyText)).stream().anyMatch(x -> x.contains("empty text")));
        String noMethod = goodBackend().replace(
                "\"method\":\"Narrative context only, shown alongside the DCF and never inside it.\",", "");
        SelfTest.check("missing disclaimer fails",
                validate(Json.parse(noMethod)).stream().anyMatch(x -> x.contains("disclaimer")));
        String numericField = goodBackend().replace(
                "\"NVDA\":{\"flag\":\"moderate\"", "\"NVDA\":{\"flag\":\"moderate\",\"value\":42.0");
        SelfTest.check("numeric valuation field rejected at parse",
                throwsIAE(numericField));
        String extraKey = goodBackend().replace(
                "\"NVDA\":{\"flag\":\"moderate\"", "\"NVDA\":{\"flag\":\"moderate\",\"source\":\"x\"");
        SelfTest.check("unexpected string key rejected by validate",
                validate(Json.parse(extraKey)).stream().anyMatch(x -> x.contains("unexpected key")));
        String longText = goodBackend().replace(
                "\"text\":\"Note for NVDA.\"", "\"text\":\"" + "x".repeat(MAX_TEXT + 1) + "\"");
        SelfTest.check("overlong text fails",
                validate(Json.parse(longText)).stream().anyMatch(x -> x.contains("exceeds")));
        String extra = goodBackend().replace("}}", "},\"ZZZ\":{\"flag\":\"none\",\"text\":\"?\"}}");
        SelfTest.check("unknown ticker fails",
                validate(Json.parse(extra)).stream().anyMatch(x -> x.contains("unknown ticker")));
        SelfTest.check("empty object fails",
                validate(Json.parse("{}")).stream().anyMatch(x -> x.contains("entries")));
        SelfTest.check("non-text JSON rejected",
                throwsIAE("{\"a\": 1}"));
        SelfTest.finish();
    }

    static boolean throwsIAE(String json) {
        try { Json.parse(json); return false; }
        catch (IllegalArgumentException e) { return true; }
    }
}
