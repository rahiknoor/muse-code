import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One-command rebuild of every JSON-backed derived file (idea #34).
 *
 * <p>Java port of {@code dcf_csv/rebuild_all.py}.
 *
 * <p>Usage:
 * <pre>
 *   java RebuildAll.java          # full rebuild
 *   java RebuildAll.java --check  # diff-only: report what would change, write nothing
 * </pre>
 *
 * <p>Run this after ANY backend change (new filing inputs, new analyst targets,
 * creator call, price-cache refresh) and the derived files can never disagree.
 *
 * <p>Fail-closed gates: verify_base.py guards DCF base inputs at write time
 * (idea #32); {@link #guardNotebooklmValues} refuses to publish any NotebookLM
 * value lacking a verified batch record.
 *
 * <p>Steps that belong to other scripts still run as subprocesses, exactly like
 * the Python version: the converted creator step (BuildCreatorValues.java),
 * your_files/dcf-valuations/.src/generate_31_company_dcf.py, and the converted
 * atlas steps (RefreshValuations.java, GenerateValuationDashboard.java).
 * (The Python original called
 * creator_tracker/build_creator_values.py, atlas/refresh_valuations.py and
 * atlas/generate_valuation_dashboard.py, but those scripts have since been
 * converted to Java and the .py files renamed to .bak, so the Java steps are
 * wired here. Child processes inherit $HOME and additionally receive
 * DCF_WORKSPACE_ROOT; javaBin() reuses this JVM for children.)
 */
public class RebuildAll {

    /**
     * Home directory. IMPORTANT: this runtime's {@code user.home} system property
     * returns {@code /root} (the passwd entry) while {@code $HOME} is
     * {@code /home/hatch}. Python's {@code os.path.expanduser("~/...")} honors
     * {@code $HOME}, so Java must too -- never use
     * {@code System.getProperty("user.home")} directly for workspace paths.
     */
    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /** Workspace root. Overridable with the DCF_WORKSPACE_ROOT env var for testing. */
    static String workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        return (e != null && !e.isEmpty()) ? e : homeDir() + "/workspace";
    }

    static {
        // Python writes UTF-8 to stdout regardless of locale (PEP 686); this JVM
        // may run under a POSIX/ASCII locale, so set UTF-8 explicitly to match.
        try {
            System.setOut(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            // fall back to the default stdout
        }
    }

    static String ROOT;
    static boolean CHECK = false;
    static final List<String> CHANGED = new ArrayList<>();

    static String WD, COMP, COMP_XLSX, ASSUMP, OUR, NBF, FWD, GO, OV, SP, WEBC, MARGIN, PC, ANALYST, CREATOR, SB,
            NB_BATCH, FWD_BATCH, DIS_CSV, SRC_CSV, HIST_CSV;

    /**
     * Idea #40: the derived-file stamp. Every file this rebuild regenerates
     * carries it — as a {@code _derived} field in JSON, inside the title
     * row of CSVs, in the workbook's document properties — and
     * {@code DerivedFiles.java --check-inputs} refuses any file that has
     * it when a job tries to use it as an input. Same literal as
     * {@code DerivedFiles.STAMP}: keep them in sync. {@code \u2014} is the
     * em dash, escaped so this source file stays pure ASCII and compiles
     * under any locale.
     */
    static final String DERIVED_STAMP =
            "derived from workbook_data.json via RebuildAll \u2014 do not edit, do not read as input";

    /**
     * Idea #40: stamp the title row of a derived CSV so a future worker can
     * see at a glance that the file must never be used as a rebuild input.
     * Readers (RefreshValuations, RecomputeColumns) rely on the 3-row shape
     * (two title rows, header at row index 2), so the stamp goes INSIDE the
     * existing title cell — no rows are added, none are moved.
     */
    static void stampCsvTitle(List<List<String>> rows, String title) {
        rows.get(0).set(0, title + " \u2014 " + DERIVED_STAMP);
    }

    static void initPaths() {
        WD = ROOT + "/your_files/dcf-valuations/.src/workbook_data.json";
        COMP = ROOT + "/dcf_csv/dcf-comparison.csv";
        COMP_XLSX = ROOT + "/dcf_csv/dcf-comparison.xlsx";
        ASSUMP = ROOT + "/dcf_csv/dcf-assumptions.csv";
        OUR = ROOT + "/dcf_csv/our_dcf.json";
        NBF = ROOT + "/dcf_csv/notebooklm_values.json";
        FWD = ROOT + "/dcf_csv/notebooklm_forward.json";
        GO = ROOT + "/dcf_csv/growth_outlook.json";
        OV = ROOT + "/dcf_csv/outside_voice.json";
        SP = ROOT + "/dcf_csv/speculation_price.json";
        WEBC = ROOT + "/dcf_csv/website_consensus.json";
        MARGIN = ROOT + "/dcf_csv/margin_requirements.json";
        PC = ROOT + "/stock_watch/price_cache.json";
        ANALYST = ROOT + "/dcf_csv/analyst_targets.json";
        CREATOR = ROOT + "/dcf_csv/creator_values.json";
        SB = ROOT + "/valuation_scoreboard/scoreboard.json";
        NB_BATCH = ROOT + "/goals/quarterly-sec-filing-watch-with-dcf-updates"
                + "/hidden_files/notebooklm_dcf_batch_2026-09-26.json";
        FWD_BATCH = ROOT + "/goals/quarterly-sec-filing-watch-with-dcf-updates"
                + "/hidden_files/notebooklm_forward_batch_2026-09-28.json";
        DIS_CSV = ROOT + "/dcf_csv/dcf-disagreements.csv";
        SRC_CSV = ROOT + "/dcf_csv/dcf-sources.csv";
        HIST_CSV = ROOT + "/dcf_csv/dcf-history.csv";
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            if (a.equals("--check")) {
                CHECK = true;
            }
        }
        ROOT = workspaceRoot();
        initPaths();
        note("== rebuild_all.py (idea #34) ==" + (CHECK ? " [--check]" : ""));
        // Audit write guard (Rahik 2026-09-27): fail loudly if any blessed value
        // drifted without an approved proposal. Background jobs may only PROPOSE
        // changes (Proposals.java file); the auditor reviews and approves/rejects.
        // A silent rewrite can never reach the workbook again. Skipped in --check
        // mode by runStep itself.
        runStep("blessed guard",
                new String[]{javaBin(), ROOT + "/dcf_csv/Proposals.java", "verify"});
        // Methodology check (Rahik 2026-09-27): fail loudly if the methodology
        // doc (what NotebookLM reads) no longer describes the formulas in
        // DcfModel.java (what actually computes). DETECTION ONLY — this tool
        // never auto-edits the doc; a mismatch stops the rebuild so a human
        // decides the repair direction in DCF Model Lab.
        runStep("methodology check",
                new String[]{javaBin(), "--class-path",
                        homeDir() + "/workspace/java-shared/classes",
                        ROOT + "/dcf_csv/MethodologyCheck.java", "--doc-code"});
        // Forward-tilt compliance gate (Rahik 2026-10-09 "do both", audit-12):
        // the traceability guard (guardForwardValues) proves a value exists in
        // the batch file; this gate proves it obeys the +2pp tilt cap and the
        // other audit-12 bounds (recompute in code, 10% hard gate, 15% review
        // backstop, citations, no negative tilt, trough-stacking rule).
        // Fail-closed: any violation aborts the rebuild before any backend is
        // touched. Runs as a compiled class (dcf_csv/build.sh) because it
        // needs package-private DcfModel.Json access, which breaks across the
        // single-file launcher's classloader.
        runStep("forward-tilt compliance gate",
                new String[]{javaBin(), "--class-path",
                        homeDir() + "/workspace/dcf_csv/classes"
                                + java.io.File.pathSeparator
                                + homeDir() + "/workspace/java-shared/classes",
                        "ForwardComplianceGate", "--check"});
        // Idea #46: snapshot the published values BEFORE any step runs, so the
        // post-rebuild report can say what moved. Fail-safe: a snapshot failure
        // only disables the diff for this run, never the rebuild.
        RebuildDiff.Snapshot diffBefore = null;
        if (!CHECK) {
            try {
                diffBefore = RebuildDiff.takeSnapshot();
            } catch (Exception e) {
                note("  [diff] pre-rebuild snapshot unavailable: " + e.getMessage());
            }
        }
        runStep("creator values",
                new String[]{javaBin(), ROOT + "/creator_tracker/BuildCreatorValues.java"});
        // NOTE (Rahik 2026-09-27): NO auto-rebless here. The creator column's
        // designated writers (the morning digest STEP 5b, the hourly buy-call
        // alerts STEP 8) re-bless their own output right after writing it.
        // A rebuild that silently blessed whatever it found would let any
        // background writer approve its own changes — the exact bypass the
        // audit write guard exists to prevent. If creator_values.json drifts
        // without a re-bless, the pre-gate above fails loudly on the next run.
        // Speculation architecture (Rahik 2026-09-29): rebuild the outside
        // median and the Muse speculation price BEFORE any consumer reads
        // them. OutsideVoice medians come from the creator / analyst /
        // website backends; SpeculationPrice computes only the Muse voice
        // (NotebookLM's slot is reserved — populated only from a recorded,
        // verified, blessed NotebookLM response, never computed here).
        runStep("outside voice",
                new String[]{javaBin(), "--class-path",
                        homeDir() + "/workspace/java-shared/classes",
                        ROOT + "/dcf_csv/OutsideVoice.java", "--build"});
        runStep("speculation price",
                new String[]{javaBin(), "--class-path",
                        homeDir() + "/workspace/java-shared/classes",
                        ROOT + "/dcf_csv/SpeculationPrice.java", "--build"});
        note("[comparison csv]");
        buildComparisonCsv();
        // HistoricalCheck (Rahik's standing order): judge every live
        // assumption against how the company actually ran in history.
        // EVALUATION ONLY — reads the blessed ours assumptions and the
        // on-disk SEC companyfacts, writes only the derived history CSV.
        // Never changes blessed values or the audit guard.
        runStep("history check",
                new String[]{javaBin(), "--class-path",
                        homeDir() + "/workspace/java-shared/classes",
                        ROOT + "/dcf_csv/DcfHistory.java"});
        note("[json extracts]");
        extractJsons();
        note("[comparison xlsx]");
        buildXlsx();
        note("[assumptions csv]");
        buildAssumptionsCsv();
        note("[workbook data sync]");
        syncWorkbookData();
        runStep("workbook xlsx", new String[]{javaBin(), "-Dfile.encoding=UTF-8",
                "-cp", homeDir() + "/workspace/lib/poi/*",
                ROOT + "/your_files/dcf-valuations/.src/GenerateDcfWorkbook.java"});
        runStep("workbook pdf", new String[]{javaBin(), "-Dfile.encoding=UTF-8",
                "-cp", homeDir() + "/workspace/lib/poi/*",
                ROOT + "/dcf_csv/BakeWorkbookPdf.java"});
        note("[disagreements csv]");
        buildDisagreementsCsv();
        note("[sources csv]");
        buildSourcesCsv();
        note("[scoreboard sync]");
        syncScoreboard();
        runStep("atlas notes",
                new String[]{javaBin(), "--class-path",
                        homeDir() + "/workspace/java-shared/classes",
                        ROOT + "/atlas-scripts/RefreshValuations.java"});
        // BLG-029 (2026-10-10): chain GenerateStockSummaries immediately
        // after RefreshValuations — RefreshValuations updates note frontmatter
        // without re-running the summary generator, so summary prose lagged
        // frontmatter (BLG-026). GenerateStockSummaries is idempotent
        // (unchanged frontmatter produces no diff), so a full run here is
        // safe and keeps the ## Summary/## Story blocks in sync on every
        // rebuild.
        runStep("stock summaries",
                new String[]{javaBin(), "--class-path",
                        homeDir() + "/workspace/java-shared/classes",
                        ROOT + "/atlas-scripts/GenerateStockSummaries.java"});
        runStep("atlas dashboard",
                new String[]{javaBin(), "--class-path",
                        homeDir() + "/workspace/java-shared/classes",
                        ROOT + "/atlas-scripts/GenerateValuationDashboard.java"});
        if (CHECK) {
            noteClassification();
        }
        // Idea #46: write the "what changed" report for the DCF Files post.
        // Fail-safe: the diff can never break a rebuild — on any error the
        // rebuild still completes and the report says it was unavailable.
        if (!CHECK) {
            try {
                RebuildDiff.writeReport(diffBefore);
            } catch (Exception e) {
                note("  [diff] unavailable this run: " + e.getMessage());
            }
        }
        // Derived-value agreement gate (audit gaps A1+A2, merged per Rahik's
        // combine rule 2026-09-29: one verifier, two check sections). Every
        // derived number must agree with its inputs: the Muse speculation
        // price recomputed from the backend-only outside median + growth
        // nudge, and exactly one Buy below — on Our DCF only — agreeing
        // across every surface. NotebookLM speculation must stay
        // null/provenance-reserved. Fail-closed: a failure exits the whole
        // rebuild 1, same as the propagation gate below.
        if (!CHECK) {
            runStep("derived-value agreement",
                    new String[]{javaBin(), "--class-path",
                            homeDir() + "/workspace/java-shared/classes:"
                                    + homeDir() + "/workspace/lib/poi/*",
                            ROOT + "/dcf_csv/DerivedValueAgreement.java", "--verify"});
        }
        // NotebookLM batch reverse guard (audit gap A3): every value in the
        // verified NotebookLM batch file must be blessed at the same number
        // or carry documented pending/rejected state. Fail-closed.
        if (!CHECK) {
            runStep("notebooklm batch reverse guard",
                    new String[]{javaBin(), "--class-path",
                            homeDir() + "/workspace/java-shared/classes",
                            ROOT + "/dcf_csv/BatchGuardReverse.java", "--verify"});
        }
        // Propagation GATE (Rahik 2026-09-28, hard-wired: "hard write it on
        // your code"). The rebuild is NOT done until every consumer is
        // current. PropagationCheck exits 1 with the exact pending legs when
        // anything is stale, and runStep exits the whole rebuild 1 on any
        // child failure — deliberately NO try/catch here. A failed gate is a
        // failed rebuild, not a note. Recovery: do each listed agent leg
        // (browser sync + MethodologyCheck --record-upload, artifact.edit +
        // --stamp, chat post + --stamp), then re-run
        // `java PropagationCheck.java` until it exits 0.
        if (!CHECK) {
            runStep("propagation gate",
                    new String[]{javaBin(), ROOT + "/dcf_csv/PropagationCheck.java"});
        }
        note("== done ==" + (CHECK ? " (" + CHANGED.size() + " files would change)" : ""));
        if (CHECK && !CHANGED.isEmpty()) {
            System.exit(2);
        }
    }

    /**
     * Idea #40: in --check mode, print the input/derived classification so a
     * worker can see at a glance which files are genuine inputs and which are
     * regenerated outputs. The enforcing guard is
     * {@code DerivedFiles.java --check-inputs}; this is the human-readable
     * map of the same idea.
     */
    static void noteClassification() {
        note("  [input/derived classification — idea #40]");
        note("  genuine inputs (never stamped; safe to read as inputs):");
        note("    " + WD);
        note("    " + ROOT + "/sec_filings/dcf_inputs_all.json");
        note("    " + NB_BATCH);
        note("    " + FWD_BATCH);
        note("    " + PC);
        note("    " + ANALYST);
        note("    " + OV);
        note("    " + SP);
        note("    " + WEBC);
        note("  derived outputs (stamped; never use as rebuild inputs):");
        note("    " + CREATOR + "  " + OUR + "  " + NBF + "  " + FWD);
        note("    " + COMP + "  " + COMP_XLSX);
        note("    " + ASSUMP + "  " + DIS_CSV + "  " + SRC_CSV + "  " + SB);
        note("    " + HIST_CSV);
        note("  RebuildAll regenerates the derived set from the genuine inputs;");
        note("  nothing else may treat a derived file as an input (DerivedFiles.java).");
    }

    /** The java launcher for child single-file runs (same JDK as this process). */
    static String javaBin() {
        return System.getProperty("java.home") + "/bin/java";
    }

    static void note(String msg) {
        System.out.println(msg);
    }

    static void writeText(String path, String text) throws Exception {
        if (CHECK) {
            Path p = Paths.get(path);
            String cur = Files.exists(p)
                    ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : "";
            // Parity quirk: Python's Path.read_text() applies universal newlines,
            // so CRLF in the file becomes LF before comparison, while the freshly
            // rendered text keeps CRLF. That makes the Python --check always flag
            // CRLF files as "would change", even when the bytes are identical.
            // We replicate that behavior exactly (normalize the same way).
            cur = cur.replace("\r\n", "\n").replace("\r", "\n");
            if (!cur.equals(text)) {
                CHANGED.add(path);
                note("  would change: " + path);
            } else {
                note("  unchanged: " + path);
            }
            return;
        }
        Files.writeString(Paths.get(path), text, StandardCharsets.UTF_8);
    }

    /** Drain a process stream in the background (avoids pipe-buffer deadlock). */
    static class Gobbler extends Thread {
        final InputStream in;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Gobbler(InputStream in) {
            this.in = in;
        }

        public void run() {
            try {
                in.transferTo(buf);
            } catch (IOException e) {
                // ignore
            }
        }
    }

    static void runStep(String name, String[] cmd) throws Exception {
        note("[" + name + "]");
        if (CHECK) {
            note("  (skipped in --check mode: " + String.join(" ", cmd) + ")");
            return;
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Paths.get(ROOT + "/dcf_csv").toFile());
        // Propagate the workspace root so the child Java step resolves the same tree.
        pb.environment().put("DCF_WORKSPACE_ROOT", ROOT);
        // Ensure UTF-8 for child steps (emoji in Atlas vault paths, etc.).
        pb.environment().put("LC_ALL", "C.UTF-8");
        Process p = pb.start();
        Gobbler gout = new Gobbler(p.getInputStream());
        Gobbler gerr = new Gobbler(p.getErrorStream());
        gout.start();
        gerr.start();
        int rc = p.waitFor();
        gout.join();
        gerr.join();
        String out = new String(gout.buf.toByteArray(), StandardCharsets.UTF_8);
        String err = new String(gerr.buf.toByteArray(), StandardCharsets.UTF_8);
        if (rc != 0) {
            System.out.println(lastChars(out, 2000));
            System.out.println(lastChars(err, 2000));
            System.err.println("step failed: " + name);
            System.exit(1);
        }
        List<String> lines = splitlines(out.strip());
        int from = Math.max(0, lines.size() - 3);
        for (int i = from; i < lines.size(); i++) {
            note("  " + lines.get(i));
        }
    }

    static String lastChars(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    /** Python str.splitlines() semantics. */
    static List<String> splitlines(String s) {
        List<String> lines = new ArrayList<>();
        int i = 0, n = s.length(), start = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(s.substring(start, i));
                i++;
                if (c == '\r' && i < n && s.charAt(i) == '\n') {
                    i++;
                }
                start = i;
            } else {
                i++;
            }
        }
        if (start < n) {
            lines.add(s.substring(start));
        }
        return lines;
    }

    /** Loaded backends: workbook companies, price quotes, analyst targets, creator calls. */
    static class Backends {
        Json.Obj companies; // parsed workbook_data.json (kept for reuse)
        List<Json.Val> wd;
        Json.Obj pc;
        Json.Obj an;
        Json.Obj cr;
        Json.Obj go; // growth_outlook.json tickers (Muse's growth assessment)
        Json.Obj ov; // outside_voice.json tickers (raw outside median)
        Json.Obj sp; // speculation_price.json tickers (per-voice speculation)
        Json.Obj web; // website_consensus.json tickers (website DCF consensus)
        Json.Obj marginTickers; // margin_requirements.json per-ticker margins
        double marginDefault; // default required margin (0.20)
    }

    /**
     * Read the per-ticker price map from the shared price cache. Current
     * writers use {"prices": {...}}; older files used {"quotes": {...}}.
     * Mirrors DailyClosesPipeline's dual-shape handling (2026-09-28:
     * the closes job rewrote the cache with "prices", which broke the
     * old "quotes"-only read here with an NPE).
     */
    static Json.Obj readPriceQuotes() throws Exception {
        Json.Obj doc = (Json.Obj) Json.parse(readFile(PC));
        Json.Obj q = doc.getObj("prices");
        if (q == null) q = doc.getObj("quotes");
        return q == null ? new Json.Obj() : q;
    }

    static Backends load() throws Exception {
        Backends b = new Backends();
        b.companies = (Json.Obj) Json.parse(readFile(WD));
        b.wd = b.companies.getList("companies");
        b.pc = readPriceQuotes();
        b.an = (Json.Obj) Json.parse(readFile(ANALYST));
        Json.Obj crAll = (Json.Obj) Json.parse(readFile(CREATOR));
        b.cr = crAll.getObj("latest");
        if (b.cr == null) {
            b.cr = new Json.Obj();
        }
        // Growth outlook is read from its canonical backend directly (the
        // workbook_data.json sync for it runs later in the pipeline; the
        // comparison CSV must not lag one run behind on a first-time sync).
        Json.Obj goAll = (Json.Obj) Json.parse(readFile(GO));
        b.go = goAll.getObj("tickers");
        if (b.go == null) {
            b.go = new Json.Obj();
        }
        // Speculation backends are read from their canonical files directly
        // (same reason as growth outlook: the workbook_data.json sync runs
        // later in the pipeline; the comparison CSV must not lag one run
        // behind). outside_voice.json carries the raw outside median;
        // speculation_price.json carries the two per-voice speculation prices.
        Json.Obj ovAll = (Json.Obj) Json.parse(readFile(OV));
        b.ov = ovAll.getObj("tickers");
        if (b.ov == null) {
            b.ov = new Json.Obj();
        }
        Json.Obj spAll = (Json.Obj) Json.parse(readFile(SP));
        b.sp = spAll.getObj("tickers");
        if (b.sp == null) {
            b.sp = new Json.Obj();
        }
        Json.Obj webAll = (Json.Obj) Json.parse(readFile(WEBC));
        b.web = webAll.getObj("tickers");
        if (b.web == null) {
            b.web = new Json.Obj();
        }
        // Required margin of safety per ticker (Rahik 2026-09-29: buy-below
        // for every source). margin_requirements.json: {"default": 0.20,
        // "tickers": {T: <margin>}}.
        b.marginDefault = 0.20;
        b.marginTickers = new Json.Obj();
        try {
            Json.Obj mAll = (Json.Obj) Json.parse(readFile(MARGIN));
            Json.Val def = mAll.get("default");
            if (def instanceof Json.Num) b.marginDefault = ((Json.Num) def).d;
            Json.Obj mt = mAll.getObj("tickers");
            if (mt != null) b.marginTickers = mt;
        } catch (Exception e) {
            note("  margin requirements unavailable (" + e.getMessage()
                    + ") — using default 20%");
        }
        guardNotebooklmValues(b.wd);
        guardForwardValues(b.wd);
        return b;
    }

    /** Required margin for one ticker: per-ticker override or the default. */
    static double reqMargin(Backends b, String ticker) {
        Json.Val v = b.marginTickers.get(ticker);
        return (v instanceof Json.Num) ? ((Json.Num) v).d : b.marginDefault;
    }

    /** Buy-below cell (Rahik 2026-09-29): value x (1 - required margin);
     *  "—" when the value is null or <= 0. Display only — gap-watch
     *  alerting stays on the base our DCF. */
    static String dispBuyBelow(Double v, double req) {
        if (v == null || v <= 0) return "\u2014";
        return disp(v * (1.0 - req));
    }

    /**
     * Fail-closed traceability gate: every non-null NotebookLM value in the backend
     * must exist as a numeric value in the verified batch file. A missing batch
     * file also aborts: no batch, no NotebookLM column.
     */
    static void guardNotebooklmValues(List<Json.Val> companies) throws Exception {
        Json.Obj batch;
        try {
            batch = (Json.Obj) ((Json.Obj) Json.parse(readFile(NB_BATCH))).get("values");
        } catch (Exception e) {
            System.err.println("notebooklm guard: cannot read verified batch "
                    + NB_BATCH + ": " + e + " \u2014 refusing rebuild");
            System.exit(1);
            return;
        }
        List<String> verified = new ArrayList<>();
        for (Map.Entry<String, Json.Val> e : batch.map.entrySet()) {
            if (e.getValue() instanceof Json.Num) {
                verified.add(e.getKey());
            }
        }
        List<String> bad = new ArrayList<>();
        for (Json.Val cv : companies) {
            Json.Obj c = (Json.Obj) cv;
            Json.Val nb = c.get("notebooklm");
            if (nb != null && !(nb instanceof Json.Null)
                    && !verified.contains(c.getStr("ticker"))) {
                bad.add(c.getStr("ticker"));
            }
        }
        if (!bad.isEmpty()) {
            System.err.println("notebooklm guard: " + bad.size()
                    + " backend value(s) lack a verified batch record and would be "
                    + "published as NotebookLM's: " + String.join(", ", bad) + ". Null them "
                    + "in workbook_data.json or add the batch record first \u2014 refusing rebuild.");
            System.exit(1);
        }
    }

    /**
     * Fail-closed traceability gate for the forward-tilt column: every non-null
     * companies[].notebooklm_forward value must exist as a numeric value in the
     * forward verified batch file. Same rule as the grounded NotebookLM guard —
     * the two voices have separate batch files and separate provenance, so a
     * grounded verification can never authorize a forward-tilt value.
     */
    static void guardForwardValues(List<Json.Val> companies) throws Exception {
        Json.Obj batch;
        try {
            batch = (Json.Obj) ((Json.Obj) Json.parse(readFile(FWD_BATCH))).get("values");
        } catch (Exception e) {
            System.err.println("forward guard: cannot read verified batch "
                    + FWD_BATCH + ": " + e + " \u2014 refusing rebuild");
            System.exit(1);
            return;
        }
        List<String> verified = new ArrayList<>();
        for (Map.Entry<String, Json.Val> e : batch.map.entrySet()) {
            if (e.getValue() instanceof Json.Num) {
                verified.add(e.getKey());
            }
        }
        List<String> bad = new ArrayList<>();
        for (Json.Val cv : companies) {
            Json.Obj c = (Json.Obj) cv;
            Json.Val fw = c.get("notebooklm_forward");
            if (fw != null && !(fw instanceof Json.Null)
                    && !verified.contains(c.getStr("ticker"))) {
                bad.add(c.getStr("ticker"));
            }
        }
        if (!bad.isEmpty()) {
            System.err.println("forward guard: " + bad.size()
                    + " backend value(s) lack a verified forward batch record and would be "
                    + "published as NotebookLM forward-tilt: " + String.join(", ", bad) + ". Null them "
                    + "in workbook_data.json or add the forward batch record first \u2014 refusing rebuild.");
            System.exit(1);
        }
    }

    static String readFile(String path) throws Exception {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    /**
     * Coerce to Double; non-numeric strings (e.g. a 'refusal ...' rate-limit artifact
     * left in workbook_data.json) count as missing, matching the standing
     * 'missing = \u2014' rule and the current CSV.
     */
    static Double cleanNum(Json.Val v) {
        if (v == null || v instanceof Json.Null) {
            return null;
        }
        if (v instanceof Json.Bool) {
            return ((Json.Bool) v).v ? 1.0 : 0.0;
        }
        if (v instanceof Json.Num) {
            return ((Json.Num) v).d;
        }
        if (v instanceof Json.Str) {
            try {
                return Double.parseDouble(((Json.Str) v).v.replace(",", "").trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Display formatting that reproduces the current CSV byte-for-byte:
     * ints stay ints ('158'), floats keep repr ('147.0', '225.07'),
     * non-numeric strings -&gt; '\u2014' per the standing missing-data rule.
     */
    static String disp(Json.Val v) {
        if (v == null || v instanceof Json.Null) {
            return "\u2014";
        }
        if (v instanceof Json.Bool) {
            return ((Json.Bool) v).v ? "True" : "False";
        }
        if (v instanceof Json.Num) {
            Json.Num n = (Json.Num) v;
            return n.isInt ? Long.toString(n.l) : Py.repr(n.d);
        }
        if (v instanceof Json.Str) {
            try {
                return Py.repr(Double.parseDouble(((Json.Str) v).v.replace(",", "").trim()));
            } catch (NumberFormatException e) {
                return "\u2014";
            }
        }
        return "\u2014";
    }

    /** Display formatting for a computed double (avg / upside columns). */
    static String disp(Double d) {
        return d == null ? "\u2014" : Py.repr(d);
    }

    /**
     * Growth outlook cell for the comparison CSV: "High — <one-line
     * thesis>", or "not yet researched" when the ticker's deep-research
     * brief has not landed. Never a plausible fill.
     */
    static String dispGrowth(Json.Obj goTickers, String ticker) {
        Json.Obj ge = goTickers.getObj(ticker);
        String outlook = ge == null ? null : ge.optStr("outlook", null);
        if (outlook == null) {
            return "not yet researched";
        }
        String thesis = ge.optStr("thesis", null);
        return thesis == null || thesis.isEmpty() ? outlook
                : outlook + " \u2014 " + thesis;
    }

    /**
     * Muse speculation price cell (Rahik's 4-result design, 2026-09-29):
     * ours pulled halfway toward the outside median and nudged by the growth
     * outlook rate, from speculation_price.json. "—" when ours or the outside
     * median is missing — never a plausible fill. Display-only: never enters
     * the "Average of available numeric sources", no gap-watch, not a
     * blessed value. (Its buy-below is a separate display column.)
     */
    static String dispMuseSpec(Json.Obj spTickers, String ticker) {
        Json.Obj se = spTickers.getObj(ticker);
        if (se == null) {
            return "\u2014";
        }
        return disp(cleanNum(se.get("muse_speculation")));
    }

    /**
     * NotebookLM speculation price cell: RESERVED slot — always "—" until
     * NotebookLM itself derives it (recorded response -> verify -> bless).
     * This code never computes it (architecture rule, Rahik 2026-09-29).
     */
    static String dispNlmSpec(Json.Obj spTickers, String ticker) {
        Json.Obj se = spTickers.getObj(ticker);
        if (se == null) {
            return "\u2014";
        }
        return disp(cleanNum(se.get("nlm_speculation")));
    }

    /**
     * Website consensus cell: the median of the DCF-comparable website
     * values, from website_consensus.json. Its own column, never blended.
     */
    static String dispWebConsensus(Json.Obj webTickers, String ticker) {
        Json.Obj we = webTickers.getObj(ticker);
        if (we == null) {
            return "\u2014";
        }
        return disp(cleanNum(we.get("consensus")));
    }

    /**
     * Market-close column: integral floats render without decimals ('123'),
     * unlike every other numeric column ('147.0'). Quirky but observed in the
     * current file; reproduced exactly.
     */
    static String dispMarket(Json.Val v) {
        if (v == null || v instanceof Json.Null) {
            return "\u2014";
        }
        if (v instanceof Json.Bool) {
            return ((Json.Bool) v).v ? "True" : "False";
        }
        if (v instanceof Json.Num) {
            Json.Num n = (Json.Num) v;
            if (n.isInt) {
                return Long.toString(n.l);
            }
            if (n.d == Math.rint(n.d) && Double.isFinite(n.d)) {
                return Long.toString((long) n.d);
            }
            return Py.repr(n.d);
        }
        return disp(v);
    }

    /** Python str() of a JSON scalar. */
    static String scalarStr(Json.Val v) {
        if (v == null || v instanceof Json.Null) {
            return "None";
        }
        if (v instanceof Json.Bool) {
            return ((Json.Bool) v).v ? "True" : "False";
        }
        if (v instanceof Json.Str) {
            return ((Json.Str) v).v;
        }
        if (v instanceof Json.Num) {
            Json.Num n = (Json.Num) v;
            return n.isInt ? Long.toString(n.l) : Py.repr(n.d);
        }
        return "?";
    }

    /** Numeric equality across int/float JSON values (Python's ==). */
    static boolean numEq(Json.Val a, Json.Val b) {
        if (a == null || b == null || a instanceof Json.Null || b instanceof Json.Null) {
            return false;
        }
        if (a instanceof Json.Num && b instanceof Json.Num) {
            return ((Json.Num) a).d == ((Json.Num) b).d;
        }
        return a.equals(b);
    }

    static double numOf(Json.Val v) {
        return ((Json.Num) v).d;
    }

    /** Null-safe list access (Python's obj.get(key, [])). */
    static List<Json.Val> optList(Json.Obj o, String k) {
        Json.Val v = o.get(k);
        return v instanceof Json.Arr ? ((Json.Arr) v).items : new ArrayList<>();
    }

    static void buildComparisonCsv() throws Exception {
        Backends b = load();
        List<List<String>> old = Csv.read(readFile(COMP));
        List<List<String>> rows = new ArrayList<>(old.subList(0, 3)); // title rows + header
        stampCsvTitle(rows, "Valuation Comparison"); // idea #40: derived-file stamp
        // One Muse DCF number (the methodology-fixed value); the original-DCF
        // column is retired.
        // "Projection price (analyst target)" is the label Rahik recognizes
        // at a glance for the analyst-target column.
        // Column order (Rahik 2026-09-29 reshape, 12 columns): Ticker |
        // Market | Our DCF | Buy below (one column, on Our DCF — the number
        // the gap-watch trades on) | Muse speculation price | Growth outlook
        // | NotebookLM grounded | NotebookLM speculation price | Forward tilt
        // | Creator | Projection price (analyst target) | Website consensus.
        // No Outside voice column (the outside median lives in the backend as
        // the speculation input); speculation prices are display-only with no
        // buy-below. No Company column (ticker is the identifier, matching
        // ComparisonTable). No Creator buy price (lives in the Creator Buys
        // log). No Average (voices stay separate, never blended). No Upside
        // (excluded by the value-request rule). No >15% flag (both DCF values
        // are visible side-by-side). No Confidence/Key sensitivity (audit
        // fields, not comparison fields).
        rows.set(2, new ArrayList<>(List.of("Ticker",
                "Market close\n2026-09-25",
                "Our DCF", "Buy below",
                "Muse speculation price",
                "Growth outlook",
                "NotebookLM",
                "NotebookLM speculation price",
                "Forward tilt",
                "Creator\nvalues",
                "Projection price\n(analyst target)",
                "Website consensus")));
        for (Json.Val cv : b.wd) {
            Json.Obj c = (Json.Obj) cv;
            String t = c.getStr("ticker");
            Json.Obj q = b.pc.getObj(t);
            Json.Val rawMarket = q == null ? null : q.get("price");
            Json.Val rawDcf = c.get("fixed");
            Json.Val rawNb = c.get("notebooklm");
            Json.Val rawFwd = c.get("notebooklm_forward");
            Json.Obj ce = b.cr.getObj(t);
            Json.Val rawCreator = ce == null ? null : ce.get("value");
            Json.Obj ae = b.an.getObj(t);
            Json.Val rawAnalyst = ae == null ? null : ae.get("target");
            Double market = cleanNum(rawMarket);
            Double dcf = cleanNum(rawDcf);
            double req = reqMargin(b, t);
            List<String> row = new ArrayList<>();
            row.add(t);
            row.add(dispMarket(rawMarket));
            // Our DCF + the single Buy below, then Muse speculation price
            // (Rahik's 4-result design, 2026-09-29, reshaped): ours pulled
            // halfway toward the outside median, nudged by the growth
            // outlook. Display-only, no buy-below.
            row.add(disp(rawDcf));
            row.add(dispBuyBelow(dcf, req));
            row.add(dispMuseSpec(b.sp, t));
            // Growth outlook sits next to the Muse DCF (Rahik 2026-09-28):
            // Muse's one-line growth assessment from the deep-research brief.
            // Read from the canonical backend so the column never lags the
            // workbook_data.json sync by a run. "not yet researched" until
            // that ticker's brief lands — never a plausible fill.
            row.add(dispGrowth(b.go, t));
            // NotebookLM grounded, then the reserved NotebookLM speculation
            // slot (always "—" until NotebookLM derives it itself), then
            // Forward tilt. All display-only, no buy-below.
            row.add(disp(rawNb));
            row.add(dispNlmSpec(b.sp, t));
            row.add(disp(rawFwd));
            row.add(disp(rawCreator));
            row.add(disp(rawAnalyst));
            // Website consensus closes the voice block (Rahik's 4-result
            // design, 2026-09-29, reshaped): its own column, never blended,
            // display-only.
            row.add(dispWebConsensus(b.web, t));
            rows.add(row);
        }
        writeText(COMP, Csv.write(rows));
        note("  wrote " + (rows.size() - 3) + " company rows to "
                + Paths.get(COMP).getFileName());
    }

    static void extractJsons() throws Exception {
        List<List<String>> allRows = Csv.read(readFile(COMP));
        List<List<String>> rows = allRows.subList(3, allRows.size());
        Map<String, Object> our = new LinkedHashMap<>();
        Map<String, Object> nbf = new LinkedHashMap<>();
        Map<String, Object> ourValues = new LinkedHashMap<>();
        Map<String, Object> nbfValues = new LinkedHashMap<>();
        Map<String, Object> fwd = new LinkedHashMap<>();
        Map<String, Object> fwdValues = new LinkedHashMap<>();
        String today = LocalDate.now().toString();
        our.put("_derived", DERIVED_STAMP); // idea #40: derived-file stamp
        our.put("asof", today);
        our.put("note", "Muse DCF intrinsic value per ticker (one number), "
                + "extracted from the DCF comparison workbook (CSV export).");
        our.put("values", ourValues);
        nbf.put("_derived", DERIVED_STAMP); // idea #40: derived-file stamp
        nbf.put("asof", today);
        nbf.put("note", "NotebookLM grounded DCF values per ticker (filings/history "
                + "only, neutral instruction), "
                + "extracted from the DCF comparison workbook (CSV export).");
        nbf.put("values", nbfValues);
        fwd.put("_derived", DERIVED_STAMP); // idea #40: derived-file stamp
        fwd.put("asof", today);
        fwd.put("note", "NotebookLM forward-tilt DCF values per ticker (grounded base "
                + "plus a modest forward-looking tilt from management guidance / "
                + "product-cycle momentum; its own column, never blended), "
                + "extracted from the DCF comparison workbook (CSV export).");
        fwd.put("values", fwdValues);
        for (List<String> r : rows) {
            if (r.size() < 9 || r.get(0).strip().isEmpty()) {
                continue;
            }
            String t = r.get(0).strip();
            // Column indices into the 12-column comparison layout (Rahik's
            // reshaped order, 2026-09-29): 2 = Our DCF, 6 = NotebookLM
            // grounded, 8 = Forward tilt. These MUST move with the layout —
            // stale indices silently shift every backend (2026-09-29
            // incident: our_dcf.json received the speculation column).
            Map<String, Object> oe = new LinkedHashMap<>();
            oe.put("value", cell(r, 2));
            ourValues.put(t, oe);
            Map<String, Object> ne = new LinkedHashMap<>();
            ne.put("value", cell(r, 6));
            nbfValues.put(t, ne);
            Map<String, Object> fe = new LinkedHashMap<>();
            fe.put("value", cell(r, 8));
            fwdValues.put(t, fe);
        }
        // Match the shipped files byte-for-byte: 1-space indent, note ends with
        // '.', no trailing newline.
        writeText(OUR, Json.dumps(our));
        writeText(NBF, Json.dumps(nbf));
        writeText(FWD, Json.dumps(fwd));
        note("  extracted " + ourValues.size()
                + " tickers -> our_dcf.json, notebooklm_values.json, notebooklm_forward.json");
    }

    /** Parse a CSV cell back to a number; '' or em-dash -> null. */
    static Double cell(List<String> r, int i) {
        String v = r.size() > i ? r.get(i).strip() : "";
        if (v.isEmpty() || v.equals("\u2014")) {
            return null;
        }
        try {
            return Double.parseDouble(v.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    // ------------------------------------------------------------------
    // Py: Python-compatible float formatting helpers.
    // ------------------------------------------------------------------

    /** Python repr()/str() semantics for doubles, plus printf-style fixed formats. */
    static class Py {
        /**
         * Python repr(float): shortest round-trip digits (same digits Java's
         * Double.toString produces), but Python's placement rules: fixed notation
         * unless the decimal exponent is &le; -4 or &gt; 16, always with a '.'.
         */
        static String repr(double v) {
            if (Double.isNaN(v)) {
                return "nan";
            }
            if (v == Double.POSITIVE_INFINITY) {
                return "inf";
            }
            if (v == Double.NEGATIVE_INFINITY) {
                return "-inf";
            }
            if (v == 0.0) {
                // Preserve the sign of -0.0: Python repr(-0.0) == '-0.0'.
                return Double.doubleToRawLongBits(v) < 0 ? "-0.0" : "0.0";
            }
            String s = Double.toString(v);
            boolean neg = s.startsWith("-");
            String t = neg ? s.substring(1) : s;
            String mant;
            int exp;
            int ePos = t.indexOf('E');
            if (ePos >= 0) {
                mant = t.substring(0, ePos);
                exp = Integer.parseInt(t.substring(ePos + 1));
            } else {
                mant = t;
                exp = 0;
            }
            int dot = mant.indexOf('.');
            String digits = mant.substring(0, dot) + mant.substring(dot + 1);
            // Value = 0.digits * 10^decpt.
            int decpt = dot + exp;
            int k = digits.length();
            String body;
            if (decpt <= -4 || decpt > 16) {
                int e10 = decpt - 1;
                String es = (e10 < 0 ? "-" : "+")
                        + String.format("%02d", Math.abs(e10));
                body = digits.charAt(0) + (k > 1 ? "." + digits.substring(1) : "")
                        + "e" + es;
            } else if (decpt <= 0) {
                body = "0." + "0".repeat(-decpt) + digits;
            } else if (decpt >= k) {
                body = digits + "0".repeat(decpt - k) + ".0";
            } else {
                body = digits.substring(0, decpt) + "." + digits.substring(decpt);
            }
            return (neg ? "-" : "") + body;
        }

        /**
         * Python's {@code f"{v:.{n}f}"}: round-half-even on the exact binary value,
         * always {@code n} decimals. Python keeps the sign of -0.0 ("-0.00").
         */
        static String fixed(double v, int n) {
            if (Double.isNaN(v)) {
                return "nan";
            }
            if (Double.isInfinite(v)) {
                return v > 0 ? "inf" : "-inf";
            }
            BigDecimal bd = new BigDecimal(v); // exact decimal expansion of the double
            bd = bd.setScale(n, RoundingMode.HALF_EVEN);
            String s = bd.toPlainString();
            if (bd.signum() == 0 && Double.doubleToRawLongBits(v) < 0) {
                s = "-" + s; // Python formats -0.0 as "-0.00"
            }
            return s;
        }

        /**
         * Python's {@code f"{v:,.{n}f}"}: like {@link #fixed} with thousands separators.
         */
        static String comma(double v, int n) {
            String s = fixed(v, n);
            // fixed() already handled the -0.0 sign; regroup the integer part.
            boolean neg = s.startsWith("-");
            String t = neg ? s.substring(1) : s;
            int dot = t.indexOf('.');
            String intPart = dot >= 0 ? t.substring(0, dot) : t;
            String fracPart = dot >= 0 ? t.substring(dot) : "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < intPart.length(); i++) {
                if (i > 0 && (intPart.length() - i) % 3 == 0) {
                    sb.append(',');
                }
                sb.append(intPart.charAt(i));
            }
            return (neg ? "-" : "") + sb + fracPart;
        }

        /** Python's round(x, ndigits): correctly-rounded half-even, back to double. */
        static double round(double v, int ndigits) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return v;
            }
            return new BigDecimal(v).setScale(ndigits, RoundingMode.HALF_EVEN).doubleValue();
        }

        /**
         * Python repr() of a string: single quotes, backslash escapes, printable
         * non-ASCII kept as-is (used for the {@code note=...} detail in verify_base).
         */
        static String strRepr(String s) {
            StringBuilder sb = new StringBuilder("'");
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                switch (cp) {
                    case '\\': sb.append("\\\\"); break;
                    case '\'': sb.append("\\'"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (cp < 0x20 || cp == 0x7F) {
                            sb.append(String.format("\\x%02x", cp));
                        } else {
                            sb.appendCodePoint(cp);
                        }
                }
                i += Character.charCount(cp);
            }
            return sb.append("'").toString();
        }
    }

    // ------------------------------------------------------------------
    // Json: minimal JSON parser (insertion-ordered) + Python-compatible writer.
    // ------------------------------------------------------------------

    /** Minimal JSON value model + parser. Only what these scripts need. */
    static class Json {
        /** Base value type. */
        abstract static class Val { }

        /** JSON object: insertion order preserved (like Python's dict). */
        static class Obj extends Val {
            final LinkedHashMap<String, Val> map = new LinkedHashMap<>();

            boolean has(String k) { return map.containsKey(k); }
            boolean isNull(String k) { return !map.containsKey(k) || map.get(k) == null; }
            Val get(String k) { return map.get(k); }
            String getStr(String k) { return (String) ((Str) map.get(k)).v; }
            Obj getObj(String k) {
                Val v = map.get(k);
                return v instanceof Obj ? (Obj) v : null;
            }
            @SuppressWarnings("unchecked")
            List<Val> getList(String k) { return (List<Val>) ((Arr) map.get(k)).items; }
            double getNum(String k) { return numOf(map.get(k)); }
            /** Null when missing or JSON null (like Python's .get returning None). */
            Double getOptNum(String k) {
                Val v = map.get(k);
                return v == null ? null : numOf(v);
            }
            String optStr(String k, String dflt) {
                Val v = map.get(k);
                if (v == null || v instanceof Null) {
                    return dflt;
                }
                return ((Str) v).v;
            }
            void put(String k, Val v) { map.put(k, v); }

            static double numOf(Val v) {
                if (v instanceof Num) {
                    return ((Num) v).d;
                }
                throw new IllegalArgumentException("not a number: " + v);
            }
        }

        static class Arr extends Val {
            final List<Val> items = new ArrayList<>();
        }

        static class Str extends Val {
            final String v;
            Str(String v) { this.v = v; }
        }

        static class Num extends Val {
            final double d;      // numeric value
            final boolean isInt; // true when the literal had no ./e/E (Python int)
            final long l;        // valid when isInt
            Num(double d) { this.d = d; this.isInt = false; this.l = 0; }
            Num(long l) { this.d = l; this.isInt = true; this.l = l; }
        }

        static class Bool extends Val {
            final boolean v;
            Bool(boolean v) { this.v = v; }
        }

        static class Null extends Val { }

        /** Parse a JSON document. */
        static Val parse(String text) {
            Parser p = new Parser(text);
            Val v = p.value();
            p.ws();
            if (p.pos != text.length()) {
                throw new IllegalArgumentException("trailing data at " + p.pos);
            }
            return v;
        }

        /** Write a value exactly like Python's json.dumps(v, indent=1, ensure_ascii=True). */
        static String dumps(Object v) {
            StringBuilder sb = new StringBuilder();
            dump(v, sb, 0);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        static void dump(Object v, StringBuilder sb, int level) {
            if (v == null || v instanceof Null) {
                sb.append("null");
            } else if (v instanceof Str) {
                quote(((Str) v).v, sb);
            } else if (v instanceof String) {
                quote((String) v, sb);
            } else if (v instanceof Num) {
                Num n = (Num) v;
                sb.append(n.isInt ? Long.toString(n.l) : jsonFloat(n.d));
            } else if (v instanceof Double) {
                sb.append(jsonFloat((Double) v));
            } else if (v instanceof Long) {
                sb.append(v);
            } else if (v instanceof Integer) {
                sb.append(v);
            } else if (v instanceof Boolean) {
                sb.append((Boolean) v ? "true" : "false");
            } else if (v instanceof Bool) {
                sb.append(((Bool) v).v ? "true" : "false");
            } else if (v instanceof Obj) {
                Obj o = (Obj) v;
                if (o.map.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                String pad = " ".repeat(level + 1);
                boolean first = true;
                for (Map.Entry<String, Val> e : o.map.entrySet()) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    first = false;
                    sb.append(pad);
                    quote(e.getKey(), sb);
                    sb.append(": ");
                    dump(e.getValue(), sb, level + 1);
                }
                sb.append("\n").append(" ".repeat(level)).append("}");
            } else if (v instanceof Arr) {
                dumpList(((Arr) v).items, sb, level);
            } else if (v instanceof List) {
                dumpList((List<Object>) v, sb, level);
            } else if (v instanceof Map) {
                // LinkedHashMap<String, Object> built by callers (insertion order).
                Map<String, Object> m = (Map<String, Object>) v;
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                String pad = " ".repeat(level + 1);
                boolean first = true;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    first = false;
                    sb.append(pad);
                    quote(e.getKey(), sb);
                    sb.append(": ");
                    dump(e.getValue(), sb, level + 1);
                }
                sb.append("\n").append(" ".repeat(level)).append("}");
            } else {
                throw new IllegalArgumentException("cannot dump " + v.getClass());
            }
        }

        static void dumpList(List<?> items, StringBuilder sb, int level) {
            if (items.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            String pad = " ".repeat(level + 1);
            boolean first = true;
            for (Object it : items) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append(pad);
                dump(it, sb, level + 1);
            }
            sb.append("\n").append(" ".repeat(level)).append("]");
        }

        /** Python's json float encoding: repr(), except inf/nan spellings. */
        static String jsonFloat(double d) {
            if (Double.isNaN(d)) {
                return "NaN";
            }
            if (d == Double.POSITIVE_INFINITY) {
                return "Infinity";
            }
            if (d == Double.NEGATIVE_INFINITY) {
                return "-Infinity";
            }
            return Py.repr(d);
        }

        /** ensure_ascii string quoting (non-ASCII -> \\uXXXX, lowercase hex). */
        static void quote(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                switch (cp) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (cp < 0x20 || cp > 0x7E) {
                            if (cp > 0xFFFF) {
                                // Surrogate pair, each \\uXXXX (like Python's ensure_ascii).
                                char hi = Character.highSurrogate(cp);
                                char lo = Character.lowSurrogate(cp);
                                sb.append(String.format("\\u%04x\\u%04x", (int) hi, (int) lo));
                            } else {
                                sb.append(String.format("\\u%04x", cp));
                            }
                        } else {
                            sb.appendCodePoint(cp);
                        }
                }
                i += Character.charCount(cp);
            }
            sb.append('"');
        }

        static class Parser {
            final String t;
            int pos;
            Parser(String t) { this.t = t; }

            void ws() {
                while (pos < t.length()) {
                    char c = t.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            Val value() {
                ws();
                if (pos >= t.length()) {
                    throw new IllegalArgumentException("unexpected end of input");
                }
                char c = t.charAt(pos);
                switch (c) {
                    case '{': return obj();
                    case '[': return arr();
                    case '"': return new Str(str());
                    case 't': expect("true"); return new Bool(true);
                    case 'f': expect("false"); return new Bool(false);
                    case 'n': expect("null"); return new Null();
                    default: return num();
                }
            }

            Obj obj() {
                Obj o = new Obj();
                pos++; // {
                ws();
                if (pos < t.length() && t.charAt(pos) == '}') {
                    pos++;
                    return o;
                }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (t.charAt(pos) != ':') {
                        throw new IllegalArgumentException("expected : at " + pos);
                    }
                    pos++;
                    o.put(k, value());
                    ws();
                    char c = t.charAt(pos);
                    if (c == ',') {
                        pos++;
                    } else if (c == '}') {
                        pos++;
                        return o;
                    } else {
                        throw new IllegalArgumentException("expected , or } at " + pos);
                    }
                }
            }

            Arr arr() {
                Arr a = new Arr();
                pos++; // [
                ws();
                if (pos < t.length() && t.charAt(pos) == ']') {
                    pos++;
                    return a;
                }
                while (true) {
                    a.items.add(value());
                    ws();
                    char c = t.charAt(pos);
                    if (c == ',') {
                        pos++;
                    } else if (c == ']') {
                        pos++;
                        return a;
                    } else {
                        throw new IllegalArgumentException("expected , or ] at " + pos);
                    }
                }
            }

            String str() {
                pos++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = t.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        char e = t.charAt(pos++);
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
                                int cp = Integer.parseInt(t.substring(pos, pos + 4), 16);
                                pos += 4;
                                if (Character.isHighSurrogate((char) cp)
                                        && pos + 1 < t.length()
                                        && t.charAt(pos) == '\\' && t.charAt(pos + 1) == 'u') {
                                    int lo = Integer.parseInt(t.substring(pos + 2, pos + 6), 16);
                                    pos += 6;
                                    sb.append(Character.toChars(
                                            Character.toCodePoint((char) cp, (char) lo)));
                                } else {
                                    sb.append((char) cp);
                                }
                                break;
                            }
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }

            Val num() {
                int start = pos;
                while (pos < t.length() && "-+0123456789.eE".indexOf(t.charAt(pos)) >= 0) {
                    pos++;
                }
                String lit = t.substring(start, pos);
                boolean isFloat = lit.indexOf('.') >= 0 || lit.indexOf('e') >= 0
                        || lit.indexOf('E') >= 0;
                if (!isFloat) {
                    try {
                        return new Num(Long.parseLong(lit));
                    } catch (NumberFormatException e) {
                        return new Num(Double.parseDouble(lit)); // huge int -> double
                    }
                }
                return new Num(Double.parseDouble(lit));
            }

            void expect(String word) {
                if (!t.startsWith(word, pos)) {
                    throw new IllegalArgumentException("expected " + word + " at " + pos);
                }
                pos += word.length();
            }
        }
    }

    // ------------------------------------------------------------------
    // Csv: Python csv-module-compatible reader/writer (QUOTE_MINIMAL).
    // ------------------------------------------------------------------

    /** CSV reader/writer matching Python's csv.reader/csv.writer defaults. */
    static class Csv {
        /** Parse CSV text into rows of fields (handles quotes, embedded newlines). */
        static List<List<String>> read(String text) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            int i = 0, n = text.length();
            boolean fieldStarted = false;
            while (i < n) {
                char c = text.charAt(i);
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < n && text.charAt(i + 1) == '"') {
                            field.append('"');
                            i += 2;
                        } else {
                            inQuotes = false;
                            i++;
                        }
                    } else {
                        field.append(c);
                        i++;
                    }
                } else if (c == '"') {
                    inQuotes = true;
                    fieldStarted = true;
                    i++;
                } else if (c == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    i++;
                } else if (c == '\r' || c == '\n') {
                    row.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    rows.add(row);
                    row = new ArrayList<>();
                    if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                        i += 2;
                    } else {
                        i++;
                    }
                } else {
                    field.append(c);
                    fieldStarted = true;
                    i++;
                }
            }
            // Trailing field/row (a file not ending in a newline still has a last row).
            if (fieldStarted || !row.isEmpty()) {
                row.add(field.toString());
                rows.add(row);
            }
            return rows;
        }

        /** Format rows like csv.writer: QUOTE_MINIMAL, \\r\\n line terminator. */
        static String write(List<List<String>> rows) {
            StringBuilder sb = new StringBuilder();
            for (List<String> row : rows) {
                boolean first = true;
                for (String f : row) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    if (f.indexOf(',') >= 0 || f.indexOf('"') >= 0
                            || f.indexOf('\r') >= 0 || f.indexOf('\n') >= 0) {
                        sb.append('"').append(f.replace("\"", "\"\"")).append('"');
                    } else {
                        sb.append(f);
                    }
                }
                sb.append("\r\n");
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // buildXlsx: comparison XLSX via a minimal hand-rolled OOXML writer/reader.
    //
    // The Python version uses openpyxl, which stamps fresh zip timestamps on
    // every save -- so even Python cannot reproduce the file byte-for-byte.
    // The script's own rule (kept here) is cell-matrix equality: only replace
    // the file when the cell values actually changed. Parity is therefore
    // verified at the cell level (values, strings, merges, empties), which the
    // Xlsx reader below checks against the existing file before writing.
    // ------------------------------------------------------------------

    static void buildXlsx() throws Exception {
        List<List<String>> rows = Csv.read(readFile(COMP));
        int nrows = rows.size();
        int ncols = 0;
        for (List<String> r : rows) {
            ncols = Math.max(ncols, r.size());
        }
        // Cell matrix, mirroring openpyxl's ws.cell(r, c).value layout.
        Object[][] vals = new Object[nrows][ncols];
        for (int ri = 0; ri < nrows; ri++) {
            List<String> r = rows.get(ri);
            for (int ci = 0; ci < ncols; ci++) {
                String v = ci < r.size() ? r.get(ci).replace('\n', ' ') : "";
                // Forward-tilt column (index 8) and the reserved NotebookLM
                // speculation slot (index 7): missing values are empty cells
                // in the shipped xlsx (other columns keep the em-dash text).
                // Both blank until verified values land.
                if ((ci == 8 || ci == 7) && v.equals("\u2014")) {
                    vals[ri][ci] = null;
                    continue;
                }
                try {
                    vals[ri][ci] = Double.parseDouble(v.replace(",", "").trim());
                } catch (NumberFormatException e) {
                    vals[ri][ci] = v;
                }
            }
        }
        if (CHECK) {
            note("  (xlsx value-check runs in full mode)");
            return;
        }
        // Merged title rows, like the Python's ws.merge_cells calls.
        List<int[]> merges = new ArrayList<>();
        merges.add(new int[]{0, 0, 0, ncols - 1});
        merges.add(new int[]{1, 0, 1, ncols - 1});
        if (Files.exists(Paths.get(COMP_XLSX))) {
            XlsxSheet old = Xlsx.read(Files.readAllBytes(Paths.get(COMP_XLSX)));
            if (old.nrows == nrows && old.ncols == ncols && matricesEqual(old.vals, vals)) {
                note("  xlsx cell matrix unchanged -> kept existing file "
                        + "(avoids zip-timestamp drift)");
                return;
            }
        }
        Files.write(Paths.get(COMP_XLSX), Xlsx.write(vals, nrows, ncols, merges));
        note("  wrote " + Paths.get(COMP_XLSX).getFileName()
                + " (" + nrows + "x" + ncols + ", sheet 'DCF Comparison')");
    }

    /** Cell-matrix equality with the Python's cells_equal semantics. */
    static boolean matricesEqual(Object[][] old, Object[][] nw) {
        for (int r = 0; r < old.length; r++) {
            for (int c = 0; c < old[r].length; c++) {
                if (!cellsEqual(old[r][c], nw[r][c])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Python cells_equal(a, b): a=None matches None or ''. Floats within 1e-12. */
    static boolean cellsEqual(Object a, Object b) {
        if (a == null) {
            return b == null || "".equals(b);
        }
        if (a instanceof Double && b instanceof Double) {
            return Math.abs((Double) a - (Double) b) < 1e-12;
        }
        return a.equals(b);
    }

    /** Minimal OOXML (.xlsx) writer + reader: numbers, inline strings, merges. */
    static class XlsxSheet {
        int nrows, ncols;
        Object[][] vals; // Double | String | null
    }

    static class Xlsx {
        /** Column index (0-based) -> letters, e.g. 0 -> A, 12 -> M. */
        static String colLetters(int c) {
            String s = "";
            c++;
            while (c > 0) {
                c--;
                s = (char) ('A' + c % 26) + s;
                c /= 26;
            }
            return s;
        }

        /** Parse "M13" -> {row0, col0}. */
        static int[] parseRef(String ref) {
            int i = 0;
            while (i < ref.length() && !Character.isDigit(ref.charAt(i))) {
                i++;
            }
            String letters = ref.substring(0, i);
            int col = 0;
            for (char ch : letters.toCharArray()) {
                col = col * 26 + (ch - 'A' + 1);
            }
            return new int[]{Integer.parseInt(ref.substring(i)) - 1, col - 1};
        }

        static void xmlText(StringBuilder sb, String s) {
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                if (cp == '&') {
                    sb.append("&amp;");
                } else if (cp == '<') {
                    sb.append("&lt;");
                } else if (cp == '>') {
                    sb.append("&gt;");
                } else {
                    sb.appendCodePoint(cp);
                }
                i += Character.charCount(cp);
            }
        }

        /** Build the .xlsx bytes for the matrix (null = empty cell). */
        static byte[] write(Object[][] vals, int nrows, int ncols, List<int[]> merges) {
            boolean[][] skip = new boolean[nrows][ncols]; // merged non-anchor cells
            for (int[] mg : merges) {
                for (int r = mg[0]; r <= mg[2]; r++) {
                    for (int c = mg[1]; c <= mg[3]; c++) {
                        if (r != mg[0] || c != mg[1]) {
                            skip[r][c] = true;
                        }
                    }
                }
            }
            StringBuilder sheet = new StringBuilder();
            sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<dimension ref=\"").append(colLetters(0)).append("1:")
                    .append(colLetters(ncols - 1)).append(nrows).append("\"/>"
                    + "<sheetData>");
            for (int r = 0; r < nrows; r++) {
                sheet.append("<row r=\"").append(r + 1).append("\">");
                for (int c = 0; c < ncols; c++) {
                    if (skip[r][c]) {
                        continue;
                    }
                    Object v = vals[r][c];
                    if (v == null) {
                        continue;
                    }
                    String ref = colLetters(c) + (r + 1);
                    if (v instanceof Double) {
                        sheet.append("<c r=\"").append(ref).append("\"><v>")
                                .append(Py.repr((Double) v)).append("</v></c>");
                    } else {
                        String s = (String) v;
                        sheet.append("<c r=\"").append(ref)
                                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
                        xmlText(sheet, s);
                        sheet.append("</t></is></c>");
                    }
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData>");
            if (!merges.isEmpty()) {
                sheet.append("<mergeCells count=\"").append(merges.size()).append("\">");
                for (int[] mg : merges) {
                    sheet.append("<mergeCell ref=\"")
                            .append(colLetters(mg[1])).append(mg[0] + 1).append(":")
                            .append(colLetters(mg[3])).append(mg[2] + 1).append("\"/>");
                }
                sheet.append("</mergeCells>");
            }
            sheet.append("</worksheet>");

            String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxml-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "</Types>";
            String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>";
            String workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"DCF Comparison\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                    + "</workbook>";
            String workbookRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>";
            String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>"
                    + "</styleSheet>";

            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out);
                String[][] parts = {
                    {"[Content_Types].xml", contentTypes},
                    {"_rels/.rels", rels},
                    {"xl/workbook.xml", workbook},
                    {"xl/_rels/workbook.xml.rels", workbookRels},
                    {"xl/worksheets/sheet1.xml", sheet.toString()},
                    {"xl/styles.xml", styles},
                };
                for (String[] p : parts) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(p[0]));
                    zip.write(p[1].getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                zip.close();
                return out.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** Read an .xlsx into a value matrix (data_only semantics: no formulas stored). */
        static XlsxSheet read(byte[] data) {
            try {
                Map<String, byte[]> parts = new LinkedHashMap<>();
                java.util.zip.ZipInputStream zip =
                        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data));
                java.util.zip.ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    zip.transferTo(b);
                    parts.put(e.getName(), b.toByteArray());
                }
                List<String> shared = new ArrayList<>();
                if (parts.containsKey("xl/sharedStrings.xml")) {
                    org.w3c.dom.Document d = parseXml(parts.get("xl/sharedStrings.xml"));
                    org.w3c.dom.NodeList sis = d.getElementsByTagName("si");
                    for (int i = 0; i < sis.getLength(); i++) {
                        shared.add(((org.w3c.dom.Element) sis.item(i)).getTextContent());
                    }
                }
                org.w3c.dom.Document doc =
                        parseXml(parts.get("xl/worksheets/sheet1.xml"));
                org.w3c.dom.Element root = doc.getDocumentElement();
                // Merged ranges: non-anchor cells read as empty.
                List<int[]> merges = new ArrayList<>();
                org.w3c.dom.NodeList mc =
                        root.getElementsByTagName("mergeCell");
                for (int i = 0; i < mc.getLength(); i++) {
                    String ref = ((org.w3c.dom.Element) mc.item(i)).getAttribute("ref");
                    String[] ends = ref.split(":");
                    int[] a = parseRef(ends[0]);
                    int[] b2 = parseRef(ends[1]);
                    merges.add(new int[]{a[0], a[1], b2[0], b2[1]});
                }
                int nrows = 0, ncols = 0;
                org.w3c.dom.NodeList dims = root.getElementsByTagName("dimension");
                if (dims.getLength() > 0) {
                    String ref = ((org.w3c.dom.Element) dims.item(0)).getAttribute("ref");
                    int[] end = parseRef(ref.split(":")[ref.contains(":") ? 1 : 0]);
                    nrows = end[0] + 1;
                    ncols = end[1] + 1;
                }
                Map<String, Object> cells = new LinkedHashMap<>();
                org.w3c.dom.NodeList cs = root.getElementsByTagName("c");
                for (int i = 0; i < cs.getLength(); i++) {
                    org.w3c.dom.Element c = (org.w3c.dom.Element) cs.item(i);
                    String ref = c.getAttribute("r");
                    int[] rc = parseRef(ref);
                    boolean mergedAway = false;
                    for (int[] mg : merges) {
                        if (rc[0] >= mg[0] && rc[0] <= mg[2]
                                && rc[1] >= mg[1] && rc[1] <= mg[3]
                                && (rc[0] != mg[0] || rc[1] != mg[1])) {
                            mergedAway = true;
                        }
                    }
                    if (mergedAway) {
                        continue;
                    }
                    nrows = Math.max(nrows, rc[0] + 1);
                    ncols = Math.max(ncols, rc[1] + 1);
                    String t = c.getAttribute("t");
                    Object val = null;
                    if (t.equals("inlineStr")) {
                        org.w3c.dom.NodeList ist = c.getElementsByTagName("is");
                        val = ist.getLength() > 0
                                ? ((org.w3c.dom.Element) ist.item(0)).getTextContent() : "";
                    } else if (t.equals("s")) {
                        val = shared.get(Integer.parseInt(textOf(c, "v")));
                    } else if (t.equals("str") || t.equals("e")) {
                        val = textOf(c, "v");
                    } else if (t.equals("b")) {
                        val = textOf(c, "v").equals("1") ? Boolean.TRUE : Boolean.FALSE;
                    } else {
                        String vtext = textOf(c, "v");
                        if (vtext != null) {
                            val = Double.parseDouble(vtext);
                        }
                    }
                    cells.put(rc[0] + "," + rc[1], val);
                }
                XlsxSheet sh = new XlsxSheet();
                sh.nrows = nrows;
                sh.ncols = ncols;
                sh.vals = new Object[nrows][ncols];
                for (Map.Entry<String, Object> en : cells.entrySet()) {
                    String[] ij = en.getKey().split(",");
                    sh.vals[Integer.parseInt(ij[0])][Integer.parseInt(ij[1])] = en.getValue();
                }
                return sh;
            } catch (Exception e) {
                throw new RuntimeException("cannot read xlsx: " + e, e);
            }
        }

        static String textOf(org.w3c.dom.Element c, String tag) {
            org.w3c.dom.NodeList nl = c.getElementsByTagName(tag);
            return nl.getLength() > 0 ? nl.item(0).getTextContent() : null;
        }

        static org.w3c.dom.Document parseXml(byte[] data) throws Exception {
            javax.xml.parsers.DocumentBuilderFactory f =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(data));
        }
    }

    static void buildAssumptionsCsv() throws Exception {
        Json.Obj data = (Json.Obj) Json.parse(readFile(WD));
        Json.Obj storedAll;
        try {
            storedAll = (Json.Obj) ((Json.Obj) Json.parse(
                    readFile(ROOT + "/sec_filings/dcf_inputs_all.json"))).get("companies");
        } catch (Exception e) {
            storedAll = new Json.Obj();
        }
        List<List<String>> old = Csv.read(readFile(ASSUMP));
        List<List<String>> rows = new ArrayList<>(old.subList(0, 3)); // title rows + header
        stampCsvTitle(rows, "Model Assumptions"); // idea #40: derived-file stamp
        for (Json.Val cv : data.getList("companies")) {
            Json.Obj c = (Json.Obj) cv;
            String t = c.getStr("ticker");
            Json.Obj a = c.getObj("assumptions");
            if (a == null) {
                a = new Json.Obj();
            }
            Json.Obj co = storedAll.getObj(t);
            Json.Obj ann = co == null ? null : co.getObj("annual");
            final Json.Obj af = a;
            java.util.function.Function<String, String> f = k -> {
                Json.Val v = af.get(k);
                if (v == null || v instanceof Json.Null) {
                    return "\u2014";
                }
                if (v instanceof Json.Num) {
                    Json.Num n = (Json.Num) v;
                    if (n.isInt) {
                        return Long.toString(n.l);
                    }
                    if (n.d == Math.rint(n.d) && Double.isFinite(n.d)) {
                        return Long.toString((long) n.d);
                    }
                    return Py.repr(n.d);
                }
                if (v instanceof Json.Bool) {
                    return ((Json.Bool) v).v ? "True" : "False";
                }
                return ((Json.Str) v).v;
            };
            List<String> row = new ArrayList<>();
            row.add(c.getStr("name"));
            row.add(t);
            row.add(annStr(af, "kind"));
            row.add(f.apply("base"));
            row.add(ann == null ? "\u2014" : annStr(ann, "ocf"));
            row.add(ann == null ? "\u2014" : annStr(ann, "capex"));
            row.add(f.apply("g"));
            row.add(f.apply("w"));
            row.add(f.apply("t"));
            row.add(f.apply("shares"));
            row.add(f.apply("netdebt"));
            row.add(f.apply("start_margin"));
            row.add(f.apply("end_margin"));
            row.add(af.has("src") ? scalarStr(af.get("src"))
                    : "No DCF assumptions were recorded in the supplied input data.");
            rows.add(row);
        }
        writeText(ASSUMP, Csv.write(rows));
        note("  wrote " + (rows.size() - 3) + " assumption rows to "
                + Paths.get(ASSUMP).getFileName());
    }

    /** Python str(ann.get(k, em-dash)): missing -> em-dash, present null -> 'None'. */
    static String annStr(Json.Obj o, String k) {
        if (!o.has(k)) {
            return "\u2014";
        }
        return scalarStr(o.get(k));
    }

    /** CSV-safe stringify for disagreement/source cells (Python str() of values). */
    static String csvStr(Object v) {
        if (v == null) {
            return "\u2014";
        }
        if (v instanceof Json.Val) {
            Json.Val jv = (Json.Val) v;
            if (jv instanceof Json.Null) {
                return "\u2014";
            }
            if (jv instanceof Json.Num) {
                Json.Num n = (Json.Num) jv;
                return n.isInt ? Long.toString(n.l) : Py.repr(n.d);
            }
            if (jv instanceof Json.Bool) {
                return ((Json.Bool) jv).v ? "True" : "False";
            }
            return ((Json.Str) jv).v;
        }
        if (v instanceof Double) {
            return Py.repr((Double) v);
        }
        return v.toString();
    }

    static void buildDisagreementsCsv() throws Exception {
        Json.Obj data = (Json.Obj) Json.parse(readFile(WD));
        Map<String, Json.Obj> byTicker = new LinkedHashMap<>();
        for (Json.Val cv : data.getList("companies")) {
            Json.Obj c = (Json.Obj) cv;
            byTicker.put(c.getStr("ticker"), c);
        }
        List<List<String>> rows = new ArrayList<>();
        // Idea #40: the stamp lives inside the title cell so the 3-row shape
        // (titles + header) that readers rely on is unchanged.
        rows.add(listOf("DCF Disagreement Spotlight \u2014 " + DERIVED_STAMP,
                "", "", "", "", ""));
        rows.add(listOf("Muse vs NotebookLM; only differences over 15%, plus IREN "
                + "sign flip. NotebookLM assumptions were not recorded.",
                "", "", "", "", ""));
        String[] fields = {"Intrinsic value / share", "Difference", "Model kind",
                "Base FCF / revenue ($M)", "OCF ($M)", "Capex ($M)",
                "Initial growth g", "WACC / CoE w", "Terminal growth t",
                "Diluted shares (M)", "Net debt ($M)", "Start margin",
                "Year-5 margin", "Assumption source"};
        // History check (Rahik 2026-09-28): every disagreement also shows how
        // the company actually ran — the gap is usually in the assumptions,
        // and the assumptions are judged against this history.
        Map<String, Map<String, String>> hist = loadHistoryCsv();
        for (Json.Val dv : data.getList("disagreements")) {
            Json.Obj d = (Json.Obj) dv;
            Json.Obj c = byTicker.get(d.getStr("ticker"));
            Json.Obj a = c.getObj("assumptions");
            if (a == null) {
                a = new Json.Obj();
            }
            double fixed = numOf(c.get("fixed"));
            double nbv = numOf(c.get("notebooklm"));
            Object diff = "Sign flip".equals(scalarStr(d.get("diff")))
                    ? (Object) "Sign flip"
                    : (Object) (Math.abs(fixed - nbv) / Math.abs(nbv));
            Map<String, Object> left = new LinkedHashMap<>();
            left.put("Intrinsic value / share", c.get("fixed"));
            left.put("Difference", diff);
            left.put("Model kind", a.has("kind") ? a.get("kind") : "\u2014");
            left.put("Base FCF / revenue ($M)", a.has("base") ? a.get("base") : "\u2014");
            left.put("OCF ($M)", a.has("ocf") ? a.get("ocf") : "\u2014");
            left.put("Capex ($M)", a.has("capex") ? a.get("capex") : "\u2014");
            left.put("Initial growth g", a.has("g") ? a.get("g") : "\u2014");
            left.put("WACC / CoE w", a.has("w") ? a.get("w") : "\u2014");
            left.put("Terminal growth t", a.has("t") ? a.get("t") : "\u2014");
            left.put("Diluted shares (M)", a.has("shares") ? a.get("shares") : "\u2014");
            left.put("Net debt ($M)", a.has("netdebt") ? a.get("netdebt") : "\u2014");
            left.put("Start margin", a.has("start_margin") ? a.get("start_margin") : "\u2014");
            left.put("Year-5 margin", a.has("end_margin") ? a.get("end_margin") : "\u2014");
            left.put("Assumption source", a.has("src") ? a.get("src") : "\u2014");
            String nbAssump = scalarStr(d.get("notebooklm_assumptions"));
            rows.add(listOf(c.getStr("name") + " (" + c.getStr("ticker") + ")",
                    "", "", "", "", ""));
            rows.add(listOf("Field", "Our fixed DCF / assumptions", "",
                    "NotebookLM", "", ""));
            for (String fl : fields) {
                Object v = left.get(fl);
                // Python: '' if v == '\u2014' else v; a None value stays None and the
                // csv writer renders it as an empty cell.
                String cell;
                if (v == null || v instanceof Json.Null) {
                    cell = "";
                } else if (v instanceof String && ((String) v).equals("\u2014")) {
                    cell = "";
                } else {
                    cell = csvStr(v);
                }
                rows.add(listOf(fl, cell, "", nbAssump, "", ""));
            }
            // History-grounding rows: our live assumptions judged against the
            // company's actual 5-year record (same file the history check writes).
            Map<String, String> h = hist.get(d.getStr("ticker"));
            String hRev = h == null ? "" : pct1(h.get("hist_rev_cagr"));
            String hFcf = h == null ? "" : pct1(h.get("hist_fcf_cagr"));
            String hG = h == null ? "" : pct1(h.get("assumed_g"));
            String hFlags = h == null ? "" : h.getOrDefault("flags", "");
            String hStory = h == null ? "" : h.getOrDefault("story", "");
            rows.add(listOf("5-yr history: revenue CAGR", hRev, "", nbAssump, "", ""));
            rows.add(listOf("5-yr history: FCF CAGR vs assumed g",
                    hFcf.isEmpty() ? "" : hFcf + " vs " + hG, "", nbAssump, "", ""));
            rows.add(listOf("5-yr history: flags",
                    hFlags.isEmpty() ? "none" : hFlags, "", nbAssump, "", ""));
            rows.add(listOf("5-yr history: how the company ran",
                    hStory.isEmpty() ? "history check unavailable" : hStory,
                    "", nbAssump, "", ""));
            rows.add(listOf("", "", "", "", "", ""));
            rows.add(listOf("", "", "", "", "", ""));
        }
        writeText(DIS_CSV, Csv.write(rows));
        note("  wrote " + data.getList("disagreements").size()
                + " disagreement blocks to " + Paths.get(DIS_CSV).getFileName());
    }

    static List<String> listOf(String... items) {
        List<String> l = new ArrayList<>();
        for (String s : items) {
            l.add(s);
        }
        return l;
    }

    /** Ticker -> history-check row (flags, story, CAGRs) from the derived CSV. */
    static Map<String, Map<String, String>> loadHistoryCsv() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try {
            List<List<String>> rows = Csv.read(readFile(HIST_CSV));
            List<String> header = null;
            for (List<String> r : rows) {
                // Skip the leading comment line ("DCF History Check — derived ...").
                if (r.size() == 1 && r.get(0).startsWith("DCF History Check")) {
                    continue;
                }
                if (header == null) {
                    header = r;
                    continue;
                }
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < header.size() && i < r.size(); i++) {
                    m.put(header.get(i), r.get(i));
                }
                if (m.containsKey("ticker")) {
                    out.put(m.get("ticker"), m);
                }
            }
        } catch (Exception e) {
            note("  history CSV unavailable for disagreement blocks: " + e.getMessage());
        }
        return out;
    }

    /** Decimal fraction string (e.g. "0.2049") -> "20.5%"; "" on bad input. */
    static String pct1(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return String.format("%.1f%%", Double.parseDouble(s) * 100);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    static void buildSourcesCsv() throws Exception {
        Json.Obj data = (Json.Obj) Json.parse(readFile(WD));
        List<List<String>> rows = new ArrayList<>();
        // Idea #40: the stamp lives inside the title cell so the 3-row shape
        // (titles + header) that readers rely on is unchanged.
        rows.add(listOf("Source Ledger \u2014 " + DERIVED_STAMP, "", "", "", "", "", ""));
        rows.add(listOf("One row per valuation input, assumption, or derived output. "
                + "External URLs were not supplied; exact source notes are preserved "
                + "without guessing links.", "", "", "", "", "", ""));
        rows.add(listOf("Company / ticker", "Field", "Value", "Unit / format", "Source type",
                "Source detail", "Workbook location"));
        String[][] afields = {
                {"Model kind", "kind", "text"},
                {"Base FCF / revenue", "base", "USD M"},
                {"Operating cash flow", "ocf", "USD M"},
                {"Capital expenditure", "capex", "USD M"},
                {"Initial growth", "g", "percent"},
                {"Discount rate / CoE", "w", "percent"},
                {"Terminal growth", "t", "percent"},
                {"Diluted shares", "shares", "M shares"},
                {"Net debt", "netdebt", "USD M"},
                {"Start margin", "start_margin", "percent"},
                {"Year-5 margin", "end_margin", "percent"},
        };
        List<Json.Val> companies = data.getList("companies");
        for (int idx = 0; idx < companies.size(); idx++) {
            Json.Obj c = (Json.Obj) companies.get(idx);
            String t = c.getStr("ticker");
            int cr = idx + 4;
            String who = c.getStr("name") + " (" + t + ")";
            Json.Val nb = c.get("notebooklm");
            boolean nbNum = nb instanceof Json.Num;
            rows.add(listOf(who, "Market close", csvStr(c.get("market")), "USD/share",
                    "Supplied market input",
                    "Friday close 2026-09-25; " + data.optStr("market_asof", "") + ". "
                            + "External URL not supplied.",
                    "Comparison!C" + cr));
            rows.add(listOf(who, "Muse DCF", csvStr(c.get("fixed")), "USD/share",
                    "DCF formula / supplied check",
                    "Formula-driven Muse DCF on company model tab "
                            + "(mid-year discounting + growth fade).",
                    "Comparison!D" + cr));
            rows.add(listOf(who, "NotebookLM", nbNum ? csvStr(nb) : "\u2014", "USD/share",
                    "Supplied NotebookLM output",
                    nbNum ? "NotebookLM independent value from workbook_data.json; "
                            + "NotebookLM assumptions not recorded."
                            : "Not recorded in supplied input data.",
                    "Comparison!E" + cr));
            // Python: c.get('creator_note') or 'Not recorded...' -- null, missing,
            // and empty-string notes all fall through to the default.
            Json.Val noteVal = c.get("creator_note");
            String noteTxt;
            if (noteVal instanceof Json.Str) {
                String s = ((Json.Str) noteVal).v;
                noteTxt = s.isEmpty() ? "Not recorded in supplied input data." : s;
            } else if (noteVal == null || noteVal instanceof Json.Null) {
                noteTxt = "Not recorded in supplied input data.";
            } else {
                noteTxt = csvStr(noteVal);
            }
            rows.add(listOf(who, "Creator value", csvStr(c.get("creator")), "USD/share",
                    "Creator value", noteTxt, "Comparison!F" + cr));
            rows.add(listOf(who, "Analyst target", csvStr(c.get("analyst")), "USD/share",
                    "Analyst target",
                    (c.get("analyst") == null || c.get("analyst") instanceof Json.Null)
                            ? "Not recorded in supplied input data."
                            : "Analyst consensus target from analyst_targets.json.",
                    "Comparison!G" + cr));
            rows.add(listOf(who, "Average of available sources", "Formula", "USD/share",
                    "Workbook formula",
                    "AVERAGE of numeric cells in Comparison columns C:G; \u2014 text is ignored.",
                    "Comparison!H" + cr));
            rows.add(listOf(who, "Upside vs market", "Formula", "Percent",
                    "Workbook formula", "Muse DCF / market close - 1.",
                    "Comparison!I" + cr));
            Json.Obj a = c.getObj("assumptions");
            if (a == null) {
                a = new Json.Obj();
            }
            if (a.has("base")) {
                int ar = idx + 4;
                String src = a.has("src") ? scalarStr(a.get("src")) : "Supplied input dataset.";
                for (int j = 0; j < afields.length; j++) {
                    String col = String.valueOf((char) ('A' + (j + 3) - 1));
                    rows.add(listOf(who, afields[j][0], csvStr(a.get(afields[j][1])),
                            afields[j][2], "Supplied model assumption", src,
                            "Assumptions!" + col + ar));
                }
            }
        }
        writeText(SRC_CSV, Csv.write(rows));
        note("  wrote " + (rows.size() - 3) + " source rows to "
                + Paths.get(SRC_CSV).getFileName());
    }

    /**
     * Syncs one nullable numeric field into a workbook_data.json company
     * record. Returns true when the record changed. Null clears the field —
     * never a plausible fill.
     */
    static boolean syncNumField(Json.Obj c, String field, Json.Val raw) {
        Double v = cleanNum(raw);
        Json.Val cur = c.get(field);
        boolean eq = (v == null && (cur == null || cur instanceof Json.Null))
                || (v != null && numEq(cur, raw));
        if (!eq) {
            c.put(field, v == null ? new Json.Null() : new Json.Num(v));
            return true;
        }
        return false;
    }

    static void syncWorkbookData() throws Exception {
        Json.Obj data = (Json.Obj) Json.parse(readFile(WD));
        Json.Obj pc = readPriceQuotes();
        Json.Obj crAll = (Json.Obj) Json.parse(readFile(CREATOR));
        Json.Obj cr = crAll.getObj("latest");
        if (cr == null) {
            cr = new Json.Obj();
        }
        Json.Obj an = (Json.Obj) Json.parse(readFile(ANALYST));
        // Growth outlook backend (Muse's assessment; null until researched).
        Json.Obj goAll = (Json.Obj) Json.parse(readFile(GO));
        Json.Obj goTickers = goAll.getObj("tickers");
        if (goTickers == null) {
            goTickers = new Json.Obj();
        }
        // Speculation backends (Rahik's 4-result design, 2026-09-29):
        // speculation_price.json (per-voice speculation prices),
        // outside_voice.json (raw outside median),
        // website_consensus.json (website DCF consensus).
        Json.Obj ovAll = (Json.Obj) Json.parse(readFile(OV));
        Json.Obj ovTickers = ovAll.getObj("tickers");
        if (ovTickers == null) {
            ovTickers = new Json.Obj();
        }
        Json.Obj spAll = (Json.Obj) Json.parse(readFile(SP));
        Json.Obj spTickers = spAll.getObj("tickers");
        if (spTickers == null) {
            spTickers = new Json.Obj();
        }
        Json.Obj webAll = (Json.Obj) Json.parse(readFile(WEBC));
        Json.Obj webTickers = webAll.getObj("tickers");
        if (webTickers == null) {
            webTickers = new Json.Obj();
        }
        List<Json.Val> companies = data.getList("companies");
        int touched = 0;
        for (Json.Val cv : companies) {
            Json.Obj c = (Json.Obj) cv;
            String t = c.getStr("ticker");
            boolean changed = false;
            Json.Obj q = pc.getObj(t);
            Json.Val mkt = q == null ? null : q.get("price");
            if (mkt != null && !(mkt instanceof Json.Null) && !numEq(c.get("market"), mkt)) {
                c.put("market", mkt);
                changed = true;
            }
            Json.Obj ce = cr.getObj(t);
            if (ce != null && ce.get("value") != null && !(ce.get("value") instanceof Json.Null)) {
                if (!numEq(c.get("creator"), ce.get("value"))) {
                    c.put("creator", ce.get("value"));
                    changed = true;
                }
                String noteTxt = scalarStr(ce.get("creator")) + ": "
                        + scalarStr(ce.get("type")) + " $" + scalarStr(ce.get("value"))
                        + " (" + scalarStr(ce.get("call_date")) + ")";
                Json.Val oldNote = c.get("creator_note");
                if (oldNote == null || oldNote instanceof Json.Null
                        || !scalarStr(oldNote).equals(noteTxt)) {
                    c.put("creator_note", new Json.Str(noteTxt));
                    changed = true;
                }
            }
            Json.Obj ae = an.getObj(t);
            if (ae != null && ae.get("target") != null && !(ae.get("target") instanceof Json.Null)) {
                if (!numEq(c.get("analyst"), ae.get("target"))) {
                    c.put("analyst", ae.get("target"));
                    changed = true;
                }
            }
            // Growth outlook (Rahik 2026-09-28): Muse's per-ticker growth
            // assessment from the deep-research forward brief. Not a blessed
            // value — synced from growth_outlook.json into the workbook data
            // so the Comparison sheet can render it next to the Muse DCF.
            // Null until that ticker's brief lands; never a plausible fill.
            Json.Obj ge = goTickers.getObj(t);
            String gOut = ge == null ? null : ge.optStr("outlook", null);
            String gThesis = ge == null ? null : ge.optStr("thesis", null);
            if (!Objects.equals(c.optStr("growth_outlook", null), gOut)) {
                c.put("growth_outlook",
                        gOut == null ? new Json.Null() : new Json.Str(gOut));
                changed = true;
            }
            if (!Objects.equals(c.optStr("growth_thesis", null), gThesis)) {
                c.put("growth_thesis",
                        gThesis == null ? new Json.Null() : new Json.Str(gThesis));
                changed = true;
            }
            // Speculation backends (Rahik's 4-result design, 2026-09-29): the two
            // per-voice speculation prices from speculation_price.json, the
            // raw outside median from outside_voice.json, and the website DCF
            // consensus from website_consensus.json — so the Comparison sheet
            // can render them. Null when missing — never a plausible fill.
            // Display-only: never averaged, no buy-below, not blessed.
            Json.Obj se = spTickers.getObj(t);
            changed |= syncNumField(c, "muse_speculation",
                    se == null ? null : se.get("muse_speculation"));
            changed |= syncNumField(c, "nlm_speculation",
                    se == null ? null : se.get("nlm_speculation"));
            Json.Obj oe = ovTickers.getObj(t);
            changed |= syncNumField(c, "outside_voice",
                    oe == null ? null : oe.get("value"));
            Json.Obj we = webTickers.getObj(t);
            changed |= syncNumField(c, "website_consensus",
                    we == null ? null : we.get("consensus"));
            if (changed) {
                touched++;
            }
        }
        // Disagreements: recompute from verified values only (same rule as the
        // dashboard flag). Old hand-written ours_summary preserved per ticker.
        Json.Obj batch = (Json.Obj) ((Json.Obj) Json.parse(readFile(NB_BATCH))).get("values");
        List<String> verified = new ArrayList<>();
        for (Map.Entry<String, Json.Val> e : batch.map.entrySet()) {
            if (e.getValue() instanceof Json.Num) {
                verified.add(e.getKey());
            }
        }
        Map<String, String> oldSum = new LinkedHashMap<>();
        for (Json.Val dv : optList(data, "disagreements")) {
            Json.Obj d = (Json.Obj) dv;
            Json.Val s = d.get("ours_summary");
            oldSum.put(d.getStr("ticker"),
                    (s == null || s instanceof Json.Null) ? null : ((Json.Str) s).v);
        }
        List<Object> newDis = new ArrayList<>();
        for (Json.Val cv : companies) {
            Json.Obj c = (Json.Obj) cv;
            String t = c.getStr("ticker");
            Json.Val f = c.get("fixed");
            Json.Val n = c.get("notebooklm");
            if (f == null || f instanceof Json.Null
                    || !(n instanceof Json.Num) || !verified.contains(t)) {
                continue;
            }
            double fd = numOf(f);
            double nd = numOf(n);
            double div = Math.abs(fd - nd) / Math.abs(nd);
            boolean signFlip = t.equals("IREN") && (fd < 0) != (nd < 0);
            if (div > 0.15 || signFlip) {
                String summary = oldSum.get(t);
                if (summary == null || summary.isEmpty()) {
                    Json.Obj a = c.getObj("assumptions");
                    String src = (a != null && a.has("src") && !(a.get("src") instanceof Json.Null))
                            ? ((Json.Str) a.get("src")).v : "Supplied input dataset.";
                    summary = "Fixed " + rawStr(data, "dcf_asof") + " run inputs: "
                            + firstCodePoints(src, 160);
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("ticker", t);
                e.put("name", c.getStr("name"));
                e.put("ours", f);
                e.put("notebooklm", n);
                e.put("diff", signFlip ? "Sign flip" : Py.fixed(div * 100, 1) + "%");
                e.put("ours_summary", summary);
                e.put("notebooklm_assumptions",
                        "not recorded - NotebookLM returned intrinsic values only");
                newDis.add(e);
            }
        }
        Json.Arr disArr = new Json.Arr();
        for (Object o : newDis) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            Json.Obj e = new Json.Obj();
            e.put("ticker", new Json.Str((String) m.get("ticker")));
            e.put("name", new Json.Str((String) m.get("name")));
            e.put("ours", (Json.Val) m.get("ours"));
            e.put("notebooklm", (Json.Val) m.get("notebooklm"));
            e.put("diff", new Json.Str((String) m.get("diff")));
            e.put("ours_summary", new Json.Str((String) m.get("ours_summary")));
            e.put("notebooklm_assumptions",
                    new Json.Str((String) m.get("notebooklm_assumptions")));
            disArr.items.add(e);
        }
        data.put("disagreements", disArr);
        if (CHECK) {
            note("  would sync " + touched + " companies, " + newDis.size() + " disagreements");
            return;
        }
        Files.writeString(Paths.get(WD), Json.dumps(data) + "\n", StandardCharsets.UTF_8);
        note("  synced " + touched + " companies, " + newDis.size()
                + " disagreements -> workbook_data.json");
    }

    /** First n Unicode code points of s (Python s[:n] slices code points). */
    static String firstCodePoints(String s, int n) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < s.length() && count < n; ) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
            count++;
        }
        return sb.toString();
    }

    /** Python data.get(k, '') with None -> 'None' (for f-string interpolation). */
    static String rawStr(Json.Obj o, String k) {
        if (!o.has(k)) {
            return "";
        }
        return scalarStr(o.get(k));
    }

    static void syncScoreboard() throws Exception {
        Json.Obj data = (Json.Obj) Json.parse(readFile(SB));
        @SuppressWarnings("unchecked")
        List<Json.Val> entries = (List<Json.Val>) (List<?>) data.getList("entries");
        Map<String, Boolean> have = new LinkedHashMap<>();
        for (Json.Val ev : entries) {
            Json.Obj e = (Json.Obj) ev;
            have.put(e.getStr("ticker") + "\0" + e.getStr("source"), true);
        }
        Json.Obj wdAll = (Json.Obj) Json.parse(readFile(WD));
        Map<String, Json.Obj> wd = new LinkedHashMap<>();
        for (Json.Val cv : wdAll.getList("companies")) {
            Json.Obj c = (Json.Obj) cv;
            wd.put(c.getStr("ticker"), c);
        }
        Json.Obj crAll = (Json.Obj) Json.parse(readFile(CREATOR));
        Json.Obj creators = crAll.getObj("latest");
        if (creators == null) {
            creators = new Json.Obj();
        }
        Json.Obj analysts = (Json.Obj) Json.parse(readFile(ANALYST));
        String today = LocalDate.now().toString();
        int[] added = {0};

        for (Map.Entry<String, Json.Obj> en : wd.entrySet()) {
            String t = en.getKey();
            Json.Obj c = en.getValue();
            Json.Val fixed = c.get("fixed");
            if (fixed != null && !(fixed instanceof Json.Null)
                    && !numEq(latestValue(entries, t, "ours"), fixed)) {
                addScoreboardEntry(entries, wd, t, "ours", "intrinsic_value", fixed,
                        "our_dcf.json (Muse DCF, mid-year discounting + growth fade)",
                        today);
                added[0]++;
            }
        }
        // Creators + analyst: append-only -- a new entry only when the value changed
        // vs the latest recorded one.
        for (Map.Entry<String, Json.Val> en : creators.map.entrySet()) {
            String t = en.getKey();
            if (!(en.getValue() instanceof Json.Obj)) {
                continue;
            }
            Json.Obj ce = (Json.Obj) en.getValue();
            Json.Val v = ce.get("value");
            if (v == null || v instanceof Json.Null) {
                continue;
            }
            if (numEq(latestValue(entries, t, "creators"), v)) {
                continue;
            }
            addScoreboardEntry(entries, wd, t, "creators", "intrinsic_value", v,
                    "creator_values.json \u2014 " + scalarStr(ce.get("creator")) + ": "
                            + scalarStr(ce.get("type")) + " $" + scalarStr(v)
                            + " (" + scalarStr(ce.get("call_date")) + ")",
                    today);
            added[0]++;
        }
        for (Map.Entry<String, Json.Val> en : analysts.map.entrySet()) {
            String t = en.getKey();
            if (!(en.getValue() instanceof Json.Obj)) {
                continue;
            }
            Json.Obj ae = (Json.Obj) en.getValue();
            Json.Val v = ae.get("target");
            if (v == null || v instanceof Json.Null) {
                continue;
            }
            if (numEq(latestValue(entries, t, "analyst"), v)) {
                continue;
            }
            addScoreboardEntry(entries, wd, t, "analyst", "price_target", v,
                    "analyst_targets.json \u2014 " + scalarStr(ae.get("source"))
                            + " consensus (asof " + scalarStr(ae.get("asof")) + ")",
                    today);
            added[0]++;
        }
        if (CHECK) {
            note(added[0] > 0 ? "  would add " + added[0] + " scoreboard entries"
                    : "  scoreboard complete");
            return;
        }
        data.put("updated", new Json.Str(today));
        // Idea #40: stamp the scoreboard as a derived file. Additive history
        // is untouched; this is metadata, not a value change.
        data.put("_derived", new Json.Str(DERIVED_STAMP));
        Files.writeString(Paths.get(SB), Json.dumps(data) + "\n", StandardCharsets.UTF_8);
        note("  added " + added[0] + " scoreboard entries (additive; history untouched)");
    }

    static void addScoreboardEntry(List<Json.Val> entries, Map<String, Json.Obj> wd,
                                   String t, String source, String valueType, Json.Val value,
                                   String sourceRef, String today) {
        int n = 1;
        for (Json.Val ev : entries) {
            if (((Json.Obj) ev).getStr("ticker").equals(t)) {
                n++;
            }
        }
        Json.Obj e = new Json.Obj();
        e.put("id", new Json.Str("vs-" + t.toLowerCase().replace(".", "")
                + "-" + String.format("%03d", n)));
        e.put("date", new Json.Str(today));
        e.put("ticker", new Json.Str(t));
        Json.Obj wc = wd.get(t);
        e.put("company", new Json.Str(wc == null ? t : wc.getStr("name")));
        e.put("source", new Json.Str(source));
        e.put("value_type", new Json.Str(valueType));
        e.put("value", value);
        e.put("source_ref", new Json.Str(sourceRef));
        e.put("checks", new Json.Arr());
        entries.add(e);
    }

    /** Latest recorded value for (ticker, source); null when none. */
    static Json.Val latestValue(List<Json.Val> entries, String t, String source) {
        Json.Val best = null;
        String bestDate = null;
        for (Json.Val ev : entries) {
            Json.Obj e = (Json.Obj) ev;
            if (!e.getStr("ticker").equals(t) || !e.getStr("source").equals(source)) {
                continue;
            }
            Json.Val v = e.get("value");
            if (v == null || v instanceof Json.Null) {
                continue;
            }
            String d = e.getStr("date");
            if (best == null || d.compareTo(bestDate) > 0) {
                best = v;
                bestDate = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Idea #46: "what changed" rebuild diff.
    //
    // After every rebuild, a short human-readable report (dcf_csv/
    // rebuild_diff.md) lists which published values moved, by how much, and
    // WHY. Each move is classified as one of:
    //   - input change: the company's DCF inputs changed (filing-driven);
    //   - new source: a NotebookLM, creator, or analyst value appeared or
    //     changed upstream;
    //   - methodology change: the builder or methodology doc changed since
    //     the last run (tracked by a SHA-256 marker in a hidden state file);
    //   - unclassified: none of the above matched — labeled, never guessed.
    //
    // Moves are measured previous-successful-run -> now: the state file
    // (.rebuild_diff_state.json) persists each run's published values and
    // upstream fingerprints, so source edits made BEFORE a rebuild starts
    // still classify correctly. On the first run (no state yet) the
    // pre-rebuild snapshot is the fallback baseline, and the report says so.
    //
    // A separate "also corrected" section lists derived-backend files that
    // disagreed with the master before the run and agree now — stale values
    // the rebuild silently fixed. Unchanged values are omitted.
    //
    // The snapshot is taken before the first rebuild step and the report is
    // written after the last one. Both calls are wrapped in try/catch by the
    // caller, so the diff can never break a rebuild.
    // ------------------------------------------------------------------
    static class RebuildDiff {

        /** Report file, quoted by the DCF Files post after each rebuild. */
        static String reportPath() { return ROOT + "/dcf_csv/rebuild_diff.md"; }

        /** Hidden run bookkeeping: methodology hash from the previous run. */
        static String statePath() { return ROOT + "/dcf_csv/.rebuild_diff_state.json"; }

        static String inputsPath() { return ROOT + "/sec_filings/dcf_inputs_all.json"; }

        /** Published values per ticker, plus the upstream fingerprints used to
         *  classify WHY a move happened.
         *
         *  Two layers are captured:
         *   - pub*: the master workbook_data.json fields (fixed, notebooklm,
         *     creator, analyst) — what the comparison sheet shows the user.
         *   - der*: the derived JSON backends (our_dcf.json,
         *     notebooklm_values.json, creator_values.json latest,
         *     analyst_targets.json) — normally identical to pub*; when they
         *     differ, the derived file went stale outside a rebuild.
         *
         *  Moves are measured previous-successful-run -> now (the previous
         *  run's pubs and fingerprints live in .rebuild_diff_state.json),
         *  so source edits made BEFORE a rebuild still classify correctly.
         *  When no previous state exists, the pre-rebuild snapshot is the
         *  fallback baseline for that run only. */
        static class Snapshot {
            // Published (master) values.
            final Map<String, Double> pubDcf = new LinkedHashMap<>();
            final Map<String, Double> pubNb = new LinkedHashMap<>();
            final Map<String, Double> pubFwd = new LinkedHashMap<>();
            final Map<String, Double> pubCreator = new LinkedHashMap<>();
            final Map<String, Double> pubAnalyst = new LinkedHashMap<>();
            // Derived-backend values, for the stale-file check (only the three
            // files this rebuild regenerates: our_dcf.json,
            // notebooklm_values.json and notebooklm_forward.json).
            final Map<String, Double> derDcf = new HashMap<>();
            final Map<String, Double> derNb = new HashMap<>();
            final Map<String, Double> derFwd = new HashMap<>();
            final Map<String, String> inputsFp = new HashMap<>();
            final Map<String, String> nbBatchFp = new HashMap<>();
            final Map<String, String> fwdBatchFp = new HashMap<>();
            final Map<String, String> creatorFp = new HashMap<>();
            final Map<String, String> analystFp = new HashMap<>();
            // "10-K (2026-02-04)" style filing refs, for the why-line.
            final Map<String, String> annualRef = new HashMap<>();
            final Map<String, String> quarterlyRef = new HashMap<>();
            // Creator / analyst provenance, for the why-line.
            final Map<String, String> creatorWho = new HashMap<>();
            final Map<String, String> analystSrc = new HashMap<>();
            String methodHash;
            // Master order, so the report reads in the familiar sequence.
            final List<String> order = new ArrayList<>();
        }

        /** One ticker's baseline: previous-run published values + fingerprints.
         *  Built either from the persisted state file or, on the first run,
         *  from the pre-rebuild snapshot. */
        static class TickBase {
            Double pubDcf, pubNb, pubFwd, pubCreator, pubAnalyst;
            String inputsFp, nbBatchFp, fwdBatchFp, creatorFp, analystFp;
            String qRef, aRef, creatorWho, analystSrc;
        }

        /** The previous successful run, as persisted in .rebuild_diff_state.json. */
        static class PrevState {
            String methodHash;
            final Map<String, TickBase> tickers = new HashMap<>();
        }

        /** One moved (ticker, column), with its classification. */
        static class Move {
            String ticker, col, why, cat, line;
        }

        /** Parse a JSON file; null when missing or unparsable (first run). */
        static Json.Obj readObj(String path) {
            try {
                Json.Val v = Json.parse(readFile(path));
                return v instanceof Json.Obj ? (Json.Obj) v : null;
            } catch (Exception e) {
                return null;
            }
        }

        /** Null-safe number: missing key or JSON null -> null (unlike
         *  getOptNum, which throws on an explicit JSON null). */
        static Double optNum(Json.Obj o, String k) {
            if (o == null) {
                return null;
            }
            Json.Val v = o.get(k);
            if (v == null || v instanceof Json.Null) {
                return null;
            }
            return v instanceof Json.Num ? ((Json.Num) v).d : null;
        }

        /** "10-K (2026-02-04)" from a dcf_inputs_all.json annual/quarterly block. */
        static String filingRef(Json.Obj entry, String key) {
            if (entry == null) {
                return null;
            }
            Json.Obj s = entry.getObj(key);
            if (s == null) {
                return null;
            }
            String form = s.optStr("form", "");
            String date = s.optStr("filing_date", "");
            if (form.isEmpty() && date.isEmpty()) {
                return null;
            }
            return form + " (" + date + ")";
        }

        /** "Couch Investor buy call (2026-09-27)" from a creator_values latest entry. */
        static String creatorLabel(Json.Obj e) {
            if (e == null) {
                return null;
            }
            String creator = e.optStr("creator", "a creator");
            String type = e.optStr("type", "");
            String date = e.optStr("call_date", "");
            String kind;
            switch (type) {
                case "buy_price": kind = "buy call"; break;
                case "target": kind = "target"; break;
                case "fair_value": kind = "fair value"; break;
                case "intrinsic_value": kind = "intrinsic value"; break;
                default: kind = type.isEmpty() ? "value" : type.replace('_', ' ');
            }
            return creator + " " + kind + (date.isEmpty() ? "" : " (" + date + ")");
        }

        static Double entryNum(Json.Obj section, String ticker, String field) {
            return optNum(section == null ? null : section.getObj(ticker), field);
        }

        /** The master workbook_data.json company object for a ticker. */
        static Json.Obj wdCompany(Json.Obj wd, String ticker) {
            if (wd == null) {
                return null;
            }
            for (Json.Val cv : optList(wd, "companies")) {
                Json.Obj c = (Json.Obj) cv;
                if (ticker.equals(c.optStr("ticker", ""))) {
                    return c;
                }
            }
            return null;
        }

        /** Fingerprint of one ticker's upstream entry: SHA-256 of its JSON
         *  dump. A hash (not the raw dump) keeps the state file small and
         *  avoids any escaping round-trip issues. */
        static String entryFp(Json.Obj section, String ticker) {
            if (section == null) {
                return null;
            }
            Json.Val v = section.get(ticker);
            return v == null ? null : sha256(Json.dumps(v));
        }

        static String sha256(String s) {
            try {
                java.security.MessageDigest md =
                        java.security.MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(
                        s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : d) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }

        /** Capture the published values and their upstream fingerprints. */
        static Snapshot takeSnapshot() {
            Snapshot s = new Snapshot();
            Json.Obj our = readObj(OUR);
            Json.Obj nbf = readObj(NBF);
            Json.Obj fwd = readObj(FWD);
            Json.Obj cr = readObj(CREATOR);
            Json.Obj an = readObj(ANALYST);
            Json.Obj inputs = readObj(inputsPath());
            Json.Obj batch = readObj(NB_BATCH);
            Json.Obj fwdBatch = readObj(FWD_BATCH);
            Json.Obj wd = readObj(WD);

            Json.Obj ourVals = our == null ? null : our.getObj("values");
            Json.Obj nbfVals = nbf == null ? null : nbf.getObj("values");
            Json.Obj fwdVals = fwd == null ? null : fwd.getObj("values");
            Json.Obj crLatest = cr == null ? null : cr.getObj("latest");
            Json.Obj inCos = inputs == null ? null : inputs.getObj("companies");
            Json.Obj batchVals = batch == null ? null : batch.getObj("values");
            Json.Obj fwdBatchVals = fwdBatch == null ? null : fwdBatch.getObj("values");

            Set<String> tickers = new LinkedHashSet<>();
            if (wd != null) {
                for (Json.Val cv : optList(wd, "companies")) {
                    String t = ((Json.Obj) cv).getStr("ticker");
                    s.order.add(t);
                    tickers.add(t);
                }
            }
            for (Json.Obj sec : new Json.Obj[]{ourVals, nbfVals, fwdVals, crLatest, an, inCos, batchVals, fwdBatchVals}) {
                if (sec != null) {
                    for (Map.Entry<String, Json.Val> e : sec.map.entrySet()) {
                        tickers.add(e.getKey());
                    }
                }
            }

            for (String t : tickers) {
                // Published: the master fields behind the comparison sheet.
                Json.Obj wc = wdCompany(wd, t);
                s.pubDcf.put(t, optNum(wc, "fixed"));
                s.pubNb.put(t, optNum(wc, "notebooklm"));
                s.pubFwd.put(t, optNum(wc, "notebooklm_forward"));
                s.pubCreator.put(t, optNum(wc, "creator"));
                s.pubAnalyst.put(t, optNum(wc, "analyst"));
                // Derived backends: normally identical to the master; when
                // they differ, the file went stale outside a rebuild.
                s.derDcf.put(t, entryNum(ourVals, t, "value"));
                s.derNb.put(t, entryNum(nbfVals, t, "value"));
                s.derFwd.put(t, entryNum(fwdVals, t, "value"));
                s.inputsFp.put(t, entryFp(inCos, t));
                s.nbBatchFp.put(t, entryFp(batchVals, t));
                s.fwdBatchFp.put(t, entryFp(fwdBatchVals, t));
                s.creatorFp.put(t, entryFp(crLatest, t));
                s.analystFp.put(t, entryFp(an, t));
                Json.Obj inEntry = inCos == null ? null : inCos.getObj(t);
                s.annualRef.put(t, filingRef(inEntry, "annual"));
                s.quarterlyRef.put(t, filingRef(inEntry, "quarterly"));
                s.creatorWho.put(t, creatorLabel(crLatest == null ? null : crLatest.getObj(t)));
                Json.Obj anEntry = an == null ? null : an.getObj(t);
                s.analystSrc.put(t, anEntry == null ? "" : anEntry.optStr("source", ""));
            }
            s.methodHash = methodologyHash();
            return s;
        }

        /** Read the previous successful run's baseline.
         *  Returns null when there is no usable baseline; {@link #prevStatus}
         *  tells whether the state file was simply absent (first run) or
         *  present but unreadable (corrupt baseline). */
        enum PrevStateStatus { OK, MISSING, UNREADABLE }
        static PrevStateStatus prevStatus = PrevStateStatus.MISSING;

        static PrevState loadPrevState() {
            if (!Files.exists(Paths.get(statePath()))) {
                prevStatus = PrevStateStatus.MISSING;
                return null;
            }
            try {
                Json.Val v = Json.parse(readFile(statePath()));
                if (!(v instanceof Json.Obj)) {
                    throw new IllegalStateException(
                            "state file is not a JSON object");
                }
                Json.Obj o = (Json.Obj) v;
                Double fmtV = optNum(o, "v");
                if (fmtV == null || fmtV != 3) {
                    throw new IllegalStateException(
                            "state format v" + fmtV + " (expected v3)");
                }
                PrevState ps = new PrevState();
                ps.methodHash = o.optStr("methodHash", null);
                Json.Obj tickers = o.getObj("tickers");
                if (tickers != null) {
                    for (Map.Entry<String, Json.Val> e : tickers.map.entrySet()) {
                        if (!(e.getValue() instanceof Json.Obj)) {
                            continue;
                        }
                        Json.Obj to = (Json.Obj) e.getValue();
                        TickBase tb = new TickBase();
                        tb.pubDcf = optNum(to, "pubDcf");
                        tb.pubNb = optNum(to, "pubNb");
                        tb.pubFwd = optNum(to, "pubFwd");
                        tb.pubCreator = optNum(to, "pubCreator");
                        tb.pubAnalyst = optNum(to, "pubAnalyst");
                        tb.inputsFp = to.optStr("inputsFp", null);
                        tb.nbBatchFp = to.optStr("nbBatchFp", null);
                        tb.fwdBatchFp = to.optStr("fwdBatchFp", null);
                        tb.creatorFp = to.optStr("creatorFp", null);
                        tb.analystFp = to.optStr("analystFp", null);
                        tb.qRef = to.optStr("qRef", null);
                        tb.aRef = to.optStr("aRef", null);
                        tb.creatorWho = to.optStr("creatorWho", null);
                        tb.analystSrc = to.optStr("analystSrc", null);
                        ps.tickers.put(e.getKey(), tb);
                    }
                }
                prevStatus = PrevStateStatus.OK;
                return ps;
            } catch (Exception e) {
                prevStatus = PrevStateStatus.UNREADABLE;
                return null;
            }
        }

        /** Build a baseline row from the pre-rebuild snapshot (first-run fallback). */
        static TickBase baseFromSnapshot(Snapshot s, String t) {
            TickBase tb = new TickBase();
            tb.pubDcf = s.pubDcf.get(t);
            tb.pubNb = s.pubNb.get(t);
            tb.pubFwd = s.pubFwd.get(t);
            tb.pubCreator = s.pubCreator.get(t);
            tb.pubAnalyst = s.pubAnalyst.get(t);
            tb.inputsFp = s.inputsFp.get(t);
            tb.nbBatchFp = s.nbBatchFp.get(t);
            tb.fwdBatchFp = s.fwdBatchFp.get(t);
            tb.creatorFp = s.creatorFp.get(t);
            tb.analystFp = s.analystFp.get(t);
            tb.qRef = s.quarterlyRef.get(t);
            tb.aRef = s.annualRef.get(t);
            tb.creatorWho = s.creatorWho.get(t);
            tb.analystSrc = s.analystSrc.get(t);
            return tb;
        }

        /**
         * SHA-256 over the builder + methodology files. When this moves, any
         * otherwise-unexplained Muse DCF move is labeled a methodology change.
         * Missing files are skipped, never fatal.
         */
        static String methodologyHash() {
            String[] files = {
                ROOT + "/your_files/dcf-valuations/.src/GenerateDcfWorkbook.java",
                ROOT + "/dcf_csv/DcfModel.java",
                ROOT + "/dcf_csv/RecomputeColumns.java",
                ROOT + "/dcf_csv/VerifyBase.java",
                ROOT + "/dcf_csv/RebuildAll.java",
                ROOT + "/research_notes/dcf_methodology.md",
                ROOT + "/dcf_csv/sensitivity_method.md",
            };
            try {
                java.security.MessageDigest md =
                        java.security.MessageDigest.getInstance("SHA-256");
                for (String f : files) {
                    Path p = Paths.get(f);
                    if (Files.exists(p)) {
                        md.update(Files.readAllBytes(p));
                    }
                    md.update((byte) 0); // separator: renames can't alias hashes
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }

        static boolean numEq(Double x, Double y) {
            if (x == null || y == null) {
                return x == y;
            }
            return Double.compare(x, y) == 0;
        }

        static String money(Double v) {
            return v == null ? "\u2014" : "$" + disp(v);
        }

        static String pctMove(Double oldV, Double newV) {
            if (oldV == null || newV == null || oldV == 0.0) {
                return "";
            }
            double p = (newV - oldV) / Math.abs(oldV) * 100.0;
            return " (" + String.format("%+.1f%%", p) + ")";
        }

        // -- classification: each returns {category, why-line} -------------

        // -- classification: each returns {category, why-line} -------------
        // oldB = previous successful run (or the pre-rebuild snapshot on the
        // first run); newB = this run's post-rebuild values. Fingerprints are
        // compared old-run -> new-run, so upstream edits made BEFORE the
        // rebuild still classify correctly.

        static String[] whyDcf(String t, TickBase oldB, TickBase newB, boolean methodChanged) {
            if (!Objects.equals(oldB.inputsFp, newB.inputsFp)) {
                if (!Objects.equals(oldB.qRef, newB.qRef) && newB.qRef != null) {
                    return new String[]{"input", "input change — new " + newB.qRef + " filed"};
                }
                if (!Objects.equals(oldB.aRef, newB.aRef) && newB.aRef != null) {
                    return new String[]{"input", "input change — new " + newB.aRef + " filed"};
                }
                return new String[]{"input", "input change (DCF inputs updated)"};
            }
            if (methodChanged) {
                return new String[]{"method", "methodology change (builder/methodology updated)"};
            }
            return new String[]{"unclass", "unclassified"};
        }

        static String[] whyNotebooklm(TickBase oldB, TickBase newB) {
            if (!Objects.equals(oldB.nbBatchFp, newB.nbBatchFp)) {
                return new String[]{"source", "new NotebookLM value"};
            }
            return new String[]{"unclass", "unclassified"};
        }

        static String[] whyForward(TickBase oldB, TickBase newB) {
            if (!Objects.equals(oldB.fwdBatchFp, newB.fwdBatchFp)) {
                return new String[]{"source", "new NotebookLM forward-tilt value"};
            }
            return new String[]{"unclass", "unclassified"};
        }

        static String[] whyCreator(TickBase oldB, TickBase newB) {
            if (!Objects.equals(oldB.creatorFp, newB.creatorFp)) {
                String who = newB.creatorWho;
                return new String[]{"source", who == null ? "creator value changed" : who};
            }
            return new String[]{"unclass", "unclassified"};
        }

        static String[] whyAnalyst(TickBase oldB, TickBase newB) {
            if (!Objects.equals(oldB.analystFp, newB.analystFp)) {
                String src = newB.analystSrc;
                return new String[]{"source", "analyst target updated"
                        + (src == null || src.isEmpty() ? "" : " (" + src + ")")};
            }
            return new String[]{"unclass", "unclassified"};
        }

        static void diffCol(List<Move> moves, String ticker, String col,
                            Double oldV, Double newV, String[] why) {
            if (numEq(oldV, newV)) {
                return;
            }
            Move m = new Move();
            m.ticker = ticker;
            m.col = col;
            m.cat = why[0];
            m.why = why[1];
            m.line = "- " + ticker + " · " + col + ": " + money(oldV)
                    + " → " + money(newV) + pctMove(oldV, newV) + " — " + why[1];
            moves.add(m);
        }

        static String summaryLine(List<Move> moves, List<String> staleNotes) {
            Map<String, Integer> n = new LinkedHashMap<>();
            for (Move m : moves) {
                n.put(m.cat, n.getOrDefault(m.cat, 0) + 1);
            }
            List<String> parts = new ArrayList<>();
            String[][] labels = {
                {"input", "input change", "input changes"},
                {"source", "new source value", "new source values"},
                {"method", "methodology change", "methodology changes"},
                {"unclass", "unclassified", "unclassified"},
            };
            for (String[] l : labels) {
                int c = n.getOrDefault(l[0], 0);
                if (c > 0) {
                    parts.add(c + " " + (c == 1 ? l[1] : l[2]));
                }
            }
            int total = moves.size();
            String head = "_" + total + (total == 1 ? " move" : " moves");
            if (!parts.isEmpty()) {
                head += ": " + String.join(" · ", parts);
            }
            if (!staleNotes.isEmpty()) {
                head += " · " + staleNotes.size()
                        + (staleNotes.size() == 1 ? " stale file corrected"
                                : " stale files corrected");
            }
            return head + "_";
        }

        /**
         * Compare the previous successful run's published values (from
         * .rebuild_diff_state.json) against the current files and write
         * dcf_csv/rebuild_diff.md. On the first run (no state file yet) the
         * pre-rebuild snapshot is the fallback baseline, and the report says
         * so honestly instead of fabricating a diff.
         *
         * When the state file exists but is unreadable, the diff is
         * genuinely unavailable — the report says so, the rebuild still
         * succeeds, and a fresh baseline is saved so the next run is healthy.
         *
         * A separate "also corrected" section lists derived-backend files
         * that disagreed with the master before this run and agree now
         * (stale values the rebuild silently fixed).
         */
        static void writeReport(Snapshot before) throws Exception {
            String today = LocalDate.now().toString();
            List<String> lines = new ArrayList<>();
            lines.add("# What changed — rebuild " + today);
            lines.add("");
            int moveCount = 0;
            Snapshot after = takeSnapshot();
            PrevState prev = loadPrevState();
            boolean firstRun = prev == null
                    && prevStatus == PrevStateStatus.MISSING;
            boolean stateUnreadable = prev == null
                    && prevStatus == PrevStateStatus.UNREADABLE;
            if (stateUnreadable) {
                lines.add("The change report was unavailable for this run "
                        + "(the previous baseline, .rebuild_diff_state.json, "
                        + "was present but unreadable).");
                lines.add("");
                lines.add("_A fresh baseline was saved from this run, so the "
                        + "next rebuild will report moves normally._");
                saveState(after);
            } else if (before == null && firstRun) {
                lines.add("The change report was unavailable for this run "
                        + "(no pre-rebuild snapshot and no previous baseline).");
            } else {
                // Baseline: previous run's persisted values, else the
                // pre-rebuild snapshot (first run only).
                Map<String, TickBase> base = new LinkedHashMap<>();
                String baseMethodHash;
                if (!firstRun) {
                    base.putAll(prev.tickers);
                    baseMethodHash = prev.methodHash;
                } else {
                    for (String t : before.order) {
                        base.put(t, baseFromSnapshot(before, t));
                    }
                    baseMethodHash = before.methodHash;
                }
                boolean methodChanged = baseMethodHash != null
                        && after.methodHash != null
                        && !baseMethodHash.equals(after.methodHash);
                List<Move> moves = new ArrayList<>();
                Set<String> tickers = new LinkedHashSet<>(base.keySet());
                tickers.addAll(after.order);
                for (String t : tickers) {
                    TickBase o = base.get(t);
                    if (o == null) {
                        o = new TickBase(); // brand-new ticker: old = blank
                    }
                    TickBase nb = baseFromSnapshot(after, t);
                    diffCol(moves, t, "Muse DCF",
                            o.pubDcf, nb.pubDcf,
                            whyDcf(t, o, nb, methodChanged));
                    diffCol(moves, t, "NotebookLM",
                            o.pubNb, nb.pubNb,
                            whyNotebooklm(o, nb));
                    diffCol(moves, t, "Forward tilt",
                            o.pubFwd, nb.pubFwd,
                            whyForward(o, nb));
                    diffCol(moves, t, "Creator values",
                            o.pubCreator, nb.pubCreator,
                            whyCreator(o, nb));
                    diffCol(moves, t, "Analyst target",
                            o.pubAnalyst, nb.pubAnalyst,
                            whyAnalyst(o, nb));
                }
                moveCount = moves.size();
                // Stale derived files the rebuild corrected: the backend
                // disagreed with the master before, agrees now.
                // Stale derived files the rebuild corrected: our_dcf.json,
                // notebooklm_values.json and notebooklm_forward.json are regenerated
                // from the master by this rebuild, so a pre-run disagreement means
                // the backend went stale outside a rebuild. (creator_values.json and
                // analyst_targets.json are upstream sources synced INTO the
                // master — a pre-run disagreement there is just a pending
                // propagation, already covered by the move line.)
                // The note only fires when the published value did NOT move;
                // otherwise the move line already tells the story.
                List<String> staleNotes = new ArrayList<>();
                if (before != null) {
                    staleCheck(staleNotes, moves, "Muse DCF", "our_dcf.json",
                            before.derDcf, before.pubDcf,
                            after.derDcf, after.pubDcf);
                    staleCheck(staleNotes, moves, "NotebookLM",
                            "notebooklm_values.json", before.derNb, before.pubNb,
                            after.derNb, after.pubNb);
                    staleCheck(staleNotes, moves, "Forward tilt",
                            "notebooklm_forward.json", before.derFwd, before.pubFwd,
                            after.derFwd, after.pubFwd);
                }
                if (moves.isEmpty() && staleNotes.isEmpty()) {
                    lines.add("No values moved in this rebuild.");
                } else {
                    if (!moves.isEmpty()) {
                        lines.add(summaryLine(moves, staleNotes));
                        lines.add("");
                        for (Move m : moves) {
                            lines.add(m.line);
                        }
                    } else {
                        lines.add("_" + staleNotes.size()
                                + (staleNotes.size() == 1 ? " stale file corrected"
                                        : " stale files corrected") + "_");
                    }
                    if (!staleNotes.isEmpty()) {
                        lines.add("");
                        lines.add("Also corrected this run (stale derived files):");
                        lines.add("");
                        lines.addAll(staleNotes);
                    }
                }
                if (firstRun) {
                    lines.add("");
                    lines.add("_First run with change tracking — moves are "
                            + "measured against the pre-rebuild snapshot; "
                            + "the next rebuild compares against this run._");
                }
                saveState(after);
            }
            lines.add("");
            lines.add("_" + DERIVED_STAMP + "_");
            writeText(reportPath(), String.join("\n", lines) + "\n");
            note("  [diff] " + (stateUnreadable ? "unavailable (state unreadable)"
                    : before == null && firstRun
                    ? "no diff (no baseline)"
                    : moveCount + (moveCount == 1 ? " move" : " moves"))
                    + " — " + Paths.get(reportPath()).getFileName());
        }

        /** One stale-file note per ticker whose derived backend disagreed
         *  with the master before this run but agrees now — and whose
         *  published value did not move (otherwise the move line already
         *  covers it). */
        static void staleCheck(List<String> notes, List<Move> moves,
                               String col, String file,
                               Map<String, Double> derBefore,
                               Map<String, Double> pubBefore,
                               Map<String, Double> derAfter,
                               Map<String, Double> pubAfter) {
            Set<String> moved = new HashSet<>();
            for (Move m : moves) {
                if (m.col.equals(col)) {
                    moved.add(m.ticker);
                }
            }
            Set<String> tickers = new LinkedHashSet<>(derBefore.keySet());
            tickers.addAll(pubBefore.keySet());
            for (String t : tickers) {
                Double db = derBefore.get(t), pb = pubBefore.get(t);
                Double da = derAfter.get(t), pa = pubAfter.get(t);
                if (db != null && !numEq(db, pb) && numEq(da, pa)
                        && numEq(pb, pa) && !moved.contains(t)) {
                    notes.add("- " + t + " · " + file + " had a stale "
                            + money(db) + " — corrected to " + money(pa));
                }
            }
        }

        /** Persist this run as the next run's baseline: published values,
         *  upstream fingerprints, and the methodology hash. */
        static void saveState(Snapshot after) throws Exception {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n \"v\": 3,\n");
            sb.append(" \"updated\": \"").append(LocalDate.now()).append("\",\n");
            sb.append(" \"methodHash\": ")
                    .append(after.methodHash == null ? "null"
                            : "\"" + after.methodHash + "\"")
                    .append(",\n");
            sb.append(" \"tickers\": {\n");
            boolean first = true;
            for (String t : after.order) {
                TickBase b = baseFromSnapshot(after, t);
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("  \"").append(t).append("\": {");
                sb.append("\"pubDcf\": ").append(jNum(b.pubDcf)).append(", ");
                sb.append("\"pubNb\": ").append(jNum(b.pubNb)).append(", ");
                sb.append("\"pubFwd\": ").append(jNum(b.pubFwd)).append(", ");
                sb.append("\"pubCreator\": ").append(jNum(b.pubCreator)).append(", ");
                sb.append("\"pubAnalyst\": ").append(jNum(b.pubAnalyst));
                sb.append(", \"inputsFp\": ").append(jStr(b.inputsFp));
                sb.append(", \"nbBatchFp\": ").append(jStr(b.nbBatchFp));
                sb.append(", \"fwdBatchFp\": ").append(jStr(b.fwdBatchFp));
                sb.append(", \"creatorFp\": ").append(jStr(b.creatorFp));
                sb.append(", \"analystFp\": ").append(jStr(b.analystFp));
                sb.append(", \"qRef\": ").append(jStr(b.qRef));
                sb.append(", \"aRef\": ").append(jStr(b.aRef));
                sb.append(", \"creatorWho\": ").append(jStr(b.creatorWho));
                sb.append(", \"analystSrc\": ").append(jStr(b.analystSrc));
                sb.append("}");
            }
            sb.append("\n }\n}\n");
            writeText(statePath(), sb.toString());
        }

        static String jNum(Double v) {
            return v == null ? "null" : v.toString();
        }

        static String jStr(String s) {
            if (s == null) {
                return "null";
            }
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r")
                    .replace("\t", "\\t") + "\"";
        }
    }
}