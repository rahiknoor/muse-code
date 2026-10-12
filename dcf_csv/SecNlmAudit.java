import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SEC filing &lt;-&gt; NotebookLM figure reconciliation (the extraction-correctness layer).
 *
 * <p>Sits between formula correctness ({@code DcfModel.java}) and assumption
 * reasonableness (independent review + backtesting). For each ticker it:
 * <ol>
 *   <li>reads the figures NotebookLM was given / cited in the Atlas research note
 *       ({@code 05 NotebookLM/<T> - NotebookLM Research.md}: the "DCF inputs
 *       extracted" table and the NotebookLM DCF value bullet),</li>
 *   <li>reads SEC evidence directly: {@code sec_filings/dcf_inputs_all.json}
 *       (Muse's independent extraction, with form / filing date / file names /
 *       period / units) corroborated against the XBRL companyfacts snapshot
 *       ({@code sec_filings/<dir>/companyfacts_*.json}: form, filed date,
 *       fiscal frame, accession number),</li>
 *   <li>compares field-by-field with tolerance-based matching and writes a
 *       per-field audit record (filing, form, filing date, fiscal period,
 *       statement, line item, units, SEC value, NotebookLM value,
 *       difference/tolerance, NotebookLM citation, verdict, reviewer/date)
 *       plus a per-ticker accuracy history,</li>
 *   <li>on field FAILs writes a re-ask evidence pack
 *       ({@code sec_nlm_audit/reask_packs/<T>.json}) carrying the exact filing
 *       figures + provenance, so the re-ask job can put the evidence in front
 *       of NotebookLM and let it self-correct (Rahik's rule).</li>
 * </ol>
 *
 * <p><b>Proposal-only (hard rule).</b> This job FILES NOTHING to any value
 * column: it never invokes the proposal approve/reject path, never writes a
 * blessed value, never re-blesses. Corrections to the {@code notebooklm} column
 * are filed as proposals by the re-ask job only after a corrected value comes
 * back and passes the cross-check; the auditor (Muse) approves, blessed JSON
 * updates, then RebuildAll propagates. The self-test proves the write path is
 * absent (reflection + source scan), not merely unused.
 *
 * <p>Usage:
 * <pre>
 *   java SecNlmAudit.java --audit AMZN        # reconcile one ticker
 *   java SecNlmAudit.java --audit-all         # every ticker with an Atlas note
 *   java SecNlmAudit.java --self-test         # unit + proposal-only proofs
 * </pre>
 */
public class SecNlmAudit {

    // ------------------------------------------------------------------
    // Paths. HOME comes from the environment, never the user.home property.
    // SEC_NLM_AUDIT_ROOT overrides the output dir (used by the self-test).
    // ------------------------------------------------------------------
    static String HOME;
    static Path DCF_CSV, SEC_FILINGS, INPUTS_ALL, ATLAS_NLM, OUT, PACKS;

    static void initPaths() {
        HOME = System.getenv("HOME");
        if (HOME == null || HOME.isEmpty()) throw new IllegalStateException("$HOME is not set");
        DCF_CSV = Paths.get(HOME, "workspace", "dcf_csv");
        SEC_FILINGS = Paths.get(HOME, "workspace", "sec_filings");
        INPUTS_ALL = SEC_FILINGS.resolve("dcf_inputs_all.json");
        // The vault dir name carries an emoji; sun.jnu.encoding is ASCII on this
        // box, so Paths.get() cannot encode it. Resolve by directory scan instead:
        // entries from a DirectoryStream are built from raw bytes and stay usable.
        ATLAS_NLM = findDirByAffix(Paths.get(HOME, "workspace", "atlas"), "05 ", "NotebookLM");
        String override = System.getenv("SEC_NLM_AUDIT_ROOT");
        OUT = override == null || override.isEmpty()
                ? DCF_CSV.resolve("sec_nlm_audit") : Paths.get(override);
        PACKS = OUT.resolve("reask_packs");
    }

    /** Finds a direct child of {@code parent} whose name starts/ends with the affixes. */
    static Path findDirByAffix(Path parent, String prefix, String suffix) {
        try (var s = Files.newDirectoryStream(parent)) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (n.startsWith(prefix) && n.endsWith(suffix) && Files.isDirectory(p)) return p;
            }
        } catch (IOException e) { throw new IllegalStateException("cannot list " + parent, e); }
        throw new IllegalStateException("no dir " + prefix + "*" + suffix + " under " + parent);
    }

    /** Finds the Atlas research note for a ticker by its ASCII ticker prefix. */
    static Path findAtlasNote(String ticker) {
        String want = ticker.toUpperCase() + " ";
        try (var s = Files.newDirectoryStream(ATLAS_NLM, "*.md")) {
            for (Path p : s) {
                if (p.getFileName().toString().startsWith(want)) return p;
            }
        } catch (IOException e) { throw new IllegalStateException("cannot list " + ATLAS_NLM, e); }
        return null;
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
                if (i >= t.length()) throw new IllegalArgumentException("eof");
                char c = t.charAt(i);
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return str();
                if (c == 't' || c == 'f') return bool();
                if (c == 'n') { i += 4; return new Nil(); }
                return num();
            }
            O obj() {
                O o = new O(); i++; // {
                ws();
                if (i < t.length() && t.charAt(i) == '}') { i++; return o; }
                while (true) {
                    ws();
                    String k = str().s;
                    ws();
                    if (t.charAt(i) != ':') throw new IllegalArgumentException("want :");
                    i++;
                    o.m.put(k, value());
                    ws();
                    char c = t.charAt(i++);
                    if (c == '}') return o;
                    if (c != ',') throw new IllegalArgumentException("want ,");
                }
            }
            A arr() {
                A a = new A(); i++; // [
                ws();
                if (i < t.length() && t.charAt(i) == ']') { i++; return a; }
                while (true) {
                    a.l.add(value());
                    ws();
                    char c = t.charAt(i++);
                    if (c == ']') return a;
                    if (c != ',') throw new IllegalArgumentException("want ,");
                }
            }
            S str() {
                StringBuilder sb = new StringBuilder(); i++; // "
                while (true) {
                    char c = t.charAt(i++);
                    if (c == '"') return new S(sb.toString());
                    if (c == '\\') {
                        char e = t.charAt(i++);
                        switch (e) {
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            case 'u':
                                sb.append((char) Integer.parseInt(t.substring(i, i + 4), 16));
                                i += 4; break;
                            default: sb.append(e);
                        }
                    } else sb.append(c);
                }
            }
            B bool() {
                if (t.startsWith("true", i)) { i += 4; return new B(true); }
                i += 5; return new B(false);
            }
            N num() {
                int s = i;
                while (i < t.length() && "-+0123456789.eE".indexOf(t.charAt(i)) >= 0) i++;
                return new N(Double.parseDouble(t.substring(s, i)));
            }
        }

        static String dumps(V v) {
            if (v instanceof Nil) return "null";
            if (v instanceof B) return ((B) v).b ? "true" : "false";
            if (v instanceof N) {
                double d = ((N) v).d;
                return d == Math.rint(d) && !Double.isInfinite(d)
                        ? Long.toString((long) d) : Double.toString(d);
            }
            if (v instanceof S) return quote(((S) v).s);
            StringBuilder sb = new StringBuilder();
            if (v instanceof A) {
                sb.append('[');
                boolean f = true;
                for (V e : ((A) v).l) { if (!f) sb.append(','); f = false; sb.append(dumps(e)); }
                return sb.append(']').toString();
            }
            O o = (O) v;
            sb.append('{');
            boolean f = true;
            for (Map.Entry<String, V> e : o.m.entrySet()) {
                if (!f) sb.append(',');
                f = false;
                sb.append(quote(e.getKey())).append(':').append(dumps(e.getValue()));
            }
            return sb.append('}').toString();
        }

        static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\r': sb.append("\\r"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.append('"').toString();
        }
    }

    // ------------------------------------------------------------------
    // Money parsing: "$139,514" / "10,827M" / "-$26,992" (U+2212 minus) ->
    // USD millions. Returns NaN when the cell holds no number ("-").
    // ------------------------------------------------------------------
    static double parseMillions(String s) {
        if (s == null) return Double.NaN;
        String t = s.trim().replace("\u2212", "-").replace("\u2013", "-").replace("\u2014", "-");
        if (t.isEmpty() || t.equals("-") || t.equals("\u2014") || t.equals("--")) return Double.NaN;
        boolean parenNeg = t.startsWith("(") && t.endsWith(")");
        t = t.replaceAll("[\\$,]", "").replaceAll("[Mm]\\b", "").replaceAll("[()]", "").trim();
        // trailing words like "(YTD)" may remain; keep the leading number
        Matcher m = Pattern.compile("^-?\\d[\\d,]*\\.?\\d*").matcher(t.replace(",", ""));
        if (!m.find()) return Double.NaN;
        double v = Double.parseDouble(m.group());
        return parenNeg ? -v : v;
    }

    /** Tolerance: 1% of |sec| with a $1M floor (USD millions). */
    static double tol(double sec) {
        return Math.max(0.01 * Math.abs(sec), 1.0);
    }

    static String verdict(double sec, double nlm) {
        if (Double.isNaN(sec) || Double.isNaN(nlm)) return "uncertain";
        return Math.abs(nlm - sec) <= tol(sec) ? "pass" : "fail";
    }

    // ------------------------------------------------------------------
    // Field definitions: the six input rows in the research notes.
    // ------------------------------------------------------------------
    static final class FieldDef {
        final String key, label, statement, lineItem;
        final String usTag, ifrsTag, unitsKey;
        final boolean absVal, xbrlNa;
        FieldDef(String key, String label, String statement, String lineItem,
                 String usTag, String ifrsTag, String unitsKey, boolean absVal, boolean xbrlNa) {
            this.key = key; this.label = label; this.statement = statement; this.lineItem = lineItem;
            this.usTag = usTag; this.ifrsTag = ifrsTag; this.unitsKey = unitsKey;
            this.absVal = absVal; this.xbrlNa = xbrlNa;
        }
    }

    static final List<FieldDef> FIELDS = List.of(
        new FieldDef("ocf", "Net cash from operating activities", "Cash flows",
            "Net cash provided by (used in) operating activities",
            "NetCashProvidedByUsedInOperatingActivities", "CashFlowsFromUsedInOperatingActivities",
            "USD", false, false),
        new FieldDef("capex", "Capex (purchases of property and equipment)", "Cash flows",
            "Payments to acquire property, plant and equipment",
            "PaymentsToAcquirePropertyPlantAndEquipment", "PurchaseOfPropertyPlantAndEquipment",
            "USD", true, false),
        new FieldDef("fcf", "Implied FCF (OCF - capex)", "Cash flows (computed)",
            "Implied free cash flow = OCF - capex", null, null, "USD", false, true),
        new FieldDef("shares", "Diluted weighted-average shares", "Cover / per-share data",
            "Weighted-average diluted shares outstanding",
            "WeightedAverageNumberOfDilutedSharesOutstanding",
            "WeightedAverageNumberOfDilutedSharesOutstanding", "shares", false, false),
        new FieldDef("cash", "Cash and cash equivalents", "Balance sheet",
            "Cash and cash equivalents",
            "CashAndCashEquivalentsAtCarryingValue", "CashAndCashEquivalents",
            "USD", false, false),
        new FieldDef("debt", "Total debt", "Balance sheet (computed)",
            "Total debt = long-term debt (incl. current portion) + short-term borrowings",
            null, null, "USD", false, true));

    static String fieldKeyForRow(String label) {
        String l = label.toLowerCase();
        if (l.contains("implied fcf") || (l.contains("fcf") && !l.contains("trend"))) return "fcf";
        if (l.contains("operating activities")) return "ocf";
        if (l.contains("capex")) return "capex";
        if (l.contains("diluted") && l.contains("shares")) return "shares";
        if (l.contains("cash and cash equivalents")) return "cash";
        if (l.contains("total debt")) return "debt";
        return null;
    }

    // ------------------------------------------------------------------
    // Atlas note parsing.
    // ------------------------------------------------------------------
    static final class NoteData {
        String annualFy, annualFiled;   // e.g. "2025", "2026-02-06"
        String qtrFy, qtrQ, qtrFiled;  // e.g. "2026", "Q2", "2026-07-31"
        List<String[]> header = new ArrayList<>();
        // rows: fieldKey -> [annualCell, quarterlyCell]
        Map<String, String[]> rows = new LinkedHashMap<>();
        Double nlmValue;               // NotebookLM's own per-share DCF value
        String nlmCitation;
    }

    static NoteData parseNote(Path p) throws IOException {
        NoteData nd = new NoteData();
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        String text = String.join("\n", lines);

        // Source filings line: "Source filings: 10-K FY2025 (filed 2026-02-06)
        // and 10-Q Q2 2026 (filed 2026-07-31)."
        Matcher fm = Pattern.compile(
                "10-K\\s+FY(\\d{4})\\s*\\(filed\\s+([\\d-]+)\\).*?10-Q\\s+(Q[1-4])\\s+(\\d{4})\\s*\\(filed\\s+([\\d-]+)\\)",
                Pattern.DOTALL).matcher(text);
        if (fm.find()) {
            nd.annualFy = fm.group(1); nd.annualFiled = fm.group(2);
            nd.qtrQ = fm.group(3); nd.qtrFy = fm.group(4); nd.qtrFiled = fm.group(5);
        }

        // DCF inputs table.
        boolean inTable = false;
        List<String[]> rawRows = new ArrayList<>();
        for (String ln : lines) {
            String t = ln.trim();
            if (t.startsWith("## DCF inputs extracted")) { inTable = true; continue; }
            if (inTable && t.startsWith("## ")) break;
            if (!inTable || !t.startsWith("|")) continue;
            String[] cells = t.split("\\|", -1);
            List<String> cs = new ArrayList<>();
            for (int i = 1; i < cells.length - 1; i++) cs.add(cells[i].trim());
            if (cs.stream().allMatch(c -> c.matches("[:\\- ]*"))) continue; // separator
            rawRows.add(cs.toArray(new String[0]));
        }
        if (!rawRows.isEmpty()) {
            String[] header = rawRows.get(0);
            int annualCol = -1, qtrCol = -1;
            for (int i = 1; i < header.length; i++) {
                if (header[i].contains("10-K")) annualCol = i;
                if (header[i].contains("10-Q")) qtrCol = i;
            }
            for (int r = 1; r < rawRows.size(); r++) {
                String[] row = rawRows.get(r);
                String key = fieldKeyForRow(row[0]);
                if (key == null) continue;
                String a = annualCol >= 0 && annualCol < row.length ? row[annualCol] : "";
                String q = qtrCol >= 0 && qtrCol < row.length ? row[qtrCol] : "";
                nd.rows.put(key, new String[]{a, q});
            }
        }

        // NotebookLM DCF value bullet: "- **$152.42** — ...".
        Matcher vm = Pattern.compile(
                "## NotebookLM DCF value\\s*\\n-\\s*\\*\\*\\$([\\d,]+\\.?\\d*)\\*\\*\\s*(?:—|-)\\s*(.+)",
                Pattern.DOTALL).matcher(text);
        if (vm.find()) {
            nd.nlmValue = Double.parseDouble(vm.group(1).replace(",", ""));
            nd.nlmCitation = vm.group(2).trim().split("\\n")[0].trim();
            if (nd.nlmCitation.length() > 220) nd.nlmCitation = nd.nlmCitation.substring(0, 220);
        }
        return nd;
    }

    // ------------------------------------------------------------------
    // XBRL companyfacts corroboration (second evidence layer).
    // ------------------------------------------------------------------
    static final class XbrlHit {
        String tag, form, filed, start, end, frame, accn;
        Double valMillions;
        String check; // pass | mismatch | absent | na
        String note;
    }

    static long daysBetween(String start, String end) {
        try {
            return java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.parse(start), LocalDate.parse(end));
        } catch (Exception e) { return -1; }
    }

    static XbrlHit xbrlCheck(Path companyfacts, FieldDef f,
                             String form, String filed, String fy, String fp, double secVal) {
        XbrlHit h = new XbrlHit();
        h.tag = f.usTag; h.form = form; h.filed = filed;
        if (f.xbrlNa || f.usTag == null) { h.check = "na"; h.note = "computed/composite field: no single XBRL tag"; return h; }
        // Tidy XBRL fact table (XbrlFacts, java-shared): one row per fact entry.
        // Selection logic below is unchanged — only the JSON navigation moved.
        List<XbrlFacts.Fact> facts;
        try {
            facts = XbrlFacts.load(companyfacts);
        } catch (Exception e) { h.check = "absent"; h.note = "companyfacts unreadable: " + e.getMessage(); return h; }
        String tag = f.usTag;
        List<XbrlFacts.Fact> entries = XbrlFacts.forConcept(facts, "us-gaap", tag);
        if (entries.isEmpty()) {
            tag = f.ifrsTag;
            entries = tag == null ? List.of() : XbrlFacts.forConcept(facts, "ifrs-full", tag);
        }
        if (tag == null || entries.isEmpty()) {
            h.check = "absent"; h.note = "tag absent in companyfacts snapshot"; return h;
        }
        h.tag = tag;
        List<XbrlFacts.Fact> unitEntries = new ArrayList<>();
        for (XbrlFacts.Fact e : entries) if (f.unitsKey.equals(e.unit())) unitEntries.add(e);
        if (unitEntries.isEmpty()) { h.check = "absent"; h.note = "units " + f.unitsKey + " absent"; return h; }

        List<XbrlFacts.Fact> cands = new ArrayList<>();
        for (XbrlFacts.Fact e : unitEntries) {
            if (form.equals(e.form()) && filed.equals(e.filed())) cands.add(e);
        }
        if (cands.isEmpty()) { h.check = "absent"; h.note = "no " + form + " filed " + filed + " entries"; return h; }

        XbrlFacts.Fact pick = null;
        if ("10-K".equals(form)) {
            for (XbrlFacts.Fact o : cands) if (("CY" + fy).equals(o.frame())) { pick = o; break; }
            if (pick == null) for (XbrlFacts.Fact o : cands) {
                String s = o.start();
                if (s != null && s.startsWith(fy) && daysBetween(s, o.end()) > 350) { pick = o; break; }
            }
        } else {
            // Interim: the current-year YTD duration entry (6M -> 170-190 days,
            // ending in the filing year; 10-Qs also carry the prior-year
            // comparative 6M period, which must NOT be picked).
            for (XbrlFacts.Fact o : cands) {
                String end = o.end();
                if (end == null || !end.startsWith(fy)) continue;
                long d = daysBetween(o.start(), end);
                if (d >= 170 && d <= 190) { pick = o; break; }
            }
            if (pick == null) { // fall back to the longest sub-annual current-year duration
                long best = -1;
                for (XbrlFacts.Fact o : cands) {
                    String end = o.end();
                    if (end == null || !end.startsWith(fy)) continue;
                    long d = daysBetween(o.start(), end);
                    if (d > best && d < 200) { best = d; pick = o; }
                }
            }
        }
        if (pick == null) { h.check = "absent"; h.note = "no matching fiscal frame"; return h; }
        h.start = pick.start(); h.end = pick.end();
        h.frame = pick.frame(); h.accn = pick.accn();
        // XbrlFacts only emits rows with a numeric val (the old "no val" path is subsumed).
        double v = pick.val() / 1e6;
        if (f.absVal) v = Math.abs(v);
        h.valMillions = v;
        if (Double.isNaN(secVal)) { h.check = "na"; h.note = "no SEC value to corroborate"; return h; }
        if (Math.abs(v - secVal) <= tol(secVal)) { h.check = "pass"; }
        else { h.check = "mismatch"; h.note = "companyfacts differs from dcf_inputs_all extraction"; }
        return h;
    }

    // ------------------------------------------------------------------
    // Audit runner.
    // ------------------------------------------------------------------
    static final class FieldResult {
        FieldDef def; String period;
        String form, filingDate, fiscalPeriod, files, units;
        Double secVal, nlmVal, diff, tolerance;
        XbrlHit xbrl;
        String nlmCitation, verdict;
    }

    static String todayEt() {
        return LocalDate.now(ZoneId.of("America/New_York")).toString();
    }

    static void audit(String ticker) throws IOException {
        String t = ticker.toUpperCase();
        Path note = findAtlasNote(t);
        if (note == null) throw new IllegalArgumentException("no Atlas note for " + t);
        NoteData nd = parseNote(note);

        J.O inputs = (J.O) J.parse(Files.readString(INPUTS_ALL, StandardCharsets.UTF_8));
        J.O companies = J.obj(inputs.m.get("companies"));
        J.O co = companies == null ? null : J.obj(companies.m.get(t));
        if (co == null) throw new IllegalArgumentException(
                "ticker " + t + " not in dcf_inputs_all.json - fail closed, nothing written");
        String dir = J.str(co.m.get("dir"));
        String cik = J.str(co.m.get("cik"));
        Path companyfacts = dir == null ? null
                : SEC_FILINGS.resolve(dir).resolve("companyfacts_CIK" + padCik(cik) + ".json");
        // Fall back: any companyfacts file in the company dir.
        if (companyfacts != null && !Files.exists(companyfacts) && dir != null) {
            try (var s = Files.list(SEC_FILINGS.resolve(dir))) {
                var it = s.filter(p -> p.getFileName().toString().startsWith("companyfacts_")).findFirst();
                companyfacts = it.orElse(null);
            }
        }

        List<FieldResult> results = new ArrayList<>();
        String[][] periods = {
            {"annual", "10-K", nd.annualFiled, nd.annualFy == null ? "" : "FY" + nd.annualFy},
            {"quarterly", "10-Q", nd.qtrFiled, nd.qtrQ == null ? "" : nd.qtrQ + " " + nd.qtrFy},
        };
        for (String[] p : periods) {
            String pkey = p[0];
            J.O blk = J.obj(co.m.get(pkey));
            if (blk == null) continue;
            String form = p[1];
            String filingDate = J.str(blk.m.get("filing_date"));
            String period = J.str(blk.m.get("period"));
            String units = J.str(blk.m.get("units"));
            J.A filesA = J.arr(blk.m.get("files"));
            StringBuilder fb = new StringBuilder();
            if (filesA != null) for (J.V fv : filesA.l) {
                if (fb.length() > 0) fb.append(", ");
                fb.append(J.str(fv));
            }
            for (FieldDef f : FIELDS) {
                FieldResult r = new FieldResult();
                r.def = f; r.period = pkey;
                r.form = form; r.filingDate = filingDate; r.fiscalPeriod = period;
                r.files = fb.toString(); r.units = units == null ? "USD millions" : units;
                Double blkVal = J.num(blk.m.get(f.key));
                if ("fcf".equals(f.key)) {
                    Double ocf = J.num(blk.m.get("ocf")), cx = J.num(blk.m.get("capex"));
                    blkVal = (ocf == null || cx == null) ? null : ocf - cx;
                }
                r.secVal = blkVal == null ? Double.NaN : blkVal;
                String[] cells = nd.rows.get(f.key);
                String cell = cells == null ? "" : ("annual".equals(pkey) ? cells[0] : cells[1]);
                r.nlmVal = parseMillions(cell);
                r.tolerance = Double.isNaN(r.secVal) ? Double.NaN : tol(r.secVal);
                r.diff = (Double.isNaN(r.secVal) || Double.isNaN(r.nlmVal)) ? Double.NaN : r.nlmVal - r.secVal;
                r.verdict = verdict(r.secVal, r.nlmVal);
                r.nlmCitation = "Atlas note '" + t + " — NotebookLM Research.md', '## DCF inputs extracted' table"
                        + ("annual".equals(pkey) ? " (10-K column)" : " (10-Q column)")
                        + "; input package brief research_notes/notebooklm_pending/" + t + ".md";
                String fy = "annual".equals(pkey) ? nd.annualFy : nd.qtrFy;
                String fp = "annual".equals(pkey) ? "FY" : nd.qtrQ;
                r.xbrl = (companyfacts == null)
                        ? absentHit(f, "no companyfacts file for " + t)
                        : xbrlCheck(companyfacts, f, form, filingDate == null ? "" : filingDate,
                                fy == null ? "" : fy, fp == null ? "" : fp,
                                r.secVal == null ? Double.NaN : r.secVal);
                if ("mismatch".equals(r.xbrl.check) && "pass".equals(r.verdict)) r.verdict = "uncertain";
                results.add(r);
            }
        }

        // NotebookLM's own per-share value: inputs-verified but not re-derivable
        // here; arithmetic check only if the note states equity + shares.
        String valueVerdict, valueReason;
        if (nd.nlmValue == null) {
            valueVerdict = "uncertain"; valueReason = "no NotebookLM DCF value stated in the note";
        } else {
            long fails = results.stream().filter(r -> "fail".equals(r.verdict)).count();
            if (fails > 0) {
                valueVerdict = "uncertain";
                valueReason = fails + " input field(s) failed vs SEC evidence - value built on disputed inputs";
            } else {
                valueVerdict = "uncertain";
                valueReason = "all cited inputs verify; per-share arithmetic inputs (equity value, share count) "
                        + "not stated in the note - value awaits the re-ask cross-check";
            }
        }

        int pass = 0, fail = 0, unc = 0;
        for (FieldResult r : results) {
            if ("pass".equals(r.verdict)) pass++;
            else if ("fail".equals(r.verdict)) fail++;
            else unc++;
        }
        String date = todayEt();

        // ---- audit JSON (with append-only accuracy history) ----
        Path outFile = OUT.resolve(t + ".json");
        J.A history = new J.A();
        if (Files.exists(outFile)) {
            try {
                J.O prev = (J.O) J.parse(Files.readString(outFile, StandardCharsets.UTF_8));
                J.A h = J.arr(prev.m.get("history"));
                if (h != null) history = h;
            } catch (Exception ignored) { }
        }
        J.O he = new J.O();
        he.m.put("date", new J.S(date));
        he.m.put("fields", new J.N((double) results.size()));
        he.m.put("pass", new J.N(pass));
        he.m.put("fail", new J.N(fail));
        he.m.put("uncertain", new J.N(unc));
        he.m.put("value_verdict", new J.S(valueVerdict));
        history.l.add(he);

        J.O rec = new J.O();
        rec.m.put("ticker", new J.S(t));
        rec.m.put("run_at", new J.S(date));
        rec.m.put("reviewer", new J.S("SecNlmAudit (automated)"));
        rec.m.put("atlas_note", new J.S("atlas/05 \uD83D\uDCD3 NotebookLM/" + t + " \u2014 NotebookLM Research.md"));
        rec.m.put("brief", new J.S("research_notes/notebooklm_pending/" + t + ".md"));
        J.A fa = new J.A();
        for (FieldResult r : results) fa.l.add(fieldJson(r));
        rec.m.put("fields", fa);
        J.O vc = new J.O();
        vc.m.put("nlm_value", nd.nlmValue == null ? new J.Nil() : new J.N(nd.nlmValue));
        vc.m.put("nlm_citation", new J.S(nd.nlmCitation == null ? "" : nd.nlmCitation));
        vc.m.put("verdict", new J.S(valueVerdict));
        vc.m.put("reason", new J.S(valueReason));
        rec.m.put("value_check", vc);
        J.O sum = new J.O();
        sum.m.put("fields", new J.N((double) results.size()));
        sum.m.put("pass", new J.N(pass));
        sum.m.put("fail", new J.N(fail));
        sum.m.put("uncertain", new J.N(unc));
        rec.m.put("summary", sum);
        rec.m.put("history", history);

        Files.createDirectories(OUT);
        Files.writeString(outFile, J.dumps(rec) + "\n", StandardCharsets.UTF_8);

        // ---- human-readable finding ----
        StringBuilder md = new StringBuilder();
        md.append("# SEC <-> NotebookLM audit: ").append(t).append("\n\n");
        md.append("Run ").append(date).append(" (automated). Fields ")
                .append(pass).append(" pass / ").append(fail).append(" fail / ").append(unc).append(" uncertain.\n\n");
        md.append("| Field | Period | SEC value | NotebookLM cited | Diff (tol) | XBRL | Verdict |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (FieldResult r : results) {
            md.append("| ").append(r.def.label).append(" | ").append(r.period)
                    .append(" | ").append(fmt(r.secVal)).append(" | ").append(fmt(r.nlmVal))
                    .append(" | ").append(fmt(r.diff)).append(" (").append(fmt(r.tolerance)).append(")")
                    .append(" | ").append(r.xbrl.check).append(" | ").append(r.verdict).append(" |\n");
        }
        md.append("\n## NotebookLM DCF value\n");
        md.append(nd.nlmValue == null ? "none stated" : ("$" + nd.nlmValue)).append(" — ").append(valueVerdict)
                .append(": ").append(valueReason).append("\n");
        if (nd.nlmCitation != null) md.append("Citation: ").append(nd.nlmCitation).append("\n");
        md.append("\n## Provenance\n");
        md.append("SEC evidence: sec_filings/dcf_inputs_all.json + XBRL companyfacts (accession per field above).\n");
        md.append("NotebookLM side: the Atlas research note input table (Muse's input package) and the saved-note read-back.\n");
        if (fail > 0) md.append("\n> FAIL fields have a re-ask evidence pack at sec_nlm_audit/reask_packs/")
                .append(t).append(".json — the re-ask job must put that exact evidence in front of NotebookLM.\n");
        Files.writeString(OUT.resolve(t + ".md"), md.toString(), StandardCharsets.UTF_8);

        // ---- re-ask evidence pack (only when NotebookLM cited a wrong figure) ----
        if (fail > 0) {
            J.O pack = new J.O();
            pack.m.put("ticker", new J.S(t));
            pack.m.put("generated", new J.S(date));
            pack.m.put("reason", new J.S("NotebookLM input figure(s) differ from SEC evidence beyond tolerance"));
            pack.m.put("instruction", new J.S(
                    "Include each correction below VERBATIM in the re-ask prompt as the filing evidence, "
                    + "and require NotebookLM to reconcile its figure against it (Rahik's rule: re-ask with "
                    + "the exact filing evidence so it self-corrects)."));
            J.A corr = new J.A();
            for (FieldResult r : results) {
                if (!"fail".equals(r.verdict)) continue;
                J.O c = new J.O();
                c.m.put("field", new J.S(r.def.key));
                c.m.put("label", new J.S(r.def.label));
                c.m.put("period", new J.S(r.period));
                c.m.put("sec_value", new J.N(r.secVal));
                c.m.put("units", new J.S(r.units));
                c.m.put("notebooklm_cited", new J.N(r.nlmVal));
                J.O prov = new J.O();
                prov.m.put("form", new J.S(r.form));
                prov.m.put("filing_date", new J.S(r.filingDate == null ? "" : r.filingDate));
                prov.m.put("fiscal_period", new J.S(r.fiscalPeriod == null ? "" : r.fiscalPeriod));
                prov.m.put("files", new J.S(r.files));
                prov.m.put("statement", new J.S(r.def.statement));
                prov.m.put("line_item", new J.S(r.def.lineItem));
                if (r.xbrl.accn != null) prov.m.put("xbrl_accession", new J.S(r.xbrl.accn));
                c.m.put("provenance", prov);
                corr.l.add(c);
            }
            pack.m.put("corrections", corr);
            Files.createDirectories(PACKS);
            Files.writeString(PACKS.resolve(t + ".json"), J.dumps(pack) + "\n", StandardCharsets.UTF_8);
        }

        System.out.println(t + ": " + pass + " pass / " + fail + " fail / " + unc
                + " uncertain; value " + valueVerdict + (fail > 0 ? " (re-ask pack written)" : ""));
    }

    static XbrlHit absentHit(FieldDef f, String note) {
        XbrlHit h = new XbrlHit();
        h.tag = f.usTag; h.check = "absent"; h.note = note;
        return h;
    }

    static String padCik(String cik) {
        if (cik == null) return "";
        String d = cik.replaceAll("\\D", "");
        while (d.length() < 10) d = "0" + d;
        return d;
    }

    static String fmt(Double v) {
        if (v == null || Double.isNaN(v)) return "-";
        return String.format("%,.0f", v);
    }

    static J.O fieldJson(FieldResult r) {
        J.O o = new J.O();
        o.m.put("field", new J.S(r.def.key));
        o.m.put("label", new J.S(r.def.label));
        o.m.put("period", new J.S(r.period));
        o.m.put("form", new J.S(r.form == null ? "" : r.form));
        o.m.put("filing_date", new J.S(r.filingDate == null ? "" : r.filingDate));
        o.m.put("fiscal_period", new J.S(r.fiscalPeriod == null ? "" : r.fiscalPeriod));
        o.m.put("files", new J.S(r.files == null ? "" : r.files));
        o.m.put("statement", new J.S(r.def.statement));
        o.m.put("line_item", new J.S(r.def.lineItem));
        o.m.put("units", new J.S(r.units));
        o.m.put("sec_value", r.secVal == null || Double.isNaN(r.secVal) ? new J.Nil() : new J.N(r.secVal));
        o.m.put("nlm_value", r.nlmVal == null || Double.isNaN(r.nlmVal) ? new J.Nil() : new J.N(r.nlmVal));
        o.m.put("difference", r.diff == null || Double.isNaN(r.diff) ? new J.Nil() : new J.N(r.diff));
        o.m.put("tolerance", r.tolerance == null || Double.isNaN(r.tolerance) ? new J.Nil() : new J.N(r.tolerance));
        J.O x = new J.O();
        x.m.put("tag", new J.S(r.xbrl.tag == null ? "" : r.xbrl.tag));
        x.m.put("check", new J.S(r.xbrl.check));
        x.m.put("value_millions", r.xbrl.valMillions == null ? new J.Nil() : new J.N(r.xbrl.valMillions));
        x.m.put("accession", new J.S(r.xbrl.accn == null ? "" : r.xbrl.accn));
        x.m.put("frame", new J.S(r.xbrl.frame == null ? "" : r.xbrl.frame));
        x.m.put("note", new J.S(r.xbrl.note == null ? "" : r.xbrl.note));
        o.m.put("xbrl", x);
        o.m.put("nlm_citation", new J.S(r.nlmCitation));
        o.m.put("verdict", new J.S(r.verdict));
        o.m.put("reviewer", new J.S("SecNlmAudit"));
        o.m.put("date", new J.S(todayEt()));
        return o;
    }

    // ------------------------------------------------------------------
    // Self-test: tolerance matching, parsing, and the proposal-only proof.
    // Run with:
    //   java -Dfile.encoding=UTF-8 --class-path ~/workspace/java-shared/classes \
    //       SecNlmAudit.java --self-test
    // Uses the shared SelfTest harness (AGENTS.md: repeated steps become a method).
    // ------------------------------------------------------------------
    static boolean eq(double a, double b) {
        return (Double.isNaN(a) && Double.isNaN(b)) || Math.abs(a - b) < 1e-9;
    }

    static void selfTest() throws Exception {
        initPaths();

        // 1. Money parsing.
        SelfTest.check("parse $139,514", eq(parseMillions("$139,514"), 139514));
        SelfTest.check("parse unicode-minus -$26,992", eq(parseMillions("\u2212$26,992"), -26992));
        SelfTest.check("parse 10,827M", eq(parseMillions("10,827M"), 10827));
        SelfTest.check("parse dash -> NaN", eq(parseMillions("\u2014"), Double.NaN));
        SelfTest.check("parse (YTD) suffix", eq(parseMillions("10,889M (YTD)"), 10889));
        SelfTest.check("parse plain", eq(parseMillions("7695"), 7695));

        // 2. Tolerance / verdict boundaries.
        SelfTest.check("tol floor", eq(tol(10), 1.0));
        SelfTest.check("tol 1pct", eq(tol(139514), 1395.14));
        SelfTest.check("verdict pass exact", verdict(139514, 139514).equals("pass"));
        SelfTest.check("verdict pass within tol", verdict(139514, 140000).equals("pass"));
        SelfTest.check("verdict fail beyond tol", verdict(139514, 150000).equals("fail"));
        SelfTest.check("verdict uncertain on NaN", verdict(139514, Double.NaN).equals("uncertain"));
        SelfTest.check("verdict fail negative flip", verdict(7695, -26992).equals("fail"));

        // 3. Note table parsing on a synthetic note.
        Path tmp = Files.createTempDirectory("secnlm");
        Path note = tmp.resolve("AMZN-note.md");
        Files.writeString(note,
                "# AMZN \u2014 NotebookLM Research\n\n"
                + "Source filings: 10-K FY2025 (filed 2026-02-06) and 10-Q Q2 2026 (filed 2026-07-31).\n\n"
                + "## DCF inputs extracted\n"
                + "| Input | 10-K FY2025 (annual) | 10-Q 6M 2026 (interim YTD) |\n"
                + "|---|---|---|\n"
                + "| Net cash from operating activities | $139,514 | $71,419 |\n"
                + "| Capex (purchases of property and equipment) | $131,819 | $98,411 |\n"
                + "| Implied FCF (OCF \u2212 capex) | $7,695 | \u2212$26,992 |\n"
                + "| Diluted weighted-average shares | 10,827M | 10,889M (YTD) |\n"
                + "\n## NotebookLM DCF value\n"
                + "- **$152.42** \u2014 harder-prompt base-case value, read back 2026-09-26.\n",
                StandardCharsets.UTF_8);
        NoteData nd = parseNote(note);
        SelfTest.check("note annual fy", "2025".equals(nd.annualFy));
        SelfTest.check("note qtr filed", "2026-07-31".equals(nd.qtrFiled));
        SelfTest.check("note qtr Q", "Q2".equals(nd.qtrQ));
        SelfTest.check("note ocf annual cell", eq(parseMillions(nd.rows.get("ocf")[0]), 139514));
        SelfTest.check("note fcf qtr cell", eq(parseMillions(nd.rows.get("fcf")[1]), -26992));
        SelfTest.check("note value", eq(nd.nlmValue, 152.42));
        SelfTest.check("note citation", nd.nlmCitation != null && nd.nlmCitation.contains("2026-09-26"));

        // 4. XBRL selector on synthetic companyfacts (frame + duration picking).
        J.O cf = new J.O(); J.O facts = new J.O(); J.O gaap = new J.O();
        J.O tagO = new J.O(); J.O units = new J.O(); J.A arr = new J.A();
        arr.l.add(xbrlEntry("10-K", "2026-02-06", "2023-01-01", "2023-12-31", "CY2023", 84946000000.0, "accn-old"));
        arr.l.add(xbrlEntry("10-K", "2026-02-06", "2025-01-01", "2025-12-31", "CY2025", 139514000000.0, "accn-new"));
        // Real 10-Q shape: the prior-year comparative 6M period sorts FIRST, so the
        // selector must skip it and take the current-year YTD entry.
        arr.l.add(xbrlEntry("10-Q", "2026-07-31", "2025-01-01", "2025-06-30", null, 49530000000.0, "accn-ytd-prior"));
        arr.l.add(xbrlEntry("10-Q", "2026-07-31", "2026-04-01", "2026-06-30", "CY2026Q2", 45387000000.0, "accn-q"));
        arr.l.add(xbrlEntry("10-Q", "2026-07-31", "2026-01-01", "2026-06-30", null, 71419000000.0, "accn-ytd"));
        units.m.put("USD", arr); tagO.m.put("units", units);
        gaap.m.put("NetCashProvidedByUsedInOperatingActivities", tagO);
        facts.m.put("us-gaap", gaap); cf.m.put("facts", facts);
        Path cfFile = tmp.resolve("companyfacts.json");
        Files.writeString(cfFile, J.dumps(cf), StandardCharsets.UTF_8);
        FieldDef ocfDef = FIELDS.get(0);
        XbrlHit ha = xbrlCheck(cfFile, ocfDef, "10-K", "2026-02-06", "2025", "FY", 139514);
        SelfTest.check("xbrl annual picks CY2025 frame",
                "pass".equals(ha.check) && "accn-new".equals(ha.accn) && Math.abs(ha.valMillions - 139514) < 1e-6);
        XbrlHit hq = xbrlCheck(cfFile, ocfDef, "10-Q", "2026-07-31", "2026", "Q2", 71419);
        SelfTest.check("xbrl interim picks 6M YTD duration",
                "pass".equals(hq.check) && "accn-ytd".equals(hq.accn) && Math.abs(hq.valMillions - 71419) < 1e-6);
        XbrlHit hm = xbrlCheck(cfFile, ocfDef, "10-Q", "2026-07-31", "2026", "Q2", 70000);
        SelfTest.check("xbrl mismatch flagged", "mismatch".equals(hm.check));
        XbrlHit hx = xbrlCheck(cfFile, FIELDS.get(2), "10-K", "2026-02-06", "2025", "FY", 7695);
        SelfTest.check("xbrl na for computed field", "na".equals(hx.check));

        // 5. PROPOSAL-ONLY PROOF — the write path is absent, not merely unused.
        // 5a. Reflection: no method or field in this class touches a blessed store.
        boolean clean = true;
        for (var m : SecNlmAudit.class.getDeclaredMethods())
            if (m.getName().toLowerCase().contains("bless")) clean = false;
        for (var f : SecNlmAudit.class.getDeclaredFields())
            if (f.getName().toLowerCase().contains("bless")) clean = false;
        SelfTest.check("no bless-named member (reflection)", clean);

        // 5b. Source scan: strip string literals + comments, then require that no
        // bless/rebless identifier survives in actual program text.
        Path src = Paths.get(HOME, "workspace", "dcf_csv", "SecNlmAudit.java");
        String code = Files.readString(src, StandardCharsets.UTF_8);
        // Single pass: a // inside a string literal must not start a comment,
        // and a quote inside a comment must not start a string. Comments become
        // a space; strings/chars collapse to "".
        java.util.regex.Pattern tok = java.util.regex.Pattern.compile(
                "(?s)/\\*.*?\\*/|//[^\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
        java.util.regex.Matcher tm = tok.matcher(code);
        StringBuffer sbc = new StringBuffer();
        while (tm.find()) {
            String tg = tm.group();
            tm.appendReplacement(sbc, (tg.startsWith("\"") || tg.startsWith("'")) ? "\"\"" : " ");
        }
        tm.appendTail(sbc);
        code = sbc.toString();
        Matcher bad = Pattern.compile("(?i)\\b[a-z]*bless\\w*\\b").matcher(code);
        boolean found = bad.find();
        if (found) System.out.println("  offending token: " + bad.group());
        SelfTest.check("no bless/rebless identifier in program text", !found);

        // 5c. Behavioral: run a real audit into a temp root; the proposals dir
        // and blessed.json must be byte-identical afterwards, and no proposal
        // file may appear.
        Path propDir = DCF_CSV.resolve("proposals").resolve("pending");
        // Read-only integrity probe: snapshot the bytes to prove the audit never
        // writes the guarded store (no write call on this path anywhere below).
        Path guardFile = DCF_CSV.resolve("blessed.json");
        Map<String, Long> beforeProps = new TreeMap<>();
        if (Files.exists(propDir)) try (var s = Files.list(propDir)) {
            s.forEach(p -> { try { beforeProps.put(p.getFileName().toString(), Files.size(p)); }
                catch (IOException ignored) { } });
        }
        byte[] guardBefore = Files.exists(guardFile)
                ? Files.readAllBytes(guardFile) : new byte[0];
        // Point only the audit output at temp; inputs stay real.
        setEnvForTest(tmp.resolve("out"));
        try {
            audit("AMZN");
        } finally {
            setEnvForTest(null);
        }
        Map<String, Long> afterProps = new TreeMap<>();
        if (Files.exists(propDir)) try (var s = Files.list(propDir)) {
            s.forEach(p -> { try { afterProps.put(p.getFileName().toString(), Files.size(p)); }
                catch (IOException ignored) { } });
        }
        byte[] guardAfter = Files.exists(guardFile)
                ? Files.readAllBytes(guardFile) : new byte[0];
        SelfTest.check("audit filed zero proposals", beforeProps.equals(afterProps));
        SelfTest.check("audit left blessed.json untouched", java.util.Arrays.equals(guardBefore, guardAfter));
        SelfTest.check("audit wrote record + finding",
                Files.exists(tmp.resolve("out").resolve("AMZN.json"))
                && Files.exists(tmp.resolve("out").resolve("AMZN.md")));
        J.O recOut = (J.O) J.parse(Files.readString(
                tmp.resolve("out").resolve("AMZN.json"), StandardCharsets.UTF_8));
        J.A hist = J.arr(recOut.m.get("history"));
        SelfTest.check("accuracy history persisted", hist != null && hist.l.size() >= 1);

        // 5d. Fail-closed: unknown ticker writes nothing.
        boolean threw = false;
        setEnvForTest(tmp.resolve("out2"));
        try { audit("ZZZZ"); } catch (IllegalArgumentException e) { threw = true; }
        finally { setEnvForTest(null); }
        SelfTest.check("unknown ticker fails closed", threw
                && !Files.exists(tmp.resolve("out2").resolve("ZZZZ.json")));

        SelfTest.finish();
    }

    static J.O xbrlEntry(String form, String filed, String start, String end,
                         String frame, double val, String accn) {
        J.O o = new J.O();
        o.m.put("form", new J.S(form)); o.m.put("filed", new J.S(filed));
        o.m.put("start", new J.S(start)); o.m.put("end", new J.S(end));
        o.m.put("frame", frame == null ? new J.Nil() : new J.S(frame));
        o.m.put("val", new J.N(val)); o.m.put("accn", new J.S(accn));
        return o;
    }

    // Test hook: redirect only the audit output root (inputs stay real).
    static Path testOutRoot = null;
    static void setEnvForTest(Path p) {
        testOutRoot = p;
        OUT = p == null
                ? (System.getenv("SEC_NLM_AUDIT_ROOT") == null || System.getenv("SEC_NLM_AUDIT_ROOT").isEmpty()
                        ? DCF_CSV.resolve("sec_nlm_audit") : Paths.get(System.getenv("SEC_NLM_AUDIT_ROOT")))
                : p;
        PACKS = OUT.resolve("reask_packs");
    }

    public static void main(String[] args) throws Exception {
        initPaths();
        if (args.length == 0) { System.err.println("usage: --audit T | --audit-all | --self-test"); System.exit(2); }
        switch (args[0]) {
            case "--self-test":
                selfTest();
                break;
            case "--audit":
                if (args.length < 2) { System.err.println("--audit needs a ticker"); System.exit(2); }
                audit(args[1]);
                break;
            case "--audit-all": {
                J.O inputs = (J.O) J.parse(Files.readString(INPUTS_ALL, StandardCharsets.UTF_8));
                J.O companies = J.obj(inputs.m.get("companies"));
                int n = 0;
                for (String t : companies.m.keySet()) {
                    if (findAtlasNote(t) == null) { System.out.println(t + ": no Atlas note, skipped"); continue; }
                    try { audit(t); n++; }
                    catch (Exception e) { System.out.println(t + ": ERROR " + e.getMessage()); }
                }
                System.out.println("audited " + n + " tickers");
                break;
            }
            default:
                System.err.println("unknown: " + args[0]); System.exit(2);
        }
    }
}
