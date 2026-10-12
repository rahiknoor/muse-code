import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filing-vs-model base cross-check gate (idea #32).
 *
 * <p>Java port of {@code dcf_csv/verify_base.py}. Run BEFORE writing any new DCF
 * base to workbook_data.json / the comparison CSV / our_dcf.json. Verifies the
 * proposed base against the filing's own figures so trough-base and
 * transcription errors can never ship silently.
 *
 * <p>Usage:
 * <pre>
 *   java VerifyBase.java TICKER --base 38219 --kind FCFF \
 *       --ocf 139514 --capex 131819 [--reported-fcf 11194] \
 *       [--note "Base FCF NORMALIZED ..."]
 *   java VerifyBase.java GRAB --base 1200 --kind REV --revenue 1150
 *   java VerifyBase.java JPM --base 6.00 --kind DDM --dividend 6.00
 * </pre>
 *
 * <p>Kinds:
 * <ul>
 *   <li>FCFF / FCFE: base is a free-cash-flow figure. Give --ocf and --capex from
 *       the filing; also give --reported-fcf when the filing states its own FCF
 *       (the company's number is authoritative).</li>
 *   <li>REV: base is revenue. Give --revenue from the filing.</li>
 *   <li>DDM: base is an annualized dividend. Give --dividend as declared.</li>
 * </ul>
 *
 * <p>Tolerance: |base - reference| &le; max(10% * |reference|, $200M).
 *
 * <p>Exit 0 = PASS (prints HIGH or FLAGGED). Exit 1 = HOLD: a
 * "BASE CROSS-CHECK HELD" section is appended to assumption_changelog.md
 * automatically; nothing was written. Exit 2 = argument error.
 *
 * <p>Idea #44: every PASS also appends one line to
 * {@code dcf_csv/verifybase_gate.log} — the persistent pass record the
 * #44 health-check verifiers (FilingWatchVerify, DcfAuditVerify) use to
 * confirm Rahik's cross-check rule: any DCF base written has a VerifyBase
 * pass record or a logged HOLD. HOLDs were already logged to the changelog.
 */
public class VerifyBase {

    /**
     * Home directory. IMPORTANT: this runtime's {@code user.home} system property
     * returns {@code /root} (the passwd entry) while {@code $HOME} is
     * {@code /home/hatch}. Python's {@code os.path.expanduser("~/...")} honors
     * {@code $HOME}, so Java must too -- never use
     * {@code System.getProperty("user.home")} directly for workspace paths.
     */
    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /** Workspace root. Overridable with the DCF_WORKSPACE_ROOT env var for testing. */
    static String workspaceRoot() {
        String e = System.getenv("DCF_WORKSPACE_ROOT");
        return (e != null && !e.isEmpty()) ? e : homeDir() + "/workspace";
    }

    static {
        // Python writes UTF-8 to stdout regardless of locale (PEP 686); this JVM
        // may run under a POSIX/ASCII locale, so set UTF-8 explicitly to match.
        try {
            System.setOut(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // fall back to the default stdout
        }
    }

    static final double ABS_TOL_M = 200.0; // $200M absolute floor (small-company headroom)
    static final double REL_TOL = 0.10;    // 10% relative

    static double tol(double ref) {
        return Math.max(REL_TOL * Math.abs(ref), ABS_TOL_M);
    }

    /**
     * Pull $-magnitudes out of prose, normalized to $M.
     * Handles $31.977B -&gt; 31977, $14.0B -&gt; 14000, $481.3M -&gt; 481.3,
     * -$3,742.5M -&gt; 3742.5, plain 11194 -&gt; 11194.
     */
    static List<Double> extractNumbersM(String text) {
        List<Double> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\$?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([BM])\\b")
                .matcher(text);
        while (m.find()) {
            double v;
            try {
                v = Double.parseDouble(m.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            if (m.group(2).equals("B")) {
                v *= 1000.0;
            }
            out.add(v);
        }
        return out;
    }

    /** Result of {@link #noteQuotesRaw}: whether the note quotes a raw figure, and which. */
    static class QuoteCheck {
        final boolean ok;
        final Double matched;
        QuoteCheck(boolean ok, Double matched) { this.ok = ok; this.matched = matched; }
    }

    /** True if the note contains any raw filing figure (within 1%). */
    static QuoteCheck noteQuotesRaw(String note, List<Double> raws) {
        List<Double> nums = extractNumbersM(note);
        for (Double r : raws) {
            if (r == null) {
                continue;
            }
            for (double n : nums) {
                if (Math.abs(n - r) <= 0.01 * Math.max(Math.abs(r), 1.0)) {
                    return new QuoteCheck(true, r);
                }
            }
        }
        return new QuoteCheck(false, null);
    }

    /** Python-style "${v:,.2f}M" list rendering used in HOLD details. */
    static String rawFigures(List<Double> raws) {
        List<String> parts = new ArrayList<>();
        for (Double r : raws) {
            if (r != null) {
                parts.add(Py.strRepr("$" + Py.comma(r, 2) + "M"));
            }
        }
        return "[" + String.join(", ", parts) + "]";
    }

    static int hold(String root, String ticker, String reason, String detail) throws Exception {
        String today = LocalDate.now().toString();
        String section = "\n## " + today + " \u2014 BASE CROSS-CHECK HELD: " + ticker + "\n"
                + "verify_base.py refused a proposed DCF base (idea #32). Nothing was written.\n"
                + "- Reason: " + reason + "\n"
                + "- Detail: " + detail + "\n";
        String changelog = root + "/valuation_scoreboard/assumption_changelog.md";
        Files.writeString(Paths.get(changelog), section, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("HOLD: " + reason);
        System.out.println("Detail: " + detail);
        System.out.println("Logged to assumption_changelog.md. Do not ship this base until resolved.");
        return 1;
    }

    /**
     * Idea #44: persistent pass record. Every PASS (HIGH or FLAGGED) appends
     * one line to {@code dcf_csv/verifybase_gate.log}:
     * {@code 2026-09-27T15:12:33-04:00|AVGO|PASS HIGH|base=38219.00|kind=FCFF}
     *
     * <p>The #44 verifiers match these lines by date + ticker to confirm the
     * cross-check gate ran before a base was written. Logging never changes
     * the gate verdict: if the append fails, a loud warning is printed but
     * the pass still stands — the verifier will then flag the missing record,
     * which is the correct fail-loud behavior (something is wrong with the
     * log path and the run should not be treated as clean).
     */
    static void logPass(String root, String ticker, String verdict, double base, String kind) {
        // Timestamps in Rahik's timezone, matching the changelog's ET dates.
        String ts = ZonedDateTime.now(ZoneId.of("America/New_York"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String line = ts + "|" + ticker + "|PASS " + verdict
                + "|base=" + Py.fixed(base, 2) + "|kind=" + kind + "\n";
        try {
            Files.writeString(Paths.get(root + "/dcf_csv/verifybase_gate.log"), line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("WARNING: VerifyBase passed but could not write "
                    + "verifybase_gate.log: " + e);
        }
    }

    /** Parsed arguments (mirrors the argparse surface of verify_base.py). */
    static class Args {
        String ticker;
        Double base;
        String kind;
        Double ocf, capex, reportedFcf, revenue, dividend;
        String note;
    }

    static Double parseDoubleOpt(String flag, String val) {
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            System.err.println("VerifyBase.java: error: argument " + flag
                    + ": invalid float value: '" + val + "'");
            System.exit(2);
            return null; // unreachable
        }
    }

    static Args parseArgs(String[] argv) {
        Args a = new Args();
        List<String> positionals = new ArrayList<>();
        for (int i = 0; i < argv.length; i++) {
            String t = argv[i];
            switch (t) {
                case "--base": a.base = parseDoubleOpt(t, next(argv, ++i, t)); break;
                case "--kind": a.kind = next(argv, ++i, t); break;
                case "--ocf": a.ocf = parseDoubleOpt(t, next(argv, ++i, t)); break;
                case "--capex": a.capex = parseDoubleOpt(t, next(argv, ++i, t)); break;
                case "--reported-fcf":
                    a.reportedFcf = parseDoubleOpt(t, next(argv, ++i, t)); break;
                case "--revenue": a.revenue = parseDoubleOpt(t, next(argv, ++i, t)); break;
                case "--dividend": a.dividend = parseDoubleOpt(t, next(argv, ++i, t)); break;
                case "--note": a.note = next(argv, ++i, t); break;
                default:
                    if (t.startsWith("--")) {
                        System.err.println("VerifyBase.java: error: unrecognized arguments: " + t);
                        System.exit(2);
                    }
                    positionals.add(t);
            }
        }
        if (positionals.size() != 1) {
            System.err.println("VerifyBase.java: error: expected exactly one TICKER argument");
            System.exit(2);
        }
        a.ticker = positionals.get(0);
        if (a.base == null) {
            System.err.println("VerifyBase.java: error: the following arguments are required: --base");
            System.exit(2);
        }
        if (a.kind == null) {
            System.err.println("VerifyBase.java: error: the following arguments are required: --kind");
            System.exit(2);
        }
        if (!a.kind.equals("FCFF") && !a.kind.equals("FCFE")
                && !a.kind.equals("REV") && !a.kind.equals("DDM")) {
            System.err.println("VerifyBase.java: error: argument --kind: invalid choice: '"
                    + a.kind + "' (choose from 'FCFF', 'FCFE', 'REV', 'DDM')");
            System.exit(2);
        }
        return a;
    }

    static String next(String[] argv, int i, String flag) {
        if (i >= argv.length) {
            System.err.println("VerifyBase.java: error: argument " + flag + ": expected one argument");
            System.exit(2);
        }
        return argv[i];
    }

    /** Python str() of a JSON scalar, as used in the "Context:" line. */
    static String scalarStr(Json.Val v) {
        if (v == null || v instanceof Json.Null) {
            return "None";
        }
        if (v instanceof Json.Bool) {
            return ((Json.Bool) v).v ? "True" : "False";
        }
        if (v instanceof Json.Str) {
            return ((Json.Str) v).v;
        }
        if (v instanceof Json.Num) {
            Json.Num n = (Json.Num) v;
            return n.isInt ? Long.toString(n.l) : Py.repr(n.d);
        }
        return v.toString();
    }

    public static void main(String[] argv) throws Exception {
        System.exit(run(argv));
    }

    static int run(String[] argv) throws Exception {
        Args a = parseArgs(argv);
        String root = workspaceRoot();
        String tkr = a.ticker.toUpperCase();

        // Context: prior filing figures on record (INFO only).
        Json.Obj stored = null;
        try {
            String text = Files.readString(
                    Paths.get(root + "/sec_filings/dcf_inputs_all.json"), StandardCharsets.UTF_8);
            Json.Obj inputs = (Json.Obj) Json.parse(text);
            stored = inputs.getObj("companies").getObj(tkr);
        } catch (Exception e) {
            stored = null;
        }
        if (stored != null) {
            Json.Obj ann = stored.getObj("annual");
            Map<String, String> annView = new LinkedHashMap<>();
            annView.put("form", "?");
            annView.put("filing_date", "?");
            String form = "?", filingDate = "?";
            String ocfS = "None", capexS = "None";
            if (ann != null) {
                if (ann.has("form")) {
                    form = scalarStr(ann.get("form"));
                }
                if (ann.has("filing_date")) {
                    filingDate = scalarStr(ann.get("filing_date"));
                }
                // Python: ann.get('ocf') -> None when missing (prints "None").
                ocfS = scalarStr(ann.get("ocf"));
                capexS = scalarStr(ann.get("capex"));
            }
            System.out.println("Context: dcf_inputs_all.json has " + tkr
                    + " (" + form + " " + filingDate + "): OCF=" + ocfS + ", capex=" + capexS);
        } else {
            System.out.println("Context: no stored filing figures for " + tkr
                    + " in dcf_inputs_all.json; verifying against the supplied filing figures only.");
        }

        double ref;
        String refname;
        List<Double> raws = new ArrayList<>();
        if (a.kind.equals("FCFF") || a.kind.equals("FCFE")) {
            if (a.ocf == null || a.capex == null) {
                System.out.println("HOLD: FCFF/FCFE check needs --ocf and --capex from the filing.");
                return hold(root, tkr, "missing filing figures",
                        "FCFF/FCFE base requires --ocf and --capex.");
            }
            double computed = a.ocf - a.capex;
            raws.add(a.ocf);
            raws.add(a.capex);
            raws.add(computed);
            if (a.reportedFcf != null) {
                ref = a.reportedFcf;
                raws.add(a.reportedFcf);
                if (Math.abs(computed - a.reportedFcf) > tol(a.reportedFcf)) {
                    System.out.println("Note: filing's reported FCF ($"
                            + Py.comma(a.reportedFcf, 0) + "M) differs "
                            + "from OCF-less-capex ($" + Py.comma(computed, 0)
                            + "M); reported figure is authoritative for the check.");
                }
            } else {
                ref = computed;
            }
            refname = a.reportedFcf != null ? "filing's reported FCF" : "filing OCF-less-capex";
        } else if (a.kind.equals("REV")) {
            if (a.revenue == null) {
                return hold(root, tkr, "missing filing figures", "REV base requires --revenue.");
            }
            ref = a.revenue;
            raws.add(a.revenue);
            refname = "filing revenue";
        } else { // DDM
            if (a.dividend == null) {
                return hold(root, tkr, "missing filing figures", "DDM base requires --dividend.");
            }
            ref = a.dividend;
            raws.add(a.dividend);
            refname = "declared annualized dividend";
        }

        double dev = Math.abs(a.base - ref);
        double devPct = ref != 0 ? dev / Math.abs(ref) * 100 : Double.POSITIVE_INFINITY;
        String devPctS = Double.isInfinite(devPct) ? "inf" : Py.fixed(devPct, 1);

        if (a.note != null) {
            QuoteCheck q = noteQuotesRaw(a.note, raws);
            if (q.ok) {
                System.out.println("PASS (FLAGGED): base $" + Py.comma(a.base, 2)
                        + "M deviates " + devPctS + "% from " + refname
                        + " ($" + Py.comma(ref, 2) + "M); normalization documented "
                        + "(note quotes raw figure $" + Py.comma(q.matched, 2) + "M).");
                System.out.println(
                        "Write the base with the note as its src; confidence will read FLAGGED.");
                logPass(root, tkr, "FLAGGED", a.base, a.kind); // idea #44 pass record
                return 0;
            }
            return hold(root, tkr, "normalization note does not quote the raw filing figures",
                    "note=" + Py.strRepr(a.note)
                            + "; raw figures=" + rawFigures(raws));
        }

        if (dev <= tol(ref)) {
            System.out.println("PASS (HIGH): base $" + Py.comma(a.base, 2)
                    + "M within tolerance of " + refname
                    + " ($" + Py.comma(ref, 2) + "M; dev " + devPctS
                    + "% <= max(10%, $200M)).");
            logPass(root, tkr, "HIGH", a.base, a.kind); // idea #44 pass record
            return 0;
        }
        return hold(root, tkr,
                "base $" + Py.comma(a.base, 2) + "M deviates " + devPctS
                        + "% from " + refname + " ($" + Py.comma(ref, 2)
                        + "M) with no normalization documented",
                "tolerance=max(10%*|ref|, $200M)=$" + Py.comma(tol(ref), 0)
                        + "M; raws=" + rawFigures(raws) + "; "
                        + "either fix the base or re-run with --note quoting the raw figures.");
    }
    // ------------------------------------------------------------------
    // Py: Python-compatible float formatting helpers.
    // ------------------------------------------------------------------

    /** Python repr()/str() semantics for doubles, plus printf-style fixed formats. */
    static class Py {
        /**
         * Python repr(float): shortest round-trip digits (same digits Java's
         * Double.toString produces), but Python's placement rules: fixed notation
         * unless the decimal exponent is &le; -4 or &gt; 16, always with a '.'.
         */
        static String repr(double v) {
            if (Double.isNaN(v)) {
                return "nan";
            }
            if (v == Double.POSITIVE_INFINITY) {
                return "inf";
            }
            if (v == Double.NEGATIVE_INFINITY) {
                return "-inf";
            }
            if (v == 0.0) {
                // Preserve the sign of -0.0: Python repr(-0.0) == '-0.0'.
                return Double.doubleToRawLongBits(v) < 0 ? "-0.0" : "0.0";
            }
            String s = Double.toString(v);
            boolean neg = s.startsWith("-");
            String t = neg ? s.substring(1) : s;
            String mant;
            int exp;
            int ePos = t.indexOf('E');
            if (ePos >= 0) {
                mant = t.substring(0, ePos);
                exp = Integer.parseInt(t.substring(ePos + 1));
            } else {
                mant = t;
                exp = 0;
            }
            int dot = mant.indexOf('.');
            String digits = mant.substring(0, dot) + mant.substring(dot + 1);
            // Value = 0.digits * 10^decpt.
            int decpt = dot + exp;
            int k = digits.length();
            String body;
            if (decpt <= -4 || decpt > 16) {
                int e10 = decpt - 1;
                String es = (e10 < 0 ? "-" : "+")
                        + String.format("%02d", Math.abs(e10));
                body = digits.charAt(0) + (k > 1 ? "." + digits.substring(1) : "")
                        + "e" + es;
            } else if (decpt <= 0) {
                body = "0." + "0".repeat(-decpt) + digits;
            } else if (decpt >= k) {
                body = digits + "0".repeat(decpt - k) + ".0";
            } else {
                body = digits.substring(0, decpt) + "." + digits.substring(decpt);
            }
            return (neg ? "-" : "") + body;
        }

        /**
         * Python's {@code f"{v:.{n}f}"}: round-half-even on the exact binary value,
         * always {@code n} decimals. Python keeps the sign of -0.0 ("-0.00").
         */
        static String fixed(double v, int n) {
            if (Double.isNaN(v)) {
                return "nan";
            }
            if (Double.isInfinite(v)) {
                return v > 0 ? "inf" : "-inf";
            }
            BigDecimal bd = new BigDecimal(v); // exact decimal expansion of the double
            bd = bd.setScale(n, RoundingMode.HALF_EVEN);
            String s = bd.toPlainString();
            if (bd.signum() == 0 && Double.doubleToRawLongBits(v) < 0) {
                s = "-" + s; // Python formats -0.0 as "-0.00"
            }
            return s;
        }

        /**
         * Python's {@code f"{v:,.{n}f}"}: like {@link #fixed} with thousands separators.
         */
        static String comma(double v, int n) {
            String s = fixed(v, n);
            // fixed() already handled the -0.0 sign; regroup the integer part.
            boolean neg = s.startsWith("-");
            String t = neg ? s.substring(1) : s;
            int dot = t.indexOf('.');
            String intPart = dot >= 0 ? t.substring(0, dot) : t;
            String fracPart = dot >= 0 ? t.substring(dot) : "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < intPart.length(); i++) {
                if (i > 0 && (intPart.length() - i) % 3 == 0) {
                    sb.append(',');
                }
                sb.append(intPart.charAt(i));
            }
            return (neg ? "-" : "") + sb + fracPart;
        }

        /** Python's round(x, ndigits): correctly-rounded half-even, back to double. */
        static double round(double v, int ndigits) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return v;
            }
            return new BigDecimal(v).setScale(ndigits, RoundingMode.HALF_EVEN).doubleValue();
        }

        /**
         * Python repr() of a string: single quotes, backslash escapes, printable
         * non-ASCII kept as-is (used for the {@code note=...} detail in verify_base).
         */
        static String strRepr(String s) {
            StringBuilder sb = new StringBuilder("'");
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                switch (cp) {
                    case '\\': sb.append("\\\\"); break;
                    case '\'': sb.append("\\'"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (cp < 0x20 || cp == 0x7F) {
                            sb.append(String.format("\\x%02x", cp));
                        } else {
                            sb.appendCodePoint(cp);
                        }
                }
                i += Character.charCount(cp);
            }
            return sb.append("'").toString();
        }
    }

    // ------------------------------------------------------------------
    // Json: minimal JSON parser (insertion-ordered) + Python-compatible writer.
    // ------------------------------------------------------------------

    /** Minimal JSON value model + parser. Only what these scripts need. */
    static class Json {
        /** Base value type. */
        abstract static class Val { }

        /** JSON object: insertion order preserved (like Python's dict). */
        static class Obj extends Val {
            final LinkedHashMap<String, Val> map = new LinkedHashMap<>();

            boolean has(String k) { return map.containsKey(k); }
            boolean isNull(String k) { return !map.containsKey(k) || map.get(k) == null; }
            Val get(String k) { return map.get(k); }
            String getStr(String k) { return (String) ((Str) map.get(k)).v; }
            Obj getObj(String k) {
                Val v = map.get(k);
                return v instanceof Obj ? (Obj) v : null;
            }
            @SuppressWarnings("unchecked")
            List<Val> getList(String k) { return (List<Val>) ((Arr) map.get(k)).items; }
            double getNum(String k) { return numOf(map.get(k)); }
            /** Null when missing or JSON null (like Python's .get returning None). */
            Double getOptNum(String k) {
                Val v = map.get(k);
                return v == null ? null : numOf(v);
            }
            String optStr(String k, String dflt) {
                Val v = map.get(k);
                if (v == null || v instanceof Null) {
                    return dflt;
                }
                return ((Str) v).v;
            }
            void put(String k, Val v) { map.put(k, v); }

            static double numOf(Val v) {
                if (v instanceof Num) {
                    return ((Num) v).d;
                }
                throw new IllegalArgumentException("not a number: " + v);
            }
        }

        static class Arr extends Val {
            final List<Val> items = new ArrayList<>();
        }

        static class Str extends Val {
            final String v;
            Str(String v) { this.v = v; }
        }

        static class Num extends Val {
            final double d;      // numeric value
            final boolean isInt; // true when the literal had no ./e/E (Python int)
            final long l;        // valid when isInt
            Num(double d) { this.d = d; this.isInt = false; this.l = 0; }
            Num(long l) { this.d = l; this.isInt = true; this.l = l; }
        }

        static class Bool extends Val {
            final boolean v;
            Bool(boolean v) { this.v = v; }
        }

        static class Null extends Val { }

        /** Parse a JSON document. */
        static Val parse(String text) {
            Parser p = new Parser(text);
            Val v = p.value();
            p.ws();
            if (p.pos != text.length()) {
                throw new IllegalArgumentException("trailing data at " + p.pos);
            }
            return v;
        }

        /** Write a value exactly like Python's json.dumps(v, indent=1, ensure_ascii=True). */
        static String dumps(Object v) {
            StringBuilder sb = new StringBuilder();
            dump(v, sb, 0);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        static void dump(Object v, StringBuilder sb, int level) {
            if (v == null || v instanceof Null) {
                sb.append("null");
            } else if (v instanceof Str) {
                quote(((Str) v).v, sb);
            } else if (v instanceof String) {
                quote((String) v, sb);
            } else if (v instanceof Num) {
                Num n = (Num) v;
                sb.append(n.isInt ? Long.toString(n.l) : jsonFloat(n.d));
            } else if (v instanceof Double) {
                sb.append(jsonFloat((Double) v));
            } else if (v instanceof Long) {
                sb.append(v);
            } else if (v instanceof Integer) {
                sb.append(v);
            } else if (v instanceof Boolean) {
                sb.append((Boolean) v ? "true" : "false");
            } else if (v instanceof Bool) {
                sb.append(((Bool) v).v ? "true" : "false");
            } else if (v instanceof Obj) {
                Obj o = (Obj) v;
                if (o.map.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                String pad = " ".repeat(level + 1);
                boolean first = true;
                for (Map.Entry<String, Val> e : o.map.entrySet()) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    first = false;
                    sb.append(pad);
                    quote(e.getKey(), sb);
                    sb.append(": ");
                    dump(e.getValue(), sb, level + 1);
                }
                sb.append("\n").append(" ".repeat(level)).append("}");
            } else if (v instanceof Arr) {
                dumpList(((Arr) v).items, sb, level);
            } else if (v instanceof List) {
                dumpList((List<Object>) v, sb, level);
            } else if (v instanceof Map) {
                // LinkedHashMap<String, Object> built by callers (insertion order).
                Map<String, Object> m = (Map<String, Object>) v;
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                String pad = " ".repeat(level + 1);
                boolean first = true;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    first = false;
                    sb.append(pad);
                    quote(e.getKey(), sb);
                    sb.append(": ");
                    dump(e.getValue(), sb, level + 1);
                }
                sb.append("\n").append(" ".repeat(level)).append("}");
            } else {
                throw new IllegalArgumentException("cannot dump " + v.getClass());
            }
        }

        static void dumpList(List<?> items, StringBuilder sb, int level) {
            if (items.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            String pad = " ".repeat(level + 1);
            boolean first = true;
            for (Object it : items) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append(pad);
                dump(it, sb, level + 1);
            }
            sb.append("\n").append(" ".repeat(level)).append("]");
        }

        /** Python's json float encoding: repr(), except inf/nan spellings. */
        static String jsonFloat(double d) {
            if (Double.isNaN(d)) {
                return "NaN";
            }
            if (d == Double.POSITIVE_INFINITY) {
                return "Infinity";
            }
            if (d == Double.NEGATIVE_INFINITY) {
                return "-Infinity";
            }
            return Py.repr(d);
        }

        /** ensure_ascii string quoting (non-ASCII -> \\uXXXX, lowercase hex). */
        static void quote(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                switch (cp) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (cp < 0x20 || cp > 0x7E) {
                            if (cp > 0xFFFF) {
                                // Surrogate pair, each \\uXXXX (like Python's ensure_ascii).
                                char hi = Character.highSurrogate(cp);
                                char lo = Character.lowSurrogate(cp);
                                sb.append(String.format("\\u%04x\\u%04x", (int) hi, (int) lo));
                            } else {
                                sb.append(String.format("\\u%04x", cp));
                            }
                        } else {
                            sb.appendCodePoint(cp);
                        }
                }
                i += Character.charCount(cp);
            }
            sb.append('"');
        }

        static class Parser {
            final String t;
            int pos;
            Parser(String t) { this.t = t; }

            void ws() {
                while (pos < t.length()) {
                    char c = t.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            Val value() {
                ws();
                if (pos >= t.length()) {
                    throw new IllegalArgumentException("unexpected end of input");
                }
                char c = t.charAt(pos);
                switch (c) {
                    case '{': return obj();
                    case '[': return arr();
                    case '"': return new Str(str());
                    case 't': expect("true"); return new Bool(true);
                    case 'f': expect("false"); return new Bool(false);
                    case 'n': expect("null"); return new Null();
                    default: return num();
                }
            }

            Obj obj() {
                Obj o = new Obj();
                pos++; // {
                ws();
                if (pos < t.length() && t.charAt(pos) == '}') {
                    pos++;
                    return o;
                }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (t.charAt(pos) != ':') {
                        throw new IllegalArgumentException("expected : at " + pos);
                    }
                    pos++;
                    o.put(k, value());
                    ws();
                    char c = t.charAt(pos);
                    if (c == ',') {
                        pos++;
                    } else if (c == '}') {
                        pos++;
                        return o;
                    } else {
                        throw new IllegalArgumentException("expected , or } at " + pos);
                    }
                }
            }

            Arr arr() {
                Arr a = new Arr();
                pos++; // [
                ws();
                if (pos < t.length() && t.charAt(pos) == ']') {
                    pos++;
                    return a;
                }
                while (true) {
                    a.items.add(value());
                    ws();
                    char c = t.charAt(pos);
                    if (c == ',') {
                        pos++;
                    } else if (c == ']') {
                        pos++;
                        return a;
                    } else {
                        throw new IllegalArgumentException("expected , or ] at " + pos);
                    }
                }
            }

            String str() {
                pos++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = t.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        char e = t.charAt(pos++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u': {
                                int cp = Integer.parseInt(t.substring(pos, pos + 4), 16);
                                pos += 4;
                                if (Character.isHighSurrogate((char) cp)
                                        && pos + 1 < t.length()
                                        && t.charAt(pos) == '\\' && t.charAt(pos + 1) == 'u') {
                                    int lo = Integer.parseInt(t.substring(pos + 2, pos + 6), 16);
                                    pos += 6;
                                    sb.append(Character.toChars(
                                            Character.toCodePoint((char) cp, (char) lo)));
                                } else {
                                    sb.append((char) cp);
                                }
                                break;
                            }
                            default: throw new IllegalArgumentException("bad escape \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }

            Val num() {
                int start = pos;
                while (pos < t.length() && "-+0123456789.eE".indexOf(t.charAt(pos)) >= 0) {
                    pos++;
                }
                String lit = t.substring(start, pos);
                boolean isFloat = lit.indexOf('.') >= 0 || lit.indexOf('e') >= 0
                        || lit.indexOf('E') >= 0;
                if (!isFloat) {
                    try {
                        return new Num(Long.parseLong(lit));
                    } catch (NumberFormatException e) {
                        return new Num(Double.parseDouble(lit)); // huge int -> double
                    }
                }
                return new Num(Double.parseDouble(lit));
            }

            void expect(String word) {
                if (!t.startsWith(word, pos)) {
                    throw new IllegalArgumentException("expected " + word + " at " + pos);
                }
                pos += word.length();
            }
        }
    }
}
