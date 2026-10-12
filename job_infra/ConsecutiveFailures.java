import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDEA 9 — FAIL FAST EVERYWHERE, never silently forgotten: consecutive-failure escalator.
 *
 * <p>Every fast-fail a job logs goes to its outcome log
 * ({@code ~/workspace/scheduler_state/job_outcomes/<job-id>.log}) with the reason.
 * This tool reads those logs, counts CONSECUTIVE {@code outcome=failed} lines per job,
 * and prints one alert block per job whose trailing failure streak reaches the
 * threshold (default 3). An {@code outcome=succeeded} line resets the streak —
 * so by construction, an alerted streak was never self-recovered.
 *
 * <p>It is wired into the {@code background-failure-watch} cron (2h), whose worker
 * delivers each alert block to the failing job's own topic chat.
 *
 * <p>Line format it understands (per ~/workspace/shared/run_protocol.md):
 * {@code YYYY-MM-DD HH:MM ET | <job-id> | YYYY-MM-DD | HH:MM ET | outcome=succeeded|failed
 * | delivered=yes|no | notes=<one short phrase>}
 * Legacy free-form lines without an {@code outcome=} marker are ignored entirely —
 * they neither extend nor reset a streak.
 *
 * <p><b>Dev-test tagging convention (2026-09-28):</b> development-time test runs
 * (deliberate-red proofs, --verify probes, worker dry runs) MUST tag their
 * outcome-log lines with the token {@code dev-test} in the notes field, e.g.
 * {@code notes=dev-test: deliberate-red proof, verify must fail here}.
 * Tagged lines are invisible to streak math: they neither extend nor reset a
 * streak — a dev-test {@code outcome=succeeded} never resets a production
 * streak, and dev-test failures never build one. This exists because a worker
 * doing deliberate-red testing on DcfGapPipeline wrote 3 untagged
 * {@code outcome=failed} lines to dcf-gap-watch.log on 2026-09-28; the
 * failure watch read it as a 3-streak production outage and auto-reverted a
 * healthy in-development file. Untagged historical lines still count as
 * production lines (history is never rewritten); the next production
 * {@code outcome=succeeded} resets any such stale streak.
 *
 * <p>Usage:
 * <pre>
 *   java --class-path ~/workspace/java-shared/classes ConsecutiveFailures.java --check [--outcomes-dir DIR] [--threshold N] [--job JOB_ID]
 *   java --class-path ~/workspace/java-shared/classes ConsecutiveFailures.java --self-test
 * </pre>
 * {@code --check} always exits 0 on a completed scan (findings go to stdout; the
 * worker parses the alert blocks). Exit 1 only on a hard error.
 */
public class ConsecutiveFailures {

