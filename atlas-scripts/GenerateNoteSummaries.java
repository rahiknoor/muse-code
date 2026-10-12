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
 * GenerateNoteSummaries — writes a "## Summary" (+ "## Story" where derivable)
 * block into every non-stock Atlas note, generated from the note's own content.
 *
 * <p>Part of Rahik's vault-summaries project: he reads/listens via Obsidian's
 * native read-aloud on his phone. Stock notes are owned by
 * GenerateStockSummaries (frontmatter-driven); this tool covers everything
 * else with per-folder strategies — never hand-written, always derived, so
 * the block can never contradict the note.
 *
 * <p>Strategies:
 * <ul>
 *   <li>05 NotebookLM ticker briefs — frozen date + sources line + live link</li>
 *   <li>02 Watches — **What:** / **Destination:** lines; cadence in story</li>
 *   <li>04 Goals — **Goal:** / **Status:** lines</li>
 *   <li>07 Creators — first sentence; Standing/coverage blocks as story</li>
 *   <li>03 Chats — **Purpose:** line; headings as story</li>
 *   <li>generic — first 1-2 sentences; headings as story</li>
 * </ul>
 *
 * <p>Excluded: Passwords.md (never summarized), 10/11 scripture (lowest
 * priority, deferred), notes that already carry a summary-story block.
 *
 * <p>Conventions follow GenerateStockSummaries: $HOME homeDir, section
 * markers, Sanitize --batch-stdin child before write, idempotent,
 * LC_ALL=C.UTF-8.
 *
 * <p>Run:
 * <pre>
 *   LC_ALL=C.UTF-8 ~/workspace/java/jdk-21/bin/java \
 *     --class-path ~/workspace/java-shared/classes \
 *     ~/workspace/atlas-scripts/GenerateNoteSummaries.java [--dry-run] [--folders 02,04]
 *   java ~/workspace/atlas-scripts/GenerateNoteSummaries.java --self-test
 * </pre>
 */
