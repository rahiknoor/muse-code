// RunKeys.java — canonical writer + data contracts for
// ~/workspace/scheduler_state/completed_runs.json.
//
// This is the ONE writer all code uses for run keys (Rahik's rule, 2026-09-27:
// repeated steps become a method). Run keys are the double-delivery guard:
// if a job's exact key is already recorded, the retried/double-fired run must
// end quietly instead of emitting a duplicate digest, alert, or message.
// Corruption here = duplicate user-facing messages, so every write is atomic
// (temp file + rename) with a .bak backup, and --contract enforces the shape.
//
// Entry shapes in the wild (all preserved byte-for-byte; new writes use the
// dict shape, a superset of the run protocol's "record the key with the date"):
//   "job | 2026-09-27 | 08:46 ET": "2026-09-27"                        (string)
//   "job | 2026-09-27 | 08:46 ET": {"date": "...", "recorded_at": "..."} (object)
//   "runs": ["<run key>", ...]   (legacy list under the single "runs" key)
//
// Usage:
//   java --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/RunKeys.java check "<run key>"
//         -> prints "proceed" (exit 0) or "duplicate" (exit 3); exit 1 on error.
//            Exit 3 means: end immediately with no message and no further work.
//   java --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/RunKeys.java record "<run key>"
//         -> idempotent; prints "recorded" or "already-recorded" (exit 0).
//   java --class-path ~/workspace/java-shared/classes[:.../json-20240303.jar] \
//       ~/workspace/java-shared/RunKeys.java --contract
//         -> Great-Expectations-style checks on the live file (VERIFY OK/FAILED).
//   java --class-path ~/workspace/java-shared/classes[:.../json-20240303.jar] \
//       ~/workspace/java-shared/RunKeys.java --self-test
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RunKeys {

    /** Zone all run keys are stamped in. Public: reader tools (e.g. WaitFor)
     *  need the same zone the writer uses. */
    public static final ZoneId ZONE = ZoneId.of("America/New_York");
    /** Canonical run-key shape per shared/run_protocol.md (seconds optional).
     *  Public: reader tools match keys against this instead of redefining it. */
    public static final Pattern KEY_RX = Pattern.compile(
        "^(.+?) \\| (\\d{4}-\\d{2}-\\d{2}) \\| (\\d{2}):(\\d{2})(:(\\d{2}))? ET$");
    static final Pattern KEY_DATE_RX = Pattern.compile("\\| (\\d{4}-\\d{2}-\\d{2}) \\|");
    /** Legacy non-run-key entry, exempt from the key-shape check. */
    static final String LEGACY_LIST_KEY = "runs";

    /** One top-level entry: the unescaped key plus its raw `"key": value` text. */
    public static final class Entry {
        public final String key;
        public final String raw;
        /** Public: read-only tools (e.g. WaitFor) build entries when scanning. */
        public Entry(String key, String raw) { this.key = key; this.raw = raw; }
    }

    private RunKeys() { }

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /** The live state file. */
    public static Path stateFile() {
        return Paths.get(home(), "workspace", "scheduler_state", "completed_runs.json");
    }

    // ------------------------------------------------------------------
    // Reading: split the top-level object into entries, preserving each
    // entry's raw text so rewrites never reformat history.

    /** Parse the file into entries. Missing file -> empty list (not started). */
    public static List<Entry> readEntries(Path f) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        String src = Files.readString(f, StandardCharsets.UTF_8);
        for (String raw : splitEntries(src)) {
            String key = keyOf(raw);
            if (key != null) out.add(new Entry(key, raw));
        }
        return out;
    }

    /** Split a flat JSON object into its top-level `"key": value` substrings.
     *  Each raw entry keeps its exact source text INCLUDING surrounding
     *  whitespace, so a parse→write round trip is byte-identical.
     *  Public: read-only tools (e.g. WaitFor) scan entries without writing. */
    public static List<String> splitEntries(String src) {
        List<String> out = new ArrayList<>();
        int i = src.indexOf('{');
        if (i < 0) return out;
        i++; // past '{'; the next entry's leading whitespace starts here
        int n = src.length();
        while (true) {
            int entryStart = i;
            while (i < n && Character.isWhitespace(src.charAt(i))) i++;
            if (i >= n || src.charAt(i) == '}') break;
            if (src.charAt(i) != '"') break; // not the shape we know; bail safely
            i = endOfString(src, i);
            while (i < n && Character.isWhitespace(src.charAt(i))) i++;
            if (i < n && src.charAt(i) == ':') i++;
            while (i < n && Character.isWhitespace(src.charAt(i))) i++;
            if (i < n && src.charAt(i) == '{') i = endOfBalanced(src, i, '{', '}');
            else if (i < n && src.charAt(i) == '[') i = endOfBalanced(src, i, '[', ']');
            else if (i < n && src.charAt(i) == '"') i = endOfString(src, i);
            else { int j = i; while (j < n && src.charAt(j) != ',' && src.charAt(j) != '}') j++; i = j; }
            int valueEnd = i;
            while (i < n && Character.isWhitespace(src.charAt(i))) i++;
            if (i < n && src.charAt(i) == '}') {
                // Last entry: keep the whitespace before the closing brace,
                // so the epilogue (e.g. "\n" in "}\n}") round-trips exactly.
                out.add(src.substring(entryStart, i));
                break;
            }
            out.add(src.substring(entryStart, valueEnd));
            if (i < n && src.charAt(i) == ',') { i++; continue; }
            break; // end of input
        }
        return out;
    }

    /** Unescape the key of one raw `"key": value` entry; null if not parseable.
     *  Public: read-only tools (e.g. WaitFor) scan entries without writing. */
    public static String keyOf(String rawEntry) {
        String t = rawEntry.stripLeading();
        if (t.isEmpty() || t.charAt(0) != '"') return null;
        int end = endOfString(t, 0);
        if (end <= 1) return null;
        try {
            return unescapeJsonString(t.substring(1, end - 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static int endOfString(String s, int i) { // s[i] == '"'
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"') return i + 1;
            i++;
        }
        return i;
    }

    static int endOfBalanced(String s, int i, char open, char close) {
        int depth = 0;
        boolean inStr = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i += 2; continue; }
                if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == open) depth++;
                else if (c == close) { depth--; if (depth == 0) return i + 1; }
            }
            i++;
        }
        return i;
    }

    static String unescapeJsonString(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (i + 1 >= s.length()) throw new IllegalArgumentException("bad escape");
            char e = s.charAt(++i);
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 >= s.length()) throw new IllegalArgumentException("bad \\u");
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                    break;
                default: throw new IllegalArgumentException("bad escape \\" + e);
            }
        }
        return out.toString();
    }

    static String escapeJsonString(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Writing: atomic (temp + rename) with a .bak of the previous content.
    // Existing entries keep their raw text — history is never reformatted.

    /** Canonical value for a newly recorded key (superset of the run protocol). */
    public static String canonicalValue(String key) {
        Matcher m = KEY_DATE_RX.matcher(key);
        String date = m.find() ? m.group(1) : LocalDate.now(ZONE).toString();
        String ts = ZonedDateTime.now(ZONE)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));
        return "{\"date\": \"" + escapeJsonString(date)
            + "\", \"recorded_at\": \"" + escapeJsonString(ts) + "\"}";
    }

    /** Write entries atomically: temp file + .bak + rename. Never partial.
     *  Raws carry their own surrounding whitespace; join with bare commas.
     *  The file's trailer (e.g. a trailing newline after the final '}') is
     *  preserved, so a parse→write round trip is byte-identical. */
    public static void writeEntries(Path f, List<String> rawEntries) throws IOException {
        Files.createDirectories(f.getParent());
        String trailer = ""; // default: no trailing newline (matches the live file)
        if (Files.exists(f)) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            int close = src.lastIndexOf('}');
            trailer = close >= 0 ? src.substring(close + 1) : "";
            if (!trailer.isBlank()) trailer = ""; // never preserve trailing garbage
        }
        String body = "{" + String.join(",", rawEntries) + "}" + trailer;
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        if (Files.exists(f))
            Files.copy(f, f.resolveSibling(f.getFileName() + ".bak"),
                StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * True when the exact key is already recorded (retried/double-fired run).
     * A present-but-unparseable file is a loud failure (never "proceed": that
     * risks a duplicate delivery; never "duplicate": that would skip real work).
     */
    public static boolean isRecorded(Path f, String key) throws IOException {
        if (!Files.exists(f)) return false;
        String src = Files.readString(f, StandardCharsets.UTF_8);
        if (src.isBlank()) return false;
        List<String> raws = splitEntries(src);
        if (raws.isEmpty() && src.contains("\""))
            throw new IOException("completed_runs.json present but unparseable: " + f);
        for (String raw : raws) {
            if (key.equals(keyOf(raw))) return true;
        }
        return false;
    }

    /**
     * Idempotent record: returns true when the key was newly recorded, false
     * when it was already present (no write at all — bytes untouched).
     */
    public static boolean record(Path f, String key) throws IOException {
        List<Entry> entries = readEntries(f);
        for (Entry e : entries) {
            if (e.key.equals(key)) return false; // already recorded: no-op
        }
        List<String> raws = new ArrayList<>();
        for (Entry e : entries) raws.add(e.raw);
        // The previous last entry may carry the epilogue (whitespace before
        // the final '}'); move it onto the new last entry so separators stay
        // clean ("},\n \"new\"" — never "}\n,").
        String epilogue = "";
        if (!raws.isEmpty()) {
            String last = raws.get(raws.size() - 1);
            int e = last.length();
            while (e > 0 && Character.isWhitespace(last.charAt(e - 1))) e--;
            epilogue = last.substring(e);
            raws.set(raws.size() - 1, last.substring(0, e));
        }
        raws.add(entryPrefix(entries) + "\"" + escapeJsonString(key) + "\": "
            + canonicalValue(key) + epilogue);
        writeEntries(f, raws);
        // Post-write sanity: the file must parse back with the new key present.
        List<Entry> back = readEntries(f);
        boolean found = back.stream().anyMatch(e -> e.key.equals(key));
        if (!found || back.size() != entries.size() + 1)
            throw new IOException("post-write sanity failed for " + f);
        return true;
    }

    // ------------------------------------------------------------------
    // Data contracts (Great-Expectations style): valid JSON, no duplicate
    // keys, expected value shapes, sane run-key format.

    /** Hard failures: the file cannot be trusted for dedupe. */
    public static List<String> contractProblems(Path f) {
        List<String> problems = new ArrayList<>();
        if (!Files.exists(f)) {
            problems.add("state file missing: " + f);
            return problems;
        }
        String src;
        try {
            src = Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            problems.add("state file unreadable: " + e.getMessage());
            return problems;
        }
        // 1. Strict JSON validity (org.json is stricter than our splitter).
        // If org.json is not on the classpath, skip this check (splitter
        // checks below still apply); it is always present in sweep context.
        Map<String, Object> parsed = null;
        try {
            parsed = strictParse(src);
        } catch (ClassNotFoundException e) {
            strictJsonSkipped = true;
        } catch (Exception e) {
            problems.add("not valid JSON: " + e.getMessage());
            return problems;
        }
        if (parsed == null) {
            problems.add("top level is not a JSON object");
            return problems;
        }
        // 2. Duplicate keys (org.json keeps the last; scan the raw text).
        List<String> raws = splitEntries(src);
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String raw : raws) {
            String k = keyOf(raw);
            if (k == null) { problems.add("unparseable entry: " + abbrev(raw)); continue; }
            seen.merge(k, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : seen.entrySet())
            if (e.getValue() > 1)
                problems.add("duplicate run key (" + e.getValue() + "x): " + e.getKey());
        // 3+4. Key shape + value shape per entry.
        for (String raw : raws) {
            String k = keyOf(raw);
            if (k == null) continue; // already reported
            String shapeProblem = checkKeyShape(k);
            if (shapeProblem != null) problems.add(shapeProblem);
            String valueProblem = checkValueShape(k, raw);
            if (valueProblem != null) problems.add(valueProblem);
        }
        return problems;
    }

    /** Informational notes (not failures): shape census, legacy entries. */
    public static List<String> contractInfos(Path f) {
        List<String> infos = new ArrayList<>();
        if (strictJsonSkipped)
            infos.add("org.json not on classpath — strict JSON check skipped");
        List<Entry> entries;
        try {
            entries = readEntries(f);
        } catch (IOException e) {
            infos.add("could not read entries: " + e.getMessage());
            return infos;
        }
        int strings = 0, objects = 0, lists = 0;
        for (Entry e : entries) {
            String v = valueOf(e.raw);
            if (v.startsWith("\"")) strings++;
            else if (v.startsWith("{")) objects++;
            else if (v.startsWith("[")) lists++;
        }
        infos.add(entries.size() + " entries: " + strings + " string, "
            + objects + " object, " + lists + " list");
        for (Entry e : entries) {
            if (e.key.equals(LEGACY_LIST_KEY))
                infos.add("legacy \"" + LEGACY_LIST_KEY + "\" list key present (historical, exempt)");
            String v = valueOf(e.raw);
            if (v.startsWith("{") && !v.contains("\"date\"") && !v.contains("\"recorded"))
                infos.add("object entry without date field: " + e.key);
        }
        return infos;
    }

    /** Strict parse via org.json (on the sweep classpath). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> strictParse(String src) throws Exception {
        Class<?> jo = Class.forName("org.json.JSONObject");
        Object obj = jo.getConstructor(String.class).newInstance(src);
        return (Map<String, Object>) jo.getMethod("toMap").invoke(obj);
    }

    /** Set by contractProblems when org.json is absent from the classpath. */
    static boolean strictJsonSkipped = false;

    /** The value half of a raw `"key": value` entry. */
    static String valueOf(String rawEntry) {
        String t = rawEntry.stripLeading();
        int i = endOfString(t, 0);
        int c = t.indexOf(':', i);
        return c < 0 ? "" : t.substring(c + 1).strip();
    }

    /** Leading-whitespace style of existing entries, for new writes. */
    static String entryPrefix(List<Entry> entries) {
        for (Entry e : entries) {
            String t = e.raw;
            int i = 0;
            while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
            if (i > 0) return t.substring(0, i);
        }
        return "\n ";
    }

    /** Null when the key matches the protocol shape (or is the legacy key). */
    static String checkKeyShape(String key) {
        if (key.equals(LEGACY_LIST_KEY)) return null;
        Matcher m = KEY_RX.matcher(key);
        if (!m.matches()) return "key not in '<job> | YYYY-MM-DD | HH:MM ET' shape: " + key;
        if (m.group(1).isBlank()) return "key has empty job id: " + key;
        try {
            LocalDate.parse(m.group(2));
        } catch (Exception e) {
            return "key has invalid date: " + key;
        }
        int hh = Integer.parseInt(m.group(3)), mm = Integer.parseInt(m.group(4));
        if (hh > 23 || mm > 59) return "key has invalid time: " + key;
        if (m.group(6) != null) {
            int ss = Integer.parseInt(m.group(6));
            if (ss > 59) return "key has invalid seconds: " + key;
        }
        return null;
    }

    /** Null when the value matches one of the known shapes. */
    static String checkValueShape(String key, String rawEntry) {
        String v = valueOf(rawEntry);
        if (v.isEmpty()) return "entry has no value: " + key;
        char c = v.charAt(0);
        if (c == '[') {
            if (!key.equals(LEGACY_LIST_KEY))
                return "array value under non-legacy key: " + key;
            return null;
        }
        if (c == '{') return null; // object shapes vary; date presence is INFO
        if (c == '"') {
            String inner;
            try {
                inner = unescapeJsonString(v.substring(1, endOfString(v, 0) - 1));
            } catch (IllegalArgumentException e) {
                return "string value not valid JSON: " + key;
            }
            if (!inner.matches("\\d{4}-\\d{2}-\\d{2}.*"))
                return "string value not date-shaped: " + key;
            return null;
        }
        return "unexpected value shape for key: " + key;
    }

    static String abbrev(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }

    // ------------------------------------------------------------------
    // CLI

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: RunKeys.java check|record \"<run key>\" | --contract | --self-test");
            System.exit(2);
        }
        Path f = stateFile();
        switch (args[0]) {
            case "check": {
                if (args.length < 2) { System.out.println("check needs a run key"); System.exit(2); }
                try {
                    if (isRecorded(f, args[1])) { System.out.println("duplicate"); System.exit(3); }
                    System.out.println("proceed");
                } catch (IOException e) {
                    System.out.println("ERROR: " + e.getMessage());
                    System.exit(1);
                }
                break;
            }
            case "record": {
                if (args.length < 2) { System.out.println("record needs a run key"); System.exit(2); }
                System.out.println(record(f, args[1]) ? "recorded" : "already-recorded");
                break;
            }
            case "--contract": {
                List<String> problems = contractProblems(f);
                List<String> infos = contractInfos(f);
                Verify.report(
                    "VERIFY OK (RunKeys): completed_runs.json contracts hold (" + infos.get(0) + ").",
                    "VERIFY FAILED (RunKeys): completed_runs.json contract violation(s):",
                    infos, problems);
                break;
            }
            case "--self-test":
                selfTest();
                break;
            default:
                System.out.println("unknown mode: " + args[0]);
                System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Self-test on synthetic files (never touches the live state file).

    static void selfTest() throws Exception {
        Path t = Files.createTempDirectory("runkeys-test");
        Path f = t.resolve("completed_runs.json");

        // 1. record a new key -> canonical dict value, valid JSON
        String k1 = "test-job | 2026-09-28 | 08:46 ET";
        SelfTest.check("record new key returns true", record(f, k1));
        String body = Files.readString(f, StandardCharsets.UTF_8);
        SelfTest.checkContains("canonical value has date", body, "\"date\": \"2026-09-28\"");
        SelfTest.checkContains("canonical value has recorded_at", body, "\"recorded_at\"");
        SelfTest.check("new key is recorded", isRecorded(f, k1));

        // 2. record the same key again -> idempotent, bytes untouched
        long sizeBefore = Files.size(f);
        String bytesBefore = Files.readString(f, StandardCharsets.UTF_8);
        SelfTest.check("record duplicate returns false", !record(f, k1));
        SelfTest.checkEq("duplicate record leaves bytes untouched",
            bytesBefore, Files.readString(f, StandardCharsets.UTF_8));
        SelfTest.check("no duplicate key in file",
            contractProblems(f).stream().noneMatch(p -> p.startsWith("duplicate run key")));

        // 3. check: unrecorded -> proceed, recorded -> duplicate
        SelfTest.check("unrecorded key not recorded",
            !isRecorded(f, "other-job | 2026-09-28 | 09:46 ET"));

        // 4. all legacy shapes survive a read+rewrite round trip byte-identical
        Path f2 = t.resolve("mixed.json");
        String mixed = "{\n"
            + " \"a | 2026-09-28 | 08:46 ET\": \"2026-09-28\",\n"
            + " \"b | 2026-09-28 | 08:47 ET\": {\"date\": \"2026-09-28\", \"recorded_at\": \"x\"},\n"
            + " \"runs\": [\"a | 2026-09-28 | 08:46 ET\", \"b | 2026-09-28 | 08:47 ET\"],\n"
            + " \"c | 2026-09-28 | 08:48 ET\": [\"x\", \"y\"]}";
        Files.writeString(f2, mixed, StandardCharsets.UTF_8);
        List<Entry> entries = readEntries(f2);
        SelfTest.checkEq("mixed shapes parse to 4 entries", 4, entries.size());
        List<String> raws = new ArrayList<>();
        for (Entry e : entries) raws.add(e.raw);
        writeEntries(f2, raws);
        SelfTest.checkEq("round trip byte-identical", mixed,
            Files.readString(f2, StandardCharsets.UTF_8));
        // contract on the mixed file: only the non-legacy array is a problem
        List<String> probs = contractProblems(f2);
        SelfTest.checkEq("one contract problem (array under c)", 1, probs.size());
        SelfTest.checkContains("problem names the key",
            probs.get(0), "c | 2026-09-28 | 08:48 ET");

        // 5. contract catches a bad key shape
        Path f3 = t.resolve("badkey.json");
        Files.writeString(f3, "{\n \"oops-no-pipes\": \"2026-09-28\"\n}",
            StandardCharsets.UTF_8);
        SelfTest.check("bad key shape flagged",
            contractProblems(f3).stream().anyMatch(p -> p.contains("not in '<job>")));

        // 6. contract catches invalid JSON
        Path f4 = t.resolve("broken.json");
        Files.writeString(f4, "{ not json", StandardCharsets.UTF_8);
        SelfTest.check("invalid JSON flagged",
            !contractProblems(f4).isEmpty());

        // 7. key with characters needing escapes round-trips
        String tricky = "job \"quoted\" \\ | 2026-09-28 | 08:46 ET";
        SelfTest.check("tricky key records", record(f, tricky));
        SelfTest.check("tricky key found again", isRecorded(f, tricky));

        // 8. no temp file left behind after writes
        try (var s = Files.list(t)) {
            SelfTest.check("no .tmp left behind",
                s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
        // 9. .bak written on second write
        SelfTest.check(".bak exists after rewrite", Files.exists(
            f.resolveSibling(f.getFileName() + ".bak")));

        SelfTest.finish();
    }
}
