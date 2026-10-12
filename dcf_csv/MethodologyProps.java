// MethodologyProps.java — audit gap D3 (behavioral).
//
// Verifies the DCF methodology's MATHEMATICAL PROPERTIES by running the
// real DcfModel and checking its outputs — not by regex-matching code
// text (that is MethodologyDocVsCode's job). If someone rewrites the
// model with different variable names but the same math, the anchors
// go stale while these properties stay green; if someone breaks the
// math but keeps the names, the anchors stay green while these go red.
// The two verifiers are complementary.
//
// Properties (each maps to a dcf_methodology.md claim):
//   P1  fade lands exactly on terminal growth at year 10
//   P2  mid-year discounting: PV(FCF_y) uses exponent y-0.5
//   P3  terminal value discounted at exponent 9.5
//   P4  Gordon TV = FCF_10 * (1+t)/(w-t)
//   P5  sustained growth capped at 15% entering the fade
//   P6  FCFE ignores net debt; FCFF subtracts it per share
//   P7  w <= t is refused (no silent nonsense value)
//   P8  trough normalization = revenue * 3y avg FCF margin
//   P9  TV-share warning fires above 75% and never alters the value
//  P10  equity = EV - netdebt identity holds on real inputs
//
// Modes: --self-test | --verify
// --verify ends via Finding.split -> Verify.report (exit 1 on any FAIL).
// Audit-write guard: REPORT only — this tool writes nothing.
import java.util.ArrayList;
import java.util.List;

public class MethodologyProps {

    static final String CHECK_ID = "D3";
    static final double TOL = 1e-9;

