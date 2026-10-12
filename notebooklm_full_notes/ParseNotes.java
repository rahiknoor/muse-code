// ParseNotes.java
// Java port of notebooklm_full_notes/parse_notes.py (kept as parse_notes.py.bak).
//
// Split the NotebookLM Notebook 1 handoff bodies into 63 verbatim note files
// (notes 1-14 and 17-65; notes 15-16 were written separately by hand).
// Reads the four db-call JSON files under
// ~/workspace/agents/f10eb922-8235-4f98-9c02-50b0a70c4402/tool-output
// and writes note-NN-<slug>.md plus INDEX.md into ~/workspace/notebooklm_full_notes.
//
// Run: ~/workspace/java/jdk-21/bin/java ~/workspace/notebooklm_full_notes/ParseNotes.java
//
// Notes on the port:
// - Java has no built-in JSON support, so a minimal JSON parser mirroring
//   Python's json module lives in the nested Json class below.
// - Python's assert is always on; Java's `assert` keyword is off by default,
//   so the port uses an explicit check() helper that always throws
//   AssertionError with the same message.
// - Non-ASCII characters in patterns/messages use backslash-u escapes so the source
//   compiles the same regardless of the platform's default charset.

import java.io.IOException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParseNotes {

    static final String HOME = homeDir();
    static final String TO = HOME + "/workspace/agents/f10eb922-8235-4f98-9c02-50b0a70c4402/tool-output";
    static final String OUT = HOME + "/workspace/notebooklm_full_notes";

    public static void main(String[] args) throws IOException {
        initUtf8Out(); // match Python stdout encoding (UTF-8)
        Files.createDirectories(Path.of(OUT)); // like os.makedirs(OUT, exist_ok=True)
        Map<Integer, Note> notes = new TreeMap<>(); // num -> (title, text)

        // ---------- Handoff A: notes 1-14 ----------
        String a1 = load("db-call_01a0de694c9c761082f0d2d88840e46d.json");
        String a2 = load("db-call_01a0de6a257b73429df098de737f40d1.json");
        String fullA = a1 + a2;
        // NOTE <n> — Title: "<title>"\nText:   (— is U+2014, written as an escape)
        Pattern patA = Pattern.compile("NOTE (\\d+) \u2014 Title: \"([^\"]+)\"\\nText: ");
        List<Match> matches = findAll(patA, fullA);
        check(intGroups(matches, 1).equals(rangeList(1, 15)), pyIntList(intGroups(matches, 1)));
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            int num = Integer.parseInt(m.group(1));
            String title = m.group(2);
            int end = (i + 1 < matches.size()) ? matches.get(i + 1).start : fullA.length();
            String text = fullA.substring(m.end, end).strip();
            if (num == 14) {
                // Cut the REMAINING list trailer (Python's str.split is literal;
                // Java's split takes a regex, so the delimiter is quoted).
                text = text.split(Pattern.quote("REMAINING 51 NOTES NOT YET EXTRACTED"))[0].strip();
            }
            notes.put(num, new Note(title, text));
        }

        // ---------- Handoff B: notes 17-65 ----------
        String b1 = load("db-call_01a0de696f2774f7b7c71540a7251c6a.json");
        String b2 = load("db-call_01a0de69758173c98f5199f343ab4680.json");
        // Note 17 prefix: header + complete first paragraph (from offset-3950 query),
        // then b1 starting at its first full sentence ("Under U.S. GAAP...").
        String prefixPara = "Microsoft Corporation's SEC filings (Form 10-K and Form 10-Q) do not contain a "
                + "discounted cash flow (DCF) stock valuation model, multi-year forward free cash flow "
                + "projections, a company WACC, a terminal growth rate, or an intrinsic value per share target.";
        String anchor = "Under U.S. GAAP and SEC disclosure rules";
        int idx = b1.indexOf(anchor);
        check(idx > 0, "anchor for note 17 splice not found");
        String fullB = "## 17. MSFT DCF intrinsic value\n\n" + prefixPara + "\n\n" + b1.substring(idx) + b2;
        Pattern patB = Pattern.compile("(?m)^## (\\d+)\\. (.+)$");
        matches = findAll(patB, fullB);
        check(intGroups(matches, 1).equals(rangeList(17, 66)), pyIntList(intGroups(matches, 1)));
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            int num = Integer.parseInt(m.group(1));
            String title = m.group(2).strip();
            int end = (i + 1 < matches.size()) ? matches.get(i + 1).start : fullB.length();
            String text = fullB.substring(m.end, end).strip();
            if (num == 65) {
                // Cut the handoff trailer.
                text = text.split(Pattern.quote("[escaped: END EXTERNAL CONTENT"))[0].strip();
            }
            notes.put(num, new Note(title, text));
        }

        check(notes.size() == 63, "expected 63 notes, got " + notes.size()); // 1-14 and 17-65

        // ---------- Write one markdown file per note, plus the index ----------
        List<String> indexLines = new ArrayList<>(List.of(
                "# Notebook 1 \u2014 Stock Research SEC Filings",
                "",
                "Verbatim saved-note texts extracted read-only on 2026-09-26 (notebook URL: https://notebook.google.com/notebook/4487f2ab-674d-4ebb-9ed8-efe6f559df8a).",
                "Notes 1\u201320 are DCF refusal/baseline notes; 21\u201365 are risks / growth-outlook / cash-flow research notes.",
                "Nothing was created, edited, renamed, or deleted in NotebookLM.",
                "",
                "## Notes",
                ""));
        for (Map.Entry<Integer, Note> e : notes.entrySet()) {
            int num = e.getKey();
            Note n = e.getValue();
            String fn = String.format("note-%02d-%s.md", num, slug(n.title));
            String body = "# " + n.title + "\n\n"
                    + "> Source: NotebookLM notebook \"Stock Research SEC Filings\", note " + num + " of 65. "
                    + "Extracted verbatim (read-only) on 2026-09-26.\n\n---\n\n"
                    + n.text + "\n";
            write(OUT + "/" + fn, body);
            indexLines.add("- Note " + num + ": [" + n.title + "](" + fn + ")");
        }
        write(OUT + "/INDEX.md", String.join("\n", indexLines) + "\n");

        List<Integer> keys = new ArrayList<>(notes.keySet());
        // print("wrote", len(notes), "notes;", first5, "...", last5)
        System.out.println("wrote " + notes.size() + " notes; "
                + pyIntList(keys.subList(0, 5)) + " ... "
                + pyIntList(keys.subList(keys.size() - 5, keys.size())));
    }

    /** Read the "s" column of the single row in a db-call JSON file. */
    static String load(String name) throws IOException {
        Map<String, Object> d = Json.parseObject(read(TO + "/" + name));
        return Json.str(Json.obj(Json.arr(Json.obj(d.get("result")).get("rows")).get(0)).get("s"));
    }

    /**
     * Filename slug: lowercase, runs of non [a-z0-9] become one dash,
     * leading/trailing dashes stripped (like re.sub(r"[^a-z0-9]+", "-", s).strip("-")).
     */
    static String slug(String title) {
        return title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /** Like Python's assert: always active (Java's assert keyword is off by default). */
    static void check(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    /** [1, 2, 3] style rendering, like Python's str(list-of-int). */
    static String pyIntList(List<Integer> xs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(xs.get(i));
        }
        return sb.append("]").toString();
    }

    static List<Integer> intGroups(List<Match> matches, int group) {
        List<Integer> out = new ArrayList<>();
        for (Match m : matches) {
            out.add(Integer.parseInt(m.group(group)));
        }
        return out;
    }

    static List<Integer> rangeList(int from, int toExclusive) {
        List<Integer> out = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) {
            out.add(i);
        }
        return out;
    }

    /** One extracted note: its title and its verbatim text. */
    static final class Note {
        final String title;
        final String text;
        Note(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    /** A regex match with its span and groups (like Python's re.Match). */
    static final class Match {
        final int start;
        final int end;
        final String[] groups;
        Match(int start, int end, String[] groups) {
            this.start = start;
            this.end = end;
            this.groups = groups;
        }
        String group(int i) {
            return groups[i - 1];
        }
    }

    static List<Match> findAll(Pattern pat, String text) {
        List<Match> out = new ArrayList<>();
        Matcher m = pat.matcher(text);
        while (m.find()) {
            String[] g = new String[m.groupCount()];
            for (int i = 0; i < g.length; i++) {
                g[i] = m.group(i + 1);
            }
            out.add(new Match(m.start(), m.end(), g));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Small helpers shared by the ports in this batch.


    /**
     * Point System.out at UTF-8 explicitly. Python coerces stdout to UTF-8 even
     * under a POSIX locale; the JVM default here is ASCII, which would turn
     * non-ASCII characters (e.g. em dashes) into '?'.
     */
    static void initUtf8Out() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    }

    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8); // like Python open().read()
    }

    static void write(String path, String content) throws IOException {
        // like Python open(path, "w").write(): UTF-8, no trailing newline added
        Files.writeString(Path.of(path), content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    /**
     * Minimal JSON parser mirroring Python's json module: objects keep
     * insertion order (like Python dicts) and a duplicate key keeps the last
     * value. Only the parser is needed here (this script writes markdown).
     */
    static final class Json {

        /** Parse a JSON document (like Python's json.loads). */
        static Object parse(String text) {
            Parser p = new Parser(text);
            Object v = p.parseValue();
            p.skipWs();
            if (p.pos != text.length()) {
                throw new JsonException("Extra data after JSON value at index " + p.pos);
            }
            return v;
        }

        /** Parse and cast to an object (like json.load(...) returning a dict). */
        @SuppressWarnings("unchecked")
        static Map<String, Object> parseObject(String text) {
            return (Map<String, Object>) parse(text);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object v) {
            return (Map<String, Object>) v;
        }

        @SuppressWarnings("unchecked")
        static List<Object> arr(Object v) {
            return (List<Object>) v;
        }

        static String str(Object v) {
            return (String) v;
        }

        static final class JsonException extends RuntimeException {
            JsonException(String msg) {
                super(msg);
            }
        }

        private static final class Parser {
            final String text;
            int pos;

            Parser(String text) {
                this.text = text;
            }

            void skipWs() {
                while (pos < text.length()) {
                    char c = text.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            char peek() {
                if (pos >= text.length()) {
                    throw new JsonException("Unexpected end of input");
                }
                return text.charAt(pos);
            }

            Object parseValue() {
                skipWs();
                char c = peek();
                switch (c) {
                    case '{': return parseObject();
                    case '[': return parseArray();
                    case '"': return parseString();
                    case 't': expect("true"); return Boolean.TRUE;
                    case 'f': expect("false"); return Boolean.FALSE;
                    case 'n': expect("null"); return null;
                    case 'N': expect("NaN"); return Double.NaN; // json.loads("NaN")
                    case 'I': expect("Infinity"); return Double.POSITIVE_INFINITY;
                    case '-':
                        if (text.startsWith("-Infinity", pos)) {
                            pos += 9;
                            return Double.NEGATIVE_INFINITY;
                        }
                        return parseNumber();
                    default: return parseNumber();
                }
            }

            void expect(String lit) {
                if (!text.startsWith(lit, pos)) {
                    throw new JsonException("Expected '" + lit + "' at index " + pos);
                }
                pos += lit.length();
            }

            Map<String, Object> parseObject() {
                pos++; // consume {
                Map<String, Object> map = new LinkedHashMap<>();
                skipWs();
                if (peek() == '}') {
                    pos++;
                    return map;
                }
                while (true) {
                    skipWs();
                    if (peek() != '"') {
                        throw new JsonException("Expected string key at index " + pos);
                    }
                    String key = parseString();
                    skipWs();
                    if (peek() != ':') {
                        throw new JsonException("Expected ':' at index " + pos);
                    }
                    pos++;
                    map.put(key, parseValue()); // last duplicate key wins, like Python
                    skipWs();
                    char c = peek();
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    if (c == '}') {
                        pos++;
                        return map;
                    }
                    throw new JsonException("Expected ',' or '}' at index " + pos);
                }
            }

            List<Object> parseArray() {
                pos++; // consume [
                List<Object> list = new ArrayList<>();
                skipWs();
                if (peek() == ']') {
                    pos++;
                    return list;
                }
                while (true) {
                    list.add(parseValue());
                    skipWs();
                    char c = peek();
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    if (c == ']') {
                        pos++;
                        return list;
                    }
                    throw new JsonException("Expected ',' or ']' at index " + pos);
                }
            }

            String parseString() {
                pos++; // consume opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (pos >= text.length()) {
                        throw new JsonException("Unterminated string");
                    }
                    char c = text.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        if (pos >= text.length()) {
                            throw new JsonException("Unterminated escape");
                        }
                        char e = text.charAt(pos++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u': sb.append(parseUnicodeEscape()); break;
                            default: throw new JsonException("Invalid escape '\\" + e + "'");
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }

            /** Parse a backslash-uXXXX escape, combining a following low surrogate like Python does. */
            String parseUnicodeEscape() {
                int hi = parseHex4();
                char h = (char) hi;
                if (Character.isHighSurrogate(h) && text.startsWith("\\u", pos)) {
                    int lo = hex4At(pos + 2);
                    char l = (char) lo;
                    if (Character.isLowSurrogate(l)) {
                        pos += 6;
                        return new String(Character.toChars(Character.toCodePoint(h, l)));
                    }
                }
                return String.valueOf(h);
            }

            int parseHex4() {
                int v = hex4At(pos);
                pos += 4;
                return v;
            }

            int hex4At(int at) {
                if (at + 4 > text.length()) {
                    throw new JsonException("Bad \\u escape");
                }
                int v = 0;
                for (int i = 0; i < 4; i++) {
                    int d = Character.digit(text.charAt(at + i), 16);
                    if (d < 0) {
                        throw new JsonException("Bad \\u escape");
                    }
                    v = v * 16 + d;
                }
                return v;
            }

            Object parseNumber() {
                int start = pos;
                if (peek() == '-') {
                    pos++;
                }
                if (pos >= text.length()) {
                    throw new JsonException("Bad number at index " + start);
                }
                char c = text.charAt(pos);
                if (c == '0') {
                    pos++;
                } else if (c >= '1' && c <= '9') {
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                } else {
                    throw new JsonException("Bad number at index " + start);
                }
                boolean isFloat = false;
                if (pos < text.length() && text.charAt(pos) == '.') {
                    isFloat = true;
                    pos++;
                    int d0 = pos;
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                    if (pos == d0) {
                        throw new JsonException("Bad number at index " + start);
                    }
                }
                if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                    isFloat = true;
                    pos++;
                    if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                        pos++;
                    }
                    int d0 = pos;
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                    if (pos == d0) {
                        throw new JsonException("Bad number at index " + start);
                    }
                }
                String num = text.substring(start, pos);
                if (isFloat) {
                    return Double.parseDouble(num);
                }
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException e) {
                    return new BigInteger(num); // Python ints have no size limit
                }
            }
        }

        /**
         * Shortest round-trip decimal like Python's repr(float): plain notation
         * for 1e-4 <= |v| < 1e16, else d[.ddd]e(+|-)XX (exponent signed, 2+ digits).
         */
        static String pyFloat(double v) {
            if (Double.isNaN(v)) return "NaN";
            if (v == Double.POSITIVE_INFINITY) return "Infinity";
            if (v == Double.NEGATIVE_INFINITY) return "-Infinity";
            String s = Double.toString(v); // shortest round-trip digits, like Python's
            int e = s.indexOf('E');
            if (e < 0) {
                return s; // plain notation already matches Python
            }
            boolean neg = s.charAt(0) == '-';
            String mant = neg ? s.substring(1, e) : s.substring(0, e);
            int exp = Integer.parseInt(s.substring(e + 1));
            String digits = mant.replace(".", "");
            int cut = digits.length();
            while (cut > 1 && digits.charAt(cut - 1) == '0') {
                cut--; // shortest repr never has real trailing zeros (only "d.0")
            }
            digits = digits.substring(0, cut);
            StringBuilder out = new StringBuilder();
            if (neg) {
                out.append('-');
            }
            if (exp >= -4 && exp < 16) {
                if (exp >= 0) {
                    int intLen = exp + 1;
                    if (digits.length() <= intLen) {
                        out.append(digits).append("0".repeat(intLen - digits.length())).append(".0");
                    } else {
                        out.append(digits, 0, intLen).append('.').append(digits.substring(intLen));
                    }
                } else {
                    out.append("0.").append("0".repeat(-exp - 1)).append(digits);
                }
                return out.toString();
            }
            out.append(digits.charAt(0));
            if (digits.length() > 1) {
                out.append('.').append(digits.substring(1));
            }
            out.append('e').append(exp < 0 ? '-' : '+');
            String expDigits = Integer.toString(Math.abs(exp));
            if (expDigits.length() < 2) {
                out.append('0');
            }
            return out.append(expDigits).toString();
        }
    }
}
