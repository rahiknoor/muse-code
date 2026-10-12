// YoutubeCache.java — shared cache for YouTube video metadata + transcripts.
//
// WHY THIS EXISTS (idea #38, approved by Rahik 2026-09-27):
// The morning digest and the hourly buy-call alerts both read the same
// YouTube videos through live browser tasks. Without a cache, a video the
// digest read at 8:46 AM gets read AGAIN by an hourly scan later that day —
// doubling page loads and transcript fetches, which is exactly what trips
// YouTube's 429 rate limiting and stalls jobs. This cache stores each
// video's data once, keyed by video ID, so whichever job runs second serves
// it from disk instead of hitting YouTube.
//
// RUN IT with single-file launch (no compile step needed):
//   ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/lib/json/json-20240303.jar \
//       ~/workspace/youtube/cache/YoutubeCache.java <command> ...
//
// COMMANDS:
//   get <videoId> [--job <name>]  Print the cached record as JSON on stdout.
//                                 Exit 0 = HIT (use the output, skip YouTube).
//                                 Exit 1 = MISS (fetch the video normally).
//   put <videoId> [--job <name>]  Read one JSON record from stdin and store it.
//   stats                          Print HIT/MISS totals per job from hits.log.
//
// CACHE POLICY (the important design decision):
//   - A cached TRANSCRIPT is served forever. YouTube transcripts are
//     immutable once published — re-fetching one is pure waste.
//   - A cached "no transcript" verdict (transcript == null) is served for
//     7 days, then re-checked once. A video CAN gain captions after upload,
//     but if it has none after a week it almost certainly never will.
//   - The description rides along in the same record; it is only a fallback
//     for when no transcript exists, so the 7-day rule covers it too.
//
// LAYOUT (under ~/workspace/youtube/cache/):
//   data/<videoId>.json   one JSON record per video (this file's folder)
//   hits.log              one line per get/put: timestamp | job | video | result
//
// NOTE on paths: never use System.getProperty("user.home") here — on this
// machine it returns /root while the workspace lives under $HOME=/home/hatch.
// We resolve the cache dir from the YOUTUBE_CACHE_DIR env var, else
// $HOME/workspace/youtube/cache.

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;

import org.json.JSONObject;

public class YoutubeCache {

    // A "no transcript" verdict goes stale after this many days, then the
    // video is re-checked once. Transcripts themselves never expire.
    private static final long NO_TRANSCRIPT_TTL_DAYS = 7;

    // YouTube video IDs are always exactly 11 of these characters.
    // Requiring the exact shape also blocks path-traversal tricks like "../".
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private static Path cacheDir;
    private static PrintStream out; // UTF-8 stdout (transcripts contain emoji etc.)

    public static void main(String[] args) throws Exception {
        // PrintStream with explicit UTF-8: the platform default charset is
        // not guaranteed to be UTF-8, and transcripts are full of unicode.
        out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        resolveCacheDir();

        if (args.length < 1) {
            usage();
            System.exit(2);
        }
        String command = args[0];
        // Optional trailing flag: --job <name>, so hits.log shows which job
        // looked the video up (digest vs buy-call alerts).
        String job = flagValue(args, "--job", "unknown");

        switch (command) {
            case "get" -> {
                requireArg(args, 1, "get <videoId> [--job <name>]");
                System.exit(cmdGet(args[1], job) ? 0 : 1);
            }
            case "put" -> {
                requireArg(args, 1, "put <videoId> [--job <name>]  (record JSON on stdin)");
                cmdPut(args[1], job);
            }
            case "stats" -> cmdStats();
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    // ---- commands -------------------------------------------------------

    // Returns true on HIT (record printed to stdout), false on MISS.
    // Either way the lookup is appended to hits.log.
    private static boolean cmdGet(String videoId, String job) throws IOException {
        checkVideoId(videoId);
        Path file = dataFile(videoId);
        boolean hit = false;
        if (Files.isRegularFile(file)) {
            try {
                JSONObject record = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
                if (isFresh(record)) {
                    out.println(record.toString(2));
                    hit = true;
                }
                // Not fresh (stale "no transcript" verdict) -> MISS so the
                // caller re-fetches the video and PUTs a fresh record.
            } catch (Exception e) {
                // Corrupt cache file: safest is MISS; the fresh PUT overwrites it.
                hit = false;
            }
        }
        logLookup(job, videoId, hit ? "HIT" : "MISS");
        return hit;
    }

    // Reads one JSON record from stdin, stamps it, and stores it.
    // The record the caller supplies needs only the video fields; we add
    // fetchedAt / fetchedBy here so every stored record has the same shape.
    private static void cmdPut(String videoId, String job) throws IOException {
        checkVideoId(videoId);
        String stdin;
        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A"); // "\\A" = start of input: reads ALL of stdin
            stdin = scanner.hasNext() ? scanner.next() : "";
        }
        if (stdin.isBlank()) {
            die("put: no JSON record received on stdin");
        }
        JSONObject incoming;
        try {
            incoming = new JSONObject(stdin);
        } catch (Exception e) {
            die("put: stdin was not valid JSON: " + e.getMessage());
            return;
        }
        if (!videoId.equals(incoming.optString("videoId", ""))) {
            die("put: record's videoId does not match the <videoId> argument");
        }

        // Build the canonical stored record: caller's fields + our stamps.
        JSONObject record = new JSONObject();
        record.put("videoId", videoId);
        record.put("title", incoming.optString("title", ""));
        record.put("channel", incoming.optString("channel", ""));
        record.put("uploadTime", incoming.optString("uploadTime", ""));
        record.put("url", incoming.optString("url", "https://www.youtube.com/watch?v=" + videoId));
        record.put("description", incoming.optString("description", ""));
        if (incoming.isNull("transcript")) {
            record.put("transcript", JSONObject.NULL);
        } else {
            record.put("transcript", incoming.optString("transcript", ""));
        }
        record.put("transcriptChecked", true);
        // Preserve the fetcher's no-transcript verdict reason (empty when the
        // record holds a real transcript).
        record.put("noTranscriptReason", incoming.optString("noTranscriptReason", ""));
        record.put("fetchedAt", Instant.now().toString());
        record.put("fetchedBy", job);

        Files.createDirectories(dataDir());
        Files.writeString(dataFile(videoId), record.toString(2), StandardCharsets.UTF_8);
        logLookup(job, videoId, "PUT");
    }

    // Summarizes hits.log so Rahik can SEE the saving during the first week:
    // total lookups, hits, misses, and the per-job breakdown.
    private static void cmdStats() throws IOException {
        Path log = cacheDir.resolve("hits.log");
        if (!Files.isRegularFile(log)) {
            out.println("No lookups logged yet.");
            return;
        }
        long hits = 0, misses = 0;
        Map<String, long[]> perJob = new HashMap<>(); // job -> [hits, misses]
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            String job = grab(line, "job=");
            String result = grab(line, "result=");
            if (job.isEmpty() || result.isEmpty()) {
                continue;
            }
            long[] counts = perJob.computeIfAbsent(job, k -> new long[2]);
            if (result.equals("HIT")) {
                hits++;
                counts[0]++;
            } else if (result.equals("MISS")) {
                misses++;
                counts[1]++;
            }
        }
        long total = hits + misses;
        out.println("YouTube cache stats (" + total + " lookups):");
        out.println("  HIT  (served from disk) : " + hits);
        out.println("  MISS (fetched from YouTube): " + misses);
        if (total > 0) {
            out.println("  Hit rate: " + Math.round(100.0 * hits / total) + "% of video reads avoided a YouTube fetch");
        }
        for (Map.Entry<String, long[]> e : perJob.entrySet()) {
            out.println("  job " + e.getKey() + ": " + e.getValue()[0] + " hits, " + e.getValue()[1] + " misses");
        }
    }

