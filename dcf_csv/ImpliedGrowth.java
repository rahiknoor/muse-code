import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implied-growth readout (Rahik 2026-10-05: "I love this idea it should be
 * implemented" — as a column in the value table).
 *
 * <p>For each holding, solves for the implied perpetual growth rate t* such
 * that our fixed-methodology DCF (via {@link DcfModel}, same code path as the
 * published Our DCF) equals the current market price (morning price cache).
 * Answers: "what perpetual growth is the market pricing in?"
 *
 * <p>Verdict bands (gap = implied_t - model_t, percentage points):
 * <ul>
 *   <li>gap &le; 0 → "cheap" (market expects at most our terminal growth)</li>
 *   <li>0 &lt; gap &le; 2 → "fair"</li>
 *   <li>2 &lt; gap &le; 5 → "rich"</li>
 *   <li>gap &gt; 5 → "fantasy"</li>
 * </ul>
 * NaN cases: price at/below the zero-growth value → "cheap"; price at/above
 * the explosive bound (t* &ge; w) → "fantasy".
 *
 * <p>Derived diagnostic only — never changes a value. Backend:
 * {@code ~/workspace/dcf_csv/implied_growth.json}. Refresh with
 * {@code --refresh}; the full-comparison-table job refreshes before render.
 */
public class ImpliedGrowth {

    static Path home() {
        String h = System.getenv("HOME");
        return Path.of(h != null && !h.isEmpty() ? h : "/home/hatch");
    }

    static String readFile(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * Solve for the terminal growth rate t such that DCF(t) = marketPrice.
     * NaN when the price implies t &le; 0 or t &ge; w (explosive).
     */
    static double impliedT(String kind, double base, double g, double w,
                           double shares, double netdebt,
                           Double startMargin, Double endMargin,
                           double marketPrice) {
        // prop-20261005-009: the DCF model enforces a 2% WACC-terminal-growth
        // spread floor — terminal growth may not exceed w - 0.02. The old
        // ceiling (w - 1e-4) now throws inside intrinsicValue, which made
        // every impliedT return NaN (all-blank column, all-"cheap" verdicts
        // — caught 2026-10-06). The bisection searches the allowed range.
        double lo = 1e-4, hi = w - 0.02;
        if (hi <= lo || w <= 0) return Double.NaN;
        double vLo, vHi;
        try {
            vLo = DcfModel.intrinsicValue(kind, base, g, w, lo, shares, netdebt,
                    startMargin, endMargin);
            vHi = DcfModel.intrinsicValue(kind, base, g, w, hi, shares, netdebt,
                    startMargin, endMargin);
        } catch (IllegalArgumentException e) {
            return Double.NaN;
        }
        if (Double.isNaN(vLo) || Double.isNaN(vHi)) return Double.NaN;
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

    /** Verdict band for the gap between implied and model terminal growth. */
    static String verdict(double impliedT, double modelT, double w,
                          double vLo, double vHi, double price) {
        if (Double.isNaN(impliedT)) {
            // Distinguish the two NaN causes: below zero-growth value vs explosive.
            if (price >= vHi) return "fantasy";
            return "cheap";
        }
        double gap = impliedT - modelT;
        // Epsilon on the boundaries: 0.05 - 0.03 is not exactly 0.02 in binary.
        double eps = 1e-9;
        if (gap <= 0) return "cheap";
        if (gap <= 0.02 + eps) return "fair";
        if (gap <= 0.05 + eps) return "rich";
        return "fantasy";
    }

    /** Refresh implied_growth.json from workbook inputs + morning price cache. */
    static void refresh() throws Exception {
        DcfModel.Json.Obj wb = (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(
                home().resolve("workspace/your_files/dcf-valuations/.src/workbook_data.json")));
        DcfModel.Json.Obj cache = (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(
                home().resolve("workspace/stock_watch/price_cache.json")));
        DcfModel.Json.Obj prices = cache.getObj("prices");
        String priceAsof = cache.getStr("asof");

        Map<String, Object> tickers = new LinkedHashMap<>();
        int n = 0, skipped = 0;
        for (DcfModel.Json.Val cv : wb.getList("companies")) {
            DcfModel.Json.Obj c = (DcfModel.Json.Obj) cv;
            String ticker = c.getStr("ticker");
            DcfModel.Json.Obj a = c.getObj("assumptions");
            if (a == null) { skipped++; continue; }
            DcfModel.Json.Obj pxy = prices.getObj(ticker);
            if (pxy == null) { skipped++; continue; }
            double price = pxy.getNum("price");
            if (price <= 0) { skipped++; continue; }
            String kind = a.getStr("kind");
            double base = a.getNum("base"), g = a.getNum("g"), w = a.getNum("w"),
                   t = a.getNum("t"), shares = a.getNum("shares"), netdebt = a.getNum("netdebt");
            Double sm = a.getOptNum("start_margin"), em = a.getOptNum("end_margin");
            double impl = impliedT(kind, base, g, w, shares, netdebt, sm, em, price);
            double vLo, vHi;
            try {
                vLo = DcfModel.intrinsicValue(kind, base, g, w, 1e-4, shares, netdebt, sm, em);
                vHi = DcfModel.intrinsicValue(kind, base, g, w, w - 0.02, shares, netdebt, sm, em);
            } catch (IllegalArgumentException e) {
                vLo = Double.NaN; vHi = Double.NaN;
            }
            String v = verdict(impl, t, w, vLo, vHi, price);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("implied_t", Double.isNaN(impl) ? null : impl);
            row.put("model_t", t);
            row.put("verdict", v);
            row.put("price", price);
            tickers.put(ticker, row);
            n++;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("asof", java.time.LocalDate.now().toString());
        doc.put("price_asof", priceAsof);
        doc.put("definition", "Implied perpetual growth t* such that our fixed-methodology DCF(t*) = market price. Verdict bands on (t* - model_t): <=0 cheap, <=2pp fair, <=5pp rich, >5pp fantasy.");
        doc.put("tickers", tickers);
        Path out = home().resolve("workspace/dcf_csv/implied_growth.json");
        Files.writeString(out, DcfModel.Json.dumps(doc), StandardCharsets.UTF_8);
        System.out.println("implied_growth.json refreshed: " + n + " tickers, " + skipped + " skipped (price_asof=" + priceAsof + ")");
    }

    static void selfTest() {
        // Golden (post-2%-floor, prop-20261005-009): the old 9.45% golden was
        // hand-verified pre-floor; 9.45% exceeds the w-0.02 = 8.5% ceiling, so
        // that price is now correctly NaN + fantasy (see beyond-ceiling pin).
        // Solvable golden: price implying t = 6% must round-trip to 0.06.
        double iv6 = DcfModel.intrinsicValue("FCFF", 6735.0, 0.22, 0.105, 0.06, 1636.0, -2317.0, null, null);
        double impl6 = impliedT("FCFF", 6735.0, 0.22, 0.105, 1636.0, -2317.0, null, null, iv6);
        SelfTest.check("AMD implied t recovers 6%", Math.abs(impl6 - 0.06) < 1e-6);
        // Beyond the ceiling: price double the ceiling value -> NaN + fantasy.
        double vHiAmd = DcfModel.intrinsicValue("FCFF", 6735.0, 0.22, 0.105, 0.085, 1636.0, -2317.0, null, null);
        double implBeyond = impliedT("FCFF", 6735.0, 0.22, 0.105, 1636.0, -2317.0, null, null, vHiAmd * 2);
        SelfTest.check("beyond ceiling -> NaN", Double.isNaN(implBeyond));
        SelfTest.check("beyond ceiling verdict fantasy",
                verdict(implBeyond, 0.03, 0.105, 0, vHiAmd, vHiAmd * 2).equals("fantasy"));

        // Round-trip: DCF at model t must imply back model t.
        double iv = DcfModel.intrinsicValue("FCFF", 6735.0, 0.22, 0.105, 0.03, 1636.0, -2317.0, null, null);
        double rt = impliedT("FCFF", 6735.0, 0.22, 0.105, 1636.0, -2317.0, null, null, iv);
        SelfTest.check("round-trip implies model t", Math.abs(rt - 0.03) < 1e-6);

        // Band boundaries.
        SelfTest.check("gap 0 -> cheap", verdict(0.03, 0.03, 0.105, 0, 1e9, 100).equals("cheap"));
        SelfTest.check("gap 2pp -> fair", verdict(0.05, 0.03, 0.105, 0, 1e9, 100).equals("fair"));
        SelfTest.check("gap 5pp -> rich", verdict(0.08, 0.03, 0.105, 0, 1e9, 100).equals("rich"));
        SelfTest.check("gap 5.1pp -> fantasy", verdict(0.081, 0.03, 0.105, 0, 1e9, 100).equals("fantasy"));

        // NaN causes.
        SelfTest.check("below zero-growth value -> NaN",
                Double.isNaN(impliedT("FCFF", 6735.0, 0.22, 0.105, 1636.0, -2317.0, null, null, 50.0)));
        SelfTest.check("below-zero-growth verdict cheap",
                verdict(Double.NaN, 0.03, 0.105, 91.0, 63305.0, 50.0).equals("cheap"));
        SelfTest.check("explosive verdict fantasy",
                verdict(Double.NaN, 0.03, 0.105, 91.0, 63305.0, 999999.0).equals("fantasy"));

        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length > 0 && args[0].equals("--verify")) {
            java.util.List<String> infos = new java.util.ArrayList<>();
            java.util.List<String> failures = new java.util.ArrayList<>();
            infos.add("ImpliedGrowth --refresh writes only dcf_csv/implied_growth.json (derived diagnostic)");
            Verify.report("VERIFY OK (ImpliedGrowth): refresh writes one derived file; no value files touched.",
                    "VERIFY FAILED (ImpliedGrowth)", infos, failures);
            return;
        }
        refresh();
    }
}
