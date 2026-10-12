// PropagationCheck.java — the "did it actually go everywhere?" enforcer.
//
// Rahik 2026-09-28: values moved in a rebuild but the Atlas zip, NotebookLM
// sync, and comparison artifact were never refreshed — and nobody noticed
// until he asked. The standing "every build propagates everywhere" rule is
// worthless if it depends on remembering.
//
// FAIL-CLOSED (hard-wired 2026-09-28, Rahik: "hard write it on your code"):
// this check exits 1 whenever anything is PENDING, and RebuildAll runs it as
// a hard gate — a rebuild that leaves a consumer stale FAILS, it does not
// "complete with notes". There is no advisory mode. What can be fixed safely
// is fixed in-process (the Atlas zip); what needs the agent (browser upload,
// artifact edit, chat post) is reported as PENDING with the exact action,
// and the run is NOT done until this exits 0.
//
// Legs:
//   1. atlas_zip          — atlas-vault.zip must be newer than the Atlas notes
//                           it ships. Stale → rebuilt here, reported FIXED.
//   2. notebooklm         — the two files NotebookLM reads (methodology doc +
//                           workbook PDF) must match the recorded upload
//                           (SHA-256). Checked via MethodologyCheck
//                           --notebook-fresh. Stale → PENDING: run the browser
//                           sync task, then MethodologyCheck --record-upload.
//   3. comparison_artifact— the DCF Comparison web artifact embeds values at
//                           build time; nothing on disk can prove it fresh,
//                           so the agent stamps .propagation_state.json after
//                           each artifact.edit that carries new values.
//                           values_fp mismatch → PENDING.
//   4. dcf_files_post     — after a rebuild that MOVED values, the fresh
//                           workbook + comparison spreadsheet + rebuild_diff.md
//                           must be posted to the DCF Files side chat, then
//                           stamped. Skipped when the diff says nothing moved.
//   5. atlas_consistency  — every stock note and research brief must match
//                           the JSON backends byte-for-byte (VaultConsistency
//                           Check.java, fail-closed). Any mismatch → FAIL:
//                           run RefreshValuations.java + StampResearchBriefs
//                           .java, then re-run this check. A green writer
//                           that never wrote (2026-09-30: the stamper's
//                           shadow proposed 10 fixes, the real run never
//                           executed) is caught here, not trusted.
//
// Modes:
//   (no args)        verify all legs, auto-fix the zip, print the report.
//   --stamp <leg>    record leg 3/4 as done for the current values.
//   --self-test      shared-harness checks.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

public class PropagationCheck {

    static String ROOT = workspaceRoot();
    static final String STATE = ".propagation_state.json";

    static String workspaceRoot() {
        String h = System.getenv("HOME");
        if (h == null || h.isEmpty()) h = System.getProperty("user.home");
        return h + "/workspace";
    }

    static String javaBin() {
        return System.getProperty("java.home") + "/bin/java";
    }

