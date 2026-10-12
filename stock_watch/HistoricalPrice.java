// HistoricalPrice.java
// One method for "what did this stock close at on/after this date?" —
// replaces the ad-hoc web searches for 30/60/90-day prices in the
// parkev-hit-rate-check job.
//
// Usage:
//     java HistoricalPrice.java TICKER YYYY-MM-DD [YYYY-MM-DD ...]
//
// For each requested date it returns the close on that date, or — when the
// date falls on a weekend or holiday — the next trading day's close, walking
// forward itself. No searches, no guessing.
//
// Output: {"TICKER": {"2026-06-27": {"date_used": "2026-06-29", "close": 208.64}}}
// A null close means no trading data within 14 days after the requested date.
//
// The HTTP fetch is delegated to stock_watch/batch_quotes.py.bak --history=...
// (Yahoo chart API) because Java's HTTPS is blocked in this sandbox while
// Python's urllib works; this class stays the single readable interface.

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HistoricalPrice {

    static final String HOME = System.getenv("HOME");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final int WALK_DAYS = 14; // how far forward to look past weekends/holidays

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java HistoricalPrice.java TICKER YYYY-MM-DD [YYYY-MM-DD ...]");
            System.exit(2);
        }
        String ticker = args[0].trim().toUpperCase();
        List<LocalDate> wanted = new ArrayList<>();
        for (int i = 1; i < args.length; i++) wanted.add(LocalDate.parse(args[i].trim(), DAY));

        LocalDate from = wanted.stream().min(LocalDate::compareTo).get();
        LocalDate to = wanted.stream().max(LocalDate::compareTo).get().plusDays(WALK_DAYS);
        Map<String, Double> prices = fetchHistory(ticker, from, to);

        StringBuilder sb = new StringBuilder("{\"" + ticker + "\": {");
        for (int i = 0; i < wanted.size(); i++) {
            LocalDate d = wanted.get(i);
            LocalDate used = null;
            for (int step = 0; step <= WALK_DAYS; step++) {
                LocalDate cand = d.plusDays(step);
                if (prices.containsKey(cand.format(DAY))) { used = cand; break; }
            }
            if (i > 0) sb.append(", ");
            sb.append("\"").append(d.format(DAY)).append("\": ");
            if (used == null) {
                sb.append("{\"date_used\": null, \"close\": null}");
            } else {
                sb.append("{\"date_used\": \"").append(used.format(DAY)).append("\", ");
                sb.append("\"close\": ").append(prices.get(used.format(DAY))).append("}");
            }
        }
        sb.append("}}");
        System.out.println(sb);
    }

    /** Fetch {YYYY-MM-DD: close} for the range via the Python --history mode. */
    static Map<String, Double> fetchHistory(String ticker, LocalDate from, LocalDate to) throws Exception {
        Map<String, Double> out = new TreeMap<>();
        Process p = new ProcessBuilder("python3",
                HOME + "/workspace/stock_watch/batch_quotes.py.bak",
                "--history=" + ticker + "," + from.format(COMPACT) + "," + to.format(COMPACT))
                .redirectErrorStream(true).start();
        String body = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        Matcher m = Pattern.compile("\"(\\d{4}-\\d{2}-\\d{2})\"\\s*:\\s*(\\d+\\.?\\d*)").matcher(body);
        while (m.find()) out.put(m.group(1), Double.parseDouble(m.group(2)));
        return out;
    }
}
