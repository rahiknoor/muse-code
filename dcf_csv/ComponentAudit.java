import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * ComponentAudit — forward-tilt component audit (Rahik-approved idea 2/3, 2026-09-29).
 *
 * <p>Extends the nightly NotebookLM DCF re-ask F-AUDIT: the independent auditor
 * now pulls the DCF components from the '[TICKER] DCF forward tilt' note
 * (discount rate, terminal growth, base-year FCF, equity value, diluted share
 * count) and this tool checks internal consistency + executor/auditor agreement.
 * A plausible-looking answer built on wrong inputs must FAIL.
 *
 * <p>ALL rates are decimals (0.105 = 10.5%). A missing/null component on either
 * side fails closed.
 *
 * <p>Usage:
 * <pre>
 *   java --class-path ~/workspace/java-shared/classes ComponentAudit.java --check --audit-file &lt;json&gt;
 *   java --class-path ~/workspace/java-shared/classes ComponentAudit.java --self-test
 * </pre>
 *
 * <p>Audit JSON shape:
 * <pre>
 *   {"ticker":"AMD",
 *    "executor":{"value":85.06,"wacc":0.105,"t":0.03,"fcf_base":1234.0,"equity":5678.0,"shares":66.7},
 *    "auditor": { ... same shape ... }}
 * </pre>
 *
 * <p>Checks (all must hold):
 * <ol>
 *   <li>|executor.value - auditor.value| &lt;= 0.02</li>
 *   <li>|executor.wacc - auditor.wacc| &lt;= 0.0001</li>
 *   <li>|auditor.wacc - SectorRecal w for ticker| &lt;= 0.0001</li>
 *   <li>auditor.t &lt; auditor.wacc (strict)</li>
 *   <li>|auditor.equity/auditor.shares - auditor.value| / auditor.value &lt;= 0.01</li>
 *   <li>|executor.fcf_base - auditor.fcf_base| / auditor.fcf_base &lt;= 0.01</li>
 *   <li>|executor.t - auditor.t| &lt;= 0.0001</li>
 * </ol>
 *
 * <p>Check (3) obtains w from the CANONICAL SectorRecal table — it is never
 * duplicated here. SectorRecal.java is executed via its additive
 * {@code --dump-table} mode; this tool compiles its same-dir siblings once
 * into {@code dcf_csv/classes/} (refreshed when SectorRecal.java is newer)
 * because this JDK's single-file launcher compiles only the named file.
 */
public class ComponentAudit {

    /** Component fields required on BOTH the executor and auditor sides. */
    static final List<String> FIELDS =
            List.of("value", "wacc", "t", "fcf_base", "equity", "shares");

    static final class Result {
        final List<String> infos = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
    }

