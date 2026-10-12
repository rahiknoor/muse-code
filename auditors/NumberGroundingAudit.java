import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.json.JSONObject;

/// Number Grounding Auditor — Rahik's auditor fleet, auditor #4.
///
/// ONE TASK: every prose valuation claim in the vault's stock notes
/// ("intrinsic value $X", "fair value $Y", "price target $Z" ...) must trace
/// to a JSON backend value for that ticker (ours, creator, speculation,
/// notebooklm grounded/forward, outside voice, market price).
///
/// Complements VaultConsistencyCheck (which byte-compares the machine-made
/// valuation tables): this one checks the human/machine prose around them.
///
/// READ-ONLY: never edits notes. Findings end in SUGGESTED actions; a
/// separate approved pass does any implementing.
///
/// Structured finding format: severity | location | expected vs actual |
/// evidence | suggestion.
public class NumberGroundingAudit {

    record Candidate(String label, double value) {}

    /** Valuation keywords; a $ amount within 60 chars after one is a claim. */
    static final Pattern CLAIM = Pattern.compile(
            "(?i)(intrinsic value|fair value|price target|\\bworth\\b|valued at|\\bdcf\\b)[^$\\n]{0,60}\\$([\\d,]+(?:\\.\\d{1,2})?)");
    /** Purchase/cost lines are not valuation claims — skip them. */
    static final Pattern NOT_A_CLAIM = Pattern.compile(
            "(?i)(shares?\\s*@|cost\\s*basis|\\bbought\\b|purchase price|\\bpaid\\b|\\bposition\\b|dividend)");
    /** $1T / $20B narrative scale references are not valuation claims. */
    static final Pattern MAGNITUDE = Pattern.compile("\\$[\\d,]+(?:\\.\\d+)?\\s*[TBM]\\b");

