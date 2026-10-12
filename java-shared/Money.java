import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One money formatter for the whole vault pipeline.
 *
 * <p>Python fmt_money: {@code f"${v:,.2f}".replace(".00", "") if v >= 100
 * else f"${v:,.2f}"}, "—" for None. Copied verbatim from
 * RefreshValuations (the valuation table's voice); StampResearchBriefs and
 * VaultConsistencyCheck both delegate here so a brief's live line can never
 * again print "$89.0" where the note's table prints "$88.95".
 *
 * <p>Run with: {@code java --class-path ~/workspace/java-shared/classes
 * Money.java --self-test}
 */
public class Money {

    /** Python f"{x:.2f}" on the exact binary value (round-half-even). */
    public static String fixed2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Insert thousands separators into a plain "1234.56" / "-1234.56" string. */
    public static String grouped(String plain) {
        boolean neg = plain.startsWith("-");
        String body = neg ? plain.substring(1) : plain;
        int dot = body.indexOf('.');
        String intPart = dot == -1 ? body : body.substring(0, dot);
        String fracPart = dot == -1 ? "" : body.substring(dot);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < intPart.length(); i++) {
            if (i > 0 && (intPart.length() - i) % 3 == 0) {
                b.append(',');
            }
            b.append(intPart.charAt(i));
        }
        return (neg ? "-" : "") + b + fracPart;
    }

    /**
     * The vault's single money format. {@code null} → "—" (never a borrowed
     * figure); values ≥ 100 drop a trailing ".00".
     */
    public static String fmtMoney(Double v) {
        if (v == null) {
            return "\u2014";
        }
        String s = "$" + grouped(fixed2(v));
        if (v >= 100) {
            s = s.replace(".00", ""); // str.replace: every literal occurrence
        }
        return s;
    }

    /** Python truthiness for numbers: None and 0.0 are falsy. */
    public static boolean truthy(Double v) {
        return v != null && v != 0.0;
    }

    static void runSelfTest() {
        SelfTest.checkEq("null -> em dash", "\u2014", fmtMoney(null));
        SelfTest.checkEq("174.8 -> $174.80", "$174.80", fmtMoney(174.8));
        SelfTest.checkEq("88.95 stays 2dp", "$88.95", fmtMoney(88.95));
        SelfTest.checkEq("7.8 -> $7.80", "$7.80", fmtMoney(7.8));
        SelfTest.checkEq("29.0229.. -> $29.02", "$29.02", fmtMoney(29.022958843566112));
        SelfTest.checkEq("5.039.. -> $5.04", "$5.04", fmtMoney(5.03907168105596));
        SelfTest.checkEq("180.0 -> $180 (.00 stripped)", "$180", fmtMoney(180.0));
        SelfTest.checkEq("grouped thousands", "$1,234.50", fmtMoney(1234.5));
        SelfTest.checkEq("grouped .00 stripped", "$1,235", fmtMoney(1235.0));
        SelfTest.checkEq("zero -> $0.00", "$0.00", fmtMoney(0.0));
        SelfTest.checkEq("truthy null", false, truthy(null));
        SelfTest.checkEq("truthy zero", false, truthy(0.0));
        SelfTest.checkEq("truthy value", true, truthy(3.5));
        SelfTest.finish();
    }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        System.out.println("Money: shared vault money formatter. Use --self-test.");
    }
}