public class GenerateNoteSummaries {
    static final String START = "<!-- summary-story:start -->";
    static final String END = "<!-- summary-story:end -->";
    static final Pattern SECTION = Pattern.compile(
            "<!-- summary-story:start -->.*?<!-- summary-story:end -->\\n?", Pattern.DOTALL);
    static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\n(.*?)^---", Pattern.MULTILINE | Pattern.DOTALL);
    static final Set<String> SKIP_FOLDERS = Set.of(
            "10 \uD83D\uDCD6 Quran", "11 \uD83D\uDD0E Peterson vs the Text", "06 \uD83D\uDC65 People");
    static final Set<String> SKIP_FILES = Set.of("Passwords.md", "Vault Changelog.md");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            System.exit(selfTest() ? 0 : 1);
        }
        boolean dry = false;
        Set<String> folders = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--dry-run")) dry = true;
            if (args[i].equals("--folders") && i + 1 < args.length)
                folders = Set.of(args[i + 1].split(","));
        }
        Path vault = homeDir().resolve("workspace/atlas");
        int written = 0, skipped = 0;
        try (SanitizerBatch san = new SanitizerBatch()) {
            List<Path> notes = collect(vault, folders);
            for (Path note : notes) {
                String rel = vault.relativize(note).toString();
                String folder = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
                String text = Files.readString(note, StandardCharsets.UTF_8);
                if (SECTION.matcher(text).find()) { skipped++; continue; }
                String[] ss = summarize(folder, note.getFileName().toString(), text);
                if (ss == null || ss[0] == null || ss[0].isEmpty()) { skipped++; continue; }
                String updated = insertAfterTitle(text, renderBlock(ss[0], ss[1]));
                if (updated.equals(text)) { skipped++; continue; }
                if (dry) {
                    System.out.println("### " + rel + "\n" + renderBlock(ss[0], ss[1]));
                } else {
                    Files.writeString(note, san.sanitize(updated), StandardCharsets.UTF_8);
                    written++;
                }
            }
        }
        System.out.println("GenerateNoteSummaries: wrote " + written + ", skipped " + skipped
                + (dry ? " (dry run)" : ""));
    }

    static List<Path> collect(Path vault, Set<String> folders) throws IOException {
        List<Path> out = new ArrayList<>();
        // root notes (skipped when a folder filter is set)
        if (folders == null) {
            try (Stream<Path> s = Files.list(vault)) {
                s.filter(p -> p.toString().endsWith(".md") && !SKIP_FILES.contains(p.getFileName().toString()))
                 .forEach(out::add);
            }
        }
        try (Stream<Path> s = Files.list(vault)) {
            for (Path d : s.filter(Files::isDirectory).sorted().toList()) {
                String name = d.getFileName().toString();
                if (name.startsWith(".") || SKIP_FOLDERS.contains(name)) continue;
                if (folders != null && folders.stream().noneMatch(name::startsWith)) continue;
                try (Stream<Path> w = Files.walk(d)) {
                    w.filter(p -> p.toString().endsWith(".md")).sorted().forEach(out::add);
                }
            }
        }
        return out;
    }

    // ---------- strategies ----------

    /** Returns {summary, story-or-null}, or null when nothing derivable. */
    static String[] summarize(String folder, String file, String text) {
        String body = stripFrontmatter(text);
        if (folder.startsWith("05") && file.endsWith("\u2014 NotebookLM Research.md"))
            return notebookBrief(file, text);
        if (folder.startsWith("02")) return watchNote(body);
        if (folder.startsWith("04")) return goalNote(body);
        if (folder.startsWith("07")) return creatorNote(body);
        if (folder.startsWith("03")) return chatNote(body);
        return genericNote(body);
    }

    static String[] notebookBrief(String file, String text) {
        String ticker = file.substring(0, file.indexOf(" \u2014 "));
        Matcher dm = Pattern.compile("FROZEN INPUT PACKAGE[^\\n]*?as of ([0-9-]+)").matcher(text);
        String date = dm.find() ? dm.group(1) : "an earlier date";
        Matcher sm = Pattern.compile("^\\*\\*Sources:\\*\\*\\s*(.+)$", Pattern.MULTILINE).matcher(text);
        String sources = sm.find() ? sm.group(1).strip() : "filing sources";
        if (sources.length() > 220) sources = sources.substring(0, 220).strip() + "\u2026";
        String summary = "Frozen research brief for " + ticker + " (" + date + ") \u2014 the input "
                + "package NotebookLM worked from. Sources: " + sources + " Live values: [[" + ticker + "]].";
        return new String[]{summary, headingsStory(text, "This brief walks through")};
    }

    static String[] watchNote(String body) {
        String what = fieldLine(body, "What");
        String dest = fieldLine(body, "Destination");
        String cadence = fieldLine(body, "Cadence");
        if (what == null) return genericNote(body);
        StringBuilder sb = new StringBuilder(ensurePeriod(what));
        if (dest != null) {
            Matcher qm = Pattern.compile("\"([^\"]+)\"").matcher(dest);
            if (qm.find()) {
                String d = qm.group(1);
                if (dest.toLowerCase().contains("side chat")) d += " side chat";
                sb.append(" Reports to the ").append(d).append(".");
            } else {
                sb.append(" Destination: ").append(ensurePeriod(dest).strip());
            }
        }
        String story = cadence != null ? "Runs " + Character.toLowerCase(cadence.charAt(0)) + cadence.substring(1) + " " : "";
        String hs = headingsStory(body, "This watch's notes cover");
        return new String[]{sb.toString(), (story + hs).strip()};
    }

    static String[] goalNote(String body) {
        String goal = fieldLine(body, "Goal");
        String status = fieldLine(body, "Status");
        if (goal == null) return genericNote(body);
        String summary = "Goal: " + ensurePeriod(goal).strip()
                + (status != null ? " Status: " + ensurePeriod(status).strip() : "");
        return new String[]{summary.strip(), headingsStory(body, "This goal's notes cover")};
    }

    static String[] creatorNote(String body) {
        String para = firstParagraph(body);
        if (para == null) return null;
        String summary = firstSentences(para, 1);
        StringBuilder story = new StringBuilder();
        String standing = boldBlock(body, "Standing");
        String cover = boldBlock(body, "What they cover");
        if (standing != null) story.append(standing).append("\n\n");
        if (cover != null) story.append(cover).append("\n");
        String s = story.toString().strip();
        return new String[]{summary, s.isEmpty() ? null : s};
    }

    static String[] chatNote(String body) {
        String purpose = fieldLine(body, "Purpose");
        if (purpose == null) return genericNote(body);
        return new String[]{ensurePeriod(purpose).strip(), headingsStory(body, "This chat's notes walk through")};
    }

    static String[] genericNote(String body) {
        String para = firstParagraph(body);
        if (para == null) return null;
        return new String[]{firstSentences(para, 2), headingsStory(body, "This note covers")};
    }

    // ---------- helpers ----------

    static Path homeDir() {
        String h = System.getenv("HOME");
        return Paths.get(h != null ? h : System.getProperty("user.home"));
    }

    static String stripFrontmatter(String text) {
        Matcher m = FRONTMATTER.matcher(text);
        return m.find() ? text.substring(m.end()).strip() : text;
    }

    /** **Label:** value on one line. */
    static String fieldLine(String body, String label) {
        Matcher m = Pattern.compile("^\\*\\*" + Pattern.quote(label) + ":\\*\\*\\s*(.+)$",
                Pattern.MULTILINE).matcher(body);
        return m.find() ? m.group(1).strip() : null;
    }

    /** Text of a **Label.** bold-led block through the next blank line. */
    static String boldBlock(String body, String label) {
        Matcher m = Pattern.compile("^\\*\\*" + Pattern.quote(label) + "\\.\\*\\*\\s*(.+?)(?=\\n\\n|\\z)",
                Pattern.MULTILINE | Pattern.DOTALL).matcher(body);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").strip() : null;
    }

    /** First paragraph: first non-heading, non-list, non-quote block. */
    static String firstParagraph(String body) {
        StringBuilder cur = new StringBuilder();
        for (String line : body.split("\n")) {
            String s = line.strip();
            if (s.isEmpty()) {
                if (cur.length() > 0) break;
                continue;
            }
            if (s.startsWith("#") || s.startsWith("- ") || s.startsWith("* ") || s.startsWith("|")
                    || s.startsWith(">") || s.startsWith("<!--") || s.startsWith("![")
                    || s.matches("\\d+\\. .*")) {
                if (cur.length() > 0) break;
                continue;
            }
            cur.append(s).append(" ");
        }
        String p = cur.toString().replaceAll("\\s+", " ").strip();
        return p.isEmpty() ? null : p;
    }

    static String firstSentences(String para, int n) {
        String[] parts = para.split("(?<=[.!?])\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, parts.length); i++) sb.append(parts[i]).append(" ");
        String s = sb.toString().strip();
        return s.length() > 400 ? s.substring(0, 400).strip() + "\u2026" : s;
    }

    static String ensurePeriod(String s) {
        s = s.strip();
        return s.endsWith(".") || s.endsWith("!") || s.endsWith("?") ? s : s + ".";
    }

    /** "This note covers A, B, and C." from ## headings; null when none. */
    static String headingsStory(String text, String lead) {
        List<String> hs = new ArrayList<>();
        Matcher m = Pattern.compile("^## (?!Summary|Story)(.+)$", Pattern.MULTILINE).matcher(text);
        while (m.find()) hs.add(m.group(1).strip());
        if (hs.isEmpty()) return null;
        if (hs.size() == 1) return lead + " " + hs.get(0) + ".";
        return lead + " " + String.join(", ", hs.subList(0, hs.size() - 1))
                + ", and " + hs.get(hs.size() - 1) + ".";
    }

    static String renderBlock(String summary, String story) {
        String b = START + "\n## Summary\n\n" + summary + "\n";
        if (story != null && !story.isEmpty()) b += "\n## Story\n\n" + story + "\n";
        return b + END + "\n";
    }

    /** Insert after the first "# " title line (past frontmatter). */
    static String insertAfterTitle(String text, String block) {
        Matcher fm = FRONTMATTER.matcher(text);
        int from = fm.find() ? fm.end() : 0;
        Matcher t = Pattern.compile("^# .+$", Pattern.MULTILINE).matcher(text);
        if (t.find(from)) {
            int eol = text.indexOf('\n', t.end());
            int at = eol < 0 ? text.length() : eol + 1;
            return text.substring(0, at) + "\n" + block + text.substring(at);
        }
        return block + "\n" + text;
    }

    // ---------- sanitizer (same protocol as the stock generators) ----------

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

    static boolean selfTest() {
        List<String> log = new ArrayList<>();
        boolean ok = true;

        // watch strategy
        String watch = "# Drop Alerts\n\n**What:** Hourly check for 6% drops.\n"
                + "**Cadence:** Hourly during market hours.\n**Destination:** \"Drop Alerts\" side chat (`abc`).\n\n## Key rules\n- x\n";
        String[] w = summarize("02 \uD83D\uDC40 Watches", "Drop Alerts.md", watch);
        ok &= check(log, "watch summary", w[0].equals(
                "Hourly check for 6% drops. Reports to the Drop Alerts side chat."));
        ok &= check(log, "watch story has cadence+headings",
                w[1].contains("Runs hourly during market hours.") && w[1].contains("Key rules"));

        // goal strategy
        String goal = "# Debt Payoff\n\n**Goal:** Pay down the card.\n**Status:** In progress.\n";
        String[] g = summarize("04 \uD83C\uDFAF Goals", "Debt Payoff.md", goal);
        ok &= check(log, "goal summary",
                g[0].equals("Goal: Pay down the card. Status: In progress."));

        // creator strategy
        String creator = "# Parkev\n\nCFA and YouTube creator. Rahik's top source for values.\n\n"
                + "**Standing.** Followed \u2014 tier 1.\n\n**What they cover.** DCF-style analysis.\n";
        String[] c = summarize("07 \uD83D\uDE99 Creators", "Parkev.md", creator);
        ok &= check(log, "creator summary = first sentence",
                c[0].equals("CFA and YouTube creator."));
        ok &= check(log, "creator story has standing+cover",
                c[1].contains("Followed") && c[1].contains("DCF-style analysis"));

        // chat strategy
        String chat = "# Stock Research\n\n**Purpose:** Home for ongoing research.\n\n## How research runs\n- x\n";
        String[] ch = summarize("03 \uD83D\uDCAC Chats", "Stock Research.md", chat);
        ok &= check(log, "chat summary = purpose",
                ch[0].equals("Home for ongoing research."));
        ok &= check(log, "chat story lists headings",
                ch[1].contains("How research runs"));

        // notebook brief strategy
        String nb = "---\ntitle: AMD \u2014 NotebookLM Research\nticker: AMD\n---\n\n# AMD \u2014 NotebookLM Research\n\n"
                + "> [!note] FROZEN INPUT PACKAGE \u2014 brief as of 2026-09-26. Old figures.\n\n"
                + "## Research brief\n\n**Sources:** FY2025 10-K filed 2026-02-04; Q2 10-Q.\n";
        String[] n = summarize("05 \uD83D\uDCD3 NotebookLM", "AMD \u2014 NotebookLM Research.md", nb);
        ok &= check(log, "notebook summary",
                n[0].contains("Frozen research brief for AMD (2026-09-26)")
                        && n[0].contains("FY2025 10-K") && n[0].contains("[[AMD]]"));

        // generic fallback
        String gen = "# Random\n\nThis is a plain note. It has two sentences here.\n\n## Alpha\n## Beta\n";
        String[] gn = summarize("08 \uD83D\uDD2C Research", "Random.md", gen);
        ok &= check(log, "generic summary", gn[0].equals("This is a plain note. It has two sentences here."));
        ok &= check(log, "generic story", gn[1].equals("This note covers Alpha, and Beta."));

        // insert + idempotency
        String once = insertAfterTitle(watch, renderBlock(w[0], w[1]));
        ok &= check(log, "insert after title",
                once.indexOf(START) > once.indexOf("# Drop Alerts")
                        && once.indexOf(START) < once.indexOf("## Key rules"));
        String[] w2 = summarize("02 \uD83D\uDC40 Watches", "Drop Alerts.md", once);
        String twice = once.replaceFirst("(?s)<!-- summary-story:start -->.*?<!-- summary-story:end -->\\n?",
                Matcher.quoteReplacement(renderBlock(w2[0], w2[1])));
        ok &= check(log, "idempotent", twice.equals(once));

        System.out.println(String.join("\n", log));
        System.out.println(ok ? "SELF-TEST PASS" : "SELF-TEST FAIL");
        return ok;
    }

    static boolean check(List<String> log, String name, boolean cond) {
        log.add((cond ? "ok   " : "FAIL ") + name);
        return cond;
    }
}
