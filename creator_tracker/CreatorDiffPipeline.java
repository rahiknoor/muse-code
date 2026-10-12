// CreatorDiffPipeline.java
//
// One-command pipeline for the weekly `creator-holdings-diff` cron
// (idea #43), with a built-in health-check verifier (idea #44).
//
// Diffs Parkev Tatevosian's stated values
// (~/workspace/creator_tracker/parkev_targets.json, latest call per ticker)
// against Rahik's holdings (~/workspace/stock_watch/watchlist.json) and
// prints the three sections as JSON on stdout for the delivery step:
//
//   IN BOTH      — Parkev covers it and Rahik owns it
//   CREATOR-ONLY — Parkev stated a value, not in Rahik's holdings
//   YOU-ONLY     — Rahik owns it, Parkev never stated a value for it
//
// This program is PURELY read-only: it writes nothing. The cron body feeds
// its stdout JSON to the delivery step.
//
// Usage:
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/creator_tracker/CreatorDiffPipeline.java --run [--root DIR] [--today YYYY-MM-DD]
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/creator_tracker/CreatorDiffPipeline.java --verify [--diff FILE] [--root DIR] [--today YYYY-MM-DD]
//
// --verify (idea #44) fails loud when:
//   * any reported value does not trace to parkev_targets.json (no invented values),
//   * the three sections do not exactly partition the union of tickers
//     (a ticker missing or duplicated),
//   * a "new this week" flag sits on an entry whose call_date is older than 7 days.
// With --diff FILE it checks the diff artifact in FILE (what --run printed and
// the body delivered); without it, it re-derives the diff and checks that.

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;

public class CreatorDiffPipeline {

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final ZoneId ET = ZoneId.of("America/New_York");

    // Share-class aliases: creator calls sometimes use the other class of a
    // dual-class ticker. Everything keys on the holding ticker (same rule as
    // BuildCreatorValues.java).
    static final Map<String, String> TICKER_ALIASES = Map.of("GOOGL", "GOOG");

