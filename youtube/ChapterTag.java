import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chapter-tagged value extraction for the YouTube digest.
 *
 * <p>When pulling transcripts, also pull the video's chapter markers and tag
 * each extracted creator value / target / buy-call with the chapter it came
 * from, so the digest can cite "in the 'Valuation' chapter" instead of a raw
 * timestamp. Tracker entries carry {@code chapter} (string, when resolvable)
 * and {@code timestamp_sec} (number, when a timestamp is known).
 *
 * <p>Chapter sources, in order:
 * <ol>
 *   <li>yt-dlp {@code --print chapters} output ({@code --fetch} mode) — the
 *       timedtext equivalent; degrades gracefully when YouTube 429s/bot-checks
 *       the datacenter IP (prints CHAPTERS_UNAVAILABLE, exit 3).</li>
 *   <li>Chapter lines in the video description ({@code 0:00 Intro} style),
 *       parsed offline from the shared cache ({@code --chapters} mode).</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   java ChapterTag.java --chapters &lt;videoId&gt;   # chapters from cached description
 *   java ChapterTag.java --fetch &lt;videoId&gt;      # chapters via yt-dlp (may be unavailable)
 *   java ChapterTag.java --tag &lt;tracker.json&gt;   # backfill chapter/timestamp_sec on entries
 *   java ChapterTag.java --self-test
 * </pre>
 *
 * <p>{@code --tag} only touches entries whose notes contain a timestamp
 * ({@code ~5:48 mark} style); entries that cannot be resolved are left
 * untouched — unknown stays unknown, never invented.
 */
public class ChapterTag {

    /** One chapter marker. */
    public record Chapter(double startSec, String title) {}

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    // ------------------------------------------------------------------
    // Chapter sources
    // ------------------------------------------------------------------

    /** Chapter line: "0:00 Intro", "12:34 - Valuation", "(1:02:03) Q&A". */
    static final Pattern CHAPTER_LINE = Pattern.compile(
            "^\\s*\\(?((?:\\d{1,3}:)?\\d{1,3}:\\d{2})\\)?\\s*[-\\u2013\\u2014:.]?\\s*(\\S.*?)\\s*$");

    /**
     * Parse chapter markers from a video description. Needs at least 3 valid
     * lines with the first starting at 0:00, otherwise returns an empty list
     * (a couple of stray timestamps are not chapters).
     */
    public static List<Chapter> fromDescription(String description) {
        List<Chapter> chapters = new ArrayList<>();
        if (description == null) return chapters;
        for (String line : description.split("\n")) {
            Matcher m = CHAPTER_LINE.matcher(line);
            if (!m.matches()) continue;
            double sec = parseTimestamp(m.group(1));
            String title = m.group(2).strip();
            if (sec < 0 || title.isEmpty() || title.length() > 120) continue;
            if (!chapters.isEmpty() && sec <= chapters.get(chapters.size() - 1).startSec()) continue;
            chapters.add(new Chapter(sec, title));
        }
        if (chapters.size() < 3) return List.of();
        if (chapters.get(0).startSec() != 0) return List.of();
        return chapters;
    }

