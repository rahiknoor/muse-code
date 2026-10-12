import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * CrowdPulse — daily Reddit mention-volume + sentiment per holding.
 *
 * <p><b>HARD FENCE — READ THIS:</b> this tool is sentiment-only. It NEVER
 * feeds a blessed value, NEVER triggers a DCF review, NEVER enters any
 * cross-check, and NEVER files a proposal. Its only output is a labeled
 * "crowd pulse" section for the digest. The store lives under the
 * wallstreetbets-news-watch goal — NOT in dcf_csv/, NOT in
 * creator_tracker/. There is deliberately no code path from here to
 * Proposals.java, blessed.json, or any valuation backend. The
 * --self-test asserts this fence holds.
 *
 * <p>Efficiency (2026-09-28 rule): ONE extraction pass per ticker — a single
 * Arctic Shift call covering all 4 subreddits and 28 days, split into weeks
 * locally. No per-subreddit queries, no re-fetching.
 *
 * <p>Subreddits: r/stocks, r/wallstreetbets, r/investing, r/ValueInvesting.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --run} — fetch (31 API calls, ~1s gap), aggregate, write
 *       crowd_pulse.json, print digest lines.</li>
 *   <li>{@code --dry-run} — same, writes nothing.</li>
 *   <li>{@code --self-test} — synthetic tests + fence assertion, no network.</li>
 * </ul>
 */
public class CrowdPulse {

    static final String HOME = home();
    static final Path STATE_DIR = Paths.get(HOME, "workspace", "goals",
        "wallstreetbets-news-watch", "hidden_files");
    static final Path STORE = STATE_DIR.resolve("crowd_pulse.json");

    static final String SUBS = "stocks,wallstreetbets,investing,ValueInvesting";
    static final int WINDOW_DAYS = 28;
    static final int SPIKE_WEEK_MIN_POSTS = 5;
    static final double SPIKE_RATIO = 2.0;
    // Data-quality guards (2026-09-28): never report a reading on bad data.
    static final int FRESHNESS_DAYS = 3;     // newest matching post must be this recent
    static final double GAP_RATIO = 0.5;     // latest week below 50% of baseline mean…
    static final int GAP_BASELINE_MIN = 5;   // …while baseline averages this many/week

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

    static final String[] BULL = {"buy","buying","bullish","moon","squeeze","calls",
        "upgrade","upgrades","beat","beats","rally","gains","gain","breakout","hodl",
        "accumulate","undervalued","cheap","long"};
    static final String[] BEAR = {"sell","selling","bearish","puts","downgrade",
        "downgrades","miss","misses","crash","dump","dumping","short","shorting",
        "losses","loss","decline","overvalued","expensive","bankruptcy","bear"};

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
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
        long now = Instant.now().getEpochSecond();
        long after = now - (long) WINDOW_DAYS * 86400;
        JSONObject out = new JSONObject();
        out.put("asof", LocalDate.now().toString());
        out.put("window_days", WINDOW_DAYS);
        out.put("subreddits", SUBS);
        out.put("note", "SENTIMENT ONLY — never a valuation input. "
            + "Fenced off from Proposals/blessed.json/dcf_csv/creator_tracker.");
        JSONArray tickers = new JSONArray();

        List<List<JSONObject>> allPosts = new ArrayList<>();
        for (String[] h : HOLDINGS) {
            String ticker = h[0];
            List<JSONObject> posts;
            try {
                posts = fetchPosts(ticker, after, now);
            } catch (Exception e) {
                System.out.println("[crowd-pulse] " + ticker + ": fetch failed ("
                    + e.getMessage() + ") — skipped");
                posts = new ArrayList<>();
            }
            allPosts.add(posts);
            Thread.sleep(1000); // politeness gap
        }

        // ONE model batch for the whole run (lean: the ~1GB model loads once).
        Map<String, String> idToText = new LinkedHashMap<>();
        for (List<JSONObject> posts : allPosts)
            for (JSONObject p : posts)
                idToText.put(p.optString("id"),
                    p.optString("title") + " " + p.optString("body"));
        Map<String, Double> modelScores = ModelSentiment.scoreAll(idToText);
        Map<String, Boolean> sarcasmFlags = ModelSentiment.lastSarcasmFlags;
        out.put("scorer", modelScores != null ? "finbert+vader" : "lexicon");

