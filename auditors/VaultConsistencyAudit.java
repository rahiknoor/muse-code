import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

/// Vault Consistency Auditor — Rahik's auditor fleet, auditor #2.
///
/// ONE TASK: check the Atlas vault for internal consistency —
/// (a) valuation tables/notes/briefs via VaultConsistencyCheck,
/// (b) orphan notes with no incoming [[wikilinks]] (archives exempt),
/// (c) hardcoded holding/stock counts vs the watchlist backend.
///
/// READ-ONLY: never edits the vault. Findings end in SUGGESTED actions;
/// a separate approved pass does any implementing.
///
/// Structured finding format (per research takeaway): severity | location |
/// expected vs actual | evidence | suggestion.
public class VaultConsistencyAudit {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path vault = Paths.get(home(), "workspace", "atlas");
        List<String> findings = audit(vault);
        if (findings.isEmpty()) {
            System.out.println("VAULT CONSISTENCY: CLEAN — tables, links and counts all check out.");
        } else {
            System.out.println("VAULT CONSISTENCY: " + findings.size() + " finding(s) — read-only, nothing changed.");
            for (String f : findings) System.out.println("\n---\n" + f);
        }
    }

    static List<String> audit(Path vault) throws Exception {
        List<String> findings = new ArrayList<>();
        findings.addAll(runTableCheck(vault));
        findings.addAll(findOrphans(vault));
        findings.addAll(checkCounts(vault));
        findings.addAll(checkCreatorViewSync(vault.getParent().getParent()));
        return findings;
    }

    /** (a) Delegate to the existing VaultConsistencyCheck verifier. */
    static List<String> runTableCheck(Path vault) {
        List<String> out = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    Paths.get(home(), "workspace", "java", "jdk-21", "bin", "java").toString(),
                    "--class-path", Paths.get(home(), "workspace", "java-shared", "classes").toString(),
                    Paths.get(home(), "workspace", "atlas-scripts", "VaultConsistencyCheck.java").toString());
            // Vault folder names contain emoji — without a UTF-8 locale the verifier
            // crashes with InvalidPathException (observed 2026-10-07).
            pb.environment().put("LC_ALL", "C.UTF-8");
            pb.environment().put("LANG", "C.UTF-8");
            Process p = pb.redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                out.add("[HIGH] valuation tables/notes/briefs | VaultConsistencyCheck FAILED\n"
                        + "  expected: all tables byte-match the JSON backends\n"
                        + "  actual: verifier reported failures\n"
                        + "  evidence:\n" + indent(head(text, 25)) + "\n"
                        + "  suggestion: run VaultConsistencyCheck.java directly, fix each FAIL line, then re-audit.");
            }
        } catch (Exception e) {
            out.add("[HIGH] valuation tables/notes/briefs | verifier could not run\n"
                    + "  evidence: " + firstLine(e.getMessage()) + "\n"
                    + "  suggestion: check that atlas-scripts/VaultConsistencyCheck.java still exists and the JDK path is valid.");
        }
        return out;
    }

    /** (b) Notes with no incoming [[wikilinks]]. Archive folders are exempt. */
    static List<String> findOrphans(Path vault) throws IOException {
        List<Path> notes = listNotes(vault);
        Pattern link = Pattern.compile("\\[\\[([^\\]|#]+)");
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, Path> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Path n : notes) {
            String name = stripExt(n.getFileName().toString());
            byName.putIfAbsent(name, n);
            incoming.putIfAbsent(name.toLowerCase(), 0);
        }
        for (Path n : notes) {
            String text = Files.readString(n);
            Matcher m = link.matcher(text);
            while (m.find()) {
                String target = m.group(1).trim().toLowerCase();
                incoming.merge(target, 1, Integer::sum);
            }
        }
        List<String> orphans = new ArrayList<>();
        for (Path n : notes) {
            String rel = vault.relativize(n).toString();
            if (rel.toLowerCase().contains("archive")) continue; // archives exempt
            String name = stripExt(n.getFileName().toString());
            if (incoming.getOrDefault(name.toLowerCase(), 0) == 0 && !isHubNote(n)) {
                orphans.add(rel);
            }
        }
        List<String> out = new ArrayList<>();
        if (!orphans.isEmpty()) {
            Map<String, Integer> byFolder = new TreeMap<>();
            for (String r : orphans) {
                int i = r.indexOf('/');
                byFolder.merge(i < 0 ? "(root)" : r.substring(0, i), 1, Integer::sum);
            }
            StringBuilder folders = new StringBuilder();
            byFolder.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue()).limit(5)
                    .forEach(e -> folders.append(e.getKey()).append(": ").append(e.getValue()).append("; "));
            out.add("[LOW] orphan notes | " + orphans.size() + " note(s) have no incoming [[wikilinks]] (archives exempt)\n"
                    + "  expected: every live note is reachable from the graph\n"
                    + "  actual: unreachable, by folder — " + folders.toString().strip() + "\n"
                    + "  sample: " + String.join(", ", orphans.subList(0, Math.min(8, orphans.size()))) + "\n"
                    + "  suggestion: link each from its natural hub note (e.g. each ticker's NotebookLM note from its stock note), "
                    + "or move retired ones to an Archive folder. Bulk-linking one folder at a time keeps this tractable.");
        }
        return out;
    }

    /** Hub/index notes link out widely by design — never orphans. */
    static boolean isHubNote(Path n) {
        String name = n.getFileName().toString().toLowerCase();
        return name.contains("index") || name.contains("hub") || name.contains("corner office")
                || name.contains("readme") || name.equals("task & failure ledger.md");
    }

    /** (c) Hardcoded "<N> holdings/stocks" claims vs the live watchlist.json count.
     *  Delegates to HoldingsCountGuard.java (BLG-030) as a subprocess — the
     *  same delegation pattern runTableCheck uses for VaultConsistencyCheck.
     *  The guard owns the scan rules (frontmatter/fenced-code/archive/chat-log/
     *  record-note exclusions, stale band) and its own --self-test; this
     *  method only wires its findings into the audit. Findings are already in
     *  the structured format, so they are embedded verbatim. */
    static List<String> checkCounts(Path vault) {
        List<String> out = new ArrayList<>();
        Path guard = Paths.get(home(), "workspace", "auditors", "HoldingsCountGuard.java");
        Path watchlist = Paths.get(home(), "workspace", "stock_watch", "watchlist.json");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    Paths.get(home(), "workspace", "java", "jdk-21", "bin", "java").toString(),
                    "--class-path", Paths.get(home(), "workspace", "lib", "json", "json-20240303.jar").toString(),
                    guard.toString(), vault.toString(), watchlist.toString());
            // Vault folder names contain emoji — without a UTF-8 locale path
            // handling breaks (observed 2026-10-07).
            pb.environment().put("LC_ALL", "C.UTF-8");
            pb.environment().put("LANG", "C.UTF-8");
            Process p = pb.redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code == 1) {
                out.add(text.strip());
            } else if (code == 2) {
                out.add("[HIGH] holdings-count guard | HoldingsCountGuard could not read watchlist.json\n"
                        + "  evidence: " + firstLine(text) + "\n"
                        + "  suggestion: check workspace/stock_watch/watchlist.json exists and parses as JSON with a tickers array.");
            } else if (code != 0) {
                out.add("[HIGH] holdings-count guard | HoldingsCountGuard exited " + code + "\n"
                        + "  evidence: " + firstLine(text) + "\n"
                        + "  suggestion: run it directly: java --class-path ~/workspace/lib/json/json-20240303.jar ~/workspace/auditors/HoldingsCountGuard.java --self-test");
            }
            // code 0 = CLEAN line, nothing to report.
        } catch (Exception e) {
            out.add("[HIGH] holdings-count guard | could not run HoldingsCountGuard\n"
                    + "  evidence: " + firstLine(e.getMessage()) + "\n"
                    + "  suggestion: check auditors/HoldingsCountGuard.java exists and the JDK path is valid.");
        }
        return out;
    }

    /** Test-only twin of checkCounts with injectable paths (proves the wiring). */
    static List<String> runGuardForTest(Path vault, Path watchlist) throws Exception {
        Path guard = Paths.get(home(), "workspace", "auditors", "HoldingsCountGuard.java");
        ProcessBuilder pb = new ProcessBuilder(
                Paths.get(home(), "workspace", "java", "jdk-21", "bin", "java").toString(),
                "--class-path", Paths.get(home(), "workspace", "lib", "json", "json-20240303.jar").toString(),
                guard.toString(), vault.toString(), watchlist.toString());
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("LANG", "C.UTF-8");
        Process p = pb.redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        List<String> out = new ArrayList<>();
        if (code == 1) out.add(text.strip());
        else if (code != 0) throw new AssertionError("guard exited " + code + ": " + firstLine(text));
        return out;
    }

    // ---- plumbing ----

    /** (d) creator_values.json latest vs creator_calls_by_creator.json view.
     *  RefreshValuations reads the per-creator VIEW, not latest — a call that
     *  lands in latest but never reaches the view leaves vault creator rows
     *  stale (observed 2026-10-08: META Couch Investor $680 missing from view). */
    static List<String> checkCreatorViewSync(Path base) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject cv = new JSONObject(Files.readString(
                    base.resolve("workspace/dcf_csv/creator_values.json")));
            JSONObject latest = cv.getJSONObject("latest");
            JSONObject view = new JSONObject(Files.readString(
                    base.resolve("workspace/creator_tracker/creator_calls_by_creator.json")));
            List<String> missing = new ArrayList<>();
            for (String t : latest.keySet()) {
                JSONObject e = latest.getJSONObject(t);
                String creator = e.optString("creator", "");
                String date = e.optString("call_date", "");
                boolean found = false;
                if (view.has(t)) {
                    JSONArray arr = view.getJSONArray(t);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject ve = arr.getJSONObject(i);
                        if (creator.equals(ve.optString("creator", ""))
                                && date.equals(ve.optString("call_date", ""))) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) missing.add(t + " (" + creator + " " + date + ")");
            }
            if (!missing.isEmpty()) {
                out.add("[MEDIUM] creator view out of sync | " + missing.size()
                        + " ticker(s) in creator_values.json latest have no matching entry in creator_calls_by_creator.json\n"
                        + "  expected: every latest call appears in the per-creator view that RefreshValuations reads\n"
                        + "  actual: missing from view: " + String.join("; ", missing) + "\n"
                        + "  evidence: workspace/dcf_csv/creator_values.json latest vs workspace/creator_tracker/creator_calls_by_creator.json\n"
                        + "  suggestion: backfill the missing entries verbatim from creator_values.json, re-run RefreshValuations, then re-audit.");
            }
        } catch (Exception e) {
            out.add("[HIGH] creator view sync | could not compare creator backends\n"
                    + "  evidence: " + firstLine(e.getMessage()) + "\n"
                    + "  suggestion: check both JSON files exist and parse.");
        }
        return out;
    }

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static List<Path> listNotes(Path vault) throws IOException {
        try (Stream<Path> s = Files.walk(vault)) {
            return s.filter(p -> p.toString().endsWith(".md") && !p.toString().contains("/.git/")).toList();
        }
    }

    static String stripExt(String f) {
        return f.endsWith(".md") ? f.substring(0, f.length() - 3) : f;
    }

    static String head(String s, int n) {
        return s.lines().limit(n).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    static String indent(String s) {
        return s.lines().map(l -> "    " + l).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    // ---- self-test ----

    static void runSelfTest() throws Exception {
        Path vault = Files.createTempDirectory("vaultconsist-test");

        // Fixture: hub links to a, nothing links to b (orphan), archive orphan exempt.
        Files.writeString(vault.resolve("Index.md"), "# Index\n\nSee [[Note A]] and the count: 32 holdings.\n");
        Files.writeString(vault.resolve("Note A.md"), "# A\n\nBody.\n");
        Files.writeString(vault.resolve("Note B.md"), "# B\n\nNobody links here.\n");
        Path arch = vault.resolve("Archive");
        Files.createDirectories(arch);
        Files.writeString(arch.resolve("Old.md"), "# Old\n\nRetired.\n");

        List<String> orphans = findOrphans(vault);
        check(orphans.size() == 1 && orphans.get(0).contains("Note B"),
                "must flag exactly the non-archive orphan, got: " + orphans);

        // GREEN: link B from the hub -> no orphans.
        Files.writeString(vault.resolve("Index.md"), "# Index\n\nSee [[Note A]] and [[Note B]].\n");
        check(findOrphans(vault).isEmpty(), "linked note must not be an orphan");

        // Count check delegates to HoldingsCountGuard: prove the wiring end to end.
        // RED: temp vault claims 31 holdings, temp watchlist has 32 -> one finding.
        Path wv = Files.createTempDirectory("vaultconsist-counts");
        StringBuilder tw = new StringBuilder("{\"tickers\":[");
        for (int i = 0; i < 32; i++) tw.append(i == 0 ? "\"T" + i + "\"" : ",\"T" + i + "\"");
        Path fakeWl = wv.resolve("watchlist.json");
        Files.writeString(fakeWl, tw.append("]}").toString());
        Path stale = wv.resolve("Stale.md");
        Files.writeString(stale, "# S\n\nStill says 31 holdings here.\n");
        Path cleanNote = wv.resolve("Clean.md");
        Files.writeString(cleanNote, "# C\n\nCorrectly says 32 holdings.\n");
        List<String> wired = runGuardForTest(wv, fakeWl);
        check(wired.size() == 1 && wired.get(0).contains("Stale.md:3"),
                "wired guard must flag the stale note with file:line, got: " + wired);
        // GREEN: fix the claim -> quiet.
        Files.writeString(stale, "# S\n\nNow says 32 holdings here.\n");
        check(runGuardForTest(wv, fakeWl).isEmpty(), "wired guard must go quiet when counts agree");

        // Creator view sync: fixture latest has META (in view) and NVDA (missing).
        Path fakeBase = Files.createTempDirectory("creatorsync-test");
        Path dcfDir = fakeBase.resolve("workspace/dcf_csv");
        Files.createDirectories(dcfDir);
        Path ctDir = fakeBase.resolve("workspace/creator_tracker");
        Files.createDirectories(ctDir);
        Files.writeString(dcfDir.resolve("creator_values.json"),
                "{\"latest\":{\"META\":{\"creator\":\"Couch Investor\",\"call_date\":\"2026-10-04\",\"value\":680},"
                + "\"NVDA\":{\"creator\":\"Parkev\",\"call_date\":\"2026-10-01\",\"value\":200}}}");
        Files.writeString(ctDir.resolve("creator_calls_by_creator.json"),
                "{\"META\":[{\"creator\":\"Couch Investor\",\"call_date\":\"2026-10-04\",\"value\":680}]}");
        List<String> sync = checkCreatorViewSync(fakeBase);
        check(sync.size() == 1 && sync.get(0).contains("NVDA"),
                "must flag NVDA missing from view, got: " + sync);
        // GREEN: add NVDA -> clean.
        Files.writeString(ctDir.resolve("creator_calls_by_creator.json"),
                "{\"META\":[{\"creator\":\"Couch Investor\",\"call_date\":\"2026-10-04\",\"value\":680}],"
                + "\"NVDA\":[{\"creator\":\"Parkev\",\"call_date\":\"2026-10-01\",\"value\":200}]}");
        check(checkCreatorViewSync(fakeBase).isEmpty(), "in-sync backends must report clean");

        System.out.println("VaultConsistencyAudit self-test: ALL GREEN (orphan red+green proven, holdings-guard wiring red+green proven, creator-sync red+green proven)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
