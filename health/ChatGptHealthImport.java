import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.json.JSONArray;
import org.json.JSONObject;

/// ChatGPT health-export importer — Rahik's health system.
///
/// INPUT: the ChatGPT data-export ZIP (Settings -> Data Controls -> Export data).
///   Drop it in ~/workspace/health/data/chatgpt_export/ and run:
///     java --class-path <json.jar> ChatGptHealthImport.java [--zip <path>]
///
/// WHAT IT DOES (read-only on the ZIP; writes only to ~/workspace/health/output/):
///   1. Extracts conversations.json from the ZIP.
///   2. Keeps conversations whose title or messages match health/nutrition keywords.
///   3. Writes output/chatgpt_health_threads.json — one entry per thread with
///      title, message count, and the full text (for the distill step).
///   4. Prints a summary: total conversations, health hits, titles.
///
/// The distill step (threads -> Atlas vault notes) runs separately once Rahik
/// reviews the hit list — nothing is filed to the vault automatically.
public class ChatGptHealthImport {

    static final String[] KEYWORDS = {
        "diet", "nutrition", "meal", "protein", "calorie", "weight", "health",
        "blood pressure", "vitamin", "mineral", "fiber", "workout", "exercise",
        "sleep", "cholesterol", "diabetes", "supplement", "fasting", "keto",
        "talon", "nutrient", "hydration", "gut", "metabolism", "bmi"
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path zip = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--zip")) zip = Paths.get(args[i + 1]);
        }
        if (zip == null) {
            zip = newestZip(Paths.get(home(), "workspace", "health", "data", "chatgpt_export"));
        }
        if (zip == null) {
            System.out.println("No ZIP found in ~/workspace/health/data/chatgpt_export/. "
                    + "Upload the ChatGPT export ZIP there first.");
            return;
        }
        List<JSONObject> hits = extractHealthThreads(zip);
        Path outDir = Paths.get(home(), "workspace", "health", "output");
        Files.createDirectories(outDir);
        JSONArray arr = new JSONArray();
        for (JSONObject h : hits) arr.put(h);
        Files.writeString(outDir.resolve("chatgpt_health_threads.json"), arr.toString(1));
        System.out.println("ZIP: " + zip.getFileName());
        System.out.println("Health threads: " + hits.size());
        for (JSONObject h : hits) {
            System.out.println("  - " + h.optString("title") + " (" + h.optInt("messages") + " msgs)");
        }
        System.out.println("Wrote: workspace/health/output/chatgpt_health_threads.json");
    }

    static List<JSONObject> extractHealthThreads(Path zip) throws Exception {
        String conversations = null;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry entry = zf.getEntry("conversations.json");
            if (entry == null) throw new IOException("conversations.json not found in ZIP");
            conversations = new String(zf.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
        JSONArray convos = new JSONArray(conversations);
        List<JSONObject> hits = new ArrayList<>();
        for (int i = 0; i < convos.length(); i++) {
            JSONObject c = convos.getJSONObject(i);
            String title = c.optString("title", "Untitled");
            StringBuilder text = new StringBuilder();
            JSONObject mapping = c.optJSONObject("mapping");
            int msgCount = 0;
            if (mapping != null) {
                for (String key : mapping.keySet()) {
                    JSONObject node = mapping.getJSONObject(key);
                    JSONObject msg = node.optJSONObject("message");
                    if (msg == null) continue;
                    JSONObject author = msg.optJSONObject("author");
                    String role = author != null ? author.optString("role", "?") : "?";
                    Object content = msg.opt("content");
                    String parts = "";
                    if (content instanceof JSONObject) {
                        JSONArray pa = ((JSONObject) content).optJSONArray("parts");
                        if (pa != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < pa.length(); j++) sb.append(pa.optString(j)).append("\n");
                            parts = sb.toString();
                        }
                    }
                    if (!parts.isBlank()) {
                        msgCount++;
                        text.append("[").append(role).append("] ").append(parts).append("\n");
                    }
                }
            }
            String hay = (title + "\n" + text).toLowerCase();
            boolean hit = false;
            for (String kw : KEYWORDS) {
                if (hay.contains(kw)) { hit = true; break; }
            }
            if (hit) {
                JSONObject h = new JSONObject();
                h.put("title", title);
                h.put("messages", msgCount);
                h.put("text", text.toString());
                hits.add(h);
            }
        }
        return hits;
    }

    static Path newestZip(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        Path best = null;
        long bestTime = -1;
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.toString().endsWith(".zip") && Files.getLastModifiedTime(p).toMillis() > bestTime) {
                    bestTime = Files.getLastModifiedTime(p).toMillis();
                    best = p;
                }
            }
        }
        return best;
    }

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    static void runSelfTest() throws Exception {
        // Fixture: 2 conversations, 1 health, 1 not.
        String fixture = "[{\"title\":\"Keto diet plan\",\"mapping\":{"
                + "\"a\":{\"message\":{\"author\":{\"role\":\"user\"},\"content\":{\"parts\":[\"What should I eat on keto?\"]}}},"
                + "\"b\":{\"message\":{\"author\":{\"role\":\"assistant\"},\"content\":{\"parts\":[\"Focus on protein and healthy fats.\"]}}}}},"
                + "{\"title\":\"Python help\",\"mapping\":{"
                + "\"c\":{\"message\":{\"author\":{\"role\":\"user\"},\"content\":{\"parts\":[\"How do I sort a list?\"]}}}}}]";
        Path tmp = Files.createTempDirectory("chatgpt-test");
        Path zipPath = tmp.resolve("export.zip");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("conversations.json"));
            zos.write(fixture.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        List<JSONObject> hits = extractHealthThreads(zipPath);
        check(hits.size() == 1, "must find exactly 1 health thread, got " + hits.size());
        check(hits.get(0).optString("title").equals("Keto diet plan"), "wrong thread matched");
        check(hits.get(0).optInt("messages") == 2, "must count 2 messages");
        System.out.println("ChatGptHealthImport self-test: ALL GREEN");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
