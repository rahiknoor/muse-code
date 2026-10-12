import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 build 4 — reverse DCF (derived diagnostics only, never changes a value).
 *
 * <p>Two diagnostics, computed from the same fixed-methodology code path as the
 * published DCF (via {@link DcfModel}):
 *
 * <ol>
 *   <li><b>Implied terminal growth</b> — given the market price, solve for the
 *       terminal growth rate {@code t} that makes the DCF equal the market
 *       price (bisection on {@code t}; cf. the implied_terminal_growth pattern
 *       in kelsonlam/stock-valuation, a public GitHub repo — engineering
 *       pattern, not peer-reviewed research). Answers "what perpetual growth
 *       is the market pricing in?"</li>
 *   <li><b>Terminal value as % of EV</b> — PV(TV)/EV from the model's own
 *       decomposition. The model's 75%-TV-share guardrail already warns past
 *       75%; this exposes the exact figure.</li>
 * </ol>
 *
 * <p>{@code --report} prints both diagnostics next to every &gt;15%
 * Muse-vs-NotebookLM disagreement (the "show me what the market implies"
 * companion to the disagreement spotlight). Reads workbook_data.json only.
 * Writes nothing.
 */
public class ReverseDcf {

    static Path home() {
        return Path.of(System.getenv("HOME"));
    }

