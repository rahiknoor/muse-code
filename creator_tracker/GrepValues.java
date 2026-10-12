import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grep fetched transcripts for value-statement patterns; print matches with context.
 *
 * <p>Usage: {@code java GrepValues.java} (no arguments; reads files in the
 * working directory, like the Python original).
 *
 * <p>Steps (mirrors grep_values.py):
 * <ol>
 *   <li>Load batch_meta.tsv: video id &rarr; (title, wanted tickers).</li>
 *   <li>Load upload_dates.tsv: video id &rarr; upload date.</li>
 *   <li>For every transcripts/*.vtt and transcripts/*.srt (alphabetical):
 *       strip subtitle timestamps and HTML tags, collapse whitespace,
 *       then find all value-statement pattern matches.</li>
 *   <li>Print each match with &plusmn;160 chars of context, deduped by the
 *       first 80 chars of the context window.</li>
 *   <li>Print TOTAL_MATCHES to stderr.</li>
 * </ol>
 *
 * <p>Transcripts are read with undecodable bytes dropped, matching Python's
 * {@code open(..., errors="ignore")}.
 */
public class GrepValues {

    // Same 13 value-statement patterns as the Python version, joined with '|'.
    // In Java source each regex backslash is doubled.
    static final String[] PATTERNS = {
        "fair value.{0,80}\\$[\\d,]+",
        "\\$[\\d,]+.{0,60}fair value",
        "intrinsic value.{0,80}\\$[\\d,]+",
        "\\$[\\d,]+.{0,60}intrinsic value",
        "intrinsic.{0,40}\\$[\\d,]+",
        "price target.{0,80}\\$[\\d,]+",
        "\\$[\\d,]+.{0,60}price target",
        "my target.{0,60}\\$[\\d,]+",
        "valuation.{0,60}\\$[\\d,]+",
        "\\$[\\d,]+.{0,40}valuation",
        "worth \\$[\\d,]+",
        "i (?:think|believe) (?:it's|its) worth \\$[\\d,]+",
        "dcf.{0,60}\\$[\\d,]+",
    };

    /**
     * Case-insensitive like Python's re.I. UNICODE_CASE + UNICODE_CHARACTER_CLASS
     * make \d/\s behave the Unicode-aware way Python's re does on str.
     */
    static final Pattern RX = Pattern.compile(String.join("|", PATTERNS),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    static final Pattern TIMESTAMP_LINE =
            Pattern.compile("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3} --> .*");
    static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern WS_SPLIT =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Strip subtitle timestamps, HTML tags, and collapse whitespace (mirrors clean()). */
    static String clean(String text) {
        // e.g. "00:01:23,456 --> 00:01:26,789"  (".*" stops at end of line, no DOTALL)
        text = TIMESTAMP_LINE.matcher(text).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = WHITESPACE.matcher(text).replaceAll(" ");
        return text;
    }

    /** Read a file as UTF-8, silently dropping undecodable bytes (errors="ignore"). */
    static String readLenient(Path path) throws Exception {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE);
        return dec.decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
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

    /** First n Unicode code points of s (Python's s[:n] counts code points, not UTF-16 units). */
    static String codePointSlice(String s, int n) {
        if (s.codePointCount(0, s.length()) <= n) return s;
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) if (a.equals("--self-test")) { selfTest(); return; }
        // Optional: --creator parkev|jeremy|couch (default parkev).
        // Selects the per-creator pattern set from CreatorPatterns.
        String creator = "parkev";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--creator")) creator = args[i + 1];
        }
        CreatorPatterns.Pat[] pats = CreatorPatterns.forCreator(creator);
        Pattern rx = CreatorPatterns.combined(pats);
        System.err.println("CreatorPatterns: " + creator + " (" + pats.length + " patterns)");

        // batch_meta.tsv: vid -> (title, wanted tickers)
        Map<String, String[]> metas = new LinkedHashMap<>();
        Path metaPath = Path.of("batch_meta.tsv");
        if (Files.exists(metaPath)) {
            for (String line : textLines(metaPath)) {
                // Python str.split("\t") keeps trailing empties; Java needs limit -1.
                String[] p = line.split("\t", -1);
                metas.put(p[0], new String[]{p[1], p.length > 2 ? p[2] : ""});
            }
        }

