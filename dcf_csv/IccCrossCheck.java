import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 build 7 — implied cost of capital cross-check (proposals/review flags
 * only; never changes a discount rate or a value).
 *
 * <p>Easton (2004, <i>Journal of Accounting Research</i>, peer-reviewed)
 * implied cost of capital from consensus earnings forecasts:
 *
 * <ul>
 *   <li>PEG: {@code r = sqrt((eps2 - eps1) / P0)} (no dividends)</li>
 *   <li>Modified PEG: {@code r = sqrt((eps2 + r*dps1 - eps1) / P0)},
 *       solved by fixed-point iteration (Easton 2004, Eq. 7)</li>
 * </ul>
 *
 * <p>The ICC is compared against the ticker's sector-table discount rate
 * ({@code w} in the workbook assumptions). A gap over 200bp files a review
 * flag. Caveat: the Easton ICC is a cost of <i>equity</i>; for FCFF/REV-kind
 * names {@code w} is a WACC, so the comparison is approximate and the flag is
 * a review prompt, not a rate revision. For FCFE-kind names (banks) {@code w}
 * is a cost of equity and the comparison is clean.
 *
 * <p>Inputs: workbook_data.json (P0, w), analyst_eps.json (FY1/FY2 consensus
 * EPS + DPS, collected by the monthly analyst-target-refresh job — no new
 * cron). analyst_eps.json is a diagnostic input, NOT a blessed column.
 * Writes: icc_crosscheck.json (diagnostics only).
 */
public class IccCrossCheck {

    static final double FLAG_BP = 0.02; // 200bp

    static Path home() {
        return Path.of(System.getenv("HOME"));
    }

