import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StampResearchBriefs — one writer for the research-brief headers.
 *
 * <p>The {@code 05 📓 NotebookLM/<TICKER> — NotebookLM Research.md} briefs are
 * frozen input packages: what NotebookLM worked from, pinned to a date.
 * Two of them quoted figures as if current (AMZN's $152.42 from a notebook
 * name that no longer exists; CVX's $133.43 in an UNVERIFIED brief), which
 * let an old input package dress up as a current figure.
 *
 * <p>For each brief this tool:
 * <ul>
 *   <li>inserts (or refreshes) a frozen-as-of banner right after the H1
 *       title — the brief's assembly date, plus a pointer to the live
 *       stock note;</li>
 *   <li>inserts (or refreshes) a {@code - Live value:} line inside the
 *       {@code ## NotebookLM DCF value} section, drawn from
 *       {@code dcf_csv/notebooklm_values.json} plus blessed
 *       date/proposal from the changelog — never from the brief's own
 *       frozen figures.</li>
 * </ul>
 *
 * <p>Idempotent: rerunning with unchanged backends produces no diff.
 * The brief body is never rewritten — only the banner and the live line,
 * which this tool owns.
 *
 * <p>Run with: {@code LC_ALL=C.UTF-8 java --class-path
 * ~/workspace/java-shared/classes StampResearchBriefs.java [--base-dir DIR]}
 * ({@code --self-test} runs the checks).
 */
public class StampResearchBriefs {

    static Path homeDir() {
        String home = System.getenv("HOME");
        return Paths.get((home == null || home.isEmpty())
                ? System.getProperty("user.home") : home);
    }

    // Money formatting lives in java-shared/Money.java — the live line must
    // print exactly what the stock note's table prints for the same Double
    // (a local tiered copy once printed "$89.0" where the table said
    // "$88.95"; 2026-09-30). Call sites use Money.fmtMoney.

    static String bannerFor(String t, String date) {
        return "> [!note] FROZEN INPUT PACKAGE \u2014 brief as of " + date + ". "
                + "This is the input package NotebookLM worked from, not a current"
                + " figure. Live values: [[" + t + "]].";
    }

    static String liveLine(String t, Double v, String blessed) {
        if (v == null) {
            return "- Live value: \u2014 \u2014 no grounded value recorded"
                    + " (unverified \u2014 pending the nightly re-ask) \u2014 see [[" + t + "]].";
        }
        return "- Live value: " + Money.fmtMoney(v) + " \u2014 " + blessed
                + " \u2014 see [[" + t + "]].";
    }

    private static final Pattern BANNER =
            Pattern.compile("^> \\[!note\\] FROZEN INPUT PACKAGE.*$", Pattern.MULTILINE);
    private static final Pattern LIVE_LINE =
            Pattern.compile("^- Live value:.*$", Pattern.MULTILINE);
    private static final Pattern DCF_SECTION = Pattern.compile(
            "^## NotebookLM DCF value\\n(.*?)(?=^## |\\z)", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern H1 = Pattern.compile("^(# .+)$", Pattern.MULTILINE);

    /** Insert the frozen banner after the H1, or refresh the existing one. */
    static String stampBanner(String text, String t, String date) {
        String banner = bannerFor(t, date);
        Matcher m = BANNER.matcher(text);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(banner));
        }
        Matcher h = H1.matcher(text);
        if (h.find()) {
            return text.substring(0, h.end()) + "\n\n" + banner + text.substring(h.end());
        }
        return banner + "\n\n" + text;
    }

    /**
     * Insert/refresh the live-value line inside the DCF value section.
     * Briefs without that section get a clearly owned "## Live value
     * (current)" section right after the banner — every brief shows its
     * current backend status/value, never just the frozen package.
     */
    static String stampLiveLine(String text, String t, Double v, String blessed) {
        Matcher m = DCF_SECTION.matcher(text);
        if (!m.find()) {
            return text;
        }
        String body = m.group(1);
        String line = liveLine(t, v, blessed);
        Matcher e = LIVE_LINE.matcher(body);
        String newBody;
        if (e.find()) {
            newBody = e.replaceFirst(Matcher.quoteReplacement(line));
        } else {
            newBody = body + (body.endsWith("\n") ? "" : "\n") + line + "\n";
            // Keep one blank line before the next heading, as before.
            if (text.substring(m.end(1)).startsWith("## ")) {
                newBody += "\n";
            }
        }
        return text.substring(0, m.start(1)) + newBody + text.substring(m.end(1));
    }

    /**
     * Briefs with no "## NotebookLM DCF value" section (e.g. QXO) still get
     * a live-status line — a "## Live value (current)" section inserted
     * right after the frozen banner. No-op when the DCF section exists (the
     * live line lives there) or a live line is already present.
     */
    static String stampLiveSection(String text, String t, Double v, String blessed) {
        if (DCF_SECTION.matcher(text).find()) {
            return text;
        }
        if (LIVE_LINE.matcher(text).find()) {
            return text;
        }
        String section = "## Live value (current)\n" + liveLine(t, v, blessed) + "\n";
        Matcher b = BANNER.matcher(text);
        if (b.find()) {
            return text.substring(0, b.end()) + "\n\n" + section + text.substring(b.end());
        }
        return section + "\n" + text;
    }

    /** Brief assembly date: first date in the DCF section, else file mtime. */
    /**
     * The "frozen as of" date: the first date in the DCF section — the input
     * package's own date. The "- Live value:" line is scrubbed first (its
     * blessed date is live metadata, not the package date; 2026-09-30 it
     * leaked "2026-09-27" into AVGO's banner). A banner already stamped
     * keeps its date (mtime would drift every run: rewrite → new mtime →
     * new banner → rewrite, never idempotent). mtime is only the
     * never-stamped fallback.
     */
    static String briefDate(String dcfBody, String text, Path file) throws IOException {
        String scrubbed = LIVE_LINE.matcher(dcfBody).replaceAll("");
        Matcher m = DATE.matcher(scrubbed);
        if (m.find()) {
            return m.group(1);
        }
        Matcher b = BANNER.matcher(text);
        if (b.find()) {
            Matcher d = DATE.matcher(b.group(0));
            if (d.find()) {
                return d.group(1);
            }
        }
        return Files.getLastModifiedTime(file).toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Double> loadNotebooklmValues(Path dcf) throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        Path p = dcf.resolve("notebooklm_values.json");
        if (!Files.exists(p)) {
            return out;
        }
        Map<String, Object> root =
                Blessed.Json.parse(Files.readString(p, StandardCharsets.UTF_8));
        Map<String, Object> values = (Map<String, Object>) root.get("values");
        if (values == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object row = e.getValue();
            Double v = null;
            if (row instanceof Map) {
                Object val = ((Map<String, Object>) row).get("value");
                if (val instanceof Number) {
                    v = ((Number) val).doubleValue();
                }
            } else if (row instanceof Number) {
                v = ((Number) row).doubleValue();
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    static void runSelfTest() throws Exception {
        String brief = "# AMZN \u2014 NotebookLM Research\n\n"
                + "Some intro.\n\n"
                + "## NotebookLM DCF value\n"
                + "- NotebookLM's DCF value: $152.42 (read back from the saved"
                + " NotebookLM note 2026-09-26).\n\n"
                + "## Related\n- [[AMZN]]\n";

        // Banner: inserted after H1 when absent.
        String b1 = stampBanner(brief, "AMZN", "2026-09-26");
        SelfTest.checkContains("banner inserted", b1, "FROZEN INPUT PACKAGE");
        SelfTest.checkContains("banner date", b1, "brief as of 2026-09-26");
        SelfTest.checkContains("banner points live", b1, "Live values: [[AMZN]]");
        SelfTest.check("banner after h1",
                b1.indexOf("# AMZN") < b1.indexOf("FROZEN INPUT PACKAGE")
                        && b1.indexOf("FROZEN INPUT PACKAGE") < b1.indexOf("Some intro."));

        // Banner: refreshed in place, never duplicated.
        String b2 = stampBanner(b1, "AMZN", "2026-09-27");
        SelfTest.checkContains("banner refreshed", b2, "brief as of 2026-09-27");
        SelfTest.checkEq("banner not duplicated", 1,
                b2.split("FROZEN INPUT PACKAGE", -1).length - 1);
        // Body untouched by the banner.
        SelfTest.checkContains("body untouched", b2, "$152.42");

        // Live line: inserted into the DCF section.
        String l1 = stampLiveLine(b2, "AMZN", 174.8,
                "blessed 2026-09-29 via prop-20260929-004");
        SelfTest.checkContains("live line inserted", l1,
                "- Live value: $174.80 \u2014 blessed 2026-09-29 via prop-20260929-004"
                        + " \u2014 see [[AMZN]].");
        SelfTest.check("live line inside dcf section",
                l1.indexOf("## NotebookLM DCF value") < l1.indexOf("- Live value:")
                        && l1.indexOf("- Live value:") < l1.indexOf("## Related"));
        // Frozen figure still present — the line is additive, not a rewrite.
        SelfTest.checkContains("frozen figure preserved", l1, "$152.42");

        // Live line: refreshed on rerun (idempotent content, not duplicated).
        String l2 = stampLiveLine(l1, "AMZN", 180.0,
                "blessed 2026-09-30 via prop-20260930-001");
        SelfTest.checkContains("live line refreshed", l2, "- Live value: $180");
        SelfTest.checkNotContains("old live line gone", l2, "$174.80 \u2014 blessed");
        SelfTest.checkEq("live line not duplicated", 1,
                l2.split("- Live value:", -1).length - 1);

        // Null value: explicit "no grounded value recorded".
        String l3 = stampLiveLine(brief, "CVX", null, "blessed date not recorded");
        SelfTest.checkContains("null live value", l3,
                "- Live value: \u2014 \u2014 no grounded value recorded");

        // No DCF section: banner plus a clearly owned live-status section
        // (QXO case, 2026-09-30) — the brief still shows its backend status.
        String noSec = "# CVX \u2014 NotebookLM Research\n\nIntro.\n";
        String s1 = stampLiveSection(
                stampLiveLine(stampBanner(noSec, "CVX", "2026-09-26"), "CVX", 133.43, "x"),
                "CVX", 133.43, "x");
        SelfTest.checkContains("banner without section", s1, "FROZEN INPUT PACKAGE");
        SelfTest.checkContains("live section without dcf section", s1,
                "## Live value (current)");
        SelfTest.checkContains("live section has live line", s1, "- Live value:");
        SelfTest.check("live section after banner",
                s1.indexOf("FROZEN INPUT PACKAGE") < s1.indexOf("## Live value (current)")
                        && s1.indexOf("## Live value (current)") < s1.indexOf("Intro."));
        // Idempotent: a second stamp changes nothing.
        String s1b = stampLiveSection(s1, "CVX", 133.43, "x");
        SelfTest.checkEq("live section idempotent", s1, s1b);
        // Null value: section says "no grounded value recorded", never invented.
        String s1c = stampLiveSection(stampBanner(noSec, "QXO", "2026-09-26"),
                "QXO", null, "blessed date not recorded");
        SelfTest.checkContains("live section null value", s1c,
                "- Live value: \u2014 \u2014 no grounded value recorded");

        // Money parity: the live line prints what the note's table prints.
        // (2026-09-30: the old tiered fmtMoney printed "$89.0" here.)
        String p1 = stampLiveLine(brief, "NVDA", 88.95,
                "blessed 2026-09-27 (bulk; no per-ticker proposal recorded)");
        SelfTest.checkContains("live line matches table format", p1,
                "- Live value: $88.95 \u2014 blessed 2026-09-27");

        // Brief date: first date in the DCF section.
        Matcher m = DCF_SECTION.matcher(brief);
        SelfTest.check("dcf section found", m.find());
        SelfTest.checkEq("brief date from section", "2026-09-26",
                DATE.matcher(m.group(1)).results().findFirst().get().group(1));

        // briefDate (2026-09-30): the live line's blessed date never leaks
        // into the frozen-as-of date; a stamped banner keeps its date.
        Path tmp = Files.createTempFile("briefdate", ".md");
        String dlBody = "- NotebookLM's DCF value: $152.42.\n\n"
                + "- Live value: $174.80 \u2014 blessed 2026-09-27 via prop-1"
                + " \u2014 see [[AMZN]].\n";
        String dlText = "# AMZN \u2014 NotebookLM Research\n\n"
                + "> [!note] FROZEN INPUT PACKAGE \u2014 brief as of 2026-09-26. x\n\n"
                + "## NotebookLM DCF value\n" + dlBody;
        Files.writeString(tmp, dlText, StandardCharsets.UTF_8);
        SelfTest.checkEq("live-line date does not leak", "2026-09-26",
                briefDate(dlBody, dlText, tmp));
        // Dateless body, never stamped: mtime fallback still works.
        String dlText2 = "# X \u2014 NotebookLM Research\n\n## NotebookLM DCF value\nNo dates.\n";
        Files.writeString(tmp, dlText2, StandardCharsets.UTF_8);
        String today = Files.getLastModifiedTime(tmp).toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate().toString();
        SelfTest.checkEq("mtime fallback when never stamped", today,
                briefDate("No dates.\n", dlText2, tmp));

        // Full-stamp idempotency on a dateless brief: the second pass —
        // which now sees the live line's blessed date in the DCF section —
        // changes nothing.
        String dateless = "# AVGO \u2014 NotebookLM Research\n\n"
                + "> [!note] FROZEN INPUT PACKAGE \u2014 brief as of 2026-09-26."
                + " Live values: [[AVGO]].\n\n## NotebookLM DCF value\n- no date here.\n";
        String blessed = "blessed 2026-09-27 (bulk; no per-ticker proposal recorded)";
        java.util.function.Function<String, String> fullStamp = t -> {
            try {
                String d = briefDate(
                        DCF_SECTION.matcher(t).results().findFirst().get().group(1), t, tmp);
                return stampLiveSection(
                        stampLiveLine(stampBanner(t, "AVGO", d), "AVGO", 131.24, blessed),
                        "AVGO", 131.24, blessed);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        String once = fullStamp.apply(dateless);
        SelfTest.checkContains("dateless banner kept", once, "brief as of 2026-09-26");
        SelfTest.checkEq("dateless brief idempotent", once, fullStamp.apply(once));
        Files.deleteIfExists(tmp);

        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path base = homeDir();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--base-dir")) {
                base = Paths.get(args[i + 1]);
            }
        }
        Path dcf = base.resolve("workspace/dcf_csv");
        Path briefs = base.resolve("workspace/atlas/05 \uD83D\uDCD3 NotebookLM");

        Map<String, Double> values = loadNotebooklmValues(dcf);
        Blessed.Store blessedStore = Blessed.load(dcf);

        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try (VaultSanitize san = new VaultSanitize();
                DirectoryStream<Path> ds = Files.newDirectoryStream(briefs,
                        "* \u2014 NotebookLM Research.md")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                String t = name.substring(0, name.indexOf(" \u2014 NotebookLM Research.md"));
                Double v = values.get(t); // null when absent too
                boolean knownTicker = values.containsKey(t);
                if (!knownTicker) {
                    skipped.add(t);
                    continue;
                }
                String text = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = DCF_SECTION.matcher(text);
                String dcfBody = m.find() ? m.group(1) : "";
                String date = briefDate(dcfBody, text, p);
                String blessed = Blessed.blessedSeg("notebooklm", t,
                        blessedStore.info, blessedStore.colDates);
                String stamped = stampLiveSection(stampLiveLine(stampBanner(text, t, date), t, v, blessed), t, v, blessed);
                String clean = san.sanitize(stamped);
                if (!clean.equals(text)) {
                    Files.writeString(p, clean, StandardCharsets.UTF_8);
                    updated.add(t);
                }
            }
        }
        System.out.println("updated: " + updated.size() + ", skipped: " + skipped);
    }
}
