// LayoutDrift.java — audit gap C1: drift between a freshly browser-read
// NotebookLM notebook listing and the expected layout in
// notebooklm_layout.json (the single source of truth — executors and
// auditors read it, never chat summaries).
//
// The browser leg that PRODUCES the read file rides inside the nightly
// re-ask job (it already opens both notebooks); this tool is the DIFF only.
// Report-only per the audit-write guard: it never edits the layout file.
//
// Browser-read JSON schema (written by the read leg):
//   goals/quarterly-sec-filing-watch-with-dcf-updates/hidden_files/notebooklm_read_latest.json
// Full schema doc: .../hidden_files/notebooklm_read_schema.md
//
// Usage:
//   java LayoutDrift.java --self-test
//   java LayoutDrift.java --verify [--read <read-file.json>]
// (--read defaults to hidden_files/notebooklm_read_latest.json; when no
// read file exists the run reports NOT-INITIALIZED and exits 0.)
//
// Launch with the shared sources visible:
//   java --class-path ~/workspace/java-shared/classes LayoutDrift.java --verify
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class LayoutDrift {

    static final String GOAL_HIDDEN =
        "workspace/goals/quarterly-sec-filing-watch-with-dcf-updates/hidden_files";
    static final String LAYOUT_FILE = GOAL_HIDDEN + "/notebooklm_layout.json";
    static final String DEFAULT_READ_FILE = GOAL_HIDDEN + "/notebooklm_read_latest.json";
    /** NotebookLM's hard source cap — the Night-1 re-ask run blocked on it. */
    static final int SOURCE_CAP = 50;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length >= 1 && args[0].equals("--verify")) {
            String readArg = null;
            for (int i = 1; i < args.length - 1; i++) {
                if (args[i].equals("--read")) readArg = args[i + 1];
            }
            verify(readArg);
            return;
        }
        System.err.println("usage: java LayoutDrift.java --self-test | --verify [--read <read-file.json>]");
        System.exit(2);
    }

    // ---- verify ------------------------------------------------------------
    static void verify(String readArg) throws IOException {
        Path home = Paths.get(System.getenv("HOME"));
        Path layoutPath = home.resolve(LAYOUT_FILE);
        if (!Files.isRegularFile(layoutPath)) {
            System.err.println("FATAL: expected-state file missing: " + layoutPath);
            System.exit(2);
        }
        J.O layout = (J.O) J.parse(Files.readString(layoutPath, StandardCharsets.UTF_8));

        Path readPath = (readArg != null) ? Paths.get(readArg) : home.resolve(DEFAULT_READ_FILE);
        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (!Files.isRegularFile(readPath)) {
            infos.add("not yet initialized — no browser-read file at " + readPath
                    + "; the read leg (nightly re-ask job) has not produced one yet. "
                    + "Schema: " + GOAL_HIDDEN + "/notebooklm_read_schema.md");
            Verify.report("VERIFY OK (LayoutDrift): no read file yet — nothing to diff.",
                    "VERIFY FAILED (LayoutDrift):", infos, failures);
            return;
        }
        J.O read = (J.O) J.parse(Files.readString(readPath, StandardCharsets.UTF_8));

        List<Finding> findings = diffLayout(layout, read, layoutPath.toString(), readPath.toString());
        int failCount = 0;
        for (Finding f : findings) if (f.severity == Finding.Severity.FAIL) failCount++;
        infos.add("diffed " + readPath.getFileName() + " against notebooklm_layout.json: "
                + findings.size() + " finding(s), " + failCount + " FAIL");
        Finding.split(findings, infos, failures);
        Verify.report(
                "VERIFY OK (LayoutDrift): notebook listing matches the expected layout.",
                "VERIFY FAILED (LayoutDrift): " + failures.size() + " problem(s) found:",
                infos, failures);
    }

    // ---- the diff ----------------------------------------------------------
    // Pure function: synthetic layouts/reads in --self-test exercise the
    // same code that runs against the real files in --verify.
    static List<Finding> diffLayout(J.O layout, J.O read,
                                    String layoutLabel, String readLabel) {
        List<Finding> out = new ArrayList<>();
        J.O layoutNotebooks = obj(layout, "notebooks");
        J.O expectedNotes = obj(layout, "expected_notes");
        J.O readNotebooks = obj(read, "notebooks");
        String evidenceBase = "expected: " + layoutLabel + "; read: " + readLabel;

        // The notes check applies to the notebook named by expected_notes
        // (the forward-tilt notes live in the main notebook).
        String notesNotebookName = str(expectedNotes, "notebook");
        J.O tiltTitles = obj(expectedNotes, "forward_tilt");
        Map<String, String> expectedNoteByTicker = new LinkedHashMap<>();
        for (Map.Entry<String, J.V> e : tiltTitles.m.entrySet()) {
            if (e.getValue() instanceof J.S) expectedNoteByTicker.put(e.getKey(), ((J.S) e.getValue()).s);
        }

        Set<String> readNames = new LinkedHashSet<>();
        for (Map.Entry<String, J.V> e : readNotebooks.m.entrySet()) {
            if (e.getValue() instanceof J.O) {
                String nm = str((J.O) e.getValue(), "name");
                readNames.add(nm.isEmpty() ? e.getKey() : nm);
            }
        }

        for (Map.Entry<String, J.V> e : layoutNotebooks.m.entrySet()) {
            if (!(e.getValue() instanceof J.O)) continue;
            J.O nb = (J.O) e.getValue();
            String name = str(nb, "name");
            List<String> expectedSources = strList(nb, "sources");

            J.O readNb = findNotebook(readNotebooks, name);
            if (readNb == null) {
                out.add(Finding.fail("C1", "-", "notebook.present",
                        "notebook '" + name + "' present in read", "absent from read",
                        evidenceBase,
                        "the read leg did not open/read this notebook — check the nightly re-ask job's browser leg"));
                continue;
            }
            List<String> actualSources = strList(readNb, "sources");

            // Duplicate titles in the read make the set diff ambiguous.
            Set<String> seen = new LinkedHashSet<>();
            List<String> dups = new ArrayList<>();
            for (String t : actualSources) {
                if (!seen.add(t)) dups.add(t);
            }
            if (!dups.isEmpty()) {
                out.add(Finding.fail("C1", "-", "sources.duplicates",
                        "each source title listed once", "duplicated: " + String.join("; ", dups),
                        evidenceBase,
                        "dedupe the read (or fix the notebook) and re-run the read leg"));
            }

            Set<String> expectedSet = new LinkedHashSet<>(expectedSources);
            Set<String> actualSet = new LinkedHashSet<>(actualSources);
            List<String> missing = new ArrayList<>();
            for (String t : expectedSet) if (!actualSet.contains(t)) missing.add(t);
            List<String> unexpected = new ArrayList<>();
            for (String t : actualSet) if (!expectedSet.contains(t)) unexpected.add(t);

            if (!missing.isEmpty()) {
                out.add(Finding.fail("C1", "-", "sources.missing",
                        expectedSources.size() + " expected titles all present",
                        "missing " + missing.size() + ": " + String.join("; ", missing),
                        evidenceBase + "; layout rule: " + str(nb, "rule"),
                        "retention-policy one-in-one-out: restore the missing source(s), "
                                + "or bless the change by updating notebooklm_layout.json in the same pass "
                                + "(Stock Research owns it)"));
            }
            if (!unexpected.isEmpty()) {
                out.add(Finding.fail("C1", "-", "sources.unexpected",
                        "no titles beyond the expected set",
                        "unexpected " + unexpected.size() + ": " + String.join("; ", unexpected),
                        evidenceBase + "; layout rule: " + str(nb, "rule"),
                        "remove the unexpected source(s), or bless them by updating "
                                + "notebooklm_layout.json in the same pass"));
            }
            if (actualSources.size() > SOURCE_CAP) {
                out.add(Finding.fail("C1", "-", "sources.cap",
                        "<= " + SOURCE_CAP + " sources (NotebookLM hard cap)",
                        actualSources.size() + " sources",
                        evidenceBase,
                        "remove sources until <= " + SOURCE_CAP
                                + " — above the cap NotebookLM blocks the re-ask legs (Night-1 precedent)"));
            }
            if (missing.isEmpty() && unexpected.isEmpty()
                    && !expectedSources.equals(actualSources)) {
                out.add(Finding.warn("C1", "-", "sources.order",
                        "titles in layout order", "same title set, different order",
                        evidenceBase,
                        "informational only — re-run the read leg if order matters to the job"));
            }

            // Notes are checked in a separate block below (the layout's
            // expected_notes.notebook name does not always equal the main
            // notebook's display name — see the notes block).
        }

        // Notes: exactly one '[TICKER] DCF forward tilt' note per ticker.
        // The layout file specifies forward_tilt titles only; grounded
        // '[TICKER] DCF intrinsic value' notes are not enumerated there
        // and are therefore not diffed here.
        if (!expectedNoteByTicker.isEmpty()) {
            String mainName = "";
            J.V mainV = layoutNotebooks.m.get("main");
            if (mainV instanceof J.O) mainName = str((J.O) mainV, "name");
            String targetName = notesNotebookName.isEmpty() ? mainName : notesNotebookName;
            J.O notesReadNb = findNotebook(readNotebooks, targetName);
            if (notesReadNb == null && !targetName.equals(mainName)) {
                notesReadNb = findNotebook(readNotebooks, mainName);
                if (notesReadNb != null) {
                    out.add(Finding.warn("C1", "-", "notes.notebook_name",
                            "expected_notes.notebook matching a read notebook",
                            "no read notebook named '" + notesNotebookName
                                    + "' — notes checked against '" + mainName + "' instead",
                            evidenceBase,
                            "fix the expected_notes.notebook name in notebooklm_layout.json "
                                    + "(Stock Research owns it)"));
                }
            }
            if (notesReadNb == null) {
                out.add(Finding.fail("C1", "-", "notes.notebook_present",
                        "notebook '" + targetName + "' present in read",
                        "absent from read",
                        evidenceBase,
                        "the read leg did not open/read the notes notebook — "
                                + "check the nightly re-ask job's browser leg"));
            } else {
                List<String> actualNotes = strList(notesReadNb, "notes");
                Set<String> seenN = new LinkedHashSet<>();
                List<String> dupN = new ArrayList<>();
                for (String t : actualNotes) {
                    if (!seenN.add(t)) dupN.add(t);
                }
                if (!dupN.isEmpty()) {
                    out.add(Finding.fail("C1", "-", "notes.duplicates",
                            "each note title listed once", "duplicated: " + String.join("; ", dupN),
                            evidenceBase,
                            "a duplicate note title is a verification failure — the F-AUDIT "
                                    + "cannot tell which note the value came from"));
                }
                Set<String> actualNoteSet = new LinkedHashSet<>(actualNotes);
                for (Map.Entry<String, String> ne : expectedNoteByTicker.entrySet()) {
                    if (!actualNoteSet.contains(ne.getValue())) {
                        out.add(Finding.fail("C1", ne.getKey(), "notes.missing",
                                "note '" + ne.getValue() + "' present",
                                "note missing from read",
                                evidenceBase,
                                "the forward-tilt leg must create the note add-then-verify; "
                                        + "never file a proposal for a ticker whose note is missing"));
                    }
                }
                Set<String> expectedNoteSet = new LinkedHashSet<>(expectedNoteByTicker.values());
                for (String t : actualNoteSet) {
                    if (expectedNoteSet.contains(t)) continue;
                    if (t.matches("^\\[.+\\] DCF forward tilt$")) {
                        // A forward-tilt-titled note the layout does not
                        // enumerate: stray or duplicate voice — a failure.
                        out.add(Finding.fail("C1", "-", "notes.unexpected",
                                "only the expected forward-tilt note titles",
                                "unexpected forward-tilt note: '" + t + "'",
                                evidenceBase,
                                "remove the stray note, or bless it by updating "
                                        + "notebooklm_layout.json expected_notes in the same pass"));
                    } else {
                        // Notes the layout does not enumerate (e.g. the
                        // grounded '[TICKER] DCF intrinsic value' notes):
                        // recorded, not checked by C1.
                        out.add(Finding.info("C1", "-", "notes.unlisted",
                                "enumerated in expected_notes", "not enumerated: '" + t + "'",
                                evidenceBase,
                                "no action — C1 only diffs the forward-tilt titles the layout specifies"));
                    }
                }
            }
        }

        // Read notebooks the layout does not know about.
        Set<String> layoutNames = new LinkedHashSet<>();
        for (J.V v : layoutNotebooks.m.values()) {
            if (v instanceof J.O) layoutNames.add(str((J.O) v, "name"));
        }
        for (String rn : readNames) {
            if (!layoutNames.contains(rn) && !rn.isEmpty()) {
                out.add(Finding.warn("C1", "-", "notebook.unexpected",
                        "only layout notebooks in the read",
                        "read contains unexpected notebook '" + rn + "'",
                        evidenceBase,
                        "check whether the read leg opened the wrong notebook"));
            }
        }
        return out;
    }

    static J.O findNotebook(J.O readNotebooks, String name) {
        for (J.V v : readNotebooks.m.values()) {
            if (!(v instanceof J.O)) continue;
            J.O nb = (J.O) v;
            String nm = str(nb, "name");
            if (name.equals(nm)) return nb;
        }
        return null;
    }

    // ---- self test ---------------------------------------------------------
    static void selfTest() {
        // Mini layout: 2 sources in main + 1 note; 1 source in overflow.
        J.O layout = (J.O) J.parse(
            "{\"notebooks\":{"
            + "\"main\":{\"name\":\"Main\",\"expected_count\":2,"
            + "\"rule\":\"annuals only\",\"sources\":[\"10-K_A.txt\",\"10-K_B.txt\"]},"
            + "\"overflow\":{\"name\":\"Overflow\",\"expected_count\":1,"
            + "\"rule\":\"interims\",\"sources\":[\"10-Q_A.txt\"]}},"
            + "\"expected_notes\":{\"notebook\":\"Main\","
            + "\"forward_tilt\":{\"AAA\":\"[AAA] DCF forward tilt\"}}}");
        J.O cleanRead = (J.O) J.parse(
            "{\"read_at\":\"2026-09-30T02:51:00-04:00\",\"notebooks\":{"
            + "\"Main\":{\"name\":\"Main\",\"sources\":[\"10-K_A.txt\",\"10-K_B.txt\"],"
            + "\"notes\":[\"[AAA] DCF forward tilt\"]},"
            + "\"Overflow\":{\"name\":\"Overflow\",\"sources\":[\"10-Q_A.txt\"],\"notes\":[]}}}");

        List<Finding> f = diffLayout(layout, cleanRead, "L", "R");
        SelfTest.checkEq("clean read: no findings", 0, f.size());
        SelfTest.check("clean read: no FAIL", !Finding.anyFail(f));

        // Missing source.
        J.O missSrc = (J.O) J.parse(
            "{\"notebooks\":{\"Main\":{\"name\":\"Main\","
            + "\"sources\":[\"10-K_A.txt\"],\"notes\":[\"[AAA] DCF forward tilt\"]},"
            + "\"Overflow\":{\"name\":\"Overflow\",\"sources\":[\"10-Q_A.txt\"],\"notes\":[]}}}");
        List<Finding> f2 = diffLayout(layout, missSrc, "L", "R");
        SelfTest.check("missing source: anyFail", Finding.anyFail(f2));
        SelfTest.checkEq("missing source: one finding", 1, f2.size());
        SelfTest.checkEq("missing source: field", "sources.missing", f2.get(0).field);
        SelfTest.checkContains("missing source: names the title", f2.get(0).actual, "10-K_B.txt");

        // Unexpected source.
        J.O extraSrc = (J.O) J.parse(
            "{\"notebooks\":{\"Main\":{\"name\":\"Main\","
            + "\"sources\":[\"10-K_A.txt\",\"10-K_B.txt\",\"STRAY.pdf\"],\"notes\":[\"[AAA] DCF forward tilt\"]},"
            + "\"Overflow\":{\"name\":\"Overflow\",\"sources\":[\"10-Q_A.txt\"],\"notes\":[]}}}");
        List<Finding> f3 = diffLayout(layout, extraSrc, "L", "R");
        SelfTest.check("unexpected source: anyFail", Finding.anyFail(f3));
        SelfTest.checkEq("unexpected source: field", "sources.unexpected", f3.get(0).field);
        SelfTest.checkContains("unexpected source: names the title", f3.get(0).actual, "STRAY.pdf");

        // Duplicate titles.
        J.O dupSrc = (J.O) J.parse(
            "{\"notebooks\":{\"Main\":{\"name\":\"Main\","
            + "\"sources\":[\"10-K_A.txt\",\"10-K_A.txt\",\"10-K_B.txt\"],\"notes\":[\"[AAA] DCF forward tilt\"]},"
            + "\"Overflow\":{\"name\":\"Overflow\",\"sources\":[\"10-Q_A.txt\"],\"notes\":[]}}}");
        List<Finding> f4 = diffLayout(layout, dupSrc, "L", "R");
        SelfTest.check("duplicate source: anyFail", Finding.anyFail(f4));
        boolean hasDup = false;
        for (Finding x : f4) if (x.field.equals("sources.duplicates")) hasDup = true;
        SelfTest.check("duplicate source: duplicates field present", hasDup);

        // 50-source cap.
        StringBuilder many = new StringBuilder("{\"notebooks\":{\"Main\":{\"name\":\"Main\",\"sources\":[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) many.append(',');
            many.append("\"S").append(i).append(".txt\"");
        }
        many.append("],\"notes\":[]},\"Overflow\":{\"name\":\"Overflow\",\"sources\":[],\"notes\":[]}}}");
        List<Finding> f5 = diffLayout(layout, (J.O) J.parse(many.toString()), "L", "R");
        boolean hasCap = false;
        for (Finding x : f5) if (x.field.equals("sources.cap")) hasCap = true;
        SelfTest.check("51 sources: cap FAIL", hasCap && Finding.anyFail(f5));

        // Missing note.
        J.O missNote = (J.O) J.parse(
            "{\"notebooks\":{\"Main\":{\"name\":\"Main\","
            + "\"sources\":[\"10-K_A.txt\",\"10-K_B.txt\"],\"notes\":[]},"
            + "\"Overflow\":{\"name\":\"Overflow\",\"sources\":[\"10-Q_A.txt\"],\"notes\":[]}}}");
        List<Finding> f6 = diffLayout(layout, missNote, "L", "R");
        SelfTest.check("missing note: anyFail", Finding.anyFail(f6));
        SelfTest.checkEq("missing note: field", "notes.missing", f6.get(0).field);
        SelfTest.checkEq("missing note: ticker carried", "AAA", f6.get(0).ticker);

        // Unexpected forward-tilt note (matches the tilt title pattern).
        J.O extraNote = (J.O) J.parse(
            "{\"notebooks\":{\"Main\":{\"name\":\"Main\","
            + "\"sources\":[\"10-K_A.txt\",\"10-K_B.txt\"],"
            + "\"notes\":[\"[AAA] DCF forward tilt\",\"[ZZZ] DCF forward tilt\"]},"
            + "\"Overflow\":{\"name\":\"Overflow\",\"sources\":[\"10-Q_A.txt\"],\"notes\":[]}}}");
        List<Finding> f7 = diffLayout(layout, extraNote, "L", "R");
        boolean hasUnexpectedNote = false;
        for (Finding x : f7) if (x.field.equals("notes.unexpected")) hasUnexpectedNote = true;
        SelfTest.check("unexpected tilt note: FAIL", hasUnexpectedNote && Finding.anyFail(f7));

        // Non-tilt note (e.g. a grounded '[TICKER] DCF intrinsic value' note)
        // is recorded as INFO, never a failure — the layout does not
        // enumerate grounded notes.
        J.O groundedNote = (J.O) J.parse(
            "{\"notebooks\":{\"Main\":{\"name\":\"Main\","
            + "\"sources\":[\"10-K_A.txt\",\"10-K_B.txt\"],"
            + "\"notes\":[\"[AAA] DCF forward tilt\",\"[AAA] DCF intrinsic value\"]},"
            + "\"Overflow\":{\"name\":\"Overflow\",\"sources\":[\"10-Q_A.txt\"],\"notes\":[]}}}");
        List<Finding> f7b = diffLayout(layout, groundedNote, "L", "R");
        SelfTest.check("grounded note: no FAIL", !Finding.anyFail(f7b));
        boolean hasUnlisted = false;
        for (Finding x : f7b) {
            if (x.field.equals("notes.unlisted") && x.severity == Finding.Severity.INFO) hasUnlisted = true;
        }
        SelfTest.check("grounded note: INFO unlisted", hasUnlisted);

        // Order differs, sets equal -> WARN only, no FAIL.
        J.O reordered = (J.O) J.parse(
            "{\"notebooks\":{\"Main\":{\"name\":\"Main\","
            + "\"sources\":[\"10-K_B.txt\",\"10-K_A.txt\"],\"notes\":[\"[AAA] DCF forward tilt\"]},"
            + "\"Overflow\":{\"name\":\"Overflow\",\"sources\":[\"10-Q_A.txt\"],\"notes\":[]}}}");
        List<Finding> f8 = diffLayout(layout, reordered, "L", "R");
        SelfTest.checkEq("reordered: one finding", 1, f8.size());
        SelfTest.checkEq("reordered: WARN severity",
                Finding.Severity.WARN, f8.get(0).severity);
        SelfTest.check("reordered: no FAIL", !Finding.anyFail(f8));

        // Whole notebook missing from the read.
        J.O missingNb = (J.O) J.parse(
            "{\"notebooks\":{\"Main\":{\"name\":\"Main\","
            + "\"sources\":[\"10-K_A.txt\",\"10-K_B.txt\"],\"notes\":[\"[AAA] DCF forward tilt\"]}}}");
        List<Finding> f9 = diffLayout(layout, missingNb, "L", "R");
        SelfTest.check("missing notebook: anyFail", Finding.anyFail(f9));
        SelfTest.checkEq("missing notebook: field", "notebook.present", f9.get(0).field);

        // Unknown notebook in the read -> WARN.
        J.O extraNb = (J.O) J.parse(
            "{\"notebooks\":{\"Main\":{\"name\":\"Main\","
            + "\"sources\":[\"10-K_A.txt\",\"10-K_B.txt\"],\"notes\":[\"[AAA] DCF forward tilt\"]},"
            + "\"Overflow\":{\"name\":\"Overflow\",\"sources\":[\"10-Q_A.txt\"],\"notes\":[]},"
            + "\"Stray\":{\"name\":\"Stray\",\"sources\":[],\"notes\":[]}}}");
        List<Finding> f10 = diffLayout(layout, extraNb, "L", "R");
        SelfTest.check("unknown notebook: no FAIL", !Finding.anyFail(f10));
        boolean hasNbWarn = false;
        for (Finding x : f10) if (x.field.equals("notebook.unexpected")) hasNbWarn = true;
        SelfTest.check("unknown notebook: WARN present", hasNbWarn);

        // Finding rendering: key-value facts, never prose blobs.
        String rendered = f2.get(0).toString();
        SelfTest.checkContains("finding renders check id", rendered, "check: C1");
        SelfTest.checkContains("finding renders severity", rendered, "severity: FAIL");

        SelfTest.finish();
    }

    // ---- small JSON helpers ------------------------------------------------
    static J.O obj(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.O) ? (J.O) v : new J.O();
    }

    static String str(J.O o, String k) {
        J.V v = o.m.get(k);
        return (v instanceof J.S) ? ((J.S) v).s : "";
    }

    static List<String> strList(J.O o, String k) {
        List<String> out = new ArrayList<>();
        J.V v = o.m.get(k);
        if (v instanceof J.A) {
            for (J.V e : ((J.A) v).l) {
                if (e instanceof J.S) out.add(((J.S) e).s);
            }
        }
        return out;
    }

    // ---- minimal JSON (same pattern as WebsiteConsensus.J) ------------------
    static final class J {
        abstract static class V { }
        static final class O extends V { final LinkedHashMap<String, V> m = new LinkedHashMap<>(); }
        static final class A extends V { final ArrayList<V> l = new ArrayList<>(); }
        static final class S extends V { final String s; S(String s) { this.s = s; } }
        static final class N extends V { final double d; final String lit; N(double d, String lit) { this.d = d; this.lit = lit; } }
        static final class B extends V { final boolean b; B(boolean b) { this.b = b; } }
        static final class Nil extends V { }

        static V parse(String t) {
            P p = new P(t);
            V v = p.val();
            p.ws();
            if (p.i != t.length()) throw new IllegalArgumentException("trailing characters at " + p.i);
            return v;
        }

        static final class P {
            final String t; int i;
            P(String t) { this.t = t; }
            void ws() {
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
                }
            }
            V val() {
                ws();
                if (i >= t.length()) throw new IllegalArgumentException("unexpected end of input");
                char c = t.charAt(i);
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return new S(str());
                if (c == 't') { exp("true"); return new B(true); }
                if (c == 'f') { exp("false"); return new B(false); }
                if (c == 'n') { exp("null"); return new Nil(); }
                return num();
            }
            void exp(String s) {
                if (t.startsWith(s, i)) i += s.length();
                else throw new IllegalArgumentException("expected '" + s + "' at " + i);
            }
            O obj() {
                O o = new O(); i++; ws();
                if (i < t.length() && t.charAt(i) == '}') { i++; return o; }
                while (true) {
                    ws();
                    if (i >= t.length() || t.charAt(i) != '"') throw new IllegalArgumentException("expected key at " + i);
                    String k = str(); ws();
                    if (i >= t.length() || t.charAt(i) != ':') throw new IllegalArgumentException("expected ':' at " + i);
                    i++;
                    o.m.put(k, val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == '}') { i++; break; }
                    throw new IllegalArgumentException("expected ',' or '}' at " + i);
                }
                return o;
            }
            A arr() {
                A a = new A(); i++; ws();
                if (i < t.length() && t.charAt(i) == ']') { i++; return a; }
                while (true) {
                    a.l.add(val()); ws();
                    if (i < t.length() && t.charAt(i) == ',') { i++; continue; }
                    if (i < t.length() && t.charAt(i) == ']') { i++; break; }
                    throw new IllegalArgumentException("expected ',' or ']' at " + i);
                }
                return a;
            }
            String str() {
                StringBuilder sb = new StringBuilder(); i++;
                while (true) {
                    if (i >= t.length()) throw new IllegalArgumentException("unterminated string");
                    char c = t.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        if (i >= t.length()) throw new IllegalArgumentException("bad escape");
                        char e = t.charAt(i++);
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
                                if (i + 4 > t.length()) throw new IllegalArgumentException("bad \\u escape");
                                sb.append((char) Integer.parseInt(t.substring(i, i + 4), 16)); i += 4;
                                break;
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else sb.append(c);
                }
                return sb.toString();
            }
            V num() {
                int s = i;
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                    else break;
                }
                String lit = t.substring(s, i);
                try { return new N(Double.parseDouble(lit), lit); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("bad number at " + s); }
            }
        }
    }
}
