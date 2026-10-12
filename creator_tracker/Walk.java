import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Newest-first walk: build transcript-fetch queue with per-ticker dedup.
 *
 * <p>Usage: {@code java Walk.java} (no arguments).
 *
 * <p>Steps (mirrors walk.py):
 * <ol>
 *   <li>Walk worklist.tsv (tab-separated: vid, title, comma-separated
 *       candidate tickers), newest first.</li>
 *   <li>Drop candidates already in CAPTURED (the seeded entries). A row whose
 *       candidates are all captured is skipped (skipped_dup); a row with no
 *       candidates at all is still queued (counted as no_candidate_queued).</li>
 *   <li>Stop early after 50 consecutive skips.</li>
 *   <li>Write fetch_queue.tsv and print stats + the last queued video to
 *       stderr.</li>
 * </ol>
 *
 * <p>Run with the working directory set to ~/workspace/creator_tracker.
 */
public class Walk {

    /** Seeded entries: tickers already captured, never re-queued. */
    static final Set<String> CAPTURED = Set.of("NOW", "LLY", "ORCL", "TTD");
    static final int CONSEC_SKIPS_STOP = 50;

    public static void main(String[] args) throws Exception {
        List<String[]> queue = new ArrayList<>(); // each: {vid, title, wantedCsv}
        int skipped = 0;
        int consecSkips = 0;
        int total = 0;
        int noCandQueued = 0;

        for (String line : textLines(Path.of("worklist.tsv"))) {
            // Python str.split("\t") keeps trailing empties; Java needs limit -1.
            String[] parts = line.split("\t", -1);
            if (parts.length < 2) continue;
            String vid = parts[0];
            String title = parts[1];
            // dict.fromkeys(parts[2].split(",")) filtered to non-empty, order kept.
            List<String> cands = parts.length > 2
                    ? dedupNonEmpty(parts[2].split(",", -1)) : List.of();
            total++;
            List<String> wanted = new ArrayList<>();
            for (String c : cands)
                if (!CAPTURED.contains(c)) wanted.add(c);
            if (!cands.isEmpty() && wanted.isEmpty()) {
                skipped++;
                consecSkips++;
            } else {
                queue.add(new String[]{vid, title, String.join(",", wanted)});
                if (cands.isEmpty()) noCandQueued++;
                consecSkips = 0;
            }
            if (consecSkips >= CONSEC_SKIPS_STOP) break;
        }

        System.err.println("walked=" + total + " queued=" + queue.size()
                + " skipped_dup=" + skipped + " no_candidate_queued=" + noCandQueued);

        try (var w = Files.newBufferedWriter(Path.of("fetch_queue.tsv"), StandardCharsets.UTF_8)) {
            for (String[] r : queue) {
                w.write(r[0] + "\t" + r[1] + "\t" + r[2]);
                w.newLine();
            }
        }

        // Show the tail: last queued video = where the walk stopped.
        if (!queue.isEmpty()) {
            String[] last = queue.get(queue.size() - 1);
            // Python's title[:80] counts Unicode code points, not UTF-16 units.
            System.err.println("last_queued: " + last[0] + " | " + codePointSlice(last[1], 80));
        }
    }

    /** dict.fromkeys(items) with empties removed, insertion order kept. */
    static List<String> dedupNonEmpty(String[] items) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : items)
            if (!s.isEmpty() && seen.add(s)) out.add(s);
        return out;
    }

    /** First n Unicode code points of s (mirrors Python's s[:n]). */
    static String codePointSlice(String s, int n) {
        if (s.codePointCount(0, s.length()) <= n) return s;
        return s.substring(0, s.offsetByCodePoints(0, n));
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
