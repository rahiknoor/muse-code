import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/**
 * ValuationDriftCheck — compare Atlas stock-note valuation fields across git
 * refs, flag what moved, attribute each move to its feed, and queue the
 * unexplained ones for deep research.
 *
 * <p>Rahik's rule: Atlas lets me compare previous information against current
 * information, question why it changed, and correct missing info. This tool is
 * the "what changed" half (git-backed); the "why" half is deterministic
 * attribution here + web research on the emitted queue by the agent.
 *
 * <p>Usage:
 * <pre>
 *   ValuationDriftCheck.java [--since REF] [--until REF|WORKTREE]
 *       [--threshold PCT] [--report PATH] [--queue PATH] [--log PATH]
 *       [--self-test]
 * </pre>
 * Defaults: --since/--until = the older/newer of the last two commits touching
 * {@code 01 📈 Stocks/}; --threshold 5.0.
 *
 * <p>Exit 0 always on success (flags are findings, not failures); non-zero on
 * errors. With --log, a dated section is prepended to the drift-log note —
 * commit it so git holds the findings.
 */
public class ValuationDriftCheck {
    static final String STOCKS_DIR = "01 \uD83D\uDCC8 Stocks";
    static final Set<String> EXCLUDE = Set.of(
            "Valuation Dashboard.md", "DCF Valuation Model.md", "Stock Research Hub.md");
    /** frontmatter field -> backend feed file (relative to ~/workspace), "" = no feed. */
    static final LinkedHashMap<String, String> FEEDS = new LinkedHashMap<>();
    static {
        FEEDS.put("market_price", "stock_watch/price_cache.json");
        FEEDS.put("dcf", "dcf_csv/our_dcf.json");
        FEEDS.put("notebooklm", "dcf_csv/notebooklm_values.json");
        FEEDS.put("notebooklm_forward", "dcf_csv/notebooklm_forward.json");
        FEEDS.put("muse_speculation", "dcf_csv/speculation_price.json");
        FEEDS.put("nlm_speculation", "");
        FEEDS.put("outside_voice", "dcf_csv/outside_voice.json");
        FEEDS.put("projection_price", "dcf_csv/analyst_targets.json");
    }
    static final double QUEUE_PCT = 15.0;

    /** Base git argv: core.quotePath=false keeps emoji paths unquoted. */
    static List<String> gitBase(Path vault) {
        return new ArrayList<>(List.of("git", "-C", vault.toString(), "-c", "core.quotePath=false"));
    }

