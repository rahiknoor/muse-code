// RetryPolicy.java — one shared retry policy for every throttled/blocked fetch.
//
// Each job used to handle rate-limits its own way (Tina transcripts: once a
// day; WSB verification: retry once then give up; drop alerts: fail open).
// Same problem class, inconsistent policies — and the Tina case showed the
// settings were either too timid or too naive. This helper is the single
// place where the retry behavior is defined and tuned (Rahik 2026-09-28:
// predictable retries; "repeated steps become a method").
//
// Policy (constants below — tune here, nowhere else):
//   - First attempt for an id is always a single gentle PROBE.
//   - On a block, the next probe waits out an exponential backoff:
//     4h after the 1st consecutive block, 8h after the 2nd, then 12h (cap).
//     A few attempts per day, hours apart — never hammering.
//   - Blocked continuously for 7 days -> STUCK: report to Rahik and try the
//     alternate route when one is configured.
//
// Usage (single-file launch, shared classes on the class path):
//   java --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/RetryPolicy.java probe <statefile> <id> [--alternate <route>]
//         PROBE (exit 0): attempt now — exactly one gentle probe, then record.
//         WAIT <minutes> (exit 3): backoff not elapsed — end this run quietly.
//         STUCK <days>d (exit 5): blocked >= 7d — report to Rahik, try alternate.
//   java --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/RetryPolicy.java record <statefile> <id> ok|blocked [--note <text>]
//         Updates the id's history (attempt count, streak, timestamps); exit 0.
//   java --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/RetryPolicy.java --self-test
//
// Rules, enforced here so no caller can get them wrong:
//   - probe is READ-ONLY: it never writes the state file (calling it twice
//     without a record in between returns the same verdict).
//   - A corrupt state file is a LOUD error (exit 2), never a silent PROBE
//     (could re-trip a limit) and never a silent WAIT (could stall a fetch).
//   - Callers must never hand-edit the state file; record is the one writer.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class RetryPolicy {

    // ------------------------------------------------------------------
    // The one place to tune the policy. Every job's retry behavior flows
    // from these three numbers; nothing per-job may override them.

    /** Wait after the 1st consecutive block; doubles with each further block. */
    static final long BASE_DELAY_MINUTES = 240;   // 4h
    /** Backoff cap: attempts stay a few per day, hours apart, never hammering. */
    static final long MAX_DELAY_MINUTES = 720;    // 12h
    /** Continuously blocked this long -> STUCK: report + alternate route. */
    static final long MAX_STUCK_DAYS = 7;

    static final int EXIT_PROBE = 0;
    static final int EXIT_WAIT = 3;
    static final int EXIT_STUCK = 5;
    static final int EXIT_ERROR = 2;

    private RetryPolicy() { }

    /** Required wait after n consecutive blocks: 4h, 8h, 12h, 12h, ... */
    static long delayMinutes(long consecutiveBlocked) {
        long d = BASE_DELAY_MINUTES;
        for (long i = 1; i < consecutiveBlocked; i++) {
            d *= 2;
            if (d >= MAX_DELAY_MINUTES) return MAX_DELAY_MINUTES;
        }
        return Math.min(d, MAX_DELAY_MINUTES);
    }

    /** One fetch target's retry history. */
    static final class Record {
        long attempts;
        long consecutiveBlocked;
        Instant blockedSince;  // start of the current blocked streak; null when clear
        Instant lastAttempt;
        String lastResult;     // "ok" | "blocked"
        String note = "";
    }

    enum Decision { PROBE, WAIT, STUCK }

    static final class Verdict {
        Decision decision;
        long waitMinutes;  // WAIT: minutes until the next probe is allowed
        long stuckDays;    // STUCK: days continuously blocked
        String reason;     // human-readable line
    }

    /**
     * The pure decision: no file writes, `now` injected for tests.
     * Read-only by construction — calling it never mutates history.
     */
    static Verdict decide(Record r, Instant now) {
        Verdict v = new Verdict();
        if (r == null || r.lastResult == null) {
            v.decision = Decision.PROBE;
            v.reason = "first attempt — single gentle probe";
            return v;
        }
        if ("blocked".equals(r.lastResult) && r.blockedSince != null) {
            long daysBlocked = Duration.between(r.blockedSince, now).toDays();
            if (daysBlocked >= MAX_STUCK_DAYS) {
                v.decision = Decision.STUCK;
                v.stuckDays = daysBlocked;
                v.reason = daysBlocked + "d continuously blocked (limit "
                        + MAX_STUCK_DAYS + "d)";
                return v;
            }
            long need = delayMinutes(r.consecutiveBlocked);
            long elapsedMin = r.lastAttempt == null ? Long.MAX_VALUE
                    : Duration.between(r.lastAttempt, now).toMinutes();
            if (elapsedMin < need) {
                v.decision = Decision.WAIT;
                v.waitMinutes = need - elapsedMin;
                v.reason = "blocked " + r.consecutiveBlocked + "x in a row; backoff "
                        + need + "m, " + v.waitMinutes + "m left";
                return v;
            }
        }
        v.decision = Decision.PROBE;
        v.reason = "backoff elapsed (last result: " + r.lastResult + ")";
        return v;
    }

    /** Fold one probe outcome into history. `now` injected for tests. */
    static Record applyResult(Record r, boolean ok, Instant now, String note) {
        if (r == null) r = new Record();
        r.attempts++;
        r.lastAttempt = now;
        r.lastResult = ok ? "ok" : "blocked";
        if (ok) {
            r.consecutiveBlocked = 0;
            r.blockedSince = null;
        } else {
            r.consecutiveBlocked++;
            if (r.blockedSince == null) r.blockedSince = now;
        }
        if (note != null) r.note = note;
        return r;
    }

    // ------------------------------------------------------------------
    // State file: {"<id>": {"attempts": n, "consecutive_blocked": n,
    //   "blocked_since": "<iso>", "last_attempt": "<iso>",
    //   "last_result": "ok|blocked", "note": "..."}}.
    // Parsed with RunKeys' public entry splitter; written canonically.

    static Map<String, Record> readState(Path f) throws IOException {
        Map<String, Record> out = new TreeMap<>();
        if (!Files.exists(f)) return out;
        String src = Files.readString(f, StandardCharsets.UTF_8);
        if (src.isBlank()) return out;
        List<String> raws = RunKeys.splitEntries(src);
        if (raws.isEmpty() && src.contains("\""))
            throw new IOException("retry state present but unparseable: " + f);
        for (String raw : raws) {
            String id = RunKeys.keyOf(raw);
            if (id == null) throw new IOException("retry state entry has no id: " + f);
            String inner = raw.substring(raw.indexOf(':') + 1).trim();
            if (!inner.startsWith("{"))
                throw new IOException("retry state entry for '" + id + "' is not an object: " + f);
            out.put(id, parseRecord(inner));
        }
        return out;
    }

    static Record parseRecord(String inner) throws IOException {
        Record r = new Record();
        for (String fieldRaw : RunKeys.splitEntries(inner)) {
            String name = RunKeys.keyOf(fieldRaw);
            String val = fieldRaw.substring(fieldRaw.indexOf(':') + 1).trim();
            if (name == null) throw new IOException("bad field in retry state: " + fieldRaw);
            switch (name) {
                case "attempts" -> r.attempts = parseLong(val);
                case "consecutive_blocked" -> r.consecutiveBlocked = parseLong(val);
                case "blocked_since" -> r.blockedSince = parseInstant(val);
                case "last_attempt" -> r.lastAttempt = parseInstant(val);
                case "last_result" -> r.lastResult = parseStr(val);
                case "note" -> r.note = parseStr(val);
                default -> throw new IOException("unknown field in retry state: " + name);
            }
        }
        if (r.lastResult == null) throw new IOException("retry state record missing last_result");
        return r;
    }

    static long parseLong(String v) throws IOException {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IOException("not a number in retry state: " + v);
        }
    }

    static Instant parseInstant(String v) throws IOException {
        String s = parseStr(v);
        if (s == null || s.isEmpty()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            throw new IOException("not an ISO-8601 instant in retry state: " + v);
        }
    }

    static String parseStr(String v) throws IOException {
        if ("null".equals(v)) return null;
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\""))
            return unescape(v.substring(1, v.length() - 1));
        throw new IOException("not a JSON string in retry state: " + v);
    }

    static String unescape(String s) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (i + 1 >= s.length()) throw new IOException("bad escape in retry state");
            char e = s.charAt(++i);
            switch (e) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                default -> out.append(e);
            }
        }
        return out.toString();
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
    }

    static String strOrNull(String s) {
        return s == null ? "null" : "\"" + escape(s) + "\"";
    }

    static String instantOrNull(Instant t) {
        return t == null ? "null" : "\"" + t.toString() + "\"";
    }

    static void writeState(Path f, Map<String, Record> state) throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, Record> e : state.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            Record r = e.getValue();
            sb.append("  \"").append(escape(e.getKey())).append("\": {")
              .append("\"attempts\": ").append(r.attempts).append(", ")
              .append("\"consecutive_blocked\": ").append(r.consecutiveBlocked).append(", ")
              .append("\"blocked_since\": ").append(instantOrNull(r.blockedSince)).append(", ")
              .append("\"last_attempt\": ").append(instantOrNull(r.lastAttempt)).append(", ")
              .append("\"last_result\": ").append(strOrNull(r.lastResult)).append(", ")
              .append("\"note\": \"").append(escape(r.note)).append("\"}");
        }
        sb.append(first ? "}" : "\n}").append("\n");
        if (f.getParent() != null) Files.createDirectories(f.getParent());
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // CLI. run() returns the exit code (testable without System.exit).

    static int run(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage();
            return args.length == 0 ? EXIT_ERROR : 0;
        }
        switch (args[0]) {
            case "probe": return cmdProbe(args);
            case "record": return cmdRecord(args);
            case "--self-test": selfTest(); return 0; // unreachable
            default: usage(); return EXIT_ERROR;
        }
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static void usage() {
        System.out.println("usage:");
        System.out.println("  RetryPolicy.java probe <statefile> <id> [--alternate <route>]");
        System.out.println("    PROBE (exit 0) | WAIT <minutes> (exit 3) | STUCK <days>d (exit 5)");
        System.out.println("  RetryPolicy.java record <statefile> <id> ok|blocked [--note <text>]");
        System.out.println("  RetryPolicy.java --self-test");
    }

    /** Read-only: prints the verdict, never writes the state file. */
    static int cmdProbe(String[] args) {
        if (args.length < 3) { usage(); return EXIT_ERROR; }
        Path stateFile = Paths.get(args[1]);
        String id = args[2];
        String alternate = null;
        for (int i = 3; i < args.length; i++) {
            if ("--alternate".equals(args[i]) && i + 1 < args.length) alternate = args[++i];
            else { usage(); return EXIT_ERROR; }
        }
        Map<String, Record> state;
        try {
            state = readState(stateFile);
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.out.println("ERROR refusing to guess — fix or reset the state file, never probe blind");
            return EXIT_ERROR;
        }
        Verdict v = decide(state.get(id), Instant.now());
        System.out.print(renderReport(id, v, alternate));
        return switch (v.decision) {
            case PROBE -> EXIT_PROBE;
            case WAIT -> EXIT_WAIT;
            case STUCK -> EXIT_STUCK;
        };
    }

    /** The one writer: folds a probe outcome into history. */
    static int cmdRecord(String[] args) {
        if (args.length < 4) { usage(); return EXIT_ERROR; }
        Path stateFile = Paths.get(args[1]);
        String id = args[2];
        boolean ok;
        if ("ok".equals(args[3])) ok = true;
        else if ("blocked".equals(args[3])) ok = false;
        else { usage(); return EXIT_ERROR; }
        String note = null;
        for (int i = 4; i < args.length; i++) {
            if ("--note".equals(args[i]) && i + 1 < args.length) note = args[++i];
            else { usage(); return EXIT_ERROR; }
        }
        Map<String, Record> state;
        try {
            state = readState(stateFile);
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            return EXIT_ERROR;
        }
        Record r = applyResult(state.get(id), ok, Instant.now(), note);
        state.put(id, r);
        try {
            writeState(stateFile, state);
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            return EXIT_ERROR;
        }
        System.out.println("recorded " + r.lastResult + " for '" + id + "' (attempt "
                + r.attempts + ", " + r.consecutiveBlocked + " blocked in a row)");
        return EXIT_PROBE;
    }

    // ------------------------------------------------------------------
    // Self-test on synthetic state files (never touches live files).

    static void selfTest() throws Exception {
        Path t = Files.createTempDirectory("retrypolicy-test");
        Instant now = Instant.parse("2026-09-28T11:50:00Z");

        // 1. backoff schedule: 4h, 8h, 12h cap
        SelfTest.checkEq("delay after 1st block = 240m", 240L, delayMinutes(1));
        SelfTest.checkEq("delay after 2nd block = 480m", 480L, delayMinutes(2));
        SelfTest.checkEq("delay after 3rd block = 720m (cap)", 720L, delayMinutes(3));
        SelfTest.checkEq("delay after 10th block still 720m", 720L, delayMinutes(10));

        // 2. unknown id -> first gentle probe
        Verdict v = decide(null, now);
        SelfTest.check("unknown id -> PROBE", v.decision == Decision.PROBE);
        SelfTest.checkContains("first probe says gentle", v.reason, "gentle probe");

        // 3. blocked just now (1 in a row) -> WAIT the full 240m
        Record r1 = blockedRecord(now, 1, now);
        v = decide(r1, now);
        SelfTest.check("blocked just now -> WAIT", v.decision == Decision.WAIT);
        SelfTest.checkEq("WAIT reports 240m left", 240L, v.waitMinutes);

        // 4. blocked 5h ago, 1 in a row -> backoff elapsed -> PROBE
        v = decide(blockedRecord(now.minus(Duration.ofHours(5)), 1, now.minus(Duration.ofHours(5))), now);
        SelfTest.check("blocked 5h ago (1x) -> PROBE", v.decision == Decision.PROBE);

        // 5. blocked 5h ago, 2 in a row -> needs 8h -> WAIT 180m
        v = decide(blockedRecord(now.minus(Duration.ofHours(5)), 2, now.minus(Duration.ofHours(5))), now);
        SelfTest.check("blocked 5h ago (2x) -> WAIT", v.decision == Decision.WAIT);
        SelfTest.checkEq("WAIT reports 180m left", 180L, v.waitMinutes);

        // 6. blocked 9h ago, 2 in a row -> PROBE
        v = decide(blockedRecord(now.minus(Duration.ofHours(9)), 2, now.minus(Duration.ofHours(9))), now);
        SelfTest.check("blocked 9h ago (2x) -> PROBE", v.decision == Decision.PROBE);

        // 7. cap binds: 5 in a row, 11h ago -> WAIT 60m (720-660)
        v = decide(blockedRecord(now.minus(Duration.ofHours(11)), 5, now.minus(Duration.ofHours(11))), now);
        SelfTest.check("blocked 11h ago (5x) -> WAIT", v.decision == Decision.WAIT);
        SelfTest.checkEq("capped WAIT reports 60m left", 60L, v.waitMinutes);

        // 8. 7d continuously blocked -> STUCK
        v = decide(blockedRecord(now.minus(Duration.ofDays(7)), 9, now.minus(Duration.ofHours(2))),
                now);
        SelfTest.check("blocked 7d -> STUCK", v.decision == Decision.STUCK);
        SelfTest.checkEq("STUCK reports 7d", 7L, v.stuckDays);

        // 9. 6d23h blocked -> not STUCK yet
        v = decide(blockedRecord(now.minus(Duration.ofHours(167)), 9, now.minus(Duration.ofHours(2))),
                now);
        SelfTest.check("blocked 6d23h -> not STUCK", v.decision != Decision.STUCK);

        // 10. a success clears the streak -> PROBE immediately
        Record cleared = applyResult(blockedRecord(now.minus(Duration.ofHours(1)), 3,
                now.minus(Duration.ofHours(1))), true, now, null);
        SelfTest.checkEq("ok resets consecutive to 0", 0L, cleared.consecutiveBlocked);
        SelfTest.check("blockedSince cleared on ok", cleared.blockedSince == null);
        v = decide(cleared, now);
        SelfTest.check("after ok -> PROBE", v.decision == Decision.PROBE);

        // 11. applyResult chains: blocked, blocked keeps the original blockedSince
        Record c1 = applyResult(null, false, now.minus(Duration.ofHours(9)), null);
        Record c2 = applyResult(c1, false, now, null);
        SelfTest.checkEq("two blocks -> 2 in a row", 2L, c2.consecutiveBlocked);
        SelfTest.checkEq("blockedSince stays at first block",
                now.minus(Duration.ofHours(9)), c2.blockedSince);
        SelfTest.checkEq("attempts counted", 2L, c2.attempts);

        // 12. round trip: write then read preserves every field
        Path f = t.resolve("state.json");
        Map<String, Record> state = new TreeMap<>();
        Record orig = new Record();
        orig.attempts = 4; orig.consecutiveBlocked = 2;
        orig.blockedSince = Instant.parse("2026-09-27T10:46:00Z");
        orig.lastAttempt = now; orig.lastResult = "blocked"; orig.note = "429 \"quoted\"";
        state.put("vid-1", orig);
        writeState(f, state);
        Record back = readState(f).get("vid-1");
        SelfTest.checkEq("round-trip attempts", 4L, back.attempts);
        SelfTest.checkEq("round-trip consecutive", 2L, back.consecutiveBlocked);
        SelfTest.checkEq("round-trip blockedSince",
                Instant.parse("2026-09-27T10:46:00Z"), back.blockedSince);
        SelfTest.checkEq("round-trip lastAttempt", now, back.lastAttempt);
        SelfTest.checkEq("round-trip lastResult", "blocked", back.lastResult);
        SelfTest.checkEq("round-trip note with quotes", "429 \"quoted\"", back.note);

        // 13. corrupt state file -> loud ERROR, never a silent verdict
        Path corrupt = t.resolve("corrupt.json");
        Files.writeString(corrupt, "\"bare string, not an object\"", StandardCharsets.UTF_8);
        int rc = run(new String[]{"probe", corrupt.toString(), "x"});
        SelfTest.checkEq("corrupt state -> exit 2 (loud)", EXIT_ERROR, rc);

        // 14. probe is read-only: the file is byte-identical afterwards
        String before = Files.readString(f, StandardCharsets.UTF_8);
        rc = run(new String[]{"probe", f.toString(), "vid-1"});
        SelfTest.checkEq("probe on blocked record -> WAIT", EXIT_WAIT, rc);
        SelfTest.checkEq("probe left the file untouched",
                before, Files.readString(f, StandardCharsets.UTF_8));

        // 15. record CLI end-to-end: blocked then ok
        Path g = t.resolve("cli.json");
        SelfTest.checkEq("record blocked -> exit 0",
                0, run(new String[]{"record", g.toString(), "v9", "blocked", "--note", "429"}));
        Record got = readState(g).get("v9");
        SelfTest.checkEq("recorded 1 in a row", 1L, got.consecutiveBlocked);
        SelfTest.checkEq("record ok -> exit 0",
                0, run(new String[]{"record", g.toString(), "v9", "ok"}));
        got = readState(g).get("v9");
        SelfTest.checkEq("ok clears the streak", 0L, got.consecutiveBlocked);

        // 16. STUCK report carries the alternate route when configured
        Path stuck = t.resolve("stuck.json");
        Map<String, Record> ss = new TreeMap<>();
        ss.put("v0", blockedRecord(now.minus(Duration.ofDays(8)), 12, now.minus(Duration.ofHours(3))));
        writeState(stuck, ss);
        String report = probeReport(stuck, "v0", "yt-dlp android client", now);
        SelfTest.checkContains("STUCK report names the alternate", report, "ALTERNATE: yt-dlp");
        SelfTest.checkContains("STUCK report shows days", report, "STUCK 8d");
        String noAlt = probeReport(stuck, "v0", null, now);
        SelfTest.checkContains("no alternate -> says none configured",
                noAlt, "ALTERNATE: none configured");

        // 17. usage errors exit 2
        SelfTest.checkEq("probe without args -> exit 2",
                EXIT_ERROR, run(new String[]{"probe"}));
        SelfTest.checkEq("record with bad result -> exit 2",
                EXIT_ERROR, run(new String[]{"record", g.toString(), "v9", "maybe"}));

        SelfTest.finish();
    }

    /** Synthetic blocked record for tests: streak of n, last attempt at `at`. */
    static Record blockedRecord(Instant since, long consecutive, Instant at) {
        Record r = new Record();
        r.attempts = consecutive;
        r.consecutiveBlocked = consecutive;
        r.blockedSince = since;
        r.lastAttempt = at;
        r.lastResult = "blocked";
        return r;
    }

    /** Pure rendering of a verdict (one place builds the report text). */
    static String renderReport(String id, Verdict v, String alternate) {
        StringBuilder sb = new StringBuilder();
        switch (v.decision) {
            case PROBE -> sb.append("PROBE\n").append(v.reason)
                    .append(": attempt '").append(id).append("' now, then record the outcome\n");
            case WAIT -> sb.append("WAIT ").append(v.waitMinutes).append("\n")
                    .append(v.reason).append(" — end this run quietly for '")
                    .append(id).append("'\n");
            case STUCK -> {
                sb.append("STUCK ").append(v.stuckDays).append("d\n")
                  .append("'").append(id).append("' ").append(v.reason)
                  .append(" — report to Rahik\n");
                sb.append(alternate != null ? "ALTERNATE: " + alternate + "\n"
                                           : "ALTERNATE: none configured\n");
            }
        }
        return sb.toString();
    }

    /** The probe report text (testable without touching System.out). */
    static String probeReport(Path stateFile, String id, String alternate, Instant now)
            throws IOException {
        return renderReport(id, decide(readState(stateFile).get(id), now), alternate);
    }
}
