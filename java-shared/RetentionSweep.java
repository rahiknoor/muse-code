// RetentionSweep.java — Rahik's 2026-09-27 rule: lessons live forever, raw run
// evidence rolls off. Prunes transient run state:
//
//   1. scheduler_state/completed_runs.json   — run-key entries past their TTL
//   2. scheduler_state/job_outcomes/*.log    — outcome lines past their TTL
//   3. goals/*/hidden_files/run_log.md       — "## YYYY-MM-DD" sections past their TTL
//   4. goals/*/hidden_files/ dated transient files (run-log-*, check_*, drop_check_*)
//      past their TTL
//
// Ebbinghaus decay (2026-09-28, MemoryBank — Zhong et al., AAAI 2024): instead of a
// flat 60-day cutoff, each entry gets a strength from importance x reference, and
// fades when effective_age = age_days / strength exceeds RETENTION_DAYS (60):
//   - importance: entries mentioning failure/error score 2.0, else 1.0
//     (failure records are what the failure watch references)
//   - reference: the entry's job seen within 30d -> 3.0, within 60d -> 2.0, else 1.0
//     (entries of actively-running jobs are live context; dead jobs' fade)
// A 61-day-old success from a dead job fades (61/1 > 60); a 200-day-old failure
// from a daily job survives (200/6 = 33 < 60). Raw evidence still eventually rolls
// off — strength is bounded, so nothing is kept forever by this tool.
//
// NEVER touched: proposals/, *changelog*, *efficiency*, MEMORY.md, ~/memory/,
// atlas/, your_files/, notebooklm_reask_log.md, or any *.json backend other
// than completed_runs.json.
//
// Usage:
//   java RetentionSweep.java --dry-run    (report only)
//   java RetentionSweep.java --sweep       (prune for real; writes .bak first)
//   java RetentionSweep.java --self-test
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class RetentionSweep {
    static final int RETENTION_DAYS = 60;
    static final ZoneId ZONE = ZoneId.of("America/New_York");
    static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    static final Pattern LOG_LINE_DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\b");
    static final Pattern SECTION_DATE = Pattern.compile("^##\\s+(\\d{4}-\\d{2}-\\d{2})\\b");
    static final Pattern ENTRY_DATE = Pattern.compile("\"date\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"");
    /** Importance signal: failure/error mentions are the records worth keeping longer. */
    static final Pattern IMPORTANT =
            Pattern.compile("(?i)outcome=failed|\\bfailed\\b|\\berror\\b|exception");

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isEmpty()) throw new IllegalStateException("$HOME is not set");
        return h;
    }

    /** MemoryBank-flavored strength: importance x reference-recency. */
    static double strength(String text, LocalDate jobLastSeen, LocalDate today) {
        double importance = IMPORTANT.matcher(text).find() ? 2.0 : 1.0;
        double reference = 1.0;
        if (jobLastSeen != null) {
            long d = ChronoUnit.DAYS.between(jobLastSeen, today);
            if (d <= 30) reference = 3.0;
            else if (d <= 60) reference = 2.0;
        }
        return importance * reference;
    }

    /** True when the entry's effective age (age / strength) exceeds retention. */
    static boolean faded(LocalDate entryDate, double strength, LocalDate today) {
        if (entryDate == null) return false; // undated -> keep (safe default)
        long age = ChronoUnit.DAYS.between(entryDate, today);
        return age / strength > RETENTION_DAYS;
    }

    static LocalDate parseDate(String s) {
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--dry-run";
        Path ws = Paths.get(home(), "workspace");
        LocalDate today = LocalDate.now(ZONE);
        if (mode.equals("--self-test")) { selfTest(); return; }
        boolean dry = !mode.equals("--sweep");
        System.out.println("Retention base: " + RETENTION_DAYS + " days (Ebbinghaus: effective age"
            + " = age / strength)" + (dry ? " — DRY RUN" : " — SWEEPING"));

        long freed = 0;
        freed += pruneCompletedRuns(ws.resolve("scheduler_state/completed_runs.json"), today, dry);
        Path outcomes = ws.resolve("scheduler_state/job_outcomes");
        if (Files.isDirectory(outcomes))
            try (var s = Files.list(outcomes)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".log")).sorted().toList())
                    freed += pruneDatedLines(p, LOG_LINE_DATE, today, dry);
            }
        Path goals = ws.resolve("goals");
        if (Files.isDirectory(goals))
            try (var s = Files.list(goals)) {
                for (Path g : s.filter(Files::isDirectory).sorted().toList()) {
                    Path hf = g.resolve("hidden_files");
                    if (!Files.isDirectory(hf)) continue;
                    Path rl = hf.resolve("run_log.md");
                    if (Files.exists(rl)) freed += pruneSections(rl, today, dry);
                    freed += pruneDatedFiles(hf, today, dry);
                }
            }
        System.out.println((dry ? "Would free" : "Freed") + ": " + freed + " bytes");
    }

    // ---- completed_runs.json: drop entries whose effective age exceeds retention ----
    // Parsing and writing go through the canonical RunKeys writer: entries
    // keep their raw text (history is never reformatted), writes are atomic
    // with a .bak backup, and a post-write sanity check guards the dedupe file.
    // Job identity = run-key prefix before " | "; jobLastSeen = the job's newest
    // entry date (first pass). Entry values carry no outcome data, so importance
    // is 1.0 here — strength comes from the job's reference recency.
    static long pruneCompletedRuns(Path f, LocalDate today, boolean dry) throws IOException {
        if (!Files.exists(f)) { System.out.println("skip (missing): " + f); return 0; }
        List<RunKeys.Entry> entries = RunKeys.readEntries(f);
        Map<String, LocalDate> jobLast = new HashMap<>();
        Map<RunKeys.Entry, LocalDate> entryDate = new HashMap<>();
        for (RunKeys.Entry e : entries) {
            LocalDate d = entryDateOf(e);
            entryDate.put(e, d);
            String job = jobOf(e.key);
            if (d != null) jobLast.merge(job, d, (a, b) -> a.isAfter(b) ? a : b);
        }
        List<String> kept = new ArrayList<>();
        int dropped = 0;
        for (RunKeys.Entry e : entries) {
            double s = strength(e.raw, jobLast.get(jobOf(e.key)), today);
            if (faded(entryDate.get(e), s, today)) dropped++;
            else kept.add(e.raw);
        }
        long before = Files.size(f);
        if (!dry && dropped > 0) {
            RunKeys.writeEntries(f, kept);
            List<RunKeys.Entry> back = RunKeys.readEntries(f);
            if (back.size() != kept.size())
                throw new IOException("post-prune sanity failed for " + f
                    + ": wrote " + kept.size() + ", re-read " + back.size());
        }
        long after = dry ? before : Files.size(f);
        System.out.println("completed_runs.json: dropped " + dropped + " entries"
            + (dry ? " (dry-run)" : ""));
        return before - after;
    }

    static LocalDate entryDateOf(RunKeys.Entry e) {
        Matcher m = ENTRY_DATE.matcher(e.raw);
        if (m.find()) return parseDate(m.group(1));
        Matcher d = DATE.matcher(e.raw);
        return d.find() ? parseDate(d.group(1)) : null;
    }

    static String jobOf(String key) {
        int i = key.indexOf(" | ");
        return i < 0 ? key : key.substring(0, i);
    }

    // ---- line-based logs: drop lines whose effective age exceeds retention ----
    // Job identity = second " | "-separated field (the job name); jobLastSeen =
    // the newest line date in this file (first pass).
    static long pruneDatedLines(Path f, Pattern p, LocalDate today, boolean dry)
            throws IOException {
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        Map<String, LocalDate> jobLast = new HashMap<>();
        List<LocalDate> lineDate = new ArrayList<>();
        List<String> lineJob = new ArrayList<>();
        for (String l : lines) {
            Matcher m = p.matcher(l);
            LocalDate d = m.find() ? parseDate(m.group(1)) : null;
            lineDate.add(d);
            String[] parts = l.split(" \\| ", 3);
            String job = parts.length > 1 ? parts[1].trim() : "";
            lineJob.add(job);
            if (d != null && !job.isEmpty())
                jobLast.merge(job, d, (a, b) -> a.isAfter(b) ? a : b);
        }
        List<String> kept = new ArrayList<>();
        int dropped = 0;
        for (int i = 0; i < lines.size(); i++) {
            double s = strength(lines.get(i), jobLast.get(lineJob.get(i)), today);
            if (faded(lineDate.get(i), s, today)) dropped++;
            else kept.add(lines.get(i));
        }
        return finishFile(f, lines, kept, dropped, dry, "lines");
    }

    // ---- run_log.md: drop "## YYYY-MM-DD" sections whose effective age exceeds retention ----
    // The log itself is the job: jobLastSeen = the newest section date in the file.
    // Sections mentioning failures score importance 2.0.
    static long pruneSections(Path f, LocalDate today, boolean dry) throws IOException {
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        // Pass 1: the log itself is the job — newest section date = reference signal.
        LocalDate newest = null;
        for (String l : lines) {
            Matcher m = SECTION_DATE.matcher(l);
            if (m.find()) {
                LocalDate d = parseDate(m.group(1));
                if (d != null && (newest == null || d.isAfter(newest))) newest = d;
            }
        }
        // Pass 2: group into sections; each section fades on its full text
        // (so failure mentions anywhere in the section score importance 2.0).
        List<String> kept = new ArrayList<>();
        List<String> sec = new ArrayList<>();
        LocalDate secDate = null;
        int droppedSections = 0, droppedLines = 0;
        for (String l : lines) {
            Matcher m = SECTION_DATE.matcher(l);
            if (m.find()) {
                if (!sec.isEmpty()) {
                    double s = strength(String.join("\n", sec), newest, today);
                    if (faded(secDate, s, today)) { droppedSections++; droppedLines += sec.size(); }
                    else kept.addAll(sec);
                }
                sec = new ArrayList<>();
                secDate = parseDate(m.group(1));
            }
            sec.add(l);
        }
        if (!sec.isEmpty()) {
            double s = strength(String.join("\n", sec), newest, today);
            if (faded(secDate, s, today)) { droppedSections++; droppedLines += sec.size(); }
            else kept.addAll(sec);
        }
        long r = finishFile(f, lines, kept, droppedLines, dry,
            "sections(" + droppedSections + ")");
        if (droppedSections > 0)
            System.out.println("  " + f.getFileName() + ": dropped " + droppedSections
                + " sections" + (dry ? " (dry-run)" : ""));
        return r;
    }

    // ---- dated transient files in hidden_files: drop files past their TTL ----
    // Job identity = parent goal dir; jobLastSeen = newest transient date in the dir.
    static final Pattern[] TRANSIENT = {
        Pattern.compile("^run-log-\\d{4}-\\d{2}-\\d{2}.*\\.md$"),
        Pattern.compile("^check_\\d{4}-\\d{2}-\\d{2}.*\\.md$"),
        Pattern.compile("^drop_check_\\d{4}-\\d{2}-\\d{2}.*\\.md$"),
    };

    static long pruneDatedFiles(Path dir, LocalDate today, boolean dry) throws IOException {
        List<Path> transientFiles = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String name = p.getFileName().toString();
                if (Arrays.stream(TRANSIENT).anyMatch(rx -> rx.matcher(name).matches()))
                    transientFiles.add(p);
            }
        }
        LocalDate newest = null;
        Map<Path, LocalDate> fileDate = new HashMap<>();
        for (Path p : transientFiles) {
            Matcher m = DATE.matcher(p.getFileName().toString());
            LocalDate d = m.find() ? parseDate(m.group(1)) : null;
            fileDate.put(p, d);
            if (d != null && (newest == null || d.isAfter(newest))) newest = d;
        }
        long freed = 0;
        for (Path p : transientFiles) {
            double s = strength(p.getFileName().toString(), newest, today);
            if (faded(fileDate.get(p), s, today)) {
                long sz = Files.size(p);
                System.out.println("  delete " + p + (dry ? " (dry-run)" : ""));
                if (!dry) Files.delete(p);
                freed += sz;
            }
        }
        return freed;
    }

    static long finishFile(Path f, List<String> orig, List<String> kept, int dropped,
            boolean dry, String unit) throws IOException {
        long before = Files.size(f);
        if (!dry && dropped > 0) {
            Files.copy(f, f.resolveSibling(f.getFileName() + ".bak"),
                StandardCopyOption.REPLACE_EXISTING);
            Files.write(f, kept, StandardCharsets.UTF_8);
        }
        long after = dry ? before : Files.size(f);
        if (dropped > 0)
            System.out.println(f.getFileName() + ": dropped " + dropped + " " + unit
                + (dry ? " (dry-run)" : ""));
        return before - after;
    }

    // ---- self-test on synthetic files (shared SelfTest helpers) ----
    static void selfTest() throws Exception {
        Path t = Files.createTempDirectory("retention-test");
        LocalDate today = LocalDate.now(ZONE);
        LocalDate old = today.minusDays(61);    // past the flat cutoff
        LocalDate fresh = today.minusDays(5);
        LocalDate ancient = today.minusDays(200);

        // completed_runs.json: old entry from an ACTIVE job survives (61/3 < 60)
        Path cr = t.resolve("completed_runs.json");
        Files.writeString(cr, "{\n \"jobA | " + old + " | 08:46 ET\": {\"date\": \"" + old + "\"},\n"
            + " \"jobA | " + fresh + " | 08:46 ET\": {\"date\": \"" + fresh + "\"},\n"
            + " \"jobB | " + old + " | 08:46 ET\": {\"date\": \"" + old + "\"}\n}\n");
        pruneCompletedRuns(cr, today, false);
        String got = Files.readString(cr);
        SelfTest.check("active job keeps old entry", got.contains("\"jobA | " + old));
        SelfTest.check("dead job drops old entry", !got.contains("\"jobB | " + old));
        SelfTest.check("fresh entry kept", got.contains("\"jobA | " + fresh));

        // dated lines: ancient failure from active job survives (200/6 < 60);
        // old success from dead job fades (61/1 > 60)
        Path log = t.resolve("x.log");
        Files.writeString(log, ancient + " 08:46 ET | jobC | outcome=failed | boom\n"
            + fresh + " 08:46 ET | jobC | outcome=succeeded\n"
            + old + " 08:46 ET | jobD | outcome=succeeded\n"
            + "nodate line\n");
        pruneDatedLines(log, LOG_LINE_DATE, today, false);
        List<String> ll = Files.readAllLines(log);
        SelfTest.check("ancient failure kept (importance x reference)", ll.size() == 3
            && ll.get(0).contains("outcome=failed"));
        SelfTest.check("dead-job old success dropped", ll.stream().noneMatch(l -> l.contains("jobD")));
        SelfTest.check("undated line kept", ll.contains("nodate line"));

        // sections: ancient failure section from a live log survives
        Path rl = t.resolve("run_log.md");
        Files.writeString(rl, "# log\n\n## " + ancient + " run\noutcome=failed: disk full\n\n## "
            + fresh + " run\nnew stuff\n");
        pruneSections(rl, today, false);
        String rs = Files.readString(rl);
        SelfTest.check("ancient failure section kept", rs.contains("disk full"));
        SelfTest.check("fresh section kept", rs.contains("new stuff"));
        SelfTest.check("preamble kept", rs.contains("# log"));

        // sections: old section from a dead log fades
        Path rl2 = t.resolve("run_log2.md");
        Files.writeString(rl2, "# log\n\n## " + old + " run\nold stuff\n");
        pruneSections(rl2, today, false);
        String rs2 = Files.readString(rl2);
        SelfTest.check("dead-log old section dropped", !rs2.contains("old stuff")
            && rs2.contains("# log"));

        // dated files: old file alongside fresh sibling survives (61/3 < 60)
        Path d1 = t.resolve("run-log-" + old + ".md"), d2 = t.resolve("run-log-" + fresh + ".md");
        Path keep = t.resolve("changelog.md");
        Files.writeString(d1, "x"); Files.writeString(d2, "x"); Files.writeString(keep, "x");
        pruneDatedFiles(t, today, false);
        SelfTest.check("old file with fresh sibling kept", Files.exists(d1));
        SelfTest.check("fresh file kept", Files.exists(d2));
        SelfTest.check("non-transient file kept", Files.exists(keep));

        // dated files: old file with only old siblings fades
        Path t2 = Files.createTempDirectory("retention-test2");
        Path e1 = t2.resolve("check_" + old + ".md");
        Files.writeString(e1, "x");
        pruneDatedFiles(t2, today, false);
        SelfTest.check("lone old file dropped", !Files.exists(e1));

        // strength unit checks
        SelfTest.checkEq("failure importance doubles", 2.0,
            strength("outcome=failed", null, today));
        SelfTest.checkEq("plain importance", 1.0, strength("all good", null, today));
        SelfTest.checkEq("active job reference x3", 3.0, strength("x", today, today));
        SelfTest.checkEq("60d job reference x2", 2.0, strength("x", today.minusDays(60), today));
        SelfTest.checkEq("dead job reference x1", 1.0, strength("x", today.minusDays(61), today));
        SelfTest.check("faded: 61d at strength 1", faded(old, 1.0, today));
        SelfTest.check("not faded: 61d at strength 3", !faded(old, 3.0, today));
        SelfTest.check("not faded: undated", !faded(null, 1.0, today));

        SelfTest.finish();
    }
}
