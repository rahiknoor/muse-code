import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

/**
 * Answer-time grounding check (IDEA 3, 2026-09-28).
 *
 * <p>Verifies a value claim about to be stated in a chat reply against the
 * JSON backend of record and prints PASS (claim matches) or FAIL (claim
 * differs - correct the claim from the backend value shown). Any value
 * claim in a chat reply gets one GroundingCheck call before send.
 *
 * <p>Field -> backend mapping:
 * <ul>
 *   <li>ours / notebooklm / creator / analyst -> dcf_csv/blessed.json
 *       columns.&lt;field&gt;.tickers.&lt;T&gt;.value</li>
 *   <li>market (alias: price) -> stock_watch/price_cache.json
 *       quotes.&lt;T&gt;.price</li>
 *   <li>consensus (alias: website) -> dcf_csv/website_consensus.json
 *       tickers.&lt;T&gt;.consensus</li>
 * </ul>
 *
 * <p>Match rule: a claim passes when it rounds to the same cent as the
 * backend value. Chat claims are written to 2 decimals; backends (blessed
 * DCF values) carry full double precision. Any real mismatch fails.
 *
 * <p>Exit codes: 0 = PASS, 1 = FAIL (mismatch), 2 = usage/lookup error.
 *
 * <p>Launch:
 * <pre>
 *   ~/workspace/java/jdk-21/bin/java \
 *     --class-path ~/workspace/java-shared/classes:~/workspace/lib/json/json-20240303.jar \
 *     ~/workspace/shared/GroundingCheck.java --check --ticker AMZN --field ours --value 26.60
 * </pre>
 */
public final class GroundingCheck {

    private static final String HOME = System.getenv("HOME");
    private static final String BLESSED = HOME + "/workspace/dcf_csv/blessed.json";
    private static final String PRICE_CACHE = HOME + "/workspace/stock_watch/price_cache.json";
    private static final String CONSENSUS = HOME + "/workspace/dcf_csv/website_consensus.json";

    /** Outcome of one claim check. */
    static final class ClaimResult {
        final boolean ok;          // PASS when true
        final boolean lookupError; // unknown ticker/field or missing value
        final String ticker;
        final String field;
        final Double claimed;
        final Double backend;      // null on lookup error
        final String message;      // the PASS/FAIL/ERROR line to print

        ClaimResult(boolean ok, boolean lookupError, String ticker, String field,
                    Double claimed, Double backend, String message) {
            this.ok = ok;
            this.lookupError = lookupError;
            this.ticker = ticker;
            this.field = field;
            this.claimed = claimed;
            this.backend = backend;
            this.message = message;
        }
    }

    private GroundingCheck() {
        // static-only
    }

