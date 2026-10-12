// IdeasBacklog.java — weekly stalled-idea check over ~/workspace/ideas/ideas_backlog.md
//
// Rahik's complaint (2026-09-30): pitched ideas lived only in chat history,
// so approved ideas fell through the cracks. The backlog file is the durable
// record; this tool flags stalled entries weekly.
//
//   --self-test  fixture-based checks of the stalled-detection logic (shared SelfTest)
//   --check      parse the real backlog file, print stalled items + nudge text.
//                REPORT-ONLY: always exits 0, never edits anything.
//
// Backlog contract: each entry is a "### BLG-NNN" section with exactly-spelled lines
//   Idea:, Pitched:, Status:, Status changed:, Evidence: (dates YYYY-MM-DD).
// Status base is the first word of the Status line (qualifiers in parens allowed).
//
// Stalled definitions (match the backlog file header):
//   pitched   >  7d without a status change  (awaiting his decision)
//   approved  > 14d without a status change  (no build activity recorded)
//   building  > 14d without a status change
// done / declined are never nudged.

import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

public class IdeasBacklog {

    static final int PITCHED_STALL_DAYS = 7;
    static final int APPROVED_STALL_DAYS = 14;
    static final int BUILDING_STALL_DAYS = 14;

    static final Pattern SECTION = Pattern.compile("^###\\s+(BLG-\\d+)\\b.*$");
    static final Pattern FIELD = Pattern.compile("^-\\s*(Idea|Pitched|Status|Status changed|Evidence):\\s*(.*)$");

