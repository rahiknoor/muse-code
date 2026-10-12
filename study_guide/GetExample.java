// GetExample.java
// Java port of study_guide/get_example.py (kept as get_example.py.bak).
//
// Study-guide price examples — always from the shared morning price cache.
//
// Reads ~/workspace/stock_watch/price_cache.json (written by BatchQuotes)
// and ~/workspace/dcf_values.json, then prints example sentences for the
// investing-vocabulary study chat.
//
// Usage:
//     ~/workspace/java/jdk-21/bin/java ~/workspace/study_guide/GetExample.java                 # all tickers with both price + intrinsic
//     ~/workspace/java/jdk-21/bin/java ~/workspace/study_guide/GetExample.java META            # single ticker example
//     ~/workspace/java/jdk-21/bin/java ~/workspace/study_guide/GetExample.java --intrinsic     # intrinsic-value example lines
//
// Rule: never hardcode a market price in a study example — always read the cache.
// The cache may store quotes under "quotes" or "prices" (both handled).
//
// Notes on the port:
// - Java has no built-in JSON support, so a minimal JSON parser mirroring
//   Python's json module lives in the nested Json class below.
// - Money formatting (${v:,.2f}) is reproduced exactly, including Python's
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class GetExample {

    static final String HOME = homeDir();
    static final String CACHE = HOME + "/workspace/stock_watch/price_cache.json";
    static final String DCF = HOME + "/workspace/dcf_values.json";

    public static void main(String[] args) throws IOException {
        initUtf8Out(); // match Python stdout encoding (UTF-8)
        // 1. Load the price cache (quotes under "quotes" or "prices") and intrinsic values.
        Map<String, Object> data = Json.parseObject(read(CACHE));
        // data.get("quotes") or data.get("prices") or {} — first truthy one wins.
        Map<String, Object> quotes = firstTruthyMap(data.get("quotes"), data.get("prices"));
        Object asofRaw = data.containsKey("asof") ? data.get("asof") : "unknown";
        String asof = pyStr(asofRaw);
        Map<String, Object> dcf;
        if (Files.exists(Path.of(DCF))) {
            dcf = Json.parseObject(read(DCF));
        } else {
            dcf = new LinkedHashMap<>(); // like Python's except FileNotFoundError
        }

        // 2. Figure out which tickers to print.
        List<String> positional = new ArrayList<>();
        boolean intrinsicOnly = false;
        for (String a : args) {
            if (a.equals("--intrinsic")) {
                intrinsicOnly = true;
            } else if (!a.startsWith("--")) {
                positional.add(a.toUpperCase(Locale.ROOT)); // t.upper() in Python
            }
            // Other --flags are ignored for tickers, like Python's args filter.
        }
        List<String> tickers;
        if (!positional.isEmpty()) {
            tickers = positional;
        } else {
            // sorted(set(quotes) & set(dcf))
            Set<String> both = new TreeSet<>(quotes.keySet());
            both.retainAll(dcf.keySet());
            tickers = new ArrayList<>(both);
        }

        // 3. Print one example line per ticker.
        for (String t : tickers) {
            Double px = priceOf(quotes.get(t));
            Object dv = dcf.get(t);
            // dcf.get(t, {}).get("value") if isinstance(dcf.get(t), dict) else None
            Double iv = (dv instanceof Map) ? numOrNull(Json.obj(dv).get("value")) : null;
            if (px == null) {
                System.out.println(t + ": no cached price");
                continue;
            }
            if (intrinsicOnly) {
                if (iv == null) {
                    System.out.println(t + ": no intrinsic value recorded");
                } else {
                    System.out.println(t + ": model says $" + pyMoney(iv)
                            + " vs market $" + pyMoney(px) + " (cache " + asof + ")");
                }
            } else {
                if (iv != null) {
                    System.out.println("Example (" + t + "): market $" + pyMoney(px)
                            + " (cache " + asof + "), intrinsic $" + pyMoney(iv));
                } else {
                    System.out.println("Example (" + t + "): market $" + pyMoney(px)
                            + " (cache " + asof + ")");
                }
            }
        }
    }

    /** First truthy candidate, like Python's `a or b or {}`. */
    static Map<String, Object> firstTruthyMap(Object... candidates) {
        for (Object c : candidates) {
            if (truthy(c)) {
                return Json.obj(c);
            }
        }
        return new LinkedHashMap<>();
    }

    /** The cached price: a {"price": ...} dict, or a bare number. */
    static Double priceOf(Object q) {
        if (q == null) {
            return null;
        }
        Object v = (q instanceof Map) ? Json.obj(q).get("price") : q;
        return numOrNull(v);
    }

    /** A JSON number as Double; null stays null; anything else raises like Python's format would. */
    static Double numOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v ? 1.0 : 0.0; // Python formats bool as 0/1
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        throw new IllegalArgumentException("Cannot format non-numeric value: " + pyStr(v));
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
     * Like Python's f"${v:,.2f}": thousands separators, 2 decimals, correctly
     * rounded from the exact binary value with ties going to even.
     */
    static String pyMoney(double v) {
        String s = new BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
        boolean neg = s.startsWith("-");
        String body = neg ? s.substring(1) : s;
        int dot = body.indexOf('.');
        String intPart = body.substring(0, dot);
        String frac = body.substring(dot); // ".xx"
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < intPart.length(); i++) {
            if (i > 0 && (intPart.length() - i) % 3 == 0) {
                out.append(',');
            }
            out.append(intPart.charAt(i));
        }
        return (neg ? "-" : "") + out + frac;
    }

    // ------------------------------------------------------------------
    /**
     * Minimal JSON parser mirroring Python's json module: objects keep
     * insertion order (like Python dicts) and a duplicate key keeps the last
     * value. Only the parser is needed here (this script prints text lines).
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
