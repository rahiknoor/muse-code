// FailureWatchPipeline.java
//
// One-command pipeline for the hourly `background-failure-watch` cron
// (idea #43), with a built-in health-check verifier (idea #44).
//
// SPLIT OF RESPONSIBILITIES (the Java program cannot call the cron.*
// tools, so the scan step stays in the cron body):
//   1. The cron body calls cron.runs (no id, limit 50) and pipes that JSON
//      into:  java .../FailureWatchPipeline.java --run --stdin-runs
//   2. This program compares the runs against the watermark
//      (~/workspace/scheduler_state/failure_watch_watermark.txt, UTC
//      ISO-8601), formats the new-failure report as JSON on stdout, records
//      it in the goal's hidden_files/last_report.json, and advances the
//      watermark to now.
//   3. The body then enriches each reported failure via cron.view/cron.status
//      (title, scheduled fire time, error text, retry/next-run picture),
//      delivers the message (only if there are new failures), appends the
//      memory lines, and writes the outcome log.
//
// Watermark trade-off, stated plainly: the watermark advances when the scan
// completes, before the delivery step. If delivery itself failed, the failure
// would not be re-reported next run — but it stays on disk in
// last_report.json and in the outcome log, so nothing is silently lost.
//
// Usage:
//   cron.runs-output | java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/goals/background-task-failure-notifications/hidden_files/FailureWatchPipeline.java \
//        --run --stdin-runs [--dry-run] [--root DIR]
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        .../FailureWatchPipeline.java --verify [--root DIR]
//
// --dry-run:  compute the report, do not touch the watermark or last_report.json.
// --root:     test hook — replaces $HOME as the filesystem root.
// --verify:   read-only health check. Fails loud when:
//             * the watermark file is missing, not ISO-8601 UTC, or in the future,
//             * last_report.json exists but is unreadable,
//             * any failure recorded in last_report.json did NOT finish after
//               the watermark that was in force when it was reported
//               (i.e. the report contains a stale or phantom failure).

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

public class FailureWatchPipeline {

    static final String SELF_JOB_ID = "background-failure-watch";

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static Path workspaceRoot = Path.of(homeDir(), "workspace");
    static boolean dryRun = false;
    static boolean stdinRuns = false;

    static Path watermarkFile() {
        return workspaceRoot.resolve("scheduler_state").resolve("failure_watch_watermark.txt");
    }

