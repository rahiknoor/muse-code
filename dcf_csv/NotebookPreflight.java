import java.nio.file.*;
import java.util.*;

/**
 * NotebookPreflight — verify a NotebookLM notebook holds the sources a browser
 * task needs BEFORE the task spawns. Tonight's lesson (2026-10-09): the
 * speculation browser task was pointed at the methodology notebook, which
 * holds no 10-Ks. NotebookLM correctly refused. This check makes that
 * mistake impossible — the spawn fails here, before the browser opens.
 *
 * Usage:
 *   java NotebookPreflight.java --notebook main --tickers NVDA,META --source-type 10K
 *   java NotebookPreflight.java --notebook overflow --tickers NVDA --source-type 10Q
 *
 * Exit 0: all tickers have their required source in the notebook.
 * Exit 1: anything missing — prints exactly what's missing and for which ticker.
 *
 * Known notebooks: main (Stock Research SEC Filings — annuals),
 *   overflow (Stock Research SEC Filings (Overflow) — interims).
 *   The methodology notebook is deliberately NOT a valid target for filings-based
 *   tasks — requesting it fails loudly with the reason.
 */
public class NotebookPreflight {
    static final String LAYOUT =
        System.getenv("HOME") + "/workspace/goals/quarterly-sec-filing-watch-with-dcf-updates/hidden_files/notebooklm_layout.json";

    public static void main(String[] args) throws Exception {
        String notebook = null, tickers = null, sourceType = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--notebook")) notebook = args[i + 1];
            if (args[i].equals("--tickers")) tickers = args[i + 1];
            if (args[i].equals("--source-type")) sourceType = args[i + 1];
        }
        if (notebook == null || tickers == null || sourceType == null) {
            System.err.println("Usage: java NotebookPreflight.java --notebook <main|overflow> --tickers T1,T2 --source-type <10K|10Q|20F|6K|transcript>");
            System.exit(1);
        }

        // The methodology notebook holds docs, not filings. Fail loudly.
        if (notebook.equalsIgnoreCase("methodology")) {
            System.err.println("PREFLIGHT FAIL: 'methodology' notebook holds methodology docs only — no SEC filings. "
                + "Use --notebook main (annuals) or --notebook overflow (interims) for filings-based tasks. "
                + "(Lesson 2026-10-09: speculation task pointed here and NotebookLM refused.)");
            System.exit(1);
        }
        if (!notebook.equals("main") && !notebook.equals("overflow")) {
            System.err.println("PREFLIGHT FAIL: unknown notebook '" + notebook + "'. Known: main, overflow.");
            System.exit(1);
        }

        String json = Files.readString(Path.of(LAYOUT));
        List<String> missing = new ArrayList<>();
        for (String t : tickers.split(",")) {
            t = t.trim();
            if (t.isEmpty()) continue;
            if (!hasSource(json, notebook, t, sourceType)) {
                missing.add(t);
            }
        }
        if (!missing.isEmpty()) {
            System.err.println("PREFLIGHT FAIL: notebook '" + notebook + "' is missing " + sourceType
                + " sources for: " + String.join(", ", missing));
            System.exit(1);
        }
        System.out.println("PREFLIGHT OK: notebook '" + notebook + "' holds " + sourceType
            + " sources for all " + tickers.split(",").length + " tickers.");
    }

    /** Tolerant match: source filenames carry ticker fragments, CIKs, or accession dates. */
    static boolean hasSource(String json, String notebook, String ticker, String sourceType) {
        // Find the notebook's sources array in the JSON (simple scan — no full parser needed).
        String nbKey = "\"" + notebook + "\"";
        int nbIdx = json.indexOf(nbKey);
        if (nbIdx < 0) return false;
        int srcIdx = json.indexOf("\"sources\"", nbIdx);
        if (srcIdx < 0) return false;
        int arrStart = json.indexOf('[', srcIdx);
        int arrEnd = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return false;
        String arr = json.substring(arrStart, arrEnd);

        String t = ticker.toLowerCase().replace(".", "");
        String[] needForms;
        if (sourceType.equalsIgnoreCase("10K")) needForms = new String[]{"10-k", "10k", "20-f", "20f"};
        else if (sourceType.equalsIgnoreCase("10Q")) needForms = new String[]{"10-q", "10q", "6-k", "6k"};
        else needForms = new String[]{sourceType.toLowerCase()};

        // A source counts if it names the ticker AND names a matching form.
        // Scan per quoted string to avoid cross-entry false positives.
        for (String part : arr.split("\"")) {
            String p = part.toLowerCase();
            if (!p.contains(t)) continue;
            for (String f : needForms) {
                if (p.contains(f)) return true;
            }
        }
        return false;
    }
}
