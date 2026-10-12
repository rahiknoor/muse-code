import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/**
 * XbrlHistoryExtractor — extracts 3-4 fiscal years of 10-K XBRL history per ticker
 * from SEC companyfacts JSON (already on disk under ~/workspace/sec_filings/).
 *
 * Purpose: V4 cross-check reference for the NLM speculation two-phase pilot
 * (prop-20261009-002). NLM extracts the 10-K table; this gives an independent
 * figure set to compare within the 5% V4 tolerance. NEVER substitutes its
 * numbers for NLM's in any column (005 provenance rule).
 *
 * Fields: capex, acquisitions, ocf, dna, opinc, tax_expense, pretax, equity,
 * debt_lt, debt_st, leases, cash, curr_assets, curr_liab.
 * Derived (pre-registered, see DERIVATIONS): tax_rate_derived, nopat_derived,
 * delta_wc_derived. Missing tags report MISSING (never zero).
 *
 * Corrections applied 2026-10-10 (Claude v3-preflight verdict):
 *  1. V4 reference keyed to the ON-DISK 10-K's accession. "Latest-filed wins"
 *     could return a restated value from a later filing that NLM never read.
 *     Facts from the on-disk filing's accession are preferred; latest-filed
 *     fallback is flagged per point (accn_fallback=true).
 *  2. Acquisitions are a SEPARATE field (PaymentsToAcquireBusinessesNetOfCashAcquired
 *     chain). V4 compares PP&E capex and acquisitions separately; the package
 *     defines CapEx to include cash acquisitions, the PP&E tag does not.
 *  3. --validate-reference diffs XBRL vs dcf_inputs_all.json (28 companies,
 *     latest-year capex + OCF); anything over 3% is listed before the pilot.
 *  4. D&A / working-capital / NOPAT derivations are PRE-REGISTERED below and
 *     must not be tuned after seeing results.
 *  5. FY labels key on period end with tolerance (anchor ends from the on-disk
 *     10-K accession; ±10d match; backward chain ±20d). Month inference is the
 *     last resort and is flagged. Test cases for NVDA + INTC (52/53-week).
 *  6. (verifier-side) seeded-fault tolerance-boundary test.
 *  7. GRAB (IFRS filer) reports NOT_AVAILABLE, never a numeric zero.
 *     Denominators reconciled in output.coverage.
 *
 * Usage:
 *   java XbrlHistoryExtractor --tickers NVDA,INTC --out xbrl_history.json
 *   java XbrlHistoryExtractor --validate-reference --xbrl xbrl_history.json
 *   java XbrlHistoryExtractor --self-test
 */
public class XbrlHistoryExtractor {

    static final String SEC_DIR = System.getenv("HOME") + "/workspace/sec_filings";

    /**
     * PRE-REGISTERED DERIVATIONS (2026-10-10, per Claude v3-preflight verdict).
     * Fixed before the pilot; not tuned after seeing results.
     *  D&A: tag DepreciationDepletionAndAmortization (cash-flow statement).
     *       Intangibles amortization INCLUDED (the standard CF tag includes
     *       amortization of finite-lived intangibles).
     *  Working capital: balance-sheet definition. WC_t = CurrentAssets_t −
     *       CurrentLiabilities_t (all-in, cash included). ΔWC = WC_t − WC_{t−1}.
     *       Known limitation: includes cash; fixed anyway.
     *  NOPAT: OperatingIncome × (1 − effective tax rate). Effective rate =
     *       IncomeTaxExpenseBenefit / IncomeLossFromContinuingOperationsBeforeIncomeTaxes,
     *       per year, clamped to [0, 0.50]. Missing inputs → MISSING (never an
     *       assumed rate).
     * These fields have no second source: a V4 miss on them is ambiguous between
     * NLM extraction error and definition error, and N4 routing must say so.
     */
    static final String DERIVATIONS_NOTE =
        "D&A=DepreciationDepletionAndAmortization (intangibles amortization included); "
        + "WC=CurrentAssets-CurrentLiabilities (cash included), dWC=WC_t-WC_{t-1}; "
        + "NOPAT=OperatingIncome*(1-tax_rate), tax_rate=IncomeTaxExpenseBenefit/"
        + "IncomeLossFromContinuingOperationsBeforeIncomeTaxes per-year clamped [0,0.50]. "
        + "Fixed 2026-10-10; not tuned post-hoc.";

