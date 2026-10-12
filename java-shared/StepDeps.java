// StepDeps.java — declarative step-dependency checker for cron job bodies.
//
// Convention (GitHub Actions `needs:` pattern, kept lightweight): under each
// `## STEP <token>` header in a cron .md file, an HTML comment declares which
// earlier steps must have succeeded first:
//
//   ## STEP 2 — RUN THE SWEEP
//   <!-- needs: STEP 1 -->
//
// Multiple deps: `<!-- needs: STEP 1, STEP 2 -->`. No deps: omit the comment
// or write `<!-- needs: none -->`. The weekly regression sweep runs this
// checker FIRST (its STEP 0) so a malformed job file fails fast instead of
// running steps out of order.
//
// Static checks: every needed token names a declared STEP; no step needs a
// step that runs later (forward reference); no step needs itself. (With no
// forward references, cycles are impossible.)
//
// Usage:
//   java --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/StepDeps.java --check <cron-job.md>
// Exit 0 when the dependency graph is sound, 1 with the problems listed.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StepDeps {

    static final Pattern STEP_HEADER =
            Pattern.compile("^## STEP\\s+(\\S+)");
    static final Pattern NEEDS_COMMENT =
            Pattern.compile("<!--\\s*needs:\\s*(.*?)\\s*-->",
                    Pattern.CASE_INSENSITIVE);

    private StepDeps() {
        // static-only
    }

    /**
     * Check one cron job file. Returns the list of problems; empty means the
     * dependency graph is sound.
     */
    public static List<String> check(Path jobFile) throws IOException {
        List<String> lines =
                Files.readAllLines(jobFile, StandardCharsets.UTF_8);
        List<String> order = new ArrayList<>();          // STEP tokens, doc order
        Map<String, List<String>> needs = new LinkedHashMap<>();
        String current = null;

        for (String line : lines) {
            Matcher sm = STEP_HEADER.matcher(line);
            if (sm.find()) {
                current = sm.group(1);
                if (!needs.containsKey(current)) {
                    order.add(current);
                    needs.put(current, new ArrayList<>());
                }
                continue;
            }
            Matcher nm = NEEDS_COMMENT.matcher(line);
            if (nm.find() && current != null) {
                String val = nm.group(1).strip();
                if (!val.equalsIgnoreCase("none") && !val.isEmpty()) {
                    // Accept "STEP 1" or "1": drop STEP prefixes, then split.
                    val = val.replaceAll("(?i)\\bSTEP[\\s_-]+", "");
                    for (String dep : val.split("[,\\s]+")) {
                        if (!dep.isEmpty()) {
                            needs.get(current).add(dep);
                        }
                    }
                }
            }
        }

        Map<String, Integer> pos = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            pos.put(order.get(i), i);
        }
        List<String> problems = new ArrayList<>();
        for (String step : order) {
            for (String dep : needs.get(step)) {
                if (!pos.containsKey(dep)) {
                    problems.add("STEP " + step + " needs unknown STEP "
                            + dep);
                } else if (dep.equals(step)) {
                    problems.add("STEP " + step + " needs itself");
                } else if (pos.get(dep) > pos.get(step)) {
                    problems.add("STEP " + step + " needs STEP " + dep
                            + ", which runs later (forward reference)");
                }
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------
    // --self-test
    static Path writeDoc(Path dir, String name, String... lines)
            throws IOException {
        Path f = dir.resolve(name);
        Files.write(f, List.of(lines), StandardCharsets.UTF_8);
        return f;
    }

    static void selfTest() throws Exception {
        Path dir = Files.createTempDirectory("stepdeps");

        Path ok = writeDoc(dir, "ok.md",
                "## STEP 1 — RUN KEY",
                "<!-- needs: none -->",
                "## STEP 2 — RUN THE SWEEP",
                "<!-- needs: STEP 1 -->",
                "## STEP 2b — RETENTION",
                "<!-- needs: STEP 1, STEP 2 -->",
                "## STEP 3 — GREEN PATH",
                "## STEP 5 — NEVER");
        SelfTest.check("valid chain passes", check(ok).isEmpty());

        Path unknown = writeDoc(dir, "unknown.md",
                "## STEP 1 — A",
                "## STEP 2 — B",
                "<!-- needs: STEP 9 -->");
        List<String> p1 = check(unknown);
        SelfTest.check("unknown step flagged",
                p1.size() == 1 && p1.get(0).contains("unknown STEP 9"));

        Path forward = writeDoc(dir, "forward.md",
                "## STEP 1 — A",
                "<!-- needs: STEP 2 -->",
                "## STEP 2 — B");
        List<String> p2 = check(forward);
        SelfTest.check("forward reference flagged",
                p2.size() == 1 && p2.get(0).contains("forward reference"));

        Path self = writeDoc(dir, "self.md",
                "## STEP 1 — A",
                "<!-- needs: STEP 1 -->");
        List<String> p3 = check(self);
        SelfTest.check("self reference flagged",
                p3.size() == 1 && p3.get(0).contains("needs itself"));

        Path noDeps = writeDoc(dir, "nodeps.md",
                "## STEP 1 — A",
                "## STEP 2 — B");
        SelfTest.check("no comments means no deps", check(noDeps).isEmpty());

        Path stray = writeDoc(dir, "stray.md",
                "<!-- needs: STEP 1 -->",
                "## STEP 1 — A");
        SelfTest.check("comment before first STEP ignored",
                check(stray).isEmpty());

        Path multi = writeDoc(dir, "multi.md",
                "## STEP 1 — A",
                "<!-- needs: STEP 1 -->",
                "## STEP 2 — B",
                "<!-- needs: STEP 1 -->",
                "<!-- needs: STEP 7 -->");
        List<String> p4 = check(multi);
        SelfTest.check("two comments merge; one bad dep flagged",
                p4.size() == 2
                        && p4.get(0).contains("needs itself")
                        && p4.get(1).contains("unknown STEP 7"));

        Path bare = writeDoc(dir, "bare.md",
                "## STEP 1 — A",
                "<!-- needs: none -->",
                "## STEP 2 — B",
                "<!-- needs: 1 -->");
        SelfTest.check("bare token form also accepted", check(bare).isEmpty());

        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--check")) {
            List<String> problems = check(Path.of(args[1]));
            if (problems.isEmpty()) {
                System.out.println("StepDeps OK: dependency graph is sound.");
            } else {
                for (String p : problems) {
                    System.out.println("FAIL: " + p);
                }
                System.out.println("StepDeps FAILED: " + problems.size()
                        + " problem(s).");
                System.exit(1);
            }
            return;
        }
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        System.out.println("Usage:");
        System.out.println("  StepDeps.java --check <cron-job.md>");
        System.out.println("  StepDeps.java --self-test");
        System.exit(2);
    }
}
