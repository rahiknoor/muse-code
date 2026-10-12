import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * GenerateDashboards — builds single-read overview tables for watches and goals.
 *
 * <p>Rahik's speed brief: a dashboard note gives Muse everything in one read
 * instead of N note opens. Both dashboards are generated from the notes'
 * own metadata lines (single source of truth), so they can't rot beside them.
 *
 * <p>Outputs:
 * <ul>
 *   <li>02 Watches/Watches Dashboard.md — Watch | What | Cadence | Destination | Status</li>
 *   <li>04 Goals/Goals Dashboard.md — Goal | Status</li>
 * </ul>
 *
 * <p>Conventions follow the summary generators: $HOME homeDir, summary-story
 * markers (so GenerateNoteSummaries skips them), Sanitize --batch-stdin,
 * idempotent, LC_ALL=C.UTF-8.
 *
 * <p>Run:
 * <pre>
 *   LC_ALL=C.UTF-8 ~/workspace/java/jdk-21/bin/java \
 *     --class-path ~/workspace/java-shared/classes \
 *     ~/workspace/atlas-scripts/GenerateDashboards.java
 *   java ~/workspace/atlas-scripts/GenerateDashboards.java --self-test
 * </pre>
 */
public class GenerateDashboards {
    static final String START = "<!-- summary-story:start -->";
    static final String END = "<!-- summary-story:end -->";
    static final Pattern SECTION = Pattern.compile(
            "<!-- summary-story:start -->.*?<!-- summary-story:end -->\\n?", Pattern.DOTALL);
    static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\n(.*?)^---", Pattern.MULTILINE | Pattern.DOTALL);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            System.exit(selfTest() ? 0 : 1);
        }
        Path vault = homeDir().resolve("workspace/atlas");
        int written = 0;
        try (SanitizerBatch san = new SanitizerBatch()) {
            String watches = buildWatches(vault.resolve("02 \uD83D\uDC40 Watches"));
            Path wOut = vault.resolve("02 \uD83D\uDC40 Watches/Watches Dashboard.md");
            written += writeIfChanged(wOut, watches, san);
            String goals = buildGoals(vault.resolve("04 \uD83C\uDFAF Goals"));
            Path gOut = vault.resolve("04 \uD83C\uDFAF Goals/Goals Dashboard.md");
            written += writeIfChanged(gOut, goals, san);
        }
        System.out.println("GenerateDashboards: wrote " + written);
    }

    static final Pattern WHATSNEW = Pattern.compile(
            "\\A\\s*## What's new\\n(.*?)(?=<!-- summary-story:start -->|^## (?!What's new)|\\z)",
            Pattern.MULTILINE | Pattern.DOTALL);

    /** Standing rule (2026-10-08): dashboards keep their "## What's new" section
     *  across regens — the generator preserves it, never rebuilds it. */
    static String preserveWhatsNew(String existing) {
        Matcher m = WHATSNEW.matcher(existing);
        if (!m.find()) return "";
        return "## What's new\n\n" + m.group(1).strip() + "\n\n";
    }

    static String mergeWhatsNew(String existing, String content) {
        String wn = preserveWhatsNew(existing);
        if (!wn.isEmpty() && !content.startsWith(wn)) content = wn + content;
        return content;
    }

    static int writeIfChanged(Path out, String content, SanitizerBatch san) throws IOException {
        String existing = Files.exists(out) ? Files.readString(out, StandardCharsets.UTF_8) : "";
        content = mergeWhatsNew(existing, content);
        if (existing.equals(content)) return 0;
        Files.writeString(out, san.sanitize(content), StandardCharsets.UTF_8);
        return 1;
    }

    // ---------- watches ----------

    static String buildWatches(Path dir) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".md")).sorted().toList()) {
                String file = p.getFileName().toString();
                if (file.equals("Watches \u2014 Index.md") || file.equals("Watches Dashboard.md")) continue;
                String name = file.substring(0, file.length() - 3);
                String text = Files.readString(p, StandardCharsets.UTF_8);
                String what = fieldLine(text, "What");
                String cadence = fieldLine(text, "Cadence");
                String dest = fieldLine(text, "Destination");
                String destShort = "\u2014";
                if (dest != null) {
                    Matcher qm = Pattern.compile("\"([^\"]+)\"").matcher(dest);
                    destShort = qm.find() ? qm.group(1) : trim(dest, 40);
                }
                String status = text.toLowerCase().contains("paus") ? "Paused" : "Active";
                rows.add(new String[]{"[[" + name + "]]", trim(what != null ? what : "", 70),
                        trim(cadence != null ? cadence : "", 60), destShort, status});
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(block("Status board for every recurring monitor \u2014 one read instead of "
                + rows.size() + " note opens.",
                "Each row links to its watch note for cadence details, key rules, and sources."));
        sb.append("# Watches Dashboard\n\n");
        sb.append("| Watch | What | Cadence | Destination | Status |\n|---|---|---|---|---|\n");
        for (String[] r : rows) sb.append("| ").append(String.join(" | ", r)).append(" |\n");
        return sb.toString();
    }

    // ---------- goals ----------

    static String buildGoals(Path dir) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".md")).sorted().toList()) {
                String file = p.getFileName().toString();
                if (file.equals("Goals \u2014 Index.md") || file.equals("Goals Dashboard.md")) continue;
                String name = file.substring(0, file.length() - 3);
                String text = Files.readString(p, StandardCharsets.UTF_8);
                String goal = fieldLine(text, "Goal");
                String status = fieldLine(text, "Status");
                if (goal == null) goal = firstSentence(text);
                rows.add(new String[]{"[[" + name + "]]",
                        status != null ? trim(status, 80) : "\u2014"});
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(block("Status board for every goal \u2014 one read instead of "
                + rows.size() + " note opens.",
                "Each row links to its goal note for the full picture."));
        sb.append("# Goals Dashboard\n\n");
        sb.append("| Goal | Status |\n|---|---|\n");
        for (String[] r : rows) sb.append("| ").append(String.join(" | ", r)).append(" |\n");
        return sb.toString();
    }

    // ---------- helpers ----------

    static String block(String summary, String story) {
        return START + "\n## Summary\n\n" + summary + "\n\n## Story\n\n" + story + "\n" + END + "\n\n";
    }

    static Path homeDir() {
        String h = System.getenv("HOME");
        return Paths.get(h != null ? h : System.getProperty("user.home"));
    }

    static String fieldLine(String text, String label) {
        Matcher m = Pattern.compile("^\\*\\*" + Pattern.quote(label) + ":\\*\\*\\s*(.+)$",
                Pattern.MULTILINE).matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    static String firstSentence(String text) {
        Matcher fm = FRONTMATTER.matcher(text);
        String body = fm.find() ? text.substring(fm.end()) : text;
        for (String line : body.split("\n")) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("-") || s.startsWith(">")
                    || s.startsWith("|") || s.startsWith("!")) continue;
            String[] parts = s.split("(?<=[.!?])\\s+");
            return parts[0].strip();
        }
        return "";
    }

    static String trim(String s, int n) {
        s = s.replaceAll("\\s+", " ").strip().replace("|", "/");
        return s.length() > n ? s.substring(0, n).strip() + "\u2026" : s;
    }

    // ---------- sanitizer ----------

    static final class SanitizerBatch implements AutoCloseable {
        private final Process proc;
        private final DataOutputStream out;
        private final DataInputStream in;

        SanitizerBatch() throws IOException {
            String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            String sanitizeJava = homeDir().resolve("workspace/atlas-scripts/Sanitize.java").toString();
            String sharedClasses = homeDir().resolve("workspace/java-shared/classes").toString();
            proc = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                            "--class-path", sharedClasses, sanitizeJava, "--batch-stdin")
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            out = new DataOutputStream(proc.getOutputStream());
            in = new DataInputStream(proc.getInputStream());
        }

        String sanitize(String dirty) throws IOException {
            byte[] bytes = dirty.getBytes(StandardCharsets.UTF_8);
            out.writeBytes("SANITIZE");
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();
            byte[] magic = new byte[2];
            try { in.readFully(magic); }
            catch (EOFException e) { throw new IOException("sanitizer child exited", e); }
            if (magic[0] != 'O' || magic[1] != 'K') throw new IOException("sanitizer protocol error");
            int len = in.readInt();
            if (len < 0 || len > 50_000_000) throw new IOException("sanitizer bad length " + len);
            byte[] buf = new byte[len];
            in.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }

        @Override public void close() {
            try { out.close(); } catch (IOException ignored) { }
            proc.destroy();
        }
    }

    // ---------- self-test ----------

    static boolean selfTest() throws Exception {
        List<String> log = new ArrayList<>();
        boolean ok = true;
        Path tmp = Files.createTempDirectory("dash");
        Path watches = tmp.resolve("watches");
        Path goals = tmp.resolve("goals");
        Files.createDirectories(watches);
        Files.createDirectories(goals);
        Files.writeString(watches.resolve("Drop Alerts.md"),
                "# Drop Alerts\n\n**What:** Hourly check for drops.\n**Cadence:** Hourly.\n"
                + "**Destination:** \"Drop Alerts\" side chat.\n", StandardCharsets.UTF_8);
        Files.writeString(watches.resolve("Paused One.md"),
                "# Paused One\n\n**What:** Old watch.\n**Cadence:** Daily.\nPaused for now.\n",
                StandardCharsets.UTF_8);
        Files.writeString(goals.resolve("Debt Payoff.md"),
                "# Debt Payoff\n\n**Goal:** Pay the card.\n**Status:** In progress.\n",
                StandardCharsets.UTF_8);

        String w = buildWatches(watches);
        ok &= check(log, "watches table has rows",
                w.contains("| [[Drop Alerts]] | Hourly check for drops. | Hourly. | Drop Alerts | Active |")
                        && w.contains("| [[Paused One]] | Old watch. | Daily. | \u2014 | Paused |"));
        ok &= check(log, "watches has summary block", w.contains(START) && w.contains("## Summary"));
        String g = buildGoals(goals);
        ok &= check(log, "goals table has rows",
                g.contains("| [[Debt Payoff]] | In progress. |"));

        // idempotent: second build identical
        ok &= check(log, "idempotent", buildWatches(watches).equals(w) && buildGoals(goals).equals(g));

        // what's-new preserved across regens (standing rule 2026-10-08)
        String wnBlock = "## What's new\n\n- 2026-10-08 — Board created.\n\n";
        String wnExisting = wnBlock + w;
        ok &= check(log, "whatsnew extracted", preserveWhatsNew(wnExisting).equals(wnBlock));
        ok &= check(log, "whatsnew merged on rewrite",
                mergeWhatsNew(wnExisting, w).equals(wnBlock + w));
        ok &= check(log, "whatsnew not duplicated",
                mergeWhatsNew(wnExisting, wnBlock + w).equals(wnBlock + w));
        ok &= check(log, "no whatsnew -> content unchanged",
                mergeWhatsNew(w, w).equals(w));
        ok &= check(log, "whatsnew absent -> empty",
                preserveWhatsNew(w).isEmpty());

        System.out.println(String.join("\n", log));
        System.out.println(ok ? "SELF-TEST PASS" : "SELF-TEST FAIL");
        return ok;
    }

    static boolean check(List<String> log, String name, boolean cond) {
        log.add((cond ? "ok   " : "FAIL ") + name);
        return cond;
    }
}
