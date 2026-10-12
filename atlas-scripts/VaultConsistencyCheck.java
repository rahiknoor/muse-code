import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VaultConsistencyCheck — the permanent fail-closed Atlas consistency audit.
 *
 * <p>Rahik 2026-09-30 ("is this all being audited somehow or we're just
 * letting numbers pass by?"): RefreshValuations and StampResearchBriefs own
 * their sections, but nothing ever verified the vault actually matches the
 * backends. This tool is the independent reader: it re-derives what every
 * stock note and research brief MUST contain from the JSON backends and
 * fails loudly on any divergence — a hand-edited number, a stale note, a
 * missing or duplicated owned section.
 *
 * <p>It is deliberately an INDEPENDENT re-implementation of the writers'
 * contracts (not a caller of their builders): if the writer and the audit
 * shared the same builder, a format bug in that builder would be green on
 * both sides. Two implementations must agree. The shared primitives it does
 * reuse are the vault's single sources of truth for their domains:
 * Money (the one money formatter), Blessed (changelog/blessed_at parsing),
 * Blessed.Json (parsing).
 *
 * <p>Per ticker from dcf-comparison.csv, for
 * {@code 01 📈 Stocks/<T>.md} it verifies:
 * <ul>
 *   <li>exactly one valuation table, byte-identical to the expected 12-line
 *       block (market, our DCF, Muse speculation, growth outlook, NotebookLM
 *       value, NotebookLM speculation, forward tilt, creators, projection
 *       price, website consensus);</li>
 *   <li>exactly one generated {@code ## Sources} section, byte-identical to
 *       the expected block (same Money.fmtMoney of the same Doubles the
 *       table prints, blessed dates/proposal IDs from the changelog);</li>
 *   <li>frontmatter parity for every generated key (market_price,
 *       notebooklm, notebooklm_forward, dcf, muse_speculation,
 *       nlm_speculation, outside_voice, growth_outlook, growth_thesis,
 *       projection_price, upside_pct, upside_projection_pct, creators);
 *       values_asof must be a valid date (it is the refresh date, not
 *       today's — exact-match would false-red the day after a refresh);</li>
 *   <li>explicit null statuses ("—", "no value recorded") — never a
 *       borrowed figure.</li>
 * </ul>
 *
 * <p>For {@code 05 📓 NotebookLM/<T> — NotebookLM Research.md} it verifies:
 * <ul>
 *   <li>exactly one frozen banner, with the correct frozen-as-of date (first
 *       date in the DCF section, live line scrubbed);</li>
 *   <li>exactly one {@code - Live value:} line, byte-identical to the
 *       expected line from notebooklm_values.json + blessed metadata;</li>
 *   <li>the live line sits inside the {@code ## NotebookLM DCF value}
 *       section when it exists, else inside the owned
 *       {@code ## Live value (current)} section (QXO case);</li>
 *   <li>the brief's live-line value is string-identical to the stock note's
 *       NotebookLM row — the two surfaces can never disagree.</li>
 * </ul>
 *
 * <p>Read-only: never writes. Exit 0 = all green, 1 = failures (concise
 * one-line reports). Run:
 * <pre>
 *   LC_ALL=C.UTF-8 java --class-path ~/workspace/java-shared/classes \
 *       ~/workspace/atlas-scripts/VaultConsistencyCheck.java [--base-dir DIR]
 * </pre>
 * {@code --self-test} runs the fixture checks (green, deliberate
 * corruptions → red, restore → green).
 */
public class VaultConsistencyCheck {

    static final String EM = "\u2014";

    // ---- section/table patterns (same contracts as the writers) ----
    private static final Pattern VALUATION_TABLE = Pattern.compile(
            "\\| Source \\| Value \\|\\n\\|---\\|---\\|\\n(?:\\| .*\\|\\n){5,11}",
            Pattern.MULTILINE);
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\n(.*?)^---", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern SOURCES_SECTION = Pattern.compile(
            "^## Sources\\n(.*?)(?=^## |\\z)", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern SOURCES_HEADING =
            Pattern.compile("^## Sources$", Pattern.MULTILINE);
    private static final Pattern BANNER =
            Pattern.compile("^> \\[!note\\] FROZEN INPUT PACKAGE.*$", Pattern.MULTILINE);
    private static final Pattern LIVE_LINE =
            Pattern.compile("^- Live value:.*$", Pattern.MULTILINE);
    private static final Pattern DCF_SECTION = Pattern.compile(
            "^## NotebookLM DCF value\\n(.*?)(?=^## |\\z)", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern LIVE_SECTION =
            Pattern.compile("^## Live value \\(current\\)$", Pattern.MULTILINE);
    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern H1 = Pattern.compile("^(# .+)$", Pattern.MULTILINE);
    private static final Pattern NLM_ROW =
            Pattern.compile("^\\| NotebookLM value \\| (.*) \\|$", Pattern.MULTILINE);

    static Path homeDir() {
        String home = System.getenv("HOME");
        return Paths.get((home == null || home.isEmpty())
                ? System.getProperty("user.home") : home);
    }

    /** Python float(v): None stays null, anything numeric becomes a double. */
    static Double num(Object v) {
        return (v instanceof Number) ? ((Number) v).doubleValue() : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapOrEmpty(Object v) {
        return (v instanceof Map) ? (Map<String, Object>) v : Map.of();
    }

    /** Ticker row from a {"tickers": {...}} backend; empty when absent OR
     *  explicitly null. */
    static Map<String, Object> rowOrEmpty(Map<String, Object> tickers, String t) {
        Object row = tickers.getOrDefault(t, Map.of());
        return (row instanceof Map) ? mapOrEmpty(row) : Map.of();
    }

    /**
     * Python str(float): shortest round-trip text. Verbatim contract twin of
     * RefreshValuations.pyRepr — the frontmatter must print what the writer
     * prints.
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
        if (mant.endsWith(".0")) {
            mant = mant.substring(0, mant.length() - 2);
        }
        char sign = exp.startsWith("-") ? '-' : '+';
        String digits = (exp.startsWith("-") || exp.startsWith("+")) ? exp.substring(1) : exp;
        if (digits.length() < 2) {
            digits = "0" + digits;
        }
        return mant + "e" + sign + digits;
    }

    /** Python yv(): "null" for None, else str(v). */
    static String yamlVal(Double v) {
        return v == null ? "null" : pyRepr(v);
    }

    /** A raw JSON number as the Python f-string would print it. */
    static String rawNumber(Object v) {
        if (v instanceof Long) {
            return v.toString();
        }
        return pyRepr(((Number) v).doubleValue());
    }

    /** Minimal CSV reader (Python csv.reader defaults). */
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

    /** Minimal JSON parser: objects -> LinkedHashMap, arrays -> ArrayList,
     *  strings -> String, whole numbers -> Long, decimals -> Double.
     *
     *  <p>Verbatim twin of RefreshValuations' parser — the audit must use the
     *  writer's EXACT number typing. The shared Blessed.Json types every
     *  number Double, which would print the frontmatter {@code creators} line
     *  as "$180.0" where the writer prints "$180" (integer JSON literals are
     *  Long in the writer). If the writer's parser ever changes, this audit
     *  fails loudly until it is synced — that is the point of an audit. */
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

    // ------------------------------------------------------------------
    // Expectation builders — independent re-derivation of the writers'
    // contracts. If the writer changes its format, these fail loudly until
    // the audit is updated: that is the point of an audit.
    // ------------------------------------------------------------------

    static String marketCell(Double market, String sess) {
        return Money.truthy(market) ? Money.fmtMoney(market) + " (" + sess + " close)" : EM;
    }

    static String creatorCell(Map<String, Object> cr) {
        if (cr == null || !(cr.get("value") instanceof Number)) {
            return EM;
        }
        Object callDate = cr.get("call_date");
        String cd = (callDate == null || ((String) callDate).isEmpty()) ? "date n/a" : (String) callDate;
        return cr.get("creator") + ": " + ((String) cr.get("type")).replace('_', ' ') + " $"
                + Money.grouped(Money.fixed2(((Number) cr.get("value")).doubleValue())) + " (" + cd + ")";
    }

    /**
     * The per-creator latest scoreable calls for one ticker from
     * creator_calls_by_creator.json — the same backend RefreshValuations
     * renders the note's YouTube-creators row from (Rahik 2026-10-03,
     * option 1: every creator's latest call is visible on the note, not
     * just the single latest valuation call from creator_values.json).
     * This filter must stay byte-identical to the writer's.
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

    /** Expected YouTube-creators strings, byte-identical to RefreshValuations. */
    static final class CreatorsExpected {
        String cell = EM;    // valuation-table row + ## Sources line
        String front = null; // frontmatter value (null = key must be absent)
    }

    /** Null-safe string for sorting call dates (writer parity). */
    static String cdStr(Object o) {
        return (o == null) ? "" : o.toString();
    }

    @SuppressWarnings("unchecked")
    static CreatorsExpected expectedCreators(Object rawCalls, Map<String, Object> cr) {
        CreatorsExpected r = new CreatorsExpected();
        List<Map<String, Object>> calls = creatorCallList(rawCalls);
        calls.sort((a, b) -> cdStr(b.get("call_date")).compareTo(cdStr(a.get("call_date"))));
        List<String> parts = new ArrayList<>();
        List<String> frontParts = new ArrayList<>();
        for (Map<String, Object> e : calls) {
            Object cd = e.get("call_date");
            String date = (cd == null || ((String) cd).isEmpty()) ? "date n/a" : (String) cd;
            parts.add(e.get("creator") + ": " + ((String) e.get("type")).replace('_', ' ') + " $"
                    + Money.grouped(Money.fixed2(((Number) e.get("value")).doubleValue()))
                    + " (" + date + ")");
            frontParts.add(e.get("creator") + ": " + e.get("type") + " $" + rawNumber(e.get("value")));
        }
        if (parts.isEmpty() && cr != null && cr.get("value") instanceof Number) {
            // Fallback: old single-creator path when the per-creator view
            // file has no entry for this ticker.
            parts.add(creatorCell(cr));
            frontParts.add(cr.get("creator") + ": " + cr.get("type") + " $" + rawNumber(cr.get("value")));
        }
        if (!parts.isEmpty()) {
            r.cell = String.join("; ", parts);
            r.front = "\"" + String.join("; ", frontParts) + "\"";
        }
        return r;
    }

    static String growthCell(String gOut, String gThesis) {
        if (gOut == null) {
            return "not yet researched";
        }
        return (gThesis == null || gThesis.isEmpty()) ? gOut : gOut + " " + EM + " " + gThesis;
    }

    /** The 12-line valuation table the stock note must contain. */
    static List<String> expectedTable(Double market, String sess, Double museDcf,
                                      Double museSpec, String gCell, Double nblm,
                                      Double nlmSpec, Double fwdTilt,
                                      String crCell, Double projection,
                                      Double webConsensus) {
        return List.of(
                "| Source | Value |",
                "|---|---|",
                "| Market price | " + marketCell(market, sess) + " |",
                "| Our DCF | " + Money.fmtMoney(museDcf) + " |",
                "| Muse speculation price | " + Money.fmtMoney(museSpec) + " |",
                "| Growth outlook | " + gCell + " |",
                "| NotebookLM value | " + Money.fmtMoney(nblm) + " |",
                "| NotebookLM speculation price | " + Money.fmtMoney(nlmSpec) + " |",
                "| NotebookLM forward tilt | " + Money.fmtMoney(fwdTilt) + " |",
                "| YouTube creators | " + crCell + " |",
                "| Projection price (analyst target) | " + Money.fmtMoney(projection) + " |",
                "| Website consensus | " + Money.fmtMoney(webConsensus) + " |");
    }

    static String valLine(String label, Double v, String blessed, String suffix) {
        if (!Money.truthy(v)) {
            return "- " + label + " " + EM + " " + EM + " no value recorded.";
        }
        return "- " + label + " " + Money.fmtMoney(v) + " " + EM + " " + blessed + " " + EM + " " + suffix + ".";
    }

    /** The 13-line generated "## Sources" block (trailing newline included). */
    static String expectedSources(String t, Double market, String sess,
                                  Double museDcf, Double nblm, Double fwdTilt,
                                  Double museSpec, Double nlmSpec,
                                  String crCell, Double projection, Double webConsensus,
                                  Map<String, Blessed.Info> blessed,
                                  Map<String, String> colDates) {
        List<String> lines = new ArrayList<>();
        lines.add("## Sources");
        lines.add("- Market price "
                + (Money.truthy(market) ? Money.fmtMoney(market) + " (" + sess + " close)" : EM)
                + " " + EM + " `stock_watch/price_cache.json`.");
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
                Money.truthy(nlmSpec) ? "derived by NotebookLM itself" : "not yet derived by NotebookLM"));
        lines.add("- YouTube creators: "
                + (crCell.equals(EM) ? EM + " (no valuation-type call recorded)." : crCell + "."));
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

    /** Python round(x, 1): banker's rounding on the binary value. */
    static Double upside(Double dcf, Double market) {
        if (!Money.truthy(dcf) || !Money.truthy(market)) {
            return null;
        }
        return new BigDecimal((dcf / market - 1) * 100).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }

    /**
     * Expected frontmatter key → exact line value (the part after "key: ").
     * values_asof is verified as a date FORMAT only — it records the refresh
     * date, so an exact match would false-red the day after a refresh.
     */
    static Map<String, String> expectedFrontmatter(Double market, Double nblm, Double fwdTilt,
                                                   Double museDcf, Double museSpec, Double nlmSpec,
                                                   Double outsideVoice, String gOut, String gThesis,
                                                   Double projection, String crFront) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("market_price", yamlVal(market));
        m.put("notebooklm", yamlVal(nblm));
        m.put("notebooklm_forward", yamlVal(fwdTilt));
        m.put("dcf", yamlVal(museDcf));
        m.put("muse_speculation", yamlVal(museSpec));
        m.put("nlm_speculation", yamlVal(nlmSpec));
        m.put("outside_voice", yamlVal(outsideVoice));
        m.put("growth_outlook", gOut == null ? "null" : "\"" + gOut + "\"");
        m.put("growth_thesis", gThesis == null ? "null"
                : "\"" + gThesis.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        m.put("projection_price", yamlVal(projection));
        m.put("upside_pct", yamlVal(upside(museDcf, market)));
        m.put("upside_projection_pct", yamlVal(upside(projection, market)));
        if (crFront != null) {
            m.put("creators", crFront);
        }
        return m;
    }

    static String bannerFor(String t, String date) {
        return "> [!note] FROZEN INPUT PACKAGE " + EM + " brief as of " + date + ". "
                + "This is the input package NotebookLM worked from, not a current"
                + " figure. Live values: [[" + t + "]].";
    }

    static String liveLine(String t, Double v, String blessed) {
        if (v == null) {
            return "- Live value: " + EM + " " + EM + " no grounded value recorded"
                    + " (unverified " + EM + " pending the nightly re-ask) " + EM + " see [[" + t + "]].";
        }
        return "- Live value: " + Money.fmtMoney(v) + " " + EM + " " + blessed
                + " " + EM + " see [[" + t + "]].";
    }

    // ------------------------------------------------------------------
    // Backend loading — mirrors RefreshValuations' reads.
    // ------------------------------------------------------------------

    static final class Ctx {
        List<String> tickers = new ArrayList<>();
        Map<String, Object> quotes = new LinkedHashMap<>();
        Map<String, Object> ourDcf = new LinkedHashMap<>();
        Map<String, Object> notebooklm = new LinkedHashMap<>();
        Map<String, Double> notebooklmFlat = new LinkedHashMap<>(); // ticker -> value (null ok)
        Map<String, Object> forward = new LinkedHashMap<>();
        Map<String, Object> analyst = new LinkedHashMap<>();
        Map<String, Object> creators = new LinkedHashMap<>();
        // Per-creator latest scoreable calls per ticker — the backend
        // RefreshValuations renders the note's YouTube-creators row from
        // since the 2026-10-03 all-creators extension (option 1).
        Map<String, Object> creatorCalls = new LinkedHashMap<>();
        Map<String, Object> growthTickers = new LinkedHashMap<>();
        Map<String, Object> specTickers = new LinkedHashMap<>();
        Map<String, Object> outsideTickers = new LinkedHashMap<>();
        Map<String, Object> webTickers = new LinkedHashMap<>();
        Blessed.Store blessedStore;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> jsonObj(Path p) throws IOException {
        if (!Files.exists(p)) {
            return Map.of();
        }
        // The writer's own parser (number typing matters) — see Json above.
        Object v = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
        return (v instanceof Map) ? (Map<String, Object>) v : Map.of();
    }

    static Ctx load(Path base) throws IOException {
        Ctx c = new Ctx();
        Path dcf = base.resolve("workspace/dcf_csv");
        Map<String, Object> cache = jsonObj(base.resolve("workspace/stock_watch/price_cache.json"));
        Object q = cache.get("quotes");
        if (!(q instanceof Map) || ((Map<?, ?>) q).isEmpty()) {
            q = cache.get("prices");
        }
        c.quotes = mapOrEmpty(q);
        c.ourDcf = mapOrEmpty(jsonObj(dcf.resolve("our_dcf.json")).get("values"));
        c.notebooklm = mapOrEmpty(jsonObj(dcf.resolve("notebooklm_values.json")).get("values"));
        for (Map.Entry<String, Object> e : c.notebooklm.entrySet()) {
            Object row = e.getValue();
            Double v = null;
            if (row instanceof Map) {
                v = num(((Map<String, Object>) row).get("value"));
            } else if (row instanceof Number) {
                v = ((Number) row).doubleValue();
            }
            c.notebooklmFlat.put(e.getKey(), v);
        }
        c.forward = mapOrEmpty(jsonObj(dcf.resolve("notebooklm_forward.json")).get("values"));
        c.analyst = jsonObj(dcf.resolve("analyst_targets.json"));
        c.creators = mapOrEmpty(jsonObj(dcf.resolve("creator_values.json")).get("latest"));
        c.creatorCalls = mapOrEmpty(jsonObj(
                base.resolve("workspace/creator_tracker/creator_calls_by_creator.json")));
        c.growthTickers = mapOrEmpty(jsonObj(dcf.resolve("growth_outlook.json")).get("tickers"));
        c.specTickers = mapOrEmpty(jsonObj(dcf.resolve("speculation_price.json")).get("tickers"));
        c.outsideTickers = mapOrEmpty(jsonObj(dcf.resolve("outside_voice.json")).get("tickers"));
        c.webTickers = mapOrEmpty(jsonObj(dcf.resolve("website_consensus.json")).get("tickers"));
        c.blessedStore = Blessed.load(dcf);

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
        for (int r = 3; r < rows.size(); r++) {
            String[] row = rows.get(r);
            if (row.length > ti && !row[ti].strip().isEmpty()) {
                c.tickers.add(row[ti].strip());
            }
        }
        return c;
    }

    /** Per-ticker derived values shared by the note and brief checks. */
    static final class Vals {
        Double market;
        String sess = "";
        Double museDcf, nblm, fwdTilt, projection, museSpec, nlmSpec, outsideVoice, webConsensus;
        String gOut, gThesis;
        // YouTube-creators expectations, computed from the same per-creator
        // backend the writer renders from. crFront == null means the
        // frontmatter key must be absent.
        String crCell = EM;
        String crFront = null;
        Double nblmFlat; // null when absent OR explicitly null
        boolean nblmKnown;
    }

    @SuppressWarnings("unchecked")
    static Vals valsFor(String t, Ctx c) {
        Vals v = new Vals();
        Object q = c.quotes.get(t);
        if (q instanceof Map) {
            Map<String, Object> qm = (Map<String, Object>) q;
            v.market = num(qm.get("price"));
            Object sd = qm.get("session_date");
            v.sess = (sd == null ? "" : sd.toString()).strip();
        } else {
            v.market = num(q);
        }
        v.museDcf = num(mapOrEmpty(c.ourDcf.get(t)).get("value"));
        v.nblm = num(mapOrEmpty(c.notebooklm.get(t)).get("value"));
        v.fwdTilt = num(mapOrEmpty(c.forward.get(t)).get("value"));
        v.projection = num(mapOrEmpty(c.analyst.get(t)).get("target"));
        Map<String, Object> specRow = rowOrEmpty(c.specTickers, t);
        v.museSpec = num(specRow.get("muse_speculation"));
        v.nlmSpec = num(specRow.get("nlm_speculation"));
        v.outsideVoice = num(rowOrEmpty(c.outsideTickers, t).get("value"));
        v.webConsensus = num(rowOrEmpty(c.webTickers, t).get("consensus"));
        Object go = c.growthTickers.get(t);
        if (go instanceof Map) {
            Map<String, Object> goMap = (Map<String, Object>) go;
            Object ov = goMap.get("outlook");
            Object tv = goMap.get("thesis");
            v.gOut = (ov instanceof String) ? (String) ov : null;
            v.gThesis = (tv instanceof String) ? (String) tv : null;
        }
        Object cr = c.creators.get(t);
        CreatorsExpected ce = expectedCreators(c.creatorCalls.get(t),
                (cr instanceof Map) ? (Map<String, Object>) cr : null);
        v.crCell = ce.cell;
        v.crFront = ce.front;
        v.nblmKnown = c.notebooklmFlat.containsKey(t);
        v.nblmFlat = c.notebooklmFlat.get(t);
        return v;
    }

    // ------------------------------------------------------------------
    // The checks.
    // ------------------------------------------------------------------

    static int countMatches(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static String firstDiffLine(String want, String got) {
        String[] w = want.split("\n", -1);
        String[] g = got.split("\n", -1);
        int n = Math.max(w.length, g.length);
        for (int i = 0; i < n; i++) {
            String wl = i < w.length ? w[i] : "<missing>";
            String gl = i < g.length ? g[i] : "<missing>";
            if (!wl.equals(gl)) {
                return "line " + (i + 1) + ": got [" + gl + "] want [" + wl + "]";
            }
        }
        return "blocks differ in trailing whitespace";
    }

    /** Verify one stock note. Returns failure lines (empty = green). */
    static List<String> checkNote(String t, Ctx c, Vals v, String text) {
        List<String> fails = new ArrayList<>();
        String tag = t + " | note";

        // 1. Exactly one valuation table, byte-identical.
        Matcher tm = VALUATION_TABLE.matcher(text);
        List<String> tables = new ArrayList<>();
        while (tm.find()) {
            tables.add(tm.group(0));
        }
        if (tables.isEmpty()) {
            fails.add(tag + " | valuation table: missing");
        } else if (tables.size() > 1) {
            fails.add(tag + " | valuation table: " + tables.size() + " found (want 1)");
        } else {
            String want = String.join("\n", expectedTable(v.market, v.sess, v.museDcf,
                    v.museSpec, growthCell(v.gOut, v.gThesis), v.nblm, v.nlmSpec,
                    v.fwdTilt, v.crCell, v.projection, v.webConsensus)) + "\n";
            if (!tables.get(0).equals(want)) {
                fails.add(tag + " | valuation table mismatch: "
                        + firstDiffLine(want, tables.get(0)));
            }
        }

        // 2. Exactly one generated ## Sources section, byte-identical.
        int sh = countMatches(SOURCES_HEADING, text);
        if (sh == 0) {
            fails.add(tag + " | ## Sources section: missing");
        } else if (sh > 1) {
            fails.add(tag + " | ## Sources section: " + sh + " found (want 1)");
        } else {
            Matcher sm = SOURCES_SECTION.matcher(text);
            sm.find();
            String want = expectedSources(t, v.market, v.sess, v.museDcf, v.nblm, v.fwdTilt,
                    v.museSpec, v.nlmSpec, v.crCell, v.projection, v.webConsensus,
                    c.blessedStore.info, c.blessedStore.colDates) + "\n";
            if (!sm.group(0).equals(want)) {
                fails.add(tag + " | ## Sources mismatch: "
                        + firstDiffLine(want, sm.group(0)));
            }
        }

        // 3. Frontmatter parity for every generated key.
        Matcher fm = FRONTMATTER.matcher(text);
        if (!fm.find()) {
            fails.add(tag + " | frontmatter: missing");
        } else {
            String[] lines = fm.group(1).split("\n", -1);
            Map<String, String> expected = expectedFrontmatter(v.market, v.nblm, v.fwdTilt,
                    v.museDcf, v.museSpec, v.nlmSpec, v.outsideVoice,
                    v.gOut, v.gThesis, v.projection, v.crFront);
            for (Map.Entry<String, String> e : expected.entrySet()) {
                String key = e.getKey();
                List<String> hits = new ArrayList<>();
                for (String ln : lines) {
                    if (ln.startsWith(key + ":")) {
                        hits.add(ln);
                    }
                }
                if (hits.isEmpty()) {
                    fails.add(tag + " | frontmatter: key '" + key + "' missing");
                } else if (hits.size() > 1) {
                    fails.add(tag + " | frontmatter: key '" + key + "' duplicated");
                } else if (!hits.get(0).trim().equals(key + ": " + e.getValue())) {
                    fails.add(tag + " | frontmatter '" + key + "' mismatch: got ["
                            + hits.get(0).trim() + "] want [" + key + ": " + e.getValue() + "]");
                }
            }
            if (v.crFront == null) {
                for (String ln : lines) {
                    if (ln.startsWith("creators:")) {
                        fails.add(tag + " | frontmatter: 'creators' present but backend has none");
                        break;
                    }
                }
            }
            boolean asofOk = false;
            for (String ln : lines) {
                if (ln.matches("^values_asof: \\d{4}-\\d{2}-\\d{2}$")) {
                    asofOk = true;
                    break;
                }
            }
            if (!asofOk) {
                fails.add(tag + " | frontmatter: values_asof not a valid yyyy-mm-dd date");
            }
        }
        return fails;
    }

    /** Verify one research brief against the backends and its stock note. */
    static List<String> checkBrief(String t, Ctx c, Vals v, String noteText, String text) {
        List<String> fails = new ArrayList<>();
        String tag = t + " | brief";

        if (!v.nblmKnown) {
            fails.add(tag + " | ticker absent from notebooklm_values.json values"
                    + " (stamper skips it — brief can never be current)");
            return fails;
        }
        String blessed = Blessed.blessedSeg("notebooklm", t,
                c.blessedStore.info, c.blessedStore.colDates);

        // 1. Exactly one frozen banner.
        Matcher bm = BANNER.matcher(text);
        List<String> banners = new ArrayList<>();
        List<Integer> bannerPos = new ArrayList<>();
        while (bm.find()) {
            banners.add(bm.group(0));
            bannerPos.add(bm.start());
        }
        if (banners.isEmpty()) {
            fails.add(tag + " | frozen banner: missing");
        } else if (banners.size() > 1) {
            fails.add(tag + " | frozen banner: " + banners.size() + " found (want 1)");
        } else {
            String banner = banners.get(0);
            // Expected frozen date: first date in the DCF section with the
            // live line scrubbed (the live line's blessed date is live
            // metadata, not the package date). No date → format-only check
            // (mtime fallback / preserved banner can't be pinned post-hoc).
            Matcher dm = DCF_SECTION.matcher(text);
            String frozenDate = null;
            if (dm.find()) {
                String scrubbed = LIVE_LINE.matcher(dm.group(1)).replaceAll("");
                Matcher dtm = DATE.matcher(scrubbed);
                if (dtm.find()) {
                    frozenDate = dtm.group(1);
                }
            }
            if (frozenDate != null) {
                String want = bannerFor(t, frozenDate);
                if (!banner.equals(want)) {
                    fails.add(tag + " | frozen banner mismatch: got [" + banner
                            + "] want [" + want + "]");
                }
            } else {
                String loose = "^> \\[!note\\] FROZEN INPUT PACKAGE " + EM
                        + " brief as of \\d{4}-\\d{2}-\\d{2}\\. This is the input package"
                        + " NotebookLM worked from, not a current figure\\. Live values: \\[\\["
                        + Pattern.quote(t) + "\\]\\]\\.$";
                if (!banner.matches(loose)) {
                    fails.add(tag + " | frozen banner malformed: [" + banner + "]");
                }
            }
            Matcher h1 = H1.matcher(text);
            if (h1.find() && bannerPos.get(0) < h1.start()) {
                fails.add(tag + " | frozen banner: not after the H1 title");
            }
        }

        // 2. Exactly one live-value line, byte-identical to the backend.
        Matcher lm = LIVE_LINE.matcher(text);
        List<String> liveLines = new ArrayList<>();
        List<Integer> livePos = new ArrayList<>();
        while (lm.find()) {
            liveLines.add(lm.group(0));
            livePos.add(lm.start());
        }
        if (liveLines.isEmpty()) {
            fails.add(tag + " | live value line: missing");
        } else if (liveLines.size() > 1) {
            fails.add(tag + " | live value line: " + liveLines.size() + " found (want 1)");
        } else {
            String want = liveLine(t, v.nblmFlat, blessed);
            if (!liveLines.get(0).equals(want)) {
                fails.add(tag + " | live value line mismatch: got [" + liveLines.get(0)
                        + "] want [" + want + "]");
            }
            // 3. Placement: inside the DCF section when it exists, else in
            // the owned ## Live value (current) section after the banner.
            Matcher dm = DCF_SECTION.matcher(text);
            int lp = livePos.get(0);
            if (dm.find()) {
                if (!(lp > dm.start() && lp < dm.end())) {
                    fails.add(tag + " | live value line: not inside ## NotebookLM DCF value");
                }
            } else {
                Matcher sm = LIVE_SECTION.matcher(text);
                List<Integer> secs = new ArrayList<>();
                while (sm.find()) {
                    secs.add(sm.start());
                }
                if (secs.size() != 1) {
                    fails.add(tag + " | ## Live value (current) section: " + secs.size()
                            + " found (want 1)");
                } else if (!banners.isEmpty() && !(secs.get(0) > bannerPos.get(0) && lp > secs.get(0))) {
                    fails.add(tag + " | ## Live value (current) section: misplaced"
                            + " (must sit after the banner and hold the live line)");
                }
            }
            // 4. Cross-check: the brief's live-line value is string-identical
            // to the stock note's NotebookLM row.
            Matcher nm = NLM_ROW.matcher(noteText);
            if (nm.find()) {
                String noteCell = nm.group(1);
                String line = liveLines.get(0);
                String prefix = "- Live value: ";
                String briefCell = line.substring(prefix.length()).split(" " + EM + " ", 2)[0];
                if (!briefCell.equals(noteCell)) {
                    fails.add(tag + " | brief/note NotebookLM value disagree: brief ["
                            + briefCell + "] vs note [" + noteCell + "]");
                }
            } else {
                fails.add(tag + " | cross-check: stock note has no NotebookLM value row");
            }
        }
        return fails;
    }

    static final class Result {
        final List<String> failures = new ArrayList<>();
        int notes;
        int briefs;
    }

    /** Read-only verification run. Never writes. */
    static Result runVerify(Path base) throws IOException {
        Result r = new Result();
        Ctx c = load(base);
        Path stocks = base.resolve("workspace/atlas/01 \uD83D\uDCC8 Stocks");
        Path briefs = base.resolve("workspace/atlas/05 \uD83D\uDCD3 NotebookLM");
        for (String t : c.tickers) {
            Vals v = valsFor(t, c);
            Path notePath = stocks.resolve(t + ".md");
            Path briefPath = briefs.resolve(t + " \u2014 NotebookLM Research.md");
            boolean noteOk = Files.isRegularFile(notePath);
            boolean briefOk = Files.isRegularFile(briefPath);
            if (!noteOk) {
                r.failures.add(t + " | stock note: file missing");
            }
            if (!briefOk) {
                r.failures.add(t + " | brief: file missing");
            }
            if (noteOk) {
                r.notes++;
                String noteText = Files.readString(notePath, StandardCharsets.UTF_8);
                r.failures.addAll(checkNote(t, c, v, noteText));
                if (briefOk) {
                    r.briefs++;
                    r.failures.addAll(checkBrief(t, c, v, noteText,
                            Files.readString(briefPath, StandardCharsets.UTF_8)));
                }
            } else if (briefOk) {
                r.briefs++;
            }
        }
        return r;
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
        Result r = runVerify(base);
        for (String f : r.failures) {
            System.out.println("FAIL " + f);
        }
        if (r.failures.isEmpty()) {
            System.out.println("atlas consistency: " + r.notes + " notes, "
                    + r.briefs + " briefs verified OK");
        } else {
            System.out.println("atlas consistency: " + r.notes + " notes, "
                    + r.briefs + " briefs, " + r.failures.size() + " FAILURES");
        }
        System.exit(r.failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // Self-test: hand-built fixture vault (AAA full, BBB nulls, CCC missing
    // files), then deliberate corruptions must each go red, restore → green.
    // ------------------------------------------------------------------

    static final String FX_AAA_NOTE = """
            ---
            title: AAA
            ticker: AAA
            tags: [stock, ira]
            market_price: 200.0
            notebooklm: 174.8
            notebooklm_forward: null
            dcf: 250.0
            muse_speculation: 168.06
            nlm_speculation: null
            outside_voice: 304.19
            growth_outlook: "High"
            growth_thesis: "AWS thesis."
            projection_price: 300.0
            upside_pct: 25.0
            upside_projection_pct: 50.0
            values_asof: 2026-09-30
            creators: "Couch Investor: price_target $200; Parkev Tatevosian: intrinsic_value $180"
            ---

            # AAA

            AAA Inc.

            | Source | Value |
            |---|---|
            | Market price | $200 (09/28/26 EDT close) |
            | Our DCF | $250 |
            | Muse speculation price | $168.06 |
            | Growth outlook | High — AWS thesis. |
            | NotebookLM value | $174.80 |
            | NotebookLM speculation price | — |
            | NotebookLM forward tilt | — |
            | YouTube creators | Couch Investor: price target $200.00 (2026-09-28); Parkev Tatevosian: intrinsic value $180.00 (2026-09-27) |
            | Projection price (analyst target) | $300 |
            | Website consensus | $287.18 |

            ## Sources
            - Market price $200 (09/28/26 EDT close) — `stock_watch/price_cache.json`.
            - Our DCF $250 — blessed 2026-09-28 via prop-20260928-001 — methodology-fixed value (mid-year discounting + growth fade).
            - NotebookLM grounded $174.80 — blessed 2026-09-29 via prop-20260929-004 — verified re-ask batch (filings-selected sources only; in-chat evidence never grounds this column).
            - NotebookLM forward tilt — — no value recorded.
            - Muse speculation $168.06 — computed this run — our DCF pulled halfway toward the outside median (`dcf_csv/speculation_price.json`); display-only.
            - NotebookLM speculation — — no value recorded.
            - YouTube creators: Couch Investor: price target $200.00 (2026-09-28); Parkev Tatevosian: intrinsic value $180.00 (2026-09-27).
            - Projection price (analyst target) $300 — backfilled 2026-09-27 — `dcf_csv/analyst_targets.json`.
            - Website consensus $287.18 — computed this run — median of DCF-comparable sites (`dcf_csv/website_consensus.json`).
            - IRA membership: Robinhood Roth IRA holdings (`stock_watch/watchlist.json`).
            - SEC filings: `~/workspace/sec_filings/aaa/`.
            - Stock Research Hub dossier: folded into the '## Stock Research Hub' section above by FoldHub.

            ## Related
            - [[AAA]]
            """;

    static final String FX_BBB_NOTE = """
            ---
            title: BBB
            ticker: BBB
            tags: [stock, ira]
            market_price: 50.0
            notebooklm: null
            notebooklm_forward: 10.5
            dcf: null
            muse_speculation: null
            nlm_speculation: null
            outside_voice: null
            growth_outlook: null
            growth_thesis: null
            projection_price: null
            upside_pct: null
            upside_projection_pct: null
            values_asof: 2026-09-30
            creators: "Couch Investor: price_target $100"
            ---

            # BBB

            BBB Inc.

            | Source | Value |
            |---|---|
            | Market price | $50.00 (09/28/26 EDT close) |
            | Our DCF | — |
            | Muse speculation price | — |
            | Growth outlook | not yet researched |
            | NotebookLM value | — |
            | NotebookLM speculation price | — |
            | NotebookLM forward tilt | $10.50 |
            | YouTube creators | Couch Investor: price target $100.00 (2026-09-26) |
            | Projection price (analyst target) | — |
            | Website consensus | — |

            ## Sources
            - Market price $50.00 (09/28/26 EDT close) — `stock_watch/price_cache.json`.
            - Our DCF — — no value recorded.
            - NotebookLM grounded — — no value recorded.
            - NotebookLM forward tilt $10.50 — blessed 2026-09-27 (bulk; no per-ticker proposal recorded) — grounded DCF plus a modest, sourced forward tilt; separate column, never blended.
            - Muse speculation — — no value recorded.
            - NotebookLM speculation — — no value recorded.
            - YouTube creators: Couch Investor: price target $100.00 (2026-09-26).
            - Projection price (analyst target) — — no value recorded.
            - Website consensus — — no value recorded.
            - IRA membership: Robinhood Roth IRA holdings (`stock_watch/watchlist.json`).
            - SEC filings: `~/workspace/sec_filings/bbb/`.
            - Stock Research Hub dossier: folded into the '## Stock Research Hub' section above by FoldHub.

            ## Related
            - [[BBB]]
            """;

    static final String FX_AAA_BRIEF = """
            ---
            title: AAA — NotebookLM Research
            ticker: AAA
            tags: [notebooklm, research]
            ---

            # AAA — NotebookLM Research

            > [!note] FROZEN INPUT PACKAGE — brief as of 2026-09-26. This is the input package NotebookLM worked from, not a current figure. Live values: [[AAA]].

            ## NotebookLM DCF value
            - NotebookLM's DCF value: $152.42 (read back 2026-09-26).
            - Live value: $174.80 — blessed 2026-09-29 via prop-20260929-004 — see [[AAA]].

            ## Related
            - [[AAA]]
            """;

    static final String FX_BBB_BRIEF = """
            ---
            title: BBB — NotebookLM Research
            ticker: BBB
            tags: [notebooklm, research]
            ---

            # BBB — NotebookLM Research

            > [!note] FROZEN INPUT PACKAGE — brief as of 2026-09-29. This is the input package NotebookLM worked from, not a current figure. Live values: [[BBB]].

            ## Live value (current)
            - Live value: — — no grounded value recorded (unverified — pending the nightly re-ask) — see [[BBB]].

            ## Related
            - [[BBB]]
            """;

    static void writeFixture(Path base) throws IOException {
        Path dcf = base.resolve("workspace/dcf_csv");
        Path watch = base.resolve("workspace/stock_watch");
        Path stocks = base.resolve("workspace/atlas/01 \uD83D\uDCC8 Stocks");
        Path briefs = base.resolve("workspace/atlas/05 \uD83D\uDCD3 NotebookLM");
        Files.createDirectories(dcf.resolve("proposals"));
        Files.createDirectories(watch);
        Files.createDirectories(stocks);
        Files.createDirectories(briefs);

        Files.writeString(dcf.resolve("dcf-comparison.csv"),
                "exported,by,fixture\n2026-09-30,,\nTicker,Name\nAAA,AAA Inc.\nBBB,BBB Inc.\nCCC,CCC Inc.\n",
                StandardCharsets.UTF_8);
        Files.writeString(watch.resolve("price_cache.json"),
                "{\"quotes\": {\"AAA\": {\"price\": 200.0, \"session_date\": \"09/28/26 EDT\"},"
                        + " \"BBB\": {\"price\": 50.0, \"session_date\": \"09/28/26 EDT\"}}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("our_dcf.json"),
                "{\"values\": {\"AAA\": {\"value\": 250.0}, \"BBB\": {\"value\": null}}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("notebooklm_values.json"),
                "{\"values\": {\"AAA\": {\"value\": 174.8}, \"BBB\": {\"value\": null},"
                        + " \"CCC\": {\"value\": null}}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("notebooklm_forward.json"),
                "{\"values\": {\"AAA\": {\"value\": null}, \"BBB\": {\"value\": 10.5}}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("analyst_targets.json"),
                "{\"AAA\": {\"target\": 300.0}, \"BBB\": {\"target\": null}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("creator_values.json"),
                "{\"latest\": {\"AAA\": {\"creator\": \"Parkev Tatevosian\","
                        + " \"type\": \"intrinsic_value\", \"value\": 180,"
                        + " \"call_date\": \"2026-09-27\"},"
                        + " \"BBB\": {\"creator\": \"Couch Investor\","
                        + " \"type\": \"price_target\", \"value\": 100,"
                        + " \"call_date\": \"2026-09-26\"}}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("growth_outlook.json"),
                "{\"tickers\": {\"AAA\": {\"outlook\": \"High\", \"thesis\": \"AWS thesis.\"}}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("speculation_price.json"),
                "{\"tickers\": {\"AAA\": {\"muse_speculation\": 168.06, \"nlm_speculation\": null},"
                        + " \"BBB\": {\"muse_speculation\": null, \"nlm_speculation\": null}}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("outside_voice.json"),
                "{\"tickers\": {\"AAA\": {\"value\": 304.19}, \"BBB\": {\"value\": null}}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("website_consensus.json"),
                "{\"tickers\": {\"AAA\": {\"consensus\": 287.18}, \"BBB\": {\"consensus\": null}}}",
                StandardCharsets.UTF_8);
        // Per-creator view (the backend the note's YouTube-creators row is
        // rendered from since the 2026-10-03 all-creators extension). AAA
        // exercises the multi-creator path; BBB is absent so the old
        // single-creator fallback from creator_values.json applies.
        Path tracker = base.resolve("workspace/creator_tracker");
        Files.createDirectories(tracker);
        Files.writeString(tracker.resolve("creator_calls_by_creator.json"),
                "{\"AAA\": [{\"creator\": \"Parkev Tatevosian\", \"type\": \"intrinsic_value\","
                        + " \"value\": 180, \"call_date\": \"2026-09-27\", \"scoreable\": true},"
                        + " {\"creator\": \"Couch Investor\", \"type\": \"price_target\","
                        + " \"value\": 200, \"call_date\": \"2026-09-28\", \"scoreable\": true},"
                        + " {\"creator\": \"Stale Bot\", \"type\": \"buy_price\","
                        + " \"value\": 10, \"call_date\": \"2026-09-29\", \"scoreable\": false}]}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("blessed.json"),
                "{\"columns\": {\"ours\": {\"blessed_at\": \"2026-09-27T00:00:00Z\"},"
                        + " \"forward\": {\"blessed_at\": \"2026-09-27T00:00:00Z\"}}}",
                StandardCharsets.UTF_8);
        Files.writeString(dcf.resolve("proposals/changelog.md"),
                "## 2026-09-28T10:00:00Z\nprop-20260928-001 APPROVED by muse-audit\n"
                        + "- ours/AAA: \u2014 -> 250.0 (n/a)\n"
                        + "## 2026-09-29T10:00:00Z\nprop-20260929-004 APPROVED by muse-audit\n"
                        + "- notebooklm/AAA: \u2014 -> 174.8 (n/a)\n",
                StandardCharsets.UTF_8);

        Files.writeString(stocks.resolve("AAA.md"), FX_AAA_NOTE, StandardCharsets.UTF_8);
        Files.writeString(stocks.resolve("BBB.md"), FX_BBB_NOTE, StandardCharsets.UTF_8);
        Files.writeString(briefs.resolve("AAA \u2014 NotebookLM Research.md"),
                FX_AAA_BRIEF, StandardCharsets.UTF_8);
        Files.writeString(briefs.resolve("BBB \u2014 NotebookLM Research.md"),
                FX_BBB_BRIEF, StandardCharsets.UTF_8);
    }

    static void runSelfTest() throws Exception {
        SelfTest.checkEq("pyRepr 174.8", "174.8", pyRepr(174.8));
        SelfTest.checkEq("pyRepr 250.0", "250.0", pyRepr(250.0));
        SelfTest.checkEq("upside 250/200", 25.0, upside(250.0, 200.0));
        SelfTest.checkEq("upside null dcf", null, upside(null, 200.0));

        Path base = Files.createTempDirectory("vaultcheck");
        Path stocks = base.resolve("workspace/atlas/01 \uD83D\uDCC8 Stocks");
        Path briefs = base.resolve("workspace/atlas/05 \uD83D\uDCD3 NotebookLM");
        writeFixture(base);

        // Pristine fixture: only the two CCC missing-file failures.
        Result r = runVerify(base);
        SelfTest.checkEq("pristine failure count", 2, r.failures.size());
        SelfTest.check("CCC note missing flagged",
                r.failures.stream().anyMatch(f -> f.equals("CCC | stock note: file missing")));
        SelfTest.check("CCC brief missing flagged",
                r.failures.stream().anyMatch(f -> f.equals("CCC | brief: file missing")));
        SelfTest.check("AAA+BBB fully green",
                r.failures.stream().noneMatch(f -> f.startsWith("AAA") || f.startsWith("BBB")));

        // Each corruption: red with the right area, then restore → green.
        recordCorruption(base, stocks.resolve("AAA.md"), FX_AAA_NOTE,
                "| Our DCF | $250 |", "| Our DCF | $251 |",
                "hand-edited table number fails", "valuation table mismatch");
        recordCorruption(base, stocks.resolve("AAA.md"), FX_AAA_NOTE,
                "prop-20260928-001", "prop-20260928-009",
                "hand-edited proposal id fails", "## Sources mismatch");
        recordCorruption(base, stocks.resolve("AAA.md"), FX_AAA_NOTE,
                "dcf: 250.0", "dcf: 251.0",
                "hand-edited frontmatter fails", "frontmatter 'dcf' mismatch");
        String bannerLine = "> [!note] FROZEN INPUT PACKAGE \u2014 brief as of 2026-09-26.";
        recordCorruption(base, briefs.resolve("AAA \u2014 NotebookLM Research.md"), FX_AAA_BRIEF,
                "## Related", bannerLine + "\n\n## Related",
                "duplicated banner fails", "frozen banner: 2 found");
        recordCorruption(base, briefs.resolve("BBB \u2014 NotebookLM Research.md"), FX_BBB_BRIEF,
                "- Live value: \u2014 \u2014 no grounded value recorded (unverified \u2014 pending the nightly re-ask) \u2014 see [[BBB]].\n",
                "",
                "removed live line fails", "live value line: missing");
        recordCorruption(base, stocks.resolve("BBB.md"), FX_BBB_NOTE,
                "## Sources\n", "## Sources-removed\n",
                "missing Sources section fails", "## Sources section: missing");
        recordCorruption(base, briefs.resolve("AAA \u2014 NotebookLM Research.md"), FX_AAA_BRIEF,
                "- Live value: $174.80", "- Live value: $174.81",
                "hand-edited live value fails", "live value line mismatch");
        // Multi-creator semantics (2026-10-03 extension): dropping one creator
        // from the cell must fail, and the old single-creator fallback path
        // (BBB: no per-creator view entry) is verified too.
        recordCorruption(base, stocks.resolve("AAA.md"), FX_AAA_NOTE,
                "Couch Investor: price target $200.00 (2026-09-28); Parkev Tatevosian: intrinsic value $180.00 (2026-09-27)",
                "Couch Investor: price target $200.00 (2026-09-28)",
                "hand-edited creators cell fails", "valuation table mismatch");
        recordCorruption(base, stocks.resolve("BBB.md"), FX_BBB_NOTE,
                "creators: \"Couch Investor: price_target $100\"",
                "creators: \"Couch Investor: price_target $101\"",
                "hand-edited fallback creators fails", "frontmatter 'creators' mismatch");

        // Restored: back to the 2 CCC failures only.
        Result r2 = runVerify(base);
        SelfTest.checkEq("restored failure count", 2, r2.failures.size());
        SelfTest.finish();
    }

    /** Apply one corruption, assert red in the right area, restore, assert green. */
    static void recordCorruption(Path base, Path file, String pristine,
                                 String from, String to, String label, String wantFrag)
            throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.contains(from)) {
            throw new IllegalStateException("corruption anchor missing: " + from);
        }
        Files.writeString(file, text.replace(from, to), StandardCharsets.UTF_8);
        Result r = runVerify(base);
        SelfTest.check(label, r.failures.stream().anyMatch(f -> f.contains(wantFrag)));
        Files.writeString(file, pristine, StandardCharsets.UTF_8);
        Result r2 = runVerify(base);
        SelfTest.check(label + " (restored green)",
                r2.failures.stream().noneMatch(f -> f.contains(wantFrag)));
    }
}
