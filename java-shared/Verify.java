import java.util.List;

/**
 * Shared verify-report step for workspace Java tools.
 *
 * <p>Every {@code --verify} mode ends the same way: print the accumulated
 * INFO lines, then either the OK line (exit 0) or the failure header plus
 * the problem list (exit 1). That repeated tail is this one method
 * (Rahik's rule, 2026-09-27: repeated steps become a method).
 *
 * <p>Launch with the shared sources visible:
 * <pre>java --class-path ~/workspace/java-shared/classes YourTool.java --verify</pre>
 * (rebuild the classes with {@code ~/workspace/java-shared/build.sh} after edits).
 */
public final class Verify {

    private Verify() {
        // static-only
    }

    /** Outcome of {@link #checkLastRun}. */
    public enum LastRunState {
        /** A last-run record file exists; the caller validates its fields. */
        PRESENT,
        /**
         * No record, and the job never recorded a production run in
         * completed_runs.json — the first scheduled run is still pending.
         * Not a regression: INFO, not failure.
         */
        NOT_INITIALIZED,
        /**
         * No record, but completed_runs.json shows the job ran before — the
         * run did not write its record. A genuine regression: failure.
         */
        REGRESSION
    }

    /**
     * Shared last-run-record check for pipeline {@code --verify} modes
     * (Rahik's rule, 2026-09-27: repeated steps become a method).
     *
     * <p>A missing record is only a failure when there is positive evidence
     * the job ran: a run key {@code "<jobId> | <date> | <time>"} in
     * completed_runs.json. Every production run records its key per the run
     * protocol, so "ran but left no record" means the run didn't write its
     * state — a regression. "Never recorded a run" means the pipeline hasn't
     * had its first scheduled fire yet (e.g. a weekday job created on a
     * Saturday) — that is reported as INFO and is not a failure. Missed
     * production runs are still caught independently by each pipeline's
     * backend-freshness checks (stale caches fail on their own).
     *
     * @param jobId             the pipeline's JOB_ID (run-key prefix)
     * @param lastRunFile       path of the pipeline's last-run record
     * @param completedRunsJson path of scheduler_state/completed_runs.json
     * @param infos             INFO lines (an INFO is added when not initialized)
     * @param failures          failure lines (a failure is added on regression)
     * @return the outcome; callers validate record fields only on PRESENT
     */
    public static LastRunState checkLastRun(String jobId, java.nio.file.Path lastRunFile,
                                            String completedRunsJson,
                                            List<String> infos, List<String> failures) {
        if (java.nio.file.Files.isRegularFile(lastRunFile)) {
            return LastRunState.PRESENT;
        }
        boolean everRan = false;
        try {
            String text = java.nio.file.Files.readString(
                    java.nio.file.Paths.get(completedRunsJson),
                    java.nio.charset.StandardCharsets.UTF_8);
            everRan = text.contains("\"" + jobId + " |");
        } catch (Exception ignored) {
            // Unreadable run history: without positive evidence of a past
            // run there is no regression to report — INFO, not failure.
        }
        if (everRan) {
            failures.add("no last-run record at " + lastRunFile
                    + " but completed_runs.json shows '" + jobId
                    + "' ran before — the run did not write its record (regression)");
            return LastRunState.REGRESSION;
        }
        infos.add("not yet initialized — no production run of '" + jobId
                + "' recorded in completed_runs.json; first scheduled run still pending");
        return LastRunState.NOT_INITIALIZED;
    }

    /**
     * Prints INFO lines, then the OK line when {@code failures} is empty
     * (exit 0), otherwise {@code failHeader} plus each failure (exit 1).
     *
     * @param okLine     exact line to print on success, e.g.
     *                   "VERIFY OK (MyTool): 12 checks passed, no problems found."
     * @param failHeader exact header for the failure case, e.g.
     *                   "VERIFY FAILED (MyTool): 3 problem(s) found:"
     */
    public static void report(String okLine, String failHeader,
                              List<String> infos, List<String> failures) {
        for (String note : infos) {
            System.out.println("INFO: " + note);
        }
        if (failures.isEmpty()) {
            System.out.println(okLine);
            System.exit(0);
        }
        System.out.println(failHeader);
        for (String f : failures) {
            System.out.println("  - " + f);
        }
        System.exit(1);
    }
}
