import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regenerate ~/workspace/atlas/01 📈 Stocks/Valuation Dashboard.md from the valuation scoreboard.
 *
 * <p>Java port of {@code generate_valuation_dashboard.py} (2026-09-27) —
 * behavior-identical. Every formatting rule mirrors the Python f-strings,
 * including number rounding (half-even on the exact binary value, like
 * Python's float formatting).
 *
 * <p>Data: {@code ~/workspace/valuation_scoreboard/scoreboard.json} (all valuation
 * sources), {@code ~/workspace/stock_watch/price_cache.json} (market prices;
 * "—" if missing), {@code ~/workspace/stock_watch/watchlist.json} (the holding tickers; count derived at render time).
 *
 * <p>Run:
 * <pre>
 *   ~/workspace/java/jdk-21/bin/java ~/workspace/atlas-scripts/GenerateValuationDashboard.java [--base-dir DIR]
 * </pre>
 * {@code --base-dir} is a test-only override (defaults to the user home);
 * normal runs omit it and behave exactly like the Python script.
 */
public class GenerateValuationDashboard {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final String[] OURS_SOURCES = {"ours"};

    /** One scoreboard row: ticker, source, value, date, company. */
    static final class Entry {
        String ticker;
        String source;
        Double value; // null when the JSON value is null
        String date;
        String company;
    }

    /** Python f"${v:,.2f}" — half-even rounding, thousands separators, "—" for null. */
    static String money(Double v) {
        if (v == null) {
            return "\u2014";
        }
        return "$" + grouped(fixed2(v));
    }

    /** Python f"{x:.2f}" on the exact binary value (round-half-even). */
    static String fixed2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Python f"{x:.1f}" on the exact binary value (round-half-even). */
    static String fixed1(double v) {
        return new BigDecimal(v).setScale(1, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Python f"{x:.0f}" on the exact binary value (round-half-even). */
    static String fixed0(double v) {
        return new BigDecimal(v).setScale(0, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Insert thousands separators into a plain "1234.56" / "-1234.56" string. */
    static String grouped(String plain) {
        boolean neg = plain.startsWith("-");
        String body = neg ? plain.substring(1) : plain;
        int dot = body.indexOf('.');
        String intPart = dot == -1 ? body : body.substring(0, dot);
        String fracPart = dot == -1 ? "" : body.substring(dot);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < intPart.length(); i++) {
            if (i > 0 && (intPart.length() - i) % 3 == 0) {
                b.append(',');
            }
            b.append(intPart.charAt(i));
        }
        return (neg ? "-" : "") + b + fracPart;
    }

    /** Python truthiness for numbers: None and 0.0 are falsy. */
    static boolean truthy(Double v) {
        return v != null && v != 0.0;
    }

    /**
     * Latest scoreboard entry for a ticker among the given sources.
     * Python: max by date string; ties keep the first (we use strictly-greater).
     */
    static Entry latest(List<Entry> entries, String ticker, Set<String> sources) {
        Entry best = null;
        for (Entry e : entries) {
            if (!e.ticker.equals(ticker) || !sources.contains(e.source) || e.value == null) {
                continue;
            }
            if (best == null || e.date.compareTo(best.date) > 0) {
                best = e;
            }
        }
        return best;
    }

    /**
     * Tickers with a numeric value in the verified NotebookLM batch file.
     * Mirrors the Python verified_notebooklm_tickers(): a missing/unreadable
     * file or a missing "values" key yields an empty set (never NotebookLM's).
     */
    static Set<String> verifiedNotebooklmTickers(Path batchFile) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>)
                    ((Map<String, Object>) Json.parse(
                            Files.readString(batchFile, StandardCharsets.UTF_8))).get("values");
            if (values == null) {
                return new LinkedHashSet<>();
            }
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                if (e.getValue() instanceof Number) {
                    out.add(e.getKey());
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return new LinkedHashSet<>();
        }
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

    public static void main(String[] args) throws Exception {
        Path base = homeDir();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--base-dir")) {
                base = Paths.get(args[i + 1]);
            }
        }
        Path ws = base.resolve("workspace");
        Path sbFile = ws.resolve("valuation_scoreboard/scoreboard.json");
        Path cacheFile = ws.resolve("stock_watch/price_cache.json");
        Path wlFile = ws.resolve("stock_watch/watchlist.json");
        Path outFile = ws.resolve("atlas/01 📈 Stocks/Valuation Dashboard.md");
        Path nbBatch = ws.resolve(
                "goals/quarterly-sec-filing-watch-with-dcf-updates/hidden_files/notebooklm_dcf_batch_2026-09-26.json");

        // 1. Load the scoreboard entries.
        @SuppressWarnings("unchecked")
        List<Object> rawEntries = (List<Object>)
                ((Map<String, Object>) Json.parse(
                        Files.readString(sbFile, StandardCharsets.UTF_8))).get("entries");
        List<Entry> entries = new ArrayList<>();
        for (Object o : rawEntries) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            Entry e = new Entry();
            e.ticker = (String) m.get("ticker");
            e.source = (String) m.get("source");
            Object v = m.get("value");
            e.value = (v instanceof Number) ? ((Number) v).doubleValue() : null;
            e.date = (String) m.get("date");
            Object company = m.get("company");
            e.company = company == null ? "" : (String) company;
            entries.add(e);
        }

        @SuppressWarnings("unchecked")
        List<Object> tickers = (List<Object>)
                ((Map<String, Object>) Json.parse(
                        Files.readString(wlFile, StandardCharsets.UTF_8))).get("tickers");

        // 2. Market prices: "quotes" first, then "prices"; missing file -> empty.
        Map<String, Object> prices = new LinkedHashMap<>();
        String cacheDate = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cache = (Map<String, Object>) Json.parse(
                    Files.readString(cacheFile, StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            Map<String, Object> quotes = (Map<String, Object>) cache.get("quotes");
            if (quotes == null || quotes.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> alt = (Map<String, Object>) cache.get("prices");
                quotes = alt;
            }
            if (quotes != null) {
                prices = quotes;
            }
            Object wd = cache.get("written_date");
            cacheDate = wd == null ? "unknown date" : (String) wd;
        } catch (IOException e) {
            // Python: FileNotFoundError -> prices = {}, cache_date = None
            cacheDate = null;
        }

        // 3. Company names, first-seen wins (Python setdefault).
        Map<String, String> names = new LinkedHashMap<>();
        for (Entry e : entries) {
            names.putIfAbsent(e.ticker, e.company);
        }

        Set<String> verifiedNb = verifiedNotebooklmTickers(nbBatch);
        Set<String> oursSources = new LinkedHashSet<>(List.of(OURS_SOURCES));
        Set<String> nblmSources = Set.of("notebooklm");
        Set<String> creatSources = Set.of("creators");
        Set<String> analSources = Set.of("analyst");

        // One row per ticker, in watchlist order.
        List<String> tableRows = new ArrayList<>();
        List<Object[]> flags = new ArrayList<>(); // {ticker, ours, nblm, div}
        for (Object tObj : tickers) {
            String t = (String) tObj;
            Entry ours = latest(entries, t, oursSources);
            Entry nblm = verifiedNb.contains(t) ? latest(entries, t, nblmSources) : null;
            Entry creat = latest(entries, t, creatSources);
            Entry anal = latest(entries, t, analSources);

            // Market price: a dict quote has "price"; a bare number is the price.
            Double mp = null;
            Object q = prices.get(t);
            if (q instanceof Map) {
                Object pq = ((Map<String, Object>) q).get("price");
                mp = (pq instanceof Number) ? ((Number) pq).doubleValue() : null;
            } else if (q instanceof Number) {
                mp = ((Number) q).doubleValue();
            }

            Double oursV = (ours == null) ? null : ours.value;
            // Divergence between our DCF and NotebookLM's (>15% listed in
            // the Disagreements section below).
            Double div = null;
            if (truthy(oursV) && nblm != null) {
                div = Math.abs(oursV - nblm.value) / Math.abs(nblm.value);
            }
            if (div != null && div > 0.15) {
                flags.add(new Object[]{t, ours, nblm, div});
            }

            String oursCell = money(oursV) + (ours != null ? " (" + ours.date + ")" : "");
            String nblmCell = (nblm == null) ? "\u2014" : money(nblm.value) + " (" + nblm.date + ")";
            String creatCell = (creat == null) ? "\u2014" : money(creat.value) + " (" + creat.date + ")";
            String analCell = (anal == null) ? "\u2014" : money(anal.value) + " (" + anal.date + ")";
            tableRows.add("| " + t + " | " + names.getOrDefault(t, "") + " | " + money(mp)
                    + " | " + oursCell + " | " + nblmCell + " | " + creatCell
                    + " | " + analCell + " |");
        }

        // 4. Assemble the dashboard markdown.
        String today = LocalDate.now(ET).toString(); // datetime.now(ET).strftime('%Y-%m-%d')
        List<String> lines = new ArrayList<>();
        lines.add("---");
        lines.add("title: Valuation Dashboard");
        lines.add("tags: [dashboard, valuation]");
        lines.add("---");
        lines.add("");
        // What's-new section (Rahik's standing rule 2026-10-08): spliced from
        // valuation_scoreboard/whats_new.md so it survives regeneration.
        // Update that snippet file (date + one line) whenever the dashboard changes.
        Path whatsNewFile = ws.resolve("valuation_scoreboard/whats_new.md");
        if (Files.exists(whatsNewFile)) {
            lines.addAll(Files.readAllLines(whatsNewFile, StandardCharsets.UTF_8));
        } else {
            // Fallback seed so the section always exists.
            lines.add("## What's new");
            lines.add("");
            lines.add("- " + today + " — Dashboard covers " + names.size()
                    + " holdings across four value columns (ours, NotebookLM, creator, analyst);"
                    + " regenerated from scoreboard.json.");
        }
        lines.add("");
        lines.add("# Valuation Dashboard");
        lines.add("");
        // Carry over the existing summary-story block, if any: it is maintained
        // by the summaries pipeline, not by this generator, so preserve it here
        // instead of wiping it on regen.
        if (Files.exists(outFile)) {
            String prev = Files.readString(outFile, StandardCharsets.UTF_8);
            int s = prev.indexOf("<!-- summary-story:start -->");
            int e = prev.indexOf("<!-- summary-story:end -->");
            if (s >= 0 && e > s) {
                String block = prev.substring(s, e + "<!-- summary-story:end -->".length()).strip();
                for (String bl : block.split("\n")) {
                    lines.add(bl);
                }
                lines.add("");
            }
        }
        lines.add("*Auto-maintained \u2014 regenerated " + today
                + " from `~/workspace/valuation_scoreboard/scoreboard.json`*");
        lines.add("(market prices from `~/workspace/stock_watch/price_cache.json`"
                + (cacheDate != null
                        ? ", dated " + cacheDate
                        : " \u2014 cache not yet written, run the 07:30 closes job first")
                + ").");
        lines.add("\"\u2014\" = no value on record for that source; nothing is invented.");
        lines.add("");
        lines.add("| Ticker | Company | Market | Our DCF | NotebookLM | Creators | Analyst PT |");
        lines.add("|---|---|---|---|---|---|---|");
        lines.addAll(tableRows);
        lines.add("");
        lines.add("## Disagreements \u2014 our DCF vs NotebookLM (>15% apart)");
        lines.add("");
        if (flags.isEmpty()) {
            lines.add("None right now. Run `python3 ~/workspace/dcf_disagreement.py` for the full spotlight.");
        } else {
            // Python: sorted(flags, key=lambda s: -s[3]) — stable, most-diverged first
            flags.sort((a, b) -> Double.compare((Double) b[3], (Double) a[3]));
            for (Object[] f : flags) {
                String t = (String) f[0];
                Entry ours = (Entry) f[1];
                Entry nblm = (Entry) f[2];
                double div = (Double) f[3];
                lines.add("- **" + t + "** (" + names.getOrDefault(t, "") + "): "
                        + fixed1(div * 100) + "% apart \u2014 "
                        + "ours (" + ours.source + ", " + ours.date + ") " + money(ours.value) + " vs "
                        + "NotebookLM (" + nblm.date + ") " + money(nblm.value) + ". "
                        + "Assumption sets: ours in `~/workspace/sec_filings/dcf_inputs_all.json`; "
                        + "NotebookLM's in the notebook notes.");
            }
        }
        lines.add("");
        lines.add("## Sources");
        lines.add("");
        lines.add("- Valuation values: `~/workspace/valuation_scoreboard/scoreboard.json` "
                + "(every entry dated and source-referenced).");
        lines.add("- Market prices: `~/workspace/stock_watch/price_cache.json` "
                + "(written by the 07:30 closes job via `batch_quotes.py --write-cache`).");
        lines.add("- Disagreement logic: `~/workspace/dcf_disagreement.py` (>15% threshold).");
        lines.add("");
        lines.add("## Related");
        lines.add("");
        lines.add("- [[Stock Research]]");
        lines.add("- [[Valuation Scoreboard]]");
        lines.add("- [[DCF Model Lab]]");
        lines.add("- [[Stock Research Hub]]");
        lines.add("- [[Home]]");
        lines.add("");
        // Idea #40: derived-file stamp as an HTML comment — invisible in
        // Obsidian reading view, but present for DerivedFiles --check-inputs
        // content detection. \u2014 is the em dash, escaped for pure ASCII.
        lines.add("<!-- derived from workbook_data.json via RebuildAll \u2014 "
                + "do not edit, do not read as input -->");

        // 5. Vault-write guard: sanitize right before the write (replaces the
        // Python `from sanitize import sanitize`), then write the file.
        Files.writeString(outFile, sanitize(String.join("\n", lines)), StandardCharsets.UTF_8);
        System.out.println("Wrote " + outFile + " (" + tableRows.size()
                + " tickers, " + flags.size() + " flagged)");
    }
}
