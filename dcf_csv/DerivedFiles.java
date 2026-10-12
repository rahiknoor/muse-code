import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * DerivedFiles.java — idea #40: derived-file registry and input guard.
 *
 * <p>Why this exists: on 2026-09-27 a background job read the EXTRACTED
 * JSONs ({@code our_dcf.json}, {@code notebooklm_values.json}) as if they
 * were inputs and rebuilt everything from stale data, silently reverting
 * good fixes. Those files are DERIVED — they are regenerated from the
 * master on every rebuild and must never flow backwards into it.
 *
 * <p>Two halves to the guard:
 * <ol>
 *   <li>Every derived file carries an explicit stamp
 *       ("derived from workbook_data.json via RebuildAll — do not edit,
 *       do not read as input") — as a {@code _derived} field in JSON, in
 *       the title row of CSVs, in the workbook's document properties.</li>
 *   <li>This program classifies files: {@code --check-inputs} fails
 *       non-zero if any listed file is derived. Automated jobs run it
 *       BEFORE reading a DCF JSON/CSV as an input.</li>
 * </ol>
 *
 * <p>The one and only genuine master is
 * {@code your_files/dcf-valuations/.src/workbook_data.json}. The genuine
 * inputs are: {@code workbook_data.json}, {@code dcf_inputs_all.json},
 * the verified NotebookLM batch files (grounded + forward tilt),
 * {@code price_cache.json}, {@code growth_outlook.json} (Muse's per-ticker
 * growth assessment from the deep-research forward briefs), and the SEC
 * filings themselves.
 *
 * <p>Exemption: {@code RebuildAll.java} is the single sanctioned pipeline
 * that regenerates derived files from the master inside one run; this
 * guard is for everything else (cron workers, ad-hoc scripts, future
 * agents) that might be tempted to treat a derived file as an input.
 *
 * <p>Rahik's cross-check condition (idea #40 approval): a number enters the
 * master only after it is cross-checked and its provenance recorded —
 * source location, filing form, date, section, page. The existing gates
 * stay in the path: {@code VerifyBase.java} (idea #32, filing-figure
 * cross-check before any DCF base is written) and the fail-closed
 * NotebookLM provenance guard inside {@code RebuildAll.java}.
 *
 * <p>Usage:
 * <pre>
 *   java DerivedFiles.java --check-inputs &lt;file&gt;...   # exit 0 = genuine, 1 = derived (refused)
 *   java DerivedFiles.java --list                       # print the derived registry
 * </pre>
 *
 * <p>Written in plain Java 17 so Rahik can read it himself.
 */
public class DerivedFiles {

    /**
     * The stamp every derived DCF file must carry. Written with a
     * {@code \u2014} escape (em dash) so this source file stays pure ASCII
     * and compiles under any locale — the JVM writes the real em dash
     * into the files. Keep this literal in sync with the copies in
     * RebuildAll.java, BuildCreatorValues.java and GenerateDcfWorkbook.java.
     */
    public static final String STAMP =
            "derived from workbook_data.json via RebuildAll \u2014 do not edit, do not read as input";

    /**
     * Every file RebuildAll regenerates, as workspace-relative paths.
     * The Atlas stock notes (atlas/Stocks/*.md) are matched by prefix in
     * {@link #isDerived} instead of being listed one by one.
     */
    static final List<String> DERIVED = List.of(
            "dcf_csv/our_dcf.json",
            "dcf_csv/notebooklm_values.json",
            "dcf_csv/notebooklm_forward.json",
            "dcf_csv/dcf-comparison.csv",
            "dcf_csv/dcf-comparison.xlsx",
            "dcf_csv/dcf-assumptions.csv",
            "dcf_csv/dcf-disagreements.csv",
            "dcf_csv/dcf-sources.csv",
            "dcf_csv/dcf-history.csv",
            "dcf_csv/creator_values.json",
            "dcf_csv/outside_voice.json",
            "dcf_csv/speculation_price.json",
            "dcf_csv/DCF Valuation Model.pdf",
            "valuation_scoreboard/scoreboard.json",
            "your_files/dcf-valuations/DCF Valuation Model.xlsx",
            "atlas/Valuation Dashboard.md");

    /** Human-readable list of the genuine (non-derived) DCF inputs. */
    static final String GENUINE_INPUTS =
            "workbook_data.json, dcf_inputs_all.json, the verified NotebookLM batch files " +
            "(grounded + forward tilt), " + "price_cache.json, growth_outlook.json, " +
            "website_consensus.json (from hand-transcribed website_values raws), SEC filings";

    /**
     * Workspace root. Honors DCF_WORKSPACE_ROOT, else $HOME/workspace.
     * NOTE: never System.getProperty("user.home") here — on this machine it
     * returns /root while $HOME is /home/hatch.
     */
    static Path workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        if (e != null && !e.isEmpty()) {
            return Path.of(e);
        }
        return Path.of(System.getenv("HOME") + "/workspace");
    }

    /**
     * Is this file a derived (regenerated) file? True when either
     * <ol>
     *   <li>its workspace-relative path is in the {@link #DERIVED} registry
     *       (or it is an Atlas stock note under atlas/Stocks/), or</li>
     *   <li>its contents carry the {@link #STAMP} or a {@code "_derived"}
     *       JSON field — catches copies renamed outside the registry.</li>
     * </ol>
     */
    static boolean isDerived(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        String rel;
        try {
            rel = workspaceRoot().relativize(abs).toString().replace('\\', '/');
        } catch (IllegalArgumentException notUnderRoot) {
            rel = abs.toString();
        }
        if (DERIVED.contains(rel)) {
            return true;
        }
        if (rel.startsWith("atlas/Stocks/") && rel.endsWith(".md")) {
            return true;
        }
        // Content backstop: sniff the head of the file for the stamp or the
        // _derived JSON field (whitespace-insensitive, so any JSON dumper's
        // spacing still matches).
        try {
            byte[] raw = Files.readAllBytes(abs);
            int n = Math.min(raw.length, 8192);
            String head = new String(raw, 0, n, StandardCharsets.UTF_8);
            String flat = head.replaceAll("\\s", "");
            return head.contains(STAMP) || flat.contains("\"_derived\":");
        } catch (Exception unreadable) {
            return false;
        }
    }

    static void usage() {
        System.out.println("DerivedFiles.java — idea #40 derived-file input guard.");
        System.out.println();
        System.out.println("  java DerivedFiles.java --check-inputs <file>...");
        System.out.println("      Exit 0 when every file is a genuine input.");
        System.out.println("      Exit 1 (REFUSED) naming each file that is derived —");
        System.out.println("      a derived file must never be used as a rebuild input.");
        System.out.println("  java DerivedFiles.java --list");
        System.out.println("      Print the registry of derived files.");
        System.out.println();
        System.out.println("Genuine inputs: " + GENUINE_INPUTS + ".");
        System.out.println("Master file: your_files/dcf-valuations/.src/workbook_data.json");
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            usage();
            return;
        }
        if (args[0].equals("--list")) {
            for (String d : DERIVED) {
                System.out.println(d);
            }
            System.out.println("atlas/Stocks/*.md  (every Atlas stock note)");
            return;
        }
        if (!args[0].equals("--check-inputs")) {
            usage();
            System.exit(2);
            return;
        }
        if (args.length < 2) {
            System.err.println("--check-inputs needs at least one file.");
            System.exit(2);
        }
        List<String> bad = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            Path p = Path.of(args[i]);
            if (!p.isAbsolute()) {
                p = workspaceRoot().resolve(args[i]);
            }
            if (!Files.exists(p)) {
                System.err.println("warning: not found (cannot verify): " + p);
                continue;
            }
            if (isDerived(p)) {
                bad.add(p.toString());
            }
        }
        if (!bad.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("REFUSED: derived file(s) supplied as input — refusing to proceed.\n");
            for (String b : bad) {
                sb.append("  DERIVED: ").append(b).append('\n');
            }
            sb.append("Each carries the stamp \"").append(STAMP).append("\".\n");
            sb.append("A derived file must never be used as an input to compute values\n");
            sb.append("for the master file — that is the silent stale-revert bug (idea #40).\n");
            sb.append("Genuine DCF inputs are: ").append(GENUINE_INPUTS).append(".\n");
            sb.append("If you need current values, read workbook_data.json (the master),\n");
            sb.append("never a derived file.");
            System.err.println(sb);
            System.exit(1);
        }
        System.out.println("OK: " + (args.length - 1)
                + " file(s) are genuine inputs (none are derived).");
    }
}
