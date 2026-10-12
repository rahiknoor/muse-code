// TranscriptCap.java — continuation-cap guard for browser-task transcript
// chains (token-efficiency idea BLG-020, approved by Rahik 2026-10-05).
//
// WHY: "continue transcript" chains (e.g. the Java Practice claude.ai audit)
// ran ~12 sequential continuations, each re-inheriting the full
// ever-growing transcript (~60M tokens for one audit + follow-ups). After
// ~3 continuations on one chain, the agent must summarize-and-restart:
// write a compact state file, spawn fresh with just that file, never carry
// the transcript further. This tool tracks per-chain continuation counts in
// a durable shared state file and signals when the cap is hit.
//
// SITE-BLOCK RULE (2026-10-09, Rahik's call: "why even try three times if
// the website has us blocked?"): the continuation budget exists for chains
// that are PROGRESSING. A site block — HTTP 429, CAPTCHA/bot challenge,
// login wall, or any explicit "stop" from the site — is not a retryable
// stall: the chain is marked blocked and gets ZERO further continuations.
// --record on a blocked chain exits 2 immediately without incrementing.
// Retrying into a block is pure waste; the site said stop, so we stop.
//
// State: ~/workspace/scheduler_state/transcript_chains.json
//   {"chains": {"<chain-id>": {"count": N, "last": "ISO-8601"}}}
// Count = number of recorded continuations on the chain; "last" = when the
// latest continuation was recorded (UTC ISO-8601).
//
// CLI:
//   TranscriptCap.java --record <chain-id> [--threshold N]
//       Increment the chain's count, print it. Exit 0 while under the cap,
//       exit 2 when the cap is hit (count >= threshold) — exit 2 means
//       "summarize the chain into a state file and restart fresh now".
//   TranscriptCap.java --check <chain-id>
//       Print the chain's count (read-only; exit 0).
//   TranscriptCap.java --reset <chain-id>
//       Clear the chain's count after a summarize-and-restart (exit 0).
//   TranscriptCap.java --self-test
//       prove-can-fail suite (uses the shared SelfTest harness).
//
// Typical use at each continuation of a chain:
//   java --class-path ~/workspace/java-shared/classes \
//        ~/workspace/java-shared/TranscriptCap.java \
//        --record java-practice-claude-audit
// Exit 0 -> keep continuing; exit 2 -> write your compact state file,
// spawn fresh with it, then --reset the chain.
//
// Chain ids are free-form short slugs the agent chooses per task chain
// (e.g. "java-practice-claude-audit"). Default threshold 3 continuations;
// --threshold N overrides it for this invocation.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class TranscriptCap {

    /** Continuations allowed per chain before summarize-and-restart. */
    public static final int DEFAULT_THRESHOLD = 3;

    /** Exit code meaning "cap hit — summarize and restart now". */
    public static final int EXIT_CAP_HIT = 2;

    /** One chain's continuation record. */
    public record Entry(int count, String last, boolean blocked) {}

    private TranscriptCap() {
        // static-only
    }

    /** Default state file. Never use the Java user.home property (it
     *  resolves to /root here); always the $HOME environment variable. */
    static Path defaultStateFile() {
        return Path.of(System.getenv("HOME"),
                "workspace", "scheduler_state", "transcript_chains.json");
    }

    /** Load state; a missing/empty file yields an empty map. */
    static Map<String, Entry> load(Path stateFile) throws IOException {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (!Files.exists(stateFile)) {
            return out;
        }
        String text =
                Files.readString(stateFile, StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || text.equals("{}")) {
            return out;
        }
        Parser p = new Parser(text);
        p.ws();
        p.expect('{');
        p.ws();
        String chainsKey = p.string();
        if (!chainsKey.equals("chains")) {
            throw new IllegalArgumentException(
                    "transcript_chains.json: top-level key must be 'chains'");
        }
        p.ws();
        p.expect(':');
        p.ws();
        p.expect('{');
        p.ws();
        if (p.peek() == '}') {
            p.next();
            p.ws();
            p.expect('}');
            return out;
        }
        while (true) {
            p.ws();
            String id = p.string();
            p.ws();
            p.expect(':');
            p.ws();
            out.put(id, p.entry());
            p.ws();
            char c = p.peek();
            if (c == ',') {
                p.next();
                continue;
            }
            if (c == '}') {
                p.next();
                break;
            }
            throw new IllegalArgumentException(
                    "transcript_chains.json: expected ',' or '}'");
        }
        p.ws();
        p.expect('}');
        return out;
    }

    /** Write state canonically (keys sorted, stable diffs). */
    static void save(Path stateFile, Map<String, Entry> state)
            throws IOException {
        StringBuilder sb = new StringBuilder("{\"chains\": {");
        boolean first = true;
        for (Map.Entry<String, Entry> e
                : new TreeMap<>(state).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Entry v = e.getValue();
            sb.append("\n  \"").append(esc(e.getKey()))
              .append("\": {\"count\": ").append(v.count())
              .append(", \"last\": \"").append(esc(v.last()))
              .append("\", \"blocked\": ").append(v.blocked()).append("}");
        }
        if (!first) {
            sb.append('\n');
        }
        sb.append("}}\n");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Record one continuation: increment the count, stamp the time.
     *  A blocked chain never increments — --record exits 2 instead. */
    static Entry record(Map<String, Entry> state, String chainId) {
        Entry prev = state.getOrDefault(chainId, new Entry(0, "", false));
        if (prev.blocked()) {
            return prev;
        }
        Entry next = new Entry(prev.count() + 1, Instant.now().toString(),
                false);
        state.put(chainId, next);
        return next;
    }

    /** Mark a chain site-blocked: no further continuations, ever,
     *  until --reset. The site said stop, so we stop. */
    static Entry markBlocked(Map<String, Entry> state, String chainId) {
        Entry prev = state.getOrDefault(chainId, new Entry(0, "", false));
        Entry next = new Entry(prev.count(), Instant.now().toString(), true);
        state.put(chainId, next);
        return next;
    }

    /** True when the chain has used up its continuation budget. */
    static boolean atCap(Entry entry, int threshold) {
        return entry.count() >= threshold;
    }

    /** Drop a chain's record (after a summarize-and-restart). */
    static void reset(Map<String, Entry> state, String chainId) {
        state.remove(chainId);
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Minimal parser for exactly the shape save() writes. */
    static final class Parser {
        final String t;
        int i;

        Parser(String t) {
            this.t = t;
        }

        void ws() {
            while (i < t.length() && Character.isWhitespace(t.charAt(i))) {
                i++;
            }
        }

        char peek() {
            if (i >= t.length()) {
                throw new IllegalArgumentException(
                        "transcript_chains.json: unexpected end of input");
            }
            return t.charAt(i);
        }

        void next() {
            peek();
            i++;
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException(
                        "transcript_chains.json: expected '" + c
                        + "' but saw '" + peek() + "'");
            }
            i++;
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                if (c == '"') {
                    i++;
                    return sb.toString();
                }
                if (c == '\\') {
                    i++;
                    sb.append(peek());
                    i++;
                } else {
                    sb.append(c);
                    i++;
                }
            }
        }

        Entry entry() {
            expect('{');
            int count = -1;
            String last = "";
            boolean blocked = false;
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                if (key.equals("count")) {
                    StringBuilder num = new StringBuilder();
                    while (i < t.length() && Character.isDigit(t.charAt(i))) {
                        num.append(t.charAt(i));
                        i++;
                    }
                    if (num.length() == 0) {
                        throw new IllegalArgumentException(
                                "transcript_chains.json: 'count' needs a number");
                    }
                    count = Integer.parseInt(num.toString());
                } else if (key.equals("last")) {
                    last = string();
                } else if (key.equals("blocked")) {
                    StringBuilder word = new StringBuilder();
                    while (i < t.length()
                            && Character.isLetter(t.charAt(i))) {
                        word.append(t.charAt(i));
                        i++;
                    }
                    if (word.toString().equals("true")) {
                        blocked = true;
                    } else if (!word.toString().equals("false")) {
                        throw new IllegalArgumentException(
                                "transcript_chains.json: 'blocked' needs"
                                + " true or false");
                    }
                } else {
                    throw new IllegalArgumentException(
                            "transcript_chains.json: unknown entry key '"
                            + key + "'");
                }
                ws();
                char c = peek();
                if (c == ',') {
                    next();
                    continue;
                }
                if (c == '}') {
                    next();
                    break;
                }
                throw new IllegalArgumentException(
                        "transcript_chains.json: expected ',' or '}'");
            }
            if (count < 0) {
                throw new IllegalArgumentException(
                        "transcript_chains.json: entry is missing 'count'");
            }
            return new Entry(count, last, blocked);
        }
    }

    // ------------------------------------------------------------------
    // --self-test (prove-can-fail: the malformed-input and cap-hit paths
    // are permanent negative controls)

    static void selfTest() throws Exception {
        GuardFiring.setEnabled(false); // firing paths must not pollute the real log
        Path dir = Files.createTempDirectory("transcriptcap");
        Path stateFile = dir.resolve("transcript_chains.json");

        // 1. Missing file loads as empty.
        SelfTest.checkEq("missing state file loads empty",
                0, load(stateFile).size());

        // 2. Record increments, stamps ISO-8601 time, persists.
        Map<String, Entry> state = load(stateFile);
        Entry e1 = record(state, "chain-a");
        SelfTest.checkEq("first record count is 1", 1, e1.count());
        SelfTest.check("first record has a timestamp",
                e1.last() != null && e1.last().contains("T"));
        Entry e2 = record(state, "chain-a");
        SelfTest.checkEq("second record count is 2", 2, e2.count());
        Entry other = record(state, "chain-b");
        SelfTest.checkEq("other chain starts at 1", 1, other.count());
        save(stateFile, state);

        // 3. Round-trip preserves everything.
        Map<String, Entry> reloaded = load(stateFile);
        SelfTest.checkEq("round-trip chain-a count", 2,
                reloaded.get("chain-a").count());
        SelfTest.checkEq("round-trip chain-b count", 1,
                reloaded.get("chain-b").count());
        SelfTest.checkEq("round-trip chain-a timestamp",
                e2.last(), reloaded.get("chain-a").last());

        // 4. Cap semantics with the default threshold.
        SelfTest.check("2 < 3 is under cap",
                !atCap(reloaded.get("chain-a"), DEFAULT_THRESHOLD));
        Entry e3 = record(reloaded, "chain-a");
        SelfTest.checkEq("third record count is 3", 3, e3.count());
        SelfTest.check("3 >= 3 hits the cap",
                atCap(e3, DEFAULT_THRESHOLD));

        // 5. --threshold override is honored.
        SelfTest.check("threshold 1 makes the first record hit the cap",
                atCap(record(new LinkedHashMap<>(), "fresh"), 1));
        SelfTest.check("threshold 10 keeps 3 under cap",
                !atCap(new Entry(3, "", false), 10));

        // 6. Reset clears the chain but leaves others.
        reset(reloaded, "chain-a");
        SelfTest.check("reset removes the chain",
                !reloaded.containsKey("chain-a"));
        SelfTest.check("reset leaves other chains alone",
                reloaded.containsKey("chain-b"));

        // 7. Check on an unknown chain reads as zero.
        SelfTest.checkEq("unknown chain count is 0",
                0, load(stateFile).getOrDefault("never-seen",
                        new Entry(0, "", false)).count());

        // 8. Malformed JSON is rejected, never silently accepted.
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"chains\": {\"x\": nope}}",
                StandardCharsets.UTF_8);
        boolean threw = false;
        try {
            load(bad);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        SelfTest.check("malformed state JSON throws", threw);

        // 9. Default state path is the shared scheduler_state file
        //    ($HOME env, never the Java user.home property).
        String home = System.getenv("HOME");
        SelfTest.checkEq("default state file path",
                Path.of(home, "workspace", "scheduler_state",
                        "transcript_chains.json"),
                defaultStateFile());

        // 10. save() output matches the specified format.
        String written =
                Files.readString(stateFile, StandardCharsets.UTF_8);
        SelfTest.checkContains("saved file has the chains wrapper",
                written, "\"chains\": {");
        SelfTest.checkContains("saved file has a count field",
                written, "\"count\": 1");

        // 11. Site-block rule (2026-10-09): a blocked chain gets ZERO
        //     further continuations — the site said stop, so we stop.
        Map<String, Entry> blocked = new LinkedHashMap<>();
        Entry b1 = record(blocked, "blocked-chain");
        SelfTest.checkEq("one continuation recorded before the block",
                1, b1.count());
        Entry b2 = markBlocked(blocked, "blocked-chain");
        SelfTest.check("mark-blocked sets the flag", b2.blocked());
        SelfTest.checkEq("mark-blocked keeps the count",
                1, b2.count());
        Entry b3 = record(blocked, "blocked-chain");
        SelfTest.check("record on a blocked chain does not increment",
                b3.count() == 1 && b3.blocked());
        save(stateFile, blocked);
        Map<String, Entry> blockedBack = load(stateFile);
        Entry b4 = blockedBack.get("blocked-chain");
        SelfTest.check("blocked flag survives the round-trip", b4.blocked());
        SelfTest.checkEq("count survives the round-trip with the flag",
                1, b4.count());
        Entry b5 = record(blockedBack, "blocked-chain");
        SelfTest.check("reloaded blocked chain still refuses to increment",
                b5.count() == 1 && b5.blocked());
        // Unblocked chains are unaffected by the rule.
        Entry free = record(blockedBack, "free-chain");
        SelfTest.checkEq("unblocked chain still records normally",
                1, free.count());
        SelfTest.check("unblocked chain is not flagged", !free.blocked());

        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // main

    static void usage() {
        System.out.println("Usage:");
        System.out.println("  TranscriptCap.java --record <chain-id> [--threshold N]");
        System.out.println("  TranscriptCap.java --check <chain-id>");
        System.out.println("  TranscriptCap.java --reset <chain-id>");
        System.out.println("  TranscriptCap.java --mark-blocked <chain-id>");
        System.out.println("  TranscriptCap.java --self-test");
        System.out.println();
        System.out.println("  --mark-blocked: the site blocked the chain (429,"
                + " CAPTCHA, login wall).");
        System.out.println("  A blocked chain gets ZERO further continuations:"
                + " --record exits 2");
        System.out.println("  immediately without incrementing. The site said"
                + " stop, so we stop.");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            try {
                selfTest();
            } catch (Exception e) {
                System.out.println("self-test runner error: " + e);
                System.exit(2);
            }
            return;
        }
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        int threshold = DEFAULT_THRESHOLD;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--threshold") && i + 1 < args.length) {
                threshold = Integer.parseInt(args[++i]);
            } else {
                System.out.println("unknown argument: " + args[i]);
                usage();
                System.exit(2);
            }
        }
        if (threshold <= 0) {
            System.out.println("--threshold needs a positive number");
            System.exit(2);
        }
        String chainId = args[1];
        Path stateFile = defaultStateFile();
        switch (args[0]) {
            case "--record" -> {
                Map<String, Entry> state = load(stateFile);
                Entry before =
                        state.getOrDefault(chainId, new Entry(0, "", false));
                if (before.blocked()) {
                    System.out.println("chain '" + chainId + "': SITE BLOCKED"
                            + " — zero continuation budget. Stop, do not"
                            + " retry the site.");
                    GuardFiring.log("transcript-cap-blocked",
                            "chain=" + chainId + " blocked=true");
                    System.exit(EXIT_CAP_HIT);
                }
                Entry entry = record(state, chainId);
                save(stateFile, state);
                if (atCap(entry, threshold)) {
                    System.out.println("chain '" + chainId + "': continuation "
                            + entry.count() + "/" + threshold
                            + " — CAP HIT: summarize the chain into a state"
                            + " file and restart fresh now");
                    GuardFiring.log("transcript-cap", "chain=" + chainId
                            + " count=" + entry.count() + "/" + threshold);
                    System.exit(EXIT_CAP_HIT);
                }
                System.out.println("chain '" + chainId + "': continuation "
                        + entry.count() + "/" + threshold);
                System.exit(0);
            }
            case "--check" -> {
                Map<String, Entry> state = load(stateFile);
                Entry entry = state.getOrDefault(chainId,
                        new Entry(0, "", false));
                System.out.println("chain '" + chainId + "': " + entry.count()
                        + " continuation(s), cap is " + threshold
                        + (entry.blocked() ? ", SITE BLOCKED" : ""));
                System.exit(0);
            }
            case "--reset" -> {
                Map<String, Entry> state = load(stateFile);
                reset(state, chainId);
                save(stateFile, state);
                System.out.println("chain '" + chainId + "': reset");
                System.exit(0);
            }
            case "--mark-blocked" -> {
                Map<String, Entry> state = load(stateFile);
                markBlocked(state, chainId);
                save(stateFile, state);
                System.out.println("chain '" + chainId + "': marked"
                        + " site-blocked — no further continuations");
                GuardFiring.log("transcript-cap-blocked",
                        "chain=" + chainId + " marked blocked");
                System.exit(0);
            }
            default -> {
                usage();
                System.exit(2);
            }
        }
    }
}
