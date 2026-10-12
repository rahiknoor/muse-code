// DigestBatches.java
//
// IDEA 5 (SPLIT THE MONOLITHS, approved 2026-09-28): parallel morning
// YouTube digest. The old digest ran one giant sequential pass (browser
// listing + per-video fetch + extraction); on 2026-09-27 it timed out and
// had to be hand-delivered. This tool partitions the video workload into
// 4 independent batches the scheduled run worker executes in parallel:
//
//   Batch A   - the 3 followed creators (Parkev Tatevosian, Jeremy Lefebvre
//               incl. the Clips tip-off channel, Couch Investor)
//   Batch B/C/D - remaining subscribed channels, split evenly (round-robin)
//
// Each batch runs as an independent background shell job:
//   java DigestBatches.java --fetch-batch <batchdir> <batchId>
// writing its per-batch status file. The run worker then extracts each
// batch's transcripts (cached, local reads) into batch_A.json..batch_D.json
// (same record shape as the old STEP 3 extraction record), and:
//   java DigestBatches.java --merge <batchdir>
// validates every batch is present and complete, and merges them in
// creator-priority order into the single digest extraction record.
// The digest watermark advances ONCE after a successful merge -- never per
// batch -- so a failed batch does not lose the window:
//   java DigestBatches.java --advance-watermark <batchdir>
//
// Modes:
//   --plan <videos.json> <batchdir>        partition the video list
//   --fetch-batch <batchdir> <batchId>     fetch one batch's transcripts
//   --merge <batchdir>                     validate + merge partials
//   --advance-watermark <batchdir>         move the digest watermark once
//   --self-test                            regression tests (shared SelfTest)
//
// videos.json shape: {"date":"YYYY-MM-DD","videos":[
//   {"videoId":"...","channel":"...","title":"...","uploadTime":"...","url":"..."}]}
//
// Per-batch partial shape (written by the run worker's extraction pass):
//   {"date":"YYYY-MM-DD","videos":[
//     {"video_id":"...","creator":"parkev","title":"...","upload_date":"...",
//      "buy_calls":[...],"targets":[...],"assumptions":[...],
//      "commentary":[...],"skipped":[...]}]}
//
// Exit codes: 0 = ok, 1 = failed (merge/advance refusal), 2 = usage error.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public class DigestBatches {

    static final String HOME = homeDir();
    static final String JAVA_BIN = HOME + "/workspace/java/jdk-21/bin/java";
    static final String JSON_JAR = HOME + "/workspace/lib/json/json-20240303.jar";
    static final String CACHE_TOOL = HOME + "/workspace/youtube/cache/YoutubeCache.java";
    static final String FETCH_TOOL = HOME + "/workspace/youtube/TranscriptFetch.java";
    static final String JOB_ID = "daily-youtube-stock-video-digest";
    static final ZoneId ET = ZoneId.of("America/New_York");

    /** Batch ids, in order. */
    static final String[] BATCH_IDS = {"A", "B", "C", "D"};

    /** Creator classes, in merge priority order. */
    static final String PARKEV = "parkev";
    static final String JEREMY = "jeremy";
    static final String COUCH = "couch";
    static final String OTHER = "other";

    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h == null || h.isEmpty()) ? "/home/hatch" : h;
    }

    // ------------------------------------------------------------------
    // Channel classification
    // ------------------------------------------------------------------

    /** Classifies a channel name into parkev|jeremy|couch|other. */
    static String classify(String channel) {
        String c = channel == null ? "" : channel.toLowerCase();
        if (c.contains("parkev")) return PARKEV;
        if (c.contains("jeremy") || c.contains("financial education")) return JEREMY;
        if (c.contains("couch")) return COUCH;
        return OTHER;
    }

    /** Merge priority rank: lower = earlier in the digest. */
    static int classRank(String cls) {
        switch (cls) {
            case PARKEV: return 0;
            case JEREMY: return 1;
            case COUCH:  return 2;
            default:     return 3;
        }
    }

    // ------------------------------------------------------------------
    // --plan
    // ------------------------------------------------------------------

    static int runPlan(Path videosJson, Path batchDir) {
        try {
            Files.createDirectories(batchDir);
            JSONObject in = new JSONObject(Files.readString(videosJson, StandardCharsets.UTF_8));
            JSONArray arr = in.optJSONArray("videos");
            if (arr == null) arr = new JSONArray();

            List<JSONObject> priority = new ArrayList<>();
            List<JSONObject> others = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject v = arr.getJSONObject(i);
                if (OTHER.equals(classify(v.optString("channel", "")))) {
                    others.add(v);
                } else {
                    priority.add(v);
                }
            }

            Map<String, List<JSONObject>> batches = new LinkedHashMap<>();
            for (String id : BATCH_IDS) batches.put(id, new ArrayList<>());
            batches.get("A").addAll(priority);
            // Round-robin the rest across B/C/D for even load.
            String[] rest = {"B", "C", "D"};
            for (int i = 0; i < others.size(); i++) {
                batches.get(rest[i % rest.length]).add(others.get(i));
            }

            // Window: frozen from the watermark so a retry re-covers the same window.
            Path watermark = batchDir.toAbsolutePath().getParent()
                    .resolve("digest_watermark.json");
            String windowStart;
            if (Files.exists(watermark)) {
                windowStart = new JSONObject(
                        Files.readString(watermark, StandardCharsets.UTF_8))
                        .optString("window_end", null);
            } else {
                windowStart = null;
            }
            String windowEnd = Instant.now().toString();
            if (windowStart == null) {
                windowStart = Instant.now().minusSeconds(24 * 3600).toString();
            }
            String date = LocalDate.now(ET).toString();

            JSONObject plan = new JSONObject();
            plan.put("date", date);
            plan.put("window_start", windowStart);
            plan.put("window_end", windowEnd);
            JSONArray bj = new JSONArray();
            for (String id : BATCH_IDS) {
                JSONObject b = new JSONObject();
                b.put("id", id);
                JSONArray vids = new JSONArray();
                for (JSONObject v : batches.get(id)) vids.put(v);
                b.put("videos", vids);
                bj.put(b);
            }
            plan.put("batches", bj);
            Files.writeString(batchDir.resolve("batches.json"), plan.toString(2),
                    StandardCharsets.UTF_8);

            // Pending watermark: advanced exactly once by --advance-watermark.
            JSONObject pending = new JSONObject();
            pending.put("date", date);
            pending.put("window_end", windowEnd);
            pending.put("advanced", false);
            Files.writeString(batchDir.resolve("pending_watermark.json"), pending.toString(2),
                    StandardCharsets.UTF_8);

            int total = priority.size() + others.size();
            System.out.println("PLAN_OK batches=4 total_videos=" + total
                    + " A=" + batches.get("A").size()
                    + " B=" + batches.get("B").size()
                    + " C=" + batches.get("C").size()
                    + " D=" + batches.get("D").size()
                    + " window_start=" + windowStart);
            return 0;
        } catch (Exception e) {
            System.out.println("PLAN_FAILED=" + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------
    // --fetch-batch  (one independent background shell job per batch)
    // ------------------------------------------------------------------

    /** Result of a batch fetch run. */
    static final class FetchResult {
        int cached;
        int fetched;
        int handRead;
        boolean ok;
        String error;
    }

    static FetchResult runFetchBatch(Path batchDir, String batchId) {
        FetchResult r = new FetchResult();
        try {
            Path planPath = batchDir.resolve("batches.json");
            JSONObject plan = new JSONObject(
                    Files.readString(planPath, StandardCharsets.UTF_8));
            JSONArray batches = plan.getJSONArray("batches");
            JSONArray vids = null;
            for (int i = 0; i < batches.length(); i++) {
                JSONObject b = batches.getJSONObject(i);
                if (batchId.equals(b.getString("id"))) vids = b.getJSONArray("videos");
            }
            if (vids == null) {
                r.error = "unknown batch id: " + batchId;
                return r;
            }

            JSONArray status = new JSONArray();
            for (int i = 0; i < vids.length(); i++) {
                JSONObject v = vids.getJSONObject(i);
                String id = v.getString("videoId");
                String st;
                if (cacheGet(id) == 0) {
                    st = "cached";
                    r.cached++;
                } else if (transcriptFetch(id) == 0) {
                    st = "fetched";
                    r.fetched++;
                } else {
                    // Exit 2: live-stream / Shorts edge case -- the run worker
                    // hand-reads these in one pass and puts them in the cache.
                    st = "hand_read_needed";
                    r.handRead++;
                }
                JSONObject s = new JSONObject();
                s.put("videoId", id);
                s.put("channel", v.optString("channel", ""));
                s.put("status", st);
                status.put(s);
            }

            JSONObject out = new JSONObject();
            out.put("batch", batchId);
            out.put("date", plan.optString("date", ""));
            out.put("videos", status);
            Files.writeString(batchDir.resolve("batch_" + batchId + "_status.json"),
                    out.toString(2), StandardCharsets.UTF_8);

            System.out.println("FETCH_BATCH=" + batchId
                    + " cached=" + r.cached
                    + " fetched=" + r.fetched
                    + " hand_read_needed=" + r.handRead);
            r.ok = true;
            return r;
        } catch (Exception e) {
            r.error = e.getMessage();
            return r;
        }
    }

    /** YoutubeCache get: exit 0 = HIT. */
    static int cacheGet(String videoId) throws IOException, InterruptedException {
        return new ProcessBuilder(JAVA_BIN,
                "--class-path", JSON_JAR, CACHE_TOOL, "get", videoId,
                "--job", JOB_ID)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor();
    }

    /** TranscriptFetch: exit 0 = ok, 2 = failed (needs hand read). */
    static int transcriptFetch(String videoId) throws IOException, InterruptedException {
        return new ProcessBuilder(JAVA_BIN,
                "--class-path", JSON_JAR, FETCH_TOOL, "fetch", videoId,
                "--job", JOB_ID)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor();
    }

    // ------------------------------------------------------------------
    // --merge  (fail-loud: any missing/duplicate/drop blocks the watermark)
    // ------------------------------------------------------------------

    /** Result of a merge run. */
    static final class MergeResult {
        boolean ok;
        String reason;          // set when !ok
        int videos;             // merged video count when ok
        List<String> order;     // video ids in merged order (debug/test)
    }

    static MergeResult runMerge(Path batchDir) {
        MergeResult r = new MergeResult();
        try {
            JSONObject plan = new JSONObject(Files.readString(
                    batchDir.resolve("batches.json"), StandardCharsets.UTF_8));
            JSONArray batches = plan.getJSONArray("batches");

            Set<String> expected = new LinkedHashSet<>();
            Map<String, Integer> planIndex = new LinkedHashMap<>();
            int idx = 0;
            for (int i = 0; i < batches.length(); i++) {
                JSONObject b = batches.getJSONObject(i);
                JSONArray vids = b.getJSONArray("videos");
                for (int j = 0; j < vids.length(); j++) {
                    String id = vids.getJSONObject(j).getString("videoId");
                    if (!expected.add(id)) {
                        return fail(r, "duplicate videoId in plan: " + id);
                    }
                    planIndex.put(id, idx++);
                }
            }

            Map<String, JSONObject> merged = new LinkedHashMap<>();
            Map<String, String> seenIn = new LinkedHashMap<>();
            for (int i = 0; i < batches.length(); i++) {
                String bid = batches.getJSONObject(i).getString("id");
                Path partial = batchDir.resolve("batch_" + bid + ".json");
                if (!Files.exists(partial)) {
                    return fail(r, "missing partial for batch " + bid
                            + " (" + partial.getFileName() + ")");
                }
                JSONObject p;
                try {
                    p = new JSONObject(Files.readString(partial, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    return fail(r, "batch " + bid + " partial is not valid JSON");
                }
                JSONArray vids = p.optJSONArray("videos");
                if (vids == null) {
                    return fail(r, "batch " + bid + " partial has no \"videos\" array");
                }
                for (int j = 0; j < vids.length(); j++) {
                    JSONObject v = vids.getJSONObject(j);
                    String id = v.optString("video_id", v.optString("videoId", ""));
                    if (id.isEmpty()) {
                        return fail(r, "batch " + bid + " partial has a video without an id");
                    }
                    if (merged.containsKey(id)) {
                        return fail(r, "video " + id + " appears in both batch "
                                + seenIn.get(id) + " and batch " + bid);
                    }
                    merged.put(id, v);
                    seenIn.put(id, bid);
                }
            }

            Set<String> actual = merged.keySet();
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            if (!missing.isEmpty()) {
                return fail(r, "dropped videos not covered by any batch: " + missing);
            }
            Set<String> extra = new LinkedHashSet<>(actual);
            extra.removeAll(expected);
            if (!extra.isEmpty()) {
                return fail(r, "videos not in the plan: " + extra);
            }

            // Creator-priority order: parkev, jeremy, couch, others; plan order within.
            List<Map.Entry<String, JSONObject>> entries =
                    new ArrayList<>(merged.entrySet());
            entries.sort((a, b) -> {
                int ra = classRank(classify(creatorOf(a.getValue())));
                int rb = classRank(classify(creatorOf(b.getValue())));
                if (ra != rb) return Integer.compare(ra, rb);
                return Integer.compare(planIndex.get(a.getKey()), planIndex.get(b.getKey()));
            });

            JSONArray outVids = new JSONArray();
            r.order = new ArrayList<>();
            for (Map.Entry<String, JSONObject> e : entries) {
                outVids.put(e.getValue());
                r.order.add(e.getKey());
            }
            JSONObject out = new JSONObject();
            String date = plan.optString("date", LocalDate.now(ET).toString());
            out.put("date", date);
            out.put("videos", outVids);
            String json = out.toString(2);
            Files.writeString(batchDir.resolve("merged_extract.json"), json,
                    StandardCharsets.UTF_8);
            // The digest steps consume the merged record from the run's /tmp path,
            // exactly where the old sequential STEP 3 wrote it.
            Files.writeString(Paths.get("/tmp/digest_extract_" + date + ".json"), json,
                    StandardCharsets.UTF_8);

            r.ok = true;
            r.videos = outVids.length();
            System.out.println("MERGE_OK videos=" + r.videos);
            return r;
        } catch (Exception e) {
            return fail(r, "internal error: " + e.getMessage());
        }
    }

    private static MergeResult fail(MergeResult r, String reason) {
        r.ok = false;
        r.reason = reason;
        System.out.println("MERGE_FAILED=" + reason);
        return r;
    }

    /** Creator label from a partial video record (accepts both spellings). */
    static String creatorOf(JSONObject v) {
        String c = v.optString("creator", "");
        if (!c.isEmpty()) return c;
        // Fall back to the channel name when the extractor used it.
        return v.optString("channel", "");
    }

    // ------------------------------------------------------------------
    // --advance-watermark  (exactly once, only after a successful merge)
    // ------------------------------------------------------------------

    static int runAdvanceWatermark(Path batchDir) {
        try {
            Path abs = batchDir.toAbsolutePath();
            Path merged = abs.resolve("merged_extract.json");
            if (!Files.exists(merged)) {
                System.out.println("WATERMARK_REFUSED=no successful merge for this batch dir");
                return 1;
            }
            Path pending = abs.resolve("pending_watermark.json");
            if (!Files.exists(pending)) {
                System.out.println("WATERMARK_REFUSED=no pending watermark (already advanced?)");
                return 1;
            }
            JSONObject p = new JSONObject(
                    Files.readString(pending, StandardCharsets.UTF_8));
            Path watermark = abs.getParent().resolve("digest_watermark.json");
            JSONObject w = new JSONObject();
            w.put("date", p.getString("date"));
            w.put("window_end", p.getString("window_end"));
            Path tmp = watermark.resolveSibling("digest_watermark.json.tmp");
            Files.writeString(tmp, w.toString(2), StandardCharsets.UTF_8);
            Files.move(tmp, watermark, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            Files.delete(pending);
            System.out.println("WATERMARK_ADVANCED window_end=" + p.getString("window_end"));
            return 0;
        } catch (Exception e) {
            System.out.println("WATERMARK_FAILED=" + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------
    // --self-test
    // ------------------------------------------------------------------

    static void runSelfTest() throws Exception {
        // Fixture: 12 videos -- 4 from the followed creators, 8 others.
        String[][] fixture = {
            {"v-p1", "Parkev Tatevosian, CFA"},
            {"v-p2", "Parkev Tatevosian, CFA"},
            {"v-j1", "Financial Education"},
            {"v-j2", "Jeremy Lefebvre Clips"},
            {"v-c1", "Couch Investor"},
            {"v-o1", "Ricky Gutierrez"},
            {"v-o2", "The Patient Investor"},
            {"v-o3", "Joseph Hogue"},
            {"v-o4", "Victor H Investing"},
            {"v-o5", "Deep Value Hunter"},
            {"v-o6", "Steven Bavaria"},
            {"v-o7", "Chris Sain"},
            {"v-o8", "Nathan Robert"},
        };
        JSONObject in = new JSONObject();
        in.put("date", "2026-09-28");
        JSONArray vids = new JSONArray();
        for (String[] f : fixture) {
            JSONObject v = new JSONObject();
            v.put("videoId", f[0]);
            v.put("channel", f[1]);
            v.put("title", "t-" + f[0]);
            v.put("uploadTime", "2026-09-28T06:00:00");
            v.put("url", "https://www.youtube.com/watch?v=" + f[0]);
            vids.put(v);
        }
        in.put("videos", vids);

        Path tmp = Files.createTempDirectory("digest_batches_test");
        Path videosJson = tmp.resolve("videos.json");
        Files.writeString(videosJson, in.toString(2), StandardCharsets.UTF_8);
        Path batchDir = tmp.resolve("digest_2026-09-28");
        Files.createDirectories(batchDir);

        // --- plan partitions with no overlap and no drops ---
        SelfTest.checkEq("plan exits 0", 0, runPlan(videosJson, batchDir));
        JSONObject plan = new JSONObject(Files.readString(
                batchDir.resolve("batches.json"), StandardCharsets.UTF_8));
        Map<String, List<String>> byBatch = new LinkedHashMap<>();
        for (String id : BATCH_IDS) byBatch.put(id, new ArrayList<>());
        JSONArray batches = plan.getJSONArray("batches");
        SelfTest.checkEq("plan has 4 batches", 4, batches.length());
        for (int i = 0; i < batches.length(); i++) {
            JSONObject b = batches.getJSONObject(i);
            JSONArray vs = b.getJSONArray("videos");
            for (int j = 0; j < vs.length(); j++) {
                byBatch.get(b.getString("id")).add(vs.getJSONObject(j).getString("videoId"));
            }
        }
        Set<String> union = new LinkedHashSet<>();
        int counted = 0;
        for (List<String> l : byBatch.values()) { union.addAll(l); counted += l.size(); }
        SelfTest.checkEq("no drops: union covers all 13 fixture videos", 13, union.size());
        SelfTest.checkEq("no overlap: counted == union size", union.size(), counted);

        SelfTest.checkEq("batch A holds exactly the 5 priority videos", 5, byBatch.get("A").size());
        SelfTest.check("batch A contains parkev v-p1", byBatch.get("A").contains("v-p1"));
        SelfTest.check("batch A contains jeremy-clips v-j2", byBatch.get("A").contains("v-j2"));
        SelfTest.check("batch A contains couch v-c1", byBatch.get("A").contains("v-c1"));
        int rest = byBatch.get("B").size() + byBatch.get("C").size() + byBatch.get("D").size();
        SelfTest.checkEq("B/C/D hold the remaining 8 videos", 8, rest);
        // Round-robin is even: 8 others over 3 batches = 3/3/2.
        SelfTest.checkEq("batch B size", 3, byBatch.get("B").size());
        SelfTest.checkEq("batch C size", 3, byBatch.get("C").size());
        SelfTest.checkEq("batch D size", 2, byBatch.get("D").size());
        for (String bid : new String[]{"B", "C", "D"}) {
            for (String vid : byBatch.get(bid)) {
                SelfTest.check("batch " + bid + " video " + vid + " is non-priority",
                        OTHER.equals(classify(channelOf(fixture, vid))));
            }
        }

        // --- classification edge cases ---
        SelfTest.checkEq("clips channel classifies as jeremy", JEREMY,
                classify("Jeremy Lefebvre Clips"));
        SelfTest.checkEq("case-insensitive parkev", PARKEV, classify("PARKEV TATEVOSIAN, CFA"));

        // --- merge combines partials in creator-priority order ---
        // Write partials in scrambled batch order (D, B, A, C) to prove
        // the merge does its own ordering.
        String[] creatorFor = new String[fixture.length];
        for (int i = 0; i < fixture.length; i++) creatorFor[i] = classify(fixture[i][1]);
        for (String bid : new String[]{"D", "B", "A", "C"}) {
            JSONObject p = new JSONObject();
            p.put("date", "2026-09-28");
            JSONArray pv = new JSONArray();
            for (String vid : byBatch.get(bid)) {
                JSONObject v = new JSONObject();
                v.put("video_id", vid);
                v.put("creator", creatorFor[indexOf(fixture, vid)]);
                v.put("title", "t-" + vid);
                pv.put(v);
            }
            p.put("videos", pv);
            Files.writeString(batchDir.resolve("batch_" + bid + ".json"), p.toString(2),
                    StandardCharsets.UTF_8);
        }
        MergeResult m = runMerge(batchDir);
        SelfTest.check("merge succeeds", m.ok);
        SelfTest.checkEq("merge covers all 13 videos", 13, m.videos);
        List<String> order = m.order;
        // Expected: parkev, parkev, jeremy, jeremy, couch, then the 8 others in plan order.
        String[] expectedOrder = {"v-p1", "v-p2", "v-j1", "v-j2", "v-c1",
            "v-o1", "v-o4", "v-o7", "v-o2", "v-o5", "v-o8", "v-o3", "v-o6"};        SelfTest.checkEq("merge order = creator priority, then plan order",
                String.join(",", expectedOrder), String.join(",", order));
        SelfTest.check("merged_extract.json written",
                Files.exists(batchDir.resolve("merged_extract.json")));
        SelfTest.check("run /tmp extract written",
                Files.exists(Paths.get("/tmp/digest_extract_2026-09-28.json")));

        // --- a failed batch blocks the merge AND the watermark advance ---
        Files.delete(batchDir.resolve("batch_C.json"));
        MergeResult m2 = runMerge(batchDir);
        SelfTest.check("merge fails when batch C partial is missing", !m2.ok);
        SelfTest.checkContains("failure names the missing batch",
                m2.reason == null ? "" : m2.reason, "C");

        Path tmp2 = Files.createTempDirectory("digest_batches_test2");
        Path batchDir2 = tmp2.resolve("digest_2026-09-28");
        Files.createDirectories(batchDir2);
        Files.copy(batchDir.resolve("batches.json"), batchDir2.resolve("batches.json"));
        Files.copy(batchDir.resolve("pending_watermark.json"),
                batchDir2.resolve("pending_watermark.json"));
        // No merged_extract.json here: no successful merge in this dir.
        SelfTest.checkEq("advance-watermark refused without a merge", 1,
                runAdvanceWatermark(batchDir2));
        SelfTest.check("watermark file not created on refusal",
                !Files.exists(tmp2.resolve("digest_watermark.json")));

        // --- happy path: merge then advance writes the watermark once ---
        // Restore batch_C.json (deleted above for the failure test) into the
        // ORIGINAL batchDir first, then copy the complete set over.
        JSONObject pc = new JSONObject();
        pc.put("date", "2026-09-28");
        JSONArray pcv = new JSONArray();
        for (String vid : byBatch.get("C")) {
            JSONObject v = new JSONObject();
            v.put("video_id", vid);
            v.put("creator", creatorFor[indexOf(fixture, vid)]);
            pcv.put(v);
        }
        pc.put("videos", pcv);
        Files.writeString(batchDir.resolve("batch_C.json"), pc.toString(2),
                StandardCharsets.UTF_8);

        Path tmp3 = Files.createTempDirectory("digest_batches_test3");
        Path batchDir3 = tmp3.resolve("digest_2026-09-28");
        Files.createDirectories(batchDir3);
        Files.copy(batchDir.resolve("batches.json"), batchDir3.resolve("batches.json"));
        Files.copy(batchDir.resolve("pending_watermark.json"),
                batchDir3.resolve("pending_watermark.json"));
        for (String bid : BATCH_IDS) {
            Files.copy(batchDir.resolve("batch_" + bid + ".json"),
                    batchDir3.resolve("batch_" + bid + ".json"));
        }

        SelfTest.checkEq("merge exits 0 on the happy path", 0, runMerge(batchDir3).ok ? 0 : 1);
        SelfTest.checkEq("advance-watermark exits 0 after merge", 0,
                runAdvanceWatermark(batchDir3));
        Path wm = tmp3.resolve("digest_watermark.json");
        SelfTest.check("watermark file written", Files.exists(wm));
        JSONObject wmj = new JSONObject(Files.readString(wm, StandardCharsets.UTF_8));
        SelfTest.checkEq("watermark window_end matches the plan",
                plan.getString("window_end"), wmj.getString("window_end"));
        SelfTest.check("pending watermark consumed",
                !Files.exists(batchDir3.resolve("pending_watermark.json")));
        SelfTest.checkEq("second advance refused (already advanced)", 1,
                runAdvanceWatermark(batchDir3));

        // --- merge rejects duplicates and drops ---
        JSONObject dup = new JSONObject(Files.readString(
                batchDir3.resolve("batch_A.json"), StandardCharsets.UTF_8));
        JSONArray dupVids = dup.getJSONArray("videos");
        JSONObject extra = new JSONObject();
        extra.put("video_id", "v-p1"); // already in batch A
        dupVids.put(extra);
        Files.writeString(batchDir3.resolve("batch_A.json"), dup.toString(2),
                StandardCharsets.UTF_8);
        MergeResult m3 = runMerge(batchDir3);
        SelfTest.check("merge fails on a duplicated video id", !m3.ok);

        SelfTest.finish();
    }

    private static String channelOf(String[][] fixture, String videoId) {
        for (String[] f : fixture) if (f[0].equals(videoId)) return f[1];
        return "";
    }

    private static int indexOf(String[][] fixture, String videoId) {
        for (int i = 0; i < fixture.length; i++) if (fixture[i][0].equals(videoId)) return i;
        return -1;
    }

    // ------------------------------------------------------------------
    // main
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0])) {
            System.out.println("Usage: DigestBatches --plan <videos.json> <batchdir>");
            System.out.println("       DigestBatches --fetch-batch <batchdir> <A|B|C|D>");
            System.out.println("       DigestBatches --merge <batchdir>");
            System.out.println("       DigestBatches --advance-watermark <batchdir>");
            System.out.println("       DigestBatches --self-test");
            System.exit(args.length == 0 ? 2 : 0);
            return;
        }
        switch (args[0]) {
            case "--plan":
                if (args.length != 3) { usage(); }
                System.exit(runPlan(Paths.get(args[1]), Paths.get(args[2])));
                break;
            case "--fetch-batch": {
                if (args.length != 3) { usage(); }
                FetchResult r = runFetchBatch(Paths.get(args[1]), args[2]);
                // Exit 0 unless the batch run itself errored; hand_read_needed
                // videos are normal and get a hand-read pass.
                System.exit(r.ok ? 0 : 1);
                break;
            }
            case "--merge": {
                if (args.length != 2) { usage(); }
                MergeResult r = runMerge(Paths.get(args[1]));
                System.exit(r.ok ? 0 : 1);
                break;
            }
            case "--advance-watermark":
                if (args.length != 2) { usage(); }
                System.exit(runAdvanceWatermark(Paths.get(args[1])));
                break;
            case "--self-test":
                runSelfTest();
                break;
            default:
                usage();
        }
    }

    private static void usage() {
        System.out.println("Usage: DigestBatches --plan|--fetch-batch|--merge|--advance-watermark|--self-test ...");
        System.exit(2);
    }
}
