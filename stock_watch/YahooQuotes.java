// YahooQuotes.java — Yahoo Finance chart API as the FAILOVER quote source for the
// drop-alert pipeline (Rahik approved 2026-09-28; Stooq verified dead: 404 + JS bot-wall).
//
// Chain (DropAlertPipeline.fetchQuotes): BatchQuotes.java (CNBC, primary) ->
// batch_quotes.py.bak (CNBC, secondary) -> YahooQuotes.java (THIS FILE, tertiary).
// Yahoo is only ever consulted when both CNBC paths fail; it never runs otherwise.
//
// Endpoint: GET {base}/v8/finance/chart/{TICKER}?interval=1d&range=5d
// (base defaults to https://query1.finance.yahoo.com; override with the
// YAHOO_CHART_BASE env var — used by --self-test to point at an unreachable
// host and prove graceful degradation.)
//
// Output document — byte-shape compatible with BatchQuotes.java so
// DropAlertPipeline.parseFetch accepts it unchanged:
//     {ok, asof, source: "yahoo-chart-failover", quotes, errors}
// Per ticker: {price, change, change_pct, open, high, low, previous_close,
// session_date (YYYY-MM-DD, AlertGuards contract), currency}.
// price = regularMarketPrice; previous_close = chartPreviousClose;
// change/change_pct computed from those two; open/high/low from the latest
// daily bar. A ticker that fails lands in "errors" — NEVER fabricated.
//
// Fail-open: any per-ticker failure (HTTP error, bad payload, timeout,
// unreachable host) puts the ticker in "errors" and moves on. Exit 0 when
// every ticker resolved, 2 when any failed (same convention as BatchQuotes).
//
// Usage:
//     java ~/workspace/stock_watch/YahooQuotes.java                      # print JSON to stdout
//     java --class-path ~/workspace/java-shared/classes \
//          ~/workspace/stock_watch/YahooQuotes.java --self-test          # 8 checks, no network except one fast refused-connection
//
// Notes:
// - One request per ticker (31 for the watchlist), 10 s timeout each, 250 ms
//   between requests to stay polite. Worst case ~5 min; typical < 1 min.
// - Proxy env (https_proxy/HTTPS_PROXY, no_proxy/NO_PROXY) honored like urllib,
//   mirroring BatchQuotes.java — this sandbox reaches the internet via proxy.
// - Yahoo symbols with dots (BRK.B) are requested as BRK-B; the watchlist
//   ticker stays the map key.

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class YahooQuotes {

    static final String HOME = homeDir();
    static final String WATCHLIST = HOME + "/workspace/stock_watch/watchlist.json";
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String SOURCE = "yahoo-chart-failover";
    static final long POLITENESS_MS = 250;

    /** Test hook: --self-test sets this to an unreachable base to prove graceful degradation. */
    static String baseOverride = null;

    static String chartBase() {
        if (baseOverride != null) return baseOverride;
        String e = System.getenv("YAHOO_CHART_BASE");
        return (e != null && !e.isEmpty()) ? e : "https://query1.finance.yahoo.com";
    }

    public static void main(String[] args) throws Exception {
        initUtf8Out();
        if (List.of(args).contains("--self-test")) {
            selfTest();
            return;
        }
        List<String> tickers = readTickers();
        Map<String, Object> quotes = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        boolean first = true;
        for (String t : tickers) {
            if (!first) sleep(POLITENESS_MS);
            first = false;
            try {
                Map<String, Object> q = fetchOne(t);
                if (q != null) quotes.put(t, q);
                else errors.add(t);
            } catch (Exception e) {
                errors.add(t); // fail-open: never fabricate, never abort the batch
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", errors.isEmpty());
        result.put("asof", nowEt("yyyy-MM-dd HH:mm z"));
        result.put("source", SOURCE);
        result.put("quotes", quotes);
        result.put("errors", errors);
        System.out.println(Json.dumps(result));
        if (!errors.isEmpty()) System.exit(2);
    }

    /** Yahoo symbol form: dots become dashes (BRK.B -> BRK-B). The watchlist ticker stays the key. */
    static String yahooSymbol(String ticker) {
        return ticker.replace('.', '-');
    }

    /** Fetch one ticker; null when it cannot be resolved (caller records the error). */
    static Map<String, Object> fetchOne(String ticker) throws Exception {
        String url = chartBase() + "/v8/finance/chart/" + yahooSymbol(ticker) + "?interval=1d&range=5d";
        // Net.get() goes through curl (sandbox proxy policy blocks java.net.http).
        // Throws on non-200/IO error -> map to null (fail-open, same as before).
        final String body;
        try {
            body = Net.get(url);
        } catch (Exception e) {
            return null;
        }
        return parseChart(ticker, body);
    }

    /**
     * Parse one Yahoo chart response into the BatchQuotes-shaped quote map.
     * Returns null when the payload carries no usable price (fail-open).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseChart(String ticker, String body) {
        Map<String, Object> doc;
        try {
            doc = (Map<String, Object>) Json.parse(body);
        } catch (Exception e) {
            return null;
        }
        Map<String, Object> chart = obj(doc.get("chart"));
        if (chart == null) return null;
        List<Object> results = arr(chart.get("result"));
        if (results.isEmpty()) return null; // {"result":null,"error":{...}} — no data, no fabrication
        Map<String, Object> r0 = obj(results.get(0));
        if (r0 == null) return null;
        Map<String, Object> meta = obj(r0.get("meta"));
        if (meta == null) return null;

        Double price = num(meta.get("regularMarketPrice"));
        Double prevClose = num(meta.get("chartPreviousClose"));
        if (prevClose == null) prevClose = num(meta.get("previousClose"));

        // Latest daily bar for open/high/low (and as a price fallback).
        Double barOpen = null, barHigh = null, barLow = null, barClose = null;
        long barTs = -1;
        Map<String, Object> ind = obj(r0.get("indicators"));
        List<Object> quoteBars = ind == null ? List.of() : arr(ind.get("quote"));
        List<Object> timestamps = arr(r0.get("timestamp"));
        if (!quoteBars.isEmpty()) {
            Map<String, Object> q0 = obj(quoteBars.get(0));
            if (q0 != null) {
                List<Object> opens = arr(q0.get("open")), highs = arr(q0.get("high")),
                             lows = arr(q0.get("low")), closes = arr(q0.get("close"));
                for (int i = closes.size() - 1; i >= 0; i--) {
                    Double c = num(closes.get(i));
                    if (c != null) {
                        barClose = c;
                        barOpen = i < opens.size() ? num(opens.get(i)) : null;
                        barHigh = i < highs.size() ? num(highs.get(i)) : null;
                        barLow = i < lows.size() ? num(lows.get(i)) : null;
                        if (i < timestamps.size() && timestamps.get(i) instanceof Number)
                            barTs = ((Number) timestamps.get(i)).longValue();
                        break;
                    }
                }
            }
        }
        if (price == null) price = barClose;
        if (price == null || prevClose == null || prevClose == 0.0) return null;

        double change = price - prevClose;
        double changePct = change / prevClose * 100.0;

        // Session date in the exchange timezone (AlertGuards expects YYYY-MM-DD).
        ZoneId tz = ET;
        Object tzName = meta.get("exchangeTimezoneName");
        if (tzName instanceof String s && !s.isEmpty()) {
            try { tz = ZoneId.of(s); } catch (Exception ignored) { }
        }
        long ts = -1;
        Object rmt = meta.get("regularMarketTime");
        if (rmt instanceof Number) ts = ((Number) rmt).longValue();
        if (ts < 0) ts = barTs;
        String sessionDate = ts > 0
                ? ZonedDateTime.ofInstant(Instant.ofEpochSecond(ts), tz).format(DateTimeFormatter.ISO_LOCAL_DATE)
                : "";

        Map<String, Object> q = new LinkedHashMap<>();
        q.put("price", price);
        q.put("change", change);
        q.put("change_pct", changePct);
        q.put("open", barOpen);
        q.put("high", barHigh != null ? barHigh : num(meta.get("regularMarketDayHigh")));
        q.put("low", barLow != null ? barLow : num(meta.get("regularMarketDayLow")));
        q.put("previous_close", prevClose);
        q.put("session_date", sessionDate);
        Object ccy = meta.get("currency");
        q.put("currency", (ccy instanceof String s && !s.isEmpty()) ? s : "USD");
        return q;
    }

    // =====================================================================
    // --self-test (shared SelfTest helpers; one fast refused-connection, no other network)
    // =====================================================================

    static void selfTest() {
        SelfTest.check("canned chart parses", parseCanned() != null);
        Map<String, Object> q = parseCanned();
        SelfTest.checkEq("price = regularMarketPrice", 225.07, q.get("price"));
        SelfTest.checkEq("previous_close = chartPreviousClose", 222.27, q.get("previous_close"));
        SelfTest.check("change = price - prevClose", Math.abs(((Number) q.get("change")).doubleValue() - 2.80) < 1e-9);
        SelfTest.check("change_pct = change/prevClose*100",
                Math.abs(((Number) q.get("change_pct")).doubleValue() - (2.80 / 222.27 * 100.0)) < 1e-9);
        SelfTest.checkEq("open from latest bar", 225.1300048828125, q.get("open"));
        SelfTest.checkEq("high from latest bar", 226.94000244140625, q.get("high"));
        SelfTest.checkEq("low from latest bar", 223.1300048828125, q.get("low"));
        SelfTest.checkEq("currency", "USD", q.get("currency"));
        SelfTest.check("session_date is YYYY-MM-DD",
                q.get("session_date") instanceof String s && s.matches("\\d{4}-\\d{2}-\\d{2}"));

        SelfTest.check("error payload -> null (no fabrication)",
                parseChart("BOGUS", "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No data\"}}}") == null);
        SelfTest.check("empty result array -> null",
                parseChart("BOGUS", "{\"chart\":{\"result\":[],\"error\":null}}") == null);
        SelfTest.check("garbage body -> null", parseChart("BOGUS", "not json{{{") == null);
        SelfTest.check("missing price and no bar -> null",
                parseChart("BOGUS", "{\"chart\":{\"result\":[{\"meta\":{\"chartPreviousClose\":10.0},\"indicators\":{\"quote\":[{\"close\":[null]}]}}],\"error\":null}}") == null);

        // Unreachable host: every ticker must land in errors, nothing fabricated.
        String saved = baseOverride;
        baseOverride = "http://127.0.0.1:1";
        try {
            boolean threw = false;
            Map<String, Object> r = null;
            try {
                r = fetchOne("NVDA");
            } catch (Exception e) {
                threw = true;
            }
            SelfTest.check("unreachable Yahoo -> fetchOne throws or returns null (no fake quote)",
                    threw || r == null);
        } finally {
            baseOverride = saved;
        }

        SelfTest.checkEq("yahoo symbol mapping", "BRK-B", yahooSymbol("BRK.B"));
        SelfTest.checkEq("plain ticker unchanged", "NVDA", yahooSymbol("NVDA"));
        SelfTest.finish();
    }

    /** Canned NVDA chart response (shape captured 2026-09-28); values must match the checks above. */
    static Map<String, Object> parseCanned() {
        String body = "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"USD\",\"symbol\":\"NVDA\","
                + "\"exchangeTimezoneName\":\"America/New_York\",\"regularMarketPrice\":225.07,"
                + "\"chartPreviousClose\":222.27,\"regularMarketTime\":1790366400,"
                + "\"regularMarketDayHigh\":226.94,\"regularMarketDayLow\":223.13},"
                + "\"timestamp\":[1789990000,1790343000],"
                + "\"indicators\":{\"quote\":[{\"open\":[224.0,225.1300048828125],"
                + "\"high\":[225.0,226.94000244140625],\"low\":[223.0,223.1300048828125],"
                + "\"close\":[224.5,225.07000732421875],\"volume\":[100,200]}]}}],\"error\":null}}";
        return parseChart("NVDA", body);
    }

    // =====================================================================
    // Infrastructure (single-file launch: link java-shared via
    //   --class-path ~/workspace/java-shared/classes ; Net.get() handles HTTP)
    // =====================================================================

    static List<String> readTickers() throws Exception {
        Map<String, Object> wl = (Map<String, Object>) Json.parse(readFile(WATCHLIST));
        List<String> tickers = new ArrayList<>();
        for (Object t : (List<Object>) wl.get("tickers")) tickers.add(String.valueOf(t));
        if (tickers.isEmpty()) throw new IllegalStateException("watchlist.json has no tickers");
        return tickers;
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (o instanceof Map) ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) { return (o instanceof List) ? (List<Object>) o : List.of(); }

    static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s.strip()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    static String readFile(String path) throws java.io.IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    static String nowEt(String pattern) {
        return ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern(pattern, Locale.US));
    }

    static void initUtf8Out() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    }

    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /* Minimal JSON parser/serializer (parse + dumps), mirroring the pattern
     * BatchQuotes.java uses — this file must run standalone via single-file launch. */
    static final class Json {
        static Object parse(String text) {
            P p = new P(text);
            Object v = p.value();
            p.ws();
            if (p.pos != text.length()) throw new IllegalArgumentException("trailing characters");
            return v;
        }

        static String dumps(Object v) {
            if (v == null) return "null";
            if (v instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
            if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
            if (v instanceof Map<?, ?> m) {
                StringBuilder b = new StringBuilder("{");
                boolean f = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!f) b.append(",");
                    f = false;
                    b.append(dumps(String.valueOf(e.getKey()))).append(":").append(dumps(e.getValue()));
                }
                return b.append("}").toString();
            }
            if (v instanceof List<?> l) {
                StringBuilder b = new StringBuilder("[");
                boolean f = true;
                for (Object e : l) {
                    if (!f) b.append(",");
                    f = false;
                    b.append(dumps(e));
                }
                return b.append("]").toString();
            }
            return dumps(String.valueOf(v));
        }

        static final class P {
            final String s;
            int pos;
            P(String s) { this.s = s; }
            void ws() { while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++; }
            Object value() {
                ws();
                if (pos >= s.length()) throw new IllegalArgumentException("eof");
                char c = s.charAt(pos);
                switch (c) {
                    case '{': return obj();
                    case '[': return arr();
                    case '"': return str();
                    case 't': lit("true"); return Boolean.TRUE;
                    case 'f': lit("false"); return Boolean.FALSE;
                    case 'n': lit("null"); return null;
                    default: return num();
                }
            }
            void lit(String l) {
                if (!s.startsWith(l, pos)) throw new IllegalArgumentException("bad literal");
                pos += l.length();
            }
            Map<String, Object> obj() {
                Map<String, Object> m = new LinkedHashMap<>();
                pos++; ws();
                if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
                while (true) {
                    ws();
                    String k = str(); ws();
                    if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("':' expected");
                    pos++;
                    m.put(k, value()); ws();
                    if (pos >= s.length()) throw new IllegalArgumentException("unterminated object");
                    char c = s.charAt(pos++);
                    if (c == '}') return m;
                    if (c != ',') throw new IllegalArgumentException("',' or '}' expected");
                }
            }
            List<Object> arr() {
                List<Object> l = new ArrayList<>();
                pos++; ws();
                if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
                while (true) {
                    l.add(value()); ws();
                    if (pos >= s.length()) throw new IllegalArgumentException("unterminated array");
                    char c = s.charAt(pos++);
                    if (c == ']') return l;
                    if (c != ',') throw new IllegalArgumentException("',' or ']' expected");
                }
            }
            String str() {
                if (s.charAt(pos) != '"') throw new IllegalArgumentException("string expected");
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
                            case 'n': b.append('\n'); break;
                            case 'r': b.append('\r'); break;
                            case 't': b.append('\t'); break;
                            case 'u':
                                b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                pos += 4;
                                break;
                            default: b.append(e); break;
                        }
                    } else b.append(c);
                }
                throw new IllegalArgumentException("unterminated string");
            }
            Double num() {
                int st = pos;
                while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
                try { return Double.parseDouble(s.substring(st, pos)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("bad number"); }
            }
        }
    }
}
