import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/// Transcript Agent — Rahik's single-task fleet, service agent #9.
///
/// ONE TASK: fetch YouTube transcripts through the ONE compliant route
/// (TranscriptFetch.java). It never fetches any other way — no yt-dlp,
/// no watch-page scraping, no concurrent fetchers, no retry wrappers.
///
/// What it enforces, so no other job has to:
///   1. Circuit breaker: if YouTube challenged us <24h ago, refuse (exit 3).
///   2. Single-flight: one fetch at a time, via a lock file (exit 4 if busy).
///   3. Delegation: runs TranscriptFetch.java with the caller's ids + job
///      name, streams its output, propagates its exit code.
///
/// Usage:
///   TranscriptAgent.java fetch <videoId>... --job <job-name>
///   TranscriptAgent.java --status          (breaker + lock state, no fetching)
///
/// NOT an auditor and NOT scheduled — it is an on-demand service every
/// job calls instead of touching TranscriptFetch directly.
public class TranscriptAgent {

    static final long BREAKER_HALT_MS = 24L * 3600_000;
    static final long LOCK_STALE_MS = 30L * 60_000;

    record Result(int exit, String message) {}

    /** Injected in tests; production shells out to TranscriptFetch.java. */
    interface Fetcher { int fetch(List<String> ids, String job) throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path breaker = breakerFile(args);
        Path lock = lockFile(args);

        if (args.length > 0 && args[0].equals("--status")) {
            System.out.println(status(breaker, lock));
            return;
        }
        if (args.length < 2 || !args[0].equals("fetch")) {
            System.err.println("usage: TranscriptAgent.java fetch <videoId>... --job <job-name>");
            System.err.println("       TranscriptAgent.java --status");
            System.exit(1);
        }
        List<String> ids = new ArrayList<>();
        String job = "transcript-agent";
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--job") && i + 1 < args.length) job = args[++i];
            else if (!args[i].startsWith("--")) ids.add(args[i]);
        }
        Result r = serve(ids, job, breaker, lock, TranscriptAgent::realFetch);
        System.out.println(r.message());
        System.exit(r.exit());
    }

    static Result serve(List<String> ids, String job, Path breaker, Path lock, Fetcher fetcher) {
        if (ids.isEmpty()) return new Result(1, "no video ids given");
        if (breakerOpen(breaker))
            return new Result(3, "REFUSED: YouTube circuit breaker open (challenged <24h ago) — skipping ALL YouTube work.");
        if (lockBusy(lock))
            return new Result(4, "REFUSED: another transcript fetch is already running — try again shortly.");
        try {
            Files.createDirectories(lock.getParent());
            Files.writeString(lock, job + " " + Instant.now());
        } catch (IOException e) {
            return new Result(1, "could not take fetch lock: " + e.getMessage());
        }
        int exit;
        try {
            exit = fetcher.fetch(ids, job);
        } catch (Exception e) {
            exit = 1;
            System.err.println("fetch failed: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
        return new Result(exit, exit == 0 ? "fetch complete (" + ids.size() + " video(s))"
                : "fetch finished with issues (exit " + exit + ") — see TranscriptFetch output above");
    }

    static boolean breakerOpen(Path breaker) {
        try {
            if (!Files.exists(breaker)) return false;
            FileTime mt = Files.getLastModifiedTime(breaker);
            return Instant.now().toEpochMilli() - mt.toMillis() < BREAKER_HALT_MS;
        } catch (IOException e) {
            return false; // fail-open on unreadable state; TranscriptFetch re-checks anyway
        }
    }

    static boolean lockBusy(Path lock) {
        try {
            if (!Files.exists(lock)) return false;
            FileTime mt = Files.getLastModifiedTime(lock);
            if (Instant.now().toEpochMilli() - mt.toMillis() > LOCK_STALE_MS) {
                Files.deleteIfExists(lock); // abandoned lock from a dead run
                return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String status(Path breaker, Path lock) {
        return "breaker: " + (breakerOpen(breaker) ? "OPEN (YouTube challenged <24h ago)" : "closed")
                + " | lock: " + (lockBusy(lock) ? "BUSY (a fetch is running)" : "free");
    }

    /** Production delegation: the one compliant route, nothing else. */
    static int realFetch(List<String> ids, String job) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("java",
                "--class-path", home() + "/workspace/java-shared/classes:"
                        + home() + "/workspace/lib/json/json-20240303.jar",
                home() + "/workspace/youtube/TranscriptFetch.java", "fetch"));
        cmd.addAll(ids);
        cmd.addAll(List.of("--job", job));
        Process p = new ProcessBuilder(cmd)
                .directory(Paths.get(home() + "/workspace/youtube").toFile())
                .inheritIO()
                .start();
        return p.waitFor();
    }

    // ---- plumbing ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static Path breakerFile(String[] args) {
        for (int i = 0; i + 1 < args.length; i++)
            if (args[i].equals("--breaker-file")) return Paths.get(args[i + 1]);
        return Paths.get(home() + "/workspace/youtube/cache/circuit_breaker.json");
    }

    static Path lockFile(String[] args) {
        for (int i = 0; i + 1 < args.length; i++)
            if (args[i].equals("--lock-file")) return Paths.get(args[i + 1]);
        return Paths.get(home() + "/workspace/scheduler_state/transcript_agent.lock");
    }

    // ---- self-test: fake fetcher, fake breaker/lock, no network ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("tagent-test");
        Path breaker = tmp.resolve("breaker.json");
        Path lock = tmp.resolve("fetch.lock");
        List<String> calls = new ArrayList<>();
        Fetcher fake = (ids, job) -> { calls.add(job + ":" + String.join(",", ids)); return 0; };

        // 1. Breaker open -> refuse, fetcher never called.
        Files.writeString(breaker, "{}");
        Result r1 = serve(List.of("abc"), "job1", breaker, lock, fake);
        check(r1.exit() == 3, "breaker-open must exit 3, got " + r1.exit());
        check(calls.isEmpty(), "fetcher must not run when breaker is open");

        // 2. Lock busy -> refuse.
        Files.delete(breaker);
        Files.writeString(lock, "other-job");
        Result r2 = serve(List.of("abc"), "job2", breaker, lock, fake);
        check(r2.exit() == 4, "busy lock must exit 4, got " + r2.exit());
        check(calls.isEmpty(), "fetcher must not run when lock is busy");

        // 3. Stale lock -> treated as abandoned, fetch proceeds.
        Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minusMillis(LOCK_STALE_MS + 1000)));
        Result r3 = serve(List.of("vid1", "vid2"), "job3", breaker, lock, fake);
        check(r3.exit() == 0, "stale lock must not block, got " + r3.exit());
        check(calls.equals(List.of("job3:vid1,vid2")), "fetcher must get ids+job, got " + calls);
        check(!Files.exists(lock), "lock must be released after fetch");

        // 4. Fetcher failure propagates its exit code.
        Fetcher failing = (ids, job) -> 2;
        Result r4 = serve(List.of("x"), "job4", breaker, lock, failing);
        check(r4.exit() == 2, "fetcher exit code must propagate, got " + r4.exit());
        check(!Files.exists(lock), "lock must be released even on failure");

        // 5. Status line renders.
        check(status(breaker, lock).contains("free"), "status must report free lock");

        System.out.println("TranscriptAgent self-test: ALL GREEN (breaker/lock refusals, stale-lock recovery, exit propagation)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
