import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Regenerate ~/workspace/atlas/Vault Changelog.md from the vault's git history.
 *
 * <p>Plain-language, newest-first list of what changed in the vault and when,
 * so Rahik can see the update history on his phone or desktop. Skips the
 * generator's own "Changelog refresh" commits to reduce noise.
 *
 * <p>Usage: VaultChangelog.java (no args). Best-effort: prints an error and
 * exits non-zero on failure; callers should never let this break a commit.
 */
public class VaultChangelog {
    static final int MAX_ENTRIES = 80;

    public static void main(String[] args) throws Exception {
        Path vault = Paths.get(System.getenv("HOME"), "workspace", "atlas");
        List<Entry> entries = readLog(vault);
        String md = render(entries);
        Path out = vault.resolve("Vault Changelog.md");
        Files.writeString(out, md, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote " + out + " (" + entries.size() + " entries)");
    }

    record Entry(String date, String time, String subject, String stat) {}

    static List<Entry> readLog(Path vault) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "-C", vault.toString(), "log",
                "--pretty=format:%H|%ad|%s", "--date=iso", "--shortstat", "-n",
                String.valueOf(MAX_ENTRIES + 20))
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("git log failed: " + out);

        List<Entry> entries = new ArrayList<>();
        String[] lines = out.split("\n");
        DateTimeFormatter inFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
        for (int i = 0; i < lines.length && entries.size() < MAX_ENTRIES; i++) {
            String line = lines[i];
            int p1 = line.indexOf('|'), p2 = line.indexOf('|', p1 + 1);
            if (p1 < 0 || p2 < 0) continue; // stat line or blank; handled below
            String subject = line.substring(p2 + 1).strip();
            if (subject.startsWith("Changelog refresh")) {
                continue;
            }
            String stat = "";
            if (i + 1 < lines.length && lines[i + 1].contains("file")) {
                stat = lines[i + 1].strip();
                i++;
            }
            ZonedDateTime zdt;
            try {
                zdt = ZonedDateTime.parse(line.substring(p1 + 1, p2).strip(), inFmt);
            } catch (Exception e) {
                continue;
            }
            ZonedDateTime local = zdt.withZoneSameInstant(ZoneId.of("America/New_York"));
            entries.add(new Entry(
                    local.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    local.format(DateTimeFormatter.ofPattern("HH:mm")),
                    subject, stat));
        }
        return entries;
    }

    static String render(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Vault Changelog — What's New\n\n");
        sb.append("Every update to the Atlas vault, newest first. ");
        sb.append("Generated automatically from the vault's change history.\n\n");
        String lastDate = "";
        for (Entry e : entries) {
            if (!e.date().equals(lastDate)) {
                sb.append("## ").append(e.date()).append("\n\n");
                lastDate = e.date();
            }
            sb.append("- **").append(e.time()).append("** — ").append(e.subject());
            if (!e.stat().isEmpty()) sb.append(" (").append(e.stat()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }
}
