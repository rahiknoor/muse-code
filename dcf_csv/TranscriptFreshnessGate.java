import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transcript freshness gate for the NLM speculation derivation.
 *
 * <p>Claude's FAIL verdict on prop-20261005-005 came down to one root cause:
 * 17-month-old transcripts were fed into an October 2026 anchor and nothing
 * mechanical stopped it -- the brief even mislabeled them "5-6 months" old.
 * This gate is the structural fix: it refuses any transcribed derivation row
 * whose call_date is older than MAX_AGE_DAYS relative to the anchor asof,
 * LOUDLY, before a single number is computed or blessed.
 *
 * <p>Usage:
 * <pre>
 *   java TranscriptFreshnessGate.java --check &lt;transcribed.json&gt;
 *       [--anchor-asof YYYY-MM-DD] [--max-age-days N]
 *   java TranscriptFreshnessGate.java --self-test
 * </pre>
 *
 * <p>Defaults: anchor asof read from dcf_csv/notebooklm_values.json "asof";
 * max age {@value #MAX_AGE_DAYS} days (one quarter + one month slack: the
 * fresh Q2-2026 batch maxes ~72 days old, the failed batch was 500+).
 * Per-row "anchor_asof" overrides the global when present (v2 rows carry it).
 *
 * <p>Exit 0: every row fresh. Exit 2: stale, future-dated, missing, or
 * unparseable call dates found -- refuse. The v2 re-run must pass this gate
 * before proposal -&gt; bless. Written in plain Java so Rahik can read it.
 */
public class TranscriptFreshnessGate {

    /** Maximum transcript age in days relative to the anchor asof. */
    static final int MAX_AGE_DAYS = 120;

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    // ---------- minimal JSON parser (objects, arrays, strings, numbers, literals) ----------

