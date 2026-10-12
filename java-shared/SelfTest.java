import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Shared regression-test harness for workspace Java tools.
 *
 * <p>Every {@code --self-test} in a workspace tool uses this instead of
 * hand-rolling its own pass/fail counters (Rahik's rule, 2026-09-27:
 * repeated steps become a method). One suite per JVM run.
 *
 * <p>Launch with the shared sources visible:
 * <pre>java --class-path ~/workspace/java-shared/classes YourTool.java --self-test</pre>
 *
 * <p>Typical use:
 * <pre>
 *   SelfTest.checkEq("adds two numbers", 4, add(2, 2));
 *   SelfTest.checkNotContains("no script survives", sanitize(input), "&lt;script");
 *   SelfTest.finish(); // prints "N passed, M failed"; exit 1 on any failure
 * </pre>
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;

    /**
     * UTF-8 output. The default console charset may be ASCII when LANG is
     * unset, which would corrupt non-ASCII text (é, ☕, …) in failure dumps.
     */
    private static final PrintStream OUT =
            new PrintStream(System.out, true, StandardCharsets.UTF_8);

    private SelfTest() {
        // static-only
    }

    /** Generic assertion: records PASS/FAIL under {@code name}. */
    public static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            OUT.println("PASS: " + name);
        } else {
            failed++;
            OUT.println("FAIL: " + name);
        }
    }

    /** Asserts {@code expected.equals(actual)}; prints both on failure. */
    public static void checkEq(String name, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
            OUT.println("PASS: " + name);
        } else {
            failed++;
            OUT.println("FAIL: " + name);
            OUT.println("  expected: " + escape(String.valueOf(expected)));
            OUT.println("  actual  : " + escape(String.valueOf(actual)));
        }
    }

    /** Asserts {@code haystack} contains {@code needle}. */
    public static void checkContains(String name, String haystack, String needle) {
        check(name, haystack != null && haystack.contains(needle));
    }

    /** Asserts {@code haystack} does not contain {@code needle}. */
    public static void checkNotContains(String name, String haystack, String needle) {
        check(name, haystack == null || !haystack.contains(needle));
    }

    /**
     * Output-shape assertion: {@code output} must contain every entry of
     * {@code mustContain} and none of {@code mustNotContain}. Dumps the
     * output on failure so the broken case is visible.
     */
    public static void checkOutput(String name, String output,
                                   String[] mustContain, String[] mustNotContain) {
        boolean ok = true;
        StringBuilder why = new StringBuilder();
        for (String s : mustContain) {
            if (output == null || !output.contains(s)) {
                ok = false;
                why.append("  missing  : ").append(escape(s)).append('\n');
            }
        }
        for (String s : mustNotContain) {
            if (output != null && output.contains(s)) {
                ok = false;
                why.append("  leaked   : ").append(escape(s)).append('\n');
            }
        }
        if (ok) {
            passed++;
            OUT.println("PASS: " + name);
        } else {
            failed++;
            OUT.println("FAIL: " + name);
            OUT.print(why);
            OUT.println("  output   : " + escape(String.valueOf(output)));
        }
    }

    /**
     * Prints "N passed, M failed" and returns the failure count, so the
     * caller can {@code System.exit} on it.
     */
    public static int summary() {
        OUT.println();
        OUT.println(passed + " passed, " + failed + " failed");
        return failed;
    }

    /**
     * Ends the self-test run: prints the summary and exits 1 on any
     * failure, 0 when everything passes.
     */
    public static void finish() {
        System.exit(summary() > 0 ? 1 : 0);
    }

    /** Resets the counters; useful when one JVM runs several suites. */
    public static void reset() {
        passed = 0;
        failed = 0;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }
}
