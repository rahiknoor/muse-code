import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * DcfHistory.java — HistoricalCheck for the DCF pipeline.
 *
 * <p>Rahik's standing order: when evaluating DCF numbers, judge the live
 * assumptions against how the company actually ran in history. This tool
 * reads the live per-company assumptions (the blessed {@code ours}
 * assumptions as re-blessed into {@code workbook_data.json}), pulls 5
 * years of FY financials from the on-disk SEC companyfacts, and flags —
 * mechanically and factually, no narrative — where an assumption runs
 * ahead of the company's own track record.
 *
 * <p>Concept tables and the FY 10-K/20-F selection rule mirror
 * {@link DcfBacktest} (same SEC selection semantics). Evaluation only:
 * never writes blessed values, never touches the audit guard. The only
 * file it writes is the derived {@code dcf-history.csv}.
 *
 * <p>Usage:
 * <pre>
 *   java --class-path ~/workspace/java-shared/classes dcf_csv/DcfHistory.java [--ticker T]
 *   java --class-path ~/workspace/java-shared/classes dcf_csv/DcfHistory.java --self-test
 * </pre>
 */
public class DcfHistory {

    // Concept candidates, first matching concept per fiscal year wins.
    // Borrowed from DcfBacktest (OCF_TAGS / CAPEX_TAGS / REV_TAGS).
    static final String[][] REV_TAGS = {
        {"us-gaap", "RevenueFromContractWithCustomerExcludingAssessedTax"},
        {"us-gaap", "Revenues"},
        {"us-gaap", "SalesRevenueNet"},
        {"ifrs-full", "Revenue"}};
    static final String[][] OCF_TAGS = {
        {"us-gaap", "NetCashProvidedByUsedInOperatingActivities"},
        {"ifrs-full", "CashFlowsFromUsedInOperatingActivities"}};
    static final String[][] CAPEX_TAGS = {
        {"us-gaap", "PaymentsToAcquirePropertyPlantAndEquipment"},
        {"us-gaap", "PaymentsToAcquireProductiveAssets"},
        {"us-gaap", "PaymentsToAcquireOtherPropertyPlantAndEquipment"},
        {"ifrs-full", "PurchaseOfPropertyPlantAndEquipment"},
        {"ifrs-full", "PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities"}};

    // Debt: total debt = DebtCurrent + LongTermDebt(/Noncurrent). Mirrors
    // DcfBacktest DEBT_ST_TAGS/DEBT_LT_TAGS, plus the current-portion and
    // short-term-borrowing fallbacks some filers (e.g. AMZN) use instead of
    // DebtCurrent. First concept with a per-year match wins, so a filer
    // reporting both DebtCurrent and LongTermDebtCurrent is not double-counted.
    static final String[][] DEBT_ST_TAGS = {
        {"us-gaap", "DebtCurrent"},
        {"us-gaap", "LongTermDebtCurrent"},
        {"us-gaap", "ShortTermBorrowings"},
        {"us-gaap", "CommercialPaper"}};
    static final String[][] DEBT_LT_TAGS = {
        {"us-gaap", "LongTermDebt"},
        {"us-gaap", "LongTermDebtNoncurrent"}};
    // Already-total debt concepts (short + long combined). When present for a
    // year they are used INSTEAD of the st+lt sum (which would double-count).
    // e.g. ORCL reports ~$130B here while LongTermDebt is empty.
    static final String[][] DEBT_COMBINED_TAGS = {
        {"us-gaap", "DebtLongtermAndShorttermCombinedAmount"},
        {"us-gaap", "DebtAndCapitalLeaseObligations"}};

    // Diluted weighted-average shares. Mirrors DcfBacktest SHARES_TAGS.
    static final String[][] SHARES_TAGS = {
        {"us-gaap", "WeightedAverageNumberOfDilutedSharesOutstanding"},
        {"us-gaap", "EntityCommonStockSharesOutstanding"},
        {"us-gaap", "CommonStockSharesOutstanding"},
        {"ifrs-full", "WeightedAverageShares"},
        {"ifrs-full", "AdjustedWeightedAverageShares"}};

    // Story-block concepts (all mechanical series; missing = honestly labeled).
    static final String[][] OPINC_TAGS = {
        {"us-gaap", "OperatingIncomeLoss"},
        {"ifrs-full", "ProfitLossFromOperatingActivities"}};
    static final String[][] GROSS_TAGS = {
        {"us-gaap", "GrossProfit"},
        {"us-gaap", "GrossProfitLoss"},
        {"ifrs-full", "GrossProfit"}};
    static final String[][] RD_TAGS = {
        {"us-gaap", "ResearchAndDevelopmentExpense"}};
    static final String[][] BUYBACK_TAGS = {
        {"us-gaap", "PaymentsForRepurchaseOfCommonStock"}};
    static final String[][] DIV_TAGS = {
        {"us-gaap", "PaymentsOfDividendsCommonStock"},
        {"us-gaap", "PaymentsOfDividends"}};
    static final String[][] INTEXP_TAGS = {
        {"us-gaap", "InterestExpense"}};
    static final String[][] SBC_TAGS = {
        {"us-gaap", "ShareBasedCompensation"}};
    static final String[][] EQUITY_TAGS = {
        {"us-gaap", "StockholdersEquity"}};
    // Round-2 story signals (Rahik 2026-09-28: "is there anything else?").
    static final String[][] ACQ_TAGS = {
        {"us-gaap", "PaymentsToAcquireBusinessNetOfCashAcquired"},
        {"us-gaap", "PaymentsToAcquireBusinessesNetOfCashAcquired"},
        {"us-gaap", "PaymentsToAcquireBusinesses"},
        {"us-gaap", "PaymentsToAcquireBusiness"}};
    static final String[][] GW_TAGS = {
        {"us-gaap", "Goodwill"}};
    static final String[][] GW_IMPAIR_TAGS = {
        {"us-gaap", "GoodwillImpairmentLoss"},
        {"us-gaap", "ImpairmentOfGoodwill"}};
    static final String[][] TAXEXP_TAGS = {
        {"us-gaap", "IncomeTaxExpenseBenefit"},
        {"ifrs-full", "IncomeTaxExpense"}};
    static final String[][] PRETAX_TAGS = {
        {"us-gaap", "IncomeLossFromContinuingOperationsBeforeIncomeTaxes"},
        {"us-gaap", "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest"},
        {"ifrs-full", "ProfitLossBeforeTax"}};

    /** Statutory US corporate tax rate used for NOPAT (labeled in output). */
    static final double STATUTORY_TAX = 0.21;

    static final String[] ANNUAL_FORMS = {"10-K", "10-K/A", "20-F", "20-F/A"};

    /** Derived-file stamp (same text as DerivedFiles.STAMP). */
    static final String STAMP =
            "derived from workbook_data.json via RebuildAll \u2014 do not edit, do not read as input";

    /**
     * CIK fallback for tickers whose assumptions live only in blessed.json
     * (no dcf_inputs_all.json entry). Verified 2026-09-28 against the
     * companyfacts directory.
     */
    static final Map<String, String> CIK_FALLBACK = Map.of(
            "AVGO", "0001730168",
            "GRAB", "0001855612",
            "META", "0001326801",
            "PFE", "0000078003",
            "QCOM", "0000804328");

    static final int YEARS = 5;

