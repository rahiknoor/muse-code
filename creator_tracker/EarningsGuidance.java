// EarningsGuidance.java — management's stated growth/margin guidance as an
// independent voice in the DCF assumption cross-check.
//
// Pipeline:
//   1. Run creator_tracker/extract_guidance.py (workspace venv python) against
//      the ONE shared parquet copy: ~/workspace/data/earnings_transcripts/part-0.parquet
//      (kurry/sp500_earnings_transcripts, MIT). No parallel stores.
//   2. Merge findings into creator_tracker/earnings_guidance.json.
//      Dedupe: same ticker/field/value/call-date is never logged twice.
//   3. File each new finding as a voice-evidence proposal:
//      java dcf_csv/Proposals.java file-evidence --voice guidance ...
//
// Never auto-blesses; never touches blessed.json, dcf_inputs_all.json, or
// workbook_data.json. The guidance store is a voice file for
// AssumptionCrossCheck (voice 4: MANAGEMENT GUIDANCE), not a blessed column.
// Every entry carries its exact quote — missing stays missing, never guessed.
//
// Usage:
//   java --class-path ~/workspace/lib/json/json-20240303.jar EarningsGuidance.java \
//       [--tickers NVDA,META,MSFT] [--max-calls 4] [--latest-only] [--no-proposals] [--self-test]
// Exit 0 = clean (findings are data, not errors); 1 = failure.
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

public class EarningsGuidance {

    static String HOME = home();
    static Path HELPER = Paths.get(HOME, "workspace", "creator_tracker", "extract_guidance.py");
    static Path STORE = Paths.get(HOME, "workspace", "creator_tracker", "earnings_guidance.json");
    static Path PROPOSALS = Paths.get(HOME, "workspace", "dcf_csv", "Proposals.java");
    static Path WATCHLIST = Paths.get(HOME, "workspace", "stock_watch", "watchlist.json");
    static String VENV_PY = Paths.get(HOME, "workspace", "venv", "bin", "python").toString();
    static String JAVA_BIN = Paths.get(HOME, "workspace", "java", "jdk-21", "bin", "java").toString();

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    public static void main(String[] args) throws Exception {
        List<String> tickers = new ArrayList<>();
        int maxCalls = 4;
        boolean latestOnly = false, noProposals = false, selfTest = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tickers": tickers = List.of(args[++i].split(",")); break;
                case "--max-calls": maxCalls = Integer.parseInt(args[++i]); break;
                case "--latest-only": latestOnly = true; break;
                case "--no-proposals": noProposals = true; break;
                case "--self-test": selfTest = true; break;
                default: throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        if (selfTest) { runSelfTest(); return; }
        if (tickers.isEmpty()) tickers = readWatchlist();

        List<JSONObject> findings = runExtractor(tickers, maxCalls, latestOnly);
        System.out.println("extractor findings: " + findings.size());

        JSONObject store = loadStore();
        Set<String> seen = existingKeys(store);
        int added = 0, proposed = 0;
        for (JSONObject f : findings) {
            String key = entryKey(f);
            if (seen.contains(key)) continue;
            appendEntry(store, f);
            seen.add(key);
            added++;
            if (!noProposals && fileEvidenceProposal(f)) proposed++;
        }
        if (added > 0) {
            Files.writeString(STORE, store.toString(1) + "\n", StandardCharsets.UTF_8);
        }
        System.out.println("new guidance entries: " + added + ", evidence proposals filed: " + proposed);
        System.out.println("store: " + STORE);
    }

    // ---- extractor ----

