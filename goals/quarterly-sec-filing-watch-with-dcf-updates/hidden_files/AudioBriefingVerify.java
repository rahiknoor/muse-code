// AudioBriefingVerify.java — idea #44 health-check verifier for the
// portfolio-audio-briefing cron (the monthly NotebookLM audio briefing).
//
// WHY THIS EXISTS: the pipeline has four moving parts — generate, download,
// upload+deliver with an expiry, and a daily-log note. The 2026-09-26 test
// already showed one part can fail while the others succeed, so the verifier
// checks EVERY part and fails LOUD on the first gap instead of letting a
// half-finished briefing look complete.
//
// RUN IT with single-file launch (no compile step needed), AFTER STEP 5,
// passing the month the run was for (YYYY-MM):
//   ~/workspace/java/jdk-21/bin/java \
//       ~/workspace/goals/quarterly-sec-filing-watch-with-dcf-updates/hidden_files/AudioBriefingVerify.java 2026-10
// Exit 0 = all checks passed. Any other exit = a check FAILED (see output).
//
// CHECKS:
//   1. The month's audio file exists at
//      ~/workspace/audio_briefings/<YYYY-MM>-portfolio-briefing.mp3 and is
//      non-zero in size. KNOWN DRIFT (documented, not a failure): the
//      2026-09-26 end-to-end test saved a .m4a instead of .mp3, so a .m4a of
//      the same name is accepted — but it is PRINTED as a drift note so the
//      filename mismatch stays visible until someone fixes the body.
//   2. The daily memory log (~/memory/<YYYY-MM>-20.md, the scheduled run
//      day) contains the one-line briefing note the body requires.
//   3. The job's outcome log has a line for this month mentioning "expir"
//      — proof the delivery carried the link's expiry, which the body
//      requires (the expiry is the only delivery-content fact that leaves
//      a machine-checkable trace).
//
// NOTE on paths: never use System.getProperty("user.home") here — on this
// machine it returns /root while the workspace lives under $HOME=/home/hatch.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AudioBriefingVerify {

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

    private static void checkAudioFile(Path ws, String ym) {
        Path dir = ws.resolve("audio_briefings");
        Path mp3 = dir.resolve(ym + "-portfolio-briefing.mp3");
        Path m4a = dir.resolve(ym + "-portfolio-briefing.m4a");
        Path found = Files.isRegularFile(mp3) ? mp3
                : (Files.isRegularFile(m4a) ? m4a : null);
        if (found == null) {
            fail("no audio file for " + ym + " at " + mp3 + " (or " + m4a + ")");
            return;
        }
        try {
            long size = Files.size(found);
            if (size == 0) {
                fail("audio file " + found + " is zero bytes");
                return;
            }
            System.out.println("OK  audio file exists: " + found.getFileName()
                    + " (" + size / 1024 + " KB)");
            if (found.equals(m4a)) {
                System.out.println("NOTE: file is .m4a, not the .mp3 the body "
                        + "names — accepted (2026-09-26 drift), fix the body "
                        + "or the save step when convenient");
            }
        } catch (IOException e) {
            fail("cannot stat " + found + ": " + e.getMessage());
        }
    }

    private static void checkDailyLog(Path ws, String ym) {
        // The scheduled run lands on the 20th; the one-line note goes in
        // that day's memory log.
        Path log = ws.getParent() // ~ is the parent of ~/workspace
                .resolve("memory/" + ym + "-20.md");
        if (!Files.isRegularFile(log)) {
            fail("missing daily log " + log + " — cannot confirm the one-line "
                    + "briefing note was written");
            return;
        }
        try {
            boolean noted = Files.lines(log, StandardCharsets.UTF_8)
                    .anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("briefing"));
            if (!noted) {
                fail(log.getFileName() + " has no line mentioning 'briefing' — "
                        + "the daily-log note was not written");
                return;
            }
            System.out.println("OK  daily log " + log.getFileName()
                    + " carries the briefing note");
        } catch (IOException e) {
            fail("cannot read " + log + ": " + e.getMessage());
        }
    }

    private static void checkExpiryLogged(Path ws, String ym) {
        Path log = ws.resolve("scheduler_state/job_outcomes/portfolio-audio-briefing.log");
        if (!Files.isRegularFile(log)) {
            fail("missing outcome log: " + log);
            return;
        }
        try {
            boolean ok = Files.lines(log, StandardCharsets.UTF_8)
                    .anyMatch(l -> l.contains(ym)
                            && l.toLowerCase(Locale.ROOT).contains("expir"));
            if (!ok) {
                fail(log.getFileName() + " has no " + ym
                        + " line mentioning an expiry — the delivery's "
                        + "link-expiry proof is missing");
                return;
            }
            System.out.println("OK  outcome log records the delivery expiry for "
                    + ym);
        } catch (IOException e) {
            fail("cannot read outcome log: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length != 1 || !args[0].matches("\\d{4}-\\d{2}")) {
            System.err.println("usage: AudioBriefingVerify <YYYY-MM>");
            System.exit(2);
        }
        String ym = args[0];
        Path ws = ws();
        if (ws == null) {
            printReport();
            return;
        }
        checkAudioFile(ws, ym);
        checkDailyLog(ws, ym);
        checkExpiryLogged(ws, ym);
        printReport();
    }

    private static void printReport() {
        if (FAILURES.isEmpty()) {
            System.out.println("VERIFY PASS — portfolio-audio-briefing "
                    + "delivery state is healthy");
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
