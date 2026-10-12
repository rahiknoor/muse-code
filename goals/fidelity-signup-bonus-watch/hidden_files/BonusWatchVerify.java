// BonusWatchVerify.java — idea #44 health-check verifier for the
// bank-bonus-watch cron (covers the Fidelity AND Capital One sections —
// both snapshots are checked from this one location).
//
// WHY THIS EXISTS: the watch compares live offers against two snapshot
// files and messages only on real changes. If a snapshot got corrupted
// (bad write, truncated file), the next compare would "see" a phantom
// change and message Rahik about nothing — or worse, swallow a real change.
// This verifier fails LOUD on corruption instead.
//
// RUN IT with single-file launch (no compile step needed), AFTER STEP 4:
//   ~/workspace/java/jdk-21/bin/java \
//       ~/workspace/goals/fidelity-signup-bonus-watch/hidden_files/BonusWatchVerify.java
// Exit 0 = all checks passed. Any other exit = a check FAILED (see output).
//
// CHECKS:
//   1. Both snapshot files exist, are non-empty, and are non-corrupt:
//      every snapshot written by this job carries a "Last checked:" line
//      and at least one "###" offer heading. A file missing either smells
//      like a truncated or hand-broken write.
//   2. The outcome log exists and holds at least one line for this job,
//      so a post-hoc review can confirm the run actually recorded itself.
//
// HONEST LIMIT (printed on every pass, by design): post-hoc verification
// CANNOT prove the BASELINE RULE was honored — that a run which found an
// empty snapshot stayed silent for that section. Whether it stayed silent
// is only known at run time. The cron body therefore carries a VERIFIER
// SELF-CHECK section that restates the baseline rule as a mandatory
// pre-delivery gate; this program covers the corrupt-snapshot half.
//
// NOTE on paths: never use System.getProperty("user.home") here — on this
// machine it returns /root while the workspace lives under $HOME=/home/hatch.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BonusWatchVerify {

    // The one failure bucket every check reports into.
    private static final List<String> FAILURES = new ArrayList<>();

    private static Path ws() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            fail("HOME env var is not set — cannot resolve workspace paths");
            return null;
        }
        return Path.of(home, "workspace");
    }

    private static void fail(String msg) {
        FAILURES.add(msg);
    }

    // A healthy snapshot always has a "Last checked:" stamp and at least
    // one offer heading ("### ..."). Missing either = truncated / corrupt.
    private static void checkSnapshot(Path p, String section) {
        if (!Files.isRegularFile(p)) {
            fail(section + ": missing snapshot file " + p);
            return;
        }
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail(section + ": cannot read " + p + ": " + e.getMessage());
            return;
        }
        if (text.isBlank()) {
            fail(section + ": snapshot file " + p + " is EMPTY");
            return;
        }
        if (!text.contains("Last checked:")) {
            fail(section + ": " + p
                    + " has no 'Last checked:' line — looks truncated/corrupt");
        }
        if (!text.contains("###")) {
            fail(section + ": " + p
                    + " has no '###' offer heading — looks truncated/corrupt");
        }
        if (FAILURES.isEmpty()) {
            System.out.println("OK  " + section + " snapshot non-corrupt ("
                    + text.lines().count() + " lines)");
        }
    }

    // The outcome log must exist with at least one parseable line for this
    // job — the run's own proof it recorded what happened.
    private static void checkOutcomeLog(Path ws) {
        Path log = ws.resolve("scheduler_state/job_outcomes/bank-bonus-watch.log");
        if (!Files.isRegularFile(log)) {
            fail("missing outcome log: " + log);
            return;
        }
        try {
            long lines = Files.lines(log, StandardCharsets.UTF_8)
                    .filter(l -> l.contains("outcome="))
                    .count();
            if (lines == 0) {
                fail("outcome log has no line containing 'outcome=': " + log);
                return;
            }
            System.out.println("OK  outcome log exists with " + lines
                    + " recorded run line(s)");
        } catch (IOException e) {
            fail("cannot read outcome log: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        Path ws = ws();
        if (ws == null) {
            printReport();
            return;
        }
        checkSnapshot(ws.resolve(
                "goals/fidelity-signup-bonus-watch/hidden_files/fidelity_bonus_snapshot.md"),
                "Fidelity");
        checkSnapshot(ws.resolve(
                "goals/capital-one-360-bonus-watch/hidden_files/capital_one_bonus_snapshot.md"),
                "Capital One");
        checkOutcomeLog(ws);
        printReport();
    }

    private static void printReport() {
        if (FAILURES.isEmpty()) {
            System.out.println("VERIFY PASS — bank-bonus-watch snapshot state "
                    + "is healthy");
            System.out.println("NOTE: post-hoc verification cannot prove the "
                    + "BASELINE RULE was honored (silence on an empty "
                    + "snapshot is only observable at run time) — the cron "
                    + "body's VERIFIER SELF-CHECK gate covers that half.");
        } else {
            System.err.println("VERIFY FAIL — " + FAILURES.size()
                    + " check(s) failed:");
            for (String f : FAILURES) {
                System.err.println("  FAIL: " + f);
            }
            System.exit(1);
        }
    }
}
