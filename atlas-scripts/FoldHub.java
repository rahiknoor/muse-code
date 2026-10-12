import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fold the Stock Research Hub's per-company dossiers into Atlas stock notes.
 *
 * <p>Java port of {@code fold_hub.py} (2026-09-27) — behavior-identical.
 * Rahik reads Java, so every step below mirrors the Python line-for-line
 * with comments saying what it does.
 *
 * <p>What it does:
 * <ol>
 *   <li>Reads {@code ~/workspace/ts-spaces/stock-research-hub/index.html}
 *       (31 company sections, each with: From the SEC filings / The market
 *       story / Key figures / SEC filing history).</li>
 *   <li>For each {@code ~/workspace/atlas/01 📈 Stocks/<TICKER>.md}, replaces the
 *       "## Research Hub" section with the hub dossier. The hub's
 *       "Cross-reference values" snapshot is SKIPPED by design: the note's
 *       valuation table at the top is the live view, refreshed from the JSON
 *       backends by RefreshValuations.</li>
 *   <li>Everything else in the note (Sources, Related, frontmatter) is
 *       untouched. Idempotent.</li>
 * </ol>
 *
 * <p>Run:
 * <pre>
 *   ~/workspace/java/jdk-21/bin/java ~/workspace/atlas-scripts/FoldHub.java [--base-dir DIR]
 * </pre>
 * {@code --base-dir} is a test-only override (defaults to the user home);
 * normal runs omit it and behave exactly like the Python script.
 */
public class FoldHub {

    // The hub subsections we keep, in the order they appear in the note.
    // ("Cross-reference values" is deliberately left out — see class doc.)
    private static final String[] WANT_SUBS = {
        "From the SEC filings", "The market story", "Key figures",
        "SEC filing history"
    };

    // Whitespace runs collapse to one space (Python re "\s+" on str text
    // matches Unicode whitespace, so we enable UNICODE_CHARACTER_CLASS).
    private static final Pattern WS_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    // Ticker chip at the end of an <h2>, e.g. "Meta Platforms · META".
    // Python: r"·\s*([A-Z]{1,6}(?:\.[A-Z])?)\s*$"
    private static final Pattern TICKER_AT_END =
            Pattern.compile("\u00b7\\s*([A-Z]{1,6}(?:\\.[A-Z])?)\\s*$", Pattern.UNICODE_CHARACTER_CLASS);

    // Ticker chips inside section text, e.g. "Meta Platforms · META".
    // Python: r"·\s*([A-Z]{2,6}(?:\.[A-Z])?)\b"
    private static final Pattern TICKER_CHIP =
            Pattern.compile("\u00b7\\s*([A-Z]{2,6}(?:\\.[A-Z])?)\\b", Pattern.UNICODE_CHARACTER_CLASS);

    // Stray ticker chip on its own line inside "SEC filing history".
    private static final Pattern BARE_TICKER =
            Pattern.compile("[A-Z]{2,6}(?:\\.[A-Z])?");

    // The "## Research Hub" section: from its heading up to (not including)
    // the next "## " heading. Python: r"^## (?:Research Hub|Stock Research Hub)\n.*?(?=^## )"
    private static final Pattern HUB_SECTION = Pattern.compile(
            "^## (?:Research Hub|Stock Research Hub)\n.*?(?=^## )",
            Pattern.MULTILINE | Pattern.DOTALL);

    // A tiny HTML5-named-entity table. Python's HTMLParser already decodes
    // entities before handing us text (and the script unescapes again), so we
    // decode here plus numeric refs; the real hub file only uses &amp; &gt; &quot;.
    private static final Map<String, String> ENTITIES = buildEntities();

