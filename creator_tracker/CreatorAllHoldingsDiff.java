// CreatorAllHoldingsDiff.java
//
// All-creators holdings diff (Rahik's 2026-10-03 decision: not Parkev-only,
// all YouTubers). Generalizes CreatorDiffPipeline.java (which stays
// untouched as LastGood until the shadow run passes).
//
// Reads ALL creator target files, computes per creator the latest stated
// value per ticker, and builds per-creator sections:
//
//   IN BOTH      — creator covers it and Rahik owns it
//   CREATOR-ONLY — creator stated a value, not in Rahik's holdings
//   NOT_SCOREABLE — latest call for the ticker is a buy call with no
//                   stated price (e.g. Jeremy's WYNN buy) — unscorable,
//                   kept visible but never in the valued sections
//
// plus one shared YOU-ONLY (holdings no creator covers).
//
// Side effects (--run): writes the artifact to the canonical persistent
// path ~/workspace/goals/daily-youtube-stock-video-digest/hidden_files/
// creator_holdings_diff_all.json (the old /tmp-only artifact was a gap)
// AND to /tmp/creator-holdings-diff.json (worker convenience), plus the
// derived view ~/workspace/creator_tracker/creator_calls_by_creator.json
// ({ticker: [{creator, type, value, call_date, video_title, video_url,
// scoreable}]}, latest call per creator per ticker) for the Atlas
// stock-note expansion. The diff JSON is also printed on stdout.
//
// This program NEVER touches ~/workspace/dcf_csv/creator_values.json or
// the comparison table / workbook — those keep their single-value
// "latest" semantics by design.
//
// Usage:
//   java --class-path ~/workspace/java-shared/classes:$HOME/workspace/lib/json/json-20240303.jar \
//        ~/workspace/creator_tracker/CreatorAllHoldingsDiff.java --run [--root DIR] [--today YYYY-MM-DD]
//   ... --verify [--diff FILE] [--view FILE] [--root DIR] [--today YYYY-MM-DD]
//   ... --self-test
//
// --verify fails loud when any artifact value disagrees with the tracker
// latest, sections do not partition the ticker union, new_this_week flags
// mismatch, or the derived view disagrees with the trackers.

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

public class CreatorAllHoldingsDiff {

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final ZoneId ET = ZoneId.of("America/New_York");

    static final Map<String, String> TICKER_ALIASES = Map.of("GOOGL", "GOOG");

    // Ordered creator sources: fixed creator first (display name is the
    // file's creator), then other_creators_targets.json grouped by
    // creator_channel. Order is also the display/priority order.
    static final String[][] FIXED_CREATORS = {
        {"parkev_targets.json", "Parkev Tatevosian"},
        {"jeremy_targets.json", "Jeremy Lefebvre"},
        {"couch_investor_targets.json", "Couch Investor"},
        {"bottom_finders_targets.json", "Bottom Finders"},
    };
    static final String OTHER_CREATORS_FILE = "other_creators_targets.json";

    static final Path CANONICAL_DIFF = Path.of("goals", "daily-youtube-stock-video-digest",
            "hidden_files", "creator_holdings_diff_all.json");
    static final Path VIEW_FILE = Path.of("creator_tracker", "creator_calls_by_creator.json");