    static Path workspaceRoot = Path.of(homeDir(), "workspace");
    static LocalDate today = LocalDate.now(ET);
    static String diffFile = null;

    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run": mode = "run"; break;
                case "--verify": mode = "verify"; break;
                case "--root": workspaceRoot = Path.of(args[++i], "workspace"); break;
                case "--today": today = LocalDate.parse(args[++i], DAY); break;
                case "--diff": diffFile = args[++i]; break;
                default: break;
            }
        }
        if (mode == null) {
            System.out.println("Usage: CreatorDiffPipeline.java --run [--root DIR] [--today YYYY-MM-DD]");
            System.out.println("       CreatorDiffPipeline.java --verify [--diff FILE] [--root DIR] [--today YYYY-MM-DD]");
            System.exit(2);
        }
        if (mode.equals("run")) run();
        else verify();
    }

    // Latest creator call per ticker: greatest call_date wins; ties break on
    // the greatest entry id (ids are zero-padded, so this is chronological).
    // Unparseable call_dates (e.g. "unknown") sort as older than every real
    // date so they never win against a dated call; a lone unparseable call
    // is still kept, with new_this_week=false (see sectionEntry).
    static LocalDate parseLenient(String s) {
        try { return LocalDate.parse(s, DAY); }
        catch (Exception e) { return LocalDate.MIN; }
    }
    static Map<String, JSONObject> latestCalls(JSONArray entries) {
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            // Title-derived placeholders (OPTION 2 backfill) carry no stated
            // value yet; the weekly diff only covers tickers Parkev has a
            // stated value for (same null-skip as BuildCreatorValues and
            // DetectRevisions).
            if (e.isNull("target_value")) continue;
            String ticker = TICKER_ALIASES.getOrDefault(
                    e.getString("ticker").toUpperCase(), e.getString("ticker").toUpperCase());
            JSONObject cur = latest.get(ticker);
            boolean wins = cur == null;
            if (!wins) {
                LocalDate dNew = parseLenient(e.getString("call_date"));
                LocalDate dCur = parseLenient(cur.getString("call_date"));
                wins = dNew.compareTo(dCur) > 0
                        || (dNew.equals(dCur)
                            && e.getString("id").compareTo(cur.getString("id")) > 0);
            }
            if (wins) {
                latest.put(ticker, e);
            }
        }
        return latest;
    }

    static JSONArray readEntries(Path trackerFile) throws Exception {
        String text = Files.readString(trackerFile, StandardCharsets.UTF_8).trim();
        if (text.startsWith("[")) return new JSONArray(text);
        return new JSONObject(text).getJSONArray("entries");
    }

    static List<String> readWatchlist(Path watchlistFile) throws Exception {
        JSONObject o = new JSONObject(Files.readString(watchlistFile, StandardCharsets.UTF_8));
        JSONArray tickers = o.getJSONArray("tickers");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tickers.length(); i++) out.add(tickers.getString(i).toUpperCase());
        return out;
    }

    // "New this week": the latest call for the ticker is dated within the
    // last 7 days (inclusive). An unparseable call_date (e.g. "unknown") is
    // never flagged new.
    static boolean isNewThisWeek(JSONObject latestCall) {
        LocalDate callDate = parseLenient(latestCall.getString("call_date"));
        if (callDate.equals(LocalDate.MIN)) return false;
        long daysAgo = ChronoUnit.DAYS.between(callDate, today);
        return daysAgo >= 0 && daysAgo <= 7;
    }

    static JSONObject sectionEntry(String ticker, JSONObject call) {
        JSONObject o = new JSONObject();
        o.put("ticker", ticker);
        o.put("company", call.optString("company", ticker));
        o.put("value", call.getDouble("target_value"));
        o.put("value_type", call.getString("target_type"));
        o.put("call_date", call.getString("call_date"));
        o.put("video_title", call.optString("video_title", "not recorded"));
        o.put("new_this_week", isNewThisWeek(call));
        return o;
    }

    static JSONObject buildDiff() throws Exception {
        Path trackerFile = workspaceRoot.resolve("creator_tracker").resolve("parkev_targets.json");
        Path watchlistFile = workspaceRoot.resolve("stock_watch").resolve("watchlist.json");
        Map<String, JSONObject> latest = latestCalls(readEntries(trackerFile));
        List<String> holdings = readWatchlist(watchlistFile);
        java.util.Set<String> holdingSet = new TreeSet<>(holdings);

        JSONArray inBoth = new JSONArray(), creatorOnly = new JSONArray(), youOnly = new JSONArray();
        // TreeMap/TreeSet keep the sections in ticker order for a stable report.
        for (String ticker : new TreeSet<>(latest.keySet())) {
            JSONObject entry = sectionEntry(ticker, latest.get(ticker));
            (holdingSet.contains(ticker) ? inBoth : creatorOnly).put(entry);
        }
        for (String ticker : new TreeSet<>(holdingSet)) {
            if (!latest.containsKey(ticker)) youOnly.put(ticker);
        }

        JSONObject out = new JSONObject();
        out.put("run_date", today.format(DAY));
        out.put("in_both", inBoth);
        out.put("creator_only", creatorOnly);
        out.put("you_only", youOnly);
        JSONObject counts = new JSONObject();
        counts.put("in_both", inBoth.length());
        counts.put("creator_only", creatorOnly.length());
        counts.put("you_only", youOnly.length());
        out.put("counts", counts);
        return out;
    }

    static void run() throws Exception {
        System.out.println(buildDiff().toString(2));
    }

    // --verify checks the diff ARTIFACT against the raw source files: with
    // --diff FILE it reads the artifact the body delivered; otherwise it
    // re-derives the diff and checks the pipeline's own output. Either way
    // the three invariants the job promises are checked with checking code
    // that is independent of the build code above.
    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        Path trackerFile = workspaceRoot.resolve("creator_tracker").resolve("parkev_targets.json");
        Path watchlistFile = workspaceRoot.resolve("stock_watch").resolve("watchlist.json");

        Map<String, JSONObject> latest;
        List<String> holdings;
        try {
            latest = latestCalls(readEntries(trackerFile));
            holdings = readWatchlist(watchlistFile);
        } catch (Exception e) {
            System.err.println("VERIFY FAILED:\n  - cannot read inputs: " + e.getMessage());
            System.exit(1);
            return;
        }

        JSONObject diff;
        try {
            diff = diffFile != null
                    ? new JSONObject(Files.readString(Path.of(diffFile), StandardCharsets.UTF_8))
                    : buildDiff();
        } catch (Exception e) {
            System.err.println("VERIFY FAILED:\n  - cannot read/parse diff "
                    + (diffFile != null ? "file " + diffFile : "output") + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        validateDiff(diff, latest, holdings, problems);

        if (!problems.isEmpty()) {
            System.err.println("VERIFY FAILED:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(1);
        }
        JSONArray inBoth = diff.getJSONArray("in_both");
        JSONArray creatorOnly = diff.getJSONArray("creator_only");
        JSONArray youOnly = diff.getJSONArray("you_only");
        System.out.println("VERIFY OK: diff partitions "
                + (inBoth.length() + creatorOnly.length() + youOnly.length())
                + " tickers (" + inBoth.length() + " in both, "
                + creatorOnly.length() + " creator-only, " + youOnly.length()
                + " you-only); all values trace to parkev_targets.json.");
    }

    static void validateDiff(JSONObject diff, Map<String, JSONObject> latest,
                             List<String> holdings, List<String> problems) {
        JSONArray inBoth = diff.getJSONArray("in_both");
        JSONArray creatorOnly = diff.getJSONArray("creator_only");
        JSONArray youOnly = diff.getJSONArray("you_only");

        // Invariant 1: exact partition of the union, no ticker missing or twice.
        java.util.Set<String> seen = new TreeSet<>();
        java.util.Set<String> union = new TreeSet<>(latest.keySet());
        union.addAll(holdings);
        List<String> dupes = new ArrayList<>();
        for (JSONArray section : new JSONArray[]{inBoth, creatorOnly, youOnly}) {
            for (int i = 0; i < section.length(); i++) {
                // YOU-ONLY entries are plain ticker strings, the other two
                // sections are objects with a "ticker" field.
                Object raw = section.get(i);
                String t = raw instanceof JSONObject o ? o.getString("ticker") : (String) raw;
                if (!seen.add(t)) dupes.add(t);
            }
        }
        if (!dupes.isEmpty())
            problems.add("ticker(s) appear in more than one section: " + dupes);
        java.util.Set<String> missing = new TreeSet<>(union);
        missing.removeAll(seen);
        if (!missing.isEmpty())
            problems.add("ticker(s) missing from all sections: " + missing);
        java.util.Set<String> extra = new TreeSet<>(seen);
        extra.removeAll(union);
        if (!extra.isEmpty())
            problems.add("ticker(s) in sections but in neither source file: " + extra);

        // Invariant 2 + 3: every value traces to the tracker; new-this-week honest.
        for (JSONArray section : new JSONArray[]{inBoth, creatorOnly}) {
            for (int i = 0; i < section.length(); i++) {
                JSONObject e = section.getJSONObject(i);
                String t = e.getString("ticker");
                JSONObject call = latest.get(t);
                if (call == null) {
                    problems.add(t + ": section entry has no tracker call behind it");
                    continue;
                }
                if (e.getDouble("value") != call.getDouble("target_value")
                        || !e.getString("value_type").equals(call.getString("target_type"))
                        || !e.getString("call_date").equals(call.getString("call_date"))) {
                    problems.add(t + ": reported value/type/date does not match "
                            + "the latest tracker call (possible invented value)");
                }
                boolean flagged = e.getBoolean("new_this_week");
                boolean honest = isNewThisWeek(call);
                if (flagged && !honest)
                    problems.add(t + ": flagged 'new this week' but call_date "
                            + call.getString("call_date") + " is older than 7 days");
                if (!flagged && honest)
                    problems.add(t + ": call_date " + call.getString("call_date")
                            + " is within 7 days but not flagged 'new this week'");
            }
        }
    }
}
