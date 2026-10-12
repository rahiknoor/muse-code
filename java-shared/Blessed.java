import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared blessed/proposal lookup from {@code dcf_csv/proposals/changelog.md}.
 *
 * <p>Added 2026-09-30 (Rahik's DRY rule): both RefreshValuations (generated
 * "## Sources" blocks) and StampResearchBriefs (live-value lines) need the
 * latest (proposal, blessed-date) per column/ticker. One parser, not two.
 *
 * <p>Changelog shape: {@code ## <ISO-ts>} blocks, each with an optional
 * {@code prop-XXX APPROVED by muse-audit} line and
 * {@code - column/TICKER: old -> new} lines. The last APPROVED entry per
 * {@code column/TICKER} wins; REJECTED blocks never contribute.
 */
public final class Blessed {

    private Blessed() {
        // static-only
    }

    /** Latest (proposal id, yyyy-mm-dd) for one column/ticker. */
    public static final class Info {
        public final String prop;
        public final String date;

        public Info(String prop, String date) {
            this.prop = prop;
            this.date = date;
        }
    }

    private static final Pattern TS = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern PROP =
            Pattern.compile("^(prop-\\S+) APPROVED by muse-audit", Pattern.MULTILINE);
    private static final Pattern LINE = Pattern.compile(
            "^\\- (ours|notebooklm|forward|creator|analyst)/([A-Za-z.]+):", Pattern.MULTILINE);

    /** Parse the changelog; keys are {@code "column/TICKER"}. */
    public static Map<String, Info> parseChangelog(String text) {
        Map<String, Info> out = new LinkedHashMap<>();
        for (String block : text.split("(?m)^## ")) {
            Matcher tm = TS.matcher(block);
            if (!tm.find()) {
                continue;
            }
            Matcher pm = PROP.matcher(block);
            if (!pm.find()) {
                continue; // REJECTED or non-proposal block
            }
            Matcher lm = LINE.matcher(block);
            while (lm.find()) {
                out.put(lm.group(1) + "/" + lm.group(2),
                        new Info(pm.group(1), tm.group(1)));
            }
        }
        return out;
    }

    /**
     * Human blessed segment for a Sources/live-value line. Per-ticker
     * proposal first, column-level bulk blessed_at second, explicit
     * "not recorded" last — never a plausible fill.
     */
    public static String blessedSeg(String col, String ticker,
                                    Map<String, Info> info, Map<String, String> colDates) {
        Info bi = info.get(col + "/" + ticker);
        if (bi != null) {
            return "blessed " + bi.date + " via " + bi.prop;
        }
        String cd = colDates.get(col);
        if (cd != null) {
            return "blessed " + cd + " (bulk; no per-ticker proposal recorded)";
        }
        return "blessed date not recorded";
    }

    /** Both halves of the blessed lookup: per-ticker changelog + column bulk dates. */
    public static final class Store {
        public final Map<String, Info> info;
        public final Map<String, String> colDates;

        public Store(Map<String, Info> info, Map<String, String> colDates) {
            this.info = info;
            this.colDates = colDates;
        }
    }

    /**
     * Load the blessed lookup from a dcf_csv dir: per-ticker (proposal, date)
     * from proposals/changelog.md, column-level blessed_at from blessed.json
     * as the bulk fallback. Missing/unparseable files degrade to empty maps —
     * callers say "not recorded", never fail the run.
     */
    public static Store load(Path dcfDir) {
        Map<String, Info> info = new LinkedHashMap<>();
        Map<String, String> colDates = new LinkedHashMap<>();
        try {
            Path changelog = dcfDir.resolve("proposals/changelog.md");
            if (Files.exists(changelog)) {
                info = parseChangelog(
                        Files.readString(changelog, StandardCharsets.UTF_8));
            }
            Path blessed = dcfDir.resolve("blessed.json");
            if (Files.exists(blessed)) {
                Map<String, Object> root = Json.parse(
                        Files.readString(blessed, StandardCharsets.UTF_8));
                @SuppressWarnings("unchecked")
                Map<String, Object> cols = (Map<String, Object>) root.get("columns");
                if (cols != null) {
                    for (String col : new String[]{"ours", "notebooklm", "forward",
                            "creator", "analyst"}) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cm = (Map<String, Object>) cols.get(col);
                        if (cm != null) {
                            Object ba = cm.get("blessed_at");
                            if (ba instanceof String && ((String) ba).length() >= 10) {
                                colDates.put(col, ((String) ba).substring(0, 10));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // degrade: empty maps -> "not recorded"
        }
        return new Store(info, colDates);
    }

    /** Minimal JSON parser for the small config files Blessed reads. */
    public static final class Json {
        private final String s;
        private int p;

        private Json(String s) {
            this.s = s;
        }

        public static Map<String, Object> parse(String s) {
            Json j = new Json(s);
            j.ws();
            Object v = j.value();
            if (!(v instanceof Map)) {
                throw new IllegalArgumentException("expected object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return m;
        }

        private void ws() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
                p++;
            }
        }

        private Object value() {
            ws();
            if (p >= s.length()) {
                throw new IllegalArgumentException("eof");
            }
            char c = s.charAt(p);
            if (c == '{') {
                return obj();
            }
            if (c == '[') {
                return arr();
            }
            if (c == '"') {
                return str();
            }
            if (c == 't' || c == 'f') {
                return bool();
            }
            if (c == 'n') {
                p += 4;
                return null;
            }
            return num();
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            p++; // {
            ws();
            if (p < s.length() && s.charAt(p) == '}') {
                p++;
                return m;
            }
            while (true) {
                ws();
                String k = str();
                ws();
                p++; // :
                m.put(k, value());
                ws();
                char c = s.charAt(p++);
                if (c == '}') {
                    break;
                }
            }
            return m;
        }

        private java.util.List<Object> arr() {
            java.util.List<Object> l = new java.util.ArrayList<>();
            p++; // [
            ws();
            if (p < s.length() && s.charAt(p) == ']') {
                p++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(p++);
                if (c == ']') {
                    break;
                }
            }
            return l;
        }

        private String str() {
            StringBuilder b = new StringBuilder();
            p++; // "
            while (true) {
                char c = s.charAt(p++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char e = s.charAt(p++);
                    b.append(switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case 'u' -> (char) Integer.parseInt(s.substring(p, p + 4), 16);
                        default -> e;
                    });
                    if (e == 'u') {
                        p += 4;
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        private Boolean bool() {
            if (s.startsWith("true", p)) {
                p += 4;
                return Boolean.TRUE;
            }
            p += 5;
            return Boolean.FALSE;
        }

        private Double num() {
            int q = p;
            while (q < s.length() && "-+0123456789.eE".indexOf(s.charAt(q)) >= 0) {
                q++;
            }
            Double d = Double.parseDouble(s.substring(p, q));
            p = q;
            return d;
        }
    }
}
