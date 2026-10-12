import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Append verified backfill entries to parkev_targets.json.
 *
 * <p>Usage: {@code java AppendEntries.java new_entries.json [source] [--no-capture]}
 *
 * <p>{@code new_entries.json} is a JSON list of objects with keys:
 * ticker, company, call_date (YYYY-MM-DD), video_title, video_url,
 * target_type, target_value, price_at_call, currency, notes.
 * Optional per-entry keys: source, status (both fall back to the defaults),
 * chapter, timestamp_sec (chapter-tagging from ChapterTag.java; passed
 * through verbatim when present).
 * An optional positional {@code source} argument overrides the default
 * "channel backfill 2026-09-26 (transcript verified)" source string;
 * {@code --no-capture} leaves the added tickers out of captured.txt so a
 * later verified pass can still upgrade them.
 *
 * <p>Steps (mirrors append_entries.py):
 * <ol>
 *   <li>Load the new entries and the existing tracker log.</li>
 *   <li>Load captured.txt (tickers already captured) as a safety net.</li>
 *   <li>Find the first free id at/after pt-005 (existing ids are kept).</li>
 *   <li>For each new entry: skip (with a stderr note) if its ticker is
 *       already captured; otherwise build the entry with
 *       seeded=false, backfilled=true, checks=[], status="open" and
 *       append it.</li>
 *   <li>Rewrite parkev_targets.json (2-space indent, like Python's
 *       {@code json.dump(..., indent=2)}).</li>
 *   <li>Append the added tickers to captured.txt; touch done_ids.txt.</li>
 * </ol>
 *
 * <p>Run with the working directory set to ~/workspace/creator_tracker,
 * the same as the Python original.
 */