    /**
     * Parse yt-dlp {@code --print chapters} output. yt-dlp prints one JSON
     * object per line: {@code {"start_time": 0.0, "end_time": 123.4, "title": "..."}}.
     * A single JSON array is accepted too.
     */
    @SuppressWarnings("unchecked")
    public static List<Chapter> fromYtDlp(String out) {
        List<Chapter> chapters = new ArrayList<>();
        if (out == null || out.isBlank()) return chapters;
        String t = out.strip();
        List<Object> items = new ArrayList<>();
        try {
            if (t.startsWith("[")) {
                items.addAll((List<Object>) Mini.parse(t));
            } else {
                for (String line : t.split("\n")) {
                    line = line.strip();
                    if (line.startsWith("{")) items.add(Mini.parse(line));
                }
            }
        } catch (Exception e) {
            return chapters;
        }
        for (Object o : items) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            Object s = m.containsKey("start_time") ? m.get("start_time") : m.get("start");
            Object title = m.get("title");
            double sec = num(s);
            if (sec < 0 || !(title instanceof String) || ((String) title).isBlank()) continue;
            chapters.add(new Chapter(sec, ((String) title).strip()));
        }
        chapters.sort((a, b) -> Double.compare(a.startSec(), b.startSec()));
        return chapters;
    }

    // ------------------------------------------------------------------
    // Timestamp <-> chapter mapping
    // ------------------------------------------------------------------

    /**
     * Parse "5:48", "1:02:03", "[5:48]" into seconds; -1 when unparseable.
     */
    public static double parseTimestamp(String s) {
        if (s == null) return -1;
        s = s.strip().replaceAll("^[\\[(]", "").replaceAll("[\\])]$", "");
        String[] p = s.split(":");
        try {
            if (p.length == 2) return Integer.parseInt(p[0]) * 60L + Double.parseDouble(p[1]);
            if (p.length == 3)
                return Integer.parseInt(p[0]) * 3600L + Integer.parseInt(p[1]) * 60L
                        + Double.parseDouble(p[2]);
        } catch (NumberFormatException e) { /* fall through */ }
        return -1;
    }

    /** Format seconds as "m:ss" (or "h:mm:ss"). */
    public static String formatTimestamp(double seconds) {
        long s = Math.round(seconds);
        long h = s / 3600, m = (s % 3600) / 60, r = s % 60;
        return h > 0 ? h + ":" + String.format("%02d", m) + ":" + String.format("%02d", r)
                     : m + ":" + String.format("%02d", r);
    }

    /**
     * The chapter containing {@code seconds}: the last chapter starting at or
     * before it. Null when there are no chapters or it precedes the first.
     */
    public static String chapterAt(List<Chapter> chapters, double seconds) {
        String hit = null;
        for (Chapter c : chapters) {
            if (c.startSec() <= seconds) hit = c.title();
            else break;
        }
        return hit;
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof Mini.RawNum r) {
            try { return Double.parseDouble(r.raw()); } catch (NumberFormatException e) { return -1; }
        }
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // CLI
    // ------------------------------------------------------------------

    static final Pattern VIDEO_ID = Pattern.compile("[?&]v=([\\w-]{11})|youtu\\.be/([\\w-]{11})");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.err.println("usage: --chapters|--fetch|--tag|--self-test"); System.exit(2); }
        switch (args[0]) {
            case "--self-test" -> selfTest();
            case "--chapters" -> {
                if (args.length < 2) die("--chapters needs a videoId");
                for (Chapter c : chaptersFor(args[1]))
                    System.out.println(formatTimestamp(c.startSec()) + "  " + c.title());
            }
            case "--fetch" -> {
                if (args.length < 2) die("--fetch needs a videoId");
                List<Chapter> chapters = fetchChapters(args[1]);
                if (chapters == null) {
                    System.err.println("CHAPTERS_UNAVAILABLE: yt-dlp blocked (429/bot-check)");
                    System.exit(3);
                }
                for (Chapter c : chapters)
                    System.out.println(formatTimestamp(c.startSec()) + "  " + c.title());
            }
            case "--tag" -> {
                if (args.length < 2) die("--tag needs a tracker json path");
                tagTracker(Path.of(args[1]));
            }
            default -> die("unknown: " + args[0]);
        }
    }

    static void die(String msg) {
        System.err.println(msg);
        System.exit(2);
    }

    /** Chapters for a cached video: description parse (offline). */
    static List<Chapter> chaptersFor(String videoId) throws Exception {
        Path cache = Path.of(home(), "workspace", "youtube", "cache", "data", videoId + ".json");
        if (!Files.isRegularFile(cache)) return List.of();
        Object root = Mini.parse(Files.readString(cache, StandardCharsets.UTF_8));
        if (!(root instanceof Map)) return List.of();
        Object desc = ((Map<?, ?>) root).get("description");
        return fromDescription(desc instanceof String ? (String) desc : null);
    }

    /**
     * Chapters via yt-dlp. Returns null when YouTube blocks the fetch
     * (429 / bot-check on this network) — callers must degrade gracefully.
     */
    static List<Chapter> fetchChapters(String videoId) {
        try {
            Process p = new ProcessBuilder("yt-dlp", "--print", "chapters", "--skip-download",
                    "--no-warnings", "https://www.youtube.com/watch?v=" + videoId)
                    .redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = p.waitFor() == 0;
            List<Chapter> chapters = fromYtDlp(out);
            if (!ok || chapters.isEmpty()) {
                if (err.contains("429") || err.contains("bot") || err.contains("Sign in")) return null;
            }
            return chapters;
        } catch (Exception e) {
            return null;
        }
    }

    /** Timestamp-in-notes pattern: "~5:48 mark", "at 12:03 minute", "1:02:03 timestamp". */
    static final Pattern NOTE_TS = Pattern.compile(
            "~?\\(?((?:\\d{1,3}:)?\\d{1,3}:\\d{2})\\)?\\s*(?:mark|minute|timestamp)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Backfill {@code chapter} / {@code timestamp_sec} on tracker entries.
     * Only entries with a timestamp in notes are touched; unresolvable
     * chapters stay absent (unknown, never invented). The file is rewritten
     * only when at least one entry changed, and only after a byte-identical
     * parse→dump round-trip proves the serializer matches the file format.
     */
    @SuppressWarnings("unchecked")
    static void tagTracker(Path tracker) throws Exception {
        String original = Files.readString(tracker, StandardCharsets.UTF_8);
        Object root = Mini.parse(original);
        if (!(root instanceof Map) || !(((Map<?, ?>) root).get("entries") instanceof List))
            die("not a tracker file: " + tracker);
        // Round-trip safety: the serializer must reproduce the file byte-for-byte
        // before we trust it with a rewrite.
        if (!Mini.dump(root, 1).equals(original))
            die("refusing rewrite: parse→dump round-trip differs (format drift)");

        List<Object> entries = (List<Object>) ((Map<?, ?>) root).get("entries");
        int tagged = 0, tsOnly = 0, skipped = 0;
        for (Object e : entries) {
            if (!(e instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) e;
            if (m.containsKey("chapter") && m.containsKey("timestamp_sec")) continue;
            String notes = m.get("notes") instanceof String ? (String) m.get("notes") : "";
            Matcher tm = NOTE_TS.matcher(notes);
            if (!tm.find()) { skipped++; continue; }
            double sec = parseTimestamp(tm.group(1));
            if (sec < 0) { skipped++; continue; }
            String id = videoIdOf(m.get("video_url") instanceof String ? (String) m.get("video_url") : "");
            String chapter = null;
            if (id != null) {
                try { chapter = chapterAt(chaptersFor(id), sec); }
                catch (Exception ex) { chapter = null; }
            }
            m.put("timestamp_sec", Mini.rawNum(sec == Math.rint(sec) ? String.valueOf((long) sec)
                                                                     : String.valueOf(sec)));
            if (chapter != null) { m.put("chapter", chapter); tagged++; }
            else tsOnly++;
        }
        if (tagged + tsOnly == 0) {
            System.out.println("no entries with resolvable timestamps; file untouched");
            return;
        }
        Files.writeString(tracker, Mini.dump(root, 1), StandardCharsets.UTF_8);
        System.out.println("tagged=" + tagged + " timestamp_only=" + tsOnly + " skipped=" + skipped);
    }

    static String videoIdOf(String url) {
        if (url == null) return null;
        Matcher m = VIDEO_ID.matcher(url);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    // ------------------------------------------------------------------
    // Minimal JSON (raw-number preserving, MiniJson.dump-compatible).
    // ------------------------------------------------------------------
    static final class Mini {
        /** Raw number text, written back verbatim (like MiniJson.Num). */
        record RawNum(String raw) {}
        static RawNum rawNum(String raw) { return new RawNum(raw); }

        static Object parse(String text) {
            int[] p = {0};
            ws(text, p);
            Object v = value(text, p);
            ws(text, p);
            if (p[0] != text.length()) throw new IllegalArgumentException("trailing data at " + p[0]);
            return v;
        }

        private static void ws(String t, int[] p) {
            while (p[0] < t.length() && " \t\n\r".indexOf(t.charAt(p[0])) >= 0) p[0]++;
        }

        private static Object value(String t, int[] p) {
            ws(t, p);
            char c = t.charAt(p[0]);
            return switch (c) {
                case '{' -> obj(t, p);
                case '[' -> arr(t, p);
                case '"' -> str(t, p);
                case 't' -> { expect(t, p, "true"); yield Boolean.TRUE; }
                case 'f' -> { expect(t, p, "false"); yield Boolean.FALSE; }
                case 'n' -> { expect(t, p, "null"); yield null; }
                default -> num(t, p);
            };
        }

        private static void expect(String t, int[] p, String s) {
            if (!t.startsWith(s, p[0])) throw new IllegalArgumentException("bad literal at " + p[0]);
            p[0] += s.length();
        }

        private static Map<String, Object> obj(String t, int[] p) {
            p[0]++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws(t, p);
            if (t.charAt(p[0]) == '}') { p[0]++; return m; }
            while (true) {
                ws(t, p);
                String k = str(t, p);
                ws(t, p);
                if (t.charAt(p[0]) != ':') throw new IllegalArgumentException("expected : at " + p[0]);
                p[0]++;
                m.put(k, value(t, p));
                ws(t, p);
                char c = t.charAt(p[0]);
                if (c == ',') { p[0]++; continue; }
                if (c == '}') { p[0]++; return m; }
                throw new IllegalArgumentException("expected , or } at " + p[0]);
            }
        }

        private static List<Object> arr(String t, int[] p) {
            p[0]++;
            List<Object> l = new ArrayList<>();
            ws(t, p);
            if (t.charAt(p[0]) == ']') { p[0]++; return l; }
            while (true) {
                l.add(value(t, p));
                ws(t, p);
                char c = t.charAt(p[0]);
                if (c == ',') { p[0]++; continue; }
                if (c == ']') { p[0]++; return l; }
                throw new IllegalArgumentException("expected , or ] at " + p[0]);
            }
        }

        private static String str(String t, int[] p) {
            p[0]++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = t.charAt(p[0]++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = t.charAt(p[0]++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(t.substring(p[0], p[0] + 4), 16));
                            p[0] += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape");
                    }
                } else sb.append(c);
            }
        }

        private static RawNum num(String t, int[] p) {
            int s = p[0];
            while (p[0] < t.length() && "-+0123456789.eE".indexOf(t.charAt(p[0])) >= 0) p[0]++;
            return new RawNum(t.substring(s, p[0]));
        }

        /** Serialize with indent spaces per level, mirroring MiniJson.dump. */
        static String dump(Object v, int indent) {
            StringBuilder sb = new StringBuilder();
            write(v, sb, indent, 0);
            return sb.toString();
        }

        private static void write(Object v, StringBuilder sb, int indent, int level) {
            if (v == null) sb.append("null");
            else if (v instanceof String s) writeStr(s, sb);
            else if (v instanceof RawNum n) sb.append(n.raw());
            else if (v instanceof Boolean) sb.append(v.toString());
            else if (v instanceof List<?> l) {
                if (l.isEmpty()) { sb.append("[]"); return; }
                sb.append("[\n");
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(",\n");
                    sb.append(" ".repeat(indent * (level + 1)));
                    write(l.get(i), sb, indent, level + 1);
                }
                sb.append("\n").append(" ".repeat(indent * level)).append("]");
            } else if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) { sb.append("{}"); return; }
                sb.append("{\n");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(",\n");
                    first = false;
                    sb.append(" ".repeat(indent * (level + 1)));
                    writeStr((String) e.getKey(), sb);
                    sb.append(": ");
                    write(e.getValue(), sb, indent, level + 1);
                }
                sb.append("\n").append(" ".repeat(indent * level)).append("}");
            } else throw new IllegalArgumentException("cannot serialize " + v.getClass());
        }

        /** Escape like Python's json with ensure_ascii=True (MiniJson-compatible). */
        private static void writeStr(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                switch (cp) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (cp < 0x20 || cp > 0x7E) {
                            if (cp <= 0xFFFF) sb.append(String.format("\\u%04x", cp));
                            else for (char c : Character.toChars(cp))
                                sb.append(String.format("\\u%04x", (int) c));
                        } else sb.appendCodePoint(cp);
                }
            }
            sb.append('"');
        }
    }

    // ------------------------------------------------------------------
    // Self-test
    // ------------------------------------------------------------------
    static void selfTest() throws Exception {
        int pass = 0, fail = 0;

        // parseTimestamp
        if (parseTimestamp("5:48") == 348) pass++; else { fail++; System.out.println("FAIL ts 5:48"); }
        if (parseTimestamp("[1:02:03]") == 3723) pass++; else { fail++; System.out.println("FAIL ts hms"); }
        if (parseTimestamp("nope") == -1) pass++; else { fail++; System.out.println("FAIL ts bad"); }
        if (formatTimestamp(348).equals("5:48")) pass++; else { fail++; System.out.println("FAIL fmt"); }
        if (formatTimestamp(3723).equals("1:02:03")) pass++; else { fail++; System.out.println("FAIL fmt h"); }

        // fromDescription
        String desc = "Some intro line\n0:00 Intro\n5:30 Valuation\n12:15 Risks\n20:00 Q&A\n";
        List<Chapter> ch = fromDescription(desc);
        if (ch.size() == 4 && ch.get(1).title().equals("Valuation")) pass++;
        else { fail++; System.out.println("FAIL desc chapters: " + ch); }
        // too few lines -> not chapters
        if (fromDescription("0:00 hi\n5:00 there\n").isEmpty()) pass++;
        else { fail++; System.out.println("FAIL desc min"); }
        // first not at zero -> not chapters
        if (fromDescription("1:00 a\n2:00 b\n3:00 c\n").isEmpty()) pass++;
        else { fail++; System.out.println("FAIL desc zero"); }

        // chapterAt
        if ("Valuation".equals(chapterAt(ch, 348))) pass++;
        else { fail++; System.out.println("FAIL chapterAt 348"); }
        if ("Intro".equals(chapterAt(ch, 0))) pass++;
        else { fail++; System.out.println("FAIL chapterAt 0"); }
        if ("Q&A".equals(chapterAt(ch, 9999))) pass++;
        else { fail++; System.out.println("FAIL chapterAt end"); }
        if (chapterAt(List.of(), 10) == null) pass++;
        else { fail++; System.out.println("FAIL chapterAt empty"); }

        // fromYtDlp (JSON-lines and array forms)
        String ytl = "{\"start_time\": 0.0, \"end_time\": 330.0, \"title\": \"Intro\"}\n"
                   + "{\"start_time\": 330.5, \"end_time\": 900.0, \"title\": \"Valuation\"}\n";
        List<Chapter> yc = fromYtDlp(ytl);
        if (yc.size() == 2 && "Valuation".equals(chapterAt(yc, 400))) pass++;
        else { fail++; System.out.println("FAIL ytdlp lines: " + yc); }
        if (fromYtDlp("not json").isEmpty()) pass++;
        else { fail++; System.out.println("FAIL ytdlp bad"); }

        // videoIdOf
        if ("Fu7xcx-BTTk".equals(videoIdOf("https://www.youtube.com/watch?v=Fu7xcx-BTTk"))) pass++;
        else { fail++; System.out.println("FAIL videoid"); }
        if ("dQw4w9WgXcQ".equals(videoIdOf("https://youtu.be/dQw4w9WgXcQ"))) pass++;
        else { fail++; System.out.println("FAIL videoid short"); }

        // NOTE_TS
        Matcher m = NOTE_TS.matcher("transcript quote \"...\" ~5:48 mark.]");
        if (m.find() && parseTimestamp(m.group(1)) == 348) pass++;
        else { fail++; System.out.println("FAIL note_ts"); }

        // Mini round-trip on a real tracker file (byte-identical required)
        Path tracker = Path.of(home(), "workspace", "creator_tracker", "parkev_targets.json");
        if (Files.isRegularFile(tracker)) {
            String orig = Files.readString(tracker, StandardCharsets.UTF_8);
            String back = Mini.dump(Mini.parse(orig), 1);
            if (back.equals(orig)) pass++;
            else { fail++; System.out.println("FAIL round-trip parkev_targets.json"); }
        } else { fail++; System.out.println("FAIL tracker file missing"); }

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
