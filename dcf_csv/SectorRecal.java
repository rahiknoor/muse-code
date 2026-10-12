import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Sector discount-rate recalibration (Rahik 2026-09-28).
 *
 * The backtest showed our DCF running systematically pessimistic (actuals beat
 * it by a median +88.8% over 1y). The growth assumptions were NOT the driver
 * (they average +2.5% vs historical FCF CAGR) — the discount rates were:
 * one flat-ish 8-10% band regardless of whether the company is a bank, a
 * semiconductor maker, an oil major, or a pharma.
 *
 * This tool re-prices every holding with a sector-appropriate discount rate
 * that mimics Damodaran's January 2026 US cost-of-capital table (rounded to
 * the nearest 0.25%, with small documented judgmental tilts), plus a
 * sector-sensible terminal growth rate:
 *   - banks / financials : cost of equity, not WACC (debt is raw material)
 *   - semiconductors     : 10.50%  (Damodaran 10.55%)
 *   - software sys/app   :  9.25%  (Damodaran 9.34%)
 *   - software internet  : 10.50%  (Damodaran 10.66%)
 *   - pharma             :  7.75%  (Damodaran 7.85%)
 *   - healthcare support :  7.00%  (Damodaran 6.83%)
 *   - integrated oil     :  5.50%  (Damodaran 5.07% + transition-risk tilt)
 *   - entertainment      :  7.25%  (Damodaran 7.13%)
 *   - retail general     :  7.50%  (Damodaran 7.27% + cyclical tilt)
 *   - beverage (soft)    :  6.50%  (Damodaran 6.33%)
 *   - hotel/gaming       :  7.50%  (Damodaran 7.36%)
 *   - auto & truck       :  9.50%  (Damodaran 9.38%)
 *   - payments networks  :  7.50%  (financial-services proxy)
 *   - conglomerate (BRK) :  7.50%  (insurance + operating blend)
 *
 * Growth assumptions are UNCHANGED — they stay story-informed (management
 * guidance, moat, cycle narrative), which is the durable difference between
 * the Muse DCF and NotebookLM's concrete-evidence DCF (Rahik 2026-09-28:
 * the two numbers should still vary; NotebookLM uses only concrete
 * evidence, Muse studies the story).
 *
 * Output: prints the re-pricing table and writes sector_recal.json, a list
 * of filing payloads (ticker, new value, new assumptions, rationale) for
 * the proposal pipeline. This tool files nothing itself.
 */
public class SectorRecal {

    static final class Row {
        String ticker, sector, anchor, note;
        double w, t;
        Row(String ticker, String sector, double w, double t, String anchor, String note) {
            this.ticker = ticker; this.sector = sector; this.w = w; this.t = t;
            this.anchor = anchor; this.note = note;
        }
    }