public class AppendEntries {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // 1. New entries come from the JSON file named as the first argument.
        List<Object> newEntries =
                (List<Object>) MiniJson.parse(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));

        // 1b. Optional args: [source] overrides the default source string
        // (only when an input entry does not carry its own "source" key);
        // --no-capture skips the captured.txt append, for provisional entries
        // a later verified pass must still be able to upgrade.
        String srcArg = null;
        boolean noCapture = false;
        for (int i = 1; i < args.length; i++) {
            if ("--no-capture".equals(args[i])) noCapture = true;
            else if (srcArg == null && !args[i].startsWith("--")) srcArg = args[i];
            else throw new IllegalArgumentException(
                    "usage: java AppendEntries.java new_entries.json [source] [--no-capture]");
        }

        // 2. The tracker log we append to.
        Map<String, Object> tracker = (Map<String, Object>) MiniJson.parse(
                Files.readString(Path.of("parkev_targets.json"), StandardCharsets.UTF_8));
        List<Object> entries = (List<Object>) tracker.get("entries");

        // 3. Tickers already captured: skip these (safety against duplicates).
        Set<String> captured = new LinkedHashSet<>();
        for (String line : Files.readAllLines(Path.of("captured.txt"), StandardCharsets.UTF_8)) {
            String tick = line.strip();
            if (!tick.isEmpty()) captured.add(tick);
        }

        // 4. First free id at/after pt-005.
        Set<String> existingIds = new LinkedHashSet<>();
        for (Object e : entries)
            existingIds.add((String) ((Map<String, Object>) e).get("id"));
        int n = 5;
        while (existingIds.contains(String.format("pt-%03d", n))) n++;

        // 5. Build and append the new entries.
        List<String> added = new ArrayList<>();
        for (Object raw : newEntries) {
            Map<String, Object> e = (Map<String, Object>) raw;
            String tick = (String) e.get("ticker");
            if (captured.contains(tick)) {
                System.err.println("SKIP " + tick + ": already captured");
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", String.format("pt-%03d", n));
            entry.put("ticker", tick);
            // Python's dict.get(key, default): default applies only when the
            // key is missing; an explicit null stays null.
            entry.put("company",      e.containsKey("company")       ? e.get("company")       : tick);
            entry.put("call_date",    e.get("call_date"));
            entry.put("video_title",  e.containsKey("video_title")   ? e.get("video_title")   : "not recorded");
            entry.put("video_url",    e.containsKey("video_url")     ? e.get("video_url")     : "not recorded");
            entry.put("target_type",  e.get("target_type"));
            entry.put("target_value", e.get("target_value"));
            entry.put("price_at_call", e.containsKey("price_at_call") ? e.get("price_at_call") : "not recorded");
            entry.put("currency",     e.containsKey("currency")      ? e.get("currency")      : "USD");
            entry.put("notes",        e.containsKey("notes")         ? e.get("notes")         : "");
            entry.put("seeded", Boolean.FALSE);
            entry.put("backfilled", Boolean.TRUE);
            // Per-entry "source" wins; then the CLI override; then the
            // transcript-verified default (OPTION 1 path, unchanged).
            Object eSrc = e.get("source");
            String src = (eSrc instanceof String && !((String) eSrc).isEmpty())
                    ? (String) eSrc
                    : (srcArg != null ? srcArg
                                      : "channel backfill 2026-09-26 (transcript verified)");
            entry.put("source", src);
            entry.put("checks", new ArrayList<>());
            // Per-entry "status" wins (e.g. "title-derived" for OPTION 2
            // placeholders, kept out of hit-rate/diff consumers); else "open".
            Object eStatus = e.get("status");
            entry.put("status", (eStatus instanceof String && !((String) eStatus).isEmpty())
                    ? eStatus : "open");
            // Chapter tagging (ChapterTag.java): pass through when the input
            // entry carries them; absent stays absent (unknown, never invented).
            if (e.containsKey("chapter")) entry.put("chapter", e.get("chapter"));
            if (e.containsKey("timestamp_sec")) entry.put("timestamp_sec", e.get("timestamp_sec"));
            entries.add(entry);
            added.add(tick);
            n++;
        }

        // 6. Write the tracker back (whole file, 2-space indent).
        Files.writeString(Path.of("parkev_targets.json"),
                MiniJson.dump(tracker, 2), StandardCharsets.UTF_8);

        // 7. Record the added tickers so future runs skip them.
        // (--no-capture: provisional entries stay upgradable; their tickers
        // are deliberately left out of captured.txt — see backfill_log.md.)
        if (!noCapture) {
            try (var w = Files.newBufferedWriter(Path.of("captured.txt"), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
                for (String t : added) {
                    w.write(t);
                    w.newLine();
                }
            }
        }

        // 8. Touch done_ids.txt (Python: open(..., "a") then pass -> creates if missing).
        try (var s = Files.newOutputStream(Path.of("done_ids.txt"),
                StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            // immediately closed: just ensures the file exists
        }

        System.out.println("added " + added.size() + ": " + String.join(", ", added));
    }
}

/**
 * Minimal JSON parser/serializer mirroring the Python {@code json} module
 * behavior these scripts rely on: objects, arrays, strings, numbers,
 * true/false/null. Key order is preserved (like Python dicts).
 *
 * <p>Numbers are kept as their raw source text ({@link Num}) instead of being
 * parsed to double/int. Python's {@code json.dump} writes a number back in
 * its original literal form, so keeping the raw text makes a parse&rarr;dump
 * round trip byte-identical &mdash; important because some scripts rewrite
 * whole tracker files.</p>
 *
 * <p>String escaping on output matches Python's {@code ensure_ascii=True}
 * default: {@code "}, {@code \}, control chars get short escapes, and every
 * other char outside the printable ASCII range becomes {@code \\uXXXX}
 * (lowercase hex, surrogate pairs for astral chars).</p>
 */
final class MiniJson {
    private MiniJson() {} // utility class: no instances

    /** A JSON number, stored as its raw source text (e.g. "158", "1.5", "2e3"). */
    static final class Num {
        final String raw;
        Num(String raw) { this.raw = raw; }
        @Override public String toString() { return raw; }
    }

    // ---------------- parsing ----------------

    /** Parse a whole JSON document; throws IllegalArgumentException on bad input. */
    static Object parse(String text) {
        int[] pos = {0}; // int[1] so recursive helpers can advance the cursor
        skipWs(text, pos);
        Object v = parseValue(text, pos);
        skipWs(text, pos);
        if (pos[0] != text.length())
            throw new IllegalArgumentException("Unexpected trailing data at index " + pos[0]);
        return v;
    }

    private static void skipWs(String t, int[] p) {
        while (p[0] < t.length()) {
            char c = t.charAt(p[0]);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p[0]++;
            else break;
        }
    }

    private static Object parseValue(String t, int[] p) {
        switch (t.charAt(p[0])) {
            case '{': return parseObject(t, p);
            case '[': return parseArray(t, p);
            case '"': return parseString(t, p);
            case 't': expect(t, p, "true");  return Boolean.TRUE;
            case 'f': expect(t, p, "false"); return Boolean.FALSE;
            case 'n': expect(t, p, "null");  return null;
            default:  return parseNumber(t, p);
        }
    }

    private static void expect(String t, int[] p, String word) {
        if (!t.startsWith(word, p[0]))
            throw new IllegalArgumentException("Expected '" + word + "' at index " + p[0]);
        p[0] += word.length();
    }

    private static java.util.Map<String, Object> parseObject(String t, int[] p) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        p[0]++; // consume '{'
        skipWs(t, p);
        if (t.charAt(p[0]) == '}') { p[0]++; return m; }
        while (true) {
            skipWs(t, p);
            String key = parseString(t, p);
            skipWs(t, p);
            if (t.charAt(p[0]) != ':')
                throw new IllegalArgumentException("Expected ':' at index " + p[0]);
            p[0]++; // consume ':'
            skipWs(t, p);
            // Duplicate keys: last one wins, like Python's json.
            m.put(key, parseValue(t, p));
            skipWs(t, p);
            char c = t.charAt(p[0]);
            if (c == ',') { p[0]++; continue; }
            if (c == '}') { p[0]++; return m; }
            throw new IllegalArgumentException("Expected ',' or '}' at index " + p[0]);
        }
    }

    private static java.util.List<Object> parseArray(String t, int[] p) {
        java.util.List<Object> list = new java.util.ArrayList<>();
        p[0]++; // consume '['
        skipWs(t, p);
        if (t.charAt(p[0]) == ']') { p[0]++; return list; }
        while (true) {
            skipWs(t, p);
            list.add(parseValue(t, p));
            skipWs(t, p);
            char c = t.charAt(p[0]);
            if (c == ',') { p[0]++; continue; }
            if (c == ']') { p[0]++; return list; }
            throw new IllegalArgumentException("Expected ',' or ']' at index " + p[0]);
        }
    }

    private static String parseString(String t, int[] p) {
        // assumes t.charAt(p[0]) == '"'
        StringBuilder sb = new StringBuilder();
        int i = p[0] + 1;
        while (true) {
            char c = t.charAt(i);
            if (c == '"') { p[0] = i + 1; return sb.toString(); }
            if (c == '\\') {
                char e = t.charAt(i + 1);
                switch (e) {
                    case '"':  sb.append('"');  i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    case '/':  sb.append('/');  i += 2; break;
                    case 'b':  sb.append('\b'); i += 2; break;
                    case 'f':  sb.append('\f'); i += 2; break;
                    case 'n':  sb.append('\n'); i += 2; break;
                    case 'r':  sb.append('\r'); i += 2; break;
                    case 't':  sb.append('\t'); i += 2; break;
                    case 'u': {
                        int cp = Integer.parseInt(t.substring(i + 2, i + 6), 16);
                        i += 6;
                        // A high surrogate followed by another \\uXXXX low
                        // surrogate forms one astral character (like Python).
                        if (Character.isHighSurrogate((char) cp) && i + 1 < t.length()
                                && t.charAt(i) == '\\' && t.charAt(i + 1) == 'u') {
                            int lo = Integer.parseInt(t.substring(i + 2, i + 6), 16);
                            if (Character.isLowSurrogate((char) lo)) {
                                cp = Character.toCodePoint((char) cp, (char) lo);
                                i += 6;
                            }
                        }
                        sb.appendCodePoint(cp);
                        break;
                    }
                    default: throw new IllegalArgumentException("Bad escape at index " + i);
                }
            } else {
                sb.append(c);
                i++;
            }
        }
    }

    private static Num parseNumber(String t, int[] p) {
        int i = p[0], n = t.length();
        if (i < n && t.charAt(i) == '-') i++;
        while (i < n && isDigit(t.charAt(i))) i++;
        if (i < n && t.charAt(i) == '.') {
            i++;
            while (i < n && isDigit(t.charAt(i))) i++;
        }
        if (i < n && (t.charAt(i) == 'e' || t.charAt(i) == 'E')) {
            i++;
            if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-')) i++;
            while (i < n && isDigit(t.charAt(i))) i++;
        }
        // Keep the raw literal text so dump() reproduces it exactly.
        Num num = new Num(t.substring(p[0], i));
        p[0] = i;
        return num;
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    // ---------------- serializing ----------------

    /**
     * Serialize to JSON with {@code indent} spaces per level, matching
     * Python's {@code json.dump(obj, f, indent=indent)} formatting exactly
     * (no trailing newline; empty objects/arrays stay on one line).
     */
    static String dump(Object v, int indent) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, indent, 0);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb, int indent, int level) {
        if (v == null)                       sb.append("null");
        else if (v instanceof String)        writeString((String) v, sb);
        else if (v instanceof Num)           sb.append(((Num) v).raw);
        else if (v instanceof Boolean)       sb.append(v.toString());
        else if (v instanceof java.util.List) writeList((java.util.List<?>) v, sb, indent, level);
        else if (v instanceof java.util.Map)  writeMap((java.util.Map<?, ?>) v, sb, indent, level);
        else throw new IllegalArgumentException("Cannot serialize " + v.getClass());
    }

    private static void writeList(java.util.List<?> list, StringBuilder sb, int indent, int level) {
        if (list.isEmpty()) { sb.append("[]"); return; }
        String pad = " ".repeat(indent * (level + 1));
        String closePad = " ".repeat(indent * level);
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append(pad);
            write(list.get(i), sb, indent, level + 1);
        }
        sb.append("\n").append(closePad).append("]");
    }

    private static void writeMap(java.util.Map<?, ?> map, StringBuilder sb, int indent, int level) {
        if (map.isEmpty()) { sb.append("{}"); return; }
        String pad = " ".repeat(indent * (level + 1));
        String closePad = " ".repeat(indent * level);
        sb.append("{\n");
        boolean first = true;
        for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append(pad);
            writeString((String) e.getKey(), sb);
            sb.append(": ");
            write(e.getValue(), sb, indent, level + 1);
        }
        sb.append("\n").append(closePad).append("}");
    }

    /** Escape a string the way Python's json does with ensure_ascii=True. */
    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (cp < 0x20 || cp > 0x7E) {
                        if (cp <= 0xFFFF) {
                            sb.append(String.format("\\u%04x", cp)); // lowercase hex, like Python
                        } else {
                            // Astral chars: one \\uXXXX per surrogate, like Python.
                            for (char c : Character.toChars(cp))
                                sb.append(String.format("\\u%04x", (int) c));
                        }
                    } else {
                        sb.appendCodePoint(cp);
                    }
            }
        }
        sb.append('"');
    }
}
