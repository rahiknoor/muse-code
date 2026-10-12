// MemoryStaleness.java — IDEA 2: memory staleness sweep (REPORT-ONLY).
//
// Memory files only grow; old entries quietly contradict new reality. This
// tool diffs ~/MEMORY.md, the latest ~/memory/2026-*.md daily log, and Atlas
// stock-note frontmatter against the LIVE JSON backends and flags
// contradictions for Rahik's review.
//
//   --sweep      run all checks; print findings; exit 1 on CRITICAL/MAJOR,
//                exit 0 when clean or MINOR/INFO only.
//   --self-test  synthetic contradiction + clean case (shared SelfTest).
//   --home DIR   scan a different home (used by --self-test; default $HOME).
//
// REPORT-ONLY GUARANTEE: this tool never writes to ~/MEMORY.md, ~/memory/,
// ~/workspace/atlas/, or any JSON backend. It only reads and prints. Findings
// are flags for review — the repair direction is always a human call, so the
// weekly cron wires --sweep as a flag-raising step with NO auto-repair leg.
//
// CHECKS (all mechanical, no NLP):
//   ROSTER-COUNT    "NN companies/stocks/holdings/tickers/equities" claims in
//                   MEMORY.md + latest daily log vs watchlist.json size.
//   OURS-DRIFT      ticker + $value + DCF/intrinsic keyword lines in MEMORY.md
//                   vs blessed.json ours value (flag drift, never fix).
//                   Daily logs are excluded: they are append-only history, and
//                   an old value that was true when written is not stale.
//   CRON-SCHEDULE   schedule claims ("hourly", "daily", HH:MM) adjacent to a
//                   cron id in MEMORY.md + latest daily log vs the live cron
//                   inventory (cron.d + goal crons frontmatter). Also flags
//                   backticked job-like ids that match no live cron.
//   PRICE-CACHE     price_cache.json written_date vs last trading weekday.
//   ATLAS           01 Stocks note frontmatter (dcf, market_price, notebooklm,
//                   projection_price, values_asof) vs blessed / price_cache /
//                   notebooklm_values / analyst_targets / our_dcf asof.
//   METHODOLOGY     named parameter claims in MEMORY.md (growth cap, terminal
//                   growth, mid-year discounting, growth fade, Damodaran)
//                   vs research_notes/dcf_methodology.md.
//   NLM-VERIFIED    "NN values in the verified batch" / "NN unverified"
//                   claims in MEMORY.md vs the verified batch file + backend.
//   PATH-EXISTENCE  ~/workspace/... and ~/memory/... paths cited in MEMORY.md
//                   that no longer exist on disk.
//
// Usage:
//   java --class-path ~/workspace/java-shared/classes \
//       ~/workspace/memory_audit/MemoryStaleness.java --sweep
//
// NOTE (TOOLS.md): System.getProperty("user.home") returns /root here while
// $HOME is /home/hatch — always use System.getenv("HOME"), never the property.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MemoryStaleness {

    enum Sev { CRITICAL, MAJOR, MINOR, INFO }

    /** One staleness finding. Printed as: file:line [SEV] CHECK — claim | backend */
    record Finding(String file, int line, String check, Sev sev,
                   String claim, String backend) {
        @Override public String toString() {
            return file + ":" + line + " [" + sev + "] " + check
                    + " — claim: " + claim + " | backend: " + backend;
        }
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser (objects, arrays, strings+escapes, numbers,
    // true/false/null). Dependency-free: RunAllTests launches suites with
    // only java-shared/classes on the classpath, so org.json is unavailable.
    // ------------------------------------------------------------------
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) {
                throw new IllegalArgumentException("trailing chars at " + j.i);
            }
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("eof");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> str();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> num();
            };
        }

        private Object lit(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw new IllegalArgumentException("bad literal at " + i);
            }
            i += word.length();
            return v;
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new IllegalArgumentException("expected : at " + i);
                }
                i++;
                m.put(k, value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("eof in obj");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , at " + i);
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("eof in arr");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , at " + i);
            }
        }

        private String str() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("expected \" at " + i);
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("eof in str");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("eof in esc");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("bad \\u at " + i);
                            }
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private Number num() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length()
                    && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'
                        || s.charAt(i) == 'e' || s.charAt(i) == 'E'
                        || s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            String t = s.substring(start, i);
            try {
                if (t.contains(".") || t.contains("e") || t.contains("E")) {
                    return Double.parseDouble(t);
                }
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number '" + t + "'");
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object o) {
            if (o instanceof Map) return (Map<String, Object>) o;
            throw new IllegalArgumentException("not an object: " + o);
        }

        @SuppressWarnings("unchecked")
        static List<Object> list(Object o) {
            if (o instanceof List) return (List<Object>) o;
            throw new IllegalArgumentException("not a list: " + o);
        }

        static String str(Object o) { return o == null ? null : o.toString(); }

        static double dbl(Object o) {
            if (o instanceof Number n) return n.doubleValue();
            throw new IllegalArgumentException("not a number: " + o);
        }
    }

    // ------------------------------------------------------------------
    // Loaded backends + cron inventory for one sweep run.
    // ------------------------------------------------------------------
    static final class Sweep {
        Path home;
        Path ws;
        List<String> tickers = new ArrayList<>();
        Map<String, Double> blessedOurs = new LinkedHashMap<>();
        Map<String, Double> ourDcf = new LinkedHashMap<>();
        String ourDcfAsof = "";
        LocalDate priceWritten;
        Map<String, Double> priceQuotes = new LinkedHashMap<>();
        Map<String, Double> notebooklm = new LinkedHashMap<>(); // null = unverified
        Map<String, Double> analystTarget = new LinkedHashMap<>();
        int nbBatchNumeric = -1; // numeric values in the verified batch file
        String methodology = "";
        List<CronJob> crons = new ArrayList<>();
        String memoryMd = "";
        List<String> memoryLines = new ArrayList<>();
        String latestLog = "";
        List<String> latestLogLines = new ArrayList<>();
        String latestLogName = "";
        List<Finding> findings = new ArrayList<>();

        void add(String file, int line, String check, Sev sev,
                 String claim, String backend) {
            findings.add(new Finding(file, line, check, sev, claim, backend));
        }

        boolean hasBlocking() {
            return findings.stream()
                    .anyMatch(f -> f.sev() == Sev.CRITICAL || f.sev() == Sev.MAJOR);
        }
    }

    record CronJob(String id, boolean enabled, String kind,
                   String time, String every, String file) {
        /** Expected plain-English period word, or "" when not mappable. */
        String expectedWord() {
            return switch (kind) {
                case "daily" -> "daily";
                case "weekly" -> "weekly";
                case "monthly" -> "monthly";
                case "yearly" -> "yearly";
                case "interval" -> switch (every) {
                    case "1h" -> "hourly";
                    case "168h" -> "weekly";
                    default -> "";
                };
                default -> "";
            };
        }
    }

    static String readFile(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    static List<String> readLines(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    static String shortPath(Path home, Path p) {
        String h = home.toString();
        String s = p.toString();
        return s.startsWith(h) ? "~" + s.substring(h.length()) : s;
    }

    /** Load every backend a check needs. Missing files degrade to empty maps. */
    static Sweep load(Path home) throws IOException {
        Sweep s = new Sweep();
        s.home = home;
        s.ws = home.resolve("workspace");

        // watchlist.json -> tickers
        Path watchlist = s.ws.resolve("stock_watch/watchlist.json");
        if (Files.exists(watchlist)) {
            Map<String, Object> w = Json.obj(Json.parse(readFile(watchlist)));
            for (Object t : Json.list(w.get("tickers"))) {
                s.tickers.add(Json.str(t));
            }
        }

        // blessed.json -> columns.ours.tickers[T].value
        Path blessed = s.ws.resolve("dcf_csv/blessed.json");
        if (Files.exists(blessed)) {
            Map<String, Object> b = Json.obj(Json.parse(readFile(blessed)));
            Map<String, Object> cols = Json.obj(b.get("columns"));
            Map<String, Object> ours = Json.obj(cols.get("ours"));
            Map<String, Object> tk = Json.obj(ours.get("tickers"));
            for (Map.Entry<String, Object> e : tk.entrySet()) {
                s.blessedOurs.put(e.getKey(), Json.dbl(Json.obj(e.getValue()).get("value")));
            }
        }

        // our_dcf.json -> asof + values
        Path ourDcf = s.ws.resolve("dcf_csv/our_dcf.json");
        if (Files.exists(ourDcf)) {
            Map<String, Object> d = Json.obj(Json.parse(readFile(ourDcf)));
            s.ourDcfAsof = Json.str(d.get("asof"));
            Map<String, Object> vals = Json.obj(d.get("values"));
            for (Map.Entry<String, Object> e : vals.entrySet()) {
                s.ourDcf.put(e.getKey(), Json.dbl(Json.obj(e.getValue()).get("value")));
            }
        }

        // price_cache.json -> written_date + quotes[T].price
        Path cache = s.ws.resolve("stock_watch/price_cache.json");
        if (Files.exists(cache)) {
            Map<String, Object> c = Json.obj(Json.parse(readFile(cache)));
            Object wd = c.get("written_date");
            if (wd != null) s.priceWritten = LocalDate.parse(Json.str(wd));
            // Cache may store under "quotes" or "prices" (same entry shape);
            // RefreshValuations.java already handles both — mirror it here.
            Object q = c.get("quotes");
            if (!(q instanceof Map) || ((Map<?, ?>) q).isEmpty()) q = c.get("prices");
            Map<String, Object> quotes = q == null ? Map.of() : Json.obj(q);
            for (Map.Entry<String, Object> e : quotes.entrySet()) {
                Object price = Json.obj(e.getValue()).get("price");
                if (price instanceof Number) {
                    s.priceQuotes.put(e.getKey(), Json.dbl(price));
                }
            }
        }

        // notebooklm_values.json -> values[T].value (null = unverified)
        Path nlm = s.ws.resolve("dcf_csv/notebooklm_values.json");
        if (Files.exists(nlm)) {
            Map<String, Object> n = Json.obj(Json.parse(readFile(nlm)));
            Map<String, Object> vals = Json.obj(n.get("values"));
            for (Map.Entry<String, Object> e : vals.entrySet()) {
                Object v = Json.obj(e.getValue()).get("value");
                s.notebooklm.put(e.getKey(), v instanceof Number ? Json.dbl(v) : null);
            }
        }

        // analyst_targets.json -> [T].target
        Path analyst = s.ws.resolve("dcf_csv/analyst_targets.json");
        if (Files.exists(analyst)) {
            Map<String, Object> a = Json.obj(Json.parse(readFile(analyst)));
            for (Map.Entry<String, Object> e : a.entrySet()) {
                Object t = Json.obj(e.getValue()).get("target");
                if (t instanceof Number) s.analystTarget.put(e.getKey(), Json.dbl(t));
            }
        }

        // verified NotebookLM batch file -> count of numeric values
        Path batch = s.ws.resolve(
                "goals/quarterly-sec-filing-watch-with-dcf-updates"
                        + "/hidden_files/notebooklm_dcf_batch_2026-09-26.json");
        if (Files.exists(batch)) {
            Map<String, Object> b = Json.obj(Json.parse(readFile(batch)));
            Map<String, Object> vals = Json.obj(b.get("values"));
            int n = 0;
            for (Object v : vals.values()) {
                if (v instanceof Number) n++;
            }
            s.nbBatchNumeric = n;
        }

        // methodology doc
        Path method = s.ws.resolve("research_notes/dcf_methodology.md");
        if (Files.exists(method)) s.methodology = readFile(method);

        // cron inventory: cron.d + goal crons (frontmatter of the saved .md copies)
        List<Path> roots = new ArrayList<>();
        Path cronD = s.ws.resolve("cron.d");
        if (Files.isDirectory(cronD)) roots.add(cronD);
        Path goals = s.ws.resolve("goals");
        if (Files.isDirectory(goals)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(goals)) {
                for (Path g : ds) {
                    Path crons = g.resolve("crons");
                    if (Files.isDirectory(crons)) roots.add(crons);
                }
            }
        }
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                        .filter(q -> q.toString().endsWith(".md"))::iterator) {
                    // _archive/ and _invalid/ hold retired copies, not live jobs
                    String ps = p.toString();
                    if (ps.contains("/_archive/") || ps.contains("/_invalid/")) continue;
                    CronJob cj = parseCronFile(p);
                    if (cj != null) s.crons.add(cj);
                }
            }
        }

        // MEMORY.md
        Path mem = home.resolve("MEMORY.md");
        if (Files.exists(mem)) {
            s.memoryMd = readFile(mem);
            s.memoryLines = readLines(mem);
        }

        // latest daily log
        Path memDir = home.resolve("memory");
        if (Files.isDirectory(memDir)) {
            List<Path> logs = new ArrayList<>();
            try (DirectoryStream<Path> ds =
                         Files.newDirectoryStream(memDir, "2026-*.md")) {
                for (Path p : ds) logs.add(p);
            }
            logs.sort((a, b) -> a.getFileName().toString()
                    .compareTo(b.getFileName().toString()));
            if (!logs.isEmpty()) {
                Path latest = logs.get(logs.size() - 1);
                s.latestLogName = latest.getFileName().toString();
                s.latestLog = readFile(latest);
                s.latestLogLines = readLines(latest);
            }
        }
        return s;
    }

    /** Minimal frontmatter parse: id, enabled, schedule.kind/time/every. */
    static CronJob parseCronFile(Path p) {
        try {
            List<String> lines = readLines(p);
            if (lines.isEmpty() || !lines.get(0).trim().equals("---")) return null;
            String id = null;
            boolean enabled = true;
            String kind = "", time = "", every = "";
            boolean inSchedule = false;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().equals("---")) break;
                if (!line.startsWith(" ") && !line.startsWith("\t")) {
                    inSchedule = line.startsWith("schedule:");
                    if (line.startsWith("id:")) id = line.substring(3).trim();
                    if (line.startsWith("enabled:")) {
                        enabled = line.substring(8).trim().equals("true");
                    }
                    continue;
                }
                if (inSchedule) {
                    String t = line.trim();
                    if (t.startsWith("kind:")) kind = t.substring(5).trim();
                    if (t.startsWith("time:")) time = t.substring(5).trim();
                    if (t.startsWith("every:")) every = t.substring(6).trim();
                }
            }
            if (id == null || id.isEmpty()) return null;
            return new CronJob(id, enabled, kind, time, every,
                    shortPath(Paths.get(System.getenv("HOME")), p));
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // The checks. Each is mechanical: regex/keyword extraction on memory
    // text, compared against a loaded backend. No NLP, no guessing.
    // ------------------------------------------------------------------

    static Pattern tickerAlternation(List<String> tickers) {
        List<String> q = new ArrayList<>(tickers);
        q.sort((a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder sb = new StringBuilder("\\b(");
        for (int i = 0; i < q.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(q.get(i)));
        }
        return Pattern.compile(sb.append(")\\b").toString());
    }

    /** $1,234.56 — never matches $500B / $2M (market caps) or $2026 (years).
     *  A sentence-final period after the amount is fine ("...is $100.00."). */
    static final Pattern AMOUNT =
            Pattern.compile("\\$\\s*([\\d,]+(?:\\.\\d+)?)(?![\\dBMK])");
    static final Pattern DCF_KW =
            Pattern.compile("(?i)\\bdcf\\b|intrinsic|fair value|\\bours\\b");
    static final Pattern NARRATIVE_EXCL = Pattern.compile(
            "revert|incident|corrupt|→|->|changed from|was \\$|\\bformerly\\b");
    static final Pattern COUNT_CLAIM = Pattern.compile(
            "(?i)\\b(\\d{1,3})\\s+(companies|stocks|holdings|tickers|equities)\\b");
    static final Pattern TARGET_KW =
            Pattern.compile("(?i)\\btarget\\b|projection");
    /** Website-consensus values (GF Value, Finbox ...) are not market prices. */
    static final Pattern SITE_KW = Pattern.compile(
            "(?i)\\b(guru|gurufocus|valueinvesting|finbox|valuesense|simply wall|alpha spread|morningstar|gf value)\\b");
    /** Roster context: the count must read as a claim about HIS holdings. */
    static final Pattern ROSTER_CTX = Pattern.compile(
            "(?i)\\b(all|every|entire|full|complete|IRA|Roth|holdings?|watchlist|portfolio)\\b");

    static Double parseAmount(String raw) {
        String t = raw.replace(",", "");
        if (t.matches("(19|20)\\d{2}")) return null; // a year, not a value
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** First $amount within 60 chars after the ticker on this line, if any. */
    static Double amountAfterTicker(String line, int tickerEnd) {
        Matcher m = AMOUNT.matcher(
                line.substring(tickerEnd, Math.min(line.length(), tickerEnd + 60)));
        if (!m.find()) return null;
        return parseAmount(m.group(1));
    }

    static String amountTextAfterTicker(String line, int tickerEnd) {
        Matcher m = AMOUNT.matcher(
                line.substring(tickerEnd, Math.min(line.length(), tickerEnd + 60)));
        return m.find() ? m.group(1) : null;
    }

    // ---- CHECK 1: ROSTER-COUNT ---------------------------------------
    // "NN companies/stocks/..." claims in MEMORY.md + latest daily log vs
    // watchlist.json. Only the latest log: older logs are history, and a
    // count that was true when written is not stale.
    static void checkRoster(Sweep s) {
        checkRosterText(s, "~/MEMORY.md", s.memoryLines);
        if (!s.latestLogName.isEmpty()) {
            checkRosterText(s, "~/memory/" + s.latestLogName, s.latestLogLines);
        }
    }

    static void checkRosterText(Sweep s, String file, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = COUNT_CLAIM.matcher(line);
            while (m.find()) {
                // Rates ("3 stocks/week") and batch samples ("20 companies with
                // comparable FCF") are not roster claims; require roster context
                // (all/every, IRA/Roth, holdings/watchlist/portfolio) nearby.
                if (m.end() < line.length() && line.charAt(m.end()) == '/') continue;
                int c0 = Math.max(0, m.start() - 60), c1 = Math.min(line.length(), m.end() + 60);
                if (!ROSTER_CTX.matcher(line.substring(c0, c1)).find()) continue;
                int claimed = Integer.parseInt(m.group(1));
                if (claimed != s.tickers.size()) {
                    s.add(file, i + 1, "ROSTER-COUNT", Sev.MAJOR,
                            "claims " + claimed + " " + m.group(2),
                            "watchlist.json holds " + s.tickers.size() + " tickers");
                }
            }
        }
    }

    // ---- CHECK 2: OURS-DRIFT ------------------------------------------
    // MEMORY.md lines citing our DCF per ticker vs blessed.json ours.
    // Daily logs excluded (append-only history). Narrative incident
    // descriptions ("AMZN $31.04 → $147.76 revert") are excluded by keyword.
    static void checkOursDrift(Sweep s) {
        if (s.tickers.isEmpty() || s.blessedOurs.isEmpty()) return;
        Pattern tp = tickerAlternation(s.tickers);
        for (int i = 0; i < s.memoryLines.size(); i++) {
            String line = s.memoryLines.get(i);
            if (!DCF_KW.matcher(line).find()) continue;
            if (NARRATIVE_EXCL.matcher(line).find()) continue;
            Matcher tm = tp.matcher(line);
            while (tm.find()) {
                String t = tm.group(1);
                Double cited = amountAfterTicker(line, tm.end());
                if (cited == null) continue;
                Double backend = s.blessedOurs.get(t);
                if (backend == null) continue;
                double tol = Math.max(1.0, 0.01 * Math.abs(backend));
                if (Math.abs(cited - backend) > tol) {
                    s.add("~/MEMORY.md", i + 1, "OURS-DRIFT", Sev.CRITICAL,
                            t + " cited at $" + amountTextAfterTicker(line, tm.end()),
                            "blessed.json ours " + t + " = $" + backend
                                    + " (drift — flag for review, never auto-fix)");
                }
            }
        }
    }

    // ---- CHECK 3: CRON-SCHEDULE ----------------------------------------
    static final Pattern PERIOD_WORD =
            Pattern.compile("(?i)\\b(hourly|daily|weekly|monthly|yearly)\\b");
    static final Pattern EVERY_PAT =
            Pattern.compile("(?i)every\\s+(\\d+)\\s*(min|h|hour)");
    static final Pattern BACKTICK_ID =
            Pattern.compile("`([a-z0-9][a-z0-9\\-]*[a-z0-9])`");
    static final Pattern CRONISH =
            Pattern.compile("(?i)\\b(cron|job|sweep|digest|watch|alert|retry|pipeline|schedule)\\b");

    static String scheduleDesc(CronJob cj) {
        StringBuilder b = new StringBuilder(cj.kind());
        if (!cj.every().isEmpty()) b.append(" every ").append(cj.every());
        if (!cj.time().isEmpty()) b.append(" at ").append(cj.time());
        if (!cj.enabled()) b.append(" [disabled]");
        return b.toString();
    }

    static int lineOf(String text, int idx) {
        int line = 1;
        for (int i = 0; i < idx && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    static void checkCronSchedules(Sweep s) {
        // Blanking: period words live inside other job ids
        // ("daily" in stock-watch-daily-closes, "weekly" in
        // weekly-regression-sweep) — blank every known id before scanning.
        List<String> allIds = new ArrayList<>();
        for (CronJob cj : s.crons) allIds.add(cj.id());
        allIds.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (CronJob cj : s.crons) {
            if (!cj.enabled()) continue;
            // Time sub-check runs on MEMORY.md only: daily logs are full of
            // historical run timestamps (01:54 ET ...) that are not claims.
            checkScheduleText(s, "~/MEMORY.md", s.memoryMd, cj, allIds, true);
            if (!s.latestLog.isEmpty()) {
                checkScheduleText(s, "~/memory/" + s.latestLogName,
                        s.latestLog, cj, allIds, false);
            }
        }
        checkDeadCronIds(s);
    }

    /** Text of the sentence containing idx. ';' and newlines always split;
     *  '.'/'!'/'?' split only before whitespace/end, so file paths and
     *  decimals ("cron.d/weekly/x.md", "02:46:00") stay intact. */
    static String sentenceAround(String text, int idx) {
        int s0 = idx, s1 = idx;
        while (s0 > 0 && !isSentDelim(text, s0 - 1)) s0--;
        while (s1 < text.length() && !isSentDelim(text, s1)) s1++;
        return text.substring(s0, s1);
    }

    static boolean isSentDelim(String text, int p) {
        char c = text.charAt(p);
        if (c == ';' || c == '\n') return true;
        if (c == '.' || c == '!' || c == '?')
            return p + 1 >= text.length() || Character.isWhitespace(text.charAt(p + 1));
        return false;
    }

    /** HH:MM:SS or H:MM -> zero-padded HH:MM for comparison. */
    static String normTime(String t) {
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(t);
        if (!m.find()) return t;
        return String.format("%02d:%s",
                Integer.parseInt(m.group(1)), m.group(2));
    }

    static void checkScheduleText(Sweep s, String file, String text, CronJob cj,
                                  List<String> allIds, boolean checkTime) {
        String expWord = cj.expectedWord();
        int idx = 0;
        while ((idx = text.indexOf(cj.id(), idx)) >= 0) {
            int lineNo = lineOf(text, idx);
            // Claim scope = the sentence holding the id mention; period words
            // from neighbouring jobs' sentences must not leak in.
            String sent = sentenceAround(text, idx);
            // A period word directly introducing another known job id belongs
            // to that job ("the weekly weekly-thing cron"), not to cj.
            for (String id : allIds) {
                if (id.equals(cj.id())) continue;
                sent = sent.replaceAll("(?i)\\b(hourly|daily|weekly|monthly|yearly)\\s+"
                        + Pattern.quote(id), " ");
            }
            for (String id : allIds) sent = sent.replace(id, " ");
            // A period word inside a file path is never a schedule claim.
            sent = PATH_REF.matcher(sent).replaceAll(" ");
            sent = sent.replaceAll("(?i)\\b\\w+\\.d/\\w+", " ");

            Matcher pm = PERIOD_WORD.matcher(sent);
            while (pm.find()) {
                String w = pm.group(1).toLowerCase();
                if (!expWord.isEmpty() && !w.equals(expWord)) {
                    s.add(file, lineNo, "CRON-SCHEDULE", Sev.MAJOR,
                            "near '" + cj.id() + "': claims '" + w + "'",
                            "live schedule: " + scheduleDesc(cj));
                    break;
                }
            }
            if (cj.kind().equals("interval") && !cj.every().isEmpty()) {
                Matcher em = EVERY_PAT.matcher(sent);
                while (em.find()) {
                    String unit = em.group(2).toLowerCase().startsWith("h") ? "h" : "m";
                    String norm = em.group(1) + unit;
                    if (!norm.equals(cj.every())) {
                        s.add(file, lineNo, "CRON-SCHEDULE", Sev.MAJOR,
                                "near '" + cj.id() + "': claims 'every "
                                        + em.group(1) + " " + em.group(2) + "'",
                                "live schedule: " + scheduleDesc(cj));
                        break;
                    }
                }
            }
            if (checkTime && !cj.time().isEmpty()) {
                String want = normTime(cj.time());
                int t0 = Math.max(0, idx - 80);
                int t1 = Math.min(text.length(), idx + cj.id().length() + 80);
                String near = text.substring(t0, t1);
                // A time counts as a schedule claim only when a cue word
                // (runs/at/@/scheduled) introduces it — bare timestamps are
                // usually run history. "~"/about/around mark approximations.
                Matcher cm = Pattern.compile(
                        "(?i)(runs?|at|@|scheduled?)\\s+([01]?\\d|2[0-3]):([0-5]\\d)")
                        .matcher(near);
                while (cm.find()) {
                    String pre = near.substring(Math.max(0, cm.start() - 12), cm.start());
                    if (pre.contains("~")
                            || Pattern.compile("(?i)\\b(about|around|approx\\.?|roughly)\\b")
                                    .matcher(pre).find()) continue;
                    String got = String.format("%02d:%s",
                            Integer.parseInt(cm.group(2)), cm.group(3));
                    if (!got.equals(want)) {
                        s.add(file, lineNo, "CRON-SCHEDULE", Sev.MAJOR,
                                "near '" + cj.id() + "': claims time " + got,
                                "live schedule: " + scheduleDesc(cj));
                        break;
                    }
                }
            }
            idx += cj.id().length();
        }
    }

    /** Backticked job-like ids cited in MEMORY.md that match no live cron. */
    static void checkDeadCronIds(Sweep s) {
        Map<String, Boolean> live = new TreeMap<>();
        for (CronJob cj : s.crons) live.put(cj.id(), cj.enabled());
        for (int i = 0; i < s.memoryLines.size(); i++) {
            String line = s.memoryLines.get(i);
            Matcher m = BACKTICK_ID.matcher(line);
            while (m.find()) {
                String tok = m.group(1);
                if (!tok.contains("-") || tok.length() < 6) continue;
                if (tok.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    continue; // side-chat UUID, not a cron id
                if (live.containsKey(tok)) continue;
                int w0 = Math.max(0, m.start() - 80);
                int w1 = Math.min(line.length(), m.end() + 80);
                if (!CRONISH.matcher(line.substring(w0, w1)).find()) continue;
                s.add("~/MEMORY.md", i + 1, "CRON-SCHEDULE", Sev.MINOR,
                        "cites job `" + tok + "`",
                        "no live cron with that id (renamed or removed?)");
            }
        }
    }

    // ---- CHECK 4: PRICE-CACHE -------------------------------------------
    static LocalDate lastWeekday(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY
                || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    static long weekdaysBetween(LocalDate fromExclusive, LocalDate toInclusive) {
        long n = 0;
        for (LocalDate d = fromExclusive.plusDays(1);
             !d.isAfter(toInclusive); d = d.plusDays(1)) {
            DayOfWeek wd = d.getDayOfWeek();
            if (wd != DayOfWeek.SATURDAY && wd != DayOfWeek.SUNDAY) n++;
        }
        return n;
    }

    static void checkPriceCache(Sweep s) {
        // 4a: cache vintage vs last trading weekday
        if (s.priceWritten != null) {
            LocalDate expected = lastWeekday(LocalDate.now());
            if (s.priceWritten.isBefore(expected)) {
                long behind = weekdaysBetween(s.priceWritten, expected);
                String claim = "price_cache.json written " + s.priceWritten;
                String backend = "last trading weekday " + expected;
                if (behind >= 2) {
                    s.add("~/workspace/stock_watch/price_cache.json", 0,
                            "PRICE-CACHE", Sev.MAJOR,
                            claim + " (" + behind + " sessions behind)", backend);
                } else {
                    s.add("~/workspace/stock_watch/price_cache.json", 0,
                            "PRICE-CACHE", Sev.MINOR,
                            claim + " (1 session behind — refresh pending?)", backend);
                }
            }
        }
        // 4b: ticker price claims in MEMORY.md (non-DCF lines) vs the cache.
        // Daily logs excluded (append-only history).
        if (s.tickers.isEmpty() || s.priceQuotes.isEmpty()) return;
        Pattern tp = tickerAlternation(s.tickers);
        for (int i = 0; i < s.memoryLines.size(); i++) {
            String line = s.memoryLines.get(i);
            if (line.contains("%")) continue;              // a move description
            if (DCF_KW.matcher(line).find()) continue;     // check 2's territory
            if (TARGET_KW.matcher(line).find()) continue;  // analyst targets
            if (SITE_KW.matcher(line).find()) continue;    // website values
            if (NARRATIVE_EXCL.matcher(line).find()) continue;
            Matcher tm = tp.matcher(line);
            while (tm.find()) {
                String t = tm.group(1);
                Double cited = amountAfterTicker(line, tm.end());
                if (cited == null) continue;
                Double quote = s.priceQuotes.get(t);
                if (quote == null || quote == 0) continue;
                double drift = Math.abs(cited - quote) / quote;
                if (drift > 0.15) {
                    s.add("~/MEMORY.md", i + 1, "PRICE-DRIFT", Sev.MAJOR,
                            t + " cited at $" + amountTextAfterTicker(line, tm.end()),
                            "price_cache.json " + t + " = $" + quote);
                } else if (drift > 0.05) {
                    s.add("~/MEMORY.md", i + 1, "PRICE-DRIFT", Sev.MINOR,
                            t + " cited at $" + amountTextAfterTicker(line, tm.end()),
                            "price_cache.json " + t + " = $" + quote);
                }
            }
        }
    }
    // ---- CHECK 5: ATLAS frontmatter vs backends --------------------------
    // The vault is Rahik's query frontend; a note whose frontmatter disagrees
    // with a backend is the silent-rewrite class of bug.
    static Double parseFrontmatterNum(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("null")
                || t.equals("—") || t.equals("-")) return null;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Map<String, String> parseFrontmatter(List<String> lines) {
        Map<String, String> fm = new LinkedHashMap<>();
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) return fm;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().equals("---")) break;
            int c = line.indexOf(':');
            if (c > 0) fm.put(line.substring(0, c).trim(),
                    line.substring(c + 1).trim());
        }
        return fm;
    }

    static void checkAtlas(Sweep s) {
        Path stocks = s.ws.resolve("atlas/01 📈 Stocks");
        if (!Files.isDirectory(stocks)) return;
        List<Path> notes = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(stocks, "*.md")) {
            for (Path p : ds) notes.add(p);
        } catch (IOException e) {
            return;
        }
        notes.sort((a, b) -> a.getFileName().toString()
                .compareTo(b.getFileName().toString()));
        for (Path p : notes) {
            List<String> lines;
            try {
                lines = readLines(p);
            } catch (IOException e) {
                continue;
            }
            Map<String, String> fm = parseFrontmatter(lines);
            String ticker = fm.get("ticker");
            if (ticker == null || !s.tickers.contains(ticker)) continue;
            String file = "~/workspace/" + s.ws.relativize(p).toString().replace(
                    s.ws.getFileSystem().getSeparator(), "/");

            Double blessed = s.blessedOurs.get(ticker);
            Double fDcf = parseFrontmatterNum(fm.get("dcf"));
            if (blessed != null && fDcf != null
                    && Math.abs(blessed - fDcf) > 1e-6) {
                s.add(file, 1, "ATLAS", Sev.CRITICAL,
                        "frontmatter dcf=" + fDcf,
                        "blessed.json ours " + ticker + " = " + blessed);
            }

            Double quote = s.priceQuotes.get(ticker);
            Double fMp = parseFrontmatterNum(fm.get("market_price"));
            if (quote != null && fMp != null && Math.abs(quote - fMp) > 0.005) {
                s.add(file, 1, "ATLAS", Sev.MAJOR,
                        "frontmatter market_price=" + fMp,
                        "price_cache.json " + ticker + " = " + quote);
            }

            Double nb = s.notebooklm.get(ticker); // null = unverified
            Double fNb = parseFrontmatterNum(fm.get("notebooklm"));
            if (nb == null && fNb != null) {
                s.add(file, 1, "ATLAS", Sev.MAJOR,
                        "frontmatter notebooklm=" + fNb,
                        "notebooklm_values.json " + ticker
                                + " is null (unverified — must not be presented)");
            } else if (nb != null && fNb == null) {
                s.add(file, 1, "ATLAS", Sev.MAJOR,
                        "frontmatter notebooklm is blank",
                        "notebooklm_values.json " + ticker + " = " + nb
                                + " (note not refreshed)");
            } else if (nb != null && fNb != null && Math.abs(nb - fNb) > 1e-6) {
                s.add(file, 1, "ATLAS", Sev.MAJOR,
                        "frontmatter notebooklm=" + fNb,
                        "notebooklm_values.json " + ticker + " = " + nb);
            }

            Double at = s.analystTarget.get(ticker);
            Double fPp = parseFrontmatterNum(fm.get("projection_price"));
            if (at != null && fPp != null && Math.abs(at - fPp) > 1e-6) {
                s.add(file, 1, "ATLAS", Sev.MAJOR,
                        "frontmatter projection_price=" + fPp,
                        "analyst_targets.json " + ticker + " = " + at);
            }

            String asof = fm.get("values_asof");
            if (asof != null && s.ourDcfAsof != null && !s.ourDcfAsof.isEmpty()
                    && asof.compareTo(s.ourDcfAsof) < 0) {
                s.add(file, 1, "ATLAS", Sev.MINOR,
                        "values_asof=" + asof,
                        "our_dcf.json asof=" + s.ourDcfAsof
                                + " (note older than latest rebuild)");
            }
        }
    }

    // ---- CHECK 6: METHODOLOGY parameter claims ----------------------------
    // Named claims in MEMORY.md vs research_notes/dcf_methodology.md.
    // The doc is the canonical methodology; memory contradicting it is stale.
    static void checkMethodology(Sweep s) {
        if (s.methodology.isEmpty()) return;
        String doc = s.methodology;

        // growth cap: memory "growth cap NN%" vs doc's 15%
        boolean docStatesCap15 = Pattern.compile(
                "(?i)(cap[^\\n]{0,60}15\\s*%|15\\s*%[^\\n]{0,60}cap)")
                .matcher(doc).find();
        Matcher cm = Pattern.compile("(?i)growth cap\\D{0,60}?(\\d+(?:\\.\\d+)?)\\s*%")
                .matcher(s.memoryMd);
        while (cm.find()) {
            double claimed = Double.parseDouble(cm.group(1));
            if (Math.abs(claimed - 15.0) > 1e-9) {
                s.add("~/MEMORY.md", lineOf(s.memoryMd, cm.start()),
                        "METHODOLOGY", Sev.MAJOR,
                        "claims growth cap " + cm.group(1) + "%",
                        "dcf_methodology.md: growth capped at 15%");
            } else if (!docStatesCap15) {
                s.add("~/MEMORY.md", lineOf(s.memoryMd, cm.start()),
                        "METHODOLOGY", Sev.MAJOR,
                        "claims growth cap 15%",
                        "dcf_methodology.md no longer states a 15% cap");
            }
        }

        // terminal growth: memory "terminal growth NN%" must sit in the doc's
        // sane band (doc: typically 2–3.5%, 1.5% integrated oil)
        Matcher tm = Pattern.compile(
                "(?i)terminal growth\\D{0,60}?(\\d+(?:\\.\\d+)?)\\s*%")
                .matcher(s.memoryMd);
        while (tm.find()) {
            double claimed = Double.parseDouble(tm.group(1));
            if (claimed < 1.0 || claimed > 4.0) {
                s.add("~/MEMORY.md", lineOf(s.memoryMd, tm.start()),
                        "METHODOLOGY", Sev.MAJOR,
                        "claims terminal growth " + tm.group(1) + "%",
                        "dcf_methodology.md: typically 2–3.5% (1.5% integrated oil)");
            }
        }

        // presence claims: if memory asserts the feature, the doc must describe it
        String memLow = s.memoryMd.toLowerCase();
        String docLow = doc.toLowerCase();
        if (memLow.contains("mid-year") && !docLow.contains("mid-year")) {
            s.add("~/MEMORY.md", 0, "METHODOLOGY", Sev.MAJOR,
                    "asserts mid-year discounting",
                    "dcf_methodology.md does not mention mid-year");
        }
        if ((memLow.contains("growth fade") || memLow.contains("fade schedule"))
                && !docLow.contains("fad")) {
            s.add("~/MEMORY.md", 0, "METHODOLOGY", Sev.MAJOR,
                    "asserts growth fade",
                    "dcf_methodology.md does not mention fading");
        }
        if (memLow.contains("damodaran") && !docLow.contains("damodaran")) {
            s.add("~/MEMORY.md", 0, "METHODOLOGY", Sev.MAJOR,
                    "asserts Damodaran-anchored discount rates",
                    "dcf_methodology.md does not mention Damodaran");
        }
    }

    // ---- CHECK 7: NLM-VERIFIED --------------------------------------------
    // "NN values in the verified batch" / "NN unverified" claims in MEMORY.md
    // vs the verified batch file + notebooklm_values.json. Provenance guard:
    // only verified-batch values may be presented as NotebookLM's.
    static void checkNlmVerified(Sweep s) {
        if (s.nbBatchNumeric < 0) return;
        Matcher vm = Pattern.compile("(?i)(\\d{1,3})\\s+values?\\s+in the verified")
                .matcher(s.memoryMd);
        while (vm.find()) {
            int claimed = Integer.parseInt(vm.group(1));
            if (claimed != s.nbBatchNumeric) {
                s.add("~/MEMORY.md", lineOf(s.memoryMd, vm.start()),
                        "NLM-VERIFIED", Sev.MAJOR,
                        "claims " + claimed + " values in the verified batch",
                        "notebooklm_dcf_batch_2026-09-26.json holds "
                                + s.nbBatchNumeric + " numeric values");
            }
        }
        int unverified = s.notebooklm.size() - s.nbBatchNumeric;
        Matcher um = Pattern.compile("(?i)(\\d{1,3})\\s+unverified")
                .matcher(s.memoryMd);
        while (um.find()) {
            int claimed = Integer.parseInt(um.group(1));
            if (claimed != unverified) {
                s.add("~/MEMORY.md", lineOf(s.memoryMd, um.start()),
                        "NLM-VERIFIED", Sev.MAJOR,
                        "claims " + claimed + " unverified values",
                        s.notebooklm.size() + " tickers - " + s.nbBatchNumeric
                                + " verified = " + unverified + " unverified");
            }
        }
    }

    // ---- CHECK 8: PATH-EXISTENCE -------------------------------------------
    // ~/workspace/... and ~/memory/... paths cited in MEMORY.md that no
    // longer exist. Standing doc only — daily logs cite historical paths.
    static final Pattern PATH_REF =
            Pattern.compile("~/(?:workspace|memory)/[^\\s)\\]`'\",;<>]+");

    static void checkPaths(Sweep s) {
        Matcher m = PATH_REF.matcher(s.memoryMd);
        while (m.find()) {
            String ref = m.group();
            // Backticked path with spaces: extend to the closing backtick.
            if (m.start() > 0 && s.memoryMd.charAt(m.start() - 1) == '`') {
                int close = s.memoryMd.indexOf('`', m.end());
                if (close > m.end()) ref = s.memoryMd.substring(m.start(), close);
            }
            while (ref.endsWith(".") || ref.endsWith(":"))
                ref = ref.substring(0, ref.length() - 1);
            if (ref.contains("*") || ref.contains("...") || ref.contains("{")
                    || ref.contains("$")) continue;
            // Filenames with spaces ("Creator Buys.xlsx"): extend the ref with
            // following words while the longer path really exists on disk.
            String rest = s.memoryMd.substring(m.end());
            String[] words = rest.split("\\s+");
            String ext = ref;
            int added = 0;
            for (String w0 : words) {
                if (added >= 3) break;
                String w = w0.replaceAll("[.,:;]$", "");
                if (w.isEmpty()) continue;
                if (!w.matches("[\\w.\\-()]+")) break;
                String cand = ext + " " + w;
                if (Files.exists(s.home.resolve(cand.substring(2)))) { ext = cand; added++; }
                else break;
            }
            Path p = s.home.resolve(ext.substring(2));
            if (!Files.exists(p)) {
                s.add("~/MEMORY.md", lineOf(s.memoryMd, m.start()),
                        "PATH-EXISTENCE", Sev.MINOR,
                        "cites " + ref,
                        "path does not exist on disk");
            }
        }
    }

    static void runChecks(Sweep s) {
        checkRoster(s);
        checkOursDrift(s);
        checkCronSchedules(s);
        checkPriceCache(s);
        checkAtlas(s);
        checkMethodology(s);
        checkNlmVerified(s);
        checkPaths(s);
    }

    static void printReport(Sweep s) {
        for (Finding f : s.findings) {
            System.out.println(f);
        }
        long crit = s.findings.stream().filter(f -> f.sev() == Sev.CRITICAL).count();
        long major = s.findings.stream().filter(f -> f.sev() == Sev.MAJOR).count();
        long minor = s.findings.stream().filter(f -> f.sev() == Sev.MINOR).count();
        long info = s.findings.stream().filter(f -> f.sev() == Sev.INFO).count();
        System.out.println();
        if (s.findings.isEmpty()) {
            System.out.println("== MemoryStaleness: no findings — "
                    + "memory matches the live backends ==");
        } else {
            System.out.println("== MemoryStaleness: " + s.findings.size()
                    + " findings (" + crit + " critical, " + major + " major, "
                    + minor + " minor, " + info + " info) ==");
            System.out.println("REPORT-ONLY: findings are flags for review; "
                    + "nothing was edited.");
        }
    }

    public static void main(String[] args) throws Exception {
        String homeEnv = System.getenv("HOME");
        if (homeEnv == null || homeEnv.isEmpty()) {
            System.err.println("HOME is not set");
            System.exit(2);
        }
        Path home = Paths.get(homeEnv);
        boolean sweep = false, test = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sweep" -> sweep = true;
                case "--self-test" -> test = true;
                case "--home" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--home needs a directory");
                        System.exit(2);
                    }
                    home = Paths.get(args[++i]);
                }
                default -> {
                    System.err.println("unknown arg: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (test) {
            selfTest(); // calls SelfTest.finish()
            return;
        }
        if (!sweep) {
            System.err.println("usage: MemoryStaleness.java --sweep | --self-test"
                    + " [--home DIR]");
            System.exit(2);
        }
        Sweep s = load(home);
        runChecks(s);
        printReport(s);
        System.exit(s.hasBlocking() ? 1 : 0);
    }

    // ------------------------------------------------------------------
    // --self-test: synthetic contradiction proving detection fires, and a
    // clean case proving quiet. Uses the shared SelfTest harness.
    // ------------------------------------------------------------------
    static void writeFile(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    static String tickersJson(String... ts) {
        StringBuilder b = new StringBuilder("{\"tickers\":[");
        for (int i = 0; i < ts.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(ts[i]).append('"');
        }
        return b.append("]}").toString();
    }

    static String blessedJson(String[] ts, double[] vs) {
        StringBuilder b = new StringBuilder("{\"columns\":{\"ours\":{\"tickers\":{");
        for (int i = 0; i < ts.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(ts[i]).append("\":{\"value\":").append(vs[i]).append('}');
        }
        return b.append("}}}}").toString();
    }

    static String nlmJson(String[] ts, String[] vs) {
        StringBuilder b = new StringBuilder("{\"values\":{");
        for (int i = 0; i < ts.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(ts[i]).append("\":{\"value\":").append(vs[i]).append('}');
        }
        return b.append("}}").toString();
    }

    static String quotesJson(String[] ts, double[] vs, String written) {
        StringBuilder b = new StringBuilder(
                "{\"written_date\":\"" + written + "\",\"quotes\":{");
        for (int i = 0; i < ts.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(ts[i]).append("\":{\"price\":").append(vs[i]).append('}');
        }
        return b.append("}}").toString();
    }

    static String analystJson(String[] ts, double[] vs) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < ts.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(ts[i]).append("\":{\"target\":").append(vs[i]).append('}');
        }
        return b.append('}').toString();
    }

    static final String METHOD_MD =
            "The model caps growth at 15% (growth cap). Mid-year discounting applies "
                    + "to each year. Growth fading linearly to terminal growth, "
                    + "typically 2–3.5% (1.5% integrated oil). Mimics Damodaran.";

    /** Shared fake backends; the memory file + price vintage vary per case. */
    static Path fakeHome(String memoryMd, String writtenDate) throws IOException {
        Path home = Files.createTempDirectory("memstale-test-");
        String[] ts = {"NVDA", "META", "AMZN", "TSLA", "SOFI"};
        double[] ours = {100.0, 200.0, 50.0, 60.0, 10.0};
        writeFile(home.resolve("MEMORY.md"), memoryMd);
        writeFile(home.resolve("workspace/your_files/creator-buys/Creator Buys.xlsx"),
                "fake buys log");
        writeFile(home.resolve("memory/2026-09-28.md"),
                "- Daily log 2026-09-28: all quiet, nothing to record.\n");
        writeFile(home.resolve("workspace/stock_watch/watchlist.json"),
                tickersJson(ts));
        writeFile(home.resolve("workspace/stock_watch/price_cache.json"),
                quotesJson(ts, ours, writtenDate));
        writeFile(home.resolve("workspace/dcf_csv/blessed.json"),
                blessedJson(ts, ours));
        StringBuilder od = new StringBuilder("{\"asof\":\"2026-09-28\",\"values\":{");
        for (int i = 0; i < ts.length; i++) {
            if (i > 0) od.append(',');
            od.append('"').append(ts[i]).append("\":{\"value\":").append(ours[i]).append('}');
        }
        writeFile(home.resolve("workspace/dcf_csv/our_dcf.json"), od.append("}}").toString());
        writeFile(home.resolve("workspace/dcf_csv/notebooklm_values.json"),
                nlmJson(new String[]{"NVDA", "META", "AMZN", "TSLA", "SOFI"},
                        new String[]{"90.0", "80.0", "null", "null", "null"}));
        writeFile(home.resolve("workspace/dcf_csv/analyst_targets.json"),
                analystJson(ts, new double[]{120.0, 220.0, 55.0, 66.0, 11.0}));
        writeFile(home.resolve("workspace/research_notes/dcf_methodology.md"), METHOD_MD);
        writeFile(home.resolve("workspace/goals/quarterly-sec-filing-watch-with-dcf-updates"
                        + "/hidden_files/notebooklm_dcf_batch_2026-09-26.json"),
                "{\"values\":{\"NVDA\":90.0,\"META\":80.0}}");
        writeFile(home.resolve("workspace/atlas/01 📈 Stocks/NVDA.md"),
                "---\ntitle: NVDA\nticker: NVDA\nmarket_price: 100.0\ndcf: 100.0\n"
                        + "notebooklm: 90.0\nprojection_price: 120.0\nvalues_asof: 2026-09-28\n---\n");
        writeFile(home.resolve("workspace/cron.d/weekly/nightly-job__daily@02:46:00.md"),
                "---\nid: nightly-job\nenabled: true\nschedule:\n  kind: daily\n"
                        + "  time: 02:46:00\n---\n# Nightly job\n");
        writeFile(home.resolve("workspace/cron.d/weekly/weekly-thing__weekly@Sun-10:46:00.md"),
                "---\nid: weekly-thing\nenabled: true\nschedule:\n  kind: weekly\n"
                        + "  time: 10:46:00\n---\n# Weekly thing\n");
        return home;
    }

    static boolean has(List<Finding> fs, String check, Sev sev) {
        return fs.stream().anyMatch(f -> f.check().equals(check) && f.sev() == sev);
    }

    static long count(List<Finding> fs, String check) {
        return fs.stream().filter(f -> f.check().equals(check)).count();
    }

    static void selfTest() throws Exception {
        // ---- JSON parser unit tests ----
        Object p = Json.parse(
                "{\"a\":1,\"b\":[1,2.5,null,true],\"c\":\"x\\\"y\\u00e9\",\"d\":{\"e\":-1.5e3}}");
        Map<String, Object> m = Json.obj(p);
        SelfTest.checkEq("json: int", 1L, m.get("a"));
        SelfTest.checkEq("json: list size", 4, Json.list(m.get("b")).size());
        SelfTest.checkEq("json: escape+unicode", "x\"yé", m.get("c"));
        SelfTest.checkEq("json: nested sci", -1500.0, Json.dbl(Json.obj(m.get("d")).get("e")));
        boolean threw = false;
        try {
            Json.parse("{bad}");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        SelfTest.check("json: malformed throws", threw);

        // ---- dirty case: every check must fire ----
        String dirtyMemory = "# Memory\n"
                + "- Stock Research: covers all 30 companies in the IRA.\n"
                + "- Our DCF for NVDA is $999.99.\n"
                + "- Guard: never repeat the AMZN $31.04 → $147.76 revert incident.\n"
                + "- Rebuilt by the hourly nightly-job cron after each run.\n"
                + "- The weekly-thing cron runs monthly now.\n"
                + "- Methodology: growth cap 20% keeps high-flyers realistic.\n"
                + "- NotebookLM provenance: only the 18 values in the verified batch "
                + "may be presented; 1 unverified value stays out.\n"
                + "- See ~/workspace/nope/missing.json for the raw dump.\n"
                + "- NVDA closed at $100.00 in the latest cache.\n";
        Sweep dirty = load(fakeHome(dirtyMemory, "2026-09-10"));
        runChecks(dirty);
        List<Finding> df = dirty.findings;

        SelfTest.check("dirty: roster-count fires MAJOR", has(df, "ROSTER-COUNT", Sev.MAJOR));
        SelfTest.check("dirty: ours-drift fires CRITICAL", has(df, "OURS-DRIFT", Sev.CRITICAL));
        SelfTest.check("dirty: incident narrative line stays silent",
                df.stream().noneMatch(f -> f.claim().contains("31.04")));
        SelfTest.check("dirty: cron-schedule contradiction fires MAJOR",
                has(df, "CRON-SCHEDULE", Sev.MAJOR));
        SelfTest.check("dirty: methodology param fires MAJOR",
                has(df, "METHODOLOGY", Sev.MAJOR));
        SelfTest.checkEq("dirty: nlm-verified fires twice (count + unverified)",
                2L, count(df, "NLM-VERIFIED"));
        SelfTest.check("dirty: missing path fires MINOR",
                has(df, "PATH-EXISTENCE", Sev.MINOR));
        SelfTest.check("dirty: stale price cache fires MAJOR",
                has(df, "PRICE-CACHE", Sev.MAJOR));
        SelfTest.check("dirty: matching price claim stays silent",
                df.stream().noneMatch(f -> f.check().equals("PRICE-DRIFT")));
        SelfTest.check("dirty: atlas note matches — no ATLAS finding",
                df.stream().noneMatch(f -> f.check().equals("ATLAS")));
        SelfTest.check("dirty: exit would be 1 (blocking)", dirty.hasBlocking());
        SelfTest.checkOutput("dirty: finding line carries file/line/claim/backend/severity",
                df.get(0).toString(),
                new String[]{"~/MEMORY.md", "ROSTER-COUNT", "MAJOR",
                        "claim:", "backend:"},
                new String[]{});

        // ---- clean case: quiet ----
        String cleanMemory = "# Memory\n"
                + "- Stock Research: covers all 5 companies in the IRA.\n"
                + "- Our DCF for NVDA is $100.00.\n"
                + "- Rebuilt daily at 02:46 by the nightly-job cron after each run.\n"
                + "- The daily nightly-job cron precedes the weekly weekly-thing cron.\n"
                + "- The nightly-job cron file lives at cron.d/weekly/nightly-job__daily@02:46:00.md.\n"
                + "- Methodology: growth cap 15% per the doc.\n"
                + "- Buys log: ~/workspace/your_files/creator-buys/Creator Buys.xlsx — one sheet.\n"
                + "- NotebookLM provenance: only the 2 values in the verified batch "
                + "may be presented; 3 unverified values stay out.\n"
                + "- NVDA closed at $100.00 in the latest cache.\n";
        Sweep clean = load(fakeHome(cleanMemory, LocalDate.now().toString()));
        runChecks(clean);
        SelfTest.checkEq("clean: no findings", 0, clean.findings.size());
        SelfTest.check("clean: exit would be 0 (not blocking)", !clean.hasBlocking());

        SelfTest.finish();
    }
}
