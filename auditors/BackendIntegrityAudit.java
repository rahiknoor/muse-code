import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/// Backend Integrity Auditor — Rahik's helper-agent fleet, agent #12.
///
/// ONE TASK (two checks, one pass — combined per Rahik's "combine the task"):
/// make sure the numbers feeding the vault aren't tainted at the source.
///   A. PROVENANCE: every backend value must trace to its source —
///      our_dcf <- blessed assumptions.src; creator calls <- video_url/notes;
///      notebooklm <- a recorded re-ask batch run.
///   B. PLAUSIBILITY: no value may jump >25% between runs without a recorded
///      reason (typo / bad-pull catcher). Baselines live in the auditor's own
///      snapshot file and advance ONLY for values that pass — a flagged value
///      keeps its old baseline until it is verified or fixed.
///
/// READ-ONLY except its own snapshot bookkeeping. Never edits backends.
/// Findings end in suggestions; fixing needs Rahik's word.
///
/// Structured finding format: severity | location | expected vs actual |
/// evidence | suggestion.
public class BackendIntegrityAudit {

    static final double JUMP_PCT = 0.25;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path home = Paths.get(home());
        Path csv = home.resolve("workspace/dcf_csv");
        Path batches = home.resolve("workspace/goals/quarterly-sec-filing-watch-with-dcf-updates/hidden_files");
        Path snapshot = home.resolve("workspace/scheduler_state/backend_value_snapshots.json");
        List<String> findings = audit(csv, batches, snapshot, false);
        if (findings.isEmpty()) {
            System.out.println("BACKEND INTEGRITY: CLEAN — every value traced to a source, no implausible jumps.");
        } else if (findings.size() == 1 && findings.get(0).startsWith("BASELINE")) {
            System.out.println(findings.get(0));
        } else {
            System.out.println("BACKEND INTEGRITY: " + findings.size() + " finding(s) — read-only, backends untouched.");
            for (String f : findings) System.out.println("\n---\n" + f);
        }
    }

    /** Returns findings; writes snapshot for clean values only (unless dryRun). */
    static List<String> audit(Path csv, Path batches, Path snapshot, boolean dryRun) throws IOException {
        List<String> findings = new ArrayList<>();

        JSONObject blessed = readJson(csv.resolve("blessed.json"));
        JSONObject ours = readJson(csv.resolve("our_dcf.json"));
        JSONObject creator = readJson(csv.resolve("creator_values.json"));
        JSONObject nlm = readJson(csv.resolve("notebooklm_values.json"));
        JSONObject spec = readJson(csv.resolve("speculation_price.json"));

        JSONObject oursTickers = blessed.getJSONObject("columns").getJSONObject("ours").optJSONObject("tickers");
        if (oursTickers == null) oursTickers = new JSONObject();

        // ---- A. provenance ----
        Map<String, Double> current = new LinkedHashMap<>();

        JSONObject oursVals = ours.getJSONObject("values");
        for (String t : oursVals.keySet()) {
            Double nv = num(oursVals, t);
            if (nv == null) continue;
            double v = nv;
            current.put("our_dcf." + t, v);
            if (!oursTickers.has(t)) {
                findings.add(provFinding("HIGH", "our_dcf:" + t,
                        "every DCF value blessed with assumption source",
                        "no blessed entry at all",
                        "file a proposal with assumptions + src, or remove the value"));
            } else {
                JSONObject e = oursTickers.getJSONObject(t);
                String src = e.optJSONObject("assumptions") != null
                        ? e.optJSONObject("assumptions").optString("src", "") : "";
                if (src.isBlank()) {
                    findings.add(provFinding("HIGH", "our_dcf:" + t,
                            "every DCF value blessed with assumption source",
                            "blessed entry exists but assumptions.src is empty",
                            "backfill the source (filing/quote) into the blessed assumptions"));
                }
            }
        }

        if (creator.has("latest")) {
            Object latestRaw = creator.get("latest");
            List<String> tickers = new ArrayList<>();
            Map<String, JSONObject> entries = new LinkedHashMap<>();
            if (latestRaw instanceof JSONObject jo) {
                for (String t : jo.keySet()) { tickers.add(t); entries.put(t, jo.getJSONObject(t)); }
            } else if (latestRaw instanceof JSONArray ja) {
                for (int i = 0; i < ja.length(); i++) {
                    JSONObject e = ja.getJSONObject(i);
                    String t = e.optString("ticker", "?");
                    tickers.add(t); entries.put(t, e);
                }
            }
            for (String t : tickers) {
                JSONObject e = entries.get(t);
                double v = e.optDouble("value", Double.NaN);
                if (!Double.isNaN(v)) current.put("creator." + t, v);
                String url = e.optString("video_url", ""), notes = e.optString("notes", "");
                if (url.isBlank() && notes.isBlank()) {
                    findings.add(provFinding("MEDIUM", "creator:" + t,
                            "every creator call cites its video or notes",
                            "latest entry has neither video_url nor notes",
                            "attach the video link + verbatim quote, or drop the call"));
                }
            }
        }

        // notebooklm values must trace to a recorded batch run
        Map<String, Double> batchVals = new LinkedHashMap<>();
        if (Files.isDirectory(batches)) {
            try (var s = Files.list(batches)) {
                for (Path p : s.filter(f -> f.getFileName().toString().startsWith("notebooklm_dcf_batch_")).toList()) {
                    try {
                        JSONObject bv = readJson(p).optJSONObject("values");
                        if (bv == null) continue;
                        for (String t : bv.keySet()) {
                            Double nb = num(bv, t);
                            if (nb != null) batchVals.putIfAbsent(t, nb);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        JSONObject nlmVals = nlm.optJSONObject("values");
        if (nlmVals != null) {
            for (String t : nlmVals.keySet()) {
                Double nv = num(nlmVals, t);
                if (nv == null) continue;
                double v = nv;
                current.put("notebooklm." + t, v);
                Double bv = batchVals.get(t);
                if (bv == null || Math.abs(bv - v) / Math.max(Math.abs(bv), 1e-9) > 0.01) {
                    findings.add(provFinding("MEDIUM", "notebooklm:" + t,
                            "every grounded value traces to a recorded re-ask batch",
                            "no batch run holds this value (within 1%)",
                            "re-verify from the notebook or mark the value unverified-batch"));
                }
            }
        }

        JSONObject specTickers = spec.optJSONObject("tickers");
        if (specTickers != null) {
            for (String t : specTickers.keySet()) {
                if (t.startsWith("_")) continue;
                Double nv = num(specTickers, t);
                if (nv != null) current.put("speculation." + t, nv);
            }
        }

        // ---- B. plausibility vs snapshot ----
        JSONObject snap = Files.exists(snapshot)
                ? new JSONObject(Files.readString(snapshot)) : null;
        if (snap == null) {
            if (!dryRun) writeSnapshot(snapshot, current);
            List<String> r = new ArrayList<>();
            r.add("BASELINE established for " + current.size() + " backend values — plausibility checks begin next run.");
            r.addAll(findings);
            return r;
        }
        Map<String, Double> old = new LinkedHashMap<>();
        for (String k : snap.keySet()) old.put(k, snap.getDouble(k));

        Map<String, Double> next = new LinkedHashMap<>(old);
        for (Map.Entry<String, Double> e : current.entrySet()) {
            String k = e.getKey();
            double v = e.getValue();
            Double o = old.get(k);
            if (o == null) { next.put(k, v); continue; } // new value: record silently
            if (o == 0) { next.put(k, v); continue; }
            double jump = Math.abs(v - o) / Math.abs(o);
            if (jump > JUMP_PCT) {
                findings.add("[HIGH] implausible jump | " + k + "\n"
                        + "  expected: values move gradually; jumps >" + (int) (JUMP_PCT * 100) + "% carry a recorded reason\n"
                        + "  actual: " + o + " -> " + v + " (" + (int) (jump * 100) + "% change)\n"
                        + "  evidence: backend_value_snapshots.json vs current backend\n"
                        + "  suggestion: verify against the source — likely a typo or bad pull. Baseline NOT advanced until this is cleared.");
                // do NOT advance: next keeps old value
            } else {
                next.put(k, v);
            }
        }
        if (!dryRun) writeSnapshot(snapshot, next);
        return findings;
    }


    /** Backend values are bare numbers or {"value": n} objects — accept both. */
    static Double num(JSONObject o, String key) {
        if (!o.has(key)) return null;
        Object v = o.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof JSONObject jo && jo.has("value")) {
            Object inner = jo.get("value");
            if (inner instanceof Number n) return n.doubleValue();
        }
        return null;
    }

    static String provFinding(String sev, String loc, String expected, String actual, String suggestion) {
        return "[" + sev + "] unsourced value | " + loc + "\n"
                + "  expected: " + expected + "\n"
                + "  actual: " + actual + "\n"
                + "  suggestion: " + suggestion + ".";
    }

    static void writeSnapshot(Path snapshot, Map<String, Double> vals) throws IOException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Double> e : vals.entrySet()) o.put(e.getKey(), e.getValue());
        Files.createDirectories(snapshot.getParent());
        Files.writeString(snapshot, o.toString(1));
    }

    static JSONObject readJson(Path p) throws IOException {
        return new JSONObject(Files.readString(p));
    }

    // ---- plumbing ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    // ---- self-test ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("bint-test");
        Path csv = tmp.resolve("csv");
        Path batches = tmp.resolve("batches");
        Files.createDirectories(csv);
        Files.createDirectories(batches);
        Path snap = tmp.resolve("snap.json");

        // blessed: AAA sourced, CCC entry with empty src, BBB missing entirely
        Files.writeString(csv.resolve("blessed.json"), """
                {"columns":{"ours":{"tickers":{
                  "AAA":{"value":100,"assumptions":{"src":"FY2025 10-K"},"blessed_at":"2026-01-01"},
                  "CCC":{"value":50,"assumptions":{"src":""},"blessed_at":"2026-01-01"},
                  "DDD":{"value":100,"assumptions":{"src":"10-Q"},"blessed_at":"2026-01-01"}
                }}}}""");
        Files.writeString(csv.resolve("our_dcf.json"),
                "{\"values\":{\"AAA\":100,\"BBB\":200,\"CCC\":50,\"DDD\":200}}");
        Files.writeString(csv.resolve("creator_values.json"), """
                {"latest":[
                  {"ticker":"X1","value":10,"video_url":"https://www.youtube.com/watch?v=abc","notes":"bought"},
                  {"ticker":"X2","value":20}
                ]}""");
        Files.writeString(csv.resolve("notebooklm_values.json"),
                "{\"values\":{\"N1\":117.9,\"N2\":999.0}}");
        Files.writeString(csv.resolve("speculation_price.json"), "{\"tickers\":{}}");
        Files.writeString(batches.resolve("notebooklm_dcf_batch_2026-09-26.json"),
                "{\"values\":{\"N1\":117.9}}");

        // run 1: baseline + provenance findings (dryRun so no snapshot written yet)
        List<String> f1 = audit(csv, batches, snap, true);
        check(f1.stream().anyMatch(f -> f.contains("our_dcf:BBB") && f.contains("HIGH")),
                "BBB missing blessed entry must flag HIGH, got: " + f1);
        check(f1.stream().anyMatch(f -> f.contains("our_dcf:CCC") && f.contains("empty")),
                "CCC empty src must flag, got: " + f1);
        check(f1.stream().anyMatch(f -> f.contains("creator:X2")),
                "X2 without video_url/notes must flag, got: " + f1);
        check(f1.stream().anyMatch(f -> f.contains("notebooklm:N2")),
                "N2 not in any batch must flag, got: " + f1);
        check(f1.stream().noneMatch(f -> f.contains("notebooklm:N1") && f.contains("MEDIUM")),
                "N1 traced to batch must pass");
        check(f1.stream().noneMatch(f -> f.contains("our_dcf:AAA") && f.contains("HIGH")),
                "AAA fully sourced must pass");

        // run 2: establish baseline with DDD=100 (rewrite our_dcf first)
        Files.writeString(csv.resolve("our_dcf.json"),
                "{\"values\":{\"AAA\":100,\"BBB\":200,\"CCC\":50,\"DDD\":100}}");
        List<String> f2 = audit(csv, batches, snap, false);
        check(f2.stream().anyMatch(f -> f.startsWith("BASELINE")), "first real run establishes baseline");
        // now jump DDD 100 -> 200 in the backend
        Files.writeString(csv.resolve("our_dcf.json"),
                "{\"values\":{\"AAA\":100,\"BBB\":200,\"CCC\":50,\"DDD\":200}}");
        // second real run: DDD jumps 100->200 = 100% -> flag, snapshot NOT advanced
        List<String> f3 = audit(csv, batches, snap, false);
        check(f3.stream().anyMatch(f -> f.contains("implausible jump") && f.contains("DDD")),
                "DDD 100->200 must flag, got: " + f3);
        JSONObject s3 = new JSONObject(Files.readString(snap));
        check(Math.abs(s3.getDouble("our_dcf.DDD") - 100) < 1e-9,
                "flagged DDD baseline must NOT advance, got " + s3.getDouble("our_dcf.DDD"));
        // third run: still flags (baseline held)
        List<String> f4 = audit(csv, batches, snap, false);
        check(f4.stream().anyMatch(f -> f.contains("implausible jump") && f.contains("DDD")),
                "DDD must keep flagging until cleared");

        System.out.println("BackendIntegrityAudit self-test: ALL GREEN (provenance flags, batch tracing, jump detection, baseline hold)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
