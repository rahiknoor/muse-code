// AssumptionCrossCheck.java — four-way DCF assumption cross-check.
//
// Compares, per company:
//   (1) MY assumptions  — workbook_data.json -> assumptions {g, w, t, base, ...}
//   (2) CREATOR assumptions — creator_tracker/creator_assumptions.json (harvested, quoted)
//   (3) NOTEBOOKLM assumptions — NOT recorded (honest gap; shown as "not recorded")
//   (4) MANAGEMENT GUIDANCE — creator_tracker/earnings_guidance.json: growth/margin
//       outlook stated by management on earnings calls (harvested, quoted)
//
// Source trust hierarchy (Rahik's standing rule, 2026-09-28):
//   Parkev > Jeremy > Couch Investor are his most reliable data source.
//   When a trusted creator states an assumption, the data sways toward them.
//   On disagreement: MY inputs are reviewed FIRST — the burden of proof sits
//   with my model. NotebookLM's filing-grounded figures are the tiebreaker.
//   MANAGEMENT GUIDANCE is an independent voice for calibrating g — with an
//   optimism-bias caveat: management systematically guides optimistically, so
//   it informs the review but never auto-blesses an input change.
//   This is NOT blind copying: every number traces to a source; flagged
//   disagreements go through the proposal/audit guard, never silent rewrites.
//
// Flag rule: only flag when BOTH sides have a real, comparable number and they
// differ a lot. Missing = missing. Half-stated assumption sets never flag.
//
// Thresholds (relative difference):
//   growth_rate, discount_wacc, terminal_growth, margin: flag if |diff| > 25%
//   fcf_projection: flag if |diff| > 30%
//
// Usage:
//   java AssumptionCrossCheck.java [--ticker NVDA] [--self-test]
// Output: side-by-side table + flags. Exit 0 = ran clean (flags are findings,
// not errors); exit 1 = self-test failure or unreadable backend.
import java.nio.file.*;
import java.util.*;

public class AssumptionCrossCheck {

    // field -> {myKey, unit, flagThreshold}
    static final String[][] FIELDS = {
        {"growth_rate",    "g", "percent", "0.25"},
        {"discount_wacc",  "w", "percent", "0.25"},
        {"terminal_growth","t", "percent", "0.25"},
        {"fcf_projection", "base", "usd_m", "0.30"},
        // margin has no counterpart in my assumptions -> always "not comparable"
        {"margin",         null, "percent", "0.25"},
    };

    public static void main(String[] args) throws Exception {
        boolean selfTest = false;
        String onlyTicker = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--self-test")) selfTest = true;
            if (args[i].equals("--ticker") && i + 1 < args.length) onlyTicker = args[++i];
        }
        if (selfTest) { runSelfTest(); return; }

        Map<String, Map<String, Object>> mine = loadMine();
        Map<String, List<Map<String, Object>>> creator = loadCreator();
        Map<String, List<Map<String, Object>>> guidance = loadGuidance();

        Set<String> tickers = new TreeSet<>();
        tickers.addAll(mine.keySet());
        tickers.addAll(creator.keySet());
        tickers.addAll(guidance.keySet());
        if (onlyTicker != null) tickers.retainAll(Set.of(onlyTicker));

