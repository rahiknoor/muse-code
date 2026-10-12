// ActionItemsPipeline.java
//
// One-command pipeline for the daily `daily-action-items-reminder` cron
// (idea #43), with a built-in health-check verifier (idea #44).
//
// This job is nearly trivial: read
// ~/workspace/goals/daily-open-action-items-reminder/hidden_files/action_items.json
// and report the pending items. The pipeline exists so the cron body is one
// command plus a verifier, like every other job.
//
// READ-ONLY RULE (from the job's stable rules): this job must never modify
// action_items.json — no adding items, no marking done, no edits. --run
// checksums the file before and after reading and fails loud if it changed.
//
// Usage:
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/goals/daily-open-action-items-reminder/hidden_files/ActionItemsPipeline.java \
//        --run [--root DIR]
//   java --class-path ~/workspace/lib/json/json-20240303.jar \
//        ~/workspace/goals/daily-open-action-items-reminder/hidden_files/ActionItemsPipeline.java \
//        --verify [--root DIR]
//
// --run:    print {"pending": [{id, label}], "count": n} on stdout for the
//           nudge step; the cron body stays silent when count is 0.
// --verify: fails loud when action_items.json is not valid JSON, has no
//           "items" array, or any item lacks a string id, a string label, or
//           a boolean done flag.

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

public class ActionItemsPipeline {

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    static Path workspaceRoot = Path.of(homeDir(), "workspace");

    static Path itemsFile() {
        return workspaceRoot.resolve("goals").resolve("daily-open-action-items-reminder")
                .resolve("hidden_files").resolve("action_items.json");
    }

    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run": mode = "run"; break;
                case "--verify": mode = "verify"; break;
                case "--root": workspaceRoot = Path.of(args[++i], "workspace"); break;
                default: break;
            }
        }
        if (mode == null) {
            System.out.println("Usage: ActionItemsPipeline.java --run [--root DIR]");
            System.out.println("       ActionItemsPipeline.java --verify [--root DIR]");
            System.exit(2);
        }
        if (mode.equals("run")) run();
        else verify();
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static void run() throws Exception {
        Path f = itemsFile();
        // Read-only enforcement: checksum before and after. If anything
        // changed the file mid-run, refuse to report from it.
        byte[] before = Files.readAllBytes(f);
        String sumBefore = sha256(before);
        JSONObject data = new JSONObject(new String(before, StandardCharsets.UTF_8));

        JSONArray pending = new JSONArray();
        JSONArray items = data.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (!item.getBoolean("done")) {
                JSONObject p = new JSONObject();
                p.put("id", item.getString("id"));
                p.put("label", item.getString("label"));
                pending.put(p);
            }
        }

        byte[] after = Files.readAllBytes(f);
        if (!sha256(after).equals(sumBefore)) {
            System.err.println("RUN FAILED: action_items.json changed during the read "
                    + "(read-only rule violated) — refusing to report from it.");
            System.exit(1);
        }

        JSONObject out = new JSONObject();
        out.put("pending", pending);
        out.put("count", pending.length());
        System.out.println(out.toString(2));
    }

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        Path f = itemsFile();
        JSONObject data;
        try {
            data = new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("VERIFY FAILED:\n  - action_items.json is not valid JSON: " + e.getMessage());
            System.exit(1);
            return;
        }
        JSONArray items = data.optJSONArray("items");
        if (items == null) {
            System.err.println("VERIFY FAILED:\n  - action_items.json has no 'items' array");
            System.exit(1);
        }
        java.util.Set<String> ids = new java.util.TreeSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) { problems.add("item #" + i + " is not an object"); continue; }
            String id = item.optString("id", "");
            if (id.isBlank()) problems.add("item #" + i + ": missing string 'id'");
            else if (!ids.add(id)) problems.add("duplicate item id '" + id + "'");
            if (item.optString("label", "").isBlank()) problems.add("item '" + id + "': missing string 'label'");
            if (!item.has("done") || item.isNull("done")
                    || !(item.get("done") instanceof Boolean))
                problems.add("item '" + id + "': 'done' is not a boolean");
        }
        if (!problems.isEmpty()) {
            System.err.println("VERIFY FAILED:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(1);
        }
        System.out.println("VERIFY OK: action_items.json is valid (" + items.length()
                + " items); file untouched by --run (checksummed).");
    }
}
