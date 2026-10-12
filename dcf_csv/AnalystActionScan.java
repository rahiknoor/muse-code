import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AnalystActionScan — daily scan of financial news for analyst actions on
 * Rahik's 31 holdings (upgrades, downgrades, target changes, initiations).
 *
 * <p>ONE extraction pass per source: batched Google News RSS fetches (8
 * tickers per query, 4 fetches total); every field (firm, analyst, action,
 * rating, targets, thesis, quote, url, date) is extracted from those fetches
 * and each item is classified against all 31 holdings locally. Never re-fetch
 * for another field (efficiency rule 2026-09-28).
 *
 * <p>Store: ~/workspace/dcf_csv/analyst_actions.json (shared backend home —
 * no parallel store). Entries carry strict "as of" dates; anything &gt;90
 * days old is STALE (flagged, never used for cross-check flags).
 *
 * <p>Accuracy: only explicitly stated facts are logged. Analyst name and
 * thesis default to "not stated" unless the text contains them. The full
 * analyst report is paywalled — the snippet is the quote, never the thesis.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --run} — fetch (4 batched RSS calls, ~1s politeness gap), parse,
 *       dedupe-append new actions, print digest blocks.</li>
 *   <li>{@code --dry-run} — same, writes nothing.</li>
 *   <li>{@code --self-test} — synthetic headline tests, no network.</li>
 * </ul>
 */
public class AnalystActionScan {

    static final String HOME = home();
    static final Path STORE = Paths.get(HOME, "workspace", "dcf_csv", "analyst_actions.json");
    static final int STALE_DAYS = 90;

    /** ticker -> company name (for query + mention matching). */
    static final String[][] HOLDINGS = {
        {"NVDA","NVIDIA"},{"META","Meta Platforms"},{"NFLX","Netflix"},{"SOFI","SoFi"},
        {"ORCL","Oracle"},{"PLTR","Palantir"},{"AMD","AMD"},{"GRAB","Grab"},
        {"PFE","Pfizer"},{"MSFT","Microsoft"},{"AMZN","Amazon"},{"QCOM","Qualcomm"},
        {"CRM","Salesforce"},{"CELH","Celsius"},{"RH","RH"},{"BRK.B","Berkshire Hathaway"},
        {"V","Visa"},{"MA","Mastercard"},{"WH","Wyndham"},{"JPM","JPMorgan"},
        {"CVX","Chevron"},{"UNH","UnitedHealth"},{"AVGO","Broadcom"},{"TSLA","Tesla"},
        {"XOM","Exxon Mobil"},{"GOOG","Alphabet"},{"IREN","Iris Energy"},{"NOW","ServiceNow"},
        {"INTC","Intel"},{"LLY","Eli Lilly"},{"JNJ","Johnson & Johnson"},
    };

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    // ------------------------------------------------------------------
    // Patterns (merged in: single self-contained file so the regression
    // runner's one-file source launcher can compile it)
    // ------------------------------------------------------------------

    /** Sell-side firms, longest names first so alternation prefers full names. */
    public static final String[] FIRMS = {
        "Goldman Sachs", "Morgan Stanley", "Bank of America", "Deutsche Bank",
        "Wells Fargo", "BofA Securities", "RBC Capital", "BMO Capital",
        "TD Cowen", "Piper Sandler", "Raymond James", "KeyBanc Capital",
        "Stifel Nicolaus", "Canaccord Genuity", "Needham & Company",
        "DA Davidson", "Loop Capital", "Wolfe Research", "Sanford Bernstein",
        "Evercore ISI", "JPMorgan", "J.P. Morgan", "Citigroup", "Citi",
        "Barclays", "Bernstein", "Evercore", "Jefferies", "Oppenheimer",
        "Wedbush", "Cantor Fitzgerald", "Cantor", "Truist", "Mizuho",
        "KeyBanc", "Stifel", "Argus", "DZ Bank", "HSBC", "UBS",
        "Credit Suisse", "Rosenblatt", "Benchmark", "B. Riley",
        "Craig-Hallum", "Northland", "Guggenheim", "Stephens",
        "Edward Jones", "Morningstar", "Zacks",
    };