    // ------------------------------------------------------------------ JSON
    /** Minimal JSON reader: objects, arrays, strings, numbers, true/false/null. */
    static final class Jp {
        final String s; int i;
        Jp(String s) { this.s = s; }
        Object parse() {
            skip();
            if (i >= s.length()) throw new IllegalArgumentException("empty JSON");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            return num();
        }
        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        void expect(char c) {
            skip();
            if (i >= s.length() || s.charAt(i) != c)
                throw new IllegalArgumentException("expected '" + c + "' at " + i);
            i++;
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{'); skip();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skip();
                if (i >= s.length() || s.charAt(i) != '"')
                    throw new IllegalArgumentException("expected string key at " + i);
                String k = str();
                expect(':');
                m.put(k, parse());
                skip();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            expect('['); skip();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(parse());
                skip();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }
        String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("bad escape");
                    char e = s.charAt(i++);
                    b.append(switch (e) {
                        case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                        case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
                        case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> { String h = s.substring(i, i + 4); i += 4;
                                      yield (char) Integer.parseInt(h, 16); }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    });
                } else b.append(c);
            }
        }
        Double num() {
            int j = i;
            while (j < s.length() && "-+0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
            if (j == i) throw new IllegalArgumentException("bad value at " + i);
            Double d = Double.parseDouble(s.substring(i, j));
            i = j;
            return d;
        }
    }

    static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        return null; // non-numeric -> treated as missing (fail-closed downstream)
    }

    // ------------------------------------------------------- sector w lookup
    static Path csvDir() {
        return Paths.get(System.getenv("HOME"), "workspace", "dcf_csv");
    }

    static Path sharedClasses() {
        return Paths.get(System.getenv("HOME"), "workspace", "java-shared", "classes");
    }

    /**
     * TICKER -> canonical sector discount rate w, obtained by EXECUTING
     * SectorRecal.java --dump-table (the table is never duplicated here).
     * Compiles SectorRecal + siblings once into dcf_csv/classes/, refreshing
     * when SectorRecal.java is newer (this JDK's single-file launcher compiles
     * only the named file, so the siblings must be on the classpath).
     */
    static Map<String, Double> sectorW() throws Exception {
        Path dir = csvDir();
        Path src = dir.resolve("SectorRecal.java");
        Path classes = dir.resolve("classes");
        Path marker = classes.resolve("SectorRecal.class");
        if (!Files.exists(src)) throw new IllegalStateException("SectorRecal.java not found at " + src);
        boolean stale = !Files.exists(marker)
                || Files.getLastModifiedTime(src).compareTo(Files.getLastModifiedTime(marker)) > 0;
        if (stale) {
            Files.createDirectories(classes);
            String javac = System.getProperty("java.home") + "/bin/javac";
            ProcessBuilder pb = new ProcessBuilder(javac,
                    "-d", classes.toString(),
                    "--class-path", sharedClasses().toString(),
                    "SectorRecal.java", "Proposals.java", "DcfModel.java");
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int rc = p.waitFor();
            if (rc != 0) throw new IllegalStateException("javac failed (rc=" + rc + "): " + out);
        }
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "--class-path",
                sharedClasses() + System.getProperty("path.separator") + classes,
                src.toAbsolutePath().toString(), "--dump-table");
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) throw new IllegalStateException(
                "SectorRecal --dump-table failed (rc=" + rc + "): " + out);
        Map<String, Double> w = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) w.put(parts[0], Double.parseDouble(parts[1]));
        }
        if (w.isEmpty()) throw new IllegalStateException("no rows parsed from --dump-table output");
        return w;
    }

    // ---------------------------------------------------------------- checks
    static void note(Result r, int n, String name, boolean pass, String detail) {
        r.infos.add(String.format("check(%d) %s: %s %s", n, name, pass ? "PASS" : "FAIL", detail));
        if (!pass) r.failures.add(String.format("check(%d) %s FAILED: %s", n, name, detail));
    }

    static Result check(String ticker, Map<String, Double> x, Map<String, Double> a,
                        Map<String, Double> wmap) {
        Result r = new Result();
        r.infos.add("INFO: ticker=" + ticker);
        // Fail-closed: every required component must be present on both sides.
        for (String f : FIELDS) {
            if (x.get(f) == null) r.failures.add("fail-closed: executor." + f + " missing/null");
            if (a.get(f) == null) r.failures.add("fail-closed: auditor." + f + " missing/null");
        }
        boolean haveX = FIELDS.stream().allMatch(f -> x.get(f) != null);
        boolean haveA = FIELDS.stream().allMatch(f -> a.get(f) != null);

        // (1) value agreement
        if (haveX && haveA) {
            double d = Math.abs(x.get("value") - a.get("value"));
            note(r, 1, "executor/auditor value agreement", d <= 0.02,
                    String.format("|%.4f-%.4f|=%.4f <= 0.02", x.get("value"), a.get("value"), d));
        } else r.infos.add("check(1) value agreement: SKIPPED (missing component)");

        // (2) discount-rate agreement executor vs auditor
        if (haveX && haveA) {
            double d = Math.abs(x.get("wacc") - a.get("wacc"));
            note(r, 2, "executor/auditor discount-rate agreement", d <= 0.0001,
                    String.format("|%.6f-%.6f|=%.6f <= 0.0001", x.get("wacc"), a.get("wacc"), d));
        } else r.infos.add("check(2) discount-rate agreement: SKIPPED (missing component)");

        // (3) auditor discount rate vs canonical sector table
        if (haveA) {
            if (wmap == null) {
                r.infos.add("check(3) sector-table wacc: SKIPPED (sector table unavailable — recorded above)");
            } else if (!wmap.containsKey(ticker)) {
                note(r, 3, "auditor wacc vs sector table", false,
                        "ticker '" + ticker + "' not in SectorRecal table (fail-closed)");
            } else {
                double d = Math.abs(a.get("wacc") - wmap.get(ticker));
                note(r, 3, "auditor wacc vs sector table", d <= 0.0001,
                        String.format("|%.6f-%.6f|=%.6f <= 0.0001 (table w for %s)",
                                a.get("wacc"), wmap.get(ticker), d, ticker));
            }
        } else r.infos.add("check(3) sector-table wacc: SKIPPED (missing component)");

        // (4) terminal growth strictly below discount rate
        if (haveA) {
            boolean ok = a.get("t") < a.get("wacc");
            note(r, 4, "terminal growth < discount rate", ok,
                    String.format("t=%.6f %s wacc=%.6f", a.get("t"), ok ? "<" : "!<", a.get("wacc")));
        } else r.infos.add("check(4) t<wacc: SKIPPED (missing component)");

        // (5) internal arithmetic: equity/shares == value within 1%
        if (haveA) {
            if (a.get("value") == 0.0 || a.get("shares") == 0.0) {
                note(r, 5, "equity/shares internal arithmetic", false,
                        "auditor.value or auditor.shares is 0 (division undefined, fail-closed)");
            } else {
                double implied = a.get("equity") / a.get("shares");
                double rel = Math.abs(implied - a.get("value")) / a.get("value");
                note(r, 5, "equity/shares internal arithmetic", rel <= 0.01,
                        String.format("|%.4f/%.4f-%.4f|/%.4f=%.6f <= 0.01",
                                a.get("equity"), a.get("shares"), a.get("value"), a.get("value"), rel));
            }
        } else r.infos.add("check(5) internal arithmetic: SKIPPED (missing component)");

        // (6) base-year FCF agreement within 1% (relative to auditor)
        if (haveX && haveA) {
            if (a.get("fcf_base") == 0.0) {
                note(r, 6, "executor/auditor fcf_base agreement", false,
                        "auditor.fcf_base is 0 (division undefined, fail-closed)");
            } else {
                double rel = Math.abs(x.get("fcf_base") - a.get("fcf_base")) / a.get("fcf_base");
                note(r, 6, "executor/auditor fcf_base agreement", rel <= 0.01,
                        String.format("|%.2f-%.2f|/%.2f=%.6f <= 0.01",
                                x.get("fcf_base"), a.get("fcf_base"), a.get("fcf_base"), rel));
            }
        } else r.infos.add("check(6) fcf_base agreement: SKIPPED (missing component)");

        // (7) terminal-growth agreement executor vs auditor
        if (haveX && haveA) {
            double d = Math.abs(x.get("t") - a.get("t"));
            note(r, 7, "executor/auditor terminal-growth agreement", d <= 0.0001,
                    String.format("|%.6f-%.6f|=%.6f <= 0.0001", x.get("t"), a.get("t"), d));
        } else r.infos.add("check(7) terminal-growth agreement: SKIPPED (missing component)");

        return r;
    }

    // ----------------------------------------------------------------- modes
    static Map<String, Double> side(Object o, String name) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("'" + name + "' is not an object");
        Map<?, ?> m = (Map<?, ?>) o;
        Map<String, Double> d = new LinkedHashMap<>();
        for (String f : FIELDS) d.put(f, asDouble(m.get(f)));
        return d;
    }

    public static void main(String[] args) throws Exception {
        List<String> argv = Arrays.asList(args);
        if (argv.contains("--self-test")) { runSelfTest(); return; }
        if (argv.contains("--check")) {
            int i = argv.indexOf("--audit-file");
            if (i < 0 || i + 1 >= argv.size()) {
                System.out.println("usage: ComponentAudit.java --check --audit-file <json>");
                System.exit(2);
            }
            Path f = Paths.get(argv.get(i + 1));
            Result r = new Result();
            String ticker;
            Map<String, Double> x, a;
            try {
                Object root = new Jp(Files.readString(f, StandardCharsets.UTF_8)).parse();
                if (!(root instanceof Map)) throw new IllegalArgumentException("root is not an object");
                Map<?, ?> m = (Map<?, ?>) root;
                Object tk = m.get("ticker");
                if (!(tk instanceof String) || ((String) tk).isBlank())
                    throw new IllegalArgumentException("missing/blank 'ticker'");
                ticker = (String) tk;
                x = side(m.get("executor"), "executor");
                a = side(m.get("auditor"), "auditor");
            } catch (Exception e) {
                r.failures.add("cannot load audit file '" + f + "': " + e.getMessage() + " (fail-closed)");
                Verify.report("COMPONENT AUDIT OK: all checks passed.",
                        "COMPONENT AUDIT FAILED: 1 problem(s) found:",
                        r.infos, r.failures);
                return;
            }
            r.infos.add("INFO: audit-file=" + f);
            r = check(ticker, x, a, loadSectorW(r));
            Verify.report("COMPONENT AUDIT OK (" + ticker + "): all 7 checks passed, no problems found.",
                    "COMPONENT AUDIT FAILED (" + ticker + "): " + r.failures.size() + " problem(s) found:",
                    r.infos, r.failures);
            return;
        }
        System.out.println("usage: ComponentAudit.java --check --audit-file <json> | --self-test");
        System.exit(2);
    }

    /** Loads the sector table, recording a fail-closed failure on any problem. */
    static Map<String, Double> loadSectorW(Result r) {
        try {
            return sectorW();
        } catch (Exception e) {
            r.failures.add("sector table unavailable — fail closed: " + e.getMessage());
            return null;
        }
    }

    // --------------------------------------------------------------- self-test
    static Map<String, Double> base(String side) {
        // Realistic NVDA-shaped forward-tilt fixture (2026-09-29 night run).
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("value", 116.08);
        m.put("wacc", 0.105);
        m.put("t", 0.035);
        m.put("fcf_base", 96676.0);
        m.put("equity", 2845514.53);
        m.put("shares", 24514.0);
        return m;
    }

    static void runSelfTest() throws Exception {
        Map<String, Double> wmap = sectorW(); // exercises the real subprocess path
        SelfTest.checkEq("sector table has NVDA w=0.105", 0.105, wmap.get("NVDA"));
        SelfTest.checkEq("sector table has AMD w=0.105", 0.105, wmap.get("AMD"));
        SelfTest.checkEq("sector table has NFLX w=0.0725", 0.0725, wmap.get("NFLX"));

        Result happy = check("NVDA", base("x"), base("a"), wmap);
        SelfTest.check("happy path: all checks pass", happy.failures.isEmpty());

        Map<String, Double> bad = base("a"); bad.put("value", 116.50);
        Result c1 = check("NVDA", base("x"), bad, wmap);
        SelfTest.check("check(1) fails on value mismatch",
                c1.failures.size() == 1 && c1.failures.get(0).contains("check(1)"));

        Map<String, Double> bx = base("x"); bx.put("wacc", 0.106);
        Result c2 = check("NVDA", bx, base("a"), wmap);
        SelfTest.check("check(2) fails on wacc mismatch",
                c2.failures.size() == 1 && c2.failures.get(0).contains("check(2)"));

        bx = base("x"); bx.put("wacc", 0.115);
        bad = base("a"); bad.put("wacc", 0.115);
        Result c3 = check("NVDA", bx, bad, wmap);
        SelfTest.check("check(3) fails when wacc off the sector table",
                c3.failures.size() == 1 && c3.failures.get(0).contains("check(3)"));

        bx = base("x"); bx.put("t", 0.105);
        bad = base("a"); bad.put("t", 0.105);
        Result c4 = check("NVDA", bx, bad, wmap);
        SelfTest.check("check(4) fails when t >= wacc",
                c4.failures.size() == 1 && c4.failures.get(0).contains("check(4)"));

        bad = base("a"); bad.put("equity", 2000000.0);
        Result c5 = check("NVDA", base("x"), bad, wmap);
        SelfTest.check("check(5) fails on equity/shares arithmetic",
                c5.failures.size() == 1 && c5.failures.get(0).contains("check(5)"));

        bx = base("x"); bx.put("fcf_base", 90000.0);
        Result c6 = check("NVDA", bx, base("a"), wmap);
        SelfTest.check("check(6) fails on fcf_base disagreement",
                c6.failures.size() == 1 && c6.failures.get(0).contains("check(6)"));

        bx = base("x"); bx.put("t", 0.036);
        Result c7 = check("NVDA", bx, base("a"), wmap);
        SelfTest.check("check(7) fails on terminal-growth disagreement",
                c7.failures.size() == 1 && c7.failures.get(0).contains("check(7)"));

        bad = base("a"); bad.remove("shares");
        Result miss = check("NVDA", base("x"), bad, wmap);
        SelfTest.check("missing component fails closed",
                !miss.failures.isEmpty()
                        && miss.failures.stream().anyMatch(f -> f.contains("auditor.shares")));

        Result unknown = check("ZZZZ", base("x"), base("a"), wmap);
        SelfTest.check("unknown ticker fails check(3)",
                unknown.failures.size() == 1 && unknown.failures.get(0).contains("check(3)"));

        SelfTest.finish();
    }
}