    static void check(boolean cond, String name,
                      List<String> failures, List<String> infos) {
        if (cond) infos.add("PASS " + name);
        else failures.add("FAIL " + name);
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) <= TOL * Math.max(1.0, Math.abs(a) + Math.abs(b));
    }

    static void verify(List<String> failures, List<String> infos) {
        // P1: fade lands exactly on terminal growth at year 10.
        for (double g : new double[]{0.05, 0.15, 0.30, -0.02}) {
            for (double t : new double[]{0.02, 0.035}) {
                DcfModel.TerminalValues tv = DcfModel.terminalValues(
                        "FCFF", 1000, g, 0.10, t, 100, 0, null, null);
                double g10 = tv.fcfs[9] / tv.fcfs[8] - 1.0;
                check(near(g10, t), "P1 fade->terminal g=" + g + " t=" + t,
                        failures, infos);
            }
        }

        // P2: mid-year discounting — recompute PV(FCF) independently.
        {
            double w = 0.095;
            DcfModel.TerminalValues tv = DcfModel.terminalValues(
                    "FCFF", 2500, 0.12, w, 0.03, 200, 500, null, null);
            double pv = 0;
            for (int y = 1; y <= 10; y++)
                pv += tv.fcfs[y - 1] / Math.pow(1 + w, y - 0.5);
            check(near(pv, tv.pvFcf), "P2 mid-year discounting", failures, infos);
        }

        // P3+P4: TV discounted at 9.5; Gordon formula.
        {
            double w = 0.11, t = 0.025;
            DcfModel.TerminalValues tv = DcfModel.terminalValues(
                    "FCFF", 1800, 0.08, w, t, 150, 0, null, null);
            double gordon = tv.fcf10 * (1 + t) / (w - t);
            check(near(gordon, tv.gordonTv), "P4 Gordon TV formula",
                    failures, infos);
            check(near(gordon / Math.pow(1 + w, 9.5), tv.pvTv),
                    "P3 TV discounted at 9.5", failures, infos);
        }

        // P5: sustained growth capped at 15% — g=0.30 must equal g=0.15.
        {
            DcfModel.TerminalValues hi = DcfModel.terminalValues(
                    "FCFF", 1000, 0.30, 0.10, 0.03, 100, 0, null, null);
            DcfModel.TerminalValues cap = DcfModel.terminalValues(
                    "FCFF", 1000, 0.15, 0.10, 0.03, 100, 0, null, null);
            boolean same = true;
            for (int i = 0; i < 10; i++) same &= near(hi.fcfs[i], cap.fcfs[i]);
            check(same, "P5 15% sustained-growth cap binds", failures, infos);
            // And the cap is visible in years 1-5 growth rates.
            double g1 = hi.fcfs[0] / 1000.0 - 1.0;
            check(near(g1, 0.15), "P5 year-1 growth is capped", failures, infos);
        }

        // P6: FCFE ignores net debt; FCFF subtracts it per share.
        {
            double ivFeN = DcfModel.intrinsicValue(
                    "FCFE", 1200, 0.10, 0.085, 0.03, 100, 5000.0, null, null);
            double ivFe0 = DcfModel.intrinsicValue(
                    "FCFE", 1200, 0.10, 0.085, 0.03, 100, 0.0, null, null);
            check(near(ivFeN, ivFe0), "P6 FCFE ignores net debt",
                    failures, infos);
            double ivFfN = DcfModel.intrinsicValue(
                    "FCFF", 1200, 0.10, 0.10, 0.03, 100, 5000.0, null, null);
            double ivFf0 = DcfModel.intrinsicValue(
                    "FCFF", 1200, 0.10, 0.10, 0.03, 100, 0.0, null, null);
            check(near(ivFfN, ivFf0 - 5000.0 / 100.0),
                    "P6 FCFF subtracts netdebt/share", failures, infos);
        }

        // P7: w <= t refused.
        {
            boolean threw = false;
            try {
                DcfModel.intrinsicValue(
                        "FCFF", 1000, 0.10, 0.03, 0.03, 100, 0, null, null);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "P7 w<=t refused", failures, infos);
        }

        // P8: trough normalization = revenue * 5y MEDIAN FCF margin, per Claude
        // audit-02 round-2 re-audit (2026-10-07) trigger spec. All must hold:
        // 5 valid years, median > 0.005, TTM <= 80% of median, last three
        // margins NOT strictly declining. Any failure -> null.
        {
            // Non-monotonic series [0.30, 0.12, 0.09, 0.11, 0.10]: median 0.11,
            // TTM 0.06 <= 0.80*0.11=0.088, last three [0.09,0.11,0.10] not
            // strictly declining -> normalizes to 50000*0.11 = 5500.
            // Median resists the 0.30 peak-year import.
            Double nb = DcfModel.troughNormalizedBase(50000.0,
                    new double[]{0.30, 0.12, 0.09, 0.11, 0.10}, 0.06);
            check(nb != null && near(nb, 5500.0),
                    "P8 trough normalization", failures, infos);
        }
        {
            // Monotonic decline [0.30, 0.12, 0.11, 0.10, 0.09]: last three
            // fall strictly -> structural erosion, declined (null).
            Double nb = DcfModel.troughNormalizedBase(50000.0,
                    new double[]{0.30, 0.12, 0.11, 0.10, 0.09}, 0.06);
            check(nb == null, "P8 decline on monotonic erosion", failures, infos);
        }
        {
            // Structural buildout: median margin near-zero -> declined.
            Double nb = DcfModel.troughNormalizedBase(50000.0,
                    new double[]{0.002, 0.003, 0.001, 0.004, 0.002}, -0.05);
            check(nb == null, "P8 decline on structural buildout", failures, infos);
        }
        {
            // Not material: TTM just above 80% of median -> declined.
            // Median of [0.12,0.11,0.10,0.13,0.09] = 0.11; 0.80*0.11 = 0.088.
            // TTM 0.09 > 0.088 -> null.
            Double nb = DcfModel.troughNormalizedBase(50000.0,
                    new double[]{0.12, 0.11, 0.10, 0.13, 0.09}, 0.09);
            check(nb == null, "P8 decline when not material", failures, infos);
        }
        {
            // Material: TTM just below 80% of median -> normalizes.
            // Same series, median 0.11, TTM 0.08 < 0.088 -> 50000*0.11=5500.
            // Last three [0.10,0.13,0.09] not strictly declining.
            Double nb = DcfModel.troughNormalizedBase(50000.0,
                    new double[]{0.12, 0.11, 0.10, 0.13, 0.09}, 0.08);
            check(nb != null && near(nb, 5500.0),
                    "P8 normalizes when material", failures, infos);
        }
        {
            // Fewer than 5 years -> declined.
            Double nb = DcfModel.troughNormalizedBase(50000.0,
                    new double[]{0.12, 0.11, 0.10, 0.09}, 0.05);
            check(nb == null, "P8 decline on short history", failures, infos);
        }
        {
            // NaN year -> declined.
            Double nb = DcfModel.troughNormalizedBase(50000.0,
                    new double[]{0.12, Double.NaN, 0.10, 0.11, 0.09}, 0.05);
            check(nb == null, "P8 decline on NaN year", failures, infos);
        }

        // P8b: terminal reinvestment-consistency warnings (prop-20261005-007,
        // Claude audit-02 Q6/Q7). Warning only — the value is never altered.
        {
            List<String> warns = DcfModel.guardrailWarnings(
                    "FCFF", 100, 0.10, 0.09, 0.05, 100, 0, null, null);
            boolean fired = warns.stream().anyMatch(
                    s -> s.contains("unfunded"));
            check(fired, "P8b terminal-growth ceiling warning fires", failures, infos);
            double ivWarn = DcfModel.intrinsicValue(
                    "FCFF", 100, 0.10, 0.09, 0.05, 100, 0, null, null);
            double ivPlain = DcfModel.intrinsicValue(
                    "FCFF", 100, 0.10, 0.09, 0.03, 100, 0, null, null);
            check(ivWarn != ivPlain,
                    "P8b ceiling warning is about t, not a value change", failures, infos);
        }
        {
            // No warning at or below the ceiling.
            List<String> warns = DcfModel.guardrailWarnings(
                    "FCFF", 100, 0.10, 0.09, 0.035, 100, 0, null, null);
            boolean fired = warns.stream().anyMatch(
                    s -> s.contains("unfunded"));
            check(!fired, "P8b no ceiling warning at t=3.5%", failures, infos);
        }
        {
            // REV kind: terminal growth on non-positive terminal margin.
            List<String> warns = DcfModel.guardrailWarnings(
                    "REV", 1000, 0.10, 0.09, 0.03, 100, 0, 0.05, -0.01);
            boolean fired = warns.stream().anyMatch(
                    s -> s.contains("unfunded"));
            check(fired, "P8b REV unfunded-growth warning fires", failures, infos);
        }

        // P9: TV-share warning fires above 75% and never alters the value.
        {
            // High growth + low discount => TV dominates EV.
            List<String> warns = DcfModel.guardrailWarnings(
                    "FCFF", 100, 0.15, 0.06, 0.035, 100, 0, null, null);
            boolean fired = warns.stream().anyMatch(
                    s -> s.toLowerCase().contains("terminal"));
            DcfModel.TerminalValues tv = DcfModel.terminalValues(
                    "FCFF", 100, 0.15, 0.06, 0.035, 100, 0, null, null);
            double share = tv.pvTv / tv.ev;
            check(share > 0.75 && fired,
                    "P9 TV-share warning fires (share=" +
                            String.format("%.3f", share) + ")",
                    failures, infos);
            double ivPlain = DcfModel.intrinsicValue(
                    "FCFF", 100, 0.15, 0.06, 0.035, 100, 0, null, null);
            check(near(ivPlain, tv.ev / 100.0),
                    "P9 warning never alters value", failures, infos);
        }

        // P10: EV identity on real inputs — equity = EV - netdebt.
        {
            double iv = DcfModel.intrinsicValue(
                    "FCFF", 5519, 0.30, 0.105, 0.03, 1640.0, 2500.0,
                    null, null);
            DcfModel.TerminalValues tv = DcfModel.terminalValues(
                    "FCFF", 5519, 0.30, 0.105, 0.03, 1640.0, 2500.0,
                    null, null);
            check(near(iv, (tv.ev - 2500.0) / 1640.0),
                    "P10 equity = EV - netdebt identity", failures, infos);
        }

        // P11/P12: numeric golden tests (Claude audit-03 F1) — fixed inputs
        // with independently hand-computed expected values (Python mirror of
        // the documented methodology, 2026-10-05 — NOT DcfModel's own
        // output). Tolerance 1e-6 absorbs float operation-ordering noise
        // between the independent computation and the JVM; any formula bug
        // (wrong TV exponent, missing mid-year, wrong fade) moves the value
        // by orders of magnitude more.
        {
            double ivFf = DcfModel.intrinsicValue(
                    "FCFF", 1000, 0.10, 0.10, 0.03, 100, 200, null, null);
            check(Math.abs(ivFf - 225.345966) <= 1e-6,
                    "P11 FCFF golden (hand-computed 225.345966, got " + ivFf + ")",
                    failures, infos);
            double ivFe = DcfModel.intrinsicValue(
                    "FCFE", 1000, 0.10, 0.10, 0.03, 100, 200, null, null);
            check(Math.abs(ivFe - 227.345966) <= 1e-6,
                    "P12 FCFE golden incl. net-debt skip (hand-computed 227.345966, got "
                            + ivFe + ")",
                    failures, infos);
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--verify";
        List<String> failures = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        verify(failures, infos);
        if ("--self-test".equals(mode)) {
            for (String i : infos) System.out.println("PASS: " + i.substring(5));
            for (String f : failures) System.out.println(f);
            System.out.println();
            System.out.println(infos.size() + " passed, " + failures.size()
                    + " failed");
            if (!failures.isEmpty()) System.exit(1);
            return;
        }
        if ("--verify".equals(mode)) {
            List<Finding> findings = new ArrayList<>();
            for (String i : infos)
                findings.add(Finding.info(CHECK_ID, "-", "prop",
                        i.substring(5), "behavioral property holds",
                        "dcf_csv/DcfModel.java", "none"));
            for (String f : failures)
                findings.add(Finding.fail(CHECK_ID, "-", "prop",
                        f.substring(5), "methodology property violated",
                        "dcf_csv/DcfModel.java",
                        "fix DcfModel or the doc claim"));
            List<String> vinfos = new ArrayList<>();
            List<String> vfailures = new ArrayList<>();
            Finding.split(findings, vinfos, vfailures);
            Verify.report(
                    "VERIFY OK (MethodologyProps): all methodology properties hold",
                    "VERIFY FAILED (MethodologyProps): " + vfailures.size()
                            + " problem(s) found:",
                    vinfos, vfailures);
            return;
        }
        System.err.println("Usage: java MethodologyProps.java --self-test | --verify");
        System.exit(2);
    }
}