        int i = 0;
        for (String[] h : HOLDINGS) {
            JSONObject agg = aggregate(h[0], h[1], allPosts.get(i++), now,
                modelScores, sarcasmFlags);
            tickers.put(agg);
        }
        out.put("tickers", tickers);

        if (!dryRun)
            Files.writeString(STORE, out.toString(2), StandardCharsets.UTF_8);

        System.out.println("CROWD_TICKERS=" + tickers.length());
        System.out.println("---CROWD-PULSE---");
        System.out.println(digestSection(out));
    }

    /** One Arctic Shift call per ticker (all subs, 28d); PullPush fallback. */
    static List<JSONObject> fetchPosts(String ticker, long after, long before) throws Exception {
        List<JSONObject> posts = tryArcticShift(ticker, after, before);
        if (!posts.isEmpty()) return posts;
        System.out.println("[crowd-pulse] " + ticker + ": arctic shift empty, trying pullpush");
        return tryPullPush(ticker, after, before);
    }

    static List<JSONObject> tryArcticShift(String ticker, long after, long before) throws Exception {
        String q = "subreddit=" + URLEncoder.encode(SUBS, StandardCharsets.UTF_8)
            + "&query=" + URLEncoder.encode(ticker, StandardCharsets.UTF_8)
            + "&limit=500&sort=desc&after=" + after + "&before=" + before;
        String body = Net.get("https://arcticshift.io/api/posts/search?" + q);
        JSONObject root = new JSONObject(body);
        JSONArray items = root.optJSONArray("data");
        if (items == null) return new ArrayList<>();
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject d = items.getJSONObject(i);
            out.add(post(d.optString("id"), d.optString("subreddit"),
                d.optString("author"),
                d.optString("title"), d.optString("selftext"),
                d.optLong("created_utc", 0),
                "https://www.reddit.com" + d.optString("permalink", "")));
        }
        return out;
    }

    static List<JSONObject> tryPullPush(String ticker, long after, long before) throws Exception {
        String q = "subreddit=" + URLEncoder.encode(SUBS, StandardCharsets.UTF_8)
            + "&q=" + URLEncoder.encode(ticker, StandardCharsets.UTF_8)
            + "&size=500&after=" + after + "&before=" + before
            + "&sort=desc&sort_type=created_utc";
        String body = Net.get("https://api.pullpush.io/reddit/search/submission/?" + q);
        JSONArray items = new JSONObject(body).optJSONArray("data");
        if (items == null) return new ArrayList<>();
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject d = items.getJSONObject(i);
            out.add(post(d.optString("id"), d.optString("subreddit"),
                d.optString("author"),
                d.optString("title"), d.optString("selftext"),
                d.optLong("created_utc", 0),
                "https://www.reddit.com" + d.optString("permalink", "")));
        }
        return out;
    }

    static JSONObject post(String id, String sub, String author,
                           String title, String body, long utc, String url) {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("subreddit", sub); o.put("author", author);
        o.put("title", title);
        o.put("body", body); o.put("created_utc", utc); o.put("url", url);
        return o;
    }

    // HTTP goes through the shared Net helper (java-shared/Net.java):
    // proxy-aware GET, one copy of the boilerplate for the whole workspace.

    // ------------------------------------------------------------------
    // Aggregate: single local pass over the 28d posts
    // ------------------------------------------------------------------

    static JSONObject aggregate(String ticker, String company,
                                List<JSONObject> posts, long now) {
        return aggregate(ticker, company, posts, now, null);
    }

    static JSONObject aggregate(String ticker, String company,
                                List<JSONObject> posts, long now,
                                Map<String, Double> modelScores) {
        return aggregate(ticker, company, posts, now, modelScores, null);
    }

    static JSONObject aggregate(String ticker, String company,
                                List<JSONObject> posts, long now,
                                Map<String, Double> modelScores,
                                Map<String, Boolean> sarcasmFlags) {
        long weekCut = now - 7L * 86400;
        int weekCount = 0, baseCount = 0, matched = 0;
        int bull = 0, bear = 0, sarcastic = 0, filteredJunk = 0;
        long maxUtc = 0;
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<String> topTitles = new ArrayList<>();

        for (JSONObject p : posts) {
            if (isJunkPost(p)) { filteredJunk++; continue; }
            String text = (p.optString("title") + " " + p.optString("body"));
            if (!mentionsTicker(text, ticker, company)) continue;
            String id = p.optString("id");
            if (seen.containsKey(id)) continue; // dedupe across sources
            seen.put(id, 1);
            long utc = p.optLong("created_utc", 0);
            if (utc > maxUtc) maxUtc = utc;
            matched++;
            // Model score when available, lexicon fallback otherwise.
            double s = modelScores != null && modelScores.containsKey(id)
                ? modelScores.get(id) : ModelSentiment.lexiconScore(text);
            if (s >= 0.25) bull++; else if (s <= -0.25) bear++;
            if (sarcasmFlags != null && Boolean.TRUE.equals(sarcasmFlags.get(id)))
                sarcastic++;
            if (utc >= weekCut) {
                weekCount++;
                if (topTitles.size() < 3 && !p.optString("title").isBlank())
                    topTitles.add("r/" + p.optString("subreddit") + ": "
                        + p.optString("title"));
            } else {
                baseCount++;
            }
        }

        double baseWeekly = baseCount / 3.0;
        double ratio = weekCount / Math.max(0.5, baseWeekly);
        double sentScore = (bull - bear) / (double) Math.max(1, bull + bear);
        String sentLabel = sentScore >= 0.3 ? "leaning bullish"
            : sentScore <= -0.3 ? "leaning bearish" : "mixed/neutral";

        // Freshness guard: posts exist but the newest is older than
        // FRESHNESS_DAYS -> the archive is stale; any reading would be fiction.
        boolean stale = matched > 0
            && (now - maxUtc) > (long) FRESHNESS_DAYS * 86400;
        boolean noData = matched == 0;
        // Completeness check: a healthy baseline but a hollow latest week
        // smells like a data gap, not a quiet week -> low confidence.
        boolean lowConfidence = !stale && !noData
            && baseWeekly >= GAP_BASELINE_MIN
            && weekCount < GAP_RATIO * baseWeekly;

        boolean spike = !stale && !noData
            && ratio >= SPIKE_RATIO && weekCount >= SPIKE_WEEK_MIN_POSTS;
        if (stale) sentLabel = "data stale — no reading";

        JSONObject o = new JSONObject();
        o.put("ticker", ticker);
        o.put("week_mentions", weekCount);
        o.put("baseline_weekly_avg", Math.round(baseWeekly * 10.0) / 10.0);
        o.put("spike_ratio", Math.round(ratio * 10.0) / 10.0);
        o.put("spike", spike);
        o.put("sentiment", sentLabel);
        o.put("sentiment_score", Math.round(sentScore * 100.0) / 100.0);
        o.put("bullish_posts", bull);
        o.put("bearish_posts", bear);
        o.put("sarcastic_posts", sarcastic);
        o.put("filtered_junk", filteredJunk); // additive only; schema otherwise unchanged
        o.put("top_titles", topTitles);
        o.put("stale", stale);
        o.put("no_data", noData);
        o.put("low_confidence", lowConfidence);
        return o;
    }

    /**
     * Conservative junk pre-filter (2026-09-28): removes only CLEAR junk —
     * bot posts, removed/deleted bodies, and zero-signal ticker-only posts.
     * When in doubt it keeps the post and lets the scorer handle it.
     */
    static final Pattern BOT_AUTHOR =
        Pattern.compile("(?i)^(automoderator|.*bot|.*_bot|bot_.*)$");

    static boolean isJunkPost(JSONObject p) {
        // Removed/deleted bodies carry no signal.
        String body = p.optString("body", "").strip();
        if (body.equalsIgnoreCase("[removed]") || body.equalsIgnoreCase("[deleted]"))
            return true;
        // Known bot accounts (AutoModerator, *Bot, *_bot, bot_*).
        String author = p.optString("author", "").strip();
        if (!author.isEmpty() && BOT_AUTHOR.matcher(author).matches())
            return true;
        // Zero-signal: no body, and the title is nothing but a bare
        // ticker mention (e.g. "$NVDA", "NVDA", "🚀"). Strip ticker-ish
        // tokens; if fewer than 5 letters of real text remain, it's junk.
        // Real short posts ("AMD buy", "NVDA to the moon") survive this.
        if (body.isEmpty()) {
            String sig = p.optString("title", "")
                .replaceAll("\\$[A-Za-z]{1,5}(\\.[A-Za-z])?", " ")
                .replaceAll("\\b[A-Z]{1,5}(\\.[A-Z])?\\b", " ")
                .replaceAll("[^a-zA-Z ]", " ").strip();
            if (sig.length() < 5) return true;
        }
        return false;
    }

    /**
     * Mention matching (2026-09-28 disambiguation):
     * 1. "$TICKER" anywhere — top confidence, unchanged.
     * 2. Bare ticker as an UPPERCASE whole word (len>=2). 1-char "V" stays
     *    $V-only: a bare "V" is an English letter, not a mention. This newly
     *    covers 2-char tickers (RH, MA) that were under-counted.
     * 3. Company-name words (>=4 chars) as case-SENSITIVE whole words:
     *    "Meta" matches, lowercase "meta" prose no longer inflates META.
     */
    static boolean mentionsTicker(String text, String ticker, String company) {
        if (text.contains("$" + ticker)) return true;
        if (ticker.length() >= 2 && Pattern.compile("\\b" + Pattern.quote(ticker) + "\\b")
                .matcher(text).find()) return true;
        for (String w : company.split("\\s+")) {
            if (w.length() >= 4 && Pattern.compile("\\b" + Pattern.quote(w) + "\\b")
                    .matcher(text).find()) return true;
        }
        return false;
    }

    /** Lexicon sentiment: bull words minus bear words (whole-word). */
    static int sentiment(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        int s = 0;
        for (String w : BULL) if (t.contains(" " + w + " ")) s++;
        for (String w : BEAR) if (t.contains(" " + w + " ")) s--;
        return s;
    }

    /** Digest-ready section text. */
    static String digestSection(JSONObject out) {
        StringBuilder sb = new StringBuilder();
        String scorer = out.optString("scorer", "lexicon");
        sb.append("📊 Crowd pulse — Reddit sentiment (r/stocks, r/wallstreetbets, "
            + "r/investing, r/ValueInvesting, last 7d vs prior 3wk avg). "
            + "Sentiment only — NOT a valuation input.\n");
        sb.append("Scorer: " + ("finbert+vader".equals(scorer)
            ? "ModernFinBERT+VADER ensemble + irony check "
                + "(sarcastic posts discounted toward neutral, flagged)"
            : "lexicon fallback (no sarcasm detection)")
            + ". Weak on sarcasm/irony — treat mixed/neutral as uncertain.\n");
        JSONArray tickers = out.getJSONArray("tickers");
        List<String> spikes = new ArrayList<>(), rest = new ArrayList<>();
        for (int i = 0; i < tickers.length(); i++) {
            JSONObject t = tickers.getJSONObject(i);
            String line;
            if (t.optBoolean("stale", false)) {
                rest.add("⚠️ " + t.getString("ticker") + ": data stale — no reading");
                continue;
            }
            if (t.optBoolean("no_data", false)) {
                rest.add(t.getString("ticker") + " 0 mentions (no discussion in window)");
                continue;
            }
            line = t.getString("ticker") + " " + t.getInt("week_mentions")
                + " mentions (" + t.getDouble("spike_ratio") + "x normal), "
                + t.getString("sentiment");
            if (t.optBoolean("low_confidence", false))
                line += " [low confidence — possible data gap]";
            int sarcN = t.optInt("sarcastic_posts", 0);
            if (sarcN > 0)
                line += " [" + sarcN + " flagged sarcastic, discounted]";
            if (t.getBoolean("spike")) spikes.add("🔥 " + line);
            else rest.add(line);
        }
        if (spikes.isEmpty()) sb.append("No mention spikes this week.\n");
        else {
            sb.append("Spikes:\n");
            for (String s : spikes) sb.append("- ").append(s).append("\n");
        }
        sb.append("Rest: ").append(String.join(" | ", rest));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Self-test (no network) — includes the HARD FENCE assertion.
    // Uses the shared SelfTest helper (~/workspace/java-shared), 2026-09-28.
    // ------------------------------------------------------------------

    static void runSelfTest() throws Exception {
        // --- FENCE: this tool must have no CODE path to valuation backends ---
        // (strip comments AND string literals first: the fence is documented
        // in the javadoc and in a store note string above)
        String src = Files.readString(
            STATE_DIR.resolve("CrowdPulse.java"), StandardCharsets.UTF_8);
        src = src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ")
                 .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
        SelfTest.check("fence: no Proposals reference", !src.contains("Proposals"));
        SelfTest.check("fence: no blessed reference", !src.contains("blessed"));
        SelfTest.check("fence: no dcf_csv reference", !src.contains("dcf_csv"));
        SelfTest.check("fence: no creator_tracker reference", !src.contains("creator_tracker"));
        SelfTest.check("fence: store lives in WSB goal dir, not valuation dirs", STORE.toString().contains("wallstreetbets-news-watch"));

        // --- mention matching ---
        SelfTest.check("$NVDA matches", mentionsTicker("I love $NVDA calls", "NVDA", "NVIDIA"));
        SelfTest.check("bare NVDA matches", mentionsTicker("NVDA earnings beat", "NVDA", "NVIDIA"));
        SelfTest.check("bare V rejected", !mentionsTicker("V for victory", "V", "Visa"));
        SelfTest.check("$V matches", mentionsTicker("$V upgrade", "V", "Visa"));
        SelfTest.check("company name matches", mentionsTicker("Visa raises guidance", "V", "Visa"));
        // --- 2026-09-28 disambiguation ---
        SelfTest.check("lowercase meta prose rejected", !mentionsTicker("the meta debate about social media", "META", "Meta Platforms"));
        SelfTest.check("capitalized Meta still matches", mentionsTicker("Meta earnings beat", "META", "Meta Platforms"));
        SelfTest.check("uppercase 2-char bare ticker matches", mentionsTicker("RH is rallying hard", "RH", "RH"));
        SelfTest.check("lowercase rh rejected", !mentionsTicker("the rh factor in blood types", "RH", "RH"));
        SelfTest.check("uppercase MA bare matches", mentionsTicker("MA raised its fees", "MA", "Mastercard"));
        SelfTest.check("lowercase ma rejected", !mentionsTicker("I took a ma-t break", "MA", "Mastercard"));

        // --- aggregation: spike math ---
        long now = Instant.now().getEpochSecond();

        // --- junk pre-filter (conservative: only clear junk dropped) ---
        SelfTest.check("AutoModerator post dropped", isJunkPost(post("j1", "stocks", "AutoModerator", "$NVDA", "daily thread",
            now - 86400, "")));
        SelfTest.check("*Bot author dropped", isJunkPost(post("j2", "stocks", "RemindMeBot", "reminder", "I will remind",
            now - 86400, "")));
        SelfTest.check("[removed] body dropped", isJunkPost(post("j3", "stocks", "someuser", "NVDA", "[removed]",
            now - 86400, "")));
        SelfTest.check("[deleted] body dropped", isJunkPost(post("j4", "stocks", "someuser", "NVDA", "[deleted]",
            now - 86400, "")));
        SelfTest.check("title-only $TICKER spam dropped", isJunkPost(post("j5", "stocks", "someuser", "$NVDA", "",
            now - 86400, "")));
        SelfTest.check("title-only bare ticker dropped", isJunkPost(post("j6", "stocks", "someuser", "NVDA", "",
            now - 86400, "")));
        SelfTest.check("short real post kept", !isJunkPost(post("j7", "stocks", "someuser", "AMD buy", "bullish",
            now - 86400, "")));
        SelfTest.check("title-only real mention kept", !isJunkPost(post("j8", "stocks", "someuser", "NVDA to the moon", "",
            now - 86400, "")));
        SelfTest.check("author merely containing 'bot' mid-word kept", !isJunkPost(post("j9", "stocks", "lobotomyfan", "NVDA", "real talk",
            now - 86400, "")));

        // --- aggregation counts filtered junk separately ---
        List<JSONObject> mixed = new ArrayList<>();
        mixed.add(post("m1", "stocks", "AutoModerator", "$PLTR", "daily thread",
            now - 86400, ""));
        mixed.add(post("m2", "stocks", "someuser", "$PLTR", "",
            now - 86400, ""));
        mixed.add(post("m3", "stocks", "someuser", "PLTR earnings beat, buy",
            "bullish", now - 86400, ""));
        JSONObject m2 = aggregate("PLTR", "Palantir", mixed, now);
        SelfTest.check("2 junk posts filtered", m2.getInt("filtered_junk") == 2);
        SelfTest.check("1 real post counted", m2.getInt("week_mentions") == 1);

        // --- sentiment ---
        SelfTest.check("bullish text scores >0", sentiment("buy the dip, bullish breakout") > 0);
        SelfTest.check("bearish text scores <0", sentiment("sell now, crash coming, puts") < 0);
        SelfTest.check("neutral text scores 0", sentiment("the company reported tuesday") == 0);

        List<JSONObject> posts = new ArrayList<>();
        // baseline: 3 posts/week over weeks 2-4 (9 posts)
        for (int d = 8; d <= 28; d += 7)
            for (int k = 0; k < 3; k++)
                posts.add(post("b" + d + k, "stocks", "someuser", "PLTR discussion",
                    "palantir", now - (long) d * 86400, ""));
        // this week: 12 bullish posts -> spike
        for (int k = 0; k < 12; k++)
            posts.add(post("w" + k, "wallstreetbets", "someuser", "PLTR to the moon, buy calls",
                "bullish", now - 86400, ""));
        JSONObject agg = aggregate("PLTR", "Palantir", posts, now);
        SelfTest.check("week count 12", agg.getInt("week_mentions") == 12);
        SelfTest.check("4x spike flagged", agg.getBoolean("spike"));
        SelfTest.check("bullish label", agg.getString("sentiment").equals("leaning bullish"));

        // --- no spike when quiet ---
        List<JSONObject> quiet = new ArrayList<>();
        for (int d = 1; d <= 28; d += 7)
            quiet.add(post("q" + d, "stocks", "someuser", "JNJ steady", "johnson", now - (long) d * 86400, ""));
        JSONObject q2 = aggregate("JNJ", "Johnson & Johnson", quiet, now);
        SelfTest.check("quiet ticker no spike", !q2.getBoolean("spike"));

        // --- dedupe across sources ---
        List<JSONObject> dup = new ArrayList<>();
        dup.add(post("x1", "stocks", "someuser", "AMD buy", "bullish", now - 86400, ""));
        dup.add(post("x1", "stocks", "someuser", "AMD buy", "bullish", now - 86400, ""));
        JSONObject d2 = aggregate("AMD", "AMD", dup, now);
        SelfTest.check("duplicate post id counted once", d2.getInt("week_mentions") == 1);

        // --- freshness guard: newest post 10d old -> stale, no reading ---
        List<JSONObject> old = new ArrayList<>();
        for (int k = 0; k < 6; k++)
            old.add(post("o" + k, "stocks", "someuser", "NVDA thread", "nvidia",
                now - 10L * 86400, ""));
        JSONObject o2 = aggregate("NVDA", "NVIDIA", old, now);
        SelfTest.check("10-day-old newest post flagged stale", o2.getBoolean("stale"));
        SelfTest.check("stale ticker never spikes", !o2.getBoolean("spike"));
        SelfTest.check("stale sentiment labeled", o2.getString("sentiment").contains("stale"));

        // --- completeness: healthy baseline, hollow latest week -> low confidence ---
        List<JSONObject> gap = new ArrayList<>();
        for (int d = 8; d <= 28; d++)
            gap.add(post("g" + d, "stocks", "someuser", "TSLA talk", "tesla",
                now - (long) d * 86400, ""));
        for (int k = 0; k < 2; k++)
            gap.add(post("h" + k, "stocks", "someuser", "TSLA talk", "tesla",
                now - 86400, ""));
        JSONObject g2 = aggregate("TSLA", "Tesla", gap, now);
        SelfTest.check("hollow latest week flagged low-confidence", g2.getBoolean("low_confidence"));
        SelfTest.check("fresh data not stale", !g2.getBoolean("stale"));

        // --- empty window -> no_data, not stale ---
        JSONObject e2 = aggregate("WH", "Wyndham", new ArrayList<>(), now);
        SelfTest.check("empty window flagged no_data", e2.getBoolean("no_data"));
        SelfTest.check("empty window not stale", !e2.getBoolean("stale"));

        SelfTest.finish();
    }

}
