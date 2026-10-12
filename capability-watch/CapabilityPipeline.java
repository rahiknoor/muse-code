// CapabilityPipeline.java
//
// One-command pipeline for the weekly `capability-watch` cron (idea #43),
// with a built-in health-check verifier (idea #44).
//
// --run: list skill directories in /opt/hatch/skills (bundled) and
//        ~/workspace/skills (custom, if it exists) -> diff against the
//        baselines (~/workspace/capability-watch/bundled-skills.txt,
//        custom-skills.txt) -> for each new directory read the first 40
//        lines of its SKILL.md (name + one-line description from the
//        frontmatter) -> update the baselines -> print a JSON report on
//        stdout. The cron body uses the report to message Rahik; the "stay
//        silent when nothing is new" rule lives in the body.
//
// Usage:
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/capability-watch/CapabilityPipeline.java --run [--dry-run] [--root DIR]
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/capability-watch/CapabilityPipeline.java --verify [--root DIR]
//
// --dry-run: compute the report, do not update baselines.
// --root:    test hook — replaces $HOME as the filesystem root for the
//            workspace side (/opt/hatch/skills is a system path and is NOT
//            affected). Tests can also point --skills-dir at a fixture.
// --skills-dir: test hook — use this directory instead of /opt/hatch/skills.
// --verify:  read-only health check. Fails loud when:
//            * a baseline file is missing or unreadable,
//            * a directory present on disk but absent from the baselines
//              ("new") has no readable SKILL.md — a skill without its
//              SKILL.md is a genuine capability problem worth surfacing.

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class CapabilityPipeline {

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static Path workspaceRoot = Path.of(homeDir(), "workspace");
    static Path bundledSkillsDir = Path.of("/opt/hatch/skills");
    static boolean dryRun = false;

    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run": mode = "run"; break;
                case "--verify": mode = "verify"; break;
                case "--dry-run": dryRun = true; break;
                case "--root": workspaceRoot = Path.of(args[++i], "workspace"); break;
                case "--skills-dir": bundledSkillsDir = Path.of(args[++i]); break;
                default: break;
            }
        }
        if (mode == null) {
            System.out.println("Usage: CapabilityPipeline.java --run [--dry-run] [--root DIR] [--skills-dir DIR]");
            System.out.println("       CapabilityPipeline.java --verify [--root DIR] [--skills-dir DIR]");
            System.exit(2);
        }
        if (mode.equals("run")) run();
        else verify();
    }

    static Path watchDir() { return workspaceRoot.resolve("capability-watch"); }

    // Directory names (sorted) directly under `dir`, or empty set if missing.
    static TreeSet<String> listSkillDirs(Path dir) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) if (Files.isDirectory(p)) out.add(p.getFileName().toString());
        }
        return out;
    }

    static TreeSet<String> readBaseline(Path f) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // Name + one-line description from the SKILL.md frontmatter
    // (name: "..." / description: "..."), falling back to the first markdown
    // heading and first prose line. Only the first 40 lines are read, per the
    // job's stable rules.
    static JSONObject describeSkill(Path skillDir) throws Exception {
        Path md = skillDir.resolve("SKILL.md");
        List<String> lines = Files.readAllLines(md, StandardCharsets.UTF_8);
        if (lines.size() > 40) lines = lines.subList(0, 40);
        String name = null, description = null, heading = null, firstProse = null;
        boolean inFrontmatter = false;
        int fmCount = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.equals("---")) {
                fmCount++;
                inFrontmatter = fmCount == 1;
                continue;
            }
            if (inFrontmatter) {
                Matcher m = Pattern.compile("^(name|description):\\s*[\"']?(.*?)[\"']?\\s*$")
                        .matcher(line);
                if (m.matches()) {
                    if ("name".equals(m.group(1))) name = m.group(2);
                    else description = m.group(2);
                }
                continue;
            }
            if (heading == null && line.startsWith("#")) heading = line.replaceAll("^#+\\s*", "");
            if (firstProse == null && !line.isEmpty() && !line.startsWith("#")
                    && !line.startsWith("```") && !line.startsWith("|") && !line.startsWith("-")) {
                firstProse = line;
            }
        }
        if (name == null) name = heading != null ? heading : skillDir.getFileName().toString();
        if (description == null) description = firstProse != null ? firstProse : "(no description found)";
        JSONObject o = new JSONObject();
        o.put("dir", skillDir.getFileName().toString());
        o.put("name", name);
        o.put("description", description.length() > 200 ? description.substring(0, 200) + "…" : description);
        return o;
    }

    static void run() throws Exception {
        Path customDir = workspaceRoot.resolve("skills");
        TreeSet<String> bundledNow = listSkillDirs(bundledSkillsDir);
        TreeSet<String> customNow = listSkillDirs(customDir);

        Path bundledBase = watchDir().resolve("bundled-skills.txt");
        Path customBase = watchDir().resolve("custom-skills.txt");
        TreeSet<String> bundledKnown = Files.exists(bundledBase) ? readBaseline(bundledBase) : new TreeSet<>();
        TreeSet<String> customKnown = Files.exists(customBase) ? readBaseline(customBase) : new TreeSet<>();

        TreeSet<String> newBundled = new TreeSet<>(bundledNow);
        newBundled.removeAll(bundledKnown);
        TreeSet<String> newCustom = new TreeSet<>(customNow);
        newCustom.removeAll(customKnown);

        JSONArray newBundledOut = new JSONArray(), newCustomOut = new JSONArray();
        try {
            for (String d : newBundled) newBundledOut.put(describeSkill(bundledSkillsDir.resolve(d)));
            for (String d : newCustom) newCustomOut.put(describeSkill(customDir.resolve(d)));
        } catch (Exception e) {
            // A new skill directory without a readable SKILL.md is a genuine
            // problem — fail loud and name it instead of crashing.
            System.err.println("RUN FAILED: cannot read SKILL.md for a new skill directory: "
                    + e.getMessage());
            System.exit(1);
        }

        boolean updated = false;
        if ((!newBundled.isEmpty() || !newCustom.isEmpty()) && !dryRun) {
            Files.createDirectories(watchDir());
            TreeSet<String> b = new TreeSet<>(bundledKnown); b.addAll(newBundled);
            TreeSet<String> c = new TreeSet<>(customKnown); c.addAll(newCustom);
            Files.writeString(bundledBase, String.join("\n", b) + "\n", StandardCharsets.UTF_8);
            Files.writeString(customBase, String.join("\n", c) + "\n", StandardCharsets.UTF_8);
            updated = true;
        }

        JSONObject out = new JSONObject();
        out.put("new_bundled", newBundledOut);
        out.put("new_custom", newCustomOut);
        out.put("baselines_updated", updated);
        out.put("dry_run", dryRun);
        System.out.println(out.toString(2));
    }

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        Path bundledBase = watchDir().resolve("bundled-skills.txt");
        Path customBase = watchDir().resolve("custom-skills.txt");

        TreeSet<String> bundledKnown = null, customKnown = null;
        for (Path base : new Path[]{bundledBase, customBase}) {
            if (!Files.isRegularFile(base)) {
                problems.add("baseline file missing: " + base);
                continue;
            }
            try {
                TreeSet<String> names = readBaseline(base);
                if (base.equals(bundledBase)) bundledKnown = names; else customKnown = names;
            } catch (Exception e) {
                problems.add("baseline file unreadable: " + base + " (" + e.getMessage() + ")");
            }
        }

        // Every directory on disk but absent from the baselines is "new" — it
        // must have a readable SKILL.md, otherwise the capability is broken.
        try {
            if (bundledKnown != null) {
                TreeSet<String> now = listSkillDirs(bundledSkillsDir);
                now.removeAll(bundledKnown);
                for (String d : now) {
                    Path md = bundledSkillsDir.resolve(d).resolve("SKILL.md");
                    if (!Files.isRegularFile(md) || !Files.isReadable(md))
                        problems.add("new bundled skill '" + d + "' has no readable SKILL.md");
                }
            }
            Path customDir = workspaceRoot.resolve("skills");
            if (customKnown != null && Files.isDirectory(customDir)) {
                TreeSet<String> now = listSkillDirs(customDir);
                now.removeAll(customKnown);
                for (String d : now) {
                    Path md = customDir.resolve(d).resolve("SKILL.md");
                    if (!Files.isRegularFile(md) || !Files.isReadable(md))
                        problems.add("new custom skill '" + d + "' has no readable SKILL.md");
                }
            }
        } catch (Exception e) {
            problems.add("could not list skill directories: " + e.getMessage());
        }

        if (!problems.isEmpty()) {
            System.err.println("VERIFY FAILED:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(1);
        }
        System.out.println("VERIFY OK: baselines parse; every new skill directory has a readable SKILL.md.");
    }
}
