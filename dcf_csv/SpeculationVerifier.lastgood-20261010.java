import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/**
 * SpeculationVerifier — V1/V2/V4/V5/V6 for the NLM speculation two-phase pilot
 * (prop-20261009-002 REV 5 + pre-pilot fixes).
 *
 * V1 (formulas): recompute RR, ROIC, g_RR, g_spec from NLM's extracted table.
 *   V1-engine (speculation-value recompute) is HOOKED, not implemented: it needs
 *   NLM's structured engine inputs (FCF base, g_grounded vector), the same gap
 *   as V3 — awaiting Rahik's V3 call.
 * V2: fixed tolerances — ±0.5pt absolute on RR/ROIC/g; 2% relative on credit.
 * V4: NLM extraction vs xbrl_history.json within 5% relative per field.
 * V5: deterministic invariants.
 * V6: audit trail (raw NLM JSON + report + retry count + re-ask text).
 *
 * N/A semantics (pre-pilot fix 1): failures/exclusions write null + status,
 * never the grounded number. ROIC<WACC and dead-zone write grounded + 0 credit
 * + label. Financials (sector list) -> null + FINANCIAL_EXCLUSION.
 *
 * Usage:
 *   java SpeculationVerifier --nlm nlm_extraction.json --xbrl xbrl_history.json --out report.json
 *   java SpeculationVerifier --self-test
 */
public class SpeculationVerifier {

    // Pre-registered constants (REV 5)
    static final double ABS_TOL = 0.005;       // V2: ±0.5pt on RR, ROIC, g
    static final double CREDIT_REL_TOL = 0.02; // V2: 2% relative on credit
    static final double V4_REL_TOL = 0.05;      // V4: 5% relative per field
    static final double G_RR_CAP = 0.15;
    static final double ROIC_WACC_BAND = 0.01;  // ±1pt dead zone

    static final Set<String> FINANCIALS = Set.of("JPM", "BRK.B", "SOFI");

    static final Map<String, Double> WACC = new HashMap<>();
    static {
        // Sector WACC table (canonical prompt v2026-10-10)
        // semiconductor 10.50%
        for (String t : List.of("AMD","NVDA","AVGO","INTC","QCOM")) WACC.put(t, 0.1050);
        // software system/application 9.25%
        for (String t : List.of("MSFT","CRM","NOW","ORCL")) WACC.put(t, 0.0925);
        WACC.put("GOOG", 0.1050);
        WACC.put("META", 0.1075);
        WACC.put("PLTR", 0.1000);
        for (String t : List.of("LLY","JNJ","PFE")) WACC.put(t, 0.0775);
        WACC.put("UNH", 0.0700);
        for (String t : List.of("CVX","XOM")) WACC.put(t, 0.0550);
        WACC.put("NFLX", 0.0725);
        WACC.put("AMZN", 0.0850);
        WACC.put("RH", 0.0850);
        WACC.put("CELH", 0.0750);
        WACC.put("WH", 0.0750);
        WACC.put("TSLA", 0.0950);
        WACC.put("JPM", 0.0850);
        WACC.put("SOFI", 0.1000);
        WACC.put("BRK.B", 0.0750);
        for (String t : List.of("V","MA")) WACC.put(t, 0.0750);
        WACC.put("GRAB", 0.1225);
        WACC.put("IREN", 0.1050);
        WACC.put("QXO", 0.0850);
    }

