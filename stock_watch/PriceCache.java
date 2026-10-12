// PriceCache.java
// Shared reader (and schema enforcer) for ~/workspace/stock_watch/price_cache.json,
// the single-writer morning price cache.
//
// Why this file exists (idea #39, approved 2026-09-27): several morning jobs used
// to fetch their own live prices for the same 31 tickers — the YouTube digest's
// upside math, the DCF gap watch, the bullish picks digest, the insider digest.
// Now ONE job (stock-watch-daily-closes, 7:30 AM ET) fetches all prices and every
// report job reads this cache instead. One fetch serves many jobs, and every
// morning output shows the same numbers. (Drop-alert jobs are NOT readers — they
// need real-time prices and keep their own live checks.)
//
// Rahik's rule — every number traces to a source: each cached entry carries its
// own "source" and "fetched_at". The --stamp mode below fills those in for cache
// files written before the rule existed, so old and new files behave the same.
//
// Usage (Java 17 single-file launch, no compile step):
//   ~/workspace/java/jdk-21/bin/java ~/workspace/stock_watch/PriceCache.java --get NVDA,META,AMZN
//       prints one "TICKER|price|fetched_at|source" line per ticker, e.g.
//         NVDA|225.07|2026-09-26 21:50 EDT|cnbc-batch
//       A ticker absent from the cache prints as "TICKER|MISSING" — the caller
//       must report it as unavailable, never invent a price for it.
//   ~/workspace/java/jdk-21/bin/java ~/workspace/stock_watch/PriceCache.java --asof
//       prints the cache's asof timestamp, written_date, and ticker count —
//       readers use the asof to label figures ("as of <asof>") when the cache
//       is older than this morning's refresh.
//   ~/workspace/java/jdk-21/bin/java ~/workspace/stock_watch/PriceCache.java --stamp
//       adds a missing per-ticker "source"/"fetched_at" (from the file's global
//       source/asof) and saves the file, but only if something was missing.
//
// Handles both cache shapes: {"prices": {...}} (current writers) and
// {"quotes": {...}} (older files).

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PriceCache {

    static final String HOME = homeDir();
    static final String CACHE = HOME + "/workspace/stock_watch/price_cache.json";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java PriceCache.java --get T1,T2 | --asof | --stamp");
            return;
        }
        Map<String, Object> root = obj(parse(read(CACHE)));
        switch (args[0]) {
            case "--get":
                if (args.length < 2) {
                    System.out.println("Usage: java PriceCache.java --get T1,T2,...");
                    return;
                }
                printPrices(root, args[1].split(","));
                break;
            case "--asof":
                printAsof(root);
                break;
            case "--stamp":
                stampCache(root);
                break;
            default:
                System.out.println("Usage: java PriceCache.java --get T1,T2 | --asof | --stamp");
        }
    }

    // ------------------------------------------------------------------
    // The three modes.

    /** Print "TICKER|price|fetched_at|source" lines for the requested tickers. */
    static void printPrices(Map<String, Object> root, String[] tickers) {
        Map<String, Object> quotes = quotesMap(root);
        String topAsof = strOr(root.get("asof"), "");
        String topSource = strOr(root.get("source"), "cnbc-batch");
        // Header line: readers use the asof to label figures ("as of <asof>").
        System.out.println("# asof: " + topAsof
                + " | written_date: " + strOr(root.get("written_date"), "")
                + " | tickers: " + quotes.size());
        for (String raw : tickers) {
            String ticker = raw.strip().toUpperCase();
            if (ticker.isEmpty()) {
                continue;
            }
            Object entry = quotes.get(ticker);
            if (!(entry instanceof Map)) {
                System.out.println(ticker + "|MISSING");
                continue;
            }
            Map<String, Object> q = obj(entry);
            String price = numStr(q.get("price"));
            String fetchedAt = strOr(q.get("fetched_at"), topAsof);
            String source = strOr(q.get("source"), topSource);
            System.out.println(ticker + "|" + price + "|" + fetchedAt + "|" + source);
        }
    }

    /** Print one line describing how fresh the cache is. */
    static void printAsof(Map<String, Object> root) {
        System.out.println("asof: " + strOr(root.get("asof"), "")
                + " | written_date: " + strOr(root.get("written_date"), "")
                + " | tickers: " + quotesMap(root).size()
                + " | source: " + strOr(root.get("source"), "cnbc-batch"));
    }

    /**
     * Fill in any missing per-ticker "source"/"fetched_at" from the file's
     * global source/asof, and save — but only when something was missing.
     * This is what keeps cache files written by older writers (or the Python
     * fallback) compliant with the per-entry source rule.
     */
    static void stampCache(Map<String, Object> root) throws IOException {
        Map<String, Object> quotes = quotesMap(root);
        String topAsof = strOr(root.get("asof"), "");
        String topSource = strOr(root.get("source"), "cnbc-batch");
        int stamped = 0;
        for (Object entry : quotes.values()) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<String, Object> q = obj(entry);
            if (!q.containsKey("source")) {
                q.put("source", topSource);
                stamped++;
            }
            if (!q.containsKey("fetched_at")) {
                q.put("fetched_at", topAsof);
                stamped++;
            }
        }
        if (stamped > 0) {
            write(CACHE, dumps(root));
            System.out.println("stamped " + stamped + " missing fields; cache saved.");
        } else {
            System.out.println("already stamped; no changes.");
        }
    }

    /** The per-ticker map, whichever top-level key the writer used. */
    static Map<String, Object> quotesMap(Map<String, Object> root) {
        Object prices = root.get("prices");
        if (prices instanceof Map) {
            return obj(prices);
        }
        Object quotes = root.get("quotes");
        if (quotes instanceof Map) {
            return obj(quotes);
        }
        return new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------
    // Small value helpers.

    static String strOr(Object v, String fallback) {
        return (v instanceof String && !((String) v).isEmpty()) ? (String) v : fallback;
    }

    /** Render a parsed number back to text (numbers keep their original literal). */
    static String numStr(Object v) {
        if (v instanceof Num) {
            return ((Num) v).literal;
        }
        if (v instanceof Double) {
            double d = (Double) v;
            return (d == Math.rint(d) && !Double.isInfinite(d)) ? Long.toString((long) d) : Double.toString(d);
        }
        return v == null ? "null" : v.toString();
    }

    /** Never use the Java user.home property here: it returns /root while $HOME
     *  is /home/hatch in this environment (see TOOLS.md). */
    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    static void write(String path, String content) throws IOException {
        Files.writeString(Path.of(path), content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser/serializer.
    //
    // Values: Map (object), List (array), String, Num (a number kept as its
    // original literal text, so --stamp round-trips prices byte-for-byte),
    // Boolean, null. dumps() writes the same 1-space-indent style the writers
    // use (Python's json.dumps(indent=1)).

    /** A JSON number kept as its original literal text. */
    static final class Num {
        final String literal;
        Num(String literal) {
            this.literal = literal;
        }
    }

    static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("Extra data after JSON value at index " + p.pos);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object v) {
        return (Map<String, Object>) v;
    }

    static String dumps(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, value, 0);
        return sb.toString();
    }

    static void writeJson(StringBuilder sb, Object v, int level) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeJsonString(sb, (String) v);
        } else if (v instanceof Num) {
            sb.append(((Num) v).literal);
        } else if (v instanceof Boolean) {
            sb.append((Boolean) v ? "true" : "false");
        } else if (v instanceof Map) {
            Map<String, Object> m = obj(v);
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                sb.append(" ".repeat(level + 1));
                writeJsonString(sb, e.getKey());
                sb.append(": ");
                writeJson(sb, e.getValue(), level + 1);
                first = false;
            }
            sb.append("\n").append(" ".repeat(level)).append("}");
        } else if (v instanceof List) {
            List<?> list = (List<?>) v;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(",\n");
                }
                sb.append(" ".repeat(level + 1));
                writeJson(sb, item, level + 1);
                first = false;
            }
            sb.append("\n").append(" ".repeat(level)).append("]");
        } else {
            throw new IllegalArgumentException("Not JSON-serializable: " + v.getClass());
        }
    }

    /** Quote a string the way Python's json.dumps does by default. */
    static void writeJsonString(StringBuilder sb, String s) {
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
                    if (c < 0x20 || c >= 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c)); // lowercase hex, like Python
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    static final class Parser {
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
                throw new IllegalArgumentException("Unexpected end of JSON input");
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
                default: return parseNumber();
            }
        }

        void expect(String lit) {
            if (!text.startsWith(lit, pos)) {
                throw new IllegalArgumentException("Expected '" + lit + "' at index " + pos);
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
                    throw new IllegalArgumentException("Expected string key at index " + pos);
                }
                String key = parseString();
                skipWs();
                if (peek() != ':') {
                    throw new IllegalArgumentException("Expected ':' at index " + pos);
                }
                pos++;
                map.put(key, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at index " + pos);
                }
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
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at index " + pos);
                }
            }
        }

        String parseString() {
            pos++; // consume opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw new IllegalArgumentException("Unterminated escape");
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
                        case 'u':
                            if (pos + 4 > text.length()) {
                                throw new IllegalArgumentException("Bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("Invalid escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        /** Keep the number's original literal text so re-serialization is exact. */
        Num parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw new IllegalArgumentException("Bad number at index " + start);
            }
            return new Num(text.substring(start, pos));
        }
    }
}
