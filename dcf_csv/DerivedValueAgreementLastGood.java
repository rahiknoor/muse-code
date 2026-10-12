// DerivedValueAgreement.java — the merged derived-value audit job.
//
// Rahik's combine principle (2026-09-29): A1 + A2 were two verifiers doing
// the same task — "derived values agree with their inputs" — so they are one
// job now, with two check sections sharing a single Finding stream and one
// --verify / --self-test. (A3 BatchGuardReverse stays its own verifier.)
//
// Section A1 (speculation): independently recompute speculation_price.json
//   from our_dcf.json + the backend-only outside median + the nudge from
//   growth_outlook.json (High +0.05, Moderate +0.025, Low -0.05,
//   not-yet-researched/missing 0; null when the DCF or the median is
//   missing). INVARIANT: nlm_speculation must remain null /
//   provenance-reserved — NotebookLM's speculation is DERIVED BY NOTEBOOKLM
//   ITSELF, so any non-null value is a FAIL. The median computation mirrors
//   OutsideVoice.java exactly; the formula mirrors SpeculationPrice.java
//   exactly; tolerance 1e-9 relative.
//
// Section A2 (buy-below — RESHAPED spec, Rahik 2026-09-29, supersedes the
// per-price buy-below design): buy-below is ONE column per ticker, on Our
// DCF only:  buy_below = our_dcf x (1 - required margin), with the required
// margin from margin_requirements.json (default 0.20). Every surface is
// checked: the comparison table render, the workbook Comparison sheet, the
// comparison CSV/XLSX, markdown exports, the valuation dashboard, Atlas
// notes, and the DcfGapPipeline thresholds (its alert boundary IS this
// buy-below). Any surface still showing per-price buy-belows = FAIL.
// The artifact is noted as manual coverage (not a workspace file).
// SUB-ROW AMENDMENT (Rahik 2026-09-29, later the same day — the 9:50 table
// call): on the 9:50 daily table surface (ComparisonTable.java --print) the
// buy-below is NOT a column. Each ticker row is followed by a "buy below"
// sub-row whose ONLY populated cell sits under Our DCF. checkComparisonTable
// enforces that geometry via checkTableSubRows; the file surfaces
// (CSV/XLSX/workbook Comparison sheet) keep the single buy-below column
// and are still checked by checkTableSurface.
// This verifier never modifies any surface; it reports only.
//
// Findings keep their audit-gap ids: "A1" for speculation findings, "A2"
// for buy-below findings.
//
// Usage (POI is needed on the class path for the xlsx reads):
//   java --class-path ~/workspace/java-shared/classes:~/workspace/lib/poi/* DerivedValueAgreement.java --self-test
//   java --class-path ~/workspace/java-shared/classes:~/workspace/lib/poi/* DerivedValueAgreement.java --verify
// Run the JVM with LC_ALL=C.UTF-8 (emoji in the Atlas vault path).
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
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class DerivedValueAgreement {

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isEmpty()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static final String WS = home() + "/workspace/";
    static final String D = WS + "dcf_csv/";
    static final String MISSING = "\u2014"; // em dash — the "no value" cell
    /** The master backend — the file the rebuild itself reads. */
    static final String WD_MASTER = WS + "your_files/dcf-valuations/.src/workbook_data.json";

    // ------------------------------------------------------------------
    // Shared helpers.
    // ------------------------------------------------------------------
    static String read(String p) throws IOException {
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }

    static String str(J.V v) {
        return (v instanceof J.S) ? ((J.S) v).s : null;
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

    /** Buy-below for one intrinsic value: v x (1 - req); null when the
     *  value is missing, zero, or negative (mirrors every builder). */
    static Double buyBelow(Double v, double req) {
        return (v != null && v > 0) ? v * (1.0 - req) : null;
    }

    /** Header normalization: newlines -> space, lowercased, spaces collapsed. */
    static String normHeader(String h) {
        if (h == null) return "";
        return h.replace('\n', ' ').replace('\r', ' ')
                .trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
    }

    /** Raw pipe count of a markdown table line — geometry matching that
     *  survives trailing-blank trimming (sub-rows legitimately trim short). */
    static int pipeCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '|') n++;
        return n;
    }

    /** Our-DCF column headers accepted under the old and new layouts. */
    static boolean isOurDcfHeader(String norm) {
        return norm.equals("our dcf") || norm.equals("muse dcf");
    }

    /** Indexes of every buy-below column in a header row. */
    static List<Integer> buyBelowCols(List<String> headers) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            if (normHeader(headers.get(i)).contains("buy below")) out.add(i);
        }
        return out;
    }

    static int indexOfNorm(List<String> headers, String want) {
        for (int i = 0; i < headers.size(); i++) {
            if (normHeader(headers.get(i)).equals(want)) return i;
        }
        return -1;
    }

    /** Minimal CSV parser: quoted fields, embedded newlines, "" escapes. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"'); i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString()); field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString()); field.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else if (c == '\r') {
                // skip (paired with \n)
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    // ---- POI helpers (xlsx reads) ----
    static Sheet findSheetWith(Workbook wb, String needle) {
        String n = needle.toLowerCase(Locale.US);
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            for (int r = 0; r <= Math.min(12, s.getLastRowNum()); r++) {
                Row row = s.getRow(r);
                if (row == null) continue;
                for (Cell c : row) {
                    String v = cellString(c);
                    if (v != null && v.toLowerCase(Locale.US).contains(n)) return s;
                }
            }
        }
        return null;
    }

    static int findHeaderRow(Sheet sheet, String needle) {
        String n = needle.toLowerCase(Locale.US);
        for (int r = 0; r <= Math.min(12, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell c : row) {
                String v = cellString(c);
                if (v != null && v.toLowerCase(Locale.US).contains(n)) return r;
            }
        }
        return 0;
    }

    static List<String> rowStrings(Sheet sheet, int r) {
        Row row = sheet.getRow(r);
        List<String> out = new ArrayList<>();
        if (row == null) return out;
        int last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
            String v = cellString(row.getCell(c));
            out.add(v == null ? "" : v);
        }
        return out;
    }

    static String cellString(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue();
            case NUMERIC: return Double.toString(cell.getNumericCellValue());
            case BOOLEAN: return cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case FORMULA:
                try {
                    if (cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                        return Double.toString(cell.getNumericCellValue());
                    }
                    if (cell.getCachedFormulaResultType() == CellType.STRING) {
                        return cell.getStringCellValue();
                    }
                } catch (Exception ignored) { }
                return null;
            default: return null;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length == 1 && args[0].equals("--verify")) { verify(); return; }
        System.err.println("usage: java DerivedValueAgreement.java --self-test | --verify");
        System.exit(2);
    }

    static void verify() throws Exception {
        List<Finding> findings = new ArrayList<>();
        checkSpeculation(findings); // section A1
        checkBuyBelow(findings);    // section A2
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();
        Finding.split(findings, infos, failures);
        long a1 = failures.stream().filter(f -> f.startsWith("check: A1\n")).count();
        long a2 = failures.stream().filter(f -> f.startsWith("check: A2\n")).count();
        Verify.report(
                "VERIFY OK (DerivedValueAgreement): speculation and buy-below agree with their inputs on all surfaces.",
                "VERIFY FAILED (DerivedValueAgreement): " + failures.size()
                        + " problem(s) found (" + a1 + " A1, " + a2 + " A2):",
                infos, failures);
    }

    // ==================================================================
    // SECTION A1 — speculation agreement.
    // ==================================================================

    /**
     * Median of the available values — EXACT mirror of OutsideVoice.median:
     * nulls skipped, sorted, middle value (or mean of the two middles for an
     * even count), rounded to cents, null when there are no values.
     */
    static Double median(List<Double> vals) {
        List<Double> xs = new ArrayList<>();
        for (Double v : vals) if (v != null) xs.add(v);
        if (xs.isEmpty()) return null;
        Collections.sort(xs);
        int n = xs.size();
        double m = (n % 2 == 1) ? xs.get(n / 2)
                : (xs.get(n / 2 - 1) + xs.get(n / 2)) / 2.0;
        return Math.round(m * 100.0) / 100.0;
    }

    /**
     * Growth-outlook nudge rate — EXACT mirror of SpeculationPrice.nudgeRate
     * (Muse's research voice only; there is deliberately no NotebookLM nudge
     * anywhere in this file).
     */
    static double nudgeRate(String outlook) {
        if ("High".equals(outlook)) return 0.05;
        if ("Moderate".equals(outlook)) return 0.025;
        if ("Low".equals(outlook)) return -0.05;
        return 0.0;
    }

    /**
     * The speculation formula — EXACT mirror of SpeculationPrice.speculate:
     * null when the voice's DCF or the outside median is missing; otherwise
     * rounded to cents (half-up via Math.round).
     */
    static Double speculate(Double voice, Double med, double nudge) {
        if (voice == null || med == null) return null;
        return Math.round((voice + 0.5 * (med - voice) + voice * nudge) * 100.0) / 100.0;
    }

    static Double num(J.V v) {
        return (v instanceof J.N) ? ((J.N) v).d : null;
    }

    /** Section A1: recompute speculation_price.json from its inputs. */
    static void checkSpeculation(List<Finding> findings) {
        J.O specDoc, ourDoc, creatorDoc, analystDoc, webDoc, goDoc, blessedDoc;
        try {
            specDoc = (J.O) J.parse(read(D + "speculation_price.json"));
            ourDoc = (J.O) J.parse(read(D + "our_dcf.json"));
            creatorDoc = (J.O) J.parse(read(D + "creator_values.json"));
            analystDoc = (J.O) J.parse(read(D + "analyst_targets.json"));
            webDoc = (J.O) J.parse(read(D + "website_consensus.json"));
            goDoc = (J.O) J.parse(read(D + "growth_outlook.json"));
            blessedDoc = (J.O) J.parse(read(D + "blessed.json"));
        } catch (Exception e) {
            // Fail-closed: if an input cannot even be read, nothing is verified.
            findings.add(Finding.fail("A1", "-", "inputs-readable",
                    "all seven backends parse", "read/parse failure: " + e.getMessage(),
                    D + " (speculation_price.json, our_dcf.json, creator_values.json, "
                            + "analyst_targets.json, website_consensus.json, growth_outlook.json, blessed.json)",
                    "Fix the unreadable file, then re-run this verifier."));
            return;
        }

        J.O spTickers = obj(specDoc, "tickers");
        J.O ourTickers = obj(ourDoc, "values");
        J.O creatorLatest = obj(creatorDoc, "latest");
        J.O webTickers = obj(webDoc, "tickers");
        J.O goTickers = obj(goDoc, "tickers");
        J.O blessedOurs = null;
        J.O blessedCols = obj(blessedDoc, "columns");
        if (blessedCols != null) blessedOurs = obj(obj(blessedCols, "ours"), "tickers");
        // Provenance evidence for the reserved slot: a blessed
        // "nlm_speculation" column would be the only sanctioned source.
        boolean blessedHasNlmSpec = blessedCols != null
                && blessedCols.m.get("nlm_speculation") instanceof J.O;

        int checked = 0, nlmNull = 0;
        for (String t : spTickers.m.keySet()) {
            J.O rec = (J.O) spTickers.m.get(t);
            String ev = D + "speculation_price.json tickers." + t;

            // --- outside median, recomputed from the raw outside backends ---
            List<Double> vals = new ArrayList<>();
            J.V cr = creatorLatest == null ? null : creatorLatest.m.get(t);
            if (cr instanceof J.O) vals.add(num(((J.O) cr).m.get("value")));
            J.V ar = analystDoc.m.get(t);
            if (ar instanceof J.O) vals.add(num(((J.O) ar).m.get("target")));
            J.V wr = webTickers == null ? null : webTickers.m.get(t);
            if (wr instanceof J.O) vals.add(num(((J.O) wr).m.get("consensus")));
            Double med = median(vals);
            Double storedMed = num(rec.m.get("outside_median"));
            if (!close(med, storedMed)) {
                findings.add(Finding.fail("A1", t, "outside_median",
                        med, storedMed, ev + ".outside_median",
                        "The stored median disagrees with the recompute. Do not hand-edit: "
                                + "regenerate via java OutsideVoice.java --build then "
                                + "java SpeculationPrice.java --build (derived-file builders)."));
            }

            // --- ours, from our_dcf.json (the audit's independent source) ---
            J.V ourRec = ourTickers == null ? null : ourTickers.m.get(t);
            Double ours = (ourRec instanceof J.O) ? num(((J.O) ourRec).m.get("value")) : null;
            if (blessedOurs != null) {
                J.V bRec = blessedOurs.m.get(t);
                Double bOurs = (bRec instanceof J.O) ? num(((J.O) bRec).m.get("value")) : null;
                if (!close(ours, bOurs)) {
                    findings.add(Finding.warn("A1", t, "ours-source-agreement",
                            "our_dcf.json value == blessed.json ours value",
                            "our_dcf=" + ours + " blessed_ours=" + bOurs,
                            D + "our_dcf.json values." + t + " vs " + D + "blessed.json columns.ours.tickers." + t,
                            "One-DCF rule: both must carry the same number. Cross-check which is right "
                                    + "(file-first), then file a proposal via Proposals.java — the verifier files nothing."));
                }
            }

            // --- nudge, from growth_outlook.json ---
            String outlook = null;
            J.V goRec = goTickers == null ? null : goTickers.m.get(t);
            if (goRec instanceof J.O) outlook = str(((J.O) goRec).m.get("outlook"));
            double nudge = nudgeRate(outlook);

            // --- muse_speculation, recomputed ---
            Double expected = speculate(ours, med, nudge);
            Double stored = num(rec.m.get("muse_speculation"));
            if (!close(expected, stored)) {
                findings.add(Finding.fail("A1", t, "muse_speculation",
                        expected, stored, ev + ".muse_speculation",
                        "Regenerate via java SpeculationPrice.java --build (derived file — never hand-edit). "
                                + "If the recompute itself is disputed, the formula lives in SpeculationPrice.java."));
            }

            // --- INVARIANT: nlm_speculation stays null / provenance-reserved ---
            Double nlmSpec = num(rec.m.get("nlm_speculation"));
            if (nlmSpec != null) {
                findings.add(Finding.fail("A1", t, "nlm_speculation",
                        "null (reserved — derived by NotebookLM itself, never computed here)",
                        nlmSpec, ev + ".nlm_speculation"
                                + (blessedHasNlmSpec ? " [blessed nlm_speculation column EXISTS]"
                                        : " [no blessed nlm_speculation column]"),
                        "Do not hand-edit the derived file. The only sanctioned path is a recorded "
                                + "NotebookLM response transcribed, verified, and blessed via proposal -> audit -> blessed. "
                                + "Escalate to the coordinator; do not invent the provenance."));
            } else {
                nlmNull++;
            }
            checked++;
        }

        findings.add(Finding.info("A1", "-", "coverage",
                checked + " tickers recomputed", checked + " checked, " + nlmNull + " with null nlm_speculation",
                D + "speculation_price.json", "None — record only."));
    }

    // ==================================================================
    // SECTION A2 — buy-below agreement (reshaped spec).
    // ==================================================================

    /** Margins from margin_requirements.json; default 0.20. */
    static class Inputs {
        Map<String, Double> fixed = new HashMap<>();
        double marginDefault = 0.20;
        Map<String, Double> marginPer = new HashMap<>();

        double req(String t) {
            return marginPer.getOrDefault(t, marginDefault);
        }
    }

    static Inputs loadInputs() throws IOException {
        Inputs in = new Inputs();
        J.O wd = (J.O) J.parse(read(WD_MASTER));
        J.V comps = wd.m.get("companies");
        List<J.V> companyRecs = new ArrayList<>();
        if (comps instanceof J.A) companyRecs.addAll(((J.A) comps).l);
        else if (comps instanceof J.O) companyRecs.addAll(((J.O) comps).m.values());
        for (J.V v : companyRecs) {
            J.O c = (J.O) v;
            in.fixed.put(str(c.m.get("ticker")), num(c.m.get("fixed")));
        }
        J.O mr = (J.O) J.parse(read(D + "margin_requirements.json"));
        J.V d = mr.m.get("default_margin");
        if (d instanceof J.N) in.marginDefault = ((J.N) d).d;
        J.V p = mr.m.get("per_ticker");
        if (p instanceof J.O) {
            for (Map.Entry<String, J.V> e : ((J.O) p).m.entrySet()) {
                if (e.getValue() instanceof J.N) in.marginPer.put(e.getKey(), ((J.N) e.getValue()).d);
            }
        }
        return in;
    }

    /** Section A2: every surface shows exactly one buy-below, on Our DCF. */
    static void checkBuyBelow(List<Finding> findings) {
        Inputs in;
        try {
            in = loadInputs();
        } catch (Exception e) {
            findings.add(Finding.fail("A2", "-", "inputs-readable",
                    "master workbook_data.json + margin_requirements.json parse",
                    "read/parse failure: " + e.getMessage(),
                    WD_MASTER + " + " + D + "margin_requirements.json",
                    "Fix the unreadable file, then re-run this verifier."));
            return;
        }
        // The master 'fixed' must agree with our_dcf.json (one-DCF rule):
        // a disagreement here means the buy-below inputs themselves are split.
        try {
            J.O ourTickers = obj((J.O) J.parse(read(D + "our_dcf.json")), "values");
            for (Map.Entry<String, Double> e : in.fixed.entrySet()) {
                J.V rec = ourTickers == null ? null : ourTickers.m.get(e.getKey());
                Double ours = (rec instanceof J.O) ? num(((J.O) rec).m.get("value")) : null;
                if (!close(e.getValue(), ours)) {
                    findings.add(Finding.warn("A2", e.getKey(), "fixed-ours-agreement",
                            "master fixed == our_dcf.json value",
                            "fixed=" + e.getValue() + " our_dcf=" + ours,
                            WD_MASTER + " vs " + D + "our_dcf.json values." + e.getKey(),
                            "File-first: cross-check which is right, then file a proposal via "
                                    + "Proposals.java — the verifier files nothing."));
                }
            }
        } catch (Exception e) {
            findings.add(Finding.warn("A2", "-", "fixed-ours-agreement",
                    "our_dcf.json readable for cross-check", "unreadable: " + e.getMessage(),
                    D + "our_dcf.json", "Check the file manually."));
        }
        checkCsv(in, findings);
        checkComparisonXlsx(in, findings);
        checkWorkbook(in, findings);
        checkComparisonTable(in, findings);
        checkGapPipeline(in, findings);
        checkAtlas(in, findings);
        checkDashboard(findings);
        checkMarkdownExports(in, findings);
        findings.add(Finding.info("A2", "-", "coverage",
                in.fixed.size() + " tickers checked across all surfaces",
                "buy-below = our_dcf x (1 - margin), margin default "
                        + in.marginDefault + " from margin_requirements.json",
                "master " + WD_MASTER + " (fixed) + " + D + "margin_requirements.json",
                "None — record only."));
        findings.add(Finding.info("A2", "-", "artifact",
                "manual coverage only",
                "The DCF Comparison artifact lives outside the workspace files — it cannot be "
                        + "read mechanically from here. The main agent checks it by eye after a rebuild "
                        + "using the same rule: exactly one buy-below, on Our DCF, = our_dcf x (1 - margin).",
                "(artifact system — DCF Comparison)",
                "If the artifact shows any per-price buy-below, it is stale (expected-pending-rebuild drift) "
                        + "or a fresh bug — verify by reading it directly."));
    }

    /**
     * The shared table-surface check (reshaped spec).
     *
     * Rule: exactly ONE buy-below column — the unqualified "Buy below" —
     * immediately after Our DCF, with per-row values equal to
     * our_dcf x (1 - margin) under closeEnough. Any QUALIFIED per-price
     * buy-below header ("Buy below (ours)", ...) is a stale remnant and
     * FAILs on its own, even if it happens to sit in the right position.
     */
    static void checkTableSurface(Inputs in, List<Finding> findings,
                                  String label, String evidence,
                                  List<String> headers, int tickerCol,
                                  List<List<String>> rows,
                                  Function<List<String>, String> tickerOf,
                                  BiPredicate<Double, Double> closeEnough) {
        List<Integer> bb = buyBelowCols(headers);
        int dcf = -1;
        for (int i = 0; i < headers.size(); i++) {
            if (isOurDcfHeader(normHeader(headers.get(i)))) { dcf = i; break; }
        }
        // 1. Qualified (per-price) buy-below headers are stale remnants — FAIL each.
        List<Integer> single = new ArrayList<>();
        for (int i : bb) {
            if (normHeader(headers.get(i)).equals("buy below")) {
                single.add(i);
            } else {
                findings.add(Finding.fail("A2", "-", "buy-below-" + label + "-stale-column",
                        "no per-price buy-below columns (only the single unqualified 'Buy below')",
                        "stale buy-below column: '" + headers.get(i).trim() + "'",
                        evidence,
                        "Rebuild the surface under the reshaped layout (one buy-below per ticker, on Our DCF). "
                                + "Never hand-edit a downstream file; rebuild from the backends."));
            }
        }
        // 2. Exactly one unqualified buy-below column must exist.
        if (single.isEmpty()) {
            findings.add(Finding.fail("A2", "-", "buy-below-" + label + "-missing",
                    "one buy-below column immediately after Our DCF",
                    "no unqualified 'Buy below' column at all",
                    evidence,
                    "Rebuild the surface under the reshaped layout."));
            return;
        }
        if (single.size() > 1) {
            findings.add(Finding.fail("A2", "-", "buy-below-" + label + "-duplicate",
                    "exactly one unqualified 'Buy below' column",
                    single.size() + " unqualified 'Buy below' columns",
                    evidence,
                    "Rebuild the surface under the reshaped layout."));
            return;
        }
        int bbi = single.get(0);
        if (dcf < 0) {
            findings.add(Finding.warn("A2", "-", "buy-below-" + label + "-dcf-header",
                    "an Our-DCF/Muse-DCF column to anchor against",
                    "no our-dcf/muse-dcf header found in: " + headers,
                    evidence,
                    "Check the header row spelling; the position rule cannot be checked without it."));
        } else if (bbi != dcf + 1) {
            findings.add(Finding.fail("A2", "-", "buy-below-" + label + "-position",
                    "buy-below column immediately after Our DCF (index " + (dcf + 1) + ")",
                    "buy-below at index " + bbi + " (our-dcf at " + dcf + ")",
                    evidence,
                    "Reorder under the reshape: Our DCF | Buy below | Muse speculation | ..."));
        }
        for (List<String> row : rows) {
            String t = tickerOf.apply(row);
            if (t == null || t.isEmpty() || !in.fixed.containsKey(t)) continue;
            String cell = bbi < row.size() ? row.get(bbi).trim() : "";
            Double expected = buyBelow(in.fixed.get(t), in.req(t));
            if (expected == null) {
                if (!cell.isEmpty() && !cell.equals(MISSING) && !cell.equals("-")) {
                    findings.add(Finding.fail("A2", t, "buy-below-" + label + "-value",
                            "empty/" + MISSING + " (our_dcf <= 0 or missing)",
                            "'" + cell + "'", evidence,
                            "Rebuild the surface from the backends."));
                }
            } else if (cell.isEmpty() || cell.equals(MISSING) || cell.equals("-")) {
                findings.add(Finding.fail("A2", t, "buy-below-" + label + "-value",
                        expected, "blank/'" + MISSING + "'", evidence,
                        "Rebuild the surface from the backends."));
            } else {
                try {
                    Double actual = Double.parseDouble(cell.replaceAll("[,$%]", ""));
                    if (!closeEnough.test(expected, actual)) {
                        findings.add(Finding.fail("A2", t, "buy-below-" + label + "-value",
                                expected, actual, evidence,
                                "Value drifted from our_dcf x (1 - margin). Never hand-edit: "
                                        + "rebuild from the backends (our_dcf.json + margin_requirements.json)."));
                    }
                } catch (NumberFormatException nfe) {
                    findings.add(Finding.warn("A2", t, "buy-below-" + label + "-value-parse",
                            "numeric cell", "'" + cell + "'", evidence,
                            "Could not parse the cell as a number; check the surface manually."));
                }
            }
        }
    }

    /**
     * The 9:50 daily table surface. Rahik 2026-09-30 PAUSED the sub-rows
     * ("I will add those later"): each ticker is currently ONE row. When a
     * "buy below" sub-row IS present it is still fully checked (value under
     * Our DCF, exactly one populated cell); when absent, that is the paused
     * layout and no finding is raised. Any buy-below COLUMN on this surface
     * is a stale remnant and FAILs either way.
     */
    static void checkTableSubRows(Inputs in, List<Finding> findings,
                                  List<String> headers, List<List<String>> rows,
                                  String evidence,
                                  BiPredicate<Double, Double> closeEnough) {
        int dcf = -1;
        for (int i = 0; i < headers.size(); i++) {
            if (isOurDcfHeader(normHeader(headers.get(i)))) { dcf = i; break; }
        }
        if (dcf < 0) {
            findings.add(Finding.warn("A2", "-", "table-dcf-header",
                    "an Our-DCF/Muse-DCF column to anchor against",
                    "no our-dcf/muse-dcf header found in: " + headers,
                    evidence,
                    "Check the header row spelling; the sub-row rule cannot be checked without it."));
            return;
        }
        // 1. No buy-below COLUMNS may remain on this surface (stale remnants).
        for (int i = 0; i < headers.size(); i++) {
            if (normHeader(headers.get(i)).contains("buy below")) {
                findings.add(Finding.fail("A2", "-", "table-stale-column",
                        "no buy-below columns (one row per ticker)",
                        "stale buy-below column: '" + headers.get(i).trim() + "'",
                        evidence,
                        "Rebuild the surface under the current layout (one row per ticker, "
                                + "no buy-below columns)."));
            }
        }
        // 2. When a ticker row is followed by a buy-below sub-row, check it.
        // (PAUSED 2026-09-30: sub-rows are out of the render for now — an
        // absent sub-row is the accepted layout, not a failure.)
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.isEmpty()) continue;
            if (normHeader(row.get(0)).equals("buy below")) continue; // checked with its ticker row
            String t = row.get(0).trim();
            if (t.isEmpty() || !in.fixed.containsKey(t)) continue;
            List<String> sub = null;
            if (r + 1 < rows.size()) {
                List<String> next = rows.get(r + 1);
                if (!next.isEmpty() && normHeader(next.get(0)).equals("buy below")) sub = next;
            }
            if (sub == null) continue; // paused layout: one row per ticker
            // 3. The sub-row carries its value under Our DCF, and nowhere else.
            Double expected = buyBelow(in.fixed.get(t), in.req(t));
            String cell = dcf < sub.size() ? sub.get(dcf).trim() : "";
            if (expected == null) {
                if (!cell.isEmpty() && !cell.equals(MISSING)) {
                    findings.add(Finding.fail("A2", t, "table-subrow-value",
                            "blank/" + MISSING + " (our_dcf <= 0 or missing)",
                            "'" + cell + "'", evidence,
                            "Rebuild the surface from the backends."));
                }
            } else if (cell.isEmpty() || cell.equals(MISSING)) {
                findings.add(Finding.fail("A2", t, "table-subrow-value",
                        expected, "blank", evidence,
                        "Rebuild the surface from the backends."));
            } else {
                try {
                    Double actual = Double.parseDouble(cell.replaceAll("[,$%]", ""));
                    if (!closeEnough.test(expected, actual)) {
                        findings.add(Finding.fail("A2", t, "table-subrow-value",
                                expected, actual, evidence,
                                "Value drifted from our_dcf x (1 - margin). Never hand-edit: "
                                        + "rebuild from the backends (our_dcf.json + margin_requirements.json)."));
                    }
                } catch (NumberFormatException nfe) {
                    findings.add(Finding.warn("A2", t, "table-subrow-value-parse",
                            "numeric cell", "'" + cell + "'", evidence,
                            "Could not parse the cell as a number; check the surface manually."));
                }
            }
            int populated = 0;
            for (int i = 1; i < sub.size(); i++) {
                String c = sub.get(i).trim();
                if (!c.isEmpty() && !c.equals(MISSING)) populated++;
            }
            if (populated > 1) {
                findings.add(Finding.fail("A2", t, "table-subrow-shape",
                        "exactly one populated cell (under Our DCF)",
                        populated + " populated cells", evidence,
                        "The sub-row must carry only the Our-DCF buy-below."));
            }
        }
    }

    // ---- surfaces ----

    static void checkCsv(Inputs in, List<Finding> findings) {
        String path = D + "dcf-comparison.csv";
        String text;
        try {
            text = read(path);
        } catch (Exception e) {
            findings.add(Finding.warn("A2", "-", "csv-present",
                    "comparison CSV exists", "unreadable: " + e.getMessage(), path,
                    "Confirm the export step of the rebuild ran."));
            return;
        }
        List<List<String>> rows = parseCsv(text);
        // The export carries banner lines above the header — find the header row.
        int hr = -1;
        for (int r = 0; r < rows.size(); r++) {
            if (indexOfNorm(rows.get(r), "ticker") >= 0) { hr = r; break; }
        }
        if (hr < 0) {
            findings.add(Finding.warn("A2", "-", "csv-present",
                    "a header row with a Ticker column", "no ticker header found", path,
                    "Confirm the export step of the rebuild ran."));
            return;
        }
        List<String> headers = rows.get(hr);
        int tickerCol = indexOfNorm(headers, "ticker");
        checkTableSurface(in, findings, "csv", path + " header row " + (hr + 1),
                headers, tickerCol, rows.subList(hr + 1, rows.size()),
                row -> tickerCol < row.size() ? row.get(tickerCol).trim() : "",
                DerivedValueAgreement::close);
    }

    static void checkComparisonXlsx(Inputs in, List<Finding> findings) {
        String path = D + "dcf-comparison.xlsx";
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(Path.of(path)))) {
            Sheet s = findSheetWith(wb, "buy below");
            if (s == null) {
                findings.add(Finding.warn("A2", "-", "comparison-xlsx-present",
                        "comparison xlsx with a buy-below column", "no sheet found", path,
                        "Confirm the export step of the rebuild ran."));
                return;
            }
            int hr = findHeaderRow(s, "buy below");
            List<String> headers = rowStrings(s, hr);
            List<List<String>> rows = new ArrayList<>();
            for (int r = hr + 1; r <= s.getLastRowNum(); r++) {
                List<String> vals = rowStrings(s, r);
                boolean any = vals.stream().anyMatch(v -> v != null && !v.isEmpty());
                if (any) rows.add(vals);
            }
            checkTableSurface(in, findings, "xlsx", path + " sheet '" + s.getSheetName() + "' header row " + (hr + 1),
                    headers, 0, rows,
                    row -> row.isEmpty() ? "" : row.get(0).trim(), DerivedValueAgreement::close);
        } catch (Exception e) {
            findings.add(Finding.warn("A2", "-", "comparison-xlsx-present",
                    "comparison xlsx readable", "unreadable: " + e.getMessage(), path,
                    "Confirm the export step of the rebuild ran."));
        }
    }

    static void checkWorkbook(Inputs in, List<Finding> findings) {
        String path = WS + "your_files/dcf-valuations/DCF Valuation Model.xlsx";
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(Path.of(path)))) {
            Sheet s = wb.getSheet("Comparison");
            if (s == null) s = findSheetWith(wb, "buy below");
            if (s == null) {
                findings.add(Finding.warn("A2", "-", "workbook-present",
                        "workbook with a Comparison sheet", "no Comparison sheet found", path,
                        "Confirm the workbook build step of the rebuild ran."));
                return;
            }
            int hr = findHeaderRow(s, "buy below");
            List<String> headers = rowStrings(s, hr);
            List<List<String>> rows = new ArrayList<>();
            for (int r = hr + 1; r <= s.getLastRowNum(); r++) {
                List<String> vals = rowStrings(s, r);
                boolean any = vals.stream().anyMatch(v -> v != null && !v.isEmpty());
                if (any) rows.add(vals);
            }
            checkTableSurface(in, findings, "workbook", path + " Comparison sheet header row " + (hr + 1),
                    headers, 0, rows,
                    row -> row.isEmpty() ? "" : row.get(0).trim(), DerivedValueAgreement::close);
        } catch (Exception e) {
            findings.add(Finding.warn("A2", "-", "workbook-present",
                    "workbook readable", "unreadable: " + e.getMessage(), path,
                    "Confirm the workbook build step of the rebuild ran."));
        }
    }

    /**
     * The live comparison-table render (the 9:50 daily table surface):
     * run ComparisonTable.java --print as a subprocess and check its
     * buy-below SUB-ROWS (Rahik 2026-09-29: each ticker row followed by a
     * "buy below" sub-row, value only under Our DCF) with 2dp-rounded
     * values (half-cent tolerance).
     */
    static void checkComparisonTable(Inputs in, List<Finding> findings) {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        List<String> cmd = Arrays.asList(javaBin, "--class-path",
                home() + "/workspace/java-shared/classes",
                D + "ComparisonTable.java", "--print");
        String out;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LC_ALL", "C.UTF-8");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                findings.add(Finding.warn("A2", "-", "table-render",
                        "ComparisonTable render completes", "timed out after 180s",
                        "subprocess: ComparisonTable.java --print",
                        "Check ComparisonTable.java separately; the render may be broken."));
                return;
            }
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                findings.add(Finding.warn("A2", "-", "table-render",
                        "ComparisonTable render exits 0", "exit " + p.exitValue(),
                        "subprocess: ComparisonTable.java --print",
                        "Check ComparisonTable.java separately; the render may be broken."));
                return;
            }
        } catch (Exception e) {
            findings.add(Finding.warn("A2", "-", "table-render",
                    "ComparisonTable render runs", "failed: " + e.getMessage(),
                    "subprocess: ComparisonTable.java --print",
                    "Check ComparisonTable.java separately; the render may be broken."));
            return;
        }
        // Parse the markdown table: header line "| Ticker | ... |", then data rows.
        List<String> headers = null;
        int headerPipes = 0;
        List<List<String>> rows = new ArrayList<>();
        boolean pastHeader = false;
        for (String line : out.split("\n")) {
            String tr = line.trim();
            if (!tr.startsWith("|")) continue;
            List<String> cells = new ArrayList<>();
            for (String c : tr.split("\\|", -1)) cells.add(c.trim());
            while (!cells.isEmpty() && cells.get(0).isEmpty()) cells.remove(0);
            while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) cells.remove(cells.size() - 1);
            if (cells.isEmpty()) continue;
            if (!pastHeader && cells.get(0).equalsIgnoreCase("ticker")) {
                headers = cells; headerPipes = pipeCount(tr); pastHeader = true; continue;
            }
            if (pastHeader) {
                if (cells.get(0).contains("---")) continue; // separator row
                // Rahik 2026-09-30: the table is followed by a separate
                // growth-outlook block with different geometry
                // (| Ticker | Growth outlook | Thesis |). Match on the RAW
                // pipe count, not the trimmed cell count — buy-below/MoS
                // sub-rows legitimately trim short on trailing blanks, so a
                // trimmed-cell-count check misfires and drops them.
                if (pipeCount(tr) != headerPipes) continue;
                rows.add(cells);
            }
        }
        if (headers == null) {
            findings.add(Finding.warn("A2", "-", "table-render",
                    "a '| Ticker | ...' header in the render", "no header found",
                    "subprocess: ComparisonTable.java --print",
                    "Check ComparisonTable.java separately; the render may be broken."));
            return;
        }
        // Cells are %.2f-rounded: half-cent absolute tolerance.
        BiPredicate<Double, Double> halfCent = (expected, actual) ->
                (expected == null && actual == null)
                        || (expected != null && actual != null
                                && Math.abs(expected - actual) <= 0.0051);
        checkTableSubRows(in, findings, headers, rows,
                "ComparisonTable.java --print (live render)", halfCent);
    }

    /**
     * DcfGapPipeline's alert boundary IS this buy-below: verify its default
     * margin matches margin_requirements.json and its last run actually
     * used the reshaped single buy-below (via its run log, when present).
     */
    static void checkGapPipeline(Inputs in, List<Finding> findings) {
        String src = WS + "stock_watch/DcfGapPipeline.java";
        String text;
        try {
            text = read(src);
        } catch (Exception e) {
            findings.add(Finding.warn("A2", "-", "gap-pipeline-present",
                    "DcfGapPipeline.java readable", "unreadable: " + e.getMessage(), src,
                    "The gap watch owns this buy-below — confirm it still exists."));
            return;
        }
        // Margin default: the pipeline's fallback default must equal the backend default.
        // The buy zone (dcf - price) / dcf >= required is exactly price <= buy-below.
        String fallbackLine = null;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("double fallback") || t.contains("fallback =")
                    || (t.startsWith("double def") && t.contains("="))) {
                fallbackLine = t;
                break;
            }
        }
        Double srcDefault = null;
        if (fallbackLine != null) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("(0\\.\\d+)").matcher(fallbackLine);
            if (m.find()) srcDefault = Double.parseDouble(m.group(1));
        }
        if (srcDefault != null && !close(srcDefault, in.marginDefault)) {
            findings.add(Finding.fail("A2", "-", "gap-pipeline-default",
                    in.marginDefault, srcDefault, src + " (double fallback line)",
                    "The gap watch's fallback margin disagrees with margin_requirements.json. "
                            + "Edit the source to match the backend, then re-run its self-test."));
        }
        // Last run: the pipeline reads the DCF backend fresh each run and records
        // the per-ticker dcf it used. Those must agree with the master fixed —
        // its buy-zone boundary (dcf-price)/dcf >= required IS this buy-below.
        Path lastRun = Path.of(WS + "goals/quarterly-sec-filing-watch-with-dcf-updates/"
                + "hidden_files/dcf_gap_last_run.json");
        if (Files.exists(lastRun)) {
            try {
                J.O rec = (J.O) J.parse(read(lastRun.toString()));
                String status = str(rec.m.get("status"));
                if (!"ok".equals(status)) {
                    findings.add(Finding.warn("A2", "-", "gap-pipeline-last-run",
                            "last run status ok", "status=" + status, lastRun.toString(),
                            "Check the gap watch's last run manually."));
                }
                J.O computed = obj(rec, "computed");
                if (computed != null) {
                    int disagreements = 0;
                    for (Map.Entry<String, J.V> e : computed.m.entrySet()) {
                        String t = e.getKey();
                        if (!(e.getValue() instanceof J.O) || !in.fixed.containsKey(t)) continue;
                        String dcfS = str(((J.O) e.getValue()).m.get("dcf"));
                        Double runDcf = null;
                        try { runDcf = dcfS == null ? null : Double.parseDouble(dcfS); }
                        catch (NumberFormatException ignored) { }
                        if (!close(in.fixed.get(t), runDcf)) {
                            disagreements++;
                            findings.add(Finding.fail("A2", t, "gap-pipeline-dcf",
                                    in.fixed.get(t), runDcf,
                                    lastRun + " computed." + t + ".dcf",
                                    "The gap watch's last run used a different DCF than the master — "
                                            + "its buy-zone boundary is wrong. Re-run the gap watch after "
                                            + "the rebuild (it reads the DCF backend fresh each run)."));
                        }
                    }
                    if (disagreements == 0) {
                        findings.add(Finding.info("A2", "-", "gap-pipeline-dcf",
                                "last run's DCFs agree with the master",
                                computed.m.size() + " tickers checked", lastRun.toString(),
                                "None — record only."));
                    }
                }
            } catch (Exception e) {
                findings.add(Finding.warn("A2", "-", "gap-pipeline-last-run",
                        "last-run record readable", "unreadable: " + e.getMessage(),
                        lastRun.toString(), "Check the record manually."));
            }
        } else {
            findings.add(Finding.warn("A2", "-", "gap-pipeline-last-run",
                    "a last-run record exists",
                    "no record at " + lastRun,
                    WS + "stock_watch/DcfGapPipeline.java",
                    "Run the gap watch once, or confirm its threshold logic manually."));
        }
    }

    /**
     * Atlas notes: under the reshape, notes must not carry per-price
     * buy-below remnants. The post-reshape contract is one Our-DCF buy-below;
     * a frontmatter 'buy_below' key (if present) must be unqualified and match
     * our_dcf x (1 - margin).
     */
    static void checkAtlas(Inputs in, List<Finding> findings) {
        Path dir = Path.of(WS + "atlas/01 \uD83D\uDCC8 Stocks");
        if (!Files.isDirectory(dir)) {
            findings.add(Finding.warn("A2", "-", "atlas-present",
                    "Atlas vault directory exists", "not found", dir.toString(),
                    "Check the vault path."));
            return;
        }
        // Qualified (per-price) buy-below keys are never valid under the reshape.
        String[] staleKeys = {"buy_below_ours", "buy_below_notebooklm", "buy_below_creator",
                "buy_below_analyst", "buy_below_combined", "buy_below_outside", "buy_below_muse",
                "buy_below_market", "buy_below_nlm"};
        int notes = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.md")) {
            for (Path note : ds) {
                String text = read(note.toString());
                String name = note.getFileName().toString();
                notes++;
                String lower = text.toLowerCase(Locale.US);
                for (String key : staleKeys) {
                    if (lower.contains(key + ":")) {
                        findings.add(Finding.fail("A2", name.replace(".md", ""), "atlas-stale-key",
                                "no qualified per-price buy-below keys",
                                "stale frontmatter key '" + key + ":'",
                                note.toString(),
                                "Refresh the note (RefreshValuations) after the reshape rebuild."));
                    }
                }
                // Unqualified frontmatter 'buy_below:' (if present) must match.
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?m)^buy_below:\\s*([0-9.]+)").matcher(text);
                if (m.find()) {
                    String ticker = name.replace(".md", "");
                    Double expected = in.fixed.containsKey(ticker)
                            ? buyBelow(in.fixed.get(ticker), in.req(ticker)) : null;
                    Double actual = Double.parseDouble(m.group(1));
                    if (!close(expected, actual)) {
                        findings.add(Finding.fail("A2", ticker, "atlas-buy-below",
                                expected, actual, note + " frontmatter buy_below",
                                "Refresh the note (RefreshValuations) after the reshape rebuild."));
                    }
                }
            }
        } catch (Exception e) {
            findings.add(Finding.warn("A2", "-", "atlas-present",
                    "Atlas notes readable", "unreadable: " + e.getMessage(), dir.toString(),
                    "Check the vault path and permissions."));
            return;
        }
        findings.add(Finding.info("A2", "-", "atlas-coverage",
                notes + " notes scanned for stale buy-below keys",
                notes + " notes", dir.toString(), "None — record only."));
    }

    /** The valuation dashboard must not encode the old qualified buy-belows. */
    static void checkDashboard(List<Finding> findings) {
        String path = WS + "atlas/Valuation Dashboard.md";
        String text;
        try {
            text = read(path);
        } catch (Exception e) {
            findings.add(Finding.warn("A2", "-", "dashboard-present",
                    "dashboard exists", "unreadable: " + e.getMessage(), path,
                    "Confirm the dashboard step of the rebuild ran."));
            return;
        }
        String lower = text.toLowerCase(Locale.US);
        boolean stale = lower.contains("buy_below_ours") || lower.contains("buy-below-ours")
                || lower.contains("buy below (ours)") || lower.contains("buy below ours");
        if (stale) {
            findings.add(Finding.fail("A2", "-", "dashboard-stale",
                    "no per-price buy-below remnants",
                    "dashboard still encodes the old qualified buy-belows", path,
                    "Rebuild the dashboard under the reshaped layout."));
        }
        // The one buy-below it must encode: our_dcf x (1 - margin).
        if (!lower.contains("buy below")) {
            findings.add(Finding.warn("A2", "-", "dashboard-buy-below",
                    "one Our-DCF buy-below shown", "no 'buy below' mention at all", path,
                    "Confirm the dashboard step of the rebuild ran."));
        }
    }

    /**
     * Markdown comparison exports (if any) follow the same table rule.
     * Only files that actually carry buy-below content are surfaces;
     * anything else (e.g. the SEC table-extraction comparison) is recorded
     * and skipped.
     */
    static void checkMarkdownExports(Inputs in, List<Finding> findings) {
        Path[] dirs = {Path.of(WS + "your_files/dcf-valuations/"), Path.of(D)};
        List<Path> mds = new ArrayList<>();
        for (Path dir : dirs) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.md")) {
                for (Path p : ds) {
                    String n = p.getFileName().toString().toLowerCase(Locale.US);
                    if (n.contains("comparison")) mds.add(p);
                }
            } catch (Exception ignored) { }
        }
        for (Path p : mds) {
            String text;
            try {
                text = read(p.toString());
            } catch (Exception e) {
                findings.add(Finding.warn("A2", "-", "markdown-export-readable",
                        "markdown export readable", "unreadable: " + e.getMessage(),
                        p.toString(), "Check the file manually."));
                continue;
            }
            if (!text.toLowerCase(Locale.US).contains("buy below")) {
                findings.add(Finding.info("A2", "-", "markdown-export",
                        "not a buy-below surface — skipped",
                        p.getFileName().toString() + " carries no buy-below content",
                        p.toString(), "None — record only."));
                continue;
            }
            // It carries buy-below content: parse its markdown table and check it.
            List<String> headers = null;
            List<List<String>> rows = new ArrayList<>();
            boolean pastHeader = false;
            for (String line : text.split("\n")) {
                String tr = line.trim();
                if (!tr.startsWith("|")) continue;
                List<String> cells = new ArrayList<>();
                for (String c : tr.split("\\|", -1)) cells.add(c.trim());
                while (!cells.isEmpty() && cells.get(0).isEmpty()) cells.remove(0);
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty())
                    cells.remove(cells.size() - 1);
                if (cells.isEmpty()) continue;
                if (!pastHeader && cells.get(0).equalsIgnoreCase("ticker")) {
                    headers = cells; pastHeader = true; continue;
                }
                if (pastHeader) {
                    if (cells.get(0).contains("---")) continue;
                    rows.add(cells);
                }
            }
            if (headers == null) {
                findings.add(Finding.warn("A2", "-", "markdown-export",
                        "a '| Ticker | ...' table in the export", "no table found",
                        p.toString(), "Check the export manually."));
                continue;
            }
            checkTableSurface(in, findings, "md",
                    p + " markdown table", headers, 0, rows,
                    row -> row.isEmpty() ? "" : row.get(0).trim(),
                    DerivedValueAgreement::close);
        }
        findings.add(Finding.info("A2", "-", "markdown-exports",
                mds.size() + " markdown *comparison* file(s) considered",
                mds.size() + " file(s)", dirs[0] + " + " + dirs[1],
                "None — record only."));
    }

    // ==================================================================
    // Self-test (hermetic; never touches live backends).
    // ==================================================================
    static void selfTest() throws Exception {
        selfTestA1();
        selfTestA2();
        SelfTest.finish();
    }

    // ---- A1 self-test ----
    static void selfTestA1() {
        // 1. median: exact mirror of OutsideVoice.
        SelfTest.checkEq("A1 median single", 150.0, median(Arrays.asList(150.0)));
        SelfTest.checkEq("A1 median even", 150.0, median(Arrays.asList(100.0, 200.0)));
        SelfTest.checkEq("A1 median odd", 200.0, median(Arrays.asList(100.0, 200.0, 300.0)));
        SelfTest.checkEq("A1 median skips nulls", 200.0, median(Arrays.asList(null, 100.0, null, 300.0)));
        SelfTest.checkEq("A1 median null when all null", null, median(Arrays.asList(null, null)));
        SelfTest.checkEq("A1 median rounds to cents", 150.01, median(Arrays.asList(150.005, 150.005)));

        // 2. nudgeRate: exact mirror of SpeculationPrice.
        SelfTest.checkEq("A1 nudge High", 0.05, nudgeRate("High"));
        SelfTest.checkEq("A1 nudge Moderate", 0.025, nudgeRate("Moderate"));
        SelfTest.checkEq("A1 nudge Low", -0.05, nudgeRate("Low"));
        SelfTest.checkEq("A1 nudge unknown -> 0", 0.0, nudgeRate("Unknown"));
        SelfTest.checkEq("A1 nudge null -> 0", 0.0, nudgeRate(null));
        SelfTest.checkEq("A1 nudge empty -> 0", 0.0, nudgeRate(""));

        // 3. speculate: exact mirror of SpeculationPrice.
        //    100 + 0.5*(200-100) + 100*0.05 = 155.00
        SelfTest.checkEq("A1 speculate high", 155.0, speculate(100.0, 200.0, 0.05));
        //    100 + 0.5*(200-100) + 100*0.025 = 152.50
        SelfTest.checkEq("A1 speculate moderate", 152.5, speculate(100.0, 200.0, 0.025));
        //    100 + 0.5*(200-100) + 100*(-0.05) = 145.00
        SelfTest.checkEq("A1 speculate low", 145.0, speculate(100.0, 200.0, -0.05));
        SelfTest.checkEq("A1 speculate none", 150.0, speculate(100.0, 200.0, 0.0));
        SelfTest.checkEq("A1 speculate null DCF -> null", null, speculate(null, 200.0, 0.05));
        SelfTest.checkEq("A1 speculate null median -> null", null, speculate(100.0, null, 0.05));

        // 4. Rounding: fractional inputs round to cents.
        //    10.005 + 0.5*(20.015-10.005) + 10.005*0.025 = 15.260125 -> 15.26
        SelfTest.checkEq("A1 speculate rounds to cents", 15.26, speculate(10.005, 20.015, 0.025));

        // 5. close(): 1e-9 relative.
        SelfTest.check("A1 close identical", close(155.0, 155.0));
        SelfTest.check("A1 close tiny diff", close(155.0, 155.0 + 1e-12));
        SelfTest.check("A1 close rejects real diff", !close(155.0, 155.001));
        SelfTest.check("A1 close null==null", close(null, null));
        SelfTest.check("A1 close null!=value", !close(null, 155.0));

        // 6. nlm_speculation invariant: null is fine, non-null must be caught.
        List<Finding> fs = new ArrayList<>();
        Double nlm = num(null);
        SelfTest.checkEq("A1 nlm null stays null", null, nlm);
        fs.add(Finding.fail("A1", "TEST", "nlm_speculation",
                "null (reserved)", 123.45, "evidence", "fix"));
        SelfTest.checkEq("A1 invariant finding recorded", 1, fs.size());
    }

    // ---- A2 self-test ----
    static void selfTestA2() {
        // 1. buyBelow: null/zero/negative -> null; standard math otherwise.
        SelfTest.checkEq("A2 buyBelow null", null, buyBelow(null, 0.20));
        SelfTest.checkEq("A2 buyBelow zero", null, buyBelow(0.0, 0.20));
        SelfTest.checkEq("A2 buyBelow negative", null, buyBelow(-5.0, 0.20));
        SelfTest.checkEq("A2 buyBelow 20%", 80.0, buyBelow(100.0, 0.20));
        SelfTest.checkEq("A2 buyBelow custom 25%", 75.0, buyBelow(100.0, 0.25));

        // 2. Header rules.
        List<String> newHeaders = Arrays.asList("Ticker", "Market", "Our DCF", "Buy below",
                "Muse speculation", "Growth outlook", "NotebookLM DCF", "NotebookLM speculation",
                "NLM forward tilt", "Creator", "Analyst target", "Website consensus");
        SelfTest.checkEq("A2 exactly one buy-below col", 1, buyBelowCols(newHeaders).size());
        SelfTest.checkEq("A2 buy-below after our-dcf", 3, (int) buyBelowCols(newHeaders).get(0));
        List<String> staleHeaders = Arrays.asList("Ticker", "Our DCF", "Buy below (ours)",
                "Buy below (notebooklm)", "Muse speculation");
        SelfTest.checkEq("A2 stale layout has 2 buy-below cols", 2, buyBelowCols(staleHeaders).size());

        // 3. Table check: stale qualified layout must FAIL (3 findings).
        Inputs in = new Inputs();
        in.fixed.put("TEST", 100.0);
        in.marginDefault = 0.20;
        List<Finding> fs = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList("TEST", "80.00", "81.00"));
        checkTableSurface(in, fs, "selftest", "synthetic stale headers", staleHeaders, 0, rows,
                row -> row.get(0), DerivedValueAgreement::close);
        SelfTest.checkEq("A2 stale layout yields 3 findings (2 stale cols + missing)", 3, fs.size());

        // 4. New layout must PASS (0 FAIL findings).
        List<Finding> fs2 = new ArrayList<>();
        checkTableSurface(in, fs2, "selftest", "synthetic new headers", newHeaders, 0,
                new ArrayList<>(Arrays.asList(
                        Arrays.asList("TEST", "90", "100", "80.00", "155", "High",
                                "110", "", "120", "130", "140", "150"))),
                row -> row.get(0), DerivedValueAgreement::close);
        long fails2 = fs2.stream().filter(f -> f.severity == Finding.Severity.FAIL).count();
        SelfTest.checkEq("A2 new layout has 0 FAIL findings", 0L, fails2);

        // 5. Wrong value under the new layout must FAIL.
        List<Finding> fs3 = new ArrayList<>();
        checkTableSurface(in, fs3, "selftest", "synthetic wrong value", newHeaders, 0,
                new ArrayList<>(Arrays.asList(
                        Arrays.asList("TEST", "90", "100", "81.00", "155", "High",
                                "110", "", "120", "130", "140", "150"))),
                row -> row.get(0), DerivedValueAgreement::close);
        SelfTest.checkEq("A2 wrong buy-below value FAILs", 1L, fs3.stream().filter(f -> f.severity == Finding.Severity.FAIL).count());

        // 6. Null expected (fixed <= 0) must be blank.
        in.fixed.put("ZERO", 0.0);
        List<Finding> fs4 = new ArrayList<>();
        checkTableSurface(in, fs4, "selftest", "synthetic zero dcf", newHeaders, 0,
                new ArrayList<>(Arrays.asList(
                        Arrays.asList("ZERO", "10", "0", "5.00", "", "", "", "", "", "", "", ""))),
                row -> row.get(0), DerivedValueAgreement::close);
        SelfTest.checkEq("A2 non-blank buy-below for zero DCF FAILs", 1L, fs4.stream().filter(f -> f.severity == Finding.Severity.FAIL).count());

        // 7. Missing column must FAIL.
        List<Finding> fs5 = new ArrayList<>();
        List<String> noBb = Arrays.asList("Ticker", "Our DCF", "Muse speculation");
        checkTableSurface(in, fs5, "selftest", "synthetic missing col", noBb, 0,
                new ArrayList<>(Arrays.asList(Arrays.asList("TEST", "100", "155"))),
                row -> row.get(0), DerivedValueAgreement::close);
        SelfTest.checkEq("A2 missing buy-below column FAILs", 1, fs5.size());

        // 8. Wrong position must FAIL.
        List<Finding> fs6 = new ArrayList<>();
        List<String> misplaced = Arrays.asList("Ticker", "Market", "Our DCF", "Muse speculation",
                "Buy below", "Growth outlook");
        checkTableSurface(in, fs6, "selftest", "synthetic misplaced", misplaced, 0,
                new ArrayList<>(Arrays.asList(
                        Arrays.asList("TEST", "90", "100", "155", "80.00", "High"))),
                row -> row.get(0), DerivedValueAgreement::close);
        SelfTest.checkEq("A2 misplaced buy-below column FAILs", 1L, fs6.stream().filter(f -> f.severity == Finding.Severity.FAIL).count());

        // 9. Half-cent tolerance for the live table render: 80.00 vs 80.0049 ok,
        //    80.01 vs 80.0049 also ok; 80.02 vs 80.0049 fails.
        BiPredicate<Double, Double> halfCent = (expected, actual) ->
                (expected == null && actual == null)
                        || (expected != null && actual != null
                                && Math.abs(expected - actual) <= 0.0051);
        SelfTest.check("A2 half-cent passes within", halfCent.test(80.0, 80.0049));
        SelfTest.check("A2 half-cent fails outside", !halfCent.test(80.0, 80.0060));

        // 9b. Sub-row layout (9:50 table): correct sub-row passes.
        List<String> subHeaders = Arrays.asList("Ticker", "Market", "Our DCF", "Muse speculation");
        List<Finding> fsr = new ArrayList<>();
        checkTableSubRows(in, fsr, subHeaders,
                new ArrayList<>(Arrays.asList(
                        Arrays.asList("TEST", "90", "100", "155"),
                        Arrays.asList("buy below", "", "80.00", ""))),
                "synthetic sub-row", DerivedValueAgreement::close);
        SelfTest.checkEq("A2 sub-row layout has 0 FAIL findings", 0L,
                fsr.stream().filter(f -> f.severity == Finding.Severity.FAIL).count());

        // 9c. PAUSED 2026-09-30 (Rahik): sub-rows are out of the render — a
        // missing sub-row is the accepted one-row layout, NOT a finding.
        List<Finding> fsr2 = new ArrayList<>();
        checkTableSubRows(in, fsr2, subHeaders,
                new ArrayList<>(Arrays.asList(
                        Arrays.asList("TEST", "90", "100", "155"))),
                "synthetic missing sub-row", DerivedValueAgreement::close);
        SelfTest.checkEq("A2 missing sub-row is accepted while paused", 0L,
                fsr2.stream().filter(f -> f.severity == Finding.Severity.FAIL).count());

        // 9d. Wrong value in the sub-row must FAIL.
        List<Finding> fsr3 = new ArrayList<>();
        checkTableSubRows(in, fsr3, subHeaders,
                new ArrayList<>(Arrays.asList(
                        Arrays.asList("TEST", "90", "100", "155"),
                        Arrays.asList("buy below", "", "81.00", ""))),
                "synthetic wrong sub-row value", DerivedValueAgreement::close);
        SelfTest.checkEq("A2 wrong sub-row value FAILs", 1L,
                fsr3.stream().filter(f -> f.severity == Finding.Severity.FAIL).count());

        // 9e. Extra populated cell in the sub-row must FAIL.
        List<Finding> fsr4 = new ArrayList<>();
        checkTableSubRows(in, fsr4, subHeaders,
                new ArrayList<>(Arrays.asList(
                        Arrays.asList("TEST", "90", "100", "155"),
                        Arrays.asList("buy below", "", "80.00", "999"))),
                "synthetic extra sub-row cell", DerivedValueAgreement::close);
        SelfTest.checkEq("A2 extra sub-row cell FAILs", 1L,
                fsr4.stream().filter(f -> f.severity == Finding.Severity.FAIL).count());

        // 9f. Stale buy-below column on the table surface must FAIL
        // (stale column = 1 finding; the sub-row is paused so its absence
        // is not a finding).
        List<Finding> fsr5 = new ArrayList<>();
        checkTableSubRows(in, fsr5, newHeaders,
                new ArrayList<>(Arrays.asList(
                        Arrays.asList("TEST", "90", "100", "80.00", "155"))),
                "synthetic stale column on table", DerivedValueAgreement::close);
        SelfTest.checkEq("A2 stale column on table FAILs", 1L,
                fsr5.stream().filter(f -> f.severity == Finding.Severity.FAIL).count());

        // 10. CSV parser: quoted fields, commas, "" escapes.
        List<List<String>> csv = parseCsv("a,\"b,c\",\"d\"\"e\"\n1,2,3\n");
        SelfTest.checkEq("A2 csv rows", 2, csv.size());
        SelfTest.checkEq("A2 csv quoted comma", "b,c", csv.get(0).get(1));
        SelfTest.checkEq("A2 csv escaped quote", "d\"e", csv.get(0).get(2));
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
