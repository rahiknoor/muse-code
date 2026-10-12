import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Proactive thesis-move detector (idea #4, approved 2026-09-28).
 *
 * <p>Surfaces "this new data moves your thesis on X" moments on its own —
 * silent when nothing is material. Report-only: it NEVER writes
 * blessed.json or any value backend, and it writes no files at all.
 * State lives in {@code ~/workspace/thesis_watch/state.json}; the caller
 * (the dcf-gap-watch cron step) writes the printed {@code state_update}
 * document after delivering any alerts.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --run} — read the four valuation backends + the price cache
 *       (read-only), diff every holding against state.json, print JSON with
 *       status / alerts / state_update. First run seeds the state silently
 *       (status "seeded"). Exit 0; stderr carries the one-line human summary
 *       ("no material moves" when quiet).</li>
 *   <li>{@code --self-test} — shared SelfTest assertions over the detection
 *       core with synthetic data.</li>
 *   <li>{@code --verify} — structural health check over the real backends
 *       and state file (shared Verify report tail).</li>
 * </ul>
 *
 * <p>Alert rules (conservative, to stay silent):
 * <ol>
 *   <li>Any valuation source's value moved &gt;10% since last check
 *       (ours, notebooklm, creator, analyst). Market price is deliberately
 *       excluded here — it is covered by rule 3, and a standalone price-move
 *       rule would duplicate the drop-alert pipeline on a daily cron.</li>
 *   <li>A NEW valuation call (intrinsic_value / fair_value — not buy_price)
 *       from a followed creator (Parkev Tatevosian, Jeremy Lefebvre,
 *       Couch Investor) that differs &gt;10% from our DCF.</li>
 *   <li>The |our-DCF − market-price| spread newly crossed 40% in either
 *       direction. Zero/negative DCF values are skipped.</li>
 * </ol>
 */
public class ThesisMoveDetector {

    static final String HOME = homeDir();
    static final String BLESSED = HOME + "/workspace/dcf_csv/blessed.json";
    static final String CREATOR_VALUES = HOME + "/workspace/dcf_csv/creator_values.json";
    static final String ANALYST = HOME + "/workspace/dcf_csv/analyst_targets.json";
    static final String CACHE = HOME + "/workspace/stock_watch/price_cache.json";
    static final String WATCHLIST = HOME + "/workspace/stock_watch/watchlist.json";
    static final String JOB_ID = "thesis-move-detector";
    static final double MOVE_THRESHOLD = 0.10;    // alert when a value moved >10%
    static final double SPREAD_THRESHOLD = 0.40;  // alert when |spread| newly crosses 40%
    static final Set<String> FOLLOWED_CREATORS =
            Set.of("Parkev Tatevosian", "Jeremy Lefebvre", "Couch Investor");
    static final Set<String> VALUATION_TYPES = Set.of("intrinsic_value", "fair_value");
    static final String[] SOURCES = {"ours", "notebooklm", "creator", "analyst"};

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String stateDir() {
        String o = System.getenv("THESIS_STATE_DIR");
        if (o != null && !o.isEmpty()) return o;
        return HOME + "/workspace/thesis_watch";
    }

