// DcfDisagreement.java
// Java port of dcf_disagreement.py (kept as dcf_disagreement.py.bak).
//
// DCF disagreement spotlight: compare our DCF vs NotebookLM's independent DCF
// per ticker using ~/workspace/valuation_scoreboard/scoreboard.json.
// Emits markdown for tickers where the two differ by more than 15%.
//
// Run: ~/workspace/java/jdk-21/bin/java ~/workspace/DcfDisagreement.java
//
// Notes on the port:
// - Java has no built-in JSON support, so a minimal JSON parser mirroring
//   Python's json module lives in the nested Json class below.
// - Python's assert is always on; Java's `assert` keyword is off by default,
//   so the port uses an explicit check() helper instead.
// - Float formatting (:.1f / :.2f) is reproduced exactly, including Python's
//   round-half-to-even (banker's) rounding.

import java.io.IOException;
import java.math.BigDecimal;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class DcfDisagreement {

    // Like Python's os.path.expanduser("~/..."): $HOME on Unix, else the JVM home.
    static final String HOME = homeDir();
    static final String SB = HOME + "/workspace/valuation_scoreboard/scoreboard.json";
    static final String NB_BATCH = HOME + "/workspace/goals/quarterly-sec-filing-watch-with-dcf-updates"
            + "/hidden_files/notebooklm_dcf_batch_2026-09-26.json";

    public static void main(String[] args) throws IOException {
        initUtf8Out(); // match Python stdout encoding (UTF-8)
        // 1. Load the scoreboard and the verified NotebookLM batch file.
        Map<String, Object> data = Json.parseObject(read(SB));
        List<Object> entries = Json.arr(data.get("entries"));
        Set<String> verifiedNb = verifiedNotebooklmTickers();

        // 2. Walk tickers in sorted order; take the latest entry per source group.
        Set<String> tickers = new TreeSet<>();
        for (Object e : entries) {
            tickers.add(Json.str(Json.obj(e).get("ticker")));
        }
        List<Spot> spots = new ArrayList<>();
        for (String t : tickers) {
            Map<String, Object> ours = latest(entries, t, Set.of("ours"));
            // A NotebookLM value only counts if the ticker is in the verified batch file.
            Map<String, Object> nblm = verifiedNb.contains(t) ? latest(entries, t, Set.of("notebooklm")) : null;
            if (ours == null || nblm == null || !truthy(nblm.get("value"))) {
                continue;
            }
            double oursVal = num(ours.get("value"));
            double nblmVal = num(nblm.get("value"));
            double div = Math.abs(oursVal - nblmVal) / Math.abs(nblmVal);
            if (div > 0.15) {
                spots.add(new Spot(t, ours, nblm, div));
            }
        }

        // 3. Print the markdown spotlight, biggest gaps first.
        if (spots.isEmpty()) {
            System.out.println("No DCF disagreements over 15% right now.");
            return;
        }
        System.out.println("# DCF disagreement spotlight (>15% apart)\n");
        spots.sort((a, b) -> Double.compare(b.div, a.div)); // stable, like Python's sorted()
        for (Spot s : spots) {
            double oursVal = num(s.ours.get("value"));
            double nblmVal = num(s.nblm.get("value"));
            Map<String, Object> hi = oursVal > nblmVal ? s.ours : s.nblm;
            Map<String, Object> lo = oursVal > nblmVal ? s.nblm : s.ours;
            double hiVal = num(hi.get("value"));
            double loVal = num(lo.get("value"));
            System.out.println("## " + s.ticker + " \u2014 " + pyStr(s.ours.get("company"))
                    + ": " + pyFixed(s.div * 100, 1) + "% apart");
            System.out.println("- Ours (" + pyStr(s.ours.get("source")) + ", " + pyStr(s.ours.get("date"))
                    + "): **$" + pyFixed(oursVal, 2) + "** \u2014 " + pyStr(s.ours.get("source_ref")));
            System.out.println("- NotebookLM (" + pyStr(s.nblm.get("date")) + "): **$"
                    + pyFixed(nblmVal, 2) + "** \u2014 " + pyStr(s.nblm.get("source_ref")));
            System.out.println("- Higher call: " + pyStr(hi.get("source")) + " by $"
                    + pyFixed(hiVal - loVal, 2) + " (" + pyFixed((hiVal / loVal - 1) * 100, 1)
                    + "% above the lower)");
            System.out.println("- Assumption sets: ours in `~/workspace/sec_filings/dcf_inputs_all.json`"
                    + " + DCF spreadsheet (slug `dcf-valuations`); NotebookLM's in the"
                    + " \"Stock Research SEC Filings\" notebook notes."
                    + " The gap is the assumption to interrogate.\n");
        }
    }

    /**
     * Tickers with a numeric value in the verified NotebookLM batch file.
     * Mirrors _guard_notebooklm_values in dcf_csv/rebuild_all.py: entries
     * lacking a verified batch record must never be presented as NotebookLM's
     * (2026-09-27 provenance correction).
     */
    static Set<String> verifiedNotebooklmTickers() {
        try {
            Map<String, Object> batch = Json.parseObject(read(NB_BATCH));
            // Python catches KeyError here -> empty set; a present-but-wrong type
            // raises, just like Python's AttributeError on .items().
            if (!batch.containsKey("values")) {
                return Set.of();
            }
            Map<String, Object> vals = Json.obj(batch.get("values"));
            Set<String> out = new TreeSet<>();
            for (Map.Entry<String, Object> e : vals.entrySet()) {
                Object v = e.getValue();
                // isinstance(v, (int, float)); bool counts too since bool subclasses int.
                if (v instanceof Number || v instanceof Boolean) {
                    out.add(e.getKey());
                }
            }
            return out;
        } catch (IOException | Json.JsonException e) {
            return Set.of(); // OSError / ValueError in Python
        }
    }

    /** Latest scoreboard entry for a ticker among the given sources (max by date string). */
    static Map<String, Object> latest(List<Object> entries, String ticker, Set<String> sources) {
        Map<String, Object> best = null;
        for (Object e : entries) {
            Map<String, Object> m = Json.obj(e);
            if (!ticker.equals(m.get("ticker")) || !sources.contains(m.get("source"))) {
                continue;
            }
            // Strict > keeps the FIRST maximal entry on ties, like Python's max().
            if (best == null || Json.str(m.get("date")).compareTo(Json.str(best.get("date"))) > 0) {
                best = m;
            }
        }
        return best;
    }

    /** One ticker where our DCF and NotebookLM's differ by more than 15%. */
    static final class Spot {
        final String ticker;
        final Map<String, Object> ours;
        final Map<String, Object> nblm;
        final double div;
        Spot(String ticker, Map<String, Object> ours, Map<String, Object> nblm, double div) {
            this.ticker = ticker;
            this.ours = ours;
            this.nblm = nblm;
            this.div = div;
        }
    }

    // ------------------------------------------------------------------
    // Small helpers shared by the ports in this batch.


    /**
     * Point System.out at UTF-8 explicitly. Python coerces stdout to UTF-8 even
     * under a POSIX locale; the JVM default here is ASCII, which would turn
     * non-ASCII characters (e.g. em dashes) into '?'.
     */
    static void initUtf8Out() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    }

    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8); // like Python open().read()
    }

    /** Numeric JSON value as double (bool counts as 0/1, like Python). */
    static double num(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v ? 1.0 : 0.0;
        }
        return ((Number) v).doubleValue();
    }

    /** Python truthiness for JSON-ish values (used where the script tests `if x`). */
    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Number) return ((Number) v).doubleValue() != 0.0;
        if (v instanceof Map) return !((Map<?, ?>) v).isEmpty();
        if (v instanceof List) return !((List<?>) v).isEmpty();
        return true;
    }

    /** Like Python's str(): None -> "None", True -> "True", floats use repr(). */
    static String pyStr(Object v) {
        if (v == null) return "None";
        if (v instanceof Boolean) return (Boolean) v ? "True" : "False";
        if (v instanceof Double) return Json.pyFloat((Double) v);
        if (v instanceof Float) return Json.pyFloat((Float) v);
        return v.toString();
    }

    /**
     * Like Python's f"{v:.{n}f}": correctly rounded from the exact binary value,
     * ties go to even (banker's rounding), always exactly n decimals.
     */
    static String pyFixed(double v, int n) {
        return new BigDecimal(v).setScale(n, RoundingMode.HALF_EVEN).toPlainString();
    }

    // ------------------------------------------------------------------
    /**
     * Minimal JSON parser mirroring Python's json module: objects keep
     * insertion order (like Python dicts) and a duplicate key keeps the last
     * value. Only the parser is needed here (this script prints markdown).
     */
    static final class Json {

        /** Parse a JSON document (like Python's json.loads). */
        static Object parse(String text) {
            Parser p = new Parser(text);
            Object v = p.parseValue();
            p.skipWs();
            if (p.pos != text.length()) {
                throw new JsonException("Extra data after JSON value at index " + p.pos);
            }
            return v;
        }

        /** Parse and cast to an object (like json.load(...) returning a dict). */
        @SuppressWarnings("unchecked")
        static Map<String, Object> parseObject(String text) {
            return (Map<String, Object>) parse(text);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object v) {
            return (Map<String, Object>) v;
        }

        @SuppressWarnings("unchecked")
        static List<Object> arr(Object v) {
            return (List<Object>) v;
        }

        static String str(Object v) {
            return (String) v;
        }

        static final class JsonException extends RuntimeException {
            JsonException(String msg) {
                super(msg);
            }
        }

        private static final class Parser {
            final String text;
            int pos;

            Parser(String text) {
                this.text = text;
            }

            void skipWs() {
                while (pos < text.length()) {
                    char c = text.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            char peek() {
                if (pos >= text.length()) {
                    throw new JsonException("Unexpected end of input");
                }
                return text.charAt(pos);
            }

            Object parseValue() {
                skipWs();
                char c = peek();
                switch (c) {
                    case '{': return parseObject();
                    case '[': return parseArray();
                    case '"': return parseString();
                    case 't': expect("true"); return Boolean.TRUE;
                    case 'f': expect("false"); return Boolean.FALSE;
                    case 'n': expect("null"); return null;
                    case 'N': expect("NaN"); return Double.NaN; // json.loads("NaN")
                    case 'I': expect("Infinity"); return Double.POSITIVE_INFINITY;
                    case '-':
                        if (text.startsWith("-Infinity", pos)) {
                            pos += 9;
                            return Double.NEGATIVE_INFINITY;
                        }
                        return parseNumber();
                    default: return parseNumber();
                }
            }

            void expect(String lit) {
                if (!text.startsWith(lit, pos)) {
                    throw new JsonException("Expected '" + lit + "' at index " + pos);
                }
                pos += lit.length();
            }

            Map<String, Object> parseObject() {
                pos++; // consume {
                Map<String, Object> map = new LinkedHashMap<>();
                skipWs();
                if (peek() == '}') {
                    pos++;
                    return map;
                }
                while (true) {
                    skipWs();
                    if (peek() != '"') {
                        throw new JsonException("Expected string key at index " + pos);
                    }
                    String key = parseString();
                    skipWs();
                    if (peek() != ':') {
                        throw new JsonException("Expected ':' at index " + pos);
                    }
                    pos++;
                    map.put(key, parseValue()); // last duplicate key wins, like Python
                    skipWs();
                    char c = peek();
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    if (c == '}') {
                        pos++;
                        return map;
                    }
                    throw new JsonException("Expected ',' or '}' at index " + pos);
                }
            }

            List<Object> parseArray() {
                pos++; // consume [
                List<Object> list = new ArrayList<>();
                skipWs();
                if (peek() == ']') {
                    pos++;
                    return list;
                }
                while (true) {
                    list.add(parseValue());
                    skipWs();
                    char c = peek();
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    if (c == ']') {
                        pos++;
                        return list;
                    }
                    throw new JsonException("Expected ',' or ']' at index " + pos);
                }
            }

            String parseString() {
                pos++; // consume opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (pos >= text.length()) {
                        throw new JsonException("Unterminated string");
                    }
                    char c = text.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        if (pos >= text.length()) {
                            throw new JsonException("Unterminated escape");
                        }
                        char e = text.charAt(pos++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u': sb.append(parseUnicodeEscape()); break;
                            default: throw new JsonException("Invalid escape '\\" + e + "'");
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }

            /** Parse a backslash-uXXXX escape, combining a following low surrogate like Python does. */
            String parseUnicodeEscape() {
                int hi = parseHex4();
                char h = (char) hi;
                if (Character.isHighSurrogate(h) && text.startsWith("\\u", pos)) {
                    int lo = hex4At(pos + 2);
                    char l = (char) lo;
                    if (Character.isLowSurrogate(l)) {
                        pos += 6;
                        return new String(Character.toChars(Character.toCodePoint(h, l)));
                    }
                }
                return String.valueOf(h);
            }

            int parseHex4() {
                int v = hex4At(pos);
                pos += 4;
                return v;
            }

            int hex4At(int at) {
                if (at + 4 > text.length()) {
                    throw new JsonException("Bad \\u escape");
                }
                int v = 0;
                for (int i = 0; i < 4; i++) {
                    int d = Character.digit(text.charAt(at + i), 16);
                    if (d < 0) {
                        throw new JsonException("Bad \\u escape");
                    }
                    v = v * 16 + d;
                }
                return v;
            }

            Object parseNumber() {
                int start = pos;
                if (peek() == '-') {
                    pos++;
                }
                if (pos >= text.length()) {
                    throw new JsonException("Bad number at index " + start);
                }
                char c = text.charAt(pos);
                if (c == '0') {
                    pos++;
                } else if (c >= '1' && c <= '9') {
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                } else {
                    throw new JsonException("Bad number at index " + start);
                }
                boolean isFloat = false;
                if (pos < text.length() && text.charAt(pos) == '.') {
                    isFloat = true;
                    pos++;
                    int d0 = pos;
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                    if (pos == d0) {
                        throw new JsonException("Bad number at index " + start);
                    }
                }
                if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                    isFloat = true;
                    pos++;
                    if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                        pos++;
                    }
                    int d0 = pos;
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                    if (pos == d0) {
                        throw new JsonException("Bad number at index " + start);
                    }
                }
                String num = text.substring(start, pos);
                if (isFloat) {
                    return Double.parseDouble(num);
                }
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException e) {
                    return new BigInteger(num); // Python ints have no size limit
                }
            }
        }

        /**
         * Shortest round-trip decimal like Python's repr(float): plain notation
         * for 1e-4 <= |v| < 1e16, else d[.ddd]e(+|-)XX (exponent signed, 2+ digits).
         */
        static String pyFloat(double v) {
            if (Double.isNaN(v)) return "NaN";
            if (v == Double.POSITIVE_INFINITY) return "Infinity";
            if (v == Double.NEGATIVE_INFINITY) return "-Infinity";
            String s = Double.toString(v); // shortest round-trip digits, like Python's
            int e = s.indexOf('E');
            if (e < 0) {
                return s; // plain notation already matches Python
            }
            boolean neg = s.charAt(0) == '-';
            String mant = neg ? s.substring(1, e) : s.substring(0, e);
            int exp = Integer.parseInt(s.substring(e + 1));
            String digits = mant.replace(".", "");
            int cut = digits.length();
            while (cut > 1 && digits.charAt(cut - 1) == '0') {
                cut--; // shortest repr never has real trailing zeros (only "d.0")
            }
            digits = digits.substring(0, cut);
            StringBuilder out = new StringBuilder();
            if (neg) {
                out.append('-');
            }
            if (exp >= -4 && exp < 16) {
                if (exp >= 0) {
                    int intLen = exp + 1;
                    if (digits.length() <= intLen) {
                        out.append(digits).append("0".repeat(intLen - digits.length())).append(".0");
                    } else {
                        out.append(digits, 0, intLen).append('.').append(digits.substring(intLen));
                    }
                } else {
                    out.append("0.").append("0".repeat(-exp - 1)).append(digits);
                }
                return out.toString();
            }
            out.append(digits.charAt(0));
            if (digits.length() > 1) {
                out.append('.').append(digits.substring(1));
            }
            out.append('e').append(exp < 0 ? '-' : '+');
            String expDigits = Integer.toString(Math.abs(exp));
            if (expDigits.length() < 2) {
                out.append('0');
            }
            return out.append(expDigits).toString();
        }
    }
}
