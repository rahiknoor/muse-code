import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refresh Atlas stock-note valuation tables + frontmatter from JSON backends.
 *
 * <p>Java port of {@code refresh_valuations.py} (2026-09-27) — behavior-identical.
 *
 * <p>Reads:
 * <ul>
 *   <li>{@code ~/workspace/stock_watch/price_cache.json} → market price</li>
 *   <li>{@code ~/workspace/dcf_csv/notebooklm_values.json} → NotebookLM grounded value</li>
 *   <li>{@code ~/workspace/dcf_csv/notebooklm_forward.json} → NotebookLM forward-tilt value</li>
 *   <li>{@code ~/workspace/dcf_csv/our_dcf.json} → Muse DCF (one number)</li>
 *   <li>{@code ~/workspace/dcf_csv/growth_outlook.json} → Muse's growth outlook
 *       (High/Moderate/Low + one-line thesis; "not yet researched" until that
 *       ticker's brief lands)</li>
 *   <li>{@code ~/workspace/dcf_csv/analyst_targets.json} → projection price (analyst target)</li>
 *   <li>{@code ~/workspace/dcf_csv/creator_values.json} → latest creator fair value/target</li>
 *   <li>{@code ~/workspace/dcf_csv/speculation_price.json} → Muse speculation
 *       price (NotebookLM's slot reserved — null until it derives it itself)</li>
 *   <li>{@code ~/workspace/dcf_csv/outside_voice.json} → raw outside median</li>
 *   <li>{@code ~/workspace/dcf_csv/website_consensus.json} → website DCF consensus</li>
 * </ul>
 *
 * <p>For each {@code ~/workspace/atlas/01 📈 Stocks/<TICKER>.md}:
 * <ul>
 *   <li>rewrites the 11-row valuation table with fresh values (Growth outlook
 *       row sits right after the Our DCF row; speculation rows after their
 *       voices; website consensus + outside voice close the block)</li>
 *   <li>relabels "Analyst consensus target" → "Projection price (analyst target)"</li>
 *   <li>adds Dataview-friendly frontmatter fields (market_price,
 *       notebooklm, notebooklm_forward, dcf, muse_speculation,
 *       nlm_speculation, outside_voice, growth_outlook, growth_thesis,
 *       projection_price, upside_pct, upside_projection_pct, values_asof)</li>
 *   <li>REGENERATES the "## Sources" section from this run's backends
 *       (2026-09-30, Rahik's "one writer per section" rule): the hand-written
 *       block rotted next to the machine table (AMZN's table said $174.80
 *       while its Sources said $152.42). Every money figure in Sources is the
 *       same Money.fmtMoney() of the same Double the table prints, plus blessed
 *       date/proposal from proposals/changelog.md — the two sections are
 *       drawn from one run and cannot disagree. Non-value provenance (IRA
 *       membership, filings folder, hub dossier) is a static template.</li>
 * </ul>
 *
 * <p>Leaves Research Hub / Related sections untouched.
 * Idempotent: rerunning with unchanged backends produces no diff.
 *
 * <p>Run:
 * <pre>
 *   ~/workspace/java/jdk-21/bin/java ~/workspace/atlas-scripts/RefreshValuations.java [--base-dir DIR]
 * </pre>
 * {@code --base-dir} is a test-only override (defaults to the user home);
 * normal runs omit it and behave exactly like the Python script.
 */
public class RefreshValuations {

    // The existing valuation table: header + separator + 5, 6, or 7 "| ... |" rows.
    // 5-row layout (pre-2026-09-28): Market, Our DCF, NotebookLM value,
    // YouTube creators, Projection price. 6-row layout adds the NotebookLM
    // forward tilt row after the NotebookLM value row. 7-row layout
    // (2026-09-28) adds the Growth outlook row after the Our DCF row.
    // The {5,7} quantifier lets the migration match the old 5/6-row tables
    // AND stay idempotent on the new 7-row tables; the whole block is
    // replaced by the fresh table.
    // Python: r"\| Source \| Value \|\n\|---\|---\|\n(?:\| .*\|\n){5,7}" with re.M
    private static final Pattern VALUATION_TABLE = Pattern.compile(
            "\\| Source \\| Value \\|\\n\\|---\\|---\\|\\n(?:\\| .*\\|\\n){5,11}",
            Pattern.MULTILINE);

    // The frontmatter block: opening "---" line through the next line starting
    // with "---". Python: r"^---\n(.*?)^---" with re.M | re.S
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\n(.*?)^---", Pattern.MULTILINE | Pattern.DOTALL);

    // Old generated frontmatter fields, dropped before fresh ones are merged.
    // Python: r"^(market_price|dcf_original|...|creators):.*\n" with re.M
    private static final Pattern OLD_FIELDS = Pattern.compile(
            "^(market_price|dcf_original|dcf_fixed|dcf|notebooklm|notebooklm_forward|projection_price|"
                    + "growth_outlook|growth_thesis|"
                    + "muse_speculation|nlm_speculation|outside_voice|"
                    + "upside_fixed_pct|upside_pct|upside_projection_pct|values_asof|creators):.*\\n",
            Pattern.MULTILINE);

    /** Python float(v): None stays null, anything numeric becomes a double. */
    static Double num(Object v) {
        return (v instanceof Number) ? ((Number) v).doubleValue() : null;
    }

    /** Ticker row from a {"tickers": {...}} backend; empty map when the
     *  ticker is absent OR explicitly null (getOrDefault returns the null). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> rowOrEmpty(Map<String, Object> tickers, String t) {
        Object row = tickers.getOrDefault(t, Map.of());
        return (row instanceof Map) ? (Map<String, Object>) row : Map.of();
    }

    // Money formatting lives in java-shared/Money.java (the vault's single
    // fmtMoney). Call sites below use Money.fmtMoney / Money.truthy /
    // Money.grouped / Money.fixed2.

    /**
     * Python str(float): shortest round-trip text ("150.25", "150.0").
     * Java's Double.toString matches for ordinary magnitudes; scientific
     * notation is restyled to Python's form ("1e-05", not "1.0E-5").
     */
    static String pyRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        String s = Double.toString(d);
        int e = s.indexOf('E');
        if (e < 0) {
            return s;
        }
        String mant = s.substring(0, e);
        String exp = s.substring(e + 1);
        if (mant.endsWith(".0")) { // Python: repr(1e16) == '1e+16'
            mant = mant.substring(0, mant.length() - 2);
        }
        char sign = exp.startsWith("-") ? '-' : '+';
        String digits = (exp.startsWith("-") || exp.startsWith("+")) ? exp.substring(1) : exp;
        if (digits.length() < 2) {
            digits = "0" + digits; // Python pads the exponent: '1e-05'
        }
        return mant + "e" + sign + digits;
    }

    /** Python yv(): "null" for None, else str(v). */
    static String yamlVal(Double v) {
        return v == null ? "null" : pyRepr(v);
    }

    /**
     * A raw JSON number as the Python f-string would print it: ints stay
     * ints ("158"), floats print shortest ("158.5").
     */
    static String rawNumber(Object v) {
        if (v instanceof Long) {
            return v.toString();
        }
        return pyRepr(((Number) v).doubleValue());
    }

    /** Null-safe string for sorting call dates. */
    static String safeStr(Object o) {
        return (o == null) ? "" : o.toString();
    }

    /**
     * The per-creator latest scoreable calls for one ticker from
     * creator_calls_by_creator.json. Non-map entries and unscorable buy calls
     * (no stated price) are dropped; callers sort as needed.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> creatorCallList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List)) {
            return out;
        }
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> e = (Map<String, Object>) o;
            Object sc = e.get("scoreable");
            if (sc instanceof Boolean && !((Boolean) sc)) {
                continue;
            }
            if (!(e.get("value") instanceof Number)) {
                continue;
            }
            out.add(e);
        }
        return out;
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
     * Minimal CSV reader (Python csv.reader defaults): commas separate,
     * double-quoted fields may hold commas/newlines, "" is an escaped quote.
     */
    static List<String[]> parseCsv(String text) {
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        List<String[]> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cur.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                cur.add(field.toString());
                field.setLength(0);
                rows.add(cur.toArray(new String[0]));
                cur = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!cur.isEmpty() || field.length() > 0) {
            cur.add(field.toString());
            rows.add(cur.toArray(new String[0]));
        }
        return rows;
    }

    // The hand-written "## Sources" section: replaced wholesale by the
    // generated block (2026-09-30). Captures through the next "## " heading
    // or EOF; the lookahead keeps the next heading in place.
    private static final Pattern SOURCES_SECTION = Pattern.compile(
            "^## Sources\\n(.*?)(?=^## |\\z)", Pattern.MULTILINE | Pattern.DOTALL);

    /**
     * The generated "## Sources" block. Every money figure is the same
     * Money.fmtMoney() of the same Double the valuation table prints — the two
     * sections are drawn from one run and cannot disagree. Blessed
     * date/proposal comes from Blessed.parseChangelog (per-ticker) with a
     * column-level blessed_at fallback; unknown stays "not recorded".
     * A null value gets "no value recorded" — never a borrowed provenance.
     */
    static String valLine(String label, Double v, String blessed, String suffix) {
        if (!Money.truthy(v)) {
            return "- " + label + " \u2014 \u2014 no value recorded.";
        }
        return "- " + label + " " + Money.fmtMoney(v) + " \u2014 " + blessed + " \u2014 " + suffix + ".";
    }

    static String buildSourcesBlock(String t, Double market, String sess,
                                    Double museDcf, Double nblm, Double fwdTilt,
                                    Double museSpec, Double nlmSpec,
                                    String creatorCell, Double projection, Double webConsensus,
                                    Map<String, Blessed.Info> blessed, Map<String, String> colDates) {
        List<String> lines = new ArrayList<>();
        lines.add("## Sources");
        lines.add("- Market price "
                + (Money.truthy(market) ? Money.fmtMoney(market) + " (" + sess + " close)" : "\u2014")
                + " \u2014 `stock_watch/price_cache.json`.");
        lines.add(valLine("Our DCF", museDcf, Blessed.blessedSeg("ours", t, blessed, colDates),
                "methodology-fixed value (mid-year discounting + growth fade)"));
        lines.add(valLine("NotebookLM grounded", nblm,
                Blessed.blessedSeg("notebooklm", t, blessed, colDates),
                "verified re-ask batch (filings-selected sources only;"
                        + " in-chat evidence never grounds this column)"));
        lines.add(valLine("NotebookLM forward tilt", fwdTilt,
                Blessed.blessedSeg("forward", t, blessed, colDates),
                "grounded DCF plus a modest, sourced forward tilt;"
                        + " separate column, never blended"));
        lines.add(valLine("Muse speculation", museSpec, "computed this run",
                "our DCF pulled halfway toward the outside median"
                        + " (`dcf_csv/speculation_price.json`); display-only"));
        lines.add(valLine("NotebookLM speculation", nlmSpec, "transcribed from 'DCF Methodology Check'",
                Money.truthy(nlmSpec)
                        ? "derived by NotebookLM itself"
                        : "not yet derived by NotebookLM"));
        lines.add("- YouTube creators: "
                + (creatorCell.equals("\u2014")
                        ? "\u2014 (no valuation-type call recorded)."
                        : creatorCell + "."));
        lines.add(valLine("Projection price (analyst target)", projection, "backfilled 2026-09-27",
                "`dcf_csv/analyst_targets.json`"));
        lines.add(valLine("Website consensus", webConsensus, "computed this run",
                "median of DCF-comparable sites (`dcf_csv/website_consensus.json`)"));
        lines.add("- IRA membership: Robinhood Roth IRA holdings (`stock_watch/watchlist.json`).");
        lines.add("- SEC filings: `~/workspace/sec_filings/" + t.toLowerCase() + "/`.");
        lines.add("- Stock Research Hub dossier: folded into the '## Stock Research Hub'"
                + " section above by FoldHub.");
        return String.join("\n", lines) + "\n";
    }

    /**
     * Replace the hand-written Sources section with the generated block,
     * keeping one blank line before the next heading. Appends the block at
     * the end when the note has no Sources section.
     */
    static String replaceSourcesSection(String text, String block) {
        Matcher m = SOURCES_SECTION.matcher(text);
        if (m.find()) {
            return text.substring(0, m.start()) + block + "\n" + text.substring(m.end());
        }
        String sep = text.endsWith("\n") ? "" : "\n";
        return text + sep + "\n" + block;
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

    /**
     * Self-test for the Sources regeneration (2026-09-30). Run with:
     * {@code java --class-path ~/workspace/java-shared/classes
     * RefreshValuations.java --self-test}
     */
    static void runSelfTest() {
        // Changelog parsing: latest APPROVED wins, REJECTED ignored.
        String cl = "## 2026-09-28T10:00:00Z\nprop-20260928-001 APPROVED by muse-audit\n"
                + "- notebooklm/AMZN: \u2014 -> 150.0 (n/a)\n"
                + "## 2026-09-29T10:00:00Z\nprop-20260929-001 REJECTED by muse-audit\n"
                + "- notebooklm/AMZN: \u2014 -> 999.0\n"
                + "## 2026-09-29T11:00:00Z\nprop-20260929-002 APPROVED by muse-audit\n"
                + "- notebooklm/AMZN: \u2014 -> 174.8 (n/a)\n";
        Map<String, Blessed.Info> info = Blessed.parseChangelog(cl);
        SelfTest.checkEq("changelog latest approved wins",
                "prop-20260929-002", info.get("notebooklm/AMZN").prop);
        SelfTest.checkEq("changelog latest approved date",
                "2026-09-29", info.get("notebooklm/AMZN").date);
        SelfTest.check("changelog rejected value absent",
                !info.values().stream().anyMatch(i -> i.prop.equals("prop-20260929-001")));

        Map<String, String> colDates = new LinkedHashMap<>();
        colDates.put("ours", "2026-09-27");

        // Full block: every money figure matches the table's fmtMoney.
        String block = buildSourcesBlock("AMZN", 249.27, "2026-09-28",
                252.77843770358243, 174.8, null, 235.42, null,
                "Parkev Tatevosian: intrinsic value $180 (2026-09-27)", 200.0, 190.5,
                info, colDates);
        SelfTest.checkContains("sources our DCF matches table", block, "- Our DCF $252.78");
        SelfTest.checkContains("sources notebooklm value", block, "- NotebookLM grounded $174.80");
        SelfTest.checkContains("sources notebooklm blessed", block,
                "blessed 2026-09-29 via prop-20260929-002");
        SelfTest.checkContains("sources ours bulk fallback", block,
                "blessed 2026-09-27 (bulk; no per-ticker proposal recorded)");
        SelfTest.checkContains("sources forward null", block, "- NotebookLM forward tilt \u2014");
        SelfTest.checkContains("sources null says no value recorded", block,
                "- NotebookLM forward tilt \u2014 \u2014 no value recorded.");
        SelfTest.checkEq("blessed seg unknown",
                "blessed date not recorded",
                Blessed.blessedSeg("forward", "ZZZ", info, colDates));
        SelfTest.checkNotContains("sources no plausible fill", block, "999");
        SelfTest.checkContains("sources market line", block,
                "- Market price $249.27 (2026-09-28 close)");
        SelfTest.checkContains("sources filings path", block, "sec_filings/amzn/");

        // Section replacement on a synthetic note.
        String note = "# AMZN\n\n| Source | Value |\n|---|---|\n| Market price | $249.27 |\n\n"
                + "## Sources\n- Market price $200 (old): stale.\n- NotebookLM value $152.42: stale.\n\n"
                + "## Related\n- [[X]]\n";
        String out = replaceSourcesSection(note, block);
        SelfTest.checkNotContains("old sources block gone", out, "$152.42");
        SelfTest.checkContains("table untouched", out, "| Market price | $249.27 |");
        SelfTest.checkContains("related intact", out, "## Related");
        SelfTest.checkContains("new block present", out, "- Our DCF $252.78");
        SelfTest.checkEq("sources replacement idempotent", out,
                replaceSourcesSection(out, block));

        // Missing section: appended at end.
        String noSources = "# AMZN\n\nSome text.\n";
        String appended = replaceSourcesSection(noSources, block);
        SelfTest.checkContains("missing section appended", appended, "## Sources");
        SelfTest.check("appended block at end", appended.endsWith(block));

        // Per-creator call list (Rahik 2026-10-03, option 1): unscorable and
        // malformed entries are dropped, callers sort newest-first.
        List<Object> rawCalls = List.of(
                Map.of("creator", "Parkev Tatevosian", "type", "fair_value",
                        "value", 338L, "call_date", "2026-09-27", "scoreable", true),
                Map.of("creator", "Couch Investor", "type", "price_target",
                        "value", 400L, "call_date", "2026-09-29", "scoreable", true),
                Map.of("creator", "Jeremy Lefebvre", "type", "buy_price",
                        "value", 0L, "call_date", "2026-10-02", "scoreable", false),
                "not-a-map");
        List<Map<String, Object>> calls = creatorCallList(rawCalls);
        SelfTest.checkEq("creatorCallList drops unscorable+malformed", 2, calls.size());
        SelfTest.check("creatorCallList keeps only scoreable",
                calls.stream().noneMatch(
                        e -> "Jeremy Lefebvre".equals(e.get("creator"))));
        SelfTest.checkEq("creatorCallList non-list -> empty", 0,
                creatorCallList("nope").size());
        SelfTest.checkEq("creatorCallList null -> empty", 0,
                creatorCallList(null).size());
        SelfTest.checkEq("safeStr null", "", safeStr(null));

        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path base = homeDir();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--base-dir")) {
                base = Paths.get(args[i + 1]);
            }
        }
        Path dcf = base.resolve("workspace/dcf_csv");
        Path stocks = base.resolve("workspace/atlas/01 📈 Stocks");

        // 1. Load every JSON backend.
        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) Json.parse(Files.readString(
                base.resolve("workspace/stock_watch/price_cache.json"), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> quotes = (Map<String, Object>) cache.get("quotes");
        if (quotes == null || quotes.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> alt = (Map<String, Object>) cache.get("prices");
            quotes = (alt == null) ? new LinkedHashMap<>() : alt;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> ourDcf = (Map<String, Object>)
                ((Map<String, Object>) Json.parse(Files.readString(
                        dcf.resolve("our_dcf.json"), StandardCharsets.UTF_8))).get("values");
        @SuppressWarnings("unchecked")
        Map<String, Object> notebooklm = (Map<String, Object>)
                ((Map<String, Object>) Json.parse(Files.readString(
                        dcf.resolve("notebooklm_values.json"), StandardCharsets.UTF_8))).get("values");
        // notebooklm_forward.json is written by RebuildAll; before the first
        // rebuild that includes it, treat every forward value as missing
        // rather than failing the whole Atlas refresh.
        @SuppressWarnings("unchecked")
        Map<String, Object> forward = Files.exists(dcf.resolve("notebooklm_forward.json"))
                ? (Map<String, Object>)
                    ((Map<String, Object>) Json.parse(Files.readString(
                            dcf.resolve("notebooklm_forward.json"), StandardCharsets.UTF_8))).get("values")
                : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> analyst = (Map<String, Object>) Json.parse(Files.readString(
                dcf.resolve("analyst_targets.json"), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> creators = (Map<String, Object>)
                ((Map<String, Object>) Json.parse(Files.readString(
                        dcf.resolve("creator_values.json"), StandardCharsets.UTF_8))).get("latest");
        // creator_calls_by_creator.json: per-creator latest scoreable call per
        // ticker (Rahik 2026-10-03, option 1 — every creator's call is visible
        // on each stock's note, not just the single latest from
        // creator_values.json). Missing file = fall back to the old
        // single-creator path per ticker.
        Path creatorCallsPath = base.resolve("workspace/creator_tracker/creator_calls_by_creator.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> creatorCalls = Files.exists(creatorCallsPath)
                ? (Map<String, Object>) Json.parse(
                        Files.readString(creatorCallsPath, StandardCharsets.UTF_8))
                : Map.of();
        // growth_outlook.json: Muse's per-ticker growth assessment from the
        // deep-research forward brief. Null until that ticker's brief lands —
        // displayed as "not yet researched", never a plausible fill.
        @SuppressWarnings("unchecked")
        Map<String, Object> growthTickers = Files.exists(dcf.resolve("growth_outlook.json"))
                ? (Map<String, Object>)
                    ((Map<String, Object>) Json.parse(Files.readString(
                            dcf.resolve("growth_outlook.json"), StandardCharsets.UTF_8))).get("tickers")
                : Map.of();
        // speculation_price.json: Muse's speculation price per ticker
        // (NotebookLM's slot is reserved — null until it derives it itself).
        @SuppressWarnings("unchecked")
        Map<String, Object> specTickers = Files.exists(dcf.resolve("speculation_price.json"))
                ? (Map<String, Object>)
                    ((Map<String, Object>) Json.parse(Files.readString(
                            dcf.resolve("speculation_price.json"), StandardCharsets.UTF_8))).get("tickers")
                : Map.of();
        // outside_voice.json: the raw outside median per ticker.
        @SuppressWarnings("unchecked")
        Map<String, Object> outsideTickers = Files.exists(dcf.resolve("outside_voice.json"))
                ? (Map<String, Object>)
                    ((Map<String, Object>) Json.parse(Files.readString(
                            dcf.resolve("outside_voice.json"), StandardCharsets.UTF_8))).get("tickers")
                : Map.of();
        // website_consensus.json: the website DCF consensus per ticker.
        @SuppressWarnings("unchecked")
        Map<String, Object> webTickers = Files.exists(dcf.resolve("website_consensus.json"))
                ? (Map<String, Object>)
                    ((Map<String, Object>) Json.parse(Files.readString(
                            dcf.resolve("website_consensus.json"), StandardCharsets.UTF_8))).get("tickers")
                : Map.of();
        String asof = LocalDate.now().toString(); // date.today().isoformat()

        // 1b. Blessed metadata for the generated Sources blocks: per-ticker
        // (proposal, date) from the changelog, column-level blessed_at as the
        // bulk fallback (shared loader in java-shared/Blessed.java).
        Blessed.Store blessedStore = Blessed.load(dcf);
        Map<String, Blessed.Info> blessedInfo = blessedStore.info;
        Map<String, String> colBlessedDates = blessedStore.colDates;

        // 2. The comparison CSV is the workbook export; used only as a sanity
        // cross-check — the ticker roster comes from its row 3 onward.
        List<String[]> rows = parseCsv(Files.readString(
                dcf.resolve("dcf-comparison.csv"), StandardCharsets.UTF_8));
        String[] hdr = rows.get(2);
        int ti = -1;
        for (int i = 0; i < hdr.length; i++) {
            if (hdr[i].equals("Ticker")) {
                ti = i;
                break;
            }
        }
        if (ti == -1) {
            throw new IllegalStateException("No Ticker column in comparison CSV");
        }

        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // 3. Refresh each note's valuation table and frontmatter.
        // One sanitizer child for all notes (batch mode); each note is still
        // sanitized immediately before its vault write.
        try (VaultSanitize san = new VaultSanitize()) {
            for (int r = 3; r < rows.size(); r++) {
                    String[] row = rows.get(r);
                    if (row.length <= ti || row[ti].strip().isEmpty()) {
                        continue;
                    }
                    String t = row[ti].strip();
                    Path notePath = stocks.resolve(t + ".md");
                    if (!Files.exists(notePath)) {
                        skipped.add(t);
                        continue;
                    }

                Object q = quotes.get(t);
                Double price;
                String sess;
                if (q instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> qm = (Map<String, Object>) q;
                    price = num(qm.get("price"));
                    Object sd = qm.get("session_date");
                    sess = (sd == null ? "" : (String) sd).strip();
                } else {
                    price = num(q);
                    sess = "";
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> dcfRow = (Map<String, Object>) ourDcf.getOrDefault(t, Map.of());
                @SuppressWarnings("unchecked")
                Map<String, Object> nbRow = (Map<String, Object>) notebooklm.getOrDefault(t, Map.of());
                @SuppressWarnings("unchecked")
                Map<String, Object> fwdRow = (Map<String, Object>) forward.getOrDefault(t, Map.of());
                @SuppressWarnings("unchecked")
                Map<String, Object> projRow = (Map<String, Object>) analyst.getOrDefault(t, Map.of());
                @SuppressWarnings("unchecked")
                Map<String, Object> cr = (Map<String, Object>) creators.get(t);

                Double market = num(price);
                Double museDcf = num(dcfRow.get("value"));
                Double nblm = num(nbRow.get("value"));
                Double fwdTilt = num(fwdRow.get("value"));
                Double projection = num(projRow.get("target"));
                // Speculation / outside voices (Rahik 2026-09-29). Muse's
                // speculation is computed; NotebookLM's slot is reserved
                // (null until it derives it itself — never computed here).
                // A ticker may map to explicit null (e.g. QXO's outside
                // median) — getOrDefault returns the null, not the default.
                Map<String, Object> specRow = rowOrEmpty(specTickers, t);
                Map<String, Object> ovRow = rowOrEmpty(outsideTickers, t);
                Map<String, Object> webRow = rowOrEmpty(webTickers, t);
                Double museSpec = num(specRow.get("muse_speculation"));
                Double nlmSpec = num(specRow.get("nlm_speculation"));
                Double outsideVoice = num(ovRow.get("value"));
                Double webConsensus = num(webRow.get("consensus"));

                // Growth outlook: Muse's assessment beside the Muse DCF.
                String growthCell;
                String gOut = null;
                String gThesis = null;
                Object go = growthTickers.get(t);
                if (go instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> goMap = (Map<String, Object>) go;
                    Object ov = goMap.get("outlook");
                    Object tv = goMap.get("thesis");
                    gOut = (ov instanceof String) ? (String) ov : null;
                    gThesis = (tv instanceof String) ? (String) tv : null;
                }
                if (gOut == null) {
                    growthCell = "not yet researched";
                } else {
                    growthCell = (gThesis == null || gThesis.isEmpty()) ? gOut
                            : gOut + " \u2014 " + gThesis;
                }
                // The 10-row valuation table: grounded, forward tilt, Muse DCF —
                // three separate intrinsic values, never blended — with the
                // growth outlook row right after the Muse DCF, the two
                // speculation prices after their voices, and the website
                // consensus closing the block. NotebookLM's
                // speculation slot is reserved ("—") until it derives it
                // itself. Outside voice is backend-only, not displayed.
                String marketCell = Money.truthy(market)
                        ? Money.fmtMoney(market) + " (" + sess + " close)" : "\u2014";
                String creatorCell;
                List<String> creatorFrontParts = new ArrayList<>();
                {
                    // Per-creator latest calls (newest first); unscorable
                    // buy calls (no stated price) are excluded.
                    List<Map<String, Object>> calls = creatorCallList(creatorCalls.get(t));
                    calls.sort((a, b) -> safeStr(b.get("call_date"))
                            .compareTo(safeStr(a.get("call_date"))));
                    List<String> parts = new ArrayList<>();
                    for (Map<String, Object> e : calls) {
                        Object cd = e.get("call_date");
                        String date = (cd == null || ((String) cd).isEmpty())
                                ? "date n/a" : (String) cd;
                        parts.add(e.get("creator") + ": "
                                + ((String) e.get("type")).replace('_', ' ') + " "
                                + "$" + Money.grouped(Money.fixed2(
                                        ((Number) e.get("value")).doubleValue()))
                                + " (" + date + ")");
                        creatorFrontParts.add(e.get("creator") + ": "
                                + e.get("type") + " $" + rawNumber(e.get("value")));
                    }
                    if (parts.isEmpty() && cr != null) {
                        // Fallback: old single-creator path when the view
                        // file has no entry for this ticker.
                        Object callDate = cr.get("call_date");
                        String cd = (callDate == null || ((String) callDate).isEmpty())
                                ? "date n/a" : (String) callDate;
                        parts.add(cr.get("creator") + ": "
                                + ((String) cr.get("type")).replace('_', ' ') + " "
                                + "$" + Money.grouped(Money.fixed2(
                                        ((Number) cr.get("value")).doubleValue()))
                                + " (" + cd + ")");
                        creatorFrontParts.add(cr.get("creator") + ": "
                                + cr.get("type") + " $" + rawNumber(cr.get("value")));
                    }
                    creatorCell = parts.isEmpty() ? "\u2014" : String.join("; ", parts);
                }
                List<String> table = List.of(
                        "| Source | Value |",
                        "|---|---|",
                        "| Market price | " + marketCell + " |",
                        "| Our DCF | " + Money.fmtMoney(museDcf) + " |",
                        "| Muse speculation price | " + Money.fmtMoney(museSpec) + " |",
                        "| Growth outlook | " + growthCell + " |",
                        "| NotebookLM value | " + Money.fmtMoney(nblm) + " |",
                        "| NotebookLM speculation price | " + Money.fmtMoney(nlmSpec) + " |",
                        "| NotebookLM forward tilt | " + Money.fmtMoney(fwdTilt) + " |",
                        "| YouTube creators | " + creatorCell + " |",
                        "| Projection price (analyst target) | " + Money.fmtMoney(projection) + " |",
                        "| Website consensus | " + Money.fmtMoney(webConsensus) + " |");

                String text = Files.readString(notePath, StandardCharsets.UTF_8);
                Matcher tm = VALUATION_TABLE.matcher(text);
                if (!tm.find()) {
                    skipped.add(t);
                    continue;
                }
                // Literal replacement (Python re.subn with plain content).
                String newText = text.substring(0, tm.start())
                        + String.join("\n", table) + "\n"
                        + text.substring(tm.end());

                // 4. Frontmatter: merge Dataview fields, preserving existing keys.
                Matcher fm = FRONTMATTER.matcher(newText);
                if (fm.find()) {
                    String body = OLD_FIELDS.matcher(fm.group(1)).replaceAll("");
                    // Python round(x, 1): banker's rounding on the binary value.
                    // Python: round((dcf / market - 1) * 100, 1) — the *100 happens
                    // in double arithmetic first; round() is banker's rounding on
                    // the resulting binary value.
                    Double upsD = (Money.truthy(museDcf) && Money.truthy(market))
                            ? new BigDecimal((museDcf / market - 1) * 100)
                                    .setScale(1, RoundingMode.HALF_EVEN).doubleValue()
                            : null;
                    Double upsP = (Money.truthy(projection) && Money.truthy(market))
                            ? new BigDecimal((projection / market - 1) * 100)
                                    .setScale(1, RoundingMode.HALF_EVEN).doubleValue()
                            : null;
                    StringBuilder extra = new StringBuilder();
                    extra.append("market_price: ").append(yamlVal(market)).append("\n");
                    extra.append("notebooklm: ").append(yamlVal(nblm)).append("\n");
                    extra.append("notebooklm_forward: ").append(yamlVal(fwdTilt)).append("\n");
                    extra.append("dcf: ").append(yamlVal(museDcf)).append("\n");
                    extra.append("muse_speculation: ").append(yamlVal(museSpec)).append("\n");
                    extra.append("nlm_speculation: ").append(yamlVal(nlmSpec)).append("\n");
                    extra.append("outside_voice: ").append(yamlVal(outsideVoice)).append("\n");
                    extra.append("growth_outlook: ")
                            .append(gOut == null ? "null" : "\"" + gOut + "\"").append("\n");
                    extra.append("growth_thesis: ")
                            .append(gThesis == null ? "null"
                                    : "\"" + gThesis.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                            .append("\n");
                    extra.append("projection_price: ").append(yamlVal(projection)).append("\n");
                    extra.append("upside_pct: ").append(yamlVal(upsD)).append("\n");
                    extra.append("upside_projection_pct: ").append(yamlVal(upsP)).append("\n");
                    extra.append("values_asof: ").append(asof).append("\n");
                    if (!creatorFrontParts.isEmpty()) {
                        extra.append("creators: \"")
                                .append(String.join("; ", creatorFrontParts))
                                .append("\"\n");
                    }
                    String repl = "---\n" + body + extra + "---";
                    newText = newText.substring(0, fm.start()) + repl
                            + newText.substring(fm.end());
                }

                // 4b. Sources: one writer. The hand-written block rotted next to
                // the machine table; regenerate it from this run's backends so
                // the two sections cannot disagree.
                String sourcesBlock = buildSourcesBlock(t, market, sess, museDcf, nblm,
                        fwdTilt, museSpec, nlmSpec, creatorCell, projection, webConsensus,
                        blessedInfo, colBlessedDates);
                newText = replaceSourcesSection(newText, sourcesBlock);

                // 5. Vault-write guard: sanitize right before the write (replaces
                // the Python `from sanitize import sanitize`).
                Files.writeString(notePath, san.sanitize(newText), StandardCharsets.UTF_8);
                updated.add(t);
            }
        }

        System.out.println("updated: " + updated.size() + ", skipped: " + pyList(skipped));
    }
}