    static String readFile(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Easton (2004) PEG ICC: r = sqrt((eps2-eps1)/P0); NaN when eps2<=eps1. */
    static double pegIcc(double p0, double eps1, double eps2) {
        if (p0 <= 0 || eps2 <= eps1) return Double.NaN;
        return Math.sqrt((eps2 - eps1) / p0);
    }

    /**
     * Easton (2004) modified PEG ICC with dividends, fixed-point iteration:
     * r = sqrt((eps2 + r*dps1 - eps1)/P0).
     */
    static double modifiedPegIcc(double p0, double eps1, double eps2, double dps1) {
        if (p0 <= 0 || dps1 < 0) return Double.NaN;
        double r = pegIcc(p0, eps1, eps2);
        if (Double.isNaN(r)) {
            // Fall back: start from 10% when eps2<=eps1 but dividends exist.
            if (eps2 + 0.10 * dps1 <= eps1) return Double.NaN;
            r = 0.10;
        }
        for (int i = 0; i < 100; i++) {
            double next = Math.sqrt(Math.max(eps2 + r * dps1 - eps1, 0) / p0);
            if (Math.abs(next - r) < 1e-10) { r = next; break; }
            r = next;
        }
        return r;
    }

    static void runReport() throws Exception {
        Path dcfDir = home().resolve("workspace/dcf_csv");
        DcfModel.Json.Obj wb = (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(
                home().resolve("workspace/your_files/dcf-valuations/.src/workbook_data.json")));
        Path epsPath = dcfDir.resolve("analyst_eps.json");
        DcfModel.Json.Obj eps = Files.exists(epsPath)
                ? (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(epsPath))
                : new DcfModel.Json.Obj();

        java.util.Map<String, Double> price = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> sectorW = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> kind = new java.util.LinkedHashMap<>();
        for (DcfModel.Json.Val cv : wb.getList("companies")) {
            DcfModel.Json.Obj c = (DcfModel.Json.Obj) cv;
            String t = c.getStr("ticker");
            price.put(t, c.getNum("market"));
            DcfModel.Json.Obj a = c.getObj("assumptions");
            sectorW.put(t, a.getNum("w"));
            kind.put(t, a.getStr("kind"));
        }

        DcfModel.Json.Obj out = new DcfModel.Json.Obj();
        out.put("asof", new DcfModel.Json.Str(java.time.LocalDate.now().toString()));
        out.put("methodology", new DcfModel.Json.Str(
                "Easton (2004) PEG / modified-PEG implied cost of capital vs the "
                + "ticker's sector-table discount rate w. Flag |ICC-w|>200bp. "
                + "Review flags only; never changes a rate or value."));
        DcfModel.Json.Arr arr = new DcfModel.Json.Arr();
        int flagged = 0, covered = 0;

        System.out.println("# Implied cost of capital cross-check");
        System.out.println();
        System.out.println("| Ticker | P0 | EPS1 | EPS2 | DPS1 | PEG ICC | Mod-PEG ICC | Sector w | Gap | Flag |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|");
        for (String ticker : price.keySet()) {
            DcfModel.Json.Obj e = eps.getObj(ticker);
            DcfModel.Json.Obj o = new DcfModel.Json.Obj();
            o.put("ticker", new DcfModel.Json.Str(ticker));
            o.put("flag", new DcfModel.Json.Bool(false));
            if (e == null) {
                o.put("note", new DcfModel.Json.Str("no EPS data in analyst_eps.json"));
                arr.items.add(o);
                System.out.println("| " + ticker + " | - | - | - | - | - | - | - | - | no EPS data |");
                continue;
            }
            covered++;
            double p0 = price.get(ticker);
            double w = sectorW.get(ticker);
            Double e1 = e.getOptNum("eps_fy1");
            Double e2 = e.getOptNum("eps_fy2");
            Double d1 = e.getOptNum("dps_fy1");
            double iccPeg = (e1 != null && e2 != null) ? pegIcc(p0, e1, e2) : Double.NaN;
            double iccMod = (e1 != null && e2 != null && d1 != null)
                    ? modifiedPegIcc(p0, e1, e2, d1) : Double.NaN;
            double icc = !Double.isNaN(iccMod) ? iccMod : iccPeg;
            o.put("p0", new DcfModel.Json.Num(p0));
            o.put("eps_fy1", e1 == null ? new DcfModel.Json.Null() : new DcfModel.Json.Num(e1));
            o.put("eps_fy2", e2 == null ? new DcfModel.Json.Null() : new DcfModel.Json.Num(e2));
            o.put("icc_peg", Double.isNaN(iccPeg) ? new DcfModel.Json.Null()
                    : new DcfModel.Json.Num(Math.round(iccPeg * 10000.0) / 10000.0));
            o.put("icc_modified_peg", Double.isNaN(iccMod) ? new DcfModel.Json.Null()
                    : new DcfModel.Json.Num(Math.round(iccMod * 10000.0) / 10000.0));
            o.put("sector_w", new DcfModel.Json.Num(w));
            String note = e.optStr("note", null);
            if (Double.isNaN(icc)) {
                o.put("gap_bp", new DcfModel.Json.Null());
                o.put("flag", new DcfModel.Json.Bool(false));
                o.put("note", new DcfModel.Json.Str("ICC not computable (eps2<=eps1 or missing)"));
                System.out.println("| " + ticker + " | " + p0 + " | " + e1 + " | " + e2
                        + " | " + d1 + " | n/a | n/a | " + w + " | n/a | - | not computable |");
            } else {
                double gap = Math.abs(icc - w);
                boolean flag = gap > FLAG_BP;
                if (flag) flagged++;
                o.put("gap_bp", new DcfModel.Json.Num(Math.round(gap * 1000000.0) / 100.0));
                o.put("flag", new DcfModel.Json.Bool(flag));
                String k = kind.get(ticker);
                String caveat = (k.equals("FCFF") || k.equals("REV"))
                        ? "w is WACC vs equity ICC: approximate" : null;
                if (note == null) note = caveat;
                o.put("note", note == null ? new DcfModel.Json.Null()
                        : new DcfModel.Json.Str(note));
                System.out.println("| " + ticker + " | " + p0 + " | " + e1 + " | " + e2
                        + " | " + d1
                        + " | " + String.format("%.2f%%", iccPeg * 100)
                        + " | " + (Double.isNaN(iccMod) ? "n/a" : String.format("%.2f%%", iccMod * 100))
                        + " | " + String.format("%.2f%%", w * 100)
                        + " | " + String.format("%.0fbp", gap * 10000)
                        + " | " + (flag ? "**FLAG**" : "-")
                        + " | " + (note == null ? "" : note) + " |");
            }
            arr.items.add(o);
        }
        out.put("covered", new DcfModel.Json.Num((long) covered));
        out.put("flagged_count", new DcfModel.Json.Num((long) flagged));
        out.put("rows", arr);
        Files.writeString(dcfDir.resolve("icc_crosscheck.json"),
                DcfModel.Json.dumps(out), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Covered: " + covered + " of " + price.size()
                + "; flagged: " + flagged + ". Full diagnostics: workspace/dcf_csv/icc_crosscheck.json");
    }

    static void selfTest() {
        // Golden: P0=100, eps1=5, eps2=6 -> r = sqrt(1/100) = 10%.
        SelfTest.check("peg golden 10%", Math.abs(pegIcc(100, 5, 6) - 0.10) < 1e-12);
        // eps2<=eps1 -> NaN.
        SelfTest.check("no growth -> NaN", Double.isNaN(pegIcc(100, 6, 5)));
        SelfTest.check("flat eps -> NaN", Double.isNaN(pegIcc(100, 5, 5)));
        // Modified PEG with dps1=2: fixed point of r=sqrt((1+2r)/100).
        // Closed form: 100r^2-2r-1=0 -> r=(2+sqrt(404))/200=0.110498...
        double mod = modifiedPegIcc(100, 5, 6, 2);
        double closed = (2 + Math.sqrt(404)) / 200;
        SelfTest.check("modified peg converges to closed form",
                Math.abs(mod - closed) < 1e-9);
        // Zero dividend -> modified == plain PEG.
        SelfTest.check("dps=0 -> modified==peg",
                Math.abs(modifiedPegIcc(100, 5, 6, 0) - pegIcc(100, 5, 6)) < 1e-12);
        // Flag logic: 200bp boundary.
        SelfTest.check("gap 250bp flags", Math.abs(0.105 - 0.08) > FLAG_BP);
        SelfTest.check("gap 150bp ok", !(Math.abs(0.095 - 0.08) > FLAG_BP));
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
            ValueGuard.verifyDiagnostic("icc_crosscheck.json",
                    new String[]{"asof", "methodology", "covered", "flagged_count", "rows"},
                    new String[]{"flag"}, "flagged_count", infos, failures);
            Verify.report("VERIFY OK (IccCrossCheck): schema, roster, flags, and protected values all check out.",
                    "VERIFY FAILED (IccCrossCheck)", infos, failures);
            return;
        }
        runReport();
    }
}
