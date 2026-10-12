import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * PRE-PUSH GATE, part 3 — LAST-GOOD ARCHIVE + AUTO-REVERT.
 *
 * <p>Rahik's rule (2026-09-28): no change goes live until it's been tested,
 * and a change that breaks in production reverts to the last good version
 * instead of being debugged live. This is the mechanism:
 *
 * <p>CONVENTION: every cron-body or shared-helper update runs
 * {@code archive} BEFORE the new version goes in:
 * <pre>
 *   java --class-path ~/workspace/java-shared/classes \
 *       ~/workspace/java-shared/LastGood.java archive &lt;file&gt; --job &lt;job-id&gt;
 * </pre>
 * {@code --job} tags the archive so the failure watcher can find it later.
 * Omit it for files with no owning job (still recoverable by hand).
 *
 * <p>On a 3-failure streak the {@code background-failure-watch} cron runs
 * {@code --revert-job <job-id>}: every file archived for that job within the
 * last 7 days is restored to its most recent archived copy — byte-identical,
 * hash-verified. A corrupt archive is REFUSED loudly, never restored.
 * Files already matching last-good are skipped (idempotent: re-running a
 * revert is a no-op, so the 2-hour watcher can't revert-spam).
 * Jobs with no recent archive keep the existing alert-only behavior.
 * A revert is never silent: the outcome always rides on the streak alert.
 *
 * <p>Store: {@code ~/workspace/java-shared/lastgood/}
 * ({@code manifest.tsv} + {@code archives/<slug>/<timestamp>-<shorthash>}).
 * The retention sweep does not prune this directory.
 *
 * <p>Exit codes: 0 ok (including "already at last-good" and
 * "no-recent-archive"); 2 usage error; 3 refused (no archive — nothing
 * touched); 4 corrupt archive/manifest — refused loudly, nothing touched.
 *
 * <p>Usage:
 * <pre>
 *   java --class-path ~/workspace/java-shared/classes \
 *       ~/workspace/java-shared/LastGood.java archive &lt;file&gt; [--job &lt;job-id&gt;]
 *   java --class-path ~/workspace/java-shared/classes \
 *       ~/workspace/java-shared/LastGood.java revert &lt;file&gt;
 *   java --class-path ~/workspace/java-shared/classes \
 *       ~/workspace/java-shared/LastGood.java --revert-job &lt;job-id&gt; [--within-days N]
 *   java --class-path ~/workspace/java-shared/classes \
 *       ~/workspace/java-shared/LastGood.java --self-test
 * </pre>
 */
public class LastGood {

    static final int DEFAULT_WITHIN_DAYS = 7;
    static final String UNTAGGED = "-";

    /** UTF-8 output: the default console charset may be ASCII when LANG is unset. */
    static final PrintStream OUT =
            new PrintStream(System.out, true, StandardCharsets.UTF_8);

    static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** One manifest row: when, for which job, what file, where the bytes are, their hash. */
    record Entry(String timestamp, String job, String file, String archiveRel, String sha256) {
    }

    /** Refusal is a first-class outcome: loud, typed, never a silent no-op. */
    static class Refusal extends Exception {
        final int exitCode;
        Refusal(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }
    }

    // Never System.getProperty("user.home") here: on this VM it returns /root,
    // while the workspace lives under $HOME (/home/hatch). See ~/TOOLS.md.
    static Path storeDir() {
        return Paths.get(System.getenv("HOME") + "/workspace/java-shared/lastgood");
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String slugFor(String absolutePath) throws Exception {
        String clean = absolutePath.replaceAll("[^A-Za-z0-9]", "_");
        if (clean.length() > 60) {
            clean = clean.substring(clean.length() - 60);
        }
        return clean + "_" + sha256(absolutePath.getBytes(StandardCharsets.UTF_8)).substring(0, 8);
    }

    /** Strict manifest load: any malformed line is a hard refusal, not a skip. */
    static List<Entry> loadManifest(Path store) throws Refusal {
        Path mf = store.resolve("manifest.tsv");
        List<Entry> entries = new ArrayList<>();
        if (!Files.isRegularFile(mf)) {
            return entries;
        }
        try {
            List<String> lines = Files.readAllLines(mf, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split("\t", -1);
                if (cols.length != 5 || cols[0].isBlank() || cols[2].isBlank()
                        || cols[3].isBlank() || cols[4].length() != 64) {
                    throw new Refusal(4, "manifest corrupt at line " + (i + 1)
                            + " — refusing to trust any entry");
                }
                entries.add(new Entry(cols[0], cols[1], cols[2], cols[3], cols[4]));
            }
        } catch (Refusal r) {
            throw r;
        } catch (Exception e) {
            throw new Refusal(4, "manifest unreadable: " + e.getMessage());
        }
        return entries;
    }

    static void appendManifest(Path store, Entry e) throws Exception {
        Path mf = store.resolve("manifest.tsv");
        Files.createDirectories(store);
        String line = String.join("\t",
                e.timestamp(), e.job(), e.file(), e.archiveRel(), e.sha256()) + "\n";
        Files.write(mf, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Archive <file> now. Returns the new entry. */
    static Entry doArchive(Path store, Path file, String job) throws Exception {
        Path abs = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            throw new Refusal(2, "archive refused: not a regular file: " + abs);
        }
        byte[] bytes = Files.readAllBytes(abs);
        String hash = sha256(bytes);
        Instant now = Instant.now();
        String fileTs = TS_FMT.format(now);
        String rel = "archives/" + slugFor(abs.toString()) + "/"
                + fileTs + "-" + hash.substring(0, 8);
        Path dest = store.resolve(rel);
        Files.createDirectories(dest.getParent());
        Files.copy(abs, dest, StandardCopyOption.REPLACE_EXISTING);
        Entry e = new Entry(now.toString(), job == null ? UNTAGGED : job,
                abs.toString(), rel, hash);
        appendManifest(store, e);
        return e;
    }

    /** Most recent entry for a file, or null. */
    static Entry latestFor(List<Entry> entries, String absoluteFile) {
        return entries.stream()
                .filter(e -> e.file().equals(absoluteFile))
                .max(Comparator.comparing(Entry::timestamp))
                .orElse(null);
    }

    /**
     * Restore the most recent archived copy of <file>, byte-identical.
     * Refuses loudly (nothing touched) when there is no archive or the
     * archived bytes fail the manifest hash check.
     */
    static String doRevert(Path store, Path file) throws Exception {
        Path abs = file.toAbsolutePath().normalize();
        Entry e = latestFor(loadManifest(store), abs.toString());
        if (e == null) {
            throw new Refusal(3, "revert refused: no archive for " + abs);
        }
        Path archived = store.resolve(e.archiveRel());
        if (!Files.isRegularFile(archived)) {
            throw new Refusal(4, "revert refused: archived copy missing: "
                    + e.archiveRel());
        }
        byte[] good = Files.readAllBytes(archived);
        if (!sha256(good).equals(e.sha256())) {
            throw new Refusal(4, "revert refused: archived copy is corrupt "
                    + "(hash mismatch) for " + abs + " — refusing to restore garbage");
        }
        if (Files.isRegularFile(abs) && sha256(Files.readAllBytes(abs)).equals(e.sha256())) {
            return "already at last-good — skipping: " + abs;
        }
        if (abs.getParent() != null) {
            Files.createDirectories(abs.getParent());
        }
        Files.write(abs, good,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return "reverted: " + abs + " to last-good (" + e.timestamp()
                + ", " + good.length + " bytes, sha256 verified)";
    }

    /**
     * Revert every file archived for <jobId> within the last <withinDays> days.
     * Files already at last-good are skipped; one corrupt archive refuses only
     * its own file, never the whole batch. Prints per-file lines.
     */
    static List<String> doRevertJob(Path store, String jobId, int withinDays) throws Exception {
        List<Entry> entries = loadManifest(store);
        Instant cutoff = Instant.now().minusSeconds((long) withinDays * 86400);
        List<String> files = entries.stream()
                .filter(e -> e.job().equals(jobId))
                .filter(e -> {
                    try {
                        return !Instant.parse(e.timestamp()).isBefore(cutoff);
                    } catch (Exception ex) {
                        return false;
                    }
                })
                .map(Entry::file)
                .distinct()
                .sorted()
                .toList();
        List<String> out = new ArrayList<>();
        if (files.isEmpty()) {
            out.add("no-recent-archive for job " + jobId
                    + " (nothing archived in the last " + withinDays + " days)");
            return out;
        }
        for (String f : files) {
            try {
                out.add(doRevert(store, Paths.get(f)));
            } catch (Refusal r) {
                out.add("REFUSED (" + r.exitCode + "): " + r.getMessage());
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path store = storeDir();
        boolean selfTest = false;
        String cmd = null;
        String fileArg = null;
        String jobArg = null;
        String revertJob = null;
        int withinDays = DEFAULT_WITHIN_DAYS;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--self-test" -> selfTest = true;
                case "archive", "revert" -> cmd = args[i];
                case "--job" -> jobArg = args[++i];
                case "--revert-job" -> revertJob = args[++i];
                case "--within-days" -> withinDays = Integer.parseInt(args[++i]);
                default -> {
                    if (cmd != null && fileArg == null && !args[i].startsWith("--")) {
                        fileArg = args[i];
                    } else {
                        System.err.println("LastGood: unknown arg: " + args[i]);
                        System.exit(2);
                    }
                }
            }
        }
        if (selfTest) {
            runSelfTest();
            return; // unreachable: SelfTest.finish() exits
        }
        try {
            if (revertJob != null) {
                for (String line : doRevertJob(store, revertJob, withinDays)) {
                    OUT.println(line);
                }
                return;
            }
            if ("archive".equals(cmd) && fileArg != null) {
                Entry e = doArchive(store, Paths.get(fileArg), jobArg);
                OUT.println("archived: " + e.file() + " -> " + e.archiveRel()
                        + " (" + e.timestamp() + ", job=" + e.job() + ")");
                return;
            }
            if ("revert".equals(cmd) && fileArg != null) {
                OUT.println(doRevert(store, Paths.get(fileArg)));
                return;
            }
            System.err.println("LastGood: usage: archive <file> [--job J] | revert <file> |"
                    + " --revert-job <job> [--within-days N] | --self-test");
            System.exit(2);
        } catch (Refusal r) {
            System.err.println("LastGood: " + r.getMessage());
            System.exit(r.exitCode);
        }
    }

    // ------------------------------------------------------------------
    // --self-test (shared SelfTest harness; synthetic store in temp dirs)
    // ------------------------------------------------------------------

    static Path writeFile(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    static void runSelfTest() throws Exception {
        // 1. archive stores a byte-identical copy and a manifest entry.
        Path s1 = Files.createTempDirectory("lg-t1-");
        Path f1 = writeFile(s1, "victim.txt", "version one\n");
        Entry e1 = doArchive(s1, f1, "job-a");
        Path archived1 = s1.resolve(e1.archiveRel());
        SelfTest.check("archive copy exists on disk", Files.isRegularFile(archived1));
        SelfTest.checkEq("archived bytes identical (sha256)",
                sha256(Files.readAllBytes(f1)), sha256(Files.readAllBytes(archived1)));
        SelfTest.checkEq("manifest records the job tag", "job-a",
                latestFor(loadManifest(s1), f1.toAbsolutePath().toString()).job());

        // 2. revert restores the MOST RECENT archive, byte-identical.
        Path s2 = Files.createTempDirectory("lg-t2-");
        Path f2 = writeFile(s2, "victim.txt", "v1\n");
        doArchive(s2, f2, "job-a");
        Files.write(f2, "v2\n".getBytes(StandardCharsets.UTF_8));
        Entry e2b = doArchive(s2, f2, "job-a");
        Files.write(f2, "v3-broken\n".getBytes(StandardCharsets.UTF_8));
        String r2 = doRevert(s2, f2);
        SelfTest.checkContains("revert reports what it did", r2, "reverted:");
        SelfTest.checkEq("revert restores most recent archive bytes",
                e2b.sha256(), sha256(Files.readAllBytes(f2)));
        SelfTest.checkEq("reverted content is v2", "v2\n",
                Files.readString(f2, StandardCharsets.UTF_8));

        // 3. revert with no archive refuses loudly and touches nothing.
        Path s3 = Files.createTempDirectory("lg-t3-");
        Path f3 = writeFile(s3, "victim.txt", "untouched\n");
        boolean refused3 = false;
        try {
            doRevert(s3, f3);
        } catch (Refusal r) {
            refused3 = r.exitCode == 3;
        }
        SelfTest.check("revert with no archive refuses (exit 3)", refused3);
        SelfTest.checkEq("refused revert leaves the file untouched", "untouched\n",
                Files.readString(f3, StandardCharsets.UTF_8));

        // 4. NEGATIVE CONTROL A: corrupted archived bytes -> revert refuses,
        //    target file untouched (never restores garbage).
        Path s4 = Files.createTempDirectory("lg-t4-");
        Path f4 = writeFile(s4, "victim.txt", "good version\n");
        Entry e4 = doArchive(s4, f4, "job-a");
        Path archived4 = s4.resolve(e4.archiveRel());
        Files.write(archived4, "GARBAGE!!!".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.write(f4, "new broken version\n".getBytes(StandardCharsets.UTF_8));
        boolean refused4 = false;
        try {
            doRevert(s4, f4);
        } catch (Refusal r) {
            refused4 = r.exitCode == 4;
        }
        SelfTest.check("corrupt archive -> revert refuses (exit 4)", refused4);
        SelfTest.checkEq("refused revert leaves broken file as-is (no garbage written)",
                "new broken version\n",
                Files.readString(f4, StandardCharsets.UTF_8));

        // 5. NEGATIVE CONTROL B: corrupted manifest -> operations refuse loudly.
        Path s5 = Files.createTempDirectory("lg-t5-");
        Path f5 = writeFile(s5, "victim.txt", "v1\n");
        doArchive(s5, f5, "job-a");
        Files.write(s5.resolve("manifest.tsv"),
                "THIS IS NOT A VALID MANIFEST LINE\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        boolean refused5 = false;
        try {
            doRevert(s5, f5);
        } catch (Refusal r) {
            refused5 = r.exitCode == 4;
        }
        SelfTest.check("corrupt manifest -> revert refuses (exit 4)", refused5);

        // 6. archive of a nonexistent file refuses; nothing written to store.
        Path s6 = Files.createTempDirectory("lg-t6-");
        boolean refused6 = false;
        try {
            doArchive(s6, s6.resolve("does-not-exist.txt"), "job-a");
        } catch (Refusal r) {
            refused6 = r.exitCode == 2;
        }
        SelfTest.check("archive of missing file refuses (exit 2)", refused6);
        SelfTest.check("refused archive writes no manifest",
                !Files.exists(s6.resolve("manifest.tsv")));

        // 7. --revert-job: only recent entries for that job revert.
        Path s7 = Files.createTempDirectory("lg-t7-");
        Path fa = writeFile(s7, "a.txt", "a-good\n");
        Path fb = writeFile(s7, "b.txt", "b-good\n");
        doArchive(s7, fa, "job-a");
        doArchive(s7, fb, "job-b");
        Files.write(fa, "a-broken\n".getBytes(StandardCharsets.UTF_8));
        Files.write(fb, "b-broken\n".getBytes(StandardCharsets.UTF_8));
        List<String> out7 = doRevertJob(s7, "job-a", 7);
        SelfTest.checkEq("revert-job touches exactly the job's files", 1, out7.size());
        SelfTest.checkContains("revert-job restores job-a's file", out7.get(0), "reverted:");
        SelfTest.checkEq("job-a file restored", "a-good\n",
                Files.readString(fa, StandardCharsets.UTF_8));
        SelfTest.checkEq("other job's file untouched", "b-broken\n",
                Files.readString(fb, StandardCharsets.UTF_8));

        // 8. --revert-job with no recent archive: honest no-op, exit path stays 0.
        List<String> out8 = doRevertJob(s7, "job-nobody", 7);
        SelfTest.checkEq("unknown job -> single no-recent-archive line", 1, out8.size());
        SelfTest.checkContains("no-recent-archive is explicit", out8.get(0), "no-recent-archive");

        // 9. Idempotency: second revert is a no-op skip, bytes unchanged.
        String r9 = doRevert(s7, fa);
        SelfTest.checkContains("second revert reports already-at-last-good", r9,
                "already at last-good");
        SelfTest.checkEq("bytes still good after no-op revert", "a-good\n",
                Files.readString(fa, StandardCharsets.UTF_8));

        // 10. revert restores a deleted file from archive.
        Path s10 = Files.createTempDirectory("lg-t10-");
        Path f10 = writeFile(s10, "victim.txt", "precious\n");
        doArchive(s10, f10, "job-a");
        Files.delete(f10);
        String r10 = doRevert(s10, f10);
        SelfTest.checkContains("deleted file is restored", r10, "reverted:");
        SelfTest.checkEq("restored bytes match", "precious\n",
                Files.readString(f10, StandardCharsets.UTF_8));

        // 11. Untagged archive: recoverable by direct revert, invisible to --revert-job.
        Path s11 = Files.createTempDirectory("lg-t11-");
        Path f11 = writeFile(s11, "victim.txt", "plain\n");
        Entry e11 = doArchive(s11, f11, null);
        SelfTest.checkEq("untagged entry records '-' job", UNTAGGED, e11.job());
        Files.write(f11, "broken\n".getBytes(StandardCharsets.UTF_8));
        doRevert(s11, f11);
        SelfTest.checkEq("direct revert works on untagged archive", "plain\n",
                Files.readString(f11, StandardCharsets.UTF_8));
        List<String> out11 = doRevertJob(s11, "job-a", 7);
        SelfTest.checkContains("untagged archive invisible to --revert-job",
                out11.get(0), "no-recent-archive");

        SelfTest.finish();
    }
}
