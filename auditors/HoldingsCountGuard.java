import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.json.JSONObject;

/// Holdings-count guard (BLG-030) — auditor fleet helper for VaultConsistencyAudit #2.
///
/// ONE TASK: scan vault prose for "N holdings" / "N stocks" count claims and
/// compare each claim's N against the LIVE watchlist.json ticker count.
/// Reports mismatches as structured findings; never edits anything.
///
/// Why this exists: VaultConsistencyAudit's old inline check counted the
/// substring "\"ticker\"" in watchlist.json to get the live count. The real
/// file stores tickers as a plain string array under the "tickers" key, so
/// the count came out 0 and the check silently returned CLEAN — BLG-027's
/// eight stale "31 holdings" notes slipped through. This guard parses the
/// JSON properly and fails loud (exit 2) when the backend can't be read.
///
/// Scan rules:
/// - Skips YAML frontmatter (--- ... --- at the top of a note),
///   fenced code blocks (``` ... ```), Archive paths, the 03 Chats folder
///   (chat logs are historical records), and append-only record notes
///   (Task & Failure Ledger, Vault Changelog, BLOCKED.md) where old numbers
///   are history, not current claims.
/// - Flags N != actual only when |N - actual| <= STALE_BAND (5). A holdings
///   count goes stale by a ticker or two when the roster changes; a claim
///   far from the live count ("~80 holdings" of an institution, "5 stocks"
///   in a video title) is about something else and stays quiet.
///
/// Exit codes: 0 = clean, 1 = findings, 2 = backend unreadable.
public class HoldingsCountGuard {

    /** Claims within this distance of the live count are treated as stale-total candidates. */
    static final int STALE_BAND = 5;

    /** Append-only record notes: counts in them are history, never current claims. */
    static final Set<String> RECORD_NOTES = Set.of(
            "task & failure ledger.md", "vault changelog.md", "blocked.md");

    static final Pattern CLAIM = Pattern.compile("(\\d{1,3})\\s+(holdings|stocks)",
            Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path vault = args.length > 0 ? Paths.get(args[0]) : Paths.get(home(), "workspace", "atlas");
        Path watchlist = args.length > 1 ? Paths.get(args[1])
                : Paths.get(home(), "workspace", "stock_watch", "watchlist.json");
        List<String> findings;
        int actual;
        try {
            actual = liveCount(watchlist);
            findings = check(vault, actual);
        } catch (Exception e) {
            System.out.println("HOLDINGS COUNT: ERROR — " + firstLine(e.getMessage()));
            System.exit(2);
            return;
        }
        if (findings.isEmpty()) {
            System.out.println("HOLDINGS COUNT: CLEAN — all prose claims match watchlist.json (" + actual + ").");
        } else {
            System.out.println("HOLDINGS COUNT: " + findings.size()
                    + " finding(s) — read-only, nothing changed.");
            for (String f : findings) System.out.println("\n---\n" + f);
            System.exit(1);
        }
    }

    /** Live holdings count, parsed from the tickers array (never substring-counted). */
    static int liveCount(Path watchlist) throws IOException {
        if (!Files.exists(watchlist)) throw new IOException("watchlist.json not found: " + watchlist);
        return new JSONObject(Files.readString(watchlist)).getJSONArray("tickers").length();
    }

    /** Scan vault prose; one structured finding per stale count claim. */
    static List<String> check(Path vault, int actual) throws IOException {
        List<String[]> rows = new ArrayList<>(); // rel, lineNo, finding
        try (Stream<Path> s = Files.walk(vault)) {
            List<Path> notes = s.filter(p -> p.toString().endsWith(".md")
                    && !p.toString().contains("/.git/")).sorted().toList();
            for (Path n : notes) {
                String rel = vault.relativize(n).toString();
                if (isExcluded(rel)) continue;
                List<String> lines = Files.readAllLines(n);
                boolean inFence = false;
                boolean inFrontmatter = false;
                for (int i = 0; i < lines.size(); i++) {
                    String stripped = lines.get(i).strip();
                    if (i == 0 && stripped.equals("---")) { inFrontmatter = true; continue; }
                    if (inFrontmatter) {
                        if (stripped.equals("---") || stripped.equals("...")) inFrontmatter = false;
                        continue;
                    }
                    if (stripped.startsWith("```")) { inFence = !inFence; continue; }
                    if (inFence) continue;
                    Matcher m = CLAIM.matcher(lines.get(i));
                    while (m.find()) {
                        int n1 = Integer.parseInt(m.group(1));
                        if (n1 != actual && Math.abs(n1 - actual) <= STALE_BAND) {
                            rows.add(new String[]{rel, String.valueOf(i + 1),
                                "[MEDIUM] stale holdings count | " + rel + ":" + (i + 1) + "\n"
                                + "  expected: " + actual + " (watchlist.json tickers)\n"
                                + "  actual: note claims \"" + m.group(0) + "\"\n"
                                + "  evidence: " + lines.get(i).strip() + "\n"
                                + "  suggestion: pitch updating the number to " + actual
                                + " (or a pointer to the live count) — report only, never silently rewrite."});
                        }
                    }
                }
            }
        }
        rows.sort(Comparator.comparing((String[] r) -> r[0])
                .thenComparingInt(r -> Integer.parseInt(r[1])));
        List<String> out = new ArrayList<>();
        for (String[] r : rows) out.add(r[2]);
        return out;
    }

