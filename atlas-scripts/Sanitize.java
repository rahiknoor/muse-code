import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared sanitizer for Atlas vault writes (ported from sanitize.py, 2026-09-27).
 *
 * <p>Every script that writes into ~/workspace/atlas/ must pass its content through
 * {@link #sanitize(String)} immediately before the file write, so junk (JavaScript
 * blobs, HTML widgets, search-widget artifact text like the
 * "No company matches that search" blob once found in Stocks/JNJ.md) can never
 * land in the vault again.
 *
 * <p>Strips (outside fenced code blocks, which are left untouched):
 * <ul>
 *   <li>{@code <script>...</script>}, {@code <iframe>}, {@code <object>},
 *       {@code <embed>}, {@code <applet>}, {@code <style>} blocks with their content
 *   <li>standalone {@code <link>}, {@code <meta>}, {@code <base>} tags
 *   <li>inline event-handler attributes ({@code onclick="..."}, ...)
 *   <li>{@code javascript:} URIs
 *   <li>contiguous HTML widget blocks that contain any dangerous tag above or a
 *       known widget-signature phrase
 *   <li>orphan HTML closing tags (widget residue)
 * </ul>
 * Preserves: markdown, tables, wikilinks, prose, fenced code blocks, benign HTML.
 *
 * <p>Usage:
 * <pre>
 *   String clean = Sanitize.sanitize(dirty);          // returns cleaned text
 *   boolean changed = Sanitize.sanitizeFile("a.md");  // cleans a file in place
 * </pre>
 * Command line (Java 11+ single-file launch, no compile step needed):
 * <pre>
 *   java Sanitize.java --stdin &lt; in.txt &gt; out.txt   // filter mode
 *   java Sanitize.java --batch-stdin                  // many texts, one JVM
 *   java Sanitize.java file1.md file2.md               // clean files in place
 *   java Sanitize.java --self-test                      // run the 12 checks
 * </pre>
 */
public class Sanitize {

    /** Block-level dangerous tags: the whole block, content included, is removed. */
    private static final String[] DANGEROUS_BLOCK_TAGS =
            {"script", "iframe", "object", "embed", "applet", "style"};

    /** Void dangerous tags: only the tag itself is removed. */
    private static final String[] DANGEROUS_VOID_TAGS = {"link", "meta", "base"};

    /** Distinctive artifact strings from UI widgets; never real note content. */
    private static final String[] WIDGET_SIGNATURES = {
        "No company matches that search",
    };