    static String statePath() {
        return stateDir() + "/state.json";
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "--run": run(); break;
            case "--self-test": selfTest(); break;
            case "--verify": verify(); break;
            default:
                System.err.println("Usage: java ThesisMoveDetector.java --run | --self-test | --verify");
                System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------

    /** One ticker's current inputs. Nulls mean "not available". */
    static final class Snap {
        String ticker;
        Double ours, notebooklm, creator, analyst, price;
        String creatorSig;   // creator|entry identity, to spot NEW calls
        String creatorName;
        String creatorType;
        String creatorDate;

        Double sourceVal(String s) {
            switch (s) {
                case "ours": return ours;
                case "notebooklm": return notebooklm;
                case "creator": return creator;
                case "analyst": return analyst;
                case "price": return price;
                default: return null;
            }
        }
    }

    static final class Alert {
        String ticker, rule, source, why;
        Double oldVal, newVal, changePct;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticker", ticker);
            m.put("rule", rule);
            m.put("source", source);
            m.put("old", oldVal);
            m.put("new", newVal);
            m.put("change_pct", changePct == null ? null
                    : Math.round(changePct * 10000.0) / 10000.0);
            m.put("why", why);
            return m;
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    static String readFile(String path) throws Exception {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    static String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return (v instanceof String) ? (String) v : null;
    }

    static Double num(Map<String, Object> m, String key) {
        if (m == null) return null;
        return num(m.get(key));
    }

    static Double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        return null;
    }

    /** Current per-ticker snapshot from the real backends (all read-only). */
    static Map<String, Snap> loadCurrent() throws Exception {
        Map<String, Object> blessed = asMap(Json.parse(readFile(BLESSED)));
        Map<String, Object> columns = asMap(blessed.get("columns"));
        Map<String, Object> oursTickers = asMap(asMap(columns.get("ours")).get("tickers"));
        Map<String, Object> nlTickers = asMap(asMap(columns.get("notebooklm")).get("tickers"));

        Map<String, Object> creatorDoc = asMap(Json.parse(readFile(CREATOR_VALUES)));
        Map<String, Object> creatorVals = asMap(creatorDoc.get("values"));

        Map<String, Object> analystDoc = asMap(Json.parse(readFile(ANALYST)));
        Map<String, Object> cacheDoc = asMap(Json.parse(readFile(CACHE)));
        Map<String, Object> quotes = asMap(cacheDoc.get("prices"));
        if (quotes == null) quotes = asMap(cacheDoc.get("quotes")); // older cache files
        Map<String, Object> watchDoc = asMap(Json.parse(readFile(WATCHLIST)));
        List<Object> tickers = (List<Object>) watchDoc.get("tickers");

        Map<String, Snap> cur = new LinkedHashMap<>();
        for (Object t : tickers) {
            String ticker = String.valueOf(t);
            Snap s = new Snap();
            s.ticker = ticker;
            s.ours = num(asMap(oursTickers.get(ticker)), "value");
            s.notebooklm = num(asMap(nlTickers.get(ticker)), "value");
            s.analyst = num(asMap(analystDoc.get(ticker)), "target");
            s.price = num(asMap(quotes.get(ticker)), "price");
            Object entries = creatorVals.get(ticker);
            if (entries instanceof List) {
                Map<String, Object> latest = latestCreatorVal((List<Object>) entries);
                if (latest != null) {
                    s.creator = num(latest.get("value"));
                    s.creatorName = str(latest, "creator");
                    s.creatorType = str(latest, "type");
                    s.creatorDate = str(latest, "call_date");
                    String entryId = str(latest, "entry_id");
                    s.creatorSig = s.creatorName + "|"
                            + (entryId != null ? entryId : s.creatorDate + "|" + latest.get("value"));
                }
            }
            cur.put(ticker, s);
        }
        return cur;
    }

    /** Latest valuation-type entry from a followed creator (log is append-only). */
    static Map<String, Object> latestCreatorVal(List<Object> entries) {
        Map<String, Object> best = null;
        String bestDate = "";
        int bestIdx = -1;
        int idx = 0;
        for (Object o : entries) {
            idx++;
            Map<String, Object> e = asMap(o);
            if (e == null) continue;
            if (!FOLLOWED_CREATORS.contains(str(e, "creator"))) continue;
            if (!VALUATION_TYPES.contains(str(e, "type"))) continue;
            String date = str(e, "call_date");
            if (date == null) date = "";
            if (best == null || date.compareTo(bestDate) > 0
                    || (date.equals(bestDate) && idx > bestIdx)) {
                best = e;
                bestDate = date;
                bestIdx = idx;
            }
        }
        return best;
    }

    /** State document, or null when state.json does not exist yet. */
    static Map<String, Object> loadState() throws Exception {
        Path p = Paths.get(statePath());
        if (!Files.isRegularFile(p)) return null;
        return asMap(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
    }

    static Map<String, Object> prevTickers(Map<String, Object> state) {
        if (state == null) return null;
        Map<String, Object> t = asMap(state.get("tickers"));
        return (t == null) ? new LinkedHashMap<>() : t;
    }

    // ------------------------------------------------------------------
    // Detection core (pure — also exercised by --self-test)
    // ------------------------------------------------------------------

    /**
     * @param cur  current per-ticker snapshot
     * @param prev previous tickers map from state.json; null = no state yet
     *             (first sighting is always a silent baseline, never an alert)
     */
    static List<Alert> detect(Map<String, Snap> cur, Map<String, Object> prev) {
        List<Alert> alerts = new ArrayList<>();
        for (Snap s : cur.values()) {
            Map<String, Object> p = (prev == null) ? null : asMap(prev.get(s.ticker));
            boolean isNew = (p == null);

            // Rule 1: a valuation source moved >10% since last check.
            if (!isNew) {
                for (String src : SOURCES) {
                    Double oldV = num(p, src);
                    Double newV = s.sourceVal(src);
                    if (oldV == null || newV == null || oldV == 0.0) continue;
                    double move = (newV - oldV) / Math.abs(oldV);
                    if (Math.abs(move) > MOVE_THRESHOLD) {
                        Alert a = new Alert();
                        a.ticker = s.ticker;
                        a.rule = "value_move";
                        a.source = src;
                        a.oldVal = oldV;
                        a.newVal = newV;
                        a.changePct = move;
                        a.why = whyForMove(s.ticker, src, move);
                        alerts.add(a);
                    }
                }
            }

            // Rule 2: NEW followed-creator valuation call differing >10% from our DCF.
            // First sighting (no previous row for this ticker) is a silent baseline.
            if (!isNew && s.creator != null && s.creatorSig != null
                    && s.ours != null && s.ours != 0.0) {
                String prevSig = (p == null) ? null : str(p, "creator_sig");
                if (!s.creatorSig.equals(prevSig)) {
                    double diff = (s.creator - s.ours) / Math.abs(s.ours);
                    if (Math.abs(diff) > MOVE_THRESHOLD) {
                        Alert a = new Alert();
                        a.ticker = s.ticker;
                        a.rule = "new_creator_value";
                        a.source = "creator";
                        a.oldVal = null;
                        a.newVal = s.creator;
                        a.changePct = diff;
                        a.why = String.format(Locale.US,
                                "%s's new %s call for %s (%s) sits %s%% %s your DCF (%s) — "
                                + "a trusted voice disagrees with your model.",
                                s.creatorName, label(s.creatorType), s.ticker, fmt(s.creator),
                                pct(Math.abs(diff)),
                                diff > 0 ? "above" : "below", fmt(s.ours));
                        alerts.add(a);
                    }
                }
            }

            // Rule 3: |our-DCF − price| spread newly crossed 40% either way.
            if (!isNew && s.ours != null && s.ours > 0.0
                    && s.price != null && s.price > 0.0) {
                double spread = (s.ours - s.price) / s.ours;
                boolean crossedNow = Math.abs(spread) >= SPREAD_THRESHOLD;
                boolean crossedBefore = false;
                Double po = num(p, "ours");
                Double pp = num(p, "price");
                if (po != null && po > 0.0 && pp != null && pp > 0.0) {
                    crossedBefore = Math.abs((po - pp) / po) >= SPREAD_THRESHOLD;
                }
                // A spread that was not computable before and is material now
                // counts as newly crossed (newly observable thesis state).
                if (crossedNow && !crossedBefore) {
                    Alert a = new Alert();
                    a.ticker = s.ticker;
                    a.rule = "spread_cross";
                    a.source = "price";
                    a.oldVal = pp;
                    a.newVal = s.price;
                    a.changePct = spread;
                    a.why = String.format(Locale.US,
                            "%s now trades %s%% %s your DCF (%s vs %s) — "
                            + "the spread crossed the 40%% materiality line.",
                            s.ticker, pct(Math.abs(spread)),
                            spread > 0 ? "below" : "above",
                            fmt(s.price), fmt(s.ours));
                    alerts.add(a);
                }
            }
        }
        return alerts;
    }

    static String whyForMove(String ticker, String src, double move) {
        String dir = (move > 0 ? "+" : "-") + pct(Math.abs(move)) + "%";
        switch (src) {
            case "ours":
                return String.format(Locale.US,
                        "Your own DCF for %s moved %s — the thesis input itself changed.",
                        ticker, dir);
            case "notebooklm":
                return String.format(Locale.US,
                        "NotebookLM's filing-based value for %s moved %s — the concrete-evidence base shifted.",
                        ticker, dir);
            case "creator":
                return String.format(Locale.US,
                        "A followed creator's latest call on %s moved %s since the last check.",
                        ticker, dir);
            case "analyst":
                return String.format(Locale.US,
                        "The analyst consensus target for %s moved %s — the Street's view shifted.",
                        ticker, dir);
            default:
                return String.format(Locale.US, "%s %s moved %s.", src, ticker, dir);
        }
    }

    static String label(String type) {
        return "fair_value".equals(type) ? "fair-value" : "intrinsic-value";
    }

    static String fmt(double v) {
        return "$" + String.format(Locale.US, "%,.2f", v);
    }

    static String pct(double v) {
        return String.format(Locale.US, "%.1f", v * 100.0);
    }

    // ------------------------------------------------------------------
    // State document
    // ------------------------------------------------------------------

    static Map<String, Object> buildState(Map<String, Snap> cur,
                                         Map<String, Object> prev,
                                         List<Alert> alerts,
                                         String now) {
        Map<String, Object> tickers = new LinkedHashMap<>();
        Map<String, List<String>> alertedRules = new LinkedHashMap<>();
        for (Alert a : alerts) {
            alertedRules.computeIfAbsent(a.ticker, k -> new ArrayList<>()).add(a.rule);
        }
        for (Snap s : cur.values()) {
            Map<String, Object> prevT = (prev == null) ? null : asMap(prev.get(s.ticker));
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("ours", s.ours);
            t.put("notebooklm", s.notebooklm);
            t.put("creator", s.creator);
            t.put("analyst", s.analyst);
            t.put("price", s.price);
            t.put("creator_sig", s.creatorSig);
            t.put("creator_name", s.creatorName);
            Double spread = null;
            if (s.ours != null && s.ours > 0.0 && s.price != null && s.price > 0.0) {
                spread = (s.ours - s.price) / s.ours;
            }
            t.put("spread", spread);
            List<String> rules = alertedRules.get(s.ticker);
            if (rules != null) {
                t.put("last_alert", now + " | rules=" + String.join(",", rules));
            } else if (prevT != null) {
                t.put("last_alert", str(prevT, "last_alert"));
            } else {
                t.put("last_alert", null);
            }
            t.put("last_seen", now);
            tickers.put(s.ticker, t);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("job", JOB_ID);
        doc.put("updated_at", now);
        doc.put("tickers", tickers);
        return doc;
    }

    // ------------------------------------------------------------------
    // Modes
    // ------------------------------------------------------------------

    static void run() throws Exception {
        Map<String, Snap> cur = loadCurrent();
        Map<String, Object> state = loadState();
        boolean seeded = (state == null);
        List<Alert> alerts = seeded ? new ArrayList<>() : detect(cur, prevTickers(state));
        String now = Instant.now().toString();
        Map<String, Object> stateUpdate = buildState(cur, prevTickers(state), alerts, now);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", seeded ? "seeded" : (alerts.isEmpty() ? "ok" : "alerts"));
        out.put("job", JOB_ID);
        out.put("tickers_checked", cur.size());
        List<Object> alertMaps = new ArrayList<>();
        for (Alert a : alerts) alertMaps.add(a.toMap());
        out.put("alerts", alertMaps);
        out.put("state_update", stateUpdate);
        Map<String, Object> cacheDoc = asMap(Json.parse(readFile(CACHE)));
        out.put("cache_asof", str(cacheDoc, "asof"));
        System.out.println(Json.dumps(out));

        if (seeded) {
            System.err.println("no material moves (state seeded — baseline established)");
        } else if (alerts.isEmpty()) {
            System.err.println("no material moves");
        } else {
            StringBuilder b = new StringBuilder();
            b.append(alerts.size()).append(" thesis-move alert(s): ");
            for (int i = 0; i < alerts.size(); i++) {
                if (i > 0) b.append("; ");
                Alert a = alerts.get(i);
                b.append(a.ticker).append(" (").append(a.rule).append(")");
            }
            System.err.println(b);
        }
    }

    static void verify() {
        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            // 1. Every backend parses and has the expected shape.
            Map<String, Object> blessed = asMap(Json.parse(readFile(BLESSED)));
            Map<String, Object> columns = asMap(blessed.get("columns"));
            Map<String, Object> oursT = asMap(asMap(columns.get("ours")).get("tickers"));
            Map<String, Object> nlT = asMap(asMap(columns.get("notebooklm")).get("tickers"));
            if (oursT == null || nlT == null) {
                failures.add("blessed.json missing columns.ours/notebooklm tickers");
            } else {
                infos.add("blessed.json: ours=" + oursT.size() + " tickers, notebooklm=" + nlT.size());
            }

            Map<String, Object> creatorDoc = asMap(Json.parse(readFile(CREATOR_VALUES)));
            if (asMap(creatorDoc.get("values")) == null) {
                failures.add("creator_values.json missing values object");
            }
            Map<String, Object> analystDoc = asMap(Json.parse(readFile(ANALYST)));
            infos.add("analyst_targets.json: " + analystDoc.size() + " tickers");
            Map<String, Object> cacheDoc = asMap(Json.parse(readFile(CACHE)));
            if (asMap(cacheDoc.get("prices")) == null && asMap(cacheDoc.get("quotes")) == null) {
                failures.add("price_cache.json missing prices/quotes object");
            }
            Map<String, Object> watchDoc = asMap(Json.parse(readFile(WATCHLIST)));
            List<Object> tickers = (List<Object>) watchDoc.get("tickers");
            Set<String> roster = new java.util.HashSet<>();
            for (Object t : tickers) roster.add(String.valueOf(t));

            // 2. The detector runs end to end over the real backends.
            Map<String, Snap> cur = loadCurrent();
            if (cur.size() != roster.size()) {
                failures.add("loadCurrent covered " + cur.size() + " tickers, watchlist has "
                        + roster.size());
            } else {
                infos.add("snapshot covers all " + cur.size() + " watchlist tickers");
            }

            // 3. State file, when present, is a valid doc: tickers ⊆ watchlist,
            //    numeric-or-null value fields, and each stored spread is
            //    consistent with that row's own stored ours/price.
            Map<String, Object> state = loadState();
            if (state == null) {
                infos.add("state.json not present yet — first run will seed it");
            } else {
                Map<String, Object> st = prevTickers(state);
                for (String t : st.keySet()) {
                    if (!roster.contains(t)) {
                        failures.add("state.json tracks non-watchlist ticker " + t);
                    }
                }
                for (Map.Entry<String, Object> e : st.entrySet()) {
                    Map<String, Object> row = asMap(e.getValue());
                    if (row == null) {
                        failures.add("state row for " + e.getKey() + " is not an object");
                        continue;
                    }
                    for (String k : new String[]{"ours", "notebooklm", "creator", "analyst", "price"}) {
                        Object v = row.get(k);
                        if (v != null && !(v instanceof Number)) {
                            failures.add("state row " + e.getKey() + "." + k + " is not a number");
                        }
                    }
                    Double so = num(row, "ours");
                    Double sp = num(row, "price");
                    Double ss = num(row, "spread");
                    if (so != null && so > 0.0 && sp != null && sp > 0.0) {
                        double recomputed = (so - sp) / so;
                        if (ss == null || Math.abs(ss - recomputed) > 1e-9) {
                            failures.add("state row " + e.getKey()
                                    + ": stored spread inconsistent with stored ours/price");
                        }
                    }
                }
                infos.add("state.json valid: " + st.size() + " tickers, spreads self-consistent");
            }
        } catch (Exception e) {
            failures.add("exception: " + e);
        }
        Verify.report("VERIFY OK (ThesisMoveDetector): backends parse, snapshot covers the watchlist.",
                "VERIFY FAILED (ThesisMoveDetector):", infos, failures);
    }

    // ------------------------------------------------------------------
    // Self-test (shared SelfTest harness)
    // ------------------------------------------------------------------

    static Snap snap(String ticker, Double ours, Double nl, Double creator,
                     Double analyst, Double price, String creatorSig) {
        Snap s = new Snap();
        s.ticker = ticker;
        s.ours = ours;
        s.notebooklm = nl;
        s.creator = creator;
        s.analyst = analyst;
        s.price = price;
        s.creatorSig = creatorSig;
        s.creatorName = "Parkev Tatevosian";
        s.creatorType = "intrinsic_value";
        s.creatorDate = "2026-09-28";
        return s;
    }

    static Map<String, Object> prevRow(Double ours, Double nl, Double creator,
                                       Double analyst, Double price, String creatorSig) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ours", ours);
        r.put("notebooklm", nl);
        r.put("creator", creator);
        r.put("analyst", analyst);
        r.put("price", price);
        r.put("creator_sig", creatorSig);
        return r;
    }

    static void selfTest() {
        // 1. A synthetic >10% move in our DCF fires an alert.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 115.0, 90.0, null, 300.0, 100.0, null));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, null, 300.0, 100.0, null));
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("value move >10% fires one alert", 1, a.size());
            SelfTest.checkEq("alert rule is value_move", "value_move", a.get(0).rule);
            SelfTest.checkEq("alert source is ours", "ours", a.get(0).source);
            SelfTest.check("why line names the ticker", a.get(0).why.contains("TEST"));
        }

        // 2. No change at all: silence.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 100.0, 90.0, 150.0, 300.0, 95.0, "Parkev Tatevosian|e1"));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, 150.0, 300.0, 95.0, "Parkev Tatevosian|e1"));
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("identical snapshot is silent", 0, a.size());
        }

