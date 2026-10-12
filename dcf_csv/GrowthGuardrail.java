import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 build 5 — growth-input guardrail (warning/proposal only, never changes a value).
 *
 * <p>Flags any set growth assumption {@code g} above
 * {@code min(0.7 x five-year filing-history FCF growth, 8%)}, with the history
 * growth capped at 15%. The history comes from the company's own 10-K/20-F
 * XBRL (companyfacts cache), earliest-filed per fiscal year (no-peek).
 *
 * <ul>
 *   <li>FCFF-kind: history = OCF less capex (the model's own FCF definition).</li>
 *   <li>REV-kind: history = revenue (g is a revenue growth assumption).</li>
 *   <li>FCFE-kind: history = net income (FCF is not meaningful for banks;
 *       JPM's DDM dividend growth is checked against NI growth).</li>
 * </ul>
 *
 * <p>When the history is unusable (missing years, non-positive base), the
 * bound falls back to {@code 8% x 0.7 = 5.6%} — an asymmetric haircut that
 * penalizes optimism when evidence is absent.
 *
 * <p>Reads: workbook_data.json, filing_watch_state.json, companyfacts cache.
 * Writes: growth_guardrail.json (diagnostics only).
 */
public class GrowthGuardrail {

    static final double HISTORY_CAP = 0.15;
    static final double HAIRCUT = 0.7;
    static final double CEILING = 0.08;
    static final double FALLBACK_BOUND = CEILING * HAIRCUT; // 5.6%

    static Path home() {
        return Path.of(System.getenv("HOME"));
    }

    static String readFile(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // Tag fallbacks mirror DcfBacktest (OCF_TAGS / CAPEX_TAGS / REV_TAGS).
    static final String[][] OCF = {
        {"us-gaap", "NetCashProvidedByUsedInOperatingActivities"},
        {"ifrs-full", "CashFlowsFromUsedInOperatingActivities"}};
    static final String[][] CAPEX = {
        {"us-gaap", "PaymentsToAcquirePropertyPlantAndEquipment"},
        {"us-gaap", "PaymentsToAcquireProductiveAssets"},
        {"us-gaap", "PaymentsToAcquireOtherPropertyPlantAndEquipment"},
        {"ifrs-full", "PurchaseOfPropertyPlantAndEquipment"},
        {"ifrs-full", "PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities"}};
    static final String[][] REV = {
        {"us-gaap", "RevenueFromContractWithCustomerExcludingAssessedTax"},
        {"us-gaap", "Revenues"},
        {"us-gaap", "SalesRevenueNet"},
        {"ifrs-full", "Revenue"}};
    static final String[][] NI = {
        {"us-gaap", "NetIncomeLoss"},
        {"us-gaap", "NetIncome"},
        {"us-gaap", "ProfitLoss"}};

    /** Earliest-filed FY 10-K/20-F value for one concept set, or null. */
    static Double fyValue(List<XbrlFacts.Fact> facts, String[][] tags, int fy) {
        for (String[] tag : tags) {
            List<XbrlFacts.Fact> rows = XbrlFacts.forConcept(facts, tag[0], tag[1]);
            String firstFiled = null;
            List<XbrlFacts.Fact> matches = new ArrayList<>();
            for (XbrlFacts.Fact r : rows) {
                if (!"USD".equals(r.unit())) continue;
                int rFy;
                try { rFy = Integer.parseInt(r.fy()); } catch (Exception e) { rFy = -1; }
                String form = r.form() == null ? "" : r.form();
                String filed = r.filed() == null ? "" : r.filed();
                if (rFy == fy && "FY".equals(r.fp()) && !filed.isEmpty()
                        && (form.equals("10-K") || form.equals("10-K/A")
                            || form.equals("20-F") || form.equals("20-F/A"))) {
                    matches.add(r);
                    if (firstFiled == null || filed.compareTo(firstFiled) < 0)
                        firstFiled = filed;
                }
            }
            Double best = null;
            String bestEnd = null;
            for (XbrlFacts.Fact r : matches) {
                if (!r.filed().equals(firstFiled)) continue;
                String end = r.end() == null ? "" : r.end();
                if (best == null || end.compareTo(bestEnd) > 0
                        || (end.equals(bestEnd) && r.val() > best)) {
                    best = r.val();
                    bestEnd = end;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Five-year CAGR from six annual points; null when unusable. */
    static Double fiveYearCagr(double[] v) {
        if (v.length != 6) return null;
        for (double x : v) {
            if (!(x > 0)) return null; // non-positive breaks CAGR
        }
        return Math.pow(v[5] / v[0], 1.0 / 5) - 1;
    }

    /** Guardrail bound for a (possibly null) history CAGR. */
    static double bound(Double histCagr) {
        if (histCagr == null) return FALLBACK_BOUND;
        double capped = Math.min(histCagr, HISTORY_CAP);
        return Math.min(capped * HAIRCUT, CEILING);
    }

    static void runReport() throws Exception {
        Path dcfDir = home().resolve("workspace/dcf_csv");
        DcfModel.Json.Obj wb = (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(
                home().resolve("workspace/your_files/dcf-valuations/.src/workbook_data.json")));
        DcfModel.Json.Obj watch = (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(
                home().resolve("workspace/sec_filings/filing_watch_state.json")));
        DcfModel.Json.Obj watchCos = watch.getObj("companies");
        Map<String, String> cikByTicker = new LinkedHashMap<>();
        for (String k : watchCos.map.keySet()) {
            DcfModel.Json.Obj co = watchCos.getObj(k);
            String cik = co.getStr("cik").replaceAll("\\D", "");
            while (cik.length() < 10) cik = "0" + cik;
            String t = co.optStr("ticker", k).toUpperCase();
            if (t.equals("BRKB")) t = "BRK.B";
            cikByTicker.put(t, cik);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int flagged = 0;
        for (DcfModel.Json.Val cv : wb.getList("companies")) {
            DcfModel.Json.Obj c = (DcfModel.Json.Obj) cv;
            String ticker = c.getStr("ticker");
            DcfModel.Json.Obj a = c.getObj("assumptions");
            String kind = a.getStr("kind");
            double g = a.getNum("g");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", ticker);
            row.put("kind", kind);
            row.put("g", Math.round(g * 10000.0) / 10000.0);

            Double histCagr = null;
            String histNote;
            String cik = cikByTicker.get(ticker);
            if (cik == null) {
                histNote = "no CIK";
            } else {
                Path cf = dcfDir.resolve("backtest/companyfacts/" + cik + ".json");
                if (!Files.exists(cf)) {
                    histNote = "companyfacts not cached";
                } else {
                    try {
                        List<XbrlFacts.Fact> facts = XbrlFacts.load(cf);
                        // Six latest FYs present in the cache.
                        java.util.TreeSet<Integer> fys = new java.util.TreeSet<>();
                        for (XbrlFacts.Fact r : facts) {
                            try {
                                int y = Integer.parseInt(r.fy());
                                if ("FY".equals(r.fp()) && y >= 1990) fys.add(y);
                            } catch (Exception ignored) { }
                        }
                        List<Integer> last6 = new ArrayList<>(fys);
                        if (last6.size() < 6) {
                            histNote = "fewer than 6 FYs in cache";
                        } else {
                            last6 = last6.subList(last6.size() - 6, last6.size());
                            double[] v = new double[6];
                            boolean ok = true;
                            for (int i = 0; i < 6; i++) {
                                int fy = last6.get(i);
                                Double val;
                                if (kind.equals("REV")) {
                                    val = fyValue(facts, REV, fy);
                                } else if (kind.equals("FCFE")) {
                                    val = fyValue(facts, NI, fy);
                                } else {
                                    Double ocf = fyValue(facts, OCF, fy);
                                    Double cx = fyValue(facts, CAPEX, fy);
                                    val = (ocf != null && cx != null) ? ocf - cx : null;
                                }
                                if (val == null) { ok = false; break; }
                                v[i] = val / 1e6;
                            }
                            if (!ok) {
                                histNote = "history gaps in FY" + last6.get(0) + "-FY"
                                        + last6.get(5);
                            } else {
                                histCagr = fiveYearCagr(v);
                                histNote = histCagr == null
                                        ? "non-positive history values (CAGR undefined)"
                                        : "FY" + last6.get(0) + "-FY" + last6.get(5);
                            }
                        }
                    } catch (Exception e) {
                        histNote = "error: " + e.getMessage();
                    }
                }
            }
            double b = bound(histCagr);
            boolean flag = g > b;
            if (flag) flagged++;
            row.put("history_cagr", histCagr == null ? null
                    : Math.round(histCagr * 10000.0) / 10000.0);
            row.put("history_note", histNote);
            row.put("bound", Math.round(b * 10000.0) / 10000.0);
            row.put("flag", flag);
            rows.add(row);
        }

        DcfModel.Json.Obj out = new DcfModel.Json.Obj();
        out.put("asof", new DcfModel.Json.Str(java.time.LocalDate.now().toString()));
        out.put("methodology", new DcfModel.Json.Str(
                "Flag g > min(0.7 x 5-yr filing-history growth, 8%); history capped at 15%; "
                + "unusable history -> fallback bound 5.6% (asymmetric haircut). "
                + "Warning/proposal only; never changes a value."));
        out.put("flagged_count", new DcfModel.Json.Num((long) flagged));
        DcfModel.Json.Arr arr = new DcfModel.Json.Arr();
        for (Map<String, Object> r : rows) {
            DcfModel.Json.Obj o = new DcfModel.Json.Obj();
            for (Map.Entry<String, Object> e : r.entrySet()) {
                Object v = e.getValue();
                if (v == null) o.put(e.getKey(), new DcfModel.Json.Null());
                else if (v instanceof String) o.put(e.getKey(), new DcfModel.Json.Str((String) v));
                else if (v instanceof Boolean) o.put(e.getKey(), new DcfModel.Json.Bool((Boolean) v));
                else o.put(e.getKey(), new DcfModel.Json.Num(((Number) v).doubleValue()));
            }
            arr.items.add(o);
        }
        out.put("rows", arr);
        Files.writeString(dcfDir.resolve("growth_guardrail.json"),
                DcfModel.Json.dumps(out), StandardCharsets.UTF_8);

        System.out.println("# Growth-input guardrail");
        System.out.println();
        System.out.println("Flagged: " + flagged + " of " + rows.size()
                + " (g above min(0.7 x 5-yr history, 8%)).");
        System.out.println();
        System.out.println("| Ticker | g | 5-yr hist | Bound | Flag | Note |");
        System.out.println("|---|---|---|---|---|---|");
        for (Map<String, Object> r : rows) {
            if (!Boolean.TRUE.equals(r.get("flag"))) continue;
            System.out.println("| " + r.get("ticker")
                    + " | " + String.format("%.1f%%", ((Number) r.get("g")).doubleValue() * 100)
                    + " | " + (r.get("history_cagr") == null ? "n/a"
                        : String.format("%.1f%%", ((Number) r.get("history_cagr")).doubleValue() * 100))
                    + " | " + String.format("%.1f%%", ((Number) r.get("bound")).doubleValue() * 100)
                    + " | **FLAG** | " + r.get("history_note") + " |");
        }
        System.out.println();
        System.out.println("Full diagnostics: workspace/dcf_csv/growth_guardrail.json");
    }

    static void selfTest() {
        // Bound math.
        SelfTest.check("hist 20% -> capped 15% -> bound 8%",
                Math.abs(bound(0.20) - 0.08) < 1e-12);
        SelfTest.check("hist 5% -> bound 3.5%",
                Math.abs(bound(0.05) - 0.035) < 1e-12);
        SelfTest.check("null history -> fallback 5.6%",
                Math.abs(bound(null) - 0.056) < 1e-12);
        // Flag logic.
        SelfTest.check("g=10% > bound 8% flags", 0.10 > bound(0.20));
        SelfTest.check("g=3% < bound 3.5% ok", !(0.03 > bound(0.05)));
        // CAGR.
        double[] v = {100, 110, 121, 133.1, 146.41, 161.051}; // 10%/yr x5
        Double cagr = fiveYearCagr(v);
        SelfTest.check("5-yr CAGR of 10%/yr", cagr != null && Math.abs(cagr - 0.10) < 1e-9);
        // Non-positive base -> null (fallback).
        SelfTest.check("negative start -> null",
                fiveYearCagr(new double[]{-100, 110, 121, 133.1, 146.41, 161.051}) == null);
        SelfTest.check("zero middle -> null",
                fiveYearCagr(new double[]{100, 110, 0, 133.1, 146.41, 161.051}) == null);
        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        if (args.length > 0 && args[0].equals("--verify")) {
            List<String> infos = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            ValueGuard.verifyDiagnostic("growth_guardrail.json",
                    new String[]{"asof", "methodology", "flagged_count", "rows"},
                    new String[]{"flag"}, "flagged_count", infos, failures);
            Verify.report("VERIFY OK (GrowthGuardrail): schema, roster, flags, and protected values all check out.",
                    "VERIFY FAILED (GrowthGuardrail)", infos, failures);
            return;
        }
        runReport();
    }
}