        int flags = 0, compared = 0, creatorStated = 0, guidanceStated = 0;
        for (String t : tickers) {
            Map<String, Object> m = mine.get(t);
            // One combined voice list: creator entries keep their "creator(name)"
            // label; guidance entries get a "guidance(call-date)" label.
            List<Map<String, Object>> voices = new ArrayList<>();
            voices.addAll(creator.getOrDefault(t, List.of()));
            voices.addAll(guidance.getOrDefault(t, List.of()));
            if (voices.isEmpty()) continue;
            for (Map<String, Object> c : voices) {
                boolean isGuidance = "guidance".equals(c.get("voice"));
                if (isGuidance) guidanceStated++; else creatorStated++;
                String label = isGuidance
                    ? "guidance(" + c.get("call_date") + ")"
                    : "creator(" + c.get("creator") + ")";
                for (String[] f : FIELDS) {
                    String field = f[0], myKey = f[1], unit = f[2];
                    double thresh = Double.parseDouble(f[3]);
                    Object cv = c.get(field);
                    if (cv == null) continue; // missing = missing
                    compared++;
                    String nlm = "not recorded";
                    if (myKey == null || m == null || m.get(myKey) == null) {
                        System.out.println(t + " | " + field + " | " + label + "=" +
                            fmtVal(cv, unit) + " | mine=— | notebooklm=" + nlm +
                            " | no comparable my-input; recorded only");
                        continue;
                    }
                    double myV = toDouble(m.get(myKey), unit);
                    double crV = toDouble(cv, unit);
                    double rel = Math.abs(crV - myV) / Math.max(Math.abs(myV), 1e-9);
                    String verdict;
                    if (rel > thresh) {
                        flags++;
                        verdict = "FLAG: differs " + pct(rel) + " — REVIEW MY INPUTS FIRST " +
                            "(burden of proof on my model per trust hierarchy); " +
                            "NotebookLM filing-grounded figures are the tiebreaker. " +
                            "File a proposal for review — never silent.";
                    } else {
                        verdict = "agree (within " + pct(rel) + ")";
                    }
                    System.out.println(t + " | " + field +
                        " | " + label + "=" + fmtVal(cv, unit) +
                        " | mine=" + fmtVal(m.get(myKey), unit) +
                        " | notebooklm=" + nlm + " | " + verdict);
                    System.out.println("    quote: \"" + c.get(field + "_quote") + "\"");
                }
            }
        }
        System.out.println("---");
        System.out.println("creator assumptions stated: " + creatorStated +
            ", guidance statements: " + guidanceStated +
            ", fields compared: " + compared + ", flags: " + flags);
        System.out.println("NotebookLM assumptions: not recorded (honest gap — values only, no stored assumptions).");
    }

    // ---- loaders (minimal JSON; files are small and flat) ----
    static Map<String, Map<String, Object>> loadMine() throws Exception {
        String p = System.getenv("HOME") + "/workspace/your_files/dcf-valuations/.src/workbook_data.json";
        String json = Files.readString(Path.of(p));
        Map<String, Map<String, Object>> out = new HashMap<>();
        // Pair each ticker with ITS OWN assumptions block (not by index — the file
        // has more ticker refs than assumption blocks).
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
            "\"ticker\"\\s*:\\s*\"([A-Z]+)\"[^\\{]*\"assumptions\"\\s*:\\s*\\{([^}]*)\\}").matcher(json);
        while (cm.find()) {
            String ticker = cm.group(1);
            // guard: the assumptions block must belong to this ticker's company object,
            // i.e. no other "ticker" key between them
            String between = json.substring(cm.start(1), cm.start(2));
            if (between.contains("\"ticker\"")) continue;
            Map<String, Object> a = new HashMap<>();
            java.util.regex.Matcher kv = java.util.regex.Pattern.compile(
                "\"(g|w|t|base|shares|netdebt|ocf|capex)\"\\s*:\\s*(-?[\\d.]+)").matcher(cm.group(2));
            while (kv.find()) a.put(kv.group(1), Double.parseDouble(kv.group(2)));
            out.put(ticker, a);
        }
        return out;
    }

    static Map<String, List<Map<String, Object>>> loadCreator() throws Exception {
        String p = System.getenv("HOME") + "/workspace/creator_tracker/creator_assumptions.json";
        String json = Files.readString(Path.of(p));
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        // entries: {"ticker":"X","creator":"...","growth_rate":0.15,"growth_rate_quote":"...",...}
        java.util.regex.Matcher em = java.util.regex.Pattern.compile(
            "\\{[^{}]*\"ticker\"\\s*:\\s*\"([A-Z]+)\"[^{}]*\\}").matcher(json);
        while (em.find()) {
            String e = em.group(0), t = em.group(1);
            Map<String, Object> m = new HashMap<>();
            m.put("ticker", t);
            java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
                "\"creator\"\\s*:\\s*\"([^\"]+)\"").matcher(e);
            if (cm.find()) m.put("creator", cm.group(1));
            for (String[] f : FIELDS) {
                String field = f[0];
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                    "\"" + field + "\"\\s*:\\s*(-?[\\d.]+)").matcher(e);
                if (vm.find()) m.put(field, Double.parseDouble(vm.group(1)));
                java.util.regex.Matcher qm = java.util.regex.Pattern.compile(
                    "\"" + field + "_quote\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(e);
                if (qm.find()) m.put(field + "_quote", qm.group(1).replace("\\\"", "\""));
            }
            out.computeIfAbsent(t, k -> new ArrayList<>()).add(m);
        }
        return out;
    }

    // ---- voice (4): management guidance ---------------------------------
    // earnings_guidance.json: "TICKER": [ {field, value, unit, quote, call_date,
    // quarter, year, source}, ... ]. Latest entry per ticker+field wins.
    // Missing store (not harvested yet) -> empty, never an error.
    static Map<String, List<Map<String, Object>>> loadGuidance() throws Exception {
        String p = System.getenv("HOME") + "/workspace/creator_tracker/earnings_guidance.json";
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        if (!Files.exists(Path.of(p))) return out;
        String json = Files.readString(Path.of(p));
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile(
            "\"([A-Z][A-Z.]{0,6})\"\\s*:\\s*\\[").matcher(json);
        while (tm.find()) {
            String t = tm.group(1);
            int start = tm.end() - 1, depth = 0, end = -1;
            for (int i = start; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '[') depth++;
                else if (ch == ']') { depth--; if (depth == 0) { end = i; break; } }
            }
            if (end < 0) continue;
            String arr = json.substring(start, end + 1);
            Map<String, Map<String, Object>> latest = new HashMap<>();
            java.util.regex.Matcher em = java.util.regex.Pattern.compile("\\{[^{}]*\\}").matcher(arr);
            while (em.find()) {
                Map<String, Object> m = parseGuidanceEntry(em.group(0), t);
                if (m == null) continue;
                String field = (String) m.get("field");
                Map<String, Object> prev = latest.get(field);
                if (prev == null || after(m, prev)) latest.put(field, m);
            }
            if (!latest.isEmpty()) out.put(t, new ArrayList<>(latest.values()));
        }
        return out;
    }

    static Map<String, Object> parseGuidanceEntry(String en, String ticker) {
        java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
            "\"field\"\\s*:\\s*\"([^\"]+)\"").matcher(en);
        if (!fm.find()) return null;
        String field = fm.group(1);
        Map<String, Object> m = new HashMap<>();
        m.put("voice", "guidance");
        m.put("ticker", ticker);
        m.put("field", field);
        java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
            "\"value\"\\s*:\\s*(null|-?[\\d.]+)").matcher(en);
        if (vm.find() && !"null".equals(vm.group(1))) m.put(field, Double.parseDouble(vm.group(1)));
        java.util.regex.Matcher qm = java.util.regex.Pattern.compile(
            "\"quote\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(en);
        if (qm.find()) m.put(field + "_quote", qm.group(1).replace("\\\"", "\""));
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile(
            "\"call_date\"\\s*:\\s*\"([^\"]+)\"").matcher(en);
        if (dm.find()) m.put("call_date", dm.group(1));
        java.util.regex.Matcher ym = java.util.regex.Pattern.compile(
            "\"year\"\\s*:\\s*(\\d+)").matcher(en);
        java.util.regex.Matcher qtm = java.util.regex.Pattern.compile(
            "\"quarter\"\\s*:\\s*(\\d+)").matcher(en);
        if (ym.find()) m.put("year", Integer.parseInt(ym.group(1)));
        if (qtm.find()) m.put("quarter", Integer.parseInt(qtm.group(1)));
        return m;
    }

    static boolean after(Map<String, Object> a, Map<String, Object> b) {
        int ya = (Integer) a.getOrDefault("year", 0), yb = (Integer) b.getOrDefault("year", 0);
        int qa = (Integer) a.getOrDefault("quarter", 0), qb = (Integer) b.getOrDefault("quarter", 0);
        return ya > yb || (ya == yb && qa > qb);
    }

    static double toDouble(Object v, String unit) {
        double d = ((Number) v).doubleValue();
        return unit.equals("percent") && d > 1.0 ? d / 100.0 : d; // accept "15" or "0.15"
    }

    static String fmtVal(Object v, String unit) {
        double d = ((Number) v).doubleValue();
        if (unit.equals("percent")) return String.format("%.2f%%", (d > 1.0 ? d : d * 100));
        if (unit.equals("usd_m")) return String.format("$%,.0fM", d);
        return String.valueOf(d);
    }

    static String pct(double r) { return String.format("%.0f%%", r * 100); }

    static void runSelfTest() {
        int pass = 0, total = 0;
        // threshold logic
        total++; if (shouldFlag(0.10, 0.15, 0.25)) pass++; else System.out.println("FAIL flag-25pct");
        total++; if (!shouldFlag(0.10, 0.11, 0.25)) pass++; else System.out.println("FAIL noflag-10pct");
        total++; if (shouldFlag(0.03, 0.05, 0.25)) pass++; else System.out.println("FAIL flag-terminal");
        // unit normalization: "15" == 0.15
        total++; if (Math.abs(toDouble(15.0, "percent") - 0.15) < 1e-9) pass++; else System.out.println("FAIL pct-norm");
        total++; if (Math.abs(toDouble(0.15, "percent") - 0.15) < 1e-9) pass++; else System.out.println("FAIL pct-dec");
        // missing never flags
        total++; if (!shouldFlag(null, 0.15, 0.25)) pass++; else System.out.println("FAIL missing-flag");
        System.out.println(pass + " passed, " + (total - pass) + " failed");
        if (pass != total) System.exit(1);
    }

    static boolean shouldFlag(Object myV, Object crV, double thresh) {
        if (myV == null || crV == null) return false; // missing = missing, never flags
        double a = ((Number) myV).doubleValue(), b = ((Number) crV).doubleValue();
        if (a > 1.0 && b <= 1.0) a /= 100.0;
        if (b > 1.0 && a <= 1.0) b /= 100.0;
        return Math.abs(b - a) / Math.max(Math.abs(a), 1e-9) > thresh;
    }
}
