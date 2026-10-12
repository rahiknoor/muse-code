import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Creator-vs-creator cross-check (Rahik's 2026-09-29 ask: "cross check the
 * YouTubers" against each other).
 *
 * <p>Previously creators were checked against MY assumptions (STEP 5d), against
 * analysts on fresh actions (STEP 5f), and against their own previous call
 * (DetectRevisions) — but never against EACH OTHER. This closes that gap.
 *
 * <p>Reads: dcf_csv/creator_values.json ("values": every creator call per ticker),
 * dcf_csv/our_dcf.json (my DCF, shown as context only — never written).
 * Writes: creator_tracker/creator_crosscheck.json (current splits + seen
 * fingerprints for alert dedup).
 *
 * <p>A split = 2+ creators with a fresh (&le;90d) valuation-type call
 * (intrinsic_value|fair_value|price_target; buy_price excluded — a personal
 * purchase is not a valuation) whose max&minus;min spread exceeds 20%.
 * One stdout line per NEW split (fingerprint dedup: a split alerts once until
 * its creator/value set changes):
 * "&#x1F3AF; Creator split — &lt;T&gt; (&lt;spread&gt;% apart): &lt;C1&gt; $&lt;v1&gt;
 * (&lt;type&gt;) vs &lt;C2&gt; $&lt;v2&gt; (&lt;type&gt;) [my DCF $&lt;w&gt;]"
 *
 * <p>Flags are review prompts — never auto-filed as proposals (same standing
 * as STEP 5f analyst flags: file via Proposals.java only after review).
 *
 * <p>Usage:
 * <pre>
 *   java --class-path ~/workspace/lib/json/json-20240303.jar:~/workspace/java-shared/classes \
 *       ~/workspace/creator_tracker/CreatorCrossCheck.java --run
 *   java --class-path ~/workspace/lib/json/json-20240303.jar:~/workspace/java-shared/classes \
 *       ~/workspace/creator_tracker/CreatorCrossCheck.java --self-test
 * </pre>
 */
public class CreatorCrossCheck {

    /** Spread above which a creator disagreement earns one digest line. */
    static final double SPREAD_THRESHOLD_PCT = 20.0;
    /** Calls older than this never participate (same convention as the analyst cross-check). */
    static final int FRESH_DAYS = 90;

    static final java.util.Set<String> VALUATION_TYPES =
            java.util.Set.of("intrinsic_value", "fair_value", "price_target");

    /** One creator call, normalized. */
    record Call(String creator, String type, double value, LocalDate callDate, String videoTitle) {}

    /** One flagged split: the creators' latest fresh calls of the SAME metric type on a ticker, wide apart.
     *  A price target is never compared against a fair/intrinsic value — different quantities. */
    record Split(String ticker, String valueType, List<Call> calls, double spreadPct, Double myDcf) {}

    static Path homeDir() {
        String h = System.getenv("HOME");
        return Path.of(h != null ? h : System.getProperty("user.home"));
    }

    /** Pure logic: latest fresh valuation call per creator per ticker, flag wide spreads. */
    static List<Split> findSplits(Map<String, List<Call>> byTicker, Map<String, Double> myDcf,
                                  LocalDate today) {
        List<Split> out = new ArrayList<>();
        for (var e : byTicker.entrySet()) {
            String ticker = e.getKey();
            // Group by metric type first: a price target is never measured against a
            // fair/intrinsic value (Rahik 2026-10-05 — different quantities, not a "split").
            Map<String, List<Call>> byType = new LinkedHashMap<>();
            for (Call c : e.getValue()) {
                if (!VALUATION_TYPES.contains(c.type())) continue;
                long age = java.time.temporal.ChronoUnit.DAYS.between(c.callDate(), today);
                if (age < 0 || age > FRESH_DAYS) continue;
                byType.computeIfAbsent(c.type(), k -> new ArrayList<>()).add(c);
            }
            for (var te : byType.entrySet()) {
                String type = te.getKey();
                // Latest fresh call per creator, within this metric type.
                Map<String, Call> latest = new LinkedHashMap<>();
                for (Call c : te.getValue()) {
                    latest.merge(c.creator(), c, (a, b) ->
                            b.callDate().isAfter(a.callDate()) ? b : a);
                }
                if (latest.size() < 2) continue;
                List<Call> calls = new ArrayList<>(latest.values());
                calls.sort(Comparator.comparingDouble(Call::value));
                double lo = calls.get(0).value();
                double hi = calls.get(calls.size() - 1).value();
                if (lo <= 0) continue;
                double spread = (hi - lo) / lo * 100.0;
                if (spread > SPREAD_THRESHOLD_PCT) {
                    out.add(new Split(ticker, type, calls, spread, myDcf.get(ticker)));
                }
            }
        }
        out.sort(Comparator.comparing(Split::ticker).thenComparing(Split::valueType));
        return out;
    }

    /** Stable fingerprint of a split's creator/value set — alerts dedup on this. */
    static String fingerprint(Split s) {
        TreeMap<String, Double> m = new TreeMap<>();
        for (Call c : s.calls()) m.put(c.creator(), Math.round(c.value() * 100.0) / 100.0);
        StringBuilder sb = new StringBuilder(s.ticker()).append('|').append(s.valueType()).append('|');
        m.forEach((k, v) -> sb.append(k).append('=').append(v).append(','));
        return sb.toString();
    }

    static String flagLine(Split s) {
        StringBuilder sb = new StringBuilder("\uD83C\uDFAF Creator split \u2014 ")
                .append(s.ticker()).append(" (")
                .append(String.format(Locale.ROOT, "%.0f", s.spreadPct()))
                .append("% apart): ");
        for (int i = 0; i < s.calls().size(); i++) {
            Call c = s.calls().get(i);
            if (i > 0) sb.append(" vs ");
            sb.append(c.creator()).append(" $")
              .append(String.format(Locale.ROOT, "%.2f", c.value()))
              .append(" (").append(c.type().replace('_', ' ')).append(')');
        }
        sb.append(" [my DCF ");
        sb.append(s.myDcf() == null ? "\u2014"
                : "$" + String.format(Locale.ROOT, "%.2f", s.myDcf()));
        sb.append(']');
        return sb.toString();
    }

    static void run() throws IOException {
        Path ws = homeDir().resolve("workspace");
        LocalDate today = LocalDate.now();
        JSONObject cv = new JSONObject(Files.readString(
                ws.resolve("dcf_csv/creator_values.json"), StandardCharsets.UTF_8));
        JSONObject values = cv.getJSONObject("values");
        Map<String, List<Call>> byTicker = new LinkedHashMap<>();
        for (String ticker : values.keySet()) {
            JSONArray arr = values.getJSONArray(ticker);
            List<Call> calls = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String dateStr = o.optString("call_date", "");
                LocalDate d;
                try {
                    d = LocalDate.parse(dateStr);
                } catch (Exception ex) {
                    continue;
                }
                calls.add(new Call(o.optString("creator", "?"),
                        o.optString("type", ""), o.optDouble("value", 0),
                        d, o.optString("video_title", "")));
            }
            byTicker.put(ticker, calls);
        }
        Map<String, Double> myDcf = new LinkedHashMap<>();
        try {
            JSONObject ours = new JSONObject(Files.readString(
                    ws.resolve("dcf_csv/our_dcf.json"), StandardCharsets.UTF_8));
            JSONObject ov = ours.getJSONObject("values");
            for (String t : ov.keySet()) myDcf.put(t, ov.getJSONObject(t).optDouble("value", 0));
        } catch (Exception ex) {
            System.err.println("WARN: could not read our_dcf.json — my-DCF context omitted");
        }

        List<Split> splits = findSplits(byTicker, myDcf, today);

        Path statePath = ws.resolve("creator_tracker/creator_crosscheck.json");
        JSONObject state = new JSONObject();
        if (Files.exists(statePath)) {
            try {
                state = new JSONObject(Files.readString(statePath, StandardCharsets.UTF_8));
            } catch (Exception ex) {
                System.err.println("WARN: unreadable creator_crosscheck.json — starting fresh");
            }
        }
        JSONObject seen = state.optJSONObject("seen");
        if (seen == null) seen = new JSONObject();

        int newFlags = 0;
        JSONArray splitArr = new JSONArray();
        for (Split s : splits) {
            String fp = fingerprint(s);
            String seenKey = s.ticker() + "|" + s.valueType();
            JSONObject so = new JSONObject()
                    .put("ticker", s.ticker())
                    .put("type", s.valueType())
                    .put("spread_pct", Math.round(s.spreadPct() * 10.0) / 10.0)
                    .put("fingerprint", fp)
                    .put("my_dcf", s.myDcf() == null ? JSONObject.NULL : s.myDcf());
            JSONArray cs = new JSONArray();
            for (Call c : s.calls()) {
                cs.put(new JSONObject()
                        .put("creator", c.creator()).put("type", c.type())
                        .put("value", c.value())
                        .put("call_date", c.callDate().toString())
                        .put("video_title", c.videoTitle()));
            }
            so.put("calls", cs);
            splitArr.put(so);
            if (!fp.equals(seen.optString(seenKey, ""))) {
                System.out.println(flagLine(s));
                newFlags++;
            }
            seen.put(seenKey, fp);
        }
        // Prune tickers whose split resolved (spread back under threshold).
        for (String t : new ArrayList<>(seen.keySet())) {
            boolean still = false;
            for (Split s : splits) if ((s.ticker() + "|" + s.valueType()).equals(t)) { still = true; break; }
            if (!still) seen.remove(t);
        }
        JSONObject out = new JSONObject()
                .put("asof", today.toString())
                .put("note", "Creator-vs-creator valuation splits (>20% spread, fresh <=90d calls, "
                        + "same metric type only — a price target is never compared to a "
                        + "fair/intrinsic value). Flags are review prompts — never auto-filed as proposals.")
                .put("splits", splitArr)
                .put("seen", seen);
        Files.writeString(statePath, out.toString(2), StandardCharsets.UTF_8);
        System.out.println("creator_crosscheck: " + splits.size() + " split(s), "
                + newFlags + " new flag(s)");
    }

    static List<Call> calls(Call... cs) {
        return new ArrayList<>(List.of(cs));
    }

    static void selfTest() {
        LocalDate today = LocalDate.of(2026, 9, 29);
        Map<String, Double> dcf = Map.of("AAA", 100.0);

        // 1. Two creators 30% apart -> one flag.
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Parkev Tatevosian", "fair_value", 100, today.minusDays(1), "v1"),
                    new Call("Jeremy Lefebvre", "fair_value", 130, today.minusDays(2), "v2")));
            var r = findSplits(in, dcf, today);
           SelfTest.checkEq("30% spread flags once", 1, r.size());
            SelfTest.checkEq("spread value", 30.0, Math.round(r.get(0).spreadPct() * 10.0) / 10.0);
            SelfTest.check("flag line names both creators",
                    flagLine(r.get(0)).contains("Parkev Tatevosian")
                            && flagLine(r.get(0)).contains("Jeremy Lefebvre"));
        }
        // 2. 10% apart -> quiet.
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Parkev Tatevosian", "fair_value", 100, today.minusDays(1), "v1"),
                    new Call("Jeremy Lefebvre", "fair_value", 110, today.minusDays(2), "v2")));
            SelfTest.checkEq("10% spread stays quiet", 0, findSplits(in, dcf, today).size());
        }
        // 3. Single creator -> quiet.
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Parkev Tatevosian", "fair_value", 100, today.minusDays(1), "v1")));
            SelfTest.checkEq("single creator stays quiet", 0, findSplits(in, dcf, today).size());
        }
        // 4. Stale call excluded -> quiet even though values differ widely.
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Parkev Tatevosian", "fair_value", 100, today.minusDays(1), "v1"),
                    new Call("Jeremy Lefebvre", "fair_value", 200, today.minusDays(120), "v2")));
            SelfTest.checkEq("stale call excluded", 0, findSplits(in, dcf, today).size());
        }
        // 5. buy_price is not a valuation -> quiet.
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Parkev Tatevosian", "fair_value", 100, today.minusDays(1), "v1"),
                    new Call("Couch Investor", "buy_price", 200, today.minusDays(2), "v2")));
            SelfTest.checkEq("buy_price excluded", 0, findSplits(in, dcf, today).size());
        }
        // 6. Latest call per creator wins (old wide call superseded by new close call).
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Parkev Tatevosian", "fair_value", 100, today.minusDays(1), "v1"),
                    new Call("Jeremy Lefebvre", "fair_value", 200, today.minusDays(30), "v-old"),
                    new Call("Jeremy Lefebvre", "fair_value", 105, today.minusDays(2), "v-new")));
            SelfTest.checkEq("latest per creator wins", 0, findSplits(in, dcf, today).size());
        }
        // 7. Fingerprint stable across identical inputs.
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Parkev Tatevosian", "fair_value", 100, today.minusDays(1), "v1"),
                    new Call("Jeremy Lefebvre", "fair_value", 130, today.minusDays(2), "v2")));
            var a = findSplits(in, dcf, today);
            var b = findSplits(in, dcf, today);
            SelfTest.checkEq("fingerprint stable", fingerprint(a.get(0)), fingerprint(b.get(0)));
        }
        // 8. Cross-type wide spread stays quiet: a price target is not an intrinsic value.
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Parkev Tatevosian", "fair_value", 100, today.minusDays(1), "v1"),
                    new Call("Chris Sain", "price_target", 250, today.minusDays(2), "v2")));
            SelfTest.checkEq("fair_value vs price_target never splits", 0, findSplits(in, dcf, today).size());
        }
        // 9. Same-type price targets still split.
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Chris Sain", "price_target", 100, today.minusDays(1), "v1"),
                    new Call("All In Investing", "price_target", 135, today.minusDays(2), "v2")));
            var r = findSplits(in, dcf, today);
            SelfTest.checkEq("price_target vs price_target splits", 1, r.size());
            SelfTest.checkEq("split carries metric type", "price_target", r.get(0).valueType());
        }
        // 10. Same ticker splits independently per type.
        {
            Map<String, List<Call>> in = Map.of("AAA", calls(
                    new Call("Parkev Tatevosian", "fair_value", 100, today.minusDays(1), "v1"),
                    new Call("Jeremy Lefebvre", "fair_value", 130, today.minusDays(2), "v2"),
                    new Call("Chris Sain", "price_target", 500, today.minusDays(1), "v3")));
            var r = findSplits(in, dcf, today);
            SelfTest.checkEq("only same-type pair splits", 1, r.size());
            SelfTest.checkEq("split is the fair_value pair", "fair_value", r.get(0).valueType());
        }
        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        run();
    }
}