    /** A line starting a fenced code block: ``` or ~~~ (3 or more). */
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})", Pattern.MULTILINE);

    /** A line that "starts with an HTML tag" (used to spot widget line-runs). */
    private static final Pattern TAG_START = Pattern.compile("^\\s*</?[a-zA-Z]");

    /** on*=... attributes inside any tag, e.g. onclick="do()" */
    private static final Pattern EVENT_HANDLER = Pattern.compile(
            "\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
            Pattern.CASE_INSENSITIVE);

    /** javascript: URIs. */
    private static final Pattern JS_URI =
            Pattern.compile("\\bjavascript\\s*:", Pattern.CASE_INSENSITIVE);

    /** Orphan HTML closing tags left over from widgets. */
    private static final Pattern ORPHAN_CLOSE_TAG =
            Pattern.compile("(?m)^\\s*</[a-zA-Z][a-zA-Z0-9]*>\\s*\\n?");

    /** One chunk of text: either a fenced code block (leave alone) or normal text. */
    private static final class Segment {
        final boolean isCode;
        final String text;

        Segment(boolean isCode, String text) {
            this.isCode = isCode;
            this.text = text;
        }
    }

    /**
     * Splits text into code / non-code segments. A fenced block runs from its
     * opening fence line to the next line starting with the same fence characters;
     * an unclosed fence swallows the rest of the text as code. The
     * {@code start < pos} guard skips fence matches that were already consumed
     * as a closing fence (this was a real bug: without it, text after a code
     * block could be misclassified as code).
     */
    private static List<Segment> splitCodeSegments(String text) {
        List<Segment> parts = new ArrayList<>();
        int pos = 0; // end of the last segment we already classified
        Matcher m = FENCE.matcher(text);
        while (m.find()) {
            if (m.start() < pos) {
                continue; // this fence was already consumed as a closing fence
            }
            String fence = m.group(1); // e.g. "```"
            int start = m.start();
            int close = text.indexOf(fence, m.end()); // matching closing fence
            int end = (close == -1) ? text.length() : close + fence.length();
            if (start > pos) {
                parts.add(new Segment(false, text.substring(pos, start)));
            }
            parts.add(new Segment(true, text.substring(start, end)));
            pos = end;
        }
        if (pos < text.length()) {
            parts.add(new Segment(false, text.substring(pos)));
        }
        return parts;
    }

    /** Removes dangerous block tags (with content) and void tags. */
    private static String stripDangerousBlocks(String seg) {
        for (String tag : DANGEROUS_BLOCK_TAGS) {
            // e.g. <script ...>...</script>, case-insensitive, across newlines
            seg = Pattern.compile("<" + tag + "\\b[^>]*>.*?</" + tag + "\\s*>",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(seg).replaceAll("");
        }
        for (String tag : DANGEROUS_VOID_TAGS) {
            seg = Pattern.compile("<" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE)
                    .matcher(seg).replaceAll("");
        }
        return seg;
    }

    /** Removes inline event-handler attributes, keeping the rest of the tag. */
    private static String stripEventHandlers(String seg) {
        return EVENT_HANDLER.matcher(seg).replaceAll("");
    }

    /** Removes javascript: URI prefixes, keeping the rest of the attribute. */
    private static String stripJsUris(String seg) {
        return JS_URI.matcher(seg).replaceAll("");
    }

    /**
     * Removes contiguous HTML line-runs that are widgets, not content.
     *
     * <p>A run is consecutive lines that start with an HTML tag; blank lines may
     * bridge two tag lines, but only when another tag line follows the blanks
     * (this keeps the run from greedily eating adjacent prose). A line of prose
     * that merely mentions &lt;PLACEHOLDERS&gt; mid-line never joins a run. The
     * run is dropped only if it contains a dangerous tag or a known
     * widget-signature phrase; benign runs are kept as-is.
     */
    private static String stripWidgetBlocks(String seg) {
        // split with limit -1 so a trailing newline keeps its empty tail piece,
        // exactly like Python's str.split("\n")
        List<String> lines = Arrays.asList(seg.split("\n", -1));
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = lines.size();
        while (i < n) {
            if (!TAG_START.matcher(lines.get(i)).lookingAt()) {
                out.add(lines.get(i));
                i++;
                continue;
            }
            // Collect the run starting at line i.
            int j = i;
            while (j < n) {
                if (TAG_START.matcher(lines.get(j)).lookingAt()) {
                    j++;
                } else if (lines.get(j).trim().isEmpty()) {
                    // Blank lines bridge the run only if a tag line follows them.
                    int k = j;
                    while (k < n && lines.get(k).trim().isEmpty()) {
                        k++;
                    }
                    if (k < n && TAG_START.matcher(lines.get(k)).lookingAt()) {
                        j = k;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            String blob = String.join("\n", lines.subList(i, j));
            boolean dangerous = false;
            for (String t : concat(DANGEROUS_BLOCK_TAGS, DANGEROUS_VOID_TAGS)) {
                if (Pattern.compile("<" + t + "\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(blob).find()) {
                    dangerous = true;
                    break;
                }
            }
            boolean signature = false;
            for (String s : WIDGET_SIGNATURES) {
                if (blob.contains(s)) {
                    signature = true;
                    break;
                }
            }
            if (dangerous || signature) {
                i = j; // drop the whole widget block
                continue;
            }
            out.addAll(lines.subList(i, j));
            i = j;
        }
        return String.join("\n", out);
    }

    /** Removes whole lines containing a widget signature, plus orphan close tags. */
    private static String stripSignatureLines(String seg) {
        for (String sig : WIDGET_SIGNATURES) {
            seg = Pattern.compile("(?m)^[^\\n]*" + Pattern.quote(sig) + "[^\\n]*\\n?")
                    .matcher(seg).replaceAll("");
        }
        seg = ORPHAN_CLOSE_TAG.matcher(seg).replaceAll("");
        return seg;
    }

    /**
     * Raw JavaScript pasted as plain text (no {@code <script>} tags, so the
     * block strippers miss it — the 2026-09-27 JNJ.md leak). A line is a
     * telltale if it references browser DOM APIs; brace-only/blank lines may
     * bridge telltales. A run is dropped only when it holds 2+ telltale
     * lines, so a prose sentence merely mentioning "document" survives.
     */
    private static final Pattern RAW_JS_TELLTALE = Pattern.compile(
            "document\\.|window\\.|addEventListener|querySelector|"
            + "getElementById|getElementsBy|\\.textContent|\\.innerHTML");

    private static String stripRawJsBlocks(String seg) {
        List<String> lines = Arrays.asList(seg.split("\n", -1));
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = lines.size();
        while (i < n) {
            if (!RAW_JS_TELLTALE.matcher(lines.get(i)).find()) {
                out.add(lines.get(i));
                i++;
                continue;
            }
            int j = i;
            int telltales = 0;
            while (j < n) {
                String t = lines.get(j).trim();
                if (RAW_JS_TELLTALE.matcher(lines.get(j)).find()) {
                    telltales++;
                    j++;
                } else if (t.isEmpty() || t.matches("[{}();,]+")) {
                    // Blank/brace-only lines bridge the run only if another
                    // telltale follows them.
                    int k = j;
                    while (k < n) {
                        String u = lines.get(k).trim();
                        if (u.isEmpty() || u.matches("[{}();,]+")) {
                            k++;
                        } else {
                            break;
                        }
                    }
                    if (k < n && RAW_JS_TELLTALE.matcher(lines.get(k)).find()) {
                        j = k;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            if (telltales >= 2) {
                i = j; // drop the raw-JS run
            } else {
                out.add(lines.get(i));
                i++;
            }
        }
        return String.join("\n", out);
    }

    /** Small helper: join two string arrays. */
    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /**
     * Removes script/widget junk from note text; leaves real content intact.
     * Fenced code blocks pass through untouched.
     */
    public static String sanitize(String text) {
        StringBuilder out = new StringBuilder();
        for (Segment part : splitCodeSegments(text)) {
            if (part.isCode) {
                out.append(part.text);
                continue;
            }
            String seg = part.text;
            seg = stripDangerousBlocks(seg);
            seg = stripEventHandlers(seg);
            seg = stripJsUris(seg);
            seg = stripWidgetBlocks(seg);
            seg = stripSignatureLines(seg);
            seg = stripRawJsBlocks(seg);
            out.append(seg);
        }
        return out.toString();
    }

    /**
     * Sanitizes a vault file in place. Returns true if the file changed.
     */
    public static boolean sanitizeFile(String path) throws IOException {
        Path p = Paths.get(path);
        String original = Files.readString(p, StandardCharsets.UTF_8);
        String cleaned = sanitize(original);
        if (!cleaned.equals(original)) {
            Files.writeString(p, cleaned, StandardCharsets.UTF_8);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Self-test: checks mirroring the original Python test battery.
    // Run with: java --source-path ~/workspace/java-shared Sanitize.java --self-test
    // Assertions live in the shared SelfTest harness (~/workspace/java-shared).
    // ------------------------------------------------------------------

    private static void check(String name, String input,
                              String[] mustContain, String[] mustNotContain) {
        SelfTest.checkOutput(name, sanitize(input), mustContain, mustNotContain);
    }

    private static void runSelfTest() throws IOException {
        // 1. <script> block removed, neighbors kept
        check("script block removed",
                "<p>Hello</p>\n<script>alert('x');</script>\nWorld",
                new String[]{"Hello", "World"}, new String[]{"alert", "<script"});

        // 2. iframe / style / object blocks removed
        check("iframe/style/object blocks removed",
                "<iframe src=\"x\"></iframe>\n<style>.a{color:red}</style>\n<object data=\"y\"></object>\nKeep me",
                new String[]{"Keep me"}, new String[]{"iframe", "<style", "<object"});

        // 3. void tags (link/meta/base) removed
        check("void tags removed",
                "<link rel=\"stylesheet\" href=\"a.css\">\n<meta charset=\"utf-8\">\n<base href=\"/\">\nKeep me",
                new String[]{"Keep me"}, new String[]{"<link", "<meta", "<base"});

        // 4. event handler stripped, rest of tag kept
        check("event handler stripped",
                "<div onclick=\"steal()\" class=\"note\">Text</div>",
                new String[]{"Text", "class=\"note\""}, new String[]{"onclick"});

        // 5. javascript: URI stripped
        check("javascript: URI stripped",
                "<a href=\"javascript:evil()\">Click</a>",
                new String[]{"Click", "evil()"}, new String[]{"javascript:"});

        // 6. widget block with signature phrase dropped (JNJ case)
        check("widget signature block dropped",
                "<div class=\"search-widget\">\n<input type=\"text\">\nNo company matches that search\n</div>\n\n# Notes",
                new String[]{"# Notes"}, new String[]{"No company matches"});

        // 7. fenced code block left untouched, even with <script> inside
        check("fenced code untouched",
                "```\n<script>alert('x');</script>\n```\nAfter",
                new String[]{"<script>alert('x');</script>", "After"}, new String[]{});

        // 8. mid-line <PLACEHOLDER> mention preserved
        check("mid-line placeholder preserved",
                "Use <PLACEHOLDER> here",
                new String[]{"Use <PLACEHOLDER> here"}, new String[]{});

        // 9. benign HTML run (no dangerous tags) kept as-is
        check("benign HTML run kept",
                "<div>\nPlain text\n</div>\n# After",
                new String[]{"Plain text", "<div>"}, new String[]{});

        // 10. orphan closing tag removed
        check("orphan closing tag removed",
                "Some text\n</div>\n# More",
                new String[]{"Some text", "# More"}, new String[]{"</div>"});

        // 11. unclosed fence: rest of text treated as code, left alone
        check("unclosed fence treated as code",
                "```\ncode <script>x</script>\nno closing fence",
                new String[]{"<script>x</script>"}, new String[]{});

        // 12. content after a closed code block is NOT swallowed as code
        // (regression test for the fence-matching bug)
        check("post-fence content preserved",
                "```\ncode\n```\n<script>bad()</script>\nTail",
                new String[]{"Tail"}, new String[]{"bad()"});

        // 13. raw JS pasted as plain text dropped (JNJ.md leak, 2026-09-27);
        // single prose mention of "document" survives
        check("raw JS block dropped",
                "Filing history\n- (function () {\n      var search = document.getElementById('x');\n      search.addEventListener('input', applyFilter);\n    }());\n## Sources",
                new String[]{"Filing history", "## Sources"},
                new String[]{"getElementById", "addEventListener"});
        check("prose mention of document kept",
                "Read the document carefully before answering.",
                new String[]{"Read the document carefully before answering."}, new String[]{});

        // Batch protocol framing round-trips through plain byte arrays
        // (no child process needed: same read/write methods runBatch uses).
        String dirty = "hello [world](https://x.com) «bad»\n<script>evil()</script>";
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        DataOutputStream w = new DataOutputStream(req);
        byte[] db = dirty.getBytes(StandardCharsets.UTF_8);
        w.writeBytes("SANITIZE");
        w.writeInt(db.length);
        w.write(db);
        w.writeBytes("SANITIZE");
        w.writeInt(0); // empty item
        w.flush();
        DataInputStream r =
                new DataInputStream(new ByteArrayInputStream(req.toByteArray()));
        SelfTest.checkEq("batch item survives framing",
                sanitize(dirty), sanitize(readBatchItem(r)));
        SelfTest.checkEq("batch empty item", "", readBatchItem(r));
        SelfTest.check("batch EOF -> null", readBatchItem(r) == null);
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        writeBatchItem(new DataOutputStream(resp), sanitize(dirty));
        DataInputStream rr =
                new DataInputStream(new ByteArrayInputStream(resp.toByteArray()));
        byte[] respMagic = new byte[2];
        rr.readFully(respMagic);
        SelfTest.checkEq("batch response magic",
                "OK", new String(respMagic, StandardCharsets.US_ASCII));
        int respLen = rr.readInt();
        byte[] respBuf = new byte[respLen];
        rr.readFully(respBuf);
        SelfTest.checkEq("batch response payload",
                sanitize(dirty), new String(respBuf, StandardCharsets.UTF_8));

        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // Batch protocol (--batch-stdin), added 2026-09-28.
    //
    // Lets a caller sanitize many texts in ONE JVM instead of launching
    // `java Sanitize.java --stdin` per text (RefreshValuations does 31).
    // Framing is length-prefixed UTF-8 so note bodies may contain anything:
    //   request  = "SANITIZE" (8 ASCII bytes) + int32 BE length + UTF-8 bytes
    //   response = "OK" (2 ASCII bytes) + int32 BE length + sanitized UTF-8
    // The client sends requests back-to-back and reads one response per
    // request; closing stdin (EOF) ends the session. Sanitizer rules are
    // untouched — this is only transport.
    // ------------------------------------------------------------------

    /** Reads one request; returns null on clean EOF (client closed stdin). */
    static String readBatchItem(DataInputStream in) throws IOException {
        byte[] magic = new byte[8];
        try {
            in.readFully(magic);
        } catch (EOFException e) {
            return null;
        }
        if (!"SANITIZE".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("bad batch magic");
        }
        int len = in.readInt();
        if (len < 0 || len > 50_000_000) {
            throw new IOException("bad batch length " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    /** Writes one sanitized response. */
    static void writeBatchItem(DataOutputStream out, String sanitized) throws IOException {
        byte[] b = sanitized.getBytes(StandardCharsets.UTF_8);
        out.writeBytes("OK");
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }

    /** --batch-stdin entry point: sanitize items until EOF. Uses raw System.out. */
    static void runBatch() throws IOException {
        DataInputStream in = new DataInputStream(System.in);
        DataOutputStream out = new DataOutputStream(System.out);
        String item;
        while ((item = readBatchItem(in)) != null) {
            writeBatchItem(out, sanitize(item));
        }
    }

    public static void main(String[] args) throws IOException {
        // Always write UTF-8 explicitly: the default console charset may be ASCII
        // when LANG is unset, which would corrupt non-ASCII note text (é, ☕, …).
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length == 1 && args[0].equals("--stdin")) {
            // Filter mode: read all of stdin, write sanitized text to stdout.
            String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            out.print(sanitize(input));
            return;
        }
        if (args.length == 1 && args[0].equals("--batch-stdin")) {
            // Batch mode: length-prefixed items on stdin, responses on stdout.
            runBatch();
            return;
        }
        if (args.length == 1 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        boolean changedAny = false;
        for (String path : args) {
            if (sanitizeFile(path)) {
                out.println("cleaned: " + path);
                changedAny = true;
            }
        }
        if (!changedAny) {
            out.println("no changes needed");
        }
    }
}
