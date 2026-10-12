// WaitFor.java — stagger guard for the morning browser-heavy job cluster.
//
// Big jobs used to pile up at 08:46 (youtube digest, bullish picks,
// insider/politician buys, bank bonus, flight watch, DCF gap watch — six
// browser-heavy agents at once). The fix is explicit sequencing: each job
// waits on its predecessor's recorded run key instead of all firing together.
//
// Usage (STEP at the top of a cron body, after the run-key dedupe check):
//   java --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/WaitFor.java run <predecessor-job-id> [--weekdays-only]
//     -> prints "proceed" (exit 0): the predecessor recorded a run key today.
//     -> prints "wait" (exit 4): the predecessor has not recorded today's run
//        yet — the caller must end quietly (record its own run key + outcome
//        log with notes=staggered-wait, no user-facing message).
//     -> prints "proceed (predecessor idle weekends)" (exit 0): --weekdays-only
//        was passed and today is Sat/Sun, so the predecessor never runs today.
//     -> prints "proceed (state unreadable)" (exit 0): the state file cannot
//        be trusted. This fails OPEN on purpose: the failure mode here is
//        resource contention, not duplicate delivery — each job's own RunKeys
//        dedupe (which fails closed) still guards delivery, and a missed
//        morning digest is worse than a contended one. The message names the
//        degradation so it shows up in logs.
//
// Matching is exact on the job-id segment (parsed with RunKeys.KEY_RX), never
// a string prefix: waiting on "bank-bonus" is not satisfied by "bank-bonus-2".
// Only top-level keys are scanned; the legacy "runs" list is historical and
// never holds today's keys. This helper READS only — RunKeys.java stays the
// one writer (Rahik's rule).
//
//   java --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/WaitFor.java --self-test
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

public final class WaitFor {

    /** Exit code meaning "predecessor not done — end this run quietly". */
    static final int EXIT_WAIT = 4;

    private WaitFor() { }

