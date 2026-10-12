import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed-methodology DCF model replica (10-year, mid-year discounting, 5-year growth fade).
 *
 * <p>Java port of {@code dcf_csv/dcf_model.py}. Mirrors the formulas in the
 * DCF workbook's {@code DCF <TICKER>} tabs so sensitivity analysis
 * and column recomputation are deterministic.
 *
 * <p>Model:
 * <ul>
 *   <li>Years 1-5 growth = min(g, 15%) — sustained growth is capped at 15%
 *       (practitioner guardrail, 2026-09-28); years 6-10 growth fades
 *       linearly from the capped rate to t:
 *       g_y = gc - (y-5) * (gc - t) / 5</li>
 *   <li>Mid-year discounting: discount exponent = y - 0.5, df = 1 / (1 + w)^(y - 0.5)</li>
 *   <li>FCFF/FCFE: FCF_y = base * prod(1 + g_i)</li>
 *   <li>REV: revenue_y = base * prod(1 + g_i); margin_y = start + (end - start) * y/5 for
 *       y &le; 5, then end margin; FCF_y = revenue_y * margin_y</li>
 *   <li>Terminal value at year 10: TV = FCF_10 * (1 + t) / (w - t)</li>
 *   <li>PV of TV = TV / (1 + w)^9.5</li>
 *   <li>EV = PV(years 1-10 FCF) + PV(TV)</li>
 *   <li>Equity = EV (FCFE) or EV - net debt (FCFF/REV)</li>
 *   <li>IV/share = equity / diluted shares</li>
 * </ul>
 * All monetary inputs in $M; shares in millions; rates as decimals.
 *
 * <p>Usage: {@code java DcfModel.java} runs the workbook verification
 * (reproduces the {@code __main__} block of the Python script).
 */
public class DcfModel {

