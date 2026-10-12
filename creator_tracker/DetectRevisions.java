// DetectRevisions.java — idea #47: creator conviction-change detection.
//
// WHY THIS EXISTS: Rahik's three followed creators (Parkev Tatevosian, Jeremy
// Lefebvre, Couch Investor) restate price targets / fair values / intrinsic
// values over time. A *change* — especially a cut — is often a louder signal
// than the number itself. The tracker logs (parkev/jeremy/couch_investor
// _targets.json) are append-only, so without this tool a lowered target just
// sits there as another row and nobody notices. This tool watches the write
// path: every time it runs, it compares each creator's newly logged valuation
// against their previous one for the same ticker and records real changes.
//
// RUN IT with single-file launch (no compile step needed), AFTER the tracker
// appends in the morning digest (STEP 5c) and in the hourly buy-call job
// (STEP 8b):
//   ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/lib/json/json-20240303.jar \
//       ~/workspace/creator_tracker/DetectRevisions.java [--dry-run]
// Exit 0 = scan completed (even when revisions were found — a detected
// revision is signal, not failure). Any other exit = a tracker file was
// unreadable/corrupt or a write failed; NOTHING is written in that case.
//
// WHAT IT WRITES (only when something actually changed):
//   ~/workspace/creator_tracker/creator_revisions.json — append-only log of
//       every detected revision + the per-(creator,ticker) reference state.
//   ~/workspace/creator_tracker/creator_revisions.md — the same log as a
//       human-readable table, regenerated each run (newest first).
//
// THE RULE (also stated in the log file's header so a future reader knows):
//   A revision is logged when a creator's newly stated VALUATION
//   (target_type fair_value | intrinsic_value | price_target) for a ticker
//   differs by MORE THAN 2% from their previous stated valuation for the
//   same ticker. First-time values only establish the baseline — they are
//   never revisions. buy_price entries are personal purchases, not targets,
//   and are excluded entirely. An entry dated EARLIER than the current
//   reference is a historical backfill: it neither triggers a revision nor
//   moves the reference. A restatement within 2% (rounding noise, e.g.
//   $338 vs $340) updates the reference silently — no log entry, no alert,
//   no duplicate.
//
// OUTPUT CONTRACT (the cron step branches on this):
//   A line "REVISIONS_DETECTED=N". When N > 0, one alert block per revision:
//       ---REVISION-ALERT-BEGIN---
//       ...one paste-ready alert...
//       ---REVISION-ALERT-END---
//   The job worker pastes each block into its side-chat delivery. One alert
//   per revision; a restated value never re-alerts because the reference
//   already moved past it.
//
// NOTE on paths: never use System.getProperty("user.home") here — on this
// machine it returns /root while the workspace lives under $HOME=/home/hatch.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DetectRevisions {

    // Valuation types that count as a "target". buy_price is deliberately
    // excluded: what someone paid is a purchase record, not a forward view.
    // (Matches BuildCreatorValues.java, which also keeps buy_price out of
    // the "latest valuation" chart column.)
    private static final List<String> VALUATION_TYPES =
            List.of("fair_value", "intrinsic_value", "price_target");

    // More than 2% move = a real conviction change. At or under 2% =
    // rounding/restatement noise, reference moves silently.
    private static final double THRESHOLD = 0.02;

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // creator key -> {display name, tracker file, (id prefix unused here)}
    private static final String[][] CREATORS = {
        {"parkev", "Parkev Tatevosian, CFA", "parkev_targets.json"},
        {"jeremy", "Jeremy Lefebvre", "jeremy_targets.json"},
        {"couch_investor", "Couch Investor (Jose Najarro)", "couch_investor_targets.json"},
        {"other_creators", "Other subscribed creators", "other_creators_targets.json"},
    };

    private static Path ws() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("HOME env var is not set");
        }
        return Path.of(home, "workspace");
    }

    private static String todayEt() {
        return LocalDate.now(ET).toString();
    }

    // "$1,494" / "$64.50" / "$338" — no trailing-zero noise.
    private static String money(double v) {
        if (v == Math.rint(v)) {
            return "$" + String.format(Locale.US, "%,.0f", v);
        }
        String s = String.format(Locale.US, "%,.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return "$" + s;
    }

    private static String pct(double p) {
        return String.format(Locale.US, "%+.1f%%", p * 100.0);
    }

    // Tracker files are either {"entries": [...]} or a bare [...].
    private static JSONArray entriesOf(Object json) {
        if (json instanceof JSONArray) return (JSONArray) json;
        return ((JSONObject) json).optJSONArray("entries") != null
                ? ((JSONObject) json).getJSONArray("entries")
                : new JSONArray();
    }

    private static void atomicWrite(Path target, String text) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) throws Exception {
        boolean dryRun = args.length > 0 && args[0].equals("--dry-run");
        Path dir = ws().resolve("creator_tracker");
        Path logPath = dir.resolve("creator_revisions.json");
        Path mdPath = dir.resolve("creator_revisions.md");

        // --- Load (or create) the revision log. A corrupt log is fail-loud:
        // we refuse to run detection against state we cannot trust.
        JSONObject log;
        if (Files.isRegularFile(logPath)) {
            try {
                log = new JSONObject(Files.readString(logPath, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                System.err.println("FAIL: creator_revisions.json is corrupt: " + e.getMessage());
                System.exit(2);
                return;
            }
        } else {
            log = new JSONObject();
            log.put("description",
                    "Append-only log of creator conviction changes (idea #47): every time "
                    + "Parkev Tatevosian, Jeremy Lefebvre, or Couch Investor restates a "
                    + "valuation for a ticker they already had a valuation for, and the "
                    + "new number differs by more than 2%, it lands here. Cuts are the "
                    + "louder signal — they are flagged in the alert text.");
            log.put("rule",
                    "REVISION = new stated valuation (fair_value|intrinsic_value|price_target) "
                    + "for a (creator,ticker) differs by MORE THAN 2% from that creator's "
                    + "previous stated valuation for the same ticker. First-time values "
                    + "establish the baseline and are never revisions. buy_price entries "
                    + "(personal purchases) are not targets and are excluded. Entries dated "
                    + "earlier than the current reference are historical backfills: no "
                    + "revision, reference unchanged. Restatements within 2% move the "
                    + "reference silently (no alert, no duplicate).");
            log.put("revisions", new JSONArray());
            log.put("reference", new JSONObject());
        }
        JSONArray revisions = log.getJSONArray("revisions");
        JSONObject reference = log.getJSONObject("reference");

        List<JSONObject> fresh = new ArrayList<>(); // revisions detected this run
        boolean dirty = false; // true once baselines/references/revisions change

        for (String[] c : CREATORS) {
            String creator = c[0], display = c[1], file = c[2];
            Path p = dir.resolve(file);
            if (!Files.isRegularFile(p)) {
                System.err.println("WARN: tracker missing, skipping: " + file);
                continue;
            }
            JSONArray entries;
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                String t = text.stripLeading();
                entries = entriesOf(t.startsWith("[") ? new JSONArray(text)
                                                     : new JSONObject(text));
            } catch (JSONException | IOException e) {
                // Fail loud, write nothing: partial detection state would lie.
                System.err.println("FAIL: cannot parse " + file + ": " + e.getMessage());
                System.exit(2);
                return;
            }

            for (int i = 0; i < entries.length(); i++) {
                JSONObject e;
                try {
                    e = entries.getJSONObject(i);
                } catch (JSONException ex) {
                    continue; // malformed row is the tracker's verifier's problem
                }
                String type = e.optString("target_type", "");
                if (!VALUATION_TYPES.contains(type)) continue; // buy_price etc: skip
                if (e.isNull("target_value")) continue;
                double value = e.optDouble("target_value", Double.NaN);
                if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                String ticker = e.optString("ticker", "").strip();
                if (ticker.isEmpty()) continue;

                String key = creator + ":" + ticker;
                String callDate = e.optString("call_date", "").strip();
                JSONObject ref = reference.optJSONObject(key);

                if (ref == null) {
                    // First valuation ever logged for this (creator, ticker):
                    // baseline only, never a revision.
                    reference.put(key, refOf(e));
                    dirty = true;
                    continue;
                }

                String refDate = ref.optString("call_date", "");
                // 2026-10-03 fix: call_date "unknown" is NOT comparable to ISO
                // dates. "unknown".compareTo("2026-...") > 0, so undated late
                // backfills slipped past the guard below and flipped references
                // on alternating runs (parkev:QCOM / parkev:CELH phantom
                // revisions). Undated entries have no provable ordering.
                boolean callDated = isIsoDate(callDate);
                boolean refDated = isIsoDate(refDate);
                if (refDated && !callDated) {
                    // Undated entry surfacing after a dated reference: a late
                    // backfill. Never an alert, never moves the reference —
                    // its file position is meaningless.
                    continue;
                }
                if (callDated && !refDated) {
                    // First dated statement for this (creator,ticker):
                    // establishes the baseline silently, then becomes the
                    // reference. No alert — the undated prior has no ordering.
                    reference.put(key, refOf(e));
                    dirty = true;
                    continue;
                }
                if (callDated && refDated && callDate.compareTo(refDate) < 0) {
                    // Older call surfacing late (backfill): not a conviction
                    // change, and it must not drag the reference backwards.
                    continue;
                }

                double oldValue = ref.getDouble("target_value");
                double change = oldValue == 0 ? (value == 0 ? 0 : 1)
                                             : (value - oldValue) / oldValue;
                if (Math.abs(change) > THRESHOLD) {
                    JSONObject r = new JSONObject();
                    r.put("id", String.format("rv-%03d", revisions.length() + 1));
                    r.put("detected", todayEt());
                    r.put("creator", creator);
                    r.put("creator_display", display);
                    r.put("ticker", ticker);
                    r.put("company", e.optString("company", ticker));
                    r.put("direction", value < oldValue ? "cut" : "raise");
                    r.put("old_value", oldValue);
                    r.put("new_value", value);
                    r.put("pct_change", Math.round(change * 1000.0) / 10.0);
                    r.put("old_target_type", ref.optString("target_type", ""));
                    r.put("new_target_type", type);
                    r.put("old_entry_id", ref.optString("entry_id", ""));
                    r.put("new_entry_id", e.optString("id", ""));
                    r.put("old_call_date", ref.optString("call_date", ""));
                    r.put("call_date", callDate);
                    r.put("video_title", e.optString("video_title", "not recorded"));
                    r.put("video_url", e.optString("video_url", "not recorded"));
                    revisions.put(r);
                    fresh.add(r);
                    dirty = true;
                }
                // In both cases the newest statement becomes the reference, so
                // restating the same number across videos never re-alerts.
                reference.put(key, refOf(e));
                dirty = true;
            }
        }

        if (dirty && !dryRun) {
            atomicWrite(logPath, log.toString(2) + "\n");
            atomicWrite(mdPath, renderMarkdown(log));
        }

        // --- Output contract: the cron step branches on REVISIONS_DETECTED.
        System.out.println("REVISIONS_DETECTED=" + fresh.size());
        for (JSONObject r : fresh) {
            System.out.println("---REVISION-ALERT-BEGIN---");
            System.out.println(alertText(r));
            System.out.println("---REVISION-ALERT-END---");
        }
        if (dryRun) {
            System.out.println("(dry run — nothing written)");
        }
    }

    // 2026-10-03: "unknown" call_dates are not ISO dates and must never be
    // compared lexicographically against real dates.
    private static boolean isIsoDate(String s) {
        return s != null && s.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private static JSONObject refOf(JSONObject e) {        JSONObject ref = new JSONObject();
        ref.put("entry_id", e.optString("id", ""));
        ref.put("call_date", e.optString("call_date", ""));
        ref.put("target_value", e.optDouble("target_value"));
        ref.put("target_type", e.optString("target_type", ""));
        return ref;
    }

    // One paste-ready alert per revision. Cuts are flagged hard — Rahik's
    // rule: a cut is the louder signal.
    private static String alertText(JSONObject r) {
        boolean cut = r.getString("direction").equals("cut");
        String headline = (cut ? "⚠️ TARGET CUT — " : "🔔 TARGET RAISED — ")
                + r.getString("creator_display") + " "
                + (cut ? "cut " : "raised ")
                + r.getString("company") + " (" + r.getString("ticker") + "): "
                + money(r.getDouble("old_value")) + " → "
                + money(r.getDouble("new_value"))
                + " (" + pct(r.getDouble("pct_change") / 100.0) + ")";
        StringBuilder sb = new StringBuilder(headline);
        sb.append("\n").append(labelOf(r.getString("new_target_type")))
          .append(" restated in \"").append(r.getString("video_title")).append("\"")
          .append(" (call date ").append(r.getString("call_date")).append(")");
        String url = r.getString("video_url");
        if (!url.isBlank() && !url.equals("not recorded")) sb.append("\n").append(url);
        sb.append("\nPrior reference: ").append(money(r.getDouble("old_value")))
          .append(" (").append(r.getString("old_entry_id"))
          .append(", ").append(r.optString("old_call_date", "")).append(")");
        return sb.toString();
    }

    private static String labelOf(String targetType) {
        return switch (targetType) {
            case "fair_value" -> "Fair value";
            case "intrinsic_value" -> "Intrinsic value";
            case "price_target" -> "Price target";
            default -> "Valuation";
        };
    }

    private static String renderMarkdown(JSONObject log) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Creator conviction changes (idea #47)\n\n");
        sb.append("> ").append(log.optString("rule", "")).append("\n\n");
        JSONArray revisions = log.getJSONArray("revisions");
        if (revisions.length() == 0) {
            sb.append("No revisions detected yet.\n");
            return sb.toString();
        }
        sb.append("| Detected | Creator | Ticker | Company | Direction | Old → New | Change | Video |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (int i = revisions.length() - 1; i >= 0; i--) { // newest first
            JSONObject r = revisions.getJSONObject(i);
            boolean cut = r.getString("direction").equals("cut");
            sb.append("| ").append(r.optString("detected", "")).append(" | ")
              .append(r.optString("creator_display", "")).append(" | ")
              .append(r.getString("ticker")).append(" | ")
              .append(r.optString("company", "")).append(" | ")
              .append(cut ? "**CUT** ⬇️" : "raise ⬆️").append(" | ")
              .append(money(r.getDouble("old_value"))).append(" → ")
              .append(money(r.getDouble("new_value"))).append(" | ")
              .append(pct(r.getDouble("pct_change") / 100.0)).append(" | ")
              .append(r.optString("video_title", "")).append(" |\n");
        }
        return sb.toString();
    }
}
