// CommentaryLog.java — per-company creator-commentary log.
//
// Even when a trusted creator states NO number, their transcript still gives
// the company's story. This log keeps that narrative: one dated entry per
// (video, ticker) with the creator's thesis, risks/opportunities, conviction,
// and change vs their prior take — all grounded in the transcript, with
// 1-2 exact quotes. Narrative context only — NEVER treated as valuations.
//
// Retention (Rahik's rule: lessons live forever, raw evidence rolls off):
//   - latest take per (creator, ticker): kept FOREVER
//   - conviction_change=true entries: kept FOREVER (compact change history)
//   - routine entries older than 60 days: roll off via --sweep
//   (wired into the weekly regression sweep, STEP 2b). Value/assumption
//   provenance stores are never touched by this tool.
//
// Usage:
//   java CommentaryLog.java --add --ticker ORCL --creator parkev \
//       --video-id s7G5dXn2VTY --video-title "..." --video-date 2026-09-26 \
//       --summary "..." --quote1 "..." [--quote2 "..."] \
//       --conviction high|medium|low [--change yes|no] [--company "Oracle"]
//   java CommentaryLog.java --prior --ticker ORCL --creator parkev
//   java CommentaryLog.java --list --ticker ORCL
//   java CommentaryLog.java --dry-run        (report what --sweep would prune)
//   java CommentaryLog.java --sweep          (prune for real; writes .bak first)
//   java CommentaryLog.java --self-test
//
// The --summary and --quote* text is written by the digest agent from the
// transcript (grounded, no invented details). This tool handles mechanics:
// dedupe, id sequencing, prior lookup, change detection, retention sweep.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class CommentaryLog {
    static final int RETENTION_DAYS = 60;
    static final ZoneId ZONE = ZoneId.of("America/New_York");

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static Path defaultStore() {
        return Paths.get(home(), "workspace", "creator_tracker", "creator_commentary.json");
    }

    // ---- store load/save ----

    static JSONObject load(Path store) throws IOException {
        if (!Files.exists(store) || Files.size(store) == 0) {
            JSONObject root = new JSONObject();
            root.put("_note", "Per-company creator commentary from trusted creators' videos. "
                + "Each entry: creator, video provenance, 2-4 sentence agent-written narrative "
                + "summary grounded in the transcript (thesis, risks/opportunities, conviction, "
                + "change vs prior take), 1-2 exact quotes. Dedupe: one entry per video_id+ticker; "
                + "revisits get new dated entries. Written by the daily YouTube digest (STEP 5e).");
            root.put("_trust", "Parkev > Jeremy > Couch Investor > other subscribed creators. "
                + "Commentary is narrative context — NEVER treated as a valuation.");
            root.put("_retention", "Latest take per (creator,ticker) kept forever; "
                + "conviction_change entries kept forever; routine entries older than "
                + RETENTION_DAYS + " days roll off via --sweep (weekly regression sweep STEP 2b). "
                + "Value/assumption provenance stores are never touched.");
            root.put("_next_id", 1);
            root.put("entries", new JSONArray());
            return root;
        }
        return new JSONObject(Files.readString(store, StandardCharsets.UTF_8));
    }

    static void save(Path store, JSONObject root, boolean backup) throws IOException {
        if (backup && Files.exists(store)) {
            Files.copy(store, store.resolveSibling(store.getFileName() + ".bak"),
                StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(store, root.toString(1) + "\n", StandardCharsets.UTF_8);
    }

    static JSONArray entriesOf(JSONObject root) {
        return root.optJSONArray("entries") != null ? root.getJSONArray("entries") : new JSONArray();
    }

    // ---- add ----

    static int addEntry(JSONObject root, Map<String, String> a) {
        JSONArray entries = entriesOf(root);
        String ticker = a.get("ticker");
        String videoId = a.get("video-id");

        // Dedupe: same video_id + ticker is never logged twice.
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (ticker.equals(e.optString("ticker"))
                    && videoId.equals(e.optString("video_id"))) {
                System.out.println("DUPLICATE — skipped: " + e.optString("id")
                    + " already covers " + ticker + " from video " + videoId);
                return -1;
            }
        }

        // Prior latest for this creator+ticker (for change detection).
        JSONObject prior = null;
        for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject e = entries.getJSONObject(i);
            if (ticker.equals(e.optString("ticker"))
                    && a.get("creator").equals(e.optString("creator"))) {
                prior = e;
                break;
            }
        }

        String conviction = a.getOrDefault("conviction", "").toLowerCase();
        boolean change;
        if (a.containsKey("change")) {
            change = a.get("change").equalsIgnoreCase("yes");
        } else {
            // Auto-derive: conviction level moved vs the prior take.
            change = prior != null
                && !conviction.isEmpty()
                && !conviction.equals(prior.optString("conviction", ""));
        }

        int n = root.optInt("_next_id", 1);
        JSONObject e = new JSONObject();
        e.put("id", String.format("cc-%03d", n));
        e.put("ticker", ticker);
        e.put("company", a.getOrDefault("company", ticker));
        e.put("creator", a.get("creator"));
        e.put("video_id", videoId);
        e.put("video_url", "https://www.youtube.com/watch?v=" + videoId);
        e.put("video_title", a.getOrDefault("video-title", ""));
        e.put("video_date", a.getOrDefault("video-date", ""));
        e.put("entry_date", LocalDate.now(ZONE).toString());
        e.put("summary", a.getOrDefault("summary", ""));
        JSONArray quotes = new JSONArray();
        if (a.containsKey("quote1")) quotes.put(a.get("quote1"));
        if (a.containsKey("quote2")) quotes.put(a.get("quote2"));
        e.put("quotes", quotes);
        e.put("conviction", conviction);
        e.put("conviction_change", change);
        e.put("verified", true);
        e.put("source", "daily-youtube-stock-video-digest");
        entries.put(e);
        root.put("entries", entries);
        root.put("_next_id", n + 1);

        System.out.println("LOGGED " + e.getString("id") + ": " + a.get("creator")
            + " on " + ticker + " (" + a.getOrDefault("video-date", "?") + ")"
            + (change ? " [CONVICTION CHANGE vs prior]" : " [baseline/no change]")
            + (prior == null ? " [first take]" : ""));
        return n;
    }

    // ---- prior / list ----

    static void showPrior(JSONObject root, String ticker, String creator) {
        JSONArray entries = entriesOf(root);
        List<JSONObject> found = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (ticker.equals(e.optString("ticker"))
                    && creator.equals(e.optString("creator"))) {
                found.add(e);
            }
        }
        if (found.isEmpty()) {
            System.out.println("(no prior commentary: " + creator + " on " + ticker
                + " — this will be the first take)");
            return;
        }
        for (JSONObject e : found) {
            printEntry(e);
        }
    }

    static void showList(JSONObject root, String ticker) {
        JSONArray entries = entriesOf(root);
        int n = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (ticker.equals(e.optString("ticker"))) {
                printEntry(e);
                n++;
            }
        }
        if (n == 0) System.out.println("(no commentary logged for " + ticker + ")");
    }

    static void printEntry(JSONObject e) {
        System.out.println("--- " + e.optString("id") + " | " + e.optString("creator")
            + " | video " + e.optString("video_date") + " | entry " + e.optString("entry_date")
            + " | conviction=" + e.optString("conviction")
            + (e.optBoolean("conviction_change") ? " [CHANGE]" : ""));
        System.out.println("    " + e.optString("video_title"));
        System.out.println("    " + e.optString("summary"));
        JSONArray q = e.optJSONArray("quotes");
        if (q != null) for (int i = 0; i < q.length(); i++)
            System.out.println("    \"" + q.getString(i) + "\"");
    }

    // ---- retention sweep ----

    static LocalDate entryDate(JSONObject e) {
        for (String f : new String[]{"entry_date", "video_date"}) {
            String d = e.optString(f, "");
            if (d.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(d);
        }
        return LocalDate.MIN; // undated: treated as old (prunable unless protected)
    }

    static int sweep(JSONObject root, LocalDate cutoff, boolean dry) {
        JSONArray entries = entriesOf(root);
        // Latest entry per (creator, ticker) — always protected.
        Map<String, Integer> latestIdx = new HashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            latestIdx.put(e.optString("creator") + "|" + e.optString("ticker"), i);
        }
        List<Integer> drop = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            String key = e.optString("creator") + "|" + e.optString("ticker");
            if (latestIdx.get(key) == i) continue;               // latest take: forever
            if (e.optBoolean("conviction_change")) continue;      // change history: forever
            if (!entryDate(e).isBefore(cutoff)) continue;         // recent: keep
            drop.add(i);                                          // routine + old: roll off
        }
        for (int i = drop.size() - 1; i >= 0; i--) {
            JSONObject e = entries.getJSONObject(drop.get(i));
            System.out.println((dry ? "would prune " : "pruned ")
                + e.optString("id") + " " + e.optString("creator") + "/"
                + e.optString("ticker") + " (" + e.optString("entry_date") + ")");
            if (!dry) entries.remove(drop.get(i).intValue());
        }
        root.put("entries", entries);
        return drop.size();
    }

    // ---- CLI ----

    // Fail-loud guard: --add must never silently ignore a flag it does not
    // understand (2026-09-28 — a stray "--dry-run" was swallowed and a bench
    // entry got written to the real log). Returns null when all keys are
    // known, else the refusal reason.
    static final Set<String> ADD_FLAGS = Set.of(
        "ticker", "creator", "video-id", "video-title", "video-date",
        "summary", "quote1", "quote2", "company", "conviction", "change",
        "store");

    static String checkAddFlags(Map<String, String> a) {
        for (String k : a.keySet()) {
            if (!ADD_FLAGS.contains(k)) return "unknown --add flag --" + k;
        }
        return null;
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        // Start at 1: args[0] is the mode (--add/--prior/...), never a flag.
        // A flag with no following value maps to "" so unknown/valueless
        // flags are visible to validation instead of silently dropped.
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String val = (i + 1 < args.length && !args[i + 1].startsWith("--"))
                    ? args[++i] : "";
                m.put(key, val);
            }
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--dry-run";
        if (mode.equals("--self-test")) { selfTest(); SelfTest.finish(); return; }
        Map<String, String> a = parseArgs(args);
        Path store = a.containsKey("store") ? Paths.get(a.get("store")) : defaultStore();

        switch (mode) {
            case "--add" -> {
                for (String req : new String[]{"ticker", "creator", "video-id", "summary"}) {
                    if (a.getOrDefault(req, "").isBlank()) {
                        System.err.println("missing required --" + req);
                        System.exit(2);
                    }
                }
                String badFlag = checkAddFlags(a);
                if (badFlag != null) {
                    System.err.println("CommentaryLog --add refused: " + badFlag);
                    System.exit(2);
                }
                JSONObject root = load(store);
                int n = addEntry(root, a);
                if (n > 0) save(store, root, false);
            }
            case "--prior" -> showPrior(load(store),
                a.getOrDefault("ticker", ""), a.getOrDefault("creator", ""));
            case "--list" -> showList(load(store), a.getOrDefault("ticker", ""));
            case "--dry-run", "--sweep" -> {
                boolean dry = mode.equals("--dry-run");
                LocalDate cutoff = LocalDate.now(ZONE).minusDays(RETENTION_DAYS);
                System.out.println("Commentary retention cutoff: " + cutoff
                    + " (" + RETENTION_DAYS + " days)" + (dry ? " — DRY RUN" : " — SWEEPING"));
                System.out.println("Protected: latest take per (creator,ticker); "
                    + "all conviction_change entries.");
                JSONObject root = load(store);
                int n = sweep(root, cutoff, dry);
                if (!dry && n > 0) save(store, root, true);
                System.out.println((dry ? "Would prune" : "Pruned") + ": " + n + " entries");
            }
            default -> {
                System.err.println("Usage: --add|--prior|--list|--dry-run|--sweep|--self-test");
                System.exit(2);
            }
        }
    }

    // ---- self test (shared helper) ----

    static void selfTest() throws Exception {
        // SelfTest.finish() prints "N passed, M failed" and exits 1 on failure.
        Path tmp = Files.createTempFile("commentary_test", ".json");
        {
            JSONObject root = load(tmp);

            // 0. unknown --add flags are refused (never silently ignored)
            Map<String, String> bad = new HashMap<>();
            bad.put("ticker", "ORCL"); bad.put("creator", "parkev");
            bad.put("video-id", "vidX"); bad.put("summary", "s");
            bad.put("dry-run", "");
            SelfTest.check("unknown --add flag refused",
                checkAddFlags(bad) != null
                    && checkAddFlags(bad).contains("--dry-run"));
            Map<String, String> good = new HashMap<>(bad);
            good.remove("dry-run");
            SelfTest.check("known --add flags pass",
                checkAddFlags(good) == null);

            // 1. add assigns cc-001
            Map<String, String> a1 = new HashMap<>();
            a1.put("ticker", "ORCL"); a1.put("creator", "parkev");
            a1.put("video-id", "vid1"); a1.put("summary", "Bullish on AI cloud.");
            a1.put("quote1", "exact quote one"); a1.put("conviction", "high");
            int n1 = addEntry(root, a1);
            SelfTest.checkEq("add assigns cc-001", 1, n1);
            SelfTest.checkEq("entry id", "cc-001",
                entriesOf(root).getJSONObject(0).getString("id"));

            // 2. dedupe: same video+ticker skipped
            int n2 = addEntry(root, a1);
            SelfTest.checkEq("duplicate returns -1", -1, n2);
            SelfTest.checkEq("no second entry written", 1, entriesOf(root).length());

            // 3. same video, different ticker is fine
            Map<String, String> a3 = new HashMap<>(a1);
            a3.put("ticker", "GOOG");
            int n3 = addEntry(root, a3);
            SelfTest.checkEq("different ticker logs cc-002", 2, n3);

            // 4. auto change detection: conviction moved high -> low
            Map<String, String> a4 = new HashMap<>(a1);
            a4.put("video-id", "vid2"); a4.put("conviction", "low");
            a4.put("summary", "Now cautious.");
            int n4 = addEntry(root, a4);
            SelfTest.checkEq("revisit logs cc-003", 3, n4);
            SelfTest.check("conviction move auto-flagged",
                entriesOf(root).getJSONObject(2).getBoolean("conviction_change"));

            // 5. no conviction move -> no change flag
            Map<String, String> a5 = new HashMap<>(a4);
            a5.put("video-id", "vid3"); a5.put("summary", "Still cautious.");
            addEntry(root, a5);
            SelfTest.check("same conviction not flagged",
                !entriesOf(root).getJSONObject(3).getBoolean("conviction_change"));

            // 6. explicit --change override
            Map<String, String> a6 = new HashMap<>(a5);
            a6.put("video-id", "vid4"); a6.put("change", "yes");
            addEntry(root, a6);
            SelfTest.check("explicit change=yes honored",
                entriesOf(root).getJSONObject(4).getBoolean("conviction_change"));

            // 7. sweep: protect latest + change entries, prune old routine
            //    cc-001 (old, baseline, not latest) -> prune
            //    cc-003 (old, change) -> keep; cc-005 (latest) -> keep
            JSONObject r2 = root;
            // age the entries: cc-001..cc-004 old, cc-005 recent
            for (int i = 0; i < 4; i++)
                entriesOf(r2).getJSONObject(i).put("entry_date", "2026-01-01");
            entriesOf(r2).getJSONObject(4).put("entry_date",
                LocalDate.now(ZONE).minusDays(10).toString());
            int pruned = sweep(r2, LocalDate.now(ZONE).minusDays(RETENTION_DAYS), true);
            SelfTest.checkEq("dry-run prunes 2 (cc-001, cc-002... check)", 2, pruned);
            // cc-001: old routine, not latest -> pruned
            // cc-002: GOOG entry, old, IS latest for parkev/GOOG -> protected
            // cc-003: old but conviction_change -> protected
            // cc-004: old routine, not latest (cc-005 is latest for parkev/ORCL) -> pruned
            // cc-005: recent -> protected
            Set<String> kept = new HashSet<>();
            JSONArray after = entriesOf(r2); // dry-run: nothing removed
            SelfTest.checkEq("dry-run removes nothing", 5, after.length());
            int real = sweep(r2, LocalDate.now(ZONE).minusDays(RETENTION_DAYS), false);
            SelfTest.checkEq("sweep prunes 2", 2, real);
            for (int i = 0; i < entriesOf(r2).length(); i++)
                kept.add(entriesOf(r2).getJSONObject(i).getString("id"));
            SelfTest.check("cc-001 pruned", !kept.contains("cc-001"));
            SelfTest.check("cc-004 pruned", !kept.contains("cc-004"));
            SelfTest.check("cc-002 kept (latest parkev/GOOG)", kept.contains("cc-002"));
            SelfTest.check("cc-003 kept (conviction change)", kept.contains("cc-003"));
            SelfTest.check("cc-005 kept (latest parkev/ORCL)", kept.contains("cc-005"));

            // 8. quote round-trip
            SelfTest.checkEq("quote1 stored",
                "exact quote one", entriesOf(r2).getJSONObject(0).getJSONArray("quotes").getString(0));
            Files.deleteIfExists(tmp);
        }
    }
}
