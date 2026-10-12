import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/// Vault Diff Auditor — Rahik's auditor fleet, auditor #1.
///
/// ONE TASK: compare ~/workspace/atlas against origin/main on GitHub and
/// report what changed, which side is newer per file, and what is missing
/// on either side.
///
/// READ-ONLY: never commits, never pushes, never pulls, never writes to the
/// vault. Findings end in SUGGESTED actions; a separate approved pass does
/// any implementing.
public class VaultDiffAudit {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path vault = Paths.get(home(), "workspace", "atlas");
        List<String> findings = audit(vault);
        if (findings.isEmpty()) {
            System.out.println("VAULT DIFF: CLEAN — working tree matches origin/main, nothing unpushed or unpulled.");
        } else {
            System.out.println("VAULT DIFF: " + findings.size() + " finding(s) — read-only, nothing changed.");
            for (String f : findings) System.out.println("\n---\n" + f);
            System.out.println("\nSUGGESTED ACTIONS (not taken — auditor never implements):");
            for (String s : suggestions(findings)) System.out.println("- " + s);
        }
    }

    static List<String> audit(Path vault) throws Exception {
        List<String> findings = new ArrayList<>();
        if (!Files.isDirectory(vault.resolve(".git"))) {
            findings.add("NOT A GIT REPO: " + vault + " has no .git — cannot diff. Suggestion: re-clone rahiknoor/atlas-vault.");
            return findings;
        }
        String remoteMain;
        try {
            remoteMain = exec(vault, "git", "ls-remote", "--heads", "origin", "main").strip();
        } catch (Exception e) {
            findings.add("FETCH FAILED: could not reach origin (" + firstLine(e.getMessage()) + "). Diff not attempted — retry when the network is back.");
            return findings;
        }
        if (remoteMain.isBlank()) {
            findings.add("NO UPSTREAM HISTORY: origin has no main branch — nothing has ever been pushed. Suggestion: push local main to create it.");
            return findings;
        }
        String fetchErr = execQuiet(vault, "git", "fetch", "-q", "origin", "main");
        if (fetchErr != null) {
            findings.add("FETCH STALE: re-fetch failed (" + firstLine(fetchErr) + ") — diff is against the last fetched state and may be outdated.");
        }

        String dirty = exec(vault, "git", "status", "--porcelain");
        if (!dirty.isBlank()) {
            findings.add("UNCOMMITTED CHANGES in working tree (" + dirty.lines().count() + " file(s)):\n" + head(dirty, 20));
        }
        boolean hasUpstream = execQuiet(vault, "git", "rev-parse", "--verify", "--quiet", "origin/main") == null;
        if (!hasUpstream) {
            findings.add("NO UPSTREAM HISTORY: origin/main does not exist — nothing has ever been pushed. Suggestion: push local main to create it.");
            return findings;
        }
        String unpushed = exec(vault, "git", "log", "origin/main..main", "--format=%h %ci %s");
        if (!unpushed.isBlank()) {
            findings.add("UNPUSHED COMMITS (local main is ahead of GitHub, " + unpushed.lines().count() + "):\n" + head(unpushed, 20));
        }
        String unpulled = exec(vault, "git", "log", "main..origin/main", "--format=%h %ci %s");
        if (!unpulled.isBlank()) {
            findings.add("UNPULLED COMMITS (GitHub changed without this machine — " + unpulled.lines().count() + "):\n" + head(unpulled, 20));
        }
        String namestat = exec(vault, "git", "diff", "--name-status", "main", "origin/main");
        if (!namestat.isBlank()) {
            StringBuilder per = new StringBuilder("FILES DIFFERING between local main and origin/main:\n");
            for (String line : namestat.lines().limit(30).toList()) {
                String[] parts = line.split("\\s+", 2);
                if (parts.length < 2) continue;
                String f = parts[1];
                String localDate = exec(vault, "git", "log", "-1", "--format=%ci", "main", "--", f).strip();
                String remoteDate = exec(vault, "git", "log", "-1", "--format=%ci", "origin/main", "--", f).strip();
                String newer = localDate.compareTo(remoteDate) >= 0 ? "LOCAL newer" : "GITHUB newer";
                per.append(String.format("  [%s] %s | local: %s | github: %s | %s%n",
                        parts[0], f, shortDate(localDate), shortDate(remoteDate), newer));
            }
            findings.add(per.toString().strip());
        }
        return findings;
    }

    static List<String> suggestions(List<String> findings) {
        List<String> out = new ArrayList<>();
        for (String f : findings) {
            if (f.startsWith("UNCOMMITTED")) out.add("Review the uncommitted files, then commit and push — or discard if they are scratch.");
            else if (f.startsWith("UNPUSHED")) out.add("Push local main so GitHub (and the iPhone/Dell clones) catch up.");
            else if (f.startsWith("UNPULLED")) out.add("Pull and review — someone or something changed GitHub directly; confirm each change is wanted.");
            else if (f.startsWith("FILES DIFFERING")) out.add("For each differing file, keep the newer side's version unless there is a reason not to — say the word per file.");
            else if (f.startsWith("FETCH FAILED")) out.add("Retry when the network is back; no diff was attempted.");
            else if (f.startsWith("FETCH STALE")) out.add("Re-fetch when the network is steady; treat this report as possibly outdated.");
            else if (f.startsWith("NOT A GIT REPO")) out.add("Re-clone the repo before trusting any vault state.");
        }
        return out;
    }

    // ---- plumbing ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static String exec(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) throw new IOException("git failed (" + code + "): " + String.join(" ", cmd) + "\n" + out);
        return out;
    }

    /** Returns error text, or null when the command succeeded. */
    static String execQuiet(Path dir, String... cmd) {
        try {
            exec(dir, cmd);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    static String head(String s, int n) {
        return s.lines().limit(n).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    static String shortDate(String ci) {
        return ci.length() >= 16 ? ci.substring(0, 16) : ci;
    }

    // ---- self-test: prove it can fail (red) and pass (green) ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("vaultdiff-test");
        Path remote = tmp.resolve("remote.git");
        exec(tmp, "git", "init", "--bare", "-q", remote.toString());

        // Seed the remote with a main branch so fixtures have an upstream.
        Path seed = tmp.resolve("seed");
        exec(tmp, "git", "clone", "-q", remote.toString(), seed.toString());
        gitConfig(seed);
        Files.writeString(seed.resolve("readme.md"), "seed");
        exec(seed, "git", "add", "-A");
        exec(seed, "git", "commit", "-qm", "seed");
        exec(seed, "git", "push", "-q", "origin", "HEAD:main");
        exec(tmp, "git", "--git-dir", remote.toString(), "symbolic-ref", "HEAD", "refs/heads/main");

        Path local = tmp.resolve("local");
        exec(tmp, "git", "clone", "-q", remote.toString(), local.toString());
        gitConfig(local);

        // GREEN: clean, synced repo -> no findings.
        List<String> clean = audit(local);
        check(clean.isEmpty(), "clean synced repo must report no findings, got: " + clean);

        // RED 1: unpushed commit.
        Files.writeString(local.resolve("a.md"), "hello");
        exec(local, "git", "add", "-A");
        exec(local, "git", "commit", "-qm", "add a");
        List<String> unpushed = audit(local);
        check(unpushed.stream().anyMatch(f -> f.startsWith("UNPUSHED")), "must flag unpushed commit");

        // RED 2: uncommitted change.
        Files.writeString(local.resolve("b.md"), "scratch");
        List<String> dirty = audit(local);
        check(dirty.stream().anyMatch(f -> f.startsWith("UNCOMMITTED")), "must flag uncommitted changes");
        exec(local, "git", "checkout", "-q", "--", ".");

        // RED 3: remote-side change (unpulled).
        Path other = tmp.resolve("other");
        exec(tmp, "git", "clone", "-q", remote.toString(), other.toString());
        gitConfig(other);
        Files.writeString(other.resolve("c.md"), "from elsewhere");
        exec(other, "git", "add", "-A");
        exec(other, "git", "commit", "-qm", "remote change");
        exec(other, "git", "push", "-q", "origin", "HEAD:main");
        List<String> unpulled = audit(local);
        check(unpulled.stream().anyMatch(f -> f.startsWith("UNPULLED")), "must flag unpulled remote change");
        check(unpulled.stream().anyMatch(f -> f.startsWith("FILES DIFFERING") && f.contains("GITHUB newer")),
                "per-file section must mark the remote side newer");

        // RED 4: remote with no main at all.
        Path emptyRemote = tmp.resolve("empty.git");
        exec(tmp, "git", "init", "--bare", "-q", emptyRemote.toString());
        Path lonely = tmp.resolve("lonely");
        exec(tmp, "git", "clone", "-q", emptyRemote.toString(), lonely.toString());
        gitConfig(lonely);
        List<String> noUpstream = audit(lonely);
        check(noUpstream.stream().anyMatch(f -> f.startsWith("NO UPSTREAM HISTORY")),
                "must flag missing upstream main, got: " + noUpstream);

        System.out.println("VaultDiffAudit self-test: ALL GREEN (4 red cases proven, 1 green case proven)");
    }

    static void gitConfig(Path repo) throws IOException, InterruptedException {
        exec(repo, "git", "config", "user.email", "test@test");
        exec(repo, "git", "config", "user.name", "test");
        exec(repo, "git", "config", "commit.gpgsign", "false");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