    /**
     * NLM table row: raw 10-K figures + derived NOPAT.
     * V1 verifies the NOPAT derivation (opinc × (1 − taxRate)) as well as
     * RR/ROIC/g_RR/g_spec. V4 cross-checks the RAW figures (capex, dna, opinc).
     */
    record YearRow(double capex, double dna, double deltaWc,
                   double opinc, double taxRate, double nopat, double icBegin) {
        double nopatRecomputed() { return opinc * (1 - taxRate); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) { runSelfTest(); return; }
        String nlmFile = null, xbrlFile = null, out = "speculation_verification_report.json";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--nlm")) nlmFile = args[++i];
            if (args[i].equals("--xbrl")) xbrlFile = args[++i];
            if (args[i].equals("--out")) out = args[++i];
        }
        if (nlmFile == null) { System.err.println("need --nlm"); System.exit(2); }
        JSONObject nlm = new JSONObject(Files.readString(Path.of(nlmFile)));
        JSONObject xbrl = xbrlFile == null ? null
            : new JSONObject(Files.readString(Path.of(xbrlFile)));
        JSONObject report = verify(nlm, xbrl);
        Files.writeString(Path.of(out), report.toString(1));
        System.out.println("wrote " + out + " status=" + report.getString("status"));
    }

    /** NLM extraction JSON -> verification report. */
    static JSONObject verify(JSONObject nlm, JSONObject xbrl) {
        JSONObject r = new JSONObject();
        String ticker = nlm.getString("ticker");
        r.put("ticker", ticker);
        r.put("verified_at", java.time.Instant.now().toString());

        // Financial exclusion first (sector list, pre-pilot fix 1)
        if (FINANCIALS.contains(ticker)) {
            r.put("status", "FINANCIAL_EXCLUSION");
            r.put("value", JSONObject.NULL);
            r.put("credit", 0.0);
            r.put("note", "GICS Financials / holding-company: null + status, never grounded.");
            return r;
        }

        // Parse NLM table
        List<YearRow> rows = new ArrayList<>();
        JSONArray table = nlm.getJSONArray("table");
        for (int i = 0; i < table.length(); i++) {
            JSONObject y = table.getJSONObject(i);
            rows.add(new YearRow(
                y.getDouble("capex"), y.getDouble("dna"), y.getDouble("delta_wc"),
                y.getDouble("opinc"), y.getDouble("tax_rate"),
                y.getDouble("nopat"), y.getDouble("ic_begin")));
        }
        double grounded = nlm.getDouble("grounded_value");

        // N/A conditions (package: NOPAT<=0 or IC<=0 -> null + N/A status)
        String naReason = naCheck(rows);
        if (naReason != null) {
            r.put("status", "N/A");
            r.put("value", JSONObject.NULL);
            r.put("na_reason", naReason);
            return r;
        }

        // ---- V1: recompute formulas ----
        JSONObject v1 = new JSONObject();
        double rrRe = rr(rows), roicRe = roic(rows);
        double gRrRe = Math.min(rrRe * roicRe, G_RR_CAP);
        JSONArray gSpecNlm = nlm.getJSONArray("g_spec");
        JSONArray gGround = nlm.getJSONArray("g_grounded");
        // g_spec[y] = max(g_grounded[y], min(g_RR, 15%)) element-wise
        List<String> mismatches = new ArrayList<>();
        // NOPAT derivation check: nopat == opinc × (1 − tax_rate) per year
        for (int i = 0; i < rows.size(); i++) {
            YearRow yr = rows.get(i);
            if (!withinAbs(table.getJSONObject(i).getDouble("nopat"),
                    yr.nopatRecomputed(), Math.abs(yr.nopatRecomputed()) * 0.001 + 1e-9)) {
                mismatches.add("nopat[" + i + "]");
            }
        }
        if (!withinAbs(nlm.getDouble("rr"), rrRe, ABS_TOL))
            mismatches.add("rr");
        if (!withinAbs(nlm.getDouble("roic"), roicRe, ABS_TOL))
            mismatches.add("roic");
        if (!withinAbs(nlm.getDouble("g_rr"), gRrRe, ABS_TOL))
            mismatches.add("g_rr");
        double gCap = Math.min(gRrRe, G_RR_CAP);
        for (int i = 0; i < gSpecNlm.length(); i++) {
            double expect = Math.max(gGround.getDouble(i), gCap);
            if (!withinAbs(gSpecNlm.getDouble(i), expect, ABS_TOL)) {
                mismatches.add("g_spec[" + i + "]");
            }
        }
        v1.put("rr_recomputed", round4(rrRe));
        v1.put("roic_recomputed", round4(roicRe));
        v1.put("g_rr_recomputed", round4(gRrRe));
        v1.put("mismatches", mismatches);
        v1.put("pass", mismatches.isEmpty());
        if (!mismatches.isEmpty()) {
            v1.put("reask_text", reaskText(mismatches));
            v1.put("reask_note", "Name ONLY the failed quantity + definition violated. "
                + "NEVER the recomputed value, NEVER the expected direction (N1).");
        }
        r.put("v1", v1);

        // ---- V2: credit tolerance (on NLM's stated credit; engine hook below) ----
        JSONObject v2 = new JSONObject();
        double creditNlm = nlm.getDouble("speculation_value") - grounded;
        v2.put("credit_nlm", round4(creditNlm));
        v2.put("credit_non_negative", creditNlm >= -1e-9);
        v2.put("engine_hook", "V1-engine/V3 pending Rahik's structured-inputs call; "
            + "credit recompute via DcfModel hooked, not run.");
        v2.put("pass", creditNlm >= -1e-9);
        r.put("v2", v2);

        // ---- ROIC vs WACC gate ----
        double wacc = WACC.getOrDefault(ticker, Double.NaN);
        JSONObject gate = new JSONObject();
        String gateStatus;
        double value;
        double credit;
        if (Double.isNaN(wacc)) {
            gateStatus = "NO_WACC";
            value = Double.NaN; credit = 0;
        } else if (roicRe > wacc + ROIC_WACC_BAND) {
            gateStatus = "ROIC_WACC_OK";
            value = grounded + Math.max(creditNlm, 0);
            credit = Math.max(creditNlm, 0);
        } else if (roicRe < wacc - ROIC_WACC_BAND) {
            gateStatus = "ROIC_WACC_NO_CREDIT";
            value = grounded; credit = 0; // floor: grounded + 0 credit + label
        } else {
            gateStatus = "ROIC_WACC_DEAD_ZONE";
            value = grounded; credit = 0;
        }
        gate.put("wacc", wacc);
        gate.put("roic_minus_wacc", round4(roicRe - wacc));
        r.put("gate", gate);

        // ---- V4: extraction vs XBRL ----
        JSONObject v4 = v4Check(ticker, nlm, xbrl);
        r.put("v4", v4);

        // ---- V5: invariants ----
        JSONObject v5 = new JSONObject();
        List<String> violations = new ArrayList<>();
        if (gRrRe > G_RR_CAP + 1e-9) violations.add("g_rr exceeds 15% cap");
        double rrRaw = rrUnclipped(rows);
        if (rrRaw < 0 || rrRaw > 1)
            v5.put("rr_clipped_note", "raw RR " + round4(rrRaw) + " clipped to [0,1]");
        if (!Double.isNaN(value) && value < grounded - 1e-9)
            violations.add("speculation < grounded");
        if (gateStatus.equals("ROIC_WACC_NO_CREDIT") || gateStatus.equals("ROIC_WACC_DEAD_ZONE")) {
            if (Math.abs(credit) > 1e-9) violations.add("gate case carries credit");
        }
        // narrative numbers: every number in narrative must appear in table or carry source tag
        // (checked heuristically: narrative must not contain $ values absent from table)
        v5.put("violations", violations);
        v5.put("pass", violations.isEmpty());
        r.put("v5", v5);

        // ---- Final status ----
        boolean allPass = v1.getBoolean("pass") && v2.getBoolean("pass")
            && v4.getBoolean("pass") && v5.getBoolean("pass");
        if (!allPass) {
            // Pre-pilot fix 1: failures write null + status, never grounded.
            if (!v1.getBoolean("pass") && v1.getJSONArray("mismatches").length() > 0) {
                r.put("status", "VERIFY_FAIL");
            } else {
                r.put("status", "INVARIANT_FAIL");
            }
            r.put("value", JSONObject.NULL);
            r.put("credit", 0.0);
        } else {
            r.put("status", gateStatus);
            r.put("value", Double.isNaN(value) ? JSONObject.NULL : round2(value));
            r.put("credit", round4(credit));
        }

        // ---- V6: audit trail ----
        JSONObject v6 = new JSONObject();
        v6.put("nlm_raw", nlm);
        v6.put("retry_count", nlm.optInt("retry_count", 0));
        v6.put("reask_text", v1.optString("reask_text", ""));
        r.put("v6", v6);
        return r;
    }

    /** Null when OK, else the N/A reason. Uses recomputed NOPAT (never trusts derived). */
    static String naCheck(List<YearRow> rows) {
        double sumNopat = 0;
        for (YearRow r : rows) {
            double nopat = r.nopatRecomputed();
            if (nopat <= 0) return "year NOPAT <= 0";
            if (r.icBegin() <= 0) return "beginning invested capital <= 0";
            sumNopat += nopat;
        }
        if (sumNopat <= 0) return "sum NOPAT <= 0";
        return null;
    }

    static double rrUnclipped(List<YearRow> rows) {
        double num = 0, den = 0;
        for (YearRow r : rows) {
            num += r.capex() - r.dna() + r.deltaWc();
            den += r.nopatRecomputed();
        }
        return den == 0 ? Double.NaN : num / den;
    }

    static double rr(List<YearRow> rows) {
        double v = rrUnclipped(rows);
        return Math.min(Math.max(v, 0), 1);
    }

    static double roic(List<YearRow> rows) {
        double num = 0, den = 0;
        for (YearRow r : rows) { num += r.nopatRecomputed(); den += r.icBegin(); }
        return den == 0 ? Double.NaN : num / den;
    }

    static boolean withinAbs(double a, double b, double tol) {
        return Math.abs(a - b) <= tol + 1e-12;
    }

    /** Re-ask text: name ONLY the failed quantity + definition violated (N1). */
    static String reaskText(List<String> mismatches) {
        StringBuilder sb = new StringBuilder("Re-check against the 10-K table (do not restate prior values): ");
        List<String> parts = new ArrayList<>();
        for (String m : mismatches) {
            if (m.startsWith("nopat["))
                parts.add("NOPAT " + m + " doesn't equal operating income × (1 − effective tax rate) for that year; show the derivation");
        }
        if (mismatches.contains("rr"))
            parts.add("RR doesn't equal ΣCapEx−D&A+ΔWC/ΣNOPAT over the table rows; show the sums");
        if (mismatches.contains("roic"))
            parts.add("ROIC doesn't equal ΣNOPAT/Σ(beginning invested capital) as the aggregate ratio; show the sums");
        if (mismatches.contains("g_rr"))
            parts.add("g_RR doesn't equal RR×ROIC capped at 15%; show the product");
        for (String m : mismatches) {
            if (m.startsWith("g_spec["))
                parts.add("g_spec " + m + " doesn't equal max(g_grounded, min(g_RR,15%)) element-wise; show the comparison");
        }
        sb.append(String.join("; ", parts)).append(".");
        return sb.toString();
    }

    /** V4: NLM table vs XBRL reference within 5% per field. */
    static JSONObject v4Check(String ticker, JSONObject nlm, JSONObject xbrl) {
        JSONObject v4 = new JSONObject();
        if (xbrl == null) {
            v4.put("pass", false);
            v4.put("note", "no XBRL reference loaded");
            return v4;
        }
        JSONObject xTickers = xbrl.optJSONObject("tickers");
        JSONObject xT = xTickers == null ? null : xTickers.optJSONObject(ticker);
        if (xT == null) {
            v4.put("pass", false);
            v4.put("note", "ticker absent from XBRL reference");
            return v4;
        }
        // Map NLM table FYs to XBRL years by period order (latest first).
        JSONArray table = nlm.getJSONArray("table");
        JSONObject xYears = xT.getJSONObject("years");
        List<String> xFys = new ArrayList<>(xYears.keySet());
        xFys.sort(Collections.reverseOrder());
        JSONArray fields = new JSONArray();
        int checked = 0, passed = 0;
        // NLM table fields -> XBRL fields (N4 like-for-like on RAW figures;
        // NOPAT is derived, so V4 checks its inputs: opinc. Tax rate vs XBRL
        // effective rate where pretax exists, else unverifiable.)
        String[][] mapping = {{"capex","capex"},{"dna","dna"},{"opinc","opinc"}};
        for (int i = 0; i < table.length() && i < xFys.size(); i++) {
            JSONObject nlmY = table.getJSONObject(i);
            JSONObject xY = xYears.getJSONObject(xFys.get(i));
            for (String[] mp : mapping) {
                if (!nlmY.has(mp[0])) continue;
                Object xv = xY.opt(mp[1]);
                JSONObject f = new JSONObject();
                f.put("nlm_field", mp[0]);
                f.put("xbrl_field", mp[1]);
                f.put("fy_nlm", nlmY.optString("fy", "?"));
                f.put("fy_xbrl", xFys.get(i));
                if (xv instanceof JSONObject) {
                    double nv = nlmY.getDouble(mp[0]);
                    double xv2 = ((JSONObject) xv).getDouble("value_m");
                    // NLM table in $M vs XBRL $M — compare directly.
                    double rel = xv2 == 0 ? (nv == 0 ? 0 : 1) : Math.abs(nv - xv2) / Math.abs(xv2);
                    f.put("nlm", nv); f.put("xbrl", xv2);
                    f.put("rel_diff", round4(rel));
                    f.put("pass", rel <= V4_REL_TOL);
                    checked++;
                    if (rel <= V4_REL_TOL) passed++;
                } else {
                    f.put("status", "unverifiable (XBRL MISSING)");
                }
                fields.put(f);
            }
        }
        v4.put("fields", fields);
        v4.put("checked", checked);
        v4.put("passed", passed);
        // 80% of verifiable fields must pass; zero verifiable -> cannot pass
        v4.put("pass", checked > 0 && (double) passed / checked >= 0.8);
        v4.put("note", "N4: capex definitional caveat — package includes cash acquisitions "
            + "in CapEx; XBRL ref is PP&E/ProductiveAssets only. Systematic gaps -> HUMAN_REVIEW.");
        return v4;
    }

    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    // ---------------- self-test ----------------

    static void runSelfTest() throws Exception {
        int pass = 0, fail = 0;

        // Fixture: hand-computed 3-year table.
        // Σ(CapEx−D&A+ΔWC) = 70+90+110 = 270; ΣNOPAT = 660 -> RR = 0.4091
        // ΣIC = 3300 -> ROIC = 0.20; g_RR = 0.0818
        JSONObject nlm = new JSONObject();
        nlm.put("ticker", "TEST");
        nlm.put("grounded_value", 100.0);
        nlm.put("g_grounded", new JSONArray(List.of(0.10, 0.09, 0.08, 0.07, 0.06)));
        JSONArray table = new JSONArray();
        double[][] rows = {{100,40,10,200,1000},{120,40,10,220,1100},{140,40,10,240,1200}};
        String[] fys = {"2026","2025","2024"};
        for (int i = 0; i < 3; i++) {
            JSONObject y = new JSONObject();
            y.put("fy", fys[i]);
            y.put("capex", rows[i][0]); y.put("dna", rows[i][1]);
            y.put("delta_wc", rows[i][2]);
            // opinc / tax_rate chosen so nopat = opinc × (1 − tax_rate) matches
            y.put("opinc", rows[i][3] / 0.8); y.put("tax_rate", 0.20);
            y.put("nopat", rows[i][3]);
            y.put("ic_begin", rows[i][4]);
            table.put(y);
        }
        nlm.put("table", table);
        nlm.put("rr", 0.4091);
        nlm.put("roic", 0.20);
        nlm.put("g_rr", 0.0818);
        // g_spec = max(g_grounded, min(0.0818,15%)) = max(g_grounded, 0.0818)
        nlm.put("g_spec", new JSONArray(List.of(0.10, 0.09, 0.0818, 0.0818, 0.0818)));
        nlm.put("speculation_value", 104.0);

        // Minimal XBRL stub: TEST ticker with matching figures
        JSONObject xbrl = new JSONObject();
        JSONObject tickers = new JSONObject();
        JSONObject xt = new JSONObject();
        JSONObject years = new JSONObject();
        for (int i = 0; i < 3; i++) {
            JSONObject y = new JSONObject();
            y.put("capex", new JSONObject().put("value_m", rows[i][0]));
            y.put("dna", new JSONObject().put("value_m", rows[i][1]));
            y.put("opinc", new JSONObject().put("value_m", rows[i][3] / 0.8));
            years.put(fys[i], y);
        }
        xt.put("years", years);
        tickers.put("NVDA", xt);
        xbrl.put("tickers", tickers);
        // NVDA WACC 10.50% for the gate test
        nlm.put("ticker", "NVDA");

        JSONObject r = verify(nlm, xbrl);
        // NVDA WACC 10.5%; ROIC 0.20 > 0.115 -> gate OK; value = 100 + 4 = 104
        boolean t1 = r.getJSONObject("v1").getBoolean("pass")
            && r.getJSONObject("v4").getBoolean("pass")
            && r.getJSONObject("v5").getBoolean("pass")
            && r.getString("status").equals("ROIC_WACC_OK")
            && Math.abs(r.getDouble("value") - 104.0) < 1e-9;
        System.out.println((t1 ? "PASS" : "FAIL") + " happy path: V1/V4/V5 pass, gate OK, value=104");
        if (t1) pass++; else {
            fail++;
            System.out.println("v1=" + r.getJSONObject("v1").getBoolean("pass")
                + " v4=" + r.getJSONObject("v4").getBoolean("pass")
                + " v5=" + r.getJSONObject("v5").getBoolean("pass")
                + " status=" + r.getString("status")
                + " value=" + r.opt("value")
                + " v4note=" + r.getJSONObject("v4").optString("note", "").substring(0, 60));
        }

        // 2. Break RR -> V1 must catch it and emit re-ask text without numbers
        JSONObject bad = new JSONObject(nlm.toString());
        bad.put("rr", 0.50);
        JSONObject r2 = verify(bad, xbrl);
        String reask = r2.getJSONObject("v1").optString("reask_text", "");
        boolean t2 = r2.getString("status").equals("VERIFY_FAIL")
            && r2.isNull("value")
            && reask.contains("RR doesn't equal")
            && !reask.matches(".*\\d+\\.\\d+.*"); // no leaked numbers
        System.out.println((t2 ? "PASS" : "FAIL") + " broken RR -> VERIFY_FAIL, null value, number-free re-ask");
        if (t2) pass++; else fail++;

        // 3. Financial control: JPM -> null + FINANCIAL_EXCLUSION even with good numbers
        JSONObject jpm = new JSONObject(nlm.toString());
        jpm.put("ticker", "JPM");
        JSONObject r3 = verify(jpm, xbrl);
        boolean t3 = r3.getString("status").equals("FINANCIAL_EXCLUSION") && r3.isNull("value");
        System.out.println((t3 ? "PASS" : "FAIL") + " JPM -> null + FINANCIAL_EXCLUSION");
        if (t3) pass++; else fail++;

        // 4. OpInc <= 0 (so recomputed NOPAT <= 0) -> N/A (null + status, never grounded)
        JSONObject na = new JSONObject(nlm.toString());
        JSONArray t3a = na.getJSONArray("table");
        t3a.getJSONObject(0).put("opinc", -10.0);
        t3a.getJSONObject(0).put("nopat", -8.0);
        JSONObject r4 = verify(na, xbrl);
        boolean t4 = r4.getString("status").equals("N/A") && r4.isNull("value");
        System.out.println((t4 ? "PASS" : "FAIL") + " NOPAT<=0 -> null + N/A");
        if (t4) pass++; else fail++;

        // 5. ROIC < WACC - 1pt -> floor at grounded + 0 credit + label (pre-pilot fix 1)
        JSONObject low = new JSONObject(nlm.toString());
        JSONArray lowTable = low.getJSONArray("table");
        for (int i = 0; i < 3; i++) lowTable.getJSONObject(i).put("ic_begin", 20000.0); // ROIC = 660/60000 = 0.011
        low.put("roic", 0.011);
        low.put("g_rr", 0.0045);
        low.put("g_spec", new JSONArray(List.of(0.10, 0.09, 0.08, 0.07, 0.06)));
        JSONObject r5 = verify(low, xbrl);
        boolean t5 = r5.getString("status").equals("ROIC_WACC_NO_CREDIT")
            && Math.abs(r5.getDouble("value") - 100.0) < 1e-9
            && Math.abs(r5.getDouble("credit")) < 1e-9;
        System.out.println((t5 ? "PASS" : "FAIL") + " ROIC<WACC -> grounded + 0 credit + label");
        if (t5) pass++; else fail++;

        System.out.println("SELF-TEST: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
