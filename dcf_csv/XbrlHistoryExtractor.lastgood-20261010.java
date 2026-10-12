import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
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
 * Fields: capex, dna, opinc, tax_expense, pretax, equity, debt_lt, debt_st,
 * leases, cash, curr_assets, curr_liab. Missing tags report MISSING (never zero).
 *
 * Usage:
 *   java XbrlHistoryExtractor --tickers NVDA,INTC,MSFT,META,PLTR --out xbrl_history.json
 *   java XbrlHistoryExtractor --self-test
 */
public class XbrlHistoryExtractor {

    static final String SEC_DIR = System.getenv("HOME") + "/workspace/sec_filings";

    /** Field -> ordered tag candidates (us-gaap). First hit with usable 10-K data wins. */
    static final Map<String, List<String>> TAG_CHAINS = new LinkedHashMap<>();
    static {
        TAG_CHAINS.put("capex", List.of(
            "PaymentsToAcquirePropertyPlantAndEquipment",
            "PaymentsToAcquireProductiveAssets"));
        TAG_CHAINS.put("dna", List.of(
            "DepreciationDepletionAndAmortization"));
        TAG_CHAINS.put("opinc", List.of(
            "OperatingIncomeLoss"));
        TAG_CHAINS.put("tax_expense", List.of(
            "IncomeTaxExpenseBenefit"));
        TAG_CHAINS.put("pretax", List.of(
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxes",
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

    record FactPoint(long fy, String end, String filed, double valueUsd, String tag) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
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
        root.put("source", "SEC companyfacts XBRL (10-K, USD)");
        JSONObject tickersObj = new JSONObject();
        for (String t : tickers) {
            tickersObj.put(t, extractTicker(t));
        }
        root.put("tickers", tickersObj);
        Files.writeString(Path.of(out), root.toString(1));
        System.out.println("wrote " + out + " (" + tickers.size() + " tickers)");
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
        JSONObject facts = new JSONObject(readMaybeGzip(files[0]))
            .getJSONObject("facts").getJSONObject("us-gaap");

        // per-field: end-date -> best FactPoint (latest filed wins per period end)
        Map<String, Map<String, FactPoint>> fieldData = new LinkedHashMap<>();
        Map<String, String> fieldTag = new LinkedHashMap<>();
        // Pick the candidate tag with the most current data (latest max period end).
        // A tag with only ancient values must not win over one with recent years.
        for (var e : TAG_CHAINS.entrySet()) {
            String field = e.getKey();
            String bestTag = null;
            Map<String, FactPoint> bestPts = null;
            String bestMaxEnd = "";
            for (String tag : e.getValue()) {
                if (!facts.has(tag)) continue;
                Map<String, FactPoint> pts = collectPoints(facts.getJSONObject(tag), field, tag);
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
        // Infer fiscal year-end month from the latest instant (balance-sheet) period end.
        int fyeMonth = 12;
        String latestEnd = null;
        for (String f : INSTANT_FIELDS) {
            Map<String, FactPoint> m = fieldData.get(f);
            if (m == null) continue;
            for (String end : m.keySet()) {
                if (latestEnd == null || end.compareTo(latestEnd) > 0) latestEnd = end;
            }
        }
        if (latestEnd != null) fyeMonth = Integer.parseInt(latestEnd.substring(5, 7));

        // Union of period ends, latest 4. Label FY from end date + FYE month.
        TreeSet<String> ends = new TreeSet<>(Collections.reverseOrder());
        for (var m : fieldData.values()) ends.addAll(m.keySet());
        List<String> keep = new ArrayList<>(ends).subList(0, Math.min(4, ends.size()));
        JSONObject years = new JSONObject();
        for (String end : keep) {
            long fy = fyLabel(end, fyeMonth);
            JSONObject y = new JSONObject();
            y.put("period_end", end);
            for (String field : TAG_CHAINS.keySet()) {
                Map<String, FactPoint> m = fieldData.get(field);
                if (m != null && m.containsKey(end)) {
                    FactPoint p = m.get(end);
                    JSONObject f = new JSONObject();
                    f.put("value_m", Math.round(p.valueUsd() / 1e6 * 100.0) / 100.0);
                    f.put("tag", p.tag());
                    f.put("filed", p.filed());
                    y.put(field, f);
                } else {
                    y.put(field, "MISSING");
                }
            }
            // Merge if two ends map to the same FY label (prefer the later end).
            String key = String.valueOf(fy);
            if (!years.has(key) || end.compareTo(years.getJSONObject(key).getString("period_end")) > 0) {
                years.put(key, y);
            }
        }
        out.put("tag_used", new JSONObject(fieldTag));
        out.put("fye_month_inferred", fyeMonth);
        out.put("years", years);
        return out;
    }

    /** FY label from period end + fiscal year-end month. */
    static long fyLabel(String end, int fyeMonth) {
        int y = Integer.parseInt(end.substring(0, 4));
        int m = Integer.parseInt(end.substring(5, 7));
        return m <= fyeMonth ? y : y + 1;
    }

    /** Collect 10-K annual points for one tag; dedupe by period END DATE, latest filed wins. */
    static Map<String, FactPoint> collectPoints(JSONObject tagObj, String field, String tag) {
        Map<String, FactPoint> best = new HashMap<>();
        JSONObject units = tagObj.optJSONObject("units");
        if (units == null) return best;
        String unit = units.has("USD") ? "USD"
            : (units.keys().hasNext() ? units.keys().next() : null);
        if (unit == null) return best;
        boolean usd = unit.equals("USD");
        JSONArray arr = units.getJSONArray(unit);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.getJSONObject(i);
            if (!"10-K".equals(v.optString("form"))) continue;
            long fy = v.optLong("fy", -1);
            if (fy < 0) continue;
            String start = v.optString("start", null);
            String end = v.optString("end", null);
            if (!INSTANT_FIELDS.contains(field)) {
                if (start == null || end == null) continue;
                long days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end));
                if (days < 350 || days > 380) continue; // annual flow only
            } else {
                if (end == null) continue;
            }
            double val = v.optDouble("val", Double.NaN);
            if (Double.isNaN(val)) continue;
            if (!usd) continue; // non-USD units not comparable; skip rather than convert silently
            String filed = v.optString("filed", "");
            FactPoint cur = best.get(end);
            if (cur == null || filed.compareTo(cur.filed()) > 0) {
                best.put(end, new FactPoint(fy, end, filed, val, tag));
            }
        }
        return best;
    }

    /** Read a file, transparently decompressing gzip (SEC serves gzip when requested). */
    static String readMaybeGzip(Path p) throws Exception {
        byte[] raw = Files.readAllBytes(p);
        if (raw.length > 2 && raw[0] == (byte) 0x1f && raw[1] == (byte) 0x8b) {
            try (var in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(raw))) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------------- self-test ----------------

    static void runSelfTest() throws Exception {
        int pass = 0, fail = 0;
        // 1. NVDA FY2026 capex via ProductiveAssets ~= pipeline 6100 (cross-source check, ±5%)
        JSONObject nvda = extractTicker("NVDA");
        JSONObject y2026 = nvda.getJSONObject("years").optJSONObject("2026");
        double nvdaCapex = y2026 != null && y2026.optJSONObject("capex") != null
            ? y2026.getJSONObject("capex").getDouble("value_m") : Double.NaN;
        boolean t1 = !Double.isNaN(nvdaCapex) && Math.abs(nvdaCapex - 6100) / 6100 <= 0.05;
        System.out.println((t1 ? "PASS" : "FAIL") + " NVDA FY2026 capex=" + nvdaCapex + " vs pipeline 6100");
        if (t1) pass++; else fail++;

        // 2. All 5 non-financial pilot tickers have >=3 FYs of opinc + tax_expense
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

        // 3. JPM financial control: capex/opinc report MISSING, never zero
        JSONObject jpm = extractTicker("JPM");
        JSONObject jy = jpm.getJSONObject("years");
        boolean t3 = true;
        for (String fy : jy.keySet()) {
            JSONObject y = jy.getJSONObject(fy);
            Object cx = y.opt("capex"), oi = y.opt("opinc");
            if (!"MISSING".equals(cx) || !"MISSING".equals(oi)) { t3 = false; }
        }
        System.out.println((t3 ? "PASS" : "FAIL") + " JPM capex/opinc MISSING (no fabrication)");
        if (t3) pass++; else fail++;

        // 4. Prove-can-fail: a bogus tag chain must yield MISSING, not a number
        Map<String, FactPoint> bogus = collectPoints(
            new JSONObject("{\"units\":{\"USD\":[]}}"), "capex", "NoSuchTag");
        boolean t4 = bogus.isEmpty();
        System.out.println((t4 ? "PASS" : "FAIL") + " bogus tag -> empty (prove-can-fail)");
        if (t4) pass++; else fail++;

        System.out.println("SELF-TEST: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
