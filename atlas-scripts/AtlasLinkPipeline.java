// AtlasLinkPipeline.java
//
// One-command pipeline for the `atlas-retrieval-spotcheck` cron (idea #43),
// with a built-in health-check verifier (idea #44).
//
// This pipeline owns ONLY the wikilink scan step (Step 3 of the job's stable
// rules). Fact checking and template compliance stay agentic by Rahik's
// standing order — the pipeline never tries to judge truth or prose style.
//
// --run: scan every *.md file under ~/workspace/atlas (excluding .git,
//        .obsidian, and hidden dirs) for [[wikilink]] targets. A link is
//        "broken" when neither `<target>.md` nor `<target>` (as a directory
//        index `<target>/index.md`... kept simple: no .md file with that name)
//        exists anywhere in the vault. Escaped or code-fenced links are not
//        wikilinks: fenced code blocks are skipped, and targets with a '#'
//        anchor or '|' alias are resolved on the part before them. Print a
//        JSON report on stdout: {"notes_scanned": n, "total_links": t,
//        "broken_links": [{"source": "note.md", "target": "..."}], "ok": bool}.
//        Read-only: writes nothing.
//
// --verify: re-runs the same scan and fails loud when:
//           * notes_scanned is 0 (the vault has no .md files — the scan
//             clearly did not cover the vault),
//           * any broken link lacks a non-empty source note,
//           * the scan missed a note that exists on disk at verify time
//             (vault changed between --run and --verify — the report no
//             longer covers "all notes").
//
// The final VERIFY gate ("each marked verified/stale/untraceable") is the
// agent's written verdict in the outcome log — the pipeline can only confirm
// its own scan; it cannot sign off on the agent's claims.

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class AtlasLinkPipeline {

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static Path workspaceRoot = Path.of(homeDir(), "workspace");
    static Path vaultDir() { return workspaceRoot.resolve("atlas"); }

    static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");

    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run": mode = "run"; break;
                case "--verify": mode = "verify"; break;
                case "--self-test": mode = "self-test"; break;
                case "--root": workspaceRoot = Path.of(args[++i], "workspace"); break;
                case "--report-file": reportFile = args[++i]; break;
                default: break;
            }
        }
        if (mode == null) {
            System.out.println("Usage: AtlasLinkPipeline.java --run [--root DIR]");
            System.out.println("       AtlasLinkPipeline.java --verify [--root DIR] [--report-file FILE]");
            System.out.println("       AtlasLinkPipeline.java --self-test");
            System.exit(2);
        }
        if (mode.equals("run")) run();
        else if (mode.equals("verify")) verify();
        else runSelfTest();
    }

    // All .md files in the vault, minus .git/.obsidian/hidden dirs.
    static List<Path> listNotes() throws Exception {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.walk(vaultDir())) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> {
                        for (Path seg : vaultDir().relativize(p))
                            if (seg.toString().startsWith(".")) return false;
                        return true;
                    })
                    .forEach(out::add);
        }
        return out;
    }

    // Names the vault resolves: every note's filename without .md, lowercased.
    static Set<String> vaultNoteNames(List<Path> notes) {
        Set<String> names = new HashSet<>();
        for (Path p : notes) {
            String f = p.getFileName().toString();
            if (f.endsWith(".md")) names.add(f.substring(0, f.length() - 3).toLowerCase());
        }
        return names;
    }

    // Wikilink targets in one note; fenced code blocks are skipped.
    static List<String> extractLinks(Path note) throws Exception {
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        for (String line : Files.readAllLines(note, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.startsWith("```")) { inFence = !inFence; continue; }
            if (inFence) continue;
            Matcher m = WIKILINK.matcher(line);
            while (m.find()) {
                String target = m.group(1).trim();
                // Resolve on the page part: strip #anchor and |alias.
                int cut = target.length();
                for (char c : new char[]{'#', '|'}) {
                    int i = target.indexOf(c);
                    if (i >= 0 && i < cut) cut = i;
                }
                target = target.substring(0, cut).trim();
                if (!target.isEmpty()) out.add(target);
            }
        }
        return out;
    }

    // A target resolves when it names a note anywhere in the vault (the
    // existing case-insensitive name check) OR when it is an Obsidian
    // path-prefixed link — [[folder/Note]], [[folder/Note|alias]],
    // [[folder/Note#anchor]] — and the path exists under the vault root.
    // The #anchor / |alias are stripped by extractLinks before this runs.
    static boolean resolves(String target, Set<String> names) {
        if (names.contains(target.toLowerCase())) return true;
        if (target.indexOf('/') >= 0) {
            try {
                Path vault = vaultDir().normalize();
                for (String cand : new String[]{target + ".md", target}) {
                    Path p = vault.resolve(cand).normalize();
                    // startsWith guard: a "../" escape must never leave the vault.
                    if (p.startsWith(vault) && Files.isRegularFile(p)) return true;
                }
            } catch (Exception e) {
                // Unresolvable path (e.g. unmappable chars under a hostile
                // locale) — fall through and report the link as broken.
            }
        }
        return false;
    }

    static JSONObject scan() throws Exception {
        List<Path> notes = listNotes();
        Set<String> names = vaultNoteNames(notes);
        JSONArray broken = new JSONArray();
        JSONArray scannedNotes = new JSONArray();
        int totalLinks = 0;
        for (Path note : notes) {
            String rel = vaultDir().relativize(note).toString();
            scannedNotes.put(rel);
            for (String target : extractLinks(note)) {
                totalLinks++;
                if (!resolves(target, names)) {
                    JSONObject b = new JSONObject();
                    b.put("source", rel);
                    b.put("target", target);
                    broken.put(b);
                }
            }
        }
        JSONObject out = new JSONObject();
        out.put("notes_scanned", notes.size());
        out.put("notes", scannedNotes);
        out.put("total_links", totalLinks);
        out.put("broken_links", broken);
        out.put("ok", broken.isEmpty());
        return out;
    }

    static void run() throws Exception {
        System.out.println(scan().toString(2));
    }

    // Self-test: builds a throwaway vault with a temp root (the same
    // workspaceRoot override --root uses), then asserts the scan treats
    // path-prefixed links like Obsidian does.
    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("atlas-link-pipeline-test");
        try {
            Path atlas = tmp.resolve("workspace").resolve("atlas");
            Files.createDirectories(atlas.resolve("folder"));
            Files.writeString(atlas.resolve("folder").resolve("Note.md"), "# Note\n",
                    StandardCharsets.UTF_8);
            Files.writeString(atlas.resolve("Linker.md"),
                    "plain [[Note]]\n"
                    + "path [[folder/Note]]\n"
                    + "alias [[folder/Note|the note]]\n"
                    + "anchor [[folder/Note#top]]\n"
                    + "both [[folder/Note#top|alias]]\n"
                    + "broken-path [[folder/Missing]]\n"
                    + "broken-plain [[Nope]]\n"
                    + "```\nfenced [[folder/Nope]]\n```\n",
                    StandardCharsets.UTF_8);
            Path savedRoot = workspaceRoot;
            workspaceRoot = tmp.resolve("workspace");
            try {
                JSONObject report = scan();
                SelfTest.checkEq("notes scanned", 2, report.getInt("notes_scanned"));
                SelfTest.checkEq("total links (fenced one skipped)", 7,
                        report.getInt("total_links"));
                JSONArray broken = report.getJSONArray("broken_links");
                SelfTest.checkEq("broken count", 2, broken.length());
                Set<String> targets = new HashSet<>();
                for (int i = 0; i < broken.length(); i++)
                    targets.add(broken.getJSONObject(i).getString("target"));
                SelfTest.check("broken path-prefixed target still flagged",
                        targets.contains("folder/Missing"));
                SelfTest.check("broken plain target still flagged",
                        targets.contains("Nope"));
                SelfTest.checkNotContains("valid path-prefixed links clear",
                        targets.toString(), "folder/Note");
                List<String> links = extractLinks(atlas.resolve("Linker.md"));
                SelfTest.check("extractLinks strips #anchor and |alias",
                        links.contains("folder/Note") && links.stream()
                                .noneMatch(t -> t.contains("#") || t.contains("|")));
            } finally {
                workspaceRoot = savedRoot;
            }
        } finally {
            deleteTree(tmp);
        }
        SelfTest.finish();
    }

    static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    static String reportFile = null;

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        JSONObject scan = scan();

        if (scan.getInt("notes_scanned") == 0)
            problems.add("notes_scanned is 0 — the vault has no .md files; the scan covered nothing");

        JSONArray broken = scan.getJSONArray("broken_links");
        for (int i = 0; i < broken.length(); i++) {
            JSONObject b = broken.getJSONObject(i);
            if (b.optString("source", "").isBlank())
                problems.add("broken link to '" + b.optString("target", "?") + "' names no source note");
        }

        // Coverage: when given the --run report, every note on disk now must
        // have been in that scan — otherwise the report no longer covers
        // "all notes" (vault changed between --run and --verify).
        if (reportFile != null) {
            try {
                JSONObject report = new JSONObject(
                        Files.readString(Path.of(reportFile), StandardCharsets.UTF_8));
                Set<String> reported = new HashSet<>();
                JSONArray arr = report.optJSONArray("notes");
                if (arr != null) for (int i = 0; i < arr.length(); i++) reported.add(arr.getString(i));
                for (Path note : listNotes()) {
                    String rel = vaultDir().relativize(note).toString();
                    if (!reported.contains(rel))
                        problems.add("note '" + rel + "' exists now but was not in the --run scan (report is stale)");
                }
            } catch (Exception e) {
                problems.add("could not read --report-file '" + reportFile + "': " + e.getMessage());
            }
        }

        if (!problems.isEmpty()) {
            System.err.println("VERIFY FAILED:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(1);
        }
        System.out.println("VERIFY OK: " + scan.getInt("notes_scanned") + " notes scanned, "
                + scan.getInt("total_links") + " wikilinks checked, "
                + broken.length() + " broken"
                + (broken.length() > 0 ? " (all name their source note)" : "")
                + (reportFile != null ? "; report covers every note on disk." : "."));
    }
}
