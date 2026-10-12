import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.json.JSONObject;

/// Artifact Freshness Auditor — Rahik's auditor fleet, auditor #5.
///
/// ONE TASK: detect when a web artifact's *source data* changed without a
/// recorded refresh, so stale artifacts get verified instead of silently
/// rotting.
///
/// HOW: keeps a fingerprint (SHA-256 over file paths+sizes+mtimes) per
/// artifact's source directories in artifact_freshness.json. Each run
/// recomputes; a changed fingerprint means the sources moved — the artifact
/// may be stale.
///
/// READ-ONLY: never touches artifacts (their files are the builder's domain).
/// Findings end in SUGGESTED actions; verifying/refreshing an artifact needs
/// Rahik's word. First run only establishes baselines (no findings).
///
/// Structured finding format: severity | artifact | expected vs actual |
/// evidence | suggestion.
public class ArtifactFreshnessAudit {

    record Artifact(String slug, String name, List<String> sources, String note) {}

    static List<Artifact> artifacts() {
        return List.of(
                new Artifact("stock-research-hub", "Stock Research Hub",
                        List.of("workspace/dcf_csv"),
                        "auto-refreshed by the SEC filing watch on new filings"),
                new Artifact("website-intrinsic-value-consensus", "Website Intrinsic-Value Consensus",
                        List.of("workspace/dcf_csv"),
                        "rebuilt from the JSON backends when values change"),
                new Artifact("dcf-comparison", "DCF Comparison",
                        List.of("workspace/dcf_csv", "workspace/research_notes/dcf_methodology.md"),
                        "methodology + backend driven"),
                new Artifact("dcf-study-guide", "DCF Study Guide",
                        List.of("workspace/research_notes/dcf_methodology.md", "workspace/your_files/dcf-study-guide.md"),
                        "static reference; refresh when methodology changes"),
                new Artifact("3-meal-diet-plan", "3-Meal Diet Plan",
                        List.of("workspace/research_notes/diet-plan-3-meal", "workspace/health_archive/talon-fitness"),
                        "updated as Talon research lands"),
                new Artifact("vitamins-minerals-chart", "Vitamins & Minerals Chart",
                        List.of("workspace/health_archive/talon-fitness"),
                        "static reference; refresh when Talon nutrient work completes"),
                new Artifact("product-shortlist", "Product Shortlist",
                        List.of("workspace/research_notes/product-shortlist-v2-20261006"),
                        "updated as dropshipping product work progresses"),
                new Artifact("atlas-vault", "Atlas Vault",
                        List.of("workspace/atlas"),
                        "mirrors the vault; refresh after vault changes"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path home = Paths.get(home());
        Path state = home.resolve("workspace/auditors/artifact_freshness.json");
        List<String> findings = audit(home, state, artifacts());
        if (findings.isEmpty()) {
            System.out.println("ARTIFACT FRESHNESS: CLEAN — no source moved without a recorded refresh.");
        } else {
            System.out.println("ARTIFACT FRESHNESS: " + findings.size() + " finding(s) — read-only, nothing changed.");
            for (String f : findings) System.out.println("\n---\n" + f);
        }
    }

    static List<String> audit(Path home, Path state, List<Artifact> arts) throws Exception {
        JSONObject prev = Files.exists(state)
                ? new JSONObject(Files.readString(state)) : new JSONObject();
        boolean firstRun = !Files.exists(state);
        JSONObject next = new JSONObject();
        List<String> findings = new ArrayList<>();
        for (Artifact a : arts) {
            String fp = fingerprint(home, a.sources());
            next.put(a.slug(), fp);
            if (firstRun) continue;
            String old = prev.optString(a.slug(), null);
            if (old == null) {
                findings.add("[LOW] new artifact tracked | " + a.name() + " (" + a.slug() + ")\n"
                        + "  suggestion: baseline recorded this run; it will be watched from here.");
            } else if (!old.equals(fp)) {
                findings.add("[LOW] sources changed | " + a.name() + " (" + a.slug() + ")\n"
                        + "  expected: artifact reflects its current sources (" + a.note() + ")\n"
                        + "  actual: source files changed since the last audit\n"
                        + "  evidence: fingerprint " + old.substring(0, 12) + " → " + fp.substring(0, 12) + "\n"
                        + "  suggestion: verify the artifact shows the new data — say the word and it gets refreshed via artifact.edit.");
            }
        }
        // Atomic write with backup, same convention as RunKeys.
        Path bak = state.resolveSibling(state.getFileName() + ".bak");
        if (Files.exists(state)) Files.copy(state, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path tmp = state.resolveSibling(state.getFileName() + ".tmp");
        Files.writeString(tmp, next.toString(1));
        Files.move(tmp, state, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        if (firstRun) {
            System.out.println("BASELINES ESTABLISHED for " + arts.size() + " artifacts — future runs will flag source drift.");
        }
        return findings;
    }

    static String fingerprint(Path home, List<String> sources) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Map<String, String> entries = new TreeMap<>();
        for (String src : sources) {
            Path p = home.resolve(src);
            if (!Files.exists(p)) {
                entries.put(src, "MISSING");
                continue;
            }
            try (Stream<Path> s = Files.isDirectory(p) ? Files.walk(p) : Stream.of(p)) {
                for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = home.relativize(f).toString();
                    entries.put(rel, Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis());
                }
            }
        }
        for (Map.Entry<String, String> e : entries.entrySet()) {
            md.update((e.getKey() + "=" + e.getValue() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    // ---- plumbing ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    // ---- self-test ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("artfresh-test");
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("data.json"), "{\"a\":1}");
        Path state = tmp.resolve("state.json");
        List<Artifact> arts = List.of(
                new Artifact("test-artifact", "Test Artifact", List.of(tmp.relativize(src).toString()), "test"));

        // Run 1: baselines established, no findings. Paths must resolve under tmp,
        // so audit against a fake home = tmp's parent layout. Instead, chdir logic:
        // fingerprint() resolves home.resolve(src) — pass home=tmp and relative source.
        List<String> r1 = audit(tmp, state, arts);
        check(r1.isEmpty(), "first run must only establish baselines, got: " + r1);
        check(Files.exists(state), "state file must be written");

        // Run 2: nothing changed -> clean.
        List<String> r2 = audit(tmp, state, arts);
        check(r2.isEmpty(), "unchanged sources must be clean, got: " + r2);

        // RED: touch a source file -> drift flagged.
        Thread.sleep(1100); // ensure mtime moves past 1s granularity
        Files.writeString(src.resolve("data.json"), "{\"a\":2}");
        List<String> r3 = audit(tmp, state, arts);
        check(r3.size() == 1 && r3.get(0).contains("sources changed"),
                "changed source must flag drift, got: " + r3);

        // Missing source -> still produces a (different) fingerprint, no crash.
        Files.delete(src.resolve("data.json"));
        List<String> r4 = audit(tmp, state, arts);
        check(r4.size() == 1, "deleted source must flag drift, got: " + r4);

        System.out.println("ArtifactFreshnessAudit self-test: ALL GREEN (baseline, clean, drift red, delete red proven)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
