// ThesisLog.java — attach a structured thesis block to a creator target entry.
//
// The valuation-call stores (parkev_targets.json, jeremy_targets.json,
// couch_investor_targets.json, bottom_finders_targets.json,
// other_creators_targets.json) carry the NUMBERS; this tool attaches the
// STORY behind a call: the creator's growth story, key drivers and risks with
// their exact transcript quotes, and links (refs) to the per-video stores
// (creator_commentary.json cc-NNN, creator_research.json cr-NNN,
// creator_assumptions.json ca-NNN). No new store — the block lives on the
// entry itself (ASSUMPTIONS_DESIGN.md section 3a). This is the STEP 4 writer
// extension for the daily YouTube digest: values are appended as before, then
// each new target entry gets its thesis block attached here.
//
// Usage:
//   java ThesisLog.java --set --file <targets.json> --id <entry-id>
//       --thesis <thesis.json> [--replace] [--store-dir <dir>]
//   java ThesisLog.java --show --file <targets.json> --id <entry-id>
//       [--store-dir <dir>]
//   java ThesisLog.java --self-test
//
// --file is resolved against --store-dir (default
// $HOME/workspace/creator_tracker) when relative.
//
// thesis.json schema (unknown keys are rejected):
//   {
//     "growth_story": "one line: why he thinks it is worth $X",   // optional
//     "drivers": [{"driver": "...", "quote": "<exact transcript quote>"}],
//     "risks":   [{"risk": "...",   "quote": "<exact transcript quote>"}],
//     "refs": {"commentary": "cc-001",
//              "research": ["cr-001"],
//              "assumptions": ["ca-001"]}
//   }
//
// Standing rules, enforced fail-closed:
// - A driver/risk WITHOUT its exact transcript quote is rejected — the story
//   is factual transcription of what the creator said, never invented.
//   Missing = missing: empty drivers/risks lists are honest output.
// - refs are LINKS: every referenced cc/cr/ca id must already exist in its
//   store. Unknown ids fail the write (a typo is data corruption). Write the
//   commentary/research/assumption entry first, then attach the thesis.
// - Dedupe: attaching to an entry that already carries a thesis block is
//   refused unless --replace is passed (genuine corrections only; note the
//   reason in the run log).
// - Backup: <file>.bak is written before every --set.
//
// JSON is parsed and rewritten with MiniJson (byte-exact 2-space formatting,
// the same convention as the other tracker writers), so a --set changes only
// the one entry — key order and every other byte are preserved.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ThesisLog {

    static final Set<String> THESIS_KEYS =
            Set.of("growth_story", "drivers", "risks", "refs");
    static final Set<String> REF_KEYS =
            Set.of("commentary", "research", "assumptions");

    static Path storeDir(Map<String, String> a) {
        String d = a.get("store-dir");
        if (d != null && !d.isBlank()) return Paths.get(d);
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) home = System.getProperty("user.home");
        return Paths.get(home, "workspace", "creator_tracker");
    }

    static Path resolveFile(Map<String, String> a) {
        String f = a.get("file");
        Path p = Paths.get(f);
        return p.isAbsolute() ? p : storeDir(a).resolve(p);
    }

    // ---- ref-store id indexes ----

    /** Collect known ids from a store file. Handles both "entries" and
     *  "assumptions" list keys (creator_assumptions.json uses the latter). */
    static Set<String> storeIds(Path storeFile) throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        if (!Files.exists(storeFile)) return ids;
        Object root = MiniJson.parse(Files.readString(storeFile, StandardCharsets.UTF_8));
        if (!(root instanceof Map)) return ids;
        Map<?, ?> m = (Map<?, ?>) root;
        for (String key : new String[]{"entries", "assumptions"}) {
            Object list = m.get(key);
            if (list instanceof List) {
                for (Object e : (List<?>) list) {
                    if (e instanceof Map) {
                        Object id = ((Map<?, ?>) e).get("id");
                        if (id instanceof String) ids.add((String) id);
                    }
                }
            }
        }
        return ids;
    }

    // ---- thesis validation (fail-closed) ----

    static String blank(Object o) {
        return !(o instanceof String) || ((String) o).isBlank() ? "blank" : null;
    }

    /** Returns null when the thesis block is valid, else the refusal reason. */
    @SuppressWarnings("unchecked")
    static String validateThesis(Object raw, Map<String, Set<String>> refIds) {
        if (!(raw instanceof Map)) return "thesis must be a JSON object";
        Map<String, Object> t = (Map<String, Object>) raw;
        for (String k : t.keySet())
            if (!THESIS_KEYS.contains(k)) return "unknown thesis key: " + k;
        Object gs = t.get("growth_story");
        if (gs != null && !(gs instanceof String)) return "growth_story must be a string";
        String derr = checkItems(t.get("drivers"), "driver", "drivers");
        if (derr != null) return derr;
        String rerr = checkItems(t.get("risks"), "risk", "risks");
        if (rerr != null) return rerr;
        Object refs = t.get("refs");
        if (refs != null) {
            if (!(refs instanceof Map)) return "refs must be an object";
            Map<String, Object> rm = (Map<String, Object>) refs;
            for (String k : rm.keySet())
                if (!REF_KEYS.contains(k)) return "unknown refs key: " + k;
            Object cc = rm.get("commentary");
            if (cc != null) {
                if (!(cc instanceof String) || ((String) cc).isBlank())
                    return "refs.commentary must be an id string";
                if (!refIds.get("commentary").contains(cc))
                    return "unknown commentary id: " + cc;
            }
            for (String k : new String[]{"research", "assumptions"}) {
                Object v = rm.get(k);
                if (v == null) continue;
                if (!(v instanceof List)) return "refs." + k + " must be a list of ids";
                for (Object id : (List<?>) v) {
                    if (!(id instanceof String) || ((String) id).isBlank())
                        return "refs." + k + " must be a list of ids";
                    if (!refIds.get(k).contains(id))
                        return "unknown " + k + " id: " + id;
                }
            }
        }
        return null;
    }

    /** Each drivers[]/risks[] item needs BOTH its label and its exact quote. */
    @SuppressWarnings("unchecked")
    static String checkItems(Object raw, String labelKey, String listName) {
        if (raw == null) return null;
        if (!(raw instanceof List)) return listName + " must be a list";
        int i = 0;
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) return listName + "[" + i + "] must be an object";
            Map<String, Object> item = (Map<String, Object>) o;
            if (blank(item.get(labelKey)) != null)
                return listName + "[" + i + "] is missing its " + labelKey;
            if (blank(item.get("quote")) != null)
                return listName + "[" + i + "] (" + item.get(labelKey)
                        + ") has no exact transcript quote — not logged";
            i++;
        }
        return null;
    }

    // ---- entry lookup / attach ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> findEntry(Map<String, Object> root, String id) {
        Object entries = root.get("entries");
        if (!(entries instanceof List)) return null;
        for (Object e : (List<?>) entries)
            if (e instanceof Map && id.equals(((Map<?, ?>) e).get("id")))
                return (Map<String, Object>) e;
        return null;
    }

    /** Insert the thesis block right after "notes" (or at the end when the
     *  entry has no notes key), preserving the entry's key order. An existing
     *  thesis key is dropped first so --replace truly replaces. */
    static void attachBlock(Map<String, Object> entry, Map<String, Object> thesis) {
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        boolean placed = false;
        for (Map.Entry<String, Object> kv : entry.entrySet()) {
            if ("thesis".equals(kv.getKey())) continue; // old block goes
            rebuilt.put(kv.getKey(), kv.getValue());
            if (!placed && "notes".equals(kv.getKey())) {
                rebuilt.put("thesis", thesis);
                placed = true;
            }
        }
        if (!placed) rebuilt.put("thesis", thesis);
        entry.clear();
        entry.putAll(rebuilt);
    }

    /** Thrown for expected, user-facing refusals (usage / validation). */
    static final class Refusal extends Exception {
        Refusal(String msg) { super(msg); }
    }

    @SuppressWarnings("unchecked")
    static String runSet(Map<String, String> a) throws Exception {
        String id = a.get("id");
        String thesisPath = a.get("thesis");
        if (id == null || id.isBlank()) throw new Refusal("missing --id");
        if (thesisPath == null || thesisPath.isBlank()) throw new Refusal("missing --thesis");
        boolean replace = a.containsKey("replace");

        Path file = resolveFile(a);
        if (!Files.exists(file)) throw new Refusal("target file not found: " + file);
        Object root = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(root instanceof Map)) throw new Refusal("target file has no JSON object root");
        Map<String, Object> doc = (Map<String, Object>) root;
        Map<String, Object> entry = findEntry(doc, id);
        if (entry == null) throw new Refusal("no entry with id " + id + " in " + file.getFileName());
        if (entry.containsKey("thesis") && !replace)
            throw new Refusal(id + " already carries a thesis block (use --replace for genuine corrections)");

        Path dir = storeDir(a);
        Map<String, Set<String>> refIds = new LinkedHashMap<>();
        refIds.put("commentary", storeIds(dir.resolve("creator_commentary.json")));
        refIds.put("research", storeIds(dir.resolve("creator_research.json")));
        refIds.put("assumptions", storeIds(dir.resolve("creator_assumptions.json")));

        Path tp = Paths.get(thesisPath);
        if (!tp.isAbsolute()) tp = Paths.get("").toAbsolutePath().resolve(tp);
        Object thesisRaw = MiniJson.parse(Files.readString(tp, StandardCharsets.UTF_8));
        String problem = validateThesis(thesisRaw, refIds);
        if (problem != null) throw new Refusal("thesis rejected: " + problem);

        attachBlock(entry, (Map<String, Object>) thesisRaw);

        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
        // No trailing newline: matches the tracker files' existing convention
        // (Python json.dump(indent=2) style, like the other writers).
        Files.writeString(file, MiniJson.dump(doc, 2), StandardCharsets.UTF_8);

        int drivers = countItems(thesisRaw, "drivers");
        int risks = countItems(thesisRaw, "risks");
        return "thesis attached to " + id + " (" + entry.get("ticker") + "): "
                + drivers + " drivers, " + risks + " risks";
    }

    @SuppressWarnings("unchecked")
    static int countItems(Object thesisRaw, String key) {
        Object v = ((Map<String, Object>) thesisRaw).get(key);
        return v instanceof List ? ((List<?>) v).size() : 0;
    }

    @SuppressWarnings("unchecked")
    static String runShow(Map<String, String> a) throws Exception {
        String id = a.get("id");
        if (id == null || id.isBlank()) throw new Refusal("missing --id");
        Path file = resolveFile(a);
        Object root = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> entry = findEntry((Map<String, Object>) root, id);
        if (entry == null) throw new Refusal("no entry with id " + id);
        Object thesis = entry.get("thesis");
        return thesis == null ? id + ": no thesis block" : MiniJson.dump(thesis, 2);
    }

    // ---- arg parsing ----

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String val = (i + 1 < args.length && !args[i + 1].startsWith("--"))
                        ? args[++i] : "";
                m.put(key, val);
            }
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        try {
            switch (mode) {
                case "--set" -> System.out.println(runSet(parseArgs(args)));
                case "--show" -> System.out.println(runShow(parseArgs(args)));
                case "--self-test" -> { selfTest(); SelfTest.finish(); }
                default -> {
                    System.err.println("usage: java ThesisLog.java --set --file <f> --id <id>"
                            + " --thesis <t.json> [--replace] [--store-dir <d>]");
                    System.err.println("       java ThesisLog.java --show --file <f> --id <id>");
                    System.err.println("       java ThesisLog.java --self-test");
                    System.exit(2);
                }
            }
        } catch (Refusal r) {
            System.err.println("ThesisLog refused: " + r.getMessage());
            System.exit(1);
        }
    }

    // ---- self-test ----

    static Path scratchDir() throws Exception {
        Path d = Files.createTempDirectory("thesislog-test");
        d.toFile().deleteOnExit();
        return d;
    }

    static void writeStore(Path dir, String name, String listKey, String... ids) throws Exception {
        StringBuilder sb = new StringBuilder("{\n  \"" + listKey + "\": [\n");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(",\n");
            sb.append("    {\"id\": \"").append(ids[i]).append("\"}");
        }
        sb.append("\n  ]\n}\n");
        Files.writeString(dir.resolve(name), sb.toString(), StandardCharsets.UTF_8);
    }

    static Path scratchTargets(Path dir) throws Exception {
        String json = "{\n  \"entries\": [\n"
                + "    {\n      \"ticker\": \"ORCL\",\n"
                + "      \"notes\": \"seed\",\n"
                + "      \"id\": \"pt-900\",\n"
                + "      \"target_value\": 131,\n"
                + "      \"status\": \"open\"\n"
                + "    },\n"
                + "    {\n      \"ticker\": \"JNJ\",\n"
                + "      \"notes\": \"seed\",\n"
                + "      \"id\": \"pt-901\",\n"
                + "      \"target_value\": 377.43,\n"
                + "      \"status\": \"open\"\n"
                + "    }\n  ]\n}\n";
        Path f = dir.resolve("scratch_targets.json");
        Files.writeString(f, json, StandardCharsets.UTF_8);
        return f;
    }

    static Path thesisFile(Path dir, String name, String body) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, body, StandardCharsets.UTF_8);
        return f;
    }

    static String goodThesis() {
        return "{\n"
                + "  \"growth_story\": \"cash flow improving, risk rising faster\",\n"
                + "  \"drivers\": [{\"driver\": \"customer deposits\", \"quote\": \"deposits have been large\"}],\n"
                + "  \"risks\": [{\"risk\": \"leverage\", \"quote\": \"highly leveraged\"}],\n"
                + "  \"refs\": {\"commentary\": \"cc-1\", \"research\": [\"cr-1\"], \"assumptions\": [\"ca-1\"]}\n"
                + "}";
    }

    /** Runs --set in-process against scratch files; returns stdout or throws. */
    static String setIn(Path dir, String file, String id, String thesisName, boolean replace)
            throws Exception {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("store-dir", dir.toString());
        a.put("file", file);
        a.put("id", id);
        a.put("thesis", dir.resolve(thesisName).toString());
        if (replace) a.put("replace", "");
        return runSet(a);
    }

    static void selfTest() throws Exception {
        Path dir = scratchDir();
        writeStore(dir, "creator_commentary.json", "entries", "cc-1");
        writeStore(dir, "creator_research.json", "entries", "cr-1");
        writeStore(dir, "creator_assumptions.json", "assumptions", "ca-1");
        scratchTargets(dir);
        thesisFile(dir, "good.json", goodThesis());

        // 1. Happy path: block attaches, lands after "notes".
        String out = setIn(dir, "scratch_targets.json", "pt-900", "good.json", false);
        SelfTest.checkContains("set reports id + counts", out, "pt-900");
        SelfTest.checkContains("set reports ticker", out, "ORCL");
        String after = Files.readString(dir.resolve("scratch_targets.json"), StandardCharsets.UTF_8);
        SelfTest.checkContains("thesis block persisted", after, "\"thesis\": {");
        SelfTest.checkContains("growth story persisted", after, "cash flow improving");
        SelfTest.check("thesis sits right after notes",
                after.indexOf("\"thesis\": {") > after.indexOf("\"notes\": \"seed\"")
                        && after.indexOf("\"thesis\": {") < after.indexOf("\"id\": \"pt-900\""));
        // Other entry untouched.
        SelfTest.checkNotContains("second entry untouched", after, "pt-901\",\n      \"thesis\"");
        // .bak holds the pre-write content.
        String bak = Files.readString(dir.resolve("scratch_targets.json.bak"), StandardCharsets.UTF_8);
        SelfTest.checkNotContains("bak predates the write", bak, "\"thesis\"");

        // 2. Dedupe: second attach refused without --replace, allowed with it.
        try {
            setIn(dir, "scratch_targets.json", "pt-900", "good.json", false);
            SelfTest.check("duplicate attach refused", false);
        } catch (Refusal r) {
            SelfTest.checkContains("duplicate attach refused", r.getMessage(), "already carries");
        }
        thesisFile(dir, "good2.json", goodThesis().replace("cash flow improving", "revised story"));
        String out2 = setIn(dir, "scratch_targets.json", "pt-900", "good2.json", true);
        SelfTest.checkContains("--replace overwrites", out2, "pt-900");
        SelfTest.checkContains("--replace content lands",
                Files.readString(dir.resolve("scratch_targets.json"), StandardCharsets.UTF_8),
                "revised story");

        // 3. Fail-closed: driver without an exact quote is rejected.
        thesisFile(dir, "noquote.json",
                "{\"drivers\": [{\"driver\": \"vibes\"}]}");
        try {
            setIn(dir, "scratch_targets.json", "pt-901", "noquote.json", false);
            SelfTest.check("quoteless driver rejected", false);
        } catch (Refusal r) {
            SelfTest.checkContains("quoteless driver rejected", r.getMessage(), "quote");
        }

        // 4. Fail-closed: unknown ref id is rejected.
        thesisFile(dir, "badref.json",
                "{\"drivers\": [{\"driver\": \"d\", \"quote\": \"q\"}],"
                        + " \"refs\": {\"commentary\": \"cc-999\"}}");
        try {
            setIn(dir, "scratch_targets.json", "pt-901", "badref.json", false);
            SelfTest.check("unknown ref rejected", false);
        } catch (Refusal r) {
            SelfTest.checkContains("unknown ref rejected", r.getMessage(), "cc-999");
        }

        // 5. Unknown entry id is rejected.
        try {
            setIn(dir, "scratch_targets.json", "pt-404", "good.json", false);
            SelfTest.check("unknown id rejected", false);
        } catch (Refusal r) {
            SelfTest.checkContains("unknown id rejected", r.getMessage(), "pt-404");
        }

        // 6. Unknown thesis key is rejected (schema discipline).
        thesisFile(dir, "badkey.json", "{\"narrative\": \"x\"}");
        try {
            setIn(dir, "scratch_targets.json", "pt-901", "badkey.json", false);
            SelfTest.check("unknown key rejected", false);
        } catch (Refusal r) {
            SelfTest.checkContains("unknown key rejected", r.getMessage(), "narrative");
        }

        // 7. Byte-exact round-trip: parsing + dumping the REAL tracker changes
        //    nothing when no thesis is attached (serializer safety).
        Path real = Paths.get(System.getenv("HOME"), "workspace", "creator_tracker",
                "parkev_targets.json");
        String orig = Files.readString(real, StandardCharsets.UTF_8);
        Object parsed = MiniJson.parse(orig);
        SelfTest.checkEq("real-file round-trip byte-identical", orig, MiniJson.dump(parsed, 2));

        // 8. --show prints the attached block.
        Map<String, String> sa = new LinkedHashMap<>();
        sa.put("store-dir", dir.toString());
        sa.put("file", "scratch_targets.json");
        sa.put("id", "pt-900");
        SelfTest.checkContains("--show prints block", runShow(sa), "revised story");
    }
}

