import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * VaultIndex — one-line-per-note lookup index for the Atlas vault.
 *
 * <p>Walks {@code ~/workspace/atlas} for {@code *.md} files and writes
 * {@code ~/workspace/atlas-index/vault-index.jsonl}: one JSON object per line
 * with {@code path} (relative to the vault root), {@code title}, {@code aliases},
 * and a one-line {@code summary}. The file is grep-friendly by design — it is
 * the first hop when looking for "which note holds X", replacing blind greps
 * across 400+ notes.
 *
 * <p>Field sources, in order of preference:
 * <ul>
 *   <li>title — frontmatter {@code title:}, else the first {@code # } heading, else the filename
 *   <li>aliases — frontmatter {@code aliases:} list
 *   <li>summary — frontmatter {@code summary:}, else the first text line under the
 *       {@code ## Summary} section (the plain-words summary every note carries),
 *       truncated to 280 chars
 * </ul>
 *
 * <p>ORDER IS THE OPTIMIZATION (2026-10-10, his "index for your speed" call):
 * notes are NOT alphabetical — entries he asks about most come FIRST, so a
 * scan of the file hits the likely answer in the first lines instead of
 * burning through all 444. Hot = the daily question surface: stock dossiers,
 * watches/alerts, creator notes, nav + task notes. Everything else follows
 * alphabetically. Each line carries {@code "hot":true/false} marking its tier.
 *
 * <p>Run: {@code LC_ALL=C.UTF-8 java ~/workspace/atlas-scripts/VaultIndex.java [--base-dir DIR]}
 * ({@code --base-dir} is a test-only override; defaults to $HOME, never the
 * JVM's user.home property — see the workspace TOOLS.md quirk).
 *
 * <p>Rebuilt on every vault refresh (see the refresh chain in
 * {@code ~/memory/topics/atlas.md}), after all note mutations are done.
 */
public class VaultIndex {

    /**
     * Hot prefixes — relative vault paths that sort FIRST in the index.
     * "01 "/"02 "/"07 " are the Stocks, Watches, and Creators dirs (ASCII-safe
     * prefixes; no emoji literals needed). The rest are top-level nav/task notes.
     * Curated 2026-10-10 from his actual question patterns; edit when his
     * question surface shifts.
     */
    static final List<String> HOT_PREFIXES = List.of(
            "01 ", "02 ", "07 ",
            "Dashboards.md",
            "Home.md",
            "Muse's Corner Office.md",
            "Task & Failure Ledger.md",
            "BLOCKED.md");

    static boolean isHot(String rel) {
        for (String h : HOT_PREFIXES) if (rel.startsWith(h)) return true;
        return false;
    }

    static Path homeDir() {
        String home = System.getenv("HOME");
        return Paths.get((home == null || home.isEmpty())
                ? System.getProperty("user.home") : home);
    }

    public static void main(String[] args) throws IOException {
        Path base = homeDir();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--base-dir")) base = Paths.get(args[i + 1]);
        }
        Path atlas = base.resolve("workspace/atlas");
        if (!Files.isDirectory(atlas)) {
            System.err.println("vault not found: " + atlas);
            System.exit(1);
        }
        Path outDir = base.resolve("workspace/atlas-index");
        Files.createDirectories(outDir);

        List<Path> notes;
        try (Stream<Path> s = Files.walk(atlas)) {
            notes = s.filter(p -> p.getFileName().toString().endsWith(".md"))
                     .filter(Files::isRegularFile)
                     .collect(Collectors.toList());
        }
        // Hot-first: the notes his questions reach for most come first,
        // alphabetical within each tier. A scan hits the likely answer early.
        notes.sort(Comparator
                .comparing((Path p) -> isHot(atlas.relativize(p).toString()) ? 0 : 1)
                .thenComparing(p -> atlas.relativize(p).toString()));
        List<String> lines = new ArrayList<>(notes.size());
        for (Path p : notes) lines.add(indexLine(atlas, p));
        Path out = outDir.resolve("vault-index.jsonl");
        Files.write(out, lines, StandardCharsets.UTF_8);
        System.out.println("indexed " + lines.size() + " notes -> " + out);
    }

    static String indexLine(Path atlas, Path p) throws IOException {
        List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
        Map<String, String> fm = parseFrontmatter(all);
        String rel = atlas.relativize(p).toString();

        String title = fm.getOrDefault("title", "").trim();
        if (title.isEmpty()) title = firstHeading(all);
        if (title.isEmpty()) {
            String f = p.getFileName().toString();
            title = f.endsWith(".md") ? f.substring(0, f.length() - 3) : f;
        }

        String summary = fm.getOrDefault("summary", "").trim();
        if (summary.isEmpty()) summary = summarySection(all);
        summary = truncate(summary, 280);

        return "{\"path\":" + q(rel)
                + ",\"title\":" + q(title)
                + ",\"aliases\":" + strList(parseAliases(fm.get("aliases")))
                + ",\"summary\":" + q(summary)
                + ",\"hot\":" + isHot(rel) + "}";
    }

    /** Lines between the leading {@code ---} fence, as key -> raw value. */
    static Map<String, String> parseFrontmatter(List<String> all) {
        Map<String, String> fm = new LinkedHashMap<>();
        if (all.isEmpty() || !all.get(0).trim().equals("---")) return fm;
        for (int i = 1; i < all.size(); i++) {
            String line = all.get(i);
            if (line.trim().equals("---")) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();
            if ((val.startsWith("\"") && val.endsWith("\"") && val.length() > 1)
                    || (val.startsWith("'") && val.endsWith("'") && val.length() > 1)) {
                val = val.substring(1, val.length() - 1);
            }
            fm.put(key, val);
        }
        return fm;
    }

    /** Parses {@code ["a", "b"]} / {@code [a, b]} style alias lists. */
    static List<String> parseAliases(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String t = raw.trim();
        if (!t.startsWith("[") || !t.endsWith("]")) {
            if (!t.isEmpty()) out.add(t);
            return out;
        }
        Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^,\"'\\[\\]]+)").matcher(t);
        while (m.find()) {
            String v = m.group(1) != null ? m.group(1)
                     : m.group(2) != null ? m.group(2) : m.group(3).trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    static String firstHeading(List<String> all) {
        for (String line : all) {
            if (line.startsWith("# ")) return line.substring(2).trim();
        }
        return "";
    }

    /** First real text line under the {@code ## Summary} section. */
    static String summarySection(List<String> all) {
        boolean inSummary = false;
        for (String line : all) {
            String t = line.trim();
            if (!inSummary) {
                if (t.equals("## Summary")) inSummary = true;
                continue;
            }
            if (t.startsWith("## ") || t.startsWith("# ")) break; // next section
            if (t.isEmpty() || t.startsWith("<!--")) continue;
            return t;
        }
        return "";
    }

    static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1).trim() + "…";
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append("\"").toString();
    }

    static String strList(List<String> items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(",");
            b.append(q(items.get(i)));
        }
        return b.append("]").toString();
    }
}
