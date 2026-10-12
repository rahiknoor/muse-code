// LeanRead.java — read planner for token efficiency (BLG-023, 2026-10-05).
//
// PROBLEM: a subagent reading 18 tutorial transcripts (~65k words) inherited
// the full parent context, so the same file words were paid for twice — once
// in the parent context the subagent inherited, once in the file read itself.
// FIX: big reads go to FRESH lean subagents (or the agent reads directly and
// hands only the distillate onward), so file content is never multiplied by a
// fat parent context. This tool plans the read; it never multiplies content.
//
// MODES:
//   --plan <file...>   for each file prints bytes + word count, then ONE
//                      recommendation: DIRECT, LEAN, or CHUNKED.
//   --self-test        prove-can-fail suite (SelfTest.java harness).
//   --help             prints the thresholds and their justifications.
//
// THRESHOLDS (in words; whitespace-split count):
//   <= 20,000 words   -> DIRECT  — read it yourself in your own context.
//   <= 100,000 words  -> LEAN    — spawn ONE fresh lean subagent holding ONLY
//                                 the file + the question; it hands back the
//                                 distillate, never the raw words.
//   > 100,000 words   -> CHUNKED — split into ~20,000-word chunks, distill
//                                 each (lean subagent per chunk or sequential
//                                 lean reads), then merge the distillates.
//
// WHY THESE NUMBERS (one line each):
//   DIRECT <=20k:  ~20k words ~= 27k tokens fits inside one working context
//                  with room to reason — below this, spawning a subagent
//                  costs more overhead than it saves.
//   LEAN  <=100k: ~100k words would drown the working chat, but one fresh
//                  subagent reads it faithfully in a single pass (the
//                  incident's 65k-word tutorial set is exactly this case) —
//                  pay for the words once, in a lean context, and take back
//                  only the distillate.
//   CHUNKED >100k: past ~135k tokens a single read gets lossy (the agent
//                  starts skimming); ~20k-word chunks keep every distill
//                  pass faithful, and merging distillates costs a fraction
//                  of re-reading the raw text.
//
// EDGE CASES:
//   Binary files (NUL byte in the first 8 KB) are flagged BINARY — word
//   count shown as -, recommendation DIRECT with a warning: never send raw
//   bytes to a subagent; keep the decision local.
//   Missing/unreadable files are FAIL-CLOSED: no plan is emitted at all,
//   an ERROR line names the file, and the exit code is 1.
//
// Launch (shared SelfTest on the class path):
//   ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/LeanRead.java --plan <file...>
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LeanRead {

    /** UTF-8 output (same rationale as SelfTest: POSIX locales corrupt
     *  non-ASCII). Non-final so --self-test can swap in a capture stream. */
    static PrintStream OUT =
            new PrintStream(System.out, true, StandardCharsets.UTF_8);

    /** Read-it-yourself ceiling, in words. */
    static final long DIRECT_MAX_WORDS = 20_000;
    /** One-lean-subagent ceiling, in words. */
    static final long LEAN_MAX_WORDS = 100_000;
    /** Target chunk size for CHUNKED plans, in words. */
    static final long CHUNK_WORDS = 20_000;
    /** Bytes sniffed to decide binary vs text. */
    static final int BINARY_SNIFF_BYTES = 8_192;

    /** Working-context window, in words (~200k tokens). Overridable. */
    static final long DEFAULT_WINDOW_WORDS = 150_000;
    /** Even in a full context, a tiny read stays direct. */
    static final long DIRECT_FLOOR_WORDS = 2_000;

    enum Rec { DIRECT, LEAN, CHUNKED }

    /** -1 words = binary (not counted as text). */
    record FileStats(Path path, long bytes, long words, boolean binary) {}

    private LeanRead() {
        // static-only
    }

    // ------------------------------------------------------------------
    // Recommendation

    static Rec recommend(long words) {
        return recommend(words, DIRECT_MAX_WORDS);
    }

    /** Direct ceiling shrinks linearly as the caller's context fills:
     *  empty context -> 20k; context at/over the window -> 2k floor.
     *  LEAN/CHUNKED ceilings never scale — a fresh subagent's context is
     *  always lean, so only DIRECT (reads into THIS context) adapts. */
    static long directMaxFor(long contextWords, long windowWords) {
        if (windowWords <= 0 || contextWords <= 0) {
            return DIRECT_MAX_WORDS;
        }
        long scaled = DIRECT_MAX_WORDS * (windowWords - contextWords)
                / windowWords;
        return Math.max(DIRECT_FLOOR_WORDS,
                Math.min(DIRECT_MAX_WORDS, scaled));
    }

    static Rec recommend(long words, long directMax) {
        if (words <= directMax) {
            return Rec.DIRECT;
        }
        if (words <= LEAN_MAX_WORDS) {
            return Rec.LEAN;
        }
        return Rec.CHUNKED;
    }

    static long chunkCount(long words) {
        return (words + CHUNK_WORDS - 1) / CHUNK_WORDS;
    }

    // ------------------------------------------------------------------
    // Inspection

    /** Whitespace-split word count, streamed so huge files never sit in
     *  memory. Bad UTF-8 bytes decode as the replacement character (graceful:
     *  a stray bad byte never aborts the count). */
    static long countWords(Path p) throws IOException {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        long words = 0;
        boolean inWord = false;
        CharBuffer out = CharBuffer.allocate(8192);
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.clear();
                dec.decode(ByteBuffer.wrap(buf, 0, n), out, false);
                out.flip();
                while (out.hasRemaining()) {
                    char c = out.get();
                    if (Character.isWhitespace(c)) {
                        inWord = false;
                    } else if (!inWord) {
                        inWord = true;
                        words++;
                    }
                }
            }
        }
        return words;
    }

    /** A NUL byte in the first 8 KB means binary for our purposes. */
    static boolean looksBinary(Path p) throws IOException {
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[BINARY_SNIFF_BYTES];
            int n = in.read(buf);
            if (n <= 0) {
                return false; // empty file is not binary
            }
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns null when the file is missing or unreadable (fail-closed). */
    static FileStats inspect(Path p) {
        try {
            if (!Files.isRegularFile(p)) {
                return null;
            }
            long bytes = Files.size(p);
            boolean binary = looksBinary(p);
            long words = binary ? -1 : countWords(p);
            return new FileStats(p, bytes, words, binary);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // --plan

    /** Exit codes: 0 = plan printed for every file; 1 = a file was missing
     *  or unreadable (fail-closed: NO plan is emitted); 2 = usage. */
    static int plan(List<Path> files) {
        return plan(files, 0, DEFAULT_WINDOW_WORDS);
    }

    static int plan(List<Path> files, long contextWords, long windowWords) {
        // Pass 1: validate everything before printing anything. A missing
        // file aborts the whole plan — never half a recommendation set.
        List<FileStats> stats = new ArrayList<>();
        for (Path p : files) {
            FileStats s = inspect(p);
            if (s == null) {
                OUT.println("ERROR: " + p
                        + ": missing or unreadable — plan aborted (fail-closed).");
                return 1;
            }
            stats.add(s);
        }
        // Pass 2: one recommendation per file.
        long directMax = directMaxFor(contextWords, windowWords);
        if (directMax != DIRECT_MAX_WORDS) {
            OUT.println("(context-aware: your context holds ~" + contextWords
                    + " words, so DIRECT covers up to " + directMax
                    + " words this turn.)");
        }
        for (FileStats s : stats) {
            printPlan(s, directMax);
        }
        return 0;
    }

    static void printPlan(FileStats s) {
        printPlan(s, DIRECT_MAX_WORDS);
    }

    static void printPlan(FileStats s, long directMax) {
        OUT.println(s.path());
        OUT.println("  bytes: " + s.bytes() + ", words: "
                + (s.binary() ? "- (binary)" : s.words()));
        if (s.binary()) {
            OUT.println("  -> DIRECT (binary: not counted as text — keep it "
                    + "local; never send raw bytes to a subagent).");
            return;
        }
        Rec r = recommend(s.words(), directMax);
        switch (r) {
            case DIRECT -> OUT.println("  -> DIRECT: small enough to read "
                    + "yourself in your own context.");
            case LEAN -> {
                OUT.println("  -> LEAN: spawn ONE fresh lean "
                        + "subagent holding ONLY the file + your question; "
                        + "it hands back the distillate, never the raw words.");
                GuardFiring.log("lean-read",
                        s.path() + " rec=LEAN words=" + s.words());
            }
            case CHUNKED -> {
                OUT.println("  -> CHUNKED: split into ~"
                        + CHUNK_WORDS + "-word chunks (about "
                        + chunkCount(s.words())
                        + " chunks), distill each, then merge the distillates.");
                GuardFiring.log("lean-read",
                        s.path() + " rec=CHUNKED words=" + s.words());
            }
        }
    }

    // ------------------------------------------------------------------
    // Distillate cache (2026-10-09, Rahik's "do it")
    //
    // PROBLEM: the same big files (audit README, queue files, run logs)
    // were re-read at full price every time. A distillate is reusable
    // until the file changes, so cache it keyed by (file content hash +
    // question): a changed file or a different question never hits stale.
    //
    //   --cache-put <file> --question <q> --distillate-file <f>
    //   --cache-get <file> --question <q>   (exit 0 + distillate on hit,
    //                                        exit 1 + MISS reason otherwise)
    //   --cache-clear

    /** Cache file lives next to the other scheduler state. */
    static Path defaultCacheFile() {
        return Path.of(System.getenv("HOME"),
                "workspace", "scheduler_state", "leanread_cache.json");
    }

    /** Max entries kept; oldest evicted first. */
    static final int CACHE_MAX_ENTRIES = 200;
    /** A distillate is short by design — refuse anything bigger. */
    static final long CACHE_MAX_DISTILLATE_BYTES = 256 * 1024;

    record CacheEntry(String file, String fileSha256, String question,
                       String distillate, String cachedAt) {}

    static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String fileSha256(Path p) throws IOException {
        return sha256Hex(Files.readAllBytes(p));
    }

    /** Lookup key binds the file CONTENT hash and the question. */
    static String cacheKey(String fileSha256, String question) {
        return sha256Hex((fileSha256 + "\n" + question)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Load cache entries; missing file = empty. Malformed JSON is
     *  fail-closed: treated as empty, never half-read. */
    static Map<String, CacheEntry> loadCache(Path cacheFile) {
        Map<String, CacheEntry> out = new LinkedHashMap<>();
        try {
            if (!Files.exists(cacheFile)) {
                return out;
            }
            String text =
                    Files.readString(cacheFile, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                return out;
            }
            Object root = Json.parse(text);
            if (!(root instanceof Map)) {
                return out;
            }
            Object entries = ((Map<?, ?>) root).get("entries");
            if (!(entries instanceof Map)) {
                return out;
            }
            for (Map.Entry<?, ?> e : ((Map<?, ?>) entries).entrySet()) {
                if (!(e.getValue() instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) e.getValue();
                Object f = m.get("file");
                Object h = m.get("file_sha256");
                Object q = m.get("question");
                Object d = m.get("distillate");
                Object c = m.get("cached_at");
                if (f instanceof String && h instanceof String
                        && q instanceof String && d instanceof String
                        && c instanceof String) {
                    out.put(String.valueOf(e.getKey()), new CacheEntry(
                            (String) f, (String) h, (String) q,
                            (String) d, (String) c));
                }
            }
        } catch (Exception ex) {
            out.clear(); // corrupt cache -> empty, never poison a read
        }
        return out;
    }

    static void saveCache(Path cacheFile, Map<String, CacheEntry> entries)
            throws IOException {
        StringBuilder sb = new StringBuilder("{\"entries\": {");
        boolean first = true;
        for (Map.Entry<String, CacheEntry> e : entries.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            CacheEntry v = e.getValue();
            sb.append("\n  \"").append(jsonEsc(e.getKey())).append("\": {")
              .append("\"file\": \"").append(jsonEsc(v.file())).append("\", ")
              .append("\"file_sha256\": \"").append(v.fileSha256())
              .append("\", ")
              .append("\"question\": \"").append(jsonEsc(v.question()))
              .append("\", ")
              .append("\"distillate\": \"").append(jsonEsc(v.distillate()))
              .append("\", ")
              .append("\"cached_at\": \"").append(jsonEsc(v.cachedAt()))
              .append("\"}");
        }
        if (!first) {
            sb.append('\n');
        }
        sb.append("}}\n");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, sb.toString(), StandardCharsets.UTF_8);
    }

    static String jsonEsc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Hit prints the distillate and exits 0; miss prints the reason and
     *  exits 1. Stale entries (file changed since caching) are pruned. */
    static int cacheGet(Path cacheFile, Path file, String question) {
        try {
            if (!Files.isRegularFile(file)) {
                OUT.println("MISS: file missing or unreadable: " + file);
                return 1;
            }
            String hash = fileSha256(file);
            Map<String, CacheEntry> entries = loadCache(cacheFile);
            CacheEntry hit = entries.get(cacheKey(hash, question));
            if (hit == null) {
                boolean pruned = entries.values().removeIf(v ->
                        v.question().equals(question)
                        && v.file().equals(file.toString())
                        && !v.fileSha256().equals(hash));
                if (pruned) {
                    try {
                        saveCache(cacheFile, entries);
                    } catch (IOException ignored) {
                        // pruning is hygiene, not load-bearing
                    }
                }
                OUT.println("MISS: no distillate cached for this file+question"
                        + " (or the file changed since it was cached).");
                return 1;
            }
            OUT.print(hit.distillate());
            if (!hit.distillate().endsWith("\n")) {
                OUT.println();
            }
            return 0;
        } catch (IOException ex) {
            OUT.println("MISS: " + ex.getMessage());
            return 1;
        }
    }

    static int cachePut(Path cacheFile, Path file, String question,
                        Path distillateFile) {
        try {
            if (!Files.isRegularFile(file)) {
                OUT.println("ERROR: file missing or unreadable: " + file);
                return 1;
            }
            if (!Files.isRegularFile(distillateFile)) {
                OUT.println("ERROR: distillate file missing: "
                        + distillateFile);
                return 1;
            }
            long size = Files.size(distillateFile);
            if (size > CACHE_MAX_DISTILLATE_BYTES) {
                OUT.println("ERROR: distillate too large (" + size
                        + " bytes > " + CACHE_MAX_DISTILLATE_BYTES
                        + ") — a distillate is short by design.");
                return 1;
            }
            String hash = fileSha256(file);
            String distillate = Files.readString(distillateFile,
                    StandardCharsets.UTF_8);
            Map<String, CacheEntry> entries = loadCache(cacheFile);
            entries.put(cacheKey(hash, question), new CacheEntry(
                    file.toString(), hash, question, distillate,
                    Instant.now().toString()));
            while (entries.size() > CACHE_MAX_ENTRIES) {
                entries.remove(entries.keySet().iterator().next());
            }
            saveCache(cacheFile, entries);
            OUT.println("CACHED: " + file + " (sha256 "
                    + hash.substring(0, 12) + "…, question "
                    + question.length() + " chars).");
            return 0;
        } catch (IOException ex) {
            OUT.println("ERROR: " + ex.getMessage());
            return 1;
        }
    }

    static int cacheClear(Path cacheFile) {
        try {
            Files.deleteIfExists(cacheFile);
            OUT.println("cache cleared");
            return 0;
        } catch (IOException ex) {
            OUT.println("ERROR: " + ex.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------
    // Targeted JSON extract (2026-10-09, Rahik's "do it")
    //
    // PROBLEM: for the JSON backends we usually need five keys, not the
    // whole file — but LeanRead routed the entire file through a read.
    // --extract pulls ONLY the requested dotted paths (a.b.0.c), printing
    // "path = <compact JSON>". Any missing path is fail-closed (exit 1,
    // names the path) — a silent null on a valuation key would be worse
    // than no extract at all.

    /** Sentinel for "path not found" (JSON null is a real value). */
    static final Object MISSING = new Object();

    /** A JSON number with its original text preserved verbatim. */
    record JsonNumber(String text) {}

    /** Minimal JSON parser: objects -> LinkedHashMap, arrays -> ArrayList,
     *  strings -> String, numbers -> JsonNumber, true/false -> Boolean,
     *  null -> null. Just enough for --extract and the cache file. */
    static final class Json {
        final String t;
        int i;

        Json(String t) {
            this.t = t;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.t.length()) {
                throw new IllegalArgumentException("trailing characters");
            }
            return v;
        }

        void ws() {
            while (i < t.length()) {
                char c = t.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (i >= t.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return t.charAt(i);
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException(
                        "expected '" + c + "' but saw '" + peek() + "'");
            }
            i++;
        }

        Object value() {
            ws();
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default: return number();
            }
        }

        Object literal(String word, Object v) {
            if (!t.startsWith(word, i)) {
                throw new IllegalArgumentException("bad literal");
            }
            i += word.length();
            return v;
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    break;
                }
                throw new IllegalArgumentException("expected ',' or '}'");
            }
            return m;
        }

        List<Object> array() {
            expect('[');
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    break;
                }
                throw new IllegalArgumentException("expected ',' or ']'");
            }
            return l;
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
                    char e = peek();
                    i++;
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': {
                            if (i + 4 > t.length()) {
                                throw new IllegalArgumentException(
                                        "bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(
                                    t.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        }
                        default:
                            throw new IllegalArgumentException("bad escape");
                    }
                } else {
                    sb.append(c);
                    i++;
                }
            }
        }

        JsonNumber number() {
            int start = i;
            if (i < t.length() && t.charAt(i) == '-') {
                i++;
            }
            while (i < t.length()) {
                char c = t.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e'
                        || c == 'E' || c == '+' || c == '-') {
                    i++;
                } else {
                    break;
                }
            }
            if (i == start) {
                throw new IllegalArgumentException("bad number");
            }
            return new JsonNumber(t.substring(start, i));
        }
    }

    /** Compact JSON rendering (numbers verbatim, strings escaped). */
    static String toJson(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return "\"" + jsonEsc(s) + "\"";
        }
        if (v instanceof JsonNumber n) {
            return n.text();
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(jsonEsc(String.valueOf(e.getKey())))
                  .append("\":").append(toJson(e.getValue()));
            }
            return sb.append("}").toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : l) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append(toJson(o));
            }
            return sb.append("]").toString();
        }
        throw new IllegalArgumentException("not a JSON value");
    }

    /** Resolve a dotted path (numeric segments index arrays). */
    static Object resolvePath(Object root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.", -1)) {
            if (cur instanceof Map<?, ?> m) {
                if (!m.containsKey(seg)) {
                    return MISSING;
                }
                cur = m.get(seg);
            } else if (cur instanceof List<?> l) {
                int idx;
                try {
                    idx = Integer.parseInt(seg);
                } catch (NumberFormatException e) {
                    return MISSING;
                }
                if (idx < 0 || idx >= l.size()) {
                    return MISSING;
                }
                cur = l.get(idx);
            } else {
                return MISSING;
            }
        }
        return cur;
    }

    static int extract(Path file, List<String> paths) {
        String text;
        try {
            if (!Files.isRegularFile(file)) {
                OUT.println("ERROR: " + file + ": missing or unreadable.");
                return 1;
            }
            text = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            OUT.println("ERROR: " + file + ": " + e.getMessage());
            return 1;
        }
        if (!(text.startsWith("{") || text.startsWith("["))) {
            OUT.println("ERROR: " + file + ": not JSON — --extract is for"
                    + " structured JSON files only.");
            return 1;
        }
        Object root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            OUT.println("ERROR: " + file + ": invalid JSON ("
                    + e.getMessage() + ").");
            return 1;
        }
        boolean ok = true;
        for (String p : paths) {
            Object v = resolvePath(root, p);
            if (v == MISSING) {
                OUT.println("ERROR: path missing: " + p);
                ok = false;
            } else {
                OUT.println(p + " = " + toJson(v));
            }
        }
        return ok ? 0 : 1;
    }

    // ------------------------------------------------------------------
    // Tail reads (2026-10-09, Rahik's "do it")
    //
    // PROBLEM: run logs and outcome logs are append-only — we almost
    // always need just the tail, but LeanRead routed the whole file
    // through a read. --tail prints the last N lines by seeking backwards
    // from the end in blocks; a 50MB log never sits in memory.

    /** Default line count when --lines is omitted. */
    static final long TAIL_DEFAULT_LINES = 50;

    static int tail(Path file, long lines) {
        if (lines <= 0) {
            OUT.println("ERROR: --lines needs a positive number.");
            return 1;
        }
        try (var ch = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.READ)) {
            long size = ch.size();
            if (size == 0) {
                return 0; // empty file: no lines, exit 0
            }
            // Does the file end with a newline? A trailing \n terminates
            // the last line (needs lines+1 newlines to delimit N lines);
            // an unterminated last line needs only lines.
            java.nio.ByteBuffer one = java.nio.ByteBuffer.allocate(1);
            ch.read(one, size - 1);
            boolean terminated = one.get(0) == '\n';
            long need = terminated ? lines + 1 : lines;
            long pos = size;
            long found = 0;
            long start = 0;
            byte[] buf = new byte[8192];
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
            outer:
            while (pos > 0) {
                long chunk = Math.min(buf.length, pos);
                pos -= chunk;
                bb.clear();
                bb.limit((int) chunk);
                int read = ch.read(bb, pos);
                for (int k = read - 1; k >= 0; k--) {
                    if (buf[k] == '\n') {
                        found++;
                        if (found == need) {
                            start = pos + k + 1;
                            break outer;
                        }
                    }
                }
            }
            long len = size - start;
            byte[] out = new byte[(int) len];
            java.nio.ByteBuffer dst = java.nio.ByteBuffer.wrap(out);
            long off = start;
            while (dst.hasRemaining()) {
                int r = ch.read(dst, off);
                if (r <= 0) {
                    break;
                }
                off += r;
            }
            OUT.write(out, 0, (int) (off - start));
            OUT.flush();
            return 0;
        } catch (IOException e) {
            OUT.println("ERROR: " + file + ": " + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------
    // --help

    static void printHelp() {
        OUT.println("LeanRead — read planner for token efficiency (BLG-023).");
        OUT.println();
        OUT.println("Usage:");
        OUT.println("  LeanRead.java --plan <file...>   plan reads for the files");
        OUT.println("      [--context-words N] [--window-words N]: shrink the");
        OUT.println("      DIRECT ceiling as your current context fills.");
        OUT.println("  LeanRead.java --tail <file> [--lines N]   print the last");
        OUT.println("      N lines (default 50) without loading the whole file.");
        OUT.println("  LeanRead.java --extract <file> --path <dotted> [--path ...]");
        OUT.println("      pull ONLY the dotted paths from a JSON file");
        OUT.println("      (a.b.0.c; numeric segments index arrays).");
        OUT.println("  LeanRead.java --cache-get <file> --question <q>");
        OUT.println("      print the cached distillate on hit (exit 0),");
        OUT.println("      MISS + exit 1 when nothing cached or file changed.");
        OUT.println("  LeanRead.java --cache-put <file> --question <q>");
        OUT.println("      --distillate-file <f>   cache a distillate.");
        OUT.println("  LeanRead.java --cache-clear   drop the whole cache.");
        OUT.println("  LeanRead.java --self-test        run the self-test suite");
        OUT.println("  LeanRead.java --help             this text");
        OUT.println();
        OUT.println("Thresholds (word counts are whitespace-split):");
        OUT.println("  <= 20,000 words  DIRECT  — read it yourself. ~20k words "
                + "(~27k tokens) fits one working context with room to reason;");
        OUT.println("                             below this, spawning a "
                + "subagent costs more overhead than it saves.");
        OUT.println("  <= 100,000 words LEAN    — one fresh lean subagent "
                + "holding ONLY the file + the question hands back the");
        OUT.println("                             distillate. ~100k words "
                + "would drown the working chat but reads faithfully in one");
        OUT.println("                             lean pass — pay for the "
                + "words once, in a lean context.");
        OUT.println("  > 100,000 words  CHUNKED — ~20,000-word chunks, distill "
                + "each, merge the distillates. Past ~135k tokens a single");
        OUT.println("                             read gets lossy; chunks "
                + "keep every distill pass faithful.");
        OUT.println();
        OUT.println("Binary files (NUL byte in first 8 KB) are flagged and "
                + "default to DIRECT with a keep-it-local warning.");
        OUT.println("Missing/unreadable files are fail-closed: no plan is "
                + "emitted, exit 1.");
    }

    // ------------------------------------------------------------------
    // --self-test

    /** Writes a file with exactly {@code n} whitespace-separated words. */
    static Path writeWords(Path dir, String name, long n) throws IOException {
        Path f = dir.resolve(name);
        StringBuilder sb = new StringBuilder();
        for (long i = 0; i < n; i++) {
            sb.append("w").append(i % 10).append(' ');
        }
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        return f;
    }

    static String capturePlan(List<Path> files, int[] exitOut) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream capture =
                new PrintStream(buf, true, StandardCharsets.UTF_8);
        PrintStream saved = OUT;
        OUT = capture;
        try {
            exitOut[0] = plan(files);
        } finally {
            OUT = saved;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** Run a CLI-shaped lambda against a captured OUT. */
    interface Cli {
        int run() throws Exception;
    }

    static String captureCli(Cli cli, int[] exitOut) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream capture =
                new PrintStream(buf, true, StandardCharsets.UTF_8);
        PrintStream saved = OUT;
        OUT = capture;
        try {
            exitOut[0] = cli.run();
        } finally {
            OUT = saved;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    static void selfTest() throws Exception {
        GuardFiring.setEnabled(false); // firing paths must not pollute the real log
        Path dir = Files.createTempDirectory("leanread");

        // 1. Word counting: whitespace split, exact cases.
        Path t1 = dir.resolve("t1.txt");
        Files.writeString(t1, "hello world", StandardCharsets.UTF_8);
        SelfTest.checkEq("two words counted", 2L, countWords(t1));
        Path t2 = dir.resolve("t2.txt");
        Files.writeString(t2, "a  b\tc\nd  ", StandardCharsets.UTF_8);
        SelfTest.checkEq("mixed whitespace counts once per word", 4L,
                countWords(t2));
        Path t3 = dir.resolve("t3.txt");
        Files.writeString(t3, "", StandardCharsets.UTF_8);
        SelfTest.checkEq("empty file is zero words", 0L, countWords(t3));
        Path t4 = dir.resolve("t4.txt");
        Files.writeString(t4, "caf\u00e9 \u2615 latte",
                StandardCharsets.UTF_8);
        SelfTest.checkEq("unicode words counted", 3L, countWords(t4));
        Path t5 = dir.resolve("t5.txt");
        Files.writeString(t5, "   \n\t  ", StandardCharsets.UTF_8);
        SelfTest.checkEq("whitespace-only is zero words", 0L, countWords(t5));

        // 2. Threshold boundaries: just under / at / just over each line.
        SelfTest.checkEq("19,999 words -> DIRECT", Rec.DIRECT,
                recommend(19_999));
        SelfTest.checkEq("20,000 words -> DIRECT", Rec.DIRECT,
                recommend(20_000));
        SelfTest.checkEq("20,001 words -> LEAN", Rec.LEAN,
                recommend(20_001));
        SelfTest.checkEq("99,999 words -> LEAN", Rec.LEAN,
                recommend(99_999));
        SelfTest.checkEq("100,000 words -> LEAN", Rec.LEAN,
                recommend(100_000));
        SelfTest.checkEq("100,001 words -> CHUNKED", Rec.CHUNKED,
                recommend(100_001));
        SelfTest.checkEq("100,001 words -> 6 chunks", 6L,
                chunkCount(100_001));
        SelfTest.checkEq("40,000 words -> 2 chunks", 2L,
                chunkCount(40_000));

        // 3. Multi-file plan: one of each recommendation, bytes + words shown.
        Path small = writeWords(dir, "small.txt", 10);
        Path medium = writeWords(dir, "medium.txt", 20_001);
        Path large = writeWords(dir, "large.txt", 100_001);
        int[] exit = new int[1];
        String out = capturePlan(List.of(small, medium, large), exit);
        SelfTest.check("multi-file plan exits 0", exit[0] == 0);
        SelfTest.checkOutput("multi-file plan names each recommendation",
                out,
                new String[]{
                    "small.txt", "-> DIRECT",
                    "medium.txt", "-> LEAN",
                    "large.txt", "-> CHUNKED"},
                new String[]{"ERROR"});
        SelfTest.checkContains("plan prints word counts", out, "words:");
        SelfTest.checkContains("plan prints byte counts", out, "bytes:");

        // 4. Missing file: fail-closed — clear error, non-zero, no plan.
        String missOut = capturePlan(
                List.of(small, dir.resolve("no-such-file.txt")), exit);
        SelfTest.check("missing file exits non-zero", exit[0] != 0);
        SelfTest.checkContains("missing file names the file", missOut,
                "no-such-file.txt");
        SelfTest.checkContains("missing file says fail-closed", missOut,
                "fail-closed");
        SelfTest.checkNotContains("missing file emits no plan", missOut,
                "-> DIRECT");

        // 5. Binary file: flagged, handled gracefully, exit 0.
        Path bin = dir.resolve("blob.bin");
        byte[] raw = new byte[100];
        raw[10] = 0; // NUL byte -> binary
        Files.write(bin, raw);
        String binOut = capturePlan(List.of(bin), exit);
        SelfTest.check("binary file exits 0", exit[0] == 0);
        SelfTest.checkContains("binary file flagged", binOut, "binary");

        // 6. Distillate cache: put -> hit; changed file -> miss; other
        //    question -> miss; tampered cache -> miss (never poison);
        //    clear -> miss. Negative control: a stale file never hits.
        Path cacheFile = dir.resolve("cache.json");
        Path doc = dir.resolve("doc.txt");
        Files.writeString(doc, "the quick brown fox", StandardCharsets.UTF_8);
        Path dist = dir.resolve("dist.txt");
        Files.writeString(dist, "DISTILLATE: a fox", StandardCharsets.UTF_8);
        String q = "what animal?";
        String putOut = captureCli(
                () -> cachePut(cacheFile, doc, q, dist), exit);
        SelfTest.check("cache put exits 0", exit[0] == 0);
        SelfTest.checkContains("cache put confirms", putOut, "CACHED");
        String hitOut = captureCli(
                () -> cacheGet(cacheFile, doc, q), exit);
        SelfTest.check("cache hit exits 0", exit[0] == 0);
        SelfTest.checkContains("cache hit returns the distillate", hitOut,
                "DISTILLATE: a fox");
        String otherQ = captureCli(
                () -> cacheGet(cacheFile, doc, "what color?"), exit);
        SelfTest.check("different question misses", exit[0] == 1);
        SelfTest.checkContains("different question says MISS", otherQ, "MISS");
        Files.writeString(doc, "the quick brown fox jumps",
                StandardCharsets.UTF_8);
        String staleOut = captureCli(
                () -> cacheGet(cacheFile, doc, q), exit);
        SelfTest.check("changed file misses (stale, never hits)", exit[0] == 1);
        SelfTest.checkContains("stale file says MISS", staleOut, "MISS");
        String reputOut = captureCli(
                () -> cachePut(cacheFile, doc, q, dist), exit);
        SelfTest.check("re-put after change exits 0", exit[0] == 0);
        String hit2 = captureCli(
                () -> cacheGet(cacheFile, doc, q), exit);
        SelfTest.check("re-put hits again", exit[0] == 0
                && hit2.contains("DISTILLATE: a fox"));
        Files.writeString(cacheFile, "{not valid json",
                StandardCharsets.UTF_8);
        String tampered = captureCli(
                () -> cacheGet(cacheFile, doc, q), exit);
        SelfTest.check("tampered cache file misses, never crashes",
                exit[0] == 1);
        SelfTest.checkContains("tampered cache says MISS", tampered, "MISS");
        // Oversize distillate refused.
        Path big = dir.resolve("big.txt");
        byte[] bigRaw = new byte[(int) (CACHE_MAX_DISTILLATE_BYTES + 1)];
        Files.write(big, bigRaw);
        String bigOut = captureCli(
                () -> cachePut(cacheFile, doc, q, big), exit);
        SelfTest.check("oversize distillate refused", exit[0] == 1);
        SelfTest.checkContains("oversize distillate says too large", bigOut,
                "too large");
        String clearOut = captureCli(() -> cacheClear(cacheFile), exit);
        SelfTest.check("cache clear exits 0", exit[0] == 0);
        SelfTest.check("cache file gone after clear",
                !Files.exists(cacheFile));

        // 7. Targeted extract: nested paths, arrays, missing path is
        //    fail-closed, non-JSON and bad JSON rejected, missing file
        //    rejected. Negative control: a missing key never returns null
        //    silently.
        Path j = dir.resolve("data.json");
        Files.writeString(j,
                "{\"a\": {\"b\": [1, {\"c\": \"x\"}], \"n\": null}, \"t\": true}",
                StandardCharsets.UTF_8);
        String ex1 = captureCli(
                () -> extract(j, List.of("a.b.0", "a.b.1.c", "a.n", "t")),
                exit);
        SelfTest.check("extract exits 0", exit[0] == 0);
        SelfTest.checkContains("extract resolves array index", ex1,
                "a.b.0 = 1");
        SelfTest.checkContains("extract resolves nested key", ex1,
                "a.b.1.c = \"x\"");
        SelfTest.checkContains("extract prints real null as null", ex1,
                "a.n = null");
        SelfTest.checkContains("extract prints boolean", ex1, "t = true");
        String exMiss = captureCli(
                () -> extract(j, List.of("a.zzz")), exit);
        SelfTest.check("missing path exits 1", exit[0] == 1);
        SelfTest.checkContains("missing path names the path", exMiss,
                "a.zzz");
        SelfTest.checkNotContains("missing key never silently null", exMiss,
                "= null");
        Path notJson = dir.resolve("plain.txt");
        Files.writeString(notJson, "just words", StandardCharsets.UTF_8);
        String exPlain = captureCli(
                () -> extract(notJson, List.of("a")), exit);
        SelfTest.check("non-JSON file rejected", exit[0] == 1);
        Path badJson = dir.resolve("bad.json");
        Files.writeString(badJson, "{\"a\": ", StandardCharsets.UTF_8);
        String exBad = captureCli(
                () -> extract(badJson, List.of("a")), exit);
        SelfTest.check("invalid JSON rejected", exit[0] == 1);
        String exNoFile = captureCli(
                () -> extract(dir.resolve("nope.json"), List.of("a")), exit);
        SelfTest.check("missing file rejected", exit[0] == 1);

        // 8. Tail: last N lines from a 100-line file, more-than-file,
        //    unterminated last line, empty file, missing file, bad count.
        //    Negative control: a one-line file never prints a phantom line.
        Path log = dir.resolve("run.log");
        StringBuilder lb = new StringBuilder();
        for (int k = 1; k <= 100; k++) {
            lb.append("line ").append(k).append('\n');
        }
        Files.writeString(log, lb.toString(), StandardCharsets.UTF_8);
        String tail10 = captureCli(() -> tail(log, 10), exit);
        SelfTest.check("tail exits 0", exit[0] == 0);
        SelfTest.check("tail 10 starts at line 91",
                tail10.startsWith("line 91\n"));
        SelfTest.check("tail 10 ends at line 100",
                tail10.endsWith("line 100\n"));
        SelfTest.check("tail 10 has exactly 10 lines",
                tail10.split("\n").length == 10);
        String tailAll = captureCli(() -> tail(log, 500), exit);
        SelfTest.check("tail beyond EOF prints the whole file", exit[0] == 0
                && tailAll.split("\n").length == 100
                && tailAll.startsWith("line 1\n"));
        Path noTerm = dir.resolve("noterm.log");
        Files.writeString(noTerm, "a\nb\nc", StandardCharsets.UTF_8);
        String tailNoTerm = captureCli(() -> tail(noTerm, 2), exit);
        SelfTest.check("unterminated tail is exact", exit[0] == 0
                && tailNoTerm.equals("b\nc"));
        Path oneLine = dir.resolve("one.log");
        Files.writeString(oneLine, "solo\n", StandardCharsets.UTF_8);
        String tailOne = captureCli(() -> tail(oneLine, 5), exit);
        SelfTest.check("one-line file prints once, no phantom line",
                exit[0] == 0 && tailOne.equals("solo\n"));
        Path empty = dir.resolve("empty.log");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        String tailEmpty = captureCli(() -> tail(empty, 10), exit);
        SelfTest.check("empty file exits 0 with no output",
                exit[0] == 0 && tailEmpty.isEmpty());
        String tailMiss = captureCli(
                () -> tail(dir.resolve("nope.log"), 10), exit);
        SelfTest.check("missing file exits 1", exit[0] == 1);
        String tailZero = captureCli(() -> tail(log, 0), exit);
        SelfTest.check("zero lines exits 1", exit[0] == 1);

        // 9. Context-aware thresholds: empty context keeps 20k; half-full
        //    context halves the DIRECT ceiling; a full context floors at
        //    2k; LEAN/CHUNKED never scale (fresh subagents are always lean).
        //    Negative control: a 15k-word file is DIRECT with room but LEAN
        //    when the context is half full.
        SelfTest.checkEq("empty context -> 20k ceiling", 20_000L,
                directMaxFor(0, 150_000));
        SelfTest.checkEq("half-full context -> 10k ceiling", 10_000L,
                directMaxFor(75_000, 150_000));
        SelfTest.checkEq("full context -> 2k floor", 2_000L,
                directMaxFor(150_000, 150_000));
        SelfTest.checkEq("overfull context -> 2k floor", 2_000L,
                directMaxFor(300_000, 150_000));
        SelfTest.checkEq("zero window -> default ceiling", 20_000L,
                directMaxFor(75_000, 0));
        SelfTest.checkEq("15k words DIRECT with room", Rec.DIRECT,
                recommend(15_000, directMaxFor(0, 150_000)));
        SelfTest.checkEq("15k words LEAN when half full", Rec.LEAN,
                recommend(15_000, directMaxFor(75_000, 150_000)));
        SelfTest.checkEq("1.5k words DIRECT even when full", Rec.DIRECT,
                recommend(1_500, directMaxFor(300_000, 150_000)));
        // End-to-end: --plan honors --context-words.
        Path mid = writeWords(dir, "mid.txt", 15_000);
        int[] exit2 = new int[1];
        String ctxOut = captureCli(
                () -> plan(List.of(mid), 75_000, 150_000), exit2);
        SelfTest.check("context-aware plan exits 0", exit2[0] == 0);
        SelfTest.checkContains("context-aware plan routes 15k to LEAN",
                ctxOut, "-> LEAN");
        SelfTest.checkContains("context-aware plan says why", ctxOut,
                "context-aware");

        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // main

    /** Value of the last "--name value" pair, or null. */
    static String flag(String[] args, String name) {
        for (int k = 0; k + 1 < args.length; k++) {
            if (args[k].equals(name)) {
                return args[k + 1];
            }
        }
        return null;
    }

    /** All values of "--name value" pairs, in order. */
    static List<String> flags(String[] args, String name) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k + 1 < args.length; k++) {
            if (args[k].equals(name)) {
                out.add(args[k + 1]);
            }
        }
        return out;
    }

    static void need(String what) {
        OUT.println("ERROR: " + what);
        printHelp();
        System.exit(2);
    }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--self-test")) {
            try {
                selfTest();
            } catch (Exception e) {
                OUT.println("self-test runner error: " + e);
                System.exit(2);
            }
            return;
        }
        if (args.length == 1 && args[0].equals("--help")) {
            printHelp();
            System.exit(0);
            return;
        }
        if (args.length >= 2 && args[0].equals("--plan")) {
            List<Path> files = new ArrayList<>();
            long contextWords = 0;
            long windowWords = DEFAULT_WINDOW_WORDS;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--context-words") && i + 1 < args.length) {
                    contextWords = Long.parseLong(args[++i]);
                } else if (args[i].equals("--window-words")
                        && i + 1 < args.length) {
                    windowWords = Long.parseLong(args[++i]);
                } else {
                    files.add(Path.of(args[i]));
                }
            }
            if (files.isEmpty()) {
                need("--plan needs at least one file");
            }
            System.exit(plan(files, contextWords, windowWords));
            return;
        }
        if (args.length >= 2 && args[0].equals("--tail")) {
            long lines = TAIL_DEFAULT_LINES;
            String l = flag(args, "--lines");
            if (l != null) {
                lines = Long.parseLong(l);
            }
            String target = null;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--lines")) {
                    i++; // skip its value
                } else if (!args[i].startsWith("--")) {
                    target = args[i];
                }
            }
            if (target == null) {
                need("--tail needs a file");
            }
            System.exit(tail(Path.of(target), lines));
            return;
        }
        if (args.length >= 2 && args[0].equals("--extract")) {
            List<String> paths = flags(args, "--path");
            if (paths.isEmpty()) {
                need("--extract needs at least one --path <dotted.path>");
            }
            System.exit(extract(Path.of(args[1]), paths));
            return;
        }
        if (args.length >= 2 && args[0].equals("--cache-get")) {
            String q = flag(args, "--question");
            if (q == null) {
                need("--cache-get needs --question <q>");
            }
            System.exit(cacheGet(defaultCacheFile(), Path.of(args[1]), q));
            return;
        }
        if (args.length >= 2 && args[0].equals("--cache-put")) {
            String q = flag(args, "--question");
            String d = flag(args, "--distillate-file");
            if (q == null || d == null) {
                need("--cache-put needs --question <q> --distillate-file <f>");
            }
            System.exit(cachePut(defaultCacheFile(), Path.of(args[1]), q,
                    Path.of(d)));
            return;
        }
        if (args.length == 1 && args[0].equals("--cache-clear")) {
            System.exit(cacheClear(defaultCacheFile()));
            return;
        }
        printHelp();
        System.exit(2);
    }
}
