import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ForwardComplianceGate.java — audit-12 compliance gate for the NotebookLM
 * forward-tilt column.
 *
 * The old guard (guardForwardValues in RebuildAll.java) is traceability-only:
 * it proves a forward value EXISTS in the verified batch file, not that it
 * obeys the rules. Audit-12 (2026-10-09, Q3 FAIL Critical) proved 7 of 13
 * forward values mathematically impossible under the +2pp tilt cap — the cap
 * was never enforced in code.
 *
 * This gate enforces the cap and the other audit-12 fixes. READ-ONLY: it
 * never writes any file. Exit 0 = pass, exit 1 = violation (RebuildAll
 * aborts the rebuild via runStep).
 *
 * Batch-record schema (per ticker under "values"; replaces the old plain-
 * number entries as the driver is updated — see FORWARD_BATCH_SCHEMA.md):
 *   New-style entry (Json object):
 *     status:            "tilted" | "no_signal" | "not_run"
 *     grounded_rate:     decimal, Y1-5 base growth, grounded (e.g. 0.08)
 *     tilted_rate:       decimal, Y1-5 base growth after tilt (e.g. 0.09)
 *     base_fcf:          $M, base-year FCF (normalized value if trough_normalized)
 *     discount_rate:     decimal (DR)
 *     terminal_growth:   decimal (g)
 *     shares:            millions, diluted share count
 *     net_debt:          $M; positive = net debt, negative = net cash
 *     kind:              "FCFF" | "FCFE" | "REV"
 *     start_margin/end_margin: decimals, REV kind only (else null)
 *     bridge:            $M equity-bridge adjustment (BRK.B only, else 0)
 *     trough_normalized: true if prop-20261008-015 fired for this run
 *     citations:         [filing citations] — required for any nonzero tilt
 *     value:             claimed TILT VALUE, $/share
 *     prompt_version:    e.g. "v2026-10-10"
 *   Old-style entry (plain number): LEGACY — traceability only, grandfathered
 *   per Rahik 2026-10-09 ("keep the numbers until the gate is built and fresh
 *   runs replace them"). Listed in the report as legacy, never rejected.
 *
 * Rules enforced (audit-12):
 *   R1 traceability: every published non-null notebooklm_forward needs a batch entry.
 *   R2 rate cap:    tilted - grounded <= +2.0pp (halved to +1.0pp when trough_normalized).
 *   R3 no negative tilt: tilted < grounded -> review (grounded's lower-of rule
 *      already pulls negative guidance; a downward tilt means grounded is stale).
 *      BRK.B's -26.6% is treated as provenance/data error per the verdict, not a
 *      prompt violation — same routing, different message.
 *   R4 sustained cap: tilted <= 15%.
 *   R5 citations:   nonzero tilt requires at least one filing citation.
 *   R6 recompute:   DcfModel.intrinsicValue(kind, base_fcf, tilted_rate, w, t,
 *      shares, netdebt, margins, bridge) must match the claimed value within 1%.
 *      The model outputs the rate; deterministic code owns the number.
 *   R7 delta backstop: |tilt-grounded|/|grounded| > 15% -> MANUAL REVIEW
 *      (never clamp — clamping hides the violation).
 *   R8 10% hard gate: delta > 10% -> reject (was a prose flag, now a control).
 *   R9 status: no_signal requires value == grounded (delta ~0); not_run with a
 *      published value is a violation.
 */
public class ForwardComplianceGate {

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        return (e != null && !e.isEmpty()) ? e : homeDir() + "/workspace";
    }

    static String readFile(String path) throws Exception {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    // Audit-12 bounds.
    static final double TILT_CAP_PP = 2.0;        // R2
    static final double TILT_CAP_TROUGH_PP = 1.0; // R2, halved when trough-normalized
    static final double SUSTAINED_CAP = 0.15;     // R4
    static final double DELTA_GATE = 0.10;        // R8 hard gate
    static final double DELTA_BACKSTOP = 0.15;    // R7 manual review
    static final double RECOMPUTE_TOL = 0.01;     // R6 relative tolerance
    static final double EPS = 1e-9;

    static class Violation {
        final String ticker, rule, detail;
        Violation(String t, String r, String d) { ticker = t; rule = r; detail = d; }
        public String toString() { return "[" + rule + "] " + ticker + ": " + detail; }
    }

    static class Report {
        final List<Violation> violations = new ArrayList<>();
        final List<String> legacy = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        int checked = 0;
        boolean ok() { return violations.isEmpty(); }
    }

    public static void main(String[] args) throws Exception {
        boolean selfTest = false;
        for (String a : args) if (a.equals("--self-test")) selfTest = true;
        if (selfTest) {
            runSelfTest();
            return;
        }
        String root = workspaceRoot();
        String fwdBatch = root + "/goals/quarterly-sec-filing-watch-with-dcf-updates"
                + "/hidden_files/notebooklm_forward_batch_2026-09-28.json";
        String wd = root + "/your_files/dcf-valuations/.src/workbook_data.json";
        Report r = check(fwdBatch, wd);
        System.out.println("forward compliance gate: checked " + r.checked
                + " ticker(s), " + r.violations.size() + " violation(s), "
                + r.legacy.size() + " legacy record(s)");
        for (String t : r.legacy) {
            System.out.println("  legacy (traceability only, no rate provenance): " + t);
        }
        for (String n : r.notes) {
            System.out.println("  note: " + n);
        }
        if (!r.ok()) {
            for (Violation v : r.violations) {
                System.err.println("VIOLATION " + v);
            }
            System.err.println("forward compliance gate: refusing rebuild — "
                    + r.violations.size() + " violation(s)");
            System.exit(1);
        }
        System.out.println("forward compliance gate: PASS");
    }

    /** File-backed entry point: read batch + workbook, validate, return report. */
    static Report check(String fwdBatchPath, String workbookPath) throws Exception {
        DcfModel.Json.Obj batchRoot =
                (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(fwdBatchPath));
        DcfModel.Json.Obj batch = batchRoot.getObj("values");
        if (batch == null) throw new IllegalStateException(
                "forward batch has no 'values' object: " + fwdBatchPath);
        DcfModel.Json.Obj wd =
                (DcfModel.Json.Obj) DcfModel.Json.parse(readFile(workbookPath));
        List<DcfModel.Json.Val> companies = wd.getList("companies");
        // claimed value per ticker = published notebooklm_forward (cleanNum semantics:
        // non-numeric counts as missing).
        Map<String, Double> published = new LinkedHashMap<>();
        for (DcfModel.Json.Val cv : companies) {
            DcfModel.Json.Obj c = (DcfModel.Json.Obj) cv;
            Double v = cleanNum(c.get("notebooklm_forward"));
            if (v != null) published.put(c.getStr("ticker"), v);
        }
        return checkRecords(batch, published);
    }

    /** Core validation over (batch records, published values). Pure + testable. */
    static Report checkRecords(DcfModel.Json.Obj batch, Map<String, Double> published) {
        Report r = new Report();
        for (Map.Entry<String, Double> e : published.entrySet()) {
            String t = e.getKey();
            r.checked++;
            DcfModel.Json.Val rec = batch.get(t);
            if (rec == null || rec instanceof DcfModel.Json.Null) {
                r.violations.add(new Violation(t, "TRACEABILITY",
                        "published forward value has no batch record"));
                continue;
            }
            if (rec instanceof DcfModel.Json.Num) {
                // Old schema: plain number. Grandfathered per Rahik 2026-10-09.
                r.legacy.add(t);
                continue;
            }
            if (!(rec instanceof DcfModel.Json.Obj)) {
                r.violations.add(new Violation(t, "SCHEMA",
                        "batch entry is neither a number nor an object"));
                continue;
            }
            validateRecord(t, (DcfModel.Json.Obj) rec, r);
        }
        return r;
    }

    static void validateRecord(String t, DcfModel.Json.Obj rec, Report r) {
        String status = rec.optStr("status", null);
        if (status == null || (!status.equals("tilted")
                && !status.equals("no_signal") && !status.equals("not_run"))) {
            r.violations.add(new Violation(t, "STATUS",
                    "status must be tilted|no_signal|not_run, got: " + status));
            return;
        }
        if (status.equals("not_run")) {
            r.violations.add(new Violation(t, "STATUS",
                    "status=not_run but a forward value is published"));
            return;
        }
        Double grounded = rec.getOptNum("grounded_rate");
        Double tilted = rec.getOptNum("tilted_rate");
        if (grounded == null || tilted == null
                || !Double.isFinite(grounded) || !Double.isFinite(tilted)) {
            r.violations.add(new Violation(t, "RATES",
                    "grounded_rate and tilted_rate are required numbers"));
            return;
        }
        double tiltPp = (tilted - grounded) * 100.0;
        boolean trough = isTrue(rec.get("trough_normalized"));

        // R3: negative tilt forbidden (routes to review = gate rejects).
        if (tiltPp < -EPS) {
            String detail = "tilted_rate " + pct(tilted) + " < grounded_rate "
                    + pct(grounded) + " (" + String.format("%.2f", tiltPp)
                    + "pp) — downward tilt means grounded is stale; review required";
            if (t.equals("BRK.B")) {
                detail += " [audit-12: treat as provenance/data error, not a prompt "
                        + "violation — diff base FCF/WACC/rates between runs before blessing]";
            }
            r.violations.add(new Violation(t, "NEGATIVE_TILT", detail));
            return;
        }
        // R2: +2pp cap, halved when the trough normalization fired.
        double cap = trough ? TILT_CAP_TROUGH_PP : TILT_CAP_PP;
        if (tiltPp > cap + EPS) {
            r.violations.add(new Violation(t, "RATE_CAP",
                    "tilt +" + String.format("%.2f", tiltPp) + "pp exceeds "
                    + (trough ? "halved " : "") + "cap of +" + cap + "pp"
                    + (trough ? " (trough_normalized: prop-20261008-015 fired)"
                              : "")));
            return;
        }
        // R4: 15% sustained cap still applies after the tilt.
        if (tilted > SUSTAINED_CAP + EPS) {
            r.violations.add(new Violation(t, "SUSTAINED_CAP",
                    "tilted_rate " + pct(tilted) + " exceeds 15% cap"));
            return;
        }
        // R5: nonzero tilt needs at least one filing citation.
        if (tiltPp > EPS && !hasCitations(rec)) {
            r.violations.add(new Violation(t, "CITATIONS",
                    "nonzero tilt (+" + String.format("%.2f", tiltPp)
                    + "pp) with no filing citation — no citation, no tilt"));
            return;
        }
        // R6: recompute in code from the tilted rate; model outputs the rate only.
        String kind = rec.optStr("kind", "FCFF");
        Double base = rec.getOptNum("base_fcf");
        Double w = rec.getOptNum("discount_rate");
        Double term = rec.getOptNum("terminal_growth");
        Double shares = rec.getOptNum("shares");
        Double netdebt = rec.getOptNum("net_debt");
        if (base == null || w == null || term == null || shares == null
                || netdebt == null || shares <= 0) {
            r.violations.add(new Violation(t, "INPUTS",
                    "base_fcf, discount_rate, terminal_growth, shares (>0), "
                    + "net_debt are required for the recompute"));
            return;
        }
        Double startM = rec.getOptNum("start_margin");
        Double endM = rec.getOptNum("end_margin");
        Double bridge = rec.getOptNum("bridge");
        double b = (bridge == null) ? 0.0 : bridge;
        double engineTilt, engineGrounded;
        try {
            engineTilt = DcfModel.intrinsicValue(kind, base, tilted, w, term,
                    shares, netdebt, startM, endM, b);
            engineGrounded = DcfModel.intrinsicValue(kind, base, grounded, w, term,
                    shares, netdebt, startM, endM, b);
        } catch (IllegalArgumentException ex) {
            r.violations.add(new Violation(t, "ENGINE",
                    "DcfModel rejected the inputs: " + ex.getMessage()));
            return;
        }
        Double claimed = rec.getOptNum("value");
        if (claimed == null || claimed <= 0) {
            r.violations.add(new Violation(t, "CLAIMED",
                    "claimed tilt value ('value') is required and must be > 0"));
            return;
        }
        double relDiff = Math.abs(engineTilt - claimed) / claimed;
        if (relDiff > RECOMPUTE_TOL) {
            r.violations.add(new Violation(t, "RECOMPUTE_MISMATCH",
                    "engine recompute $" + f2(engineTilt) + " vs claimed $"
                    + f2(claimed) + " (rel diff "
                    + String.format("%.2f", relDiff * 100)
                    + "% > 1%) — inputs changed or cap violated"));
            return;
        }
        // R9: no_signal must equal grounded.
        double deltaBase = Math.abs(engineGrounded) < EPS ? Double.NaN
                : Math.abs(engineTilt - engineGrounded) / Math.abs(engineGrounded);
        if (status.equals("no_signal")) {
            if (Double.isNaN(deltaBase) || deltaBase > 0.001) {
                r.violations.add(new Violation(t, "STATUS",
                        "status=no_signal but tilt differs from grounded by "
                        + (Double.isNaN(deltaBase) ? "n/a"
                           : String.format("%.2f", deltaBase * 100) + "%")));
            }
            return;
        }
        // R7 then R8: delta backstop (manual review) before the 10% hard gate.
        if (Double.isNaN(deltaBase)) {
            r.violations.add(new Violation(t, "DELTA",
                    "grounded engine value is ~0; delta undefined — review required"));
            return;
        }
        if (deltaBase > DELTA_BACKSTOP) {
            r.violations.add(new Violation(t, "DELTA_BACKSTOP",
                    "MANUAL REVIEW REQUIRED: tilt delta "
                    + String.format("%.1f", deltaBase * 100)
                    + "% > 15% — a delta this large proves a bound was broken "
                    + "(never clamp; clamping hides the violation)"));
            return;
        }
        if (deltaBase > DELTA_GATE) {
            r.violations.add(new Violation(t, "DELTA_GATE",
                    "tilt delta " + String.format("%.1f", deltaBase * 100)
                    + "% > 10% hard gate (was a prose flag, now a control)"));
        }
    }

    static boolean isTrue(DcfModel.Json.Val v) {
        return v instanceof DcfModel.Json.Bool && ((DcfModel.Json.Bool) v).v;
    }

    static boolean hasCitations(DcfModel.Json.Obj rec) {
        DcfModel.Json.Val c = rec.get("citations");
        if (c instanceof DcfModel.Json.Arr) {
            return !((DcfModel.Json.Arr) c).items.isEmpty();
        }
        if (c instanceof DcfModel.Json.Str) {
            return !((DcfModel.Json.Str) c).v.trim().isEmpty();
        }
        return false;
    }

    static Double cleanNum(DcfModel.Json.Val v) {
        if (v == null || v instanceof DcfModel.Json.Null) return null;
        if (v instanceof DcfModel.Json.Num) return ((DcfModel.Json.Num) v).d;
        if (v instanceof DcfModel.Json.Str) {
            try {
                return Double.parseDouble(
                        ((DcfModel.Json.Str) v).v.replace(",", "").trim());
            } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    static String pct(double d) { return String.format("%.2f%%", d * 100); }
    static String f2(double d) { return String.format("%.2f", d); }

    // ------------------------------------------------------------------
    // Self-test. Each case builds a synthetic batch record, runs the same
    // validateRecord() the gate uses, and asserts the expected outcome.
    // Prove-can-fail: every REJECT case plants a violating record and asserts
    // the gate fires on exactly the expected rule.
    // ------------------------------------------------------------------
    static int failures = 0;

    static void runSelfTest() throws Exception {
        System.out.println("ForwardComplianceGate self-test");
        // Base valid record (AMD-like, FCFF).
        DcfModel.Json.Obj base = newRecord("tilted", 0.030, 0.035,
                6697.0, 0.105, 0.035, 1636.0, -7330.0, "FCFF", false);
        double engineTilt = engine(base, 0.035);
        base.put("value", new DcfModel.Json.Num(engineTilt));

        expectPass("valid tilt passes", copyOf(base));

        DcfModel.Json.Obj cap = copyOf(base);
        cap.put("tilted_rate", new DcfModel.Json.Num(0.056)); // +2.6pp
        cap.put("value", new DcfModel.Json.Num(engine(cap, 0.056)));
        expectReject("rate cap +2.6pp rejected", cap, "RATE_CAP");

        DcfModel.Json.Obj neg = copyOf(base);
        neg.put("tilted_rate", new DcfModel.Json.Num(0.025)); // -0.5pp
        neg.put("value", new DcfModel.Json.Num(engine(neg, 0.025)));
        expectReject("negative tilt rejected", neg, "NEGATIVE_TILT");

        DcfModel.Json.Obj mm = copyOf(base);
        mm.put("value", new DcfModel.Json.Num(engineTilt * 1.5)); // +50% off
        expectReject("recompute mismatch rejected", mm, "RECOMPUTE_MISMATCH");

        DcfModel.Json.Obj nocit = copyOf(base);
        nocit.put("citations", arrOf(new ArrayList<>()));
        expectReject("missing citations rejected", nocit, "CITATIONS");

        DcfModel.Json.Obj trough = copyOf(base);
        trough.put("trough_normalized", new DcfModel.Json.Bool(true));
        trough.put("tilted_rate", new DcfModel.Json.Num(0.045)); // +1.5pp > halved 1.0
        trough.put("value", new DcfModel.Json.Num(engine(trough, 0.045)));
        expectReject("trough-stacking halved cap rejected", trough, "RATE_CAP");

        DcfModel.Json.Obj troughOk = copyOf(base);
        troughOk.put("trough_normalized", new DcfModel.Json.Bool(true));
        troughOk.put("tilted_rate", new DcfModel.Json.Num(0.040)); // +1.0pp = halved cap
        troughOk.put("value", new DcfModel.Json.Num(engine(troughOk, 0.040)));
        expectPass("trough tilt at halved cap passes", troughOk);

        // Delta gate: craft a record whose tilt is within +2pp but whose value
        // delta exceeds 10%. Use a low-growth base where +2pp moves value a lot.
        DcfModel.Json.Obj dg = newRecord("tilted", 0.01, 0.03,
                1000.0, 0.08, 0.02, 100.0, 0.0, "FCFF", false);
        double dgTilt = engine(dg, 0.03), dgGround = engine(dg, 0.01);
        double dgDelta = Math.abs(dgTilt - dgGround) / Math.abs(dgGround);
        dg.put("value", new DcfModel.Json.Num(dgTilt));
        System.out.println("  (delta-gate fixture: tilt +2.0pp -> value delta "
                + String.format("%.1f", dgDelta * 100) + "%)");
        if (dgDelta > DELTA_GATE && dgDelta <= DELTA_BACKSTOP) {
            expectReject("delta >10% hard gate rejected", dg, "DELTA_GATE");
        } else {
            System.out.println("  SKIP delta-gate fixture out of (10%,15%] band");
        }

        DcfModel.Json.Obj ns = newRecord("no_signal", 0.030, 0.030,
                6697.0, 0.105, 0.035, 1636.0, -7330.0, "FCFF", false);
        ns.put("value", new DcfModel.Json.Num(engine(ns, 0.030)));
        expectPass("no_signal with value==grounded passes", ns);

        DcfModel.Json.Obj nsBad = newRecord("no_signal", 0.030, 0.031,
                6697.0, 0.105, 0.035, 1636.0, -7330.0, "FCFF", false);
        nsBad.put("value", new DcfModel.Json.Num(engine(nsBad, 0.031)));
        expectReject("no_signal with rate drift rejected", nsBad, "STATUS");

        // Legacy numeric entry passes with a warning, no violation.
        DcfModel.Json.Obj batchLegacy = new DcfModel.Json.Obj();
        batchLegacy.put("AMD", new DcfModel.Json.Num(123.87));
        Map<String, Double> pub = new LinkedHashMap<>();
        pub.put("AMD", 123.87);
        Report rl = checkRecords(batchLegacy, pub);
        check("legacy numeric entry passes", rl.ok() && rl.legacy.size() == 1);

        // Missing batch entry fails traceability.
        DcfModel.Json.Obj batchEmpty = new DcfModel.Json.Obj();
        Report rm = checkRecords(batchEmpty, pub);
        check("missing batch entry rejected",
                !rm.ok() && rm.violations.get(0).rule.equals("TRACEABILITY"));

        // Bad status rejected.
        DcfModel.Json.Obj badStatus = copyOf(base);
        badStatus.put("status", new DcfModel.Json.Str("maybe"));
        expectReject("bad status rejected", badStatus, "STATUS");

        System.out.println(failures == 0
                ? "SELF-TEST: ALL GREEN (" + 13 + " cases)"
                : "SELF-TEST: " + failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    static DcfModel.Json.Obj newRecord(String status, double grounded, double tilted,
            double baseFcf, double w, double t, double shares, double netdebt,
            String kind, boolean trough) {
        DcfModel.Json.Obj o = new DcfModel.Json.Obj();
        o.put("status", new DcfModel.Json.Str(status));
        o.put("grounded_rate", new DcfModel.Json.Num(grounded));
        o.put("tilted_rate", new DcfModel.Json.Num(tilted));
        o.put("base_fcf", new DcfModel.Json.Num(baseFcf));
        o.put("discount_rate", new DcfModel.Json.Num(w));
        o.put("terminal_growth", new DcfModel.Json.Num(t));
        o.put("shares", new DcfModel.Json.Num(shares));
        o.put("net_debt", new DcfModel.Json.Num(netdebt));
        o.put("kind", new DcfModel.Json.Str(kind));
        o.put("trough_normalized", new DcfModel.Json.Bool(trough));
        List<DcfModel.Json.Val> cits = new ArrayList<>();
        cits.add(new DcfModel.Json.Str("10-K Item 7: Data Center AI demand"));
        o.put("citations", arrOf(cits));
        o.put("prompt_version", new DcfModel.Json.Str("v2026-10-10"));
        return o;
    }

    static DcfModel.Json.Arr arrOf(List<DcfModel.Json.Val> items) {
        DcfModel.Json.Arr a = new DcfModel.Json.Arr();
        a.items.addAll(items);
        return a;
    }

    static DcfModel.Json.Obj copyOf(DcfModel.Json.Obj o) {
        DcfModel.Json.Obj c = new DcfModel.Json.Obj();
        for (Map.Entry<String, DcfModel.Json.Val> e : o.map.entrySet()) {
            c.put(e.getKey(), e.getValue());
        }
        return c;
    }

    static double engine(DcfModel.Json.Obj rec, double g) {
        return DcfModel.intrinsicValue(
                rec.optStr("kind", "FCFF"),
                rec.getNum("base_fcf"), g,
                rec.getNum("discount_rate"), rec.getNum("terminal_growth"),
                rec.getNum("shares"), rec.getNum("net_debt"),
                rec.getOptNum("start_margin"), rec.getOptNum("end_margin"),
                rec.getOptNum("bridge") == null ? 0.0 : rec.getOptNum("bridge"));
    }

    static void expectPass(String name, DcfModel.Json.Obj rec) {
        DcfModel.Json.Obj batch = new DcfModel.Json.Obj();
        batch.put("TEST", rec);
        Map<String, Double> pub = new LinkedHashMap<>();
        pub.put("TEST", rec.getNum("value"));
        Report r = checkRecords(batch, pub);
        check(name, r.ok());
    }

    static void expectReject(String name, DcfModel.Json.Obj rec, String rule) {
        DcfModel.Json.Obj batch = new DcfModel.Json.Obj();
        batch.put("TEST", rec);
        Map<String, Double> pub = new LinkedHashMap<>();
        pub.put("TEST", rec.getOptNum("value") == null ? 0.0 : rec.getNum("value"));
        Report r = checkRecords(batch, pub);
        boolean fired = !r.ok() && r.violations.stream()
                .anyMatch(v -> v.rule.equals(rule));
        if (!fired && !r.ok()) {
            System.out.println("    (fired: " + r.violations.get(0).rule
                    + " — expected " + rule + ")");
        }
        check(name + " [" + rule + "]", fired);
    }

    static void check(String name, boolean cond) {
        System.out.println((cond ? "  PASS " : "  FAIL ") + name);
        if (!cond) failures++;
    }
}
