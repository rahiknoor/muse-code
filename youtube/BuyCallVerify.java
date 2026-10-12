// BuyCallVerify.java — idea #44 health-check verifier for the
// youtube-buy-call-alerts cron (the hourly intraday buy-call scan).
//
// WHY THIS EXISTS: the scan writes buys.json (STEP 7) and rebuilds the
// Creator Buys spreadsheet (STEP 8). The no-inference rule is load-bearing
// here — a buy_price must never be invented, so vs_buy_pct must never be
// computed from an invented price. If the watermark is corrupt, if buys.json
// got corrupted, or if STEP 8 never ran, the alert pipeline silently drifts.
// This verifier fails LOUD instead.
//
// RUN IT with single-file launch (no compile step needed), AFTER STEP 8:
//   ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/lib/json/json-20240303.jar \
//       ~/workspace/youtube/BuyCallVerify.java
// Exit 0 = all checks passed. Any other exit = a check FAILED (see output).
//
// CHECKS:
//   1. The watermark file exists and holds a valid ISO-8601 UTC timestamp.
//   2. buys.json parses as valid JSON.
//   3. Cross-check: buy_price == null  =>  vs_buy_pct == null (never computed
//      from an invented price). When both are set, vs_buy_pct must agree with
//      the formula round1((current_price - buy_price) / buy_price * 100).
//   4. Every entry with a current_price also records price_asof (provenance).
//   5. Creator Buys.xlsx exists and is at least as new as buys.json — proving
//      STEP 8 (the sheet rebuild) actually ran after the tracker append.
//
// NOTE on paths: never use System.getProperty("user.home") here — on this
// machine it returns /root while the workspace lives under $HOME=/home/hatch.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class BuyCallVerify {

    // The one failure bucket every check reports into.
    private static final List<String> FAILURES = new ArrayList<>();

    private static Path ws() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            fail("HOME env var is not set — cannot resolve workspace paths");
            return null;
        }
        return Path.of(home, "workspace");
    }

    private static void fail(String msg) {
        FAILURES.add(msg);
    }

    // The watermark is written as an ISO-8601 UTC instant, e.g.
    // 2026-09-27T19:03:22Z. Anything else means a hand-edit or a bad write.
    private static void checkWatermark(Path ws) {
        Path wm = ws.resolve(
                "goals/daily-youtube-stock-video-digest/hidden_files/buy_alert_watermark.txt");
        if (!Files.isRegularFile(wm)) {
            fail("watermark file is missing: " + wm);
            return;
        }
        try {
            String text = Files.readString(wm, StandardCharsets.UTF_8).strip();
            Instant.parse(text); // throws on anything not ISO-8601 UTC
            System.out.println("OK  watermark parses as ISO-8601 UTC: " + text);
        } catch (IOException e) {
            fail("cannot read watermark file: " + e.getMessage());
        } catch (DateTimeParseException e) {
            fail("watermark is not a valid ISO-8601 UTC timestamp: " + e.getMessage());
        }
    }

    // Read buys.json and return its entries; null (and a recorded failure)
    // when the file is missing, empty, or not valid JSON.
    private static JSONArray readBuys(Path ws) {
        Path p = ws.resolve("creator_buys/buys.json");
        if (!Files.isRegularFile(p)) {
            fail("missing file: " + p);
            return null;
        }
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                fail("empty file: " + p);
                return null;
            }
            JSONArray arr = new JSONArray(text);
            System.out.println("OK  buys.json parses — " + arr.length() + " entries");
            return arr;
        } catch (IOException e) {
            fail("cannot read buys.json: " + e.getMessage());
            return null;
        } catch (JSONException e) {
            fail("buys.json is not valid JSON: " + e.getMessage());
            return null;
        }
    }

    // The no-inference cross-check: a null buy_price MUST keep
    // vs_buy_pct null. If both are set, the percentage must be the real
    // formula result — a hand-edited or invented percentage fails here.
    // Also: a current_price without price_asof has no provenance — fail.
    private static void checkBuysCross(JSONArray entries) {
        int checked = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e;
            try {
                e = entries.getJSONObject(i);
            } catch (JSONException ex) {
                fail("buys.json: entry #" + i + " is not an object");
                continue;
            }
            String label = "buys.json entry #" + i
                    + " (" + e.optString("ticker", "?") + "/"
                    + e.optString("creator", "?") + ")";
            checked++;

            boolean buyNull = e.isNull("buy_price");
            boolean pctNull = e.isNull("vs_buy_pct");
            if (buyNull && !pctNull) {
                fail(label + ": buy_price is null but vs_buy_pct is "
                        + e.optDouble("vs_buy_pct")
                        + " — computed from an INVENTED price");
            }
            if (!buyNull && !pctNull) {
                double buy = e.getDouble("buy_price");
                double cur = e.optDouble("current_price", Double.NaN);
                if (!Double.isNaN(cur) && buy != 0) {
                    double expected = Math.round((cur - buy) / buy * 1000.0) / 10.0;
                    double actual = e.getDouble("vs_buy_pct");
                    if (Math.abs(expected - actual) > 0.15) {
                        fail(label + ": vs_buy_pct " + actual
                                + " does not match the formula result " + expected);
                    }
                }
            }
            // #44 cross-check rule: a number written to a backend/state file
            // must carry its provenance.
            if (!e.isNull("current_price")
                    && e.optString("price_asof", "").isBlank()) {
                fail(label + ": current_price set but price_asof is missing "
                        + "(no provenance for the price)");
            }
            if (!e.isNull("current_price")) {
                double v = e.optDouble("current_price", Double.NaN);
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    fail(label + ": current_price is not a finite number");
                }
            }
        }
        System.out.println("OK  no-inference + provenance cross-checks on "
                + checked + " buys.json entries");
    }

    // STEP 8 proof: the spreadsheet must be at least as new as the tracker
    // it was rebuilt from. If it is older, the rebuild never ran.
    private static void checkSheetFreshness(Path ws) {
        Path buys = ws.resolve("creator_buys/buys.json");
        Path sheet = ws.resolve("your_files/creator-buys/Creator Buys.xlsx");
        if (!Files.isRegularFile(sheet)) {
            fail("missing file: " + sheet);
            return;
        }
        try {
            FileTime buysMtime = Files.getLastModifiedTime(buys);
            FileTime sheetMtime = Files.getLastModifiedTime(sheet);
            if (sheetMtime.compareTo(buysMtime) < 0) {
                fail("Creator Buys.xlsx (" + sheetMtime.toInstant() + ") is OLDER than "
                        + "buys.json (" + buysMtime.toInstant()
                        + ") — STEP 8 sheet rebuild did not run after the append");
                return;
            }
            System.out.println("OK  Creator Buys.xlsx newer than buys.json "
                    + "(STEP 8 ran)");
        } catch (IOException e) {
            fail("cannot stat buys.json / Creator Buys.xlsx: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        Path ws = ws();
        if (ws == null) {
            printReport();
            return;
        }
        checkWatermark(ws);
        JSONArray entries = readBuys(ws);
        if (entries != null) {
            checkBuysCross(entries);
        }
        checkSheetFreshness(ws);
        printReport();
    }

    private static void printReport() {
        if (FAILURES.isEmpty()) {
            System.out.println("VERIFY PASS — youtube-buy-call-alerts "
                    + "tracker state is healthy");
        } else {
            System.err.println("VERIFY FAIL — " + FAILURES.size()
                    + " check(s) failed:");
            for (String f : FAILURES) {
                System.err.println("  FAIL: " + f);
            }
            System.exit(1);
        }
    }
}
