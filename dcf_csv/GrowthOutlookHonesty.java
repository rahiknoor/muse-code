// GrowthOutlookHonesty.java — audit gap D1.
//
// Checks the honesty/candor contract on dcf_csv/growth_outlook.json
// (Muse's per-ticker growth-outlook ratings from the deep-research briefs):
//
//   1. Every rated ticker (outlook High/Moderate/Low) must have a thesis.
//      A missing thesis on a rated ticker is a FAIL — a rating without its
//      reasoning is not an assessment, it is a guess.
//   2. The thesis must be substantive: at least 200 characters.
//   3. The rating must be one of the rubric's known values.
//   4. A "Low" rating carries Rahik's candor requirement (2026-09-28): the
//      thesis must state PLAINLY that no meaningful growth outlook exists.
//      Lows that fail the honesty-pattern check are never auto-failed —
//      they go to a manual-review queue (a WARN finding quoting the
//      thesis), because candor is a judgment call, not a regex verdict.
//
// Null (not-yet-researched) tickers are skipped, never flagged.
//
// Honesty patterns (case-insensitive; documented here, reviewed with Rahik):
//   "no meaningful", "no credible", "no growth drivers",
//   "no defensible", "lack of", "limited growth",
//   "declining", "ex-growth", "no outlook", "no growth outlook"
// Dressed-up euphemisms ("cautiously positioned", "stable", "mature
// compounder") match none of these and land in the review queue.
//
// Modes: --self-test | --verify (Finding.split -> Verify.report, exit 1 on FAIL).
// Audit-write guard: REPORT only — this tool never edits growth_outlook.json.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GrowthOutlookHonesty {

    static final String HOME = home();
    static final String OUTLOOK_FILE = HOME + "/workspace/dcf_csv/growth_outlook.json";
    static final String CHECK_ID = "D1";
    static final int MIN_THESIS_CHARS = 200;

    static final List<String> KNOWN_RATINGS = List.of("High", "Moderate", "Low");

    /** Candor patterns for a Low rating: the thesis must say, plainly, that
     *  there is no meaningful growth outlook. Matched case-insensitively. */
    static final List<String> HONEST_LOW_PHRASES = List.of(
            "no meaningful",
            "no credible",
            "no growth drivers",
            "no defensible",
            "lack of",
            "limited growth",
            "declining",
            "ex-growth",
            "no outlook");

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /** One rated ticker from the backend. */
    static final class Entry {
        final String ticker;
        final String outlook;
        final String thesis;
        Entry(String ticker, String outlook, String thesis) {
            this.ticker = ticker; this.outlook = outlook; this.thesis = thesis;
        }
    }

    /** Pure honesty logic — takes parsed entries, returns findings.
     *  Separated from file I/O so --self-test can drive it with fixtures. */
    static List<Finding> audit(List<Entry> entries, String evidence) {
        List<Finding> findings = new ArrayList<>();
        int high = 0, mod = 0, low = 0;
        for (Entry e : entries) {
            if (e.outlook == null || e.outlook.isBlank()) {
                findings.add(Finding.fail(CHECK_ID, e.ticker, "outlook",
                        "one of " + KNOWN_RATINGS, "missing/blank",
                        evidence,
                        "set the outlook to High/Moderate/Low, or null the entry"
                                + " until the research brief lands"));
                continue;
            }
            if (!KNOWN_RATINGS.contains(e.outlook)) {
                findings.add(Finding.fail(CHECK_ID, e.ticker, "outlook",
                        "one of " + KNOWN_RATINGS, e.outlook,
                        evidence,
                        "fix the outlook to a known rating (rubric in growth_outlook.json)"));
                continue;
            }
            if (e.thesis == null || e.thesis.isBlank()) {
                findings.add(Finding.fail(CHECK_ID, e.ticker, "thesis",
                        "thesis >= " + MIN_THESIS_CHARS + " chars", "missing",
                        evidence,
                        "write the deep-research thesis before rating the ticker"));
                continue;
            }
            if (e.thesis.length() < MIN_THESIS_CHARS) {
                findings.add(Finding.fail(CHECK_ID, e.ticker, "thesis",
                        "thesis >= " + MIN_THESIS_CHARS + " chars",
                        "only " + e.thesis.length() + " chars",
                        evidence,
                        "expand the thesis with the research evidence (drivers, burdens, risks)"));
                continue;
            }
            if ("High".equals(e.outlook)) high++;
            else if ("Moderate".equals(e.outlook)) mod++;
            else {
                low++;
                if (!honestLow(e.thesis)) {
                    findings.add(Finding.warn(CHECK_ID, e.ticker, "low_candor",
                            "plain no-growth statement matching one of " + HONEST_LOW_PHRASES,
                            "thesis: \"" + e.thesis + "\"",
                            evidence,
                            "MANUAL REVIEW: reword to the candor standard (e.g. 'No meaningful"
                                    + " growth outlook — no credible growth drivers identified in"
                                    + " research') or justify why this Low is not a no-growth call"));
                }
            }
        }
        if (!entries.isEmpty()) {
            findings.add(Finding.info(CHECK_ID, "-", "summary",
                    entries.size() + " rated ticker(s) checked",
                    "High: " + high + ", Moderate: " + mod + ", Low: " + low,
                    evidence, "none"));
        } else {
            findings.add(Finding.info(CHECK_ID, "-", "summary",
                    "no rated tickers yet", "all entries null (not yet researched)",
                    evidence, "none — re-run after briefs land"));
        }
        return findings;
    }

    /** True when a Low thesis states, plainly, that no meaningful growth
     *  outlook exists. Case-insensitive substring match on the candor list. */
    static boolean honestLow(String thesis) {
        String t = thesis.toLowerCase(Locale.US);
        for (String p : HONEST_LOW_PHRASES) {
            if (t.contains(p)) return true;
        }
        return false;
    }

    /** Parse the tickers map out of growth_outlook.json. */
    @SuppressWarnings("unchecked")
    static List<Entry> readEntries(String jsonText) {
        List<Entry> out = new ArrayList<>();
        Map<String, Object> doc = (Map<String, Object>) Json.parse(jsonText);
        Object tickers = doc.get("tickers");
        if (!(tickers instanceof Map)) return out;
        for (Map.Entry<String, Object> te : ((Map<String, Object>) tickers).entrySet()) {
            String ticker = te.getKey();
            Object v = te.getValue();
            if (v == null) continue; // not yet researched — skipped, never flagged
            if (!(v instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) v;
            Object outlook = m.get("outlook");
            Object thesis = m.get("thesis");
            out.add(new Entry(ticker,
                    outlook == null ? null : String.valueOf(outlook),
                    thesis == null ? null : String.valueOf(thesis)));
        }
        return out;
    }

    // =====================================================================
    // Modes
    // =====================================================================

    static void verify() throws Exception {
        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        String text = Files.readString(Paths.get(OUTLOOK_FILE), StandardCharsets.UTF_8);
        List<Finding> findings = audit(readEntries(text),
                "growth_outlook.json (tickers map)");
        Finding.split(findings, infos, failures);
        int rated = readEntries(text).size();
        Verify.report(
                "VERIFY OK (GrowthOutlookHonesty): " + rated + " rated ticker(s),"
                        + " all theses present and substantive, no candor failures.",
                "VERIFY FAILED (GrowthOutlookHonesty): " + failures.size()
                        + " problem(s) found:",
                infos, failures);
    }

    // =====================================================================
    // Self-test
    // =====================================================================

    static void selfTest() {
        String honest = "No meaningful growth outlook — no credible growth drivers identified"
                + " in research. The business is declining and financial engineering"
                + " cannot paper over the revenue decay we documented across three filings.";
        String dressed = "The company is cautiously positioned as a mature compounder with"
                + " stable operations, a solid foundation, and prudent capital allocation."
                + " Management remains constructively optimistic about the long-term"
                + " trajectory while navigating near-term dynamics thoughtfully and"
                + " positioning the franchise well for durable shareholder value creation.";
        String thin = "Good growth prospects ahead with many exciting opportunities.";
        String full = "High conviction: serial acquirer targeting $50B revenue within the"
                + " decade with named synergy mechanisms, set against material leverage"
                + " and integration risk across three acquisitions in eleven months of"
                + " documented deal history and stated management targets.";

        // 1. Clean High passes.
        List<Finding> f = audit(List.of(new Entry("AAA", "High", full)), "test");
        SelfTest.check("clean High: no findings", f.size() == 1
                && f.get(0).severity == Finding.Severity.INFO);

        // 2. Missing thesis on a rated ticker = FAIL.
        f = audit(List.of(new Entry("BBB", "Moderate", null)), "test");
        SelfTest.checkEq("missing thesis is FAIL", Finding.Severity.FAIL,
                firstFail(f).severity);

        // 3. Short thesis = FAIL.
        f = audit(List.of(new Entry("CCC", "High", thin)), "test");
        SelfTest.check("short thesis is FAIL",
                f.stream().anyMatch(x -> x.severity == Finding.Severity.FAIL
                        && "thesis".equals(x.field)));

        // 4. Unknown rating = FAIL.
        f = audit(List.of(new Entry("DDD", "SkyHigh", full)), "test");
        SelfTest.check("unknown rating is FAIL",
                f.stream().anyMatch(x -> x.severity == Finding.Severity.FAIL
                        && "outlook".equals(x.field)));

        // 5. Honest Low passes.
        f = audit(List.of(new Entry("EEE", "Low", honest)), "test");
        SelfTest.check("honest Low passes",
                f.stream().noneMatch(x -> x.severity == Finding.Severity.FAIL
                        || x.severity == Finding.Severity.WARN));

        // 6. Dressed-up Low -> WARN (never FAIL), thesis quoted for review.
        f = audit(List.of(new Entry("FFF", "Low", dressed)), "test");
        SelfTest.check("dressed-up Low is WARN not FAIL",
                f.stream().anyMatch(x -> x.severity == Finding.Severity.WARN
                        && "low_candor".equals(x.field))
                        && f.stream().noneMatch(x -> x.severity == Finding.Severity.FAIL));
        SelfTest.checkContains("review queue quotes the thesis",
                f.stream().filter(x -> x.severity == Finding.Severity.WARN)
                        .findFirst().get().actual, "cautiously positioned");

        // 7. Empty backend -> INFO only.
        f = audit(List.of(), "test");
        SelfTest.check("no rated tickers: INFO only",
                f.size() == 1 && f.get(0).severity == Finding.Severity.INFO);

        // 8. Round-trip through the real parser on a synthetic file shape.
        String doc = "{\"tickers\": {\"QQQ\": {\"outlook\": \"Low\", \"thesis\": \"" + honest
                + "\"}, \"ZZZ\": null}}";
        List<Entry> parsed = readEntries(doc);
        SelfTest.checkEq("parser: one rated entry, one null skipped", 1, parsed.size());
        SelfTest.checkEq("parser: outlook survives", "Low", parsed.get(0).outlook);

        SelfTest.finish();
    }

    static Finding firstFail(List<Finding> findings) {
        for (Finding x : findings) {
            if (x.severity == Finding.Severity.FAIL) return x;
        }
        return findings.get(0);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "--self-test": selfTest(); break;
            case "--verify": verify(); break;
            default:
                System.err.println("Usage: java GrowthOutlookHonesty.java --self-test | --verify");
                System.exit(2);
        }
    }

    // =====================================================================
    // Minimal JSON reader (parse only), embedded so this file runs standalone
    // via `java GrowthOutlookHonesty.java` (single-file launch cannot link
    // sibling sources — the same convention BatchQuotes/DcfGapPipeline use).
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