    static boolean isExcluded(String rel) {
        String lower = rel.toLowerCase();
        if (lower.contains("archive")) return true;                       // archives are records
        String first = lower.contains("/") ? lower.substring(0, lower.indexOf('/')) : lower;
        if (first.startsWith("03")) return true;                            // 03 Chats: chat logs are records
        String name = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        return RECORD_NOTES.contains(name);                                 // append-only logs
    }

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    // ---- self-test (red + green proven) ----

    static void runSelfTest() throws Exception {
        Path vault = Files.createTempDirectory("holdingsguard-test");
        Path watchlist = vault.resolve("watchlist.json");
        StringBuilder tickers = new StringBuilder("{\"tickers\":[");
        for (int i = 0; i < 32; i++) tickers.append(i == 0 ? "\"T" + i + "\"" : ",\"T" + i + "\"");
        Files.writeString(watchlist, tickers.append("]}").toString());

        // RED: stale claim in live prose must be flagged with file + line.
        Files.writeString(vault.resolve("Live.md"),
                "# Live\n\nLine two.\nWe track 31 holdings across the portfolio.\n");
        List<String> red = check(vault, 32);
        check(red.size() == 1 && red.get(0).contains("Live.md:4")
                && red.get(0).contains("\"31 holdings\"") && red.get(0).contains("expected: 32"),
                "must flag '31 holdings' with file:line vs actual 32, got: " + red);

        // GREEN: corrected claim goes quiet.
        Files.writeString(vault.resolve("Live.md"), "# Live\n\nWe track 32 holdings across the portfolio.\n");
        check(check(vault, 32).isEmpty(), "corrected '32 holdings' must report clean");

        // Exclusions, each holding a stale "31 holdings" that must stay quiet.
        Files.writeString(vault.resolve("Fenced.md"), "# F\n\n```\n31 holdings in code\n```\n");
        Files.writeString(vault.resolve("Front.md"), "---\ntitle: 31 holdings\n---\n\nBody.\n");
        Path arch = vault.resolve("Archive"); Files.createDirectories(arch);
        Files.writeString(arch.resolve("Old.md"), "# Old\n\n31 holdings back then.\n");
        Path chats = vault.resolve("03 Chats"); Files.createDirectories(chats);
        Files.writeString(chats.resolve("Log.md"), "# Log\n\nSaid 31 holdings on air.\n");
        Files.writeString(vault.resolve("Task & Failure Ledger.md"), "# Ledger\n\nFixed 31 holdings notes.\n");
        Files.writeString(vault.resolve("FarAway.md"), "# F\n\nThe fund runs ~80 holdings.\n");
        Files.writeString(vault.resolve("Video.md"), "# V\n\n\"I'm Buying These 5 Stocks\"\n");
        List<String> quiet = check(vault, 32);
        check(quiet.isEmpty(), "exclusions + out-of-band numbers must stay quiet, got: " + quiet);

        // Band edges: actual+/-5 flagged, actual+/-6 quiet.
        Files.writeString(vault.resolve("Edge.md"), "27 holdings here, and 37 stocks there.\n");
        List<String> edge = check(vault, 32);
        check(edge.size() == 2, "band edges 27/37 vs 32 must both flag, got: " + edge);
        Files.writeString(vault.resolve("Edge.md"), "26 holdings here.\n");
        check(check(vault, 32).stream().noneMatch(f -> f.contains("Edge.md")),
                "26 vs 32 (band 5) must stay quiet");

        // Backend unreadable -> loud error, not silent clean.
        boolean threw = false;
        try { liveCount(vault.resolve("missing.json")); } catch (IOException e) { threw = true; }
        check(threw, "missing watchlist.json must throw (exit 2 path)");

        // The old dead-code bug, regression-tested: substring count must NOT be used.
        check(liveCount(watchlist) == 32, "liveCount must parse the tickers array (32), not substring-count");

        System.out.println("HoldingsCountGuard self-test: ALL GREEN "
                + "(stale-claim red+green, 7 exclusions, band edges, backend-error path proven)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