        // 3. A 5% move stays silent (conservative threshold).
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 105.0, 90.0, null, 300.0, 100.0, null));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, null, 300.0, 100.0, null));
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("5% move stays silent", 0, a.size());
        }

        // 4. A NEW creator value differing >10% from our DCF fires.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 100.0, 90.0, 150.0, 300.0, 95.0, "Parkev Tatevosian|new"));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, 150.0, 300.0, 95.0, "Parkev Tatevosian|old"));
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("new creator value >10% from DCF fires", 1, a.size());
            SelfTest.checkEq("alert rule is new_creator_value", "new_creator_value", a.get(0).rule);
            SelfTest.check("why names the creator", a.get(0).why.contains("Parkev Tatevosian"));
        }

        // 5. Same creator signature: no re-fire (dedup).
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 100.0, 90.0, 150.0, 300.0, 95.0, "Parkev Tatevosian|e1"));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, 150.0, 300.0, 95.0, "Parkev Tatevosian|e1"));
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("seen creator signature does not re-fire", 0, a.size());
        }

        // 6. New creator call within 10% of our DCF: silent.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 100.0, 90.0, 105.0, 300.0, 95.0, "Parkev Tatevosian|new"));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, null, 300.0, 95.0, null));
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("new creator call within 10% of DCF is silent", 0, a.size());
        }

        // 7. Spread newly crossing 40% fires.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 100.0, 90.0, null, 300.0, 55.0, null));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, null, 300.0, 80.0, null)); // 20% spread
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("spread newly crossing 40% fires", 1, a.size());
            SelfTest.checkEq("alert rule is spread_cross", "spread_cross", a.get(0).rule);
            SelfTest.check("why says below DCF", a.get(0).why.contains("below your DCF"));
        }

        // 8. Spread already ≥40%: no re-fire.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 100.0, 90.0, null, 300.0, 50.0, null));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, null, 300.0, 55.0, null)); // 45% before
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("already-wide spread does not re-fire", 0, a.size());
        }

        // 9. Spread below 40% both sides: silent.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 100.0, 90.0, null, 300.0, 70.0, null));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, null, 300.0, 80.0, null));
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("spread under 40% stays silent", 0, a.size());
        }

        // 10. Spread crossing on the overpriced side fires too.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 100.0, 90.0, null, 300.0, 150.0, null));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, 90.0, null, 300.0, 120.0, null)); // 20% above
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("spread crossing 40% above DCF fires", 1, a.size());
            SelfTest.check("why says above DCF", a.get(0).why.contains("above your DCF"));
        }

        // 11. Null previous value is a baseline, not a move.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 100.0, 88.0, null, 300.0, 95.0, null));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(100.0, null, null, 300.0, 95.0, null));
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("null-to-value is a baseline, not a move", 0, a.size());
        }

        // 12. Zero/negative DCF: spread rule skipped.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 0.0, 90.0, null, 300.0, 50.0, null));
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("TEST", prevRow(0.0, 90.0, null, 300.0, 80.0, null));
            List<Alert> a = detect(cur, prev);
            SelfTest.checkEq("zero DCF skips the spread rule", 0, a.size());
        }

        // 13. First sighting (no state at all): silent baseline.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("TEST", snap("TEST", 115.0, 90.0, 150.0, 300.0, 50.0, "Parkev Tatevosian|e1"));
            List<Alert> a = detect(cur, null);
            SelfTest.checkEq("no state means silent seed, never alerts", 0, a.size());
        }

        // 14. State document round-trips through JSON with values intact.
        {
            Map<String, Snap> cur = new LinkedHashMap<>();
            cur.put("NVDA", snap("NVDA", 109.26, 88.95, null, 324.14, 225.07, null));
            Map<String, Object> doc = buildState(cur, null, new ArrayList<>(), "2026-09-28T00:00:00Z");
            Map<String, Object> back = asMap(Json.parse(Json.dumps(doc)));
            Map<String, Object> row = asMap(asMap(back.get("tickers")).get("NVDA"));
            SelfTest.checkEq("state round-trip keeps ours", 109.26, num(row, "ours"));
            SelfTest.checkEq("state round-trip keeps job", "thesis-move-detector", str(back, "job"));
            double spread = (109.26 - 225.07) / 109.26;
            SelfTest.check("state round-trip keeps spread",
                    Math.abs(num(row, "spread") - spread) < 1e-9);
        }

        // 15. Alert serialization keeps the required fields.
        {
            Alert a = new Alert();
            a.ticker = "LLY";
            a.rule = "new_creator_value";
            a.source = "creator";
            a.oldVal = null;
            a.newVal = 1494.0;
            a.changePct = 0.2978;
            a.why = "why it matters";
            Map<String, Object> m = a.toMap();
            SelfTest.checkEq("alert map has all fields", 7, m.size());
            SelfTest.checkEq("alert map ticker", "LLY", m.get("ticker"));
            SelfTest.check("alert map why", "why it matters".equals(m.get("why")));
        }

        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser/serializer (same proven pattern as DcfGapPipeline)
    // ------------------------------------------------------------------

    static final class Json {
        private final String s;
        private int pos;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.skipWs();
            Object v = j.readValue();
            j.skipWs();
            if (j.pos != j.s.length())
                throw new IllegalArgumentException("trailing characters after JSON document");
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        private Object readValue() {
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
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
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("expected '" + lit + "'");
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
                if (pos >= s.length() || s.charAt(pos) != ':')
                    throw new IllegalArgumentException("expected ':'");
                pos++;
                skipWs();
                m.put(key, readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated object");
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
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated array");
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
                    if (pos >= s.length()) break;
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
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number at position " + start);
            }
        }

        static String dumps(Object v) {
            if (v == null) return "null";
            if (v instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
            if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
            if (v instanceof Map<?, ?> m) {
                StringBuilder b = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(",");
                    first = false;
                    b.append(dumps(String.valueOf(e.getKey()))).append(":").append(dumps(e.getValue()));
                }
                return b.append("}").toString();
            }
            if (v instanceof List<?> l) {
                StringBuilder b = new StringBuilder("[");
                boolean first = true;
                for (Object e : l) {
                    if (!first) b.append(",");
                    first = false;
                    b.append(dumps(e));
                }
                return b.append("]").toString();
            }
            return dumps(String.valueOf(v));
        }
    }
}
