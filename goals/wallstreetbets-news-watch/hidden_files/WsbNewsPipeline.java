import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One-command pipeline for the "wallstreetbets-news-watch" cron job
 * (every 30 min). Reuses the existing FetchWsbNews.java and VerifyPosts.java.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --run [--job=<job-id>]} — per-job time gating (see {@link #gate})
 *       → FetchWsbNews.java → seen_posts.json dedupe
 *       (ALL considered ids are appended, including stale/unreported ones) →
 *       drop posts older than 72h → VerifyPosts.java on the rest
 *       (true = report; false = never report; null = wait 60s, retry once,
 *       then report as unconfirmed) → print reportable posts as JSON for the
 *       agent to deliver → append the run_log.md line.</li>
 *   <li>{@code --dry-run [--job=<job-id>]} — same but writes nothing (no seen/run_log/state
 *       changes). Safe for testing.</li>
 *   <li>{@code --verify} — health check over the current real state.</li>
 * </ul>
 *
 * <p>Job ids: {@code wallstreetbets-news-watch} (the every-2h job; the default
 * when {@code --job} is omitted), {@code wallstreetbets-news-watch-morning},
 * {@code wallstreetbets-news-watch-morning-late}. The 2026-10-07
 * schedule/body mismatch (the shared pipeline ran full checks inside the
 * morning window because the gating lived only in the bodies' prose) is fixed
 * by enforcing each caller's window here, in code.
 *
 * <p>The browser fallback (when the script prints [] or errors) and the
 * CAPTCHA/429 hard-stop stay as AGENT steps in the cron body — this pipeline
 * handles the script path only. The pipeline never sends chat messages.
 *
 * <p>Testing: set PIPELINE_STATE_DIR to a /tmp copy of the hidden_files;
 * --run then reads/writes seen_posts.json, run_log.md, offhours_state.json
 * and the fetch snapshot there.
 */
public class WsbNewsPipeline {

    static final String HOME = homeDir();
    static final ZoneId ET = ZoneId.of("America/New_York");
    static final String JOB_ID = "wallstreetbets-news-watch";
    static final long RECENCY_HOURS = 72;
    static final long OFFHOURS_MIN_HOURS = 4;

    static String stateDir() {
        String o = System.getenv("PIPELINE_STATE_DIR");
        if (o != null && !o.isEmpty()) return o;
        return HOME + "/workspace/goals/wallstreetbets-news-watch/hidden_files";
    }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static String javaBin() {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isEmpty()) return jh + "/bin/java";
        // Cron agents often lack java on PATH — fall back to the workspace JDK.
        return homeDir() + "/workspace/java/jdk-21/bin/java";
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        // Optional per-job mode: --job=<cron job id>. Each caller gets its own
        // gating (see gate()); the default preserves the every-2h job's
        // behavior for callers that pass no --job.
        String job = JOB_ID;
        for (int i = 1; i < args.length; i++)
            if (args[i].startsWith("--job=") && args[i].length() > 6)
                job = args[i].substring(6);
        switch (mode) {
            case "--run": run(false, job); break;
            case "--dry-run": run(true, job); break;
            case "--verify": verify(); break;
            default:
                System.err.println("Usage: java WsbNewsPipeline.java --run [--job=<job-id>] | --dry-run [--job=<job-id>] | --verify");
                System.exit(2);
        }
    }

    // =====================================================================
    // --run / --dry-run
    // =====================================================================

    /** Morning-window jobs: they cover 7:00–9:00 AM ET only. */
    static boolean isMorningJob(String job) {
        return "wallstreetbets-news-watch-morning".equals(job)
                || "wallstreetbets-news-watch-morning-late".equals(job);
    }

    /**
     * Gate decision for --run. {@code fullCheck=false} means quiet exit with
     * {@code reason}; {@code offHoursFull=true} means this full check stamps
     * offhours_state.json after a successful fetch.
     */
    static final class Gate {
        boolean fullCheck;
        boolean offHoursFull;
        String reason;
    }

    /**
     * Per-job time gating (America/New_York hour). Pure function of its inputs
     * so it is unit-testable.
     *
     * <ul>
     *   <li>Morning jobs: full check inside 7:00–9:00 AM ET, quiet exit outside
     *       it. Inside the window they ignore offhours_state.json entirely
     *       (never read, never stamp).</li>
     *   <li>Every-2h job (default): quiet exit during night quiet hours
     *       (11 PM–5 AM ET) and during the morning window (7:00–9:00 AM ET,
     *       covered by the morning jobs); full check 9:00 AM–4:00 PM ET;
     *       shoulder hours (5–7 AM, 4–11 PM) check only when the last
     *       off-hours check is 4h+ old.</li>
     * </ul>
     */
    static Gate gate(int hour, String job, Instant offHoursLast, Instant now) {
        Gate g = new Gate();
        if (isMorningJob(job)) {
            if (hour >= 7 && hour < 9) {
                g.fullCheck = true;
            } else {
                g.reason = "outside morning window (7:00–9:00 AM ET)";
            }
            return g;
        }
        if (hour >= 23 || hour < 5) {
            g.reason = "night quiet hours (11 PM–5 AM ET)";
        } else if (hour >= 7 && hour < 9) {
            g.reason = "morning window (7:00–9:00 AM ET) — covered by wallstreetbets-news-watch-morning";
        } else if (hour >= 9 && hour < 16) {
            g.fullCheck = true;
        } else if (offHoursLast == null || ChronoUnit.HOURS.between(offHoursLast, now) >= OFFHOURS_MIN_HOURS) {
            g.fullCheck = true;
            g.offHoursFull = true;
            // NOTE: the off-hours stamp is written AFTER a successful fetch
            // (see below), so a failed fetch never suppresses the next check.
        } else {
            g.reason = "off-hours check only " +
                    ChronoUnit.MINUTES.between(offHoursLast, now) + "m ago (< 4h)";
        }
        return g;
    }

    static void run(boolean dryRun, String job) throws Exception {
        long runStart = System.currentTimeMillis();
        Path dir = Paths.get(stateDir());

        // 1. Time gating — per calling job (see gate()).
        ZonedDateTime now = ZonedDateTime.now(ET);
        // Morning jobs ignore offhours_state.json entirely: do not even read it.
        Instant offHoursLast = isMorningJob(job) ? null : readOffHoursLastCheck(dir);
        Gate g = gate(now.getHour(), job, offHoursLast, now.toInstant());
        boolean fullCheck = g.fullCheck;
        boolean offHoursFull = g.offHoursFull;
        String gateReason = g.reason;
        if (!fullCheck) {
            if (!dryRun) writeLastRun(runStart, job, "quiet_exit", gateReason, null, 0, 0, 0, 0, List.of(), 0, false);
            System.out.println(Json.dumps(Map.of("status", dryRun ? "dry_run:quiet_exit" : "quiet_exit",
                    "job", job, "reason", gateReason)));
            return; // quiet exit: no run_log.md line, no message (agent still records the run key)
        }
        // Dry-run reports what WOULD happen; it just must not write anything.

        // 2. Fetch via the resilient script. [] or an error means the AGENT
        //    takes the browser-fallback step from the cron body.
        // NOTE: FetchWsbNews uses the shared Polite helper (human pacing), so the
        // child launch needs java-shared on the class path (single-file launcher
        // resolves deps from --class-path, not --source-path).
        ExecResult f = exec(List.of(javaBin(), "--class-path", homeDir() + "/workspace/java-shared/classes",
                dir.resolve("FetchWsbNews.java").toString()), 180_000);
        String fetchStdout = f.stdout == null ? "" : f.stdout.strip();
        String fetchSource = firstFetchLine(f.stderr);
        List<Map<String, Object>> posts = new ArrayList<>();
        String fetchProblem = null;
        if (f.timedOut) {
            fetchProblem = "FetchWsbNews.java timed out";
        } else if (f.exit != 0) {
            fetchProblem = "FetchWsbNews.java exit=" + f.exit + ": " + firstLine(f.stderr);
        } else if (fetchStdout.equals("[]") || fetchStdout.isEmpty()) {
            fetchProblem = "FetchWsbNews.java printed no posts";
        } else {
            try {
                for (Object p : asList(Json.parse(fetchStdout))) {
                    Map<String, Object> m = asMap(p);
                    if (m != null && !str(m.get("id")).isBlank()) posts.add(m);
                }
                if (posts.isEmpty()) fetchProblem = "FetchWsbNews.java printed unparseable/empty post list";
            } catch (Exception e) {
                fetchProblem = "unparseable fetch output: " + e.getMessage();
            }
        }
        if (fetchProblem != null) {
            if (!dryRun) writeLastRun(runStart, job, "fetch_failed", fetchProblem, fetchSource,
                    0, 0, 0, 0, List.of(), 0, false);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", dryRun ? "dry_run:fetch_failed" : "fetch_failed");
            out.put("job", job);
            out.put("note", fetchProblem + " — agent takes the browser-fallback step from the cron body");
            System.out.println(Json.dumps(out));
            return;
        }
        if (!dryRun) atomicWrite(dir.resolve("last_fetch.json").toString(), fetchStdout);
        // Stamp the off-hours check only now that the fetch succeeded — a
        // failed fetch must not push the next off-hours check 4h out.
        if (offHoursFull && !dryRun) writeOffHoursNow(dir, now);

        // 3. Seen-list dedupe. "Considered" = fetched posts not seen before;
        //    EVERY considered id is appended — including stale and unreported.
        Set<String> seen = readSeen(dir);
        List<Map<String, Object>> considered = new ArrayList<>();
        for (Map<String, Object> p : posts)
            if (!seen.contains(str(p.get("id")))) considered.add(p);
        Set<String> newSeen = new LinkedHashSet<>(seen);
        for (Map<String, Object> p : considered) newSeen.add(str(p.get("id")));
        if (!dryRun) atomicWrite(dir.resolve("seen_posts.json").toString(), Json.dumps(new ArrayList<>(newSeen)));

        // 4. Recency: drop posts older than 72h (their ids stay in the seen
        //    list so they are never reconsidered).
        long nowEpoch = now.toEpochSecond();
        List<Map<String, Object>> fresh = new ArrayList<>();
        int stale = 0;
        for (Map<String, Object> p : considered) {
            long created = num(p.get("created_utc"));
            if (created > 0 && nowEpoch - created <= RECENCY_HOURS * 3600) fresh.add(p);
            else stale++;
        }

        // 5. Live verification. true = report; false = never report;
        //    null = wait 60s, retry once, then report as unconfirmed.
        List<Map<String, Object>> reportable = new ArrayList<>();
        int removed = 0;
        if (!fresh.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> p : fresh) ids.add(str(p.get("id")));
            Map<String, Object> verdicts = verifyPosts(dir, ids);
            List<String> retry = new ArrayList<>();
            for (String id : ids)
                if (!verdicts.containsKey(id) || verdicts.get(id) == null) retry.add(id);
            if (!retry.isEmpty()) {
                Thread.sleep(60_000); // one 60-second wait, then a single retry
                Map<String, Object> second = verifyPosts(dir, retry);
                for (String id : retry) verdicts.put(id, second.get(id)); // may still be null
            }
            for (Map<String, Object> p : fresh) {
                String id = str(p.get("id"));
                Object v = verdicts.get(id);
                if (Boolean.TRUE.equals(v)) {
                    reportable.add(postJson(p));
                } else if (Boolean.FALSE.equals(v)) {
                    removed++; // removed/deleted: never report
                } else {
                    Map<String, Object> u = postJson(p); // null verdict: removal unconfirmed
                    u.put("verification", "unconfirmed");
                    reportable.add(u);
                }
            }
        }

        // 6. Run log line (full-check runs only).
        String logLine = String.format("%s | source=%s | found=%d new=%d removed=%d stale=%d reportable=%d",
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                fetchSource.isEmpty() ? "unknown" : fetchSource,
                posts.size(), considered.size(), removed, stale, reportable.size());
        if (!dryRun) appendLine(dir.resolve("run_log.md").toString(), logLine);

        if (!dryRun) writeLastRun(runStart, job, "ok", null, fetchSource, posts.size(), considered.size(),
                removed, stale, idsOf(reportable), newSeen.size(), offHoursFull);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", dryRun ? "dry_run:ok" : "ok");
        out.put("job", job);
        out.put("off_hours_full_check", offHoursFull);
        out.put("reportable", reportable);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("found", posts.size());
        counts.put("new", considered.size());
        counts.put("removed", removed);
        counts.put("stale", stale);
        counts.put("reportable", reportable.size());
        out.put("counts", counts);
        out.put("delivery", reportable.isEmpty()
                ? "no live new News posts — stay silent"
                : "send the chat message listing each post's title, link, and time posted");
        System.out.println(Json.dumps(out));
    }

    static Map<String, Object> postJson(Map<String, Object> p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(p.get("id")));
        m.put("title", str(p.get("title")));
        m.put("link", str(p.get("link")));
        m.put("created_utc", num(p.get("created_utc")));
        return m;
    }

    static List<String> idsOf(List<Map<String, Object>> posts) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> p : posts) ids.add(str(p.get("id")));
        return ids;
    }

    /** Run VerifyPosts.java on the ids; returns id -> true/false/null. */
    static Map<String, Object> verifyPosts(Path dir, List<String> ids) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        List<String> cmd = new ArrayList<>(List.of(javaBin(), dir.resolve("VerifyPosts.java").toString()));
        cmd.addAll(ids);
        ExecResult r = exec(cmd, 180_000);
        if (r.timedOut || r.exit != 0) return out; // all null → caller retries once
        try {
            Map<String, Object> doc = asMap(Json.parse(r.stdout.strip()));
            if (doc != null) out.putAll(doc);
        } catch (Exception ignored) { }
        return out;
    }

    // ---- state files ----

    static Set<String> readSeen(Path dir) {
        Path p = dir.resolve("seen_posts.json");
        Set<String> seen = new LinkedHashSet<>();
        if (!Files.exists(p)) return seen;
        try {
            for (Object id : asList(Json.parse(Files.readString(p, StandardCharsets.UTF_8))))
                seen.add(String.valueOf(id));
        } catch (Exception ignored) { }
        return seen;
    }

    static Instant readOffHoursLastCheck(Path dir) {
        Path p = dir.resolve("offhours_state.json");
        if (!Files.exists(p)) return null;
        try {
            Map<String, Object> doc = asMap(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
            String ts = str(doc.get("last_check"));
            if (ts.isBlank()) return null;
            return OffsetDateTime.parse(ts).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    static void writeOffHoursNow(Path dir, ZonedDateTime now) throws IOException {
        atomicWrite(dir.resolve("offhours_state.json").toString(),
                Json.dumps(Map.of("last_check", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))));
    }

    static void writeLastRun(long runStart, String job, String status, String reason, String fetchSource,
                             int found, int fresh, int removed, int stale,
                             List<String> reportableIds, int seenSize, boolean offHoursFull) throws IOException {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("job", job);
        rec.put("run_start_epoch_ms", runStart);
        rec.put("run_start_et", ZonedDateTime.now(ET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        rec.put("status", status);
        rec.put("off_hours_full_check", offHoursFull);
        if (reason != null) rec.put("reason", reason);
        if (fetchSource != null) rec.put("fetch_source", fetchSource);
        rec.put("found", found);
        rec.put("new_considered", fresh);
        rec.put("removed", removed);
        rec.put("stale", stale);
        rec.put("reportable_ids", reportableIds);
        rec.put("seen_size", seenSize);
        atomicWrite(Paths.get(stateDir(), "last_run.json").toString(), Json.dumps(rec));
    }

    static void appendLine(String path, String line) throws IOException {
        Path p = Paths.get(path);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The fetch script logs its working source to stderr as "[wsb-fetch] ...". */
    static String firstFetchLine(String stderr) {
        if (stderr == null) return "";
        for (String line : stderr.split("\n")) {
            line = line.strip();
            if (line.startsWith("[wsb-fetch]")) return line;
        }
        return firstLine(stderr);
    }

    // =====================================================================
    // --verify
    // =====================================================================

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        Path dir = Paths.get(stateDir());
        Map<String, Object> rec = readLastRun();
        if (rec == null)
            failVerify(JOB_ID,
                    List.of("no last-run record at " + dir.resolve("last_run.json")
                            + " — --run did not complete or did not write its record"),
                    null);
        // Verify against the job that actually ran last (per-job state), not
        // the hardcoded default — the outcome-log line must land in that
        // job's own log file.
        String job = str(rec.get("job"));
        if (job.isBlank()) job = JOB_ID;
        String status = str(rec.get("status"));
        long runStart = num(rec.get("run_start_epoch_ms"));

        // 1. seen_posts.json must be a valid JSON array.
        Set<String> seen = null;
        try {
            Object parsed = Json.parse(Files.readString(dir.resolve("seen_posts.json"), StandardCharsets.UTF_8));
            if (!(parsed instanceof List)) {
                problems.add("seen_posts.json is not a JSON array");
            } else {
                seen = new LinkedHashSet<>();
                for (Object id : (List<?>) parsed) seen.add(String.valueOf(id));
            }
        } catch (Exception e) {
            problems.add("seen_posts.json missing/unparseable: " + e.getMessage());
        }

        if ("quiet_exit".equals(status)) {
            // 5. Quiet runs: run key + outcome log are the agent's job, but the
            //    pipeline must have written NO run_log.md line and sent nothing.
            long logMtime = fileMtime(dir.resolve("run_log.md").toString());
            if (logMtime >= runStart)
                problems.add("quiet-exit run appended a run_log.md line (forbidden)");
            long seenMtime = fileMtime(dir.resolve("seen_posts.json").toString());
            if (seenMtime >= runStart)
                problems.add("quiet-exit run modified seen_posts.json (forbidden)");
        } else if ("ok".equals(status)) {
            // 2. Every reported post id must be in the seen list.
            List<String> reported = new ArrayList<>();
            for (Object id : asList(rec.get("reportable_ids"))) reported.add(String.valueOf(id));
            if (seen != null)
                for (String id : reported)
                    if (!seen.contains(id))
                        problems.add("reported post " + id + " is not in seen_posts.json");

            // 3. No reported post older than 72h — recomputed from the fetch data.
            try {
                Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
                for (Object p : asList(Json.parse(Files.readString(dir.resolve("last_fetch.json"), StandardCharsets.UTF_8)))) {
                    Map<String, Object> m = asMap(p);
                    if (m != null) byId.put(str(m.get("id")), m);
                }
                for (String id : reported) {
                    Map<String, Object> p = byId.get(id);
                    if (p == null) {
                        problems.add("reported post " + id + " not found in last_fetch.json");
                        continue;
                    }
                    long created = num(p.get("created_utc"));
                    long ageH = (runStart / 1000 - created) / 3600;
                    if (created <= 0 || ageH > RECENCY_HOURS)
                        problems.add("reported post " + id + " is " + ageH + "h old (> 72h recency rule)");
                }
            } catch (Exception e) {
                problems.add("last_fetch.json missing/unparseable: " + e.getMessage());
            }

            // 4. Off-hours full checks must have stamped offhours_state.json.
            if (Boolean.TRUE.equals(rec.get("off_hours_full_check"))) {
                Instant last = readOffHoursLastCheck(dir);
                if (last == null || Math.abs(last.toEpochMilli() - runStart) > 3_600_000)
                    problems.add("off-hours full check did not stamp offhours_state.json near the run start");
            }
        }
        // fetch_failed: nothing to verify beyond the record itself.

        if (!problems.isEmpty())
            failVerify(job, problems, str(rec.get("run_start_et")));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("job", job);
        ok.put("last_run_status", status);
        ok.put("seen_size", seen == null ? -1 : seen.size());
        System.out.println(Json.dumps(ok));
    }

    /**
     * --verify failure path: fail LOUDLY and record the failure in the shared
     * outcome log (the task requires verification failures to be loud AND
     * recorded). PIPELINE_OUTCOME_DIR overrides the log directory in tests;
     * production uses ~/workspace/scheduler_state/job_outcomes. (Never Java
     * user.home — it resolves to /root in this sandbox; $HOME is the real home.)
     */
    static void failVerify(String jobId, List<String> problems, String runStartEt) {
        String dir = System.getenv("PIPELINE_OUTCOME_DIR");
        if (dir == null || dir.isEmpty())
            dir = System.getenv("HOME") + "/workspace/scheduler_state/job_outcomes";
        try {
            Files.createDirectories(Paths.get(dir));
            String stamp;
            if (runStartEt != null && runStartEt.length() >= 16) {
                stamp = runStartEt.substring(0, 10) + " " + runStartEt.substring(11, 16) + " ET";
            } else {
                stamp = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " ET";
            }
            String line = stamp + " | " + jobId + " | " + stamp.substring(0, 10) + " | "
                    + stamp.substring(11, 16) + " ET"
                    + " | outcome=failed | delivered=no | notes=verify failed: " + problems.get(0) + "\n";
            Files.writeString(Paths.get(dir, jobId + ".log"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("WARNING: could not append to outcome log: " + e.getMessage());
        }
        System.err.println("VERIFY FAILED (" + jobId + "):");
        for (String p : problems) System.err.println("  - " + p);
        System.exit(1);
    }

    static Map<String, Object> readLastRun() {
        try {
            return asMap(Json.parse(readFile(Paths.get(stateDir(), "last_run.json").toString())));
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // Small utilities
    // =====================================================================

    static class ExecResult {
        int exit = -1;
        String stdout = "";
        String stderr = "";
        boolean timedOut = false;
    }

    static ExecResult exec(List<String> cmd, long timeoutMs) {
        ExecResult r = new ExecResult();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LC_ALL", "C.UTF-8");
            pb.redirectErrorStream(false);
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t1 = new Thread(() -> { try { p.getInputStream().transferTo(out); } catch (IOException ignored) { } });
            t1.start();
            Thread t2 = new Thread(() -> { try { p.getErrorStream().transferTo(err); } catch (IOException ignored) { } });
            t2.start();
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            t1.join(5000);
            t2.join(5000);
            if (!done) {
                p.destroyForcibly();
                r.timedOut = true;
                return r;
            }
            r.exit = p.exitValue();
            r.stdout = out.toString(StandardCharsets.UTF_8);
            r.stderr = err.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            r.stderr = e.toString();
        }
        return r;
    }

    static String readFile(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    static void atomicWrite(String path, String content) throws IOException {
        Path target = Paths.get(path);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path tmp = Paths.get(path + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static long fileMtime(String path) {
        try {
            return Files.getLastModifiedTime(Paths.get(path)).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : List.of();
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(str(o)); } catch (NumberFormatException e) { return -1; }
    }

    /* =====================================================================
     * Minimal JSON reader/writer, embedded so this file runs standalone via
     * `java WsbNewsPipeline.java` (single-file launch cannot link other
     * files — the same pattern FetchWsbNews.java uses).
     * ===================================================================== */
    static final class Json {
        private final String s;
        private int pos;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.skipWs();
            Object v = j.readValue();
            j.skipWs();
            if (j.pos != j.s.length()) throw new IllegalArgumentException("trailing characters after JSON document");
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        private Object readValue() {
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return readNumber();
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("expected '" + lit + "'");
            pos += lit.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':'");
                pos++;
                skipWs();
                m.put(key, readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' or '}'");
            }
        }

        private List<Object> readArray() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected ',' or ']'");
            }
        }

        private String readString() {
            if (s.charAt(pos) != '"') throw new IllegalArgumentException("expected string");
            pos++;
            StringBuilder b = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: b.append(e); break;
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Double readNumber() {
            int start = pos;
            while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number at position " + start);
            }
        }

        static String dumps(Object v) {
            if (v == null) return "null";
            if (v instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
            if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
            if (v instanceof Map<?, ?> m) {
                StringBuilder b = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(",");
                    first = false;
                    b.append(dumps(String.valueOf(e.getKey()))).append(":").append(dumps(e.getValue()));
                }
                return b.append("}").toString();
            }
            if (v instanceof List<?> l) {
                StringBuilder b = new StringBuilder("[");
                boolean first = true;
                for (Object e : l) {
                    if (!first) b.append(",");
                    first = false;
                    b.append(dumps(e));
                }
                return b.append("]").toString();
            }
            return dumps(String.valueOf(v));
        }
    }
}
