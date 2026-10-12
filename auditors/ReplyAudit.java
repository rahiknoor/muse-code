import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// Reply Auditor — Rahik's helper-agent fleet, agent #10.
///
/// ONE TASK: audit yesterday's assistant replies in the main chat and flag
/// substantive replies that carried NO suggestion, question, or next step —
/// Rahik's standing order is "suggestions every turn, no exceptions."
///
/// READ-ONLY: analyzes a JSONL dump of messages (one object per line:
/// {"id","at","body"}). The cron job builds the dump via muse.db; this
/// program never touches the database or the chat. Findings are suggestions
/// for Muse to correct the pattern — never actions against the user.
///
/// Heuristic (deliberately conservative — only clear misses flag):
///   - body < 150 chars -> not substantive, skip (acks, status lines)
///   - contains "?", a suggestion keyword, or an options-widget token -> has suggestion
///   - otherwise -> MISS
public class ReplyAudit {

    static final int SUBSTANTIVE_MIN = 150;
    static final Pattern SUGGEST = Pattern.compile(
            "(?i)suggest|recommend|want me to|should i|shall i|next step|say the word"
            + "|how about|what if|consider |offer to|shall we|maybe we");

    record Msg(String id, String at, String body) {}
    record Miss(Msg msg, String why) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        String input = null;
        for (int i = 0; i + 1 < args.length; i++)
            if (args[i].equals("--input")) input = args[i + 1];
        if (input == null) {
            System.err.println("usage: ReplyAudit.java --input <messages.jsonl>");
            System.exit(1);
        }
        List<Msg> msgs = load(Paths.get(input));
        List<Miss> misses = audit(msgs);
        if (misses.isEmpty()) {
            System.out.println("REPLY AUDIT: CLEAN — " + msgs.size() + " replies checked, every substantive one carried a suggestion.");
        } else {
            System.out.println("REPLY AUDIT: " + misses.size() + " miss(es) out of " + msgs.size() + " replies — suggestions missing:");
            for (Miss m : misses) {
                System.out.println("\n---\n[SUGGESTION MISSING] " + m.msg().at() + " | " + m.msg().id() + "\n"
                        + "  " + snippet(m.msg().body()) + "\n"
                        + "  suggestion: add the missing suggestion/next-step next turn; if this becomes a pattern, tighten the pre-reply checklist.");
            }
        }
    }

    static List<Miss> audit(List<Msg> msgs) {
        List<Miss> out = new ArrayList<>();
        for (Msg m : msgs) {
            String body = m.body() == null ? "" : m.body().strip();
            if (body.length() < SUBSTANTIVE_MIN) continue; // ack / status, not substantive
            if (body.contains("?")) continue;
            if (body.contains("[[hatch_widget:")) continue; // options widget = choice offered
            if (SUGGEST.matcher(body).find()) continue;
            out.add(new Miss(m, "substantive reply with no question, offer, or next step"));
        }
        return out;
    }

    /** Minimal JSONL parsing: extracts id/at/body string fields. */
    static List<Msg> load(Path p) throws IOException {
        List<Msg> out = new ArrayList<>();
        for (String line : Files.readAllLines(p)) {
            line = line.strip();
            if (line.isEmpty()) continue;
            out.add(new Msg(field(line, "id"), field(line, "at"), field(line, "body")));
        }
        return out;
    }

    static String field(String json, String name) {
        Pattern pat = Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        var m = pat.matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    static String snippet(String body) {
        String s = body.replaceAll("\\s+", " ").strip();
        return s.length() <= 140 ? s : s.substring(0, 140) + "...";
    }

    // ---- self-test ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("replyaudit-test");
        Path f = tmp.resolve("msgs.jsonl");
        String good = "{\"id\":\"m1\",\"at\":\"2026-10-07T10:00\",\"body\":\""
                + "All built. The fleet is now 8 auditors plus the transcript agent. "
                + "The duplicate auditor found 2 real pairs in your vault notes today. "
                + "Say the word and I will propose the merges properly.\"}";
        String bad = "{\"id\":\"m2\",\"at\":\"2026-10-07T11:00\",\"body\":\""
                + "The scan finished. I checked all 232 vault notes against the backends "
                + "and everything matched. Nothing else to report on this run today. "
                + "The full log is in the usual place if you want to look at it later.\"}";
        String ack = "{\"id\":\"m3\",\"at\":\"2026-10-07T12:00\",\"body\":\"Done.\"}";
        String widget = "{\"id\":\"m4\",\"at\":\"2026-10-07T13:00\",\"body\":\""
                + "Here are the three schedules I created for the new auditors just now. "
                + "They are all enabled and will run quietly in the background. "
                + "[[hatch_widget:widget-1234]]\"}";
        Files.writeString(f, good + "\n" + bad + "\n" + ack + "\n" + widget + "\n");

        List<Miss> misses = audit(load(f));
        check(misses.size() == 1, "exactly 1 miss expected, got " + misses.size());
        check(misses.get(0).msg().id().equals("m2"), "must flag m2, got " + misses.get(0).msg().id());
        System.out.println("ReplyAudit self-test: ALL GREEN (miss flagged, suggestion/widget/ack correctly passed)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