    static Path hiddenFiles() {
        return workspaceRoot.resolve("goals").resolve("background-task-failure-notifications")
                .resolve("hidden_files");
    }

    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run": mode = "run"; break;
                case "--verify": mode = "verify"; break;
                case "--dry-run": dryRun = true; break;
                case "--stdin-runs": stdinRuns = true; break;
                case "--root": workspaceRoot = Path.of(args[++i], "workspace"); break;
                default: break;
            }
        }
        if (mode == null || (mode.equals("run") && !stdinRuns)) {
            System.out.println("Usage: <cron.runs JSON> | FailureWatchPipeline.java --run --stdin-runs [--dry-run] [--root DIR]");
            System.out.println("       FailureWatchPipeline.java --verify [--root DIR]");
            System.exit(2);
        }
        if (mode.equals("run")) run();
        else verify();
    }

    static void run() throws Exception {
        Path wmFile = watermarkFile();
        Instant now = Instant.now();
        String nowIso = now.toString(); // ISO-8601 UTC, e.g. 2026-09-27T19:03:22Z

        // Missing watermark: initialize and end quietly, per the job's rules.
        if (!Files.exists(wmFile)) {
            if (!dryRun) {
                Files.createDirectories(wmFile.getParent());
                Files.writeString(wmFile, nowIso + "\n", StandardCharsets.UTF_8);
            }
            JSONObject out = new JSONObject();
            out.put("initialized", true);
            out.put("watermark", nowIso);
            out.put("new_failures", new JSONArray());
            System.out.println(out.toString(2));
            return;
        }

        Instant watermarkBefore = parseWatermark(wmFile);

        // The cron.runs payload: either {"runs": [...]} or a bare [...].
        String stdin = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).trim();
        JSONArray runs;
        if (stdin.startsWith("[")) {
            runs = new JSONArray(stdin);
        } else {
            runs = new JSONObject(stdin).getJSONArray("runs");
        }

        JSONArray failures = new JSONArray();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.getJSONObject(i);
            if (!"failed".equals(r.optString("status", ""))) continue;
            if (SELF_JOB_ID.equals(r.optString("job_id", ""))) continue; // never report on itself
            if (r.isNull("finished_at_utc")) continue;
            // cron.runs reports finished_at_utc as epoch seconds.
            Instant finished = Instant.ofEpochSecond(r.getLong("finished_at_utc"));
            if (!finished.isAfter(watermarkBefore)) continue;
            JSONObject f = new JSONObject();
            f.put("job_id", r.optString("job_id", "unknown"));
            f.put("run_id", r.optString("run_id", ""));
            f.put("finished_at_utc", finished.toString());
            f.put("finished_at_epoch", r.getLong("finished_at_utc"));
            f.put("scheduled_for_local", r.optString("scheduled_for_local", ""));
            f.put("error_text", r.isNull("error_text") ? JSONObject.NULL : r.getString("error_text"));
            f.put("result_summary", r.isNull("result_summary") ? JSONObject.NULL : r.getString("result_summary"));
            failures.put(f);
        }

        // Persist the report for audit + for --verify, then advance the watermark.
        JSONObject report = new JSONObject();
        report.put("checked_at_utc", nowIso);
        report.put("watermark_before", watermarkBefore.toString());
        report.put("watermark_after", nowIso);
        report.put("new_failures", failures);
        if (!dryRun) {
            Files.createDirectories(hiddenFiles());
            Files.writeString(hiddenFiles().resolve("last_report.json"),
                    report.toString(2) + "\n", StandardCharsets.UTF_8);
            Files.writeString(wmFile, nowIso + "\n", StandardCharsets.UTF_8);
        }

        JSONObject out = new JSONObject();
        out.put("initialized", false);
        out.put("watermark_before", watermarkBefore.toString());
        out.put("watermark_after", nowIso);
        out.put("new_failures", failures);
        out.put("dry_run", dryRun);
        System.out.println(out.toString(2));
    }

    static Instant parseWatermark(Path wmFile) throws Exception {
        String text = Files.readString(wmFile, StandardCharsets.UTF_8).trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            System.err.println("RUN FAILED: watermark file is not valid ISO-8601 UTC: '" + text + "'");
            System.exit(1);
            throw new IllegalStateException(); // unreachable
        }
    }

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        Path wmFile = watermarkFile();

        Instant watermark = null;
        if (!Files.exists(wmFile)) {
            problems.add("watermark file missing: " + wmFile);
        } else {
            String text;
            try {
                text = Files.readString(wmFile, StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                problems.add("watermark file unreadable: " + e.getMessage());
                text = "";
            }
            if (!text.isEmpty()) {
                try {
                    watermark = Instant.parse(text);
                } catch (DateTimeParseException e) {
                    problems.add("watermark is not valid ISO-8601 UTC: '" + text + "'");
                }
                // Generous 5-minute allowance for clock skew; anything beyond
                // that means the watermark is genuinely in the future.
                if (watermark != null && watermark.isAfter(Instant.now().plusSeconds(300)))
                    problems.add("watermark is in the future: '" + text + "'");
            }
        }

        // Every failure the last report claimed must have finished AFTER the
        // watermark that was in force when the report ran — otherwise the
        // report contains a stale or phantom failure.
        Path lastReport = hiddenFiles().resolve("last_report.json");
        if (Files.exists(lastReport)) {
            try {
                JSONObject rep = new JSONObject(Files.readString(lastReport, StandardCharsets.UTF_8));
                Instant before = Instant.parse(rep.getString("watermark_before"));
                JSONArray failures = rep.optJSONArray("new_failures");
                if (failures == null) problems.add("last_report.json has no 'new_failures' array");
                else for (int i = 0; i < failures.length(); i++) {
                    JSONObject f = failures.getJSONObject(i);
                    Instant finished = Instant.ofEpochSecond(f.getLong("finished_at_epoch"));
                    if (!finished.isAfter(before))
                        problems.add("last_report.json failure for job '" + f.optString("job_id")
                                + "' finished at " + f.optString("finished_at_utc")
                                + ", which is NOT after watermark_before " + rep.getString("watermark_before"));
                }
            } catch (Exception e) {
                problems.add("last_report.json unreadable or malformed: " + e.getMessage());
            }
        }

        if (!problems.isEmpty()) {
            System.err.println("VERIFY FAILED:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(1);
        }
        System.out.println("VERIFY OK: watermark"
                + (watermark != null ? " " + watermark : "")
                + " is valid; last report's failures all postdate its watermark.");
    }
}
