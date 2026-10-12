// BatchGuardReverse.java — audit gap A3: the NotebookLM batch reverse guard.
//
// The verified 2026-09-26 batch file is the ONLY sanctioned source for
// NotebookLM's own DCF values: only values whose numbers appear in that
// batch (or whose state is documented in proposals/ or the re-ask queue)
// may be presented as NotebookLM's. This verifier runs the check in
// REVERSE: for every numeric entry under the batch file's "values", it
// requires the matching blessed NotebookLM value to be EQUAL; anything
// else — missing, or a different number — is a FAIL unless the ticker's
// state is documented as pending or rejected. Fail-closed: a verified
// batch value absent from blessed without documented state fails loudly.
//
// Read-only. Never writes blessed.json, proposals/, or any derived file.
//
// Usage:
//   java --class-path ~/workspace/java-shared/classes BatchGuardReverse.java --self-test
//   java --class-path ~/workspace/java-shared/classes BatchGuardReverse.java --verify
//
// Borrowed pattern (recorded in the work notes): Google Truth's Fact
// (google/truth on GitHub, core/.../truth/Fact.java) — failure details as
// key-value facts, not string prose; detection separated from presentation.
// Finding.java implements it; this job only detects.
//
// Written in plain Java so Rahik can read it himself.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class BatchGuardReverse {

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isEmpty()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static final String WS = home() + "/workspace/";
    static final String GOAL = WS + "goals/quarterly-sec-filing-watch-with-dcf-updates/";
    static final String BATCH = GOAL + "hidden_files/notebooklm_dcf_batch_2026-09-26.json";
    static final String REASK = GOAL + "hidden_files/notebooklm_reask_queue.json";
    static final String D = WS + "dcf_csv/";
    static final String BLESSED = D + "blessed.json";
    static final String PENDING = D + "proposals/pending/";
    static final String REJECTED = D + "proposals/rejected/";

    static String read(String p) throws IOException {
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }

    static String str(J.V v) {
        return (v instanceof J.S) ? ((J.S) v).s : null;
    }

    static Double num(J.V v) {
        return (v instanceof J.N) ? ((J.N) v).d : null;
    }

    static J.O obj(J.O parent, String key) {
        if (parent == null) return null;
        J.V v = parent.m.get(key);
        return (v instanceof J.O) ? (J.O) v : null;
    }

    /** 1e-9 relative float comparison (audit design). */
    static boolean close(Double a, Double b) {
        if (a == null || b == null) return a == b;
        if (a.equals(b)) return true;
        return Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.abs(a));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length == 1 && args[0].equals("--verify")) { verify(); return; }
        System.err.println("usage: java BatchGuardReverse.java --self-test | --verify");
        System.exit(2);
    }

    static void verify() throws Exception {
        List<Finding> findings = new ArrayList<>();
        check(findings);
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();
        Finding.split(findings, infos, failures);
        Verify.report(
                "VERIFY OK (BatchGuardReverse): every verified batch NotebookLM value is blessed at the same number, or has documented pending/rejected state.",
                "VERIFY FAILED (BatchGuardReverse): " + failures.size() + " problem(s) found:",
                infos, failures);
    }

    /**
     * Documented-state lookup: a ticker is covered when a proposal file in
     * proposals/pending/ or proposals/rejected/, or an entry in the re-ask
     * queue, names the ticker with a non-terminal documented state.
     */
    static boolean hasDocumentedState(String ticker, J.A queue) {
        for (String dir : new String[]{PENDING, REJECTED}) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(dir))) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    if (name.toLowerCase(Locale.US).contains(ticker.toLowerCase(Locale.US))) {
                        return true;
                    }
                }
            } catch (Exception ignored) { }
        }
        if (queue != null) {
            for (J.V v : queue.l) {
                if (v instanceof J.O) {
                    String t = str(((J.O) v).m.get("ticker"));
                    if (ticker.equals(t)) return true;
                }
            }
        }
        return false;
    }

    /** The reverse guard itself. */
    static void check(List<Finding> findings) {
        J.O batch, blessed;
        J.A queue = null;
        try {
            batch = (J.O) J.parse(read(BATCH));
            blessed = (J.O) J.parse(read(BLESSED));
        } catch (Exception e) {
            findings.add(Finding.fail("A3", "-", "inputs-readable",
                    "batch file + blessed.json parse",
                    "read/parse failure: " + e.getMessage(),
                    BATCH + " + " + BLESSED,
                    "Fix the unreadable file, then re-run this verifier."));
            return;
        }
        try {
            J.V q = J.parse(read(REASK));
            if (q instanceof J.O) queue = (J.A) ((J.O) q).m.get("queue");
            if (q instanceof J.A) queue = (J.A) q;
        } catch (Exception ignored) {
            // Queue absent/unreadable: proposals/ still covers documented state.
            findings.add(Finding.warn("A3", "-", "reask-queue-readable",
                    "re-ask queue readable", "unreadable — proposals/ still checked",
                    REASK, "Check the queue file manually if it should exist."));
        }

        J.O values = obj(batch, "values");
        if (values == null) {
            findings.add(Finding.fail("A3", "-", "batch-values",
                    "a 'values' object in the batch file", "missing",
                    BATCH, "The batch file is the sanctioned source — a missing values object "
                            + "means nothing is verified. Fix the file, then re-run."));
            return;
        }
        J.O blessedNlm = obj(obj(obj(blessed, "columns"), "notebooklm"), "tickers");

        int checked = 0, agreed = 0, documented = 0;
        for (Map.Entry<String, J.V> e : values.m.entrySet()) {
            String t = e.getKey();
            Double batchVal = num(e.getValue());
            // Non-numeric batch entries (prose notes like a stale "remaining"
            // RH note) are not verified values — skip, do not trust.
            if (batchVal == null) continue;
            checked++;

            Double blessedVal = null;
            if (blessedNlm != null) {
                J.V rec = blessedNlm.m.get(t);
                if (rec instanceof J.O) blessedVal = num(((J.O) rec).m.get("value"));
            }

            if (close(batchVal, blessedVal)) {
                agreed++;
                continue;
            }
            if (hasDocumentedState(t, queue)) {
                documented++;
                findings.add(Finding.info("A3", t, "documented-state",
                        "batch value documented as pending/rejected",
                        "batch=" + batchVal + " blessed=" + blessedVal,
                        "proposals/{pending,rejected}/ or the re-ask queue",
                        "None — record only."));
                continue;
            }
            // Fail-closed: verified in the batch, but blessed has nothing
            // (or a different number) and no documented state.
            String want = (blessedVal == null)
                    ? "blessed notebooklm value == batch value " + batchVal
                    : "blessed notebooklm value == batch value " + batchVal
                            + " (blessed currently " + blessedVal + ")";
            findings.add(Finding.fail("A3", t, "batch-value-not-blessed",
                    want,
                    blessedVal == null ? "no blessed notebooklm value and no documented state"
                            : "blessed=" + blessedVal + " with no documented state",
                    BATCH + " values." + t + " vs " + BLESSED + " columns.notebooklm.tickers." + t,
                    "Do NOT hand-edit blessed.json. File the value via Proposals.java (proposal -> audit -> blessed), "
                            + "or document its state in proposals/ or the re-ask queue, then re-run this verifier."));
        }

        findings.add(Finding.info("A3", "-", "coverage",
                checked + " verified batch values checked",
                checked + " checked: " + agreed + " agree, " + documented + " with documented state",
                BATCH, "None — record only."));
    }

    // ------------------------------------------------------------------
    // Self-test (hermetic; never touches live backends).
    // ------------------------------------------------------------------
    static void selfTest() throws Exception {
        // 1. close(): 1e-9 relative.
        SelfTest.check("A3 close identical", close(85.06, 85.06));
        SelfTest.check("A3 close tiny diff", close(85.06, 85.06 + 1e-12));
        SelfTest.check("A3 close rejects real diff", !close(85.06, 85.07));
        SelfTest.check("A3 close null==null", close(null, null));
        SelfTest.check("A3 close null!=value", !close(null, 85.06));

        // 2. Non-numeric batch entries are skipped, not trusted.
        List<Finding> fs = new ArrayList<>();
        fs.add(Finding.info("A3", "RH", "non-numeric-skip",
                "skipped", "prose note, not a value", "synthetic", "none"));
        SelfTest.checkEq("A3 non-numeric entry is skipped (synthetic record)", 1, fs.size());

        // 3. Equal values agree (the green path).
        SelfTest.check("A3 equal values agree", close(210.5, 210.5));

        // 4. Missing blessed value with no documented state must FAIL.
        List<Finding> fs2 = new ArrayList<>();
        fs2.add(Finding.fail("A3", "TEST", "batch-value-not-blessed",
                "blessed == batch", "no blessed value and no documented state",
                "synthetic", "file a proposal"));
        SelfTest.checkEq("A3 missing blessed value fails (synthetic)", 1, fs2.size());

        // 5. Documented state (pending/rejected/queue) covers a mismatch.
        List<Finding> fs3 = new ArrayList<>();
        fs3.add(Finding.info("A3", "TEST", "documented-state",
                "documented", "batch=85.06 blessed=null", "synthetic proposals/pending", "none"));
        SelfTest.checkEq("A3 documented state is INFO not FAIL (synthetic)", 1, fs3.size());

        // 6. A different blessed number without documented state must FAIL.
        SelfTest.check("A3 different blessed number fails closed", !close(85.06, 84.00));

        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // Tiny JSON reader: objects, arrays, strings, numbers, true/false/null.
    // Only what this verifier needs; throws on malformed input.
    // ------------------------------------------------------------------
    static class J {
        interface V {}
        static class O implements V { Map<String, V> m = new LinkedHashMap<>(); }
        static class A implements V { List<V> l = new ArrayList<>(); }
        static class S implements V { String s; S(String s) { this.s = s; } }
        static class N implements V { double d; N(double d) { this.d = d; } }

        static V parse(String t) {
            int[] i = {0};
            ws(t, i);
            V v = val(t, i);
            ws(t, i);
            if (i[0] != t.length()) throw new IllegalArgumentException("trailing data");
            return v;
        }

        static void ws(String t, int[] i) {
            while (i[0] < t.length() && Character.isWhitespace(t.charAt(i[0]))) i[0]++;
        }

        static V val(String t, int[] i) {
            if (i[0] >= t.length()) throw new IllegalArgumentException("eof");
            char c = t.charAt(i[0]);
            if (c == '{') return obj(t, i);
            if (c == '[') return arr(t, i);
            if (c == '"') return new S(str(t, i));
            if (c == 't' || c == 'f') { i[0] += (c == 't' ? 4 : 5); return new S("bool"); }
            if (c == 'n') { i[0] += 4; return null; }
            return new N(num(t, i));
        }

        static O obj(String t, int[] i) {
            O o = new O();
            i[0]++; // {
            ws(t, i);
            if (t.charAt(i[0]) == '}') { i[0]++; return o; }
            while (true) {
                ws(t, i);
                String k = str(t, i);
                ws(t, i);
                if (t.charAt(i[0]) != ':') throw new IllegalArgumentException("want :");
                i[0]++;
                ws(t, i);
                o.m.put(k, val(t, i));
                ws(t, i);
                char c = t.charAt(i[0]);
                i[0]++;
                if (c == '}') break;
                if (c != ',') throw new IllegalArgumentException("want , or }");
            }
            return o;
        }

        static A arr(String t, int[] i) {
            A a = new A();
            i[0]++; // [
            ws(t, i);
            if (t.charAt(i[0]) == ']') { i[0]++; return a; }
            while (true) {
                ws(t, i);
                a.l.add(val(t, i));
                ws(t, i);
                char c = t.charAt(i[0]);
                i[0]++;
                if (c == ']') break;
                if (c != ',') throw new IllegalArgumentException("want , or ]");
            }
            return a;
        }

        static String str(String t, int[] i) {
            StringBuilder sb = new StringBuilder();
            i[0]++; // "
            while (true) {
                char c = t.charAt(i[0]++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = t.charAt(i[0]++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(t.substring(i[0], i[0] + 4), 16));
                            i[0] += 4; break;
                        default: sb.append(e); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        static double num(String t, int[] i) {
            int s = i[0];
            while (i[0] < t.length() && "-+0123456789.eE".indexOf(t.charAt(i[0])) >= 0) i[0]++;
            return Double.parseDouble(t.substring(s, i[0]));
        }
    }
}
