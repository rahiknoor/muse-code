// TranscriptFetch.java
// Scripted YouTube transcript fetcher (audit item #3, approved 2026-09-28).
//
// Spike result 2026-09-28: the watch-page HTML path is 429-blocked from this
// datacenter IP (redirected to google.com/sorry — an IP-level bot flag that
// backoff does not clear). The Innertube player API (youtubei/v1/player with
// the public ANDROID client) returns HTTP 200 with captionTracks, and the
// timedtext baseUrl then returns the transcript XML. Java's HttpClient is
// denied by the sandbox proxy policy ("Other TCP connections is turned off"),
// so all HTTP goes through curl subprocesses, which the proxy allows.
//
// Strategy (jdepoix/youtube-transcript-api pattern):
//   1. Check the shared cache (youtube/cache/YoutubeCache.java) first —
//      YouTube transcripts are immutable, so a cached transcript is forever fresh.
//   2. POST youtubei/v1/player -> captionTracks.
//   3. Prefer manual English captions; fall back to auto-generated English.
//   4. GET the track's timedtext baseUrl -> caption XML -> plain text.
//   5. Store the full record in the shared cache (put) — forever.
// 429 / 5xx / network errors: exponential backoff + jitter, up to 5 tries,
// all quiet (never prompts Rahik).
// 429: NOT retried — a 429 is YouTube's explicit rate challenge (research
// rank #1, 2026-09-30), so the first 429 trips the circuit breaker
// immediately instead of probing into the flag. 5xx / network errors:
// exponential backoff + jitter, up to 5 tries, all quiet (never prompts Rahik).
// Politeness (2026-09-28, Rahik's ask; human-shaped 2026-09-30, his "human
// touch" order): real YouTube requests are spaced by Polite.paceHuman —
// exponential gaps (mean 15s) with occasional longer pauses, the shape of
// human inter-action timing, never metronomic. All backoff sleeps go through
// the shared Polite.backoffSleep (honors Retry-After). Circuit breaker
// (research rank #1): a 429 (on the FIRST occurrence — never retried into the
// flag), or a LOGIN_REQUIRED playability status, trips a 24h halt on ALL
// YouTube traffic (exit 3); cache hits are still served from disk.
// Shared copy: java-shared/Polite.java.
//
// Usage:
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/youtube/TranscriptFetch.java fetch <videoId>... [--job <job-id>]
//
// Output: one JSON object per videoId on stdout:
//   {"videoId":"...","ok":true,"cached":false,"manual":false,"chars":12345}
//   {"videoId":"...","ok":true,"cached":false,"noTranscript":true,"chars":0,
//    "title":"...","channel":"...","description":"..."}
//       — permanent "no transcript" verdict (no English captions, empty
//       timedtext, or a non-challenge playability failure). The verdict is
//       cached as a null-transcript record, so no job retries this video
//       for 7 days (the cache's no-transcript TTL). Callers use the
//       description and do NOT send the video to the browser fallback.
//   {"videoId":"...","ok":false,"error":"..."}        (stderr carries retry notes)
// Exit: 0 when every video resolved (a no-transcript verdict counts as
// resolved), 2 when any failed transiently, 3 when the circuit
// breaker is open (YouTube challenged us <24h ago — the caller must skip ALL
// YouTube work this run, with no fallback).
//
// No new cron — this rides inside the existing digest; the digest calls it
// instead of opening video pages by hand.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class TranscriptFetch {

    static final String HOME = homeDir();
    static final String JSON_JAR = HOME + "/workspace/lib/json/json-20240303.jar";
    static final String CACHE = HOME + "/workspace/youtube/cache/YoutubeCache.java";
    static final String JAVA = HOME + "/workspace/java/jdk-21/bin/java";

    // Public YouTube web-client API key (embedded in YouTube's own web client; not a secret).
    static final String PLAYER_URL =
        "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";
    static final String ANDROID_UA =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US)";
    static final String WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    static final int MAX_ATTEMPTS = 5;
    static final long PACE_MEAN_MS = 15_000; // mean human-shaped gap between YouTube requests

    // Circuit breaker: shared halt flag, tripped when YouTube issues an
    // explicit bot challenge. All jobs read it through this tool, so one
    // trip stops every YouTube job until it expires.
    static final Path BREAKER_FILE =
        Path.of(HOME + "/workspace/youtube/cache/circuit_breaker.json");
    static final long BREAKER_HALT_MS = 24L * 3600_000; // 24h halt after a challenge

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "--self-test".equals(args[0])) {
            runSelfTest();
            return;
        }
        if (args.length >= 2 && "--put-null".equals(args[0])) {
            // Hidden test hook: cache a permanent no-transcript verdict for
            // one video (exercises cacheNoTranscript without network).
            String id = args[1], job = "self-test";
            for (int i = 2; i < args.length; i++)
                if ("--job".equals(args[i]) && i + 1 < args.length) job = args[++i];
            System.out.println(cacheNoTranscript(id, job, "Test Title",
                "Test Channel", "Test description", "self-test verdict"));
            return;
        }
        if (args.length < 2 || !"fetch".equals(args[0])) {
            System.err.println("usage: TranscriptFetch.java fetch <videoId>... [--job <job-id>]");
            System.err.println("       TranscriptFetch.java --self-test");
            System.exit(1);
        }
        List<String> ids = new ArrayList<>();
        String job = "transcript-fetch";
        for (int i = 1; i < args.length; i++) {
            if ("--job".equals(args[i]) && i + 1 < args.length) { job = args[++i]; }
            else ids.add(args[i]);
        }
        boolean anyFail = false, breakerHit = false;
        for (String id : ids) {
            JSONObject out = new JSONObject().put("videoId", id);
            try {
                out = fetchOne(id, job);
            } catch (BreakerOpenException e) {
                breakerHit = true;
                out.put("ok", false).put("error", e.getMessage());
            } catch (Exception e) {
                out.put("ok", false).put("error", e.getMessage());
            }
            System.out.println(out.toString());
            if (!out.optBoolean("ok", false)) anyFail = true;
        }
        // Exit 3 = circuit breaker open: the caller must skip ALL YouTube work
        // this run (no retries, no browser fallback) and stay silent.
        System.exit(breakerHit ? 3 : anyFail ? 2 : 0);
    }

    static JSONObject fetchOne(String videoId, String job) throws Exception {
        // 1. Shared cache first (forever-fresh transcripts).
        JSONObject cached = cacheGet(videoId, job);
        if (cached != null) {
            JSONObject out = new JSONObject()
                .put("videoId", videoId).put("ok", true).put("cached", true);
            String t = cached.optString("transcript", null);
            out.put("chars", t == null ? 0 : t.length());
            return out;
        }

        // 1b. Circuit breaker (2026-09-30): cache hits are served from disk (no
        //     YouTube traffic), but a miss while the breaker is open fails fast
        //     instead of probing a challenged service.
        String breakerErr = breakerOpenErr();
        if (breakerErr != null) throw new BreakerOpenException(breakerErr);

        // 1c. Human-shaped pacing (2026-09-30): only real YouTube requests are
        //     paced — cache hits stay fast. First request in a batch is free.
        Polite.paceHuman("youtubei", PACE_MEAN_MS);

        // 2. Innertube player API -> caption tracks + video details.
        JSONObject player;
        try {
            player = postPlayer(videoId);
        } catch (PlayabilityException e) {
            // LOGIN_REQUIRED already tripped the breaker inside postPlayer —
            // never cache a challenge as a verdict. Any other playability
            // failure (private/deleted/etc.) is permanent: cache the
            // null-transcript verdict so jobs stop retrying this video.
            if ("LOGIN_REQUIRED".equals(e.status)) throw e;
            return cacheNoTranscript(videoId, job, "", "", "",
                "playability=" + e.status);
        }
        JSONObject details = player.optJSONObject("videoDetails");
        String title = details != null ? details.optString("title", "") : "";
        String channel = details != null ? details.optString("author", "") : "";
        String publishDate = details != null ? details.optString("publishDate", null) : null;
        String description = details != null ? details.optString("shortDescription", "") : "";

        List<JSONObject> tracks = new ArrayList<>();
        JSONObject caps = player.optJSONObject("captions");
        if (caps != null) {
            JSONObject list = caps.optJSONObject("playerCaptionsTracklistRenderer");
            if (list != null) {
                JSONArray arr = list.optJSONArray("captionTracks");
                if (arr != null) for (int i = 0; i < arr.length(); i++) tracks.add(arr.getJSONObject(i));
            }
        }
        List<JSONObject> en = new ArrayList<>();
        for (JSONObject t : tracks)
            if (t.optString("languageCode", "").startsWith("en")) en.add(t);
        if (en.isEmpty())
            // Permanent (2026-09-30): re-requesting this video every run is
            // what wastes requests and risks the IP flag. Cache the verdict;
            // the cache re-checks once after 7 days in case captions appear.
            return cacheNoTranscript(videoId, job, title, channel, description,
                "no English caption track (" + tracks.size() + " tracks in other languages)");

        JSONObject manual = null, auto = null;
        for (JSONObject t : en) {
            if ("asr".equals(t.optString("kind", ""))) { if (auto == null) auto = t; }
            else if (manual == null) manual = t;
        }
        JSONObject chosen = manual != null ? manual : auto;

        // 3. timedtext -> plain text.
        String xml = curlGet(chosen.getString("baseUrl"));
        String transcript = parseCaptions(xml);
        if (transcript.isEmpty())
            return cacheNoTranscript(videoId, job, title, channel, description,
                "timedtext returned no cues");

        // 4. Store in the shared cache (forever).
        JSONObject record = new JSONObject()
            .put("videoId", videoId)
            .put("title", title)
            .put("channel", channel)
            .put("uploadTime", publishDate == null ? JSONObject.NULL : publishDate)
            .put("url", "https://www.youtube.com/watch?v=" + videoId)
            .put("description", description)
            .put("transcript", transcript)
            .put("transcriptChecked", true);
        cachePut(videoId, job, record);

        return new JSONObject()
            .put("videoId", videoId).put("ok", true).put("cached", false)
            .put("manual", manual != null).put("chars", transcript.length());
    }

    /** Permanent "no transcript" verdict: cache a null-transcript record so no
     *  job retries this video for 7 days (the cache's no-transcript TTL), and
     *  report it as resolved — the player-API description still goes to the
     *  callers, and they skip the browser fallback (a page load would only
     *  re-discover what the player API already said). Transient failures
     *  (429, LOGIN_REQUIRED, 5xx/network exhaustion) must NEVER come here —
     *  they are not verdicts. */
    static JSONObject cacheNoTranscript(String videoId, String job,
            String title, String channel, String description, String reason) throws Exception {
        JSONObject record = new JSONObject()
            .put("videoId", videoId)
            .put("title", title)
            .put("channel", channel)
            .put("uploadTime", JSONObject.NULL)
            .put("url", "https://www.youtube.com/watch?v=" + videoId)
            .put("description", description)
            .put("transcript", JSONObject.NULL)
            .put("transcriptChecked", true)
            .put("noTranscriptReason", reason);
        cachePut(videoId, job, record);
        System.err.println("transcript-fetch: no transcript for " + videoId
            + " (" + reason + ") — cached null verdict, not retrying for 7 days");
        return new JSONObject()
            .put("videoId", videoId).put("ok", true).put("cached", false)
            .put("noTranscript", true).put("chars", 0)
            .put("title", title).put("channel", channel).put("description", description);
    }

    // ------------------------------------------------------------------ network

    static JSONObject postPlayer(String videoId) throws Exception {
        JSONObject body = new JSONObject()
            .put("context", new JSONObject().put("client", new JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", "20.10.38")
                .put("androidSdkVersion", 34)
                .put("hl", "en").put("gl", "US")))
            .put("videoId", videoId);
        Path tmp = Files.createTempFile("player-body", ".json");
        try {
            Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8);
            String resp = withBackoff(() -> curl("POST", PLAYER_URL, tmp));
            JSONObject player = new JSONObject(resp);
            String status = player.optJSONObject("playabilityStatus") != null
                ? player.optJSONObject("playabilityStatus").optString("status", "?") : "?";
            if (!"OK".equals(status)) {
                // LOGIN_REQUIRED is YouTube's explicit bot challenge (research
                // rank #1, 2026-09-30): stop all YouTube traffic for 24h rather
                // than retrying into the flag.
                if ("LOGIN_REQUIRED".equals(status)) tripBreaker("LOGIN_REQUIRED playability for " + videoId);
                throw new PlayabilityException(status, videoId);
            }
            return player;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static String curlGet(String url) throws Exception {
        return withBackoff(() -> curl("GET", url, null));
    }

    interface CurlCall { String run() throws Exception; }

    /** Exponential backoff + jitter on 5xx/network failure (shared Polite helper).
     *  A 429 is never retried: it trips the circuit breaker immediately —
     *  retrying into YouTube's explicit rate challenge is what gets IPs flagged.
     *  Quiet: stderr note only. */
    static String withBackoff(CurlCall call) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.run();
            } catch (CurlHttpException e) {
                last = e;
                if (e.status == 429) {
                    // A 429 is YouTube's explicit rate challenge (research
                    // rank #1, 2026-09-30): stop immediately — never retry
                    // into the flag.
                    tripBreaker("HTTP 429 from YouTube (tripped on first 429, not retried)");
                    throw e;
                }
                if (e.status >= 500 && attempt < MAX_ATTEMPTS) {
                    long wait = Polite.backoffSleep(attempt, e.retryAfterMs);
                    System.err.println("transcript-fetch: HTTP " + e.status
                        + ", backing off " + wait + "ms (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");
                } else {
                    throw e;
                }
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    long wait = Polite.backoffSleep(attempt);
                    System.err.println("transcript-fetch: " + e.getMessage()
                        + ", backing off " + wait + "ms (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");
                } else throw e;
            }
        }
        throw last;
    }

    static final class CurlHttpException extends IOException {
        final int status;
        final long retryAfterMs;
        CurlHttpException(int status, long retryAfterMs) {
            super("HTTP " + status);
            this.status = status;
            this.retryAfterMs = retryAfterMs;
        }
    }

    static final class BreakerOpenException extends IOException {
        BreakerOpenException(String msg) { super(msg); }
    }

    /** Non-OK playability from the player API. LOGIN_REQUIRED is YouTube's
     *  explicit bot challenge (trips the breaker in postPlayer); anything
     *  else (private, deleted, age-gated...) is a permanent verdict. */
    static final class PlayabilityException extends IOException {
        final String status;
        PlayabilityException(String status, String videoId) {
            super("player playability=" + status + " for " + videoId);
            this.status = status;
        }
    }

    // ------------------------------------------------------- circuit breaker

    /** Returns the open-breaker error, or null when YouTube requests may proceed.
     *  An expired breaker is cleared. An unreadable breaker file is treated as
     *  closed (fail-open would only risk one probe, which re-trips on challenge). */
    static String breakerOpenErr() {
        try {
            if (!Files.exists(BREAKER_FILE)) return null;
            JSONObject b = new JSONObject(Files.readString(BREAKER_FILE, StandardCharsets.UTF_8));
            long tripped = b.optLong("tripped_at_ms", 0);
            long age = System.currentTimeMillis() - tripped;
            if (age < BREAKER_HALT_MS) {
                long resumeMin = (BREAKER_HALT_MS - age + 59999) / 60000;
                return "circuit breaker open (" + b.optString("reason", "YouTube challenge")
                    + "); YouTube traffic halted ~" + resumeMin + " more min";
            }
            Files.deleteIfExists(BREAKER_FILE); // expired: clear it
        } catch (Exception ignored) { /* unreadable: treat as closed */ }
        return null;
    }

    /** Trip the breaker: no YouTube traffic from any job for 24h. Best effort.
     *  The state file is written atomically (temp + atomic move) so two jobs
     *  tripping at once cannot leave a torn file. */
    static void tripBreaker(String reason) {
        try {
            JSONObject b = new JSONObject()
                .put("tripped_at_ms", System.currentTimeMillis())
                .put("tripped_at", java.time.Instant.now().toString())
                .put("reason", reason);
            Path tmp = Files.createTempFile(BREAKER_FILE.getParent(), "breaker", ".json");
            Files.writeString(tmp, b.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, BREAKER_FILE,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.err.println("transcript-fetch: CIRCUIT BREAKER TRIPPED — " + reason
                + " (all YouTube traffic halted 24h)");
        } catch (Exception ignored) { /* best effort */ }
    }

    /** curl subprocess — the sandbox proxy allows curl but blocks Java's raw sockets.
     *  Response headers are dumped to a temp file so a 429's Retry-After can be honored. */
    static String curl(String method, String url, Path dataFile) throws Exception {
        Path hdrFile = Files.createTempFile("curl-hdr", ".txt");
        try {
            List<String> cmd = new ArrayList<>(List.of(
                "curl", "-sS", "-m", "60",
                "-D", hdrFile.toString(),
                "-A", "POST".equals(method) ? ANDROID_UA : WEB_UA,
                "-H", "Accept-Language: en-US,en;q=0.9",
                "-w", "\n%{http_code}"));
            if ("POST".equals(method)) {
                cmd.addAll(List.of("-X", "POST", "-H", "Content-Type: application/json",
                    "--data-binary", "@" + dataFile.toString()));
            }
            cmd.add(url);
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = p.waitFor();
            if (exit != 0) throw new IOException("curl exit=" + exit + ": " + stderr);
            int nl = stdout.lastIndexOf('\n');
            int status = Integer.parseInt(stdout.substring(nl + 1).trim());
            String body = stdout.substring(0, nl);
            if (status == 200) return body;
            throw new CurlHttpException(status, retryAfterMs(hdrFile));
        } finally {
            Files.deleteIfExists(hdrFile);
        }
    }

    /** Parse a Retry-After response header into milliseconds: delta-seconds,
     *  or an HTTP-date (delay until that instant). 0 when absent/unparseable. */
    static long retryAfterMs(Path hdrFile) {
        try {
            for (String line : Files.readAllLines(hdrFile, StandardCharsets.UTF_8)) {
                if (line.regionMatches(true, 0, "Retry-After:", 0, 12)) {
                    String v = line.substring(12).trim();
                    try {
                        return Math.max(0, Long.parseLong(v)) * 1000;
                    } catch (NumberFormatException nfe) {
                        try {
                            long then = java.time.ZonedDateTime.parse(v,
                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant().toEpochMilli();
                            return Math.max(0, then - System.currentTimeMillis());
                        } catch (Exception ignored) { return 0; }
                    }
                }
            }
        } catch (Exception ignored) { /* absent header: no backoff override */ }
        return 0;
    }

    // ------------------------------------------------------------------ parsing

    /** Format-3 (<p><s>) or srv1 (<text>) caption XML -> plain text. */
    static String parseCaptions(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<s[^>]*>(.*?)</s>", Pattern.DOTALL).matcher(xml);
        boolean found = false;
        while (m.find()) { appendCue(sb, m.group(1)); found = true; }
        if (!found) {
            m = Pattern.compile("<text[^>]*>(.*?)</text>", Pattern.DOTALL).matcher(xml);
            while (m.find()) appendCue(sb, m.group(1));
        }
        return sb.toString().trim();
    }

    static void appendCue(StringBuilder sb, String raw) {
        String t = raw.replaceAll("<[^>]+>", "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replaceAll("\\s+", " ").trim();
        if (!t.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(t); }
    }

    // ------------------------------------------------------------------ cache

    /** YoutubeCache get: exit 0 + record JSON on stdout = HIT, exit 1 = MISS.
     *  The cache itself enforces freshness: real transcripts are forever,
     *  "no transcript" verdicts are re-checked after 7 days. */
    static JSONObject cacheGet(String videoId, String job) throws Exception {
        Process p = new ProcessBuilder(JAVA, "--class-path", JSON_JAR, CACHE,
                "get", videoId, "--job", job)
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) return null;
        return new JSONObject(out.trim());
    }

    static void cachePut(String videoId, String job, JSONObject record) throws Exception {
        Process p = new ProcessBuilder(JAVA, "--class-path", JSON_JAR, CACHE,
                "put", videoId, "--job", job)
            .redirectErrorStream(true).start();
        p.getOutputStream().write(record.toString().getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        p.waitFor();
    }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    // ------------------------------------------------------------------ self-test

    /** --self-test: no network. Verifies that a permanent no-transcript
     *  verdict is cached as a null-transcript record (served as a HIT with
     *  transcript null), and that a real transcript is never nullified
     *  (no over-caching). All cache traffic goes to a scratch dir. */
    static void runSelfTest() throws Exception {
        Path scratch = Files.createTempDirectory("tf-selftest");
        try {
            String id1 = "dQw4w9WgXcQ", id2 = "9bZkp7q19f0";
            // 1. --put-null caches the verdict and reports it resolved.
            JSONObject o1 = new JSONObject(runChild(
                new String[]{"--put-null", id1, "--job", "self-test"}, scratch).trim());
            SelfTest.checkEq("put-null ok", true, o1.optBoolean("ok", false));
            SelfTest.checkEq("put-null noTranscript", true, o1.optBoolean("noTranscript", false));
            SelfTest.checkEq("put-null chars", 0, o1.optInt("chars", -1));
            // 2. The cached verdict is served as a HIT with transcript null.
            JSONObject r = new JSONObject(runCacheGet(id1, scratch).trim());
            SelfTest.checkEq("verdict videoId", id1, r.optString("videoId", ""));
            SelfTest.check("verdict transcript null", r.isNull("transcript"));
            SelfTest.checkEq("verdict reason kept", "self-test verdict",
                r.optString("noTranscriptReason", ""));
            SelfTest.checkEq("verdict job stamped", "self-test", r.optString("fetchedBy", ""));
            // 3. A real transcript is NOT turned into a null verdict.
            JSONObject real = new JSONObject().put("videoId", id2)
                .put("title", "T").put("channel", "C").put("uploadTime", JSONObject.NULL)
                .put("url", "https://www.youtube.com/watch?v=" + id2)
                .put("description", "D").put("transcript", "hello world")
                .put("transcriptChecked", true);
            cachePutTo(id2, "self-test", real, scratch);
            JSONObject r2 = new JSONObject(runCacheGet(id2, scratch).trim());
            SelfTest.checkEq("real transcript kept", "hello world",
                r2.optString("transcript", ""));
            SelfTest.check("real transcript not null", !r2.isNull("transcript"));
        } finally {
            deleteDir(scratch);
        }
        SelfTest.finish();
    }

    static String runChild(String[] args, Path scratch) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(JAVA, "--class-path",
            HOME + "/workspace/java-shared/classes:" + JSON_JAR,
            HOME + "/workspace/youtube/TranscriptFetch.java"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("YOUTUBE_CACHE_DIR", scratch.toString());
        // NOTE: stderr is NOT merged — cacheNoTranscript logs a note there,
        // and stdout must stay parseable JSON.
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("--put-null child exited " + exit + ": " + out);
        return out;
    }

    static String runCacheGet(String videoId, Path scratch) throws Exception {
        List<String> cmd = List.of(JAVA, "--class-path", JSON_JAR,
            CACHE, "get", videoId, "--job", "self-test");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("YOUTUBE_CACHE_DIR", scratch.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("cache get exited " + exit + " (want HIT): " + out);
        return out;
    }

    static void cachePutTo(String videoId, String job, JSONObject record, Path scratch)
            throws Exception {
        List<String> cmd = List.of(JAVA, "--class-path", JSON_JAR,
            CACHE, "put", videoId, "--job", job);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("YOUTUBE_CACHE_DIR", scratch.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(record.toString().getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("cache put exited " + exit + ": " + out);
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
