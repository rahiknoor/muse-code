import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SecMuseAudit - audit gap B1: the three-way cross-check of MY OWN DCF inputs.
 *
 * <p>Rahik's idea: NotebookLM (which holds every holding's latest 10-K/20-F +
 * 10-Q/6-K across the two filing notebooks and is the designated
 * concrete-evidence dispute arbiter) audits my stored DCF inputs. My inputs
 * live in {@code sec_filings/dcf_inputs_all.json}; NotebookLM's cited figures
 * arrive as a per-ticker TRANSCRIPTION RECORD (transcribed from a RECORDED
 * NotebookLM answer - provenance rule: recorded answers only, never live
 * chat memory); the third leg is SEC EDGAR XBRL companyfacts, read through
 * the shared {@link XbrlFacts} tidy-table reader.
 *
 * <p>Verdict logic, per input field (OCF, capex, diluted shares, cash, total
 * debt, revenue if present) and period (annual / quarterly):
 * <ul>
 *   <li>all three agree within tolerance -&gt; pass, no finding;
 *   <li>NotebookLM + XBRL agree against me -&gt; FAIL finding: my input is
 *       wrong -&gt; remediation "file proposal via Proposals.java";
 *   <li>me + XBRL agree against NotebookLM -&gt; WARN finding: my input
 *       stands -&gt; remediation "re-ask NotebookLM with filing evidence"
 *       (the existing SecNlmAudit correction flow);
 *   <li>XBRL differs from both of us -&gt; WARN: possible restated
 *       comparative -&gt; manual restatement check;
 *   <li>all three differ -&gt; WARN manual-review finding with all three
 *       values side by side, never silently picked;
 *   <li>XBRL conflicting contexts (the known QXO case: two contexts giving
 *       different OCF/capex) -&gt; CONFLICT WARN surfacing every context
 *       (accession, filed, frame, value), never silently picked.
 * </ul>
 *
 * <p>Tolerance mirrors SecNlmAudit exactly: 1% of |value| with a $1M floor
 * (USD millions), made symmetric for pairwise three-way comparison:
 * agree(a,b) iff |a-b| &lt;= max(1%|a|, 1%|b|, 1.0).
 *
 * <p><b>Report-only (hard rule).</b> This tool never writes backends. It files
 * nothing, approves nothing, never touches dcf_inputs_all.json, blessed.json,
 * or any derived file. Corrections travel the proposal -&gt; audit -&gt;
 * blessed -&gt; RebuildAll path, filed as proposals with this tool's
 * findings as evidence. The self-test proves the write path is absent by
 * scanning this source, the same way SecNlmAudit does.
 *
 * <p>Usage:
 * <pre>
 *   java SecMuseAudit.java --audit  sec_muse_audit/records/QXO.json   # one ticker
 *   java SecMuseAudit.java --audit-all                                 # every record
 *   java SecMuseAudit.java --verify                                   # tool health check
 *   java SecMuseAudit.java --self-test                                # unit tests
 * </pre>
 */
public class SecMuseAudit {

    // ------------------------------------------------------------------
    // Paths. HOME comes from the environment, never the user.home property.
    // ------------------------------------------------------------------
    static String HOME;
    static Path DCF_CSV, SEC_FILINGS, INPUTS_ALL, RECORDS;

    static void initPaths() {
        HOME = System.getenv("HOME");
        if (HOME == null || HOME.isEmpty()) throw new IllegalStateException("$HOME is not set");
        DCF_CSV = Paths.get(HOME, "workspace", "dcf_csv");
        SEC_FILINGS = Paths.get(HOME, "workspace", "sec_filings");
        INPUTS_ALL = SEC_FILINGS.resolve("dcf_inputs_all.json");
        String override = System.getenv("SEC_MUSE_AUDIT_RECORDS");
        RECORDS = override == null || override.isEmpty()
                ? DCF_CSV.resolve("sec_muse_audit").resolve("records") : Paths.get(override);
    }