    /** Canonical field names, with accepted aliases. */
    private static String canonicalField(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase(Locale.US)) {
            case "ours": return "ours";
            case "notebooklm": return "notebooklm";
            case "creator": return "creator";
            case "analyst": return "analyst";
            case "market":
            case "price": return "market";
            case "consensus":
            case "website": return "consensus";
            default: return null;
        }
    }

    private static JSONObject readJson(String path) throws Exception {
        return new JSONObject(Files.readString(Paths.get(path), StandardCharsets.UTF_8));
    }

    private static String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** PASS iff claim and backend round to the same cent. */
    static boolean sameCent(double claimed, double backend) {
        return Math.round(claimed * 100.0) == Math.round(backend * 100.0);
    }

    /** Checks one claim; never throws (lookup failures become ERROR results). */
    static ClaimResult checkClaim(String tickerRaw, String fieldRaw, double claimed) {
        String ticker = tickerRaw == null ? "" : tickerRaw.trim().toUpperCase(Locale.US);
        String field = canonicalField(fieldRaw);
        if (field == null) {
            return new ClaimResult(false, true, ticker, fieldRaw, claimed, null,
                    "ERROR: unknown field '" + fieldRaw
                    + "' - use ours | notebooklm | creator | analyst | market | consensus");
        }
        try {
            switch (field) {
                case "ours":
                case "notebooklm":
                case "creator":
                case "analyst": {
                    JSONObject root = readJson(BLESSED);
                    JSONObject col = root.getJSONObject("columns").optJSONObject(field);
                    if (col == null) {
                        return error(ticker, field, claimed, BLESSED, "no column '" + field + "'");
                    }
                    JSONObject tickers = col.optJSONObject("tickers");
                    if (tickers == null || !tickers.has(ticker) || tickers.isNull(ticker)) {
                        return error(ticker, field, claimed, BLESSED, "unknown ticker");
                    }
                    JSONObject rec = tickers.getJSONObject(ticker);
                    if (!rec.has("value") || rec.isNull("value")) {
                        return error(ticker, field, claimed, BLESSED, "no value recorded");
                    }
                    double backend = rec.getDouble("value");
                    String provenance = col.optString("store", "");
                    String detail = "backend dcf_csv/blessed.json"
                            + (rec.has("blessed_at") ? ", blessed " + rec.optString("blessed_at") : "")
                            + (provenance.isEmpty() ? "" : " | source: " + provenance);
                    return verdict(ticker, field, claimed, backend, detail);
                }
                case "market": {
                    JSONObject root = readJson(PRICE_CACHE);
                    JSONObject quotes = root.optJSONObject("quotes");
                    if (quotes == null || !quotes.has(ticker) || quotes.isNull(ticker)) {
                        return error(ticker, field, claimed, PRICE_CACHE, "unknown ticker");
                    }
                    JSONObject rec = quotes.getJSONObject(ticker);
                    if (!rec.has("price") || rec.isNull("price")) {
                        return error(ticker, field, claimed, PRICE_CACHE, "no price recorded");
                    }
                    double backend = rec.getDouble("price");
                    String detail = "backend stock_watch/price_cache.json"
                            + (root.has("asof") ? ", asof " + root.optString("asof") : "")
                            + (rec.has("session_date") ? ", session " + rec.optString("session_date") : "");
                    return verdict(ticker, field, claimed, backend, detail);
                }
                case "consensus": {
                    JSONObject root = readJson(CONSENSUS);
                    JSONObject tickers = root.optJSONObject("tickers");
                    if (tickers == null || !tickers.has(ticker) || tickers.isNull(ticker)) {
                        return error(ticker, field, claimed, CONSENSUS, "unknown ticker");
                    }
                    JSONObject rec = tickers.getJSONObject(ticker);
                    if (!rec.has("consensus") || rec.isNull("consensus")) {
                        return error(ticker, field, claimed, CONSENSUS, "no consensus recorded (no site values yet)");
                    }
                    double backend = rec.getDouble("consensus");
                    String detail = "backend dcf_csv/website_consensus.json"
                            + (root.has("asof") ? ", asof " + root.optString("asof") : "")
                            + (rec.has("n_sources") ? ", " + rec.optInt("n_sources") + " site(s)" : "");
                    return verdict(ticker, field, claimed, backend, detail);
                }
                default:
                    throw new IllegalStateException("unreachable field: " + field);
            }
        } catch (ClaimResultException e) {
            return e.result;
        } catch (Exception e) {
            return new ClaimResult(false, true, ticker, field, claimed, null,
                    "ERROR: could not read backend (" + e.getMessage() + ")");
        }
    }

    /** Wraps the checked-exception path so checkClaim stays throw-free. */
    private static final class ClaimResultException extends RuntimeException {
        final ClaimResult result;
        ClaimResultException(ClaimResult result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    private static ClaimResult error(String ticker, String field, double claimed,
                                     String file, String reason) {
        return new ClaimResult(false, true, ticker, field, claimed, null,
                "ERROR: " + ticker + " " + field + " - " + reason
                + " in " + shortPath(file));
    }

    private static ClaimResult verdict(String ticker, String field, double claimed,
                                       double backend, String detail) {
        if (sameCent(claimed, backend)) {
            return new ClaimResult(true, false, ticker, field, claimed, backend,
                    "PASS: " + ticker + " " + field + " = " + fmt2(backend)
                    + " (claim " + fmt2(claimed) + " matches; " + detail + ")");
        }
        return new ClaimResult(false, false, ticker, field, claimed, backend,
                "FAIL: " + ticker + " " + field + " claimed " + fmt2(claimed)
                + " != backend " + fmt2(backend)
                + " (" + detail + ") - correct the claim from the backend value");
    }

    private static String shortPath(String abs) {
        String marker = "/workspace/";
        int i = abs.indexOf(marker);
        return i >= 0 ? abs.substring(i + marker.length()) : abs;
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  GroundingCheck --check --ticker AMZN --field ours --value 26.60");
        System.out.println("  GroundingCheck --self-test");
        System.out.println("Fields: ours | notebooklm | creator | analyst | market (alias: price) | consensus (alias: website)");
        System.out.println("Exit codes: 0 PASS, 1 FAIL (mismatch), 2 usage/lookup error");
    }

    public static void main(String[] args) {
        boolean isCheck = false;
        boolean isSelfTest = false;
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--check")) {
                isCheck = true;
                continue;
            }
            if (a.equals("--self-test")) {
                isSelfTest = true;
                continue;
            }
            if (a.equals("--help") || a.equals("-h")) {
                usage();
                return;
            }
            if (a.startsWith("--") && i + 1 < args.length) {
                opts.put(a.substring(2), args[++i]);
            }
        }
        if (isSelfTest) {
            runSelfTest();
            return;
        }
        String ticker = opts.get("ticker");
        String field = opts.get("field");
        String value = opts.get("value");
        if (!isCheck || ticker == null || field == null || value == null) {
            System.err.println("ERROR: need --check --ticker T --field F --value V");
            usage();
            System.exit(2);
            return;
        }
        double claimed;
        try {
            claimed = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            System.err.println("ERROR: --value '" + value + "' is not a number");
            System.exit(2);
            return;
        }
        ClaimResult r = checkClaim(ticker, field, claimed);
        if (r.lookupError) {
            System.err.println(r.message);
            System.exit(2);
        }
        System.out.println(r.message);
        System.exit(r.ok ? 0 : 1);
    }

    // ---- --self-test ---------------------------------------------------

    /** Runs real assertions against the live backends (no hardcoded values). */
    private static void runSelfTest() {
        try {
            // Discover live fixtures at runtime so the test never goes stale.
            JSONObject blessed = readJson(BLESSED);
            String oursTicker = firstKey(blessed.getJSONObject("columns")
                    .getJSONObject("ours").getJSONObject("tickers"));
            double oursValue = blessed.getJSONObject("columns").getJSONObject("ours")
                    .getJSONObject("tickers").getJSONObject(oursTicker).getDouble("value");

            JSONObject cache = readJson(PRICE_CACHE);
            String priceTicker = firstKey(cache.getJSONObject("quotes"));
            double priceValue = cache.getJSONObject("quotes")
                    .getJSONObject(priceTicker).getDouble("price");

            JSONObject consensus = readJson(CONSENSUS);
            String consensusTicker = null;
            double consensusValue = 0;
            for (String t : consensus.getJSONObject("tickers").keySet()) {
                JSONObject rec = consensus.getJSONObject("tickers").getJSONObject(t);
                if (!rec.isNull("consensus")) {
                    consensusTicker = t;
                    consensusValue = rec.getDouble("consensus");
                    break;
                }
            }

            // 1. A correct claim passes, naming the backend file.
            ClaimResult ok = checkClaim(oursTicker, "ours", round2(oursValue));
            SelfTest.check("correct claim passes", ok.ok && !ok.lookupError);
            SelfTest.checkContains("pass names backend file", ok.message, "blessed.json");

            // 2. A wrong claim fails and shows the true backend value.
            ClaimResult bad = checkClaim(oursTicker, "ours", round2(oursValue) + 10.0);
            SelfTest.check("wrong claim fails", !bad.ok && !bad.lookupError);
            SelfTest.checkContains("fail shows true backend value", bad.message, fmt2(oursValue));

            // 3. Unknown ticker errors cleanly (exit-2 path), not a crash.
            ClaimResult unknown = checkClaim("ZZZZ", "ours", 1.0);
            SelfTest.check("unknown ticker is a lookup error", unknown.lookupError);
            SelfTest.checkContains("unknown ticker message names ticker", unknown.message, "ZZZZ");

            // 4. Unknown field errors cleanly.
            ClaimResult badField = checkClaim(oursTicker, "wacc", 1.0);
            SelfTest.check("unknown field is a lookup error", badField.lookupError);
            SelfTest.checkContains("unknown field message", badField.message, "unknown field");

            // 5. Field aliases resolve to the canonical field.
            ClaimResult alias = checkClaim(priceTicker, "price", priceValue);
            SelfTest.check("alias 'price' behaves like market",
                    alias.ok && !alias.lookupError && alias.field.equals("market"));
            if (consensusTicker != null) {
                ClaimResult alias2 = checkClaim(consensusTicker, "website", round2(consensusValue));
                SelfTest.check("alias 'website' behaves like consensus",
                        alias2.ok && !alias2.lookupError && alias2.field.equals("consensus"));
            }

            // 6. Market-price field reads the price cache.
            ClaimResult price = checkClaim(priceTicker, "market", priceValue);
            SelfTest.check("correct price claim passes", price.ok && !price.lookupError);
            SelfTest.checkContains("price names cache file", price.message, "price_cache.json");

            // 7. Consensus field reads website_consensus.json.
            if (consensusTicker != null) {
                ClaimResult con = checkClaim(consensusTicker, "consensus", round2(consensusValue));
                SelfTest.check("correct consensus claim passes", con.ok && !con.lookupError);
                SelfTest.checkContains("consensus names backend file", con.message, "website_consensus.json");
            } else {
                SelfTest.check("consensus backend has at least one value", false);
            }

            // 8. Cent-match rule on the pure function (backend-independent).
            SelfTest.check("same-cent: 26.60 vs 26.604 passes", sameCent(26.60, 26.604));
            SelfTest.check("same-cent: 26.60 vs 26.61 fails", !sameCent(26.60, 26.61));

            // 9. Claimed value keeps the backend value attached for correction.
            SelfTest.checkEq("fail carries backend value", oursValue, bad.backend);
        } catch (Exception e) {
            SelfTest.check("self-test ran without exception: " + e, false);
        }
        SelfTest.finish();
    }

    private static String firstKey(JSONObject obj) {
        for (String k : obj.keySet()) {
            return k;
        }
        return null;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