    static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object val() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("truncated json");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't' || c == 'f' || c == 'n') return lit();
            return num();
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                if (s.charAt(i) != ':') throw new IllegalArgumentException("expected : at " + i);
                i++;
                m.put(k, val());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , at " + i);
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(val());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , at " + i);
            }
        }
        String str() {
            StringBuilder b = new StringBuilder();
            i++; // "
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
        }
        Object lit() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw new IllegalArgumentException("bad literal at " + i);
        }
        Object num() {
            int j = i;
            while (j < s.length() && "-+0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
            String t = s.substring(i, j);
            i = j;
            try { return Long.parseLong(t); } catch (NumberFormatException e) { return Double.parseDouble(t); }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObj(String text) {
        Object v = new P(text).val();
        if (!(v instanceof Map)) throw new IllegalArgumentException("top level is not an object");
        return (Map<String, Object>) v;
    }

    // ---------- gate ----------

    static final class Refusal {
        final String ticker, reason;
        Refusal(String ticker, String reason) { this.ticker = ticker; this.reason = reason; }
    }

    /** Check rows; returns refusals (empty = pass). */
    static List<Refusal> check(Map<String, Object> transcribed, LocalDate anchorAsOf, int maxAgeDays) {
        List<Refusal> refusals = new ArrayList<>();
        Object tickers = transcribed.get("tickers");
        if (!(tickers instanceof Map)) {
            refusals.add(new Refusal("(file)", "no \"tickers\" object found"));
            return refusals;
        }
        for (var en : ((Map<String, Object>) tickers).entrySet()) {
            String ticker = en.getKey();
            if (!(en.getValue() instanceof Map)) {
                refusals.add(new Refusal(ticker, "row is not an object"));
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) en.getValue();
            Object cd = row.get("call_date");
            if (!(cd instanceof String) || ((String) cd).isBlank()) {
                refusals.add(new Refusal(ticker, "missing call_date -- staleness cannot be verified"));
                continue;
            }
            LocalDate callDate;
            try {
                callDate = LocalDate.parse(((String) cd).trim());
            } catch (Exception e) {
                refusals.add(new Refusal(ticker, "unparseable call_date \"" + cd + "\""));
                continue;
            }
            LocalDate asof = anchorAsOf;
            Object ra = row.get("anchor_asof");
            if (ra instanceof String && !((String) ra).isBlank()) {
                try { asof = LocalDate.parse(((String) ra).trim()); }
                catch (Exception e) {
                    refusals.add(new Refusal(ticker, "unparseable anchor_asof \"" + ra + "\""));
                    continue;
                }
            }
            long age = ChronoUnit.DAYS.between(callDate, asof);
            if (age < 0) {
                refusals.add(new Refusal(ticker, "call_date " + callDate
                        + " is after anchor asof " + asof + " -- incoherent row"));
            } else if (age > maxAgeDays) {
                refusals.add(new Refusal(ticker, "call_date " + callDate + " is " + age
                        + " days older than anchor asof " + asof + " (max " + maxAgeDays + ")"));
            }
        }
        return refusals;
    }

    static LocalDate defaultAnchorAsOf() throws Exception {
        Path p = Path.of(homeDir(), "workspace", "dcf_csv", "notebooklm_values.json");
        Map<String, Object> nb = parseObj(Files.readString(p, StandardCharsets.UTF_8));
        Object asof = nb.get("asof");
        if (!(asof instanceof String)) throw new IllegalArgumentException("notebooklm_values.json has no asof");
        return LocalDate.parse((String) asof);
    }

    // ---------- self-test (with prove-can-fail) ----------

    static int selfTest() throws Exception {
        int failures = 0;
        LocalDate anchor = LocalDate.of(2026, 10, 3);

        // 1. Fresh row passes (38 days).
        Map<String, Object> fresh = Map.of("tickers", Map.of(
                "NVDA", Map.of("call_date", "2026-08-26", "value", 104.23)));
        if (!check(fresh, anchor, MAX_AGE_DAYS).isEmpty()) {
            System.out.println("FAIL: fresh row refused"); failures++;
        } else System.out.println("PASS: fresh row accepted");

        // 2. Stale row refused (520 days).
        Map<String, Object> stale = Map.of("tickers", Map.of(
                "AMZN", Map.of("call_date", "2025-05-01", "value", 183.67)));
        List<Refusal> r = check(stale, anchor, MAX_AGE_DAYS);
        if (r.size() != 1 || !r.get(0).reason.contains("520")) {
            System.out.println("FAIL: stale row not refused with age"); failures++;
        } else System.out.println("PASS: stale row refused (" + r.get(0).reason + ")");

        // 3. Missing call_date refused.
        Map<String, Object> missing = Map.of("tickers", Map.of(
                "XOM", Map.of("status", "valued", "value", 1.0)));
        if (check(missing, anchor, MAX_AGE_DAYS).isEmpty()) {
            System.out.println("FAIL: missing call_date accepted"); failures++;
        } else System.out.println("PASS: missing call_date refused");

        // 4. Boundary: exactly 120 days passes, 121 fails.
        Map<String, Object> b120 = Map.of("tickers", Map.of(
                "A", Map.of("call_date", anchor.minusDays(120).toString())));
        Map<String, Object> b121 = Map.of("tickers", Map.of(
                "A", Map.of("call_date", anchor.minusDays(121).toString())));
        boolean ok120 = check(b120, anchor, MAX_AGE_DAYS).isEmpty();
        boolean ok121 = check(b121, anchor, MAX_AGE_DAYS).isEmpty();
        if (!ok120 || ok121) {
            System.out.println("FAIL: boundary wrong (120d=" + ok120 + " 121d=" + ok121 + ")"); failures++;
        } else System.out.println("PASS: boundary 120d accepted, 121d refused");

        // 5. Future call_date refused.
        Map<String, Object> future = Map.of("tickers", Map.of(
                "B", Map.of("call_date", anchor.plusDays(10).toString())));
        if (check(future, anchor, MAX_AGE_DAYS).isEmpty()) {
            System.out.println("FAIL: future call_date accepted"); failures++;
        } else System.out.println("PASS: future call_date refused");

        // 6. Per-row anchor_asof override honored.
        Map<String, Object> over = Map.of("tickers", Map.of(
                "C", Map.of("call_date", "2025-05-01", "anchor_asof", "2025-06-01")));
        if (!check(over, anchor, MAX_AGE_DAYS).isEmpty()) {
            System.out.println("FAIL: per-row anchor_asof not honored"); failures++;
        } else System.out.println("PASS: per-row anchor_asof honored (31 days)");

        // 7. REAL negative control: the failed v1 transcription must be refused.
        Path v1 = Path.of(homeDir(), "workspace", "dcf_csv",
                "nlm_speculation_transcribed_20261005.json");
        List<Refusal> v1r = check(parseObj(Files.readString(v1, StandardCharsets.UTF_8)),
                LocalDate.of(2026, 10, 3), MAX_AGE_DAYS);
        if (v1r.isEmpty()) {
            System.out.println("FAIL: v1 failed-batch file NOT refused -- gate is vacuous"); failures++;
        } else System.out.println("PASS: v1 failed batch refused (" + v1r.size() + " stale rows)");

        System.out.println(failures == 0 ? "SELF-TEST GREEN (7/7)" : "SELF-TEST RED (" + failures + " failures)");
        return failures == 0 ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) System.exit(selfTest());

        String file = null, anchorStr = null;
        int maxAge = MAX_AGE_DAYS;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--check": file = args[++i]; break;
                case "--anchor-asof": anchorStr = args[++i]; break;
                case "--max-age-days": maxAge = Integer.parseInt(args[++i]); break;
                default:
                    System.out.println("usage: java TranscriptFreshnessGate.java --check <file> "
                            + "[--anchor-asof YYYY-MM-DD] [--max-age-days N] | --self-test");
                    System.exit(1);
            }
        }
        if (file == null) { System.out.println("missing --check <file>"); System.exit(1); }
        LocalDate anchor = anchorStr != null ? LocalDate.parse(anchorStr) : defaultAnchorAsOf();
        Map<String, Object> transcribed =
                parseObj(Files.readString(Path.of(file), StandardCharsets.UTF_8));
        List<Refusal> refusals = check(transcribed, anchor, maxAge);
        if (refusals.isEmpty()) {
            int n = ((Map<?, ?>) transcribed.get("tickers")).size();
            System.out.println("GATE PASS: all " + n + " rows fresh vs anchor asof "
                    + anchor + " (max age " + maxAge + "d).");
            System.exit(0);
        }
        System.out.println("GATE REFUSE: " + refusals.size()
                + " row(s) failed freshness vs anchor asof " + anchor
                + " (max " + maxAge + "d). No value may be computed or blessed from this file.");
        for (Refusal rf : refusals) System.out.println("  - " + rf.ticker + ": " + rf.reason);
        System.exit(2);
    }
}