    static String readFile(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * Solve for the terminal growth rate t such that DCF(t) = marketPrice.
     *
     * @return implied t, or NaN when the price implies t &le; 0 (price at or
     *         below the zero-growth value) or t &ge; w (price at or above the
     *         value as t approaches w — explosive, no finite solution)
     */
    static double impliedTerminalGrowth(String kind, double base, double g, double w,
                                        double shares, double netdebt,
                                        Double startMargin, Double endMargin,
                                        double marketPrice) {
        double lo = 1e-4, hi = w - 1e-4;
        if (hi <= lo) return Double.NaN;
        double vLo = DcfModel.intrinsicValue(kind, base, g, w, lo, shares, netdebt,
                startMargin, endMargin);
        double vHi = DcfModel.intrinsicValue(kind, base, g, w, hi, shares, netdebt,
                startMargin, endMargin);
        if (marketPrice <= vLo) return Double.NaN; // implies t <= 0
        if (marketPrice >= vHi) return Double.NaN; // implies t >= w
        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2;
            double v = DcfModel.intrinsicValue(kind, base, g, w, mid, shares, netdebt,
                    startMargin, endMargin);
            if (v < marketPrice) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** PV of terminal value as a fraction of EV. */
    static double terminalValueShare(String kind, double base, double g, double w, double t,
                                     double shares, double netdebt,
                                     Double startMargin, Double endMargin) {
        DcfModel.TerminalValues tv = DcfModel.terminalValues(
                kind, base, g, w, t, shares, netdebt, startMargin, endMargin);
        if (tv.ev == 0) return Double.NaN;
        return tv.pvTv / tv.ev;
    }

    static void runReport() throws Exception {
        DcfModel.Json.Obj wb = (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(
                home().resolve("workspace/your_files/dcf-valuations/.src/workbook_data.json")));
        String marketAsof = wb.optStr("market_asof", "not recorded");

        // Ticker -> assumptions + market price.
        java.util.Map<String, DcfModel.Json.Obj> asm = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> price = new java.util.LinkedHashMap<>();
        for (DcfModel.Json.Val cv : wb.getList("companies")) {
            DcfModel.Json.Obj c = (DcfModel.Json.Obj) cv;
            asm.put(c.getStr("ticker"), c.getObj("assumptions"));
            price.put(c.getStr("ticker"), c.getNum("market"));
        }

        System.out.println("# Reverse DCF - disagreements with market-implied terminal growth");
        System.out.println();
        System.out.println("Market prices as of: " + marketAsof);
        System.out.println();
        System.out.println("| Ticker | Price | Our DCF | NLM | Implied t | TV % of EV |");
        System.out.println("|---|---|---|---|---|---|");
        for (DcfModel.Json.Val dv : wb.getList("disagreements")) {
            DcfModel.Json.Obj d = (DcfModel.Json.Obj) dv;
            String ticker = d.getStr("ticker");
            DcfModel.Json.Obj a = asm.get(ticker);
            if (a == null) continue;
            double p = price.getOrDefault(ticker, Double.NaN);
            double implT = impliedTerminalGrowth(a.getStr("kind"), a.getNum("base"),
                    a.getNum("g"), a.getNum("w"), a.getNum("shares"), a.getNum("netdebt"),
                    a.getOptNum("start_margin"), a.getOptNum("end_margin"), p);
            double tvShare = terminalValueShare(a.getStr("kind"), a.getNum("base"),
                    a.getNum("g"), a.getNum("w"), a.getNum("t"), a.getNum("shares"),
                    a.getNum("netdebt"), a.getOptNum("start_margin"), a.getOptNum("end_margin"));
            System.out.println("| " + ticker
                    + " | " + round2(p)
                    + " | " + round2(d.getNum("ours"))
                    + " | " + round2(d.getNum("notebooklm"))
                    + " | " + (Double.isNaN(implT) ? "n/a" : String.format("%.2f%%", implT * 100))
                    + " | " + (Double.isNaN(tvShare) ? "n/a" : String.format("%.1f%%", tvShare * 100))
                    + " |");
        }
        System.out.println();
        System.out.println("n/a on implied t: price implies t<=0% (at/below zero-growth value) "
                + "or t>=w (explosive, no finite solution).");
    }

    static String round2(double v) {
        return String.format("%.2f", v);
    }

    static void selfTest() {
        // Golden: constant-FCF perpetuity analogue. With g=0 fade... use a tiny
        // model where the answer is closed-form: FCFF, base=100, g=0 -> the
        // fade keeps g=0 throughout, so FCF=100 every year; DCF(t) = PV(100 x10y)
        // + TV. Solve DCF(t)=P for t by bisection and verify round-trip.
        String kind = "FCFF";
        double w = 0.10, t = 0.03;
        double iv = DcfModel.intrinsicValue(kind, 100, 0.0, w, t, 10, 0.0, null, null);
        double implT = impliedTerminalGrowth(kind, 100, 0.0, w, 10, 0.0, null, null, iv);
        SelfTest.check("round-trip implied t == 3%", Math.abs(implT - 0.03) < 1e-6);

        // Monotonicity: higher price -> higher implied t.
        double iv2 = DcfModel.intrinsicValue(kind, 100, 0.0, w, 0.05, 10, 0.0, null, null);
        double implT2 = impliedTerminalGrowth(kind, 100, 0.0, w, 10, 0.0, null, null, iv2);
        SelfTest.check("higher price -> higher implied t", implT2 > implT);
        SelfTest.check("implied t == 5%", Math.abs(implT2 - 0.05) < 1e-6);

        // Price at/below zero-growth value -> NaN.
        double vZero = DcfModel.intrinsicValue(kind, 100, 0.0, w, 1e-4, 10, 0.0, null, null);
        SelfTest.check("price below zero-growth value -> NaN",
                Double.isNaN(impliedTerminalGrowth(kind, 100, 0.0, w, 10, 0.0, null, null,
                        vZero - 1)));

        // TV share is in (0,1) for a sane model and matches pvTv/ev.
        DcfModel.TerminalValues tv = DcfModel.terminalValues(kind, 100, 0.10, w, t, 10, 0.0,
                null, null);
        double share = terminalValueShare(kind, 100, 0.10, w, t, 10, 0.0, null, null);
        SelfTest.check("tv share in (0,1)", share > 0 && share < 1);
        SelfTest.check("tv share == pvTv/ev",
                Math.abs(share - tv.pvTv / tv.ev) < 1e-12);

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
            infos.add("ReverseDcf is read-only: writes no files");
            Verify.report("VERIFY OK (ReverseDcf): read-only diagnostic, no value files touched.",
                    "VERIFY FAILED (ReverseDcf)", infos, failures);
            return;
        }
        runReport();
    }
}
