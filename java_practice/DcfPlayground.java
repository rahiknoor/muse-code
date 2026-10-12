public class DcfPlayground {

    public static void main(String[] args) {
        // ---------- PUT YOUR NUMBERS HERE ----------
        double base = 1000.0;   // base free cash flow ($ millions)
        double g = 0.12;        // growth rate: 0.12 = 12%
        double w = 0.09;        // WACC (discount rate): 0.09 = 9%
        double t = 0.025;       // terminal growth: 0.025 = 2.5%
        double shares = 100.0;  // diluted shares (millions)
        double netdebt = 500.0; // net debt ($ millions)
        // -------------------------------------------

        if (FLAT_YEARS >= HORIZON) {
            throw new IllegalArgumentException("FLAT_YEARS must be less than HORIZON");
        }
        if (shares <= 0) {
            throw new IllegalArgumentException("shares must be positive");
        }
        if (base <= 0) {
            throw new IllegalArgumentException("base must be positive");
        }
        // Loud cap warning (Claude's fix for the silent cap)
        if (g > MAX_GROWTH) {
            System.out.printf("WARNING: g = %.1f%% exceeds the %.0f%% cap — valuation runs at %.0f%%%n%n",
                    g * 100, MAX_GROWTH * 100, MAX_GROWTH * 100);
        }

        // Watch each step happen, one function at a time:
        System.out.print("Growth each year:          ");
        for (int y = 1; y <= HORIZON; y++) {
            System.out.printf("%.1f%% ", growthInYear(y, g, t) * 100);
        }
        System.out.println();

        double[] fcfs = projectFcfs(base, g, t);
        System.out.print("Projected FCF ($M):       ");
        for (double f : fcfs) {
            System.out.printf("%.0f ", f);
        }
        System.out.println();

        double pvFcf = presentValue(fcfs, w);
        System.out.printf("PV of %d-year FCF:        $%.1fM%n", HORIZON, pvFcf);

        double pvTv = presentValueOfTerminal(fcfs[HORIZON - 1], w, t);
        System.out.printf("PV of terminal value:      $%.1fM%n", pvTv);

        double ev = pvFcf + pvTv;
        System.out.printf("Enterprise value:          $%.1fM%n", ev);

        double equity = ev - netdebt; // FCFE: pass netdebt = 0.0 (headline and grid)
        System.out.printf("Equity value:              $%.1fM%n", equity);

        double iv = equity / shares;
        System.out.printf("Intrinsic value per share: $%.2f%n", iv);

        // Sensitivity grid: IV per share across WACC x terminal growth
        System.out.println("\nSensitivity grid (IV per share):");
        double[] wVals = {w - 0.02, w - 0.01, w, w + 0.01, w + 0.02};
        double[] tVals = {t - 0.01, t, t + 0.01};
        System.out.print("   w \\ t  |");
        for (double tt : tVals) {
            System.out.printf(" %9.1f%% |", tt * 100);
        }
        System.out.println();
        for (double ww : wVals) {
            System.out.printf("   %5.1f%%  |", ww * 100);
            if (ww <= 0) {
                System.out.println(" (skipped: WACC must be positive)");
                continue;
            }
            for (double tt : tVals) {
                // Epsilon guard: ww and tt are built by floating-point addition,
                // so an exact <= can miss a near-tie and print a garbage value.
                if (ww - tt < 1e-6) {
                    System.out.print("         — |");
                } else {
                    System.out.printf(" %9.2f |", intrinsicValue(base, g, ww, tt, shares, netdebt));
                }
            }
            System.out.println();
        }
    }

    /** Growth above 15% is capped — no company compounds faster for a decade. */
    static final double MAX_GROWTH = 0.15;
    /** Projection horizon in years. */
    static final int HORIZON = 10;
    /** Years of flat growth before the fade begins. */
    static final int FLAT_YEARS = 5;

    /** Growth rate in a single year: flat (capped) for FLAT_YEARS, then fades to t. */
    static double growthInYear(int year, double g, double t) {
        double gc = Math.min(g, MAX_GROWTH);
        if (year <= FLAT_YEARS) {
            return gc;
        }
        return gc - (year - FLAT_YEARS) * (gc - t) / (HORIZON - FLAT_YEARS);
    }

    /** Build the projected free cash flows using growthInYear. */
    static double[] projectFcfs(double base, double g, double t) {
        double[] fcfs = new double[HORIZON];
        double fcf = base;
        for (int y = 1; y <= HORIZON; y++) {
            fcf *= (1 + growthInYear(y, g, t));
            fcfs[y - 1] = fcf;
        }
        return fcfs;
    }

    /** Discount each FCF to today and add them up (mid-year: exponent y - 0.5). */
    static double presentValue(double[] fcfs, double w) {
        double pv = 0.0;
        for (int y = 1; y <= fcfs.length; y++) {
            pv += fcfs[y - 1] / Math.pow(1 + w, y - 0.5);
        }
        return pv;
    }

    /** Gordon terminal value on the final FCF, discounted back to today. */
    static double presentValueOfTerminal(double fcfLast, double w, double t) {
        double tv = fcfLast * (1 + t) / (w - t);
        return tv / Math.pow(1 + w, HORIZON - 0.5);
    }

    /** Full valuation in one call (no printing) — used by the sensitivity grid. */
    static double intrinsicValue(double base, double g, double w, double t,
                                 double shares, double netdebt) {
        if (w <= t) {
            throw new IllegalArgumentException("WACC must exceed terminal growth");
        }
        double[] fcfs = projectFcfs(base, g, t);
        double ev = presentValue(fcfs, w) + presentValueOfTerminal(fcfs[HORIZON - 1], w, t);
        return (ev - netdebt) / shares;
    }
}