        // upload_dates.tsv: vid -> upload date (whitespace-separated pairs)
        Map<String, String> dates = new LinkedHashMap<>();
        Path datesPath = Path.of("upload_dates.tsv");
        if (Files.exists(datesPath)) {
            for (String line : textLines(datesPath)) {
                // Python's line.strip().split(): split on whitespace runs.
                String[] p = WS_SPLIT.split(line.strip());
                if (p.length == 2) dates.put(p[0], p[1]);
            }
        }

        // sorted(glob("transcripts/*.vtt") + glob("transcripts/*.srt"))
        List<String> tpaths = new ArrayList<>();
        for (String ext : new String[]{"vtt", "srt"}) {
            try (var dir = Files.newDirectoryStream(Path.of("transcripts"), "*." + ext)) {
                for (Path q : dir)
                    tpaths.add("transcripts/" + q.getFileName().toString());
            } catch (NoSuchFileException e) {
                // Python's glob returns [] for a missing dir; do the same.
            }
        }
        tpaths.sort(String::compareTo);

        int hits = 0;
        for (String tp : tpaths) {
            // basename, then the part before the first "." (e.g. "abc123.en.vtt" -> "abc123")
            String name = Path.of(tp).getFileName().toString();
            String vid = name.split("\\.")[0];
            String text = clean(readLenient(Path.of(tp)));
            Set<String> seen = new LinkedHashSet<>();
            Matcher m = rx.matcher(text);
            while (m.find()) {
                int s = Math.max(0, m.start() - 160);
                int e = Math.min(text.length(), m.end() + 160);
                String ctx = text.substring(s, e).strip();
                String key = codePointSlice(ctx, 80);
                if (!seen.add(key)) continue; // dedupe by first 80 chars of context
                String[] meta = metas.getOrDefault(vid, new String[]{"?", "?"});
                String sugg = CreatorPatterns.suggestedType(pats, m.group());
                // Matches Python's print(f"=== ...\n    ...\n    ...{ctx}...\n") exactly.
                System.out.println("=== " + vid + " | " + dates.getOrDefault(vid, "?")
                        + " | wanted:" + meta[1]
                        + " | suggested:" + sugg
                        + "\n    " + meta[0]
                        + "\n    ..." + ctx + "...\n");
                hits++;
            }
        }
        System.err.println("\nTOTAL_MATCHES=" + hits);
    }

    /** Self-test for the per-creator patterns: positives must match, loose language must not. */
    static void selfTest() {
        int pass = 0, total = 0;

        // Jeremy: explicit target matches
        total++; if (t("jeremy", "my price target is $250", true)) pass++; else System.out.println("FAIL jeremy-target");
        total++; if (t("jeremy", "I think it's worth $180 per share", true)) pass++; else System.out.println("FAIL jeremy-worth");
        total++; if (t("jeremy", "I'd buy up to $90 on this one", true)) pass++; else System.out.println("FAIL jeremy-buylimit");
        // Jeremy: qualitative language does NOT match
        total++; if (t("jeremy", "Celsius has the most upside potential", false)) pass++; else System.out.println("FAIL jeremy-qual1");
        total++; if (t("jeremy", "ELF has the best risk-reward profile", false)) pass++; else System.out.println("FAIL jeremy-qual2");

        // Couch: buy price matches
        total++; if (t("couch", "I purchased 43 shares at $352", true)) pass++; else System.out.println("FAIL couch-buy");
        total++; if (t("couch", "I paid $120 per share", true)) pass++; else System.out.println("FAIL couch-paid");
        // Couch: position totals do NOT match (no at-price)
        total++; if (t("couch", "I bought $15,000 of stock last week", false)) pass++; else System.out.println("FAIL couch-position");

        // Parkev: still matches (backward compat)
        total++; if (t("parkev", "I updated my fair value estimate of Oracle to $131", true)) pass++; else System.out.println("FAIL parkev-fv");
        total++; if (t("parkev", "my intrinsic value is $1494", true)) pass++; else System.out.println("FAIL parkev-iv");

        System.out.println(pass + " passed, " + (total - pass) + " failed");
        if (pass != total) System.exit(1);
    }

    static boolean t(String creator, String text, boolean expect) {
        CreatorPatterns.Pat[] pats = CreatorPatterns.forCreator(creator);
        boolean found = CreatorPatterns.combined(pats).matcher(text).find();
        return found == expect;
    }
}