    /** Field -> ordered tag candidates (us-gaap). First usable 10-K data wins. */
    static final Map<String, List<String>> TAG_CHAINS = new LinkedHashMap<>();
    static {
        TAG_CHAINS.put("capex", List.of(
            "PaymentsToAcquirePropertyPlantAndEquipment",
            "PaymentsToAcquireProductiveAssets"));
        TAG_CHAINS.put("acquisitions", List.of(
            "PaymentsToAcquireBusinessesNetOfCashAcquired",
            "PaymentsToAcquireBusinessesNetOfCashAcquiredAndNoncontrollingInterest"));
        TAG_CHAINS.put("ocf", List.of(
            "NetCashProvidedByUsedInOperatingActivities",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations"));
        TAG_CHAINS.put("dna", List.of(
            "DepreciationDepletionAndAmortization"));
        TAG_CHAINS.put("opinc", List.of(
            "OperatingIncomeLoss"));
        TAG_CHAINS.put("tax_expense", List.of(
            "IncomeTaxExpenseBenefit"));
        TAG_CHAINS.put("pretax", List.of(
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxes",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesIncludingPortionAttributableToNoncontrollingInterest",
            "ProfitLoss"));
        TAG_CHAINS.put("equity", List.of(
            "StockholdersEquity",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"));
        TAG_CHAINS.put("debt_lt", List.of(
            "LongTermDebt", "LongTermDebtNoncurrent"));
        TAG_CHAINS.put("debt_st", List.of(
            "ShortTermBorrowings", "DebtCurrent"));
        TAG_CHAINS.put("leases", List.of(
            "OperatingLeaseLiability"));
        TAG_CHAINS.put("cash", List.of(
            "CashAndCashEquivalentsAtCarryingValue",
            "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents"));
        TAG_CHAINS.put("curr_assets", List.of("AssetsCurrent"));
        TAG_CHAINS.put("curr_liab", List.of("LiabilitiesCurrent"));
    }

    /** Balance-sheet (instant) fields skip the duration check. */
    static final Set<String> INSTANT_FIELDS = Set.of(
        "equity", "debt_lt", "debt_st", "leases", "cash", "curr_assets", "curr_liab");

    /** Ticker -> sec_filings directory. */
    static final Map<String, String> TICKER_DIRS = Map.ofEntries(
        Map.entry("NVDA", "nvidia"), Map.entry("INTC", "intel"), Map.entry("MSFT", "microsoft"),
        Map.entry("META", "meta"), Map.entry("PLTR", "palantir"), Map.entry("JPM", "jpmorgan"),
        Map.entry("AMZN", "amazon"), Map.entry("AMD", "amd"), Map.entry("AVGO", "broadcom"),
        Map.entry("BRK.B", "berkshire"), Map.entry("CELH", "celsius"), Map.entry("CRM", "salesforce"),
        Map.entry("CVX", "chevron"), Map.entry("GOOG", "alphabet"), Map.entry("GRAB", "grab"),
        Map.entry("IREN", "iren"), Map.entry("JNJ", "jnj"), Map.entry("LLY", "lilly"),
        Map.entry("MA", "mastercard"), Map.entry("NFLX", "netflix"), Map.entry("NOW", "servicenow"),
        Map.entry("ORCL", "oracle"), Map.entry("PFE", "pfizer"), Map.entry("QCOM", "qualcomm"),
        Map.entry("QXO", "qxo"), Map.entry("RH", "rh"), Map.entry("SOFI", "sofi"),
        Map.entry("TSLA", "tesla"), Map.entry("UNH", "unitedhealth"), Map.entry("V", "visa"),
        Map.entry("WH", "wyndham"), Map.entry("XOM", "exxon"));

    /** Denominator reconciliation (correction 7). */
    static final int WATCHLIST_N = 32;
    static final int DCF_INPUTS_N = 28; // AVGO, GRAB, PFE, QCOM absent
    static final List<String> DCF_INPUTS_ABSENT = List.of("AVGO", "GRAB", "PFE", "QCOM");

    record FactPoint(String end, String filed, String accn, double valueUsd,
                     String tag, boolean accnFallback) {}
    record Anchor(String end, long fy) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        if (args.length > 0 && args[0].equals("--validate-reference")) {
            String xbrl = "xbrl_history.json";
            for (int i = 0; i < args.length; i++)
                if (args[i].equals("--xbrl")) xbrl = args[++i];
            validateReference(xbrl);
            return;
        }
        String tickersArg = null, out = "xbrl_history.json";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--tickers")) tickersArg = args[++i];
            if (args[i].equals("--out")) out = args[++i];
        }
        List<String> tickers = tickersArg == null
            ? new ArrayList<>(TICKER_DIRS.keySet())
            : Arrays.asList(tickersArg.split(","));
        JSONObject root = new JSONObject();
        root.put("extracted_at", java.time.Instant.now().toString());
        root.put("unit", "USD millions");
        root.put("source", "SEC companyfacts XBRL (10-K, USD); accession-preferred to on-disk 10-K");
        root.put("derivations", DERIVATIONS_NOTE);
        JSONObject coverage = new JSONObject();
        coverage.put("watchlist_tickers", WATCHLIST_N);
        coverage.put("dcf_inputs_all_tickers", DCF_INPUTS_N);
        coverage.put("dcf_inputs_all_absent", new JSONArray(DCF_INPUTS_ABSENT));
        coverage.put("note", "28 = 32 minus AVGO/GRAB/PFE/QCOM (never extracted); "
            + "xbrl_history covers 31 (GRAB=NOT_AVAILABLE, IFRS filer).");
        root.put("coverage", coverage);
        JSONObject tickersObj = new JSONObject();
        int ok = 0;
        for (String t : tickers) {
            JSONObject tj = extractTicker(t);
            tickersObj.put(t, tj);
            if (!tj.has("status") || !tj.getString("status").equals("NOT_AVAILABLE")) ok++;
        }
        coverage.put("xbrl_ok", ok);
        coverage.put("xbrl_not_available", tickers.size() - ok);
        root.put("tickers", tickersObj);
        Files.writeString(Path.of(out), root.toString(1));
        System.out.println("wrote " + out + " (" + ok + " ok, "
            + (tickers.size() - ok) + " NOT_AVAILABLE)");
    }

    static JSONObject extractTicker(String ticker) throws Exception {
        JSONObject out = new JSONObject();
        String dir = TICKER_DIRS.get(ticker);
        if (dir == null) { out.put("error", "no dir mapping"); return out; }
        out.put("dir", dir);
        Path[] files = Files.list(Path.of(SEC_DIR, dir))
            .filter(p -> p.getFileName().toString().startsWith("companyfacts_"))
            .toArray(Path[]::new);
        if (files.length == 0) { out.put("error", "no companyfacts file"); return out; }
        JSONObject usGaap;
        try {
            usGaap = new JSONObject(readMaybeGzip(files[0]))
                .getJSONObject("facts").getJSONObject("us-gaap");
        } catch (Exception e) {
            // IFRS filer (GRAB): no us-gaap section -> NOT_AVAILABLE, never zero.
            out.put("status", "NOT_AVAILABLE");
            out.put("reason", "no us-gaap facts in companyfacts (IFRS filer); "
                + "MISSING-never-zero rule: reported as NOT_AVAILABLE, not 0.");
            return out;
        }

        // On-disk 10-K accession (correction 1).
        String onDiskFiled = onDisk10KFiledDate(dir);
        String onDiskAccn = onDiskFiled == null ? null : findAccn(usGaap, onDiskFiled);
        out.put("on_disk_10k_filed", onDiskFiled == null ? JSONObject.NULL : onDiskFiled);
        out.put("on_disk_accn", onDiskAccn == null ? JSONObject.NULL : onDiskAccn);

        // Per-field: end -> best FactPoint (on-disk accession preferred; else
        // latest filed, flagged accnFallback=true).
        Map<String, Map<String, FactPoint>> fieldData = new LinkedHashMap<>();
        Map<String, String> fieldTag = new LinkedHashMap<>();
        for (var e : TAG_CHAINS.entrySet()) {
            String field = e.getKey();
            String bestTag = null;
            Map<String, FactPoint> bestPts = null;
            String bestMaxEnd = "";
            for (String tag : e.getValue()) {
                if (!usGaap.has(tag)) continue;
                Map<String, FactPoint> pts =
                    collectPoints(usGaap.getJSONObject(tag), field, tag, onDiskAccn);
                if (pts.isEmpty()) continue;
                String maxEnd = Collections.max(pts.keySet());
                if (maxEnd.compareTo(bestMaxEnd) > 0) {
                    bestMaxEnd = maxEnd;
                    bestTag = tag;
                    bestPts = pts;
                }
            }
            if (bestTag != null) {
                fieldData.put(field, bestPts);
                fieldTag.put(field, bestTag);
            } else {
                fieldTag.put(field, "MISSING");
            }
        }

        // FY anchors from the on-disk accession (correction 5).
        List<Anchor> anchors = buildAnchors(usGaap, onDiskAccn);
        int fyeMonth = inferFyeMonth(fieldData);

        // Union of period ends, latest 4 buckets.
        TreeSet<String> ends = new TreeSet<>(Collections.reverseOrder());
        for (var m : fieldData.values()) ends.addAll(m.keySet());
        // IFRS filer with a near-empty us-gaap section (GRAB): no usable 10-K
        // history -> NOT_AVAILABLE, never a numeric zero (correction 7).
        if (ends.isEmpty()) {
            out.put("status", "NOT_AVAILABLE");
            out.put("reason", "us-gaap section has no usable 10-K facts (IFRS filer); "
                + "MISSING-never-zero rule: reported as NOT_AVAILABLE, not 0.");
            return out;
        }
        List<String> keep = new ArrayList<>(ends).subList(0, Math.min(4, ends.size()));

        JSONObject years = new JSONObject();
        List<String> fyFlags = new ArrayList<>();
        // For derived fields we need FY-sorted raw values.
        Map<Long, Map<String, Double>> rawByFy = new TreeMap<>(Collections.reverseOrder());
        Map<Long, String> fyToEnd = new HashMap<>();
        Map<Long, Boolean> fyAccnFallback = new HashMap<>();
        for (String end : keep) {
            long[] fyOut = new long[1];
            boolean[] flagOut = new boolean[1];
            long fy = labelFY(end, anchors, fyeMonth, fyOut, flagOut);
            JSONObject y = new JSONObject();
            y.put("period_end", end);
            y.put("fy_label_method",
                flagOut[0] ? "month_fallback" : (anchors.isEmpty() ? "month_fallback" : "accession_anchor"));
            Map<String, Double> raw = new HashMap<>();
            boolean anyFallback = false;
            for (String field : TAG_CHAINS.keySet()) {
                Map<String, FactPoint> m = fieldData.get(field);
                if (m != null && m.containsKey(end)) {
                    FactPoint p = m.get(end);
                    JSONObject f = new JSONObject();
                    f.put("value_m", Math.round(p.valueUsd() / 1e6 * 100.0) / 100.0);
                    f.put("tag", p.tag());
                    f.put("filed", p.filed());
                    f.put("accn", p.accn());
                    if (p.accnFallback()) {
                        f.put("accn_fallback", true);
                        anyFallback = true;
                    }
                    y.put(field, f);
                    raw.put(field, p.valueUsd() / 1e6);
                } else {
                    y.put(field, "MISSING");
                }
            }
            // Merge if two ends map to the same FY label (prefer the later end).
            String key = String.valueOf(fy);
            if (!years.has(key)
                    || end.compareTo(years.getJSONObject(key).getString("period_end")) > 0) {
                years.put(key, y);
                rawByFy.put(fy, raw);
                fyToEnd.put(fy, end);
                fyAccnFallback.put(fy, anyFallback);
            }
            if (flagOut[0]) fyFlags.add(end + "->FY" + fy + " via month_fallback");
        }

        // Derived fields (pre-registered derivations).
        List<Long> fys = new ArrayList<>(rawByFy.keySet());
        Collections.sort(fys);
        for (int i = 0; i < fys.size(); i++) {
            long fy = fys.get(i);
            JSONObject y = years.getJSONObject(String.valueOf(fy));
            Map<String, Double> raw = rawByFy.get(fy);
            // tax_rate_derived
            Double taxRate = null;
            Double te = raw.get("tax_expense"), pt = raw.get("pretax");
            if (te != null && pt != null && pt != 0) {
                taxRate = Math.min(Math.max(te / pt, 0.0), 0.50);
            }
            y.put("tax_rate_derived", taxRate == null ? "MISSING"
                : Math.round(taxRate * 10000.0) / 10000.0);
            // nopat_derived
            Double op = raw.get("opinc");
            Double nopat = (op != null && taxRate != null) ? op * (1 - taxRate) : null;
            y.put("nopat_derived", nopat == null ? "MISSING"
                : (Object) (Math.round(nopat * 100.0) / 100.0));
            // delta_wc_derived needs the prior year
            Double dwc = null;
            if (i > 0) {
                Map<String, Double> prev = rawByFy.get(fys.get(i - 1));
                Double ca = raw.get("curr_assets"), cl = raw.get("curr_liab");
                Double pca = prev.get("curr_assets"), pcl = prev.get("curr_liab");
                if (ca != null && cl != null && pca != null && pcl != null) {
                    dwc = (ca - cl) - (pca - pcl);
                }
            }
            y.put("delta_wc_derived", dwc == null ? "MISSING"
                : (Object) (Math.round(dwc * 100.0) / 100.0));
            if (fyAccnFallback.getOrDefault(fy, false)) y.put("accn_fallback_any", true);
        }

        out.put("tag_used", new JSONObject(fieldTag));
        out.put("fye_month_inferred", fyeMonth);
        out.put("fy_label_flags", new JSONArray(fyFlags));
        out.put("years", years);
        return out;
    }

    /** Filed date of the on-disk 10-K, from its filename (10-K_YYYY-MM-DD_*). */
    static String onDisk10KFiledDate(String dir) throws Exception {
        try (var s = Files.list(Path.of(SEC_DIR, dir))) {
            var m = s.filter(p -> p.getFileName().toString().matches("10-K_\\d{4}-\\d{2}-\\d{2}_.*"))
                .map(p -> {
                    var mm = Pattern.compile("10-K_(\\d{4}-\\d{2}-\\d{2})_")
                        .matcher(p.getFileName().toString());
                    return mm.find() ? mm.group(1) : null;
                })
                .filter(Objects::nonNull)
                .max(String::compareTo);
            return m.orElse(null);
        }
    }

    /** Accession of the on-disk 10-K: first 10-K fact with that filed date. */
    static String findAccn(JSONObject usGaap, String filedDate) {
        for (String tag : List.of("AssetsCurrent", "OperatingIncomeLoss",
                "PaymentsToAcquirePropertyPlantAndEquipment")) {
            if (!usGaap.has(tag)) continue;
            JSONObject units = usGaap.getJSONObject(tag).optJSONObject("units");
            if (units == null || !units.has("USD")) continue;
            JSONArray arr = units.getJSONArray("USD");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject v = arr.getJSONObject(i);
                if ("10-K".equals(v.optString("form"))
                        && filedDate.equals(v.optString("filed"))) {
                    return v.optString("accn", null);
                }
            }
        }
        return null;
    }

    /** FY anchors: distinct 10-K period ends under the on-disk accession, desc. */
    static List<Anchor> buildAnchors(JSONObject usGaap, String onDiskAccn) {
        List<Anchor> anchors = new ArrayList<>();
        if (onDiskAccn == null) return anchors;
        TreeSet<String> ends = new TreeSet<>(Collections.reverseOrder());
        long filingFy = -1;
        for (String tag : List.of("AssetsCurrent", "OperatingIncomeLoss")) {
            if (!usGaap.has(tag)) continue;
            JSONObject units = usGaap.getJSONObject(tag).optJSONObject("units");
            if (units == null || !units.has("USD")) continue;
            JSONArray arr = units.getJSONArray("USD");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject v = arr.getJSONObject(i);
                if (!"10-K".equals(v.optString("form"))) continue;
                if (!onDiskAccn.equals(v.optString("accn"))) continue;
                if (v.has("end")) ends.add(v.getString("end"));
                filingFy = Math.max(filingFy, v.optLong("fy", -1));
            }
        }
        int i = 0;
        for (String end : ends) anchors.add(new Anchor(end, filingFy - i++));
        return anchors;
    }