    // ---- cache policy ---------------------------------------------------

    // A record is fresh (served as HIT) when it holds a transcript — those
    // never change — or when a transcript-less record is still inside the
    // 7-day "no transcript" verdict window.
    private static boolean isFresh(JSONObject record) {
        if (!record.isNull("transcript") && !record.optString("transcript", "").isEmpty()) {
            return true;
        }
        try {
            Instant fetchedAt = Instant.parse(record.getString("fetchedAt"));
            return Duration.between(fetchedAt, Instant.now()).toDays() < NO_TRANSCRIPT_TTL_DAYS;
        } catch (Exception e) {
            return false; // no parseable timestamp -> treat as stale
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static void resolveCacheDir() {
        // Env override first (lets tests point at a scratch dir), else
        // $HOME/workspace/youtube/cache. $HOME is /home/hatch in every shell
        // here; the Java user.home property would wrongly give /root.
        String dir = System.getenv("YOUTUBE_CACHE_DIR");
        if (dir == null || dir.isBlank()) {
            String home = System.getenv("HOME");
            if (home == null || home.isBlank()) {
                die("HOME is not set and YOUTUBE_CACHE_DIR is not set; cannot locate the cache");
            }
            dir = home + "/workspace/youtube/cache";
        }
        cacheDir = Paths.get(dir);
    }

    private static Path dataDir() {
        return cacheDir.resolve("data");
    }

    private static Path dataFile(String videoId) {
        return dataDir().resolve(videoId + ".json");
    }

    private static void checkVideoId(String videoId) {
        if (!VIDEO_ID.matcher(videoId).matches()) {
            die("not a valid YouTube video ID (want 11 chars of A-Za-z0-9_-): " + videoId);
        }
    }

    // One append-only line per lookup; this is the "first week" visibility log.
    private static void logLookup(String job, String videoId, String result) throws IOException {
        Files.createDirectories(cacheDir);
        String line = Instant.now() + " | job=" + job + " | video=" + videoId + " | result=" + result + "\n";
        Files.writeString(cacheDir.resolve("hits.log"), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // Pulls "key=value" out of a " | "-separated log line.
    private static String grab(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) {
            return "";
        }
        int end = line.indexOf(" |", i);
        return line.substring(i + key.length(), end < 0 ? line.length() : end).trim();
    }

    private static String flagValue(String[] args, String flag, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static void requireArg(String[] args, int index, String hint) {
        if (args.length <= index) {
            die("missing argument. Usage: " + hint);
        }
    }

    private static void usage() {
        out.println("Usage:");
        out.println("  YoutubeCache.java get <videoId> [--job <name>]   (exit 0=HIT, 1=MISS)");
        out.println("  YoutubeCache.java put <videoId> [--job <name>]   (record JSON on stdin)");
        out.println("  YoutubeCache.java stats");
    }

    private static void die(String message) {
        System.err.println("YoutubeCache: " + message);
        System.exit(2);
    }
}
