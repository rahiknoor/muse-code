// MethodologyCheck.java — verifies the DCF methodology the doc describes is the
// methodology the code computes, and that NotebookLM's notebook holds current files.
//
// Two checks:
//   --doc-code        doc<->code consistency: every key formula in DcfModel.java
//                     must be described in research_notes/dcf_methodology.md.
//   --notebook-fresh  upload freshness: the methodology doc + workbook PDF that
//                     NotebookLM reads must match what was last uploaded
//                     (SHA-256 vs the recorded upload state).
//   --record-upload   record today's SHA-256 of the two uploaded files after a
//                     successful NotebookLM sync (run by the sync task).
//   --self-test       synthetic positive/negative tests.
//
// ALIGNMENT GUARANTEE (Rahik, 2026-09-27):
//   This tool DETECTS drift; it NEVER repairs it. On any doc<->code mismatch it
//   fails loudly and reports both sides — it does NOT rewrite the methodology
//   doc to match the code (that would let the code dictate what NotebookLM
//   believes). The repair direction is decided by a human in DCF Model Lab:
//   either the code changed deliberately and the doc gets an honest, reviewed
//   update, or the doc is right and the code has a bug. The doc itself stays a
//   neutral description of the method — no values, no conclusions, no steering
//   — so NotebookLM keeps doing its own independent application of it
//   (dual-track rule: compared, never copied).
//
// HONESTY LIMITS (stated, not hidden):
//   - Code patterns are matched against DcfModel.java with comments stripped,
//     so a formula change can't hide behind an unchanged comment.
//   - The freshness check cannot see NotebookLM's servers; it verifies the
//     checkable invariant: current local bytes == bytes recorded at upload.
//     A re-upload must run --record-upload afterwards, or the next check fails.
//
// Wiring: RebuildAll.java runs `--doc-code` as a fail-loud build gate
// (exit 1 stops the rebuild). Exit 0 = OK.
//
// Launch:
//   LC_ALL=C.UTF-8 ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/java-shared/classes \
//       ~/workspace/dcf_csv/MethodologyCheck.java --doc-code
//
// Test overrides (paths): MC_CODE, MC_DOC, MC_STATE env vars.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class MethodologyCheck {

    /** Workspace root: DCF_WORKSPACE_ROOT env, else $HOME/workspace. */
    static String workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        if (e != null && !e.isEmpty()) {
            return e;
        }
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty() ? h : System.getProperty("user.home"))
                + "/workspace";
    }

    static String envOr(String name, String dflt) {
        String e = System.getenv(name);
        return (e != null && !e.isEmpty()) ? e : dflt;
    }

    static Path codePath() {
        return Paths.get(envOr("MC_CODE", workspaceRoot() + "/dcf_csv/DcfModel.java"));
    }

    static Path docPath() {
        return Paths.get(envOr("MC_DOC",
                workspaceRoot() + "/research_notes/dcf_methodology.md"));
    }

    static Path statePath() {
        return Paths.get(envOr("MC_STATE",
                workspaceRoot() + "/dcf_csv/notebooklm_upload_state.json"));
    }

    /** Files NotebookLM's 'DCF Methodology Check' notebook must hold, rel. to root. */
    static final String[] TRACKED = {
        "research_notes/dcf_methodology.md",
        "dcf_csv/DCF Valuation Model.pdf",
    };

    /**
     * One methodology claim: a structural pattern that must appear in the
     * CODE (comments stripped) and phrases that must appear in the DOC.
     * Patterns are regexes over real code, not prose — changing the formula
     * without updating the doc breaks the match and fails the build.
     */
    record Claim(String id, String codeRegex, String[] docPhrases) {}

    static final List<Claim> CLAIMS = List.of(
        new Claim("mid-year discounting",
                "\\by\\s*-\\s*0\\.5\\b",
                new String[]{"mid-year", "0.5"}),
        new Claim("growth fade years 6-10",
                "\\(gc\\s*-\\s*t\\)\\s*/\\s*5",
                new String[]{"fading linearly"}),
        new Claim("Gordon-growth terminal value",
                "\\(\\s*1\\s*\\+\\s*t\\s*\\)\\s*/\\s*\\(\\s*w\\s*-\\s*t\\s*\\)",
                new String[]{"Gordon growth"}),
        new Claim("terminal value discounted at 9.5",
                "\\b9\\.5\\b",
                new String[]{"9.5"}),
        new Claim("WACC must exceed terminal growth",
                "\\bw\\s*<=\\s*t\\b",
                new String[]{"less than WACC"}),
        new Claim("FCFE variant skips net-debt subtraction",
                "\"FCFE\"\\)\\s*\\?\\s*ev\\s*:\\s*ev\\s*-\\s*netdebt",
                new String[]{"FCFE", "net-debt"}),
        new Claim("REV variant margin ramp",
                "\\(\\s*endMargin\\s*-\\s*startMargin\\s*\\)",
                new String[]{"margin", "ramping linearly"}),
        new Claim("10-year projection horizon",
                "new\\s+double\\s*\\[\\s*10\\s*\\]",
                new String[]{"10 years"}),
        new Claim("sustained growth capped at 15%",
                "Math\\.min\\(\\s*g\\s*,\\s*MAX_SUSTAINED_GROWTH\\s*\\)",
                new String[]{"capped at 15%"}),
        new Claim("trough-year base normalization",
                "revenue\\s*\\*\\s*avgFcfMargin3y",
                new String[]{"3-year average FCF margin"}),
        new Claim("terminal-value share warning at 75%",
                "TV_SHARE_WARN\\s*=\\s*0\\.75",
                new String[]{"75%", "terminal-value"})
    );

    private MethodologyCheck() {
        // static-only
    }

    // ------------------------------------------------------------------
    // Comment stripper: // and /* */ removed; strings/chars preserved so a
    // formula can't "pass" via a comment or a prose string.
    // ------------------------------------------------------------------
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\') {
                        j += 2;
                    } else if (d == '"') {
                        j++;
                        break;
                    } else {
                        j++;
                    }
                }
                out.append(src, i, Math.min(j, n));
                i = Math.min(j, n);
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\') {
                        j += 2;
                    } else if (d == '\'') {
                        j++;
                        break;
                    } else {
                        j++;
                    }
                }
                out.append(src, i, Math.min(j, n));
                i = Math.min(j, n);
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Check 1: doc<->code consistency. Returns failure descriptions.
    // ------------------------------------------------------------------
    static List<String> checkDocCode(Path codeFile, Path docFile) throws Exception {
        List<String> failures = new ArrayList<>();
        if (!Files.isRegularFile(codeFile)) {
            failures.add("code file missing: " + codeFile);
            return failures;
        }
        if (!Files.isRegularFile(docFile)) {
            failures.add("doc file missing: " + docFile);
            return failures;
        }
        String code = stripComments(
                Files.readString(codeFile, StandardCharsets.UTF_8));
        String doc = Files.readString(docFile, StandardCharsets.UTF_8);
        String docLow = doc.toLowerCase();
        for (Claim cl : CLAIMS) {
            boolean inCode = Pattern.compile(cl.codeRegex()).matcher(code).find();
            if (!inCode) {
                failures.add("claim '" + cl.id() + "': formula not found in "
                        + codeFile.getFileName() + " (code changed without a doc-visible trace?)");
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String phrase : cl.docPhrases()) {
                if (!docLow.contains(phrase.toLowerCase())) {
                    missing.add(phrase);
                }
            }
            if (!missing.isEmpty()) {
                failures.add("claim '" + cl.id() + "': described in code but the doc "
                        + "never says " + missing + " — doc and code have drifted");
            }
        }
        return failures;
    }

    // ------------------------------------------------------------------
    // Check 2: notebook upload freshness (SHA-256 vs recorded state).
    // ------------------------------------------------------------------
    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Minimal reader for the state file this tool writes. */
    static Map<String, Map<String, String>> readState(Path state) throws Exception {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(state)) {
            return out;
        }
        String t = Files.readString(state, StandardCharsets.UTF_8);
        // Shape: {"files": {"rel/path": {"sha256": "...", "uploaded_at": "..."}}}
        int fi = t.indexOf("\"files\"");
        if (fi < 0) {
            return out;
        }
        java.util.regex.Matcher m = Pattern.compile(
                "\"([^\"]+)\"\\s*:\\s*\\{\\s*\"sha256\"\\s*:\\s*\"([0-9a-f]+)\"\\s*,"
                + "\\s*\"uploaded_at\"\\s*:\\s*\"([^\"]+)\"\\s*\\}").matcher(t);
        while (m.find()) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("sha256", m.group(2));
            e.put("uploaded_at", m.group(3));
            out.put(m.group(1), e);
        }
        return out;
    }

    static void recordUpload(Path state, String root) throws Exception {
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        for (String rel : TRACKED) {
            Path p = Paths.get(root, rel);
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("cannot record upload, file missing: " + p);
            }
            Map<String, String> e = new LinkedHashMap<>();
            e.put("sha256", sha256(p));
            e.put("uploaded_at", LocalDate.now() + "T" + java.time.LocalTime.now()
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            files.put(rel, e);
        }
        StringBuilder sb = new StringBuilder("{\n \"files\": {\n");
        boolean first = true;
        for (Map.Entry<String, Map<String, String>> en : files.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  \"").append(en.getKey()).append("\": {\"sha256\": \"")
              .append(en.getValue().get("sha256")).append("\", \"uploaded_at\": \"")
              .append(en.getValue().get("uploaded_at")).append("\"}");
        }
        sb.append("\n }\n}\n");
        Files.writeString(state, sb.toString(), StandardCharsets.UTF_8);
        for (String rel : files.keySet()) {
            System.out.println("recorded: " + rel + " sha256="
                    + files.get(rel).get("sha256").substring(0, 12) + "...");
        }
    }

    static List<String> checkFresh(Path state, String root) throws Exception {
        List<String> failures = new ArrayList<>();
        Map<String, Map<String, String>> recorded = readState(state);
        if (recorded.isEmpty()) {
            failures.add("no upload record at " + state + " — run --record-upload "
                    + "after a successful NotebookLM sync before trusting notebook values");
            return failures;
        }
        for (String rel : TRACKED) {
            Path p = Paths.get(root, rel);
            Map<String, String> e = recorded.get(rel);
            if (e == null) {
                failures.add(rel + ": not in the upload record — re-upload and re-record");
                continue;
            }
            if (!Files.isRegularFile(p)) {
                failures.add(rel + ": local file missing, cannot compare");
                continue;
            }
            String now = sha256(p);
            if (!now.equals(e.get("sha256"))) {
                failures.add(rel + ": changed since the recorded upload ("
                        + e.get("uploaded_at") + ") — the notebook holds a STALE copy; "
                        + "re-upload, then --record-upload");
            }
        }
        return failures;
    }

    // ------------------------------------------------------------------
    // CLI
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--doc-code";
        String root = workspaceRoot();
        switch (mode) {
            case "--doc-code": {
                List<String> failures = checkDocCode(codePath(), docPath());
                List<String> infos = List.of(
                        codePath().toString(), docPath().toString(),
                        CLAIMS.size() + " methodology claims checked");
                Verify.report(
                        "METHODOLOGY CHECK OK (doc-code): " + CLAIMS.size()
                                + "/" + CLAIMS.size() + " claims verified, doc matches code.",
                        "METHODOLOGY CHECK FAILED (doc-code): " + failures.size()
                                + " problem(s) — doc and code have drifted. "
                                + "This tool never auto-edits the doc; "
                                + "resolve the direction in DCF Model Lab.",
                        infos, failures);
                break;
            }
            case "--notebook-fresh": {
                List<String> failures = checkFresh(statePath(), root);
                List<String> infos = List.of(
                        "state: " + statePath(),
                        TRACKED.length + " tracked uploads checked");
                Verify.report(
                        "METHODOLOGY CHECK OK (notebook-fresh): " + TRACKED.length
                                + "/" + TRACKED.length
                                + " uploads current — notebook matches local files.",
                        "METHODOLOGY CHECK FAILED (notebook-fresh): "
                                + failures.size() + " problem(s):",
                        infos, failures);
                break;
            }
            case "--record-upload":
                recordUpload(statePath(), root);
                System.out.println("upload state written: " + statePath());
                break;
            case "--self-test":
                selfTest();
                break;
            default:
                System.err.println("usage: --doc-code | --notebook-fresh | --record-upload | --self-test");
                System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Self-test: synthetic fixtures prove the detector fires (and stays
    // quiet) for the right reasons. Uses temp copies — never touches the
    // real code or doc.
    // ------------------------------------------------------------------
    static void selfTest() throws Exception {
        Path tmp = Files.createTempDirectory("mcheck-test");
        String realCode = Files.readString(codePath(), StandardCharsets.UTF_8);
        String realDoc = Files.readString(docPath(), StandardCharsets.UTF_8);

        // 1. Real pair passes.
        Path c1 = tmp.resolve("DcfModel.java");
        Path d1 = tmp.resolve("doc.md");
        Files.writeString(c1, realCode, StandardCharsets.UTF_8);
        Files.writeString(d1, realDoc, StandardCharsets.UTF_8);
        SelfTest.check("real code+doc agree", checkDocCode(c1, d1).isEmpty());

        // 2. Formula changed in code only (9.5 -> 8.5): must FAIL.
        Path c2 = tmp.resolve("DcfModel2.java");
        Files.writeString(c2, realCode.replace("Math.pow(1 + w, 9.5)", "Math.pow(1 + w, 8.5)"),
                StandardCharsets.UTF_8);
        List<String> f2 = checkDocCode(c2, d1);
        SelfTest.check("code-only formula change is caught",
                f2.size() == 1 && f2.get(0).contains("9.5"));

        // 3. Comment-only change: must still PASS (comments don't count).
        Path c3 = tmp.resolve("DcfModel3.java");
        Files.writeString(c3,
                realCode.replace("10-year, mid-year discounting, 5-year growth fade",
                        "12-year, mid-year discounting, 5-year growth fade"),
                StandardCharsets.UTF_8);
        SelfTest.check("comment-only change stays green", checkDocCode(c3, d1).isEmpty());

        // 4. Doc phrase removed: must FAIL.
        Path d4 = tmp.resolve("doc4.md");
        Files.writeString(d4, realDoc.replace("Gordon growth", "Gordon  growth"),
                StandardCharsets.UTF_8);
        List<String> f4 = checkDocCode(c1, d4);
        SelfTest.check("doc phrase removal is caught",
                f4.size() == 1 && f4.get(0).contains("Gordon"));

        // 5. Freshness round-trip on temp files.
        Path st = tmp.resolve("state.json");
        Path f1 = tmp.resolve("a.txt");
        Files.writeString(f1, "v1", StandardCharsets.UTF_8);
        String root = tmp.toString();
        // use a private tracked set via direct calls
        Map<String, Map<String, String>> rec = new LinkedHashMap<>();
        Map<String, String> e = new LinkedHashMap<>();
        e.put("sha256", sha256(f1));
        e.put("uploaded_at", "2026-09-27T00:00:00");
        rec.put("a.txt", e);
        Files.writeString(st, "{\"files\": {\"a.txt\": {\"sha256\": \""
                + e.get("sha256") + "\", \"uploaded_at\": \"2026-09-27T00:00:00\"}}}\n",
                StandardCharsets.UTF_8);
        SelfTest.check("fresh upload verifies",
                checkFreshAgainst(st, root, new String[]{"a.txt"}).isEmpty());
        Files.writeString(f1, "v2 — changed", StandardCharsets.UTF_8);
        List<String> f5 = checkFreshAgainst(st, root, new String[]{"a.txt"});
        SelfTest.check("changed file after upload is caught",
                f5.size() == 1 && f5.get(0).contains("STALE"));

        // 6. Missing state file fails loud (never silently "fresh").
        Path missing = tmp.resolve("nope.json");
        List<String> f6 = checkFreshAgainst(missing, root, new String[]{"a.txt"});
        SelfTest.check("missing upload record fails loud",
                f6.size() == 1 && f6.get(0).contains("no upload record"));

        // 7. Growth cap removed from code: must FAIL.
        Path c7 = tmp.resolve("DcfModel7.java");
        Files.writeString(c7,
                realCode.replace("Math.min(g, MAX_SUSTAINED_GROWTH)", "g"),
                StandardCharsets.UTF_8);
        List<String> f7 = checkDocCode(c7, d1);
        SelfTest.check("growth-cap removal is caught",
                f7.size() == 1 && f7.get(0).contains("15%"));

        // 8. Trough normalization removed from code: must FAIL.
        Path c8 = tmp.resolve("DcfModel8.java");
        Files.writeString(c8,
                realCode.replace("revenue * avgFcfMargin3y", "revenue"),
                StandardCharsets.UTF_8);
        List<String> f8 = checkDocCode(c8, d1);
        SelfTest.check("trough-normalization removal is caught",
                f8.size() == 1 && f8.get(0).contains("trough"));

        // 9. TV warning threshold changed in code only (0.75 -> 0.50): must FAIL.
        Path c9 = tmp.resolve("DcfModel9.java");
        Files.writeString(c9,
                realCode.replace("TV_SHARE_WARN = 0.75", "TV_SHARE_WARN = 0.50"),
                StandardCharsets.UTF_8);
        List<String> f9 = checkDocCode(c9, d1);
        SelfTest.check("TV-warning threshold change is caught",
                f9.size() == 1 && f9.get(0).contains("75%"));

        SelfTest.finish();
    }

    /** Test seam: freshness check over an explicit tracked set. */
    static List<String> checkFreshAgainst(Path state, String root, String[] tracked)
            throws Exception {
        List<String> failures = new ArrayList<>();
        Map<String, Map<String, String>> recorded = readState(state);
        if (recorded.isEmpty()) {
            failures.add("no upload record at " + state + " — run --record-upload first");
            return failures;
        }
        for (String rel : tracked) {
            Path p = Paths.get(root, rel);
            Map<String, String> en = recorded.get(rel);
            if (en == null) {
                failures.add(rel + ": not in the upload record");
                continue;
            }
            if (!sha256(p).equals(en.get("sha256"))) {
                failures.add(rel + ": changed since the recorded upload — the notebook holds a STALE copy");
            }
        }
        return failures;
    }
}
