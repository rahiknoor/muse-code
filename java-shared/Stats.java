import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stats — shared statistical helpers for the workspace Java tools.
 *
 * <p>Rahik's rule (2026-09-27): repeated steps become a method. The median
 * computation was copy-pasted in 4+ DCF files (DcfModel, DerivedValueAgreement,
 * OutsideVoice, WebsiteConsensus) with identical logic. This is the single
 * copy now; new code uses these methods.
 *
 * <p>Two variants exist because callers have different contracts:
 * <ul>
 *   <li>{@link #median(List)} — nullable inputs, nulls skipped, result rounded
 *       to cents, null when empty. The valuation-column contract.</li>
 *   <li>{@link #median(double[])} — primitive array (no nulls possible),
 *       unrounded. The internal-computation contract.</li>
 * </ul>
 */
public final class Stats {

    private Stats() {}

    /**
     * Median of a list of Doubles. Null elements are skipped. Returns null
     * when the list is empty or all null. Result rounded to 2 decimals.
     */
    public static Double median(List<Double> vals) {
        List<Double> xs = new ArrayList<>();
        for (Double v : vals) if (v != null) xs.add(v);
        if (xs.isEmpty()) return null;
        Collections.sort(xs);
        int n = xs.size();
        double m = (n % 2 == 1) ? xs.get(n / 2)
                : (xs.get(n / 2 - 1) + xs.get(n / 2)) / 2.0;
        return Math.round(m * 100.0) / 100.0;
    }

    /**
     * Median of a primitive double array. Unrounded; caller owns rounding.
     * The input array is not modified.
     */
    public static double median(double[] vals) {
        double[] s = vals.clone();
        Arrays.sort(s);
        int n = s.length;
        if (n % 2 == 1) return s[n / 2];
        return (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }
}
