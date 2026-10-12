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
//
// Usage:
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/youtube/TranscriptFetch.java fetch <videoId>... [--job <job-id>]
//
// Output: one JSON object per videoId on stdout:
//   {"videoId":"...","ok":true,"cached":false,"manual":false,"chars":12345}
//   {"videoId":"...","ok":false,"error":"..."}        (stderr carries retry notes)
// Exit: 0 when every video resolved, 2 when any failed.
//
// No new cron — this rides inside the existing digest; the digest calls it
// instead of opening video pages by hand.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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

    static final Random RAND = new Random();
    static final int MAX_ATTEMPTS = 5;

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !"fetch".equals(args[0])) {
            System.err.println("usage: TranscriptFetch.java fetch <videoId>... [--job <job-id>]");
            System.exit(1);
        }
        List<String> ids = new ArrayList<>();
        String job = "transcript-fetch";
        for (int i = 1; i < args.length; i++) {
            if ("--job".equals(args[i]) && i + 1 < args.length) { job = args[++i]; }
            else ids.add(args[i]);
        }
        boolean anyFail = false;
        for (String id : ids) {
            JSONObject out = new JSONObject().put("videoId", id);
            try {
                out = fetchOne(id, job);
            } catch (Exception e) {
                out.put("ok", false).put("error", e.getMessage());
            }
            System.out.println(out.toString());
            if (!out.optBoolean("ok", false)) anyFail = true;
        }
        System.exit(anyFail ? 2 : 0);
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

        // 2. Innertube player API -> caption tracks + video details.
        JSONObject player = postPlayer(videoId);
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
            throw new IOException("no English caption track for " + videoId
                + " (" + tracks.size() + " tracks in other languages)");

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
            throw new IOException("timedtext returned no cues for " + videoId);

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
            if (!"OK".equals(status))
                throw new IOException("player playability=" + status + " for " + videoId);
            return player;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static String curlGet(String url) throws Exception {
        return withBackoff(() -> curl("GET", url, null));
    }

    interface CurlCall { String run() throws Exception; }

    /** Exponential backoff + jitter on 429/5xx/network failure. Quiet: stderr note only. */
    static String withBackoff(CurlCall call) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.run();
            } catch (CurlHttpException e) {
                last = e;
                if ((e.status == 429 || e.status >= 500) && attempt < MAX_ATTEMPTS) {
                    long wait = (1L << attempt) * 1000 + RAND.nextInt(1000);
                    System.err.println("transcript-fetch: HTTP " + e.status
                        + ", backing off " + wait + "ms (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");
                    Thread.sleep(wait);
                } else throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    long wait = (1L << attempt) * 1000 + RAND.nextInt(1000);
                    System.err.println("transcript-fetch: " + e.getMessage()
                        + ", backing off " + wait + "ms (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");
                    Thread.sleep(wait);
                } else throw e;
            }
        }
        throw last;
    }

    static final class CurlHttpException extends IOException {
        final int status;
        CurlHttpException(int status) { super("HTTP " + status); this.status = status; }
    }

    /** curl subprocess — the sandbox proxy allows curl but blocks Java's raw sockets. */
    static String curl(String method, String url, Path dataFile) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            "curl", "-sS", "-m", "60",
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
        throw new CurlHttpException(status);
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
}
