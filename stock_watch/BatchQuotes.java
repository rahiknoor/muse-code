// BatchQuotes.java
// Java port of stock_watch/batch_quotes.py (kept as batch_quotes.py.bak).
//
// Batch stock quotes for the portfolio watchlist plus the research watchlist
// (researched but not owned) — one HTTP request, not one search per ticker.
// Source: CNBC quote webservice batch endpoint (free, no API key).
//
// Usage:
//     ~/workspace/java/jdk-21/bin/java ~/workspace/stock_watch/BatchQuotes.java                 # print JSON {ticker: {...}} to stdout
//     ~/workspace/java/jdk-21/bin/java ~/workspace/stock_watch/BatchQuotes.java --write-cache   # also write ~/workspace/stock_watch/price_cache.json
//
// Output JSON per ticker:
//     {price, change, change_pct, open, high, low, previous_close, session_date, currency}
// Tickers that fail are listed under the top-level "errors" key; "ok" is false if any failed.
// Exit code: 0 when every ticker resolved, 2 when any failed (1 on unexpected errors).
//
// Notes on the port:
// - Same endpoint, same query params, same User-Agent header ("Mozilla/5.0"),
//   same 30 s timeout, and redirects followed like urllib does.
// - Java has no built-in JSON support, so a minimal JSON parser/serializer
//   mirroring Python's json module lives in the nested Json class below.
//   dumps() reproduces json.dumps(indent=1) byte-for-byte.

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class BatchQuotes {

    static final String HOME = homeDir();
    static final String WATCHLIST = HOME + "/workspace/stock_watch/watchlist.json";
    static final String RESEARCH_WATCHLIST = HOME + "/workspace/stock_watch/research_watchlist.json";
    static final String CACHE = HOME + "/workspace/stock_watch/price_cache.json";
    // Exact endpoint and params from the Python script (single {syms} placeholder).
    static final String URL_TEMPLATE = "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
            + "?symbols={syms}&requestMethod=quick&noform=1&partnerId=2&fund=1&exthrs=1&output=json";

    public static void main(String[] args) throws Exception {
        initUtf8Out(); // match Python stdout encoding (UTF-8)
        if (List.of(args).contains("--self-test")) { selfTest(); return; }
        // 1. Read the ticker lists: portfolio watchlist + research watchlist
        //    (researched but not owned). Union, portfolio order first.
        List<String> tickers = readTickerUnion();

        // 2. One HTTP request for the whole batch.
        Map<String, Object> quotes = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        fetch(tickers, quotes, errors);

        // 3. Assemble the result document (insertion order matches the Python dict).
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", errors.isEmpty());
        String asof = nowEt("yyyy-MM-dd HH:mm z"); // like strftime("%Y-%m-%d %H:%M %Z")
        result.put("asof", asof);
        result.put("source", "cnbc-batch");
        result.put("quotes", quotes);
        result.put("errors", errors);
        if (List.of(args).contains("--write-cache")) {
            result.put("cache_written", writeCache(quotes, asof));
        }
        System.out.println(Json.dumps(result)); // print() adds the trailing newline
        if (!errors.isEmpty()) {
            System.exit(2); // 0 when every ticker resolved, like `return 0 if not errors else 2`
        }
    }

    /** Portfolio tickers first, then research-only tickers, deduplicated.
     *  The research list is optional (missing file = portfolio only). */
    static List<String> readTickerUnion() throws Exception {
        return readTickerUnion(WATCHLIST, RESEARCH_WATCHLIST);
    }

    static List<String> readTickerUnion(String portfolioPath, String researchPath) throws Exception {
        List<String> tickers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] paths = {portfolioPath, researchPath};
        for (int i = 0; i < paths.length; i++) {
            List<Object> raw;
            try {
                raw = Json.arr(Json.parseObject(read(paths[i])).get("tickers"));
            } catch (Exception e) {
                if (i == 1) continue; // research list is optional
                throw e;
            }
            for (Object t : raw) {
                String s = Json.str(t);
                if (seen.add(s)) tickers.add(s);
            }
        }
        if (tickers.isEmpty()) throw new IllegalStateException("watchlist.json has no tickers");
        return tickers;
    }

    /** Self-test: run with --class-path ~/workspace/java-shared/classes. */
    static void selfTest() throws Exception {
        Path dir = Files.createTempDirectory("bq-test");
        Path pf = dir.resolve("watchlist.json");
        Path rf = dir.resolve("research_watchlist.json");
        Files.writeString(pf, "{\"tickers\": [\"AAPL\", \"MSFT\", \"NVDA\"]}");
        Files.writeString(rf, "{\"tickers\": [\"NVDA\", \"TSLA\"]}");
        // Overlap deduped, portfolio order first, research appended.
        SelfTest.checkEq("union-dedupe-order",
                List.of("AAPL", "MSFT", "NVDA", "TSLA"),
                readTickerUnion(pf.toString(), rf.toString()));
        // Missing research file = portfolio only.
        SelfTest.checkEq("union-missing-research",
                List.of("AAPL", "MSFT", "NVDA"),
                readTickerUnion(pf.toString(), dir.resolve("nope.json").toString()));
        // Empty research tickers = portfolio only.
        Files.writeString(rf, "{\"tickers\": []}");
        SelfTest.checkEq("union-empty-research",
                List.of("AAPL", "MSFT", "NVDA"),
                readTickerUnion(pf.toString(), rf.toString()));
        // Live files: union covers every portfolio ticker.
        List<String> live = readTickerUnion();
        SelfTest.check("union-live-nonempty", !live.isEmpty());
        SelfTest.check("union-live-unique", new HashSet<>(live).size() == live.size());
        // CNBC "UNCH" change fields (2026-09-29: PFE closed exactly unchanged at
        // $28.72 and was dropped from drop-alert coverage because parseNum("UNCH")
        // returned null). An unchanged quote is a 0.0 move, not missing data.
        Map<String, Object> unchQuotes = new LinkedHashMap<>();
        List<String> unchErrors = new ArrayList<>();
        String unchBody = "{\"FormattedQuoteResult\":{\"FormattedQuote\":[{\"symbol\":\"PFE\","
                + "\"code\":0,\"last\":28.72,\"change\":\"UNCH\",\"change_pct\":\"UNCH\","
                + "\"open\":28.72,\"high\":28.90,\"low\":28.60,\"previous_day_closing\":28.72,"
                + "\"last_timedate\":\"2026-09-29 16:10 ET\",\"currencyCode\":\"USD\"}]}}";
        parseQuotes(unchBody, List.of("PFE"), unchQuotes, unchErrors);
        SelfTest.check("UNCH ticker parsed into quotes", unchQuotes.containsKey("PFE"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pfe = unchQuotes.containsKey("PFE")
                ? (Map<String, Object>) unchQuotes.get("PFE") : null;
        SelfTest.checkEq("UNCH change_pct -> 0.0", 0.0,
                pfe != null ? pfe.get("change_pct") : null);
        SelfTest.checkEq("UNCH change -> 0.0", 0.0,
                pfe != null ? pfe.get("change") : null);
        SelfTest.finish();
    }

    /** One GET against the CNBC batch endpoint; fills quotes and errors. */
    static void fetch(List<String> tickers, Map<String, Object> quotes, List<String> errors) throws Exception {
        String syms = String.join("|", tickers);
        // Same endpoint and params as the Python script. java.net.URI rejects a raw '|'
        // in the query string, so the URI is built from components: the multi-arg
        // constructor percent-encodes the '|' separators as %7C, which the server
        // decodes back to '|' — the query the server sees is identical to Python's.
        String query = "symbols=" + quote(syms)
                + "&requestMethod=quick&noform=1&partnerId=2&fund=1&exthrs=1&output=json";
        URI uri = new URI("https", "quote.cnbc.com",
                "/quote-html-webservice/restQuote/symbolType/symbol", query, null);
        // Net.get() goes through curl (sandbox proxy policy blocks java.net.http).
        // Throws on non-200/IO error -> mark every ticker failed (fail-open, no fabrication).
        final String body;
        try {
            body = Net.get(uri.toString(), 30); // like urlopen(..., timeout=30)
        } catch (Exception e) {
            errors.addAll(tickers);
            return;
        }
        parseQuotes(body, tickers, quotes, errors);
    }

    /**
     * Write the price cache file for the --write-cache flag and return the cache
     * path for the "cache_written" field. Same document shape as the Python script.
     */
    static String writeCache(Map<String, Object> quotes, String asof) throws java.io.IOException {
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("asof", asof);
        cache.put("written_date", nowEt("yyyy-MM-dd")); // like strftime("%Y-%m-%d")
        cache.put("source", "cnbc-batch");
        cache.put("prices", new LinkedHashMap<>(quotes));
        write(CACHE, Json.dumps(cache)); // like open(CACHE, "w").write(json.dump(..., indent=1))
        return CACHE;
    }

    /**
     * Parse one CNBC batch-response body into the quotes map and errors list.
     * Same field mapping and skip rules as the Python script's fetch().
     */
    static void parseQuotes(String body, List<String> tickers,
                            Map<String, Object> quotes, List<String> errors) {
        Map<String, Object> data = Json.parseObject(body);
        // data.get("FormattedQuoteResult", {}) — the default only applies when the key is missing.
        Object fqr = data.containsKey("FormattedQuoteResult") ? data.get("FormattedQuoteResult")
                : new LinkedHashMap<String, Object>();
        List<Object> items = Json.arr(Json.obj(fqr).getOrDefault("FormattedQuote", new ArrayList<>()));

        Set<String> seen = new HashSet<>();
        for (Object q : items) {
            Map<String, Object> m = Json.obj(q);
            Object symRaw = m.get("symbol");
            String sym = symRaw instanceof String ? (String) symRaw : null;
            // if not sym or q.get("code") != 0 or not q.get("last"): continue
            if (sym == null || sym.isEmpty() || !isZero(m.get("code")) || !truthy(m.get("last"))) {
                continue;
            }
            seen.add(sym);
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("price", parseNum(m.get("last")));
            quote.put("change", parseChange(m.get("change")));
            quote.put("change_pct", parseChange(m.get("change_pct")));
            quote.put("open", parseNum(m.get("open")));
            quote.put("high", parseNum(m.get("high")));
            quote.put("low", parseNum(m.get("low")));
            quote.put("previous_close", parseNum(m.get("previous_day_closing")));
            quote.put("session_date", m.get("last_timedate"));
            // q.get("currencyCode") or "USD"
            quote.put("currency", truthy(m.get("currencyCode")) ? m.get("currencyCode") : "USD");
            quotes.put(sym, quote);
        }
        for (String t : tickers) {
            if (!seen.contains(t)) {
                errors.add(t);
            }
        }
    }

    /** q.get("code") != 0 -> keep only when code == 0 (0, 0.0, or False in Python). */
    static boolean isZero(Object code) {
        return (code instanceof Number && ((Number) code).doubleValue() == 0.0)
                || Boolean.FALSE.equals(code);
    }

    /**
     * Like the script's parse_num: None -> null; strip commas, % and whitespace;
     * "", "--", "N/A" -> null; otherwise float(s), unparseable -> null.
     */
    static Double parseNum(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue(); // like float(str(number))
        }
        String s = v.toString().replace(",", "").replace("%", "").strip();
        if (s.isEmpty() || s.equals("--") || s.equals("N/A")) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null; // like Python's float(s) raising ValueError
        }
    }

    /**
     * Like parseNum, but understands CNBC's "UNCH" literal in the change
     * fields: a quote that closed exactly unchanged (e.g. PFE 2026-09-29) is
     * a 0.0 move, NOT missing data. Without this, the pipeline's coverage
     * check drops the ticker and --verify fails loudly on a phantom gap.
     * Scoped to change fields only — price/open/high/low/previous_close keep
     * the strict parseNum (an "UNCH" price would be data corruption, and must
     * stay visible as null, not be masked as 0.0).
     */
    static Double parseChange(Object v) {
        if (v instanceof String s && s.strip().equalsIgnoreCase("UNCH")) {
            return 0.0;
        }
        return parseNum(v);
    }

    /**
     * Like urllib.parse.quote(s, safe="|"): letters, digits and _.-~ plus "|"
     * stay as-is; everything else becomes %XX (uppercase hex) of its UTF-8 bytes.
     */
    static String quote(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9')
                    || cp == '_' || cp == '.' || cp == '-' || cp == '~' || cp == '|') {
                out.appendCodePoint(cp);
            } else {
                byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    out.append(String.format("%%%02X", b));
                }
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /** Current time in America/New_York, formatted like Python's strftime. */
    static String nowEt(String pattern) {
        return ZonedDateTime.now(ZoneId.of("America/New_York"))
                .format(DateTimeFormatter.ofPattern(pattern, Locale.US));
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

    static String read(String path) throws java.io.IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8); // like Python open().read()
    }

    static void write(String path, String content) throws java.io.IOException {
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

    // ------------------------------------------------------------------
    /**
     * Minimal JSON parser/serializer mirroring Python's json module: objects keep
     * insertion order (like Python dicts), a duplicate key keeps the last value,
     * and dumps() reproduces json.dumps(indent=1) byte-for-byte.
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

        /** Serialize like Python's json.dumps(value, indent=1). */
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

        /** Escape a string like Python's json.dumps. */
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
                        if (c < 0x20 || c >= 0x7f) {
                            // ensure_ascii=True (the script's json default): escape
                            // non-ASCII with backslash-u hex escapes, lowercase, like Python
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
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