final class MiniJson {
    private MiniJson() {} // utility class: no instances

    /** A JSON number, stored as its raw source text (e.g. "158", "1.5", "2e3"). */
    static final class Num {
        final String raw;
        Num(String raw) { this.raw = raw; }
        @Override public String toString() { return raw; }
    }

    // ---------------- parsing ----------------

    /** Parse a whole JSON document; throws IllegalArgumentException on bad input. */
    static Object parse(String text) {
        int[] pos = {0}; // int[1] so recursive helpers can advance the cursor
        skipWs(text, pos);
        Object v = parseValue(text, pos);
        skipWs(text, pos);
        if (pos[0] != text.length())
            throw new IllegalArgumentException("Unexpected trailing data at index " + pos[0]);
        return v;
    }

    private static void skipWs(String t, int[] p) {
        while (p[0] < t.length()) {
            char c = t.charAt(p[0]);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p[0]++;
            else break;
        }
    }

    private static Object parseValue(String t, int[] p) {
        switch (t.charAt(p[0])) {
            case '{': return parseObject(t, p);
            case '[': return parseArray(t, p);
            case '"': return parseString(t, p);
            case 't': expect(t, p, "true");  return Boolean.TRUE;
            case 'f': expect(t, p, "false"); return Boolean.FALSE;
            case 'n': expect(t, p, "null");  return null;
            default:  return parseNumber(t, p);
        }
    }

