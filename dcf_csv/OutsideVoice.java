// OutsideVoice.java — builds the "Speculation price" backend.
//
// "Speculation price" (Rahik's name, 2026-09-29): the outside voices are the
// speculation, vs the fundamentals-based DCFs. For each ticker, the median of
// the available outside-source values:
//   (1) latest creator value  (creator_values.json "latest" -> value)
//   (2) analyst price target  (analyst_targets.json -> target)
//   (3) website DCF consensus (website_consensus.json tickers -> consensus)
// Missing sources are excluded from the median; null when a ticker has no
// outside value at all (e.g. QXO). Median chosen for robustness against
// outlier creator values, consistent with the website-consensus method.
//
// Display-only: it never enters the "Average of available numeric sources"
// (it would multi-count the same voices) and it is not a blessed value —
// not covered by the proposal/audit write guard.
//
// Usage:
//   java --class-path ~/workspace/java-shared/classes OutsideVoice.java --self-test
//   java --class-path ~/workspace/java-shared/classes OutsideVoice.java --build
//
// Written in plain Java so Rahik can read it himself.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

public class OutsideVoice {

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isEmpty()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static String read(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length == 1 && args[0].equals("--build")) { build(); return; }
        System.err.println("usage: java OutsideVoice.java --self-test | --build");
        System.exit(2);
    }

    /** Median of the available values; null when there are none. Rounded to cents. */
    // median() lives in java-shared Stats.java (dedup 2026-10-09).

    static Double num(J.V v) {
        return (v instanceof J.N) ? ((J.N) v).d : null;
    }

    static String str(J.V v) {
        return (v instanceof J.S) ? ((J.S) v).s : null;
    }

    static void build() throws Exception {
        String root = home() + "/workspace";
        J.O wl = (J.O) J.parse(read(root + "/stock_watch/watchlist.json"));
        List<String> tickers = new ArrayList<>();
        for (J.V v : ((J.A) wl.m.get("tickers")).l) tickers.add(((J.S) v).s);

        J.O crAll = (J.O) J.parse(read(root + "/dcf_csv/creator_values.json"));
        J.O crLatest = (J.O) crAll.m.get("latest");
        J.O an = (J.O) J.parse(read(root + "/dcf_csv/analyst_targets.json"));
        J.O wcAll = (J.O) J.parse(read(root + "/dcf_csv/website_consensus.json"));
        J.O wcTickers = (J.O) wcAll.m.get("tickers");

        String today = LocalDate.now().toString();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(" \"_derived\": \"derived from creator_values.json, analyst_targets.json, website_consensus.json via OutsideVoice --build \\u2014 do not edit, do not read as input\",\n");
        sb.append(" \"asof\": \"").append(today).append("\",\n");
        sb.append(" \"note\": \"Speculation price per ticker (Rahik's name for the outside voices — the speculation, vs the fundamentals-based DCFs). Median of the available outside-source values; missing sources are excluded; null when a ticker has none. Display-only: never enters the average of available numeric sources and is not a blessed value.\",\n");
        sb.append(" \"definition\": \"For each ticker, the median of up to three outside-source values: (1) the latest creator value (creator_values.json 'latest'), (2) the analyst price target (analyst_targets.json 'target'), (3) the website DCF consensus (website_consensus.json 'consensus', itself a median of ValueInvesting.io / Simply Wall St / Finbox / ValueSense DCF values). Median chosen for robustness against outlier creator values, consistent with the website-consensus method.\",\n");
        sb.append(" \"tickers\": {\n");
        for (int i = 0; i < tickers.size(); i++) {
            String t = tickers.get(i);
            List<Double> vals = new ArrayList<>();
            List<String> srcs = new ArrayList<>();
            // (1) creator
            Double creator = null;
            if (crLatest != null && crLatest.m.get(t) instanceof J.O ce) {
                creator = num(ce.m.get("value"));
            }
            if (creator != null) { vals.add(creator); srcs.add("creator"); }
            // (2) analyst target
            Double analyst = null;
            if (an.m.get(t) instanceof J.O ae) {
                analyst = num(ae.m.get("target"));
            }
            if (analyst != null) { vals.add(analyst); srcs.add("analyst"); }
            // (3) website consensus
            Double consensus = null;
            if (wcTickers != null && wcTickers.m.get(t) instanceof J.O we) {
                consensus = num(we.m.get("consensus"));
            }
            if (consensus != null) { vals.add(consensus); srcs.add("consensus"); }
            Double m = Stats.median(vals);
            sb.append("  \"").append(t).append("\": ");
            if (m == null) {
                sb.append("null");
            } else {
                sb.append("{\"value\": ").append(String.format(Locale.US, "%.2f", m))
                  .append(", \"sources\": [");
                for (int s = 0; s < srcs.size(); s++) {
                    if (s > 0) sb.append(", ");
                    sb.append("\"").append(srcs.get(s)).append("\"");
                }
                sb.append("], \"asof\": \"").append(today).append("\"}");
            }
            sb.append(i + 1 < tickers.size() ? ",\n" : "\n");
        }
        sb.append(" }\n}\n");
        Path out = Paths.get(root + "/dcf_csv/outside_voice.json");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("wrote " + out + " (" + tickers.size() + " tickers)");
    }

    static void selfTest() throws Exception {
        // Synthetic median checks.
        SelfTest.checkEq("median odd", 2.0, Stats.median(Arrays.asList(1.0, 2.0, 3.0)));
        SelfTest.checkEq("median even", 2.5, Stats.median(Arrays.asList(1.0, 2.0, 3.0, 4.0)));
        SelfTest.checkEq("median single", 5.0, Stats.median(Arrays.asList(5.0)));
        SelfTest.checkEq("median empty", null, Stats.median(new ArrayList<>()));
        SelfTest.checkEq("median skips nulls", 20.0,
                Stats.median(Arrays.asList(null, 10.0, 20.0, 30.0)));
        SelfTest.checkEq("median rounds to cents", 10.13,
                Stats.median(Arrays.asList(10.126, 10.124)));
        // (-121.0 + 81.47)/2 = -19.765 decimal, but the double is
        // -19.7649999999999986 — strictly below halfway, so nearest-cent is
        // -19.76 and Math.round (half-up) correctly yields that.
        SelfTest.checkEq("median negative ok", -19.76,
                Stats.median(Arrays.asList(-121.0, 81.47)));
        // Structural checks on the built backend.
        String root = home() + "/workspace";
        Path p = Paths.get(root + "/dcf_csv/outside_voice.json");
        SelfTest.check("backend file exists", Files.exists(p));
        J.O doc = (J.O) J.parse(read(p.toString()));
        J.O tickers = (J.O) doc.m.get("tickers");
        SelfTest.checkEq("32 tickers", 32, tickers.m.size());
        SelfTest.check("definition recorded",
                str(doc.m.get("definition")) != null
                        && str(doc.m.get("definition")).contains("median"));
        // QXO now has two outside voices (2026-10-07: website consensus 57.17
        // SWS cash flow value + analyst target 27.21 MarketBeat 18 analysts;
        // median of the two = 42.19).
        SelfTest.checkEq("QXO median(27.21, 57.17)", 42.19,
                num(((J.O) tickers.m.get("QXO")).m.get("value")));
        // Spot-checks against hand-computed medians (inputs as of 2026-10-07).
        // GOOG: creator 338 (live), analyst 422.16, consensus 292.08.
        SelfTest.checkEq("GOOG median(338, 422.16, 292.08)", 338.0,
                num(((J.O) tickers.m.get("GOOG")).m.get("value")));
        // META: creator 680 (Couch Investor 2026-10-04, reblessed), analyst
        // 785.9, consensus 618.48 -> median 680.0.
        SelfTest.checkEq("META median(680, 785.9, 618.48)", 680.0,
                num(((J.O) tickers.m.get("META")).m.get("value")));
        // NVDA (inputs as of 2026-10-05): creator 332, analyst 324.14, web 170.97.
        SelfTest.checkEq("NVDA median(332, 324.14, 170.97)", 324.14,
                num(((J.O) tickers.m.get("NVDA")).m.get("value")));
        // AVGO (inputs as of 2026-10-05): creator 523 is back, analyst 519.21,
        // web 286.37 -> median 519.21.
        SelfTest.checkEq("AVGO median(523, 519.21, 286.37)", 519.21,
                num(((J.O) tickers.m.get("AVGO")).m.get("value")));
        J.O goog = (J.O) tickers.m.get("GOOG");
        J.A gsrcs = (J.A) goog.m.get("sources");
        SelfTest.checkEq("GOOG three sources", 3, gsrcs.l.size());
        SelfTest.finish();
    }

    // ---- minimal JSON (same pattern as ComparisonTable.J) ----
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
            if (p.i != t.length()) throw new IllegalArgumentException("trailing characters at " + p.i);
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
                                sb.append((char) Integer.parseInt(t.substring(i, i + 4), 16)); i += 4;
                                break;
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else sb.append(c);
                }
                return sb.toString();
            }
            V num() {
                int s = i;
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                    else break;
                }
                String lit = t.substring(s, i);
                try { return new N(Double.parseDouble(lit), lit); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("bad number at " + s); }
            }
        }
    }
}