    static List<JSONObject> runExtractor(List<String> tickers, int maxCalls, boolean latestOnly)
            throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            VENV_PY, HELPER.toString(), "--tickers", String.join(",", tickers),
            "--max-calls", String.valueOf(maxCalls)));
        if (latestOnly) cmd.add("--latest-only");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        List<JSONObject> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;
                JSONObject o = new JSONObject(line);
                if (o.has("field")) out.add(o); // skip {"warning":...} / {"status":...} lines
            }
        }
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int rc = p.waitFor();
        if (rc != 0) throw new IllegalStateException("extract_guidance.py exit " + rc + ": " + err);
        return out;
    }

    // ---- store ----

    static JSONObject loadStore() throws Exception {
        if (Files.exists(STORE)) {
            return new JSONObject(Files.readString(STORE, StandardCharsets.UTF_8));
        }
        JSONObject s = new JSONObject();
        s.put("_note", "Management's stated growth/margin guidance harvested from S&P 500 "
            + "earnings call transcripts (kurry/sp500_earnings_transcripts, MIT). "
            + "Every entry carries its exact quote. Missing = missing — never filled with a guess. "
            + "Dedupe: same ticker/field/value/call not logged twice. "
            + "Independent voice in AssumptionCrossCheck (voice 4); reviewed via Proposals.java file-evidence.");
        s.put("_fields", new JSONArray(List.of("growth_rate", "margin")));
        return s;
    }

    /** Dedupe key: same ticker/field/value/call-date is one entry, never two. */
    static String entryKey(JSONObject f) {
        Object v = f.opt("value");
        String vs = (v == null || JSONObject.NULL.equals(v)) ? "null" : v.toString();
        return f.optString("ticker") + "|" + f.optString("field") + "|" + vs
            + "|" + f.optString("call_date");
    }

    static Set<String> existingKeys(JSONObject store) {
        Set<String> keys = new LinkedHashSet<>();
        for (String t : store.keySet()) {
            if (t.startsWith("_")) continue;
            JSONArray arr = store.optJSONArray(t);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) keys.add(entryKey(arr.getJSONObject(i)));
        }
        return keys;
    }

    static void appendEntry(JSONObject store, JSONObject f) {
        String t = f.optString("ticker");
        JSONArray arr = store.optJSONArray(t);
        if (arr == null) { arr = new JSONArray(); store.put(t, arr); }
        JSONObject e = new JSONObject();
        e.put("ticker", t);
        e.put("field", f.optString("field"));
        e.put("value", f.opt("value"));
        e.put("unit", f.opt("unit"));
        e.put("quote", f.optString("quote"));
        e.put("call_date", f.optString("call_date"));
        e.put("quarter", f.optInt("quarter"));
        e.put("year", f.optInt("year"));
        e.put("source", f.optString("source"));
        arr.put(e);
    }

    // ---- proposals ----

    static boolean fileEvidenceProposal(JSONObject f) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            JAVA_BIN, PROPOSALS.toString(), "file-evidence",
            "--voice", "guidance",
            "--ticker", f.optString("ticker"),
            "--field", f.optString("field")));
        Object v = f.opt("value");
        if (v != null && !JSONObject.NULL.equals(v)) {
            cmd.add("--value"); cmd.add(v.toString());
            if (!f.optString("unit").isEmpty()) { cmd.add("--unit"); cmd.add(f.optString("unit")); }
        }
        cmd.add("--quote"); cmd.add(f.optString("quote"));
        cmd.add("--date"); cmd.add(f.optString("call_date"));
        cmd.add("--source"); cmd.add(f.optString("source"));
        cmd.add("--by"); cmd.add("earnings-guidance");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int rc = p.waitFor();
        System.out.println("  proposal: " + (out.isEmpty() ? "(no output)" : out.split("\n")[0]));
        return rc == 0;
    }

    static List<String> readWatchlist() throws Exception {
        JSONObject w = new JSONObject(Files.readString(WATCHLIST, StandardCharsets.UTF_8));
        List<String> t = new ArrayList<>();
        for (Object o : w.getJSONArray("tickers")) t.add(o.toString());
        return t;
    }

    // ---- self-test (pure functions, no file IO) ----

    static void runSelfTest() {
        int pass = 0, fail = 0;
        // entryKey dedupe: identical entries share a key
        JSONObject a = finding("NVDA", "growth_rate", 12.5, "2025-08-27");
        JSONObject b = finding("NVDA", "growth_rate", 12.5, "2025-08-27");
        JSONObject c = finding("NVDA", "growth_rate", 13.0, "2025-08-27");
        JSONObject d = finding("NVDA", "growth_rate", null, "2025-08-27");
        JSONObject e = finding("NVDA", "growth_rate", null, "2025-08-27");
        if (entryKey(a).equals(entryKey(b))) pass++; else { fail++; System.out.println("FAIL dedupe-same"); }
        if (!entryKey(a).equals(entryKey(c))) pass++; else { fail++; System.out.println("FAIL dedupe-diff-value"); }
        if (entryKey(d).equals(entryKey(e))) pass++; else { fail++; System.out.println("FAIL dedupe-null"); }
        if (!entryKey(a).equals(entryKey(d))) pass++; else { fail++; System.out.println("FAIL dedupe-null-vs-num"); }
        // merge: appendEntry lands under the ticker key with all fields
        JSONObject store = new JSONObject();
        appendEntry(store, a);
        appendEntry(store, finding("MSFT", "margin", 42.0, "2025-07-30"));
        boolean ok = store.getJSONArray("NVDA").length() == 1
            && store.getJSONArray("MSFT").getJSONObject(0).optDouble("value") == 42.0
            && store.getJSONArray("NVDA").getJSONObject(0).optString("quote").equals("q");
        if (ok) pass++; else { fail++; System.out.println("FAIL append"); }
        // existingKeys round-trips through appendEntry
        Set<String> keys = existingKeys(store);
        if (keys.contains(entryKey(a)) && keys.size() == 2) pass++;
        else { fail++; System.out.println("FAIL keys: " + keys); }
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static JSONObject finding(String ticker, String field, Double value, String date) {
        JSONObject f = new JSONObject();
        f.put("ticker", ticker); f.put("field", field);
        f.put("value", value == null ? JSONObject.NULL : value);
        f.put("unit", value == null ? JSONObject.NULL : "percent");
        f.put("quote", "q"); f.put("call_date", date);
        f.put("quarter", 2); f.put("year", 2025);
        f.put("source", "test");
        return f;
    }
}