    private static void expect(String t, int[] p, String word) {
        if (!t.startsWith(word, p[0]))
            throw new IllegalArgumentException("Expected '" + word + "' at index " + p[0]);
        p[0] += word.length();
    }

    private static java.util.Map<String, Object> parseObject(String t, int[] p) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        p[0]++; // consume '{'
        skipWs(t, p);
        if (t.charAt(p[0]) == '}') { p[0]++; return m; }
        while (true) {
            skipWs(t, p);
            String key = parseString(t, p);
            skipWs(t, p);
            if (t.charAt(p[0]) != ':')
                throw new IllegalArgumentException("Expected ':' at index " + p[0]);
            p[0]++; // consume ':'
            skipWs(t, p);
            // Duplicate keys: last one wins, like Python's json.
            m.put(key, parseValue(t, p));
            skipWs(t, p);
            char c = t.charAt(p[0]);
            if (c == ',') { p[0]++; continue; }
            if (c == '}') { p[0]++; return m; }
            throw new IllegalArgumentException("Expected ',' or '}' at index " + p[0]);
        }
    }

    private static java.util.List<Object> parseArray(String t, int[] p) {
        java.util.List<Object> list = new java.util.ArrayList<>();
        p[0]++; // consume '['
        skipWs(t, p);
        if (t.charAt(p[0]) == ']') { p[0]++; return list; }
        while (true) {
            skipWs(t, p);
            list.add(parseValue(t, p));
            skipWs(t, p);
            char c = t.charAt(p[0]);
            if (c == ',') { p[0]++; continue; }
            if (c == ']') { p[0]++; return list; }
            throw new IllegalArgumentException("Expected ',' or ']' at index " + p[0]);
        }
    }

    private static String parseString(String t, int[] p) {
        // assumes t.charAt(p[0]) == '"'
        StringBuilder sb = new StringBuilder();
        int i = p[0] + 1;
        while (true) {
            char c = t.charAt(i);
            if (c == '"') { p[0] = i + 1; return sb.toString(); }
            if (c == '\\') {
                char e = t.charAt(i + 1);
                switch (e) {
                    case '"':  sb.append('"');  i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    case '/':  sb.append('/');  i += 2; break;
                    case 'b':  sb.append('\b'); i += 2; break;
                    case 'f':  sb.append('\f'); i += 2; break;
                    case 'n':  sb.append('\n'); i += 2; break;
                    case 'r':  sb.append('\r'); i += 2; break;
                    case 't':  sb.append('\t'); i += 2; break;
                    case 'u': {
                        int cp = Integer.parseInt(t.substring(i + 2, i + 6), 16);
                        i += 6;
                        // A high surrogate followed by another \\uXXXX low
                        // surrogate forms one astral character (like Python).
                        if (Character.isHighSurrogate((char) cp) && i + 1 < t.length()
                                && t.charAt(i) == '\\' && t.charAt(i + 1) == 'u') {
                            int lo = Integer.parseInt(t.substring(i + 2, i + 6), 16);
                            if (Character.isLowSurrogate((char) lo)) {
                                cp = Character.toCodePoint((char) cp, (char) lo);
                                i += 6;
                            }
                        }
                        sb.appendCodePoint(cp);
                        break;
                    }
                    default: throw new IllegalArgumentException("Bad escape at index " + i);
                }
            } else {
                sb.append(c);
                i++;
            }
        }
    }

    private static Num parseNumber(String t, int[] p) {
        int i = p[0], n = t.length();
        if (i < n && t.charAt(i) == '-') i++;
        while (i < n && isDigit(t.charAt(i))) i++;
        if (i < n && t.charAt(i) == '.') {
            i++;
            while (i < n && isDigit(t.charAt(i))) i++;
        }
        if (i < n && (t.charAt(i) == 'e' || t.charAt(i) == 'E')) {
            i++;
            if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-')) i++;
            while (i < n && isDigit(t.charAt(i))) i++;
        }
        // Keep the raw literal text so dump() reproduces it exactly.
        Num num = new Num(t.substring(p[0], i));
        p[0] = i;
        return num;
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    // ---------------- serializing ----------------

    /**
     * Serialize to JSON with {@code indent} spaces per level, matching
     * Python's {@code json.dump(obj, f, indent=indent)} formatting exactly
     * (no trailing newline; empty objects/arrays stay on one line).
     */
    static String dump(Object v, int indent) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, indent, 0);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb, int indent, int level) {
        if (v == null)                       sb.append("null");
        else if (v instanceof String)        writeString((String) v, sb);
        else if (v instanceof Num)           sb.append(((Num) v).raw);
        else if (v instanceof Boolean)       sb.append(v.toString());
        else if (v instanceof java.util.List) writeList((java.util.List<?>) v, sb, indent, level);
        else if (v instanceof java.util.Map)  writeMap((java.util.Map<?, ?>) v, sb, indent, level);
        else throw new IllegalArgumentException("Cannot serialize " + v.getClass());
    }

    private static void writeList(java.util.List<?> list, StringBuilder sb, int indent, int level) {
        if (list.isEmpty()) { sb.append("[]"); return; }
        String pad = " ".repeat(indent * (level + 1));
        String closePad = " ".repeat(indent * level);
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append(pad);
            write(list.get(i), sb, indent, level + 1);
        }
        sb.append("\n").append(closePad).append("]");
    }

    private static void writeMap(java.util.Map<?, ?> map, StringBuilder sb, int indent, int level) {
        if (map.isEmpty()) { sb.append("{}"); return; }
        String pad = " ".repeat(indent * (level + 1));
        String closePad = " ".repeat(indent * level);
        sb.append("{\n");
        boolean first = true;
        for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append(pad);
            writeString((String) e.getKey(), sb);
            sb.append(": ");
            write(e.getValue(), sb, indent, level + 1);
        }
        sb.append("\n").append(closePad).append("}");
    }

    /** Escape a string the way Python's json does with ensure_ascii=True. */
    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (cp < 0x20 || cp > 0x7E) {
                        if (cp <= 0xFFFF) {
                            sb.append(String.format("\\u%04x", cp)); // lowercase hex, like Python
                        } else {
                            // Astral chars: one \\uXXXX per surrogate, like Python.
                            for (char c : Character.toChars(cp))
                                sb.append(String.format("\\u%04x", (int) c));
                        }
                    } else {
                        sb.appendCodePoint(cp);
                    }
            }
        }
        sb.append('"');
    }
}