    // Ticker -> sector discount rate + terminal growth. IREN omitted (no
    // benchmark; BTC/AI-infra special case keeps its 15%).
    static final List<Row> TABLE = List.of(
        new Row("AMD",  "semiconductor",        0.1050, 0.030, "Damodaran 10.55%", "at benchmark"),
        new Row("NVDA", "semiconductor",        0.1050, 0.030, "Damodaran 10.55%", "at benchmark"),
        new Row("AVGO", "semiconductor",        0.1050, 0.030, "Damodaran 10.55%", "was 12.0%, above benchmark"),
        new Row("INTC", "semiconductor",        0.1050, 0.025, "Damodaran 10.55%", "at benchmark"),
        new Row("QCOM", "semiconductor",        0.1050, 0.030, "Damodaran 10.55%", "at benchmark"),
        new Row("MSFT", "software (sys/app)",  0.0925, 0.030, "Damodaran 9.34%",  "near benchmark"),
        new Row("CRM",  "software (sys/app)",  0.0925, 0.035, "Damodaran 9.34%",  "was 8.5%, below benchmark"),
        new Row("NOW",  "software (sys/app)",  0.0925, 0.035, "Damodaran 9.34%",  "near benchmark"),
        new Row("ORCL", "software (sys/app)",  0.0925, 0.025, "Damodaran 9.34%",  "near benchmark"),
        new Row("GOOG", "software (internet)", 0.1050, 0.030, "Damodaran 10.66%", "was 9.0%, below benchmark"),
        new Row("META", "software (internet)", 0.1075, 0.030, "Damodaran 10.66%", "was 11.5%, above benchmark"),
        new Row("PLTR", "software, high-beta", 0.1000, 0.035, "Damodaran 9.34% + beta tilt", "was 11.0%"),
        new Row("LLY",  "pharma",              0.0775, 0.035, "Damodaran 7.85%",  "near benchmark; t trimmed to 3.5%"),
        new Row("JNJ",  "pharma",              0.0775, 0.025, "Damodaran 7.85%",  "near benchmark"),
        new Row("PFE",  "pharma",              0.0775, 0.025, "Damodaran 7.85%",  "was 8.5%, above benchmark"),
        new Row("UNH",  "healthcare support",  0.0700, 0.025, "Damodaran 6.83%",  "was 8.5%, well above benchmark"),
        new Row("CVX",  "integrated oil",      0.0550, 0.015, "Damodaran 5.07% + transition-risk tilt", "was 8.0%, far above"),
        new Row("XOM",  "integrated oil",      0.0550, 0.015, "Damodaran 5.07% + transition-risk tilt", "was 9.0%, far above"),
        new Row("NFLX", "entertainment",       0.0725, 0.035, "Damodaran 7.13%",  "was 8.5%, above benchmark"),
        new Row("AMZN", "retail/internet blend", 0.0850, 0.030, "retail 7.27% / internet 10.66% blend", "AWS + retail blend"),
        new Row("RH",   "retail (general)",    0.0850, 0.025, "Damodaran 7.27% + cyclical/size tilt", "was 9.5%; luxury cyclical mid-cap"),
        new Row("CELH", "beverage (soft)",     0.0750, 0.030, "Damodaran 6.33% + growth/mid-cap tilt", "was 10.0%; not KO/PEP"),
        new Row("WH",   "hotel/gaming",        0.0750, 0.025, "Damodaran 7.36%",  "was 8.5%, above benchmark"),
        new Row("TSLA", "auto & truck",        0.0950, 0.030, "Damodaran 9.38%",  "near benchmark"),
        new Row("JPM",  "bank (FCFE @ Ke)",    0.0850, 0.030, "Ke ~= Rf 4.3% + beta~1.0 x ERP 4.23%", "was 10.0%; banks use cost of equity"),
        new Row("SOFI", "fintech bank (FCFE @ Ke)", 0.1000, 0.030, "money-center Ke + small-bank premium", "was 11.0%"),
        new Row("BRK.B","conglomerate",        0.0750, 0.025, "insurance ~6.5% + operating ~8.5% blend", "was 8.5%"),
        new Row("V",    "payments network",    0.0750, 0.025, "financial-services proxy", "was 8.5%"),
        new Row("MA",   "payments network",    0.0750, 0.030, "financial-services proxy", "was 8.0%; t trimmed to 3.0%"),
        new Row("GRAB", "internet + SEA country risk", 0.1225, 0.030, "internet 10.66% + ~1.6% CRP", "was 11.5%"),
        new Row("QXO",  "building materials",  0.0850, 0.030, "Damodaran 7.85%", "post-acquisition leverage tilt; matches blessed 2026-10-03 re-audit w")
    );