    /**
     * Home directory. IMPORTANT: this runtime's {@code user.home} system property
     * returns {@code /root} (the passwd entry) while {@code $HOME} is
     * {@code /home/hatch}. Python's {@code os.path.expanduser("~/...")} honors
     * {@code $HOME}, so Java must too -- never use
     * {@code System.getProperty("user.home")} directly for workspace paths.
     */
    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /** Workspace root. Overridable with the DCF_WORKSPACE_ROOT env var for testing. */
    static String workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        return (e != null && !e.isEmpty()) ? e : homeDir() + "/workspace";
    }

    static {
        // Python writes UTF-8 to stdout regardless of locale (PEP 686); this JVM
        // may run under a POSIX/ASCII locale, so set UTF-8 explicitly to match.
        try {
            System.setOut(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // fall back to the default stdout
        }
    }

    /**
     * Intrinsic value per share under the fixed methodology.
     *
     * <p>Practitioner guardrails (2026-09-28, see the methodology doc):
     * sustained growth is capped at 15% before the fade schedule
     * ({@link #MAX_SUSTAINED_GROWTH}); trough-year bases are normalized by
     * the caller via {@link #troughNormalizedBase}; a terminal value above
     * 75% of EV is warned about via {@link #guardrailWarnings}, never
     * silently altered.
     *
     * @param kind        "FCFF", "FCFE" or "REV"
     * @param base        base FCF ($M), or base revenue ($M) for REV
     * @param g           initial growth rate (decimal)
     * @param w           WACC / cost of equity (decimal)
     * @param t           terminal growth rate (decimal)
     * @param shares      diluted shares (M)
     * @param netdebt     net debt ($M)
     * @param startMargin year-0 margin, REV only (may be null otherwise)
     * @param endMargin   year-5 margin, REV only (may be null otherwise)
     */
    public static double intrinsicValue(String kind, double base, double g, double w, double t,
                                        double shares, double netdebt,
                                        Double startMargin, Double endMargin) {
        return intrinsicValue(kind, base, g, w, t, shares, netdebt,
                startMargin, endMargin, 0.0);
    }

    /**
     * Equity-bridge variant (prop-20261005-008, Claude audit-03 issue a1).
     *
     * @param bridge  optional equity-bridge adjustment ($M): added to equity
     *                after the net-debt subtraction. 0.0 for every ticker
     *                except BRK.B. The bridge is computed by
     *                {@link #bridgeAdjust} from itemized filing-sourced
     *                assumption fields; the engine never invents it.
     */
    public static double intrinsicValue(String kind, double base, double g, double w, double t,
                                        double shares, double netdebt,
                                        Double startMargin, Double endMargin,
                                        double bridge) {
        validateKind(kind);
        return components(kind, base, g, w, t, shares, netdebt,
                startMargin, endMargin, bridge).iv;
    }

    /**
     * Computes the equity-bridge adjustment from a company's assumptions.
     * BRK.B-only for now (prop-20261005-008 F2).
     *
     * bridge = bridge_portfolio - bridge_float - bridge_minority
     *          - bridge_leases + bridge_pension
     *
     * bridge_pension is signed (positive = net pension asset). Returns 0.0
     * when bridge_portfolio is absent — every other ticker is unaffected.
     *
     * Design note (documented for the re-audit): the float ($176B) is
     * SUBTRACTED as a financial liability funding the portfolio. The DCF
     * base excludes investment income, so the portfolio's income is not
     * double-counted; the float franchise beyond net asset value is
     * conservatively valued at zero — flagged as an open question.
     */
    static double bridgeAdjust(Json.Obj a) {
        Double portfolio = a.getOptNum("bridge_portfolio");
        if (portfolio == null) return 0.0;
        double f = or0(a.getOptNum("bridge_float"));
        double m = or0(a.getOptNum("bridge_minority"));
        double l = or0(a.getOptNum("bridge_leases"));
        double p = or0(a.getOptNum("bridge_pension"));
        return portfolio - f - m - l + p;
    }

    static double or0(Double d) { return d == null ? 0.0 : d; }

    /**
     * Practitioner guardrail (2026-09-28): sustained growth entering the
     * fade schedule is capped at 15%. A company cannot compound above 15%
     * for a full decade in this model — the backtest showed trough-year
     * CAGRs extrapolating a cyclical recovery as permanent hypergrowth.
     */
    static final double MAX_SUSTAINED_GROWTH = 0.15;

    /**
     * Practitioner guardrail (2026-09-28): warn when the present value of
     * the terminal value exceeds this share of enterprise value. Warning
     * only — the value is never altered.
     */
    static final double TV_SHARE_WARN = 0.75;

    /**
     * Practitioner guardrail (2026-10-05, prop-20261005-007, Claude audit-02
     * Q6/Q7): terminal growth above long-run nominal GDP is unfunded by
     * definition — a perpetuity cannot outgrow the economy forever.
     * Warning only — the value is never altered.
     */
    static final double TERMINAL_GROWTH_CEILING = 0.04;

    /**
     * Damodaran trough-year normalization (practitioner guardrail,
     * 2026-09-28; window widened 2026-10-05 by prop-20261005-007, Claude
     * audit-02 Q4; trigger refined 2026-10-07 by Claude audit-02 round-2
     * re-audit): when the base year's FCF has collapsed (cyclical trough),
     * replace the base with base-year revenue times the 5-year MEDIAN FCF
     * margin. The median resists peak-year import; 3 years rarely spans a
     * full cycle.
     *
     * <p>Trigger (all must hold, per Claude 2026-10-07):
     * <ul>
     *   <li>5 valid years of margin history (fewer, or any NaN, declines)
     *   <li>median &gt; 0.005 (negative/near-zero median = structural buildout,
     *       not a cyclical trough)
     *   <li>ttmMargin &lt;= 0.80 * median (material trough — TTM at or below
     *       80% of the median)
     *   <li>the last three annual margins are NOT strictly declining
     *       (h[2] &gt; h[3] &gt; h[4] signals structural erosion, not a trough)
     * </ul>
     * Any failure returns null — the caller keeps the reported base and flags
     * for a different model kind. Pure function — the caller supplies
     * filing-sourced figures; this method never invents them.
     */
    public static Double troughNormalizedBase(double revenue, double[] annualMargins,
                                              double ttmMargin) {
        if (annualMargins == null || annualMargins.length < 5) return null;
        double[] h = Arrays.copyOfRange(annualMargins, annualMargins.length - 5,
                                        annualMargins.length);
        for (double m : h) if (Double.isNaN(m)) return null;
        double median = Stats.median(h);
        if (median <= 0.005) return null;
        if (ttmMargin > 0.80 * median) return null;      // not a material trough
        if (h[2] > h[3] && h[3] > h[4]) return null;     // structural erosion
        return revenue * median;
    }

    /** Median of an array (sorts a copy; for odd lengths takes the middle). */
    // median(double[]) lives in java-shared Stats.java (dedup 2026-10-09).

    /** Valuation components, so guardrails inspect the same numbers the
     *  intrinsic value is built from (one code path, no drift). */
    static final class Components {
        double pvFcf;
        double pvTv;
        double ev;
        double equity;
        double iv;
    }

    /**
     * Public terminal-value decomposition for audit-layer cross-checks
     * (Phase 1, 2026-09-28: TvCrossCheck, ReverseDcf, IccCrossCheck).
     * Pure function — same code path as {@link #intrinsicValue}, no behavior
     * change to the model itself.
     */
    public static final class TerminalValues {
        /** Gordon terminal value at year 10: FCF_10 x (1+t)/(w-t). */
        public final double gordonTv;
        /** Present value of the Gordon TV (discounted at exponent 9.5). */
        public final double pvTv;
        /** Present value of years 1-10 FCF. */
        public final double pvFcf;
        /** Enterprise value = pvFcf + pvTv. */
        public final double ev;
        /** Year-10 FCF (the Gordon TV's anchor). */
        public final double fcf10;
        /** Mean of year-8..10 FCF (lumpiness anchor). */
        public final double avgFcf8910;
        /** The 10 projected FCFs (index 0 = year 1). */
        public final double[] fcfs;

        TerminalValues(double gordonTv, double pvTv, double pvFcf, double ev,
                       double fcf10, double avgFcf8910, double[] fcfs) {
            this.gordonTv = gordonTv;
            this.pvTv = pvTv;
            this.pvFcf = pvFcf;
            this.ev = ev;
            this.fcf10 = fcf10;
            this.avgFcf8910 = avgFcf8910;
            this.fcfs = fcfs;
        }
    }

    /**
     * Validate the model kind. A lowercase "fcfe" must not silently become FCFF.
     */
    static void validateKind(String kind) {
        if (!"FCFF".equals(kind) && !"FCFE".equals(kind) && !"REV".equals(kind)) {
            throw new IllegalArgumentException("kind must be FCFF, FCFE or REV");
        }
    }

    /**
     * Terminal-value decomposition under the fixed methodology.
     * Re-runs the same fade schedule as {@link #components}; the two stay in
     * sync because both call the shared {@link #projectFcfs} schedule.
     */
    public static TerminalValues terminalValues(String kind, double base, double g, double w,
                                                double t, double shares, double netdebt,
                                                Double startMargin, Double endMargin) {
        validateKind(kind);
        // 2% WACC-minus-terminal-growth floor (prop-20261005-009, Claude audit-03).
        // A spread below 2% makes the terminal value hypersensitive to tiny
        // input changes; refuse rather than produce a fragile number.
        // 1e-9 epsilon: 0.045-0.025 is 0.019999999999999997 in doubles.
        if (w - t < 0.02 - 1e-9) {
            throw new IllegalArgumentException(
                    "WACC-terminal growth spread (" + Py.repr(w - t)
                    + ") below 2% floor (prop-20261005-009); WACC="
                    + Py.repr(w) + " terminal growth=" + Py.repr(t) + ")");
        }
        double[] fcfs = projectFcfs(kind, base, g, t, startMargin, endMargin);
        double[] terms = new double[10];
        for (int y = 1; y <= 10; y++) {
            terms[y - 1] = fcfs[y - 1] / Math.pow(1 + w, y - 0.5);
        }
        double pvFcf = neumaierSum(terms);
        double f10 = fcfs[9];
        double tv = f10 * (1 + t) / (w - t);
        double pvTv = tv / Math.pow(1 + w, 9.5);
        double avg8910 = (fcfs[7] + fcfs[8] + fcfs[9]) / 3.0;
        return new TerminalValues(tv, pvTv, pvFcf, pvFcf + pvTv, f10, avg8910, fcfs);
    }

    /**
     * The 10-year FCF projection (the fade schedule), shared by
     * {@link #components} and {@link #terminalValues} so the two can never
     * drift apart.
     */
    static double[] projectFcfs(String kind, double base, double g, double t,
                                Double startMargin, Double endMargin) {
        double gc = Math.min(g, MAX_SUSTAINED_GROWTH);
        double[] fcfs = new double[10];
        if (kind.equals("REV")) {
            if (startMargin == null || endMargin == null) {
                throw new IllegalArgumentException(
                        "REV requires startMargin and endMargin (got null)");
            }
            double rev = base;
            for (int y = 1; y <= 10; y++) {
                double gy = y <= 5 ? gc : gc - (y - 5) * (gc - t) / 5.0;
                rev *= (1 + gy);
                double m = y <= 5
                        ? startMargin + (endMargin - startMargin) * y / 5.0
                        : endMargin;
                fcfs[y - 1] = rev * m;
            }
        } else {
            double fcf = base;
            for (int y = 1; y <= 10; y++) {
                double gy = y <= 5 ? gc : gc - (y - 5) * (gc - t) / 5.0;
                fcf *= (1 + gy);
                fcfs[y - 1] = fcf;
            }
        }
        return fcfs;
    }

    /** Full valuation: projected FCFs, terminal value, EV, equity, IV/share. */
    static Components components(String kind, double base, double g, double w, double t,
                                 double shares, double netdebt,
                                 Double startMargin, Double endMargin) {
        return components(kind, base, g, w, t, shares, netdebt,
                startMargin, endMargin, 0.0);
    }

    /** Full valuation with equity-bridge adjustment (prop-20261005-008). */
    static Components components(String kind, double base, double g, double w, double t,
                                 double shares, double netdebt,
                                 Double startMargin, Double endMargin,
                                 double bridge) {
        // Single code path: the PV/TV math lives in terminalValues(); this method
        // only adds the EV -> equity -> IV/share steps (Claude 2026-10-04).
        // Neumaier compensated summation mirrors CPython 3.12+ sum(); earlier
        // Pythons use plain addition and Math.pow need not match C pow to the
        // last ulp — the 0.005 tolerance in main() is the real parity defense.
        TerminalValues tv = terminalValues(kind, base, g, w, t, shares, netdebt,
                startMargin, endMargin);
        // Equity bridge (prop-20261005-008): the simple EV-minus-net-debt
        // bridge ignores non-operating assets/liabilities. bridge is 0.0 for
        // every ticker except BRK.B. Not applied to FCFE (equity-direct).
        double equity = kind.equals("FCFE") ? tv.ev : tv.ev - netdebt + bridge;
        Components c = new Components();
        c.pvFcf = tv.pvFcf;
        c.pvTv = tv.pvTv;
        c.ev = tv.ev;
        c.equity = equity;
        c.iv = equity / shares;
        return c;
    }

    /**
     * Practitioner guardrail warnings for one valuation. Detection only —
     * a warning never changes the value. Returns an empty list when
     * nothing trips.
     */
    public static List<String> guardrailWarnings(String kind, double base, double g, double w,
                                                 double t, double shares, double netdebt,
                                                 Double startMargin, Double endMargin) {
        validateKind(kind);
        List<String> out = new ArrayList<>();
        if (g > MAX_SUSTAINED_GROWTH) {
            out.add("sustained growth " + Py.fixed(g * 100, 1) + "% exceeds the "
                    + Py.fixed(MAX_SUSTAINED_GROWTH * 100, 0)
                    + "% cap — the fade schedule compounds at 15%");
        }
        if (base <= 0) {
            String what = kind.equals("REV") ? "base revenue" : "base FCF";
            out.add(what + " " + Py.fixed(base, 1) + " is not positive (trough year?) — "
                    + "normalize the base with troughNormalizedBase (base-year revenue x "
                    + "5-year median FCF margin) before trusting this value");
        }
        Components c = components(kind, base, g, w, t, shares, netdebt,
                startMargin, endMargin);
        if (c.ev > 0 && c.pvTv / c.ev > TV_SHARE_WARN) {
            out.add("terminal value is " + Py.fixed(100 * c.pvTv / c.ev, 1)
                    + "% of EV — the valuation is a terminal-value bet");
        }
        // Hard flag at 90% (prop-20261005-009): above this the 10-year DCF is
        // decorative — the value is essentially the terminal perpetuity.
        // Flag, not refusal; the value is never altered.
        if (c.ev > 0 && c.pvTv / c.ev > 0.90) {
            out.add("FLAG: terminal value is " + Py.fixed(100 * c.pvTv / c.ev, 1)
                    + "% of EV (above 90%) — the 10-year projection contributes almost nothing; "
                    + "the valuation is the terminal perpetuity");
        }
        // Reinvestment consistency (prop-20261005-007, Claude audit-02 Q6/Q7):
        // a perpetuity growing faster than long-run nominal GDP is unfunded
        // by definition; likewise terminal growth on a non-positive terminal
        // FCF margin (REV kind). Warning only — the value is never altered.
        if (t > TERMINAL_GROWTH_CEILING) {
            out.add("terminal growth " + Py.fixed(t * 100, 1) + "% exceeds the "
                    + Py.fixed(TERMINAL_GROWTH_CEILING * 100, 0)
                    + "% ceiling (long-run nominal GDP) — perpetual growth above the economy is unfunded");
        }
        if ("REV".equals(kind) && endMargin != null && endMargin <= 0 && t > 0) {
            out.add("terminal growth " + Py.fixed(t * 100, 1)
                    + "% on a non-positive terminal FCF margin — the growth is unfunded");
        }
        return out;
    }

    /** Neumaier compensated summation, mirroring CPython's built-in sum(). */
    static double neumaierSum(double[] terms) {
        double s = 0.0, c = 0.0;
        for (double x : terms) {
            double t = s + x;
            if (Math.abs(s) >= Math.abs(x)) {
                c += (s - t) + x;
            } else {
                c += (x - t) + s;
            }
            s = t;
        }
        return s + c;
    }

    /** One input's bump result. */
    public static class BumpResult {
        public final double meanAbsPct;
        public final double upPct;
        public final double dnPct;

        BumpResult(double meanAbsPct, double upPct, double dnPct) {
            this.meanAbsPct = meanAbsPct;
            this.upPct = upPct;
            this.dnPct = dnPct;
        }
    }

    /** Outcome of {@link #sensitivity}. */
    public static class SensitivityResult {
        public final String winner;
        public final double meanAbsPct;
        /** "+" if raising the winning input raises IV, "-" if it lowers IV. */
        public final String direction;
        public final Map<String, BumpResult> all;

        SensitivityResult(String winner, double meanAbsPct, String direction,
                          Map<String, BumpResult> all) {
            this.winner = winner;
            this.meanAbsPct = meanAbsPct;
            this.direction = direction;
            this.all = all;
        }
    }

    /**
     * Bump each key input +/-10% (relative) through the model; return per-input
     * mean absolute % change in IV and the winning input.
     *
     * <p>All bumps are relative (+/-10%) for comparability across inputs. The
     * denominator is |IV| so negative-IV companies still rank correctly.
     */
    public static SensitivityResult sensitivity(String kind, double base, double g, double w,
                                                double t, double shares, double netdebt,
                                                Double startMargin, Double endMargin) {
        validateKind(kind);
        double baseIv = intrinsicValue(kind, base, g, w, t, shares, netdebt, startMargin, endMargin);
        double denom = baseIv != 0 ? Math.abs(baseIv) : 1.0;

        // Input label -> model parameter name, in evaluation order. Python's max()
        // keeps the FIRST maximal entry on ties, so order matters.
        List<String[]> inputs = new ArrayList<>();
        inputs.add(new String[]{"base FCF/revenue", "base"});
        inputs.add(new String[]{"growth g", "g"});
        inputs.add(new String[]{"WACC", "w"});
        inputs.add(new String[]{"terminal growth t", "t"});
        if (kind.equals("REV") && endMargin != null) {
            inputs.add(new String[]{"Year-5 margin", "end_margin"});
        }

        Map<String, BumpResult> results = new LinkedHashMap<>();
        for (String[] in : inputs) {
            String label = in[0];
            String name = in[1];
            Double up = withBump(kind, base, g, w, t, shares, netdebt,
                    startMargin, endMargin, name, 1.10);
            Double dn = withBump(kind, base, g, w, t, shares, netdebt,
                    startMargin, endMargin, name, 0.90);
            if (up == null && dn == null) {
                continue;
            }
            // One-sided bump when the other side is invalid (e.g. the WACC
            // down-bump would violate w > t): rank on the valid side instead
            // of silently dropping the input (Claude 2026-10-04).
            double chgUp = up == null ? Double.NaN : (up - baseIv) / denom * 100;
            double chgDn = dn == null ? Double.NaN : (dn - baseIv) / denom * 100;
            double meanAbs;
            String displayLabel = label;
            if (up == null || dn == null) {
                meanAbs = Math.abs(up == null ? chgDn : chgUp);
                displayLabel += " (one-sided)";
            } else {
                meanAbs = (Math.abs(chgUp) + Math.abs(chgDn)) / 2;
            }
            if (name.equals("g") && g >= MAX_SUSTAINED_GROWTH) {
                // Both bumps can sit above the 15% cap, muting growth's rank.
                displayLabel += " [capped — sensitivity understated]";
            }
            results.put(displayLabel, new BumpResult(meanAbs, chgUp, chgDn));
        }
        // First maximal entry wins (mirrors Python max() tie behavior).
        String winner = null;
        BumpResult best = null;
        for (Map.Entry<String, BumpResult> e : results.entrySet()) {
            if (best == null || e.getValue().meanAbsPct > best.meanAbsPct) {
                winner = e.getKey();
                best = e.getValue();
            }
        }
        // Direction: "+" if raising the input raises IV. For a one-sided
        // down-bump-only result, raising the input reverses the down move.
        double ref = Double.isNaN(best.upPct) ? -best.dnPct : best.upPct;
        String direction = ref > 0 ? "+" : "-";
        return new SensitivityResult(winner, best.meanAbsPct, direction, results);
    }

    /** Re-run the model with one parameter scaled by {@code factor}; null if invalid. */
    static Double withBump(String kind, double base, double g, double w, double t,
                           double shares, double netdebt,
                           Double startMargin, Double endMargin,
                           String name, double factor) {
        double bBase = base, bG = g, bW = w, bT = t;
        Double bEndMargin = endMargin;
        double val;
        switch (name) {
            case "base": val = base; bBase = val * factor; break;
            case "g": val = g; bG = val * factor; break;
            case "w": val = w; bW = val * factor; break;
            case "t":
                val = t;
                if (val * factor >= w) {
                    return null; // would violate w > t
                }
                bT = val * factor;
                break;
            case "end_margin": val = endMargin; bEndMargin = val * factor; break;
            default: throw new IllegalArgumentException("unknown input: " + name);
        }
        try {
            return intrinsicValue(kind, bBase, bG, bW, bT, shares, netdebt,
                    startMargin, bEndMargin);
        } catch (IllegalArgumentException e) {
            return null; // w <= t after the bump
        }
    }

    /** Verification against the workbook's supplied fixed values (the __main__ block). */
    public static void main(String[] args) throws Exception {
        String dataPath = workspaceRoot() + "/your_files/dcf-valuations/.src/workbook_data.json";
        Json.Obj data = (Json.Obj) Json.parse(readFile(dataPath));
        double worst = 0;
        for (Json.Val c : data.getList("companies")) {
            Json.Obj co = (Json.Obj) c;
            Json.Obj a = co.getObj("assumptions");
            if (a == null || !a.has("base") || co.isNull("fixed")) {
                continue;
            }
            double iv = intrinsicValue(
                    a.getStr("kind"), a.getNum("base"), a.getNum("g"), a.getNum("w"),
                    a.getNum("t"), a.getNum("shares"), a.getNum("netdebt"),
                    a.getOptNum("start_margin"), a.getOptNum("end_margin"));
            double fixed = co.getNum("fixed");
            double diff = Math.abs(iv - fixed);
            if (diff > worst) {
                worst = diff;
            }
            String flag = diff < 0.005 ? "OK " : "MISMATCH";
            System.out.println(flag + " " + co.getStr("ticker")
                    + ": model=" + Py.fixed(iv, 2)
                    + " supplied=" + Py.fixed(fixed, 2)
                    + " diff=" + Py.fixed(diff, 4));
        }
        System.out.println("worst diff: " + Py.repr(Py.round(worst, 4)));
    }

    static String readFile(String path) throws Exception {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Py: Python-compatible float formatting helpers.
    // ------------------------------------------------------------------

    /** Python repr()/str() semantics for doubles, plus printf-style fixed formats. */
    static class Py {
        /**
         * Python repr(float): shortest round-trip digits (same digits Java's
         * Double.toString produces), but Python's placement rules: fixed notation
         * unless the decimal exponent is &le; -4 or &gt; 16, always with a '.'.
         */
        static String repr(double v) {
            if (Double.isNaN(v)) {
                return "nan";
            }
            if (v == Double.POSITIVE_INFINITY) {
                return "inf";
            }
            if (v == Double.NEGATIVE_INFINITY) {
                return "-inf";
            }
            if (v == 0.0) {
                // Preserve the sign of -0.0: Python repr(-0.0) == '-0.0'.
                return Double.doubleToRawLongBits(v) < 0 ? "-0.0" : "0.0";
            }
            String s = Double.toString(v);
            boolean neg = s.startsWith("-");
            String t = neg ? s.substring(1) : s;
            String mant;
            int exp;
            int ePos = t.indexOf('E');
            if (ePos >= 0) {
                mant = t.substring(0, ePos);
                exp = Integer.parseInt(t.substring(ePos + 1));
            } else {
                mant = t;
                exp = 0;
            }
            int dot = mant.indexOf('.');
            String digits = mant.substring(0, dot) + mant.substring(dot + 1);
            // Strip trailing zeros: Python's repr never emits them
            // (repr(0.0001) == "0.0001", not "0.00010") (Claude 2026-10-04).
            int zi = digits.length();
            while (zi > 1 && digits.charAt(zi - 1) == '0') {
                zi--;
            }
            digits = digits.substring(0, zi);
            // Value = 0.digits * 10^decpt.
            int decpt = dot + exp;
            int k = digits.length();
            String body;
            if (decpt <= -4 || decpt > 16) {
                int e10 = decpt - 1;
                String es = (e10 < 0 ? "-" : "+")
                        + String.format("%02d", Math.abs(e10));
                body = digits.charAt(0) + (k > 1 ? "." + digits.substring(1) : "")
                        + "e" + es;
            } else if (decpt <= 0) {
                body = "0." + "0".repeat(-decpt) + digits;
            } else if (decpt >= k) {
                body = digits + "0".repeat(decpt - k) + ".0";
            } else {
                body = digits.substring(0, decpt) + "." + digits.substring(decpt);
            }
            return (neg ? "-" : "") + body;
        }

        /**
         * Python's {@code f"{v:.{n}f}"}: round-half-even on the exact binary value,
         * always {@code n} decimals. Python keeps the sign of -0.0 ("-0.00").
         */
        static String fixed(double v, int n) {
            if (Double.isNaN(v)) {
                return "nan";
            }
            if (Double.isInfinite(v)) {
                return v > 0 ? "inf" : "-inf";
            }
            BigDecimal bd = new BigDecimal(v); // exact decimal expansion of the double
            bd = bd.setScale(n, RoundingMode.HALF_EVEN);
            String s = bd.toPlainString();
            if (bd.signum() == 0 && Double.doubleToRawLongBits(v) < 0) {
                s = "-" + s; // Python formats -0.0 as "-0.00"
            }
            return s;
        }

        /**
         * Python's {@code f"{v:,.{n}f}"}: like {@link #fixed} with thousands separators.
         */
        static String comma(double v, int n) {
            String s = fixed(v, n);
            // fixed() already handled the -0.0 sign; regroup the integer part.
            boolean neg = s.startsWith("-");
            String t = neg ? s.substring(1) : s;
            int dot = t.indexOf('.');
            String intPart = dot >= 0 ? t.substring(0, dot) : t;
            String fracPart = dot >= 0 ? t.substring(dot) : "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < intPart.length(); i++) {
                if (i > 0 && (intPart.length() - i) % 3 == 0) {
                    sb.append(',');
                }
                sb.append(intPart.charAt(i));
            }
            return (neg ? "-" : "") + sb + fracPart;
        }

        /** Python's round(x, ndigits): correctly-rounded half-even, back to double. */
        static double round(double v, int ndigits) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return v;
            }
            return new BigDecimal(v).setScale(ndigits, RoundingMode.HALF_EVEN).doubleValue();
        }

        /**
         * Python repr() of a string: single quotes, backslash escapes, printable
         * non-ASCII kept as-is (used for the {@code note=...} detail in verify_base).
         */
        static String strRepr(String s) {
            StringBuilder sb = new StringBuilder("'");
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                switch (cp) {
                    case '\\': sb.append("\\\\"); break;
                    case '\'': sb.append("\\'"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (cp < 0x20 || cp == 0x7F) {
                            sb.append(String.format("\\x%02x", cp));
                        } else {
                            sb.appendCodePoint(cp);
                        }
                }
                i += Character.charCount(cp);
            }
            return sb.append("'").toString();
        }
    }

    // ------------------------------------------------------------------
    // Json: minimal JSON parser (insertion-ordered) + Python-compatible writer.
    // ------------------------------------------------------------------

    /** Minimal JSON value model + parser. Only what these scripts need. */
    static class Json {
        /** Base value type. */
        abstract static class Val { }

        /** JSON object: insertion order preserved (like Python's dict). */
        static class Obj extends Val {
            final LinkedHashMap<String, Val> map = new LinkedHashMap<>();

            boolean has(String k) { return map.containsKey(k); }
            boolean isNull(String k) {
                Val v = map.get(k);
                return v == null || v instanceof Null;
            }
            Val get(String k) { return map.get(k); }
            String getStr(String k) { return (String) ((Str) map.get(k)).v; }
            Obj getObj(String k) {
                Val v = map.get(k);
                return v instanceof Obj ? (Obj) v : null;
            }
            @SuppressWarnings("unchecked")
            List<Val> getList(String k) { return (List<Val>) ((Arr) map.get(k)).items; }
            double getNum(String k) { return numOf(map.get(k)); }
            /** Null when missing or JSON null (like Python's .get returning None). */
            Double getOptNum(String k) {
                Val v = map.get(k);
                return (v == null || v instanceof Null) ? null : numOf(v);
            }
            String optStr(String k, String dflt) {
                Val v = map.get(k);
                if (v == null || v instanceof Null) {
                    return dflt;
                }
                return ((Str) v).v;
            }
            void put(String k, Val v) { map.put(k, v); }

            static double numOf(Val v) {
                if (v instanceof Num) {
                    return ((Num) v).d;
                }
                throw new IllegalArgumentException("not a number: " + v);
            }
        }

        static class Arr extends Val {
            final List<Val> items = new ArrayList<>();
        }

        static class Str extends Val {
            final String v;
            Str(String v) { this.v = v; }
        }

        static class Num extends Val {
            final double d;      // numeric value
            final boolean isInt; // true when the literal had no ./e/E (Python int)
            final long l;        // valid when isInt
            Num(double d) { this.d = d; this.isInt = false; this.l = 0; }
            Num(long l) { this.d = l; this.isInt = true; this.l = l; }
        }

        static class Bool extends Val {
            final boolean v;
            Bool(boolean v) { this.v = v; }
        }

        static class Null extends Val { }

        /** Parse a JSON document. */
        static Val parse(String text) {
            Parser p = new Parser(text);
            Val v = p.value();
            p.ws();
            if (p.pos != text.length()) {
                throw new IllegalArgumentException("trailing data at " + p.pos);
            }
            return v;
        }

        /** Write a value exactly like Python's json.dumps(v, indent=1, ensure_ascii=True). */
        static String dumps(Object v) {
            StringBuilder sb = new StringBuilder();
            dump(v, sb, 0);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        static void dump(Object v, StringBuilder sb, int level) {
            if (v == null || v instanceof Null) {
                sb.append("null");
            } else if (v instanceof Str) {
                quote(((Str) v).v, sb);
            } else if (v instanceof String) {
                quote((String) v, sb);
            } else if (v instanceof Num) {
                Num n = (Num) v;
                sb.append(n.isInt ? Long.toString(n.l) : jsonFloat(n.d));
            } else if (v instanceof Double) {
                sb.append(jsonFloat((Double) v));
            } else if (v instanceof Long) {
                sb.append(v);
            } else if (v instanceof Integer) {
                sb.append(v);
            } else if (v instanceof Boolean) {
                sb.append((Boolean) v ? "true" : "false");
            } else if (v instanceof Bool) {
                sb.append(((Bool) v).v ? "true" : "false");
            } else if (v instanceof Obj) {
                Obj o = (Obj) v;
                if (o.map.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                String pad = " ".repeat(level + 1);
                boolean first = true;
                for (Map.Entry<String, Val> e : o.map.entrySet()) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    first = false;
                    sb.append(pad);
                    quote(e.getKey(), sb);
                    sb.append(": ");
                    dump(e.getValue(), sb, level + 1);
                }
                sb.append("\n").append(" ".repeat(level)).append("}");
            } else if (v instanceof Arr) {
                dumpList(((Arr) v).items, sb, level);
            } else if (v instanceof List) {
                dumpList((List<Object>) v, sb, level);
            } else if (v instanceof Map) {
                // LinkedHashMap<String, Object> built by callers (insertion order).
                Map<String, Object> m = (Map<String, Object>) v;
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                String pad = " ".repeat(level + 1);
                boolean first = true;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    first = false;
                    sb.append(pad);
                    quote(e.getKey(), sb);
                    sb.append(": ");
                    dump(e.getValue(), sb, level + 1);
                }
                sb.append("\n").append(" ".repeat(level)).append("}");
            } else {
                throw new IllegalArgumentException("cannot dump " + v.getClass());
            }
        }

        static void dumpList(List<?> items, StringBuilder sb, int level) {
            if (items.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            String pad = " ".repeat(level + 1);
            boolean first = true;
            for (Object it : items) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append(pad);
                dump(it, sb, level + 1);
            }
            sb.append("\n").append(" ".repeat(level)).append("]");
        }

        /** Python's json float encoding: repr(), except inf/nan spellings. */
        static String jsonFloat(double d) {
            if (Double.isNaN(d)) {
                return "NaN";
            }
            if (d == Double.POSITIVE_INFINITY) {
                return "Infinity";
            }
            if (d == Double.NEGATIVE_INFINITY) {
                return "-Infinity";
            }
            return Py.repr(d);
        }

        /** ensure_ascii string quoting (non-ASCII -> \\uXXXX, lowercase hex). */
        static void quote(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                switch (cp) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (cp < 0x20 || cp > 0x7E) {
                            if (cp > 0xFFFF) {
                                // Surrogate pair, each \\uXXXX (like Python's ensure_ascii).
                                char hi = Character.highSurrogate(cp);
                                char lo = Character.lowSurrogate(cp);
                                sb.append(String.format("\\u%04x\\u%04x", (int) hi, (int) lo));
                            } else {
                                sb.append(String.format("\\u%04x", cp));
                            }
                        } else {
                            sb.appendCodePoint(cp);
                        }
                }
                i += Character.charCount(cp);
            }
            sb.append('"');
        }

        static class Parser {
            final String t;
            int pos;
            Parser(String t) { this.t = t; }

            void ws() {
                while (pos < t.length()) {
                    char c = t.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            Val value() {
                ws();
                if (pos >= t.length()) {
                    throw new IllegalArgumentException("unexpected end of input");
                }
                char c = t.charAt(pos);
                switch (c) {
                    case '{': return obj();
                    case '[': return arr();
                    case '"': return new Str(str());
                    case 't': expect("true"); return new Bool(true);
                    case 'f': expect("false"); return new Bool(false);
                    case 'n': expect("null"); return new Null();
                    default: return num();
                }
            }

            Obj obj() {
                Obj o = new Obj();
                pos++; // {
                ws();
                if (pos < t.length() && t.charAt(pos) == '}') {
                    pos++;
                    return o;
                }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (t.charAt(pos) != ':') {
                        throw new IllegalArgumentException("expected : at " + pos);
                    }
                    pos++;
                    o.put(k, value());
                    ws();
                    char c = t.charAt(pos);
                    if (c == ',') {
                        pos++;
                    } else if (c == '}') {
                        pos++;
                        return o;
                    } else {
                        throw new IllegalArgumentException("expected , or } at " + pos);
                    }
                }
            }

            Arr arr() {
                Arr a = new Arr();
                pos++; // [
                ws();
                if (pos < t.length() && t.charAt(pos) == ']') {
                    pos++;
                    return a;
                }
                while (true) {
                    a.items.add(value());
                    ws();
                    char c = t.charAt(pos);
                    if (c == ',') {
                        pos++;
                    } else if (c == ']') {
                        pos++;
                        return a;
                    } else {
                        throw new IllegalArgumentException("expected , or ] at " + pos);
                    }
                }
            }

            String str() {
                pos++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = t.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
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
                            case 'u': {
                                int cp = Integer.parseInt(t.substring(pos, pos + 4), 16);
                                pos += 4;
                                if (Character.isHighSurrogate((char) cp)
                                        && pos + 1 < t.length()
                                        && t.charAt(pos) == '\\' && t.charAt(pos + 1) == 'u') {
                                    int lo = Integer.parseInt(t.substring(pos + 2, pos + 6), 16);
                                    pos += 6;
                                    sb.append(Character.toChars(
                                            Character.toCodePoint((char) cp, (char) lo)));
                                } else {
                                    sb.append((char) cp);
                                }
                                break;
                            }
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }

            Val num() {
                // The writer emits NaN/Infinity (like Python's json); the parser
                // must read them back (Claude 2026-10-04).
                if (t.startsWith("NaN", pos)) {
                    pos += 3;
                    return new Num(Double.NaN);
                }
                if (t.startsWith("Infinity", pos)) {
                    pos += 8;
                    return new Num(Double.POSITIVE_INFINITY);
                }
                if (t.startsWith("-Infinity", pos)) {
                    pos += 9;
                    return new Num(Double.NEGATIVE_INFINITY);
                }
                int start = pos;
                while (pos < t.length() && "-+0123456789.eE".indexOf(t.charAt(pos)) >= 0) {
                    pos++;
                }
                String lit = t.substring(start, pos);
                boolean isFloat = lit.indexOf('.') >= 0 || lit.indexOf('e') >= 0
                        || lit.indexOf('E') >= 0;
                if (!isFloat) {
                    try {
                        return new Num(Long.parseLong(lit));
                    } catch (NumberFormatException e) {
                        return new Num(Double.parseDouble(lit)); // huge int -> double
                    }
                }
                return new Num(Double.parseDouble(lit));
            }

            void expect(String word) {
                if (!t.startsWith(word, pos)) {
                    throw new IllegalArgumentException("expected " + word + " at " + pos);
                }
                pos += word.length();
            }
        }
    }
}
