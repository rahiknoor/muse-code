import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-lookup end-to-end provenance trail (idea #3, approved 2026-09-29).
 *
 * <p>Each blessed value carries a structured PROVENANCE trail — executor task
 * id, auditor task id, note URL + title, timestamps — so any number is
 * traceable in ONE lookup instead of digging through run logs. Proposals filed
 * via {@code Proposals.java file --provenance '<json>'} have it validated as
 * a JSON object and stored on the proposal; {@code Proposals#blessOne} copies
 * it onto the blessed entry as {@code provenance} together with
 * {@code proposal_id}.
 *
 * <p>Supported provenance JSON shape (all keys optional; whichever exist are
 * shown, in this order):
 * <pre>
 *   {"executor_task_id":"browser-task:...","auditor_task_id":"browser-task:...",
 *    "note_url":"https://notebook.google.com/notebook/<id>",
 *    "note_title":"[T] DCF forward tilt",
 *    "executor_ts":"...","auditor_ts":"...","run_date":"2026-09-29"}
 * </pre>
 *
 * <p>Backward compatible: blessed entries written before this feature have no
 * provenance and print "no provenance recorded" instead of erroring.
 *
 * <p>Usage:
 * <pre>
 *   java Provenance.java --show --column C --ticker T
 *   java Provenance.java --self-test
 * </pre>
 */
public class Provenance {

    // ------------------------------------------------------------------
    // Minimal JSON parser (parse-only copy of the embedded parser in
    // Proposals.java; self-contained so this file runs standalone).
    // ------------------------------------------------------------------
    static final class J {
        abstract static class V { }
        static final class O extends V { final LinkedHashMap<String, V> m = new LinkedHashMap<>(); }
        static final class A extends V { final ArrayList<V> l = new ArrayList<>(); }
        static final class S extends V { final String s; S(String s) { this.s = s; } }
        static final class N extends V { final double d; final String lit; N(double d, String lit) { this.d = d; this.lit = lit; } }
        static final class B extends V { final boolean b; B(boolean b) { this.b = b; } }
        static final class Nil extends V { }

        static V parse(String t) {
            P p = new P(t);
            V v = p.val();
            p.ws();
            if (p.i != t.length()) {
                throw new IllegalArgumentException("trailing characters at " + p.i);
            }
            return v;
        }

        static final class P {
            final String t; int i;
            P(String t) { this.t = t; }
            void ws() {
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
                }
            }
            V val() {
                ws();
                if (i >= t.length()) throw new IllegalArgumentException("unexpected end of input");
                char c = t.charAt(i);
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return new S(str());
                if (c == 't') { exp("true"); return new B(true); }
                if (c == 'f') { exp("false"); return new B(false); }
                if (c == 'n') { exp("null"); return new Nil(); }
                return num();
            }
            void exp(String s) {
                if (t.startsWith(s, i)) i += s.length();
                else throw new IllegalArgumentException("expected '" + s + "' at " + i);
            }
            O obj() {
                O o = new O(); i++; ws();
                if (i < t.length() && t.charAt(i) == '}') { i++; return o; }
                while (true) {
                    ws();
                    if (i >= t.length() || t.charAt(i) != '"') throw new IllegalArgumentException("expected key at " + i);
                    String k = str(); ws();
                    if (i >= t.length() || t.charAt(i) != ':') throw new IllegalArgumentException("expected ':' at " + i);
                    i++;
                    o.m.put(k, val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == '}') { i++; break; }
                    throw new IllegalArgumentException("expected ',' or '}' at " + i);
                }
                return o;
            }
            A arr() {
                A a = new A(); i++; ws();
                if (i < t.length() && t.charAt(i) == ']') { i++; return a; }
                while (true) {
                    a.l.add(val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == ']') { i++; break; }
                    throw new IllegalArgumentException("expected ',' or ']' at " + i);
                }
                return a;
            }
            String str() {
                StringBuilder sb = new StringBuilder(); i++;
                while (true) {
                    if (i >= t.length()) throw new IllegalArgumentException("unterminated string");
                    char c = t.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        if (i >= t.length()) throw new IllegalArgumentException("bad escape");
                        char e = t.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                if (i + 4 > t.length()) throw new IllegalArgumentException("bad \\u escape");
                                int cp = Integer.parseInt(t.substring(i, i + 4), 16); i += 4;
                                sb.append((char) cp);
                                break;
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            V num() {
                int s = i;
                if (i < t.length() && (t.charAt(i) == '-' || t.charAt(i) == '+')) i++;
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                    else break;
                }
                String lit = t.substring(s, i);
                try {
                    return new N(Double.parseDouble(lit), lit);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad number: " + lit);
                }
            }
        }

        static Double num(V v) { return v instanceof N ? ((N) v).d : null; }
        static String str(V v) { return v instanceof S ? ((S) v).s : null; }
        static O obj(V v) { return v instanceof O ? (O) v : null; }
        static String numLit(N n) {
            try {
                if (Double.parseDouble(n.lit) == n.d) return n.lit;
            } catch (Exception ignored) { }
            if (n.d == Math.rint(n.d) && Math.abs(n.d) < 1e15) return Long.toString((long) n.d) + ".0";
            return Double.toString(n.d);
        }
    }

    // ------------------------------------------------------------------
    // Trail rendering. Known keys print first, in this fixed order; any
    // extra keys follow in file order. Keys that are absent are skipped.
    // ------------------------------------------------------------------
    static final List<String> KNOWN_KEYS = List.of(
            "executor_task_id", "auditor_task_id", "note_url", "note_title",
            "executor_ts", "auditor_ts", "run_date");

    static String scalar(J.V v) {
        if (v == null || v instanceof J.Nil) return "—";
        if (v instanceof J.N) return J.numLit((J.N) v);
        String s = J.str(v);
        if (s != null) return s;
        if (v instanceof J.B) return Boolean.toString(((J.B) v).b);
        return "—";
    }

    /**
     * One-lookup trail for a blessed column/ticker entry. Returns the lines
     * (no trailing newline) so the self-test can assert on them without
     * touching the filesystem.
     */
    static List<String> trail(String column, String ticker, J.O blessed) {
        List<String> out = new ArrayList<>();
        out.add(column + "/" + ticker);
        J.O entry = null;
        J.O cols = J.obj(blessed.m.get("columns"));
        J.O col = cols == null ? null : J.obj(cols.m.get(column));
        J.O tickers = col == null ? null : J.obj(col.m.get("tickers"));
        if (tickers != null) entry = J.obj(tickers.m.get(ticker));
        if (entry == null) {
            out.add("  no blessed entry for " + column + "/" + ticker);
            return out;
        }
        out.add("  value     : " + scalar(entry.m.get("value")));
        out.add("  blessed_at: " + scalar(entry.m.get("blessed_at")));
        out.add("  blessed_by: " + scalar(entry.m.get("blessed_by")));
        String pid = J.str(entry.m.get("proposal_id"));
        if (pid != null) out.add("  proposal_id: " + pid);
        J.O prov = J.obj(entry.m.get("provenance"));
        if (prov == null) {
            out.add("  no provenance recorded");
            return out;
        }
        out.add("  provenance trail:");
        for (String k : KNOWN_KEYS) {
            J.V v = prov.m.get(k);
            if (v != null && !(v instanceof J.Nil)) out.add("    " + k + ": " + scalar(v));
        }
        for (Map.Entry<String, J.V> e : prov.m.entrySet()) {
            if (KNOWN_KEYS.contains(e.getKey())) continue;
            J.V v = e.getValue();
            if (v != null && !(v instanceof J.Nil)) out.add("    " + e.getKey() + ": " + scalar(v));
        }
        return out;
    }

    static Path blessedPath() {
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) throw new IllegalStateException("$HOME is not set");
        return Paths.get(home, "workspace", "dcf_csv", "blessed.json");
    }

    static void cmdShow(String column, String ticker) throws IOException {
        J.O blessed = (J.O) J.parse(
                Files.readString(blessedPath(), StandardCharsets.UTF_8));
        for (String line : trail(column, ticker.toUpperCase(), blessed)) {
            System.out.println(line);
        }
    }

    // ------------------------------------------------------------------
    // Self-test.
    // ------------------------------------------------------------------
    static final String BLESSED_FULL = """
        {"columns": {"forward": {"store": "workbook_data.json",
         "tickers": {"NVDA": {"value": 145.2, "blessed_at": "2026-09-29T01:50:00-04:00",
          "blessed_by": "muse-audit", "proposal_id": "prop-20260929-001",
          "provenance": {"executor_task_id": "browser-task:exec-111",
           "auditor_task_id": "browser-task:audit-222",
           "note_url": "https://notebook.google.com/notebook/abc123",
           "note_title": "[NVDA] DCF forward tilt",
           "executor_ts": "2026-09-29T01:20:00-04:00",
           "auditor_ts": "2026-09-29T01:40:00-04:00",
           "run_date": "2026-09-29"}}}}}}
        """;

    static final String BLESSED_PARTIAL = """
        {"columns": {"forward": {"store": "workbook_data.json",
         "tickers": {"META": {"value": 88.0, "blessed_at": "2026-09-29T01:50:00-04:00",
          "blessed_by": "muse-audit", "proposal_id": "prop-20260929-002",
          "provenance": {"executor_task_id": "browser-task:exec-333",
           "note_title": "[META] DCF forward tilt"}}}}}}
        """;

    static final String BLESSED_LEGACY = """
        {"columns": {"ours": {"store": "workbook_data.json",
         "tickers": {"NVDA": {"value": 120.5, "blessed_at": "2026-09-27T10:00:00-04:00",
          "blessed_by": "muse-audit"}}}}}
        """;

    static String join(List<String> lines) {
        return String.join("\n", lines) + "\n";
    }

    static void selfTest() {
        // 1. Full provenance: every documented field renders.
        String full = join(trail("forward", "NVDA", (J.O) J.parse(BLESSED_FULL)));
        SelfTest.checkOutput("full provenance renders", full,
                new String[]{
                    "forward/NVDA", "value     : 145.2",
                    "blessed_at: 2026-09-29T01:50:00-04:00",
                    "blessed_by: muse-audit",
                    "proposal_id: prop-20260929-001",
                    "provenance trail:",
                    "executor_task_id: browser-task:exec-111",
                    "auditor_task_id: browser-task:audit-222",
                    "note_url: https://notebook.google.com/notebook/abc123",
                    "note_title: [NVDA] DCF forward tilt",
                    "executor_ts: 2026-09-29T01:20:00-04:00",
                    "auditor_ts: 2026-09-29T01:40:00-04:00",
                    "run_date: 2026-09-29"},
                new String[]{"no provenance recorded"});

        // 2. Partial provenance: present keys shown, absent keys skipped.
        String partial = join(trail("forward", "META", (J.O) J.parse(BLESSED_PARTIAL)));
        SelfTest.checkOutput("partial provenance shows only present keys", partial,
                new String[]{
                    "executor_task_id: browser-task:exec-333",
                    "note_title: [META] DCF forward tilt",
                    "proposal_id: prop-20260929-002"},
                new String[]{"auditor_task_id", "note_url", "no provenance recorded"});

        // 3. Legacy entry (no provenance): backward compatible, no error.
        String legacy = join(trail("ours", "NVDA", (J.O) J.parse(BLESSED_LEGACY)));
        SelfTest.checkOutput("legacy entry without provenance", legacy,
                new String[]{"ours/NVDA", "value     : 120.5",
                    "blessed_at: 2026-09-27T10:00:00-04:00",
                    "no provenance recorded"},
                new String[]{"proposal_id", "provenance trail:"});

        // 4. Unknown column/ticker: reported, not an exception.
        String missing = join(trail("forward", "ZZZZ", (J.O) J.parse(BLESSED_FULL)));
        SelfTest.checkContains("unknown ticker reported", missing, "no blessed entry for forward/ZZZZ");
        String missingCol = join(trail("nope", "NVDA", (J.O) J.parse(BLESSED_FULL)));
        SelfTest.checkContains("unknown column reported", missingCol, "no blessed entry for nope/NVDA");

        // 5. Extra unknown provenance keys are carried, not dropped.
        String extra = """
            {"columns": {"forward": {"tickers": {"NVDA": {"value": 1.0,
             "provenance": {"run_date": "2026-09-29", "model": "x"}}}}}}
            """;
        String extraOut = join(trail("forward", "NVDA", (J.O) J.parse(extra)));
        SelfTest.checkOutput("extra provenance keys carried", extraOut,
                new String[]{"run_date: 2026-09-29", "model: x"},
                new String[]{"no provenance recorded"});

        SelfTest.finish();
    }

    static void usage() {
        System.out.println("Usage:");
        System.out.println("  java Provenance.java --show --column C --ticker T");
        System.out.println("  java Provenance.java --self-test");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        if (args[0].equals("--self-test")) { selfTest(); return; }
        if (args[0].equals("--show")) {
            String col = null, ticker = null;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--column") && i + 1 < args.length) col = args[++i];
                else if (args[i].equals("--ticker") && i + 1 < args.length) ticker = args[++i];
                else { usage(); System.exit(2); }
            }
            if (col == null || ticker == null) { usage(); System.exit(2); }
            cmdShow(col, ticker);
            return;
        }
        usage();
        System.exit(2);
    }
}
