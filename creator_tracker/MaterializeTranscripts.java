// MaterializeTranscripts.java
// Replaces the Python cache-materialization snippet in the
// parkev-backfill-transcript-retry cron (2026-09-30 audit — Rahik reads Java,
// not Python). Reads the cache records TranscriptFetch.java wrote and
// materializes them into the plain-text files GrepValues needs:
//
//   today_ids.txt          (one 11-char video ID per line, from the batch step)
//   ~/workspace/youtube/cache/data/<id>.json   (TranscriptFetch cache records)
//     -> transcripts/<id>.vtt                 (plain transcript text)
//     -> upload_dates.tsv                     ("<id> <yyyymmdd|unknown|raw>" per line)
//
// Cache records are keyed forever-fresh; this tool never touches YouTube.
// IDs with no cache record are skipped (no transcript yet). A record with a
// null/empty transcript still gets an upload_dates.tsv line (upload date may
// exist even when captions don't).
//
// Usage (work dir ~/workspace/creator_tracker):
//   java --class-path "$HOME/workspace/java-shared/classes:$HOME/workspace/lib/json/json-20240303.jar" \
//        ~/workspace/creator_tracker/MaterializeTranscripts.java
//
// Options: --ids <file> (default today_ids.txt) --cache-dir <dir>
// (default ~/workspace/youtube/cache/data) --out-dir <dir> (default
// transcripts) --dates-file <file> (default upload_dates.tsv)

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

public class MaterializeTranscripts {

    static final String HOME = homeDir();

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }

        String idsFile = "today_ids.txt";
        Path cacheDir = Path.of(HOME + "/workspace/youtube/cache/data");
        Path outDir = Path.of("transcripts");
        Path datesFile = Path.of("upload_dates.tsv");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ids" -> idsFile = args[++i];
                case "--cache-dir" -> cacheDir = Path.of(args[++i]);
                case "--out-dir" -> outDir = Path.of(args[++i]);
                case "--dates-file" -> datesFile = Path.of(args[++i]);
                default -> { System.err.println("unknown arg: " + args[i]); System.exit(1); }
            }
        }

        int materialized = run(Path.of(idsFile), cacheDir, outDir, datesFile);
        System.out.println("materialized " + materialized + " videos");
    }

    /** Returns the number of cache records found (dates lines written). */
    static int run(Path idsFile, Path cacheDir, Path outDir, Path datesFile) throws Exception {
        Files.createDirectories(outDir);
        List<String> dateLines = new ArrayList<>();
        for (String line : Files.readAllLines(idsFile, StandardCharsets.UTF_8)) {
            String vid = line.trim();
            if (vid.isEmpty()) continue;
            Path rec = cacheDir.resolve(vid + ".json");
            if (!Files.exists(rec)) continue; // no fetch yet — skip
            JSONObject r = new JSONObject(Files.readString(rec, StandardCharsets.UTF_8));
            String transcript = r.optString("transcript", null);
            if (transcript != null && !transcript.isEmpty()) {
                Files.writeString(outDir.resolve(vid + ".vtt"), transcript, StandardCharsets.UTF_8);
            }
            dateLines.add(vid + " " + uploadDateStamp(r.optString("uploadTime", "").trim()));
        }
        Files.writeString(datesFile, String.join("\n", dateLines) + "\n", StandardCharsets.UTF_8);
        return dateLines.size();
    }

    /** Upload time -> yyyymmdd stamp. "unknown" when absent; raw when unparseable. */
    static String uploadDateStamp(String uploadTime) {
        if (uploadTime.isEmpty()) return "unknown";
        DateTimeFormatter out = DateTimeFormatter.ofPattern("yyyyMMdd");
        DateTimeFormatter[] fmts = {
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        };
        for (DateTimeFormatter f : fmts) {
            try { return LocalDate.parse(uploadTime, f).format(out); }
            catch (DateTimeParseException ignored) { }
        }
        return uploadTime; // matches the old Python behavior for unparseable dates
    }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    // ------------------------------------------------------------------ self-test

    /** java --class-path ~/workspace/java-shared/classes:~/workspace/lib/json/json-20240303.jar \
     *      ~/workspace/creator_tracker/MaterializeTranscripts.java --self-test */
    static void selfTest() throws Exception {
        Path tmp = Files.createTempDirectory("mt-selftest");
        Path cacheDir = tmp.resolve("cache"); Files.createDirectories(cacheDir);
        Path outDir = tmp.resolve("transcripts");
        Path idsFile = tmp.resolve("today_ids.txt");
        Path datesFile = tmp.resolve("upload_dates.tsv");

        // Fixture 1: full record, "Sep 15, 2026" date.
        Files.writeString(cacheDir.resolve("VID11111111.json"), new JSONObject()
            .put("videoId", "VID11111111")
            .put("transcript", "hello world transcript one")
            .put("uploadTime", "Sep 15, 2026").toString(), StandardCharsets.UTF_8);
        // Fixture 2: ISO date, empty transcript (still gets a date line, no vtt).
        Files.writeString(cacheDir.resolve("VID22222222.json"), new JSONObject()
            .put("videoId", "VID22222222")
            .put("transcript", "")
            .put("uploadTime", "2026-03-04").toString(), StandardCharsets.UTF_8);
        // Fixture 3: no uploadTime at all.
        Files.writeString(cacheDir.resolve("VID33333333.json"), new JSONObject()
            .put("videoId", "VID33333333")
            .put("transcript", "third transcript").toString(), StandardCharsets.UTF_8);
        // VID44444444 has NO cache record — must be skipped.
        Files.writeString(idsFile, "VID11111111\nVID22222222\nVID33333333\nVID44444444\n",
            StandardCharsets.UTF_8);

        int n = run(idsFile, cacheDir, outDir, datesFile);
        SelfTest.checkEq("3 records materialized (missing ID skipped)", 3, n);

        SelfTest.checkEq("vtt 1 contents", "hello world transcript one",
            Files.readString(outDir.resolve("VID11111111.vtt"), StandardCharsets.UTF_8));
        SelfTest.check("vtt 2 absent (empty transcript)",
            !Files.exists(outDir.resolve("VID22222222.vtt")));
        SelfTest.checkEq("vtt 3 contents", "third transcript",
            Files.readString(outDir.resolve("VID33333333.vtt"), StandardCharsets.UTF_8));

        List<String> dates = Files.readAllLines(datesFile, StandardCharsets.UTF_8);
        SelfTest.checkEq("date lines", 3, dates.size());
        SelfTest.check("date 1 parsed Sep 15, 2026 -> 20260915",
            dates.get(0).equals("VID11111111 20260915"));
        SelfTest.check("date 2 parsed 2026-03-04 -> 20260304",
            dates.get(1).equals("VID22222222 20260304"));
        SelfTest.check("date 3 unknown (no uploadTime)",
            dates.get(2).equals("VID33333333 unknown"));

        SelfTest.finish();
    }
}
