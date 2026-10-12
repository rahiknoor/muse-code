// AgentBudget.java — BLG-022 (2026-10-05).
//
// WHY: the AGENTS.md frugality rule ("batch subagent work, don't spray
// agents") is not self-enforcing — e.g. the Nursery Rhyme AI Studio chat
// spawned 34 subagents in 24h (~131M tokens) unnoticed. This helper tracks
// agent spawns per chat tree per day in a durable state file; past a
// threshold the spawning agent gets a nudge to batch sequentially instead.
// Not a hard block — a tap on the shoulder.
//
// STATE: ~/workspace/scheduler_state/agent_budget.json, date-keyed:
//   {"2026-10-05":{"nursery-rhyme-studio":34}}
// Entries older than 14 days are pruned on every write (keeps the file small).
//
// CLI:
//   --record <tree-id> [--threshold N]   increment today's count, print count
//   --check  <tree-id> [--threshold N]   print count/threshold/nudge=yes|no;
//                                        exit 0 under threshold, exit 2 at/over
//   --self-test                          prove-can-fail suite
// Default threshold 10/day, overridable via --threshold N.
//
// Spawn-time protocol (see /tmp/blg022_rule.md — draft rule for AGENTS.md):
// run --check BEFORE spawning; if it nudges, batch into fewer agents;
// ALWAYS --record after spawning.
//
// Launch (shared helpers on the class path):
//   ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/AgentBudget.java --check <tree-id>
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class AgentBudget {

    /** UTF-8 output; non-final so --self-test can swap in a capturer. */
    static PrintStream OUT =
            new PrintStream(System.out, true, StandardCharsets.UTF_8);

    static final String REL_STATE = "scheduler_state/agent_budget.json";
    static final int DEFAULT_THRESHOLD = 10;
    static final int RETENTION_DAYS = 14;
    /** Tree ids are short slugs (a chat id or "nursery-rhyme-studio"). */
    static final Pattern TREE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    // ---- test seams (self-test only; production leaves both null) ----
    static String wsOverride = null;
    static LocalDate todayOverride = null;

    private AgentBudget() {
        // static-only
    }

    static Path workspace() {
        return wsOverride != null ? Path.of(wsOverride)
                : Path.of(System.getenv("HOME"), "workspace");
    }

    static Path statePath() {
        return workspace().resolve(REL_STATE);
    }

    static LocalDate today() {
        return todayOverride != null ? todayOverride : LocalDate.now();
    }

    // ------------------------------------------------------------------
    // State: date -> tree -> count

    /** Loads the state file; missing file = empty state. Throws (loud, not
     *  silent) when the file exists but is unreadable or corrupt — a
     *  silently-zeroed budget would hide real overspend. */
    @SuppressWarnings("unchecked")
    static TreeMap<String, TreeMap<String, Integer>> load(Path file)
            throws IOException {
        TreeMap<String, TreeMap<String, Integer>> state = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return state;
        }
        String text =
                Files.readString(file, StandardCharsets.UTF_8);
        Map<String, Object> root;
        try {
            root = Blessed.Json.parse(text);
        } catch (Exception e) {
            throw new IOException("corrupt state file " + file
                    + " (" + e.getMessage() + ")", e);
        }
        for (Map.Entry<String, Object> dateEntry : root.entrySet()) {
            String date = dateEntry.getKey();
            if (!(dateEntry.getValue() instanceof Map)) {
                continue; // not our shape: ignore, never crash
            }
            TreeMap<String, Integer> trees = new TreeMap<>();
            for (Map.Entry<String, Object> treeEntry :
                    ((Map<String, Object>) dateEntry.getValue()).entrySet()) {
                if (treeEntry.getValue() instanceof Number n) {
                    int c = Math.max(0, n.intValue());
                    trees.put(treeEntry.getKey(), c);
                }
            }
            state.put(date, trees);
        }
        return state;
    }

    /** Prunes dates older than RETENTION_DAYS and writes the state file.
     *  Keys are ISO dates and slug-validated tree ids, so no escaping is
     *  needed — the shape is fully owned by this tool. */
    static void save(Path file,
                     TreeMap<String, TreeMap<String, Integer>> state)
            throws IOException {
        LocalDate cutoff = today().minusDays(RETENTION_DAYS);
        StringBuilder b = new StringBuilder("{");
        boolean firstDate = true;
        for (Map.Entry<String, TreeMap<String, Integer>> dateEntry :
                state.entrySet()) {
            LocalDate d;
            try {
                d = LocalDate.parse(dateEntry.getKey());
            } catch (Exception ignored) {
                continue; // not our key: drop, never crash
            }
            if (d.isBefore(cutoff)) {
                continue; // older than 14 days: pruned
            }
            if (!firstDate) {
                b.append(',');
            }
            firstDate = false;
            b.append('"').append(dateEntry.getKey()).append("\":{");
            boolean firstTree = true;
            for (Map.Entry<String, Integer> treeEntry :
                    dateEntry.getValue().entrySet()) {
                if (!firstTree) {
                    b.append(',');
                }
                firstTree = false;
                b.append('"').append(treeEntry.getKey()).append("\":")
                        .append(treeEntry.getValue());
            }
            b.append('}');
        }
        b.append('}');
        Files.createDirectories(file.getParent());
        Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
    }

    static int countFor(TreeMap<String, TreeMap<String, Integer>> state,
                        String date, String tree) {
        TreeMap<String, Integer> trees = state.get(date);
        return trees == null ? 0 : trees.getOrDefault(tree, 0);
    }

    // ------------------------------------------------------------------
    // CLI

    static void usage() {
        OUT.println("Usage:");
        OUT.println("  AgentBudget.java --record <tree-id> [--threshold N]");
        OUT.println("  AgentBudget.java --check  <tree-id> [--threshold N]");
        OUT.println("  AgentBudget.java --self-test");
        OUT.println("tree-id: short slug for the chat tree, e.g. "
                + "\"nursery-rhyme-studio\" (pattern " + TREE_ID + ")");
        OUT.println("--check exit codes: 0 under threshold, "
                + "2 at/over (nudge: batch sequentially)");
    }

    /**
     * Returns the process exit code (main exits on it). Never System.exits
     * itself, so --self-test can drive the same path.
     */
    static int run(String[] args) {
        boolean selfTest = false;
        boolean record = false;
        boolean check = false;
        String tree = null;
        int threshold = DEFAULT_THRESHOLD;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--self-test" -> selfTest = true;
                case "--record" -> {
                    if (record || check) {
                        usage();
                        return 2;
                    }
                    record = true;
                    if (i + 1 < args.length) {
                        tree = args[++i];
                    }
                }
                case "--check" -> {
                    if (record || check) {
                        usage();
                        return 2;
                    }
                    check = true;
                    if (i + 1 < args.length) {
                        tree = args[++i];
                    }
                }
                case "--threshold" -> {
                    if (i + 1 >= args.length) {
                        usage();
                        return 2;
                    }
                    try {
                        threshold = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        usage();
                        return 2;
                    }
                    if (threshold < 1) {
                        OUT.println("AgentBudget: --threshold needs N >= 1");
                        return 2;
                    }
                }
                default -> {
                    usage();
                    return 2;
                }
            }
        }
        if (selfTest && (record || check || tree != null)) {
            usage();
            return 2;
        }
        if (selfTest) {
            try {
                selfTest();
            } catch (IOException e) {
                OUT.println("self-test runner error: " + e);
                return 1;
            }
            return 0; // unreachable (SelfTest.finish exits) — clarity only
        }
        if (!record && !check) {
            usage();
            return 2;
        }
        if (tree == null || tree.isBlank()) {
            usage();
            return 2;
        }
        if (!TREE_ID.matcher(tree).matches()) {
            OUT.println("AgentBudget: bad tree id (want slug matching "
                    + TREE_ID + ")");
            return 2;
        }
        Path file = statePath();
        TreeMap<String, TreeMap<String, Integer>> state;
        try {
            state = load(file);
        } catch (IOException e) {
            OUT.println("FAIL: " + e.getMessage());
            return 1;
        }
        String date = today().toString();
        if (record) {
            int n = countFor(state, date, tree) + 1;
            state.computeIfAbsent(date, k -> new TreeMap<>())
                    .put(tree, n);
            try {
                save(file, state);
            } catch (IOException e) {
                OUT.println("FAIL: " + e.getMessage());
                return 1;
            }
            OUT.println("tree=" + tree + " date=" + date + " count=" + n);
            return 0;
        }
        int n = countFor(state, date, tree);
        boolean nudge = n >= threshold;
        OUT.println("tree=" + tree + " date=" + date + " count=" + n
                + " threshold=" + threshold
                + " nudge=" + (nudge ? "yes" : "no"));
        if (nudge) {
            OUT.println("nudge: " + n + " subagents spawned today in tree '"
                    + tree + "' (threshold " + threshold
                    + "/day) — batch remaining work into fewer agents, "
                    + "run them sequentially instead of fanning out.");
            GuardFiring.log("agent-budget", "tree=" + tree + " n=" + n
                    + " threshold=" + threshold);
        }
        return nudge ? 2 : 0;
    }

    // ------------------------------------------------------------------
    // --self-test (prove-can-fail: the red paths below are permanent
    // negative controls — the runner must fail LOUD on each of them)

    /** Runs run() with OUT swapped for a capturer; single-threaded only. */
    static String capture(String[] args, int[] exitOut) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream capture =
                new PrintStream(buf, true, StandardCharsets.UTF_8);
        PrintStream saved = OUT;
        OUT = capture;
        try {
            exitOut[0] = run(args);
        } finally {
            OUT = saved;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    static void selfTest() throws IOException {
        GuardFiring.setEnabled(false); // firing paths must not pollute the real log
        Path ws = Files.createTempDirectory("agentbudget");
        wsOverride = ws.toString();
        todayOverride = LocalDate.of(2026, 10, 5);
        Path file = statePath();
        int[] exit = new int[1];

        // 1. --check on a missing state file: count 0, no nudge, exit 0.
        String out = capture(new String[]{"--check", "nursery-rhyme-studio"},
                exit);
        SelfTest.check("missing state: exit 0", exit[0] == 0);
        SelfTest.checkOutput("missing state: count=0 nudge=no", out,
                new String[]{"count=0", "threshold=10", "nudge=no"},
                new String[]{"nudge=yes"});

        // 2. --record increments: 1, 2, 3.
        for (int i = 1; i <= 3; i++) {
            out = capture(new String[]{"--record", "nursery-rhyme-studio"},
                    exit);
            SelfTest.checkEq("record #" + i + " exits 0", 0, exit[0]);
            SelfTest.checkContains("record #" + i + " prints count=" + i,
                    out, "count=" + i);
        }
        SelfTest.check("state file written",
                Files.isRegularFile(file));
        SelfTest.check("state file is date-keyed",
                Files.readString(file, StandardCharsets.UTF_8)
                        .contains("\"2026-10-05\":{\"nursery-rhyme-studio\":3}"));

        // 3. Counts are per-tree: a second tree starts at 0.
        out = capture(new String[]{"--check", "other-tree"}, exit);
        SelfTest.check("second tree: exit 0", exit[0] == 0);
        SelfTest.checkContains("second tree starts at 0", out, "count=0");

        // 4. At/over threshold: --check exits 2 with nudge=yes + human line.
        for (int i = 4; i <= 10; i++) {
            capture(new String[]{"--record", "nursery-rhyme-studio"}, exit);
        }
        out = capture(new String[]{"--check", "nursery-rhyme-studio"}, exit);
        SelfTest.check("at threshold: exit 2", exit[0] == 2);
        SelfTest.checkOutput("at threshold: nudge=yes", out,
                new String[]{"count=10", "nudge=yes", "batch remaining work"},
                new String[]{"nudge=no"});

        // 5. --threshold override is honored both ways.
        out = capture(new String[]{
                "--check", "nursery-rhyme-studio", "--threshold", "3"}, exit);
        SelfTest.check("custom threshold 3: exit 2", exit[0] == 2);
        SelfTest.checkContains("custom threshold printed", out,
                "threshold=3");
        out = capture(new String[]{
                "--check", "other-tree", "--threshold", "3"}, exit);
        SelfTest.check("custom threshold 3, count 0: exit 0", exit[0] == 0);
        SelfTest.checkContains("custom threshold 3, count 0: nudge=no", out,
                "nudge=no");

        // 6. Counts are per-day: a record "yesterday" does not inflate today.
        todayOverride = LocalDate.of(2026, 10, 4);
        capture(new String[]{"--record", "other-tree"}, exit);
        todayOverride = LocalDate.of(2026, 10, 5);
        out = capture(new String[]{"--check", "other-tree"}, exit);
        SelfTest.checkContains("yesterday's record stays on yesterday", out,
                "count=0");

        // 7. Pruning: entries older than 14 days are dropped on write,
        //    a 14-day-old entry survives.
        todayOverride = LocalDate.of(2026, 10, 20);
        String oldDate = LocalDate.of(2026, 10, 5).toString(); // 15 days old
        String edgeDate = LocalDate.of(2026, 10, 6).toString(); // 14 days old
        TreeMap<String, TreeMap<String, Integer>> probe =
                new TreeMap<>();
        TreeMap<String, Integer> old = new TreeMap<>();
        old.put("old-tree", 7);
        probe.put(oldDate, old);
        TreeMap<String, Integer> edge = new TreeMap<>();
        edge.put("edge-tree", 2);
        probe.put(edgeDate, edge);
        save(file, probe);
        String written =
                Files.readString(file, StandardCharsets.UTF_8);
        SelfTest.check("15-day-old entry pruned",
                !written.contains(oldDate) && !written.contains("old-tree"));
        SelfTest.check("14-day-old entry kept",
                written.contains(edgeDate) && written.contains("edge-tree"));
        todayOverride = LocalDate.of(2026, 10, 5);

        // 8. Corrupt state file fails LOUD, never silently zeroed.
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        out = capture(new String[]{"--record", "nursery-rhyme-studio"},
                exit);
        SelfTest.check("corrupt state: --record fails", exit[0] != 0);
        SelfTest.checkContains("corrupt state: says corrupt", out, "corrupt");
        out = capture(new String[]{"--check", "nursery-rhyme-studio"}, exit);
        SelfTest.check("corrupt state: --check fails", exit[0] != 0);
        Files.deleteIfExists(file);

        // 9. Bad tree id is a usage error (exit 2). Permanent negative
        //    control: a silently-accepted id would poison the state file.
        out = capture(new String[]{"--record", "../../evil"}, exit);
        SelfTest.check("bad tree id: exit 2", exit[0] == 2);
        SelfTest.check("bad tree id: state file untouched",
                !Files.isRegularFile(file));

        // 10. --threshold N < 1 is a usage error.
        out = capture(new String[]{
                "--check", "ok-tree", "--threshold", "0"}, exit);
        SelfTest.check("threshold 0: exit 2", exit[0] == 2);

        // 11. Missing tree id is a usage error.
        out = capture(new String[]{"--record"}, exit);
        SelfTest.check("missing tree id: exit 2", exit[0] == 2);
        SelfTest.checkContains("missing tree id: usage shown", out, "Usage:");

        wsOverride = null;
        todayOverride = null;
        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // main

    public static void main(String[] args) {
        System.exit(run(args));
    }
}
