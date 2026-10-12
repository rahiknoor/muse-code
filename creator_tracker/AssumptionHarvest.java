// AssumptionHarvest.java — DCF assumption candidate scanner AND verified writer.
//
// --file mode: extracts DCF assumption candidates from a transcript file.
//   Usage: java AssumptionHarvest.java --file <transcript.txt> [--creator parkev|jeremy|couch]
//   Output: one line per candidate: FIELD | value | "exact quote..."
//   Missing fields print nothing. A match is a CANDIDATE — verify the quote
//   against the transcript before logging it anywhere.
//   NOTE (2026-09-28, efficiency consolidation): --file is NOT on the daily
//   digest path anymore. Rahik's one-pass rule — one fetch per transcript,
//   one extraction pass for all fields — means the digest's STEP 3 read
//   already pulls assumptions with their exact quotes. Re-scanning the
//   transcript in a later step was a duplicate read. --file stays for
//   one-off backfills over saved transcript files.
//
// --append mode: writes VERIFIED assumptions (already extracted with exact
//   quotes in the digest's single pass) to creator_assumptions.json.
//   Usage: java AssumptionHarvest.java --append --ticker ORCL --creator parkev
//     --video-id VID --video-title "T" --date 2026-09-28
//     [--growth_rate 15 --growth_rate_quote "..."]
//     [--discount_wacc 10 --discount_wacc_quote "..."]
//     [--terminal_growth 3 --terminal_growth_quote "..."]
//     [--fcf_projection 5000 --fcf_projection_quote "..."]
//     [--margin 40 --margin_quote "..."]
//   Rules: every stated field MUST carry its exact <field>_quote (refused
//   without it — a value without its source is not logged); same
//   ticker+creator+field+value is never logged twice; ids are ca-NNN.
//   Values are stored as numbers (the cross-check accepts "15" or "0.15"
//   for percent fields).
//
// --self-test covers both modes.
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

import org.json.JSONArray;
import org.json.JSONObject;

