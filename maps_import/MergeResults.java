// MergeResults.java
// Java port of maps_import/merge_results.py (kept as merge_results.py.bak).
//
// Merges one batch of closure-scan decisions (/tmp/merge_batch.json, a list of
// {"index": n, "status": s, "evidence": e}) into
// ~/workspace/maps_import/closed_scan_part_2.json, keyed by place title.
// Prints the total record count and per-status counts.
//
// Run: ~/workspace/java/jdk-21/bin/java ~/workspace/maps_import/MergeResults.java
//
// Notes on the port:
// - Java has no built-in JSON support, so a minimal JSON parser/serializer
//   mirroring Python's json module lives in the nested Json class below.
//   dumps() reproduces json.dump(indent=1, ensure_ascii=False) byte-for-byte.
// - Python dicts keep insertion order and keep their position on key update;
//   LinkedHashMap behaves the same way here.

import java.io.IOException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MergeResults {

    static final String HOME = homeDir();
    static final String PART = HOME + "/workspace/maps_import/closed_scan_part_2.json";
    static final String SRC = HOME + "/workspace/maps_import/saved_places.json";
    static final String BATCH = "/tmp/merge_batch.json"; // [{"index": n, "status": s, "evidence": e}]

    public static void main(String[] args) throws IOException {
        initUtf8Out(); // match Python stdout encoding (UTF-8)
        // 1. Load saved places and the existing part file (empty if it doesn't exist yet).
        List<Object> src = Json.parseArray(read(SRC));
        Map<String, Object> byTitle = new LinkedHashMap<>();
        if (Files.exists(Path.of(PART))) {
            for (Object e : Json.parseArray(read(PART))) {
                Map<String, Object> m = Json.obj(e);
                byTitle.put(Json.str(m.get("title")), m);
            }
        }

        // 2. Apply each batch decision, keyed by the place's title.
        for (Object b : Json.parseArray(read(BATCH))) {
            Map<String, Object> dec = Json.obj(b);
            Map<String, Object> r = Json.obj(src.get(listIndex(dec.get("index"), src.size())));
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("title", r.get("title"));
            rec.put("list", r.get("list"));
            rec.put("url", r.get("url"));
            rec.put("status", dec.get("status"));
            rec.put("evidence", dec.get("evidence"));
            // Dict assignment updates in place and keeps the original position, like Python.
            byTitle.put(Json.str(r.get("title")), rec);
        }

        // 3. Write the full part file and print the status counts.
        write(PART, Json.dumps(new ArrayList<>(byTitle.values())));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object e : byTitle.values()) {
            String st = pyStr(Json.obj(e).get("status"));
            counts.put(st, counts.getOrDefault(st, 0) + 1);
        }
        // print('total:', len(byTitle), counts) -> "total: 143 {'closed': 140, ...}"
        System.out.println("total: " + byTitle.size() + " " + pyDictStr(counts));
    }

    /**
     * Like Python's list[i]: the index must be integral (bool counts, like Python),
     * negatives count from the end, out-of-range raises.
     */
    static int listIndex(Object index, int size) {
        long i;
        if (index instanceof Boolean) {
            i = (Boolean) index ? 1 : 0;
        } else if (index instanceof Number) {
            if (index instanceof Double || index instanceof Float) {
                throw new IllegalArgumentException(
                        "list indices must be integers or slices, not float");
            }
            i = ((Number) index).longValue();
        } else {
            throw new IllegalArgumentException(
                    "list indices must be integers or slices, not " + index.getClass().getSimpleName());
        }
        if (i < 0) {
            i += size;
        }
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("list index out of range");
        }
        return (int) i;
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

    static void write(String path, String content) throws IOException {
        // like Python open(path, "w").write(): UTF-8, no trailing newline added
        Files.writeString(Path.of(path), content, StandardCharsets.UTF_8);
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
     * Like Python's repr(s) for strings: single quotes unless the string
     * contains a single quote but no double quote.
     */
    static String pyRepr(String s) {
        boolean hasSingle = s.indexOf('\'') >= 0;
        boolean hasDouble = s.indexOf('"') >= 0;
        char q = (hasSingle && !hasDouble) ? '"' : '\'';
        StringBuilder out = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == q) {
                out.append('\\').append(q);
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20 || c == 0x7f) {
                out.append(String.format("\\x%02x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append(q).toString();
    }

    /** Like Python's str(dict): {'key': value, ...} with repr'd keys. */
    static String pyDictStr(Map<?, ?> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            Object k = e.getKey();
            sb.append(k instanceof String ? pyRepr((String) k) : pyStr(k));
            sb.append(": ").append(pyStr(e.getValue()));
            first = false;
        }
        return sb.append("}").toString();
    }

    // ------------------------------------------------------------------
    /**
     * Minimal JSON parser/serializer mirroring Python's json module: objects keep
     * insertion order (like Python dicts), a duplicate key keeps the last value,
     * and dumps() reproduces json.dump(indent=1, ensure_ascii=False) byte-for-byte.
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

        /** Parse a JSON array and cast it (like json.load(...) returning a list). */
        @SuppressWarnings("unchecked")
        static List<Object> parseArray(String text) {
            return (List<Object>) parse(text);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object v) {
            return (Map<String, Object>) v;
        }

        static String str(Object v) {
            return (String) v;
        }

        /** Serialize like Python's json.dumps(value, indent=1, ensure_ascii=False). */
        static String dumps(Object value) {
            StringBuilder sb = new StringBuilder();
            write(sb, value, 0);
            return sb.toString();
        }

        private static void write(StringBuilder sb, Object v, int level) {
            if (v == null) {
                sb.append("null");
                return;
            }
            if (v instanceof String) {
                writeString(sb, (String) v);
                return;
            }
            if (v instanceof Boolean) {
                sb.append((Boolean) v ? "true" : "false");
                return;
            }
            if (v instanceof Double) {
                sb.append(pyFloat((Double) v));
                return;
            }
            if (v instanceof Float) {
                sb.append(pyFloat((Float) v));
                return;
            }
            if (v instanceof Number) { // Long, Integer, BigInteger
                sb.append(v.toString());
                return;
            }
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) v;
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                String pad = " ".repeat(level + 1);
                String close = " ".repeat(level);
                boolean first = true;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    sb.append(pad);
                    writeString(sb, e.getKey());
                    sb.append(": ");
                    write(sb, e.getValue(), level + 1);
                    first = false;
                }
                sb.append('\n').append(close).append('}');
                return;
            }
            if (v instanceof List) {
                List<?> l = (List<?>) v;
                if (l.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append("[\n");
                String pad = " ".repeat(level + 1);
                String close = " ".repeat(level);
                boolean first = true;
                for (Object item : l) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    sb.append(pad);
                    write(sb, item, level + 1);
                    first = false;
                }
                sb.append('\n').append(close).append(']');
                return;
            }
            throw new JsonException("Not JSON-serializable: " + v.getClass());
        }

        /** Escape a string like Python's json.dumps with ensure_ascii=False. */
        private static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c); // ensure_ascii=False: raw non-ASCII, like Python
                        }
                }
            }
            sb.append('"');
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