    /** Matches any firm name as a whole phrase (case-insensitive). */
    public static final Pattern FIRM = Pattern.compile(
        "\\b(Goldman Sachs|Morgan Stanley|Bank of America|Deutsche Bank|Wells Fargo|"
        + "BofA Securities|RBC Capital|BMO Capital|TD Cowen|Piper Sandler|Raymond James|"
        + "KeyBanc Capital|Stifel Nicolaus|Canaccord Genuity|Needham(?: & Company)?|"
        + "DA Davidson|Loop Capital|Wolfe Research|Sanford Bernstein|Evercore ISI|"
        + "J\\.?P\\.? Morgan|JPMorgan|Citigroup|\\bCiti\\b|Barclays|Bernstein|Evercore|"
        + "Jefferies|Oppenheimer|Wedbush|Cantor(?: Fitzgerald)?|Truist|Mizuho|KeyBanc|"
        + "Stifel|Argus|DZ Bank|HSBC|\\bUBS\\b|Rosenblatt|Benchmark|B\\. Riley|"
        + "Craig-Hallum|Northland|Guggenheim|Stephens|Edward Jones|Morningstar|Zacks)\\b",
        Pattern.CASE_INSENSITIVE);

    /** Action verbs that constitute an analyst action (incl. past tense). */
    public static final Pattern ACTION = Pattern.compile(
        "\\b(upgrades?|upgraded|downgrades?|downgraded|initiates?|initiated|"
        + "resumes?|resumed|reiterates?|reiterated|raises?|raised|lifts?|lifted|"
        + "boosts?|boosted|hikes?|hiked|cuts?|cut|trims?|trimmed|lowers?|lowered|"
        + "reduces?|reduced|maintains?|maintained|keeps?|kept|"
        + "starts?|started|double-upgrades?|double-downgrades?)\\b",
        Pattern.CASE_INSENSITIVE);

    /** Rating words. */
    public static final Pattern RATING = Pattern.compile(
        "\\b(Strong Buy|Buy|Overweight|Outperform|Market Outperform|"
        + "Hold|Neutral|Equal Weight|Equal-Weight|Market Perform|Sector Perform|"
        + "Underweight|Underperform|Market Underperform|Sell|Strong Sell)\\b",
        Pattern.CASE_INSENSITIVE);

    /** "price target to $250" / "target of $45" / "PT at $600". Group 1 = number. */
    public static final Pattern TARGET_NEW = Pattern.compile(
        "(?:price\\s+target|\\bPT\\b|\\btarget)\\s+(?:to|of|at)\\s+\\$?([\\d,]+(?:\\.\\d+)?)",
        Pattern.CASE_INSENSITIVE);