    static Path backlogPath() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("HOME is not set");
        }
        return Paths.get(home, "workspace", "ideas", "ideas_backlog.md");
    }

    static final class Idea {
        String id, title, statusBase, statusRaw, changedRaw;
        LocalDate changed;
    }

    static final class Stalled {
        Idea idea;
        long days;
        String reason;
    }

    /** Two-pass parse: collect raw sections first, then fill entries. */
    static List<Idea> parseTwoPass(String md) {
        List<Idea> out = new ArrayList<>();
        String curId = null;
        Map<String, String> fields = new LinkedHashMap<>();
        for (String raw : md.split("\n")) {
            String line = raw.strip();
            Matcher sm = SECTION.matcher(line);
            if (sm.matches()) {
                if (curId != null) {
                    Idea i = toIdea(curId, fields);
                    if (i != null) {
                        out.add(i);
                    }
                }
                curId = sm.group(1);
                fields = new LinkedHashMap<>();
                continue;
            }
            if (curId != null) {
                Matcher fm = FIELD.matcher(line);
                if (fm.matches()) {
                    fields.put(fm.group(1), fm.group(2).strip());
                }
            }
        }
        if (curId != null) {
            Idea i = toIdea(curId, fields);
            if (i != null) {
                out.add(i);
            }
        }
        return out;
    }

    static Idea toIdea(String id, Map<String, String> f) {
        String statusRaw = f.get("Status");
        String changedRaw = f.get("Status changed");
        if (statusRaw == null || changedRaw == null) {
            return null;
        }
        LocalDate changed;
        try {
            changed = LocalDate.parse(changedRaw);
        } catch (DateTimeParseException e) {
            return null; // malformed date: skip rather than guess
        }
        String base = statusRaw.split("[\\s(]")[0].toLowerCase(Locale.ROOT);
        if (!Set.of("pitched", "approved", "building", "done", "declined").contains(base)) {
            return null; // unknown status: skip rather than guess
        }
        Idea i = new Idea();
        i.id = id;
        i.title = f.getOrDefault("Idea", "(untitled)");
        i.statusBase = base;
        i.statusRaw = statusRaw;
        i.changedRaw = changedRaw;
        i.changed = changed;
        return i;
    }

    static List<Stalled> findStalled(List<Idea> ideas, LocalDate today) {
        List<Stalled> out = new ArrayList<>();
        for (Idea i : ideas) {
            long days = ChronoUnit.DAYS.between(i.changed, today);
            String reason = null;
            switch (i.statusBase) {
                case "pitched" -> {
                    if (days > PITCHED_STALL_DAYS) {
                        reason = "awaiting his decision for " + days + "d";
                    }
                }
                case "approved" -> {
                    if (days > APPROVED_STALL_DAYS) {
                        reason = "no build activity recorded for " + days + "d";
                    }
                }
                case "building" -> {
                    if (days > BUILDING_STALL_DAYS) {
                        reason = "no status change for " + days + "d";
                    }
                }
                default -> {
                } // done / declined: never nudged
            }
            if (reason != null) {
                Stalled s = new Stalled();
                s.idea = i;
                s.days = days;
                s.reason = reason;
                out.add(s);
            }
        }
        return out;
    }

    static String nudgeText(List<Stalled> stalled, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ideas backlog — ").append(stalled.size()).append(stalled.size() == 1 ? " idea" : " ideas");
        sb.append(" stalled (checked ").append(today).append("):\n");
        for (Stalled s : stalled) {
            sb.append("- ").append(s.idea.id).append(" \"").append(s.idea.title).append("\" — ");
            sb.append(s.idea.statusBase).append(" since ").append(s.idea.changedRaw);
            sb.append(" (").append(s.reason).append(").\n");
        }
        return sb.toString();
    }

    static void runCheck() throws Exception {
        Path p = backlogPath();
        String md = Files.readString(p);
        List<Idea> ideas = parseTwoPass(md);
        LocalDate today = LocalDate.now();
        List<Stalled> stalled = findStalled(ideas, today);
        System.out.println("IDEAS BACKLOG CHECK — " + today + " — " + ideas.size() + " entries parsed");
        if (stalled.isEmpty()) {
            System.out.println("CLEAN — nothing stalled.");
        } else {
            System.out.println("STALLED (" + stalled.size() + "):");
            for (Stalled s : stalled) {
                System.out.println("- " + s.idea.id + " \"" + s.idea.title + "\" — "
                        + s.idea.statusBase + " since " + s.idea.changedRaw + " — " + s.reason);
            }
            System.out.println();
            System.out.println("NUDGE — post to the Ideas side chat:");
            System.out.print(nudgeText(stalled, today));
        }
    }

    // ------------------------------------------------------------------
    // Self-test (fixtures with dates relative to today so they never rot)
    // ------------------------------------------------------------------

    static String fixture(LocalDate today) {
        String ago10 = today.minusDays(10).toString();
        String ago3 = today.minusDays(3).toString();
        String ago20 = today.minusDays(20).toString();
        String ago5 = today.minusDays(5).toString();
        String ago60 = today.minusDays(60).toString();
        return """
                ### BLG-901
                - Idea: Old pitched idea
                - Pitched: %s
                - Status: pitched
                - Status changed: %s
                - Evidence: none
                ### BLG-902
                - Idea: Fresh pitched idea
                - Pitched: %s
                - Status: pitched
                - Status changed: %s
                - Evidence: none
                ### BLG-903
                - Idea: Old approved idea
                - Pitched: %s
                - Status: approved
                - Status changed: %s
                - Evidence: none
                ### BLG-904
                - Idea: Fresh approved idea
                - Pitched: %s
                - Status: approved
                - Status changed: %s
                - Evidence: none
                ### BLG-905
                - Idea: Old building idea
                - Pitched: %s
                - Status: building
                - Status changed: %s
                - Evidence: none
                ### BLG-906
                - Idea: Fresh building idea
                - Pitched: %s
                - Status: building
                - Status changed: %s
                - Evidence: none
                ### BLG-907
                - Idea: Ancient done idea
                - Pitched: %s
                - Status: done
                - Status changed: %s
                - Evidence: none
                ### BLG-908
                - Idea: Ancient declined idea
                - Pitched: %s
                - Status: declined
                - Status changed: %s
                - Evidence: none
                ### BLG-909
                - Idea: Old partial approved idea
                - Pitched: %s
                - Status: approved (partial build)
                - Status changed: %s
                - Evidence: none
                ### BLG-910
                - Idea: Bad date idea
                - Pitched: not-a-date
                - Status: pitched
                - Status changed: not-a-date
                - Evidence: none
                ### BLG-911
                - Idea: Weird status idea
                - Pitched: %s
                - Status: dreaming
                - Status changed: %s
                - Evidence: none
                """.formatted(ago10, ago10, ago3, ago3, ago20, ago20, ago5, ago5,
                        ago20, ago20, ago5, ago5, ago60, ago60, ago60, ago60,
                        ago20, ago20, ago20, ago20);
    }

    static void selfTest() {
        LocalDate today = LocalDate.now();
        List<Idea> ideas = parseTwoPass(fixture(today));
        SelfTest.checkEq("fixture parses 9 valid entries (bad date + unknown status skipped)", 9, ideas.size());

        List<Stalled> stalled = findStalled(ideas, today);
        Set<String> ids = new HashSet<>();
        for (Stalled s : stalled) {
            ids.add(s.idea.id);
        }
        SelfTest.checkEq("stalled set is exactly the 4 old ones", Set.of("BLG-901", "BLG-903", "BLG-905", "BLG-909"), ids);
        SelfTest.check("done/declined never stalled", !ids.contains("BLG-907") && !ids.contains("BLG-908"));
        SelfTest.check("fresh items not stalled",
                !ids.contains("BLG-902") && !ids.contains("BLG-904") && !ids.contains("BLG-906"));

        Map<String, String> reasons = new HashMap<>();
        for (Stalled s : stalled) {
            reasons.put(s.idea.id, s.reason);
        }
        SelfTest.checkContains("pitched reason names the decision wait", reasons.get("BLG-901"), "awaiting his decision");
        SelfTest.checkContains("approved reason names missing build activity", reasons.get("BLG-903"), "no build activity recorded");
        SelfTest.checkContains("building reason names missing status change", reasons.get("BLG-905"), "no status change");
        SelfTest.check("qualifier in Status still counts as approved", reasons.get("BLG-909").contains("no build activity"));

        String nudge = nudgeText(stalled, today);
        SelfTest.checkContains("nudge names each stalled id", nudge, "BLG-901");
        SelfTest.checkContains("nudge names BLG-909", nudge, "BLG-909");
        SelfTest.checkNotContains("nudge omits fresh items", nudge, "BLG-902");

        // Boundary: exactly at the threshold is NOT stalled (strictly greater).
        String edge = """
                ### BLG-921
                - Idea: Edge pitched
                - Pitched: %s
                - Status: pitched
                - Status changed: %s
                - Evidence: none
                ### BLG-922
                - Idea: Edge approved
                - Pitched: %s
                - Status: approved
                - Status changed: %s
                - Evidence: none
                """.formatted(today.minusDays(7), today.minusDays(7), today.minusDays(14), today.minusDays(14));
        List<Stalled> edgeStalled = findStalled(parseTwoPass(edge), today);
        SelfTest.checkEq("exactly-at-threshold is not stalled", 0, edgeStalled.size());

        SelfTest.finish();
    }

    static void die(String msg) {
        System.err.println("ERROR: " + msg);
        System.err.println("Usage:");
        System.err.println("  java IdeasBacklog.java --check");
        System.err.println("  java IdeasBacklog.java --self-test");
        System.exit(2);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            die("need exactly one mode");
        }
        switch (args[0]) {
            case "--self-test" -> selfTest();
            case "--check" -> runCheck();
            default -> die("unknown mode: " + args[0]);
        }
    }
}
