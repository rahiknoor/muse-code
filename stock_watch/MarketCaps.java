// MarketCaps.java
// One method for "what's the market cap of these tickers?" — replaces the
// per-candidate finance-vertical browser searches in the bullish-picks digest,
// the insider/politician watch, and the buy-call alerts' STEP 7.
//
// Usage:
//     java MarketCaps.java T1,T2,T3         # prints {"T1": 5424000000000, "T2": null}
//     java MarketCaps.java --refresh-cache  # refetch the portfolio + research
//                                           # watchlist tickers,
//                                           # write ~/workspace/stock_watch/market_caps.json
//
// How it works: fresh cache entries (cache written today) are served from
// disk; anything missing is fetched in ONE batch request via the existing
// batch_quotes.py.bak fetcher — never one search per ticker. Values are
// whole USD. A null means the ticker did not resolve; the caller falls back
// to one targeted lookup, never a guess.

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarketCaps {

    static final String HOME = System.getenv("HOME");
    static final String CACHE = HOME + "/workspace/stock_watch/market_caps.json";
    static final String WATCHLIST = HOME + "/workspace/stock_watch/watchlist.json";
    static final String RESEARCH_WATCHLIST = HOME + "/workspace/stock_watch/research_watchlist.json";
    static final ZoneId ET = ZoneId.of("America/New_York");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java MarketCaps.java T1,T2 | --refresh-cache | --self-test");
            System.exit(2);
        }
        if (args[0].equals("--self-test")) { selfTest(); return; }
        if (args[0].equals("--refresh-cache")) {
            List<String> tickers = readTickerUnion();
            Map<String, Long> caps = fetchCaps(tickers);
            String today = LocalDate.now(ET).toString();
            StringBuilder sb = new StringBuilder("{\n");
            sb.append("  \"written_date\": \"").append(today).append("\",\n");
            sb.append("  \"asof\": \"").append(today).append("\",\n");
            sb.append("  \"source\": \"cnbc-batch\",\n");
            sb.append("  \"caps\": {\n");
            int i = 0;
            for (Map.Entry<String, Long> e : new TreeMap<>(caps).entrySet()) {
                sb.append("    \"").append(e.getKey()).append("\": ")
                  .append(e.getValue() == null ? "null" : e.getValue());
                if (++i < caps.size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("  }\n}\n");
            Files.writeString(Path.of(CACHE), sb.toString(), StandardCharsets.UTF_8);
            System.out.println("wrote " + CACHE + " (" + caps.size() + " tickers)");
            return;
        }
        List<String> tickers = List.of(args[0].split(","));
        Map<String, Long> cached = readCache();
        List<String> missing = new ArrayList<>();
        for (String t : tickers) {
            if (!cached.containsKey(t.trim().toUpperCase())) missing.add(t.trim().toUpperCase());
        }
        Map<String, Long> fresh = missing.isEmpty() ? Map.of() : fetchCaps(missing);
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < tickers.size(); i++) {
            String t = tickers.get(i).trim().toUpperCase();
            Long v = cached.containsKey(t) ? cached.get(t) : fresh.get(t);
            if (i > 0) sb.append(", ");
            sb.append("\"").append(t).append("\": ").append(v == null ? "null" : v);
        }
        sb.append("}");
        System.out.println(sb);
    }

    /** Caps from today's cache file; empty when the cache is missing or stale. */
    static Map<String, Long> readCache() throws Exception {
        Map<String, Long> out = new LinkedHashMap<>();
        Path p = Path.of(CACHE);
        if (!Files.exists(p)) return out;
        String body = Files.readString(p, StandardCharsets.UTF_8);
        Matcher wm = Pattern.compile("\"written_date\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"").matcher(body);
        if (!wm.find() || !wm.group(1).equals(LocalDate.now(ET).toString())) return out; // stale
        Matcher m = Pattern.compile("\"([A-Z.]+)\"\\s*:\\s*(\\d+|null)").matcher(body);
        while (m.find()) out.put(m.group(1), m.group(2).equals("null") ? null : Long.parseLong(m.group(2)));
        return out;
    }

    static List<String> readTickers(String path) throws Exception {
        String body = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"tickers\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(body);
        List<String> out = new ArrayList<>();
        if (m.find()) {
            Matcher tm = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
            while (tm.find()) out.add(tm.group(1));
        }
        return out;
    }

    /** Portfolio tickers first, then research-only tickers, deduplicated.
     *  The research list is optional (missing file = portfolio only). */
    static List<String> readTickerUnion() throws Exception {
        return readTickerUnion(WATCHLIST, RESEARCH_WATCHLIST);
    }

    static List<String> readTickerUnion(String portfolioPath, String researchPath) throws Exception {
        List<String> tickers = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String[] paths = {portfolioPath, researchPath};
        for (int i = 0; i < paths.length; i++) {
            List<String> raw;
            try {
                raw = readTickers(paths[i]);
            } catch (Exception e) {
                if (i == 1) continue; // research list is optional
                throw e;
            }
            for (String t : raw) if (seen.add(t)) tickers.add(t);
        }
        return tickers;
    }

    /** Self-test: run with --class-path ~/workspace/java-shared/classes. */
    static void selfTest() throws Exception {
        Path dir = Files.createTempDirectory("mc-test");
        Path pf = dir.resolve("watchlist.json");
        Path rf = dir.resolve("research_watchlist.json");
        Files.writeString(pf, "{\"tickers\": [\"AAPL\", \"MSFT\"]}");
        Files.writeString(rf, "{\"tickers\": [\"MSFT\", \"TSLA\"]}");
        SelfTest.checkEq("union-dedupe-order",
                List.of("AAPL", "MSFT", "TSLA"),
                readTickerUnion(pf.toString(), rf.toString()));
        SelfTest.checkEq("union-missing-research",
                List.of("AAPL", "MSFT"),
                readTickerUnion(pf.toString(), dir.resolve("nope.json").toString()));
        // Live files: union is non-empty, unique, and starts with the portfolio list.
        List<String> live = readTickerUnion();
        List<String> portfolio = readTickers(WATCHLIST);
        SelfTest.check("union-live-nonempty", !live.isEmpty());
        SelfTest.check("union-live-unique", new java.util.HashSet<>(live).size() == live.size());
        SelfTest.check("union-live-portfolio-first",
                live.subList(0, portfolio.size()).equals(portfolio));
        SelfTest.finish();
    }

    /**
     * One batch fetch for the given tickers; ticker -> market cap USD (or null).
     * The HTTP itself is delegated to stock_watch/batch_quotes.py.bak --tickers=...
     * because Java's HTTPS is blocked in this sandbox while Python's urllib
     * works; this class stays the single readable interface for the cron jobs.
     */
    static Map<String, Long> fetchCaps(List<String> tickers) throws Exception {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String t : tickers) out.put(t, null);
        if (tickers.isEmpty()) return out;
        Process p = new ProcessBuilder("python3",
                HOME + "/workspace/stock_watch/batch_quotes.py.bak",
                "--tickers=" + String.join(",", tickers))
                .redirectErrorStream(true).start();
        String body = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        // Each quote is "TICKER": { ... "market_cap_usd": N ... } (flat object).
        Matcher m = Pattern.compile("\"([A-Z.]+)\"\\s*:\\s*\\{[^{}]*\"market_cap_usd\"\\s*:\\s*(\\d+|null)")
                .matcher(body);
        while (m.find()) {
            String sym = m.group(1);
            if (out.containsKey(sym) && !m.group(2).equals("null")) {
                out.put(sym, Long.parseLong(m.group(2)));
            }
        }
        return out;
    }
}