    static String padCik(String cik) {
        if (cik == null) return "";
        String d = cik.replaceAll("\\D", "");
        while (d.length() < 10) d = "0" + d;
        return d;
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser (object/array/string/number/true/false/null).
    // ------------------------------------------------------------------
    static final class J {
        abstract static class V { }
        static final class O extends V { final LinkedHashMap<String, V> m = new LinkedHashMap<>(); }
        static final class A extends V { final ArrayList<V> l = new ArrayList<>(); }
        static final class S extends V { final String s; S(String s) { this.s = s; } }
        static final class N extends V { final double d; N(double d) { this.d = d; } }
        static final class B extends V { final boolean b; B(boolean b) { this.b = b; } }
        static final class Nil extends V { }

        static V parse(String t) { return new P(t).value(); }
        static String str(V v) { return v instanceof S ? ((S) v).s : null; }
        static Double num(V v) { return v instanceof N ? ((N) v).d : null; }
        static O obj(V v) { return v instanceof O ? (O) v : null; }
        static A arr(V v) { return v instanceof A ? (A) v : null; }

        static final class P {
            final String t; int i;
            P(String t) { this.t = t; }
            void ws() { while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++; }
            V value() {
                ws();
                char c = t.charAt(i);
                if (c == '{') { i++; O o = new O(); ws(); if (t.charAt(i) == '}') { i++; return o; }
                    while (true) { ws(); String k = string(); ws(); i++; V v = value(); o.m.put(k, v); ws();
                        char d = t.charAt(i); i++; if (d == '}') break; } return o; }
                if (c == '[') { i++; A a = new A(); ws(); if (t.charAt(i) == ']') { i++; return a; }
                    while (true) { a.l.add(value()); ws(); char d = t.charAt(i); i++; if (d == ']') break; } return a; }
                if (c == '"') return new S(string());
                if (c == 't') { i += 4; return new B(true); }
                if (c == 'f') { i += 5; return new B(false); }
                if (c == 'n') { i += 4; return new Nil(); }
                int j = i;
                while (j < t.length() && "-+0123456789.eE".indexOf(t.charAt(j)) >= 0) j++;
                double d = Double.parseDouble(t.substring(i, j)); i = j;
                return new N(d);
            }
            String string() {
                i++; StringBuilder sb = new StringBuilder();
                while (true) { char c = t.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') { char e = t.charAt(i++);
                        sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e); }
                    else sb.append(c); }
                return sb.toString();
            }
        }
    }

    // ------------------------------------------------------------------
    // Field definitions: the six audited input rows.
    // ------------------------------------------------------------------
    static final class FieldDef {
        final String key, label, units;
        final String[] usGaap, ifrsGaap;
        final boolean absVal, xbrlNa, optional;
        FieldDef(String key, String label, String units,
                 String[] usGaap, String[] ifrsGaap,
                 boolean absVal, boolean xbrlNa, boolean optional) {
            this.key = key; this.label = label; this.units = units;
            this.usGaap = usGaap; this.ifrsGaap = ifrsGaap;
            this.absVal = absVal; this.xbrlNa = xbrlNa; this.optional = optional;
        }
    }

    static final List<FieldDef> FIELDS = List.of(
        new FieldDef("ocf", "Net cash from operating activities", "USD millions",
            new String[]{"NetCashProvidedByUsedInOperatingActivities"},
            new String[]{"CashFlowsFromUsedInOperatingActivities"}, false, false, false),
        new FieldDef("capex", "Capex (purchases of property and equipment)", "USD millions",
            new String[]{"PaymentsToAcquirePropertyPlantAndEquipment",
                         "PaymentsToAcquireProductiveAssets"},
            new String[]{"PurchaseOfPropertyPlantAndEquipment"}, true, false, false),
        new FieldDef("diluted_shares", "Diluted weighted-average shares", "millions of shares",
            new String[]{"WeightedAverageNumberOfDilutedSharesOutstanding"},
            new String[]{"WeightedAverageNumberOfDilutedSharesOutstanding"}, false, false, false),
        new FieldDef("cash", "Cash and cash equivalents", "USD millions",
            new String[]{"CashAndCashEquivalentsAtCarryingValue"},
            new String[]{"CashAndCashEquivalents"}, false, false, false),
        new FieldDef("total_debt", "Total debt (computed from components)", "USD millions",
            new String[]{}, new String[]{}, false, true, false),
        new FieldDef("revenue", "Revenue (corroboration only, not a DCF input)", "USD millions",
            new String[]{"Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax",
                         "SalesRevenueNet"}, new String[]{}, false, false, true));

    // ------------------------------------------------------------------
    // Tolerance: mirrors SecNlmAudit's tol(sec) = max(1%*|sec|, 1.0),
    // made symmetric for pairwise three-way comparison.
    // ------------------------------------------------------------------
    /** True when a and b are within tolerance of each other. */
    static boolean agree(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return false;
        return Math.abs(a - b) <= Math.max(0.01 * Math.abs(a), Math.max(0.01 * Math.abs(b), 1.0));
    }

    static boolean present(double v) { return !Double.isNaN(v); }

    static double parseMaybeNumber(J.V v) {
        Double n = J.num(v);
        return n == null ? Double.NaN : n;
    }

    static String fmt(double v) {
        if (Double.isNaN(v)) return "n/a";
        return String.format("%,.1f", v);
    }

    // ------------------------------------------------------------------
    // XBRL leg. One selected value per field/period, plus conflict detection:
    // two DIFFERENT filings (accessions) giving DIFFERENT values for the same
    // period_end is a CONFLICT - surfaced, never silently picked.
    // ------------------------------------------------------------------
    static final class XbrlContext {
        String concept, taxonomy, end, frame, form, filed, accn;
        double valMillions;
        String source; // "companyfacts" or "record-cited"
    }

    static final class XbrlLeg {
        String status;      // "match", "conflict", "absent", "na"
        XbrlContext pick;   // the single selected context (match)
        List<XbrlContext> conflicts = new ArrayList<>(); // all contexts (conflict)
        String note;
    }

    /** Picks the filing-tagged XBRL fact for this field/period, mirroring
     *  SecNlmAudit's selection: same form family, fiscal frame matching the
     *  filing, longest-duration fact from the period-end. */
    static XbrlLeg xbrlLeg(FieldDef f, String periodKey, String form,
                            String filingDate, Path companyfacts) {
        XbrlLeg leg = new XbrlLeg();
        if (f.xbrlNa || f.usGaap.length == 0) { leg.status = "na"; leg.note = "computed field, no XBRL tag"; return leg; }
        if (companyfacts == null || !Files.isRegularFile(companyfacts)) {
            leg.status = "absent"; leg.note = "no companyfacts file"; return leg;
        }
        List<XbrlFacts.Fact> all;
        try {
            all = XbrlFacts.load(companyfacts);
        } catch (IOException e) {
            leg.status = "absent"; leg.note = "companyfacts unreadable: " + e.getMessage(); return leg;
        }
        List<XbrlFacts.Fact> cands = new ArrayList<>();
        for (String tax : new String[]{"us-gaap", "ifrs-full"})
            for (String c : ("us-gaap".equals(tax) ? f.usGaap : f.ifrsGaap)) {
                List<XbrlFacts.Fact> got = XbrlFacts.forConcepts(all, tax, c);
                cands.addAll(got);
            }
        if (cands.isEmpty()) { leg.status = "absent"; leg.note = "concept not tagged"; return leg; }

        String fy = filingDate != null && filingDate.length() >= 4 ? filingDate.substring(0, 4) : "";
        boolean annual = "annual".equals(periodKey);
        List<String> wantForms = annual ? List.of("10-K", "20-F") : List.of("10-Q", "6-K");

        // Group by (end, accn): the latest filing's context for each period-end.
        Map<String, List<XbrlFacts.Fact>> byEnd = new TreeMap<>();
        for (XbrlFacts.Fact o : cands) {
            if (!wantForms.contains(o.form())) continue;
            if (o.end() == null) continue;
            long dur = daysBetween(o.start(), o.end());
            if (annual && dur < 350) continue;
            if (!annual && (dur < 75 || dur > 200)) continue;
            byEnd.computeIfAbsent(o.end(), k -> new ArrayList<>()).add(o);
        }
        if (byEnd.isEmpty()) { leg.status = "absent"; leg.note = "no matching fiscal frame"; return leg; }

        // Choose the period-end: annual -> latest end; interim -> latest end in the filing year.
        String pickEnd = null;
        for (String end : byEnd.keySet()) {
            if (!annual && !end.startsWith(fy)) continue;
            pickEnd = end; // TreeMap: iterates ascending, last wins = latest
        }
        if (pickEnd == null) { leg.status = "absent"; leg.note = "no period-end in filing year"; return leg; }

        // Latest-filed accession for that end = the as-filed context; any OTHER
        // accession with a beyond-tolerance different value = conflict.
        List<XbrlFacts.Fact> same = byEnd.get(pickEnd);
        same.sort((a, b) -> String.valueOf(b.filed()).compareTo(String.valueOf(a.filed())));
        XbrlFacts.Fact winner = same.get(0);
        XbrlContext pick = ctxOf(winner, f, "companyfacts");
        Map<String, XbrlContext> byAccn = new LinkedHashMap<>();
        byAccn.put(winner.accn(), pick);
        for (XbrlFacts.Fact o : same) {
            if (o.accn().equals(winner.accn())) continue;
            XbrlContext c = ctxOf(o, f, "companyfacts");
            if (!agree(c.valMillions, pick.valMillions) && !byAccn.containsKey(o.accn()))
                byAccn.put(o.accn(), c);
        }
        if (byAccn.size() > 1) {
            leg.status = "conflict";
            leg.conflicts.addAll(byAccn.values());
            leg.note = byAccn.size() + " accessions give different values for period-end " + pickEnd;
            return leg;
        }
        leg.status = "match"; leg.pick = pick;
        return leg;
    }

    static XbrlContext ctxOf(XbrlFacts.Fact o, FieldDef f, String source) {
        XbrlContext c = new XbrlContext();
        c.concept = o.concept(); c.taxonomy = o.taxonomy();
        c.end = o.end(); c.frame = o.frame(); c.form = o.form();
        c.filed = o.filed(); c.accn = o.accn(); c.source = source;
        double v = o.val() / 1e6; // XBRL val is raw USD / shares; compare in millions
        if (f.absVal) v = Math.abs(v);
        c.valMillions = v;
        return c;
    }

    static long daysBetween(String a, String b) {
        if (a == null || b == null || a.length() < 10 || b.length() < 10) return -1;
        try {
            return Math.abs(java.time.LocalDate.parse(b.substring(0, 10)).toEpochDay()
                    - java.time.LocalDate.parse(a.substring(0, 10)).toEpochDay());
        } catch (Exception e) { return -1; }
    }

    static String ctxLine(XbrlContext c) {
        return c.valMillions + " " + c.taxonomy + ":" + c.concept
                + " end=" + c.end + " frame=" + c.frame + " " + c.form
                + " filed=" + c.filed + " accn=" + c.accn;
    }

    /**
     * Report-only proof helper (mirror of SecNlmAudit 5b): strips string
     * literals and comments, so the scan only sees actual program text -
     * the proof's own mention of the banned identifiers can't trip it.
     */
    static String stripCode(String code) {
        java.util.regex.Pattern tok = java.util.regex.Pattern.compile(
                "\"\"\"(?s).*?\"\"\"|(?s)/\\*.*?\\*/|//[^\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
        java.util.regex.Matcher tm = tok.matcher(code);
        StringBuffer sb = new StringBuffer();
        while (tm.find()) {
            String tg = tm.group();
            tm.appendReplacement(sb, (tg.startsWith("\"") || tg.startsWith("'")) ? "\"\"" : " ");
        }
        tm.appendTail(sb);
        return sb.toString();
    }

    /** True when the stripped source contains no write-path identifier. */
    static List<String> writePathViolations(String stripped) {
        List<String> hits = new ArrayList<>();
        // Production code is everything before selfTest(): it must contain
        // zero file-write calls (the self-test's own temp-file scaffolding
        // lives after that marker and is not production behavior).
        int cut = stripped.indexOf("static void selfTest()");
        String prod = cut < 0 ? stripped : stripped.substring(0, cut);
        if (prod.contains("Files.write")) hits.add("Files.write* (production code)");
        for (String b : new String[]{"Proposals", "RebuildAll", "approve(",
                "rebless(", "bless"}) {
            if (stripped.contains(b)) hits.add(b);
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // Transcription record. Parsed straight from the JSON file; the prompt
    // template (sec_muse_audit/prompt_templates.md) makes the NotebookLM
    // answer shape map 1:1 onto this.
    // ------------------------------------------------------------------
    static final class NlmField {
        double value = Double.NaN;
        String units, citation, statementLine;
        List<XbrlContext> citedContexts = new ArrayList<>(); // optional, transcriber-captured
    }

    static final class Record {
        String schema, ticker, company, recordedAt, notebook, recordedBy, promptTemplate;
        List<Map<String, String>> notebookSources = new ArrayList<>();
        // fieldKey -> periodKey("annual"/"quarterly") -> cited value
        Map<String, Map<String, NlmField>> fields = new LinkedHashMap<>();
        String path;
    }

    static Record parseRecord(Path p) throws IOException {
        J.O root = J.obj(J.parse(Files.readString(p, StandardCharsets.UTF_8)));
        if (root == null) throw new IOException("record root is not an object: " + p);
        Record r = new Record();
        r.path = p.toString();
        r.schema = J.str(root.m.get("schema"));
        r.ticker = J.str(root.m.get("ticker"));
        r.company = J.str(root.m.get("company"));
        r.recordedAt = J.str(root.m.get("recorded_at"));
        r.notebook = J.str(root.m.get("notebook"));
        r.recordedBy = J.str(root.m.get("recorded_by"));
        r.promptTemplate = J.str(root.m.get("prompt_template"));
        J.A srcs = J.arr(root.m.get("notebook_sources"));
        if (srcs != null) for (J.V v : srcs.l) {
            J.O o = J.obj(v); if (o == null) continue;
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<String, J.V> e : o.m.entrySet()) m.put(e.getKey(), J.str(e.getValue()));
            r.notebookSources.add(m);
        }
        J.O fields = J.obj(root.m.get("fields"));
        if (fields != null) for (Map.Entry<String, J.V> fe : fields.m.entrySet()) {
            J.O periods = J.obj(fe.getValue()); if (periods == null) continue;
            Map<String, NlmField> pm = new LinkedHashMap<>();
            for (Map.Entry<String, J.V> pe : periods.m.entrySet()) {
                J.O fo = J.obj(pe.getValue()); if (fo == null) continue;
                NlmField nf = new NlmField();
                nf.value = parseMaybeNumber(fo.m.get("value"));
                nf.units = J.str(fo.m.get("units"));
                nf.citation = J.str(fo.m.get("citation"));
                nf.statementLine = J.str(fo.m.get("statement_line"));
                J.A ctxs = J.arr(fo.m.get("xbrl_contexts"));
                if (ctxs != null) for (J.V cv : ctxs.l) {
                    J.O co = J.obj(cv); if (co == null) continue;
                    XbrlContext c = new XbrlContext();
                    c.concept = J.str(co.m.get("concept")); c.taxonomy = J.str(co.m.get("taxonomy"));
                    c.end = J.str(co.m.get("period_end")); c.frame = J.str(co.m.get("frame"));
                    c.form = J.str(co.m.get("form")); c.filed = J.str(co.m.get("filed"));
                    c.accn = J.str(co.m.get("accn")); c.source = "record-cited";
                    c.valMillions = parseMaybeNumber(co.m.get("val"));
                    nf.citedContexts.add(c);
                }
                pm.put(pe.getKey(), nf);
            }
            r.fields.put(fe.getKey(), pm);
        }
        return r;
    }

    // ------------------------------------------------------------------
    // My inputs (dcf_inputs_all.json). Values may be the string "not found"
    // -> NaN (missing leg).
    // ------------------------------------------------------------------
    static final class MyInputs {
        String dir, cik;
        Map<String, Map<String, Double>> vals = new LinkedHashMap<>(); // field -> period -> val
        Map<String, Map<String, String>> meta = new LinkedHashMap<>(); // period -> form/filing_date/period/units
    }

    static MyInputs loadMyInputs(String ticker) throws IOException {
        J.O inputs = J.obj(J.parse(Files.readString(INPUTS_ALL, StandardCharsets.UTF_8)));
        J.O companies = J.obj(inputs.m.get("companies"));
        J.O co = companies == null ? null : J.obj(companies.m.get(ticker.toUpperCase()));
        if (co == null) throw new IllegalArgumentException(
                "ticker " + ticker + " not in dcf_inputs_all.json - fail closed, nothing audited");
        MyInputs mi = new MyInputs();
        mi.dir = J.str(co.m.get("dir"));
        mi.cik = J.str(co.m.get("cik"));
        for (String pkey : new String[]{"annual", "quarterly"}) {
            J.O blk = J.obj(co.m.get(pkey));
            if (blk == null) continue;
            Map<String, String> mm = new LinkedHashMap<>();
            mm.put("form", J.str(blk.m.get("form")));
            mm.put("filing_date", J.str(blk.m.get("filing_date")));
            mm.put("period", J.str(blk.m.get("period")));
            mm.put("units", J.str(blk.m.get("units")));
            mm.put("files", filesOf(blk));
            mi.meta.put(pkey, mm);
            for (FieldDef f : FIELDS) {
                double v = parseMaybeNumber(blk.m.get(f.key));
                mi.vals.computeIfAbsent(f.key, k -> new LinkedHashMap<>()).put(pkey, v);
            }
        }
        return mi;
    }

    static String filesOf(J.O blk) {
        J.A filesA = J.arr(blk.m.get("files"));
        StringBuilder fb = new StringBuilder();
        if (filesA != null) for (J.V fv : filesA.l) {
            if (fb.length() > 0) fb.append(", ");
            fb.append(J.str(fv));
        }
        return fb.toString();
    }

    static Path companyfactsPath(MyInputs mi) {
        if (mi.dir == null) return null;
        Path p = SEC_FILINGS.resolve(mi.dir).resolve("companyfacts_CIK" + padCik(mi.cik) + ".json");
        if (Files.isRegularFile(p)) return p;
        try (var s = Files.list(SEC_FILINGS.resolve(mi.dir))) {
            return s.filter(x -> x.getFileName().toString().startsWith("companyfacts_")).findFirst().orElse(null);
        } catch (IOException e) { return null; }
    }

    // ------------------------------------------------------------------
    // The verdict engine. Returns findings; empty list = clean pass.
    // ------------------------------------------------------------------
    static List<Finding> auditRecord(Record r, MyInputs mi, Path companyfacts, List<String> log) {
        List<Finding> out = new ArrayList<>();
        for (FieldDef f : FIELDS) {
            Map<String, NlmField> periods = r.fields.get(f.key);
            if (periods == null && !f.optional) {
                out.add(Finding.info("B1", r.ticker, f.key,
                        "field present in record", "absent",
                        "transcription record " + r.path,
                        "re-ask NotebookLM with the prompt template covering this field, or mark not-found"));
                continue;
            }
            if (periods == null) continue; // optional field not cited: nothing to audit
            for (Map.Entry<String, NlmField> pe : periods.entrySet()) {
                String pkey = pe.getKey();
                NlmField nf = pe.getValue();
                double mine = mi.vals.getOrDefault(f.key, Map.of()).getOrDefault(pkey, Double.NaN);
                double nlm = nf.value;
                boolean mineP = present(mine), nlmP = present(nlm);

                // XBRL leg: transcriber-cited contexts win (the QXO conflict
                // illustration path); otherwise pull companyfacts.
                XbrlLeg xleg;
                if (!nf.citedContexts.isEmpty()) {
                    xleg = new XbrlLeg();
                    Map<String, Double> seen = new LinkedHashMap<>();
                    for (XbrlContext c : nf.citedContexts) {
                        boolean dup = false;
                        for (double v : seen.values()) if (agree(v, c.valMillions)) { dup = true; break; }
                        if (!dup) { seen.put(c.accn + "|" + c.end, c.valMillions); xleg.conflicts.add(c); }
                    }
                    if (xleg.conflicts.size() > 1) {
                        xleg.status = "conflict";
                        xleg.note = xleg.conflicts.size() + " record-cited contexts disagree";
                    } else {
                        xleg.status = "match"; xleg.pick = xleg.conflicts.get(0); xleg.conflicts.clear();
                    }
                } else {
                    String form = mi.meta.getOrDefault(pkey, Map.of()).get("form");
                    String fdate = mi.meta.getOrDefault(pkey, Map.of()).get("filing_date");
                    xleg = xbrlLeg(f, pkey, form, fdate, companyfacts);
                }
                double xbrl = "match".equals(xleg.status) ? xleg.pick.valMillions : Double.NaN;
                boolean xbrlP = present(xbrl);

                String evBase = "record " + r.path
                        + "; mine=sec_filings/dcf_inputs_all.json[" + r.ticker + "/" + pkey + "/" + f.key + "]"
                        + (nf.citation == null ? "" : "; nlm cited: " + nf.citation);
                String trio = "mine=" + fmt(mine) + " nlm=" + fmt(nlm) + " xbrl=" + fmt(xbrl)
                        + " (" + f.units + ")";

                log.add(r.ticker + " " + pkey + " " + f.key + ": " + trio
                        + " | xbrl=" + xleg.status + " -> " + verdictName(mineP, nlmP, xbrlP, xleg, f, mine, nlm, xbrl));

                // --- CONFLICT: two XBRL contexts disagree. Never pick one. ---
                if ("conflict".equals(xleg.status)) {
                    StringBuilder both = new StringBuilder();
                    for (XbrlContext c : xleg.conflicts) both.append("\n    - ").append(ctxLine(c));
                    out.add(Finding.warn("B1", r.ticker, f.key + "/" + pkey,
                            "single XBRL context", xleg.conflicts.size() + " conflicting contexts" + both,
                            evBase + "; " + xleg.note,
                            "CONFLICT: manual review - determine the authoritative filing (usually the later "
                            + "filing's comparative), then re-run; never silently pick a context"));
                    continue;
                }

                // --- Missing legs: record only, never guess. ---
                if (!mineP && !nlmP) continue; // nothing cited anywhere
                if (!mineP) {
                    out.add(Finding.info("B1", r.ticker, f.key + "/" + pkey,
                            "input present", "missing (not found/uncited in my inputs)",
                            evBase,
                            "NotebookLM cites " + fmt(nlm) + " - if material, add via VerifyBase-gated "
                            + "extraction, then proposal -> audit -> blessed -> RebuildAll"));
                    continue;
                }
                if (!nlmP) {
                    out.add(Finding.info("B1", r.ticker, f.key + "/" + pkey,
                            "NotebookLM citation", "not cited (NOT_FOUND or missing)",
                            evBase, "no arbiter figure for this field/period - two-way check only"));
                    continue;
                }

                boolean mN = agree(mine, nlm), mX = xbrlP && agree(mine, xbrl), nX = xbrlP && agree(nlm, xbrl);

                // --- All three agree: clean pass. ---
                if (mN && (mX || !xbrlP) && (nX || !xbrlP)) continue;

                // --- FAIL: NotebookLM + XBRL agree against me. My input is wrong. ---
                if (nX && !mX && !mN) {
                    out.add(Finding.fail("B1", r.ticker, f.key + "/" + pkey,
                            "mine == nlm == xbrl within tolerance", trio,
                            evBase + "; xbrl context: " + ctxLine(xleg.pick),
                            "my input disagrees with BOTH corroborating sources - file proposal via "
                            + "Proposals.java to correct dcf_inputs_all.json; attach this finding as evidence"));
                    continue;
                }

                // --- WARN: me + XBRL agree against NotebookLM. My input stands. ---
                if (mX && !nX && !mN) {
                    out.add(Finding.warn("B1", r.ticker, f.key + "/" + pkey,
                            "me == xbrl within tolerance", trio,
                            evBase + "; xbrl context: " + ctxLine(xleg.pick),
                            "my input stands; re-ask NotebookLM with the filing evidence "
                            + "(existing SecNlmAudit correction flow) so it self-corrects"));
                    continue;
                }

                // --- XBRL disagrees with both of us: possible restated comparative. ---
                if (xbrlP && mN && !mX && !nX) {
                    out.add(Finding.warn("B1", r.ticker, f.key + "/" + pkey,
                            "xbrl corroborates mine+nlm", trio,
                            evBase + "; xbrl context: " + ctxLine(xleg.pick),
                            "check the later filing's restated comparative - if the filing restated the "
                            + "figure, update inputs via proposal -> audit -> blessed -> RebuildAll"));
                    continue;
                }

                // --- All three differ (or me+nlm agree against xbrl-absent...): manual review. ---
                String rem = xbrlP
                        ? "manual review - resolve from the primary filing text, then route: wrong input -> "
                        + "proposal path; wrong NotebookLM figure -> re-ask with evidence"
                        : "two-way check (XBRL leg " + xleg.status + ": " + xleg.note + ") - mine vs NotebookLM "
                        + "disagree; resolve from the primary filing text, then proposal or re-ask as appropriate";
                out.add(Finding.warn("B1", r.ticker, f.key + "/" + pkey,
                        "three-way agreement", trio, evBase, rem));
            }
        }
        return out;
    }

    static String verdictName(boolean mineP, boolean nlmP, boolean xbrlP, XbrlLeg xleg,
                              FieldDef f, double mine, double nlm, double xbrl) {
        if ("conflict".equals(xleg.status)) return "CONFLICT";
        if (!mineP && !nlmP) return "skip (nothing cited)";
        if (!mineP) return "INFO (missing input)";
        if (!nlmP) return "INFO (uncited)";
        boolean mN = agree(mine, nlm), mX = xbrlP && agree(mine, xbrl), nX = xbrlP && agree(nlm, xbrl);
        if (mN && (mX || !xbrlP) && (nX || !xbrlP)) return "PASS";
        if (nX && !mX && !mN) return "FAIL (mine wrong)";
        if (mX && !nX && !mN) return "WARN (nlm wrong)";
        if (xbrlP && mN && !mX && !nX) return "WARN (xbrl restatement?)";
        return "WARN (manual review)";
    }

    // ------------------------------------------------------------------
    // CLI: --audit, --audit-all, --verify, --self-test.
    // ------------------------------------------------------------------
    static int runAudit(Path recordPath) throws IOException {
        initPaths();
        Record r = parseRecord(recordPath);
        if (r.ticker == null || r.ticker.isEmpty()) {
            System.out.println("record has no ticker - fail closed");
            return 1;
        }
        MyInputs mi = loadMyInputs(r.ticker);
        Path companyfacts = companyfactsPath(mi);
        List<String> log = new ArrayList<>();
        List<Finding> findings = auditRecord(r, mi, companyfacts, log);
        System.out.println("== SecMuseAudit: " + r.ticker + " (" + r.company + ") ==");
        System.out.println("record: " + r.path + " | recorded " + r.recordedAt + " in '" + r.notebook + "'");
        System.out.println("companyfacts: " + (companyfacts == null ? "none" : companyfacts));
        for (String l : log) System.out.println("  " + l);
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();
        Finding.split(findings, infos, failures);
        for (String s : infos) System.out.println("INFO: " + s);
        for (String s : failures) System.out.println("FAIL: " + s);
        System.out.println(findings.size() + " finding(s); " + failures.size() + " FAIL");
        return failures.isEmpty() ? 0 : 1;
    }

    static void runVerify() throws IOException {
        initPaths();
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();

        // 1. Records validate against the schema.
        List<Path> records = new ArrayList<>();
        if (Files.isDirectory(RECORDS)) {
            try (var s = Files.list(RECORDS)) {
                s.filter(p -> p.toString().endsWith(".json"))
                 .filter(p -> !p.getFileName().toString().startsWith("illustrative_"))
                 .forEach(records::add);
            }
        }
        if (records.isEmpty()) {
            infos.add("no production transcription records in " + RECORDS + " yet - re-audit will create them");
        } else {
            for (Path p : records) {
                try {
                    Record r = parseRecord(p);
                    if (r.ticker == null || !"sec_muse_audit_record/1".equals(r.schema))
                        failures.add("record " + p + ": bad schema/ticker");
                    else
                        infos.add("record OK: " + p.getFileName() + " (" + r.ticker + ")");
                } catch (Exception e) {
                    failures.add("record " + p + " unparsable: " + e.getMessage());
                }
            }
        }

        // 2. Report-only proof: stripped source must contain no write path.
        String src = stripCode(Files.readString(
                Paths.get(HOME, "workspace", "dcf_csv", "SecMuseAudit.java"),
                StandardCharsets.UTF_8));
        List<String> violations = writePathViolations(src);
        if (violations.isEmpty())
            infos.add("report-only proof: no write/approve/rebuild/bless path in program text");
        else
            failures.add("report-only violated, program text contains: " + violations);

        // 3. Tolerance mirror: agrees exactly where SecNlmAudit's tol() does.
        boolean mirror = agree(139514, 140000) == (Math.abs(140000 - 139514) <= Math.max(0.01 * 139514, 1.0))
                && !agree(139514, 150000)
                && agree(261.4, 261.4);
        if (mirror) infos.add("tolerance mirrors SecNlmAudit: 1%|sec| with $1M floor (USD millions)");
        else failures.add("tolerance does not mirror SecNlmAudit");

        // 4. Engine sanity on the illustrative record: conflicts must surface,
        //    and the run must emit zero FAILs (all non-conflict QXO legs pass).
        Path illus = DCF_CSV.resolve("sec_muse_audit").resolve("illustrative_qxo_record.json");
        if (Files.isRegularFile(illus)) {
            Record r = parseRecord(illus);
            MyInputs mi = loadMyInputs(r.ticker);
            List<String> log = new ArrayList<>();
            List<Finding> findings = auditRecord(r, mi, null, log);
            long conflicts = findings.stream().filter(f -> f.remediation.startsWith("CONFLICT")).count();
            if (conflicts == 2) infos.add("illustrative QXO run: 2 CONFLICT findings surfaced (ocf, capex)");
            else failures.add("illustrative QXO run: expected 2 CONFLICT findings, got " + conflicts);
            if (!Finding.anyFail(findings)) infos.add("illustrative QXO run: no FAIL findings");
            else failures.add("illustrative QXO run: unexpected FAIL finding");
        } else {
            infos.add("illustrative_qxo_record.json absent - skipping engine sanity leg");
        }

        Verify.report("VERIFY OK (SecMuseAudit): tool healthy, report-only holds.",
                "VERIFY FAILED (SecMuseAudit):", infos, failures);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }
        if (args.length == 1 && "--verify".equals(args[0])) { runVerify(); return; }
        if (args.length == 2 && "--audit".equals(args[0])) { System.exit(runAudit(Paths.get(args[1]))); return; }
        if (args.length == 1 && "--audit-all".equals(args[0])) {
            initPaths();
            int fails = 0;
            try (var s = Files.list(RECORDS)) {
                var recs = s.filter(p -> p.toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().startsWith("illustrative_"))
                        .sorted().toList();
                if (recs.isEmpty()) { System.out.println("no production records in " + RECORDS); return; }
                for (Path p : recs) { System.out.println(); fails += runAudit(p); }
            }
            System.exit(fails > 0 ? 1 : 0);
            return;
        }
        System.out.println("Usage: java SecMuseAudit.java --audit <record.json> | --audit-all | --verify | --self-test");
        System.exit(2);
    }

    // ------------------------------------------------------------------
    // Self-test: tolerance boundaries, verdict matrix, record parsing,
    // conflict detection, report-only proof.
    // ------------------------------------------------------------------
    static XbrlLeg legOf(String status, double val) {
        XbrlLeg l = new XbrlLeg();
        l.status = status;
        if ("match".equals(status)) {
            l.pick = new XbrlContext();
            l.pick.valMillions = val; l.pick.concept = "Test"; l.pick.taxonomy = "us-gaap";
            l.pick.end = "2025-12-31"; l.pick.frame = "CY2025"; l.pick.form = "10-K";
            l.pick.filed = "2026-02-27"; l.pick.accn = "0000"; l.pick.source = "test";
        }
        return l;
    }

    static void selfTest() throws IOException {
        initPaths();

        // 1. Tolerance boundaries (mirror of SecNlmAudit).
        SelfTest.check("tol pass exact", agree(139514, 139514));
        SelfTest.check("tol pass within 1%", agree(139514, 140000));
        SelfTest.check("tol fail beyond 1%", !agree(139514, 150000));
        SelfTest.check("tol $1M floor holds small values", agree(0.5, 1.2));
        SelfTest.check("tol floor breaks at 1.6", !agree(0.5, 2.1));
        SelfTest.check("tol NaN never agrees", !agree(139514, Double.NaN));
        SelfTest.check("tol negative flip fails", !agree(7695, -26992));

        // 2. Verdict matrix on synthetic legs (field = ocf).
        FieldDef ocf = FIELDS.get(0);
        SelfTest.checkEq("all agree -> PASS",
                "PASS", verdictName(true, true, true, legOf("match", 261.4), ocf, 261.4, 261.0, 261.4));
        SelfTest.checkEq("nlm+xbrl vs me -> FAIL",
                "FAIL (mine wrong)", verdictName(true, true, true, legOf("match", 84.8), ocf, 261.4, 84.8, 84.8));
        SelfTest.checkEq("me+xbrl vs nlm -> WARN nlm",
                "WARN (nlm wrong)", verdictName(true, true, true, legOf("match", 261.4), ocf, 261.4, 84.8, 261.4));
        SelfTest.checkEq("all three differ -> WARN manual",
                "WARN (manual review)", verdictName(true, true, true, legOf("match", 100.0), ocf, 261.4, 84.8, 100.0));
        SelfTest.checkEq("conflict -> CONFLICT",
                "CONFLICT", verdictName(true, true, false, legOf("conflict", 0), ocf, 261.4, 261.4, Double.NaN));
        SelfTest.checkEq("missing mine -> INFO",
                "INFO (missing input)", verdictName(false, true, false, legOf("absent", 0), ocf, Double.NaN, 261.4, Double.NaN));
        SelfTest.checkEq("uncited nlm -> INFO",
                "INFO (uncited)", verdictName(true, false, false, legOf("absent", 0), ocf, 261.4, Double.NaN, Double.NaN));
        SelfTest.checkEq("two-way agree without xbrl -> PASS",
                "PASS", verdictName(true, true, false, legOf("absent", 0), ocf, 261.4, 261.0, Double.NaN));

        // 3. End-to-end: FAIL finding carries the proposal remediation;
        //    all other fields two-way pass (mine == nlm, no XBRL leg pulled).
        Record r = new Record();
        r.ticker = "TEST"; r.path = "test"; r.schema = "sec_muse_audit_record/1";
        MyInputs mi = new MyInputs(); mi.dir = "none";
        mi.meta.put("annual", Map.of("form", "10-K", "filing_date", "2026-02-27"));
        String[][] allFields = {
            {"ocf", "261.4", "84.8"}, {"capex", "78.2", "78.2"},
            {"diluted_shares", "600.0", "600.0"}, {"cash", "2361.6", "2361.6"},
            {"total_debt", "3057.3", "3057.3"}};
        for (String[] kv : allFields) {
            NlmField x = new NlmField();
            x.value = Double.parseDouble(kv[2]); x.citation = "test";
            if ("ocf".equals(kv[0])) { // give ocf an XBRL leg that agrees with NotebookLM
                XbrlContext xc = new XbrlContext();
                xc.valMillions = 84.8; xc.accn = "B2"; xc.end = "2025-12-31";
                xc.concept = "NetCashProvidedByUsedInOperatingActivities";
                xc.taxonomy = "us-gaap"; xc.frame = "CY2025"; xc.form = "10-K";
                xc.filed = "2026-02-27"; xc.source = "test";
                x.citedContexts.add(xc);
            }
            r.fields.put(kv[0], Map.of("annual", x));
            mi.vals.computeIfAbsent(kv[0], kk -> new java.util.HashMap<>())
                    .put("annual", Double.parseDouble(kv[1]));
        }
        List<String> log = new ArrayList<>();
        List<Finding> fs = auditRecord(r, mi, null, log);
        SelfTest.checkEq("one finding total (ocf FAIL only)", 1, fs.size());
        SelfTest.checkEq("severity FAIL", Finding.Severity.FAIL, fs.get(0).severity);
        SelfTest.checkContains("fail remediation = proposal path", fs.get(0).remediation, "Proposals.java");
        SelfTest.checkEq("check id B1", "B1", fs.get(0).checkId);

        // me+xbrl agree, nlm wrong -> WARN with re-ask remediation.
        XbrlLeg mleg = legOf("match", 261.4);
        SelfTest.checkEq("re-ask remediation branch",
                "WARN (nlm wrong)", verdictName(true, true, true, mleg, ocf, 261.4, 84.8, 261.4));

        // 4. Conflict detection inside cited contexts: two contexts, one field.
        Record rc = new Record();
        rc.ticker = "TEST"; rc.path = "test"; rc.schema = "sec_muse_audit_record/1";
        NlmField nfc = new NlmField(); nfc.value = 261.4; nfc.citation = "10-K p.45";
        XbrlContext c1 = new XbrlContext(); c1.valMillions = 261.4; c1.accn = "A1"; c1.end = "2025-12-31";
        c1.concept = "X"; c1.taxonomy = "us-gaap"; c1.frame = "CY2025"; c1.form = "10-K"; c1.filed = "2026-02-27";
        XbrlContext c2 = new XbrlContext(); c2.valMillions = 84.8; c2.accn = "A1"; c2.end = "2025-12-31";
        c2.concept = "X"; c2.taxonomy = "us-gaap"; c2.frame = "CY2024"; c2.form = "10-K"; c2.filed = "2026-02-27";
        nfc.citedContexts.add(c1); nfc.citedContexts.add(c2);
        rc.fields.put("ocf", Map.of("annual", nfc));
        List<Finding> fsc = auditRecord(rc, mi, null, new ArrayList<>());
        long conflicts = fsc.stream().filter(f -> f.remediation.startsWith("CONFLICT")).count();
        SelfTest.checkEq("conflict yields exactly one CONFLICT finding", 1L, conflicts);
        Finding cf = fsc.stream().filter(f -> f.remediation.startsWith("CONFLICT")).findFirst().get();
        SelfTest.checkEq("conflict severity WARN", Finding.Severity.WARN, cf.severity);
        SelfTest.checkContains("conflict surfaces context A", cf.actual, "261.4");
        SelfTest.checkContains("conflict surfaces context B", cf.actual, "84.8");

        // 5. Record parsing: prompt-shaped JSON maps 1:1.
        Path tmp = Files.createTempFile("sec_muse_audit_test", ".json");
        Files.writeString(tmp, """
                {"schema":"sec_muse_audit_record/1","ticker":"QXO","company":"QXO, Inc.",
                 "recorded_at":"2026-09-29T03:10:00-04:00","notebook":"Stock Research SEC Filings",
                 "recorded_by":"test","prompt_template":"sec_muse_audit_prompt/1",
                 "notebook_sources":[{"source":"10-K FY2025","filed":"2026-02-27"}],
                 "fields":{"ocf":{"annual":{"value":261.4,"units":"USD millions",
                 "citation":"10-K, Consolidated Statements of Cash Flows, p. 45",
                 "statement_line":"Net cash provided by operating activities"}}}}""",
                StandardCharsets.UTF_8);
        Record parsed = parseRecord(tmp);
        SelfTest.checkEq("record ticker", "QXO", parsed.ticker);
        SelfTest.checkEq("record field value", 261.4, parsed.fields.get("ocf").get("annual").value);
        SelfTest.checkEq("record citation", "10-K, Consolidated Statements of Cash Flows, p. 45",
                parsed.fields.get("ocf").get("annual").citation);
        Files.deleteIfExists(tmp);

        // 6. Report-only proof: no write path in stripped program text
        //    (mirror of SecNlmAudit 5b; literals and comments don't count).
        String src = stripCode(Files.readString(
                Paths.get(HOME, "workspace", "dcf_csv", "SecMuseAudit.java"),
                StandardCharsets.UTF_8));
        SelfTest.checkEq("no write-path identifier in program text",
                List.of(), writePathViolations(src));

        SelfTest.finish();
    }
}
