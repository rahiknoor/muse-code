import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.JSONArray;
import org.json.JSONObject;

/// Rahik's current-diet importer — his health system.
///
/// INPUT: his current diet as a text/markdown file (whatever format he writes it in).
///   Drop it in ~/workspace/health/data/my_diet/ and run:
///     java --class-path <json.jar> DietImport.java [--file <path>]
///   (If he uploads a photo instead, this reports that and stops — photo meals
///   get transcribed separately.)
///
/// WHAT IT DOES:
///   1. Reads the diet file (newest .txt/.md in the folder by default).
///   2. Splits it into meal blocks on blank lines / "Meal N" / time headers.
///   3. Writes output/my_current_diet.json — structured meals with raw text each.
///   4. Prints the parsed meals for eyeball verification.
///
/// This is the INPUT side. The comparison (his diet vs the rebuilt 3-meal plan)
/// runs after the ChatGPT threads and Talon rankings are both in.
public class DietImport {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path file = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--file")) file = Paths.get(args[i + 1]);
        }
        if (file == null) {
            file = newestDietFile(Paths.get(home(), "workspace", "health", "data", "my_diet"));
        }
        if (file == null) {
            System.out.println("No diet file in ~/workspace/health/data/my_diet/. "
                    + "Upload your current diet as .txt or .md (a photo works too — say so and I'll transcribe it).");
            return;
        }
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
            System.out.println("Photo diet upload detected (" + file.getFileName() + "). "
                    + "Transcription runs separately — nothing parsed yet.");
            return;
        }
        JSONObject diet = parseDiet(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString());
        Path outDir = Paths.get(home(), "workspace", "health", "output");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("my_current_diet.json"), diet.toString(1));
        System.out.println("Parsed " + diet.getJSONArray("meals").length() + " meal blocks from " + file.getFileName());
        JSONArray meals = diet.getJSONArray("meals");
        for (int i = 0; i < meals.length(); i++) {
            JSONObject m = meals.getJSONObject(i);
            System.out.println("  [" + m.optString("label") + "] " + firstLine(m.optString("text")));
        }
        System.out.println("Wrote: workspace/health/output/my_current_diet.json");
    }

    static JSONObject parseDiet(String text, String source) {
        JSONObject diet = new JSONObject();
        diet.put("source", source);
        JSONArray meals = new JSONArray();
        // Split on blank lines; label blocks that start with a meal/time header.
        String[] blocks = text.split("\\n\\s*\\n");
        int n = 0;
        for (String b : blocks) {
            b = b.strip();
            if (b.isEmpty()) continue;
            n++;
            String first = firstLine(b);
            String label;
            if (first.matches("(?i)^(meal|breakfast|lunch|dinner|snack|pre|post).{0,30}")) {
                label = first;
            } else {
                label = "Block " + n;
            }
            JSONObject m = new JSONObject();
            m.put("label", label);
            m.put("text", b);
            meals.put(m);
        }
        diet.put("meals", meals);
        return diet;
    }

    static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    static Path newestDietFile(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        Path best = null;
        long bestTime = -1;
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString().toLowerCase();
                if ((n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".jpg")
                        || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
                        && Files.getLastModifiedTime(p).toMillis() > bestTime) {
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
        String diet = "Breakfast\nOatmeal with whey protein and blueberries\n\nLunch\nChicken breast, quinoa, spinach\n\nJust a note about water intake";
        JSONObject d = parseDiet(diet, "test.md");
        JSONArray meals = d.getJSONArray("meals");
        check(meals.length() == 3, "must split 3 blocks, got " + meals.length());
        check(meals.getJSONObject(0).optString("label").equals("Breakfast"), "first block label");
        check(meals.getJSONObject(2).optString("label").equals("Block 3"), "unlabeled block fallback");
        System.out.println("DietImport self-test: ALL GREEN");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
