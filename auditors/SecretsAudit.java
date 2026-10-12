import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Secrets Auditor — Rahik's auditor fleet, auditor #7.
///
/// ONE TASK: scan the workspace for accidentally committed secrets —
/// API keys, tokens, passwords, private keys — especially dangerous
/// because the vault pushes to GitHub.
///
/// READ-ONLY: never edits or deletes. Findings REVEAL NOTHING: values are
/// masked (type + file:line + first-4-chars only). Findings end in
/// SUGGESTED actions (rotate the credential, move it to the Secure Vault);
/// a separate approved pass does any implementing.
///
/// Structured finding format: severity | location | expected vs actual |
/// evidence (masked) | suggestion.
public class SecretsAudit {

    record Sig(String name, Pattern pattern, String advice) {}

    static final List<Sig> SIGNATURES = List.of(
            new Sig("OpenAI key", Pattern.compile("sk-[A-Za-z0-9]{20,}"),
                    "rotate at platform.openai.com; never store in files"),
            new Sig("GitHub token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
                    "revoke at github.com/settings/tokens; the Dell pull uses a PAT that must be replaced if leaked"),
            new Sig("AWS key", Pattern.compile("AKIA[0-9A-Z]{16}"),
                    "revoke in IAM immediately"),
            new Sig("Slack token", Pattern.compile("xox[barp]-[A-Za-z0-9-]{10,}"),
                    "revoke in Slack app settings"),
            new Sig("private key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                    "move to ~/.ssh with 600 perms; never commit"),
            new Sig("assigned secret", Pattern.compile(
                    "(?i)(api[_-]?key|api[_-]?secret|client[_-]?secret|auth[_-]?token|access[_-]?token|secret[_-]?key)\\s*[:=]\\s*['\"]?([A-Za-z0-9_\\-./+]{16,})['\"]?"),
                    "move to the Secure Vault; reference by name, never by value"));

    /** Paths never scanned (vendored code, runtime artifacts, backups, the auditor's own fixtures). */
    static boolean excluded(Path home, Path p) {
        String s = home.relativize(p).toString();
        return s.contains("/.git/") || s.startsWith(".git/")
                || s.contains("node_modules") || s.contains("/venv/") || s.contains(".pwvenv")
                || s.contains("jdk-21") || s.endsWith(".jar") || s.endsWith(".tar.gz")
                || s.endsWith(".zip") || s.endsWith(".bak") || s.contains("lastgood")
                || s.contains("LastGood") || s.contains("RETIRED")
                || s.contains("/.hatch-browser/")   // runtime browser snapshots, not user files
                || s.contains("/.local/")           // vendored tool installs (yt-dlp etc.)
                || s.contains("/.cache/") || s.contains("/__pycache__/")
                || s.contains("venv")               // python virtualenvs (vendored packages)
                || s.contains("site-packages");     // vendored python packages
    }

    /** Reference-only corpora (standing rule 2026-10-07): machine-fetched reference
     *  text never contains his secrets — skip the weekly pattern scan over it. */
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

    static boolean isReferenceOnly(Path home, Path p, List<String> refDirs) {
        String s;
        try { s = home.relativize(p).toString().replace('\\', '/'); }
        catch (Exception e) { return false; }
        for (String d : refDirs) {
            if (s.equals(d) || s.startsWith(d + "/")) return true;
        }
        return false;
    }

    /** Lines that look like secrets but are documentation/examples. */
    static final Pattern EXAMPLE = Pattern.compile(
            "(?i)(example|sample|placeholder|your[_-]?key|xxx+|\\*\\*\\*|test[_-]?key|fake)");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path home = Paths.get(home());
        List<String> findings = audit(home.resolve("workspace"), loadReferenceOnlyDirs(home));
        if (findings.isEmpty()) {
            System.out.println("SECRETS AUDIT: CLEAN — no secret patterns in the workspace.");
        } else {
            System.out.println("SECRETS AUDIT: " + findings.size() + " finding(s) — read-only, nothing changed; values masked.");
            for (String f : findings) System.out.println("\n---\n" + f);
        }
    }

