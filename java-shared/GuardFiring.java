// GuardFiring.java — shared firing log for the token-efficiency guards
// (BLG-020 transcript cap, BLG-021 probe aborts, BLG-022 agent budget,
// BLG-023 lean reads). Every guard firing appends one JSON line to
// ~/workspace/scheduler_state/guard_firings.jsonl so the firings are
// visible — to Rahik in chat (one-liner per firing) and to the Token Usage
// analysis (counts + which guards fire). Guards are never silent.
//
// Rahik's rule (2026-10-05): notify on every firing; notifications stay ON
// until he says they're too tedious, then he decides the action.
// "full speed" / "guards off" / "don't throttle" from him disables guards
// for that task/tree (per-task unless he says "always"); "try anyway"
// overrides a probe abort.
//
// Usage:
//   GuardFiring.log("transcript-cap", "chain=foo count=3/3");
//   GuardFiring.java --self-test

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class GuardFiring {
    static PrintStream OUT = System.out;

    /** Set false in self-tests so firing paths never pollute the real log.
     *  Public because single-file runs load helpers in a different
     *  class loader than the compiled classes (package-private access
     *  fails across that boundary — same reason SelfTest's API is public). */
    private static boolean enabled = true;

    public static void setEnabled(boolean b) {
        enabled = b;
    }

    /** Default log file. Never the Java user.home property (it resolves to
     *  /root here); always the $HOME environment variable. */
    static Path defaultLogFile() {
        return Path.of(System.getenv("HOME"), "workspace",
                "scheduler_state", "guard_firings.jsonl");
    }

    public static void log(String guard, String detail) {
        log(guard, detail, defaultLogFile());
    }

    public static void log(String guard, String detail, Path logFile) {
        if (!enabled) {
            return;
        }
        String line = "{\"ts\":\"" + Instant.now()
                + "\",\"guard\":\"" + esc(guard)
                + "\",\"detail\":\"" + esc(detail) + "\"}\n";
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            OUT.println("GuardFiring: could not append to " + logFile
                    + ": " + e);
        }
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    // ------------------------------------------------------------------
    // --self-test

    static void selfTest() throws Exception {
        // 1. A firing appends one JSON line to the given file.
        Path tmp = Files.createTempFile("guardfiring", ".jsonl");
        try {
            log("transcript-cap", "chain=audit-x count=3/3", tmp);
            String written = Files.readString(tmp, StandardCharsets.UTF_8);
            SelfTest.checkContains("line carries the guard name",
                    written, "\"guard\":\"transcript-cap\"");
            SelfTest.checkContains("line carries the detail",
                    written, "chain=audit-x count=3/3");
            SelfTest.checkContains("line carries a timestamp",
                    written, "\"ts\":\"");

            // 2. Two firings append as two lines.
            log("agent-budget", "tree=ideas n=10 threshold=10", tmp);
            long lines = Files.readAllLines(tmp, StandardCharsets.UTF_8)
                    .stream().filter(l -> !l.isBlank()).count();
            SelfTest.checkEq("two firings = two lines", 2L, lines);

            // 3. Quotes and backslashes in detail are escaped.
            log("lean-read", "path=\"weird\" \\ name", tmp);
            String w3 = Files.readString(tmp, StandardCharsets.UTF_8);
            SelfTest.checkContains("quote escaped", w3, "\\\"weird\\\"");
            SelfTest.checkContains("backslash escaped", w3, "\\\\ name");

            // 4. enabled=false suppresses the write entirely.
            Path tmp2 = Files.createTempFile("guardfiring-off", ".jsonl");
            try {
                setEnabled(false);
                log("probe", "should-not-appear", tmp2);
                SelfTest.checkEq("disabled guard writes nothing",
                        0L, Files.size(tmp2));
            } finally {
                setEnabled(true);
            }

            // 5. Default log path is the shared scheduler_state file
            //    ($HOME env, never the Java user.home property).
            String home = System.getenv("HOME");
            SelfTest.checkEq("default log file path",
                    Path.of(home, "workspace", "scheduler_state",
                            "guard_firings.jsonl"),
                    defaultLogFile());
        } finally {
            Files.deleteIfExists(tmp);
        }
        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // main

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--self-test")) {
            try {
                selfTest();
            } catch (Exception e) {
                OUT.println("self-test runner error: " + e);
                System.exit(2);
            }
            return;
        }
        OUT.println("Usage:");
        OUT.println("  GuardFiring.java --self-test   run the self-test suite");
        OUT.println("  (logging is via GuardFiring.log(guard, detail) from Java)");
        System.exit(2);
    }
}
