import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * UpgradePlaceholders — transcript-verified upgrades for creator backfill trackers.
 *
 * Parkev's channel-backfill first lands title-derived PLACEHOLDERS in
 * parkev_targets.json (status="title-derived", target_value=null) because no
 * transcript could be fetched. Once a transcript is fetched and a stated
 * valuation is verified against it (exact quote), this tool updates THAT
 * placeholder IN PLACE — never appends a duplicate entry.
 *
 * Usage:
 *   java UpgradePlaceholders.java --tracker parkev_targets.json --spec upgrades.json
 *   java UpgradePlaceholders.java --self-test
 *
 * upgrades.json is an array of:
 *   { "ticker": "JNJ", "videoId": "AY-no4_TAEw",
 *     "target_value": 377.43, "target_type": "intrinsic_value",
 *     "call_date": "unknown", "price_at_call": null,
 *     "source": "channel backfill 2026-09-30 (transcript verified)",
 *     "quote": "...exact transcript quote..." }
 *
 * Dedupe (non-negotiable): before writing, each spec is compared against the
 * ticker's existing real entries (status="open"). If target_value AND
 * target_type match an existing open call, the spec is SKIPPED — no duplicate.
 */
public class UpgradePlaceholders {

    static Path trackerPath(Path workDir, String tracker) {
        return workDir.resolve(tracker);
    }

    static JSONObject load(Path p) throws Exception {
        return new JSONObject(Files.readString(p));
    }

    static void save(Path p, JSONObject doc) throws Exception {
        // pretty-print with 2-space indent, matching the tracker's existing format
        Files.writeString(p, doc.toString(2) + "\n");
    }

