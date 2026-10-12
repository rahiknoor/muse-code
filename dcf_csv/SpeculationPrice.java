// SpeculationPrice.java — builds ~/workspace/dcf_csv/speculation_price.json.
//
// Rahik's design (2026-09-29, architecture rule same day): four results per
// company — the untouched Muse DCF and NotebookLM grounded DCF, plus one
// speculation price per voice. MY code computes ONLY the Muse speculation:
//
//   muse_speculation = ours + PULL_WEIGHT * (guarded_median - ours)
//                      + |ours| * my_nudge_rate
//
// PULL_WEIGHT = 0.5 (named constant; equal-weight blend, the standard
// forecast-combination prior — Claude audit-01, 2026-10-05, question 1 PASS).
//
// |ours|: the nudge always shifts in the outlook's direction. With a plain
// ours * nudge, a High (+5%) outlook on a negative DCF would push the value
// DOWN (more negative) — the opposite of the intent (Claude 2026-10-04).
//
// my_nudge_rate comes from growth_outlook.json (MY deep research):
// High +0.05, Low -0.05, Moderate 0.0, anything else 0.0.
// (Claude audit-01, 2026-10-05, question 2 FAIL: Moderate was +0.025 with no
// negative counterpart — an upward bias. Moderate is now neutral.
// Growth outlook never feeds the DCF itself (DcfModel.java has zero
// references to growth_outlook.json) — no double-counting.
//
// Input guards (Claude audit-01, 2026-10-05, question 3 FAIL): a negative
// speculation price is a bug, and with only 2 voices the median is just
// their midpoint — it absorbs nothing. So: non-positive voices are dropped
// (IREN's -121, GRAB's -1.89), a non-positive our-DCF yields null (IREN),
// and after dropping there must be at least 3 voices, or exactly 2 within
// ~3x of each other — otherwise null (INTC: 4.91 vs 108.49, 22x apart).
// Whole-dollar rounding (question 4 FAIL: cent precision on a 22x voice
// spread is noise). One rounding, at the very end — the 2026-10-05 audit's
// cent drift (ORCL 128.93 vs 128.94) came from rounded brief inputs, not
// double rounding in code; still, a single end-round is now explicit.
//
// NotebookLM's speculation price is DERIVED BY NOTEBOOKLM ITSELF — never
// computed, never approximated by this code. The nlm_speculation slot is
// reserved here and stays null until a recorded NotebookLM response is
// transcribed, verified, and blessed via proposal -> audit -> blessed (the
// same provenance rule as the verified value batch). The grounded and
// forward values recorded in "inputs" are facts from the backends, kept so
// the re-ask has its inputs — they are NOT used to derive anything here.
//
// outside_median = median of the available per-ticker values from
// creator_values.json (latest), analyst_targets.json (target), and
// website_consensus.json (consensus); missing sources excluded, null when a
// ticker has none. Built by OutsideVoice.java; read from outside_voice.json.
//
// muse_speculation is null when ours is null OR the outside median is null.
// Never invented.
//
// Speculation columns are display-only: they never enter the "Average of
// available numeric sources", get no gap-watch wiring, and are not blessed
// values. (Buy-below DISPLAY per source is a separate table treatment —
// Rahik 2026-09-29 — and does not make speculation a blessed value.)
//
// Usage:
//   java SpeculationPrice.java --build      # writes speculation_price.json
//   java SpeculationPrice.java --self-test  # unit + backend checks
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

public class SpeculationPrice {
    static final String HOME = homeDir();
    static final String D = HOME + "/workspace/dcf_csv/";
    static final String OUT = D + "speculation_price.json";