    // ------------------------------------------------------------------
    // values fingerprint: SHA-256 over sorted "TICKER=pubDcf" lines from
    // .rebuild_diff_state.json (the just-finished rebuild's published set).
    // ------------------------------------------------------------------
    static String valuesFp() throws Exception {
        Path p = Paths.get(ROOT, "dcf_csv/.rebuild_diff_state.json");
        String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        // "NVDA": {"pubDcf": 117.64, ...
        Matcher m = Pattern.compile("\"([A-Z0-9.]+)\":\\s*\\{\\s*\"pubDcf\":\\s*([^,}]+)")
                .matcher(t);
        TreeMap<String, String> vals = new TreeMap<>();
        while (m.find()) vals.put(m.group(1), m.group(2).trim());
        if (vals.isEmpty()) throw new IllegalStateException(
                "no pubDcf values parsed from " + p);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : vals.entrySet())
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    // ------------------------------------------------------------------
    // .propagation_state.json — {"leg": {"values_fp": "...", "at": "..."}}
    // ------------------------------------------------------------------
    static Map<String, String> readStamps() throws Exception {
        Path p = Paths.get(ROOT, "dcf_csv/" + STATE);
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(p)) return out;
        String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"(comparison_artifact|dcf_files_post)\"\\s*:\\s*\\{[^}]*\"values_fp\"\\s*:\\s*\"([0-9a-f]+)\"")
                .matcher(t);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    static void writeStamps(Map<String, String> stamps) throws Exception {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, String> e : stamps.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append(" \"").append(e.getKey()).append("\": {\"values_fp\": \"")
              .append(e.getValue()).append("\", \"at\": \"")
              .append(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
              .append("\"}");
        }
        sb.append("\n}\n");
        Files.writeString(Paths.get(ROOT, "dcf_csv/" + STATE), sb.toString(),
                StandardCharsets.UTF_8);
    }

    static void stamp(String leg) throws Exception {
        if (!leg.equals("comparison_artifact") && !leg.equals("dcf_files_post"))
            throw new IllegalArgumentException("stampable legs: comparison_artifact, dcf_files_post");
        Map<String, String> s = readStamps();
        s.put(leg, valuesFp());
        writeStamps(s);
        System.out.println("stamped " + leg + " for current values_fp");
    }

    // ------------------------------------------------------------------
    // Leg 1: atlas-vault.zip. Stale (older than the notes or our_dcf.json)
    // → rebuild in-process with the blessed flags (.obsidian in, .git out).
    // ------------------------------------------------------------------
    static String checkZip(List<String> pending) throws Exception {
        Path zip = Paths.get(ROOT, "your_files/atlas-vault.zip");
        Path notes = Paths.get(ROOT, "atlas");
        Path ours = Paths.get(ROOT, "dcf_csv/our_dcf.json");
        long zipT = Files.isRegularFile(zip)
                ? Files.getLastModifiedTime(zip).toMillis() : 0;
        long notesT = Files.walk(notes).filter(Files::isRegularFile)
                .mapToLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); }
                    catch (IOException e) { return 0; } }).max().orElse(0);
        long oursT = Files.isRegularFile(ours)
                ? Files.getLastModifiedTime(ours).toMillis() : 0;
        if (zipT >= notesT && zipT >= oursT)
            return "OK   atlas_zip: current";
        // Auto-fix: rebuild the zip right here.
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "cd " + quote(ROOT + "/atlas")
                + " && zip -qr ../your_files/atlas-vault.zip . -x \".git/*\"");
        pb.redirectErrorStream(true);
        Process pr = pb.start();
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = pr.waitFor();
        if (rc != 0) {
            pending.add("atlas_zip: rebuild FAILED (rc=" + rc + "): " + out.trim()
                    + " — rebuild the zip manually before reporting done");
            return "FAIL atlas_zip: rebuild failed";
        }
        return "FIXED atlas_zip: was stale, rebuilt";
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------
    // Leg 2: NotebookLM freshness via MethodologyCheck --notebook-fresh.
    // ------------------------------------------------------------------
    static String checkNotebook(List<String> pending) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(javaBin(),
                "--class-path", System.getenv("HOME") + "/workspace/java-shared/classes",
                ROOT + "/dcf_csv/MethodologyCheck.java", "--notebook-fresh");
        // Same UTF-8 requirement as the atlas leg (2026-09-30 fix).
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("LANG", "C.UTF-8");
        pb.redirectErrorStream(true);
        Process pr = pb.start();
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = pr.waitFor();
        if (rc == 0) return "OK   notebooklm: uploaded copy matches local files";
        pending.add("notebooklm: STALE — " + out.trim().replace("\n", " | ")
                + " — spawn the browser sync task for 'DCF Methodology Check', then run MethodologyCheck.java --record-upload");
        return "PENDING notebooklm: re-upload required";
    }

    // ------------------------------------------------------------------
    // Leg 5: atlas_consistency — the read-only audit (VaultConsistencyCheck)
    // over every stock note + research brief. Fail-closed: any mismatch is
    // a PENDING leg and a nonzero exit, never a warning.
    // ------------------------------------------------------------------
    static String checkAtlasConsistency(List<String> pending) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(javaBin(),
                "--class-path", System.getenv("HOME") + "/workspace/java-shared/classes",
                ROOT + "/atlas-scripts/VaultConsistencyCheck.java");
        // Vault paths carry emoji (01 📈 Stocks); the JVM throws
        // InvalidPathException under a C locale. Always UTF-8 the child.
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("LANG", "C.UTF-8");
        pb.redirectErrorStream(true);
        Process pr = pb.start();
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = pr.waitFor();
        String summary = "vault verifier gave no summary";
        for (String line : out.split("\n")) {
            if (line.startsWith("atlas consistency: ")) {
                summary = line;
                break;
            }
        }
        if (rc == 0) return "OK   atlas_consistency: " + summary;
        List<String> fails = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.startsWith("FAIL ")) fails.add(line.substring(5));
        }
        String first = fails.isEmpty() ? out.trim().replace("\n", " | ") : fails.get(0);
        if (first.length() > 220) first = first.substring(0, 220) + "…";
        pending.add("atlas_consistency: " + fails.size() + " vault mismatch(es) — "
                + "run RefreshValuations.java and StampResearchBriefs.java, then re-run "
                + "this check. First: " + first);
        return "FAIL atlas_consistency: " + fails.size() + " mismatch(es)";
    }

    // ------------------------------------------------------------------
    // Legs 3+4: stamp-based (nothing on disk proves the artifact/chat).
    // ------------------------------------------------------------------
    static boolean valuesMoved() throws Exception {
        Path diff = Paths.get(ROOT, "dcf_csv/rebuild_diff.md");
        if (!Files.isRegularFile(diff)) return true; // unknown → require the post
        String t = new String(Files.readAllBytes(diff), StandardCharsets.UTF_8);
        return !t.contains("No values moved");
    }

    static String checkStamp(String leg, String label, String action,
                             Map<String, String> stamps, String fp,
                             List<String> pending) {
        if (fp.equals(stamps.get(leg))) return "OK   " + label + ": in sync";
        pending.add(label + ": STALE — " + action);
        return "PENDING " + label + ": needs sync";
    }

    /**
     * Returns the number of PENDING legs. Zero means every consumer is
     * current. main() turns this into the process exit code, so callers
     * (RebuildAll's gate, workers, cron verifiers) get a machine-readable
     * pass/fail: exit 0 = done, exit 1 = NOT done.
     */
    public static int runCheck() throws Exception {
        List<String> pending = new ArrayList<>();
        List<String> report = new ArrayList<>();
        String fp = valuesFp();
        Map<String, String> stamps = readStamps();

        report.add(checkZip(pending));
        report.add(checkNotebook(pending));
        report.add(checkAtlasConsistency(pending));
        report.add(checkStamp("comparison_artifact", "comparison_artifact",
                "run artifact.edit carrying the current our_dcf.json values, then: java PropagationCheck.java --stamp comparison_artifact",
                stamps, fp, pending));
        if (valuesMoved()) {
            report.add(checkStamp("dcf_files_post", "dcf_files_post",
                    "post the fresh workbook + comparison xlsx + rebuild_diff.md to the DCF Files side chat, then: java PropagationCheck.java --stamp dcf_files_post",
                    stamps, fp, pending));
        } else {
            report.add("SKIP  dcf_files_post: no values moved this rebuild");
        }

        System.out.println("== propagation check ==");
        for (String r : report) System.out.println("  " + r);
        if (pending.isEmpty()) {
            System.out.println("PROPAGATION OK — every consumer is current");
        } else {
            System.out.println("PROPAGATION PENDING (" + pending.size() + ") — "
                    + "the rebuild is NOT done until these are clear:");
            for (String p : pending) System.out.println("  !! " + p);
        }
        return pending.size();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--stamp")) { stamp(args[1]); return; }
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        int pending = runCheck();
        // Fail-closed: a stale consumer is a failed run, not a warning.
        System.exit(pending == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    static void selfTest() throws Exception {
        // Uses the shared harness (SelfTest.java) per workspace convention.
        Class<?> st = Class.forName("SelfTest");
        java.lang.reflect.Method check = st.getMethod("check", String.class, boolean.class);
        java.lang.reflect.Method finish = st.getMethod("finish");
        String fp1 = valuesFp(), fp2 = valuesFp();
        check.invoke(null, "valuesFp stable, 64-hex", fp1.equals(fp2) && fp1.length() == 64);
        // stamp round-trip on a scratch copy
        Map<String, String> s = readStamps();
        check.invoke(null, "readStamps never null", s != null);
        check.invoke(null, "valuesMoved agrees with rebuild_diff.md", valuesMoved() == !new String(
                Files.readAllBytes(Paths.get(ROOT, "dcf_csv/rebuild_diff.md")),
                StandardCharsets.UTF_8).contains("No values moved"));
        check.invoke(null, "atlas-vault.zip exists",
                Files.isRegularFile(Paths.get(ROOT, "your_files/atlas-vault.zip")));
        // Leg 5 contract: a verdict string, and the pending list grows if and
        // only if the verdict is FAIL (fail-closed wiring, not advisory).
        java.lang.reflect.Method leg5 = PropagationCheck.class.getDeclaredMethod(
                "checkAtlasConsistency", List.class);
        leg5.setAccessible(true);
        List<String> p5 = new ArrayList<>();
        String r5 = (String) leg5.invoke(null, p5);
        check.invoke(null, "atlas_consistency returns a verdict",
                r5.startsWith("OK   atlas_consistency") || r5.startsWith("FAIL atlas_consistency"));
        check.invoke(null, "atlas_consistency pending iff FAIL",
                r5.startsWith("FAIL") == !p5.isEmpty());
        // Fail-closed contract (Rahik 2026-09-28): the int runCheck returns
        // must equal the PENDING count main() turns into the exit code.
        // Capture stdout and verify the printed count matches the return.
        PrintStream origOut = System.out;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bos, true, StandardCharsets.UTF_8));
        int n;
        try {
            n = runCheck();
        } finally {
            System.setOut(origOut);
        }
        String printed = bos.toString(StandardCharsets.UTF_8);
        check.invoke(null, "runCheck returns pending count >= 0", n >= 0);
        boolean contractOk = n == 0
                ? printed.contains("PROPAGATION OK")
                : printed.contains("PROPAGATION PENDING (" + n + ")");
        check.invoke(null, "printed PENDING count == returned count (exit-code contract)",
                contractOk);
        finish.invoke(null);
    }
}
