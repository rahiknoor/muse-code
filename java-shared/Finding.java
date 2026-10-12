import java.util.List;

/**
 * Structured audit finding — borrowed pattern: Google Truth's {@code Fact}
 * (google/truth, core/src/main/java/com/google/common/truth/Fact.java):
 * failure details as key-value facts, never string-concatenated prose, so
 * every verifier's output is machine-parseable and uniformly formatted.
 *
 * <p>Detection stays in the verifier; rendering stays here. A verifier
 * collects {@code List<Finding>} and hands it to {@link Verify#report}
 * via {@link #split}. Exit code derives from max severity: any FAIL
 * fails the run (fail-closed, per the PropagationCheck precedent).
 *
 * <p>Launch with the shared sources visible:
 * <pre>java --class-path ~/workspace/java-shared/classes YourVerifier.java --verify</pre>
 * (rebuild the classes with {@code ~/workspace/java-shared/build.sh} after edits).
 */
public final class Finding {

    /** FAIL fails the run; WARN is surfaced but green; INFO is record only. */
    public enum Severity { FAIL, WARN, INFO }

    public final String checkId;     // e.g. "A1" — the audit-gap id from the 2026-09-29 list
    public final Severity severity;
    public final String ticker;      // ticker, or "-" for a portfolio-wide finding
    public final String field;       // what was checked, e.g. "buy_below"
    public final String expected;    // the independently recomputed / required value
    public final String actual;      // what the surface or backend shows
    public final String evidence;    // file + line (or source) grounding the claim
    public final String remediation; // what to do, e.g. "file proposal via Proposals.java"

    private Finding(String checkId, Severity severity, String ticker, String field,
                    Object expected, Object actual, String evidence, String remediation) {
        this.checkId = checkId;
        this.severity = severity;
        this.ticker = ticker;
        this.field = field;
        this.expected = String.valueOf(expected);
        this.actual = String.valueOf(actual);
        this.evidence = evidence;
        this.remediation = remediation;
    }

    public static Finding fail(String checkId, String ticker, String field,
                               Object expected, Object actual,
                               String evidence, String remediation) {
        return new Finding(checkId, Severity.FAIL, ticker, field,
                expected, actual, evidence, remediation);
    }

    public static Finding warn(String checkId, String ticker, String field,
                               Object expected, Object actual,
                               String evidence, String remediation) {
        return new Finding(checkId, Severity.WARN, ticker, field,
                expected, actual, evidence, remediation);
    }

    public static Finding info(String checkId, String ticker, String field,
                               Object expected, Object actual,
                               String evidence, String remediation) {
        return new Finding(checkId, Severity.INFO, ticker, field,
                expected, actual, evidence, remediation);
    }

    /** Uniform rendering: one block of "key: value" lines (Truth Fact style). */
    @Override
    public String toString() {
        return "check: " + checkId + "\n"
                + "severity: " + severity + "\n"
                + "ticker: " + ticker + "\n"
                + "field: " + field + "\n"
                + "expected: " + expected + "\n"
                + "actual: " + actual + "\n"
                + "evidence: " + evidence + "\n"
                + "remediation: " + remediation;
    }

    /** True when any finding has severity FAIL. */
    public static boolean anyFail(List<Finding> findings) {
        for (Finding f : findings) {
            if (f.severity == Severity.FAIL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits findings into the INFO / failure line lists {@link Verify#report}
     * expects. FAIL findings become failures; WARN and INFO become INFO lines
     * (a WARN never greens silently — it is printed on every run).
     */
    public static void split(List<Finding> findings,
                             List<String> infos, List<String> failures) {
        for (Finding f : findings) {
            if (f.severity == Severity.FAIL) {
                failures.add(f.toString());
            } else {
                infos.add("[" + f.severity + "] " + f.checkId + " " + f.ticker
                        + " " + f.field + " — expected " + f.expected
                        + ", actual " + f.actual + " (" + f.evidence + ")");
            }
        }
    }
}