    /** HOME with fallback (mirrors DcfModel.homeDir); never "null/...". */
    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        if (args.length > 0 && args[0].equals("--build")) {
            build();
            return;
        }
        System.out.println("usage: java SpeculationPrice.java --build | --self-test");
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser (objects, arrays, strings, numbers, literals).
    // ------------------------------------------------------------------
    static class J {
        abstract static class V {}
        static class O extends V { LinkedHashMap<String, V> m = new LinkedHashMap<>(); }
        static class A extends V { List<V> l = new ArrayList<>(); }
        static class S extends V { String s; S(String s) { this.s = s; } }
        static class N extends V { double d; N(double d) { this.d = d; } }
        static class B extends V { boolean b; B(boolean b) { this.b = b; } }
        static class Nil extends V {}
        static class P {
            final String s; int i;
            P(String s) { this.s = s; }
            void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
            V val() {
                ws();
                char c = s.charAt(i);
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return str();
                if (c == 't') { i += 4; return new B(true); }
                if (c == 'f') { i += 5; return new B(false); }
                if (c == 'n') { i += 4; return new Nil(); }
                int j = i;
                while (j < s.length() && "-+0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
                double d = Double.parseDouble(s.substring(i, j));
                i = j;
                return new N(d);
            }
            O obj() {
                O o = new O(); i++; ws();
                if (s.charAt(i) == '}') { i++; return o; }
                while (true) {
                    ws(); S k = str(); ws(); i++; V v = val(); o.m.put(k.s, v);
                    ws();
                    char c = s.charAt(i); i++;
                    if (c == '}') break;
                }
                return o;
            }
            A arr() {
                A a = new A(); i++; ws();
                if (s.charAt(i) == ']') { i++; return a; }
                while (true) { a.l.add(val()); ws(); char c = s.charAt(i); i++; if (c == ']') break; }
                return a;
            }
            S str() {
                StringBuilder sb = new StringBuilder(); i++;
                while (true) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                            default: sb.append(e);
                        }
                    } else sb.append(c);
                }
                return new S(sb.toString());
            }
        }
        static V parse(String s) { return new P(s).val(); }
    }

    static String read(String p) throws IOException {
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }

    /** Fail-fast JSON object lookup with a message (instead of ClassCastException). */
    static J.O objAt(J.O parent, String key) {
        J.V v = parent.m.get(key);
        if (!(v instanceof J.O)) {
            throw new IllegalArgumentException("expected JSON object at '" + key + "'");
        }
        return (J.O) v;
    }

    /** Fail-fast cast of a parsed document with a message. */
    static J.O asObj(J.V v, String what) {
        if (!(v instanceof J.O)) {
            throw new IllegalArgumentException("expected JSON object for " + what);
        }
        return (J.O) v;
    }

    static Double num(J.V v) {
        return (v instanceof J.N) ? ((J.N) v).d : null;
    }

    static String str(J.V v) {
        return (v instanceof J.S) ? ((J.S) v).s : null;
    }

    /**
     * Pull weight: how far our DCF moves toward the outside median.
     * 0.5 = equal-weight blend of ours and the median, the standard
     * forecast-combination prior (Claude audit-01, 2026-10-05: PASS).
     */
    static final double PULL_WEIGHT = 0.5;

    /**
     * Round to whole dollars, half-up, on the decimal value.
     * Cent precision is noise when voices span an order of magnitude
     * (Claude audit-01, 2026-10-05, question 4 FAIL). Single rounding,
     * at the very end — never rounded upstream.
     * BigDecimal.valueOf (not new BigDecimal(double)) so a binary tie
     * rounds deterministically instead of depending on float error.
     */
    static double roundWhole(double v) {
        return BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Growth-outlook nudge rate — MY research voice (Rahik 2026-09-29):
     * High +0.05, Low -0.05. Moderate and anything else — including a
     * missing / not-yet-researched outlook — is 0 (neutral, never a null
     * column). Moderate was +0.025 until Claude audit-01 (2026-10-05)
     * called the asymmetry an upward bias; it is now neutral.
     *
     * NOTE (architecture rule): there is deliberately NO nlmNudgeRate here.
     * NotebookLM's speculation is derived by NotebookLM itself; this code
     * must never compute or approximate anything labeled NotebookLM's.
     */
    static double nudgeRate(String outlook) {
        if ("High".equals(outlook)) return 0.05;
        if ("Low".equals(outlook)) return -0.05;
        return 0.0;
    }

    /**
     * Input guards (Claude audit-01, 2026-10-05, question 3 FAIL).
     * Non-positive voices are dropped; then there must be at least 3
     * surviving voices, or exactly 2 within ~3x of each other — otherwise
     * the "median" is meaningless (two 22x-apart voices have no midpoint
     * worth publishing) and this returns null.
     */
    static Double guardedMedian(List<Double> voices) {
        List<Double> v = new ArrayList<>();
        for (Double x : voices) if (x != null && x > 0) v.add(x);
        if (v.size() < 2) return null;
        if (v.size() == 2) {
            double hi = Math.max(v.get(0), v.get(1));
            double lo = Math.min(v.get(0), v.get(1));
            if (hi > 3.0 * lo) return null;
        }
        Collections.sort(v);
        int n = v.size();
        return (n % 2 == 1) ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    /**
     * The speculation formula. Null when the voice's DCF is missing or
     * non-positive, or the guarded median is missing — never invented.
     */
    static Double speculate(Double voice, List<Double> voices, double nudge) {
        if (voice == null || voice <= 0) return null;
        Double median = guardedMedian(voices);
        if (median == null) return null;
        // |voice|: a High (+5%) nudge must shift optimistic (up) even when the
        // voice's DCF is negative — voice * nudge would push it the wrong way.
        return roundWhole(voice + PULL_WEIGHT * (median - voice) + Math.abs(voice) * nudge);
    }

    /** Escape a string for JSON output; other control chars become unicode escapes. */
    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static String jstr(String s) {
        return s == null ? "null" : "\"" + esc(s) + "\"";
    }

    static String jnum(Double d) {
        if (d == null || !Double.isFinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d.doubleValue());
        return String.valueOf(d);
    }

    static void build() throws IOException {
        J.O blessed = asObj(J.parse(read(D + "blessed.json")), "blessed.json");
        J.O cols = objAt(blessed, "columns");
        J.O oursTickers = objAt(objAt(cols, "ours"), "tickers");
        // NotebookLM's speculation slot: filled ONLY from a blessed
        // "nlm_speculation" column (recorded NotebookLM response ->
        // verify -> bless, same provenance as the verified value batch).
        // No such column exists yet -> every slot is null. This code never
        // computes the value itself.
        J.O blessedNlmSpec = new J.O();
        J.V nlmSpecCol = cols.m.get("nlm_speculation");
        if (nlmSpecCol instanceof J.O) {
            J.V tv = ((J.O) nlmSpecCol).m.get("tickers");
            if (tv instanceof J.O) blessedNlmSpec = (J.O) tv;
        }
        // (removed: unused nlmTickers lookup — the grounded/forward voices come
        // from notebooklm_values.json / notebooklm_forward.json above)

                J.O ovAll = asObj(J.parse(read(D + "outside_voice.json")), "outside_voice.json");
        J.O ovTickers = objAt(ovAll, "tickers");

        J.O goAll = asObj(J.parse(read(D + "growth_outlook.json")), "growth_outlook.json");
        J.O goTickers = objAt(goAll, "tickers");

        // NotebookLM's own research voice: grounded + forward tilt.
        J.O nlmGroundedAll = asObj(J.parse(read(D + "notebooklm_values.json")), "notebooklm_values.json");
        J.O nlmGroundedTickers = objAt(nlmGroundedAll, "values");
        J.O nlmFwdAll = asObj(J.parse(read(D + "notebooklm_forward.json")), "notebooklm_forward.json");
        J.O nlmFwdTickers = objAt(nlmFwdAll, "values");

        J.O creatorAll = asObj(J.parse(read(D + "creator_values.json")), "creator_values.json");
        J.O creatorLatest = objAt(creatorAll, "latest");
        J.O analystAll = asObj(J.parse(read(D + "analyst_targets.json")), "analyst_targets.json");
        J.O webAll = asObj(J.parse(read(D + "website_consensus.json")), "website_consensus.json");
        J.O webTickers = objAt(webAll, "tickers");

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(" \"_derived\": \"derived from outside_voice.json, growth_outlook.json, notebooklm_values.json via SpeculationPrice --build \\u2014 do not edit, do not read as input\",\n");
        sb.append(" \"asof\": \"").append(LocalDate.now()).append("\",\n");
        sb.append(" \"asof_note\": \"build date, not data date\",\n");
        sb.append(" \"definition\": \"Per-voice speculation prices (Rahik 2026-09-29). ")
          .append("Muse's speculation pulls our DCF halfway (PULL_WEIGHT=0.5) toward the guarded ")
          .append("outside median (non-positive voices dropped; needs >=3 voices, or exactly 2 ")
          .append("within ~3x of each other — otherwise null) and nudges it by MY deep-research ")
          .append("growth-outlook rate (High +0.05, Low -0.05, Moderate and not-researched 0.0). ")
          .append("Rounded to whole dollars, half-up, once at the end. ")
          .append("NotebookLM's speculation is DERIVED BY NOTEBOOKLM ITSELF — this file reserves the slot ")
          .append("as null until a recorded NotebookLM response is verified and blessed ")
          .append("(same provenance rule as the verified value batch); it is never computed here. ")
          .append("Display-only: never averaged, no gap-watch, not blessed.\",\n");
        sb.append(" \"formula\": \"muse_speculation = ours + 0.5*(guarded_median - ours) + |ours|*my_nudge_rate; ")
          .append("null when ours <= 0 or the guarded median is missing; guarded_median = median of ")
          .append("positive voices only, requiring >=3 voices or exactly 2 within ~3x; ")
          .append("rounded once, at the end, to whole dollars (half-up, BigDecimal). ")
          .append("nlm_speculation: reserved slot, always null until NotebookLM derives it.\",\n");
        sb.append(" \"nudge_rates\": {\"my_nudge_rate\": {\"source\": \"growth_outlook.json (Muse deep research)\", ")
          .append("\"High\": 0.05, \"Low\": -0.05, \"Moderate\": 0.0, \"not_yet_researched\": 0.0}},\n");
        sb.append(" \"nlm_speculation_provenance\": \"RESERVED — may only be filled from a recorded NotebookLM ")
          .append("response transcribed, verified, and blessed via proposal -> audit -> blessed ")
          .append("(same provenance rule as the verified value batch). Never computed by this code.\",\n");
        sb.append(" \"bounds\": \"Muse's speculation lies halfway between our DCF and the guarded outside median, ")
          .append("then shifted by at most 5% of |our DCF| (the largest |my_nudge_rate|, High) ")
          .append("in the outlook's direction. The shift is measured from the halfway point, ")
          .append("not bounded by the median. Rounded to whole dollars. Null for non-positive ")
          .append("DCFs, dropped non-positive voices, or too few / too scattered voices.\",\n");
        sb.append(" \"tickers\": {\n");

        List<String> tickers = new ArrayList<>(oursTickers.m.keySet());
        for (int ti = 0; ti < tickers.size(); ti++) {
            String t = tickers.get(ti);
            J.V ourRec = oursTickers.m.get(t);
            Double ours = (ourRec instanceof J.O) ? num(((J.O) ourRec).m.get("value")) : null;
            // NotebookLM's grounded voice comes from its own file
            // (notebooklm_values.json) — the same value blessed.json carries
            // (cross-checked in --self-test).
            J.V nlmGRec = nlmGroundedTickers.m.get(t);
            Double nlm = (nlmGRec instanceof J.O)
                    ? num(((J.O) nlmGRec).m.get("value")) : null;
            J.V nlmFRec = nlmFwdTickers.m.get(t);
            Double nlmFwd = (nlmFRec instanceof J.O)
                    ? num(((J.O) nlmFRec).m.get("value")) : null;

            J.V ovRec = ovTickers.m.get(t);
            List<String> sources = new ArrayList<>();
            if (ovRec instanceof J.O) {
                J.V sv = ((J.O) ovRec).m.get("sources");
                if (sv instanceof J.A) {
                    for (J.V s : ((J.A) sv).l) sources.add(str(s));
                }
            }

            // Per-voice nudge: MY research voice only. There is deliberately NO
            // NotebookLM nudge computation here — NotebookLM derives its own
            // speculation itself (architecture rule).
            String outlook = null;
            J.V goRec = goTickers.m.get(t);
            if (goRec instanceof J.O) outlook = str(((J.O) goRec).m.get("outlook"));
            double myNudge = nudgeRate(outlook);

            Double creatorVal = null, analystVal = null, webVal = null;
            J.V cr = creatorLatest.m.get(t);
            if (cr instanceof J.O) creatorVal = num(((J.O) cr).m.get("value"));
            J.V ar = analystAll.m.get(t);
            if (ar instanceof J.O) analystVal = num(((J.O) ar).m.get("target"));
            J.V wr = webTickers.m.get(t);
            if (wr instanceof J.O) webVal = num(((J.O) wr).m.get("consensus"));

            // Guards live here, not in OutsideVoice (its median is also shown
            // elsewhere): the guarded median is the one the formula uses.
            List<Double> voices = Arrays.asList(creatorVal, analystVal, webVal);
            Double guarded = guardedMedian(voices);
            Double museSpec = speculate(ours, voices, myNudge);
            // nlm_speculation: filled ONLY from the blessed "nlm_speculation"
            // column (NotebookLM's own derivation, recorded -> verified ->
            // blessed). Absent until then -> null. Never computed here.
            J.V bnsRec = blessedNlmSpec.m.get(t);
            Double nlmSpec = (bnsRec instanceof J.O)
                    ? num(((J.O) bnsRec).m.get("value")) : null;

            sb.append("  ").append(jstr(t)).append(": {");
            sb.append("\"muse_speculation\": ").append(jnum(museSpec)).append(", ");
            sb.append("\"nlm_speculation\": ").append(jnum(nlmSpec)).append(", ");
            // outside_median here is the GUARDED median (the one the formula
            // used) — may differ from outside_voice.json's raw median.
            sb.append("\"outside_median\": ").append(jnum(guarded)).append(", ");
            sb.append("\"inputs\": {");
            sb.append("\"ours\": ").append(jnum(ours)).append(", ");
            sb.append("\"nlm_grounded\": ").append(jnum(nlm)).append(", ");
            sb.append("\"nlm_forward\": ").append(jnum(nlmFwd)).append(", ");
            sb.append("\"my_nudge_rate\": ").append(myNudge).append(", ");
            sb.append("\"my_nudge_source\": \"growth_outlook.json\", ");
            sb.append("\"growth_outlook\": ").append(jstr(outlook)).append(", ");
            sb.append("\"sources\": [");
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(jstr(sources.get(i)));
            }
            sb.append("], ");
            sb.append("\"creator_value\": ").append(jnum(creatorVal)).append(", ");
            sb.append("\"analyst_target\": ").append(jnum(analystVal)).append(", ");
            sb.append("\"website_consensus\": ").append(jnum(webVal));
            sb.append("}}");
            sb.append(ti + 1 < tickers.size() ? ",\n" : "\n");
        }
        sb.append(" }\n}\n");
        // Atomic write: a crash can't leave a truncated JSON behind.
        Path tmp = Files.createTempFile(Path.of(D), "speculation_price", ".tmp");
        try {
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, Path.of(OUT), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, Path.of(OUT), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.out.println("wrote " + OUT + " (" + tickers.size() + " tickers)");
    }

    static void selfTest() throws IOException {
        // Formula unit tests (whole-dollar rounding, guards).
        SelfTest.checkEq("PULL_WEIGHT", 0.5, PULL_WEIGHT);
        SelfTest.checkEq("spec halfway, no nudge", 150.0,
                speculate(100.0, Arrays.asList(200.0, 190.0, 210.0), 0.0));
        SelfTest.checkEq("spec halfway, high nudge", 155.0,
                speculate(100.0, Arrays.asList(200.0, 190.0, 210.0), 0.05));
        SelfTest.checkEq("spec halfway, low nudge", 145.0,
                speculate(100.0, Arrays.asList(200.0, 190.0, 210.0), -0.05));
        SelfTest.checkEq("spec null voice", null,
                speculate(null, Arrays.asList(200.0, 190.0, 210.0), 0.0));
        SelfTest.checkEq("spec non-positive voice", null,
                speculate(-14.38, Arrays.asList(81.47, 75.0), 0.0));
        SelfTest.checkEq("spec rounds to whole dollars", 168.0,
                speculate(88.95, Arrays.asList(247.56, 240.0, 250.0), 0.0));
        SelfTest.checkEq("spec rounds half-up", 151.0,
                speculate(100.0, Arrays.asList(201.0, 201.0, 201.0), 0.0));
        // Guards.
        SelfTest.checkEq("guard drops non-positive", 6.145,
                guardedMedian(Arrays.asList(6.18, 6.11, -1.89)));
        SelfTest.checkEq("guard two agreeing voices", 6.145,
                guardedMedian(Arrays.asList(6.18, 6.11)));
        SelfTest.checkEq("guard two scattered voices", null,
                guardedMedian(Arrays.asList(108.49, 4.91)));
        SelfTest.checkEq("guard single voice", null,
                guardedMedian(Arrays.asList(200.0)));
        SelfTest.checkEq("guard empty", null,
                guardedMedian(new ArrayList<>()));
        SelfTest.checkEq("guard skips nulls", 200.0,
                guardedMedian(Arrays.asList(null, 200.0, 190.0, 210.0)));
        SelfTest.checkEq("INTC case -> null", null,
                speculate(8.18, Arrays.asList(null, 108.49, 4.91), 0.025));
        SelfTest.checkEq("IREN case -> null", null,
                speculate(-14.38, Arrays.asList(null, 81.47, -121.0), 0.025));
        // Sign check (Claude 2026-10-04): with a positive voice, a High nudge
        // still shifts optimistic (up).
        SelfTest.checkEq("spec high nudge on positive voice", 155.0,
                speculate(100.0, Arrays.asList(200.0, 190.0, 210.0), 0.05));
        // Nudge mapping (MY research voice only — no NotebookLM nudge is ever
        // computed here; architecture rule).
        SelfTest.checkEq("nudge High", 0.05, nudgeRate("High"));
        SelfTest.checkEq("nudge Moderate neutral", 0.0, nudgeRate("Moderate"));
        SelfTest.checkEq("nudge Low", -0.05, nudgeRate("Low"));
        SelfTest.checkEq("nudge null outlook", 0.0, nudgeRate(null));
        SelfTest.checkEq("nudge unknown", 0.0, nudgeRate("Bananas"));

        // Backend checks.
        SelfTest.check("backend file exists", Files.exists(Path.of(OUT)));
        J.O doc = (J.O) J.parse(read(OUT));
        SelfTest.checkContains("definition recorded", str(doc.m.get("definition")), "halfway");
        SelfTest.checkContains("formula recorded", str(doc.m.get("formula")), "muse_speculation");
        SelfTest.checkContains("bounds recorded", str(doc.m.get("bounds")), "5%");
        J.O tickers = (J.O) doc.m.get("tickers");
        SelfTest.checkEq("32 tickers", 32, tickers.m.size());

        // Formula consistency (replaces the hard-coded 2026-09-30 snapshots, which
        // broke whenever inputs updated): every record's muse_speculation must
        // equal the formula applied to its own recorded inputs, and its
        // outside_median must equal the guarded median of its raw voices.
        // Data-independent.
        int consistent = 0;
        for (J.V v : tickers.m.values()) {
            J.O rec = (J.O) v;
            J.O inputs = (J.O) rec.m.get("inputs");
            Double cOurs = num(inputs.m.get("ours"));
            Double cNudge = num(inputs.m.get("my_nudge_rate"));
            List<Double> voices = Arrays.asList(
                    num(inputs.m.get("creator_value")),
                    num(inputs.m.get("analyst_target")),
                    num(inputs.m.get("website_consensus")));
            Double expectedMedian = guardedMedian(voices);
            Double gotMedian = num(rec.m.get("outside_median"));
            Double expected = (cNudge == null) ? null : speculate(cOurs, voices, cNudge);
            Double got = num(rec.m.get("muse_speculation"));
            if (Objects.equals(expected, got) && Objects.equals(expectedMedian, gotMedian)) consistent++;
            else System.out.println("  inconsistent: ours=" + cOurs + " voices=" + voices
                    + " nudge=" + cNudge + " expected=" + expected + " got=" + got
                    + " expectedMedian=" + expectedMedian + " gotMedian=" + gotMedian);
        }
        SelfTest.checkEq("muse_spec consistent for all 32", 32, consistent);
        // Architecture rule: nlm_speculation is null for EVERY ticker —
        // this code must never put a number in NotebookLM's slot.
        int nlmNull = 0;
        for (J.V v : tickers.m.values()) {
            if (num(((J.O) v).m.get("nlm_speculation")) == null) nlmNull++;
        }
        SelfTest.checkEq("nlm_speculation null for all 32", 32, nlmNull);
        // Provenance marker for the reserved slot.
        SelfTest.checkContains("nlm provenance recorded",
                str(doc.m.get("nlm_speculation_provenance")), "Never computed by this code");
        // Every record carries traceable inputs, including MY nudge rate and
        // its source (no NotebookLM nudge is computed here).
        int withInputs = 0;
        for (J.V v : tickers.m.values()) {
            J.O rec = (J.O) v;
            J.V inputs = rec.m.get("inputs");
            if (inputs instanceof J.O
                    && ((J.O) inputs).m.containsKey("ours")
                    && ((J.O) inputs).m.containsKey("nlm_grounded")
                    && ((J.O) inputs).m.containsKey("nlm_forward")
                    && ((J.O) inputs).m.containsKey("my_nudge_rate")
                    && ((J.O) inputs).m.containsKey("my_nudge_source")
                    && ((J.O) inputs).m.containsKey("sources")
                    && !((J.O) inputs).m.containsKey("nlm_nudge_rate")) {
                withInputs++;
            }
        }
        SelfTest.checkEq("all 32 records carry inputs", 32, withInputs);
        // notebooklm_values.json grounded must agree with blessed.json
        // notebooklm (the displayed column) — the speculation base must never
        // silently differ from what's shown.
        J.O blessed = (J.O) J.parse(read(D + "blessed.json"));
        J.O blessedNlm = (J.O) ((J.O) ((J.O) blessed.m.get("columns")).m.get("notebooklm")).m.get("tickers");
        J.O nlmVals = (J.O) ((J.O) J.parse(read(D + "notebooklm_values.json"))).m.get("values");
        int agree = 0, compared = 0;
        for (String t : tickers.m.keySet()) {
            J.V bv = blessedNlm.m.get(t);
            Double bvd = (bv instanceof J.O) ? num(((J.O) bv).m.get("value")) : null;
            J.O rec = (J.O) tickers.m.get(t);
            Double ivd = num(((J.O) rec.m.get("inputs")).m.get("nlm_grounded"));
            compared++;
            if (Objects.equals(bvd, ivd)) agree++;
            else System.out.println("  grounded mismatch: " + t + " blessed=" + bvd + " values=" + ivd);
        }
        SelfTest.checkEq("grounded agrees with blessed", compared, agree);
        SelfTest.finish();
    }
}