    static int inferFyeMonth(Map<String, Map<String, FactPoint>> fieldData) {
        String latestEnd = null;
        for (String f : INSTANT_FIELDS) {
            Map<String, FactPoint> m = fieldData.get(f);
            if (m == null) continue;
            for (String end : m.keySet())
                if (latestEnd == null || end.compareTo(latestEnd) > 0) latestEnd = end;
        }
        return latestEnd == null ? 12 : Integer.parseInt(latestEnd.substring(5, 7));
    }

    /**
     * FY label keyed on period end with tolerance (correction 5).
     * 1. ±10d match to an accession anchor -> anchor FY.
     * 2. Backward chain: within ±20d of earliestAnchor − k×365.25d -> earliestFy − k.
     * 3. Month inference (flagged).
     */
    static long labelFY(String end, List<Anchor> anchors, int fyeMonth,
                        long[] fyOut, boolean[] flagOut) {
        LocalDate d = LocalDate.parse(end);
        for (Anchor a : anchors) {
            if (Math.abs(ChronoUnit.DAYS.between(d, LocalDate.parse(a.end()))) <= 10) {
                fyOut[0] = a.fy();
                flagOut[0] = false;
                return a.fy();
            }
        }
        if (!anchors.isEmpty()) {
            Anchor earliest = anchors.get(anchors.size() - 1);
            LocalDate ed = LocalDate.parse(earliest.end());
            for (int k = 1; k <= 4; k++) {
                LocalDate cand = ed.minusDays(Math.round(k * 365.25));
                if (Math.abs(ChronoUnit.DAYS.between(d, cand)) <= 20) {
                    fyOut[0] = earliest.fy() - k;
                    flagOut[0] = false;
                    return earliest.fy() - k;
                }
            }
        }
        // Last resort: month inference (flagged).
        int y = Integer.parseInt(end.substring(0, 4));
        int m = Integer.parseInt(end.substring(5, 7));
        fyOut[0] = m <= fyeMonth ? y : y + 1;
        flagOut[0] = true;
        return fyOut[0];
    }