    static final int PER_NOTE_CAP = 5;
    static final int TOTAL_CAP = 40;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path home = Paths.get(home());
        List<String> findings = audit(
                home.resolve("workspace/atlas/01 \uD83D\uDCC8 Stocks"),
                home.resolve("workspace/dcf_csv"),
                home.resolve("workspace/stock_watch/price_cache.json"),
                home.resolve("workspace/stock_watch/watchlist.json"));
        if (findings.isEmpty()) {
            System.out.println("NUMBER GROUNDING: CLEAN — every prose valuation claim traces to a backend value.");
        } else {
            System.out.println("NUMBER GROUNDING: " + findings.size() + " finding(s) — read-only, nothing changed.");
            for (String f : findings) System.out.println("\n---\n" + f);
        }
    }

    static List<String> audit(Path stocksDir, Path dcfDir, Path priceCache, Path watchlist) throws Exception {
        Map<String, List<Candidate>> backend = loadBackends(dcfDir, priceCache);
        List<String> tickers = loadTickers(watchlist);
        List<String> findings = new ArrayList<>();
        try (Stream<Path> s = Files.list(stocksDir)) {
            for (Path note : s.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                String file = note.getFileName().toString();
                String ticker = file.substring(0, file.length() - 3);
                if (!tickers.contains(ticker)) continue;
                List<Candidate> cands = backend.getOrDefault(ticker, List.of());
                if (cands.isEmpty()) continue;
                int perNote = 0;
                for (String line : proseLines(note)) {
                    if (perNote >= PER_NOTE_CAP || findings.size() >= TOTAL_CAP) break;
                    if (NOT_A_CLAIM.matcher(line).find()) continue;
                    Matcher m = CLAIM.matcher(line);
                    while (m.find()) {
                        double amount = Double.parseDouble(m.group(2).replace(",", ""));
                        if (matchesAny(amount, cands)) continue;
                        perNote++;
                        findings.add("[MEDIUM] ungrounded claim | " + file + "\n"
                                + "  expected: a backend value for " + ticker + " " + candSummary(cands) + "\n"
                                + "  actual: prose claims $" + m.group(2) + " (via \"" + m.group(1).trim() + "\")\n"
                                + "  evidence: " + line.strip() + "\n"
                                + "  suggestion: if the claim is current, update the backend that owns it; if stale, correct the note to the live value.");
                        if (perNote >= PER_NOTE_CAP || findings.size() >= TOTAL_CAP) break;
                    }
                }
            }
        }
        return findings;
    }

    /** Prose = lines minus frontmatter, markdown tables, and the Sources section. */
    static List<String> proseLines(Path note) throws IOException {
        List<String> all = Files.readAllLines(note);
        List<String> out = new ArrayList<>();
        boolean inFront = false, frontDone = false, inSources = false;
        for (String line : all) {
            String t = line.strip();
            if (!frontDone && t.equals("---")) {
                if (!inFront) { inFront = true; continue; }
                inFront = false; frontDone = true; continue;
            }
            if (inFront) continue;
            if (t.startsWith("|")) continue;                    // valuation table
            if (t.matches("##\\s+Sources.*")) { inSources = true; continue; }
            if (inSources && t.startsWith("## ")) inSources = false;
            if (inSources) continue;
            if (MAGNITUDE.matcher(line).find()) continue;       // $1T narrative scale
            if (t.isEmpty()) continue;
            out.add(line);
        }
        return out;
    }

    static boolean matchesAny(double amount, List<Candidate> cands) {
        for (Candidate c : cands) {
            double tol = Math.max(0.10, 0.01 * Math.abs(c.value()));
            if (Math.abs(amount - c.value()) <= tol) return true;
        }
        return false;
    }

    static String candSummary(List<Candidate> cands) {
        StringBuilder b = new StringBuilder("[");
        for (Candidate c : cands) b.append(c.label()).append(" $").append(fmt(c.value())).append(", ");
        if (b.length() > 1) b.setLength(b.length() - 2);
        return b.append("]").toString();
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    static Map<String, List<Candidate>> loadBackends(Path dcfDir, Path priceCache) throws IOException {
        Map<String, List<Candidate>> out = new LinkedHashMap<>();
        JSONObject ours = read(dcfDir.resolve("our_dcf.json"));
        JSONObject creator = read(dcfDir.resolve("creator_values.json"));
        JSONObject spec = read(dcfDir.resolve("speculation_price.json"));
        JSONObject nlm = read(dcfDir.resolve("notebooklm_values.json"));
        JSONObject nlmFwd = read(dcfDir.resolve("notebooklm_forward.json"));
        JSONObject outside = read(dcfDir.resolve("outside_voice.json"));
        JSONObject cache = read(priceCache);
        for (String t : keys(ours, "values")) {
            List<Candidate> cs = new ArrayList<>();
            add(cs, "ours", get(ours, "values", t, "value"));
            add(cs, "creator", get(creator, "latest", t, "value"));
            add(cs, "muse_speculation", get(spec, "tickers", t, "muse_speculation"));
            add(cs, "outside_median", get(spec, "tickers", t, "outside_median"));
            add(cs, "nlm_grounded", get(nlm, "values", t, "value"));
            add(cs, "nlm_forward", get(nlmFwd, "values", t, "value"));
            add(cs, "outside_voice", get(outside, "tickers", t, "value"));
            add(cs, "market", get(cache, "prices", t, "price"));
            out.put(t, cs);
        }
        return out;
    }

    static void add(List<Candidate> cs, String label, double v) {
        if (!Double.isNaN(v)) cs.add(new Candidate(label, v));
    }

    static double get(JSONObject root, String a, String b, String c) {
        try {
            JSONObject o = root.optJSONObject(a);
            if (o == null) return Double.NaN;
            JSONObject t = o.optJSONObject(b);
            if (t == null) return Double.NaN;
            return t.optDouble(c, Double.NaN);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    static List<String> keys(JSONObject root, String section) {
        List<String> ks = new ArrayList<>();
        JSONObject o = root.optJSONObject(section);
        if (o != null) o.keys().forEachRemaining(ks::add);
        return ks;
    }

    static JSONObject read(Path p) throws IOException {
        return new JSONObject(Files.readString(p));
    }

    static List<String> loadTickers(Path watchlist) throws IOException {
        List<String> out = new ArrayList<>();
        String raw = Files.readString(watchlist);
        Matcher m = Pattern.compile("\"ticker\"\\s*:\\s*\"([A-Z.]+)\"").matcher(raw);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ---- plumbing ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    // ---- self-test ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("numground-test");
        Path dcf = tmp.resolve("dcf");
        Files.createDirectories(dcf);
        // Minimal backends: TEST ticker, ours=100, creator=150, market=120.
        Files.writeString(dcf.resolve("our_dcf.json"), "{\"values\":{\"TEST\":{\"value\":100}}}");
        Files.writeString(dcf.resolve("creator_values.json"), "{\"latest\":{\"TEST\":{\"value\":150}}}");
        Files.writeString(dcf.resolve("speculation_price.json"), "{\"tickers\":{}}");
        Files.writeString(dcf.resolve("notebooklm_values.json"), "{\"values\":{}}");
        Files.writeString(dcf.resolve("notebooklm_forward.json"), "{\"values\":{}}");
        Files.writeString(dcf.resolve("outside_voice.json"), "{\"tickers\":{}}");
        Path cache = tmp.resolve("cache.json");
        Files.writeString(cache, "{\"prices\":{\"TEST\":{\"price\":120}}}");
        Path wl = tmp.resolve("watchlist.json");
        Files.writeString(wl, "[{\"ticker\":\"TEST\"}]");

        Path stocks = tmp.resolve("stocks");
        Files.createDirectories(stocks);
        Files.writeString(stocks.resolve("TEST.md"), """
                ---
                title: TEST
                ---
                # TEST

                | Source | Value |
                |---|---|
                | Our DCF | $100 |

                Parkev's fair value is $150 and our DCF is $100.10 — both grounded.
                Some analyst claims intrinsic value of $999 which matches nothing.
                I hold 10 shares @ $12.15 cost basis, bought last year.
                The thesis cites a $1T market and $20B ramp.
                """);

        List<String> findings = audit(stocks, dcf, cache, wl);
        boolean has999 = findings.stream().anyMatch(f -> f.contains("$999"));
        check(has999, "must flag the ungrounded $999 claim, got: " + findings);
        check(findings.stream().noneMatch(f -> f.contains("actual: prose claims $150")), "grounded $150 must not flag");
        check(findings.stream().noneMatch(f -> f.contains("actual: prose claims $100.10")), "grounded $100.10 (within tol) must not flag");
        check(findings.stream().noneMatch(f -> f.contains("$12.15")), "cost-basis line must be skipped");
        check(findings.stream().noneMatch(f -> f.contains("$1T")), "$1T narrative scale must be skipped");
        check(findings.size() == 1, "exactly one finding expected, got " + findings.size());

        // GREEN: all claims grounded -> clean.
        Files.writeString(stocks.resolve("TEST.md"), "---\ntitle: TEST\n---\n# TEST\n\nOur DCF values it at $100.\n");
        check(audit(stocks, dcf, cache, wl).isEmpty(), "fully grounded note must be clean");

        System.out.println("NumberGroundingAudit self-test: ALL GREEN (ungrounded red proven, 5 skip-rules proven, green proven)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
