import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Guard for the seven protected value backends (blessed.json, our_dcf.json,
 * workbook_data.json, notebooklm_values.json, notebooklm_forward.json,
 * analyst_targets.json, creator_values.json).
 *
 * <p>Phase 1 diagnostics (TvCrossCheck, GrowthGuardrail, IccCrossCheck,
 * ReverseDcf, DcfBacktest) are read-only by design: they may warn and file
 * proposals, never alter a value. Every diagnostic's --verify calls
 * {@link #check()} and fails when any protected backend drifted from the
 * baseline in protected_hashes.json.
 *
 * <p>The baseline is updated ONLY by explicit auditor action
 * ({@code --rebaseline}), after an approved proposal legitimately changed a
 * value through the Proposals.java guard. Never rebaselined to silence a
 * drift.
 */
public class ValueGuard {

    static Path dcfDir() {
        return Path.of(System.getenv("HOME")).resolve("workspace/dcf_csv");
    }

    static Path srcDir() {
        return Path.of(System.getenv("HOME"))
                .resolve("workspace/your_files/dcf-valuations/.src");
    }

    /** Resolve a backend name to its file (workbook_data.json lives in .src). */
    static Path resolve(String name) {
        if (name.equals("workbook_data.json")) {
            return srcDir().resolve(name);
        }
        return dcfDir().resolve(name);
    }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(p);
        byte[] dig = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : dig) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Compare current backend hashes against the baseline.
     * Returns failure descriptions; empty means all seven unchanged.
     */
    static List<String> check() {
        List<String> failures = new ArrayList<>();
        try {
            String raw = Files.readString(dcfDir().resolve("protected_hashes.json"),
                    StandardCharsets.UTF_8);
            DcfModel.Json.Obj base =
                    (DcfModel.Json.Obj) DcfModel.Json.parse(raw);
            for (String name : new TreeMap<>(base.map).keySet()) {
                if (name.startsWith("_")) {
                    continue;
                }
                String want = base.getStr(name);
                Path p = resolve(name);
                if (!Files.exists(p)) {
                    failures.add(name + " MISSING on disk");
                    continue;
                }
                String got;
                try {
                    got = sha256(p);
                } catch (Exception e) {
                    failures.add(name + " unreadable: " + e.getMessage());
                    continue;
                }
                if (!got.equals(want)) {
                    failures.add(name + " DRIFTED (baseline " + want.substring(0, 12)
                            + "..., now " + got.substring(0, 12) + "...)");
                }
            }
        } catch (Exception e) {
            failures.add("could not read protected_hashes.json: " + e.getMessage());
        }
        return failures;
    }

    /**
     * Strengthened verifier for a Phase 1 diagnostic output file. Checks:
     * <ol>
     *   <li>file presence and required top-level keys;</li>
     *   <li>roster coverage: every ticker with DCF inputs
     *       (workbook_data.json companies) has a row;</li>
     *   <li>flag fields are booleans on every row;</li>
     *   <li>flagged_count equals the rows with any flag field true;</li>
     *   <li>the seven protected value backends are unchanged (ValueGuard).</li>
     * </ol>
     * Appends human-readable lines to infos/failures; the caller finishes
     * with Verify.report.
     */
    static void verifyDiagnostic(String fileName, String[] topKeys,
                                 String[] flagFields, String countField,
                                 List<String> infos, List<String> failures) {
        Path p = dcfDir().resolve(fileName);
        if (!Files.exists(p)) {
            failures.add(fileName + " MISSING - run the diagnostic first");
            failures.addAll(check());
            return;
        }
        infos.add(fileName + " present");
        DcfModel.Json.Obj root;
        try {
            root = (DcfModel.Json.Obj) DcfModel.Json.parse(
                    Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            failures.add(fileName + " unparseable: " + e.getMessage());
            failures.addAll(check());
            return;
        }
        for (String k : topKeys) {
            if (!root.map.containsKey(k)) {
                failures.add(fileName + ": top-level key '" + k + "' missing");
            }
        }
        // Roster coverage from the DCF input roster (workbook companies).
        List<String> roster = new ArrayList<>();
        try {
            DcfModel.Json.Obj wb = (DcfModel.Json.Obj) DcfModel.Json.parse(
                    Files.readString(srcDir().resolve("workbook_data.json"),
                            StandardCharsets.UTF_8));
            for (DcfModel.Json.Val v : wb.getList("companies")) {
                roster.add(((DcfModel.Json.Obj) v).getStr("ticker"));
            }
        } catch (Exception e) {
            failures.add("could not read workbook roster: " + e.getMessage());
        }
        List<DcfModel.Json.Val> rows;
        try {
            rows = root.getList("rows");
        } catch (Exception e) {
            failures.add(fileName + ": 'rows' is not a list");
            failures.addAll(check());
            return;
        }
        java.util.Map<String, DcfModel.Json.Obj> byTicker = new TreeMap<>();
        int flagged = 0;
        for (DcfModel.Json.Val v : rows) {
            DcfModel.Json.Obj r = (DcfModel.Json.Obj) v;
            String t = r.optStr("ticker", null);
            if (t == null) {
                failures.add(fileName + ": row without ticker");
                continue;
            }
            byTicker.put(t, r);
            boolean any = false;
            for (String f : flagFields) {
                DcfModel.Json.Val fv = r.map.get(f);
                if (!(fv instanceof DcfModel.Json.Bool)) {
                    failures.add(fileName + ": " + t + " flag field '" + f
                            + "' not a boolean");
                } else if (((DcfModel.Json.Bool) fv).v) {
                    any = true;
                }
            }
            if (any) {
                flagged++;
            }
        }
        List<String> missing = new ArrayList<>();
        for (String t : roster) {
            if (!byTicker.containsKey(t)) {
                missing.add(t);
            }
        }
        if (!missing.isEmpty()) {
            failures.add(fileName + ": roster tickers without rows: "
                    + String.join(",", missing));
        } else {
            infos.add("roster coverage: " + roster.size() + "/" + roster.size()
                    + " tickers have rows");
        }
        try {
            long want = (long) root.getNum(countField);
            if (want != flagged) {
                failures.add(fileName + ": " + countField + "=" + want
                        + " but " + flagged + " rows have a flag set");
            } else {
                infos.add(countField + "=" + want + " matches flagged rows");
            }
        } catch (Exception e) {
            failures.add(fileName + ": count field '" + countField
                    + "' missing or not numeric");
        }
        List<String> drift = check();
        if (drift.isEmpty()) {
            infos.add("protected value backends unchanged");
        }
        failures.addAll(drift);
    }

    static void selfTest() {
        // check() against the live baseline must be green right now.
        List<String> failures = check();
        SelfTest.check("protected backends unchanged vs baseline", failures.isEmpty());
        // resolve() maps workbook_data.json into .src.
        SelfTest.check("workbook_data resolves to .src",
                resolve("workbook_data.json").toString().contains(".src"));
        SelfTest.check("blessed resolves to dcf_csv",
                resolve("blessed.json").toString().endsWith("dcf_csv/blessed.json"));
        SelfTest.finish();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        if (args.length > 0 && args[0].equals("--rebaseline")) {
            // Auditor-only: recompute the baseline after an approved,
            // guard-mediated value change. Prints the new hashes; does NOT
            // write until --rebaseline --confirm.
            TreeMap<String, String> cur = new TreeMap<>();
            String[] names = {"blessed.json", "our_dcf.json", "workbook_data.json",
                    "notebooklm_values.json", "notebooklm_forward.json",
                    "analyst_targets.json", "creator_values.json"};
            for (String n : names) {
                cur.put(n, sha256(resolve(n)));
            }
            boolean confirm = args.length > 1 && args[1].equals("--confirm");
            if (!confirm) {
                System.out.println("DRY RUN - new hashes would be:");
                for (var e : cur.entrySet()) {
                    System.out.println("  " + e.getKey() + ": " + e.getValue());
                }
                System.out.println("Re-run with --rebaseline --confirm to write. "
                        + "Only after an approved proposal changed a value.");
                return;
            }
            DcfModel.Json.Obj o = new DcfModel.Json.Obj();
            o.put("_note", new DcfModel.Json.Str(
                    "Baseline SHA-256 of the seven protected value backends. "
                    + "Updated ONLY by explicit auditor action after an approved "
                    + "proposal changed a value via the guard."));
            for (var e : cur.entrySet()) {
                o.put(e.getKey(), new DcfModel.Json.Str(e.getValue()));
            }
            Files.writeString(dcfDir().resolve("protected_hashes.json"),
                    DcfModel.Json.dumps(o), StandardCharsets.UTF_8);
            System.out.println("Baseline rewritten. Record the reason in the "
                    + "daily log - a rebaseline without an approved proposal "
                    + "is a guard violation.");
            return;
        }
        List<String> failures = check();
        List<String> infos = new ArrayList<>();
        if (failures.isEmpty()) {
            infos.add("all 7 protected backends byte-identical to baseline");
        }
        Verify.report("VERIFY OK (ValueGuard): protected values unchanged.",
                "VERIFY FAILED (ValueGuard)", infos, failures);
    }
}
