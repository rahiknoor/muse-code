import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Build next fetch batch: newest-first videos with uncaptured candidates.
 *
 * <p>Usage: {@code java NextBatch.java [batch_size]} (default 80).
 *
 * <p>Steps (mirrors next_batch.py):
 * <ol>
 *   <li>Load captured.txt (tickers already captured) and done_ids.txt
 *       (video ids already fetched; missing file -&gt; empty).</li>
 *   <li>Walk fetch_queue.tsv (tab-separated: vid, title, comma-separated
 *       candidate tickers), newest first. For each row, drop candidates
 *       already captured; skip the row if it had candidates but none are
 *       left wanted (counted as skipped_dup), or if the video id is done.</li>
 *   <li>Stop after batch_size rows. Write batch_ids.txt (one id per line)
 *       and batch_meta.tsv (vid, title, wanted tickers).</li>
 *   <li>Print {@code batch=N skipped_dup=M} to stderr.</li>
 * </ol>
 *
 * <p>Run with the working directory set to ~/workspace/creator_tracker.
 */
public class NextBatch {

    public static void main(String[] args) throws Exception {
        int batchSize = args.length > 0 ? Integer.parseInt(args[0]) : 80;

        Set<String> captured = new LinkedHashSet<>();
        for (String line : textLines(Path.of("captured.txt"))) {
            String t = line.strip();
            if (!t.isEmpty()) captured.add(t);
        }

        // done_ids.txt may not exist yet (Python catches FileNotFoundError).
        Set<String> done = new LinkedHashSet<>();
        Path donePath = Path.of("done_ids.txt");
        if (Files.exists(donePath)) {
            for (String line : textLines(donePath)) {
                String t = line.strip();
                if (!t.isEmpty()) done.add(t);
            }
        }

        List<String[]> batch = new ArrayList<>(); // each: {vid, title, wantedCsv}
        int skipped = 0;
        for (String line : textLines(Path.of("fetch_queue.tsv"))) {
            // Python str.split("\t") keeps trailing empties; Java needs limit -1.
            String[] parts = line.split("\t", -1);
            String vid = parts[0];
            String title = parts[1];
            // dict.fromkeys(parts[2].split(",")) filtered to non-empty, order kept.
            List<String> cands = parts.length > 2
                    ? dedupNonEmpty(parts[2].split(",", -1)) : List.of();
            List<String> wanted = new ArrayList<>();
            for (String c : cands)
                if (!captured.contains(c)) wanted.add(c);
            if (!cands.isEmpty() && wanted.isEmpty()) {
                skipped++;
                continue;
            }
            if (done.contains(vid)) continue;
            batch.add(new String[]{vid, title, String.join(",", wanted)});
            if (batch.size() >= batchSize) break;
        }

        try (var w = Files.newBufferedWriter(Path.of("batch_ids.txt"), StandardCharsets.UTF_8)) {
            for (String[] r : batch) {
                w.write(r[0]);
                w.newLine();
            }
        }
        try (var w = Files.newBufferedWriter(Path.of("batch_meta.tsv"), StandardCharsets.UTF_8)) {
            for (String[] r : batch) {
                w.write(r[0] + "\t" + r[1] + "\t" + r[2]);
                w.newLine();
            }
        }

        System.err.println("batch=" + batch.size() + " skipped_dup=" + skipped);
    }

    /** dict.fromkeys(items) with empties removed, insertion order kept. */
    static List<String> dedupNonEmpty(String[] items) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : items)
            if (!s.isEmpty() && seen.add(s)) out.add(s);
        return out;
    }

    /**
     * Read a text file the way Python's {@code for line in open(...)} does:
     * universal newlines, each line rstripped of "\n" (a trailing newline does
     * not produce an extra empty line).
     */
    static List<String> textLines(Path path) throws Exception {
        String c = Files.readString(path, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n");
        List<String> lines = new ArrayList<>(Arrays.asList(c.split("\n", -1)));
        if (c.endsWith("\n")) lines.remove(lines.size() - 1);
        return lines;
    }
}
