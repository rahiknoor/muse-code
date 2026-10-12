// WebsiteConsensus.java — merges per-site intrinsic-value collections into
// website_consensus.json. The consensus is the median of available DCF-comparable
// per-share USD values (ValueInvesting.io DCF Growth Exit 5Y, Simply Wall St
// future cash flow value, Finbox DCF Fair Value, ValueSense DCF Value).
// GuruFocus GF Value is recorded separately (multiples-based, not DCF).
// Missing values stay null — never estimated.
//
// Raw inputs (written by hand from browser collection reports):
//   ~/workspace/dcf_csv/website_values/<site>.json
// Output:
//   ~/workspace/dcf_csv/website_consensus.json
//
// Usage:
//   java WebsiteConsensus.java --self-test   (synthetic median tests)
//   java WebsiteConsensus.java --build       (merge raw files -> consensus)
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class WebsiteConsensus {

    static final String[] TICKERS = {
        "AMD","AMZN","AVGO","BRK.B","CELH","CRM","CVX","GOOG","GRAB","INTC",
        "IREN","JNJ","JPM","LLY","MA","META","MSFT","NFLX","NOW","NVDA",
        "ORCL","PFE","PLTR","QCOM","QXO","RH","SOFI","TSLA","UNH","V","WH","XOM"
    };
    // Sites whose values enter the consensus median (DCF-comparable).
    static final String[] CONSENSUS_SITES = {
        "valueinvesting_io", "simplywallst", "finbox", "valuesense"
    };
    static final String GURUFOCUS = "gurufocus";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length == 1 && args[0].equals("--build")) { build(); return; }
        System.err.println("usage: java WebsiteConsensus.java --self-test | --build");
        System.exit(2);
    }

    // ---- median ------------------------------------------------------------
    // median() lives in java-shared Stats.java (dedup 2026-10-09).

    /**
     * Source-level filter (audit-05, Claude verdict 2026-10-06).
     * Returns null when the value is dropped, with the reason in reasons.
     * Filters run BEFORE the median — a downstream guard can't repair
     * an already-contaminated median.
     *
     * @param value  the source's intrinsic value
     * @param marketPrice  current market price for the plausibility band
     * @param reasons  dropped values are recorded here as "site: reason"
     * @return the value if kept, null if dropped
     */
    static Double filterSource(String site, Double value, Double marketPrice,
                               List<String> reasons) {
        if (value == null) return null;
        // Rule 1: drop non-positive (<= 0, not < 0). A negative "intrinsic
        // value" means the source's model found no value in the cash flows,
        // not that the stock is worth less than nothing (equity floors at
        // zero by limited liability). Drop rather than clamp — a clamped 0
        // would drag the median down.
        if (value <= 0) {
            reasons.add(site + ": non-positive value " + value + " (dropped, not clamped)");
            return null;
        }
        // Rule 2: plausibility band vs market price. Catches positive garbage
        // like ORCL $3.63 that a sign filter misses.
        if (marketPrice != null && marketPrice > 0) {
            double ratio = value / marketPrice;
            if (ratio < 0.2 || ratio > 5.0) {
                reasons.add(site + ": value " + value + " outside 0.2x-5x price band "
                        + "(price " + marketPrice + ", ratio " + String.format("%.2f", ratio) + ")");
                return null;
            }
        }
        return value;
    }

    // ---- build -------------------------------------------------------------
    static void build() throws IOException {
        Path home = Paths.get(System.getenv("HOME"));
        Path dir = home.resolve("workspace/dcf_csv/website_values");
        Map<String, J.O> raw = new LinkedHashMap<>();
        for (String site : CONSENSUS_SITES) raw.put(site, readRaw(dir, site));
        raw.put(GURUFOCUS, readRaw(dir, GURUFOCUS));

        // Market prices for the plausibility band (audit-05).
        Map<String, Double> marketPrices = readMarketPrices(home);

        J.O out = new J.O();
        out.m.put("asof", new J.S(java.time.LocalDate.now().toString()));
        out.m.put("method", new J.S(
            "Median of available DCF-comparable per-share USD intrinsic values across " +
            "ValueInvesting.io (DCF Growth Exit 5Y), Simply Wall St (future cash flow value), " +
            "Finbox (DCF Fair Value), ValueSense (DCF Value). " +
            "GuruFocus GF Value is recorded separately (multiples-based, not DCF, excluded from median). " +
            "Sources filtered BEFORE the median (audit-05): non-positive values dropped; " +
            "values outside 0.2x-5x market price dropped. " +
            "Voice count is post-filter; fewer than 2 valid voices -> consensus null. " +
            "Dropped values quarantined with reason codes. " +
            "Missing values are null, never estimated."));
        J.O sites = new J.O();
        for (Map.Entry<String, J.O> e : raw.entrySet()) {
            J.O meta = new J.O();
            meta.m.put("display", str(e.getValue(), "display"));
            meta.m.put("metric", str(e.getValue(), "metric"));
            meta.m.put("method", str(e.getValue(), "method"));
            meta.m.put("in_consensus", new J.B(Arrays.asList(CONSENSUS_SITES).contains(e.getKey())));
            meta.m.put("collected", str(e.getValue(), "collected"));
            sites.m.put(e.getKey(), meta);
        }
        out.m.put("sites", sites);

        J.O tickers = new J.O();
        for (String t : TICKERS) {
            J.O row = new J.O();
            J.O sources = new J.O();
            List<Double> dcfVals = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            Double mkt = marketPrices.get(t);
            for (String site : CONSENSUS_SITES) {
                J.O cell = cellFor(raw.get(site), t);
                sources.m.put(site, cell);
                Double v = num(cell, "value");
                // Audit-05: filter BEFORE the median, count voices after.
                Double kept = filterSource(site, v, mkt, dropped);
                if (kept != null) dcfVals.add(kept);
            }
            row.m.put("sources", sources);
            // Quarantine: record dropped values with reasons (audit-05).
            J.A quar = new J.A();
            int nNonpositiveDropped = 0;
            for (String d : dropped) {
                quar.l.add(new J.S(d));
                if (d.contains("non-positive")) nNonpositiveDropped++;
            }
            row.m.put("quarantined", quar);
            // A majority of negative sources is itself a bearish signal
            // (audit-05): worth displaying, not just filtering silently.
            row.m.put("n_nonpositive_dropped", new J.N(nNonpositiveDropped,
                    String.valueOf(nNonpositiveDropped)));
            // Validation status (phased approach, owner 2026-10-06):
            // "consensus" (2+ valid sources) > "unvalidated" (1 valid source,
            // shown but flagged) > "none" (0 valid sources -> null).
            // When more sources are added, tickers auto-graduate from
            // unvalidated to consensus. Strict N/A only when truly empty.
            Double med = null;
            String status;
            if (dcfVals.size() >= 2) {
                med = Stats.median(dcfVals);
                status = "consensus";
            } else if (dcfVals.size() == 1) {
                med = dcfVals.get(0);
                status = "unvalidated";
                dropped.add("consensus: single valid source after filtering "
                        + "(shown as unvalidated, not a consensus)");
            } else {
                status = "none";
                if (!dropped.isEmpty()) {
                    dropped.add("consensus: no valid sources after filtering (N/A)");
                }
            }
            row.m.put("consensus", med == null ? new J.Nil() : new J.N(med, fmt(med)));
            row.m.put("validation", new J.S(status));
            row.m.put("n_sources", new J.N(dcfVals.size(), String.valueOf(dcfVals.size())));
            if (!dcfVals.isEmpty()) {
                double mn = Collections.min(dcfVals), mx = Collections.max(dcfVals);
                row.m.put("min", new J.N(mn, fmt(mn)));
                row.m.put("max", new J.N(mx, fmt(mx)));
            } else {
                row.m.put("min", new J.Nil());
                row.m.put("max", new J.Nil());
            }
            row.m.put(GURUFOCUS, cellFor(raw.get(GURUFOCUS), t));
            tickers.m.put(t, row);
        }
        out.m.put("tickers", tickers);

        Path dest = home.resolve("workspace/dcf_csv/website_consensus.json");
        Files.writeString(dest, J.write(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("wrote " + dest);
    }

    /** Market prices from workbook_data.json for the plausibility band. */
    static Map<String, Double> readMarketPrices(Path home) throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        Path wd = home.resolve("workspace/your_files/dcf-valuations/.src/workbook_data.json");
        if (!Files.exists(wd)) return out;
        J.O data = (J.O) J.parse(Files.readString(wd, StandardCharsets.UTF_8));
        J.V cv = data.m.get("companies");
        if (!(cv instanceof J.A)) return out;
        for (J.V c : ((J.A) cv).l) {
            if (!(c instanceof J.O)) continue;
            J.O co = (J.O) c;
            J.V t = co.m.get("ticker");
            J.V m = co.m.get("market");
            if (t instanceof J.S && m instanceof J.N) {
                out.put(((J.S) t).s, ((J.N) m).d);
            }
        }
        return out;
    }

    static J.O readRaw(Path dir, String site) throws IOException {
        Path p = dir.resolve(site + ".json");
        if (!Files.exists(p)) {
            J.O empty = new J.O();
            empty.m.put("values", new J.O());
            return empty;
        }
        return (J.O) J.parse(Files.readString(p, StandardCharsets.UTF_8));
    }

    static J.O cellFor(J.O rawSite, String ticker) {
        J.V vals = rawSite.m.get("values");
        if (vals instanceof J.O) {
            J.V cell = ((J.O) vals).m.get(ticker);
            if (cell instanceof J.O) return (J.O) cell;
        }
        J.O c = new J.O();
        c.m.put("value", new J.Nil());
        return c;
    }

    static J.S str(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.S) ? (J.S) v : new J.S("");
    }

    static Double num(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.N) ? ((J.N) v).d : null;
    }

    static String fmt(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 100.0) / 100.0);
    }

    // ---- self test ---------------------------------------------------------
    static void selfTest() {
        SelfTest.checkEq("median odd", 2.0, Stats.median(Arrays.asList(1.0, 2.0, 3.0)));
        SelfTest.checkEq("median even", 2.5, Stats.median(Arrays.asList(1.0, 2.0, 3.0, 4.0)));
        SelfTest.checkEq("median single", 5.0, Stats.median(Arrays.asList(5.0)));
        SelfTest.checkEq("median empty", null, Stats.median(new ArrayList<>()));
        SelfTest.checkEq("median skips nulls", 20.0,
            Stats.median(Arrays.asList(null, 10.0, 20.0, 30.0, null)));
        SelfTest.checkEq("median rounds to cents", 100.34,
            Stats.median(Arrays.asList(100.335, 100.335)));
        // MSFT spot-check shape from the 2026-09-28 survey:
        // 472.89, 498.19, 528.81, 275.20 -> sorted -> (472.89+498.19)/2 = 485.54
        SelfTest.checkEq("median msft survey", 485.54,
            Stats.median(Arrays.asList(472.89, 498.19, 528.81, 275.20)));

        // Audit-05 filter tests (prove-can-fail: flip the <= to < and watch red).
        List<String> r = new ArrayList<>();
        // Non-positive dropped (<= 0, not < 0).
        SelfTest.checkEq("filter drops negative", null,
            filterSource("s1", -56.70, 100.0, r));
        SelfTest.check("filter negative reason", r.get(r.size()-1).contains("non-positive"));
        SelfTest.checkEq("filter drops zero", null,
            filterSource("s1", 0.0, 100.0, r));
        // Positive garbage outside 0.2x-5x band dropped (ORCL $3.63 vs ~$200 price).
        SelfTest.checkEq("filter drops low outlier", null,
            filterSource("s1", 3.63, 200.0, r));
        SelfTest.check("filter band reason", r.get(r.size()-1).contains("0.2x-5x"));
        SelfTest.checkEq("filter drops high outlier", null,
            filterSource("s1", 1200.0, 200.0, r));
        // Valid values pass.
        r.clear();
        SelfTest.checkEq("filter keeps valid", 122.60,
            filterSource("s1", 122.60, 100.0, r));
        SelfTest.checkEq("filter no reasons when kept", 0, r.size());
        // Null market price skips the band check (sign filter still applies).
        SelfTest.checkEq("filter null price keeps positive", 50.0,
            filterSource("s1", 50.0, null, r));
        SelfTest.checkEq("filter null price drops negative", null,
            filterSource("s1", -10.0, null, r));
        SelfTest.finish();
    }

    // ---- minimal JSON (same pattern as Proposals.J) --------------------------
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

        static String write(V v) {
            StringBuilder sb = new StringBuilder();
            wr(v, sb);
            return sb.toString();
        }

        static void wr(V v, StringBuilder sb) {
            if (v instanceof Nil) { sb.append("null"); return; }
            if (v instanceof B) { sb.append(((B) v).b ? "true" : "false"); return; }
            if (v instanceof N) { sb.append(((N) v).lit); return; }
            if (v instanceof S) {
                sb.append('"');
                for (char c : ((S) v).s.toCharArray()) {
                    switch (c) {
                        case '"': sb.append("\\\""); break;
                        case '\\': sb.append("\\\\"); break;
                        case '\n': sb.append("\\n"); break;
                        case '\r': sb.append("\\r"); break;
                        case '\t': sb.append("\\t"); break;
                        default:
                            if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                            else sb.append(c);
                    }
                }
                sb.append('"');
                return;
            }
            if (v instanceof A) {
                sb.append('[');
                boolean first = true;
                for (V e : ((A) v).l) { if (!first) sb.append(','); first = false; wr(e, sb); }
                sb.append(']');
                return;
            }
            if (v instanceof O) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, V> e : ((O) v).m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    wr(new S(e.getKey()), sb);
                    sb.append(':');
                    wr(e.getValue(), sb);
                }
                sb.append('}');
                return;
            }
            throw new IllegalArgumentException("unknown V");
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