    static List<String> audit(Path root) throws IOException {
        return audit(root, loadReferenceOnlyDirs(root.getParent()));
    }

    static List<String> audit(Path root, List<String> refDirs) throws IOException {
        Path home = root.getParent();
        List<String> findings = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                if (excluded(home, p) || isReferenceOnly(home, p, refDirs)) continue;
                String rel;
                try { rel = home.relativize(p).toString(); }
                catch (Exception e) { rel = p.toString(); }
                // Skip absurdly large files (videos, data dumps).
                long size;
                try { size = Files.size(p); } catch (IOException e) { continue; }
                if (size > 5_000_000) continue;
                List<String> lines;
                try { lines = Files.readAllLines(p); }
                catch (Exception e) { continue; } // binary
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (EXAMPLE.matcher(line).find()) continue;
                    for (Sig sig : SIGNATURES) {
                        Matcher m = sig.pattern().matcher(line);
                        if (m.find()) {
                            String val = m.groupCount() >= 2 && m.group(2) != null ? m.group(2) : m.group();
                            if (val.startsWith("self.") || val.contains("(")) continue; // code, not a literal
                            findings.add("[CRITICAL] possible secret | " + rel + ":" + (i + 1) + "\n"
                                    + "  expected: no credential values in workspace files\n"
                                    + "  actual: " + sig.name() + " pattern matched (value masked: " + mask(val) + ")\n"
                                    + "  suggestion: " + sig.advice() + ". Then say the word and the value gets purged from the file/history.");
                            if (findings.size() >= 25) return findings;
                            break; // one finding per line is enough
                        }
                    }
                }
            }
        }
        return findings;
    }

    /** Show at most the first 4 chars — never the value. */
    static String mask(String v) {
        if (v == null || v.length() <= 4) return "****";
        return v.substring(0, 4) + "**** (length " + v.length() + ")";
    }

    // ---- plumbing ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    // ---- self-test ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("secrets-test");
        Path ws = tmp.resolve("workspace");
        Files.createDirectories(ws);

        // RED: fake GitHub token (built at runtime so this source holds no literal).
        String fakeTok = "ghp_" + "abcdefghij1234567890ABCD";
        Files.writeString(ws.resolve("notes.md"), "# notes\ntoken=" + fakeTok + "\n");
        // RED: assigned secret.
        String fakeKey = "supersecret" + "value12345";
        Files.writeString(ws.resolve("cfg.txt"), "api_key: " + fakeKey + "\n");
        // GREEN: documentation example.
        Files.writeString(ws.resolve("doc.md"), "Set api_key to \"your-key-here\" — example only.\n");
        // GREEN: clean file.
        Files.writeString(ws.resolve("clean.md"), "Nothing to see here.\n");
        // GREEN: excluded path.
        Path git = ws.resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("config"), "token=" + fakeTok + "\n");

        // GREEN: fake secret inside a reference-only corpus dir — skipped.
        Path corpus = ws.resolve("religion/quran/corpus");
        Files.createDirectories(corpus);
        Files.writeString(corpus.resolve("translation.json"), "{\"key\": \"" + fakeTok + "\"}\n");

        List<String> findings = audit(ws, List.of("workspace/religion/quran/corpus"));
        check(findings.size() == 2, "exactly 2 findings expected, got " + findings.size() + ": " + findings);
        check(findings.stream().anyMatch(f -> f.contains("GitHub token") && f.contains("notes.md")),
                "must flag the GitHub token");
        check(findings.stream().anyMatch(f -> f.contains("assigned secret") && f.contains("cfg.txt")),
                "must flag the assigned api_key");
        check(findings.stream().noneMatch(f -> f.contains("abcdefghij1234567890")),
                "values must be masked — full token leaked into output!");
        check(findings.stream().noneMatch(f -> f.contains(".git")),
                ".git must be excluded");

        System.out.println("SecretsAudit self-test: ALL GREEN (2 reds flagged, masking proven, excludes proven)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
