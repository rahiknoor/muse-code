import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Aggregate creator tracker logs into creator_values.json (chart backend).
 *
 * <p>Usage: {@code java BuildCreatorValues.java} (no arguments).
 *
 * <p>Reads every {@code *_targets.json} in ~/workspace/creator_tracker/
 * (parkev_targets.json, jeremy_targets.json, ...), each an append-only log
 * of {creator}-stated price targets / intrinsic values / fair values /
 * buy prices from YouTube videos.
 *
 * <p>Writes ~/workspace/dcf_csv/creator_values.json:
 * <pre>
 *   { "asof": ..., "values": { TICKER: [ {creator, type, value, call_date,
 *      video_title, video_url, notes} ... ] }, "latest": { TICKER: &lt;latest entry&gt; } }
 * </pre>
 *
 * <p>"Latest" = tiered creator priority per ticker (Rahik 2026-10-05):
 * Parkev's intrinsic/fair value &gt; Couch Investor's value calls &gt;
 * The Patient Investor (trusted) &gt; Jeremy's buy price &gt; no voice.
 * Everyone else is noise: excluded entirely, no fallback. A set number is
 * never overridden: the winning tier's entry stands even when a lower tier
 * posts a newer call. Only the tiered types feed the chart column; other
 * entries are kept in values[] but never become "latest".
 *
 * <p>Share-class aliases (e.g. GOOGL&rarr;GOOG) are normalized so everything
 * downstream keys on the holding ticker. Run after the daily YouTube
 * digest appends new tracker entries.
 */
public class BuildCreatorValues {

    /**
     * Home directory, honoring the $HOME environment variable first (this is
     * what Python's os.path.expanduser("~") does). In this runtime the JVM's
     * "user.home" can point at /root while $HOME is /home/hatch, so reading
     * "user.home" directly would resolve ~/workspace to the wrong place.
     */
    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static final Path TRACKER_DIR =
            Path.of(homeDir(), "workspace", "creator_tracker");
    static final Path OUT =
            Path.of(homeDir(), "workspace", "dcf_csv", "creator_values.json");

    /** Only these target types feed the chart column. */
    static final Set<String> CHART_TYPES =
            Set.of("intrinsic_value", "fair_value", "price_target");

    static final Map<String, String> CREATOR_NAMES = Map.of(
            "parkev_targets.json", "Parkev Tatevosian",
            "jeremy_targets.json", "Jeremy Lefebvre",
            "couch_investor_targets.json", "Couch Investor",
            "bottom_finders_targets.json", "Bottom Finders",
            "other_creators_targets.json", "Other creators");

    // Share-class / listing aliases: creator calls often use the other class
    // of a dual-class ticker. Everything downstream keys on the holding
    // ticker, so normalize here.
    static final Map<String, String> TICKER_ALIASES = Map.of("GOOGL", "GOOG");

    @SuppressWarnings("unchecked")
    /**
     * Creator priority tier for the "latest" voice (Rahik 2026-10-05).
     * 1 = Parkev intrinsic/fair value, 2 = Couch Investor value calls
     * (price_target, fair_value, buy_zone), 3 = The Patient Investor
     * valuation calls (trusted feed creator), 4 = Jeremy buy price
     * (he states no intrinsic value; his entry prices are the signal),
     * 99 = noise (excluded entirely).
     *
     * <p>STANDING RULE (Rahik 2026-10-05): never add another creator to
     * tiers 1-4 on my own judgment. New trusted creators get proposed to
     * Rahik first; he verifies before they are tiered here.
     */
    static int tier(String creator, String type) {
        if ("Parkev Tatevosian".equals(creator)
                && ("intrinsic_value".equals(type) || "fair_value".equals(type))) return 1;
        if ("Couch Investor".equals(creator)
                && ("price_target".equals(type) || "fair_value".equals(type)
                    || "buy_zone".equals(type))) return 2;
        if ("The Patient Investor".equals(creator)
                && ("price_target".equals(type) || "fair_value".equals(type)
                    || "intrinsic_value".equals(type))) return 3;
        if ("Jeremy Lefebvre".equals(creator) && "buy_price".equals(type)) return 4;
        return 99;
    }

