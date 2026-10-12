import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Correction ledger (Rahik's order, Idea 1, 2026-09-28).
 *
 * <p>When Rahik corrects the agent, the rule gets logged in AGENTS.md — but
 * nothing verified the behavior actually changed. This ledger closes that
 * loop: each entry records the correction (rule, banned pattern, date,
 * source), and {@code --check} scans the last 7 days of job outcome logs
 * for any recurrence of a banned pattern.
 *
 * <p>Ledger backend: {@code ~/workspace/memory/correction_ledger.json}
 * (protected from RetentionSweep pruning — lessons live forever).
 * {@code pattern_banned} is a Java regex matched case-insensitively
 * against outcome-log lines.
 *
 * <p>Usage:
 * <pre>
 * java CorrectionLedger.java --file --rule "..." --pattern "..." --source "..." [--date YYYY-MM-DD]
 * java CorrectionLedger.java --list
 * java CorrectionLedger.java --check          (report-only; never edits anything)
 * java CorrectionLedger.java --self-test
 * </pre>
 *
 * <p>Launch with: {@code java --class-path ~/workspace/java-shared/classes:~/workspace/lib/json/json-20240303.jar
 * ~/workspace/correction_ledger/CorrectionLedger.java &lt;mode&gt;}
 */
public class CorrectionLedger {

    // HOME comes from the environment, never the user.home property
    // (which returns /root on this box while $HOME is /home/hatch).
    static String HOME;
    static Path LEDGER, OUTCOMES;

    static final Pattern LINE_DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s+\\d{2}:\\d{2}");
    static final Pattern DATE_OK = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    static final int WINDOW_DAYS = 7;

    /** One correction: short rule name, banned behavior as a regex, date, origin. */
    record Entry(String rule, String pattern, String date, String source) {}

    static void initPaths() {
        HOME = System.getenv("HOME");
        if (HOME == null || HOME.isEmpty()) {
            throw new IllegalStateException("$HOME is not set");
        }
        LEDGER = Paths.get(HOME, "workspace", "memory", "correction_ledger.json");
        OUTCOMES = Paths.get(HOME, "workspace", "scheduler_state", "job_outcomes");
    }

    // ------------------------------------------------------------------
    // Ledger I/O
    // ------------------------------------------------------------------

    static List<Entry> readEntries(Path ledgerPath) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.exists(ledgerPath)) {
            return entries;
        }
        String text = Files.readString(ledgerPath, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(text);
        JSONArray arr = root.optJSONArray("entries");
        if (arr == null) {
            return entries;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            entries.add(new Entry(
                    o.optString("rule", ""),
                    o.optString("pattern_banned", ""),
                    o.optString("date", ""),
                    o.optString("source", "")));
        }
        return entries;
    }

    static void writeEntries(Path ledgerPath, List<Entry> entries) throws IOException {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            o.put("rule", e.rule());
            o.put("pattern_banned", e.pattern());
            o.put("date", e.date());
            o.put("source", e.source());
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("entries", arr);
        Files.writeString(ledgerPath, root.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    /** Appends a correction (replaces the entry when the rule name already exists). Returns entry count. */
    static int fileEntry(Path ledgerPath, String rule, String pattern, String date, String source)
            throws IOException {
        List<Entry> entries = readEntries(ledgerPath);
        String d = (date == null || date.isBlank()) ? LocalDate.now().toString() : date;
        Entry e = new Entry(rule, pattern, d, source);
        boolean replaced = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).rule().equals(rule)) {
                entries.set(i, e);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entries.add(e);
        }
        writeEntries(ledgerPath, entries);
        return entries.size();
    }

    static String listEntries(Path ledgerPath) throws IOException {
        List<Entry> entries = readEntries(ledgerPath);
        StringBuilder out = new StringBuilder();
        out.append("== Correction ledger: ").append(entries.size()).append(" entries ==\n");
        int i = 1;
        for (Entry e : entries) {
            out.append(i++).append(". [").append(e.date()).append("] ").append(e.rule()).append("\n");
            out.append("   forbids : ").append(e.pattern()).append("\n");
            out.append("   source  : ").append(e.source()).append("\n");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // --check: scan recent outcome logs for recurrence of banned patterns
    // ------------------------------------------------------------------

    /** Report-only: never edits anything. Returns the full report text. */
    static String checkOn(Path ledgerPath, Path outcomesDir) {
        StringBuilder out = new StringBuilder();
        List<Entry> entries;
        try {
            entries = readEntries(ledgerPath);
        } catch (Exception e) {
            return "== CorrectionLedger --check ==\nLEDGER ERROR: cannot read "
                    + ledgerPath + ": " + e.getMessage() + "\n";
        }
        out.append("== CorrectionLedger --check (last ").append(WINDOW_DAYS)
                .append(" days of ").append(outcomesDir).append(") ==\n");

        Map<String, Pattern> compiled = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Entry e : entries) {
            try {
                compiled.put(e.rule(), Pattern.compile(e.pattern(), Pattern.CASE_INSENSITIVE));
            } catch (Exception ex) {
                warnings.add("WARNING: bad pattern for [" + e.rule() + "]: " + ex.getMessage());
            }
        }

        LocalDate cutoff = LocalDate.now().minusDays(WINDOW_DAYS);
        int files = 0, lines = 0, skipped = 0, hits = 0;
        List<String> hitLines = new ArrayList<>();
        if (Files.isDirectory(outcomesDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(outcomesDir, "*.log")) {
                for (Path log : ds) {
                    files++;
                    List<String> all;
                    try {
                        all = Files.readAllLines(log, StandardCharsets.UTF_8);
                    } catch (IOException ioe) {
                        warnings.add("WARNING: cannot read " + log.getFileName());
                        continue;
                    }
                    for (String line : all) {
                        Matcher m = LINE_DATE.matcher(line);
                        if (!m.find()) {
                            skipped++;
                            continue;
                        }
                        LocalDate d;
                        try {
                            d = LocalDate.parse(m.group(1));
                        } catch (Exception ex) {
                            skipped++;
                            continue;
                        }
                        if (d.isBefore(cutoff)) {
                            continue;
                        }
                        lines++;
                        for (Map.Entry<String, Pattern> pe : compiled.entrySet()) {
                            Matcher pm = pe.getValue().matcher(line);
                            if (pm.find()) {
                                hits++;
                                String frag = pm.group();
                                if (frag.length() > 80) {
                                    frag = frag.substring(0, 80) + "...";
                                }
                                hitLines.add("HIT [" + pe.getKey() + "] "
                                        + line.trim() + "  <<match: \"" + frag + "\">>");
                            }
                        }
                    }
                }
            } catch (IOException ioe) {
                warnings.add("WARNING: cannot list " + outcomesDir);
            }
        } else {
            warnings.add("WARNING: outcomes dir missing: " + outcomesDir);
        }

        for (String w : warnings) {
            out.append(w).append('\n');
        }
        out.append("Scanned ").append(files).append(" log files, ").append(lines)
                .append(" dated lines in window (").append(skipped).append(" undated skipped).\n");
        if (hits == 0) {
            out.append("CLEAN — no recurrence of any banned pattern in the last ")
                    .append(WINDOW_DAYS).append(" days.\n");
        } else {
            out.append(hits).append(" RECURRENCE HIT(S):\n");
            for (String h : hitLines) {
                out.append(h).append('\n');
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // --self-test
    // ------------------------------------------------------------------

    static void selfTest() throws Exception {
        Path tmp = Files.createTempDirectory("correction-ledger-test");

        // 1. file/list round-trip on a throwaway ledger
        Path ledger = tmp.resolve("ledger.json");
        SelfTest.checkEq("missing ledger reads as empty", 0, readEntries(ledger).size());
        int n = fileEntry(ledger, "test rule", "(?i)test-violation-xyz", "2026-09-28", "self-test");
        SelfTest.checkEq("file: first entry counted", 1, n);
        List<Entry> back = readEntries(ledger);
        SelfTest.checkEq("round-trip: one entry", 1, back.size());
        SelfTest.checkEq("round-trip rule", "test rule", back.get(0).rule());
        SelfTest.checkEq("round-trip pattern", "(?i)test-violation-xyz", back.get(0).pattern());
        SelfTest.checkEq("round-trip date", "2026-09-28", back.get(0).date());
        SelfTest.checkEq("round-trip source", "self-test", back.get(0).source());
        // same rule name replaces instead of duplicating
        int n2 = fileEntry(ledger, "test rule", "(?i)new-pattern", "2026-09-28", "self-test");
        SelfTest.checkEq("re-file same rule: no duplicate", 1, n2);
        SelfTest.checkEq("re-file same rule: pattern updated",
                "(?i)new-pattern", readEntries(ledger).get(0).pattern());
        // default date = today
        fileEntry(ledger, "dated rule", "(?i)xyz", null, "self-test");
        SelfTest.checkEq("default date is today",
                LocalDate.now().toString(), readEntries(ledger).get(1).date());

        // 2. --check detects a seeded synthetic violation
        Path outcomes = tmp.resolve("outcomes");
        Files.createDirectory(outcomes);
        String today = LocalDate.now().toString();
        fileEntry(ledger, "audit write guard",
                "(?i)(silent(ly)? (re-?writ(e|es|en|ing)|rewrote|updat(e|ed|ing)|chang(e|ed|ing))"
                        + "|drift without (an )?(approved )?proposal)",
                "2026-09-27", "self-test");
        Files.writeString(outcomes.resolve("test-job.log"),
                today + " 07:00 ET | test-job | run | outcome=succeeded | delivered=no"
                        + " | notes=silently rewrote blessed without a proposal\n"
                        + today + " 08:00 ET | test-job | run | outcome=succeeded | delivered=no"
                        + " | notes=routine, nothing moved\n",
                StandardCharsets.UTF_8);
        String hitReport = checkOn(ledger, outcomes);
        SelfTest.checkContains("check detects synthetic violation (rule named)",
                hitReport, "audit write guard");
        SelfTest.checkContains("check detects synthetic violation (line shown)",
                hitReport, "silently rewrote");
        SelfTest.checkNotContains("violation report is not CLEAN", hitReport, "CLEAN");

        // 3. --check is quiet on clean logs
        Path cleanDir = tmp.resolve("clean");
        Files.createDirectory(cleanDir);
        Files.writeString(cleanDir.resolve("quiet.log"),
                today + " 09:00 ET | quiet-job | run | outcome=succeeded | delivered=no"
                        + " | notes=all green, nothing moved\n",
                StandardCharsets.UTF_8);
        String cleanReport = checkOn(ledger, cleanDir);
        SelfTest.checkContains("check quiet on clean logs", cleanReport, "CLEAN");

        // 4. lines older than the window are ignored
        Path oldDir = tmp.resolve("old");
        Files.createDirectory(oldDir);
        Files.writeString(oldDir.resolve("old.log"),
                "2020-01-01 09:00 ET | old-job | run | outcome=succeeded | delivered=no"
                        + " | notes=silently rewrote blessed without a proposal\n",
                StandardCharsets.UTF_8);
        String oldReport = checkOn(ledger, oldDir);
        SelfTest.checkContains("old logs excluded from scan", oldReport, "CLEAN");
        SelfTest.checkNotContains("old violation not flagged", oldReport, "silently rewrote");

        // 5. a bad pattern warns, never crashes the check
        Path badDir = tmp.resolve("bad");
        Files.createDirectory(badDir);
        Files.writeString(badDir.resolve("x.log"),
                today + " 09:00 ET | x | run | outcome=succeeded | delivered=no | notes=ok\n",
                StandardCharsets.UTF_8);
        fileEntry(ledger, "bad pattern rule", "[unclosed(", today, "self-test");
        String badReport = checkOn(ledger, badDir);
        SelfTest.checkContains("bad pattern reports WARNING", badReport, "WARNING");
        SelfTest.checkContains("bad pattern still scans", badReport, "Scanned");

        // 6. the seeded production ledger is intact
        List<Entry> seeded = readEntries(LEDGER);
        SelfTest.check("seeded ledger has 10+ entries", seeded.size() >= 10);
        boolean allFields = true, allPatterns = true, allDates = true;
        for (Entry e : seeded) {
            if (e.rule().isBlank() || e.pattern().isBlank()
                    || e.date().isBlank() || e.source().isBlank()) {
                allFields = false;
            }
            try {
                Pattern.compile(e.pattern(), Pattern.CASE_INSENSITIVE);
            } catch (Exception ex) {
                allPatterns = false;
            }
            if (!DATE_OK.matcher(e.date()).matches()) {
                allDates = false;
            }
        }
        SelfTest.check("seeded entries have all four fields", allFields);
        SelfTest.check("seeded patterns all compile", allPatterns);
        SelfTest.check("seeded dates are YYYY-MM-DD", allDates);

        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // CLI
    // ------------------------------------------------------------------

    static void die(String msg) {
        System.err.println("ERROR: " + msg);
        System.err.println("Usage:");
        System.err.println("  java CorrectionLedger.java --file --rule \"...\" --pattern \"...\" --source \"...\" [--date YYYY-MM-DD]");
        System.err.println("  java CorrectionLedger.java --list");
        System.err.println("  java CorrectionLedger.java --check");
        System.err.println("  java CorrectionLedger.java --self-test");
        System.exit(2);
    }

    public static void main(String[] args) throws Exception {
        initPaths();
        String mode = null;
        Map<String, String> kv = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--file") || a.equals("--list")
                    || a.equals("--check") || a.equals("--self-test")) {
                mode = a;
            } else if (a.equals("--rule") || a.equals("--pattern")
                    || a.equals("--source") || a.equals("--date")) {
                if (i + 1 >= args.length) {
                    die("missing value for " + a);
                }
                kv.put(a.substring(2), args[++i]);
            } else {
                die("unknown arg: " + a);
            }
        }
        if (mode == null) {
            die("no mode given");
        }
        switch (mode) {
            case "--self-test" -> selfTest();
            case "--file" -> {
                String rule = kv.get("rule"), pattern = kv.get("pattern"), source = kv.get("source");
                if (rule == null || rule.isBlank()) {
                    die("--file needs --rule");
                }
                if (pattern == null || pattern.isBlank()) {
                    die("--file needs --pattern");
                }
                if (source == null || source.isBlank()) {
                    die("--file needs --source");
                }
                String date = kv.getOrDefault("date", null);
                try {
                    Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                } catch (Exception ex) {
                    die("pattern is not a valid regex: " + ex.getMessage());
                }
                int count = fileEntry(LEDGER, rule, pattern, date, source);
                String used = (date == null || date.isBlank()) ? LocalDate.now().toString() : date;
                System.out.println("Filed: " + rule + " (" + used + ") — ledger now holds "
                        + count + " entr" + (count == 1 ? "y" : "ies") + ".");
            }
            case "--list" -> System.out.print(listEntries(LEDGER));
            case "--check" -> System.out.print(checkOn(LEDGER, OUTCOMES));
            default -> die("no mode given");
        }
    }
}