    /**
     * Collect 10-K annual points for one tag. Dedupe by period END DATE;
     * the on-disk accession's fact wins; otherwise latest filed wins and the
     * point is flagged accnFallback=true.
     */
    static Map<String, FactPoint> collectPoints(JSONObject tagObj, String field,
                                               String tag, String onDiskAccn) {
        Map<String, FactPoint> best = new HashMap<>();
        JSONObject units = tagObj.optJSONObject("units");
        if (units == null) return best;
        String unit = units.has("USD") ? "USD"
            : (units.keys().hasNext() ? units.keys().next() : null);
        if (unit == null || !unit.equals("USD")) return best;
        JSONArray arr = units.getJSONArray(unit);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.getJSONObject(i);
            if (!"10-K".equals(v.optString("form"))) continue;
            String end = v.optString("end", null);
            if (end == null) continue;
            if (!INSTANT_FIELDS.contains(field)) {
                String start = v.optString("start", null);
                if (start == null) continue;
                long days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end));
                if (days < 350 || days > 380) continue; // annual flow only
            }
            double val = v.optDouble("val", Double.NaN);
            if (Double.isNaN(val)) continue;
            String filed = v.optString("filed", "");
            String accn = v.optString("accn", "");
            boolean isOnDisk = onDiskAccn != null && onDiskAccn.equals(accn);
            FactPoint cur = best.get(end);
            boolean take = false;
            if (cur == null) take = true;
            else if (isOnDisk && cur.accnFallback()) take = true;   // accession beats fallback
            else if (isOnDisk == !cur.accnFallback()
                    && filed.compareTo(cur.filed()) > 0) take = true; // tie-break: later filed
            if (take) {
                best.put(end, new FactPoint(end, filed, accn, val, tag,
                    onDiskAccn != null && !isOnDisk));
            }
        }
        return best;
    }

    /** Read a file, transparently decompressing gzip. */
    static String readMaybeGzip(Path p) throws Exception {
        byte[] raw = Files.readAllBytes(p);
        if (raw.length > 2 && raw[0] == (byte) 0x1f && raw[1] == (byte) 0x8b) {
            try (var in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(raw))) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Parse a pipeline value that may be a Number, numeric String, or "not found". */
    static Double toDouble(Object pv) {
        if (pv == null) return null;
        if (pv instanceof Number) return ((Number) pv).doubleValue();
        String s = String.valueOf(pv).replaceAll("[,$]", "").trim();
        if (s.isEmpty() || s.equalsIgnoreCase("not found")
                || s.equalsIgnoreCase("n/a") || s.equals("-")) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Correction 3: diff XBRL reference vs dcf_inputs_all.json (28 companies,
     * latest-year capex + OCF). Lists anything over 3% before the pilot.
     * Costs nothing in NLM runs.
     */
    static void validateReference(String xbrlFile) throws Exception {
        JSONObject xbrl = new JSONObject(Files.readString(Path.of(xbrlFile)));
        JSONObject xTickers = xbrl.getJSONObject("tickers");
        JSONObject companies = new JSONObject(Files.readString(
            Path.of(SEC_DIR, "dcf_inputs_all.json"))).getJSONObject("companies");
        Pattern datePat = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Pattern fyPat = Pattern.compile("FY(\\d{4})");
        int checked = 0, flagged = 0;
        List<String> flags = new ArrayList<>();
        System.out.println("ticker field pipe_xbrl rel_diff status");
        for (String t : new TreeSet<>(companies.keySet())) {
            JSONObject annual = companies.getJSONObject(t).getJSONObject("annual");
            JSONObject xT = xTickers.optJSONObject(t);
            if (xT == null || xT.has("status")) {
                System.out.println(t + " SKIP (no XBRL reference)");
                continue;
            }
            JSONObject xYears = xT.getJSONObject("years");
            // Match the XBRL year: prefer exact period-end date, else FY label.
            JSONObject xY = null;
            var perM = datePat.matcher(annual.optString("period", ""));
            if (perM.find()) {
                String periodEnd = perM.group(1);
                for (String fy : xYears.keySet()) {
                    if (periodEnd.equals(xYears.getJSONObject(fy).optString("period_end"))) {
                        xY = xYears.getJSONObject(fy);
                        break;
                    }
                }
            }
            if (xY == null) {
                var fyM = fyPat.matcher(annual.optString("period", ""));
                if (fyM.find() && xYears.has(fyM.group(1)))
                    xY = xYears.getJSONObject(fyM.group(1));
            }
            if (xY == null) { System.out.println(t + " SKIP (FY not in XBRL)"); continue; }
            for (String f : List.of("capex", "ocf")) {
                Object pv = annual.opt(f);
                Double pipe = toDouble(pv);
                if (pipe == null) {
                    System.out.println(t + " " + f + " SKIP (pipeline: " + pv + ")");
                    continue;
                }
                Object xv = xY.opt(f);
                if (!(xv instanceof JSONObject)) {
                    System.out.println(t + " " + f + " SKIP (XBRL MISSING)");
                    continue;
                }
                double xv2 = ((JSONObject) xv).getDouble("value_m");
                double rel = pipe == 0 ? (xv2 == 0 ? 0 : 1)
                    : Math.abs(xv2 - pipe) / Math.abs(pipe);
                checked++;
                boolean over = rel > 0.03;
                if (over) { flagged++; flags.add(t + "." + f); }
                System.out.printf("%s %s pipe=%.1f xbrl=%.1f rel=%.3f %s%n",
                    t, f, pipe, xv2, rel, over ? "FLAG>3%" : "ok");
            }
        }
        System.out.println("checked=" + checked + " flagged=" + flagged
            + (flags.isEmpty() ? "" : " " + flags));
        if (flagged > 0) System.exit(1);
    }

    // ---------------- self-test ----------------

    static void runSelfTest() throws Exception {
        int pass = 0, fail = 0;

        // 1. NVDA FY2026 capex via ProductiveAssets ~= pipeline 6100 (±5%), and the
        //    point comes from the on-disk 10-K accession (not a later restatement).
        JSONObject nvda = extractTicker("NVDA");
        JSONObject y2026 = nvda.getJSONObject("years").optJSONObject("2026");
        JSONObject cx = y2026 != null ? y2026.optJSONObject("capex") : null;
        double nvdaCapex = cx != null ? cx.getDouble("value_m") : Double.NaN;
        boolean t1 = !Double.isNaN(nvdaCapex) && Math.abs(nvdaCapex - 6100) / 6100 <= 0.05
            && !cx.optBoolean("accn_fallback", false)
            && nvda.optString("on_disk_accn", "").equals(cx.optString("accn", ""));
        System.out.println((t1 ? "PASS" : "FAIL") + " NVDA FY2026 capex=" + nvdaCapex
            + " from on-disk accession (no fallback)");
        if (t1) pass++; else fail++;

        // 2. All 5 non-financial pilot tickers have >=3 FYs of opinc + tax_expense.
        boolean t2 = true;
        for (String t : List.of("NVDA", "INTC", "MSFT", "META", "PLTR")) {
            JSONObject yt = extractTicker(t).getJSONObject("years");
            int n = 0;
            for (String fy : yt.keySet()) {
                JSONObject y = yt.getJSONObject(fy);
                if (y.optJSONObject("opinc") != null && y.optJSONObject("tax_expense") != null) n++;
            }
            if (n < 3) { t2 = false; System.out.println("  short: " + t + " has " + n); }
        }
        System.out.println((t2 ? "PASS" : "FAIL") + " 5 pilot tickers >=3 FYs opinc+tax");
        if (t2) pass++; else fail++;

        // 3. JPM financial control: capex/opinc report MISSING, never zero.
        JSONObject jpm = extractTicker("JPM");
        JSONObject jy = jpm.getJSONObject("years");
        boolean t3 = true;
        for (String fy : jy.keySet()) {
            JSONObject y = jy.getJSONObject(fy);
            if (!"MISSING".equals(y.opt("capex")) || !"MISSING".equals(y.opt("opinc"))) t3 = false;
        }
        System.out.println((t3 ? "PASS" : "FAIL") + " JPM capex/opinc MISSING (no fabrication)");
        if (t3) pass++; else fail++;

        // 4. Prove-can-fail: a bogus tag chain must yield MISSING, not a number.
        Map<String, FactPoint> bogus = collectPoints(
            new JSONObject("{\"units\":{\"USD\":[]}}"), "capex", "NoSuchTag", null);
        boolean t4 = bogus.isEmpty();
        System.out.println((t4 ? "PASS" : "FAIL") + " bogus tag -> empty (prove-can-fail)");
        if (t4) pass++; else fail++;

        // 5. FY labels for 52/53-week filers (correction 5): key on period end.
        //    NVDA: 2026-01-25->FY2026, 2025-01-26->FY2025, 2024-01-28->FY2024.
        //    INTC: 2025-12-27->FY2025, 2024-12-28->FY2024.
        boolean t5 = true;
        JSONObject nvdaYears = nvda.getJSONObject("years");
        for (String[] c : new String[][]{{"2026","2026-01-25"},{"2025","2025-01-26"},{"2024","2024-01-28"}}) {
            JSONObject y = nvdaYears.optJSONObject(c[0]);
            if (y == null || !c[1].equals(y.optString("period_end"))) {
                t5 = false; System.out.println("  NVDA FY" + c[0] + " end mismatch");
            }
        }
        JSONObject intcYears = extractTicker("INTC").getJSONObject("years");
        for (String[] c : new String[][]{{"2025","2025-12-27"},{"2024","2024-12-28"}}) {
            JSONObject y = intcYears.optJSONObject(c[0]);
            if (y == null || !c[1].equals(y.optString("period_end"))) {
                t5 = false; System.out.println("  INTC FY" + c[0] + " end mismatch");
            }
        }
        // No month_fallback flags on the pilot tickers' kept years.
        for (JSONObject yObj : List.of(nvdaYears, intcYears)) {
            for (String fy : yObj.keySet()) {
                if ("month_fallback".equals(yObj.getJSONObject(fy).optString("fy_label_method"))) {
                    t5 = false;
                    System.out.println("  month_fallback used for FY" + fy);
                }
            }
        }
        System.out.println((t5 ? "PASS" : "FAIL") + " NVDA/INTC FY labels via accession anchors");
        if (t5) pass++; else fail++;

        // 6. GRAB reports NOT_AVAILABLE, never a numeric zero (correction 7).
        JSONObject grab = extractTicker("GRAB");
        boolean t6 = "NOT_AVAILABLE".equals(grab.optString("status"));
        System.out.println((t6 ? "PASS" : "FAIL") + " GRAB -> NOT_AVAILABLE");
        if (t6) pass++; else fail++;

        // 7. Derived fields present with pre-registered definitions (correction 4).
        JSONObject my = y2026;
        boolean t7 = my != null
            && my.optJSONObject("tax_expense") != null
            && !"MISSING".equals(my.opt("tax_rate_derived"))
            && !"MISSING".equals(my.opt("nopat_derived"));
        // nopat_derived == opinc * (1 - tax_rate_derived)
        if (t7) {
            double op = my.getJSONObject("opinc").getDouble("value_m");
            double tr = ((Number) my.get("tax_rate_derived")).doubleValue();
            double np = ((Number) my.get("nopat_derived")).doubleValue();
            // 4dp rounding on the stored rate allows 1e-3 relative slack here;
            // the implementation uses full precision internally.
            t7 = Math.abs(np - op * (1 - tr)) / Math.abs(np) < 1e-3;
        }
        System.out.println((t7 ? "PASS" : "FAIL") + " derived nopat/tax_rate per pre-registered defs");
        if (t7) pass++; else fail++;

        // 8. delta_wc_derived = (CA-CL)_t − (CA-CL)_{t-1} on real NVDA years.
        boolean t8 = false;
        JSONObject y2025 = nvdaYears.optJSONObject("2025");
        if (y2026 != null && y2025 != null
                && !"MISSING".equals(y2026.opt("delta_wc_derived"))) {
            double ca26 = y2026.getJSONObject("curr_assets").getDouble("value_m");
            double cl26 = y2026.getJSONObject("curr_liab").getDouble("value_m");
            double ca25 = y2025.getJSONObject("curr_assets").getDouble("value_m");
            double cl25 = y2025.getJSONObject("curr_liab").getDouble("value_m");
            double expect = (ca26 - cl26) - (ca25 - cl25);
            double got = ((Number) y2026.get("delta_wc_derived")).doubleValue();
            t8 = Math.abs(got - expect) < 1.0; // $1M rounding slack
        }
        System.out.println((t8 ? "PASS" : "FAIL") + " delta_wc_derived = Δ(CA−CL)");
        if (t8) pass++; else fail++;

        System.out.println("SELF-TEST: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
