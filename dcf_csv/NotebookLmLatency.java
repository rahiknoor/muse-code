import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * NotebookLM latency stopwatch.
 *
 * <p>The probe itself ("List the titles of all sources currently in this
 * notebook.") goes through a logged-in browser task — Java cannot drive
 * NotebookLM. This tool keeps the timing log and judges degradation, so
 * slowness is a measured fact, not a feeling (Rahik, 2026-09-28).
 *
 * <p>Usage:
 * <pre>
 *   java NotebookLmLatency.java record filings 42 "sunday probe"
 *   java NotebookLmLatency.java report
 * </pre>
 *
 * <p>Verdict rule: DEGRADED when the latest probe is slower than twice the
 * baseline AND slower than 90 seconds (avoids flagging noise on fast
 * baselines). Baseline = median of the first 3 <i>successful</i> probes for
 * the notebook — failed probes ("signed out", "abort:") never seed
 * baselines and never count as degradation; they measure the block, not
 * the notebook (Rahik, 2026-10-04: the first three probes were all
 * sign-out fast-fails and would otherwise have polluted every baseline).
 * Two consecutive DEGRADED verdicts trigger the migration playbook at
 * ~/workspace/research_notes/notebooklm_notebook_migration.md.
 */
public final class NotebookLmLatency {

    private static final Path LOG = Paths.get(
            System.getenv("HOME") + "/workspace/research_notes/notebooklm_latency_log.md");
    private static final double DEGRADED_RATIO = 2.0;
    private static final double DEGRADED_FLOOR_SECS = 90.0;

    private NotebookLmLatency() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && args[0].equals("record")) {
            String notebook = args[1];
            double seconds = Double.parseDouble(args[2]);
            String note = args.length >= 4 ? args[3] : "";
            record(notebook, seconds, note);
            return;
        }
        if (args.length >= 1 && args[0].equals("report")) {
            report();
            return;
        }
        System.out.println("usage: record <notebook> <seconds> [note] | report");
        System.exit(2);
    }

    private static void record(String notebook, double seconds, String note) throws Exception {
        String row = String.format("%s | %s | %.0f | %s%n",
                LocalDate.now(), notebook, seconds, note.replace("|", "/"));
        if (!Files.exists(LOG)) {
            Files.writeString(LOG,
                    "# NotebookLM latency log\n\n"
                    + "Append-only. One row per probe: date | notebook | seconds | note.\n"
                    + "Probe question (always the same): "
                    + "\"List the titles of all sources currently in this notebook.\"\n\n",
                    StandardCharsets.UTF_8);
        }
        Files.writeString(LOG, row, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        System.out.println("recorded: " + row.trim());
    }

    private static void report() throws Exception {
        if (!Files.exists(LOG)) {
            System.out.println("no probes recorded yet");
            return;
        }
        Map<String, List<Probe>> byNotebook = new LinkedHashMap<>();
        for (String line : Files.readAllLines(LOG, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\\|");
            if (parts.length < 3 || !parts[0].trim().matches("\\d{4}-\\d{2}-\\d{2}")) {
                continue;
            }
            try {
                String note = parts.length >= 4 ? parts[3].trim() : "";
                byNotebook.computeIfAbsent(parts[1].trim(), k -> new ArrayList<>())
                        .add(new Probe(Double.parseDouble(parts[2].trim()), note));
            } catch (NumberFormatException ignored) {
            }
        }
        boolean anyDegraded = false;
        for (Map.Entry<String, List<Probe>> e : byNotebook.entrySet()) {
            List<Probe> all = e.getValue();
            // Failed probes measure the block, not the notebook: exclude
            // them from baselines and from the degradation verdict.
            List<Double> ok = new ArrayList<>();
            for (Probe p : all) if (p.ok()) ok.add(p.seconds);
            Probe latest = all.get(all.size() - 1);
            if (ok.isEmpty()) {
                System.out.printf("%s: n=%d no successful probes yet -> BLOCKED (%s)%n",
                        e.getKey(), all.size(),
                        latest.note.isEmpty() ? "no note" : latest.note);
                continue;
            }
            double baseline = median(ok.subList(0, Math.min(3, ok.size())));
            double latestOk = ok.get(ok.size() - 1);
            boolean degraded = latest.ok()
                    && latestOk > DEGRADED_RATIO * baseline
                    && latestOk > DEGRADED_FLOOR_SECS;
            // Two-in-a-row check: last two SUCCESSFUL probes both degraded
            // (a failed probe breaks the consecutive chain).
            boolean twice = degraded && ok.size() >= 2
                    && ok.get(ok.size() - 2) > DEGRADED_RATIO * baseline
                    && ok.get(ok.size() - 2) > DEGRADED_FLOOR_SECS;
            System.out.printf("%s: n=%d baseline=%.0fs latest=%.0fs -> %s%s%n",
                    e.getKey(), all.size(), baseline, latestOk,
                    degraded ? "DEGRADED" : "OK",
                    twice ? " (2nd consecutive — run the migration playbook)" : "");
            anyDegraded |= degraded;
        }
        System.exit(anyDegraded ? 1 : 0);
    }

    /** One probe row. A probe counts as successful unless its note marks a
     *  block ("signed out", "abort:") or its seconds hit the 999 abort sentinel. */
    private record Probe(double seconds, String note) {
        boolean ok() {
            String n = note.toLowerCase();
            return seconds < 999 && !n.contains("signed out") && !n.startsWith("abort:");
        }
    }

    private static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
}