    /** "from $200". Group 1 = number. */
    public static final Pattern TARGET_OLD = Pattern.compile(
        "\\bfrom\\s+\\$?([\\d,]+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    /** "(NVDA)" ticker in parentheses. Group 1 = ticker. */
    public static final Pattern TICKER_PAREN = Pattern.compile("\\(([A-Z]{1,5}(?:\\.[A-Z])?)\\)");

    /** "Analyst Jane Doe at Goldman" / "Goldman analyst Jane Doe". Group 1 = name. */
    public static final Pattern ANALYST_NAME = Pattern.compile(
        "(?:[Aa]nalyst\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z'.-]+){1,2})"
        + "|([A-Z][a-z]+(?:\\s+[A-Z][a-z'.-]+){1,2}),?\\s+[Aa]nalyst)");

    /** Normalize an action verb to a canonical action type. */
    public static String actionType(String verb) {
        String v = verb.toLowerCase();
        if (v.startsWith("upgrad") || v.startsWith("double-upgrad")) return "upgrade";
        if (v.startsWith("downgrad") || v.startsWith("double-downgrad")) return "downgrade";
        if (v.startsWith("initiat") || v.startsWith("start")) return "initiation";
        if (v.startsWith("resum")) return "resumption";
        if (v.startsWith("reiterat") || v.startsWith("maintain") || v.startsWith("keep"))
            return "reiteration";
        if (v.startsWith("rais") || v.startsWith("lift") || v.startsWith("boost")
                || v.startsWith("hike")) return "target_raise";
        if (v.startsWith("cut") || v.startsWith("trim") || v.startsWith("lower")
                || v.startsWith("reduc")) return "target_cut";
        return "other";
    }

    /** Parse a "1,234.56" number string to double. */
    public static double parseNum(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    public static void main(String[] args) throws Exception {
        boolean selfTest = false, dryRun = false;
        for (String a : args) {
            if (a.equals("--self-test")) selfTest = true;
            if (a.equals("--dry-run")) dryRun = true;
        }
        if (selfTest) { runSelfTest(); return; }
        run(dryRun);
    }

    // ------------------------------------------------------------------
    // Run
    // ------------------------------------------------------------------

    static void run(boolean dryRun) throws Exception {
        JSONObject store = loadStore();
        JSONArray actions = store.getJSONArray("actions");
        int added = 0;
        List<JSONObject> fresh = new ArrayList<>();

        // Batched queries: 8 tickers per Google News RSS call (4 calls total
        // instead of 31). Every returned item is classified against all 31
        // holdings locally in this same pass — one fetch, one extraction pass.
        final int BATCH = 8;
        for (int b = 0; b < HOLDINGS.length; b += BATCH) {
            List<String[]> batch = Arrays.asList(HOLDINGS)
                .subList(b, Math.min(b + BATCH, HOLDINGS.length));
            List<Map<String,String>> items;
            try {
                items = fetchBatch(batch);
            } catch (Exception e) {
                System.out.println("[analyst-scan] batch " + (b / BATCH + 1)
                    + ": fetch failed (" + e.getMessage() + ") — skipped");
                continue;
            }
            for (Map<String,String> item : items) {
                String text = item.get("title") + " " + item.get("description");
                for (String[] h : HOLDINGS) {
                    if (!mentionsTicker(text, h[0], h[1])) continue;
                    JSONObject action = extract(h[0], h[1], item);
                    if (action == null) continue;
                    String key = dedupeKey(action);
                    if (hasKey(actions, key)) continue; // dedupe on write
                    action.put("dedupe_key", key);
                    action.put("id", nextId(actions));
                    if (!dryRun) actions.put(action);
                    added++;
                    fresh.add(action);
                }
            }
            Thread.sleep(1000); // politeness gap between batches
        }

        if (!dryRun) {
            store.put("asof", LocalDate.now().toString());
            Files.writeString(STORE, store.toString(2), StandardCharsets.UTF_8);
        }
        System.out.println("ACTIONS_ADDED=" + added);
        for (JSONObject a : fresh) {
            System.out.println("---ANALYST-ACTION---");
            System.out.println(a.getString("date") + " | " + a.getString("ticker")
                + " | " + a.getString("firm") + " " + a.getString("action")
                + (a.has("target_new") ? " -> target $" + a.get("target_new") : "")
                + " | \"" + a.getString("quote") + "\"");
            System.out.println("  source: " + a.getString("source_url"));
        }
    }

    // ------------------------------------------------------------------
    // Fetch: one Google News RSS call per 8-ticker batch (single pass)
    // ------------------------------------------------------------------

    static List<Map<String,String>> fetchBatch(List<String[]> batch) throws Exception {
        StringBuilder terms = new StringBuilder();
        for (String[] h : batch) {
            if (terms.length() > 0) terms.append(" OR ");
            terms.append('"').append(h[0]).append("\" OR \"").append(h[1]).append('"');
        }
        String q = URLEncoder.encode(
            "(" + terms + ") "
            + "(upgrade OR downgrade OR \"price target\" OR \"initiates coverage\") when:7d",
            StandardCharsets.UTF_8);
        String url = "https://news.google.com/rss/search?q=" + q
            + "&hl=en-US&gl=US&ceid=US:en";
        return parseRssItems(Net.get(url));
    }

    static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    static final Pattern TAG = Pattern.compile("<%s>(.*?)</%s>", Pattern.DOTALL);

    static List<Map<String,String>> parseRssItems(String rss) {
        List<Map<String,String>> out = new ArrayList<>();
        Matcher m = ITEM.matcher(rss);
        while (m.find()) {
            String item = m.group(1);
            Map<String,String> e = new LinkedHashMap<>();
            e.put("title", unescape(tag(item, "title")));
            e.put("link", unescape(tag(item, "link")));
            e.put("pubDate", tag(item, "pubDate").trim());
            e.put("description", unescape(stripTags(tag(item, "description"))).trim());
            out.add(e);
        }
        return out;
    }

    static String tag(String item, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(item);
        return m.find() ? m.group(1).trim() : "";
    }

    static String stripTags(String s) { return s.replaceAll("<[^>]+>", " "); }

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // Extract: single pass over title+snippet -> action record or null
    // ------------------------------------------------------------------

    static JSONObject extract(String ticker, String company, Map<String,String> item) {
        String title = item.get("title");
        String snippet = item.get("description");
        String text = title + " " + snippet;

        if (!mentionsTicker(text, ticker, company)) return null;

        Matcher firmM = FIRM.matcher(text);
        Matcher actionM = ACTION.matcher(text);
        Matcher targetM = TARGET_NEW.matcher(text);
        boolean hasFirm = firmM.find();
        boolean hasAction = actionM.find();
        boolean hasTarget = targetM.find();
        Matcher ratingM = RATING.matcher(text);
        boolean hasRating = ratingM.find();

        // Must look like an analyst action: an action verb AND (a firm OR a
        // rating OR a price target). Headlines about earnings are not actions.
        if (!hasAction || !(hasFirm || hasRating || hasTarget)) return null;

        String firm = hasFirm ? firmM.group(1) : "not stated";
        String verb = actionM.group(1);
        String actionType = actionType(verb);

        JSONObject o = new JSONObject();
        o.put("ticker", ticker);
        o.put("company", company);
        o.put("firm", firm);
        o.put("analyst", analystName(text));
        o.put("action", actionType);
        o.put("action_verb", verb);
        if (hasRating) o.put("rating_new", ratingM.group(1));
        if (hasTarget) o.put("target_new", parseNum(targetM.group(1)));
        Matcher oldM = TARGET_OLD.matcher(text);
        // "from $X" only counts as the old target if a new target was found
        if (hasTarget && oldM.find()) {
            double oldV = parseNum(oldM.group(1));
            if (oldV != o.getDouble("target_new")) o.put("target_old", oldV);
        }
        o.put("date", toIsoDate(item.get("pubDate")));
        o.put("source_url", item.get("link"));
        o.put("quote", title); // exact headline = the quote; never paraphrased
        // Thesis: the snippet is all we can honestly quote. Full reports are paywalled.
        o.put("reason", snippet.isEmpty() ? "not stated in snippet" : snippet);
        o.put("asof", LocalDate.now().toString());
        return o;
    }

    /** Ticker mentioned as (TICKER), $TICKER, whole word (len>=3), or company name. */
    static boolean mentionsTicker(String text, String ticker, String company) {
        if (text.contains("(" + ticker + ")") || text.contains("$" + ticker)) return true;
        String tl = ticker.toLowerCase(Locale.ROOT);
        if (tl.length() >= 3
                && Pattern.compile("\\b" + Pattern.quote(ticker) + "\\b").matcher(text).find())
            return true;
        // company name: match on the first significant word (>=4 chars)
        for (String w : company.split("\\s+")) {
            if (w.length() >= 4
                    && Pattern.compile("\\b" + Pattern.quote(w) + "\\b",
                        Pattern.CASE_INSENSITIVE).matcher(text).find())
                return true;
        }
        return false;
    }

    /** Analyst name only when the text actually names one; else "not stated". */
    static String analystName(String text) {
        Matcher m = ANALYST_NAME.matcher(text);
        if (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            if (n != null && !n.isBlank()) return n.trim();
        }
        return "not stated";
    }

    /** "Sun, 27 Sep 2026 14:30:00 GMT" -> "2026-09-27". Unparseable -> today. */
    static String toIsoDate(String pubDate) {
        try {
            return ZonedDateTime.parse(pubDate,
                DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString();
        } catch (Exception e) {
            return LocalDate.now().toString();
        }
    }

    // ------------------------------------------------------------------
    // Store helpers
    // ------------------------------------------------------------------

    static JSONObject loadStore() throws Exception {
        if (Files.exists(STORE)) {
            JSONObject s = new JSONObject(Files.readString(STORE, StandardCharsets.UTF_8));
            if (!s.has("actions")) s.put("actions", new JSONArray());
            return s;
        }
        JSONObject s = new JSONObject();
        s.put("asof", LocalDate.now().toString());
        s.put("actions", new JSONArray());
        return s;
    }

    static String dedupeKey(JSONObject a) {
        return a.getString("ticker") + "|" + a.getString("firm") + "|"
            + a.getString("action") + "|"
            + (a.has("target_new") ? String.valueOf(a.get("target_new")) : "-") + "|"
            + a.getString("date");
    }

    static boolean hasKey(JSONArray actions, String key) {
        for (int i = 0; i < actions.length(); i++)
            if (key.equals(actions.getJSONObject(i).optString("dedupe_key"))) return true;
        return false;
    }

    static String nextId(JSONArray actions) {
        int max = 0;
        for (int i = 0; i < actions.length(); i++) {
            String id = actions.getJSONObject(i).optString("id", "");
            if (id.startsWith("aa-")) {
                try { max = Math.max(max, Integer.parseInt(id.substring(3))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return String.format("aa-%03d", max + 1);
    }

    /** Days since the action date; used by the cross-check for staleness. */
    public static boolean isStale(JSONObject a) {
        try {
            LocalDate d = LocalDate.parse(a.getString("date"));
            return d.plusDays(STALE_DAYS).isBefore(LocalDate.now());
        } catch (Exception e) {
            return true; // unparseable date = treat as stale, never flag
        }
    }

    // ------------------------------------------------------------------
    // Self-test (no network)
    // ------------------------------------------------------------------

    static void runSelfTest() {
        Map<String,String> item = new LinkedHashMap<>();
        item.put("pubDate", "Sun, 27 Sep 2026 14:30:00 GMT");
        item.put("link", "https://example.com/1");

        // 1. Full headline: firm + upgrade + rating + new/old target
        item.put("title", "Goldman Sachs upgrades NVIDIA (NVDA) to Buy, raises price target to $250 from $200");
        item.put("description", "Goldman cites AI datacenter demand acceleration into 2027.");
        JSONObject a = extract("NVDA", "NVIDIA", item);
        check(a != null, "full headline extracted");
        checkEq("firm", "Goldman Sachs", a.getString("firm"));
        checkEq("action", "upgrade", a.getString("action"));
        checkEq("target_new", 250.0, a.getDouble("target_new"));
        checkEq("target_old", 200.0, a.getDouble("target_old"));
        checkEq("analyst not stated", "not stated", a.getString("analyst"));
        checkEq("quote is exact headline", item.get("title"), a.getString("quote"));

        // 2. Downgrade without explicit target
        item.put("title", "Morgan Stanley downgrades Tesla to Equal Weight on valuation");
        item.put("description", "MS says risk-reward balanced at current levels.");
        JSONObject b = extract("TSLA", "Tesla", item);
        check(b != null, "downgrade extracted");
        checkEq("action downgrade", "downgrade", b.getString("action"));
        check(!b.has("target_new"), "no target invented");
        checkEq("rating", "Equal Weight", b.getString("rating_new"));

        // 3. Initiation with analyst name
        item.put("title", "JPMorgan initiates coverage of Palantir with Overweight rating and $45 target, analyst Jane Doe says");
        item.put("description", "");
        JSONObject c = extract("PLTR", "Palantir", item);
        check(c != null, "initiation extracted");
        checkEq("initiation", "initiation", c.getString("action"));
        checkEq("analyst named", "Jane Doe", c.getString("analyst"));
        checkEq("reason default", "not stated in snippet", c.getString("reason"));

        // 4. Non-action headline rejected (earnings news, no action verb)
        item.put("title", "NVIDIA (NVDA) reports record quarterly revenue, shares jump");
        item.put("description", "Datacenter revenue beat estimates.");
        check(extract("NVDA", "NVIDIA", item) == null, "earnings headline rejected");

        // 5. Headline about another company rejected for this ticker
        item.put("title", "Goldman Sachs upgrades AMD to Buy, price target to $300");
        check(extract("NVDA", "NVIDIA", item) == null, "wrong-ticker headline rejected");

        // 6. Short ticker (V) needs $V or company name, not bare "V"
        item.put("title", "V for victory: markets rally on strong data");
        check(extract("V", "Visa", item) == null, "bare single-letter V rejected");
        item.put("title", "Visa ($V) upgraded by Barclays to Overweight, target to $400");
        JSONObject v = extract("V", "Visa", item);
        check(v != null && v.getString("firm").equals("Barclays"), "$V cashtag accepted");

        // 7. Dedupe
        JSONArray arr = new JSONArray();
        a.put("dedupe_key", dedupeKey(a)); // run() sets this before appending
        arr.put(a);
        check(hasKey(arr, dedupeKey(a)), "dedupe key found");
        JSONObject a2 = extract("NVDA", "NVIDIA", item("Goldman Sachs upgrades NVIDIA (NVDA) to Buy, raises price target to $250 from $200"));
        check(hasKey(arr, dedupeKey(a2)), "same action deduped");

        // 8. Staleness
        JSONObject old = new JSONObject().put("date", "2026-01-01");
        JSONObject freshA = new JSONObject().put("date", LocalDate.now().toString());
        check(isStale(old), "old action is stale");
        check(!isStale(freshA), "today action is fresh");

        // 9. Date parse
        checkEq("pubDate parse", "2026-09-27", toIsoDate("Sun, 27 Sep 2026 14:30:00 GMT"));

        finish();
    }

    static Map<String,String> item(String title) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("title", title); m.put("description", "");
        m.put("pubDate", "Sun, 27 Sep 2026 14:30:00 GMT");
        m.put("link", "https://example.com/x");
        return m;
    }

    // Minimal self-test harness (mirrors SelfTest semantics without the dep,
    // so --self-test runs with zero classpath).
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
    static void checkEq(String name, Object exp, Object act) {
        check(Objects.equals(exp, act == null ? null
            : (act instanceof Double d ? d : String.valueOf(act))), name
            + (Objects.equals(exp, act instanceof Double d ? d : String.valueOf(act))
                ? "" : " [expected=" + exp + " actual=" + act + "]"));
    }
    static void finish() {
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
    static class Objects {
        static boolean equals(Object a, Object b) {
            if (a instanceof Double da && b instanceof Double db)
                return Math.abs(da - db) < 1e-9;
            return java.util.Objects.equals(a, b);
        }
    }
}