public class AssumptionHarvest {
    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && args[0].equals("--self-test")) { selfTest(); return; }
        if (args.length >= 1 && args[0].equals("--append")) { runAppend(args); return; }

        String file = null, creator = "unknown";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--file")) file = args[i + 1];
            if (args[i].equals("--creator")) creator = args[i + 1];
        }
        if (file == null) { System.err.println("Usage: --file <txt> [--creator name] | --append ..."); System.exit(2); }

        String text = Files.readString(Path.of(file));
        boolean any = false;
        for (String field : AssumptionPatterns.FIELDS) {
            String pat = AssumptionPatterns.patternFor(field);
            Matcher m = Pattern.compile(pat).matcher(text);
            while (m.find()) {
                any = true;
                String val = firstGroup(m);
                String quote = context(text, m.start(), m.end());
                System.out.println(field + " | " + val + " | \"" + quote + "\"");
            }
        }
        if (!any) System.out.println("(no assumption candidates found — creator did not state assumptions explicitly)");
    }

    static String firstGroup(Matcher m) {
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) != null) return m.group(g).replace(",", "");
        }
        return "?";
    }

    static String context(String text, int s, int e) {
        int a = Math.max(0, s - 90), b = Math.min(text.length(), e + 90);
        return text.substring(a, b).replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // --append mode: the writer. Pure helpers below are unit-tested; only
    // runAppend touches the real store file.
    // ------------------------------------------------------------------

    private static void die(String msg) {
        System.err.println("AssumptionHarvest --append refused: " + msg);
        System.exit(2);
    }

    private static Path storePath() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) die("HOME env var is not set");
        return Path.of(home, "workspace/creator_tracker/creator_assumptions.json");
    }

    // Next free numeric id for prefix ca- (1 when the log is empty).
    static int nextIdNum(JSONArray arr) {
        int max = 0;
        for (int i = 0; i < arr.length(); i++) {
            String id = arr.optJSONObject(i).optString("id", "");
            if (id.startsWith("ca-")) {
                try { max = Math.max(max, Integer.parseInt(id.substring(3))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return max + 1;
    }

    // Dedupe: same ticker+creator+field+value is never logged twice.
    static boolean isDup(JSONArray arr, String ticker, String creator,
                         String field, double value) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null || !e.has(field)) continue;
            if (!e.optString("ticker", "").equalsIgnoreCase(ticker)) continue;
            if (!e.optString("creator", "").equalsIgnoreCase(creator)) continue;
            try {
                if (Math.abs(e.getDouble(field) - value) < 1e-9) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    // Every stated field must carry its exact quote; at least one field.
    // Returns null when valid, else the refusal reason.
    static String validateAppend(Map<String, Double> vals, Map<String, String> quotes) {
        if (vals.isEmpty()) return "no assumption fields given";
        for (String f : vals.keySet()) {
            String q = quotes.get(f);
            if (q == null || q.isBlank()) return f + " has a value but no exact quote";
        }
        return null;
    }

    static void runAppend(String[] args) throws Exception {
        Map<String, String> meta = new LinkedHashMap<>();
        Map<String, Double> vals = new LinkedHashMap<>();
        Map<String, String> quotes = new LinkedHashMap<>();
        Set<String> fields = new HashSet<>(Arrays.asList(AssumptionPatterns.FIELDS));
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--ticker") || a.equals("--creator") || a.equals("--video-id")
                    || a.equals("--video-title") || a.equals("--date")) {
                if (i + 1 >= args.length) die("missing value for " + a);
                String key = a.substring(2).replace('-', '_');
                meta.put(key, args[++i]);
            } else if (a.startsWith("--")) {
                String name = a.substring(2);
                boolean isQuote = name.endsWith("_quote");
                String field = isQuote ? name.substring(0, name.length() - 6) : name;
                if (!fields.contains(field)) die("unknown option " + a);
                if (i + 1 >= args.length) die("missing value for " + a);
                String v = args[++i];
                if (isQuote) {
                    quotes.put(field, v);
                } else {
                    try { vals.put(field, Double.parseDouble(v)); }
                    catch (NumberFormatException e) { die("not a number for " + a + ": " + v); }
                }
            } else {
                die("unexpected argument " + a);
            }
        }
        String ticker = meta.get("ticker"), creator = meta.get("creator");
        String videoId = meta.get("video_id"), date = meta.get("date");
        if (ticker == null || ticker.isBlank()) die("--ticker is required");
        if (creator == null || creator.isBlank()) die("--creator is required");
        if (videoId == null || videoId.isBlank()) die("--video-id is required");
        if (date == null || date.isBlank()) die("--date is required");
        String bad = validateAppend(vals, quotes);
        if (bad != null) die(bad);

        Path store = storePath();
        JSONObject root = new JSONObject(Files.readString(store, StandardCharsets.UTF_8));
        JSONArray arr = root.getJSONArray("assumptions");

        JSONObject entry = new JSONObject();
        entry.put("id", String.format("ca-%03d", nextIdNum(arr)));
        entry.put("ticker", ticker.toUpperCase());
        entry.put("creator", creator);
        entry.put("video_id", videoId);
        entry.put("video_title", meta.getOrDefault("video_title", ""));
        entry.put("date", date);
        int addedFields = 0, dupFields = 0;
        for (String f : AssumptionPatterns.FIELDS) {
            if (!vals.containsKey(f)) continue;
            double v = vals.get(f);
            if (isDup(arr, ticker, creator, f, v)) {
                System.out.println("SKIP duplicate: " + ticker.toUpperCase()
                    + " " + f + "=" + v + " already logged");
                dupFields++;
                continue;
            }
            entry.put(f, v);
            entry.put(f + "_quote", quotes.get(f));
            addedFields++;
        }
        if (addedFields == 0) {
            System.out.println("APPENDED=0 SKIPPED_DUP=" + dupFields + " (nothing new)");
            return;
        }
        arr.put(entry);
        Files.writeString(store, root.toString(2) + "\n", StandardCharsets.UTF_8);
        System.out.println("APPENDED=1 (" + entry.getString("id") + ", "
            + addedFields + " fields) SKIPPED_DUP=" + dupFields);
    }

    static void selfTest() {
        int pass = 0, fail = 0;

        // Positive: growth rate
        pass += check("growth explicit", AssumptionPatterns.GROWTH,
            "I'm assuming 15% revenue growth over the next five years", true) ? 1 : 0;
        // Positive: WACC
        pass += check("wacc explicit", AssumptionPatterns.DISCOUNT_WACC,
            "using a 10% discount rate for this DCF", true) ? 1 : 0;
        // Positive: terminal growth
        pass += check("terminal explicit", AssumptionPatterns.TERMINAL_GROWTH,
            "with 3% terminal growth into perpetuity", true) ? 1 : 0;
        // Positive: FCF projection
        pass += check("fcf explicit", AssumptionPatterns.FCF_PROJECTION,
            "free cash flow of $5 billion by 2030", true) ? 1 : 0;
        // Positive: margin
        pass += check("margin explicit", AssumptionPatterns.MARGIN,
            "operating margins expanding to 40%", true) ? 1 : 0;

        // Negative: Parkev's loose language must NOT match
        pass += check("no false growth", AssumptionPatterns.GROWTH,
            "I revised my cash flow estimations for Oracle", false) ? 1 : 0;
        pass += check("no false wacc", AssumptionPatterns.DISCOUNT_WACC,
            "the current market price is 138", false) ? 1 : 0;
        pass += check("no false terminal", AssumptionPatterns.TERMINAL_GROWTH,
            "roughly 14% upside from here over the next 12 to 18 months", false) ? 1 : 0;
        pass += check("no false fcf", AssumptionPatterns.FCF_PROJECTION,
            "tens of billions of dollars of debt on the balance sheet", false) ? 1 : 0;
        pass += check("no false margin", AssumptionPatterns.MARGIN,
            "I calculate roughly 14% upside from here", false) ? 1 : 0;

        // Negative: Jeremy's narrative language must NOT match
        pass += check("jeremy no growth", AssumptionPatterns.GROWTH,
            "Celsius has the most upside potential of any stock I cover", false) ? 1 : 0;

        // --append writer tests (pure functions, no file IO)
        pass += t("append nextId empty", nextIdNum(new JSONArray()) == 1) ? 1 : 0;
        JSONArray a1 = new JSONArray();
        JSONObject e1 = new JSONObject();
        e1.put("id", "ca-001");
        e1.put("ticker", "ORCL");
        e1.put("creator", "parkev");
        e1.put("growth_rate", 15);
        e1.put("growth_rate_quote", "fifteen percent growth");
        a1.put(e1);
        pass += t("append nextId after ca-001", nextIdNum(a1) == 2) ? 1 : 0;
        pass += t("append dup detected", isDup(a1, "orcl", "PARKEV", "growth_rate", 15.0)) ? 1 : 0;
        pass += t("append dup different value ok", !isDup(a1, "ORCL", "parkev", "growth_rate", 16.0)) ? 1 : 0;
        pass += t("append dup different field ok", !isDup(a1, "ORCL", "parkev", "discount_wacc", 15.0)) ? 1 : 0;
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("growth_rate", 15.0);
        Map<String, String> q = new LinkedHashMap<>();
        pass += t("append quote required", validateAppend(v, q) != null) ? 1 : 0;
        q.put("growth_rate", "exact words");
        pass += t("append quote present ok", validateAppend(v, q) == null) ? 1 : 0;
        pass += t("append empty refused", validateAppend(new LinkedHashMap<>(), q) != null) ? 1 : 0;

        int total = 19;
        int failed = total - pass;
        System.out.println(pass + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static boolean t(String name, boolean cond) {
        System.out.println((cond ? "PASS" : "FAIL") + " " + name);
        return cond;
    }

    static boolean check(String name, String pat, String text, boolean expect) {
        boolean found = Pattern.compile(pat).matcher(text).find();
        boolean ok = (found == expect);
        System.out.println((ok ? "PASS" : "FAIL") + " " + name +
            (ok ? "" : " (expected " + expect + ", got " + found + ")"));
        return ok;
    }
    // ---- AssumptionPatterns (merged 2026-09-28; was a standalone file) ----
    // Regexes for harvesting DCF assumption candidates from creator transcripts.
    // Kept nested so this tool stays single-file (weekly regression sweep
    // launches one .java per suite). See the patterns' own comments below.
    static class AssumptionPatterns {


    // Growth rate: "assuming 15% growth", "growing at 12%", "15% revenue growth",
    // "growth rate of 10%", "CAGR of 18%"
    public static final String GROWTH =
        "(?i)(?:assuming|assume|using|use|with|at|of)\\s+(\\d{1,2}(?:\\.\\d+)?)\\s*%\\s*(?:annual\\s+)?(?:revenue\\s+|fcf\\s+|free\\s+cash\\s+flow\\s+|earnings\\s+|growth)"
        + "|(?i)(?:revenue|fcf|free\\s+cash\\s+flow|earnings)\\s+growth\\s+(?:of|at|around|about)\\s+(\\d{1,2}(?:\\.\\d+)?)\\s*%"
        + "|(?i)growth\\s+rate\\s+(?:of|at|around|about)\\s+(\\d{1,2}(?:\\.\\d+)?)\\s*%"
        + "|(?i)CAGR\\s+(?:of|at|around|about)\\s+(\\d{1,2}(?:\\.\\d+)?)\\s*%";

    // Discount rate / WACC: "10% discount rate", "using a 9% WACC",
    // "discounting at 11%", "cost of capital of 8.5%"
    public static final String DISCOUNT_WACC =
        "(?i)(\\d{1,2}(?:\\.\\d+)?)\\s*%\\s*(?:discount\\s+rate|WACC)"
        + "|(?i)(?:discount\\s+rate|WACC)\\s+(?:of|at|around|about|is)\\s+(\\d{1,2}(?:\\.\\d+)?)\\s*%"
        + "|(?i)discount(?:ing)?\\s+(?:at|using|with)\\s+(\\d{1,2}(?:\\.\\d+)?)\\s*%"
        + "|(?i)cost\\s+of\\s+capital\\s+(?:of|at|around|about|is)\\s+(\\d{1,2}(?:\\.\\d+)?)\\s*%";

    // Terminal growth: "3% terminal growth", "2.5% perpetual growth",
    // "terminal value.*3%", "growing at 2% forever"
    public static final String TERMINAL_GROWTH =
        "(?i)(\\d(?:\\.\\d+)?)\\s*%\\s*(?:terminal|perpetual)\\s+growth"
        + "|(?i)(?:terminal|perpetual)\\s+growth\\s+(?:of|at|around|about|is)\\s+(\\d(?:\\.\\d+)?)\\s*%"
        + "|(?i)growing\\s+at\\s+(\\d(?:\\.\\d+)?)\\s*%\\s+(?:forever|in\\s+perpetuity|into\\s+perpetuity)";

    // FCF projection: "free cash flow of $5 billion by 2030",
    // "$12 billion in FCF", "FCF reaching $8B"
    public static final String FCF_PROJECTION =
        "(?i)free\\s+cash\\s+flow\\s+(?:of|at|around|about|reaching|will\\s+be|to)\\s+\\$\\s?([\\d,]+(?:\\.\\d+)?)\\s*(billion|million)"
        + "|(?i)\\$\\s?([\\d,]+(?:\\.\\d+)?)\\s*(billion|million)\\s+(?:in\\s+)?(?:free\\s+cash\\s+flow|FCF)"
        + "|(?i)FCF\\s+(?:of|at|around|about|reaching|will\\s+be|to)\\s+\\$\\s?([\\d,]+(?:\\.\\d+)?)\\s*(billion|million)";

    // Margin assumption: "25% operating margins", "operating margin of 30%",
    // "margins expanding to 40%"
    public static final String MARGIN =
        "(?i)(\\d{1,2}(?:\\.\\d+)?)\\s*%\\s*(?:operating\\s+|net\\s+|gross\\s+|EBITDA\\s+|free\\s+cash\\s+flow\\s+)?margins?"
        + "|(?i)(?:operating\\s+|net\\s+|gross\\s+|EBITDA\\s+)?margins?\\s+(?:of|at|around|about|expanding\\s+to|reaching)\\s+(\\d{1,2}(?:\\.\\d+)?)\\s*%";

    /** Field names in harvest order. */
    public static final String[] FIELDS = {
        "growth_rate", "discount_wacc", "terminal_growth", "fcf_projection", "margin"
    };

    /** Returns the regex for a field name, or null if unknown. */
    public static String patternFor(String field) {
        switch (field) {
            case "growth_rate": return GROWTH;
            case "discount_wacc": return DISCOUNT_WACC;
            case "terminal_growth": return TERMINAL_GROWTH;
            case "fcf_projection": return FCF_PROJECTION;
            case "margin": return MARGIN;
            default: return null;
        }
    }
    }
}