    private static Map<String, String> buildEntities() {
        String[][] pairs = {
            {"amp", "&"}, {"lt", "<"}, {"gt", ">"}, {"quot", "\""}, {"apos", "'"},
            {"nbsp", "\u00a0"}, {"middot", "\u00b7"}, {"ndash", "\u2013"}, {"mdash", "\u2014"},
            {"rsquo", "\u2019"}, {"lsquo", "\u2018"}, {"rdquo", "\u201d"}, {"ldquo", "\u201c"},
            {"hellip", "\u2026"}, {"copy", "\u00a9"}, {"reg", "\u00ae"}, {"trade", "\u2122"},
            {"euro", "\u20ac"}, {"pound", "\u00a3"}, {"yen", "\u00a5"}, {"cent", "\u00a2"},
            {"sect", "\u00a7"}, {"para", "\u00b6"}, {"bull", "\u2022"}, {"laquo", "\u00ab"},
            {"raquo", "\u00bb"}, {"iexcl", "\u00a1"}, {"iquest", "\u00bf"}, {"deg", "\u00b0"},
            {"plusmn", "\u00b1"}, {"times", "\u00d7"}, {"divide", "\u00f7"}, {"ne", "\u2260"},
            {"le", "\u2264"}, {"ge", "\u2265"}, {"infin", "\u221e"}, {"rarr", "\u2192"},
            {"larr", "\u2190"}, {"uarr", "\u2191"}, {"darr", "\u2193"}, {"harr", "\u2194"},
        };
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] p : pairs) {
            m.put(p[0], p[1]);
        }
        return m;
    }

    /**
     * Decode HTML entities the way Python's HTMLParser (convert_charrefs=True)
     * plus html.unescape() does: named entities, decimal &#123; and hex
     * &#x1F; references.
     */
    static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int amp = s.indexOf('&', i);
            if (amp == -1) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, amp);
            int semi = s.indexOf(';', amp + 1);
            if (semi == -1 || semi - amp > 12) {
                out.append('&');
                i = amp + 1;
                continue;
            }
            String body = s.substring(amp + 1, semi);
            String repl = null;
            if (body.startsWith("#x") || body.startsWith("#X")) {
                try {
                    repl = new String(Character.toChars(Integer.parseInt(body.substring(2), 16)));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (body.startsWith("#")) {
                try {
                    repl = new String(Character.toChars(Integer.parseInt(body.substring(1))));
                } catch (IllegalArgumentException ignored) {
                }
            } else {
                repl = ENTITIES.get(body);
            }
            if (repl == null) {
                out.append(s, amp, semi + 1); // unknown: leave as-is, like Python
            } else {
                out.append(repl);
            }
            i = semi + 1;
        }
        return out.toString();
    }

    /** Python str.strip() — strips Unicode White_Space (incl. non-breaking space). */
    static String pyStrip(String s) {
        return s.replaceAll("^[\\p{IsWhite_Space}]+|[\\p{IsWhite_Space}]+$", "");
    }

    /** One parsed hub company: its subsections and the ticker chips seen. */
    static final class Company {
        // subsection name -> text chunks, in document order
        final LinkedHashMap<String, List<String>> subs = new LinkedHashMap<>();
        final List<String> tickers = new ArrayList<>();
    }

    /**
     * State machine mirroring the Python HubParser(HTMLParser).
     * <ul>
     *   <li>&lt;h2&gt; starts a company name (mode "co")</li>
     *   <li>&lt;h3&gt; is an index card between dossiers — it only clears the
     *       current subsection, never joins one</li>
     *   <li>&lt;h4&gt; starts a subsection name (mode "sub")</li>
     *   <li>other text lands in the current subsection's chunk list</li>
     * </ul>
     */
    static final class HubParser {
        final LinkedHashMap<String, Company> cos = new LinkedHashMap<>();
        String curCo = null;
        String curSub = null;
        String mode = null;
        final StringBuilder buf = new StringBuilder();

        void startTag(String tag) {
            if (tag.equals("h2")) {
                mode = "co";
                buf.setLength(0);
            } else if (tag.equals("h3")) {
                curSub = null; // index cards sit between dossier sections; never part of one
            } else if (tag.equals("h4")) {
                mode = "sub";
                buf.setLength(0);
            }
        }

        void endTag(String tag) {
            if (tag.equals("h2") && "co".equals(mode)) {
                String name = pyStrip(WS_RUN.matcher(unescape(buf.toString())).replaceAll(" "));
                curCo = name;
                cos.computeIfAbsent(name, k -> new Company());
                Matcher m = TICKER_AT_END.matcher(name);
                if (m.find()) {
                    String tk = m.group(1);
                    if (!cos.get(name).tickers.contains(tk)) {
                        cos.get(name).tickers.add(tk);
                    }
                }
                curSub = null;
                mode = null;
            } else if (tag.equals("h4") && "sub".equals(mode)) {
                String sub = pyStrip(WS_RUN.matcher(unescape(buf.toString())).replaceAll(" "));
                if (curCo != null) {
                    cos.get(curCo).subs.computeIfAbsent(sub, k -> new ArrayList<>());
                    curSub = sub;
                }
                mode = null;
            }
        }

        void data(String d) {
            d = unescape(d); // Python calls htmlmod.unescape(d) on every data chunk
            if ("co".equals(mode) || "sub".equals(mode)) {
                buf.append(d);
            } else if (curCo != null && curSub != null && !pyStrip(d).isEmpty()) {
                cos.get(curCo).subs.get(curSub).add(pyStrip(d));
                // ticker chip near the section top, e.g. "Meta Platforms · META"
                Matcher m = TICKER_CHIP.matcher(d);
                while (m.find()) {
                    String tk = m.group(1);
                    List<String> tks = cos.get(curCo).tickers;
                    if (!tks.contains(tk)) {
                        tks.add(tk);
                    }
                }
            }
        }
    }

    /**
     * Feed raw HTML through the HubParser. A small hand-rolled scanner:
     * Python's HTMLParser lowercases tag names, decodes entities in text,
     * skips comments/doctypes, and treats self-closing tags as start+end —
     * we do the same.
     */
    static void parseHtml(String html, HubParser p) {
        StringBuilder text = new StringBuilder();
        int i = 0;
        int n = html.length();
        while (i < n) {
            char c = html.charAt(i);
            if (c != '<') {
                text.append(c);
                i++;
                continue;
            }
            if (text.length() > 0) {
                p.data(unescape(text.toString()));
                text.setLength(0);
            }
            if (html.startsWith("<!--", i)) {
                int e = html.indexOf("-->", i + 4);
                i = (e == -1) ? n : e + 3;
                continue;
            }
            if (html.startsWith("<!", i)) { // doctype and friends: skip to '>'
                int e = html.indexOf('>', i + 2);
                i = (e == -1) ? n : e + 1;
                continue;
            }
            if (html.startsWith("<?", i)) {
                int e = html.indexOf("?>", i + 2);
                i = (e == -1) ? n : e + 2;
                continue;
            }
            int e = html.indexOf('>', i + 1);
            if (e == -1) {
                text.append(html.substring(i));
                break;
            }
            String inside = html.substring(i + 1, e);
            boolean closing = inside.startsWith("/");
            boolean selfClose = inside.trim().endsWith("/");
            String raw = closing ? inside.substring(1) : inside;
            int k = 0;
            while (k < raw.length() && Character.isLetterOrDigit(raw.charAt(k))) {
                k++;
            }
            String tagName = raw.substring(0, k).toLowerCase(Locale.ROOT);
            if (closing) {
                p.endTag(tagName);
            } else {
                p.startTag(tagName);
                if (selfClose) {
                    p.endTag(tagName); // like HTMLParser.handle_startendtag
                }
            }
            i = e + 1;
        }
        if (text.length() > 0) {
            p.data(unescape(text.toString()));
        }
    }

    /** Python clean_filings(): drop empty chunks and stray ticker chips. */
    static List<String> cleanFilings(List<String> chunks) {
        List<String> out = new ArrayList<>();
        for (String c : chunks) {
            c = pyStrip(c);
            if (c.isEmpty() || BARE_TICKER.matcher(c).matches()) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /**
     * The user's home directory. Python's os.path.expanduser("~") uses the
     * HOME environment variable; the JVM's user.home property can point
     * elsewhere (e.g. /root when launched from a service account), so we
     * prefer $HOME exactly like the Python does.
     */
    static Path homeDir() {
        String home = System.getenv("HOME");
        return Paths.get((home == null || home.isEmpty())
                ? System.getProperty("user.home") : home);
    }

    /**
     * Run content through the shared Sanitize.java CLI (filter mode) and
     * return the cleaned text. This replaces the Python
     * {@code from sanitize import sanitize}: same guard, same bytes.
     */
    static String sanitize(String content) throws IOException, InterruptedException {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String sanitizeJava =
                homeDir().resolve("workspace/atlas-scripts/Sanitize.java").toString();
        String sharedClasses =
                homeDir().resolve("workspace/java-shared/classes").toString();
        // Note: -Dfile.encoding=UTF-8 is required on the child JVM because
        // Sanitize.java's --stdin mode prints via System.out (platform
        // charset); without it, non-ASCII note content (—, €, →, …) would be
        // mangled to '?' wherever LANG is unset (ASCII default).
        // --class-path: Sanitize references the shared SelfTest harness, so
        // the child must compile with it (missed in the 2026-09-27 move).
        Process proc = new ProcessBuilder(
                        javaBin, "-Dfile.encoding=UTF-8",
                        "--class-path", sharedClasses, sanitizeJava, "--stdin")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        proc.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
        proc.getOutputStream().close();
        byte[] cleaned = proc.getInputStream().readAllBytes();
        int code = proc.waitFor();
        if (code != 0) {
            throw new IOException("Sanitize.java exited with code " + code);
        }
        return new String(cleaned, StandardCharsets.UTF_8);
    }

    /** Minimal JSON parser: objects -> LinkedHashMap, arrays -> ArrayList,
     *  strings -> String, whole numbers -> Long, decimals -> Double. */
    static final class Json {
        private final String s;
        private int pos;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.skipWs();
            if (j.pos != j.s.length()) {
                throw new IllegalArgumentException("Trailing JSON at " + j.pos);
            }
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && " \t\n\r".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
        }

        private Object value() {
            skipWs();
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return obj();
                case '[':
                    return arr();
                case '"':
                    return str();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return num();
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw new IllegalArgumentException("Bad JSON at " + pos);
            }
            pos += lit.length();
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String key = str();
                skipWs();
                pos++; // :
                m.put(key, value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') {
                    return m;
                }
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                l.add(value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') {
                    return l;
                }
            }
        }

        private String str() {
            StringBuilder b = new StringBuilder();
            pos++; // opening quote
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':
                            b.append('"');
                            break;
                        case '\\':
                            b.append('\\');
                            break;
                        case '/':
                            b.append('/');
                            break;
                        case 'b':
                            b.append('\b');
                            break;
                        case 'f':
                            b.append('\f');
                            break;
                        case 'n':
                            b.append('\n');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'u':
                            int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            pos += 4;
                            if (Character.isHighSurrogate((char) cp) && s.startsWith("\\u", pos)) {
                                int lo = Integer.parseInt(s.substring(pos + 2, pos + 6), 16);
                                b.append(Character.toChars(Character.toCodePoint((char) cp, (char) lo)));
                                pos += 6;
                            } else {
                                b.append((char) cp);
                            }
                            break;
                        default:
                            throw new IllegalArgumentException("Bad escape at " + pos);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private Number num() {
            int start = pos;
            while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            String lit = s.substring(start, pos);
            if (lit.matches("-?\\d+")) {
                try {
                    return Long.parseLong(lit);
                } catch (NumberFormatException ignored) {
                }
            }
            return Double.parseDouble(lit);
        }
    }

    /** Python print() of a String list: ['A', 'B'], [] when empty. */
    static String pyList(List<String> items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append('\'').append(items.get(i)).append('\'');
        }
        return b.append(']').toString();
    }

    /** Replace the FIRST match of a pattern with a literal replacement
     *  (Python re.subn semantics for content without backslash escapes). */
    static String replaceFirstLiteral(String text, Pattern pattern, String replacement) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        return text.substring(0, m.start()) + replacement + text.substring(m.end());
    }

    public static void main(String[] args) throws Exception {
        Path base = homeDir();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--base-dir")) {
                base = Paths.get(args[i + 1]);
            }
        }
        Path hub = base.resolve("workspace/ts-spaces/stock-research-hub/index.html");
        Path stocks = base.resolve("workspace/atlas/01 📈 Stocks");
        Path watchlist = base.resolve("workspace/stock_watch/watchlist.json");

        // 1. Parse the hub HTML into companies -> subsections -> text chunks.
        HubParser p = new HubParser();
        parseHtml(Files.readString(hub, StandardCharsets.UTF_8), p);

        // 2. Company name -> ticker (first ticker chip seen in its section).
        LinkedHashMap<String, String> nameToTicker = new LinkedHashMap<>();
        for (Map.Entry<String, Company> e : p.cos.entrySet()) {
            if (!e.getValue().tickers.isEmpty()) {
                nameToTicker.put(e.getKey(), e.getValue().tickers.get(0));
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> watch = (List<Object>) ((Map<String, Object>)
                Json.parse(Files.readString(watchlist, StandardCharsets.UTF_8))).get("tickers");
        String asof = LocalDate.now().toString(); // date.today().isoformat()

        List<String> updated = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // 3. For each watchlist ticker, build its dossier section and fold it in.
        for (Object tObj : watch) {
            String t = (String) tObj;
            String name = null;
            for (Map.Entry<String, String> e : nameToTicker.entrySet()) {
                if (e.getValue().equals(t)) {
                    name = e.getKey();
                    break;
                }
            }
            if (name == null) {
                missing.add(t);
                continue;
            }
            Map<String, List<String>> subs = p.cos.get(name).subs;
            List<String> parts = new ArrayList<>();
            parts.add("## Stock Research Hub");
            parts.add("_Hub dossier as of " + asof + ". Valuation figures live in the table above "
                    + "(refreshed from JSON backends) \u2014 the hub's own cross-reference snapshot "
                    + "is superseded by it._");
            parts.add("");
            for (String sub : WANT_SUBS) {
                List<String> chunks = new ArrayList<>(
                        subs.getOrDefault(sub, new ArrayList<>()));
                if (sub.equals("SEC filing history")) {
                    chunks = cleanFilings(chunks);
                }
                if (chunks.isEmpty()) {
                    continue;
                }
                parts.add("### " + sub);
                if (sub.equals("Key figures")) {
                    parts.add(String.join(" \u00b7 ", chunks));
                } else {
                    for (String c : chunks) {
                        parts.add("- " + c);
                    }
                }
                parts.add("");
            }
            // "\n".join(parts).rstrip() + "\n", then one more "\n" as the
            // re.subn replacement text — exactly like the Python.
            String section = String.join("\n", parts).replaceAll("(?U)\\s+\\z", "") + "\n";
            String replacement = section + "\n";

            Path notePath = stocks.resolve(t + ".md");
            String text = Files.readString(notePath, StandardCharsets.UTF_8);
            String newText = replaceFirstLiteral(text, HUB_SECTION, replacement);
            if (newText == null) {
                skipped.add(t);
                continue;
            }
            // Vault-write guard: sanitize right before the write (replaces
            // the Python `from sanitize import sanitize`).
            Files.writeString(notePath, sanitize(newText), StandardCharsets.UTF_8);
            updated.add(t);
        }

        System.out.println("updated: " + updated.size()
                + ", missing hub section: " + pyList(missing)
                + ", skipped: " + pyList(skipped));
    }
}