    static final int DEFAULT_THRESHOLD = 3;
    static final String ALERT_OPEN = "---CONSECUTIVE-FAILURES-ALERT---";
    static final Pattern LEADING_TS =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(?::\\d{2})? (?:ET|EDT))");

    /** Token in the notes field marking a development-time test line (see header).
     * Such lines are invisible to streak math. */
    static final String DEV_TEST_TOKEN = "dev-test";

    /** UTF-8 output: the default console charset may be ASCII when LANG is unset,
     * which would corrupt non-ASCII text (—, é, …) copied from outcome-log notes. */
    static final PrintStream OUT =
            new PrintStream(System.out, true, StandardCharsets.UTF_8);

    /** One job whose trailing failure streak reached the threshold. */
    record Alert(String jobId, int streak, String lastFailedAt, String lastError) {
    }

    public static void main(String[] args) throws Exception {
        boolean selfTest = false;
        // Never System.getProperty("user.home") here: on this VM it returns /root,
        // while the workspace lives under $HOME (/home/hatch). See ~/TOOLS.md.
        String home = System.getenv("HOME");
        Path dir = Paths.get(home + "/workspace/scheduler_state/job_outcomes");
        int threshold = DEFAULT_THRESHOLD;
        String onlyJob = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--self-test" -> selfTest = true;
                case "--check" -> { /* default action: scan and print alert blocks */ }
                case "--outcomes-dir" -> dir = Paths.get(args[++i]);
                case "--threshold" -> threshold = Integer.parseInt(args[++i]);
                case "--job" -> onlyJob = args[++i];
                default -> {
                    System.err.println("ConsecutiveFailures: unknown arg: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (selfTest) {
            runSelfTest();
            return; // unreachable: SelfTest.finish() exits
        }
        for (Alert a : scan(dir, threshold, onlyJob)) {
            OUT.print(formatAlert(a));
        }
    }

    /**
     * Scans every {@code *.log} in {@code dir} and returns one alert per job whose
     * trailing consecutive-failure streak is {@code >= threshold}.
     */
    static List<Alert> scan(Path dir, int threshold, String onlyJob) throws IOException {
        List<Alert> alerts = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            System.err.println("ConsecutiveFailures: outcomes dir not found: " + dir + " — no alerts");
            return alerts;
        }
        List<Path> logs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.log")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) {
                    logs.add(p);
                }
            }
        }
        logs.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path log : logs) {
            String file = log.getFileName().toString();
            String jobId = file.substring(0, file.length() - ".log".length());
            if (onlyJob != null && !onlyJob.equals(jobId)) {
                continue;
            }
            int streak = 0;
            String lastAt = null;
            String lastErr = null;
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (isDevTestLine(line)) {
                    continue; // dev/test runs: invisible to streak math
                }
                if (line.contains("outcome=failed")) {
                    streak++;
                    lastAt = leadingTimestamp(line);
                    lastErr = notesOf(line);
                } else if (line.contains("outcome=succeeded")) {
                    streak = 0;
                    lastAt = null;
                    lastErr = null;
                }
                // anything else (legacy free-form lines): ignored, streak untouched
            }
            if (streak >= threshold) {
                alerts.add(new Alert(jobId, streak, lastAt, lastErr));
            }
        }
        return alerts;
    }

    /** The printable alert block the cron worker parses and delivers. */
    static String formatAlert(Alert a) {
        return ALERT_OPEN + "\n"
                + "job_id: " + a.jobId() + "\n"
                + "streak: " + a.streak() + " consecutive failures\n"
                + "last_failed_at: " + a.lastFailedAt() + "\n"
                + "last_error: " + a.lastError() + "\n";
    }

    /** True when the line's notes field carries the dev-test token: a
     * development-time test line, invisible to streak math. */
    static boolean isDevTestLine(String line) {
        int i = line.indexOf("notes=");
        if (i < 0) {
            return false;
        }
        return line.substring(i + "notes=".length()).contains(DEV_TEST_TOKEN);
    }

    static String leadingTimestamp(String line) {
        Matcher m = LEADING_TS.matcher(line);
        return m.find() ? m.group(1) : "not recorded";
    }

    static String notesOf(String line) {
        int i = line.indexOf("notes=");
        if (i < 0) {
            return "not recorded";
        }
        String notes = line.substring(i + "notes=".length()).trim();
        return notes.isEmpty() ? "not recorded" : notes;
    }

    // ------------------------------------------------------------------
    // --self-test (shared SelfTest harness; synthetic logs in temp dirs)
    // ------------------------------------------------------------------

    static String proto(String job, String date, String hm, boolean failed, String notes) {
        return date + " " + hm + " ET | " + job + " | " + date + " | " + hm + " ET"
                + " | outcome=" + (failed ? "failed" : "succeeded")
                + " | delivered=no | notes=" + notes;
    }

    static Path writeLog(Path dir, String jobId, String... lines) throws IOException {
        Path f = dir.resolve(jobId + ".log");
        Files.write(f, List.of(lines), StandardCharsets.UTF_8);
        return f;
    }

    static void runSelfTest() throws Exception {
        // 1. A 3-failure streak fires, naming the job, streak, last time, last error.
        Path d1 = Files.createTempDirectory("cf-t1-");
        writeLog(d1, "synth-three",
                proto("synth-three", "2026-09-28", "01:00", true, "first boom"),
                proto("synth-three", "2026-09-28", "02:00", true, "second boom"),
                proto("synth-three", "2026-09-28", "03:00", true, "third boom"));
        List<Alert> a1 = scan(d1, 3, null);
        SelfTest.checkEq("3-failure streak fires exactly one alert", 1, a1.size());
        String block = a1.isEmpty() ? "" : formatAlert(a1.get(0));
        SelfTest.checkOutput("alert names job, streak, last time, last error", block,
                new String[]{"job_id: synth-three", "streak: 3 consecutive failures",
                        "last_failed_at: 2026-09-28 03:00 ET", "last_error: third boom"},
                new String[]{});

        // 2. A 2-failure streak stays silent.
        Path d2 = Files.createTempDirectory("cf-t2-");
        writeLog(d2, "synth-two",
                proto("synth-two", "2026-09-28", "01:00", true, "boom one"),
                proto("synth-two", "2026-09-28", "02:00", true, "boom two"));
        SelfTest.checkEq("2-failure streak stays silent", 0, scan(d2, 3, null).size());

        // 3. A success resets the streak (F,F,F,S,F,F -> trailing streak 2, silent).
        Path d3 = Files.createTempDirectory("cf-t3-");
        writeLog(d3, "synth-reset",
                proto("synth-reset", "2026-09-28", "01:00", true, "boom"),
                proto("synth-reset", "2026-09-28", "02:00", true, "boom"),
                proto("synth-reset", "2026-09-28", "03:00", true, "boom"),
                proto("synth-reset", "2026-09-28", "04:00", false, "recovered"),
                proto("synth-reset", "2026-09-28", "05:00", true, "boom"),
                proto("synth-reset", "2026-09-28", "06:00", true, "boom"));
        SelfTest.checkEq("streak resets after a success", 0, scan(d3, 3, null).size());

        // 4. Success then three failures fires again (recovery does not grant immunity).
        Path d4 = Files.createTempDirectory("cf-t4-");
        writeLog(d4, "synth-refail",
                proto("synth-refail", "2026-09-28", "01:00", false, "ok"),
                proto("synth-refail", "2026-09-28", "02:00", true, "boom"),
                proto("synth-refail", "2026-09-28", "03:00", true, "boom"),
                proto("synth-refail", "2026-09-28", "04:00", true, "boom"));
        List<Alert> a4 = scan(d4, 3, null);
        SelfTest.checkEq("post-recovery 3-streak fires", 1, a4.size());
        SelfTest.checkEq("post-recovery streak length is 3", 3, a4.get(0).streak());

        // 5. Longer streaks report their true length.
        Path d5 = Files.createTempDirectory("cf-t5-");
        writeLog(d5, "synth-five",
                proto("synth-five", "2026-09-28", "01:00", true, "b1"),
                proto("synth-five", "2026-09-28", "02:00", true, "b2"),
                proto("synth-five", "2026-09-28", "03:00", true, "b3"),
                proto("synth-five", "2026-09-28", "04:00", true, "b4"),
                proto("synth-five", "2026-09-28", "05:00", true, "b5"));
        List<Alert> a5 = scan(d5, 3, null);
        SelfTest.checkEq("5-streak fires", 1, a5.size());
        SelfTest.checkContains("5-streak reports streak: 5", formatAlert(a5.get(0)), "streak: 5 consecutive failures");

        // 6. Legacy free-form lines (no outcome= marker) neither extend nor reset.
        Path d6 = Files.createTempDirectory("cf-t6-");
        writeLog(d6, "synth-legacy",
                "2026-09-26 20:00 ET: batch finished — old free-form line, no outcome marker",
                proto("synth-legacy", "2026-09-28", "01:00", true, "boom"),
                "2026-09-26 20:35 EDT | synth-legacy.log | SUCCESS end-to-end (legacy format)",
                proto("synth-legacy", "2026-09-28", "02:00", true, "boom"),
                proto("synth-legacy", "2026-09-28", "03:00", true, "boom"));
        SelfTest.checkEq("legacy lines ignored; 3 protocol failures still fire", 1, scan(d6, 3, null).size());

        // 7. Empty dir -> no alerts; missing dir -> no alerts, no crash.
        Path d7 = Files.createTempDirectory("cf-t7-");
        SelfTest.checkEq("empty outcomes dir stays silent", 0, scan(d7, 3, null).size());
        SelfTest.checkEq("missing outcomes dir stays silent", 0,
                scan(d7.resolve("does-not-exist"), 3, null).size());

        // 8. Jobs are independent: one streaking job alerts, a quiet one does not.
        Path d8 = Files.createTempDirectory("cf-t8-");
        writeLog(d8, "synth-loud",
                proto("synth-loud", "2026-09-28", "01:00", true, "boom"),
                proto("synth-loud", "2026-09-28", "02:00", true, "boom"),
                proto("synth-loud", "2026-09-28", "03:00", true, "boom"));
        writeLog(d8, "synth-quiet",
                proto("synth-quiet", "2026-09-28", "01:00", false, "ok"),
                proto("synth-quiet", "2026-09-28", "02:00", true, "single hiccup"));
        List<Alert> a8 = scan(d8, 3, null);
        SelfTest.checkEq("only the streaking job alerts", 1, a8.size());
        SelfTest.checkEq("the alert names the right job", "synth-loud", a8.get(0).jobId());

        // 9. last_error is the MOST RECENT failure's notes, not the first.
        Path d9 = Files.createTempDirectory("cf-t9-");
        writeLog(d9, "synth-err",
                proto("synth-err", "2026-09-28", "01:00", true, "error-alpha"),
                proto("synth-err", "2026-09-28", "02:00", true, "error-beta"),
                proto("synth-err", "2026-09-28", "03:00", true, "error-gamma"));
        String b9 = formatAlert(scan(d9, 3, null).get(0));
        SelfTest.checkContains("last_error carries the latest failure", b9, "error-gamma");
        SelfTest.checkNotContains("last_error is not the oldest failure", b9, "error-alpha");

        // 10. --job filter scopes the scan to one job.
        SelfTest.checkEq("--job filter scopes the scan", 0, scan(d8, 3, "synth-quiet").size());
        SelfTest.checkEq("--job filter keeps the match", 1, scan(d8, 3, "synth-loud").size());

        // 11. Dev-test tagged failures do NOT extend a streak (2026-09-28: a worker's
        // deliberate-red --verify runs polluted dcf-gap-watch.log and faked a 3-streak).
        Path d11 = Files.createTempDirectory("cf-t11-");
        writeLog(d11, "synth-devtag",
                proto("synth-devtag", "2026-09-28", "01:00", true, "real boom one"),
                proto("synth-devtag", "2026-09-28", "02:00", true, "dev-test: deliberate-red verify should fail here"),
                proto("synth-devtag", "2026-09-28", "03:00", true, "dev-test: deliberate-red verify should fail here"),
                proto("synth-devtag", "2026-09-28", "04:00", true, "dev-test: deliberate-red verify should fail here"),
                proto("synth-devtag", "2026-09-28", "05:00", true, "real boom two"));
        SelfTest.checkEq("dev-tagged failures do not extend the streak", 0, scan(d11, 3, null).size());

        // 12. Dev-tagged lines neither extend nor reset: interleaved dev lines leave
        // the real streak intact.
        Path d12 = Files.createTempDirectory("cf-t12-");
        writeLog(d12, "synth-devmix",
                proto("synth-devmix", "2026-09-28", "01:00", true, "real boom one"),
                proto("synth-devmix", "2026-09-28", "02:00", true, "dev-test: deliberate-red probe"),
                proto("synth-devmix", "2026-09-28", "03:00", true, "real boom two"),
                proto("synth-devmix", "2026-09-28", "04:00", false, "dev-test: deliberate-green probe"),
                proto("synth-devmix", "2026-09-28", "05:00", true, "real boom three"));
        List<Alert> a12 = scan(d12, 3, null);
        SelfTest.checkEq("dev-tagged lines ignored; 3 real failures still fire", 1, a12.size());
        SelfTest.checkEq("streak counts only the 3 real failures", 3, a12.get(0).streak());

        SelfTest.finish();
    }
}
