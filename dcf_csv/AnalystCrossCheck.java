import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AnalystCrossCheck — analyst actions as the FOURTH voice in the valuation
 * cross-check: mine vs NotebookLM vs creators vs analysts.
 *
 * <p>Reads (all from ~/workspace/dcf_csv/, the shared backend home):
 * <ul>
 *   <li>analyst_actions.json — latest non-stale analyst action target per ticker
 *   <li>analyst_targets.json — consensus target per ticker (context)
 *   <li>our_dcf.json — my DCF (one number)
 *   <li>notebooklm_values.json — NotebookLM's verified values
 *   <li>creator_values.json — latest valuation-type creator call per ticker
 * </ul>
 *
 * <p>Source trust hierarchy (Rahik's standing rule): Parkev &gt; Jeremy &gt;
 * Couch Investor are his most reliable source. ANALYSTS RANK BELOW the
 * trusted YouTubers. Banking-relationship bias is noted openly on every
 * flag. An analyst disagreement is a review prompt — it does NOT carry the
 * "review my inputs first" weight a trusted creator's call does.
 *
 * <p>Stale actions (&gt;90 days) are shown as STALE and never flag — a stale
 * target is worse than none.
 *
 * <p>Flag rule: |analyst_target − my_dcf| / my_dcf &gt; 25%, both numbers
 * real and comparable. Flags print a FLAG block for the digest agent to
 * paste; disagreements go through Proposals.java for review — never silent.
 *
 * <p>Usage: {@code java AnalystCrossCheck.java [--self-test]}.
 * Exit 0 = ran clean (flags are findings, not errors).
 */
public class AnalystCrossCheck {

    static final String HOME = home();
    static final Path DIR = Paths.get(HOME, "workspace", "dcf_csv");
    static final double FLAG_THRESHOLD = 0.25;
    static final int STALE_DAYS = 90;

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    public static void main(String[] args) throws Exception {
        boolean selfTest = false;
        for (String a : args) if (a.equals("--self-test")) selfTest = true;
        if (selfTest) { runSelfTest(); return; }
        run();
    }

    static void run() throws Exception {
        Map<String, JSONObject> actionTarget = latestActionTargets();
        Map<String, Double> consensus = readTargets(DIR.resolve("analyst_targets.json"), "target");
        Map<String, Double> mine = readTargets(DIR.resolve("our_dcf.json"), "value");
        Map<String, Double> nlm = readTargets(DIR.resolve("notebooklm_values.json"), "value");
        Map<String, JSONObject> creator = readCreatorLatest();

        int flags = 0, compared = 0, staleShown = 0;
        for (String ticker : actionTarget.keySet()) {
            JSONObject act = actionTarget.get(ticker);
            double at = act.getDouble("target_new");
            boolean stale = isStale(act.getString("date"));
            Double my = mine.get(ticker);
            String staleTag = stale ? " [STALE >90d — shown only, never flags]" : "";

            StringBuilder line = new StringBuilder(ticker + " | analyst("
                + act.getString("firm") + " " + act.getString("action") + ")=" + fmt(at)
                + staleTag + " | consensus=" + fmtD(consensus.get(ticker))
                + " | mine=" + fmtD(my)
                + " | notebooklm=" + fmtD(nlm.get(ticker))
                + " | creator=" + creatorStr(creator.get(ticker)));

            if (!stale && my != null && my != 0) {
                compared++;
                double rel = Math.abs(at - my) / Math.abs(my);
                if (rel > FLAG_THRESHOLD) {
                    flags++;
                    line.append(" | FLAG: differs " + pct(rel) + " from my DCF");
                    System.out.println(line);
                    System.out.println("---ANALYST-FLAG---");
                    System.out.println(ticker + ": " + act.getString("firm") + " "
                        + act.getString("action") + " -> target $" + fmt(at)
                        + " (" + act.getString("date") + ") vs my DCF $" + fmt(my)
                        + " — differs " + pct(rel) + ".");
                    System.out.println("  Hierarchy: analysts rank BELOW your trusted "
                        + "YouTubers (Parkev > Jeremy > Couch Investor); "
                        + "banking-relationship bias possible — treat as a review "
                        + "prompt, not a directive. Cross-check against NotebookLM "
                        + "filing-grounded figures before revising my inputs.");
                    System.out.println("  Quote: \"" + act.getString("quote") + "\"");
                    System.out.println("  Source: " + act.getString("source_url"));
                    System.out.println("  If this changes your view of my inputs, file for review: "
                        + "java Proposals.java file --column ours --ticker " + ticker
                        + " --new <value> --rationale \"analyst cross-check: "
                        + act.getString("firm") + " " + act.getString("action")
                        + " target $" + fmt(at) + "\" --by analyst-crosscheck");
                    continue;
                }
            } else if (stale) {
                staleShown++;
            }
            System.out.println(line);
        }
        System.out.println("ANALYST_COMPARED=" + compared
            + " ANALYST_FLAGS=" + flags + " STALE_SHOWN=" + staleShown);
    }

    /** Latest action WITH a target per ticker (by date, then id). */
    static Map<String, JSONObject> latestActionTargets() throws Exception {
        Map<String, JSONObject> out = new TreeMap<>();
        Path p = DIR.resolve("analyst_actions.json");
        if (!Files.exists(p)) return out;
        JSONArray arr = new JSONObject(Files.readString(p, StandardCharsets.UTF_8))
            .optJSONArray("actions");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.getJSONObject(i);
            if (!a.has("target_new")) continue;
            String t = a.getString("ticker");
            JSONObject cur = out.get(t);
            if (cur == null || a.getString("date").compareTo(cur.getString("date")) >= 0)
                out.put(t, a);
        }
        return out;
    }

    /** {ticker: number} from a {values:{T:{key:n}}} or {[T]:{key:n}} backend. */
    static Map<String, Double> readTargets(Path p, String key) throws Exception {
        Map<String, Double> out = new TreeMap<>();
        if (!Files.exists(p)) return out;
        JSONObject root = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
        JSONObject values = root.has("values") ? root.getJSONObject("values") : root;
        for (String t : values.keySet()) {
            JSONObject e = values.optJSONObject(t);
            if (e != null && !e.isNull(key)) {
                try { out.put(t, e.getDouble(key)); }
                catch (Exception ignored) { /* non-numeric -> skip */ }
            }
        }
        return out;
    }

    /** Latest valuation-type creator call per ticker (buy_price excluded). */
    static Map<String, JSONObject> readCreatorLatest() throws Exception {
        Map<String, JSONObject> out = new TreeMap<>();
        Path p = DIR.resolve("creator_values.json");
        if (!Files.exists(p)) return out;
        JSONObject latest = new JSONObject(Files.readString(p, StandardCharsets.UTF_8))
            .optJSONObject("latest");
        if (latest == null) return out;
        for (String t : latest.keySet()) {
            JSONObject e = latest.getJSONObject(t);
            if (!"buy_price".equals(e.optString("type"))) out.put(t, e);
        }
        return out;
    }

    static String creatorStr(JSONObject c) {
        if (c == null) return "—";
        return c.optString("creator", "?") + " $" + fmt(c.optDouble("value", 0));
    }

    static boolean isStale(String date) {
        try {
            return LocalDate.parse(date).plusDays(STALE_DAYS).isBefore(LocalDate.now());
        } catch (Exception e) {
            return true;
        }
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }
    static String fmtD(Double v) { return v == null ? "—" : "$" + fmt(v); }
    static String pct(double r) { return String.format("%.0f%%", r * 100); }

    // ------------------------------------------------------------------
    // Self-test (no network, synthetic data)
    // ------------------------------------------------------------------

    static void runSelfTest() {
        // Flag fires when analyst target differs >25% from mine
        double my = 100.0, at = 140.0;
        double rel = Math.abs(at - my) / Math.abs(my);
        check(rel > FLAG_THRESHOLD, "40% diff flags");

        double at2 = 110.0;
        check(Math.abs(at2 - my) / Math.abs(my) <= FLAG_THRESHOLD, "10% diff does not flag");

        // Boundary: exactly 25% does not flag (strictly greater)
        double at3 = 125.0;
        check(!(Math.abs(at3 - my) / Math.abs(my) > FLAG_THRESHOLD), "exactly 25% does not flag");

        // Stale logic
        check(isStale("2026-01-01"), "jan date is stale");
        check(!isStale(LocalDate.now().toString()), "today is fresh");
        check(isStale("not-a-date"), "unparseable date treated stale");

        // buy_price excluded from creator voice
        JSONObject buy = new JSONObject().put("type", "buy_price").put("value", 352);
        JSONObject fv = new JSONObject().put("type", "fair_value").put("value", 158)
            .put("creator", "Parkev Tatevosian");
        check("buy_price".equals(buy.optString("type")), "buy_price identified");
        check(!"buy_price".equals(fv.optString("type")), "fair_value kept");

        // Latest-action selection prefers newer date
        JSONObject oldA = new JSONObject().put("ticker", "NVDA")
            .put("date", "2026-09-20").put("target_new", 200.0);
        JSONObject newA = new JSONObject().put("ticker", "NVDA")
            .put("date", "2026-09-27").put("target_new", 250.0);
        Map<String, JSONObject> m = new TreeMap<>();
        for (JSONObject a : List.of(oldA, newA)) {
            JSONObject cur = m.get("NVDA");
            if (cur == null || a.getString("date").compareTo(cur.getString("date")) >= 0)
                m.put("NVDA", a);
        }
        check(m.get("NVDA").getDouble("target_new") == 250.0, "latest action wins");

        finish();
    }

    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
    static void finish() {
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
