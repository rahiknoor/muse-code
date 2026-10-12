import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Idea 1 (approved 2026-09-29): every forward-tilt/nudge adjustment filed by
 * the nightly NotebookLM DCF re-ask cron must cite a TRACEABLE DRIVER — the
 * specific deep-research finding behind the tilt. A tilt with no traceable
 * driver is FLAGGED and NEVER FILED. Magnitude is never the trigger: a huge
 * tilt with a valid driver passes; a tiny tilt with no driver fails.
 *
 * <p>Read-only by design: this tool only inspects the rationale text (and
 * checks named files exist). It never writes anything.
 *
 * <p>Usage:
 * <pre>
 *   java --class-path ~/workspace/java-shared/classes TiltJustification.java \
 *       --check --ticker NVDA --rationale "Forward tilt ... driver: ..."
 *   java --class-path ~/workspace/java-shared/classes TiltJustification.java \
 *       --check --ticker NVDA --rationale-file /tmp/nvda_rationale.txt
 *   java --class-path ~/workspace/java-shared/classes TiltJustification.java --self-test
 * </pre>
 *
 * <p>Exit 0 on PASS (VERIFY OK), 1 on FAIL (VERIFY FAILED), 2 on usage error.
 */
public class TiltJustification {

    private static final String NO_SIGNAL =
            "no credible forward signal";

    private static final Pattern DRIVER_MARKER =
            Pattern.compile("driver\\s*:", Pattern.CASE_INSENSITIVE);

    // ~/... paths may contain spaces (e.g. "~/workspace/atlas/01 📈 Stocks/AMZN.md"),
    // so the token runs until a hard delimiter: quote, comma, semicolon, closing
    // paren/bracket, or newline. resolveExisting() then trims prose tail via
    // space back-off ("/note (re-read 2026-09-29" -> "/note").
    private static final Pattern TILDE_PATH =
            Pattern.compile("~\\/[^\\n'\",;)\\]]+");

    private static final Pattern NOTEBOOKLM =
            Pattern.compile("notebooklm", Pattern.CASE_INSENSITIVE);

    private static final Pattern NOTEBOOKLM_URL =
            Pattern.compile("notebook\\.google\\.com", Pattern.CASE_INSENSITIVE);

    private static final Pattern QUOTED =
            Pattern.compile("['\"][^'\"\\n]{4,}['\"]");

