// FlakyQuarantine.java — flaky-suite quarantine state for RunAllTests.
//
// Suites that fail intermittently are retried once with backoff; suites that
// fail N consecutive runs are flagged QUARANTINED in the report so a repeat
// offender is visible instead of looking like a fresh break. Quarantine never
// weakens a check: a quarantined suite that fails still fails the sweep —
// the flag only records history and skips the pointless retry.
//
// State lives in ~/workspace/scheduler_state/flaky_quarantine.json:
//   {"Suite name": {"consecutive": 2, "quarantined": false, "lastFailed": "2026-09-28"}, ...}
// A pass resets the consecutive count and clears quarantine. The file is NOT
// in RetentionSweep's allowlist, so history survives the 60-day evidence
// roll-off (it is state, not raw evidence).
//
// Pattern: Bazel --flaky_test_attempts / Airflow retry+quarantine handling.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class FlakyQuarantine {

    /** Consecutive failures after which a suite is flagged quarantined. */
    public static final int QUARANTINE_AFTER = 3;

    /** One suite's quarantine record. */
    public record Entry(int consecutive, boolean quarantined, String lastFailed) {}

    private FlakyQuarantine() {
        // static-only
    }

    /** Load state; missing/empty file yields an empty map (never throws for that). */
    public static Map<String, Entry> load(Path stateFile) throws IOException {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (!Files.exists(stateFile)) {
            return out;
        }
        String text = Files.readString(stateFile, StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || text.equals("{}")) {
            return out;
        }
        Parser p = new Parser(text);
        p.ws();
        p.expect('{');
        p.ws();
        if (p.peek() == '}') {
            return out;
        }
        while (true) {
            p.ws();
            String name = p.string();
            p.ws();
            p.expect(':');
            p.ws();
            out.put(name, p.entry());
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
                    "flaky_quarantine.json: expected ',' or '}'");
        }
        return out;
    }

    /** Write state canonically (keys sorted, stable diffs). */
    public static void save(Path stateFile, Map<String, Entry> state)
            throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, Entry> e : new TreeMap<>(state).entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            Entry v = e.getValue();
            sb.append(" \"").append(esc(e.getKey())).append("\": {\"consecutive\": ")
              .append(v.consecutive()).append(", \"quarantined\": ")
              .append(v.quarantined()).append(", \"lastFailed\": \"")
              .append(esc(v.lastFailed())).append("\"}");
        }
        sb.append(first ? "}" : "\n}");
        sb.append("\n");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Record one run's outcome. A pass resets the streak and clears
     * quarantine; a failure increments it and quarantines at QUARANTINE_AFTER.
     */
    public static Entry record(Map<String, Entry> state, String suite,
                               boolean passed, String today) {
        Entry prev = state.getOrDefault(suite, new Entry(0, false, ""));
        Entry next = passed
                ? new Entry(0, false, prev.lastFailed())
                : new Entry(prev.consecutive() + 1,
                            prev.consecutive() + 1 >= QUARANTINE_AFTER, today);
        state.put(suite, next);
        return next;
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
                        "flaky_quarantine.json: unexpected end of input");
            }
            return t.charAt(i);
        }

        void next() {
            i++;
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException(
                        "flaky_quarantine.json: expected '" + c + "' at index " + i);
            }
            i++;
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    sb.append(peek());
                    next();
                } else {
                    sb.append(c);
                }
            }
        }

        Entry entry() {
            expect('{');
            int consecutive = 0;
            boolean quarantined = false;
            String lastFailed = "";
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                switch (key) {
                    case "consecutive" -> consecutive = number();
                    case "quarantined" -> quarantined = bool();
                    case "lastFailed" -> lastFailed = string();
                    default -> skipValue();
                }
                ws();
                char c = peek();
                if (c == ',') {
                    next();
                    continue;
                }
                if (c == '}') {
                    next();
                    return new Entry(consecutive, quarantined, lastFailed);
                }
                throw new IllegalArgumentException(
                        "flaky_quarantine.json: expected ',' or '}' in entry");
            }
        }

        int number() {
            int start = i;
            while (i < t.length()
                    && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '-')) {
                i++;
            }
            return Integer.parseInt(t.substring(start, i));
        }

        boolean bool() {
            if (t.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (t.startsWith("false", i)) {
                i += 5;
                return false;
            }
            throw new IllegalArgumentException(
                    "flaky_quarantine.json: expected boolean at index " + i);
        }

        void skipValue() {
            char c = peek();
            if (c == '"') {
                string();
            } else if (c == '{') {
                int depth = 0;
                do {
                    char d = peek();
                    next();
                    if (d == '"') {
                        i--; // re-read as string
                        string();
                    } else if (d == '\\') {
                        next(); // skip the escaped char
                    } else if (d == '{') {
                        depth++;
                    } else if (d == '}') {
                        depth--;
                    }
                } while (depth > 0);
            } else {
                while (i < t.length() && ",}".indexOf(t.charAt(i)) < 0) {
                    i++;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // --self-test
    static void selfTest() throws Exception {
        Path dir = Files.createTempDirectory("flakyq");
        Path f = dir.resolve("flaky_quarantine.json");

        // missing file -> empty state, no crash
        Map<String, Entry> s = load(f);
        SelfTest.check("missing state file loads empty", s.isEmpty());

        // failure streak builds, quarantine trips at 3
        Entry e1 = record(s, "Suite A", false, "2026-09-28");
        SelfTest.checkEq("1st failure: consecutive=1", 1, e1.consecutive());
        SelfTest.check("1st failure: not quarantined", !e1.quarantined());
        Entry e2 = record(s, "Suite A", false, "2026-09-28");
        SelfTest.checkEq("2nd failure: consecutive=2", 2, e2.consecutive());
        SelfTest.check("2nd failure: not quarantined", !e2.quarantined());
        Entry e3 = record(s, "Suite A", false, "2026-09-28");
        SelfTest.checkEq("3rd failure: consecutive=3", 3, e3.consecutive());
        SelfTest.check("3rd failure: quarantined", e3.quarantined());
        SelfTest.checkEq("lastFailed recorded", "2026-09-28", e3.lastFailed());

        // a pass clears the streak and the quarantine flag
        Entry e4 = record(s, "Suite A", true, "2026-09-29");
        SelfTest.checkEq("pass resets consecutive", 0, e4.consecutive());
        SelfTest.check("pass clears quarantine", !e4.quarantined());

        // independent streaks per suite
        record(s, "Suite B", false, "2026-09-28");
        SelfTest.checkEq("Suite B streak independent",
                1, s.get("Suite B").consecutive());
        SelfTest.checkEq("Suite A untouched by Suite B",
                0, s.get("Suite A").consecutive());

        // round-trip through the file (persistence across runs)
        save(f, s);
        Map<String, Entry> s2 = load(f);
        SelfTest.checkEq("round-trip keeps Suite B streak",
                1, s2.get("Suite B").consecutive());
        SelfTest.checkEq("round-trip keeps Suite A cleared",
                0, s2.get("Suite A").consecutive());
        SelfTest.check("round-trip: no phantom suites", s2.size() == 2);

        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        System.out.println("Usage: FlakyQuarantine.java --self-test");
        System.out.println("(Used by RunAllTests.java for flaky-suite quarantine state.)");
    }
}
