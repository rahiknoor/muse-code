import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared "last processed X" watermark helper (idea #7: incremental runs).
 *
 * <p>A watermark file holds one UTC ISO-8601 timestamp meaning "everything up
 * to this moment has been processed". A job reads it at run start, processes
 * only items NEWER than it, and advances it ONLY after a successful run —
 * never on failure, so a failed run keeps its window for the next attempt.
 *
 * <p>Usage (single-file launch, shared classes on the class path):
 * <pre>
 *   java --class-path ~/workspace/java-shared/classes ~/workspace/java-shared/Watermark.java --read &lt;path&gt;
 *       prints the stored value, or an empty line when the file is missing/empty (first run); always exit 0
 *   java --class-path ~/workspace/java-shared/classes ~/workspace/java-shared/Watermark.java --advance &lt;path&gt; &lt;iso8601&gt;
 *       writes &lt;iso8601&gt; only if it parses as UTC ISO-8601 AND is strictly after the stored value;
 *       exit 0 on write or harmless no-op, exit 1 (file untouched) when the value is invalid
 *   java --class-path ~/workspace/java-shared/classes ~/workspace/java-shared/Watermark.java --self-test
 * </pre>
 *
 * <p>Safety rules, enforced here so no caller can get them wrong:
 * <ul>
 *   <li>First run (missing/empty file) is normal: --read prints nothing, --advance creates the file.</li>
 *   <li>--advance never moves the watermark backward: an older-or-equal value is a no-op (exit 0).</li>
 *   <li>An invalid timestamp is rejected with exit 1 and the file is never touched.</li>
 * </ul>
 * "Advance only on success" is the CALLER's policy — the cron body must invoke
 * --advance only after the run's work completed successfully.
 */
public class Watermark {

    /** Exit code when the --advance value is not a valid ISO-8601 instant. */
    static final int EXIT_INVALID = 1;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Runs the command, returning the process exit code (testable without System.exit). */
    static int run(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            usage();
            return 0;
        }
        switch (args[0]) {
            case "--read":
                if (args.length != 2) {
                    usage();
                    return 2;
                }
                return read(Paths.get(args[1]));
            case "--advance":
                if (args.length != 3) {
                    usage();
                    return 2;
                }
                return advance(Paths.get(args[1]), args[2]);
            case "--self-test":
                return selfTest();
            default:
                usage();
                return 2;
        }
    }

    /** Prints the stored watermark (empty line when missing/empty); always exit 0. */
    static int read(Path path) {
        System.out.println(readValue(path));
        return 0;
    }

    /**
     * Advances the watermark. Returns 0 on write or harmless no-op,
     * 1 when {@code value} is invalid (file untouched).
     */
    static int advance(Path path, String value) {
        Instant next;
        try {
            next = Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            System.err.println("watermark: invalid ISO-8601 timestamp: " + value);
            return EXIT_INVALID;
        }
        String current = readValue(path);
        if (!current.isEmpty()) {
            try {
                Instant have = Instant.parse(current);
                if (!next.isAfter(have)) {
                    System.out.println("unchanged: " + current);
                    return 0;
                }
            } catch (DateTimeParseException e) {
                // Corrupt stored value must not block progress: fall through and replace it.
                System.err.println("watermark: stored value unparseable, replacing: " + current);
            }
        }
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, (next.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("watermark: write failed: " + e.getMessage());
            return EXIT_INVALID;
        }
        System.out.println(next);
        return 0;
    }

    /** Returns the trimmed first non-blank line of the file, or "" when missing/empty/unreadable. */
    static String readValue(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return "";
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    return t;
                }
            }
            return "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  Watermark.java --read <path>             print stored value (empty line = no watermark yet)");
        System.out.println("  Watermark.java --advance <path> <iso8601> write value if valid and newer; exit 1 if invalid");
        System.out.println("  Watermark.java --self-test               run the regression suite");
    }

    // ------------------------------------------------------------------
    // Self-test: real CLI paths and real subprocess exit codes.
    // ------------------------------------------------------------------

    static int selfTest() {
        try {
            Path dir = Files.createTempDirectory("watermark-test");
            Path wm = dir.resolve("wm.txt");
            try {
                // 1. First run: missing file reads as empty, exit 0.
                SelfTest.checkEq("first-run --read exit code", 0, run(new String[]{"--read", wm.toString()}));
                SelfTest.checkEq("first-run --read value", "", readValue(wm));

                // 2. Advance on a valid value: file created, exit 0.
                String t1 = "2026-09-28T10:58:48Z";
                SelfTest.checkEq("--advance valid exit code", 0, run(new String[]{"--advance", wm.toString(), t1}));
                SelfTest.checkEq("--advance wrote value", t1, readValue(wm));

                // 3. Advance with an OLDER value: no-op, file untouched, exit 0.
                String older = "2026-09-27T10:58:48Z";
                SelfTest.checkEq("--advance older exit code", 0, run(new String[]{"--advance", wm.toString(), older}));
                SelfTest.checkEq("--advance older did not move file", t1, readValue(wm));

                // 4. Advance with the SAME value: no-op, exit 0.
                SelfTest.checkEq("--advance equal exit code", 0, run(new String[]{"--advance", wm.toString(), t1}));
                SelfTest.checkEq("--advance equal did not move file", t1, readValue(wm));

                // 5. Advance with a NEWER value: file updated, exit 0.
                String t2 = "2026-09-28T12:00:00Z";
                SelfTest.checkEq("--advance newer exit code", 0, run(new String[]{"--advance", wm.toString(), t2}));
                SelfTest.checkEq("--advance newer wrote value", t2, readValue(wm));

                // 6. Invalid value: exit 1, file untouched ("no advance on failure").
                SelfTest.checkEq("--advance invalid exit code", EXIT_INVALID,
                        run(new String[]{"--advance", wm.toString(), "not-a-timestamp"}));
                SelfTest.checkEq("--advance invalid left file alone", t2, readValue(wm));

                // 7. Empty file behaves like a missing file (first run).
                Path empty = dir.resolve("empty.txt");
                Files.write(empty, new byte[0]);
                SelfTest.checkEq("empty file --read exit code", 0, run(new String[]{"--read", empty.toString()}));
                SelfTest.checkEq("empty file --read value", "", readValue(empty));

                // 8. Corrupt stored value does not block a valid advance.
                Path corrupt = dir.resolve("corrupt.txt");
                Files.write(corrupt, "garbage\n".getBytes(StandardCharsets.UTF_8));
                SelfTest.checkEq("corrupt store --advance exit code", 0,
                        run(new String[]{"--advance", corrupt.toString(), t1}));
                SelfTest.checkEq("corrupt store replaced", t1, readValue(corrupt));

                // 9. REAL subprocess exit codes (not just in-process returns).
                SelfTest.checkEq("subprocess --advance valid exit 0", 0,
                        cliExit(dir, "--advance", dir.resolve("sub.txt").toString(), t1));
                SelfTest.checkEq("subprocess wrote value", t1, readValue(dir.resolve("sub.txt")));
                SelfTest.checkEq("subprocess --advance invalid exit 1", 1,
                        cliExit(dir, "--advance", dir.resolve("sub.txt").toString(), "bogus"));
                SelfTest.checkEq("subprocess --read exit 0", 0,
                        cliExit(dir, "--read", dir.resolve("sub.txt").toString()));
                SelfTest.checkEq("subprocess --bogus-flag exit 2", 2,
                        cliExit(dir, "--bogus-flag"));
            } finally {
                deleteTree(dir);
            }
        } catch (IOException | InterruptedException e) {
            SelfTest.check("self-test setup", false);
            System.out.println("  setup failed: " + e.getMessage());
        }
        return SelfTest.summary() > 0 ? 1 : 0;
    }

    /**
     * Runs the real Watermark CLI in a child JVM and returns its true process
     * exit code. Uses this JVM's java.home and class path so the shared
     * classes (SelfTest) resolve exactly as they do for cron bodies.
     */
    static int cliExit(Path workDir, String... cliArgs) throws IOException, InterruptedException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("--class-path");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("Watermark");
        for (String a : cliArgs) {
            cmd.add(a);
        }
        Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true).start();
        // Drain stdout so a chatty child can never block on a full pipe.
        byte[] out = p.getInputStream().readAllBytes();
        int exit = p.waitFor();
        if (System.getenv("WATERMARK_TEST_DEBUG") != null) {
            System.out.println("  child: " + String.join(" ", cmd) + " -> " + exit
                    + " out=" + new String(out, StandardCharsets.UTF_8).trim());
        }
        return exit;
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort cleanup of test scratch
                        }
                    });
        } catch (IOException ignored) {
            // best effort cleanup of test scratch
        }
    }
}