    /** Workspace root. Honors DCF_WORKSPACE_ROOT, else $HOME/workspace. */
    static Path workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        if (e != null && !e.isEmpty()) return Path.of(e);
        return Path.of(System.getenv("HOME") + "/workspace");
    }

    // ------------------------------------------------------------------
    // History extraction
    // ------------------------------------------------------------------

    /**
     * Per fiscal year: first concept (in tag order) with a matching FY
     * 10-K/20-F fact wins; among duplicate filings the latest period-end
     * wins, then the latest filed date (latest restatement = best history).
     */
    static Map<Integer, Double> annualSeries(List<XbrlFacts.Fact> facts, String[][] tags) {
        return annualSeries(facts, tags, "USD", null);
    }

    static Map<Integer, Double> annualSeries(List<XbrlFacts.Fact> facts, String[][] tags,
            String unit) {
        return annualSeries(facts, tags, unit, null);
    }

    /**
     * Share-count series with per-year period-end dates captured (needed for
     * split adjustment). Unit rule mirrors DcfBacktest: prefer USD, else the
     * first unit in file order (share counts are usually unit "shares").
     */
    static Map<Integer, Double> sharesSeries(List<XbrlFacts.Fact> facts,
            Map<Integer, String> endsOut) {
        for (String[] tag : SHARES_TAGS) {
            List<XbrlFacts.Fact> rows = XbrlFacts.forConcept(facts, tag[0], tag[1]);
            if (rows.isEmpty()) continue;
            String unit = null;
            for (XbrlFacts.Fact r : rows) {
                if ("USD".equals(r.unit())) { unit = "USD"; break; }
            }
            if (unit == null) unit = rows.get(0).unit();
            return annualSeries(facts, SHARES_TAGS, unit, endsOut);
        }
        return new LinkedHashMap<>();
    }

    /**
     * Stock splits, mirroring DcfBacktestWalkForward.WF_SPLITS (verified
     * 2026-09-28 against public split records, XBRL share-count jumps, and
     * Yahoo's split-event feed). Splits are cosmetic unit changes, not
     * dilution.
     */
    static final String[][] SPLITS = {
        {"AMZN", "2022-06-06", "20"},
        {"GOOG", "2022-07-18", "20"},
        {"NVDA", "2021-07-20", "4"},
        {"NVDA", "2024-06-10", "10"},
        {"TSLA", "2020-08-31", "5"},
        {"TSLA", "2022-08-25", "3"},
        {"CELH", "2023-11-15", "3"},
        {"AVGO", "2024-07-15", "10"},
        {"NFLX", "2025-11-17", "10"},
        {"NOW", "2025-12-18", "5"}};

    /**
     * Cumulative split factor for a share count whose fiscal period ended on
     * {@code end} (yyyy-MM-dd): pre-split years scale up into today's share
     * terms so the dilution series measures real issuance, not splits.
     */
    static double splitFactor(String ticker, String end) {
        double f = 1.0;
        for (String[] s : SPLITS) {
            if (s[0].equals(ticker) && end.compareTo(s[1]) < 0) {
                f *= Double.parseDouble(s[2]);
            }
        }
        return f;
    }

    /**
     * Per fiscal year: the concept used by the LATEST fiscal year wins
     * ("current tagging wins"); that concept's years form the series. This
     * keeps one consistent measure across the window instead of mixing
     * concepts year-by-year (e.g. NVDA's legacy FY2011 PPE fact vs its
     * current ProductiveAssets tagging). Among duplicate filings the latest
     * period-end wins, then the latest filed date.
     */
    static Map<Integer, Double> annualSeries(List<XbrlFacts.Fact> facts, String[][] tags,
            String unit, Map<Integer, String> endsOut) {
        Map<Integer, List<XbrlFacts.Fact>> byFy = new LinkedHashMap<>();
        for (String[] tag : tags) {
            for (XbrlFacts.Fact f : XbrlFacts.forConcept(facts, tag[0], tag[1])) {
                if (!unit.equals(f.unit())) continue;
                if (!"FY".equals(f.fp())) continue;
                boolean annual = false;
                for (String form : ANNUAL_FORMS) {
                    if (form.equals(f.form())) { annual = true; break; }
                }
                if (!annual) continue;
                if (f.filed() == null || f.filed().isEmpty()) continue;
                if (f.end() == null || f.end().isEmpty()) continue;
                int fy;
                try {
                    fy = Integer.parseInt(String.valueOf(f.fy()));
                } catch (Exception e) {
                    continue;
                }
                byFy.computeIfAbsent(fy * 100 + indexOf(tags, tag), k -> new ArrayList<>()).add(f);
            }
        }
        Map<Integer, Double> out = new LinkedHashMap<>();
        List<Integer> fys = new ArrayList<>();
        for (int key : byFy.keySet()) fys.add(key / 100);
        TreeSet<Integer> sorted = new TreeSet<>(fys);
        if (sorted.isEmpty()) return out;
        // Winning concept = the one the latest fiscal year uses ("current
        // tagging wins"), keeping one consistent measure across the window.
        int latestFy = sorted.last();
        int win = -1;
        for (int i = 0; i < tags.length; i++) {
            List<XbrlFacts.Fact> es = byFy.get(latestFy * 100 + i);
            if (es != null && !es.isEmpty()) { win = i; break; }
        }
        if (win < 0) return out;
        for (int fy : sorted) {
            List<XbrlFacts.Fact> es = byFy.get(fy * 100 + win);
            if (es == null || es.isEmpty()) continue;
            String maxEnd = "";
            for (XbrlFacts.Fact f : es) {
                if (f.end().compareTo(maxEnd) > 0) maxEnd = f.end();
            }
            String bestFiled = "";
            double bestVal = 0;
            for (XbrlFacts.Fact f : es) {
                if (f.end().equals(maxEnd) && f.filed().compareTo(bestFiled) >= 0) {
                    bestFiled = f.filed();
                    bestVal = f.val();
                }
            }
            out.put(fy, bestVal);
            if (endsOut != null) endsOut.put(fy, maxEnd);
        }
        return out;
    }

    static int indexOf(String[][] tags, String[] tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i][0].equals(tag[0]) && tags[i][1].equals(tag[1])) return i;
        }
        return -1;
    }

    /** Revenue series and FCF series, each the last YEARS points available. */
    static class Series {
        int[] fy = {};
        double[] v = {};
    }

    static Series lastN(Map<Integer, Double> s) {
        List<Integer> fys = new ArrayList<>(s.keySet());
        fys.sort(Integer::compareTo);
        int from = Math.max(0, fys.size() - YEARS);
        Series r = new Series();
        r.fy = new int[fys.size() - from];
        r.v = new double[fys.size() - from];
        for (int i = from; i < fys.size(); i++) {
            r.fy[i - from] = fys.get(i);
            r.v[i - from] = s.get(fys.get(i));
        }
        return r;
    }

    // ------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------

    static class Stats {
        Series rev;          // last 5 revenue years (may be fewer)
        Series fcf;          // last 5 OCF-capex years (may be fewer)
        Double revCagr;      // null when < 5 points or non-positive endpoints
        Double fcfCagr;      // null when < 5 points or non-positive FCF anywhere
        Double marginMin, marginAvg, marginMax; // FCF/revenue, over fcf years
        Double bestFcfGrowth; // best single-year FCF growth, null when unavailable
        Double avgFcf;
        Double marginFirst, marginLast; // FCF/revenue of first/last fcf-window year

        // --- capex intensity ---
        Double capFirst, capLast;          // USD
        Double intensFirst, intensLast;    // capex/revenue
        String intensTrend;                // rising|falling|flat, null when <2 pts
        boolean heavyCapexBuildout;
        // --- debt trajectory ---
        Double debtFirst, debtLast;        // USD total debt
        Double debtChange;                 // last/first - 1
        Double debtVsAvgFcf;               // debtLast / avgFcf
        boolean debtFundedGrowth;
        // --- shares ---
        Double sharesFirst, sharesLast;    // share counts (split-adjusted)
        Double sharesChange;               // last/first - 1
        boolean sharesSplitAdjusted;
        // --- ROIC (NOPAT at statutory tax; null/not-comparable when unreliable) ---
        Double roicFirst, roicLast;        // avg of first/last 2 non-null yearly ROICs
        String roicStatus;                 // ok | not-comparable | insufficient
        boolean roicDeteriorating;
        // --- margin trajectory ---
        Double grossFirst, grossLast, opMFirst, opMLast;
        String marginTrend;                // compressing|expanding|flat, null when <2 pts
        // --- R&D intensity ---
        Double rndFirst, rndLast;          // R&D/revenue
        String rndTrend;
        // --- shareholder payout ---
        Double buyback5y, div5y;           // USD sums over window; null when no data
        boolean buybacksOffsetByIssuance;
        // --- interest coverage ---
        Double covFirst, covLast;          // EBIT/interest
        String covStatus;                  // ok | not-comparable | insufficient
        // --- share-based comp ---
        Double sbcAvg;                     // avg SBC/revenue
        boolean sbcDrivenDilution;
        // --- round-2 story signals (Rahik 2026-09-28) ---
        Double acq5y;                      // USD M&A spend over window
        Integer acqYears;                  // years with material M&A spend
        Double gwImpair5y;                 // USD goodwill impairments over window
        Double goodwillLast;               // USD goodwill, last window year
        Double effTaxFirst, effTaxLast;    // effective tax rate, avg of ends
        String effTaxStatus;               // ok | insufficient
        String allocScorecard;             // "where every dollar went" sentence
        String guidanceStatus;             // pending until earnings-call pipeline lands
        String insiderNote;                // insider-buy/sell mentions vs buybacks
        int insiderBuys, insiderSells;     // mention counts behind insiderNote
        // --- story ---
        String story;
    }

    static Stats compute(Series rev, Series fcf) {
        Stats st = new Stats();
        st.rev = rev;
        st.fcf = fcf;
        if (rev.v.length == YEARS && rev.v[0] > 0 && rev.v[YEARS - 1] > 0) {
            st.revCagr = Math.pow(rev.v[YEARS - 1] / rev.v[0], 1.0 / (YEARS - 1)) - 1;
        }
        if (fcf.v.length == YEARS) {
            boolean pos = true;
            for (double v : fcf.v) if (v <= 0) pos = false;
            if (pos) {
                st.fcfCagr = Math.pow(fcf.v[YEARS - 1] / fcf.v[0], 1.0 / (YEARS - 1)) - 1;
            }
            double sum = 0, mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
            int m = Math.min(fcf.v.length, rev.v.length);
            for (int i = 0; i < fcf.v.length; i++) {
                sum += fcf.v[i];
                double r = i < m ? revAt(rev, fcf.fy[i]) : Double.NaN;
                if (!Double.isNaN(r) && r > 0) {
                    double mg = fcf.v[i] / r;
                    if (mg < mn) mn = mg;
                    if (mg > mx) mx = mg;
                }
            }
            st.avgFcf = sum / fcf.v.length;
            if (mx > -Double.MAX_VALUE) {
                st.marginMin = mn;
                st.marginMax = mx;
            }
            double msum = 0;
            int mc = 0;
            for (int i = 0; i < fcf.v.length; i++) {
                double r = revAt(rev, fcf.fy[i]);
                if (!Double.isNaN(r) && r > 0) { msum += fcf.v[i] / r; mc++; }
            }
            if (mc > 0) st.marginAvg = msum / mc;
            Double best = null;
            for (int i = 1; i < fcf.v.length; i++) {
                if (fcf.v[i - 1] > 0) {
                    double g = (fcf.v[i] - fcf.v[i - 1]) / fcf.v[i - 1];
                    if (best == null || g > best) best = g;
                }
            }
            st.bestFcfGrowth = best;
        }
        return st;
    }

    static double revAt(Series rev, int fy) {
        for (int i = 0; i < rev.fy.length; i++) {
            if (rev.fy[i] == fy) return rev.v[i];
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------
    // Extended history: capex intensity, debt, shares, ROIC, margins, R&D,
    // payout, coverage, SBC. Per-year series are anchored on the fcf-window
    // years so every ratio compares the same fiscal years. All thresholds
    // below are mechanical and stated; no narrative judgments.
    // ------------------------------------------------------------------

    /** Per-year concept maps pulled from one company's companyfacts. */
    static class Maps {
        Map<Integer, Double> rev = Map.of();
        Map<Integer, Double> ocf = Map.of();
        Map<Integer, Double> cap = Map.of();
        Map<Integer, Double> debtSt = Map.of();
        Map<Integer, Double> debtLt = Map.of();
        Map<Integer, Double> debtCombined = Map.of();
        Map<Integer, Double> shares = Map.of();
        Map<Integer, String> shareEnds = Map.of(); // period-end per fy, for splits
        Map<Integer, Double> opInc = Map.of();
        Map<Integer, Double> gross = Map.of();
        Map<Integer, Double> rnd = Map.of();
        Map<Integer, Double> buyback = Map.of();
        Map<Integer, Double> div = Map.of();
        Map<Integer, Double> intExp = Map.of();
        Map<Integer, Double> sbc = Map.of();
        Map<Integer, Double> equity = Map.of();
        // Round-2 story signals (Rahik 2026-09-28).
        Map<Integer, Double> acq = Map.of();
        Map<Integer, Double> goodwill = Map.of();
        Map<Integer, Double> gwImpair = Map.of();
        Map<Integer, Double> taxExp = Map.of();
        Map<Integer, Double> pretax = Map.of();
    }

    static Maps pullMaps(List<XbrlFacts.Fact> facts) {
        Maps m = new Maps();
        m.rev = annualSeries(facts, REV_TAGS);
        m.ocf = annualSeries(facts, OCF_TAGS);
        m.cap = annualSeries(facts, CAPEX_TAGS);
        m.debtSt = annualSeries(facts, DEBT_ST_TAGS);
        m.debtLt = annualSeries(facts, DEBT_LT_TAGS);
        m.debtCombined = annualSeries(facts, DEBT_COMBINED_TAGS);
        Map<Integer, String> ends = new LinkedHashMap<>();
        m.shares = sharesSeries(facts, ends);
        m.shareEnds = ends;
        m.opInc = annualSeries(facts, OPINC_TAGS);
        m.gross = annualSeries(facts, GROSS_TAGS);
        m.rnd = annualSeries(facts, RD_TAGS);
        m.buyback = annualSeries(facts, BUYBACK_TAGS);
        m.div = annualSeries(facts, DIV_TAGS);
        m.intExp = annualSeries(facts, INTEXP_TAGS);
        m.sbc = annualSeries(facts, SBC_TAGS);
        m.equity = annualSeries(facts, EQUITY_TAGS);
        m.acq = annualSeries(facts, ACQ_TAGS);
        m.goodwill = annualSeries(facts, GW_TAGS);
        m.gwImpair = annualSeries(facts, GW_IMPAIR_TAGS);
        m.taxExp = annualSeries(facts, TAXEXP_TAGS);
        m.pretax = annualSeries(facts, PRETAX_TAGS);
        return m;
    }

    static String trend(double first, double last) {
        if (last > first) return "rising";
        if (last < first) return "falling";
        return "flat";
    }

    /** Average of the first (or last) up-to-2 values — dampens single-year noise. */
    static double avgEnds(List<Double> xs, boolean first) {
        int n = Math.min(2, xs.size());
        double s = 0;
        for (int i = 0; i < n; i++) s += first ? xs.get(i) : xs.get(xs.size() - 1 - i);
        return s / n;
    }

    /**
     * The analysis window: the fcf-window years when FCF exists (FCFE names
     * like JPM have no comparable FCF series, so they fall back to the
     * revenue window and still get capex/debt/shares series).
     */
    static int[] windowYears(Stats st) {
        return st.fcf.fy.length > 0 ? st.fcf.fy : st.rev.fy;
    }

    /**
     * Total debt for a fiscal year: the combined total when the filer reports
     * one, else current + long-term; null when no debt concept covers the
     * year (skipped, not zero).
     */
    static Double totalDebt(Maps m, int fy) {
        Double comb = m.debtCombined.get(fy);
        if (comb != null) return comb;
        boolean hasSt = m.debtSt.containsKey(fy), hasLt = m.debtLt.containsKey(fy);
        if (hasSt || hasLt) {
            return m.debtSt.getOrDefault(fy, 0.0) + m.debtLt.getOrDefault(fy, 0.0);
        }
        return null;
    }

    /**
     * Fills the extended Stats fields from the concept maps. Series are
     * anchored on the analysis-window years so every ratio compares the same
     * fiscal years. Missing concepts leave their fields null and the story
     * labels them honestly (n/a or not-comparable) instead of forcing a
     * number.
     */
    static void enrich(Maps m, Stats st, String ticker) {
        int[] fys = windowYears(st);
        if (fys.length == 0) return;
        Map<Integer, Double> fcfByFy = new LinkedHashMap<>();
        for (int i = 0; i < st.fcf.fy.length; i++) {
            fcfByFy.put(st.fcf.fy[i], st.fcf.v[i]);
        }

        // --- capex intensity + FCF margin first/last (aligned years) ---
        List<Double> intens = new ArrayList<>();
        Double cFirst = null, cLast = null;
        for (int fy : fys) {
            Double c = m.cap.get(fy);
            double r = revAt(st.rev, fy);
            if (c != null && !Double.isNaN(r) && r > 0) {
                intens.add(c / r);
                if (cFirst == null) cFirst = c;
                cLast = c;
            }
        }
        st.capFirst = cFirst;
        st.capLast = cLast;
        if (intens.size() >= 2) {
            st.intensFirst = intens.get(0);
            st.intensLast = intens.get(intens.size() - 1);
            st.intensTrend = trend(st.intensFirst, st.intensLast);
        }
        List<Double> fm = new ArrayList<>();
        for (int fy : fys) {
            Double fv = fcfByFy.get(fy);
            double r = revAt(st.rev, fy);
            if (fv != null && !Double.isNaN(r) && r > 0) fm.add(fv / r);
        }
        if (!fm.isEmpty()) {
            st.marginFirst = fm.get(0);
            st.marginLast = fm.get(fm.size() - 1);
        }
        // Heavy-capex buildout: capex/revenue high AND rising while FCF
        // margins compress — depressed FCF reads as investment, not decline.
        if (st.intensFirst != null && st.marginFirst != null) {
            st.heavyCapexBuildout = st.intensLast >= 0.10
                    && st.intensLast > st.intensFirst
                    && st.marginLast < st.marginFirst;
        }

        // --- debt trajectory (total debt per year) ---
        List<Double> debt = new ArrayList<>();
        for (int fy : fys) {
            Double d = totalDebt(m, fy);
            if (d != null) debt.add(d);
        }
        if (debt.size() >= 2) {
            st.debtFirst = debt.get(0);
            st.debtLast = debt.get(debt.size() - 1);
            if (st.debtFirst > 0) st.debtChange = st.debtLast / st.debtFirst - 1;
        } else if (debt.size() == 1) {
            st.debtFirst = debt.get(0);
            st.debtLast = debt.get(0);
        }
        if (st.debtLast != null && st.avgFcf != null && st.avgFcf > 0) {
            st.debtVsAvgFcf = st.debtLast / st.avgFcf;
        }

        // --- share dilution (split-adjusted into today's share terms) ---
        List<Double> sh = new ArrayList<>();
        for (int fy : fys) {
            Double s = m.shares.get(fy);
            String end = m.shareEnds.get(fy);
            if (s != null && s > 0 && end != null) {
                double f = splitFactor(ticker, end);
                if (f != 1.0) st.sharesSplitAdjusted = true;
                sh.add(s * f);
            }
        }
        if (sh.size() >= 2) {
            st.sharesFirst = sh.get(0);
            st.sharesLast = sh.get(sh.size() - 1);
            st.sharesChange = st.sharesLast / st.sharesFirst - 1;
        }

        // --- ROIC: NOPAT (operating income at statutory tax) / invested
        // capital (total debt + equity). Years with non-positive invested
        // capital (e.g. negative equity) are skipped, not forced.
        if (m.opInc.isEmpty()) {
            st.roicStatus = "not-comparable (no operating income: bank/insurer or missing)";
        } else {
            List<Double> roics = new ArrayList<>();
            for (int fy : fys) {
                Double oi = m.opInc.get(fy);
                Double eq = m.equity.get(fy);
                Double td = totalDebt(m, fy);
                if (oi == null || eq == null || td == null) continue;
                double invested = eq + td;
                if (invested > 0) roics.add(oi * (1 - STATUTORY_TAX) / invested);
            }
            if (roics.size() >= 2) {
                st.roicStatus = "ok";
                st.roicFirst = avgEnds(roics, true);
                st.roicLast = avgEnds(roics, false);
                st.roicDeteriorating = st.roicLast < st.roicFirst * 0.8
                        && st.intensFirst != null && st.intensLast != null
                        && st.intensLast > st.intensFirst;
            } else {
                st.roicStatus = "insufficient (fewer than 2 comparable years)";
            }
        }

        // --- margin trajectory ---
        List<Double> gm = new ArrayList<>(), om = new ArrayList<>();
        for (int fy : fys) {
            double r = revAt(st.rev, fy);
            if (Double.isNaN(r) || r <= 0) continue;
            Double g = m.gross.get(fy), o = m.opInc.get(fy);
            if (g != null) gm.add(g / r);
            if (o != null) om.add(o / r);
        }
        if (gm.size() >= 2) {
            st.grossFirst = gm.get(0);
            st.grossLast = gm.get(gm.size() - 1);
        }
        if (om.size() >= 2) {
            st.opMFirst = om.get(0);
            st.opMLast = om.get(om.size() - 1);
            double d = st.opMLast - st.opMFirst;
            st.marginTrend = d <= -0.03 ? "compressing" : d >= 0.03 ? "expanding" : "flat";
        }

        // --- R&D intensity ---
        List<Double> rd = new ArrayList<>();
        for (int fy : fys) {
            double r = revAt(st.rev, fy);
            Double v = m.rnd.get(fy);
            if (v != null && !Double.isNaN(r) && r > 0) rd.add(v / r);
        }
        if (rd.size() >= 2) {
            st.rndFirst = rd.get(0);
            st.rndLast = rd.get(rd.size() - 1);
            st.rndTrend = trend(st.rndFirst, st.rndLast);
        }

        // --- shareholder payout ---
        double bb = 0, dv = 0;
        boolean anyPayout = false;
        for (int fy : fys) {
            Double b = m.buyback.get(fy), d = m.div.get(fy);
            if (b != null) { bb += b; anyPayout = true; }
            if (d != null) { dv += d; anyPayout = true; }
        }
        if (anyPayout) {
            st.buyback5y = bb;
            st.div5y = dv;
        }
        st.buybacksOffsetByIssuance = anyPayout && bb > 0
                && st.sharesChange != null && st.sharesChange >= 0.05;

        // --- interest coverage (EBIT ~ operating income) ---
        if (m.opInc.isEmpty()) {
            st.covStatus = "not-comparable (no operating income: bank/insurer or missing)";
        } else if (m.intExp.isEmpty()) {
            st.covStatus = "insufficient (no interest expense data)";
        } else {
            List<Double> cov = new ArrayList<>();
            for (int fy : fys) {
                Double oi = m.opInc.get(fy), ie = m.intExp.get(fy);
                if (oi != null && ie != null && ie > 0) cov.add(oi / ie);
            }
            if (cov.size() >= 2) {
                st.covStatus = "ok";
                st.covFirst = cov.get(0);
                st.covLast = cov.get(cov.size() - 1);
            } else {
                st.covStatus = "insufficient (fewer than 2 comparable years)";
            }
        }

        // --- share-based comp ---
        List<Double> sbcI = new ArrayList<>();
        for (int fy : fys) {
            double r = revAt(st.rev, fy);
            Double v = m.sbc.get(fy);
            if (v != null && !Double.isNaN(r) && r > 0) sbcI.add(v / r);
        }
        if (!sbcI.isEmpty()) {
            double s = 0;
            for (double v : sbcI) s += v;
            st.sbcAvg = s / sbcI.size();
        }
        st.sbcDrivenDilution = st.sbcAvg != null && st.sbcAvg >= 0.02
                && st.sharesChange != null && st.sharesChange >= 0.05;

        // --- M&A vs organic growth (round 2): acquisition spend, goodwill,
        // impairments. Revenue CAGR that was purchased is not the same story
        // as revenue CAGR that was earned.
        double acqSum = 0;
        int acqYrs = 0;
        boolean anyAcq = false;
        for (int fy : fys) {
            Double v = m.acq.get(fy);
            if (v != null) {
                anyAcq = true;
                acqSum += v;
                double r = revAt(st.rev, fy);
                if (v > 0 && !Double.isNaN(r) && r > 0 && v / r >= 0.05) acqYrs++;
                else if (v > 0) acqYrs++;
            }
        }
        if (anyAcq) {
            st.acq5y = acqSum;
            st.acqYears = acqYrs;
        }
        double gwImp = 0;
        boolean anyGwImp = false;
        for (int fy : fys) {
            Double v = m.gwImpair.get(fy);
            if (v != null) { anyGwImp = true; gwImp += v; }
        }
        if (anyGwImp) st.gwImpair5y = gwImp;
        if (fys.length > 0) st.goodwillLast = m.goodwill.get(fys[fys.length - 1]);

        // --- effective tax rate (round 2): tax expense / pre-tax income.
        // ROIC is computed at the 21% statutory rate; a structurally
        // low-tax company earns more NOPAT than that suggests.
        List<Double> etr = new ArrayList<>();
        for (int fy : fys) {
            Double tx = m.taxExp.get(fy), pt = m.pretax.get(fy);
            if (tx != null && pt != null && pt > 0) etr.add(tx / pt);
        }
        if (etr.size() >= 2) {
            st.effTaxStatus = "ok";
            st.effTaxFirst = avgEnds(etr, true);
            st.effTaxLast = avgEnds(etr, false);
        } else {
            st.effTaxStatus = "insufficient (fewer than 2 comparable years)";
        }

        // --- capital allocation scorecard (round 2): where every dollar went.
        // Sources = 5y FCF + net debt raised; uses = capex + M&A + buybacks
        // + dividends. Stated as both totals; no forced balance (cash can
        // build or drain, and other items exist).
        double fcfSum = 0;
        for (int i = 0; i < st.fcf.v.length; i++) fcfSum += st.fcf.v[i];
        double capSum = 0;
        for (int fy : fys) {
            Double c = m.cap.get(fy);
            if (c != null) capSum += c;
        }
        double debtRaised = (st.debtFirst != null && st.debtLast != null)
                ? Math.max(0, st.debtLast - st.debtFirst) : 0;
        double sources = fcfSum + debtRaised;
        double uses = capSum + acqSum + bb + dv;
        if (st.fcf.v.length > 0) {
            st.allocScorecard = "sources " + money(sources) + " (FCF "
                    + money(fcfSum) + " + net debt raised " + money(debtRaised)
                    + "); uses " + money(uses) + " (capex " + money(capSum)
                    + ", M&A " + money(acqSum) + ", buybacks " + money(bb)
                    + ", dividends " + money(dv) + ")";
        }

        // --- guidance credibility (round 2): no companyfacts source.
        // Wires into the earnings-call guidance pipeline (audit #9) when it
        // lands; until then the column says pending, never a guess.
        st.guidanceStatus = "pending (earnings-call guidance pipeline, audit #9)";

        // --- insider behavior vs buybacks (round 2): scan the insider-watch
        // digests for (TICKER) mentions in buy vs sell sections.
        int[] ib = insiderCountsFor(ticker);
        st.insiderBuys = ib[0];
        st.insiderSells = ib[1];
        st.insiderNote = ib[3] == 1 ? "insider watch: no digest data"
                : ib[0] == 0 && ib[1] == 0
                        ? "no insider/politician mentions in " + ib[2] + " digest(s)"
                        : ib[0] + " buy / " + ib[1] + " sell mention(s) across "
                                + ib[2] + " digest(s)";

        st.story = story(st, fys);
    }

    /**
     * Returns {buys, sells, digests, noDataFlag} for one ticker across the
     * insider-watch digest files. Counts (TICKER) hits inside the buy sections
     * (Insider buys, Politician buys) vs the sell sections.
     */
    static int[] insiderCountsFor(String ticker) {
        String tag = "(" + ticker + ")";
        int buys = 0, sells = 0, digests = 0;
        try {
            Path dir = workspaceRoot().resolve(
                    "goals/big-cap-insider-and-politician-buy-watch/hidden_files");
            if (!Files.isDirectory(dir)) return new int[]{0, 0, 0, 1};
            List<Path> files = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().startsWith("digest_")
                        && p.getFileName().toString().endsWith(".md"))
                        .forEach(files::add);
            }
            files.sort((a, b) -> a.getFileName().toString()
                    .compareTo(b.getFileName().toString()));
            for (Path p : files) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                digests++;
                String lower = text.toLowerCase();
                int ib = lower.indexOf("## insider buys");
                int is = lower.indexOf("## insider sells");
                int pb = lower.indexOf("## politician buys");
                int ps = lower.indexOf("## politician sells");
                buys += countInRanges(text, tag, ranges(lower, ib, is, pb, ps, true));
                sells += countInRanges(text, tag, ranges(lower, ib, is, pb, ps, false));
            }
        } catch (Exception e) {
            return new int[]{0, 0, 0, 1};
        }
        if (digests == 0) return new int[]{0, 0, 0, 1};
        return new int[]{buys, sells, digests, 0};
    }

    /** Character ranges of the buy (or sell) sections of a digest. */
    static List<int[]> ranges(String lower, int ib, int is, int pb, int ps, boolean buySide) {
        List<int[]> out = new ArrayList<>();
        int n = lower.length();
        // Section order in the digest: insider buys, insider sells,
        // politician buys, politician sells; each runs to the next ## heading.
        int[][] secs = {{ib, is}, {is, pb}, {pb, ps}, {ps, n}};
        boolean[] isBuy = {true, false, true, false};
        for (int i = 0; i < 4; i++) {
            if (secs[i][0] < 0) continue;
            if (isBuy[i] != buySide) continue;
            int end = secs[i][1] >= 0 ? secs[i][1] : n;
            // Extend to the next "## " heading if a section is missing.
            if (secs[i][1] < 0) {
                int nx = lower.indexOf("\n## ", secs[i][0] + 1);
                end = nx >= 0 ? nx : n;
            }
            out.add(new int[]{secs[i][0], end});
        }
        return out;
    }

    static int countInRanges(String text, String tag, List<int[]> ranges) {
        int c = 0;
        for (int[] r : ranges) {
            int idx = r[0];
            while ((idx = text.indexOf(tag, idx)) >= 0 && idx < r[1]) {
                c++;
                idx += tag.length();
            }
        }
        return c;
    }

    /**
     * "How the company ran": one mechanical paragraph assembled from the
     * series — numbers in sentence form, no narrative invention. A clause
     * is included only when its data exists; otherwise it says n/a or
     * not-comparable.
     */
    static String story(Stats st, int[] fys) {
        String yrs = fys.length == 0 ? "n/a"
                : ("FY" + fys[0] + "\u2013FY" + fys[fys.length - 1]);
        List<String> c = new ArrayList<>();
        if (st.capFirst != null && st.intensFirst != null) {
            c.add("capex " + money(st.capFirst) + "\u2192" + money(st.capLast)
                    + " (" + pct(st.intensFirst) + "\u2192" + pct(st.intensLast)
                    + " of revenue, " + st.intensTrend + ")");
        } else {
            c.add("capex n/a");
        }
        if (st.marginFirst != null) {
            c.add("FCF margin " + pct(st.marginFirst) + "\u2192" + pct(st.marginLast));
        }
        if ("ok".equals(st.roicStatus)) {
            c.add("ROIC " + pct(st.roicFirst) + "\u2192" + pct(st.roicLast)
                    + " (NOPAT at 21% statutory tax)");
        } else {
            c.add("ROIC " + st.roicStatus);
        }
        if (st.opMFirst != null) {
            c.add("op margin " + pct(st.opMFirst) + "\u2192" + pct(st.opMLast)
                    + (st.marginTrend == null ? "" : " (" + st.marginTrend + ")"));
        } else {
            c.add("op margin n/a");
        }
        if (st.grossFirst != null) {
            c.add("gross margin " + pct(st.grossFirst) + "\u2192" + pct(st.grossLast));
        }
        if (st.rndFirst != null) {
            c.add("R&D " + pct(st.rndFirst) + "\u2192" + pct(st.rndLast)
                    + " of revenue (" + st.rndTrend + ")");
        } else {
            c.add("R&D n/a");
        }
        if (st.debtFirst != null) {
            String dch = "";
            if (st.debtChange != null) {
                // Near-zero starting base: a percentage is noise, say from ~$0.
                dch = st.debtFirst < 1e7 ? " (from ~$0)"
                        : st.debtChange >= 0 ? " (up " + pct(st.debtChange) + ")"
                        : " (down " + pct(-st.debtChange) + ")";
            }
            c.add("debt " + money(st.debtFirst) + "\u2192" + money(st.debtLast) + dch);
        } else {
            c.add("debt n/a");
        }
        if ("ok".equals(st.covStatus)) {
            c.add("interest coverage " + String.format("%.1fx", st.covFirst) + "\u2192"
                    + String.format("%.1fx", st.covLast));
        } else {
            c.add("interest coverage " + st.covStatus);
        }
        if (st.buyback5y != null) {
            c.add("returned " + money(st.buyback5y + st.div5y)
                    + " to shareholders over 5y (buybacks " + money(st.buyback5y)
                    + ", dividends " + money(st.div5y) + ")");
        } else {
            c.add("shareholder payout n/a");
        }
        if (st.sharesChange != null) {
            c.add("shares " + (st.sharesChange >= 0 ? "up " : "down ")
                    + pct(Math.abs(st.sharesChange))
                    + (st.sharesSplitAdjusted ? " (split-adjusted)" : "")
                    + (st.sharesChange <= -0.05 ? " (net buybacks)" : ""));
        } else {
            c.add("shares n/a");
        }
        if (st.sbcAvg != null) {
            c.add("SBC " + pct(st.sbcAvg) + " of revenue (avg)");
        }
        // --- round-2 story signals ---
        if (st.allocScorecard != null) {
            c.add("capital allocation: " + st.allocScorecard);
        }
        if (st.acq5y != null && st.acq5y > 0) {
            StringBuilder ac = new StringBuilder("M&A " + money(st.acq5y)
                    + " over 5y (" + st.acqYears + " of " + fys.length + " yrs)");
            if (st.goodwillLast != null && st.goodwillLast > 0) {
                ac.append("; goodwill on books ").append(money(st.goodwillLast));
            }
            if (st.gwImpair5y != null && st.gwImpair5y > 0) {
                ac.append("; impairments ").append(money(st.gwImpair5y));
            }
            c.add(ac.toString());
        } else if (st.goodwillLast != null && st.goodwillLast > 0) {
            // All-stock deals (e.g. AMD/Xilinx) leave no cash M&A trace but do
            // leave goodwill — say so instead of claiming no M&A.
            c.add("M&A cash n/a; goodwill " + money(st.goodwillLast)
                    + " on books (stock deals leave no cash trace)");
        } else {
            c.add("M&A n/a");
        }
        if ("ok".equals(st.effTaxStatus)) {
            c.add("effective tax " + pct(st.effTaxFirst) + "\u2192"
                    + pct(st.effTaxLast) + " (vs 21% statutory in ROIC)");
        } else {
            c.add("effective tax " + st.effTaxStatus);
        }
        c.add("guidance track record: " + st.guidanceStatus);
        if (st.insiderNote != null) {
            c.add("insider watch: " + st.insiderNote);
        }
        return yrs + ": " + String.join("; ", c) + ".";
    }

    /** Compact money: $55.7B / $940M / $526k / $12. */
    static String money(double usd) {
        double a = Math.abs(usd);
        if (a >= 1e9) return String.format("$%.1fB", usd / 1e9);
        if (a >= 1e6) return String.format("$%.0fM", usd / 1e6);
        if (a >= 1e3) return String.format("$%.0fk", usd / 1e3);
        return String.format("$%.0f", usd);
    }

    // ------------------------------------------------------------------
    // Flags: mechanical assumption-vs-history comparisons. No narrative.
    // ------------------------------------------------------------------

    static List<String> flags(Json.Obj asm, Stats st) {
        return flags(asm, st, -1);
    }

    static List<String> flags(Json.Obj asm, Stats st, int baseYear) {
        List<String> out = new ArrayList<>();
        String kind = asm.has("kind") && asm.get("kind") instanceof Json.Str
                ? ((Json.Str) asm.get("kind")).v : "FCFF";
        Double g = num(asm, "g");
        Double base = num(asm, "base");
        Double endMargin = num(asm, "end_margin");

        if ("REV".equals(kind)) {
            if (g != null && st.revCagr != null && g > st.revCagr) {
                out.add("assumed revenue g " + pct(g) + " vs 5-yr revenue CAGR " + pct(st.revCagr));
            }
            if (endMargin != null && st.marginMax != null && endMargin > st.marginMax) {
                out.add("assumed year-5 margin " + pct(endMargin)
                        + " vs historical peak FCF margin " + pct(st.marginMax));
            }
            if (base != null && st.rev.v.length == YEARS) {
                double avg = 0;
                for (double v : st.rev.v) avg += v;
                avg /= YEARS;
                if (base < avg) {
                    out.add("base revenue " + mUSD(base) + "M below 5-yr average "
                            + mUSD(avg) + "M (trough year, not normalized)");
                }
            }
        } else if ("FCFF".equals(kind)) {
            if (g != null && st.fcfCagr != null && g > st.fcfCagr) {
                out.add("assumed g " + pct(g) + " vs 5-yr FCF CAGR " + pct(st.fcfCagr));
            }
            if (g != null && st.bestFcfGrowth != null && g > st.bestFcfGrowth) {
                out.add("assumed g " + pct(g) + " above best single-year FCF growth "
                        + pct(st.bestFcfGrowth) + " (hope vs track record)");
            }
            if (base != null && st.avgFcf != null && base < st.avgFcf) {
                // Classify the trough: heavy-capex buildout means depressed
                // FCF reads as investment (future capacity), not decline.
                String why;
                if (st.heavyCapexBuildout) {
                    why = "trough year, not normalized \u2014 depressed FCF looks"
                            + " investment-driven (capex intensity "
                            + pct(st.intensFirst) + "\u2192" + pct(st.intensLast)
                            + " of revenue)";
                } else if (st.intensFirst != null) {
                    why = "trough year, not normalized (capex intensity "
                            + pct(st.intensFirst) + "\u2192" + pct(st.intensLast)
                            + " of revenue \u2014 not a heavy-capex buildout)";
                } else {
                    why = "trough year, not normalized";
                }
                out.add("base FCF " + mUSD(base) + "M below 5-yr average "
                        + mUSD(st.avgFcf) + "M (" + why + ")");
            }
            // Base-year cross-check: the history series' last year should
            // reproduce the live base FCF (same filing). Only applies when the
            // series actually reaches the live base year; a stale series is
            // already labeled by its year range.
            if (base != null && st.fcf.v.length == YEARS && st.fcf.fy[YEARS - 1] == baseYear) {
                double histBaseM = st.fcf.v[YEARS - 1] / 1e6;
                double denom = Math.max(Math.abs(base / 1e6), 1.0);
                if (Math.abs(histBaseM - base / 1e6) / denom > 0.25) {
                    out.add("base-year cross-check: history FY" + baseYear + " FCF "
                            + String.format("%,.0f", histBaseM) + "M vs live base "
                            + String.format("%,.0f", base / 1e6)
                            + "M (series may be unreliable)");
                }
            }
        }

        // --- capital-structure & history-quality flags (all kinds, including
        // FCFE: these describe how the company ran, not the FCF projection) ---
        if (st.heavyCapexBuildout) {
            out.add("heavy-capex buildout: capex " + pct(st.intensFirst) + "\u2192"
                    + pct(st.intensLast) + " of revenue while FCF margin compressed "
                    + pct(st.marginFirst) + "\u2192" + pct(st.marginLast)
                    + " \u2014 depressed FCF looks investment-driven");
        }
        if (!"FCFE".equals(kind) && st.debtChange != null && st.debtChange >= 0.50
                && (st.fcfCagr == null || st.fcfCagr <= 0.0)) {
            out.add("debt-funded growth: total debt up " + pct(st.debtChange)
                    + " over 5y while "
                    + (st.fcfCagr == null
                            ? "5-yr FCF CAGR n/a (FCF not consistently positive)"
                            : "5-yr FCF CAGR " + pct(st.fcfCagr)));
        }
        if (st.sharesChange != null && st.sharesChange >= 0.05) {
            out.add("shares diluted " + pct(st.sharesChange)
                    + " over 5y \u2014 DCF divides by the share count");
        }
        if (st.roicDeteriorating) {
            out.add("ROIC deteriorating " + pct(st.roicFirst) + "\u2192"
                    + pct(st.roicLast) + " while capex intensity rising"
                    + " \u2014 capex not earning returns yet");
        }
        if ("compressing".equals(st.marginTrend)) {
            out.add("sustained operating-margin compression " + pct(st.opMFirst)
                    + "\u2192" + pct(st.opMLast));
        } else if ("expanding".equals(st.marginTrend)) {
            out.add("operating-margin expansion " + pct(st.opMFirst) + "\u2192"
                    + pct(st.opMLast));
        }
        if (st.rndFirst != null && st.rndFirst > 0 && st.rndLast != null
                && st.rndLast >= st.rndFirst * 1.25) {
            out.add("R&D intensity rising " + pct(st.rndFirst) + "\u2192"
                    + pct(st.rndLast) + " of revenue");
        }
        if (st.buybacksOffsetByIssuance) {
            out.add("buybacks " + money(st.buyback5y) + " over 5y but shares still diluted "
                    + pct(st.sharesChange) + " \u2014 issuance offset buybacks");
        }
        if ("ok".equals(st.covStatus) && st.covFirst != null && st.covLast != null) {
            if (st.covLast < 3.0) {
                out.add("interest coverage " + String.format("%.1fx", st.covLast)
                        + " \u2014 below 3x");
            }
            if (st.covFirst - st.covLast >= 2.0 && st.covLast < st.covFirst) {
                out.add("interest coverage deteriorating "
                        + String.format("%.1fx", st.covFirst) + "\u2192"
                        + String.format("%.1fx", st.covLast));
            }
        }
        if (st.sbcDrivenDilution) {
            out.add("SBC averaged " + pct(st.sbcAvg)
                    + " of revenue \u2014 likely the real dilution driver");
        }
        // --- round-2 flags (Rahik 2026-09-28) ---
        if (st.acq5y != null && st.acq5y > 0 && st.acqYears != null && st.acqYears >= 2) {
            out.add("serial acquirer: " + money(st.acq5y) + " M&A over 5y ("
                    + st.acqYears + " of 5 yrs) \u2014 part of revenue growth is"
                    + " purchased, not organic");
        }
        if (st.gwImpair5y != null && st.gwImpair5y > 0) {
            out.add("goodwill impairments " + money(st.gwImpair5y)
                    + " over 5y \u2014 past deals overpaid or deteriorated");
        }
        if ("ok".equals(st.effTaxStatus) && st.effTaxLast != null && st.effTaxLast < 0.15) {
            out.add("effective tax rate ~" + pct(st.effTaxLast)
                    + " vs 21% statutory \u2014 NOPAT/ROIC at statutory understates"
                    + " true cash returns");
        }
        if (st.insiderSells > 0 && st.buyback5y != null && st.buyback5y > 0) {
            out.add("insider sells recorded (" + st.insiderSells
                    + " mention(s) in the insider watch) while company bought back "
                    + money(st.buyback5y)
                    + " \u2014 signals disagree; check 10b5-1 context in the digest");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    static String pct(Double d) {
        return d == null ? "n/a" : String.format("%.1f%%", d * 100);
    }

    static String pct4(Double d) {
        return d == null ? "n/a" : String.format("%.4f", d);
    }

    /** Values are USD in companyfacts; live assumptions are $M. */
    static String mUSD(double usd) {
        return String.format("%,.0f", usd / 1e6);
    }

    /** CSV cell: USD millions with 1 decimal, blank when missing. */
    static String cellM(Stats st, java.util.function.Function<Stats, Double> get) {
        if (st == null) return "";
        Double v = get.apply(st);
        return v == null ? "" : String.format("%.1f", v / 1e6);
    }

    /** CSV cell: ratio with 4 decimals, blank when missing. */
    static String cellR(Stats st, java.util.function.Function<Stats, Double> get) {
        if (st == null) return "";
        Double v = get.apply(st);
        return v == null ? "" : String.format("%.4f", v);
    }

    static String mUSD(Double usd) {
        return usd == null ? "n/a" : mUSD(usd.doubleValue());
    }

    static Double num(Json.Obj o, String k) {
        if (!o.has(k) || o.get(k) == null) return null;
        Json.Val v = o.get(k);
        if (v instanceof Json.Num n) return n.d;
        return null;
    }

    // ------------------------------------------------------------------
    // Main flow
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        boolean selfTest = false;
        String onlyTicker = null;
        for (int i = 0; i < args.length; i++) {
            if ("--self-test".equals(args[i])) selfTest = true;
            else if ("--ticker".equals(args[i]) && i + 1 < args.length) onlyTicker = args[++i];
        }
        if (selfTest) {
            selfTest();
            return;
        }
        run(onlyTicker);
    }

    static void run(String onlyTicker) throws Exception {
        Path root = workspaceRoot();
        Json.Obj wd = (Json.Obj) Json.parse(readFile(root.resolve(
                "your_files/dcf-valuations/.src/workbook_data.json")));
        Json.Obj inputs = null;
        try {
            inputs = (Json.Obj) ((Json.Obj) Json.parse(
                    readFile(root.resolve("sec_filings/dcf_inputs_all.json")))).get("companies");
        } catch (Exception e) {
            inputs = new Json.Obj();
        }

        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("DCF History Check \u2014 " + STAMP));
        rows.add(List.of("ticker", "name", "kind", "assumed_g", "hist_rev_cagr",
                "hist_fcf_cagr", "best_fcf_growth", "fcf_margin_min", "fcf_margin_avg",
                "fcf_margin_max", "base_fcf_M", "avg_fcf_5y_M", "flags",
                "capex_first_M", "capex_last_M", "capex_rev_first", "capex_rev_last",
                "capex_trend", "heavy_capex_buildout",
                "debt_first_M", "debt_last_M", "debt_change", "debt_vs_avgfcf",
                "shares_first_M", "shares_last_M", "shares_change",
                "roic_first", "roic_last", "roic_status",
                "op_margin_first", "op_margin_last", "margin_trend",
                "rnd_rev_first", "rnd_rev_last",
                "payout_5y_M", "buyback_5y_M", "div_5y_M",
                "coverage_first", "coverage_last", "coverage_status",
                "sbc_rev_avg",
                "acq_5y_M", "acq_years", "gw_impair_5y_M", "goodwill_last_M",
                "eff_tax_first", "eff_tax_last", "eff_tax_status",
                "alloc_scorecard", "guidance_status", "insider_note",
                "story")); 

        int flagged = 0, total = 0;
        for (Json.Val cv : wd.getList("companies")) {
            Json.Obj c = (Json.Obj) cv;
            String t = c.getStr("ticker");
            if (onlyTicker != null && !onlyTicker.equals(t)) continue;
            total++;
            Json.Obj asm = c.getObj("assumptions");
            if (asm == null) asm = new Json.Obj();
            String kind = asm.has("kind") && asm.get("kind") instanceof Json.Str
                    ? ((Json.Str) asm.get("kind")).v : "FCFF";
            Double g = num(asm, "g");
            Double baseM = num(asm, "base");

            String cik = cikFor(t, inputs);
            Stats st = null;
            String histNote = "";
            if (cik == null) {
                histNote = "no CIK";
            } else {
                Path cf = root.resolve("dcf_csv/backtest/companyfacts/" + cik + ".json");
                if (!Files.exists(cf)) {
                    histNote = "no companyfacts file";
                } else {
                    List<XbrlFacts.Fact> facts = XbrlFacts.load(cf);
                    Maps maps = pullMaps(facts);
                    Series rev = lastN(maps.rev);
                    Map<Integer, Double> fcfMap = new LinkedHashMap<>();
                    for (int fy : maps.ocf.keySet()) {
                        if (maps.cap.containsKey(fy)) {
                            fcfMap.put(fy, maps.ocf.get(fy) - maps.cap.get(fy));
                        }
                    }
                    Series fcf = lastN(fcfMap);
                    st = compute(rev, fcf);
                    enrich(maps, st, t);
                    if (rev.v.length < YEARS || fcf.v.length < YEARS) {
                        histNote = "insufficient history (" + rev.v.length
                                + " rev yrs, " + fcf.v.length + " fcf yrs)";
                    }
                }
            }

            // Scale live base ($M) to USD so the trough check compares
            // like-for-like with the history series.
            int baseYear = baseYearFor(t, inputs, asm);
            List<String> flScaled;
            if (st == null) {
                flScaled = List.of();
            } else if (baseM != null) {
                flScaled = flags(copyWithBaseUSD(asm, baseM), st, baseYear);
            } else {
                flScaled = flags(asm, st, baseYear);
            }
            if (!flScaled.isEmpty()) flagged++;

            // Report.
            System.out.println(t + " " + c.getStr("name") + " (" + kind + ")"
                    + " \u2014 assumptions: g=" + pct(g)
                    + " base=" + (baseM == null ? "n/a" : String.format("%,.0f", baseM) + "M"));
            if (st == null) {
                System.out.println("  history: " + histNote);
            } else {
                String yrs = st.rev.fy.length == YEARS
                        ? ("FY" + st.rev.fy[0] + "-" + st.rev.fy[YEARS - 1]) : "partial";
                System.out.println("  history " + yrs + ": rev CAGR " + pct(st.revCagr)
                        + ", FCF CAGR " + pct(st.fcfCagr)
                        + ", FCF margin " + pct(st.marginMin) + "/" + pct(st.marginAvg)
                        + "/" + pct(st.marginMax) + " (min/avg/max)"
                        + ", best 1-yr FCF growth " + pct(st.bestFcfGrowth)
                        + ", avg FCF " + mUSD(st.avgFcf) + "M"
                        + (histNote.isEmpty() ? "" : " [" + histNote + "]"));
            }
            if (flScaled.isEmpty()) {
                System.out.println("  flags: none");
            } else {
                System.out.println("  flags:");
                for (String f : flScaled) System.out.println("    - " + f);
            }
            String storyLine;
            if (st != null && st.story != null) storyLine = st.story;
            else storyLine = histNote.isEmpty() ? "n/a" : histNote;
            System.out.println("  story: " + storyLine);

            List<String> row = new ArrayList<>();
            row.add(t);
            row.add(c.getStr("name"));
            row.add(kind);
            row.add(g == null ? "" : String.format("%.4f", g));
            row.add(st == null || st.revCagr == null ? "" : String.format("%.4f", st.revCagr));
            row.add(st == null || st.fcfCagr == null ? "" : String.format("%.4f", st.fcfCagr));
            row.add(st == null || st.bestFcfGrowth == null ? ""
                    : String.format("%.4f", st.bestFcfGrowth));
            row.add(st == null || st.marginMin == null ? ""
                    : String.format("%.4f", st.marginMin));
            row.add(st == null || st.marginAvg == null ? ""
                    : String.format("%.4f", st.marginAvg));
            row.add(st == null || st.marginMax == null ? ""
                    : String.format("%.4f", st.marginMax));
            row.add(baseM == null ? "" : String.format("%.1f", baseM));
            row.add(st == null || st.avgFcf == null ? ""
                    : String.format("%.1f", st.avgFcf / 1e6));
            row.add(String.join("; ", flScaled));
            // --- extended columns (appended; existing order preserved) ---
            row.add(cellM(st, s -> s.capFirst));
            row.add(cellM(st, s -> s.capLast));
            row.add(cellR(st, s -> s.intensFirst));
            row.add(cellR(st, s -> s.intensLast));
            row.add(st == null || st.intensTrend == null ? "" : st.intensTrend);
            row.add(st != null && st.heavyCapexBuildout ? "true" : "false");
            row.add(cellM(st, s -> s.debtFirst));
            row.add(cellM(st, s -> s.debtLast));
            row.add(cellR(st, s -> s.debtChange));
            row.add(cellR(st, s -> s.debtVsAvgFcf));
            row.add(st == null || st.sharesFirst == null ? ""
                    : String.format("%.1f", st.sharesFirst / 1e6));
            row.add(st == null || st.sharesLast == null ? ""
                    : String.format("%.1f", st.sharesLast / 1e6));
            row.add(cellR(st, s -> s.sharesChange));
            row.add(cellR(st, s -> s.roicFirst));
            row.add(cellR(st, s -> s.roicLast));
            row.add(st == null || st.roicStatus == null ? "" : st.roicStatus);
            row.add(cellR(st, s -> s.opMFirst));
            row.add(cellR(st, s -> s.opMLast));
            row.add(st == null || st.marginTrend == null ? "" : st.marginTrend);
            row.add(cellR(st, s -> s.rndFirst));
            row.add(cellR(st, s -> s.rndLast));
            row.add(st == null || st.buyback5y == null ? ""
                    : String.format("%.1f", (st.buyback5y + st.div5y) / 1e6));
            row.add(cellM(st, s -> s.buyback5y));
            row.add(cellM(st, s -> s.div5y));
            row.add(st == null || st.covFirst == null ? ""
                    : String.format("%.2f", st.covFirst));
            row.add(st == null || st.covLast == null ? ""
                    : String.format("%.2f", st.covLast));
            row.add(st == null || st.covStatus == null ? "" : st.covStatus);
            row.add(cellR(st, s -> s.sbcAvg));
            // --- round-2 columns (appended; existing order preserved) ---
            row.add(cellM(st, s -> s.acq5y));
            row.add(st == null || st.acqYears == null ? "" : String.valueOf(st.acqYears));
            row.add(cellM(st, s -> s.gwImpair5y));
            row.add(cellM(st, s -> s.goodwillLast));
            row.add(cellR(st, s -> s.effTaxFirst));
            row.add(cellR(st, s -> s.effTaxLast));
            row.add(st == null || st.effTaxStatus == null ? "" : st.effTaxStatus);
            row.add(st == null || st.allocScorecard == null ? "" : st.allocScorecard);
            row.add(st == null || st.guidanceStatus == null ? "" : st.guidanceStatus);
            row.add(st == null || st.insiderNote == null ? "" : st.insiderNote);
            row.add(st == null || st.story == null ? "" : st.story);
            rows.add(row);
        }

        Path out = root.resolve("dcf_csv/dcf-history.csv");
        writeCsv(out, rows);
        System.out.println();
        System.out.println("wrote " + out.getFileName() + " (" + total
                + " companies, " + flagged + " flagged)");
    }

    /** Trough comparison needs base in USD like the history series. */
    static Json.Obj copyWithBaseUSD(Json.Obj asm, double baseM) {
        Json.Obj c = new Json.Obj();
        for (Map.Entry<String, Json.Val> e : asm.map.entrySet()) c.map.put(e.getKey(), e.getValue());
        c.map.put("base", new Json.Num(baseM * 1e6));
        return c;
    }

    /** Live base fiscal year, from the annual input period or the src note. */
    static int baseYearFor(String ticker, Json.Obj inputs, Json.Obj asm) {
        String text = null;
        Json.Obj co = inputs.getObj(ticker);
        if (co != null) {
            Json.Obj ann = co.getObj("annual");
            if (ann != null) text = ann.getStr("period");
        }
        if (text == null && asm.has("src")) text = asm.getStr("src");
        if (text == null) return -1;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("FY(\\d{4})").matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = java.util.regex.Pattern.compile("year ended \\d{4}-\\d{2}-\\d{2}")
                .matcher(text);
        if (m.find()) return Integer.parseInt(m.group(0).substring(11, 15));
        return -1;
    }

    static String cikFor(String ticker, Json.Obj inputs) {
        Json.Obj co = inputs.getObj(ticker);
        if (co != null && co.has("cik")) {
            Json.Val v = co.get("cik");
            String cik = v instanceof Json.Str s ? s.v
                    : v instanceof Json.Num n ? String.valueOf((long) n.d)
                    : String.valueOf(v);
            while (cik.length() < 10) cik = "0" + cik;
            return cik;
        }
        return CIK_FALLBACK.get(ticker);
    }

    static String readFile(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    static void writeCsv(Path p, List<List<String>> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(',');
                String cell = row.get(i);
                if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
                    sb.append('"').append(cell.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(cell);
                }
            }
            sb.append('\n');
        }
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Self-test (shared SelfTest harness; real companyfacts for AMD/MSFT)
    // ------------------------------------------------------------------

    static void selfTest() throws Exception {
        Path root = workspaceRoot();
        Path amdCf = root.resolve("dcf_csv/backtest/companyfacts/0000002488.json");
        List<XbrlFacts.Fact> amdFacts = XbrlFacts.load(amdCf);
        Series rev = lastN(annualSeries(amdFacts, REV_TAGS));
        Map<Integer, Double> ocfS = annualSeries(amdFacts, OCF_TAGS);
        Map<Integer, Double> capS = annualSeries(amdFacts, CAPEX_TAGS);
        Map<Integer, Double> fcfMap = new LinkedHashMap<>();
        for (int fy : ocfS.keySet()) {
            if (capS.containsKey(fy)) fcfMap.put(fy, ocfS.get(fy) - capS.get(fy));
        }
        Series fcf = lastN(fcfMap);
        Stats st = compute(rev, fcf);

        SelfTest.checkEq("AMD 5 rev years", 5, rev.v.length);
        SelfTest.checkEq("AMD 5 fcf years", 5, fcf.v.length);
        SelfTest.checkEq("AMD first fy", 2021, rev.fy[0]);
        SelfTest.checkEq("AMD last fy", 2025, rev.fy[4]);
        SelfTest.checkEq("AMD FY2025 revenue $M", 34639L, Math.round(rev.v[4] / 1e6));
        SelfTest.checkEq("AMD FY2025 FCF $M", 6735L, Math.round(fcf.v[4] / 1e6));
        SelfTest.checkEq("AMD rev CAGR", "0.2049", pct4(st.revCagr));
        SelfTest.checkEq("AMD FCF CAGR", "0.2026", pct4(st.fcfCagr));
        SelfTest.checkEq("AMD margin min", "0.0494", pct4(st.marginMin));
        SelfTest.checkEq("AMD margin avg", "0.1330", pct4(st.marginAvg));
        SelfTest.checkEq("AMD margin max", "0.1959", pct4(st.marginMax));
        SelfTest.checkEq("AMD best 1-yr FCF growth", "1.8004", pct4(st.bestFcfGrowth));
        SelfTest.checkEq("AMD avg FCF $M", 3319L, Math.round(st.avgFcf / 1e6));

        // Live AMD assumptions: g=0.22 vs FCF CAGR 0.2026 -> flagged; base 6735M
        // USD-scaled vs avg 3319.2M -> not a trough; base year 2025 matches
        // history -> cross-check passes silently.
        Json.Obj liveAmd = asm("FCFF", 0.22, 6735.0 * 1e6, null);
        List<String> fl = flags(liveAmd, st, 2025);
        SelfTest.check("AMD g-vs-FCF-CAGR flag fires",
                fl.stream().anyMatch(s -> s.contains("assumed g 22.0% vs 5-yr FCF CAGR 20.3%")));
        SelfTest.check("AMD no hope-vs-track-record flag (g < 180%)",
                fl.stream().noneMatch(s -> s.contains("hope vs track record")));
        SelfTest.check("AMD no trough flag (base above avg)",
                fl.stream().noneMatch(s -> s.contains("trough year")));

        // Deliberately inflated g gets flagged both ways.
        Json.Obj inflated = asm("FCFF", 2.0, 6735.0 * 1e6, null);
        List<String> fl2 = flags(inflated, st);
        SelfTest.check("inflated g flagged vs FCF CAGR",
                fl2.stream().anyMatch(s -> s.contains("assumed g 200.0% vs 5-yr FCF CAGR 20.3%")));
        SelfTest.check("inflated g flagged hope-vs-track-record",
                fl2.stream().anyMatch(s -> s.contains("above best single-year FCF growth 180.0%")));

        // Trough-year base gets flagged.
        Json.Obj trough = asm("FCFF", 0.10, 1000.0 * 1e6, null);
        List<String> fl3 = flags(trough, st, 2025);
        SelfTest.check("trough base flagged",
                fl3.stream().anyMatch(s -> s.contains("trough year, not normalized")));

        // Base-year cross-check: history FY2025 FCF 6735M vs a wrong live base.
        Json.Obj wrongBase = asm("FCFF", 0.10, 100.0 * 1e6, null);
        List<String> fl3b = flags(wrongBase, st, 2025);
        SelfTest.check("base-year cross-check fires on mismatch",
                fl3b.stream().anyMatch(s -> s.contains("base-year cross-check")));
        SelfTest.check("base-year cross-check silent on match",
                fl.stream().noneMatch(s -> s.contains("base-year cross-check")));
        // Stale series (base year beyond history) -> no cross-check.
        SelfTest.check("no cross-check when series is stale",
                flags(trough, st, 2030).stream()
                        .noneMatch(s -> s.contains("base-year cross-check")));

        // REV kind: end margin above historical peak flagged.
        Json.Obj revAsm = asm("REV", 0.10, 34639.0 * 1e6, 0.25);
        List<String> fl4 = flags(revAsm, st);
        SelfTest.check("REV end-margin vs peak margin flagged",
                fl4.stream().anyMatch(s -> s.contains(
                        "assumed year-5 margin 25.0% vs historical peak FCF margin 19.6%")));
        // REV g below history: no revenue-g flag.
        SelfTest.check("REV g below history not flagged",
                fl4.stream().noneMatch(s -> s.contains("assumed revenue g")));

        // Second real company: MSFT.
        Path msftCf = root.resolve("dcf_csv/backtest/companyfacts/0000789019.json");
        List<XbrlFacts.Fact> msftFacts = XbrlFacts.load(msftCf);
        Stats mst = compute(lastN(annualSeries(msftFacts, REV_TAGS)),
                fcfSeries(msftFacts));
        SelfTest.checkEq("MSFT 5 fcf years", 5, mst.fcf.v.length);
        SelfTest.checkEq("MSFT rev CAGR", "0.1374", pct4(mst.revCagr));
        SelfTest.checkEq("MSFT FCF CAGR", "0.0070", pct4(mst.fcfCagr));
        SelfTest.checkEq("MSFT margin min", "0.2019", pct4(mst.marginMin));
        SelfTest.checkEq("MSFT margin max", "0.3286", pct4(mst.marginMax));
        Json.Obj liveMsft = asm("FCFF", 0.12, 66987.0 * 1e6, null);
        List<String> flm = flags(liveMsft, mst);
        SelfTest.check("MSFT g-vs-FCF-CAGR flag fires",
                flm.stream().anyMatch(s -> s.contains("assumed g 12.0% vs 5-yr FCF CAGR 0.7%")));

        // Missing companyfacts: no crash, insufficient series.
        Stats empty = compute(new Series(), new Series());
        SelfTest.check("empty series -> null CAGRs",
                empty.revCagr == null && empty.fcfCagr == null);
        SelfTest.check("empty series -> no flags", flags(asm("FCFF", 0.5, 1e9, null), empty).isEmpty());

        // --- extended history: capex intensity, debt, shares, ROIC, margins,
        // R&D, payout, coverage, SBC, story (all values cross-checked against
        // raw companyfacts with an independent probe, 2026-09-28) ---
        Stats orcl = enriched("0001341439", "ORCL");
        SelfTest.checkEq("ORCL capex intensity FY2022", "0.1063", pct4(orcl.intensFirst));
        SelfTest.checkEq("ORCL capex intensity FY2026", "0.8264", pct4(orcl.intensLast));
        SelfTest.checkEq("ORCL capex FY2026 $M", 55663L, Math.round(orcl.capLast / 1e6));
        SelfTest.checkEq("ORCL capex trend", "rising", orcl.intensTrend);
        SelfTest.check("ORCL heavy-capex buildout", orcl.heavyCapexBuildout);
        // Debt uses the combined total ORCL actually reports (~$130B, not the
        // $7B DebtCurrent stub).
        SelfTest.checkEq("ORCL debt FY2022 $M", 75859L, Math.round(orcl.debtFirst / 1e6));
        SelfTest.checkEq("ORCL debt FY2026 $M", 129541L, Math.round(orcl.debtLast / 1e6));
        SelfTest.checkEq("ORCL debt change", "0.7077", pct4(orcl.debtChange));
        SelfTest.checkEq("ORCL ROIC first", "0.1185", pct4(orcl.roicFirst));
        SelfTest.checkEq("ORCL ROIC last", "0.1091", pct4(orcl.roicLast));
        SelfTest.check("ORCL ROIC not deteriorating (10.9% above 80% of 11.8%)",
                !orcl.roicDeteriorating);
        SelfTest.checkEq("ORCL coverage FY2026", "4.48",
                String.format("%.2f", orcl.covLast));
        List<String> orclFlags = flags(asm("FCFF", 0.10, 18000.0 * 1e6, null), orcl, 2026);
        SelfTest.check("ORCL heavy-capex flag fires",
                orclFlags.stream().anyMatch(s -> s.contains("heavy-capex buildout")));
        SelfTest.check("ORCL debt-funded growth flag fires",
                orclFlags.stream().anyMatch(s -> s.contains("debt-funded growth: total debt up 70.8%")));
        SelfTest.check("ORCL no low-coverage flag",
                orclFlags.stream().noneMatch(s -> s.contains("below 3x")));
        SelfTest.check("ORCL story has capex figures", orcl.story.contains("capex $4.5B"));
        SelfTest.check("ORCL story has debt figures", orcl.story.contains("debt $75.9B"));
        SelfTest.check("ORCL story has ROIC", orcl.story.contains("ROIC 11.8%"));
        SelfTest.check("ORCL story year range", orcl.story.startsWith("FY2022"));

        // SOFI: real dilution (137.6%) driven by SBC (66.6% of revenue).
        Stats sofi = enriched("0001818874", "SOFI");
        SelfTest.checkEq("SOFI shares change", "1.3765", pct4(sofi.sharesChange));
        SelfTest.checkEq("SOFI SBC avg", "0.6664", pct4(sofi.sbcAvg));
        List<String> sofiFlags = flags(asm("FCFE", 0.20, 481.0 * 1e6, null), sofi, 2025);
        SelfTest.check("SOFI dilution flag fires",
                sofiFlags.stream().anyMatch(s -> s.contains("shares diluted 137.6%")));
        SelfTest.check("SOFI buybacks-offset flag fires",
                sofiFlags.stream().anyMatch(s -> s.contains("issuance offset buybacks")));
        SelfTest.check("SOFI SBC-driven flag fires",
                sofiFlags.stream().anyMatch(s -> s.contains("SBC averaged 66.6%")));

        // AMD: sustained margin compression + rising R&D intensity.
        Stats amdX = enriched("0000002488", "AMD");
        SelfTest.checkEq("AMD margin trend", "compressing", amdX.marginTrend);
        SelfTest.checkEq("AMD op margin first", "0.2220", pct4(amdX.opMFirst));
        SelfTest.checkEq("AMD op margin last", "0.1066", pct4(amdX.opMLast));
        List<String> amdXFlags = flags(asm("FCFF", 0.22, 6735.0 * 1e6, null), amdX, 2025);
        SelfTest.check("AMD margin compression flag fires",
                amdXFlags.stream().anyMatch(s -> s.contains("sustained operating-margin compression 22.2%")));
        SelfTest.check("AMD R&D rising flag fires",
                amdXFlags.stream().anyMatch(s -> s.contains("R&D intensity rising")));
        SelfTest.check("AMD ROIC deteriorating flag fires",
                amdXFlags.stream().anyMatch(s -> s.contains("ROIC deteriorating")));

        // NVDA: splits are cosmetic — split-adjusted shares fell 1.7%, so no
        // dilution flag despite the raw 40x split-driven count.
        Stats nvda = enriched("0001045810", "NVDA");
        SelfTest.check("NVDA shares split-adjusted", nvda.sharesSplitAdjusted);
        SelfTest.checkEq("NVDA split-adj shares change", "-0.0171", pct4(nvda.sharesChange));
        SelfTest.checkEq("NVDA split factor pre-2024-split", 10.0, splitFactor("NVDA", "2024-01-28"));
        SelfTest.checkEq("NVDA split factor post-split", 1.0, splitFactor("NVDA", "2026-01-25"));
        SelfTest.checkEq("NVDA margin trend", "expanding", nvda.marginTrend);
        SelfTest.check("NVDA no dilution flag (split-adjusted)",
                flags(asm("FCFF", 0.15, 96618.0 * 1e6, null), nvda, 2026).stream()
                        .noneMatch(s -> s.contains("shares diluted")));

        // AMZN: trough year classified with capex-intensity facts.
        Stats amzn = enriched("0001018724", "AMZN");
        List<String> amznFlags = flags(asm("FCFF", 0.18, 7695.0 * 1e6, null), amzn, 2025);
        SelfTest.check("AMZN trough classified with capex facts",
                amznFlags.stream().anyMatch(s -> s.contains("trough year, not normalized (capex intensity")));

        // JPM (bank): ROIC / coverage honestly labeled not-comparable, never
        // forced; debt/shares/payout series still reported.
        Stats jpm = enriched("0000019617", "JPM");
        SelfTest.check("JPM ROIC not-comparable",
                jpm.roicStatus != null && jpm.roicStatus.startsWith("not-comparable"));
        SelfTest.check("JPM coverage not-comparable",
                jpm.covStatus != null && jpm.covStatus.startsWith("not-comparable"));
        SelfTest.check("JPM story labels ROIC not-comparable",
                jpm.story.contains("ROIC not-comparable"));
        SelfTest.check("JPM story still has debt series", jpm.story.contains("debt $"));
        SelfTest.check("JPM no ROIC flag",
                flags(asm("FCFE", 0.06, 6.0 * 1e6, null), jpm, 2025).stream()
                        .noneMatch(s -> s.contains("ROIC")));

        // AVGO: serial acquirer ($26.3B M&A, 4 of 5 yrs), structurally low tax
        // (4.0%→18.0% vs 21% statutory), insider sells vs $22.5B buybacks.
        Stats avgo = enriched("0001730168", "AVGO");
        SelfTest.checkEq("AVGO M&A 5y $M", 26285L, Math.round(avgo.acq5y / 1e6));
        SelfTest.checkEq("AVGO M&A years", 4, avgo.acqYears);
        SelfTest.checkEq("AVGO goodwill $M", 97801L, Math.round(avgo.goodwillLast / 1e6));
        SelfTest.checkEq("AVGO eff tax first", "0.0399", pct4(avgo.effTaxFirst));
        SelfTest.checkEq("AVGO eff tax last", "0.1803", pct4(avgo.effTaxLast));
        SelfTest.checkEq("AVGO insider buys", 1, avgo.insiderBuys);
        SelfTest.checkEq("AVGO insider sells", 2, avgo.insiderSells);
        List<String> avgoFlags = flags(asm("FCFF", 0.12, 10000.0 * 1e6, null), avgo, 2025);
        SelfTest.check("AVGO serial-acquirer flag fires",
                avgoFlags.stream().anyMatch(s -> s.contains("serial acquirer: $26.3B M&A over 5y")));
        SelfTest.check("AVGO insider-sell vs buyback flag fires",
                avgoFlags.stream().anyMatch(s -> s.contains("insider sells recorded")));
        SelfTest.check("AVGO story has allocation scorecard",
                avgo.story.contains("capital allocation: sources $120.8B"));
        SelfTest.check("AVGO story has M&A clause",
                avgo.story.contains("M&A $26.3B over 5y (4 of 5 yrs)"));
        SelfTest.check("AVGO story has effective tax",
                avgo.story.contains("effective tax 4.0%"));
        SelfTest.check("AVGO story has guidance pending",
                avgo.story.contains("guidance track record: pending"));
        SelfTest.check("AVGO story has insider note",
                avgo.story.contains("1 buy / 2 sell mention(s)"));

        // ORCL: $27.9B M&A (VMware-era buildout), allocation shows the debt
        // story in one line — $1.2B FCF + $53.7B debt raised.
        Stats orcl2 = enriched("0001341439", "ORCL");
        SelfTest.checkEq("ORCL M&A 5y $M", 27932L, Math.round(orcl2.acq5y / 1e6));
        SelfTest.check("ORCL serial-acquirer flag fires",
                flags(asm("FCFF", 0.10, 18000.0 * 1e6, null), orcl2, 2026).stream()
                        .anyMatch(s -> s.contains("serial acquirer: $27.9B M&A over 5y")));
        SelfTest.check("ORCL allocation names debt raised",
                orcl2.allocScorecard.contains("net debt raised $53.7B"));
        SelfTest.check("ORCL eff tax honestly insufficient",
                orcl2.effTaxStatus != null && orcl2.effTaxStatus.startsWith("insufficient"));

        // AMD: no cash M&A (Xilinx was all-stock) but $25.1B goodwill on books;
        // effective tax 1.9%→8.2% fires the low-tax flag.
        Stats amd2 = enriched("0000002488", "AMD");
        SelfTest.check("AMD acq5y null (all-stock deals leave no cash trace)",
                amd2.acq5y == null);
        SelfTest.checkEq("AMD goodwill $M", 25126L, Math.round(amd2.goodwillLast / 1e6));
        SelfTest.check("AMD story notes stock-deal goodwill",
                amd2.story.contains("M&A cash n/a; goodwill $25.1B on books"));
        SelfTest.checkEq("AMD eff tax last", "0.0819", pct4(amd2.effTaxLast));
        SelfTest.check("AMD low-tax flag fires",
                flags(asm("FCFF", 0.22, 6735.0 * 1e6, null), amd2, 2025).stream()
                        .anyMatch(s -> s.contains("effective tax rate ~8.2% vs 21% statutory")));

        SelfTest.finish();
    }

    /** Builds a fully enriched Stats for one companyfacts file (test helper). */
    static Stats enriched(String cik, String ticker) throws Exception {
        Path cf = workspaceRoot().resolve("dcf_csv/backtest/companyfacts/" + cik + ".json");
        List<XbrlFacts.Fact> facts = XbrlFacts.load(cf);
        Maps maps = pullMaps(facts);
        Series rev = lastN(maps.rev);
        Map<Integer, Double> fcfMap = new LinkedHashMap<>();
        for (int fy : maps.ocf.keySet()) {
            if (maps.cap.containsKey(fy)) fcfMap.put(fy, maps.ocf.get(fy) - maps.cap.get(fy));
        }
        Stats s = compute(rev, lastN(fcfMap));
        enrich(maps, s, ticker);
        return s;
    }

    /** Synthetic assumptions object for tests: base in USD. */
    static Json.Obj asm(String kind, double g, double baseUsd, Double endMargin) {
        Json.Obj o = new Json.Obj();
        o.map.put("kind", new Json.Str(kind));
        o.map.put("g", new Json.Num(g));
        o.map.put("base", new Json.Num(baseUsd));
        if (endMargin != null) o.map.put("end_margin", new Json.Num(endMargin));
        return o;
    }

    static Series fcfSeries(List<XbrlFacts.Fact> facts) {
        Map<Integer, Double> ocfS = annualSeries(facts, OCF_TAGS);
        Map<Integer, Double> capS = annualSeries(facts, CAPEX_TAGS);
        Map<Integer, Double> fcfMap = new LinkedHashMap<>();
        for (int fy : ocfS.keySet()) {
            if (capS.containsKey(fy)) fcfMap.put(fy, ocfS.get(fy) - capS.get(fy));
        }
        return lastN(fcfMap);
    }

    // ------------------------------------------------------------------
    // Minimal typed JSON (objects -> Obj, arrays -> ArrayList).
    // ------------------------------------------------------------------

    static final class Json {
        abstract static class Val {}
        static final class Obj extends Val {
            final Map<String, Val> map = new LinkedHashMap<>();
            boolean has(String k) { return map.containsKey(k); }
            Val get(String k) { return map.get(k); }
            String getStr(String k) {
                Val v = map.get(k);
                return v instanceof Str s ? s.v : null;
            }
            Obj getObj(String k) {
                Val v = map.get(k);
                return v instanceof Obj o ? o : null;
            }
            @SuppressWarnings("unchecked")
            List<Val> getList(String k) {
                Val v = map.get(k);
                return v instanceof Arr a ? a.items : List.of();
            }
        }
        static final class Arr extends Val {
            final List<Val> items = new ArrayList<>();
        }
        static final class Str extends Val {
            final String v;
            Str(String v) { this.v = v; }
        }
        static final class Num extends Val {
            final double d;
            Num(double d) { this.d = d; }
        }
        static final class Bool extends Val {
            final boolean v;
            Bool(boolean v) { this.v = v; }
        }
        static final class Null extends Val {
            static final Null INSTANCE = new Null();
        }

        static Val parse(String text) {
            Parser p = new Parser(text);
            Val v = p.value();
            p.ws();
            if (p.pos != text.length()) throw new IllegalArgumentException("trailing data");
            return v;
        }

        private static final class Parser {
            final String t;
            int pos;
            Parser(String t) { this.t = t; }
            void ws() {
                while (pos < t.length() && " \t\n\r".indexOf(t.charAt(pos)) >= 0) pos++;
            }
            Val value() {
                ws();
                if (pos >= t.length()) throw new IllegalArgumentException("unexpected end");
                char c = t.charAt(pos);
                return switch (c) {
                    case '{' -> obj();
                    case '[' -> arr();
                    case '"' -> new Str(str());
                    case 't' -> { lit("true"); yield new Bool(true); }
                    case 'f' -> { lit("false"); yield new Bool(false); }
                    case 'n' -> { lit("null"); yield Null.INSTANCE; }
                    default -> new Num(num());
                };
            }
            void lit(String s) {
                if (!t.startsWith(s, pos)) throw new IllegalArgumentException("bad literal");
                pos += s.length();
            }
            Obj obj() {
                pos++;
                Obj o = new Obj();
                ws();
                if (peek() == '}') { pos++; return o; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (peek() != ':') throw new IllegalArgumentException("expected :");
                    pos++;
                    o.map.put(k, value());
                    ws();
                    char c = peek();
                    if (c == ',') { pos++; continue; }
                    if (c == '}') { pos++; return o; }
                    throw new IllegalArgumentException("expected , or }");
                }
            }
            Arr arr() {
                pos++;
                Arr a = new Arr();
                ws();
                if (peek() == ']') { pos++; return a; }
                while (true) {
                    a.items.add(value());
                    ws();
                    char c = peek();
                    if (c == ',') { pos++; continue; }
                    if (c == ']') { pos++; return a; }
                    throw new IllegalArgumentException("expected , or ]");
                }
            }
            char peek() {
                if (pos >= t.length()) throw new IllegalArgumentException("unexpected end");
                return t.charAt(pos);
            }
            String str() {
                pos++;
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (pos >= t.length()) throw new IllegalArgumentException("unterminated string");
                    char c = t.charAt(pos++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        char e = t.charAt(pos++);
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
                                sb.append((char) Integer.parseInt(t.substring(pos, pos + 4), 16));
                                pos += 4;
                                break;
                            default: throw new IllegalArgumentException("bad escape");
                        }
                    } else sb.append(c);
                }
            }
            double num() {
                int s = pos;
                while (pos < t.length() && "-+0123456789.eE".indexOf(t.charAt(pos)) >= 0) pos++;
                return Double.parseDouble(t.substring(s, pos));
            }
        }
    }
}