    static boolean isWeekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }

    /**
     * Read-only scan of the state file. Missing or blank file -> no entries
     * (predecessor simply has not run). A present-but-unparseable file throws:
     * callers must treat that as "state untrusted", never as "no runs".
     */
    static List<RunKeys.Entry> readEntriesOrThrow(Path f) throws IOException {
        List<RunKeys.Entry> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        String src = Files.readString(f, StandardCharsets.UTF_8);
        if (src.isBlank()) return out;
        List<String> raws = RunKeys.splitEntries(src);
        if (raws.isEmpty() && src.contains("\""))
            throw new IOException("completed_runs.json present but unparseable: " + f);
        for (String raw : raws) {
            String key = RunKeys.keyOf(raw);
            if (key != null) out.add(new RunKeys.Entry(key, raw));
        }
        return out;
    }

    /**
     * True when <jobId> recorded any run key dated <today> (time segment may
     * differ — scheduled vs actual fire time — so it is not compared).
     */
    static boolean predecessorDoneToday(String jobId, LocalDate today,
                                        List<RunKeys.Entry> entries) {
        String want = today.toString();
        for (RunKeys.Entry e : entries) {
            Matcher m = RunKeys.KEY_RX.matcher(e.key);
            if (m.matches() && m.group(1).equals(jobId) && m.group(2).equals(want))
                return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            System.out.println("usage: WaitFor.java run <predecessor-job-id> [--weekdays-only] | --self-test");
            System.exit(args.length == 0 ? 2 : 0);
        }
        if ("--self-test".equals(args[0])) {
            selfTest();
            return; // unreachable: SelfTest.finish() exits
        }
        if (!"run".equals(args[0]) || args.length < 2) {
            System.out.println("usage: WaitFor.java run <predecessor-job-id> [--weekdays-only]");
            System.exit(2);
        }
        String jobId = args[1];
        boolean weekdaysOnly = args.length > 2 && "--weekdays-only".equals(args[2]);
        LocalDate today = LocalDate.now(RunKeys.ZONE);

        if (weekdaysOnly && isWeekend(today)) {
            System.out.println("proceed (predecessor idle weekends)");
            return;
        }
        List<RunKeys.Entry> entries;
        try {
            entries = readEntriesOrThrow(RunKeys.stateFile());
        } catch (IOException e) {
            System.err.println("WARNING: stagger guard degraded — " + e.getMessage());
            System.out.println("proceed (state unreadable)");
            return;
        }
        if (predecessorDoneToday(jobId, today, entries)) {
            System.out.println("proceed");
        } else {
            System.out.println("wait");
            System.exit(EXIT_WAIT);
        }
    }

    // ------------------------------------------------------------------
    // Self-test on synthetic state files (never touches the live file).

    static void selfTest() throws Exception {
        Path t = Files.createTempDirectory("waitfor-test");
        LocalDate monday = LocalDate.of(2026, 9, 28);   // Monday (verified)
        LocalDate saturday = LocalDate.of(2026, 9, 26); // Saturday (verified)
        LocalDate sunday = LocalDate.of(2026, 9, 27);   // Sunday (verified)

        // 1. weekend detection
        SelfTest.check("2026-09-26 is weekend", isWeekend(saturday));
        SelfTest.check("2026-09-27 is weekend", isWeekend(sunday));
        SelfTest.check("2026-09-28 is not weekend", !isWeekend(monday));

        // 2. missing file -> not done (wait)
        Path missing = t.resolve("nope.json");
        SelfTest.check("missing state file -> wait",
            !predecessorDoneToday("job", monday, readEntriesOrThrow(missing)));

        // 3. blank file -> not done (wait)
        Path blank = t.resolve("blank.json");
        Files.writeString(blank, "  \n", StandardCharsets.UTF_8);
        SelfTest.check("blank state file -> wait",
            !predecessorDoneToday("job", monday, readEntriesOrThrow(blank)));

        // 4. predecessor keyed today (canonical dict shape) -> proceed
        Path f = t.resolve("completed_runs.json");
        RunKeys.record(f, "daily-youtube-stock-video-digest | 2026-09-28 | 08:46 ET");
        SelfTest.check("predecessor key today -> proceed",
            predecessorDoneToday("daily-youtube-stock-video-digest", monday,
                readEntriesOrThrow(f)));

        // 5. predecessor keyed yesterday only -> wait
        RunKeys.record(f, "bank-bonus-watch | 2026-09-27 | 08:46 ET");
        SelfTest.check("predecessor key yesterday only -> wait",
            !predecessorDoneToday("bank-bonus-watch", monday, readEntriesOrThrow(f)));

        // 6. different job keyed today -> wait (no cross-talk)
        RunKeys.record(f, "other-job | 2026-09-28 | 08:46 ET");
        SelfTest.check("different job today -> still wait",
            !predecessorDoneToday("bank-bonus-watch", monday, readEntriesOrThrow(f)));

        // 7. job-id prefix trap: "job-x" must not satisfy a wait on "job"
        RunKeys.record(f, "job-x | 2026-09-28 | 08:46 ET");
        SelfTest.check("prefix job id does not match",
            !predecessorDoneToday("job", monday, readEntriesOrThrow(f)));

        // 8. legacy string-value shape counts too
        Path legacy = t.resolve("legacy.json");
        Files.writeString(legacy,
            "{\n \"old-job | 2026-09-28 | 08:46 ET\": \"2026-09-28\"\n}",
            StandardCharsets.UTF_8);
        SelfTest.check("legacy string shape -> proceed",
            predecessorDoneToday("old-job", monday, readEntriesOrThrow(legacy)));

        // 9. key with seconds still matches
        RunKeys.record(f, "sec-job | 2026-09-28 | 08:46:00 ET");
        SelfTest.check("key with seconds -> proceed",
            predecessorDoneToday("sec-job", monday, readEntriesOrThrow(f)));

        // 10. corrupt file -> loud failure (fail open), never silent "wait".
        // (Mirrors RunKeys.isRecorded's own "present but unparseable"
        // definition: quotes present but zero entries split out.)
        Path corrupt = t.resolve("corrupt.json");
        Files.writeString(corrupt, "\"bare string, not an object\"", StandardCharsets.UTF_8);
        boolean threw = false;
        try {
            readEntriesOrThrow(corrupt);
        } catch (IOException e) {
            threw = true;
        }
        SelfTest.check("corrupt state file throws (fail open, not silent wait)", threw);

        // 11. the time segment is not compared: a key stamped with a different
        // fire time on the same day still satisfies the wait
        RunKeys.record(f, "bank-bonus-watch | 2026-09-28 | 07:15 ET");
        SelfTest.check("different time segment same day -> proceed",
            predecessorDoneToday("bank-bonus-watch", monday, readEntriesOrThrow(f)));

        SelfTest.finish();
    }
}
