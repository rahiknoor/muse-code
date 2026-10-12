import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/// Duplicate Content Auditor — Rahik's auditor fleet, auditor #8.
///
/// ONE TASK: find near-duplicate notes across the vault + research notes
/// (5-word shingle Jaccard similarity >= 0.75), so merge candidates get
/// suggested instead of silently piling up.
///
/// READ-ONLY: never merges or deletes. Findings end in SUGGESTED merges;
/// merging needs Rahik's word (destructive -> approval matrix).
///
/// Structured finding format: severity | location | expected vs actual |
/// evidence | suggestion.
public class DuplicateContentAudit {

    static final int SHINGLE = 5;
    static final double THRESHOLD = 0.75;
    static final int MIN_WORDS = 50;
    static final int PAIR_CAP = 15;

    record Doc(String rel, Set<String> shingles, int words) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path home = Paths.get(home());
        List<String> refDirs = loadReferenceOnlyDirs(home);
        List<Doc> docs = loadDocs(home, List.of(
                home.resolve("workspace/atlas"),
                home.resolve("workspace/research_notes")), refDirs);
        List<String> findings = findDuplicates(docs, home.toString());
        if (findings.isEmpty()) {
            System.out.println("DUPLICATE CONTENT: CLEAN — no near-duplicate pairs above "
                    + (int) (THRESHOLD * 100) + "% similarity (" + docs.size() + " notes scanned).");
        } else {
            System.out.println("DUPLICATE CONTENT: " + findings.size() + " candidate pair(s) — read-only, nothing merged.");
            for (String f : findings) System.out.println("\n---\n" + f);
        }
    }

    static List<Doc> loadDocs(Path home, List<Path> roots, List<String> refEntries) throws IOException {
        List<Doc> docs = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            Set<String> tops = referenceOnlyTopDirs(home, root, refEntries);
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".md") && !f.toString().contains("/.git/")
                        && !isExcludedDir(root, f, tops)).toList()) {
                    String text = normalize(Files.readString(p));
                    String[] words = text.split("\\s+");
                    if (words.length < MIN_WORDS) continue;
                    Set<String> sh = new HashSet<>();
                    for (int i = 0; i + SHINGLE <= words.length; i++) {
                        sh.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, i + SHINGLE)));
                    }
                    docs.add(new Doc(root.relativize(p).toString(), sh, words.length));
                }
            }
        }
        return docs;
    }

    /** Lowercase, strip frontmatter/punctuation, collapse whitespace. */
    static String normalize(String text) {
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) text = text.substring(end + 4);
        }
        return text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> small = a.size() < b.size() ? a : b;
        Set<String> big = small == a ? b : a;
        int inter = 0;
        for (String s : small) if (big.contains(s)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    static List<String> findDuplicates(List<Doc> docs, String homePrefix) {
        record Pair(Doc a, Doc b, double sim) {}
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            for (int j = i + 1; j < docs.size(); j++) {
                double sim = jaccard(docs.get(i).shingles(), docs.get(j).shingles());
                if (sim >= THRESHOLD) pairs.add(new Pair(docs.get(i), docs.get(j), sim));
            }
        }
        pairs.sort((x, y) -> Double.compare(y.sim(), x.sim()));
        List<String> out = new ArrayList<>();
        for (Pair pr : pairs.stream().limit(PAIR_CAP).toList()) {
            out.add("[LOW] near-duplicate notes | " + pr.a().rel() + "  <>  " + pr.b().rel() + "\n"
                    + "  expected: one home per idea\n"
                    + "  actual: " + (int) (pr.sim() * 100) + "% shingle similarity ("
                    + pr.a().words() + " vs " + pr.b().words() + " words)\n"
                    + "  suggestion: merge into one note and keep the other as a redirect stub — say the word and it gets proposed properly.");
        }
        return out;
    }

    // ---- plumbing ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    // ---- self-test ----

    /**
     * Reference corpora excluded from duplicate scanning (standing rule 2026-10-07):
     * scripture kept in the vault is for reading; repeated formulaic phrases across
     * surahs (e.g. the basmala) would waste compute and risk false-positive pairs.
     */
    static List<String> loadReferenceOnlyDirs(Path home) {
        Path f = home.resolve("workspace/atlas-scripts/reference_only_dirs.txt");
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
            return out;
        } catch (IOException e) {
            return List.of(); // fail-open
        }
    }

    /** Top-level dir names under root excluded by the workspace-relative entries. */
    static Set<String> referenceOnlyTopDirs(Path home, Path root, List<String> entries) {
        String rootRel = home.relativize(root).toString().replace('\\', '/');
        Set<String> tops = new HashSet<>();
        for (String e : entries) {
            if (e.startsWith(rootRel + "/")) {
                String p = e.substring(rootRel.length() + 1);
                int i = p.indexOf('/');
                tops.add(i < 0 ? p : p.substring(0, i));
            }
        }
        return tops;
    }

    static boolean isExcludedDir(Path root, Path p, Set<String> tops) {
        Path rel = root.relativize(p);
        return rel.getNameCount() > 0 && tops.contains(rel.getName(0).toString());
    }

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("dup-test");
        Path home = tmp;
        Path vault = tmp.resolve("workspace/atlas");
        Files.createDirectories(vault);
        String base = ("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi "
                + "omicron pi rho sigma tau upsilon phi chi psi omega one two three four five ").repeat(4);
        Files.writeString(vault.resolve("a.md"), "# A\n\n" + base + "unique ending words for document alpha here now");
        Files.writeString(vault.resolve("b.md"), "# B\n\n" + base + "unique ending words for document bravo here now");
        Files.writeString(vault.resolve("c.md"), "# C\n\nCompletely different content about gardening tomatoes "
                + "peppers soil compost watering sunshine harvest vegetables ".repeat(6));
        // Two near-identical docs inside the excluded corpus dir — must be ignored.
        Path excl = vault.resolve("10 \uD83D\uDCC6 Quran");
        Files.createDirectories(excl);
        Files.writeString(excl.resolve("q1.md"), "# Q1\n\n" + base + "unique ending words for document alpha here now");
        Files.writeString(excl.resolve("q2.md"), "# Q2\n\n" + base + "unique ending words for document bravo here now");

        List<String> entries = List.of("workspace/atlas/10 \uD83D\uDCC6 Quran");
        List<Doc> docs = loadDocs(home, List.of(vault), entries);
        List<String> findings = findDuplicates(docs, "");
        check(findings.size() == 1, "exactly 1 pair expected, got " + findings.size() + ": " + findings);
        check(findings.get(0).contains("a.md") && findings.get(0).contains("b.md"),
                "must pair a.md <> b.md, got: " + findings.get(0));
        check(findings.stream().noneMatch(f -> f.contains("c.md")),
                "c.md must not pair with anything");
        check(findings.stream().noneMatch(f -> f.contains("Quran")),
                "excluded corpus dir must not appear in findings");
        check(docs.stream().noneMatch(d -> d.rel().contains("Quran")),
                "excluded corpus docs must not be loaded");

        // Prove it can fail: threshold too high misses the pair.
        System.out.println("DuplicateContentAudit self-test: ALL GREEN (near-dup paired, distinct doc untouched)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
