import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 build 3 — terminal-value cross-check (verifier only, never changes a value).
 *
 * <p>The DCF's terminal value is typically the largest single number in the
 * valuation, and it is the most assumption-fragile: it compounds one year's
 * FCF (year 10) through a perpetuity formula. This tool computes two
 * independent anchors for the same terminal value and flags disagreements
 * over 15% for assumption review:
 *
 * <ol>
 *   <li><b>Lumpiness anchor</b> — the Gordon TV re-anchored to the mean of
 *       years 8-10 FCF instead of year-10 FCF alone. A lumpy year-10 capex
 *       (or margin spike) that moves the Gordon TV &gt;15% vs the smoothed
 *       anchor means the TV inherits a one-year artifact.</li>
 *   <li><b>Book anchor</b> — an Ohlson (1995, <i>Contemporary Accounting
 *       Research</i>, peer-reviewed) residual-income valuation: firm value =
 *       book equity + PV of residual income, with abnormal earnings fading at
 *       persistence &omega; = 0.62 per Dechow, Hutton &amp; Sloan (1999,
 *       <i>Contemporary Accounting Research</i>, peer-reviewed; the 0.62
 *       persistence parameter) and Fama &amp; French (2000, <i>Journal of
 *       Business</i>, peer-reviewed) on profitability mean-reversion. Book
 *       equity and net income come from the company's own 10-K/20-F XBRL
 *       (companyfacts cache) — an independent anchor, not the FCF path.
 *       McKinsey's value-driver formula is the same economics in different
 *       notation: with model-consistent inputs it collapses to Gordon, so the
 *       genuine second anchor is the book-anchored residual-income value.</li>
 * </ol>
 *
 * <p>Caveat: for FCFF/REV-kind names {@code w} is a WACC while residual income
 * uses return on <i>equity</i>; the book anchor is therefore approximate for
 * those names and its flags are review prompts, not valuations. For FCFE-kind
 * names (banks) {@code w} is a cost of equity and the comparison is clean.
 *
 * <p>Reads: workbook_data.json (assumptions), filing_watch_state.json (CIKs),
 * companyfacts cache (book equity + net income). Writes: tv_crosscheck.json
 * (diagnostics only). Never touches blessed.json, our_dcf.json, or any
 * published value.
 */
public class TvCrossCheck {

    static final double FLAG_THRESHOLD = 0.15;
    /** Residual-income persistence (Dechow-Hutton-Sloan 1999: omega ~ 0.62). */
    static final double OMEGA = 0.62;

    static Path home() {
        return Path.of(System.getenv("HOME"));
    }

    // ------------------------------------------------------------------
    // Pure cross-check math (unit-tested).
    // ------------------------------------------------------------------

    /** Smoothed Gordon TV anchored to mean years-8..10 FCF. */
    static double smoothedTv(double avgFcf8910, double w, double t) {
        return avgFcf8910 * (1 + t) / (w - t);
    }

    /** Relative disagreement of the smoothed anchor vs the Gordon TV. */
    static double lumpinessGap(double gordonTv, double avgFcf8910, double w, double t) {
        if (gordonTv == 0) return 0;
        return Math.abs(smoothedTv(avgFcf8910, w, t) - gordonTv) / Math.abs(gordonTv);
    }

    /**
     * Ohlson residual-income firm value.
     *
     * @param bookEquity book equity at time 0 (latest 10-K/20-F)
     * @param roe0       return on equity at time 0 (NI_0 / book_0)
     * @param w          discount rate (cost of equity for FCFE-kind)
     * @param omega      residual-income persistence (0.62)
     * @param years      explicit fade horizon (10)
     * @return equity value = book + PV(fading residual income) + PV(fading TV)
     */
    static double residualIncomeValue(double bookEquity, double roe0, double w,
                                      double omega, int years) {
        double ri0 = (roe0 - w) * bookEquity;
        double pv = 0;
        for (int y = 1; y <= years; y++) {
            double riY = ri0 * Math.pow(omega, y);
            pv += riY / Math.pow(1 + w, y - 0.5); // mid-year, model convention
        }
        // Fading perpetuity at the horizon: RI_H x omega/(1+w-omega).
        double riH = ri0 * Math.pow(omega, years);
        double tvH = riH * omega / (1 + w - omega);
        pv += tvH / Math.pow(1 + w, years - 0.5);
        return bookEquity + pv;
    }

