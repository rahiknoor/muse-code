import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * GenerateStockSummaries — writes a "## Summary" + "## Story" block into each
 * Atlas stock note, generated from the note's own frontmatter.
 *
 * <p>Purpose (Rahik's vault-summaries project): he reads/listens via Obsidian's
 * native read-aloud on his phone. No TTS by us — content only, and it must
 * read cleanly aloud. Structured notes are generated programmatically (never
 * hand-written 426x); the block regenerates from frontmatter on every run, so
 * it can never disagree with the valuation table.
 *
 * <p>Conventions follow RefreshOptionality.java:
 * <ul>
 *   <li>homeDir() prefers $HOME (never the JVM user.home property)</li>
 *   <li>section markers {@code <!-- summary-story:start -->} /
 *       {@code <!-- summary-story:end -->}; one writer per section</li>
 *   <li>every note sanitized via the Sanitize --batch-stdin child before write</li>
 *   <li>idempotent: unchanged frontmatter produces no diff</li>
 *   <li>run with LC_ALL=C.UTF-8 (emoji in the note path)</li>
 * </ul>
 *
 * <p>Placement: directly before the valuation table (after the company-name
 * line), so read-aloud flows title → summary → story → numbers.
 *
 * <p>Run:
 * <pre>
 *   LC_ALL=C.UTF-8 ~/workspace/java/jdk-21/bin/java \
 *     --class-path ~/workspace/java-shared/classes \
 *     ~/workspace/atlas-scripts/GenerateStockSummaries.java [--tickers META,NVDA]
 *   java ~/workspace/atlas-scripts/GenerateStockSummaries.java --self-test
 * </pre>
 */
public class GenerateStockSummaries {
    static final String STOCKS_DIR = "01 \uD83D\uDCC8 Stocks";
    static final Set<String> EXCLUDE = Set.of(
            "Valuation Dashboard.md", "DCF Valuation Model.md", "Stock Research Hub.md");
    static final String START = "<!-- summary-story:start -->";
    static final String END = "<!-- summary-story:end -->";
    static final Pattern SECTION = Pattern.compile(
            "<!-- summary-story:start -->.*?<!-- summary-story:end -->\\n?", Pattern.DOTALL);
    static final Pattern TABLE_ANCHOR = Pattern.compile("^\\| Source \\|", Pattern.MULTILINE);
    static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\n(.*?)^---", Pattern.MULTILINE | Pattern.DOTALL);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            System.exit(selfTest() ? 0 : 1);
        }
        Set<String> only = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--tickers")) only = Set.of(args[i + 1].split(","));
        }
        Path vault = homeDir().resolve("workspace/atlas");
        Path dir = vault.resolve(STOCKS_DIR);
        int written = 0, skipped = 0;
        try (SanitizerBatch san = new SanitizerBatch()) {
            try (var stream = Files.list(dir)) {
                for (Path note : stream.filter(p -> p.toString().endsWith(".md"))
                        .sorted().toList()) {
                    String file = note.getFileName().toString();
                    if (EXCLUDE.contains(file)) continue;
                    String ticker = file.substring(0, file.length() - 3);
                    if (only != null && !only.contains(ticker)) continue;
                    String text = Files.readString(note, StandardCharsets.UTF_8);
                    String updated = applyOne(text);
                    if (updated == null) { skipped++; continue; }
                    if (!updated.equals(text)) {
                        Files.writeString(note, san.sanitize(updated), StandardCharsets.UTF_8);
                        written++;
                    } else {
                        skipped++;
                    }
                }
            }
        }
        System.out.println("GenerateStockSummaries: wrote " + written + ", unchanged " + skipped);
    }

    /** Returns the updated note text, or null when the note has no frontmatter. */
    static String applyOne(String text) {
        Map<String, String> fm = frontmatter(text);
        if (fm.isEmpty()) return null;
        String company = companyName(text, fm.getOrDefault("title", ""));
        String block = renderBlock(fm, company);
        Matcher m = SECTION.matcher(text);
        if (m.find()) return m.replaceFirst(Matcher.quoteReplacement(block));
        Matcher t = TABLE_ANCHOR.matcher(text);
        if (!t.find()) return text; // no table anchor: leave untouched
        return text.substring(0, t.start()) + block + text.substring(t.start());
    }

    // ---------- frontmatter ----------

    static Path homeDir() {
        String h = System.getenv("HOME");
        return Paths.get(h != null ? h : System.getProperty("user.home"));
    }

    static Map<String, String> frontmatter(String md) {
        Map<String, String> fm = new LinkedHashMap<>();
        Matcher m = FRONTMATTER.matcher(md);
        if (!m.find()) return fm;
        for (String line : m.group(1).split("\n")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c).strip();
            String v = line.substring(c + 1).strip().replaceAll("^\"|\"$", "");
            fm.put(k, v);
        }
        return fm;
    }

    static Double num(Map<String, String> fm, String key) {
        String v = fm.get(key);
        if (v == null || v.equalsIgnoreCase("null") || v.equals("—") || v.isEmpty()) return null;
        try { return Double.parseDouble(v.replaceAll("[,$%]", "")); }
        catch (NumberFormatException e) { return null; }
    }

    static String companyName(String text, String title) {
        // First non-empty, non-heading line after "# TITLE".
        boolean pastTitle = false;
        for (String line : text.split("\n")) {
            String s = line.strip();
            if (!pastTitle) {
                if (s.equals("# " + title)) pastTitle = true;
                continue;
            }
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("|") || s.startsWith("<!--")) continue;
            return s;
        }
        return title;
    }

    static String money(Double d) {
        if (d == null) return null;
        return "$" + String.format(Locale.US, "%,.2f", d);
    }

    // ---------- content ----------

    /** 2–3 sentences: company, price, DCF vs market, other voices, outlook. */
    static String buildSummary(Map<String, String> fm, String company) {
        String ticker = fm.getOrDefault("ticker", fm.getOrDefault("title", ""));
        Double market = num(fm, "market_price");
        Double dcf = num(fm, "dcf");
        Double nlm = num(fm, "notebooklm");
        Double upside = num(fm, "upside_pct");
        Double proj = num(fm, "projection_price");
        String outlook = fm.getOrDefault("growth_outlook", "");
        String creators = fm.getOrDefault("creators", "");

        StringBuilder sb = new StringBuilder();
        sb.append(company).append(" (").append(ticker).append(")");
        if (market != null) sb.append(" trades at ").append(money(market));
        sb.append(".");
        if (dcf != null) {
            sb.append(" Our DCF values it at ").append(money(dcf));
            if (upside != null && market != null) {
                sb.append(" \u2014 the stock trades ")
                  .append(String.format(Locale.US, "%.0f", Math.abs(upside)))
                  .append("% ").append(upside < 0 ? "above" : "below")
                  .append(" our value.");
            } else sb.append(".");
        }
        StringBuilder voices = new StringBuilder();
        if (nlm != null) voices.append("NotebookLM's grounded value is ").append(money(nlm)).append(". ");
        String cs = creatorSentence(creators);
        if (!cs.isEmpty()) voices.append(cs).append(". ");
        if (proj != null) voices.append("Analysts target ").append(money(proj)).append(". ");
        if (voices.length() > 0) sb.append(" ").append(voices.toString().strip());
        if (!outlook.isEmpty() && !outlook.equalsIgnoreCase("null")) {
            sb.append(" Growth outlook: ").append(outlook).append(".");
        }
        return sb.toString().replaceAll("\\s+", " ").strip();
    }

    /** Narrative paragraphs: valuation picture, voices, outlook. Reads aloud cleanly. */
    static String buildStory(Map<String, String> fm, String company) {
        String ticker = fm.getOrDefault("ticker", fm.getOrDefault("title", ""));
        Double market = num(fm, "market_price");
        Double dcf = num(fm, "dcf");
        Double nlm = num(fm, "notebooklm");
        Double nlmFwd = num(fm, "notebooklm_forward");
        Double spec = num(fm, "muse_speculation");
        Double outside = num(fm, "outside_voice");
        Double proj = num(fm, "projection_price");
        String outlook = fm.getOrDefault("growth_outlook", "");
        String thesis = fm.getOrDefault("growth_thesis", "");
        String creators = fm.getOrDefault("creators", "");
        String asof = fm.getOrDefault("values_asof", "");

        StringBuilder sb = new StringBuilder();
        // P1: the valuation picture
        List<String> parts = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        if (dcf != null) { parts.add("our DCF lands at " + money(dcf)); vals.add(dcf); }
        if (nlm != null) { parts.add("NotebookLM's grounded work at " + money(nlm)); vals.add(nlm); }
        if (nlmFwd != null) { parts.add("its forward tilt at " + money(nlmFwd)); vals.add(nlmFwd); }
        if (spec != null) { parts.add("Muse's speculation price at " + money(spec)); vals.add(spec); }
        if (outside != null) { parts.add("the outside median at " + money(outside)); vals.add(outside); }
        if (proj != null) { parts.add("the analyst target at " + money(proj)); vals.add(proj); }
        sb.append(company).append(" (").append(ticker).append("): ");
        if (!parts.isEmpty()) {
            sb.append(String.join(", ", parts)).append(". ");
            if (market != null && !vals.isEmpty()) {
                double lo = vals.stream().mapToDouble(d -> d).min().orElse(0);
                double hi = vals.stream().mapToDouble(d -> d).max().orElse(0);
                sb.append("The estimates span ").append(money(lo)).append(" to ").append(money(hi));
                sb.append(", against a market price of ").append(money(market)).append(". ");
            }
        }
        if (!asof.isEmpty() && !asof.equalsIgnoreCase("null")) {
            sb.append("Values as of ").append(asof).append(".");
        }
        sb.append("\n\n");
        // P2: the voices
        String cs = creatorSentence(creators);
        if (!cs.isEmpty()) {
            sb.append("The creator voices: ").append(cs).append(".");
            sb.append("\n\n");
        }
        // P3: outlook
        if ((!outlook.isEmpty() && !outlook.equalsIgnoreCase("null"))
                || (!thesis.isEmpty() && !thesis.equalsIgnoreCase("null"))) {
            if (!outlook.isEmpty() && !outlook.equalsIgnoreCase("null")) {
                sb.append("Growth outlook: ").append(outlook).append(". ");
            }
            if (!thesis.isEmpty() && !thesis.equalsIgnoreCase("null")) sb.append(thesis.strip());
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    /** "Name: kind $v; ..." -> "Name targets $v; Name2's fair value is $w". */
    static String creatorSentence(String creators) {
        if (creators == null || creators.isEmpty() || creators.equalsIgnoreCase("null")) return "";
        List<String> bits = new ArrayList<>();
        for (String chunk : creators.split(";")) {
            String[] kv = chunk.strip().split(":", 2);
            if (kv.length < 2) continue;
            String name = kv[0].strip();
            String rest = kv[1].strip();
            Matcher m = Pattern.compile("(\\w+)\\s+\\$?([0-9,.]+)").matcher(rest);
            if (!m.find()) continue;
            String kind = m.group(1);
            Double val;
            try { val = Double.parseDouble(m.group(2).replace(",", "")); }
            catch (NumberFormatException e) { continue; }
            String v = money(val);
            bits.add(switch (kind) {
                case "price_target" -> name + " targets " + v;
                case "fair_value" -> name + "'s fair value is " + v;
                case "buy_price" -> name + " buys at " + v;
                case "buy_zone" -> name + "'s buy zone is " + v;
                default -> name + " calls " + v;
            });
        }
        return String.join("; ", bits);
    }

    static String renderBlock(Map<String, String> fm, String company) {
        return START + "\n## Summary\n\n" + buildSummary(fm, company)
                + "\n\n## Story\n\n" + buildStory(fm, company) + "\n" + END + "\n";
    }

    // ---------- sanitizer (same protocol as RefreshOptionality) ----------

    static final class SanitizerBatch implements AutoCloseable {
        private final Process proc;
        private final DataOutputStream out;
        private final DataInputStream in;

        SanitizerBatch() throws IOException {
            String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            String sanitizeJava = homeDir().resolve("workspace/atlas-scripts/Sanitize.java").toString();
            String sharedClasses = homeDir().resolve("workspace/java-shared/classes").toString();
            proc = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                            "--class-path", sharedClasses, sanitizeJava, "--batch-stdin")
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            out = new DataOutputStream(proc.getOutputStream());
            in = new DataInputStream(proc.getInputStream());
        }

        String sanitize(String dirty) throws IOException {
            byte[] bytes = dirty.getBytes(StandardCharsets.UTF_8);
            out.writeBytes("SANITIZE");
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();
            byte[] magic = new byte[2];
            try { in.readFully(magic); }
            catch (EOFException e) { throw new IOException("sanitizer child exited", e); }
            if (magic[0] != 'O' || magic[1] != 'K') throw new IOException("sanitizer protocol error");
            int len = in.readInt();
            if (len < 0 || len > 50_000_000) throw new IOException("sanitizer bad length " + len);
            byte[] buf = new byte[len];
            in.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }

        @Override public void close() {
            try { out.close(); } catch (IOException ignored) { }
            proc.destroy();
        }
    }

    // ---------- self-test ----------

    static boolean selfTest() throws Exception {
        List<String> log = new ArrayList<>();
        boolean ok = true;

        String note = "---\ntitle: META\nticker: META\nmarket_price: 725.93\ndcf: 418.29\n"
                + "notebooklm: 382.56\nnotebooklm_forward: 364.64\nmuse_speculation: 539.3\n"
                + "outside_voice: 618.48\nprojection_price: 785.9\nupside_pct: -42.4\n"
                + "growth_outlook: High\ngrowth_thesis: The ad engine compounds.\n"
                + "creators: \"The Patient Investor: price_target $600\"\nvalues_asof: 2026-10-04\n---\n"
                + "\n# META\n\nMeta Platforms\n\n| Source | Value |\n|---|---|\n| Market price | $725.93 |\n";

        // 1. insert
        String once = applyOne(note);
        ok &= check(log, "inserts block before table",
                once != null && once.contains(START) && once.indexOf(START) < once.indexOf("| Source |"));
        ok &= check(log, "summary names company+price",
                once.contains("Meta Platforms (META) trades at $725.93."));
        ok &= check(log, "summary has DCF vs market",
                once.contains("Our DCF values it at $418.29")
                        && once.contains("trades 42% above our value."));
        ok &= check(log, "summary has NLM + creator + analyst",
                once.contains("NotebookLM's grounded value is $382.56.")
                        && once.contains("The Patient Investor targets $600.00.")
                        && once.contains("Analysts target $785.90."));
        ok &= check(log, "story has spread + asof",
                once.contains("span $364.64 to $785.90") && once.contains("Values as of 2026-10-04."));
        ok &= check(log, "story carries thesis", once.contains("The ad engine compounds."));
        // 2. idempotent
        String twice = applyOne(once);
        ok &= check(log, "idempotent", twice.equals(once));
        // 3. missing values degrade gracefully
        String thin = "---\ntitle: XYZ\nticker: XYZ\nmarket_price: 10.0\ndcf: 12.0\n---\n\n# XYZ\n\nXyz Corp\n\n| Source | Value |\n";
        String thinOut = applyOne(thin);
        ok &= check(log, "thin note still renders",
                thinOut != null && thinOut.contains("Xyz Corp (XYZ) trades at $10.00."));
        ok &= check(log, "thin note skips absent voices",
                !thinOut.contains("NotebookLM") && !thinOut.contains("creator"));
        // 4. no frontmatter -> null
        ok &= check(log, "no frontmatter -> null", applyOne("# Nope\n") == null);

        System.out.println(String.join("\n", log));
        System.out.println(ok ? "SELF-TEST PASS" : "SELF-TEST FAIL");
        return ok;
    }

    static boolean check(List<String> log, String name, boolean cond) {
        log.add((cond ? "ok   " : "FAIL ") + name);
        return cond;
    }
}
