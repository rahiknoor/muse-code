// NearbyCheckVerify.java — idea #44 health-check verifier for the
// saved-places-nearby-check cron (the 35-minute location watch).
//
// WHY THIS EXISTS: the check reads last_location.json, saved_places.json,
// and muted_places.json every run. If any of them got corrupted, the run
// would either crash mid-flight or — worse — compute distances against
// garbage and spam (or miss) alerts. This verifier fails LOUD on bad state
// instead of letting it pile up.
//
// RUN IT with single-file launch (no compile step needed) at the START of
// the run, right after STEP 1's silent-exit rules (before any state read):
//   ~/workspace/java/jdk-21/bin/java \
//       --class-path ~/workspace/lib/json/json-20240303.jar \
//       ~/workspace/maps_import/NearbyCheckVerify.java
// Exit 0 = state files are healthy. Any other exit = a check FAILED
// (see output) — end the run quietly, same as the STEP 1 silent exits.
//
// IMPORTANT: this verifier is READ-ONLY. It never writes, never alters
// the job's paused/enabled state, never touches the watermark-equivalent
// state. It only checks that what the run is about to read is sane.
//
// CHECKS:
//   1. last_location.json: valid JSON with numeric lat/lng (if present;
//      a missing file is fine — it just means "no prior location") and,
//      when present, an "alerted_keys" array.
//   2. saved_places.json: valid JSON array.
//   3. muted_places.json: valid JSON array (missing file is fine — treated
//      as empty by the job body).
//
// NOTE on paths: never use System.getProperty("user.home") here — on this
// machine it returns /root while the workspace lives under $HOME=/home/hatch.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class NearbyCheckVerify {

    // The one failure bucket every check reports into.
    private static final List<String> FAILURES = new ArrayList<>();

    private static Path ws() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            fail("HOME env var is not set — cannot resolve workspace paths");
            return null;
        }
        return Path.of(home, "workspace");
    }

    private static void fail(String msg) {
        FAILURES.add(msg);
    }

    // Read and parse a JSON file. "optional" files may be missing (fine);
    // anything present must parse.
    private static JSONObject readObject(Path p, boolean optional) {
        if (!Files.isRegularFile(p)) {
            if (!optional) {
                fail("missing required file: " + p);
            }
            return null;
        }
        try {
            return new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("cannot read " + p + ": " + e.getMessage());
            return null;
        } catch (JSONException e) {
            fail("invalid JSON in " + p + ": " + e.getMessage());
            return null;
        }
    }

    private static JSONArray readArray(Path p, boolean optional) {
        if (!Files.isRegularFile(p)) {
            if (!optional) {
                fail("missing required file: " + p);
            }
            return null;
        }
        try {
            return new JSONArray(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("cannot read " + p + ": " + e.getMessage());
            return null;
        } catch (JSONException e) {
            fail("invalid JSON in " + p + ": " + e.getMessage());
            return null;
        }
    }

    // last_location.json is optional (first run). When present: lat/lng
    // must be finite numbers, and alerted_keys must be an array of strings.
    // Anything else means the run would compute distance against garbage.
    private static void checkLastLocation(Path ws) {
        Path p = ws.resolve("maps_import/last_location.json");
        JSONObject loc = readObject(p, true);
        if (loc == null) {
            System.out.println("OK  last_location.json absent (first run — allowed)");
            return;
        }
        double lat = loc.optDouble("lat", Double.NaN);
        double lng = loc.optDouble("lng", Double.NaN);
        if (Double.isNaN(lat) || Double.isInfinite(lat)
                || Double.isNaN(lng) || Double.isInfinite(lng)) {
            fail("last_location.json: lat/lng are not finite numbers "
                    + "(lat=" + loc.opt("lat") + ", lng=" + loc.opt("lng") + ")");
        } else if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            fail("last_location.json: lat/lng out of range (lat=" + lat
                    + ", lng=" + lng + ")");
        }
        JSONArray keys = loc.optJSONArray("alerted_keys");
        if (keys == null) {
            fail("last_location.json: 'alerted_keys' is missing or not an array");
        }
        if (FAILURES.isEmpty()) {
            System.out.println("OK  last_location.json — lat=" + lat + ", lng=" + lng
                    + ", alerted_keys=" + (keys == null ? "?" : keys.length()));
        }
    }

    private static void checkSavedPlaces(Path ws) {
        Path p = ws.resolve("maps_import/saved_places.json");
        JSONArray arr = readArray(p, false);
        if (arr == null) {
            return;
        }
        // Spot-check: every entry should be an object with a string title.
        // A non-object entry means the import data is corrupt.
        int bad = 0;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject e = arr.getJSONObject(i);
                if (e.optString("title", "").isBlank()) {
                    bad++;
                }
            } catch (JSONException ex) {
                bad++;
            }
        }
        if (bad > 0) {
            fail("saved_places.json: " + bad + " of " + arr.length()
                    + " entries are malformed (not an object or missing title)");
            return;
        }
        System.out.println("OK  saved_places.json — " + arr.length() + " entries parse");
    }

    private static void checkMutedPlaces(Path ws) {
        Path p = ws.resolve("maps_import/muted_places.json");
        JSONArray arr = readArray(p, true);
        if (arr == null) {
            System.out.println("OK  muted_places.json absent (treated as empty — allowed)");
            return;
        }
        System.out.println("OK  muted_places.json — " + arr.length()
                + " muted title(s)");
    }

    public static void main(String[] args) {
        Path ws = ws();
        if (ws == null) {
            printReport();
            return;
        }
        checkLastLocation(ws);
        checkSavedPlaces(ws);
        checkMutedPlaces(ws);
        printReport();
    }

    private static void printReport() {
        if (FAILURES.isEmpty()) {
            System.out.println("VERIFY PASS — saved-places-nearby-check "
                    + "state files are healthy");
        } else {
            System.err.println("VERIFY FAIL — " + FAILURES.size()
                    + " check(s) failed:");
            for (String f : FAILURES) {
                System.err.println("  FAIL: " + f);
            }
            System.exit(1);
        }
    }
}