    private static final Pattern SEC_FORM =
            Pattern.compile("\\b(10-?K|10-?Q|8-?K)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern FILING_DATE =
            Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");

    private static final Pattern FILING_DATE_WORDY =
            Pattern.compile("filed\\s+(on\\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4}",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern FILING_SECTION =
            Pattern.compile("\\b(MD&A|guidance|item\\s*[78]|risk\\s+factors|outlook)\\b",
                    Pattern.CASE_INSENSITIVE);

    // Relative research_notes/... or atlas/... tokens (workspace-relative).
    private static final Pattern RELATIVE_PATH =
            Pattern.compile("(?:^|[\\s(\"\'])([\\w.\\/\\-]*(?:research_notes\\/|atlas\\/)[^\\n'\",;)\\]]+)");

    /** Home directory: $HOME only — NEVER Java's user.home (returns /root). */
    private static String homeDir() {
        String home = System.getenv("HOME");
        return (home == null || home.isEmpty()) ? null : home;
    }

    private static String expandHome(String p) {
        String home = homeDir();
        if (home == null) {
            return p;
        }
        p = p.trim();
        if (p.startsWith("~/")) {
            return home + p.substring(1);
        }
        if (p.startsWith("$HOME/")) {
            return home + p.substring(5);
        }
        // workspace-relative research_notes/... or atlas/... token
        return home + "/workspace/" + p;
    }

    private static String stripTrailing(String token) {
        return token.trim().replaceAll("[.,;:!?'\")\\]]+$", "");
    }

    /**
     * Resolves a raw path token to an existing file. Because ~/ paths may
     * contain spaces and the regex token is space-tolerant, trailing prose
     * (e.g. "(re-read 2026-09-29") is trimmed by cutting at the last space
     * and retrying. Returns null when nothing matches.
     */
    private static Path resolveExisting(String raw) {
        String token = stripTrailing(raw);
        while (!token.isEmpty()) {
            Path p = Paths.get(expandHome(token)).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
            int cut = token.lastIndexOf(' ');
            if (cut < 0) {
                return null;
            }
            token = stripTrailing(token.substring(0, cut));
        }
        return null;
    }

    static final class Result {
        boolean pass;
        final List<String> info = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
    }

    /** Pure validation — no I/O beyond existence checks on named paths. */
    public static Result validate(String ticker, String rationale) {
        Result r = new Result();
        r.info.add("ticker=" + ticker);
        if (rationale == null || rationale.trim().isEmpty()) {
            r.failures.add("rationale is empty — cannot justify any forward tilt");
            r.pass = false;
            return r;
        }

        // Rule 0: no tilt applied -> nothing to justify.
        if (rationale.toLowerCase().contains(NO_SIGNAL)) {
            r.info.add("no tilt applied (states 'no credible forward signal') — nothing to justify");
            r.pass = true;
            return r;
        }

        // Rule 1: tilt was applied -> driver: marker is mandatory.
        Matcher marker = DRIVER_MARKER.matcher(rationale);
        if (!marker.find()) {
            r.failures.add("tilt applied but rationale has no 'driver:' marker naming a traceable source");
        }

        // Rule 2: every ~/... path mentioned anywhere must exist on disk.
        Matcher tilde = TILDE_PATH.matcher(rationale);
        while (tilde.find()) {
            String token = stripTrailing(tilde.group());
            Path hit = resolveExisting(token);
            if (hit == null) {
                r.failures.add("path mentioned in rationale does not exist: " + token);
            }
        }

        // Rule 3: the driver: marker must name a traceable source (driver segment = after marker).
        if (marker.reset().find()) {
            String segment = rationale.substring(marker.end());
            List<String> forms = acceptedForms(segment);
            if (forms.isEmpty()) {
                r.failures.add("driver: marker present but names no traceable source — "
                        + "need (a) existing file under ~/workspace/research_notes/ or ~/workspace/atlas/, "
                        + "or (b) NotebookLM note reference (quoted title or notebook.google.com URL), "
                        + "or (c) SEC filing reference (10-K/10-Q/8-K + filing date or section)");
            } else {
                r.info.add("traceable driver: " + String.join("; ", forms));
            }
        }

        r.pass = r.failures.isEmpty();
        return r;
    }

    /** Returns which accepted driver forms the segment satisfies (empty = none). */
    private static List<String> acceptedForms(String segment) {
        List<String> forms = new ArrayList<>();

        // (a) existing file path under research_notes/ or atlas/
        String home = homeDir();
        if (home != null) {
            Path research = Paths.get(home, "workspace", "research_notes");
            Path atlas = Paths.get(home, "workspace", "atlas");
            List<String> candidates = new ArrayList<>();
            Matcher mt = TILDE_PATH.matcher(segment);
            while (mt.find()) {
                candidates.add(mt.group());
            }
            Matcher mr = RELATIVE_PATH.matcher(segment);
            while (mr.find()) {
                candidates.add(mr.group(1));
            }
            for (String raw : candidates) {
                Path hit = resolveExisting(raw);
                if (hit != null) {
                    Path norm = hit.toAbsolutePath().normalize();
                    if (norm.startsWith(research) || norm.startsWith(atlas)) {
                        forms.add("research/atlas file: " + stripTrailing(raw));
                        break;
                    }
                }
            }
        }

        // (b) NotebookLM note reference: "notebooklm" + quoted note title or notebook.google.com URL
        if (NOTEBOOKLM.matcher(segment).find()
                && (QUOTED.matcher(segment).find() || NOTEBOOKLM_URL.matcher(segment).find())) {
            forms.add("NotebookLM note reference");
        }

        // (c) filing reference: SEC form + filing date or section
        if (SEC_FORM.matcher(segment).find()
                && (FILING_DATE.matcher(segment).find()
                        || FILING_DATE_WORDY.matcher(segment).find()
                        || FILING_SECTION.matcher(segment).find())) {
            forms.add("SEC filing reference");
        }

        return forms;
    }

    private static void usage() {
        System.err.println("Usage: TiltJustification.java --check --ticker T (--rationale \"<text>\" | --rationale-file <path>)");
        System.err.println("       TiltJustification.java --self-test");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }

        String ticker = null;
        String rationale = null;
        String rationaleFile = null;
        boolean check = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--check" -> check = true;
                case "--ticker" -> ticker = args[++i];
                case "--rationale" -> rationale = args[++i];
                case "--rationale-file" -> rationaleFile = args[++i];
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }
        if (!check || ticker == null || (rationale == null && rationaleFile == null)) {
            usage();
            System.exit(2);
        }
        if (rationaleFile != null) {
            rationale = Files.readString(Paths.get(rationaleFile), StandardCharsets.UTF_8);
        }

        Result r = validate(ticker, rationale);
        Verify.report(
                "VERIFY OK: " + ticker + " forward-tilt rationale has a traceable driver",
                "VERIFY FAILED: " + ticker + " forward-tilt rationale lacks a traceable driver",
                r.info, r.failures);
    }

