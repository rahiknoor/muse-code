public class DcfCalculator {

    public static void main(String[] args) {
        // ---------- PUT YOUR NUMBERS HERE ----------
        String kind = "FCFF";    // "FCFF" or "FCFE" (exact)
        double base = 1000.0;    // base free cash flow ($ millions)
        double g = 0.12;         // growth rate: 0.12 = 12%
        double w = 0.09;         // discount rate: WACC for FCFF, cost of equity for FCFE
        double t = 0.025;        // terminal growth: 0.025 = 2.5%
        double shares = 100.0;   // diluted shares (millions)
        double netdebt = 500.0;  // net debt ($ millions)
        // -------------------------------------------

        if (!kind.equals("FCFF") && !kind.equals("FCFE")) {
            throw new IllegalArgumentException("kind must be \"FCFF\" or \"FCFE\"");
        }
        if (shares <= 0) {
            throw new IllegalArgumentException("shares must be positive");
        }
        if (base <= 0) {
            throw new IllegalArgumentException("base must be positive");
        }
        if (g > MAX_GROWTH) {
            System.out.printf("WARNING: g = %.1f%% exceeds the %.0f%% cap — valuation runs at %.0f%%%n%n",
                    g * 100, MAX_GROWTH * 100, MAX_GROWTH * 100);
        }

        double iv = intrinsicValue(kind, base, g, w, t, shares, netdebt);
        System.out.printf("%nIntrinsic value per share: $%.2f%n", iv);
    }

    /** Growth above 15% is capped — no company compounds faster for a decade. */
    static final double MAX_GROWTH = 0.15;
    /** Projection horizon in years. */
    static final int HORIZON = 10;
    /** Years of flat growth before the fade begins. */
    static final int FLAT_YEARS = 5;

    public static double intrinsicValue(String kind, double base, double g, double w,
                                        double t, double shares, double netdebt) {
        if (w <= t) {
            throw new IllegalArgumentException("WACC must be bigger than terminal growth");
        }
        double gc = Math.min(g, MAX_GROWTH);

        // Project HORIZON years of FCF. First FLAT_YEARS grow at gc,
        // then fade linearly down to the terminal rate.
        double pvFcf = 0.0;
        double fcf = base;
        System.out.println("Year | FCF ($M)");
        for (int y = 1; y <= HORIZON; y++) {
            double gy = (y <= FLAT_YEARS) ? gc : gc - (y - FLAT_YEARS) * (gc - t) / (HORIZON - FLAT_YEARS);
            fcf *= (1 + gy);
            pvFcf += fcf / Math.pow(1 + w, y - 0.5); // mid-year discounting
            System.out.printf("%4d | %8.1f%n", y, fcf);
        }

        // Gordon terminal value on year-10 FCF, discounted back.
        double tv = fcf * (1 + t) / (w - t);
        double pvTv = tv / Math.pow(1 + w, 9.5);

        double ev = pvFcf + pvTv;
        double equity = kind.equals("FCFE") ? ev : ev - netdebt;
        return equity / shares;
    }
}
