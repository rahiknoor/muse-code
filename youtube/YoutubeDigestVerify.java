// YoutubeDigestVerify.java — idea #44 health-check verifier for the
// daily-youtube-stock-video-digest cron.
//
// WHY THIS EXISTS: the digest run appends creator calls to four tracker
// logs and then rebuilds creator_values.json (STEP 5b). If a tracker file
// got corrupted, if an id sequence developed a gap (a lost call) or a
// duplicate (a double-counted call), or if STEP 5b never ran, the comparison
// chart silently drifts. This verifier fails LOUD instead of letting that
// drift pile up.
//
// RUN IT with single-file launch (no compile step needed), AFTER STEP 5b:
//   ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/lib/json/json-20240303.jar \
//       ~/workspace/youtube/YoutubeDigestVerify.java
// Exit 0 = all checks passed. Any other exit = a check FAILED (see output).
//
// CHECKS:
//   1. All tracker logs parse as valid JSON (four value trackers + the
//      creator commentary log).
//   2. Entry id sequences (pt-NNN / jl-NNN / ci-NNN / bf-NNN / cr-NNN / oc-NNN / cc-NNN) have
//      no gaps and no duplicates.
//   3. No-inference spot check: a recorded value always carries a source
//      attribution (video_url present); numeric fields are finite numbers.
//      Commentary entries always carry a summary, >=1 exact quote, and a
//      video_url — a narrative without its source is not logged.
//   4. creator_values.json is NEWER than all four tracker files — proving
//      STEP 5b (the backend rebuild) actually ran after the appends.
//
// NOTE on paths: never use System.getProperty("user.home") here — on this
// machine it returns /root while the workspace lives under $HOME=/home/hatch.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class YoutubeDigestVerify {

    // The one failure bucket every check reports into.
    private static final List<String> FAILURES = new ArrayList<>();

    private static Path ws() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            fail("HOME env var is not set — cannot resolve workspace paths");
            return null;
        }
        return Path.of(home, "workspace");
    }

    private static void fail(String msg) {
        FAILURES.add(msg);
    }

    // Read a file and parse it as JSON (object or array at the top level).
    // Returns null and records a failure if anything is wrong.
    private static Object readJson(Path p) {
        if (!Files.isRegularFile(p)) {
            fail("missing file: " + p);
            return null;
        }
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                fail("empty file: " + p);
                return null;
            }
            String t = text.stripLeading();
            return t.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
        } catch (IOException e) {
            fail("cannot read " + p + ": " + e.getMessage());
            return null;
        } catch (JSONException e) {
            fail("invalid JSON in " + p + ": " + e.getMessage());
            return null;
        }
    }

    // Normalize the tracker file's entries: it may be an object with an
    // "entries" array, or a bare array (empty at start of tracking).
    private static JSONArray entriesOf(Object json, Path p) {
        if (json instanceof JSONArray) {
            return (JSONArray) json; // e.g. jeremy_targets.json starts as []
        }
        JSONArray entries = ((JSONObject) json).optJSONArray("entries");
        if (entries == null) {
            fail(p.getFileName() + ": no 'entries' array found");
            return new JSONArray();
        }
        return entries;
    }

    // Check one tracker log: ids like pt-001, pt-002 ... with no gaps and
    // no duplicates; every stated value keeps its source attribution.
    private static void checkTracker(Path p, String prefix) {
        Object json = readJson(p);
        if (json == null) {
            return;
        }
        JSONArray entries = entriesOf(json, p);
        Set<String> seen = new HashSet<>();
        int max = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e;
            try {
                e = entries.getJSONObject(i);
            } catch (JSONException ex) {
                fail(p.getFileName() + ": entry #" + i + " is not an object");
                continue;
            }
            String id = e.optString("id", "");
            // Id shape: <prefix>-NNN, e.g. pt-004. A malformed id is a
            // hand-edit smell — fail loud.
            if (!id.matches(prefix + "-\\d{3}")) {
                fail(p.getFileName() + ": entry #" + i
                        + " has malformed id '" + id + "'");
                continue;
            }
            if (!seen.add(id)) {
                fail(p.getFileName() + ": duplicate id '" + id + "'");
            }
            max = Math.max(max, Integer.parseInt(id.substring(id.indexOf('-') + 1)));

            // No-inference spot check: a logged VALUE is only trustworthy if
            // it keeps its source. target_value non-null with no video_url
            // (even "not recorded") means the provenance chain is broken.
            if (!e.isNull("target_value") && e.optString("video_url", "").isBlank()) {
                fail(p.getFileName() + ": id '" + id
                        + "' has a target_value but no video_url (source lost)");
            }
            // A NaN / Infinity numeric field means something computed garbage.
            for (String field : new String[]{"target_value", "price_at_call"}) {
                if (!e.isNull(field)) {
                    double v = e.optDouble(field, Double.NaN);
                    if (Double.isNaN(v) || Double.isInfinite(v)) {
                        fail(p.getFileName() + ": id '" + id
                                + "' has a non-finite " + field);
                    }
                }
            }
        }
        // Gap check: ids 001..max must ALL be present. A gap means a call
        // was dropped (or the sequence counter jumped) — fail loud.
        for (int n = 1; n <= max; n++) {
            if (!seen.contains(String.format("%s-%03d", prefix, n))) {
                fail(p.getFileName() + ": gap in id sequence, missing '"
                        + String.format("%s-%03d", prefix, n) + "'");
            }
        }
        System.out.println("OK  " + p.getFileName() + " — "
                + entries.length() + " entries, ids " + prefix + "-001.."
                + String.format("%03d", max) + ", no gaps/duplicates"
                + (entries.length() == 0 ? " (empty log)" : ""));
    }

    // Commentary provenance: a narrative entry is only trustworthy if it
    // keeps its source. Every entry must carry a non-blank summary, at
    // least one exact quote, a video_url, and a conviction level.
    private static void checkCommentaryProvenance(Path p) {
        Object json = readJson(p);
        if (json == null) {
            return;
        }
        JSONArray entries = entriesOf(json, p);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e;
            try {
                e = entries.getJSONObject(i);
            } catch (JSONException ex) {
                fail(p.getFileName() + ": entry #" + i + " is not an object");
                continue;
            }
            String id = e.optString("id", "entry #" + i);
            if (e.optString("summary", "").isBlank()) {
                fail(p.getFileName() + ": id '" + id + "' has a blank summary");
            }
            JSONArray q = e.optJSONArray("quotes");
            if (q == null || q.length() == 0) {
                fail(p.getFileName() + ": id '" + id + "' has no exact quotes");
            }
            if (e.optString("video_url", "").isBlank()) {
                fail(p.getFileName() + ": id '" + id + "' has no video_url (source lost)");
            }
            String conv = e.optString("conviction", "");
            if (!conv.equals("high") && !conv.equals("medium") && !conv.equals("low")) {
                fail(p.getFileName() + ": id '" + id
                        + "' has invalid conviction '" + conv + "'");
            }
        }
        System.out.println("OK  " + p.getFileName() + " — " + entries.length()
                + " entries, all carry summary + quotes + video_url + conviction");
    }

    // Assumption store check: the --append writer owns this file's id/dedupe
    // logic, but a hand-edit or a crashed write could still corrupt it.
    // ca-NNN ids have no gaps/dups; every stored assumption value keeps its
    // exact quote (a number without its source is not logged).
    private static final String[] ASSUMPTION_FIELDS = {
        "growth_rate", "discount_wacc", "terminal_growth",
        "fcf_projection", "margin"
    };

    private static void checkAssumptions(Path p) {
        Object json = readJson(p);
        if (json == null) {
            return;
        }
        if (!(json instanceof JSONObject)) {
            fail(p.getFileName() + ": top level is not an object");
            return;
        }
        JSONArray entries = ((JSONObject) json).optJSONArray("assumptions");
        if (entries == null) {
            fail(p.getFileName() + ": no 'assumptions' array found");
            return;
        }
        Set<String> seen = new HashSet<>();
        int max = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e;
            try {
                e = entries.getJSONObject(i);
            } catch (JSONException ex) {
                fail(p.getFileName() + ": entry #" + i + " is not an object");
                continue;
            }
            String id = e.optString("id", "");
            if (!id.matches("ca-\\d{3}")) {
                fail(p.getFileName() + ": entry #" + i
                        + " has malformed id '" + id + "'");
                continue;
            }
            if (!seen.add(id)) {
                fail(p.getFileName() + ": duplicate id '" + id + "'");
            }
            max = Math.max(max, Integer.parseInt(id.substring(3)));
            for (String req : new String[]{"ticker", "creator", "video_id", "date"}) {
                if (e.optString(req, "").isBlank()) {
                    fail(p.getFileName() + ": id '" + id + "' missing " + req);
                }
            }
            for (String field : ASSUMPTION_FIELDS) {
                if (!e.has(field) || e.isNull(field)) continue;
                double v = e.optDouble(field, Double.NaN);
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    fail(p.getFileName() + ": id '" + id
                            + "' has a non-finite " + field);
                }
                if (e.optString(field + "_quote", "").isBlank()) {
                    fail(p.getFileName() + ": id '" + id + "' stores "
                            + field + " with no exact quote (source lost)");
                }
            }
        }
        for (int n = 1; n <= max; n++) {
            if (!seen.contains(String.format("ca-%03d", n))) {
                fail(p.getFileName() + ": gap in id sequence, missing '"
                        + String.format("ca-%03d", n) + "'");
            }
        }
        System.out.println("OK  " + p.getFileName() + " — "
                + entries.length() + " entries, ids ca-001.."
                + String.format("%03d", max) + ", no gaps/duplicates"
                + (entries.length() == 0 ? " (empty log)" : "")
                + ", every value keeps its quote");
    }

    // STEP 5b proof: creator_values.json must be newer than every tracker it
    // was rebuilt from. If it is older, the rebuild never ran and the chart
    // backend is stale.
    private static void checkBackendFreshness(Path ws) {
        Path backend = ws.resolve("dcf_csv/creator_values.json");
        String[] trackers = {
            "creator_tracker/parkev_targets.json",
            "creator_tracker/jeremy_targets.json",
            "creator_tracker/couch_investor_targets.json",
            "creator_tracker/bottom_finders_targets.json",
            "creator_tracker/other_creators_targets.json",
        };
        FileTime backendMtime;
        try {
            backendMtime = Files.getLastModifiedTime(backend);
        } catch (IOException e) {
            fail("cannot stat dcf_csv/creator_values.json: " + e.getMessage());
            return;
        }
        for (String t : trackers) {
            Path tp = ws.resolve(t);
            try {
                FileTime tm = Files.getLastModifiedTime(tp);
                if (tm.compareTo(backendMtime) > 0) {
                    fail("dcf_csv/creator_values.json (" + backendMtime.toInstant()
                            + ") is OLDER than " + t + " (" + tm.toInstant()
                            + ") — STEP 5b rebuild did not run after the append");
                }
            } catch (IOException e) {
                fail("cannot stat " + t + ": " + e.getMessage());
            }
        }
        System.out.println("OK  dcf_csv/creator_values.json newer than all five "
                + "tracker files (STEP 5b ran)");
    }

    public static void main(String[] args) {
        Path ws = ws();
        if (ws == null) {
            printReport();
            return;
        }
        checkTracker(ws.resolve("creator_tracker/parkev_targets.json"), "pt");
        checkTracker(ws.resolve("creator_tracker/jeremy_targets.json"), "jl");
        checkTracker(ws.resolve("creator_tracker/couch_investor_targets.json"), "ci");
        checkTracker(ws.resolve("creator_tracker/bottom_finders_targets.json"), "bf");
        checkTracker(ws.resolve("creator_tracker/creator_research.json"), "cr");
        checkTracker(ws.resolve("creator_tracker/other_creators_targets.json"), "oc");
        checkTracker(ws.resolve("creator_tracker/creator_commentary.json"), "cc");
        checkCommentaryProvenance(ws.resolve("creator_tracker/creator_commentary.json"));
        checkAssumptions(ws.resolve("creator_tracker/creator_assumptions.json"));
        checkBackendFreshness(ws);
        printReport();
    }

    private static void printReport() {
        if (FAILURES.isEmpty()) {
            System.out.println("VERIFY PASS — daily-youtube-stock-video-digest "
                    + "tracker state is healthy");
        } else {
            System.err.println("VERIFY FAIL — " + FAILURES.size()
                    + " check(s) failed:");
            for (String f : FAILURES) {
                System.err.println("  FAIL: " + f);
            }
            System.exit(1);
        }
    }
}