    // 2026-10-06: undated backfills (call_date "unknown") must never displace
    // dated calls as latest — mirrors DetectRevisions' isIsoDate guard from the
    // 2026-10-03 phantom-revision fix. "latest = most-recent call_date", not
    // discovery order; bare string compareTo lets "unknown" beat any ISO date.
    static boolean isIsoDate(String s) {
        return s != null && s.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    static boolean isNewerCall(String d, String id, String bestDate, String bestId) {
        boolean dated = isIsoDate(d);
        boolean bestDated = isIsoDate(bestDate);
        if (dated && !bestDated) return true;
        if (!dated && bestDated) return false;
        if (dated && bestDated && !d.equals(bestDate)) return d.compareTo(bestDate) > 0;
        return id.compareTo(bestId) > 0; // same date, or both undated: id tie-break
    }

    public static void main(String[] args) throws Exception {
        // Ticker -> list of value entries, in first-seen order (LinkedHashMap
        // keeps insertion order, like a Python dict).
        Map<String, List<Map<String, Object>>> values = new LinkedHashMap<>();

        // sorted(glob(...)): full path strings, alphabetically.
        List<Path> files = new ArrayList<>();
        try (var dir = Files.newDirectoryStream(TRACKER_DIR, "*_targets.json")) {
            for (Path p : dir) files.add(p);
        } catch (NoSuchFileException e) {
            // Python's glob returns [] for a missing dir; do the same.
        }
        files.sort(Comparator.comparing(Path::toString));

        for (Path path : files) {
            String fname = path.getFileName().toString();
            // Unknown creators fall back to the filename minus "_targets.json"
            // (Python str.replace replaces every occurrence; so does Java's).
            String creator = CREATOR_NAMES.getOrDefault(fname, fname.replace("_targets.json", ""));
            Object data;
            try {
                data = MiniJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            } catch (Exception e) {
                continue; // JSONDecodeError / FileNotFoundError -> skip the file
            }
            List<Object> entries = new ArrayList<>();
            if (data instanceof Map) {
                Object e = ((Map<String, Object>) data).get("entries");
                if (e instanceof List) entries = (List<Object>) e;
            } else if (data instanceof List) {
                // Bare [...] tracker files (jeremy_targets.json) — same shape
                // DetectRevisions.entriesOf accepts. (2026-10-06 fix: the Map-only
                // branch silently dropped every Jeremy entry from the aggregate.)
                entries = (List<Object>) data;
            }
            for (Object raw : entries) {
                Map<String, Object> e = (Map<String, Object>) raw;
                String t = str(e.get("ticker")).strip().toUpperCase(Locale.ROOT);
                t = TICKER_ALIASES.getOrDefault(t, t);
                if (t.isEmpty() || e.get("target_value") == null) continue;
                Map<String, Object> v = new LinkedHashMap<>();
                // Other-creators entries carry their own channel; use it so
                // trusted channels (Rahik 2026-10-05: The Patient Investor)
                // can tier above noise. Falls back to the file-level name.
                String ch = str(e.get("creator_channel"));
                v.put("creator", ch.isEmpty() ? creator : ch);
                v.put("type", e.get("target_type"));
                v.put("value", e.get("target_value")); // raw number text preserved
                v.put("call_date", e.get("call_date"));
                v.put("video_title", e.get("video_title"));
                v.put("video_url", e.get("video_url"));
                v.put("notes", e.get("notes"));
                v.put("entry_id", e.get("id"));
                values.computeIfAbsent(t, k -> new ArrayList<>()).add(v);
            }
        }

        // "Latest" per ticker: tiered creator priority (Rahik 2026-10-05).
        // Tier 1: Parkev's intrinsic/fair value. Tier 2: Couch Investor's
        // value calls. Tier 3: The Patient Investor (trusted feed creator).
        // Tier 4: Jeremy's buy price. Everyone else is noise:
        // excluded entirely, no fallback to other creators.
        // A set number is never overridden: the winning tier's entry stands
        // even when a lower tier or a noise creator posts a newer call.
        // (2026-10-03 fix: a $1,000 hype target from an unfollowed creator
        // displaced Parkev's $195 as PLTR's column value.)
        // (2026-10-05 change: supersedes the 2026-09-27 followed-set rule and
        // the 2026-09-29 buy_price exclusion -- the exclusion now applies to
        // everyone except Jeremy, whose buy prices Rahik explicitly wants.)
        Map<String, Object> latest = new LinkedHashMap<>();
        for (var en : values.entrySet()) {
            Map<String, Object> best = null;
            int bestTier = 99;
            String bestDate = "";
            String bestId = "";
            for (var x : en.getValue()) {
                int t = tier(str(x.get("creator")), str(x.get("type")));
                if (t == 99) continue;
                String d = str(x.get("call_date"));
                String id = str(x.get("entry_id"));
                if (best == null || t < bestTier
                        || (t == bestTier && isNewerCall(d, id, bestDate, bestId))) {
                    best = x;
                    bestTier = t;
                    bestDate = d;
                    bestId = id;
                }
            }
            // No tiered voice: the ticker gets NO latest entry (noise
            // creators never feed the chart column or the outside median).
            if (best != null) latest.put(en.getKey(), best);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        // Idea #40: derived-file stamp. creator_values.json is regenerated
        // from creator_tracker/*_targets.json on every run — it must never
        // be read as an input. Same literal as DerivedFiles.STAMP (kept in
        // sync); \u2014 is the em dash.
        out.put("_derived",
                "derived from workbook_data.json via RebuildAll \u2014 do not edit, do not read as input");
        out.put("asof", LocalDate.now().toString()); // like Python's date.today().isoformat()
        out.put("note", "Aggregated from creator_tracker/*_targets.json (append-only logs fed by "
                + "the daily YouTube digest). 'latest' = tiered creator priority per ticker "
                + "(Parkev intrinsic/fair value > Couch Investor value calls > The Patient Investor "
                + "> Jeremy buy price; everyone else excluded); tiebreak latest call_date, then "
                + "highest entry id. A set number is never overridden by a lower tier.");
        out.put("values", values);
        out.put("latest", latest);

        Files.writeString(OUT, MiniJson.dump(out, 1), StandardCharsets.UTF_8);

        int total = values.values().stream().mapToInt(List::size).sum();
        System.out.println("wrote " + OUT + ": " + values.size() + " tickers, " + total + " entries");
    }

    /** Python's {@code e.get(k) or ""}: missing/null -> "". */
    private static String str(Object o) {
        return o == null ? "" : o.toString();
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
