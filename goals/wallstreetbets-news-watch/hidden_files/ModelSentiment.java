import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * ModelSentiment — FinBERT+VADER ensemble scorer for the Reddit crowd-pulse.
 *
 * <p>Java stays the orchestrator (Rahik reads Java): this class shells out
 * ONCE per JVM run to {@code sentiment_batch.py}, feeding it every post text
 * as JSON lines on stdin and reading back {@code {id,label,score}} lines.
 * One Python invocation total — the ~1GB model loads exactly once.
 *
 * <p>Any failure (no venv, no model, timeout, bad output) returns null and
 * the caller falls back to the lexicon scorer. The digest never breaks
 * because the model is unavailable.
 *
 * <p>Known weakness, disclosed not hidden: sarcasm/irony. "Great, another
 * dip, love it" can score bullish. CrowdPulse's digest section says so.
 */
public class ModelSentiment {

    static final Path STATE_DIR = Paths.get(home(), "workspace", "goals",
        "wallstreetbets-news-watch", "hidden_files");
    static final Path SCRIPT = STATE_DIR.resolve("sentiment_batch.py");
    static final Path MODELS_DIR = Paths.get(home(), "workspace", "models");

    // Overridable for tests.
    static String pythonBin = MODELS_DIR.resolve("venv/bin/python").toString();
    static String modelPath = MODELS_DIR.resolve("modernfinbert").toString();

    static final long TIMEOUT_MIN = 20;

    /** Sarcasm flags from the most recent scoreAll call (id -> true when the
     *  CLI flagged the post sarcastic). Empty when the model path was used
     *  without irony support or the lexicon fallback ran. */
    static final Map<String, Boolean> lastSarcasmFlags = new LinkedHashMap<>();

    static String home() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /**
     * Score every text in one Python batch. Returns id -&gt; score (-1..1),
     * or null when the model path is unavailable (caller uses lexicon).
     */
    static Map<String, Double> scoreAll(Map<String, String> idToText) {
        if (idToText.isEmpty()) return new LinkedHashMap<>();
        if (!Files.isExecutable(Paths.get(pythonBin)) || !Files.isRegularFile(SCRIPT)) {
            System.out.println("[sentiment] model unavailable (no venv/script) — lexicon fallback");
            return null;
        }
        // Model dir may be absent on first run before download; the script
        // would then try the HF hub name — still fine, but prefer the local copy.
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonBin, SCRIPT.toString());
            pb.environment().put("FINBERT_MODEL",
                Files.isDirectory(Paths.get(modelPath)) ? modelPath : "tabularisai/ModernFinBERT");
            Path ironyPath = MODELS_DIR.resolve("irony");
            if (Files.isDirectory(ironyPath))
                pb.environment().put("IRONY_MODEL", ironyPath.toString());
            pb.redirectErrorStream(false);
            Process p = pb.start();

            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8))) {
                for (Map.Entry<String, String> e : idToText.entrySet()) {
                    JSONObject o = new JSONObject();
                    o.put("id", e.getKey());
                    o.put("text", e.getValue());
                    w.write(o.toString());
                    w.newLine();
                }
            }

            Map<String, Double> out = new LinkedHashMap<>();
            lastSarcasmFlags.clear();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || !line.startsWith("{")) continue;
                    JSONObject o = new JSONObject(line);
                    if (o.has("id") && o.has("score"))
                        out.put(o.getString("id"), o.getDouble("score"));
                    if (o.has("id") && o.optBoolean("sarcasm_flag", false))
                        lastSarcasmFlags.put(o.getString("id"), Boolean.TRUE);
                }
            }
            // Drain stderr to the log so model errors are visible, not silent.
            try (BufferedReader e = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = e.readLine()) != null)
                    System.out.println("[sentiment] " + line);
            }

            boolean done = p.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES);
            if (!done || p.exitValue() != 0 || out.isEmpty()) {
                p.destroyForcibly();
                System.out.println("[sentiment] batch failed (timeout/exit/empty) — lexicon fallback");
                return null;
            }
            System.out.println("[sentiment] model scored " + out.size() + " texts");
            return out;
        } catch (Exception e) {
            System.out.println("[sentiment] " + e.getClass().getSimpleName()
                + ": " + e.getMessage() + " — lexicon fallback");
            return null;
        }
    }

    /** Lexicon fallback mapped onto the model's -1..1 scale. */
    static double lexiconScore(String text) {
        int s = CrowdPulse.sentiment(text);
        return s > 0 ? 0.5 : s < 0 ? -0.5 : 0.0;
    }

    // ------------------------------------------------------------------
    // Self-test (no network; model-dependent parts are conditional).
    // Uses the shared SelfTest helper (~/workspace/java-shared), 2026-09-28.
    // ------------------------------------------------------------------

    static void runSelfTest() throws Exception {
        // 1. Missing python -> null (fallback), no exception.
        String saved = pythonBin;
        pythonBin = "/nonexistent/python3";
        Map<String, String> one = new LinkedHashMap<>();
        one.put("x1", "bullish breakout, buy the dip");
        SelfTest.check("missing python returns null (fallback)", scoreAll(one) == null);
        pythonBin = saved;

        // 2. Lexicon fallback mapping sane.
        SelfTest.check("lexicon bull > 0", lexiconScore("buy the dip, bullish breakout") > 0);
        SelfTest.check("lexicon bear < 0", lexiconScore("sell now, crash coming") < 0);
        SelfTest.check("lexicon neutral 0", lexiconScore("the company reported tuesday") == 0.0);

        // 3. Live smoke — only when the venv + model are actually present.
        if (Files.isExecutable(Paths.get(pythonBin))
                && Files.isDirectory(Paths.get(modelPath))) {
            Map<String, String> two = new LinkedHashMap<>();
            two.put("a", "blowout earnings, raising guidance, buying more shares");
            two.put("b", "missed badly, downgraded, selling everything");
            Map<String, Double> r = scoreAll(two);
            SelfTest.check("live batch returns 2 scores", r != null && r.size() == 2);
            if (r != null) {
                SelfTest.check("live: bullish text scores above bearish", r.get("a") > r.get("b"));
                System.out.println("[sentiment] live scores: a=" + r.get("a")
                    + " b=" + r.get("b"));
            }
        } else {
            System.out.println("[sentiment] live smoke skipped (no venv/model)");
        }
        SelfTest.finish();
    }


    public static void main(String[] args) throws Exception {
        runSelfTest();
    }
}