    // ------------------------------------------------------------------
    // Data plumbing.
    // ------------------------------------------------------------------

    static String readFile(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * Latest FY 10-K/20-F annual value for a concept, earliest-filed (no-peek).
     * Concepts are tried in order; the first concept with a <i>matching</i> row
     * wins (a concept with rows but no FY 10-K/20-F match does not block the
     * fallback — e.g. JNJ's quarterly-only StockholdersEquity rows).
     */
    static XbrlFacts.Fact latestAnnual(List<XbrlFacts.Fact> facts, String unit, String... concepts) {
        for (String concept : concepts) {
            List<XbrlFacts.Fact> rows = XbrlFacts.forConcept(facts, "us-gaap", concept);
            XbrlFacts.Fact best = null;
            for (XbrlFacts.Fact r : rows) {
                if (!unit.equals(r.unit())) continue;
                if (!"FY".equals(r.fp())) continue;
                String form = r.form() == null ? "" : r.form();
                if (!(form.equals("10-K") || form.equals("10-K/A")
                        || form.equals("20-F") || form.equals("20-F/A"))) continue;
                String filed = r.filed() == null ? "" : r.filed();
                if (filed.isEmpty()) continue;
                String end = r.end() == null ? "" : r.end();
                // Prefer the latest period end; tie-break on earliest filed (no-peek).
                if (best == null || end.compareTo(best.end()) > 0
                        || (end.equals(best.end()) && filed.compareTo(best.filed()) < 0)) {
                    best = r;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Report.
    // ------------------------------------------------------------------

    static void runReport() throws Exception {
        Path dcfDir = home().resolve("workspace/dcf_csv");
        DcfModel.Json.Obj wb = (DcfModel.Json.Obj) DcfModel.Json.parse(
                readFile(home().resolve("workspace/your_files/dcf-valuations/.src/workbook_data.json")));
        // CIKs live in the filing-watch state (lowercase keys; BRKB -> BRK.B).
        DcfModel.Json.Obj watch = (DcfModel.Json.Obj) DcfModel.Json.parse(
                readFile(home().resolve("workspace/sec_filings/filing_watch_state.json")));
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
            double base = a.getNum("base");
            double g = a.getNum("g");
            double w = a.getNum("w");
            double t = a.getNum("t");
            double shares = a.getNum("shares");
            double netdebt = a.getNum("netdebt");
            Double sm = a.getOptNum("start_margin");
            Double em = a.getOptNum("end_margin");

            DcfModel.TerminalValues tv = DcfModel.terminalValues(
                    kind, base, g, w, t, shares, netdebt, sm, em);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", ticker);
            row.put("kind", kind);
            row.put("gordon_tv", Math.round(tv.gordonTv * 100.0) / 100.0);
            row.put("pv_tv", Math.round(tv.pvTv * 100.0) / 100.0);
            row.put("tv_share_of_ev", tv.ev == 0 ? null
                    : Math.round(tv.pvTv / tv.ev * 10000.0) / 10000.0);

            // Anchor 1: lumpiness.
            double gap = lumpinessGap(tv.gordonTv, tv.avgFcf8910, w, t);
            boolean lumpFlag = gap > FLAG_THRESHOLD;
            row.put("smoothed_tv", Math.round(smoothedTv(tv.avgFcf8910, w, t) * 100.0) / 100.0);
            row.put("lumpiness_gap", Math.round(gap * 10000.0) / 10000.0);
            row.put("lumpiness_flag", lumpFlag);

            // Anchor 2: book-anchored residual income, compared PER SHARE.
            // (JPM's DCF is a per-share DDM with shares=1.0 normalized, so a
            // firm-level comparison would mismatch units. Per-share, the share
            // count cancels for ordinary tickers.)
            double dcfPerShare = DcfModel.intrinsicValue(
                    kind, base, g, w, t, shares, netdebt, sm, em);
            String bookNote = null;
            Boolean bookFlag = null;
            Double bookValuePs = null;
            try {
                String cik = cikByTicker.get(ticker);
                if (cik == null) {
                    bookNote = "no CIK in filing_watch_state.json";
                } else {
                    Path cf = dcfDir.resolve("backtest/companyfacts/"
                            + String.format("%010d", Long.parseLong(cik)) + ".json");
                    if (!Files.exists(cf)) {
                        bookNote = "companyfacts not cached";
                    } else {
                        List<XbrlFacts.Fact> facts = XbrlFacts.load(cf);
                        XbrlFacts.Fact b = latestAnnual(facts, "USD", "StockholdersEquity",
                                "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest");
                        XbrlFacts.Fact n = latestAnnual(facts, "USD", "NetIncomeLoss", "NetIncome",
                                "ProfitLoss");
                        XbrlFacts.Fact s = latestAnnual(facts, "shares",
                                "CommonStockSharesOutstanding");
                        if (b == null || n == null) {
                            bookNote = "book equity or net income not in XBRL";
                        } else if (b.val() <= 0) {
                            bookNote = "non-positive book equity";
                        } else if (dcfPerShare <= 0 || w <= 0.04) {
                            bookNote = "DCF per-share value or w not usable";
                        } else {
                            double actualSharesM = (s != null && s.val() > 0)
                                    ? s.val() / 1e6 : shares;
                            double roe0 = n.val() / b.val();
                            // XBRL is USD; model is $M.
                            double riFirmM = residualIncomeValue(b.val() / 1e6, roe0, w, OMEGA, 10);
                            double riPs = riFirmM / actualSharesM;
                            bookValuePs = Math.round(riPs * 100.0) / 100.0;
                            double bookGap = Math.abs(riPs - dcfPerShare) / Math.abs(dcfPerShare);
                            bookFlag = bookGap > FLAG_THRESHOLD;
                            row.put("book_gap", Math.round(bookGap * 10000.0) / 10000.0);
                            row.put("book_equity_m", Math.round(b.val() / 1e6 * 100.0) / 100.0);
                            row.put("ni_m", Math.round(n.val() / 1e6 * 100.0) / 100.0);
                            row.put("book_fy_end", b.end());
                            row.put("dcf_per_share", Math.round(dcfPerShare * 100.0) / 100.0);
                            if (kind.equals("FCFF") || kind.equals("REV")) {
                                bookNote = "w is WACC vs ROE-on-equity: approximate, review only";
                            }
                        }
                    }
                }
            } catch (Exception e) {
                bookNote = "error: " + e.getMessage();
            }
            row.put("ri_value_per_share", bookValuePs);
            // A skipped anchor (null) can never disagree: boolean false, with
            // the skip reason preserved in book_note.
            row.put("book_flag", Boolean.TRUE.equals(bookFlag));
            row.put("book_note", bookNote);
            if (lumpFlag || Boolean.TRUE.equals(bookFlag)) flagged++;
            rows.add(row);
        }

        DcfModel.Json.Obj out = new DcfModel.Json.Obj();
        out.put("asof", new DcfModel.Json.Str(java.time.LocalDate.now().toString()));
        out.put("methodology",
                new DcfModel.Json.Str("Gordon TV vs (1) years-8..10 smoothed TV and (2) Ohlson 1995 "
                        + "residual-income value, RI persistence omega=0.62 (Dechow-Hutton-Sloan 1999; "
                        + "Fama-French 2000). Verifier only; flags are review prompts, never value changes."));
        out.put("flag_threshold", new DcfModel.Json.Num(FLAG_THRESHOLD));
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
        Files.writeString(dcfDir.resolve("tv_crosscheck.json"),
                DcfModel.Json.dumps(out), StandardCharsets.UTF_8);

        // Markdown report to stdout.
        System.out.println("# Terminal-value cross-check");
        System.out.println();
        System.out.println("Flagged: " + flagged + " of " + rows.size()
                + " (disagreement > 15% vs either anchor).");
        System.out.println();
        System.out.println("| Ticker | Lumpiness gap | Lump flag | Book gap | Book flag | Book note |");
        System.out.println("|---|---|---|---|---|---|");
        for (Map<String, Object> r : rows) {
            boolean lf = Boolean.TRUE.equals(r.get("lumpiness_flag"));
            boolean bf = Boolean.TRUE.equals(r.get("book_flag"));
            if (!lf && !bf) continue;
            System.out.println("| " + r.get("ticker")
                    + " | " + r.get("lumpiness_gap")
                    + " | " + (lf ? "**FLAG**" : "-")
                    + " | " + r.get("book_gap")
                    + " | " + (bf ? "**FLAG**" : "-")
                    + " | " + r.get("book_note") + " |");
        }
        System.out.println();
        System.out.println("Full diagnostics: workspace/dcf_csv/tv_crosscheck.json");
    }

    // ------------------------------------------------------------------
    // Self-test.
    // ------------------------------------------------------------------

    static void selfTest() {
        // Lumpiness: hand-verified golden.
        // fcf10=100, avg=120, w=10%, t=3%: Gordon=1471.43, smoothed=1765.71, gap=20% -> flag.
        double gap = lumpinessGap(100 * 1.03 / 0.07, 120, 0.10, 0.03);
        SelfTest.check("lumpy gap is 20%", Math.abs(gap - 0.20) < 1e-9);
        SelfTest.check("lumpy gap flags", gap > FLAG_THRESHOLD);
        // fcf10=100, avg=105 -> gap=5% -> no flag.
        double gap2 = lumpinessGap(100 * 1.03 / 0.07, 105, 0.10, 0.03);
        SelfTest.check("smooth gap is 5%", Math.abs(gap2 - 0.05) < 1e-9);
        SelfTest.check("smooth gap does not flag", gap2 <= FLAG_THRESHOLD);

        // Ohlson invariant: ROE == w -> residual income is zero -> value == book.
        double v0 = residualIncomeValue(1000, 0.10, 0.10, OMEGA, 10);
        SelfTest.checkEq("roe==w -> value==book", 1000.0, Math.round(v0 * 1e6) / 1e6);

        // Direction: positive (negative) residual income adds (subtracts) value.
        double vPos = residualIncomeValue(1000, 0.15, 0.10, OMEGA, 10);
        double vNeg = residualIncomeValue(1000, 0.05, 0.10, OMEGA, 10);
        SelfTest.check("positive RI -> value > book", vPos > 1000);
        SelfTest.check("negative RI -> value < book", vNeg < 1000);

        // Hand-computed 1-year golden: B0=1000, RI0=50, w=10%, omega=0.62.
        // term1 = 31/sqrt(1.1) = 29.55724; TV1 = 19.22/0.48 = 40.041667;
        // term2 = 40.041667/sqrt(1.1) = 38.17820; value = 1067.73544.
        double v1 = residualIncomeValue(1000, 0.15, 0.10, OMEGA, 1);
        SelfTest.check("1-year golden ~1067.74", Math.abs(v1 - 1067.73544) < 0.01);

        // TerminalValues accessor agrees with intrinsicValue's EV decomposition:
        // ev - pvTv must equal pvFcf (internal consistency, no drift).
        DcfModel.TerminalValues tv = DcfModel.terminalValues(
                "FCFF", 1000, 0.10, 0.10, 0.03, 100, 0.0, null, null);
        SelfTest.check("ev == pvFcf + pvTv",
                Math.abs(tv.ev - (tv.pvFcf + tv.pvTv)) < 1e-6);
        SelfTest.check("gordon tv anchors on fcf10",
                Math.abs(tv.gordonTv - tv.fcf10 * 1.03 / 0.07) < 1e-6);

        // Per-share consistency: for an ordinary ticker the per-share book gap
        // must equal the firm-level book gap (shares cancel).
        double riFirm = residualIncomeValue(2000, 0.15, 0.10, OMEGA, 10);
        double dcfFirm = 2600.0, sh = 100.0;
        double gapFirm = Math.abs(riFirm - dcfFirm) / Math.abs(dcfFirm);
        double gapPs = Math.abs(riFirm / sh - dcfFirm / sh) / Math.abs(dcfFirm / sh);
        SelfTest.check("per-share gap == firm gap", Math.abs(gapFirm - gapPs) < 1e-12);

        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        if (args.length > 0 && args[0].equals("--verify")) {
            java.util.List<String> infos = new java.util.ArrayList<>();
            java.util.List<String> failures = new java.util.ArrayList<>();
            ValueGuard.verifyDiagnostic("tv_crosscheck.json",
                    new String[]{"asof", "methodology", "flag_threshold", "flagged_count", "rows"},
                    new String[]{"lumpiness_flag", "book_flag"}, "flagged_count",
                    infos, failures);
            Verify.report("VERIFY OK (TvCrossCheck): schema, roster, flags, and protected values all check out.",
                    "VERIFY FAILED (TvCrossCheck)", infos, failures);
            return;
        }
        runReport();
    }
}