    static Path workspaceRoot = Path.of(homeDir(), "workspace");
    static LocalDate today = LocalDate.now(ET);
    static String diffFile = null;
    static String viewFileOverride = null;

    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run": mode = "run"; break;
                case "--verify": mode = "verify"; break;
                case "--self-test": mode = "selftest"; break;
                case "--root": workspaceRoot = Path.of(args[++i], "workspace"); break;
                case "--today": today = LocalDate.parse(args[++i], DAY); break;
                case "--diff": diffFile = args[++i]; break;
                case "--view": viewFileOverride = args[++i]; break;
                default: break;
            }
        }
        if (mode == null) {
            System.out.println("Usage: CreatorAllHoldingsDiff.java --run [--root DIR] [--today YYYY-MM-DD]");
            System.out.println("       CreatorAllHoldingsDiff.java --verify [--diff FILE] [--view FILE] [--root DIR] [--today YYYY-MM-DD]");
            System.out.println("       CreatorAllHoldingsDiff.java --self-test");
            System.exit(2);
        }
        if (mode.equals("run")) run();
        else if (mode.equals("verify")) verify();
        else selfTest();
    }

    // ---- parsing helpers (same semantics as CreatorDiffPipeline) ----

    static LocalDate parseLenient(String s) {
        try { return LocalDate.parse(s, DAY); }
        catch (Exception e) { return LocalDate.MIN; }
    }

    static String normTicker(String t) {
        String u = t.toUpperCase();
        return TICKER_ALIASES.getOrDefault(u, u);
    }

    // call_date can be JSON null (title-derived placeholders) — never
    // assume a string; "unknown" sorts as oldest and never flags new.
    static String callDateStr(JSONObject e) {
        return e.optString("call_date", "unknown");
    }

    static JSONArray readEntries(Path trackerFile) throws Exception {
        String text = Files.readString(trackerFile, StandardCharsets.UTF_8).trim();
        if (text.startsWith("[")) return new JSONArray(text);
        return new JSONObject(text).getJSONArray("entries");
    }

    // Latest SCOREABLE call per ticker (stated number present). Ties on
    // call_date break on the greatest entry id (chronological).
    static Map<String, JSONObject> latestCalls(JSONArray entries) {
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (e.isNull("target_value")) continue;
            String ticker = normTicker(e.getString("ticker"));
            JSONObject cur = latest.get(ticker);
            boolean wins = cur == null;
            if (!wins) {
                LocalDate dNew = parseLenient(callDateStr(e));
                LocalDate dCur = parseLenient(callDateStr(cur));
                wins = dNew.compareTo(dCur) > 0
                        || (dNew.equals(dCur)
                            && e.getString("id").compareTo(cur.getString("id")) > 0);
            }
            if (wins) latest.put(ticker, e);
        }
        return latest;
    }

    // Latest call per ticker REGARDLESS of scoreability — used to find the
    // not-scoreable ones (buy calls with no stated price). If a ticker's
    // latest call overall is scoreable it never lands here.
    static Map<String, JSONObject> latestCallsAny(JSONArray entries) {
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            String ticker = normTicker(e.getString("ticker"));
            JSONObject cur = latest.get(ticker);
            boolean wins = cur == null;
            if (!wins) {
                LocalDate dNew = parseLenient(callDateStr(e));
                LocalDate dCur = parseLenient(callDateStr(cur));
                wins = dNew.compareTo(dCur) > 0
                        || (dNew.equals(dCur)
                            && e.getString("id").compareTo(cur.getString("id")) > 0);
            }
            if (wins) latest.put(ticker, e);
        }
        return latest;
    }

    static List<String> readWatchlist(Path watchlistFile) throws Exception {
        JSONObject o = new JSONObject(Files.readString(watchlistFile, StandardCharsets.UTF_8));
        Object tickers = o.get("tickers");
        List<String> out = new ArrayList<>();
        if (tickers instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                Object t = arr.get(i);
                out.add(t instanceof JSONObject jo ? jo.getString("ticker").toUpperCase()
                                                  : String.valueOf(t).toUpperCase());
            }
        }
        return out;
    }

    static boolean isNewThisWeek(JSONObject latestCall) {
        LocalDate callDate = parseLenient(callDateStr(latestCall));
        if (callDate.equals(LocalDate.MIN)) return false;
        long daysAgo = ChronoUnit.DAYS.between(callDate, today);
        return daysAgo >= 0 && daysAgo <= 7;
    }

    // ---- per-creator input assembly ----

    // Ordered map: creator display name -> JSONArray of entries.
    static Map<String, JSONArray> creatorSources() throws Exception {
        Map<String, JSONArray> out = new LinkedHashMap<>();
        Path dir = workspaceRoot.resolve("creator_tracker");
        for (String[] fc : FIXED_CREATORS) {
            out.put(fc[1], readEntries(dir.resolve(fc[0])));
        }
        // other_creators: group by creator_channel, alphabetical for stability.
        JSONArray other = readEntries(dir.resolve(OTHER_CREATORS_FILE));
        Map<String, JSONArray> byChannel = new TreeMap<>();
        for (int i = 0; i < other.length(); i++) {
            JSONObject e = other.getJSONObject(i);
            String channel = e.optString("creator_channel", "Other creators");
            byChannel.computeIfAbsent(channel, k -> new JSONArray()).put(e);
        }
        out.putAll(byChannel);
        return out;
    }

    // Section entry shape is byte-compatible with CreatorDiffPipeline's so
    // the shadow run can compare Parkev sections exactly.
    static JSONObject sectionEntry(String ticker, JSONObject call) {
        JSONObject o = new JSONObject();
        o.put("ticker", ticker);
        o.put("company", call.optString("company", ticker));
        o.put("value", call.getDouble("target_value"));
        o.put("value_type", call.getString("target_type"));
        o.put("call_date", callDateStr(call));
        o.put("video_title", call.optString("video_title", "not recorded"));
        o.put("new_this_week", isNewThisWeek(call));
        return o;
    }

    static JSONObject notScoreableEntry(String ticker, JSONObject call) {
        JSONObject o = new JSONObject();
        o.put("ticker", ticker);
        o.put("id", call.optString("id", ""));
        o.put("call_date", call.optString("call_date", "unknown"));
        o.put("video_title", call.optString("video_title", "not recorded"));
        o.put("reason", "buy call with no stated price — unscorable");
        return o;
    }

    static class CreatorDiff {
        String creator;
        Map<String, JSONObject> latest;        // scoreable latest per ticker
        Map<String, JSONObject> notScoreable; // latest-overall is null-target
        JSONArray inBoth = new JSONArray();
        JSONArray creatorOnly = new JSONArray();
        JSONArray notScoreableArr = new JSONArray();
    }

    static List<CreatorDiff> buildCreatorDiffs(List<String> holdings) throws Exception {
        Map<String, JSONArray> sources = creatorSources();
        java.util.Set<String> holdingSet = new TreeSet<>(holdings);
        List<CreatorDiff> diffs = new ArrayList<>();
        for (Map.Entry<String, JSONArray> src : sources.entrySet()) {
            CreatorDiff cd = new CreatorDiff();
            cd.creator = src.getKey();
            cd.latest = latestCalls(src.getValue());
            Map<String, JSONObject> anyLatest = latestCallsAny(src.getValue());
            cd.notScoreable = new TreeMap<>();
            for (Map.Entry<String, JSONObject> e : anyLatest.entrySet()) {
                if (cd.latest.containsKey(e.getKey())) continue;
                // Title-derived placeholders (OPTION 2 backfill) are coverage
                // markers, not creator-stated buy calls — never listed as
                // not-scoreable either; they vanish the moment a real call
                // for the ticker arrives.
                if ("title-derived".equals(e.getValue().optString("status", ""))) continue;
                cd.notScoreable.put(e.getKey(), e.getValue());
            }
            for (String ticker : new TreeSet<>(cd.latest.keySet())) {
                (holdingSet.contains(ticker) ? cd.inBoth : cd.creatorOnly)
                        .put(sectionEntry(ticker, cd.latest.get(ticker)));
            }
            for (String ticker : cd.notScoreable.keySet()) {
                cd.notScoreableArr.put(notScoreableEntry(ticker, cd.notScoreable.get(ticker)));
            }
            diffs.add(cd);
        }
        return diffs;
    }

    // Derived view: latest call PER CREATOR per ticker (scoreable or not).
    static JSONObject buildView(List<CreatorDiff> diffs) {
        Map<String, List<JSONObject>> byTicker = new TreeMap<>();
        for (CreatorDiff cd : diffs) {
            Map<String, JSONObject> all = new TreeMap<>();
            for (Map.Entry<String, JSONObject> e : cd.latest.entrySet()) all.put(e.getKey(), e.getValue());
            for (Map.Entry<String, JSONObject> e : cd.notScoreable.entrySet()) all.put(e.getKey(), e.getValue());
            for (Map.Entry<String, JSONObject> e : all.entrySet()) {
                JSONObject call = e.getValue();
                JSONObject v = new JSONObject();
                v.put("creator", cd.creator);
                v.put("type", call.optString("target_type", "buy_price"));
                if (call.isNull("target_value")) v.put("value", JSONObject.NULL);
                else v.put("value", call.getDouble("target_value"));
                v.put("call_date", call.optString("call_date", "unknown"));
                v.put("video_title", call.optString("video_title", "not recorded"));
                v.put("video_url", call.optString("video_url", "not recorded"));
                v.put("scoreable", cd.latest.containsKey(e.getKey()));
                byTicker.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(v);
            }
        }
        JSONObject out = new JSONObject();
        for (Map.Entry<String, List<JSONObject>> e : byTicker.entrySet()) {
            out.put(e.getKey(), new JSONArray(e.getValue()));
        }
        return out;
    }

    static JSONObject buildDiff(List<CreatorDiff> diffs, List<String> holdings) {
        java.util.Set<String> covered = new TreeSet<>();
        for (CreatorDiff cd : diffs) covered.addAll(cd.latest.keySet());

        JSONObject creators = new JSONObject();
        JSONObject counts = new JSONObject();
        for (CreatorDiff cd : diffs) {
            JSONObject sec = new JSONObject();
            sec.put("in_both", cd.inBoth);
            sec.put("creator_only", cd.creatorOnly);
            sec.put("not_scoreable", cd.notScoreableArr);
            creators.put(cd.creator, sec);
            JSONObject c = new JSONObject();
            c.put("in_both", cd.inBoth.length());
            c.put("creator_only", cd.creatorOnly.length());
            c.put("not_scoreable", cd.notScoreableArr.length());
            counts.put(cd.creator, c);
        }
        JSONArray youOnly = new JSONArray();
        for (String t : new TreeSet<>(holdings)) {
            if (!covered.contains(t)) youOnly.put(t);
        }
        JSONObject out = new JSONObject();
        out.put("run_date", today.format(DAY));
        out.put("creators", creators);
        out.put("you_only", youOnly);
        JSONObject top = new JSONObject();
        top.put("per_creator", counts);
        top.put("you_only", youOnly.length());
        top.put("holdings", holdings.size());
        out.put("counts", top);
        return out;
    }

    static void run() throws Exception {
        List<String> holdings = readWatchlist(workspaceRoot.resolve("stock_watch").resolve("watchlist.json"));
        List<CreatorDiff> diffs = buildCreatorDiffs(holdings);
        JSONObject diff = buildDiff(diffs, holdings);
        JSONObject view = buildView(diffs);

        // Persistent canonical artifact + worker-convenience copy.
        Path canonical = workspaceRoot.resolve(CANONICAL_DIFF);
        Files.createDirectories(canonical.getParent());
        Files.writeString(canonical, diff.toString(2), StandardCharsets.UTF_8);
        Files.writeString(Path.of("/tmp", "creator-holdings-diff.json"), diff.toString(2), StandardCharsets.UTF_8);
        Path viewPath = workspaceRoot.resolve(VIEW_FILE);
        Files.createDirectories(viewPath.getParent());
        Files.writeString(viewPath, view.toString(2), StandardCharsets.UTF_8);

        // Print the diff on stdout for the delivery step (old body's shape).
        System.out.println(diff.toString(2));
    }

    // ---- verify ----

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        List<String> holdings;
        List<CreatorDiff> diffs;
        try {
            holdings = readWatchlist(workspaceRoot.resolve("stock_watch").resolve("watchlist.json"));
            diffs = buildCreatorDiffs(holdings);
        } catch (Exception e) {
            System.err.println("VERIFY FAILED:\n  - cannot read inputs: " + e.getMessage());
            System.exit(1);
            return;
        }

        JSONObject diff;
        try {
            Path dp = diffFile != null ? Path.of(diffFile) : workspaceRoot.resolve(CANONICAL_DIFF);
            diff = new JSONObject(Files.readString(dp, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("VERIFY FAILED:\n  - cannot read/parse diff artifact: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Invariant A: per creator, in_both + creator_only partition that
        // creator's scoreable tickers; every value traces to the tracker;
        // new_this_week honest.
        JSONObject creators = diff.getJSONObject("creators");
        for (CreatorDiff cd : diffs) {
            JSONObject sec = creators.optJSONObject(cd.creator);
            if (sec == null) {
                problems.add("creator '" + cd.creator + "' missing from artifact");
                continue;
            }
            java.util.Set<String> seen = new TreeSet<>();
            for (JSONArray section : new JSONArray[]{sec.getJSONArray("in_both"),
                                                    sec.getJSONArray("creator_only")}) {
                for (int i = 0; i < section.length(); i++) {
                    JSONObject e = section.getJSONObject(i);
                    String t = e.getString("ticker");
                    if (!seen.add(t)) problems.add(cd.creator + ": " + t + " duplicated in sections");
                    JSONObject call = cd.latest.get(t);
                    if (call == null) {
                        problems.add(cd.creator + ": " + t + " has no tracker call behind it");
                        continue;
                    }
                    if (e.getDouble("value") != call.getDouble("target_value")
                            || !e.getString("value_type").equals(call.getString("target_type"))
                            || !e.getString("call_date").equals(callDateStr(call))) {
                        problems.add(cd.creator + ": " + t
                                + " value/type/date does not match the latest tracker call");
                    }
                    if (e.getBoolean("new_this_week") != isNewThisWeek(call)) {
                        problems.add(cd.creator + ": " + t + " new_this_week flag mismatch");
                    }
                    boolean inHoldings = new TreeSet<>(holdings).contains(t);
                    boolean inInBoth = section == sec.getJSONArray("in_both");
                    if (inInBoth != inHoldings) {
                        problems.add(cd.creator + ": " + t + " in wrong section for holdings membership");
                    }
                }
            }
            java.util.Set<String> missing = new TreeSet<>(cd.latest.keySet());
            missing.removeAll(seen);
            if (!missing.isEmpty())
                problems.add(cd.creator + ": tickers missing from sections: " + missing);
            // not_scoreable must match the null-target latest calls exactly.
            JSONArray ns = sec.optJSONArray("not_scoreable");
            java.util.Set<String> nsSeen = new TreeSet<>();
            if (ns != null) {
                for (int i = 0; i < ns.length(); i++) nsSeen.add(ns.getJSONObject(i).getString("ticker"));
            }
            if (!nsSeen.equals(cd.notScoreable.keySet())) {
                problems.add(cd.creator + ": not_scoreable mismatch (artifact=" + nsSeen
                        + " tracker=" + cd.notScoreable.keySet() + ")");
            }
        }
        // No unexpected creators in the artifact.
        for (String name : creators.keySet()) {
            boolean known = diffs.stream().anyMatch(cd -> cd.creator.equals(name));
            if (!known) problems.add("artifact has unknown creator '" + name + "'");
        }

        // Invariant B: you_only = holdings minus all covered tickers, exactly.
        java.util.Set<String> covered = new TreeSet<>();
        for (CreatorDiff cd : diffs) covered.addAll(cd.latest.keySet());
        java.util.Set<String> expectedYouOnly = new TreeSet<>(holdings);
        expectedYouOnly.removeAll(covered);
        JSONArray youOnly = diff.getJSONArray("you_only");
        java.util.Set<String> actualYouOnly = new TreeSet<>();
        for (int i = 0; i < youOnly.length(); i++) actualYouOnly.add(youOnly.getString(i));
        if (!actualYouOnly.equals(expectedYouOnly)) {
            problems.add("you_only mismatch (artifact=" + actualYouOnly
                    + " expected=" + expectedYouOnly + ")");
        }

        // Invariant C: the derived view traces to the trackers.
        Path vp = viewFileOverride != null ? Path.of(viewFileOverride)
                                          : workspaceRoot.resolve(VIEW_FILE);
        if (Files.exists(vp)) {
            JSONObject view = new JSONObject(Files.readString(vp, StandardCharsets.UTF_8));
            Map<String, Map<String, JSONObject>> expected = new TreeMap<>();
            for (CreatorDiff cd : diffs) {
                Map<String, JSONObject> all = new TreeMap<>();
                all.putAll(cd.latest);
                all.putAll(cd.notScoreable);
                for (Map.Entry<String, JSONObject> e : all.entrySet()) {
                    expected.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>())
                            .put(cd.creator, e.getValue());
                }
            }
            for (String t : expected.keySet()) {
                JSONArray arr = view.optJSONArray(t);
                if (arr == null) {
                    problems.add("view: ticker " + t + " missing");
                    continue;
                }
                java.util.Set<String> viewCreators = new TreeSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject v = arr.getJSONObject(i);
                    String c = v.getString("creator");
                    viewCreators.add(c);
                    JSONObject call = expected.get(t).get(c);
                    if (call == null) {
                        problems.add("view: " + t + " has unexpected creator " + c);
                        continue;
                    }
                    boolean scoreable = expected.get(t).containsKey(c)
                            && diffs.stream().anyMatch(cd -> cd.creator.equals(c)
                                && cd.latest.containsKey(t));
                    if (v.getBoolean("scoreable") != scoreable) {
                        problems.add("view: " + t + "/" + c + " scoreable flag mismatch");
                    }
                    if (scoreable) {
                        boolean valOk = (call.isNull("target_value") && v.isNull("value"))
                                || (!call.isNull("target_value")
                                    && v.getDouble("value") == call.getDouble("target_value"));
                        if (!valOk || !v.getString("call_date").equals(callDateStr(call))) {
                            problems.add("view: " + t + "/" + c + " value/date does not trace to tracker");
                        }
                    }
                }
                if (!viewCreators.equals(expected.get(t).keySet())) {
                    problems.add("view: " + t + " creator set mismatch (view=" + viewCreators
                            + " expected=" + expected.get(t).keySet() + ")");
                }
            }
            java.util.Set<String> extraTickers = new TreeSet<>(view.keySet());
            extraTickers.removeAll(expected.keySet());
            if (!extraTickers.isEmpty()) problems.add("view: unexpected tickers " + extraTickers);
        } else {
            problems.add("view file missing: " + vp);
        }

        if (!problems.isEmpty()) {
            System.err.println("VERIFY FAILED:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(1);
        }
        System.out.println("VERIFY OK: " + creators.length() + " creators, "
                + expectedCount(diffs) + " scoreable calls partition; you_only=" + youOnly.length()
                + "; view traces to trackers.");
    }

    static int expectedCount(List<CreatorDiff> diffs) {
        int n = 0;
        for (CreatorDiff cd : diffs) n += cd.inBoth.length() + cd.creatorOnly.length();
        return n;
    }

    // ---- self test ----

    static void selfTest() {
        JSONObject c1 = new JSONObject().put("id", "pt-1").put("ticker", "GOOGL")
                .put("target_value", 100.0).put("target_type", "fair_value")
                .put("call_date", "2026-10-01");
        JSONObject c2 = new JSONObject().put("id", "pt-2").put("ticker", "goog")
                .put("target_value", 110.0).put("target_type", "fair_value")
                .put("call_date", "2026-10-01");
        JSONArray arr = new JSONArray().put(c1).put(c2);
        Map<String, JSONObject> latest = latestCalls(arr);
        SelfTest.checkEq("GOOGL normalized to GOOG", true, latest.containsKey("GOOG"));
        SelfTest.checkEq("same-date tie breaks on greatest id", "pt-2", latest.get("GOOG").getString("id"));

        JSONObject nullCall = new JSONObject().put("id", "jl-1").put("ticker", "WYNN")
                .put("target_value", JSONObject.NULL).put("target_type", "buy_price")
                .put("call_date", "2026-10-02");
        JSONArray arr2 = new JSONArray().put(nullCall);
        SelfTest.checkEq("null target skipped from scoreable latest", 0, latestCalls(arr2).size());
        SelfTest.checkEq("null target kept in latest-any", 1, latestCallsAny(arr2).size());

        JSONObject old = new JSONObject().put("call_date", "2020-01-01");
        JSONObject recent = new JSONObject().put("call_date", today.format(DAY));
        SelfTest.checkEq("old call not new", false, isNewThisWeek(old));
        SelfTest.checkEq("today's call is new", true, isNewThisWeek(recent));

        SelfTest.checkEq("unparseable date sorts oldest",
                LocalDate.MIN, parseLenient("unknown"));
        SelfTest.checkEq("unparseable date never new",
                false, isNewThisWeek(new JSONObject().put("call_date", "unknown")));

        JSONObject e = sectionEntry("AAPL", new JSONObject()
                .put("ticker", "AAPL").put("company", "Apple")
                .put("target_value", 250.0).put("target_type", "price_target")
                .put("call_date", "2026-10-01").put("video_title", "v"));
        SelfTest.checkEq("section entry value", 250.0, e.getDouble("value"));
        SelfTest.checkEq("section entry type", "price_target", e.getString("value_type"));
        SelfTest.check("section entry new_this_week is boolean",
                e.get("new_this_week") instanceof Boolean);

        JSONObject ns = notScoreableEntry("WYNN", nullCall);
        SelfTest.checkEq("not-scoreable reason", "buy call with no stated price — unscorable",
                ns.getString("reason"));

        SelfTest.finish();
    }
}
