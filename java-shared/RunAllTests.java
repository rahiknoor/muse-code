// RunAllTests.java — idea: one command that runs every --self-test / --verify
// suite in the workspace and prints a single combined scoreboard.
//
// Each suite runs as its own subprocess (one JVM per tool, same as the cron
// bodies invoke them), so a crash or hang in one suite can't take down the
// sweep. A suite passes when it exits 0; anything else (non-zero exit,
// timeout) is a FAIL and its captured output is printed in full.
//
// Flaky handling (Bazel/Airflow pattern): a failed suite is retried ONCE
// after a short backoff (timeouts and runner errors are not retried — a hang
// is not a flake). Consecutive failures are tracked in
// scheduler_state/flaky_quarantine.json; after 3 in a row the suite is
// flagged QUARANTINED in the report and the retry is skipped (chronic
// failure, not a flake). Quarantine never weakens a check: a quarantined
// suite that fails still fails the sweep.
//
// Usage:
//   LC_ALL=C.UTF-8 ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/RunAllTests.java
//
// Exit 0 when every suite passes, 1 otherwise (via SelfTest.finish()).
//
// Suite registry: add new --self-test / --verify tools to SUITES below.
// (Rahik's rule, 2026-09-27: repeated steps become a method — one runner,
// not N copy-pasted invocations.)
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class RunAllTests {

    static final String HOME = System.getenv("HOME");
    static final String WS = HOME + "/workspace";
    static final String JAVA = WS + "/java/jdk-21/bin/java";
    /** Every suite gets both: shared test helpers + org.json (some need both). */
    static final String SUITE_CP = WS + "/java-shared/classes"
            + ":" + WS + "/lib/json/json-20240303.jar";

    /** Per-suite timeout: a hung suite fails instead of hanging the sweep. */
    static final long SUITE_TIMEOUT_MINUTES = 5;

    /** Cap on captured output kept per suite (chars). */
    static final int OUTPUT_CAP = 20000;

    /** Quarantine state: consecutive-failure history across sweep runs. */
    static final Path FLAKY_STATE =
            Paths.get(WS + "/scheduler_state/flaky_quarantine.json");

    /** Backoff before the single retry of a failed suite. */
    static final long RETRY_BACKOFF_MS = 20_000;

    /** One suite: display name, path under ~/workspace, argv for the tool. */
    record Suite(String name, String relPath, String... args) {}

    /** Suites whose source file has same-package sibling dependencies.
     *  The suite's own source plus its siblings are compiled together with
     *  javac into a temp dir that is prepended to the suite's classpath, and
     *  the compiled CLASS is launched (not the source file): the single-file
     *  launcher would isolate the named source in its own MemoryClassLoader,
     *  breaking package-private access between siblings in different loaders.
     *  (CrowdPulse and ModelSentiment reference each other, so neither
     *  compiles or links alone — same as the digest cron, which javacs both
     *  together and runs the class.) Keyed by exact suite name. */
    static final Map<String, List<String>> EXTRA_SOURCES = Map.of(
        "CrowdPulse --self-test", List.of(
            "goals/wallstreetbets-news-watch/hidden_files/ModelSentiment.java"),
        "ModelSentiment --self-test", List.of(
            "goals/wallstreetbets-news-watch/hidden_files/CrowdPulse.java"),
        "ProposalsSelfTest", List.of(
            "dcf_csv/Proposals.java"),
        "MethodologyProps --self-test", List.of(
            "dcf_csv/DcfModel.java"),
        "MethodologyProps --verify", List.of(
            "dcf_csv/DcfModel.java"));

    static final List<Suite> SUITES = List.of(
        // ---- --self-test suites ----
        new Suite("Sanitize --self-test",
                "atlas-scripts/Sanitize.java", "--self-test"),
        new Suite("SecNlmAudit --self-test",
                "dcf_csv/SecNlmAudit.java", "--self-test"),
        new Suite("MethodologyCheck --self-test",
                "dcf_csv/MethodologyCheck.java", "--self-test"),
        new Suite("MethodologyProps --self-test",
                "dcf_csv/MethodologyProps.java", "--self-test"),
        new Suite("RetentionSweep --self-test",
                "java-shared/RetentionSweep.java", "--self-test"),
        new Suite("RunKeys --self-test",
                "java-shared/RunKeys.java", "--self-test"),
        new Suite("RunKeys --contract",
                "java-shared/RunKeys.java", "--contract"),
        new Suite("WaitFor --self-test",
                "java-shared/WaitFor.java", "--self-test"),
        new Suite("Watermark --self-test",
                "java-shared/Watermark.java", "--self-test"),
        new Suite("LastGood --self-test",
                "java-shared/LastGood.java", "--self-test"),
        new Suite("CommentaryLog --self-test",
                "creator_tracker/CommentaryLog.java", "--self-test"),
        new Suite("AssumptionHarvest --self-test",
                "creator_tracker/AssumptionHarvest.java", "--self-test"),
        new Suite("AnalystActionScan --self-test",
                "dcf_csv/AnalystActionScan.java", "--self-test"),
        new Suite("AnalystCrossCheck --self-test",
                "dcf_csv/AnalystCrossCheck.java", "--self-test"),
        new Suite("CrowdPulse --self-test",
                "goals/wallstreetbets-news-watch/hidden_files/CrowdPulse.java",
                "--self-test"),
        new Suite("ModelSentiment --self-test",
                "goals/wallstreetbets-news-watch/hidden_files/ModelSentiment.java"),
        new Suite("DcfHistory --self-test",
                "dcf_csv/DcfHistory.java", "--self-test"),
        new Suite("CorrectionLedger --self-test",
                "correction_ledger/CorrectionLedger.java", "--self-test"),
        new Suite("FeedRss --self-test",
                "youtube/FeedRss.java", "--self-test"),
        new Suite("MemoryStaleness --self-test",
                "memory_audit/MemoryStaleness.java", "--self-test"),
        new Suite("IdeasBacklog --self-test",
                "ideas/IdeasBacklog.java", "--self-test"),
        new Suite("ProposalsSelfTest",
                "dcf_csv/ProposalsSelfTest.java"),
        new Suite("PipelineRunner --self-test",
                "java-shared/PipelineRunner.java", "--self-test"),
        new Suite("AgentBudget --self-test",
                "java-shared/AgentBudget.java", "--self-test"),
        new Suite("GuardFiring --self-test",
                "java-shared/GuardFiring.java", "--self-test"),

        // ---- pipeline --verify suites ----
        new Suite("DailyClosesPipeline --verify",
                "stock_watch/DailyClosesPipeline.java", "--verify"),
        new Suite("DropAlertPipeline --verify",
                "stock_watch/DropAlertPipeline.java", "--verify"),
        new Suite("DcfGapPipeline --verify",
                "stock_watch/DcfGapPipeline.java", "--verify"),
        new Suite("CreatorDiffPipeline --verify",
                "creator_tracker/CreatorDiffPipeline.java", "--verify"),
        new Suite("HitRatePipeline --verify",
                "creator_tracker/HitRatePipeline.java", "--verify"),
        new Suite("DcfHitRatePipeline --verify",
                "dcf_tracker/DcfHitRatePipeline.java", "--verify"),
        new Suite("ScoreboardPipeline --verify",
                "valuation_scoreboard/ScoreboardPipeline.java", "--verify"),
        new Suite("AtlasLinkPipeline --verify",
                "atlas-scripts/AtlasLinkPipeline.java", "--verify"),
        new Suite("CapabilityPipeline --verify",
                "capability-watch/CapabilityPipeline.java", "--verify"),
        new Suite("FailureWatchPipeline --verify",
                "goals/background-task-failure-notifications/hidden_files/FailureWatchPipeline.java",
                "--verify"),
        new Suite("ActionItemsPipeline --verify",
                "goals/daily-open-action-items-reminder/hidden_files/ActionItemsPipeline.java",
                "--verify"),
        new Suite("PerfumePricePipeline --verify",
                "goals/terre-d-herm-s-200-ml-price-watch/hidden_files/PerfumePricePipeline.java",
                "--verify"),
        new Suite("ThailandFlightPipeline --verify",
                "goals/thailand-flight-price-watch/hidden_files/ThailandFlightPipeline.java",
                "--verify"),
        new Suite("WsbNewsPipeline --verify",
                "goals/wallstreetbets-news-watch/hidden_files/WsbNewsPipeline.java",
                "--verify"),

        // ---- standalone --verify suites (no args) ----
        new Suite("DcfAuditVerify --verify",
                "dcf_csv/DcfAuditVerify.java", "--verify"),
        new Suite("FilingWatchVerify --verify",
                "dcf_csv/FilingWatchVerify.java", "--verify"),
        // ---- audit-gap verifiers (built 2026-09-29, Rahik's 12-gap mission) ----
        // WaccSectorAudit: currently exits 1 (QXO has no sector discount row)
        // — fail-closed by design; stays red until the QXO sector assignment
        // lands via proposal → audit → blessed.
        new Suite("WaccSectorAudit --verify",
                "dcf_csv/WaccSectorAudit.java", "--verify"),
        new Suite("GrowthOutlookHonesty --verify",
                "dcf_csv/GrowthOutlookHonesty.java", "--verify"),
        new Suite("GapWatchReplay --verify",
                "dcf_csv/GapWatchReplay.java", "--verify"),
        new Suite("MethodologyDocVsCode --verify",
                "dcf_csv/MethodologyDocVsCode.java", "--verify"),
        new Suite("MethodologyProps --verify",
                "dcf_csv/MethodologyProps.java", "--verify"),
        new Suite("BuyCallVerify",
                "youtube/BuyCallVerify.java"),
        new Suite("YoutubeDigestVerify",
                "youtube/YoutubeDigestVerify.java"),
        new Suite("NearbyCheckVerify",
                "maps_import/NearbyCheckVerify.java")
    );

    /** Result of one suite run. */
    record Result(Suite suite, boolean pass, int exitCode, String output) {}

    private RunAllTests() {
        // static-only
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== RunAllTests: " + SUITES.size() + " suites ==");
        System.out.println();
        Map<String, FlakyQuarantine.Entry> flaky = loadFlakyState();
        String today = LocalDate.now().toString();
        List<Result> results = new ArrayList<>();
        for (Suite s : SUITES) {
            FlakyQuarantine.Entry before = flaky.getOrDefault(s.name(),
                    new FlakyQuarantine.Entry(0, false, ""));
            Result r = runSuite(s);
            boolean retried = false;
            // Retry once with backoff on a plain failure. Timeouts (-1) and
            // runner errors (-2) are not retried, and neither are suites
            // already quarantined (chronic failure, not a flake).
            if (!r.pass && r.exitCode > 0 && !before.quarantined()) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                r = runSuite(s);
                retried = true;
            }
            FlakyQuarantine.Entry after =
                    FlakyQuarantine.record(flaky, s.name(), r.pass, today);
            saveFlakyState(flaky);
            String tag = r.pass && retried ? " (passed on retry)"
                    : !r.pass && after.quarantined()
                        ? " (QUARANTINED: " + after.consecutive()
                            + " consecutive failures)"
                    : !r.pass && retried ? " (failed, incl. 1 retry)"
                    : "";
            results.add(r);
            String summary = lastLine(r.output());
            if (r.pass) {
                SelfTest.check(s.name() + tag + " — " + summary, true);
            } else {
                String why = r.exitCode == -1
                        ? "timed out after " + SUITE_TIMEOUT_MINUTES + " min"
                        : "exit " + r.exitCode;
                SelfTest.check(s.name() + tag + " — FAILED (" + why + ")"
                        + (summary.isEmpty() ? "" : " — " + summary), false);
            }
        }
        System.out.println();
        boolean anyFail = false;
        for (Result r : results) {
            if (!r.pass) {
                anyFail = true;
                System.out.println("----- FAILED: " + r.suite().name() + " -----");
                System.out.println(r.output());
                System.out.println();
            }
        }
        if (!anyFail) {
            System.out.println("All " + results.size() + " suites green.");
        }
        SelfTest.finish();
    }

    static Result runSuite(Suite s) {
        // Suites with sibling sources: compile the suite's own source plus
        // its siblings together with javac into a temp dir prepended to the
        // classpath, then launch the COMPILED CLASS (not the source file).
        // The single-file launcher would recompile the named source into its
        // own MemoryClassLoader while siblings load from the app loader, and
        // same-package classes in different loaders cannot see each other's
        // package-private members (IllegalAccessError). One classloader for
        // all siblings avoids that — same as the digest cron, which javacs
        // both files and runs the class.
        String classPath = SUITE_CP;
        String launchTarget = WS + "/" + s.relPath();
        List<String> extras = EXTRA_SOURCES.getOrDefault(s.name(), List.of());
        if (!extras.isEmpty()) {
            try {
                Path tmp = Files.createTempDirectory("runalltests-");
                List<String> javac = new ArrayList<>();
                javac.add(JAVA.replace("/bin/java", "/bin/javac"));
                javac.add("-cp");
                javac.add(SUITE_CP);
                javac.add("-d");
                javac.add(tmp.toString());
                // The suite's own source plus its siblings, compiled
                // together (handles mutual references).
                javac.add(WS + "/" + s.relPath());
                for (String e : extras) {
                    javac.add(WS + "/" + e);
                }
                Process jp = new ProcessBuilder(javac)
                        .redirectErrorStream(true).start();
                String jout = new String(jp.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                boolean jok = jp.waitFor(2, TimeUnit.MINUTES)
                        && jp.exitValue() == 0;
                if (!jok) {
                    return new Result(s, false, jp.exitValue(),
                            "extra-source compile failed:\n" + jout);
                }
                classPath = tmp + ":" + SUITE_CP;
                String base = Paths.get(s.relPath()).getFileName().toString();
                launchTarget = base.substring(0, base.length() - ".java".length());
            } catch (Exception e) {
                return new Result(s, false, -2,
                        "runner error compiling extra sources: " + e);
            }
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(JAVA);
        cmd.add("--class-path");
        cmd.add(classPath);
        cmd.add(launchTarget);
        cmd.addAll(List.of(s.args()));
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Paths.get(WS).toFile());
            pb.environment().put("LC_ALL", "C.UTF-8");
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process proc = p;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        synchronized (buf) {
                            if (buf.size() < OUTPUT_CAP) {
                                buf.write(chunk, 0,
                                        Math.min(n, OUTPUT_CAP - buf.size()));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // reader thread: best effort
                }
            });
            boolean done = p.waitFor(SUITE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            reader.join(10_000);
            String out = buf.toString(StandardCharsets.UTF_8);
            if (!done) {
                p.destroyForcibly();
                return new Result(s, false, -1, out);
            }
            return new Result(s, p.exitValue() == 0, p.exitValue(), out);
        } catch (Exception e) {
            return new Result(s, false, -2,
                    "runner error: " + e);
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /** Load quarantine state; a corrupt/missing file never fails the sweep. */
    static Map<String, FlakyQuarantine.Entry> loadFlakyState() {
        try {
            return FlakyQuarantine.load(FLAKY_STATE);
        } catch (Exception e) {
            System.out.println(
                    "warning: could not read flaky state, starting fresh: " + e);
            return new LinkedHashMap<>();
        }
    }

    /** Persist quarantine state after every suite (cheap, crash-safe). */
    static void saveFlakyState(Map<String, FlakyQuarantine.Entry> flaky) {
        try {
            FlakyQuarantine.save(FLAKY_STATE, flaky);
        } catch (Exception e) {
            System.out.println("warning: could not save flaky state: " + e);
        }
    }

    /** Last non-empty line of the output, trimmed to a scoreboard snippet. */
    static String lastLine(String output) {
        if (output == null) {
            return "";
        }
        String last = "";
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                last = t;
            }
        }
        return last.length() > 160 ? last.substring(0, 157) + "..." : last;
    }
}
