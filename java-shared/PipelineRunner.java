// PipelineRunner.java — declarative pipeline runner for cron jobs (idea #2, 2026-09-30).
//
// WHY: scheduled jobs had become ever-longer instruction lists; every feature
// bloated the cron body, and two real bugs came from editing them (a STEP 2c
// self-dependency, a --class-path breakage across every cron body). The fix:
// each job declares its mechanical steps in a small step file, and the cron
// body becomes a one-liner invoking this runner. The runner owns the shared
// invocation details (--class-path, JAVA_HOME, workdir, timeouts) in ONE
// place, so a shared-surface change touches one file, not every cron body.
//
// A JOB = an ordered step list. Each step runs either a Java main class or a
// shell line. Per-step verify = exit code (+ optional expected output
// marker); any failure is FAIL-CLOSED — the run stops loudly with the
// failing step named, never proceeds silently. Guard steps (trading-day /
// market-open checks) declare `on-nonzero: quiet-stop`: a non-zero exit ends
// the run quietly with exit 0 — control flow, not failure.
//
// WHAT THIS IS FOR: deterministic command sequences (run pipeline, run
// verifier, append outcome line). It is NOT for agent-judgment work: jobs
// whose steps need browser delegation, reading answers, or numeric
// cross-checks (e.g. the nightly NotebookLM re-ask) cannot be expressed as a
// static step list — their per-ticker commands carry values the agent derives
// mid-run. Those jobs keep agent-shaped bodies. Migrate only the mechanical
// jobs; say so explicitly when a job does not fit.
//
// STEP FILE FORMAT (plain-text DSL, human-readable, editable without Java):
//
//   # comment lines start with '#'; blank lines are ignored.
//   # Directives apply to the steps that follow them (later overrides earlier):
//   @env LC_ALL=C.UTF-8              # extra env for every step after this line
//   @env JAVA_HOME=/home/hatch/workspace/java/jdk-21
//   @workdir /home/hatch/workspace/dcf_csv   # default working directory
//   @timeout 600                     # default per-step timeout, seconds
//   @classpath /home/hatch/workspace/java-shared/classes  # for java: steps
//
//   step trading-day-guard
//     workdir: /home/hatch/workspace/stock_watch   # per-step override
//     shell: java MarketCalendar.java --is-trading-day
//     on-nonzero: quiet-stop          # or: fail (default)
//
//   step render-table
//     java: ComparisonTable.java --print    # args split quote-aware
//     expect: Prices are from the morning price cache   # output MUST contain this
//     timeout: 300
//     env: EXTRA_FLAG=1               # per-step extra env
//     shadow: shell echo "[shadow] table render skipped"
//
// Rules: step names are unique ([a-z0-9][a-z0-9_-]*); each step has exactly
// one of `java:` / `shell:`; unknown directives or keys are parse errors
// (fail fast, never silently ignore a typo). `java:` steps run as
//   $JAVA_HOME/bin/java --class-path <@classpath> <file> <args...>
// JAVA_HOME resolves from step env, else @env, else the process env, else
// ~/workspace/java/jdk-21; its bin/ is prepended to PATH for every step so
// `shell:` steps can call `java` directly. A leading ~ in paths expands to
// $HOME.
//
// MODES:
//   --run <file.steps>     run the pipeline (fail-closed)
//   --shadow <file.steps>  shadow run: PIPELINE_SHADOW=1 is set, and any step
//                          with a `shadow:` line runs that command INSTEAD of
//                          its normal one. Shadow commands must be the
//                          writes/delivery-disabled variant of the step.
//                          Steps without `shadow:` run normally (the log marks
//                          which steps ran shadowed).
//   --check <file.steps>   parse + static validation only (step file is
//                          well-formed, referenced .java files exist)
//   --self-test            prove-can-fail suite (good list green; a failing
//                          step stops the run and is named; missing expect
//                          marker fails; quiet-stop exits 0; malformed file
//                          rejected)
//
// RUN TAIL (Verify.java conventions): per-step INFO lines, then
//   VERIFY OK (PipelineRunner <file>): N steps passed.
// or
//   VERIFY FAILED (PipelineRunner <file>): step '<name>' failed: <reason>
// A quiet stop is not a failure:
//   VERIFY OK (PipelineRunner <file>): stopped quietly at step '<name>'.
//
// MIGRATION PATTERN (the repeatable recipe for future jobs — do not migrate
// more than one job per pass):
//   1. ARCHIVE — copy the job's current cron body verbatim to the goal's
//      hidden_files/pipelines/LastGood-<job-id>.md BEFORE touching anything.
//   2. CONVERT — translate the mechanical steps into
//      hidden_files/pipelines/<job-id>.steps (directives for the shared
//      env/workdir/timeout; one `step` block per command). Judgment/delivery
//      prose stays in the cron body AROUND the one-liner — the runner never
//      delivers chat messages.
//   3. PROVE-CAN-FAIL — `PipelineRunner.java --check` the step file (must be
//      green); then run a deliberately-broken copy (bad expect marker, a step
//      that exits 1) and confirm the runner fails LOUD naming the step.
//      Restore. A check that has never seen red cannot be trusted.
//   4. SHADOW — `--shadow` the real step file against real inputs; diff the
//      step outputs against the OLD commands run by hand. Outputs must match
//      (modulo the runner's own INFO/tail lines). For write-capable jobs,
//      every mutating step needs a `shadow:` line with writes disabled.
//   5. SWITCH — cron.update the body to the one-liner + the unchanged
//      delivery prose. Re-read with cron.view before confirming.
//   6. VERIFY — run the job's own verifier (its --verify / --self-test) after
//      the switch, and re-run the exact one-liner once to prove the new body
//      works. Never hand-edit a downstream file; step files are the source.
//
// Launch (shared helpers on the class path):
//   ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/java-shared/classes \
//       ~/workspace/java-shared/PipelineRunner.java --run <file.steps>
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class PipelineRunner {

    /** UTF-8 output (same rationale as SelfTest: POSIX locales corrupt —/é).
     *  Non-final so --self-test can swap in a capturing stream. */
    static PrintStream OUT =
            new PrintStream(System.out, true, StandardCharsets.UTF_8);

    static final Pattern STEP_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final long DEFAULT_TIMEOUT_SECS = 600;

    private PipelineRunner() {
        // static-only
    }

    // ------------------------------------------------------------------
    // Model

    enum CmdType { JAVA, SHELL }
    enum OnNonzero { FAIL, QUIET_STOP }

    /** Replacement command used in --shadow mode (null when the step has none). */
    record ShadowCmd(CmdType type, String command) {}

    static final class Step {
        String name;
        CmdType type;
        String command;             // raw text after `java:` / `shell:`
        String expect;              // optional output marker
        OnNonzero onNonzero = OnNonzero.FAIL;
        Long timeoutSecs;           // null = pipeline default
        String workdir;             // null = pipeline default
        final List<String[]> extraEnv = new ArrayList<>(); // KEY, VALUE
        ShadowCmd shadow;           // null = run normally in shadow mode
    }

    static final class Pipeline {
        final Map<String, String> env = new LinkedHashMap<>();
        String workdir = System.getenv("HOME");
        long timeoutSecs = DEFAULT_TIMEOUT_SECS;
        String classpath = System.getenv("HOME") + "/workspace/java-shared/classes";
        final List<Step> steps = new ArrayList<>();
    }

    /** Parse failure with a line number — fail fast, never guess. */
    static final class ParseException extends Exception {
        ParseException(int line, String msg) {
            super("line " + line + ": " + msg);
        }
    }

    // ------------------------------------------------------------------
    // Parsing

    static Pipeline parse(Path file) throws IOException, ParseException {
        List<String> lines =
                Files.readAllLines(file, StandardCharsets.UTF_8);
        Pipeline p = new Pipeline();
        Step cur = null;
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("@")) {
                if (cur != null) {
                    // Directives between steps apply to the steps that follow.
                    cur = null;
                }
                parseDirective(p, line, lineNo);
                continue;
            }
            if (line.startsWith("step ")) {
                String name = line.substring(5).strip();
                if (!STEP_NAME.matcher(name).matches()) {
                    throw new ParseException(lineNo,
                            "bad step name '" + name
                            + "' (use [a-z0-9][a-z0-9_-]*)");
                }
                for (Step s : p.steps) {
                    if (s.name.equals(name)) {
                        throw new ParseException(lineNo,
                                "duplicate step name '" + name + "'");
                    }
                }
                cur = new Step();
                cur.name = name;
                p.steps.add(cur);
                continue;
            }
            if (cur == null) {
                throw new ParseException(lineNo,
                        "step key outside any 'step <name>' block: '" + line
                        + "'");
            }
            parseStepKey(cur, line, lineNo);
        }
        // Static validation.
        if (p.steps.isEmpty()) {
            throw new ParseException(lineNo, "no steps declared");
        }
        for (Step s : p.steps) {
            if (s.type == null) {
                throw new ParseException(lineNo,
                        "step '" + s.name + "' needs exactly one of "
                        + "'java:' / 'shell:'");
            }
            if (s.command == null || s.command.isBlank()) {
                throw new ParseException(lineNo,
                        "step '" + s.name + "' has an empty command");
            }
            if (s.timeoutSecs != null && s.timeoutSecs <= 0) {
                throw new ParseException(lineNo,
                        "step '" + s.name + "' needs timeout > 0");
            }
        }
        return p;
    }

    private static void parseDirective(Pipeline p, String line, int lineNo)
            throws ParseException {
        if (line.startsWith("@env ")) {
            String kv = line.substring(5).strip();
            int eq = kv.indexOf('=');
            if (eq <= 0) {
                throw new ParseException(lineNo,
                        "@env needs KEY=VALUE, got '" + kv + "'");
            }
            String k = kv.substring(0, eq).strip();
            if (!ENV_KEY.matcher(k).matches()) {
                throw new ParseException(lineNo, "bad env key '" + k + "'");
            }
            p.env.put(k, kv.substring(eq + 1));
            return;
        }
        if (line.startsWith("@workdir ")) {
            p.workdir = line.substring(9).strip();
            return;
        }
        if (line.startsWith("@timeout ")) {
            p.timeoutSecs = parsePositiveLong(
                    line.substring(9).strip(), lineNo, "@timeout");
            return;
        }
        if (line.startsWith("@classpath ")) {
            p.classpath = line.substring(11).strip();
            return;
        }
        throw new ParseException(lineNo, "unknown directive '" + line + "'");
    }

    private static void parseStepKey(Step s, String line, int lineNo)
            throws ParseException {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw new ParseException(lineNo,
                    "bad step key (want 'key: value'): '" + line + "'");
        }
        String key = line.substring(0, colon).strip();
        String val = line.substring(colon + 1).strip();
        switch (key) {
            case "java" -> {
                if (s.type != null) {
                    throw new ParseException(lineNo, "step '" + s.name
                            + "' declares two commands");
                }
                s.type = CmdType.JAVA;
                s.command = val;
            }
            case "shell" -> {
                if (s.type != null) {
                    throw new ParseException(lineNo, "step '" + s.name
                            + "' declares two commands");
                }
                s.type = CmdType.SHELL;
                s.command = val;
            }
            case "expect" -> s.expect = val;
            case "on-nonzero" -> {
                if (val.equals("fail")) {
                    s.onNonzero = OnNonzero.FAIL;
                } else if (val.equals("quiet-stop")) {
                    s.onNonzero = OnNonzero.QUIET_STOP;
                } else {
                    throw new ParseException(lineNo,
                            "on-nonzero wants 'fail' or 'quiet-stop', got '"
                            + val + "'");
                }
            }
            case "timeout" -> s.timeoutSecs =
                    parsePositiveLong(val, lineNo, "timeout");
            case "workdir" -> s.workdir = val;
            case "env" -> {
                int eq = val.indexOf('=');
                if (eq <= 0) {
                    throw new ParseException(lineNo,
                            "env needs KEY=VALUE, got '" + val + "'");
                }
                String k = val.substring(0, eq).strip();
                if (!ENV_KEY.matcher(k).matches()) {
                    throw new ParseException(lineNo,
                            "bad env key '" + k + "'");
                }
                s.extraEnv.add(new String[]{k, val.substring(eq + 1)});
            }
            case "shadow" -> {
                int sp = val.indexOf(' ');
                if (sp <= 0) {
                    throw new ParseException(lineNo,
                            "shadow needs '<java|shell> <command>', got '"
                            + val + "'");
                }
                String t = val.substring(0, sp).strip();
                CmdType ct = switch (t) {
                    case "java" -> CmdType.JAVA;
                    case "shell" -> CmdType.SHELL;
                    default -> throw new ParseException(lineNo,
                            "shadow type wants 'java' or 'shell', got '"
                            + t + "'");
                };
                s.shadow = new ShadowCmd(ct, val.substring(sp + 1).strip());
            }
            default -> throw new ParseException(lineNo,
                    "unknown step key '" + key + "'");
        }
    }

    private static long parsePositiveLong(String v, int lineNo, String what)
            throws ParseException {
        try {
            long n = Long.parseLong(v);
            if (n <= 0) {
                throw new ParseException(lineNo,
                        what + " needs a positive number of seconds");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new ParseException(lineNo,
                    what + " needs a number of seconds, got '" + v + "'");
        }
    }

    // ------------------------------------------------------------------
    // Execution

    /** Exit codes: 0 ok (incl. quiet stop), 1 step failed, 2 usage/parse. */
    static int execute(Path file, boolean shadow) {
        return execute(file, shadow, true);
    }

    /**
     * When {@code doExit} is false the standard Verify tail is printed but
     * the JVM is NOT exited — the exit code is returned instead. The
     * production path (main) always exits; --self-test never does.
     */
    static int execute(Path file, boolean shadow, boolean doExit) {
        Pipeline p;
        try {
            p = parse(file);
        } catch (IOException e) {
            OUT.println("PipelineRunner: cannot read " + file + ": " + e);
            return 2;
        } catch (ParseException e) {
            OUT.println("PipelineRunner: " + file + ": " + e.getMessage());
            return 2;
        }
        // Static check: java: steps must name an existing file.
        for (Step s : p.steps) {
            if (s.type == CmdType.JAVA) {
                String target = firstToken(s.command);
                Path targetPath = resolveJavaFile(target, s, p);
                if (!Files.isRegularFile(targetPath)) {
                    OUT.println("PipelineRunner: step '" + s.name
                            + "' names a missing Java file: " + target);
                    return 2;
                }
            }
        }

        List<String> infos = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        String mode = shadow ? "shadow" : "run";
        OUT.println("INFO: PipelineRunner " + mode + ": " + file + " ("
                + p.steps.size() + " steps)");
        int ran = 0;
        for (int i = 0; i < p.steps.size(); i++) {
            Step s = p.steps.get(i);
            String tag = "step " + (i + 1) + "/" + p.steps.size()
                    + " '" + s.name + "'";
            boolean shadowed = shadow && s.shadow != null;
            OUT.println("INFO: " + tag + ": start"
                    + (shadowed ? " [shadow]" : ""));
            StepOutcome o = runStep(s, p, shadowed);
            ran++;
            if (!o.output.isEmpty()) {
                OUT.println(o.output);
            }
            if (o.timedOut) {
                String why = "timed out after " + o.timeoutSecs + "s";
                OUT.println("INFO: " + tag + ": FAILED (" + why + ")");
                failures.add("step '" + s.name + "' failed: " + why);
                break;
            }
            if (o.exit != 0 && s.onNonzero == OnNonzero.QUIET_STOP) {
                OUT.println("INFO: " + tag + ": quiet stop (exit "
                        + o.exit + ") — guard closed the run, not a failure");
                return finish(
                        "VERIFY OK (PipelineRunner " + file.getFileName()
                        + "): stopped quietly at step '" + s.name + "' — "
                        + ran + "/" + p.steps.size() + " steps ran.",
                        "VERIFY FAILED (PipelineRunner " + file.getFileName()
                        + "):", infos, failures, doExit);
            }
            if (o.exit != 0) {
                String why = "exit " + o.exit;
                OUT.println("INFO: " + tag + ": FAILED (" + why + ")");
                failures.add("step '" + s.name + "' failed: " + why);
                break;
            }
            if (s.expect != null && !o.output.contains(s.expect)) {
                String why = "exit 0 but output lacks expected marker '"
                        + s.expect + "'";
                OUT.println("INFO: " + tag + ": FAILED (" + why + ")");
                failures.add("step '" + s.name + "' failed: " + why);
                break;
            }
            OUT.println("INFO: " + tag + ": OK (exit 0, "
                    + String.format("%.1f", o.secs) + "s)");
            infos.add(tag + " OK");
        }
        if (failures.isEmpty()) {
            return finish(
                    "VERIFY OK (PipelineRunner " + file.getFileName() + "): "
                    + p.steps.size() + " steps passed"
                    + (shadow ? " (shadow mode)" : "") + ".",
                    "VERIFY FAILED (PipelineRunner " + file.getFileName()
                    + "):", infos, failures, doExit);
        }
        return finish(
                "VERIFY OK (PipelineRunner " + file.getFileName() + "): "
                + p.steps.size() + " steps passed.",
                "VERIFY FAILED (PipelineRunner " + file.getFileName()
                + "): " + failures.size() + " problem(s) found:",
                infos, failures, doExit);
    }

    /**
     * The standard tail. Production delegates to Verify.report (the shared
     * tail — Rahik's rule); the no-exit path mirrors it exactly so
     * --self-test can assert on the tail without killing the JVM.
     */
    static int finish(String okLine, String failHeader,
                      List<String> infos, List<String> failures,
                      boolean doExit) {
        if (doExit) {
            Verify.report(okLine, failHeader, infos, failures);
            return 0; // unreachable (report exits) — kept for clarity
        }
        for (String note : infos) {
            OUT.println("INFO: " + note);
        }
        if (failures.isEmpty()) {
            OUT.println(okLine);
            return 0;
        }
        OUT.println(failHeader);
        for (String f : failures) {
            OUT.println("  - " + f);
        }
        return 1;
    }

    record StepOutcome(int exit, String output, double secs,
                       boolean timedOut, long timeoutSecs) {}

    private static StepOutcome runStep(Step s, Pipeline p, boolean shadowed) {
        CmdType type = s.type;
        String command = s.command;
        if (shadowed) {
            type = s.shadow.type();
            command = s.shadow.command();
        }
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.putAll(p.env);
        for (String[] kv : s.extraEnv) {
            env.put(kv[0], kv[1]);
        }
        if (shadowed) {
            env.put("PIPELINE_SHADOW", "1");
        }
        String javaHome = env.getOrDefault("JAVA_HOME",
                System.getenv("HOME") + "/workspace/java/jdk-21");
        String javaBin = javaHome + "/bin/java";

        List<String> cmd = new ArrayList<>();
        if (type == CmdType.JAVA) {
            List<String> parts = splitArgs(command);
            cmd.add(javaBin);
            cmd.add("--class-path");
            cmd.add(expandHome(p.classpath));
            cmd.addAll(parts);
        } else {
            cmd.add("bash");
            cmd.add("-c");
            cmd.add(command);
        }
        String workdir = expandHome(
                s.workdir != null ? s.workdir : p.workdir);
        long timeout = s.timeoutSecs != null ? s.timeoutSecs : p.timeoutSecs;

        Process proc = null;
        long start = System.nanoTime();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(workdir).toFile());
            Map<String, String> penv = pb.environment();
            penv.clear();
            penv.putAll(env);
            // $JAVA_HOME/bin first on PATH so `shell:` steps find java.
            String path = penv.getOrDefault("PATH", "/usr/bin:/bin");
            penv.put("PATH", javaHome + "/bin:" + path);
            penv.put("JAVA_HOME", javaHome);
            pb.redirectErrorStream(true);
            proc = pb.start();
            final Process pr = proc;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var in = pr.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        synchronized (buf) {
                            buf.write(chunk, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                    // reader thread: best effort
                }
            });
            boolean done = proc.waitFor(timeout, TimeUnit.SECONDS);
            reader.join(10_000);
            double secs = (System.nanoTime() - start) / 1e9;
            if (!done) {
                proc.destroyForcibly();
                return new StepOutcome(-1,
                        buf.toString(StandardCharsets.UTF_8).strip(),
                        secs, true, timeout);
            }
            return new StepOutcome(proc.exitValue(),
                    buf.toString(StandardCharsets.UTF_8).strip(),
                    secs, false, timeout);
        } catch (Exception e) {
            double secs = (System.nanoTime() - start) / 1e9;
            return new StepOutcome(-1, "runner error: " + e, secs, false,
                    timeout);
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    /** Quote-aware split: whitespace separates, '...' and "..." stay whole. */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    static String firstToken(String s) {
        List<String> parts = splitArgs(s);
        return parts.isEmpty() ? "" : parts.get(0);
    }

    /** Resolve a java: step's file: absolute, ~-relative, or relative to the
     *  step's workdir (the cron bodies always ran from the tool's own dir). */
    static Path resolveJavaFile(String target, Step s, Pipeline p) {
        if (target.startsWith("/") || target.startsWith("~")) {
            return Path.of(expandHome(target));
        }
        String workdir = expandHome(
                s.workdir != null ? s.workdir : p.workdir);
        return Path.of(workdir, target);
    }

    static String expandHome(String path) {
        if (path.equals("~")) {
            return System.getenv("HOME");
        }
        if (path.startsWith("~/")) {
            return System.getenv("HOME") + path.substring(1);
        }
        return path;
    }

    // ------------------------------------------------------------------
    // --check

    static int check(Path file) {
        Pipeline p;
        try {
            p = parse(file);
        } catch (IOException e) {
            OUT.println("FAIL: cannot read " + file + ": " + e);
            return 1;
        } catch (ParseException e) {
            OUT.println("FAIL: " + file + ": " + e.getMessage());
            return 1;
        }
        List<String> problems = new ArrayList<>();
        for (Step s : p.steps) {
            if (s.type == CmdType.JAVA) {
                Path target = resolveJavaFile(firstToken(s.command), s, p);
                if (!Files.isRegularFile(target)) {
                    problems.add("step '" + s.name
                            + "' names a missing Java file: " + target);
                }
            }
        }
        if (problems.isEmpty()) {
            OUT.println("PipelineRunner --check OK: " + file.getFileName()
                    + " — " + p.steps.size() + " steps, no problems.");
            return 0;
        }
        for (String pr : problems) {
            OUT.println("FAIL: " + pr);
        }
        OUT.println("PipelineRunner --check FAILED: " + problems.size()
                + " problem(s).");
        return 1;
    }

    // ------------------------------------------------------------------
    // --self-test (prove-can-fail: every red path below is a permanent
    // negative control — a good list runs green, each broken list fails LOUD)

    static Path writeSteps(Path dir, String name, String... lines)
            throws IOException {
        Path f = dir.resolve(name);
        Files.write(f, List.of(lines), StandardCharsets.UTF_8);
        return f;
    }

    static Path writeProbe(Path dir) throws IOException {
        Path f = dir.resolve("Probe.java");
        Files.writeString(f,
                "public class Probe {\n"
                + "    public static void main(String[] a) {\n"
                + "        System.out.println(\"PROBE-OK \"\n"
                + "                + String.join(\" \", a));\n"
                + "    }\n"
                + "}\n",
                StandardCharsets.UTF_8);
        return f;
    }

    /** Runs execute() with OUT swapped for a capturing stream. Single-threaded
     *  self-test only — no other writer is active while a capture runs. */
    static String captureOutput(Path file, boolean shadow, int[] exitOut) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream capture =
                new PrintStream(buf, true, StandardCharsets.UTF_8);
        PrintStream saved = OUT;
        OUT = capture;
        try {
            exitOut[0] = execute(file, shadow, false);
        } finally {
            OUT = saved;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    static void selfTest() throws Exception {
        Path dir = Files.createTempDirectory("pipelinerunner");
        writeProbe(dir);
        String home = System.getenv("HOME");
        int[] exit = new int[1];

        // 1. Good list: java step + shell step, expect markers hold.
        Path good = writeSteps(dir, "good.steps",
                "@env PIPELINE_RUNNER_TEST=1",
                "@workdir " + dir,
                "@timeout 60",
                "step probe",
                "  java: Probe.java hello",
                "  expect: PROBE-OK hello",
                "step echo",
                "  shell: echo SHELL-OK",
                "  expect: SHELL-OK");
        SelfTest.check("good list parses", parse(good).steps.size() == 2);
        String goodOut = captureOutput(good, false, exit);
        SelfTest.check("good list exits 0", exit[0] == 0);
        SelfTest.checkOutput("good list tail is the standard OK tail",
                goodOut,
                new String[]{
                    "VERIFY OK (PipelineRunner good.steps): 2 steps passed."},
                new String[]{"FAILED"});

        // 2. Failing step: exits 1 -> run stops, step named, later step
        //    never runs. This is the permanent negative control.
        Path failing = writeSteps(dir, "failing.steps",
                "@workdir " + dir,
                "step first",
                "  shell: echo FIRST-OK",
                "step boom",
                "  shell: echo BEFORE-EXIT; exit 3",
                "step never",
                "  shell: echo NEVER-RAN > " + dir + "/never_ran.txt");
        String failOut = captureOutput(failing, false, exit);
        SelfTest.check("failing step exits non-zero", exit[0] != 0);
        SelfTest.checkOutput("failing step is named in the tail", failOut,
                new String[]{"VERIFY FAILED", "step 'boom' failed: exit 3"},
                new String[]{"VERIFY OK"});
        SelfTest.check("later step never ran",
                !Files.exists(dir.resolve("never_ran.txt")));

        // 3. Missing expect marker fails even though the step exits 0.
        Path noMarker = writeSteps(dir, "nomarker.steps",
                "@workdir " + dir,
                "step echo",
                "  shell: echo hello",
                "  expect: THIS-MARKER-IS-ABSENT");
        String noMarkerOut = captureOutput(noMarker, false, exit);
        SelfTest.check("missing expect marker fails", exit[0] != 0);
        SelfTest.checkContains("missing marker names the step", noMarkerOut,
                "step 'echo' failed");

        // 4. quiet-stop: guard exits non-zero -> overall exit 0, run stops.
        Path quiet = writeSteps(dir, "quiet.steps",
                "@workdir " + dir,
                "step guard",
                "  shell: echo GUARD-CLOSED; exit 1",
                "  on-nonzero: quiet-stop",
                "step never2",
                "  shell: echo NEVER-RAN > " + dir + "/never2_ran.txt");
        String quietOut = captureOutput(quiet, false, exit);
        SelfTest.check("quiet-stop exits 0", exit[0] == 0);
        SelfTest.checkContains("quiet-stop says so in the tail", quietOut,
                "stopped quietly at step 'guard'");
        SelfTest.check("quiet-stop skips later steps",
                !Files.exists(dir.resolve("never2_ran.txt")));

        // 5. Malformed files are rejected (fail fast, never guess).
        Path bad1 = writeSteps(dir, "bad1.steps",
                "@workdir " + dir,
                "step oops",
                "  frobnicate: yes");
        captureOutput(bad1, false, exit);
        SelfTest.check("unknown step key rejected", exit[0] == 2);
        Path bad2 = writeSteps(dir, "bad2.steps",
                "@workdir " + dir,
                "step a",
                "  shell: echo hi",
                "step a",
                "  shell: echo hi");
        captureOutput(bad2, false, exit);
        SelfTest.check("duplicate step name rejected", exit[0] == 2);
        Path bad3 = writeSteps(dir, "bad3.steps",
                "@bogus yes");
        captureOutput(bad3, false, exit);
        SelfTest.check("unknown directive rejected", exit[0] == 2);
        Path bad4 = writeSteps(dir, "bad4.steps",
                "@workdir " + dir,
                "step ghost",
                "  java: NoSuchFile.java --run");
        captureOutput(bad4, false, exit);
        SelfTest.check("missing java file rejected", exit[0] == 2);

        // 6. Shadow mode: the shadow: command runs instead of the normal one.
        Path shadowed = writeSteps(dir, "shadow.steps",
                "@workdir " + dir,
                "step writer",
                "  shell: echo REAL-WRITE > " + dir + "/real_write.txt",
                "  shadow: shell echo SHADOWED > " + dir + "/shadow_write.txt");
        String shadowOut = captureOutput(shadowed, true, exit);
        SelfTest.check("shadow run exits 0", exit[0] == 0);
        SelfTest.check("shadow ran the shadow command, not the real one",
                Files.exists(dir.resolve("shadow_write.txt"))
                && !Files.exists(dir.resolve("real_write.txt")));
        SelfTest.checkContains("shadow steps are marked in the log",
                shadowOut, "[shadow]");

        // 7. Quoted args survive the java: arg splitter.
        SelfTest.checkEq("quote-aware arg split",
                List.of("a", "b c", "d"),
                splitArgs("a \"b c\" 'd'"));

        SelfTest.check("home resolved", home != null && !home.isEmpty());
        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // main

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--self-test")) {
            try {
                selfTest();
            } catch (Exception e) {
                OUT.println("self-test runner error: " + e);
                System.exit(2);
            }
            return;
        }
        if (args.length == 2 && args[0].equals("--check")) {
            System.exit(check(Path.of(args[1])));
        }
        if (args.length == 2
                && (args[0].equals("--run") || args[0].equals("--shadow"))) {
            // execute() exits via the standard tail; the System.exit below
            // only covers the unreachable path (kept for clarity).
            int code = execute(Path.of(args[1]), args[0].equals("--shadow"));
            System.exit(code);
            return;
        }
        System.out.println("Usage:");
        System.out.println("  PipelineRunner.java --run <file.steps>");
        System.out.println("  PipelineRunner.java --shadow <file.steps>");
        System.out.println("  PipelineRunner.java --check <file.steps>");
        System.out.println("  PipelineRunner.java --self-test");
        System.exit(2);
    }
}
