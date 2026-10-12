import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Walk-forward extension of {@link DcfBacktest} with purged + embargoed
 * evaluation (de Prado, "Advances in Financial Machine Learning", ch. 7).
 *
 * <p>EXTENDS, does not replace, DcfBacktest. The fixed methodology, the
 * mechanical no-peek input rules, the IV formula and the grading rule are
 * byte-for-byte the same code paths (DcfBacktest.buildRecord,
 * DcfModel.intrinsicValue, DcfBacktest.splitFactor-style restatement). What
 * changes:
 * <ul>
 *   <li>more folds: cohorts 2020-2024 instead of 2023-2024, all built OFFLINE
 *       from the already-downloaded companyfacts (never refetched);</li>
 *   <li>per-fold report cards (one test fold per cohort year, in time order);</li>
 *   <li>purged + embargoed pooled aggregates: when pooling predictions across
 *       folds, a prediction is PURGED if its label window [asof, actual date]
 *       positively overlaps an already-pooled prediction's window (oldest
 *       first), so the same market move is not double-counted; a prediction
 *       is EMBARGOED if its asof falls within 90 days after a test fold's
 *       window end.</li>
 * </ul>
 *
 * <p>Honest notes on what purge/embargo do here. The DCF methodology has NO
 * fitted parameters (mechanical rules + a fixed w), so purging cannot change
 * any prediction - it changes only the POOLED AGGREGATE statistics, by
 * removing double-counted overlapping label windows. For the 1-yr horizon at
 * yearly asofs the label windows merely abut (no positive overlap), so the
 * purge is a no-op there and the code reports "0 purged" rather than
 * pretending otherwise. The purge bites on the 2-yr horizon, where adjacent
 * cohorts' windows overlap by ~1 year. The embargo is likewise a reported
 * no-op at yearly asof granularity (next fold starts 365 days later, the gap
 * is 90 days); it is implemented faithfully and the count is reported.
 *
 * <p>Split handling: DcfBacktest.SPLITS only covers the 2024 NVDA/AVGO splits.
 * Walking back to 2020 needs the full 2020-2025 split history, verified
 * 2026-09-28 against public split records AND the ~2x+ jumps in the XBRL
 * share counts. PLTR's 1.96x FY2020-&gt;2021 jump is IPO/issuance, NOT a
 * split - deliberately excluded. Two of these (NFLX 10:1 on 2025-11-17,
 * NOW 5:1 on 2025-12-18) also correct FOUR ROWS of the published
 * backtest_report.md, which compared pre-split IVs against post-split
 * actuals (two of the four even flip sign).
 *
 * <p>Shared caveats with the base backtest: w is the median of CURRENT model
 * WACCs (not historical), REV tickers reuse current margins, reporting-lag
 * convention, not a strict no-peek evaluation at Dec 31. See DcfBacktest.
 *
 * <p>Usage (compile-first, like DcfBacktest):
 * <pre>
 *   javac -cp $HOME/workspace/java-shared/classes -d /tmp/wf_classes \
 *       DcfBacktestWalkForward.java DcfBacktest.java DcfModel.java
 *   java -cp /tmp/wf_classes:$HOME/workspace/java-shared/classes \
 *       DcfBacktestWalkForward --prepare-wf  # offline: build historical_inputs_wf.json
 *   java -cp /tmp/wf_classes:$HOME/workspace/java-shared/classes \
 *       DcfBacktestWalkForward --run-wf      # grade -> walkforward_report.md + .csv, print digest
 *   java -cp /tmp/wf_classes:$HOME/workspace/java-shared/classes \
 *       DcfBacktestWalkForward               # prepare + run
 *   java -cp /tmp/wf_classes:$HOME/workspace/java-shared/classes \
 *       DcfBacktestWalkForward --self-test   # purge/embargo/fold unit tests
 * </pre>
 *
 * <p>Reusable entry points for other backtests (public static):
 * {@link #overlapsAnyTest}, {@link #applyPurgeEmbargo}, {@link #byHorizon} -
 * the purge+embargo machinery itself lives in the shared
 * {@code WalkForward} helper (java-shared), with no DCF specifics.
 */
public class DcfBacktestWalkForward {

    static final int[] WF_COHORTS = {2020, 2021, 2022, 2023, 2024};
    static final int EMBARGO_DAYS = 90; // de Prado-style gap after each test window

    // Verified stock splits, 2020-2025. splitDate = first trading day on the
    // new share terms (same convention as DcfBacktest.SPLITS). Applied when
    // splitDate is in (shares_fy_end, today] - Yahoo's closes are adjusted
    // for all splits through today, so IVs are restated into today's share
    // terms (verified 2026-09-28). Splits are cosmetic unit changes,
    // not peeking. NVDA-2024 and AVGO mirror DcfBacktest.SPLITS (verified
    // 2026-09-27); the rest verified 2026-09-28 against public split records
    // and corroborated by the XBRL share-count jumps.
    static final String[][] WF_SPLITS = {
            {"AMZN", "2022-06-06", "20"},   // 20:1, trading began 2022-06-06
            {"GOOG", "2022-07-18", "20"},   // 20:1, trading began 2022-07-18
            {"NVDA", "2021-07-20", "4"},    // 4:1, trading began 2021-07-20
            {"NVDA", "2024-06-10", "10"},   // 10:1 (DcfBacktest.SPLITS)
            {"TSLA", "2020-08-31", "5"},    // 5:1, trading began 2020-08-31
            {"TSLA", "2022-08-25", "3"},    // 3:1, trading began 2022-08-25
            {"CELH", "2023-11-15", "3"},    // 3:1, ex-distribution 2023-11-15
            {"AVGO", "2024-07-15", "10"},   // 10:1 (DcfBacktest.SPLITS)
            {"NFLX", "2025-11-17", "10"},   // 10:1, split-adjusted trading 2025-11-17
            {"NOW", "2025-12-18", "5"},     // 5:1, split-adjusted trading 2025-12-18
    };

    static String workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        return (e != null && !e.isEmpty()) ? e
                : System.getenv("HOME") + "/workspace";
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
        boolean prepare = true, run = true;
        for (String a : args) {
            if (a.equals("--prepare-wf")) { run = false; }
            if (a.equals("--run-wf")) { prepare = false; }
            if (a.equals("--self-test")) {
                selfTest();
                return;
            }
        }
        if (prepare) {
            prepareWf();
        }
        if (run) {
            runWf();
        }
    }

    // ------------------------------------------------------------------
    // Prepare (offline): build historical_inputs_wf.json for 2020-2024.
    // companyfacts are reused from disk; nothing is fetched.
    // ------------------------------------------------------------------

    static void prepareWf() throws Exception {
        Map<String, String> cikByTicker = DcfBacktest.cikByTicker();
        Map<String, Map<String, Object>> asmByTicker =
                DcfBacktest.assumptionsByTicker();
        double wMedian = DcfBacktest.medianW(asmByTicker);

        List<Object> records = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        for (Map.Entry<String, String> e : cikByTicker.entrySet()) {
            String ticker = e.getKey();
            Path p = Paths.get(outDir(), "companyfacts", e.getValue() + ".json");
            if (!Files.exists(p)) {
                skipped.add(DcfBacktest.skip(ticker, null,
                        "companyfacts not on disk (offline prepare, no fetch)"));
                continue;
            }
            List<XbrlFacts.Fact> facts;
            try {
                facts = XbrlFacts.load(p);
            } catch (Exception ex) {
                skipped.add(DcfBacktest.skip(ticker, null,
                        "companyfacts parse failed"));
                continue;
            }
            Map<String, Object> asm = asmByTicker.get(ticker);
            if (asm == null) {
                skipped.add(DcfBacktest.skip(ticker, null,
                        "no current assumptions (kind unknown)"));
                continue;
            }
            for (int asofY : WF_COHORTS) {
                // Same mechanical no-peek builder as the base backtest.
                Map<String, Object> rec = DcfBacktest.buildRecord(ticker, facts,
                        asofY, (String) asm.get("kind"), wMedian, asm);
                if (rec.containsKey("skip_reason")) {
                    skipped.add(rec);
                } else {
                    records.add(rec);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", LocalDate.now().toString());
        out.put("method", "walk-forward extension of DcfBacktest: cohorts 2020-2024, "
                + "same fixed-methodology builder; splits per WF_SPLITS in "
                + "DcfBacktestWalkForward.java");
        out.put("w_median", wMedian);
        out.put("records", records);
        out.put("skipped", skipped);
        Files.writeString(Paths.get(outDir(), "historical_inputs_wf.json"),
                DcfModel.Json.dumps(out), StandardCharsets.UTF_8);
        System.out.println("records=" + records.size()
                + " skipped=" + skipped.size());
        DcfBacktest.warnUnrecordedSplits(records, WF_SPLITS);
    }

    /** Product of WF split factors with splitDate in (fyEnd, date]. */
    static double wfSplitFactor(String ticker, String fyEnd, String date) {
        double f = 1.0;
        for (String[] s : WF_SPLITS) {
            if (s[0].equals(ticker) && s[1].compareTo(fyEnd) > 0
                    && s[1].compareTo(date) <= 0) {
                f *= Double.parseDouble(s[2]);
            }
        }
        return f;
    }

    // ------------------------------------------------------------------
    // Run: grade, fold, purge+embargo, compare vs the base backtest.
    // ------------------------------------------------------------------

    static class Pred {
        String ticker;
        int fy;               // cohort year; fold = fy
        LocalDate asof;       // fy-12-31
        int horizon;          // 1 or 2 (years)
        double iv;            // split-restated into actual-date share terms
        double splitF;
        Double actual;        // null when not yet knowable
        LocalDate actualDate; // first trading day on/after fy+horizon-12-31
        double err;           // (actual - iv) / |iv|, graded only
        boolean graded;
        boolean purged;
        boolean embargoed;
        String note = "";
    }

    static void runWf() throws Exception {
        String inp = Paths.get(outDir(), "historical_inputs_wf.json").toString();
        DcfModel.Json.Obj root =
                (DcfModel.Json.Obj) DcfModel.Json.parse(
                        Files.readString(Paths.get(inp), StandardCharsets.UTF_8));

        List<Pred> preds = new ArrayList<>();
        // Yahoo's chart endpoint returns closes adjusted for ALL splits
        // through today, so every IV is restated into today's share terms:
        // splits in (fyEnd, today]. (Verified 2026-09-28: AVGO 2020+1yr
        // fetched 66.54 = 665.4/10, CELH 24.86 = 74.58/3, NFLX 60.24 =
        // 602.4/10 - the endpoint adjusts history; using actualDate as the
        // window end would unit-mismatch every row whose split came after
        // its actual date.)
        String today = LocalDate.now().toString();
        for (DcfModel.Json.Val v : root.getList("records")) {
            DcfModel.Json.Obj r = (DcfModel.Json.Obj) v;
            int fy = (int) r.getNum("fy");
            String kind = r.getStr("kind");
            double base = r.getNum("base");
            double g = r.getNum("g");
            double w = r.getNum("w");
            double t = r.getNum("t");
            double shares = r.getNum("shares");
            double netdebt = r.getNum("netdebt");
            Double sm = r.isNull("start_margin") ? null : r.getOptNum("start_margin");
            Double em = r.isNull("end_margin") ? null : r.getOptNum("end_margin");
            String fyEnd = r.optStr("shares_fy_end", "");
            String ticker = r.getStr("ticker");
            for (int h : new int[]{1, 2}) {
                Pred p = new Pred();
                p.ticker = ticker;
                p.fy = fy;
                p.asof = LocalDate.of(fy, 12, 31);
                p.horizon = h;
                p.actualDate = LocalDate.of(fy + h, 12, 31);
                p.splitF = wfSplitFactor(ticker, fyEnd, today);
                p.iv = DcfModel.intrinsicValue(kind, base, g, w, t,
                        shares * p.splitF, netdebt, sm, em);
                preds.add(p);
            }
        }

        // Actuals: Yahoo closes via the base backtest's fetch path. NOTE: the
        // endpoint returns closes adjusted for all splits through today
        // (verified 2026-09-28) - NOT unadjusted as the base backtest's
        // comment claims - which is why IVs above are restated into today's
        // share terms.
        Map<String, List<String>> datesByTicker = new TreeMap<>();
        for (Pred p : preds) {
            List<String> ds = datesByTicker.computeIfAbsent(p.ticker,
                    k -> new ArrayList<>());
            String d = p.actualDate.toString();
            if (d.compareTo(today) <= 0 && !ds.contains(d)) {
                ds.add(d);
            }
        }
        Map<String, Map<String, Double>> closes = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : datesByTicker.entrySet()) {
            closes.put(e.getKey(), DcfBacktest.fetchCloses(e.getKey(), e.getValue()));
            Thread.sleep(300);
        }
        for (Pred p : preds) {
            Double c = closes.get(p.ticker).get(p.actualDate.toString());
            p.actual = c;
            // Same grading rule as the base backtest.
            p.graded = c != null && p.iv != 0;
            if (p.graded) {
                p.err = (c - p.iv) / Math.abs(p.iv);
            }
        }
        long missing1 = preds.stream()
                .filter(p -> p.horizon == 1 && !p.graded).count();
        long total1 = preds.stream().filter(p -> p.horizon == 1).count();
        if (total1 > 0 && missing1 * 4 > total1) {
            throw new IllegalStateException("price fetch failed for "
                    + missing1 + " of " + total1
                    + " 1-yr rows - refusing to write an empty report card");
        }

        // Folds in time order; test set of fold k = cohort k.
        Map<Integer, List<Pred>> folds = new TreeMap<>();
        for (Pred p : preds) {
            if (p.graded) {
                folds.computeIfAbsent(p.fy, k -> new ArrayList<>()).add(p);
            }
        }

        // Per-fold test metrics + train-set purge/embargo accounting.
        // (The methodology is fixed, so the train composition cannot change
        // predictions; the accounting below shows the machinery working and
        // the purge/embargo bite in the pooled aggregates.)
        Map<Integer, DcfBacktest.Stats> foldStats1 = new TreeMap<>();
        Map<Integer, DcfBacktest.Stats> foldStats2 = new TreeMap<>();
        Map<Integer, int[]> trainAudit = new TreeMap<>(); // {raw, purged, embargoed}
        for (int fy : folds.keySet()) {
            foldStats1.put(fy, DcfBacktest.stats(errs(folds.get(fy), 1)));
            foldStats2.put(fy, DcfBacktest.stats(errs(folds.get(fy), 2)));
            for (int h : new int[]{1, 2}) {
                List<Pred> test = byHorizon(folds.get(fy), h);
                List<Pred> train = new ArrayList<>();
                for (int f2 : folds.keySet()) {
                    if (f2 < fy) {
                        train.addAll(byHorizon(folds.get(f2), h));
                    }
                }
                int raw = train.size(), purged = 0, emb = 0;
                LocalDate testEnd = test.stream().map(p -> p.actualDate)
                        .max(LocalDate::compareTo).orElse(null);
                List<Pred> kept = new ArrayList<>();
                for (Pred tr : train) {
                    if (overlapsAnyTest(tr, test)) {
                        purged++;
                        continue;
                    }
                    if (testEnd != null && tr.asof.isAfter(testEnd)
                            && !tr.asof.isAfter(testEnd.plusDays(EMBARGO_DAYS))) {
                        emb++;
                        continue;
                    }
                    kept.add(tr);
                }
                int[] a = trainAudit.computeIfAbsent(fy, k -> new int[3]);
                a[0] += raw;
                a[1] += purged;
                a[2] += emb;
            }
        }

        // Pooled aggregates: naive vs purged+embargoed, per horizon.
        Map<Integer, Agg> naive = new TreeMap<>();
        Map<Integer, Agg> purgedAgg = new TreeMap<>();
        for (int h : new int[]{1, 2}) {
            List<Pred> all = new ArrayList<>();
            for (int fy : folds.keySet()) {
                all.addAll(byHorizon(folds.get(fy), h));
            }
            naive.put(h, agg(all));
            purgedAgg.put(h, agg(applyPurgeEmbargo(all, h)));
        }

        // "Current backtest" baseline, recomputed from its own CSV artifact.
        Map<String, DcfBacktest.Stats> current = currentBaseline();

        writeWfCsv(preds);
        String md = buildWfReport(preds, folds, foldStats1, foldStats2,
                trainAudit, naive, purgedAgg, current, root);
        Files.writeString(Paths.get(outDir(), "walkforward_report.md"), md,
                StandardCharsets.UTF_8);
        printWfDigest(foldStats1, foldStats2, naive, purgedAgg, current, preds);
    }

    static List<Double> errs(List<Pred> ps, int h) {
        List<Double> out = new ArrayList<>();
        for (Pred p : ps) {
            if (p.horizon == h && p.graded) {
                out.add(p.err);
            }
        }
        return out;
    }

    public static List<Pred> byHorizon(List<Pred> ps, int h) {
        List<Pred> out = new ArrayList<>();
        for (Pred p : ps) {
            if (p.horizon == h) {
                out.add(p);
            }
        }
        return out;
    }

    public static boolean overlapsAnyTest(Pred tr, List<Pred> test) {
        for (Pred t : test) {
            if (WalkForward.overlaps(tr.asof, tr.actualDate,
                    t.asof, t.actualDate)) {
                return true;
            }
        }
        return false;
    }

    static class Agg {
        DcfBacktest.Stats stats;
        int nPurged, nEmbargoed, nKept;
    }

    static Agg agg(List<Pred> ps) {
        Agg a = new Agg();
        List<Double> e = new ArrayList<>();
        for (Pred p : ps) {
            if (p.graded && !p.purged && !p.embargoed) {
                e.add(p.err);
                a.nKept++;
            } else if (p.purged) {
                a.nPurged++;
            } else if (p.embargoed) {
                a.nEmbargoed++;
            }
        }
        a.stats = DcfBacktest.stats(e);
        return a;
    }

    /**
     * Fold-level purge + embargo for the pooled aggregate. Every ticker in
     * one cohort shares the same label window, so purge/embargo must decide
     * per FOLD: a retained fold keeps all its tickers; a purged/embargoed
     * fold drops all of them. (Purging per ticker would wrongly delete
     * same-fold cross-sectional rows, whose identical windows trivially
     * "overlap" each other.)
     *
     * <p>The window math lives in the shared {@link WalkForward} helper;
     * this method only maps folds to windows and flags back onto preds.
     */
    public static List<Pred> applyPurgeEmbargo(List<Pred> all, int h) {
        List<Pred> ps = byHorizon(all, h);
        Map<Integer, List<Pred>> byFold = new TreeMap<>();
        for (Pred p : ps) {
            if (p.graded) {
                byFold.computeIfAbsent(p.fy, k -> new ArrayList<>()).add(p);
            }
        }
        List<FoldWin> folds = new ArrayList<>();
        for (int fy : byFold.keySet()) {
            folds.add(new FoldWin(fy, h));
        }
        WalkForward.Split<FoldWin> split =
                WalkForward.purgeAndEmbargo(folds, EMBARGO_DAYS);
        Set<Integer> purgedFolds = new HashSet<>();
        Set<Integer> embargoedFolds = new HashSet<>();
        Map<Integer, String> foldNote = new HashMap<>();
        for (FoldWin w : split.purged) {
            purgedFolds.add(w.fy);
            foldNote.put(w.fy, split.notes.get(w));
        }
        for (FoldWin w : split.embargoed) {
            embargoedFolds.add(w.fy);
            foldNote.put(w.fy, split.notes.get(w));
        }
        List<Pred> kept = new ArrayList<>();
        for (Pred p : ps) {
            if (!p.graded) {
                continue;
            }
            if (purgedFolds.contains(p.fy)) {
                p.purged = true;
                p.note = foldNote.get(p.fy);
            } else if (embargoedFolds.contains(p.fy)) {
                p.embargoed = true;
                p.note = foldNote.get(p.fy);
            } else {
                kept.add(p);
            }
        }
        return ps; // all preds, flags set; agg() counts via the flags
    }

    /** One cohort's label window [Dec 31 of fy, Dec 31 of fy+h]. */
    static final class FoldWin implements WalkForward.Window {
        final int fy;
        final LocalDate s;
        final LocalDate e;

        FoldWin(int fy, int h) {
            this.fy = fy;
            this.s = LocalDate.of(fy, 12, 31);
            this.e = LocalDate.of(fy + h, 12, 31);
        }

        @Override
        public LocalDate start() {
            return s;
        }

        @Override
        public LocalDate end() {
            return e;
        }

        @Override
        public String toString() {
            return "fold " + fy;
        }
    }

    /**
     * The published verdict, recomputed from the base backtest's own CSV
     * artifact (backtest_results.csv) so the comparison is grounded.
     * Keys: "2023 -> 1yr", "2023 -> 2yr", "2024 -> 1yr", "all -> 1yr".
     */
    static Map<String, DcfBacktest.Stats> currentBaseline() throws Exception {
        Path csv = Paths.get(outDir(), "backtest_results.csv");
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        groups.put("2023 -> 1yr", new ArrayList<>());
        groups.put("2023 -> 2yr", new ArrayList<>());
        groups.put("2024 -> 1yr", new ArrayList<>());
        groups.put("all -> 1yr", new ArrayList<>());
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split(",", -1);
            if (c.length < 12) {
                continue;
            }
            String asof = c[1];
            String e1 = c[10], e2 = c[11];
            if (asof.startsWith("2023")) {
                if (!e1.isEmpty()) {
                    double v = Double.parseDouble(e1) / 100.0;
                    groups.get("2023 -> 1yr").add(v);
                    groups.get("all -> 1yr").add(v);
                }
                if (!e2.isEmpty()) {
                    groups.get("2023 -> 2yr").add(Double.parseDouble(e2) / 100.0);
                }
            } else if (asof.startsWith("2024")) {
                if (!e1.isEmpty()) {
                    double v = Double.parseDouble(e1) / 100.0;
                    groups.get("2024 -> 1yr").add(v);
                    groups.get("all -> 1yr").add(v);
                }
            }
        }
        Map<String, DcfBacktest.Stats> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : groups.entrySet()) {
            out.put(e.getKey(), DcfBacktest.stats(e.getValue()));
        }
        return out;
    }

    static void writeWfCsv(List<Pred> preds) throws Exception {
        StringBuilder sb = new StringBuilder(
                "ticker,asof,fy,horizon,iv,split_f,actual,err_pct,fold,purged,embargoed,note\n");
        List<Pred> ps = new ArrayList<>(preds);
        ps.sort((a, b) -> {
            int c = Integer.compare(a.fy, b.fy);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.horizon, b.horizon);
            return c != 0 ? c : a.ticker.compareTo(b.ticker);
        });
        for (Pred p : ps) {
            sb.append(p.ticker).append(",").append(p.asof).append(",").append(p.fy)
                    .append(",").append(p.horizon).append(",")
                    .append(String.format("%.2f", p.iv)).append(",")
                    .append(String.format("%.0f", p.splitF)).append(",")
                    .append(p.actual == null ? "" : String.format("%.2f", p.actual))
                    .append(",").append(!p.graded ? ""
                            : String.format("%.1f", p.err * 100))
                    .append(",").append(p.fy)
                    .append(",").append(p.purged).append(",").append(p.embargoed)
                    .append(",\"").append(p.note.replace("\"", "")).append("\"\n");
        }
        Files.writeString(Paths.get(outDir(), "walkforward_results.csv"),
                sb.toString(), StandardCharsets.UTF_8);
    }

    static String pct1(double v) {
        return DcfBacktest.pct(v);
    }

    static String buildWfReport(List<Pred> preds,
                                Map<Integer, List<Pred>> folds,
                                Map<Integer, DcfBacktest.Stats> foldStats1,
                                Map<Integer, DcfBacktest.Stats> foldStats2,
                                Map<Integer, int[]> trainAudit,
                                Map<Integer, Agg> naive,
                                Map<Integer, Agg> purgedAgg,
                                Map<String, DcfBacktest.Stats> current,
                                DcfModel.Json.Obj root) {
        StringBuilder md = new StringBuilder();
        md.append("# DCF walk-forward report (purged + embargoed)\n\n");
        md.append("Generated ").append(LocalDate.now()).append(". Extension of ")
                .append("the DCF backtest report card: same fixed methodology, same ")
                .append("mechanical no-peek input rules, same grading rule - but five ")
                .append("walk-forward folds (cohorts 2020-2024, all inputs from the ")
                .append("on-disk companyfacts, nothing refetched) and purged + ")
                .append("embargoed pooled aggregates (de Prado ch. 7).\n\n");

        md.append("## Method\n\n");
        md.append("- One fold per cohort year, in time order. Fold k's test set = ")
                .append("cohort k's predictions graded on 1-yr and 2-yr actuals ")
                .append("(Yahoo closes, split-adjusted through today - IVs ")
                .append("restated to match; first trading day on/after Dec 31).\n");
        md.append("- PURGE (pooled aggregate): folds processed oldest-first; a ")
                .append("prediction is dropped when its label window [asof, actual ")
                .append("date] positively overlaps an already-kept window, so one ")
                .append("market move is never double-counted. Bites on the 2-yr ")
                .append("horizon (adjacent cohorts overlap ~1 yr); a no-op on 1-yr ")
                .append("at yearly asofs (windows merely abut).\n");
        md.append("- EMBARGO: predictions with asof within ").append(EMBARGO_DAYS)
                .append(" days after an earlier fold's window end are dropped. ")
                .append("At yearly asofs this excludes nothing (next fold starts ")
                .append("365 days later); implemented faithfully, count reported.\n");
        md.append("- The methodology has no fitted parameters, so purge/embargo ")
                .append("change only the pooled aggregates, never any prediction.\n");
        md.append("- Splits: WF_SPLITS in DcfBacktestWalkForward.java covers ")
                .append("2020-2025 (AMZN 20:1 2022-06-06, GOOG 20:1 2022-07-18, NVDA ")
                .append("4:1 2021-07-20 and 10:1 2024-06-10, TSLA 5:1 2020-08-31 and ")
                .append("3:1 2022-08-25, CELH 3:1 2023-11-15, AVGO 10:1 2024-07-15, ")
                .append("NFLX 10:1 2025-11-17, NOW 5:1 2025-12-18), verified ")
                .append("2026-09-28 against public split records, the XBRL ")
                .append("share-count jumps, and Yahoo's own split-event feed ")
                .append("(all 10 dates/ratios match). PLTR's 1.96x FY2020->2021 jump is ")
                .append("IPO/issuance, not a split - excluded. ")
                .append("Yahoo's closes are adjusted for ALL splits through today, ")
                .append("so every IV is restated into today's share terms ")
                .append("- splits in (fyEnd, today] - keeping all rows ")
                .append("unit-consistent.\n");
        md.append("- w = ").append(String.format("%.2f%%",
                root.getNum("w_median") * 100))
                .append(" (median of current model WACCs - same known caveat as ")
                .append("the base backtest).\n\n");

        md.append("## Model version note\n\n");
        md.append("This run grades the CURRENT methodology, including the ")
                .append("2026-09-28 practitioner guardrails (sustained growth ")
                .append("capped at 15% - DcfModel.MAX_SUSTAINED_GROWTH). The base ")
                .append("backtest_report.md was regenerated 2026-09-28 under the ")
                .append("same capped model (an earlier 02:25 edition used the ")
                .append("uncapped model - superseded). The clean purge+embargo ")
                .append("comparison is naive pooled vs purged+embargoed WITHIN ")
                .append("this run (same model, same inputs, same actuals).\n\n");

        md.append("## Split-adjustment note (Yahoo returns adjusted closes)\n\n");
        md.append("Yahoo's chart endpoint returns closes adjusted for ALL splits ")
                .append("through today - not unadjusted as the base backtest's ")
                .append("comment claims (verified 2026-09-28: AVGO 2020+1yr fetched ")
                .append("66.54 = 665.4/10, CELH 24.86 = 74.58/3, NFLX 60.24 = ")
                .append("602.4/10). Every IV in this run is therefore restated ")
                .append("into today's share terms - splits in (fyEnd, today] - ")
                .append("so all rows below are unit-consistent. Using the actual ")
                .append("date as the window end would mismatch every row whose ")
                .append("split came after its actual date.\n\n");
        md.append("The base card had the same two defects until 2026-09-28, when ")
                .append("its SPLITS table was extended with the late-2025 splits ")
                .append("(NFLX 10:1 2025-11-17, NOW 5:1 2025-12-18) and given the ")
                .append("same (fyEnd, today] window - its rows are unit-consistent ")
                .append("now. Before that fix its NFLX/NOW rows compared pre-split ")
                .append("IVs against split-adjusted actuals (e.g. NFLX 2023-12-31 ")
                .append("-78.1%/-76.9%); those numbers survive only in history.\n\n");

        md.append("## Per-fold report card\n\n");
        md.append("| fold | 1-yr n | 1-yr median err | 1-yr within +/-15% | ")
                .append("2-yr n | 2-yr median err |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (int fy : folds.keySet()) {
            DcfBacktest.Stats s1 = foldStats1.get(fy);
            DcfBacktest.Stats s2 = foldStats2.get(fy);
            md.append("| ").append(fy).append(" | ").append(s1.n).append(" | ")
                    .append(pct1(s1.medianErr)).append(" | ")
                    .append(String.format("%.0f%%", s1.within15)).append(" | ")
                    .append(s2.n).append(" | ").append(pct1(s2.medianErr))
                    .append(" |\n");
        }
        md.append("\nTrain-set purge/embargo accounting per fold ")
                .append("(raw train size, purged for label overlap, embargoed; ")
                .append("predictions unaffected - fixed methodology):\n\n");
        md.append("| fold | train raw | purged | embargoed |\n");
        md.append("|---|---|---|---|\n");
        for (int fy : trainAudit.keySet()) {
            int[] a = trainAudit.get(fy);
            md.append("| ").append(fy).append(" | ").append(a[0]).append(" | ")
                    .append(a[1]).append(" | ").append(a[2]).append(" |\n");
        }

        md.append("\n## Aggregate comparison: does the upgrade change the verdict?\n\n");
        md.append("| scope | n | median err | within +/-15% | within +/-25% |\n");
        md.append("|---|---|---|---|---|\n");
        String[] curKeys = {"2023 -> 1yr", "2023 -> 2yr", "2024 -> 1yr", "all -> 1yr"};
        for (String k : curKeys) {
            DcfBacktest.Stats s = current.get(k);
            md.append("| published: ").append(k).append(" | ").append(s.n)
                    .append(" | ").append(pct1(s.medianErr)).append(" | ")
                    .append(String.format("%.0f%%", s.within15)).append(" | ")
                    .append(String.format("%.0f%%", s.within25)).append(" |\n");
        }
        for (int h : new int[]{1, 2}) {
            Agg n = naive.get(h);
            Agg p = purgedAgg.get(h);
            md.append("| WF naive pooled +").append(h).append("yr (2020-2024) | ")
                    .append(n.stats.n).append(" | ").append(pct1(n.stats.medianErr))
                    .append(" | ").append(String.format("%.0f%%", n.stats.within15))
                    .append(" | ").append(String.format("%.0f%%", n.stats.within25))
                    .append(" |\n");
            md.append("| WF purged+embargoed +").append(h).append("yr | ")
                    .append(p.stats.n).append(" | ").append(pct1(p.stats.medianErr))
                    .append(" | ").append(String.format("%.0f%%", p.stats.within15))
                    .append(" | ").append(String.format("%.0f%%", p.stats.within25))
                    .append(" | purged ").append(p.nPurged).append(", embargoed ")
                    .append(p.nEmbargoed).append(" |\n");
        }

        md.append("\n## Verdict\n\n");
        Agg n1 = naive.get(1), p1 = purgedAgg.get(1);
        Agg n2 = naive.get(2), p2 = purgedAgg.get(2);
        DcfBacktest.Stats pub = current.get("all -> 1yr");
        md.append("- Published 1-yr verdict (2023-2024, base card, same capped model): median err ")
                .append(pct1(pub.medianErr)).append(", within +/-15%: ")
                .append(String.format("%.0f%%", pub.within15)).append(".\n");
        md.append("- Walk-forward 1-yr, naive pooled (2020-2024, CAPPED model): ")
                .append("median err ").append(pct1(n1.stats.medianErr))
                .append(", within +/-15%: ")
                .append(String.format("%.0f%%", n1.stats.within15)).append(".\n");
        md.append("- Walk-forward 1-yr, purged+embargoed: median err ")
                .append(pct1(p1.stats.medianErr)).append(" (purged ")
                .append(p1.nPurged).append(", embargoed ").append(p1.nEmbargoed)
                .append(") - purge is a no-op on 1-yr at yearly asofs, as designed.\n");
        md.append("- Walk-forward 2-yr: naive median ").append(pct1(n2.stats.medianErr))
                .append(" (n=").append(n2.stats.n).append(") vs purged median ")
                .append(pct1(p2.stats.medianErr)).append(" (n=").append(p2.stats.n)
                .append(", purged ").append(p2.nPurged).append(") - the purge ")
                .append("removes the double-counted overlapping 2-yr windows.\n");
        md.append("\nRaw rows: walkforward_results.csv. Inputs: ")
                .append("historical_inputs_wf.json.\n");
        return md.toString();
    }

    static void printWfDigest(Map<Integer, DcfBacktest.Stats> foldStats1,
                              Map<Integer, DcfBacktest.Stats> foldStats2,
                              Map<Integer, Agg> naive,
                              Map<Integer, Agg> purgedAgg,
                              Map<String, DcfBacktest.Stats> current,
                              List<Pred> preds) {
        System.out.println("DCF WALK-FORWARD (purged+embargoed), folds 2020-2024");
        for (int fy : foldStats1.keySet()) {
            DcfBacktest.Stats s1 = foldStats1.get(fy);
            DcfBacktest.Stats s2 = foldStats2.get(fy);
            System.out.println("fold " + fy + ": 1yr n=" + s1.n + " median "
                    + pct1(s1.medianErr) + " within15 "
                    + String.format("%.0f%%", s1.within15) + " | 2yr n=" + s2.n
                    + " median " + pct1(s2.medianErr));
        }
        DcfBacktest.Stats pub = current.get("all -> 1yr");
        System.out.println("published 1-yr (2023-2024): n=" + pub.n + " median "
                + pct1(pub.medianErr) + " within15 "
                + String.format("%.0f%%", pub.within15));
        for (int h : new int[]{1, 2}) {
            Agg n = naive.get(h), p = purgedAgg.get(h);
            System.out.println("WF naive +" + h + "yr: n=" + n.stats.n + " median "
                    + pct1(n.stats.medianErr) + " within15 "
                    + String.format("%.0f%%", n.stats.within15));
            System.out.println("WF purged +" + h + "yr: n=" + p.stats.n + " median "
                    + pct1(p.stats.medianErr) + " within15 "
                    + String.format("%.0f%%", p.stats.within15) + " (purged "
                    + p.nPurged + ", embargoed " + p.nEmbargoed + ")");
        }
        System.out.println("split note: all IVs restated into today's share terms");
        System.out.println("  (Yahoo closes are split-adjusted); base card fixed");
        System.out.println("  2026-09-28 - see report for split-adjustment notes");
        System.out.println("Full report: dcf_csv/backtest/walkforward_report.md");
    }

    // ------------------------------------------------------------------
    // Self-test: purge/embargo/fold machinery on synthetic predictions.
    // No network, no files - pure logic.
    // ------------------------------------------------------------------

    static Pred synthPred(String ticker, int fy, int h) {
        Pred p = new Pred();
        p.ticker = ticker;
        p.fy = fy;
        p.horizon = h;
        p.asof = LocalDate.of(fy, 12, 31);
        p.actualDate = LocalDate.of(fy + h, 12, 31);
        p.graded = true;
        return p;
    }

    static void selfTest() {
        // FoldWin: the DCF-specific window mapping (generic overlap/purge
        // math is tested in WalkForward --self-test).
        FoldWin fw = new FoldWin(2021, 2);
        SelfTest.checkEq("FoldWin: start is Dec 31 of fy",
                LocalDate.of(2021, 12, 31), fw.start());
        SelfTest.checkEq("FoldWin: end is Dec 31 of fy+h",
                LocalDate.of(2023, 12, 31), fw.end());

        // overlapsAnyTest()
        Pred tr = synthPred("T", 2021, 2); // window [2021-12-31, 2023-12-31]
        List<Pred> test1 = List.of(synthPred("T", 2020, 2)); // [2020-12-31, 2022-12-31]
        SelfTest.check("overlapsAnyTest: overlapping train detected",
                overlapsAnyTest(tr, test1));
        List<Pred> test2 = List.of(synthPred("T", 2019, 2)); // [2019-12-31, 2021-12-31] abuts
        SelfTest.check("overlapsAnyTest: abutting train is not purged",
                !overlapsAnyTest(tr, test2));

        // byHorizon()
        List<Pred> mixed = List.of(synthPred("A", 2020, 1),
                synthPred("A", 2020, 2), synthPred("B", 2021, 1));
        SelfTest.checkEq("byHorizon: filters to horizon", 1, byHorizon(mixed, 2).size());
        SelfTest.checkEq("byHorizon: keeps all of horizon 1", 2, byHorizon(mixed, 1).size());

        // wfSplitFactor()
        SelfTest.checkEq("wfSplitFactor: before split -> 1.0",
                1.0, wfSplitFactor("NVDA", "2021-06-30", "2021-07-19"));
        SelfTest.checkEq("wfSplitFactor: after 4:1 -> 4.0",
                4.0, wfSplitFactor("NVDA", "2021-06-30", "2022-12-31"));
        SelfTest.checkEq("wfSplitFactor: splits compound",
                40.0, wfSplitFactor("NVDA", "2020-01-01", "2025-01-01"));
        SelfTest.checkEq("wfSplitFactor: split on fyEnd boundary excluded",
                1.0, wfSplitFactor("NVDA", "2021-07-20", "2022-12-31"));
        SelfTest.checkEq("wfSplitFactor: no-split ticker -> 1.0",
                1.0, wfSplitFactor("MSFT", "2020-01-01", "2025-01-01"));

        // applyPurgeEmbargo(): 2-yr horizon, folds 2020-2024.
        // Fold 2021's window overlaps retained 2020 -> purged; fold 2023's
        // overlaps retained 2022 -> purged; 2020/2022/2024 abut -> retained.
        List<Pred> wf2 = new ArrayList<>();
        for (int fy = 2020; fy <= 2024; fy++) {
            wf2.add(synthPred("T" + fy, fy, 2));
        }
        applyPurgeEmbargo(wf2, 2);
        Set<Integer> purged2 = new HashSet<>();
        Set<Integer> kept2 = new HashSet<>();
        int emb2 = 0;
        for (Pred p : wf2) {
            if (p.purged) {
                purged2.add(p.fy);
            } else if (p.embargoed) {
                emb2++;
            } else {
                kept2.add(p.fy);
            }
        }
        SelfTest.checkEq("purge h=2: folds 2021,2023 purged",
                Set.of(2021, 2023), purged2);
        SelfTest.checkEq("purge h=2: folds 2020,2022,2024 retained",
                Set.of(2020, 2022, 2024), kept2);
        SelfTest.checkEq("purge h=2: nothing embargoed at yearly asofs", 0, emb2);

        // applyPurgeEmbargo(): 1-yr horizon - windows merely abut, no-op.
        List<Pred> wf1 = new ArrayList<>();
        for (int fy = 2020; fy <= 2024; fy++) {
            wf1.add(synthPred("T" + fy, fy, 1));
        }
        applyPurgeEmbargo(wf1, 1);
        Agg a1 = agg(wf1);
        SelfTest.checkEq("purge h=1: 0 purged (abutting windows)", 0, a1.nPurged);
        SelfTest.checkEq("purge h=1: 0 embargoed", 0, a1.nEmbargoed);
        SelfTest.checkEq("purge h=1: all 5 kept", 5, a1.nKept);

        // agg() counts via flags.
        Agg a2 = agg(wf2);
        SelfTest.checkEq("agg h=2: nPurged=2", 2, a2.nPurged);
        SelfTest.checkEq("agg h=2: nKept=3", 3, a2.nKept);

        SelfTest.finish();
    }
}