    /** Find the title-derived placeholder for ticker + videoId, or null. */
    static JSONObject findPlaceholder(JSONArray entries, String ticker, String videoId) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (!ticker.equals(e.optString("ticker"))) continue;
            if (!"title-derived".equals(e.optString("status"))) continue;
            if (videoId != null && !e.optString("video_url").contains(videoId)) continue;
            return e;
        }
        return null;
    }

    /** Dedupe: does the ticker already have an OPEN call with the same value+type? */
    static boolean alreadyLogged(JSONArray entries, String ticker, double value, String type) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (!ticker.equals(e.optString("ticker"))) continue;
            if (!"open".equals(e.optString("status"))) continue;
            if (!type.equals(e.optString("target_type"))) continue;
            if (Double.compare(e.optDouble("target_value", Double.NaN), value) == 0) return true;
        }
        return false;
    }

    /** Apply one verified spec to the tracker doc. Returns a one-line report. */
    static String apply(JSONObject doc, JSONObject spec) {
        JSONArray entries = doc.getJSONArray("entries");
        String ticker = spec.getString("ticker");
        String videoId = spec.optString("videoId", null);
        double value = spec.getDouble("target_value");
        String type = spec.getString("target_type");

        if (alreadyLogged(entries, ticker, value, type)) {
            return "DEDUPE-SKIP " + ticker + " (" + type + " " + value + " already logged)";
        }
        JSONObject ph = findPlaceholder(entries, ticker, videoId);
        if (ph == null) {
            return "NO-PLACEHOLDER " + ticker + " (no title-derived entry" +
                    (videoId != null ? " for video " + videoId : "") + ")";
        }
        ph.put("target_value", value);
        ph.put("target_type", type);
        ph.put("call_date", spec.optString("call_date", "unknown"));
        if (spec.isNull("price_at_call")) ph.put("price_at_call", JSONObject.NULL);
        else ph.put("price_at_call", spec.getDouble("price_at_call"));
        ph.put("status", "open");
        ph.put("source", spec.getString("source"));
        ph.put("notes", spec.getString("source") +
                ". Exact quote: \"" + spec.getString("quote") + "\"");
        return "UPGRADED " + ticker + " (" + ph.optString("id") + "): " + type + " " + value;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) {
            selfTest();
            return;
        }
        String tracker = "parkev_targets.json";
        String specFile = null;
        for (int i = 0; i < args.length; i++) {
            if ("--tracker".equals(args[i])) tracker = args[++i];
            else if ("--spec".equals(args[i])) specFile = args[++i];
        }
        if (specFile == null) {
            System.err.println("usage: java UpgradePlaceholders.java --tracker <file> --spec <file>");
            System.exit(2);
        }
        Path workDir = Paths.get("").toAbsolutePath();
        Path tp = trackerPath(workDir, tracker);
        // safety: keep a backup of the tracker before mutating
        Files.copy(tp, tp.resolveSibling(tracker + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        JSONObject doc = load(tp);
        JSONArray specs = new JSONArray(Files.readString(Paths.get(specFile)));
        List<String> report = new ArrayList<>();
        for (int i = 0; i < specs.length(); i++) report.add(apply(doc, specs.getJSONObject(i)));
        save(tp, doc);
        for (String line : report) System.out.println(line);
    }

    // ------------------------------------------------------------------ self-test
    static void selfTest() throws Exception {
        Path tmp = Files.createTempDirectory("upg-test");
        JSONObject doc = new JSONObject();
        JSONArray entries = new JSONArray();

        JSONObject ph = new JSONObject();
        ph.put("id", "pt-999");
        ph.put("ticker", "TST");
        ph.put("status", "title-derived");
        ph.put("target_value", JSONObject.NULL);
        ph.put("target_type", JSONObject.NULL);
        ph.put("video_url", "https://www.youtube.com/watch?v=VID123");
        ph.put("notes", "placeholder");
        entries.put(ph);

        JSONObject real = new JSONObject();
        real.put("id", "pt-998");
        real.put("ticker", "TST");
        real.put("status", "open");
        real.put("target_value", 100.0);
        real.put("target_type", "intrinsic_value");
        entries.put(real);
        doc.put("entries", entries);

        // 1. duplicate of the existing open call -> skipped
        JSONObject dup = new JSONObject()
                .put("ticker", "TST").put("videoId", "VID123")
                .put("target_value", 100.0).put("target_type", "intrinsic_value")
                .put("call_date", "unknown").put("price_at_call", JSONObject.NULL)
                .put("source", "channel backfill 2026-09-30 (transcript verified)")
                .put("quote", "q");
        SelfTest.check("dedupe skips same value+type", apply(doc, dup).startsWith("DEDUPE-SKIP"));
        SelfTest.check("placeholder untouched after dedupe skip",
                "title-derived".equals(ph.optString("status")));

        // 2. genuine new value -> upgrades the placeholder in place
        JSONObject gen = new JSONObject()
                .put("ticker", "TST").put("videoId", "VID123")
                .put("target_value", 123.45).put("target_type", "intrinsic_value")
                .put("call_date", "unknown").put("price_at_call", JSONObject.NULL)
                .put("source", "channel backfill 2026-09-30 (transcript verified)")
                .put("quote", "intrinsic value per share of $123.45");
        SelfTest.check("new value upgrades", apply(doc, gen).startsWith("UPGRADED"));
        SelfTest.checkEq("value set", 123.45, ph.getDouble("target_value"));
        SelfTest.checkEq("status open", "open", ph.optString("status"));
        SelfTest.checkEq("source set",
                "channel backfill 2026-09-30 (transcript verified)", ph.optString("source"));
        SelfTest.checkContains("quote in notes", ph.optString("notes"), "$123.45");
        SelfTest.check("price_at_call null stays null", ph.isNull("price_at_call"));
        SelfTest.checkEq("no entry appended", 2, doc.getJSONArray("entries").length());

        // 3. unknown ticker -> no placeholder report
        JSONObject miss = new JSONObject()
                .put("ticker", "ZZZ").put("videoId", "VID999")
                .put("target_value", 1.0).put("target_type", "fair_value")
                .put("call_date", "unknown").put("price_at_call", JSONObject.NULL)
                .put("source", "s").put("quote", "q");
        SelfTest.check("missing placeholder reported", apply(doc, miss).startsWith("NO-PLACEHOLDER"));

        SelfTest.finish();
    }
}
