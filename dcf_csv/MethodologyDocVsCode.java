// MethodologyDocVsCode.java — audit gap D3.
//
// Keeps the canonical methodology doc (research_notes/dcf_methodology.md)
// and the code that implements it from drifting apart. The invariant table
// (dcf_csv/methodology_invariants.json) lists (doc claim -> code anchor
// regex) pairs; this verifier checks every anchor against the code and
// every doc-sourced claim against the doc.
//
// A claim with no code anchor is an ORPHAN (WARN): either the code moved
// (fix the table's code_file/anchor) or the doc now promises something the
// code does not do (fix the doc). Orphans are WARN, never FAIL, because
// resolving them is a judgment call for Rahik/Muse, not a mechanical truth.
//
// The table grows without code changes: add an entry to
// methodology_invariants.json and it is checked on the next --verify.
//
// Modes: --self-test | --verify (Finding.split -> Verify.report, exit 1 on FAIL).
// Audit-write guard: REPORT only — this tool edits nothing (doc, table, code).
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class MethodologyDocVsCode {

    static final String HOME = home();
    static final String DOC = HOME + "/workspace/research_notes/dcf_methodology.md";
    static final String TABLE = HOME + "/workspace/dcf_csv/methodology_invariants.json";
    static final String CHECK_ID = "D3";
    /** Marker that a claim comes from the canonical doc (vs a standing rule). */
    static final String DOC_MARKER = "dcf_methodology.md";

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /** One invariant-table entry. */
    static final class Invariant {
        String id, claim, claimSource, codeFile, anchor, notes;
    }

    /** Pure check logic — parsed invariants + file contents in, findings out.
     *  fileContents maps workspace-relative paths to file text; a missing
     *  key means the file was not found. Separated from I/O for --self-test. */
    static List<Finding> audit(List<Invariant> invariants, String docText,
                               Map<String, String> fileContents, String tablePath) {
        List<Finding> findings = new ArrayList<>();
        int ok = 0;
        for (Invariant inv : invariants) {
            String evidence = tablePath + " (invariant " + inv.id + ")";
            // 1. Doc-sourced claims must still be present in the doc — this
            //    keeps the table itself honest about what the doc says.
            if (inv.claimSource != null && inv.claimSource.contains(DOC_MARKER)) {
                String section = sectionOf(inv.claimSource);
                if (docText == null || !docText.contains(section)) {
                    findings.add(Finding.warn(CHECK_ID, "-", "doc_anchor/" + inv.id,
                            "claim grounded in doc section '" + section + "'",
                            "section marker not found in dcf_methodology.md",
                            evidence,
                            "the doc may have been reorganized — update claim_source"
                                    + " (the section marker after §) or restore the section"));
                    continue;
                }
            }
            // 2. The code anchor must match the implementing file.
            if (!fileContents.containsKey(inv.codeFile)) {
                findings.add(Finding.warn(CHECK_ID, "-", "orphan/" + inv.id,
                        "code anchor present: " + inv.anchor,
                        "file not found: " + inv.codeFile,
                        evidence,
                        "either the code moved (fix code_file/anchor in the table)"
                                + " or the claim is stale (fix the doc/standing rule)"));
                continue;
            }
            Pattern rx;
            try {
                rx = Pattern.compile(inv.anchor, Pattern.DOTALL);
            } catch (PatternSyntaxException ex) {
                findings.add(Finding.warn(CHECK_ID, "-", "bad_anchor/" + inv.id,
                        "valid Java regex", "PatternSyntaxException: " + ex.getMessage(),
                        evidence,
                        "fix the anchor regex in the table — the claim itself is untested meanwhile"));
                continue;
            }
            if (!rx.matcher(fileContents.get(inv.codeFile)).find()) {
                findings.add(Finding.warn(CHECK_ID, "-", "orphan/" + inv.id,
                        "code anchor present: " + inv.anchor,
                        "no match in " + inv.codeFile,
                        evidence,
                        "MANUAL REVIEW: either the implementation moved (update code_file/"
                                + "anchor) or the code drifted from the documented claim"
                                + " (fix the code, or correct the doc)"));
                continue;
            }
            ok++;
            findings.add(Finding.info(CHECK_ID, "-", "anchored/" + inv.id,
                    inv.claim, inv.codeFile + " matches",
                    evidence, "none"));
        }
        findings.add(0, Finding.info(CHECK_ID, "-", "summary",
                invariants.size() + " invariant(s) checked",
                ok + " anchored, " + (invariants.size() - ok) + " needing review",
                tablePath, "none"));
        return findings;
    }

    /** The doc section marker: text after "§" in the claim_source. */
    static String sectionOf(String claimSource) {
        int i = claimSource.indexOf('§');
        return i < 0 ? claimSource.trim() : claimSource.substring(i + 1).trim();
    }

    // =====================================================================
    // --verify: real inputs
    // =====================================================================

    @SuppressWarnings("unchecked")
    static List<Invariant> readTable(String jsonText) {
        List<Invariant> out = new ArrayList<>();
        Map<String, Object> doc = (Map<String, Object>) Json.parse(jsonText);
        Object list = doc.get("invariants");
        if (!(list instanceof List)) return out;
        for (Object o : (List<Object>) list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            Invariant inv = new Invariant();
            inv.id = str(m.get("id"));
            inv.claim = str(m.get("claim"));
            inv.claimSource = str(m.get("claim_source"));
            inv.codeFile = str(m.get("code_file"));
            inv.anchor = str(m.get("anchor"));
            inv.notes = str(m.get("notes"));
            out.add(inv);
        }
        return out;
    }

    static void verify() throws Exception {
        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        String tableText = Files.readString(Paths.get(TABLE), StandardCharsets.UTF_8);
        List<Invariant> invariants = readTable(tableText);
        String docText = Files.isRegularFile(Paths.get(DOC))
                ? Files.readString(Paths.get(DOC), StandardCharsets.UTF_8) : null;
        if (docText == null)
            infos.add("canonical doc not found at " + DOC + " — doc anchoring skipped");

        Map<String, String> fileContents = new LinkedHashMap<>();
        for (Invariant inv : invariants) {
            if (fileContents.containsKey(inv.codeFile)) continue;
            Path p = Paths.get(HOME, "workspace", inv.codeFile);
            if (Files.isRegularFile(p))
                fileContents.put(inv.codeFile,
                        Files.readString(p, StandardCharsets.UTF_8));
        }
        infos.add("doc: " + DOC);
        infos.add("table: " + TABLE + " (" + invariants.size() + " invariants)");

        List<Finding> findings = audit(invariants, docText, fileContents, TABLE);
        Finding.split(findings, infos, failures);
        Verify.report(
                "VERIFY OK (MethodologyDocVsCode): all " + invariants.size()
                        + " methodology claims are anchored in code.",
                "VERIFY FAILED (MethodologyDocVsCode): " + failures.size()
                        + " problem(s) found:",
                infos, failures);
    }

    // =====================================================================
    // Self-test
    // =====================================================================

    static Invariant inv(String id, String source, String file, String anchor) {
        Invariant i = new Invariant();
        i.id = id; i.claim = "claim " + id; i.claimSource = source;
        i.codeFile = file; i.anchor = anchor; i.notes = "";
        return i;
    }

    static void selfTest() {
        String doc = "## Standard model structure\n\n3. Discount at exponent y - 0.5.\n";

        List<Invariant> invs = List.of(
                // M1: doc section present, code anchor matches.
                inv("M1", "dcf_methodology.md § Standard model structure",
                        "dcf_csv/DcfModel.java", "y - 0\\.5"),
                // M9: anchor no longer matches the code -> orphan WARN.
                inv("M9", "standing rule (AGENTS.md § Audit write guard)",
                        "dcf_csv/Proposals.java", "TV_SHARE_WARN = 0\\.99"),
                // MX: code file gone entirely -> orphan WARN.
                inv("MX", "dcf_methodology.md § Standard model structure",
                        "dcf_csv/Deleted.java", "anything"),
                // MB: broken regex -> bad_anchor WARN.
                inv("MB", "dcf_methodology.md § Standard model structure",
                        "dcf_csv/DcfModel.java", "(unclosed"),
                // MD: doc section marker no longer in the doc -> doc_anchor WARN.
                inv("MD", "dcf_methodology.md § Removed section",
                        "dcf_csv/DcfModel.java", "y - 0\\.5"));

        Map<String, String> files = Map.of(
                "dcf_csv/DcfModel.java", "double df = Math.pow(1 + w, y - 0.5);",
                "dcf_csv/Proposals.java", "static final double TV_SHARE_WARN = 0.75;");

        List<Finding> f = audit(invs, doc, files, "table");
        SelfTest.check("M1 anchored -> no WARN/FAIL",
                f.stream().noneMatch(x -> "M1".equals(x.field.replace("anchored/", "")
                        .replace("orphan/", "").replace("doc_anchor/", "")
                        .replace("bad_anchor/", ""))
                        && x.severity != Finding.Severity.INFO));
        SelfTest.check("M9 stale anchor -> orphan WARN not FAIL",
                f.stream().anyMatch(x -> x.severity == Finding.Severity.WARN
                        && x.field.equals("orphan/M9"))
                        && f.stream().noneMatch(x -> x.severity == Finding.Severity.FAIL
                        && x.field.contains("M9")));
        SelfTest.check("MX missing file -> orphan WARN",
                f.stream().anyMatch(x -> x.severity == Finding.Severity.WARN
                        && x.field.equals("orphan/MX")));
        SelfTest.check("MB broken regex -> bad_anchor WARN",
                f.stream().anyMatch(x -> x.severity == Finding.Severity.WARN
                        && x.field.equals("bad_anchor/MB")));
        SelfTest.check("MD removed doc section -> doc_anchor WARN",
                f.stream().anyMatch(x -> x.severity == Finding.Severity.WARN
                        && x.field.equals("doc_anchor/MD")));

        // All-green table: single matching invariant -> summary only, exit path.
        List<Finding> g = audit(
                List.of(inv("M1", "dcf_methodology.md § Standard model structure",
                        "dcf_csv/DcfModel.java", "y - 0\\.5")),
                doc, files, "table");
        SelfTest.check("clean table: no WARN/FAIL",
                g.stream().noneMatch(x -> x.severity == Finding.Severity.WARN
                        || x.severity == Finding.Severity.FAIL));
        SelfTest.check("summary INFO first",
                g.get(0).severity == Finding.Severity.INFO
                        && g.get(0).field.equals("summary"));

        // Table parser round-trip on the real file shape.
        String tableJson = "{\"invariants\": [{\"id\": \"T1\", \"claim\": \"c\","
                + " \"claim_source\": \"s\", \"code_file\": \"f\", \"anchor\": \"a\","
                + " \"notes\": \"n\"}]}";
        List<Invariant> parsed = readTable(tableJson);
        SelfTest.checkEq("table parser: one invariant", 1, parsed.size());
        SelfTest.checkEq("table parser: id survives", "T1", parsed.get(0).id);

        SelfTest.finish();
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "--self-test": selfTest(); break;
            case "--verify": verify(); break;
            default:
                System.err.println("Usage: java MethodologyDocVsCode.java --self-test | --verify");
                System.exit(2);
        }
    }

    // =====================================================================
    // Minimal JSON reader (parse only) — see GrowthOutlookHonesty.java for why.
    // =====================================================================
    static final class Json {
        private final String s;
        private int pos;
        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.skipWs();
            Object v = j.readValue();
            j.skipWs();
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        private Object readValue() {
            char c = s.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return readNumber();
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("bad literal");
            pos += lit.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':'");
                pos++;
                skipWs();
                m.put(key, readValue());
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' or '}'");
            }
        }

        private List<Object> readArray() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(readValue());
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected ',' or ']'");
            }
        }

        private String readString() {
            if (s.charAt(pos) != '"') throw new IllegalArgumentException("expected string");
            pos++;
            StringBuilder b = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: b.append(e); break;
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Double readNumber() {
            int start = pos;
            while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