    public static void main(String[] args) throws Exception {
        // Additive read-only mode (2026-09-29, idea 2/3): dump the canonical
        // sector discount table for ComponentAudit.java. Returns before any
        // other behavior; existing behavior is untouched.
        if (args.length == 1 && args[0].equals("--dump-table")) {
            for (Row r : TABLE) System.out.println(r.ticker + " " + r.w + " " + r.t);
            return;
        }
        Path home = Paths.get(System.getenv("HOME"));
        Path blessedF = home.resolve("workspace/dcf_csv/blessed.json");
        Proposals.J.O blessed = (Proposals.J.O) Proposals.J.parse(
                Files.readString(blessedF, StandardCharsets.UTF_8));
        Proposals.J.O ours = (Proposals.J.O)
                ((Proposals.J.O) blessed.m.get("columns")).m.get("ours");
        Proposals.J.O tickers = (Proposals.J.O) ours.m.get("tickers");

        List<Proposals.J.O> payloads = new ArrayList<>();
        System.out.println("ticker  sector                      old w/t      new w/t      old value    new value    chg");
        for (Row r : TABLE) {
            Proposals.J.O entry = (Proposals.J.O) tickers.m.get(r.ticker);
            if (entry == null) throw new IllegalStateException("no blessed entry: " + r.ticker);
            double oldV = Proposals.J.num(entry.m.get("value"));
            Proposals.J.O asm = (Proposals.J.O) Proposals.J.parse(
                    Proposals.J.dumps(entry.m.get("assumptions")));
            double oldW = Proposals.J.num(asm.m.get("w"));
            double oldT = Proposals.J.num(asm.m.get("t"));
            asm.m.put("w", new Proposals.J.N(r.w, Double.toString(r.w)));
            asm.m.put("t", new Proposals.J.N(r.t, Double.toString(r.t)));
            String kind = ((Proposals.J.S) asm.m.get("kind")).s;
            double base = Proposals.J.num(asm.m.get("base"));
            double g = Proposals.J.num(asm.m.get("g"));
            double shares = Proposals.J.num(asm.m.get("shares"));
            double netdebt = Proposals.J.num(asm.m.get("netdebt"));
            Double sm = asm.m.get("start_margin") == null ? null
                    : Proposals.J.num(asm.m.get("start_margin"));
            Double em = asm.m.get("end_margin") == null ? null
                    : Proposals.J.num(asm.m.get("end_margin"));
            double newV = DcfModel.intrinsicValue(kind, base, g, r.w, r.t, shares, netdebt, sm, em);
            double chg = (newV - oldV) / oldV * 100;
            System.out.printf("%-6s  %-26s  %4.1f/%3.1f -> %4.1f/%3.1f  %10.2f -> %10.2f  %+6.1f%%%n",
                    r.ticker, r.sector, oldW * 100, oldT * 100, r.w * 100, r.t * 100,
                    oldV, newV, chg);

            String rationale = "Sector discount-rate recalibration (2026-09-28, Rahik-approved): "
                    + "a bank is not a tech company is not an oil major — w "
                    + String.format("%.2f%%", oldW * 100) + "->" + String.format("%.2f%%", r.w * 100)
                    + " mimics Damodaran Jan-2026 US " + r.sector + " (" + r.anchor + "); "
                    + r.note + ". t " + String.format("%.2f%%", oldT * 100) + "->"
                    + String.format("%.2f%%", r.t * 100) + ". Growth assumption unchanged "
                    + "(story-informed; the durable Muse-vs-NotebookLM difference).";
            Proposals.J.O pl = new Proposals.J.O();
            pl.m.put("ticker", new Proposals.J.S(r.ticker));
            pl.m.put("new_value", new Proposals.J.N(newV, Double.toString(newV)));
            pl.m.put("assumptions", asm);
            pl.m.put("rationale", new Proposals.J.S(rationale));
            payloads.add(pl);
        }
        Path out = home.resolve("workspace/dcf_csv/sector_recal.json");
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < payloads.size(); i++) {
            sb.append(Proposals.J.dumps(payloads.get(i)));
            if (i + 1 < payloads.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwrote " + out + " (" + payloads.size() + " payloads)");
    }
}