    // ---------------- self-test ----------------

    private static boolean isPass(Result r) {
        return r.pass && r.failures.isEmpty();
    }

    private static void runSelfTest() {
        String realFile = "~/workspace/research_notes/qxo_comms_sweep_2026-09-28.md";
        String ghostFile = "~/workspace/research_notes/does-not-exist-xyz-123.md";

        // 1. No tilt applied -> PASS regardless of drivers.
        SelfTest.check("no-credible-signal passes",
                isPass(validate("NVDA", "Forward tilt 2026-09-29: no credible forward signal — returned the grounded number.")));

        // 2. Case-insensitive no-signal.
        SelfTest.check("NO CREDIBLE FORWARD SIGNAL (caps) passes",
                isPass(validate("NVDA", "NO CREDIBLE FORWARD SIGNAL found; grounded value filed.")));

        // 3. Tilt + driver: naming an existing research_notes file -> PASS.
        SelfTest.check("tilt + existing research file passes",
                isPass(validate("QXO", "Forward tilt: growth Y1 25->30 - data-center end-market entry. "
                        + "driver: deep-research brief " + realFile + " (CEO TopBuild-close press release).")));

        // 4. Tilt + driver: naming an existing atlas note (path with spaces) -> PASS.
        SelfTest.check("tilt + atlas file path (spaces) passes",
                isPass(validate("AMZN", "Forward tilt: Y1 14->16 - AWS reacceleration. "
                        + "driver: Atlas note ~/workspace/atlas/01 📈 Stocks/AMZN.md (re-read 2026-09-29).")));

        // 4b. Tilt + driver: NotebookLM quoted note title (no path) -> PASS.
        SelfTest.check("tilt + notebooklm quoted title passes",
                isPass(validate("NVDA", "Forward tilt: Y1 30->35 - Blackwell scaling. "
                        + "driver: NotebookLM note \"[NVDA] DCF forward tilt\" (re-read 2026-09-29).")));
        // 5. Tilt + driver: notebook.google.com URL form -> PASS.
        SelfTest.check("tilt + notebooklm URL passes",
                isPass(validate("META", "Forward tilt: Y1 18->20 - ad momentum. "
                        + "driver: notebooklm forward-tilt note https://notebook.google.com/note/abc123 (2026-09-29).")));

        // 6. Tilt + driver: SEC filing reference (form + filed date) -> PASS.
        SelfTest.check("tilt + 10-K filing date passes",
                isPass(validate("META", "Forward tilt: Y1 18->20 - guided capex. "
                        + "driver: 10-K filed 2026-02-27, MD&A guidance on 2026 capex toward AI engines.")));

        // 7. Tilt + driver: marker case-insensitive -> PASS.
        SelfTest.check("Driver: (capitalized) passes",
                isPass(validate("NFLX", "Forward tilt: Y1 12->15 - ad tier scaling. "
                        + "Driver: NotebookLM note '[NFLX] DCF forward tilt'.")));

        // 8. Huge tilt with valid driver -> PASS (magnitude never the trigger).
        SelfTest.check("huge tilt (+90% value move) with driver passes",
                isPass(validate("XYZ", "Forward tilt: grounded $10 -> tilted $19.00, growth Y1-Y5 doubled. "
                        + "driver: 8-K filed 2026-09-28 announcing transformative acquisition, guidance section.")));

        // 9. Tiny tilt, no driver: marker -> FAIL.
        Result tiny = validate("ABC", "Forward tilt: grounded $100 -> tilted $100.50, Y1 5.0->5.1 - mild momentum.");
        SelfTest.check("tiny tilt without driver fails", !isPass(tiny));
        SelfTest.checkOutput("tiny-tilt failure names the marker",
                String.join("\n", tiny.failures),
                new String[]{"driver:"}, new String[]{});

        // 10. Tilt with driver: but no traceable form -> FAIL.
        Result vague = validate("ABC", "Forward tilt: Y1 10->12 - strong vibes. driver: management is optimistic.");
        SelfTest.check("driver: with no traceable form fails", !isPass(vague));

        // 11. ~ path mentioned that does not exist -> FAIL even with a valid driver.
        Result ghost = validate("ABC", "Forward tilt: Y1 10->12 - momentum. "
                + "driver: brief " + ghostFile + ". Also NotebookLM note '[ABC] DCF forward tilt'.");
        SelfTest.check("nonexistent ~ path fails despite valid NotebookLM driver", !isPass(ghost));
        SelfTest.checkOutput("ghost-path failure names the path",
                String.join("\n", ghost.failures),
                new String[]{"does-not-exist-xyz-123"}, new String[]{});

        // 12. Existing ~ path + valid driver -> PASS.
        SelfTest.check("existing ~ path passes",
                isPass(validate("QXO", "Forward tilt: Y1 25->30 - data-center mix. "
                        + "driver: see " + realFile + ".")));

        // 13. Empty rationale -> FAIL.
        SelfTest.check("empty rationale fails", !isPass(validate("ABC", "   ")));

        // 14. Missing driver marker but NotebookLM quoted note elsewhere -> still FAIL (marker required).
        Result noMarker = validate("NVDA", "Forward tilt: Y1 30->35 - Blackwell scaling. "
                + "Source: NotebookLM note '[NVDA] DCF forward tilt', 10-K filed 2026-02-25.");
        SelfTest.check("traceable source without driver: marker still fails", !isPass(noMarker));

        // 15. ~ path rule applies outside the driver segment too.
        Result stray = validate("ABC", "Forward tilt: Y1 10->12. driver: NotebookLM note '[ABC] DCF forward tilt'. "
                + "Full log at " + ghostFile);
        SelfTest.check("stray nonexistent ~ path fails", !isPass(stray));

        // 16. Real-world shape: current (pre-driver) NVDA proposal rationale -> FAIL (expected; it predates the marker rule).
        String nvdaReal = "Forward tilt 2026-09-29: executor cross-check passed (chat answer == saved note == 116.08; "
                + "provenance complete; equity/shares arithmetic verified); tilt: growth Y1 30.0->35.0 - Blackwell Ultra/GB300 "
                + "production-shipment scaling; grounded baseline $106.89 -> tilted $116.08. "
                + "Run log: goals/quarterly-sec-filing-watch-with-dcf-updates/hidden_files/notebooklm_reask_log.md.";
        SelfTest.check("pre-driver-era NVDA rationale fails (marker absent)", !isPass(validate("NVDA", nvdaReal)));

        SelfTest.finish();
    }
}