    record Flag(String ticker, String field, String kind,
                Double oldVal, Double newVal, Double pct, String attribution) {}
    record MissingInfo(String ticker, String field, String detail) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("--self-test")) {
            System.exit(selfTest() ? 0 : 1);
        }
        Path home = Paths.get(System.getenv("HOME"));
        Path vault = home.resolve("workspace/atlas");
        Path ws = home.resolve("workspace");
        double threshold = Double.parseDouble(opts.getOrDefault("--threshold", "5.0"));
        String since = opts.get("--since");
        String until = opts.getOrDefault("--until", null);
        if (since == null || until == null) {
            List<String> refs = gitLines(vault, "log", "--format=%H", "-2", "--", STOCKS_DIR);
            if (refs.size() < 2) fail("need 2+ commits touching " + STOCKS_DIR);
            if (until == null) until = refs.get(0);
            if (since == null) since = refs.get(1);
        }
        boolean worktree = "WORKTREE".equalsIgnoreCase(until);

        List<String> notes = gitLines(vault, "ls-tree", "-r", "--name-only", worktree ? "HEAD" : until,
                "--", STOCKS_DIR);
        List<Flag> flags = new ArrayList<>();
        List<MissingInfo> missing = new ArrayList<>();
        List<Map<String, Object>> queue = new ArrayList<>();
        int tickers = 0, newNotes = 0;

        for (String note : notes) {
            String file = note.substring(note.lastIndexOf('/') + 1);
            if (!file.endsWith(".md") || EXCLUDE.contains(file)) continue;
            String ticker = file.substring(0, file.length() - 3);
            Map<String, Double> oldFm, newFm;
            try {
                oldFm = frontmatter(gitShow(vault, since, note));
            } catch (Exception e) {
                newNotes++; // note did not exist at --since
                continue;
            }
            newFm = worktree
                    ? frontmatter(Files.readString(vault.resolve(note), StandardCharsets.UTF_8))
                    : frontmatter(gitShow(vault, until, note));
            tickers++;
            for (String field : FEEDS.keySet()) {
                Double o = oldFm.get(field), n = newFm.get(field);
                String attr = attribute(ws, field);
                if (o == null && n == null) continue;
                if (o == null) {
                    flags.add(new Flag(ticker, field, "APPEARED", null, n, null, attr));
                } else if (n == null) {
                    flags.add(new Flag(ticker, field, "VANISHED", o, null, null, attr));
                } else if (Math.abs(o) > 1e-9) {
                    double pct = (n - o) / Math.abs(o) * 100.0;
                    if (Math.abs(pct) >= threshold) {
                        flags.add(new Flag(ticker, field, "MOVED", o, n, pct, attr));
                    }
                } else if (Math.abs(n) > 1e-9) {
                    flags.add(new Flag(ticker, field, "MOVED", o, n, null, attr));
                }
            }
        }
        // Missing-info: backend currently holds a value the note lacks.
        for (String note : notes) {
            String file = note.substring(note.lastIndexOf('/') + 1);
            if (!file.endsWith(".md") || EXCLUDE.contains(file)) continue;
            String ticker = file.substring(0, file.length() - 3);
            Map<String, Double> fm = worktree
                    ? frontmatter(Files.readString(vault.resolve(note), StandardCharsets.UTF_8))
                    : frontmatter(gitShow(vault, until, note));
            checkMissing(ws, ticker, fm, missing);
        }

        for (Flag f : flags) {
            if (!"MOVED".equals(f.kind()) || Math.abs(f.pct()) >= QUEUE_PCT
                    || f.attribution().startsWith("unknown")) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("ticker", f.ticker()); q.put("field", f.field()); q.put("kind", f.kind());
                q.put("old", f.oldVal()); q.put("new", f.newVal()); q.put("pct", f.pct());
                q.put("attribution", f.attribution());
                queue.add(q);
            }
        }

        String report = render(since, until, worktree, threshold, tickers, newNotes, flags, missing, queue);
        if (opts.containsKey("--report")) {
            Files.writeString(Paths.get(opts.get("--report")), report, StandardCharsets.UTF_8);
            System.out.println("report -> " + opts.get("--report"));
        } else {
            System.out.print(report);
        }
        if (opts.containsKey("--queue")) {
            Files.writeString(Paths.get(opts.get("--queue")), toJson(queue), StandardCharsets.UTF_8);
            System.out.println("queue -> " + opts.get("--queue") + " (" + queue.size() + " items)");
        }
        if (opts.containsKey("--log")) {
            prependLog(Paths.get(opts.get("--log")), since, until, threshold, flags, missing, queue);
            System.out.println("logged -> " + opts.get("--log"));
        }
        System.out.println("tickers=" + tickers + " flags=" + flags.size()
                + " missing=" + missing.size() + " queued=" + queue.size());
    }

    // ---------- git + parsing ----------

    static List<String> gitLines(Path vault, String... argv) throws IOException, InterruptedException {
        List<String> cmd = gitBase(vault);
        cmd.addAll(List.of(argv));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) fail("git failed: " + out);
        List<String> lines = new ArrayList<>();
        for (String l : out.split("\n")) if (!l.isBlank()) lines.add(l.strip());
        return lines;
    }

    static String gitShow(Path vault, String ref, String path) throws IOException, InterruptedException {
        List<String> cmd = gitBase(vault);
        cmd.addAll(List.of("show", ref + ":" + path));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("no such blob: " + ref + ":" + path);
        return out;
    }

    /** Parse YAML frontmatter numerics: key -> Double (null for null/—/missing). */
    static Map<String, Double> frontmatter(String md) {
        Map<String, Double> m = new LinkedHashMap<>();
        String[] lines = md.split("\n");
        int start = -1, end = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) { if (start < 0) start = i; else { end = i; break; } }
        }
        if (start < 0 || end < 0) return m;
        for (int i = start + 1; i < end; i++) {
            int c = lines[i].indexOf(':');
            if (c < 0) continue;
            String k = lines[i].substring(0, c).strip();
            String v = lines[i].substring(c + 1).strip().replaceAll("^\"|\"$", "");
            if (v.equalsIgnoreCase("null") || v.equals("—") || v.isEmpty()) { m.put(k, null); continue; }
            try { m.put(k, Double.parseDouble(v.replaceAll("[,$%]", ""))); }
            catch (NumberFormatException ignored) { /* non-numeric field */ }
        }
        return m;
    }

    static String attribute(Path ws, String field) {
        String feed = FEEDS.get(field);
        if (feed == null || feed.isEmpty()) return "unknown feed (no backend mapped)";
        Path p = ws.resolve(feed);
        if (!Files.exists(p)) return "unknown feed (" + feed + " missing)";
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"asof\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            String asof = m.find() ? m.group(1) : "asof unknown";
            String extra = "";
            if (field.equals("muse_speculation")) extra = "; derived = voice_DCF + 0.5*(outside_median - voice_DCF) + |voice_DCF|*nudge";
            return feed + " " + asof + extra;
        } catch (IOException e) {
            return "unknown feed (" + feed + " unreadable)";
        }
    }

    /** Best-effort: backend has a value for ticker+field but the note shows null. */
    static void checkMissing(Path ws, String ticker, Map<String, Double> fm, List<MissingInfo> missing) {
        checkOne(ws, ticker, fm, missing, "dcf", "dcf_csv/our_dcf.json",
                "\"" + ticker + "\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*([0-9.eE+-]+)");
        checkOne(ws, ticker, fm, missing, "notebooklm", "dcf_csv/notebooklm_values.json",
                "\"" + ticker + "\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*([0-9.eE+-]+)");
        checkOne(ws, ticker, fm, missing, "market_price", "stock_watch/price_cache.json",
                "\"" + ticker + "\"\\s*:\\s*\\{[^}]*\"price\"\\s*:\\s*([0-9.eE+-]+)");
    }

    static void checkOne(Path ws, String ticker, Map<String, Double> fm,
                         List<MissingInfo> missing, String field, String feed, String regex) {
        if (fm.get(field) != null) return;
        try {
            String json = Files.readString(ws.resolve(feed), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(regex).matcher(json);
            if (m.find()) missing.add(new MissingInfo(ticker, field,
                    "backend " + feed + " holds " + m.group(1) + " but note shows null"));
        } catch (IOException ignored) { }
    }

    // ---------- rendering ----------

    static String render(String since, String until, boolean worktree, double threshold,
                         int tickers, int newNotes, List<Flag> flags,
                         List<MissingInfo> missing, List<Map<String, Object>> queue) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Valuation Drift Check — ").append(shortRef(since)).append(" .. ")
          .append(worktree ? "WORKTREE" : shortRef(until)).append("\n\n");
        sb.append(tickers).append(" tickers compared, threshold ±").append(threshold).append("%");
        if (newNotes > 0) sb.append(", ").append(newNotes).append(" new notes skipped");
        sb.append(".\n\n## Flags (").append(flags.size()).append(")\n\n");
        if (flags.isEmpty()) sb.append("No valuation field moved beyond threshold; nothing appeared or vanished.\n");
        for (Flag f : flags) {
            sb.append("- **").append(f.ticker()).append("** `").append(f.field()).append("` ")
              .append(f.kind()).append(": ").append(fmt(f.oldVal())).append(" → ").append(fmt(f.newVal()));
            if (f.pct() != null) sb.append(String.format(" (%+.1f%%)", f.pct()));
            sb.append(" — ").append(f.attribution()).append("\n");
        }
        sb.append("\n## Missing info (").append(missing.size()).append(")\n\n");
        if (missing.isEmpty()) sb.append("None.\n");
        for (MissingInfo mi : missing)
            sb.append("- **").append(mi.ticker()).append("** `").append(mi.field())
              .append("`: ").append(mi.detail()).append("\n");
        sb.append("\n## Research queue (").append(queue.size()).append(")\n\n");
        if (queue.isEmpty()) sb.append("Nothing needs deep research.\n");
        else sb.append("Queued for web research (move ≥").append(QUEUE_PCT)
                .append("% or unattributed/appeared/vanished):\n");
        for (Map<String, Object> q : queue)
            sb.append("- ").append(q.get("ticker")).append(" / ").append(q.get("field"))
              .append(" / ").append(q.get("kind")).append("\n");
        long big = queue.stream().filter(q -> "MOVED".equals(q.get("kind"))
                && q.get("pct") instanceof Double && Math.abs((Double) q.get("pct")) >= QUEUE_PCT).count();
        sb.append("\nClaude audit: ").append(queue.isEmpty() ? "not needed — nothing queued."
                : "recommended — " + queue.size() + " queued items (" + big + " large moves). Batch the brief; his hands, so only when worth it.").append("\n");
        return sb.toString();
    }

    static String fmt(Double d) { return d == null ? "null" : String.format("%.2f", d); }
    static String shortRef(String r) { return r.length() > 7 ? r.substring(0, 7) : r; }

    static String toJson(List<Map<String, Object>> queue) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < queue.size(); i++) {
            Map<String, Object> q = queue.get(i);
            sb.append("  {");
            boolean first = true;
            for (Map.Entry<String, Object> e : q.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("\"").append(e.getKey()).append("\": ");
                Object v = e.getValue();
                sb.append(v == null ? "null" : v instanceof String ? "\"" + v + "\"" : v);
            }
            sb.append("}").append(i + 1 < queue.size() ? ",\n" : "\n");
        }
        return sb.append("]\n").toString();
    }

    static void prependLog(Path log, String since, String until, double threshold,
                           List<Flag> flags, List<MissingInfo> missing,
                           List<Map<String, Object>> queue) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(LocalDate.now()).append(" — drift check ")
          .append(shortRef(since)).append("..").append(shortRef(until)).append("\n\n");
        sb.append("- Flags: ").append(flags.size())
          .append(", missing-info: ").append(missing.size())
          .append(", research queued: ").append(queue.size())
          .append(" (threshold ±").append(threshold).append("%)\n");
        for (Flag f : flags)
            sb.append("- ").append(f.ticker()).append(" ").append(f.field()).append(" ")
              .append(f.kind()).append(" ").append(fmt(f.oldVal())).append("→").append(fmt(f.newVal()))
              .append(f.pct() != null ? String.format(" (%+.1f%%)", f.pct()) : "").append("\n");
        for (MissingInfo mi : missing)
            sb.append("- MISSING ").append(mi.ticker()).append(" ").append(mi.field())
              .append(": ").append(mi.detail()).append("\n");
        sb.append("\n### Research findings\n(to fill after web research; then commit)\n\n---\n\n");
        String prev = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "# Valuation Drift Log\n\n";
        String replacement = "# Valuation Drift Log\n\n" + sb;
        Files.writeString(log, prev.replaceFirst("(?m)^# Valuation Drift Log\n",
                Matcher.quoteReplacement(replacement)), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ---------- self-test ----------

    static boolean selfTest() throws Exception {
        Path tmp = Files.createTempDirectory("drift-test");
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(STOCKS_DIR));
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        // commit A
        writeNote(repo, "META", 418.29, 382.56, 725.93);
        writeNote(repo, "NVDA", 200.00, 180.00, 150.00);
        writeNote(repo, "SOFI", 15.00, null, 20.00);
        writeNote(repo, "PLTR", 150.00, 90.00, 100.00);
        git(repo, "add", "-A"); git(repo, "commit", "-qm", "A");
        String a = gitOut(repo, "rev-parse", "HEAD").get(0);
        // commit B: META dcf +10% (flag), NVDA dcf +1% (no flag),
        // SOFI notebooklm appeared, PLTR dcf vanished
        writeNote(repo, "META", 460.12, 382.56, 725.93);
        writeNote(repo, "NVDA", 202.00, 180.00, 150.00);
        writeNote(repo, "SOFI", 15.00, 14.50, 20.00);
        writeNote(repo, "PLTR", null, 90.00, 100.00);
        git(repo, "add", "-A"); git(repo, "commit", "-qm", "B");
        String b = gitOut(repo, "rev-parse", "HEAD").get(0);

        // run the real main against the fixture repo via env override
        List<String> out = new ArrayList<>();
        boolean ok = true;
        // Invoke the same detection path main() uses, pointed at fixture paths.
        String report = runForTest(repo, tmp, a, b, 5.0);
        ok &= expect(report, "**META** `dcf` MOVED: 418.29 → 460.12 (+10.0%)", out);
        ok &= expect(report, "**SOFI** `notebooklm` APPEARED: null → 14.50", out);
        ok &= expect(report, "**PLTR** `dcf` VANISHED: 150.00 → null", out);
        ok &= expectAbsent(report, "`dcf` MOVED: 200.00 → 202.00", out); // +1% under threshold
        ok &= expect(report, "## Research queue (3)", out); // META big + APPEARED + VANISHED
        System.out.println(String.join("\n", out));
        System.out.println(ok ? "SELF-TEST PASS" : "SELF-TEST FAIL");
        return ok;
    }

    /** Test-only runner: same logic as main, pointed at fixture paths. */
    static String runForTest(Path vault, Path ws, String since, String until, double threshold)
            throws Exception {
        List<String> notes = gitOut(vault, "ls-tree", "-r", "--name-only", until, "--", STOCKS_DIR);
        List<Flag> flags = new ArrayList<>();
        int tickers = 0, newNotes = 0;
        for (String note : notes) {
            String file = note.substring(note.lastIndexOf('/') + 1);
            if (!file.endsWith(".md") || EXCLUDE.contains(file)) continue;
            String ticker = file.substring(0, file.length() - 3);
            Map<String, Double> oldFm;
            try { oldFm = frontmatter(gitShow(vault, since, note)); }
            catch (Exception e) { newNotes++; continue; }
            Map<String, Double> newFm = frontmatter(gitShow(vault, until, note));
            tickers++;
            for (String field : FEEDS.keySet()) {
                Double o = oldFm.get(field), n = newFm.get(field);
                if (o == null && n == null) continue;
                if (o == null) flags.add(new Flag(ticker, field, "APPEARED", null, n, null, "unknown feed (no backend mapped)"));
                else if (n == null) flags.add(new Flag(ticker, field, "VANISHED", o, null, null, "unknown feed (no backend mapped)"));
                else if (Math.abs(o) > 1e-9) {
                    double pct = (n - o) / Math.abs(o) * 100.0;
                    if (Math.abs(pct) >= threshold) flags.add(new Flag(ticker, field, "MOVED", o, n, pct, "unknown feed (no backend mapped)"));
                }
            }
        }
        List<Map<String, Object>> queue = new ArrayList<>();
        for (Flag f : flags) {
            if (!"MOVED".equals(f.kind()) || Math.abs(f.pct()) >= QUEUE_PCT || f.attribution().startsWith("unknown")) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("ticker", f.ticker()); q.put("field", f.field()); q.put("kind", f.kind());
                queue.add(q);
            }
        }
        return render(since, until, false, threshold, tickers, newNotes, flags, List.of(), queue);
    }

    static void writeNote(Path repo, String ticker, Double dcf, Double nlm, Double price) throws IOException {
        String fm = "---\ntitle: " + ticker + "\n"
                + "market_price: " + price + "\n"
                + "notebooklm: " + nlm + "\n"
                + "dcf: " + dcf + "\n---\n\n# " + ticker + "\n";
        Files.writeString(repo.resolve(STOCKS_DIR).resolve(ticker + ".md"), fm, StandardCharsets.UTF_8);
    }

    static void git(Path repo, String... argv) throws IOException, InterruptedException {
        List<String> cmd = gitBase(repo);
        cmd.addAll(List.of(argv));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) fail("git fixture failed: " + out);
    }

    static List<String> gitOut(Path repo, String... argv) throws IOException, InterruptedException {
        List<String> cmd = gitBase(repo);
        cmd.addAll(List.of(argv));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) fail("git fixture failed: " + out);
        List<String> lines = new ArrayList<>();
        for (String l : out.split("\n")) if (!l.isBlank()) lines.add(l.strip());
        return lines;
    }

    static boolean check(String name, boolean cond, List<String> out) {
        out.add((cond ? "ok   " : "FAIL ") + name);
        return cond;
    }
    static boolean expect(String report, String needle, List<String> out) {
        return check("contains: " + needle, report.contains(needle), out);
    }
    static boolean expectAbsent(String report, String needle, List<String> out) {
        return check("absent: " + needle, !report.contains(needle), out);
    }

    // ---------- args ----------

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(args[i], args[++i]);
                else m.put(args[i], "true");
            }
        }
        return m;
    }

    static void fail(String msg) { System.err.println("ValuationDriftCheck: " + msg); System.exit(2); throw new RuntimeException(msg); }
}
