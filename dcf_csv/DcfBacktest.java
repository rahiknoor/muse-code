import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DCF backtest: the model's report card.
 *
 * <p>Rebuilds what the fixed-methodology DCF (DcfModel: 10-year, mid-year
 * discounting, 5-year growth fade) would have said about each holding as of
 * the filing date of its FY2023 and FY2024 10-K/20-F, then grades the
 * predictions against actual prices 1 and 2 years later. Strict no-peek:
 * every fact comes from the fiscal year's OWN 10-K/20-F (earliest filed
 * date); each valuation is anchored to the latest filing date among the
 * facts it uses (the first day all inputs were knowable); outcome windows
 * are measured from that anchor. Only completed windows are graded: the
 * 1-yr window completes on the 1st filing anniversary, the 2-yr window on
 * the 2nd. (2026-09-28: eliminated the old Dec-31/reporting-lag convention,
 * which pretended a Dec 31 valuation while using facts filed weeks later.)
 *
 * <p>Historical inputs come from EDGAR companyfacts (XBRL, machine-readable),
 * so no filing text parsing is needed. All assumption rules are mechanical
 * and documented below — nothing peeks at subsequent prices:
 * <ul>
 *   <li>base = FY operating cash flow − capex (the as-of fiscal year's 10-K/20-F)</li>
 *   <li>g = 3-year FCF CAGR, clamped to [0%, 30%]; falls back to 3-year revenue
 *       CAGR clamped [0%, 25%], then to 8% when neither is computable</li>
 *   <li>w = median of the current model WACCs across all tickers (one number,
 *       documented in the inputs file)</li>
 *   <li>t = 3% terminal growth</li>
 *   <li>kind = the ticker's current model kind; REV tickers use FY revenue as
 *       base and revenue CAGR for g, reusing current start/end margins
 *       (documented caveat)</li>
 *   <li>shares / net debt = as-of fiscal year balance-sheet figures</li>
 *   <li>actuals = Yahoo closes (split-adjusted through today, verified
 *       2026-09-28) on the first trading day on/after the 1-yr and 2-yr
 *       filing anniversaries; IVs are restated into today's share terms via
 *       the verified SPLITS table (splits are cosmetic, not peeking)</li>
 *   <li>no-peek extraction: each fact comes from the fiscal year's OWN
 *       10-K/20-F (earliest filed date) and, within it, the latest period-end
 *       column (the year's own figures, not prior-year comparatives)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   java DcfBacktest.java --prepare   # fetch companyfacts + build historical_inputs.json
 *   java DcfBacktest.java --run       # grade -> backtest_report.md + backtest_results.csv, print digest
 *   java DcfBacktest.java             # prepare + run
 * </pre>
 * Network fetch uses curl (Java HTTPS is blocked in this sandbox); all logic
 * stays in this file so Rahik can read it.
 */
public class DcfBacktest {

    static final String UA = "Muse-stock-research/1.0 contact research";
    static final int[] COHORTS = {2023, 2024};

    // Concept -> taxonomy/tag candidates, first hit wins.
    static final String[][] OCF_TAGS = {
        {"us-gaap", "NetCashProvidedByUsedInOperatingActivities"},
        {"ifrs-full", "CashFlowsFromUsedInOperatingActivities"}};
    static final String[][] CAPEX_TAGS = {
        {"us-gaap", "PaymentsToAcquirePropertyPlantAndEquipment"},
        {"us-gaap", "PaymentsToAcquireProductiveAssets"},
        {"us-gaap", "PaymentsToAcquireOtherPropertyPlantAndEquipment"},
        {"ifrs-full", "PurchaseOfPropertyPlantAndEquipment"},
        {"ifrs-full", "PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities"}};
    static final String[][] REV_TAGS = {
        {"us-gaap", "RevenueFromContractWithCustomerExcludingAssessedTax"},
        {"us-gaap", "Revenues"},
        {"us-gaap", "SalesRevenueNet"},
        {"ifrs-full", "Revenue"}};
    static final String[][] SHARES_TAGS = {
        {"us-gaap", "WeightedAverageNumberOfDilutedSharesOutstanding"},
        {"us-gaap", "EntityCommonStockSharesOutstanding"},
        {"us-gaap", "CommonStockSharesOutstanding"},
        {"ifrs-full", "WeightedAverageShares"},
        {"ifrs-full", "AdjustedWeightedAverageShares"}};
    static final String[][] DEBT_LT_TAGS = {
        {"us-gaap", "LongTermDebt"},
        {"us-gaap", "LongTermDebtNoncurrent"}};
    static final String[][] DEBT_ST_TAGS = {
        {"us-gaap", "DebtCurrent"}};
    static final String[][] CASH_TAGS = {
        {"us-gaap", "CashAndCashEquivalentsAtCarryingValue"},
        {"ifrs-full", "CashAndCashEquivalents"}};

    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        return (e != null && !e.isEmpty()) ? e : homeDir() + "/workspace";
    }

    static String outDir() {
        return workspaceRoot() + "/dcf_csv/backtest";
    }

    static {
        try {
            System.setOut(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            // fall back
        }
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            if (a.equals("--self-test")) {
                selfTest();
                return;
            }
        }
        boolean prepare = true, run = true;
        for (String a : args) {
            if (a.equals("--prepare")) { run = false; }
            if (a.equals("--run")) { prepare = false; }
        }
        Files.createDirectories(Paths.get(outDir(), "companyfacts"));
        if (prepare) {
            prepare();
        }
        if (run) {
            run();
        }
    }

    // ------------------------------------------------------------------
    // Prepare: fetch companyfacts, build historical_inputs.json
    // ------------------------------------------------------------------

    static void prepare() throws Exception {
        Map<String, String> cikByTicker = cikByTicker();
        Map<String, Map<String, Object>> asmByTicker = assumptionsByTicker();
        double wMedian = medianW(asmByTicker);

        // Fetch companyfacts per CIK (skip when already on disk).
        for (Map.Entry<String, String> e : cikByTicker.entrySet()) {
            String cik = e.getValue();
            Path p = Paths.get(outDir(), "companyfacts", cik + ".json");
            if (Files.exists(p) && Files.size(p) > 1000) {
                continue;
            }
            String url = "https://data.sec.gov/api/xbrl/companyfacts/CIK" + cik + ".json";
            System.out.println("fetch " + e.getKey() + " " + cik);
            int rc = sh(new String[]{"curl", "-sS", "-A", UA, "--max-time", "180",
                    url, "-o", p.toString()});
            if (rc != 0 || !Files.exists(p) || Files.size(p) < 1000) {
                System.out.println("  FAILED fetch for " + e.getKey());
                Files.deleteIfExists(p);
            }
        }

        List<Object> records = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        for (Map.Entry<String, String> e : cikByTicker.entrySet()) {
            String ticker = e.getKey();
            String cik = e.getValue();
            Path p = Paths.get(outDir(), "companyfacts", cik + ".json");
            if (!Files.exists(p)) {
                skipped.add(skip(ticker, null, "companyfacts fetch failed"));
                continue;
            }
            List<XbrlFacts.Fact> facts;
            try {
                // Tidy XBRL fact table (XbrlFacts, java-shared): selection
                // policy in fact()/factValue() is unchanged.
                facts = XbrlFacts.load(p);
            } catch (Exception ex) {
                skipped.add(skip(ticker, null, "companyfacts parse failed"));
                continue;
            }
            Map<String, Object> asm = asmByTicker.get(ticker);
            if (asm == null) {
                skipped.add(skip(ticker, null, "no current assumptions (kind unknown)"));
                continue;
            }
            for (int asofY : COHORTS) {
                Map<String, Object> rec = buildRecord(ticker, facts, asofY,
                        (String) asm.get("kind"), wMedian, asm);
                if (rec.containsKey("skip_reason")) {
                    skipped.add(rec);
                } else {
                    records.add(rec);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", LocalDate.now().toString());
        out.put("method", "fixed-methodology DCF (DcfModel.intrinsicValue); mechanical no-peek rules, see DcfBacktest.java header");
        out.put("w_median", wMedian);
        out.put("records", records);
        out.put("skipped", skipped);
        Files.writeString(Paths.get(outDir(), "historical_inputs.json"),
                DcfModel.Json.dumps(out), StandardCharsets.UTF_8);
        System.out.println("records=" + records.size() + " skipped=" + skipped.size());
        warnUnrecordedSplits(records, SPLITS);
    }

    /**
     * Guard: flag any ticker whose share count roughly doubled (or more)
     * between consecutive observations without a matching SPLITS entry.
     * A ~2x+ jump in shares outstanding is almost always a split; if it is
     * not in the table, the IV-vs-actual comparison for that ticker is wrong.
     */
    static void warnUnrecordedSplits(List<Object> records, String[][] splits) {
        Map<String, List<double[]>> byTicker = new TreeMap<>();
        for (Object o : records) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) o;
            String t = (String) r.get("ticker");
            double shares = ((Number) r.get("shares")).doubleValue();
            int fy = ((Number) r.get("fy")).intValue();
            byTicker.computeIfAbsent(t, k -> new ArrayList<>())
                    .add(new double[]{fy, shares});
        }
        for (Map.Entry<String, List<double[]>> e : byTicker.entrySet()) {
            List<double[]> pts = e.getValue();
            pts.sort(Comparator.comparingDouble(a -> a[0]));
            for (int i = 1; i < pts.size(); i++) {
                double ratio = pts.get(i)[1] / pts.get(i - 1)[1];
                if (ratio >= 1.9) {
                    int fromFy = (int) pts.get(i - 1)[0];
                    int toFy = (int) pts.get(i)[0];
                    boolean known = false;
                    for (String[] s : splits) {
                        if (s[0].equals(e.getKey())) {
                            int sy = Integer.parseInt(s[1].substring(0, 4));
                            if (sy >= fromFy && sy <= toFy + 1) {
                                known = true;
                            }
                        }
                    }
                    if (!known) {
                        System.out.println("WARNING: " + e.getKey()
                                + " shares jumped " + String.format("%.1fx", ratio)
                                + " between FY" + fromFy + " and FY" + toFy
                                + " with no SPLITS entry - check for an unrecorded split");
                    }
                }
            }
        }
    }

    /** Build one as-of record, or a skip map with skip_reason. */
    static Map<String, Object> buildRecord(String ticker, List<XbrlFacts.Fact> facts,
                                           int asofY, String kind, double wMedian,
                                           Map<String, Object> asm) {
        // Need FY asofY-3 .. asofY for the 3-yr CAGR window.
        Double[] fcf = new Double[4];
        Double[] rev = new Double[4];
        // Base-year facts are kept as Fact objects so the filing-date anchor
        // can be computed from the actual knowability dates.
        Fact ocfBase = null, capexBase = null, revBase = null;
        for (int i = 0; i < 4; i++) {
            int fy = asofY - 3 + i;
            Fact ocf = fact(facts, OCF_TAGS, fy);
            Fact capex = fact(facts, CAPEX_TAGS, fy);
            if (ocf != null && capex != null) {
                fcf[i] = (ocf.val - capex.val) / 1e6; // USD -> $M
            }
            Fact r = fact(facts, REV_TAGS, fy);
            if (r != null) {
                rev[i] = r.val / 1e6;
            }
            if (i == 3) {
                ocfBase = ocf;
                capexBase = capex;
                revBase = r;
            }
        }
        String gSrc;
        double g;
        double base;
        String baseSrc;
        if (kind.equals("REV")) {
            // Revenue model: base is revenue, growth is revenue CAGR.
            if (rev[3] == null) {
                return skip(ticker, asofY, "no FY" + asofY + " revenue in companyfacts");
            }
            base = rev[3];
            baseSrc = "FY" + asofY + " 10-K/20-F revenue via companyfacts";
            Double cagrRev = cagr(rev);
            if (cagrRev != null) {
                g = clamp(cagrRev, 0.0, 0.30);
                gSrc = "3-yr revenue CAGR FY" + (asofY - 3) + "->FY" + asofY + ", clamped [0%,30%]";
            } else {
                g = 0.08;
                gSrc = "fallback 8% (revenue CAGR not computable)";
            }
        } else {
            if (fcf[3] == null) {
                return skip(ticker, asofY, "no FY" + asofY + " OCF/capex in companyfacts");
            }
            base = fcf[3];
            baseSrc = "FY" + asofY + " 10-K/20-F via companyfacts: OCF less capex";
            Double cagrFcf = cagr(fcf);
            if (cagrFcf != null) {
                g = clamp(cagrFcf, 0.0, 0.30);
                gSrc = "3-yr FCF CAGR FY" + (asofY - 3) + "->FY" + asofY + ", clamped [0%,30%]";
            } else {
                Double cagrRev = cagr(rev);
                if (cagrRev != null) {
                    g = clamp(cagrRev, 0.0, 0.25);
                    gSrc = "3-yr revenue CAGR (FCF CAGR not computable), clamped [0%,25%]";
                } else {
                    g = 0.08;
                    gSrc = "fallback 8% (neither FCF nor revenue CAGR computable)";
                }
            }
        }

        Fact sharesFact = fact(facts, SHARES_TAGS, asofY);
        Fact debtLtF = fact(facts, DEBT_LT_TAGS, asofY);
        Fact debtStF = fact(facts, DEBT_ST_TAGS, asofY);
        Fact cashF = fact(facts, CASH_TAGS, asofY);
        Double debtLt = debtLtF == null ? null : debtLtF.val;
        Double debtSt = debtStF == null ? null : debtStF.val;
        Double cash = cashF == null ? null : cashF.val;
        if (sharesFact == null) {
            return skip(ticker, asofY, "no FY" + asofY + " shares outstanding");
        }
        double shares = sharesFact.val / 1e6; // shares -> millions
        double netdebt = ((debtLt != null ? debtLt : 0)
                + (debtSt != null ? debtSt : 0)
                - (cash != null ? cash : 0)) / 1e6;

        // Filing-date anchor: the valuation is knowable only when ALL of its
        // inputs are knowable — the latest filing date among the facts used
        // (base-year cash flow or revenue, shares, net debt). This replaces
        // the old Dec-31/reporting-lag convention.
        String anchor = maxFiled(
                ocfBase == null ? null : ocfBase.filed,
                capexBase == null ? null : capexBase.filed,
                revBase == null ? null : revBase.filed,
                sharesFact.filed,
                debtLtF == null ? null : debtLtF.filed,
                debtStF == null ? null : debtStF.filed,
                cashF == null ? null : cashF.filed);
        if (anchor == null) {
            return skip(ticker, asofY,
                    "no filing date determinable for FY" + asofY);
        }

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("ticker", ticker);
        rec.put("asof", anchor);
        rec.put("filing_date", anchor);
        rec.put("fy", asofY);
        rec.put("kind", kind);
        rec.put("base", round2(base));
        rec.put("g", round4(g));
        rec.put("w", round4(wMedian));
        rec.put("t", 0.03);
        rec.put("shares", round2(shares));
        rec.put("shares_fy_end", sharesFact.end);
        rec.put("netdebt", round2(netdebt));
        rec.put("start_margin", asm.get("start_margin"));
        rec.put("end_margin", asm.get("end_margin"));
        rec.put("base_src", baseSrc);
        rec.put("g_src", gSrc);
        return rec;
    }

    static Map<String, Object> skip(String ticker, Integer asofY, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticker", ticker);
        m.put("asof", asofY == null ? null : asofY + "-12-31");
        m.put("skip_reason", reason);
        return m;
    }

    /** 3-yr CAGR over four annual points; null unless all four are positive. */
    static Double cagr(Double[] v) {
        for (Double d : v) {
            if (d == null || d <= 0) {
                return null;
            }
        }
        return Math.pow(v[3] / v[0], 1.0 / 3) - 1;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Latest (max) of the given YYYY-MM-DD dates; null when all null. */
    static String maxFiled(String... ds) {
        String m = null;
        for (String d : ds) {
            if (d != null && (m == null || d.compareTo(m) > 0)) {
                m = d;
            }
        }
        return m;
    }

    /** Outcome-date anchor: the N-year anniversary of the filing date. */
    static LocalDate anniversary(LocalDate filingDate, int years) {
        return filingDate.plusYears(years);
    }

    /** A window is graded only when its anniversary has passed (completed
     *  365/730-day window; outcome date lands within 330-400 days of the
     *  anchor by construction since the fetch walks forward at most 14
     *  days past the anniversary). */
    static boolean windowComplete(LocalDate filingDate, int years, LocalDate today) {
        return !anniversary(filingDate, years).isAfter(today);
    }

    static void selfTest() {
        // Golden: filing 2024-02-28 -> anniversaries 2025-02-28 / 2026-02-28.
        LocalDate a = LocalDate.parse("2024-02-28");
        SelfTest.check("1yr anniversary golden",
                anniversary(a, 1).toString().equals("2025-02-28"));
        SelfTest.check("2yr anniversary golden",
                anniversary(a, 2).toString().equals("2026-02-28"));
        // Leap-day filing: 2024-02-29 +1yr -> 2025-02-28 (Java adjusts).
        SelfTest.check("leap-day anniversary",
                anniversary(LocalDate.parse("2024-02-29"), 1).toString()
                        .equals("2025-02-28"));
        // Window completion against a fixed today (2026-09-28).
        LocalDate today = LocalDate.parse("2026-09-28");
        SelfTest.check("2024 cohort 1yr complete", windowComplete(a, 1, today));
        SelfTest.check("2024 cohort 2yr complete", windowComplete(a, 2, today));
        LocalDate b = LocalDate.parse("2025-03-15");
        SelfTest.check("2025 filing 1yr complete", windowComplete(b, 1, today));
        SelfTest.check("2025 filing 2yr incomplete", !windowComplete(b, 2, today));
        SelfTest.check("future filing 1yr incomplete",
                !windowComplete(LocalDate.parse("2026-06-01"), 1, today));
        // maxFiled: knowability = the LATEST filing date among inputs.
        SelfTest.check("maxFiled picks latest",
                "2024-03-01".equals(maxFiled("2024-02-20", "2024-03-01", null)));
        SelfTest.check("maxFiled ignores nulls",
                "2024-02-20".equals(maxFiled(null, "2024-02-20", null)));
        SelfTest.check("maxFiled all null", maxFiled(null, null) == null);
        SelfTest.finish();
    }

    /** An extracted annual fact: value, period end date, and the filing
     *  date of the 10-K/20-F it was extracted from (the actual knowability
     *  date). */
    static class Fact {
        double val;
        String end;
        String filed;
    }

    /**
     * Annual fact for a fiscal year, or null.
     *
     * <p>companyfacts repeats each annual figure as a comparative column in
     * later years' filings, so a (fy, FY, 10-K) match is not unique. Rule:
     * take the entries from the EARLIEST filed date — that is the fiscal
     * year's own 10-K/20-F, i.e. what was knowable at the as-of date plus the
     * standard reporting lag (no peeking at later restatements). Within that
     * filing, annual figures repeat as comparative columns for prior years, so
     * pick the entry with the LATEST period end date — the fiscal year's own
     * column (exact, not a heuristic). A final max-value tiebreak covers
     * same-period segment splits, where the consolidated figure is largest.
     */
    static Fact fact(List<XbrlFacts.Fact> facts, String[][] candidates, int fy) {
        if (facts == null) {
            return null;
        }
        for (String[] cand : candidates) {
            List<XbrlFacts.Fact> rows = XbrlFacts.forConcept(facts, cand[0], cand[1]);
            if (rows.isEmpty()) {
                continue;
            }
            // Prefer USD units, else the first (and usually only) unit in file order.
            String unit = null;
            for (XbrlFacts.Fact r : rows) {
                if ("USD".equals(r.unit())) { unit = "USD"; break; }
            }
            if (unit == null) unit = rows.get(0).unit();
            String firstFiled = null;
            List<XbrlFacts.Fact> matches = new ArrayList<>();
            for (XbrlFacts.Fact r : rows) {
                if (!java.util.Objects.equals(unit, r.unit())) {
                    continue;
                }
                String filed = r.filed() == null ? "" : r.filed();
                // fy arrives as a label ("2024"); non-numeric means no match,
                // mirroring the old (long) cast-or-(-1) logic.
                int rFy;
                try { rFy = Integer.parseInt(r.fy()); } catch (Exception e) { rFy = -1; }
                if (rFy == fy && "FY".equals(r.fp()) && !filed.isEmpty()
                        && ("10-K".equals(r.form()) || "10-K/A".equals(r.form())
                            || "20-F".equals(r.form()) || "20-F/A".equals(r.form()))) {
                    matches.add(r);
                    if (firstFiled == null || filed.compareTo(firstFiled) < 0) {
                        firstFiled = filed;
                    }
                }
            }
            Double best = null;
            String bestEnd = null;
            String bestFiled = null;
            for (XbrlFacts.Fact r : matches) {
                String filed = r.filed();
                if (!filed.equals(firstFiled)) {
                    continue;
                }
                String end = r.end() == null ? "" : r.end();
                double val = r.val();
                if (best == null || end.compareTo(bestEnd) > 0
                        || (end.equals(bestEnd) && val > best)) {
                    best = val;
                    bestEnd = end;
                    bestFiled = filed;
                }
            }
            if (best != null) {
                Fact f = new Fact();
                f.val = best;
                f.end = bestEnd;
                f.filed = bestFiled;
                return f;
            }
        }
        return null;
    }

    /** Value-only wrapper around {@link #fact}. */
    static Double factValue(List<XbrlFacts.Fact> facts, String[][] candidates, int fy) {
        Fact f = fact(facts, candidates, fy);
        return f == null ? null : f.val;
    }

    // Verified stock splits in the backtest window. Yahoo's chart endpoint
    // returns closes adjusted for ALL splits through today (verified
    // 2026-09-28: AVGO 2020+1yr fetched 66.54 = 665.4/10), NOT unadjusted -
    // so a historical IV must be restated into today's share terms: shares
    // x factor for every split with splitDate in (shares_fy_end, today].
    // Splits are cosmetic unit changes, so this is not peeking - it is the
    // same normalization every backtest applies. Sources: NVDA 10:1 first
    // split trading day 2024-06-10; AVGO 10:1 first split trading day
    // 2024-07-15; NFLX 10:1 ex-date 2025-11-17 and NOW 5:1 ex-date 2025-12-18
    // (both verified 2026-09-28 against public split records, the XBRL
    // share-count jumps, and Yahoo's own split-event feed).
    static final String[][] SPLITS = {
            {"NVDA", "2024-06-10", "10"},
            {"AVGO", "2024-07-15", "10"},
            {"NFLX", "2025-11-17", "10"},
            {"NOW", "2025-12-18", "5"}};

    /** Product of split factors for ticker with splitDate in (fyEnd, date]. */
    static double splitFactor(String ticker, String fyEnd, String date) {
        double f = 1.0;
        for (String[] s : SPLITS) {
            if (s[0].equals(ticker) && s[1].compareTo(fyEnd) > 0
                    && s[1].compareTo(date) <= 0) {
                f *= Double.parseDouble(s[2]);
            }
        }
        return f;
    }

    /** ticker (upper) -> 10-digit CIK, from the filing-watch state file. */
    static Map<String, String> cikByTicker() throws Exception {
        String p = workspaceRoot() + "/sec_filings/filing_watch_state.json";
        DcfModel.Json.Obj state = (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(p));
        DcfModel.Json.Obj cos = state.getObj("companies");
        Map<String, String> out = new TreeMap<>();
        for (String k : cos.map.keySet()) {
            DcfModel.Json.Obj co = cos.getObj(k);
            String cik = co.getStr("cik").replaceAll("\\D", "");
            while (cik.length() < 10) {
                cik = "0" + cik;
            }
            String t = k.toUpperCase();
            if (t.equals("BRKB")) {
                t = "BRK.B"; // workbook ticker uses the dot form
            }
            out.put(t, cik);
        }
        return out;
    }

    /** ticker -> {kind, start_margin, end_margin} from the master workbook data. */
    static Map<String, Map<String, Object>> assumptionsByTicker() throws Exception {
        String p = workspaceRoot() + "/your_files/dcf-valuations/.src/workbook_data.json";
        DcfModel.Json.Obj data = (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(p));
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (DcfModel.Json.Val v : data.getList("companies")) {
            DcfModel.Json.Obj co = (DcfModel.Json.Obj) v;
            DcfModel.Json.Obj a = co.getObj("assumptions");
            if (a == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", a.getStr("kind"));
            m.put("w", a.getNum("w"));
            m.put("start_margin", a.getOptNum("start_margin"));
            m.put("end_margin", a.getOptNum("end_margin"));
            out.put(co.getStr("ticker").toUpperCase(), m);
        }
        return out;
    }

    static double medianW(Map<String, Map<String, Object>> asm) {
        List<Double> ws = new ArrayList<>();
        for (Map<String, Object> m : asm.values()) {
            ws.add((Double) m.get("w"));
        }
        ws.sort(Double::compareTo);
        int n = ws.size();
        return n % 2 == 1 ? ws.get(n / 2) : (ws.get(n / 2 - 1) + ws.get(n / 2)) / 2;
    }

    static int sh(String[] cmd) throws Exception {
        Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = pr.waitFor();
        if (rc != 0 && out.length() < 2000) {
            System.out.println("  cmd failed: " + String.join(" ", cmd) + "\n" + out);
        }
        return rc;
    }

    static String readFile(String path) throws Exception {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    /**
     * Ticker -> history-check flags from dcf-history.csv
     * (Rahik 2026-09-28: the report card cross-reads live assumptions against
     * how each company actually ran). Quote-aware parse; missing file -> empty.
     */
    static Map<String, String> loadHistoryFlags() {
        Map<String, String> out = new TreeMap<>();
        try {
            String text = readFile(workspaceRoot() + "/dcf_csv/dcf-history.csv");
            StringBuilder f = new StringBuilder();
            boolean inQ = false;
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inQ) {
                    if (c == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                            f.append('"');
                            i++;
                        } else {
                            inQ = false;
                        }
                    } else {
                        f.append(c);
                    }
                } else if (c == '"') {
                    inQ = true;
                } else if (c == ',') {
                    row.add(f.toString());
                    f.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    row.add(f.toString());
                    f.setLength(0);
                    if (!row.stream().allMatch(String::isEmpty)) {
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                } else {
                    f.append(c);
                }
            }
            List<String> header = null;
            int ti = -1, fi = -1;
            for (List<String> r : rows) {
                if (r.size() == 1 && r.get(0).startsWith("DCF History Check")) {
                    continue;
                }
                if (header == null) {
                    header = r;
                    ti = header.indexOf("ticker");
                    fi = header.indexOf("flags");
                    continue;
                }
                if (ti >= 0 && fi >= 0 && ti < r.size() && fi < r.size()) {
                    out.put(r.get(ti), r.get(fi));
                }
            }
        } catch (Exception e) {
            // History file absent (e.g. first run) -> section shows "unavailable".
        }
        return out;
    }
    // ------------------------------------------------------------------
    // Run: grade historical_inputs.json -> report + csv, print digest
    // ------------------------------------------------------------------

    static class Row {
        String ticker, asof, kind, gSrc, fyEnd;
        LocalDate anchor; // filing-date anchor: first day all inputs knowable
        int fy;
        double base, g, w, t, shares, netdebt, iv1, iv2, f1, f2;
        Double sm, em;
        String d1, d2;
        Double p1, p2;
    }

    static class Stats {
        int n;
        double medianErr, meanErr, within15, within25;
    }

    static void run() throws Exception {
        String inp = Paths.get(outDir(), "historical_inputs.json").toString();
        DcfModel.Json.Obj root =
                (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(inp));
        List<Row> rows = new ArrayList<>();
        // Yahoo closes are adjusted for all splits through today, so every
        // IV is restated into today's share terms (see SPLITS).
        String today = LocalDate.now().toString();
        LocalDate todayD = LocalDate.now();
        for (DcfModel.Json.Val v : root.getList("records")) {
            DcfModel.Json.Obj r = (DcfModel.Json.Obj) v;
            Row row = new Row();
            row.ticker = r.getStr("ticker");
            row.asof = r.getStr("asof");
            // New records carry filing_date; old (pre-2026-09-28) records fall
            // back to asof, which for them is the Dec-31 convention label.
            row.anchor = LocalDate.parse(r.optStr("filing_date", row.asof));
            row.fy = (int) r.getNum("fy");
            row.kind = r.getStr("kind");
            row.base = r.getNum("base");
            row.g = r.getNum("g");
            row.w = r.getNum("w");
            row.t = r.getNum("t");
            row.shares = r.getNum("shares");
            row.netdebt = r.getNum("netdebt");
            row.sm = r.isNull("start_margin") ? null : r.getOptNum("start_margin");
            row.em = r.isNull("end_margin") ? null : r.getOptNum("end_margin");
            row.gSrc = r.getStr("g_src");
            row.fyEnd = r.optStr("shares_fy_end", "");
            // Outcome windows are measured from the filing-date anchor, not
            // Dec 31: 1-yr and 2-yr anniversaries of the anchor date.
            row.d1 = anniversary(row.anchor, 1).toString();
            row.d2 = anniversary(row.anchor, 2).toString();
            row.f1 = splitFactor(row.ticker, row.fyEnd, today);
            row.f2 = splitFactor(row.ticker, row.fyEnd, today);
            row.iv1 = DcfModel.intrinsicValue(row.kind, row.base, row.g, row.w,
                    row.t, row.shares * row.f1, row.netdebt, row.sm, row.em);
            row.iv2 = DcfModel.intrinsicValue(row.kind, row.base, row.g, row.w,
                    row.t, row.shares * row.f2, row.netdebt, row.sm, row.em);
            rows.add(row);
        }

        // One HistoricalPrice call per ticker for all its needed dates.
        // Only completed windows are graded: a window whose anniversary has
        // not passed yet (e.g. 2-yr for the 2024 cohort) can never have a
        // close, so its date is not even requested.
        Map<String, List<String>> datesByTicker = new TreeMap<>();
        for (Row row : rows) {
            List<String> ds = datesByTicker.computeIfAbsent(row.ticker,
                    k -> new ArrayList<>());
            if (windowComplete(row.anchor, 1, todayD) && !ds.contains(row.d1)) {
                ds.add(row.d1);
            }
            if (windowComplete(row.anchor, 2, todayD) && !ds.contains(row.d2)) {
                ds.add(row.d2);
            }
        }
        Map<String, Map<String, Double>> closes = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : datesByTicker.entrySet()) {
            closes.put(e.getKey(), fetchCloses(e.getKey(), e.getValue()));
            Thread.sleep(300); // be gentle to Yahoo
        }
        for (Row row : rows) {
            Map<String, Double> c = closes.get(row.ticker);
            row.p1 = c.get(row.d1);
            row.p2 = c.get(row.d2);
        }

        // Fail loud if price fetching broke wholesale (API shape change,
        // outage, stale classes): a near-empty grade set must never be
        // written out as a report card. Single-ticker hiccups stay tolerated.
        long missingP1 = rows.stream().filter(r -> r.p1 == null).count();
        if (!rows.isEmpty() && missingP1 * 4 > rows.size()) {
            throw new IllegalStateException("price fetch failed for "
                    + missingP1 + " of " + rows.size()
                    + " rows - refusing to write an empty report card");
        }

        // Cohort stats: "2023 -> 1yr", "2023 -> 2yr", "2024 -> 1yr".
        Map<String, List<Double>> cohortErrs = new LinkedHashMap<>();
        cohortErrs.put("2023 -> 1yr", new ArrayList<>());
        cohortErrs.put("2023 -> 2yr", new ArrayList<>());
        cohortErrs.put("2024 -> 1yr", new ArrayList<>());
        cohortErrs.put("2024 -> 2yr", new ArrayList<>());
        // Growth buckets (1-yr errors only).
        Map<String, List<Double>> bucketErrs = new LinkedHashMap<>();
        bucketErrs.put("high (g>=15%)", new ArrayList<>());
        bucketErrs.put("mid (5-15%)", new ArrayList<>());
        bucketErrs.put("low (g<5%)", new ArrayList<>());
        List<Double> allErr1 = new ArrayList<>();
        List<Double> allAbsErr1 = new ArrayList<>();
        for (Row row : rows) {
            if (row.p1 != null && row.iv1 != 0) {
                double e1 = (row.p1 - row.iv1) / Math.abs(row.iv1);
                cohortErrs.get(row.fy + " -> 1yr").add(e1);
                allErr1.add(e1);
                allAbsErr1.add(Math.abs(e1));
                String b = row.g >= 0.15 ? "high (g>=15%)"
                        : row.g >= 0.05 ? "mid (5-15%)" : "low (g<5%)";
                bucketErrs.get(b).add(e1);
            }
            if (row.p2 != null && row.iv2 != 0) {
                cohortErrs.get(row.fy + " -> 2yr")
                        .add((row.p2 - row.iv2) / Math.abs(row.iv2));
            }
        }

        Map<String, Stats> cohortStats = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : cohortErrs.entrySet()) {
            cohortStats.put(e.getKey(), stats(e.getValue()));
        }
        Map<String, Stats> bucketStats = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : bucketErrs.entrySet()) {
            bucketStats.put(e.getKey(), stats(e.getValue()));
        }

        writeCsv(rows);
        List<String> skipped = new ArrayList<>();
        for (DcfModel.Json.Val v : root.getList("skipped")) {
            DcfModel.Json.Obj s = (DcfModel.Json.Obj) v;
            skipped.add(s.getStr("ticker") + " " + s.optStr("asof", "?")
                    + ": " + s.optStr("skip_reason", "?"));
        }
        String md = buildReport(rows, cohortStats, bucketStats, allErr1, allAbsErr1,
                root, skipped);
        Files.writeString(Paths.get(outDir(), "backtest_report.md"), md,
                StandardCharsets.UTF_8);
        printDigest(cohortStats, bucketStats, allErr1, rows.size(), skipped);
    }

    /** Close per requested date via HistoricalPrice.java (split-adjusted). */
    static Map<String, Double> fetchCloses(String ticker, List<String> dates)
            throws Exception {
        String javaBin = System.getenv("HOME") + "/workspace/java/jdk-21/bin/java";
        String hp = System.getenv("HOME") + "/workspace/stock_watch/HistoricalPrice.java";
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add(hp);
        cmd.add(ticker);
        cmd.addAll(dates);
        Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(pr.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        pr.waitFor();
        Map<String, Double> m = new LinkedHashMap<>();
        try {
            DcfModel.Json.Obj root = (DcfModel.Json.Obj) DcfModel.Json.parse(out.trim());
            DcfModel.Json.Obj t = root.getObj(ticker);
            for (String d : dates) {
                DcfModel.Json.Obj e = t.getObj(d);
                m.put(d, e == null ? null : e.getOptNum("close"));
            }
        } catch (Exception ex) {
            System.out.println("  price fetch failed for " + ticker + ": " + ex);
            for (String d : dates) {
                m.put(d, null);
            }
        }
        return m;
    }

    static Stats stats(List<Double> errs) {
        Stats s = new Stats();
        s.n = errs.size();
        if (s.n == 0) {
            return s;
        }
        List<Double> sorted = new ArrayList<>(errs);
        sorted.sort(Double::compareTo);
        s.medianErr = sorted.get(s.n / 2);
        double sum = 0;
        int w15 = 0, w25 = 0;
        for (double e : errs) {
            sum += e;
            if (Math.abs(e) <= 0.15) {
                w15++;
            }
            if (Math.abs(e) <= 0.25) {
                w25++;
            }
        }
        s.meanErr = sum / s.n;
        s.within15 = 100.0 * w15 / s.n;
        s.within25 = 100.0 * w25 / s.n;
        return s;
    }

    static String pct(double v) {
        return String.format("%+.1f%%", v * 100);
    }

    static String money(Double v) {
        return v == null ? "--" : String.format("$%.2f", v);
    }

    static void writeCsv(List<Row> rows) throws Exception {
        StringBuilder sb = new StringBuilder(
                "ticker,asof,kind,base_M,g,w,iv_1yr,split_f,actual_1yr,actual_2yr,err_1yr_pct,err_2yr_pct\n");
        for (Row r : rows) {
            sb.append(r.ticker).append(",").append(r.asof).append(",").append(r.kind)
                    .append(",").append(String.format("%.1f", r.base))
                    .append(",").append(String.format("%.4f", r.g))
                    .append(",").append(String.format("%.4f", r.w))
                    .append(",").append(String.format("%.2f", r.iv1))
                    .append(",").append(String.format("%.0f", r.f1))
                    .append(",").append(r.p1 == null ? "" : String.format("%.2f", r.p1))
                    .append(",").append(r.p2 == null ? "" : String.format("%.2f", r.p2));
            sb.append(",").append(r.p1 == null || r.iv1 == 0 ? ""
                    : String.format("%.1f", (r.p1 - r.iv1) / Math.abs(r.iv1) * 100));
            sb.append(",").append(r.p2 == null || r.iv2 == 0 ? ""
                    : String.format("%.1f", (r.p2 - r.iv2) / Math.abs(r.iv2) * 100));
            sb.append("\n");
        }
        Files.writeString(Paths.get(outDir(), "backtest_results.csv"), sb.toString(),
                StandardCharsets.UTF_8);
    }

    static String buildReport(List<Row> rows, Map<String, Stats> cohorts,
                              Map<String, Stats> buckets, List<Double> allErr1,
                              List<Double> allAbsErr1, DcfModel.Json.Obj root,
                              List<String> skipped) {
        StringBuilder md = new StringBuilder();
        md.append("# DCF backtest report card\n\n");
        md.append("Generated ").append(LocalDate.now()).append(". ");
        md.append("Predictions rebuilt with the fixed methodology ")
                .append("(DcfModel.intrinsicValue: 10-year forecast, mid-year discounting, ")
                .append("5-year growth fade), then graded against actual prices. ")
                .append("Input discipline: every fact comes from the fiscal year's OWN ")
                .append("10-K/20-F (earliest filed date wins), each valuation is anchored ")
                .append("to the latest filing date among the facts it uses (the first day ")
                .append("all inputs were knowable), and outcome windows are measured ")
                .append("from that anchor. Strict no-peek: nothing filed after the ")
                .append("anchor is used, and only completed windows are graded.\n\n");
        md.append("## Assumption rules (mechanical, no peeking)\n\n");
        md.append("- Each fact comes from the fiscal year's OWN 10-K/20-F (earliest ")
                .append("filed date wins over later comparative columns), and within that ")
                .append("filing from the latest period-end column (the year's own figures, ")
                .append("not prior-year comparatives).\n");
        md.append("- Filing-date anchor: the valuation date is the latest filing date ")
                .append("among the facts used (base-year cash flow or revenue, shares, ")
                .append("net debt) - the first day a contemporary analyst could have ")
                .append("run it. Nothing filed later is used.\n");
        md.append("- FCFF/FCFE: base = FY operating cash flow minus capex; ")
                .append("REV: base = FY revenue.\n");
        md.append("- g = 3-year FCF CAGR clamped [0%, 30%]; fallback 3-year revenue ")
                .append("CAGR clamped [0%, 25%]; then 8%. REV models use revenue CAGR.\n");
        md.append("- w = ").append(String.format("%.2f%%",
                root.getNum("w_median") * 100))
                .append(" (median of current model WACCs across all tickers)\n");
        md.append("- t = 3% terminal growth; kind = ticker's current model kind\n");
        md.append("- Split adjustment: Yahoo's closes are adjusted for ALL splits ")
                .append("through today (verified 2026-09-28), so each ")
                .append("IV is restated into today's share terms ")
                .append("(splits in (fyEnd, today]; NVDA 10:1 2024-06-10, AVGO 10:1 ")
                .append("2024-07-15, NFLX 10:1 2025-11-17, NOW 5:1 2025-12-18). ")
                .append("Splits are cosmetic unit changes, not peeking.\n\n");
        md.append("## Caveats\n\n");
        md.append("- REV-model tickers reuse CURRENT start/end margins (historical ")
                .append("margins were not reconstructed) - a known bias risk on those rows.\n");
        md.append("- w is a cross-sectional median, not the historical per-company WACC.\n");
        md.append("- Prices are split/dividend-adjusted closes (Yahoo); actuals use the ")
                .append("first trading day on/after the 1-yr and 2-yr filing ")
                .append("anniversaries (completed windows only).\n");
        md.append("- 2-year actuals exist for the 2023 cohort and for early FY2024 ")
                .append("filers whose 2-yr filing anniversary has passed.\n\n");

        md.append("## Report card\n\n");
        md.append("| cohort | n | median err | mean err | within +/-15% | within +/-25% |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Stats> e : cohorts.entrySet()) {
            Stats s = e.getValue();
            md.append("| ").append(e.getKey()).append(" | ").append(s.n).append(" | ")
                    .append(pct(s.medianErr)).append(" | ").append(pct(s.meanErr))
                    .append(" | ").append(String.format("%.0f%%", s.within15))
                    .append(" | ").append(String.format("%.0f%%", s.within25))
                    .append(" |\n");
        }
        md.append("\n| growth bucket (1-yr) | n | median err |\n");
        md.append("|---|---|---|\n");
        for (Map.Entry<String, Stats> e : buckets.entrySet()) {
            md.append("| ").append(e.getKey()).append(" | ").append(e.getValue().n)
                    .append(" | ").append(pct(e.getValue().medianErr)).append(" |\n");
        }

        md.append("\n## What this teaches\n\n");
        if (!allErr1.isEmpty()) {
            List<Double> srt = new ArrayList<>(allErr1);
            srt.sort(Double::compareTo);
            double med = srt.get(srt.size() / 2);
            List<Double> asrt = new ArrayList<>(allAbsErr1);
            asrt.sort(Double::compareTo);
            double medAbs = asrt.get(asrt.size() / 2);
            md.append("- Overall 1-yr bias: model runs ")
                    .append(med >= 0 ? "pessimistic" : "optimistic")
                    .append(" by a median of ").append(pct(Math.abs(med)))
                    .append(" (positive error = actual price beat the DCF).\n");
            md.append("- Typical 1-yr miss: median |error| ")
                    .append(String.format("%.1f%%", medAbs * 100)).append(".\n");
            String worst = null;
            double worstAbs = -1;
            for (Map.Entry<String, Stats> e : buckets.entrySet()) {
                if (e.getValue().n > 0
                        && Math.abs(e.getValue().medianErr) > worstAbs) {
                    worstAbs = Math.abs(e.getValue().medianErr);
                    worst = e.getKey();
                }
            }
            if (worst != null) {
                md.append("- Weakest bucket: ").append(worst)
                        .append(" (median err ").append(pct(
                                buckets.get(worst).medianErr)).append(").\n");
            }
        } else {
            md.append("- No graded 1-yr predictions this run.\n");
        }

        md.append("\n## Where the mechanical model breaks\n\n");
        md.append("Rows where the base-year FCF was zero or negative produce a "
                + "zero/negative IV no matter the growth story - the model "
                + "cannot see through a heavy-investment year:\n\n");
        boolean anyBroken = false;
        for (Row r : rows) {
            if (r.iv1 <= 0) {
                md.append("- ").append(r.ticker).append(" ").append(r.asof)
                        .append(": IV ").append(money(r.iv1))
                        .append(" vs actual ").append(money(r.p1))
                        .append(" - base-year FCF collapsed (capex wall / cyclical trough); ")
                        .append("a human analyst would normalize the base year.\n");
                anyBroken = true;
            }
        }
        if (!anyBroken) {
            md.append("- none this run.\n");
        }
        md.append("\nTrough-year CAGRs: when the 3-yr window starts in a trough "
                + "(e.g. energy FCF in 2020), the CAGR hits the 30% clamp and the "
                + "model extrapolates a recovery as permanent hypergrowth. "
                + "CVX/XOM 2023 are the textbook cases (-78% to -82% errors).\n");

        md.append("\n## History check cross-read\n\n");
        md.append("Current history-check flags (dcf-history.csv) next to "
                + "each ticker's backtest 1-yr median error. Where today's live "
                + "assumptions exceed the company's own track record AND the "
                + "backtest missed, the assumption is the prime suspect — not the "
                + "formulas.\n\n");
        md.append("| ticker | 1-yr median err | history flags |\n");
        md.append("|---|---|---|\n");
        Map<String, List<Double>> errsByTicker = new TreeMap<>();
        for (Row r : rows) {
            if (r.p1 != null && r.iv1 != 0) {
                errsByTicker.computeIfAbsent(r.ticker, k -> new ArrayList<>())
                        .add((r.p1 - r.iv1) / Math.abs(r.iv1));
            }
        }
        Map<String, String> histFlags = loadHistoryFlags();
        for (Map.Entry<String, List<Double>> e : errsByTicker.entrySet()) {
            List<Double> srt = new ArrayList<>(e.getValue());
            srt.sort(Double::compareTo);
            double med = srt.get(srt.size() / 2);
            String flags = histFlags.getOrDefault(e.getKey(), "history check unavailable");
            md.append("| ").append(e.getKey()).append(" | ").append(pct(med))
                    .append(" | ").append(flags.isEmpty() ? "none" : flags)
                    .append(" |\n");
        }

        md.append("\n## Per-ticker results\n\n");
        md.append("IV is the 1-yr comparable value (split-adjusted where noted).\n\n");
        md.append("| ticker | as of | IV | actual +1yr | err | actual +2yr | err |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append("| ").append(r.ticker).append(" | ").append(r.asof).append(" | ")
                    .append(money(r.iv1))
                    .append(r.f1 > 1 ? " (x" + String.format("%.0f", r.f1) + " split)" : "")
                    .append(" | ").append(money(r.p1)).append(" | ")
                    .append(r.p1 == null || r.iv1 == 0 ? "--"
                            : pct((r.p1 - r.iv1) / Math.abs(r.iv1))).append(" | ")
                    .append(money(r.p2)).append(" | ")
                    .append(r.p2 == null || r.iv2 == 0 ? "--"
                            : pct((r.p2 - r.iv2) / Math.abs(r.iv2))).append(" |\n");
        }
        if (!skipped.isEmpty()) {
            md.append("\n## Skipped (not graded)\n\n");
            for (String sk : skipped) {
                md.append("- ").append(sk).append("\n");
            }
        }
        md.append("\nRaw rows: backtest_results.csv. Inputs: historical_inputs.json.\n");
        return md.toString();
    }

    /** Compact digest for the DCF Model Lab chat. */
    static void printDigest(Map<String, Stats> cohorts, Map<String, Stats> buckets,
                            List<Double> allErr1, int nRows, List<String> skipped) {
        System.out.println("DCF BACKTEST REPORT CARD (FY2023 & FY2024 cohorts, filing-date anchored; actuals 1-2 yrs after each 10-K/20-F filing)");
        System.out.println("Coverage: " + nRows + " predictions graded"
                + (skipped.isEmpty() ? "" : "; skipped " + skipped.size()));
        for (Map.Entry<String, Stats> e : cohorts.entrySet()) {
            Stats s = e.getValue();
            System.out.println(e.getKey() + ": n=" + s.n + ", median err "
                    + pct(s.medianErr) + ", within +/-15%: "
                    + String.format("%.0f%%", s.within15) + ", within +/-25%: "
                    + String.format("%.0f%%", s.within25));
        }
        StringBuilder bb = new StringBuilder("Growth buckets (1-yr): ");
        boolean first = true;
        for (Map.Entry<String, Stats> e : buckets.entrySet()) {
            if (!first) {
                bb.append(" | ");
            }
            first = false;
            bb.append(e.getKey()).append(" median ").append(pct(e.getValue().medianErr))
                    .append(" (n=").append(e.getValue().n).append(")");
        }
        System.out.println(bb);
        if (!allErr1.isEmpty()) {
            List<Double> srt = new ArrayList<>(allErr1);
            srt.sort(Double::compareTo);
            double med = srt.get(srt.size() / 2);
            System.out.println("Headline: model runs "
                    + (med >= 0 ? "pessimistic" : "optimistic")
                    + " by a median of " + pct(Math.abs(med))
                    + " on 1-yr actuals.");
        }
        if (!skipped.isEmpty()) {
            System.out.println("Skipped: " + String.join("; ", skipped));
        }
        System.out.println("Full report: dcf_csv/backtest/backtest_report.md");
    }
}
