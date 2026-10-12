import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// Suggestion Tracker — Rahik's helper-agent fleet, agent #11.
///
/// ONE TASK: keep a durable log of every suggestion Muse makes to Rahik,
/// so good ideas don't die quietly when a turn moves on. A weekly job
/// reconciles the log against the conversation: new suggestions get logged,
/// acted-on ones get resolved, and the still-open ones get re-surfaced.
///
/// READ-ONLY except its own store file (scheduler_state/suggestions.jsonl).
/// It never messages Rahik itself — the weekly job delivers the open list.
/// Resolving "done" vs "still open" is the job agent's judgment call from
/// the conversation; this program is the store + extraction helper.
///
/// Commands:
///   log --text "..." --chat <id> --msg <id> [--store <path>]
///   list --open [--store <path>]
///   resolve --n <num> --status done|dismissed [--note "..."] [--store <path>]
///   extract --input <replies.jsonl>   (candidate suggestion lines per message)
public class SuggestionTracker {

    record Sug(int n, String date, String text, String chat, String msg, String status, String note) {}

    static final Pattern OFFER = Pattern.compile(
            "(?i)(want me to [^.?]+[.?]|should i [^.?]+[.?]|shall i [^.?]+[.?]"
            + "|say the word[^.]*[.]|how about [^.?]+[.?]|suggest[^.]*[.])");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        String store = arg(args, "--store", defaultStore().toString());
        switch (args[0]) {
            case "log" -> {
                String text = arg(args, "--text", null);
                if (text == null || text.isBlank()) { System.err.println("log needs --text"); System.exit(1); }
                Sug s = append(Paths.get(store), text, arg(args, "--chat", ""), arg(args, "--msg", ""));
                System.out.println("logged suggestion #" + s.n());
            }
            case "list" -> {
                boolean openOnly = has(args, "--open");
                for (Sug s : readAll(Paths.get(store))) {
                    if (openOnly && !s.status().equals("open")) continue;
                    System.out.println("#" + s.n() + " [" + s.status() + "] " + s.date() + " | "
                            + s.text() + (s.note().isEmpty() ? "" : " — " + s.note()));
                }
            }
            case "resolve" -> {
                int n = Integer.parseInt(arg(args, "--n", "0"));
                String st = arg(args, "--status", null);
                if (!"done".equals(st) && !"dismissed".equals(st)) { System.err.println("status must be done|dismissed"); System.exit(1); }
                resolve(Paths.get(store), n, st, arg(args, "--note", ""));
                System.out.println("resolved #" + n + " as " + st);
            }
            case "extract" -> {
                String input = arg(args, "--input", null);
                if (input == null) { System.err.println("extract needs --input"); System.exit(1); }
                for (String line : Files.readAllLines(Paths.get(input))) {
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    String id = field(line, "id"), body = field(line, "body");
                    var m = OFFER.matcher(body);
                    while (m.find()) System.out.println(id + " :: " + m.group().strip());
                }
            }
            default -> { System.err.println("unknown command: " + args[0]); System.exit(1); }
        }
    }

    // ---- store ----

    static Path defaultStore() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return Paths.get(h + "/workspace/scheduler_state/suggestions.jsonl");
    }

    static List<Sug> readAll(Path store) throws IOException {
        List<Sug> out = new ArrayList<>();
        if (!Files.exists(store)) return out;
        for (String line : Files.readAllLines(store)) {
            line = line.strip();
            if (line.isEmpty()) continue;
            int n;
            var nm = Pattern.compile("\"n\"\\s*:\\s*(\\d+)").matcher(line);
            n = nm.find() ? Integer.parseInt(nm.group(1)) : 0;
            out.add(new Sug(
                    n, field(line, "date"), field(line, "text"),
                    field(line, "chat"), field(line, "msg"), field(line, "status"), field(line, "note")));
        }
        return out;
    }

    static Sug append(Path store, String text, String chat, String msg) throws IOException {
        List<Sug> all = readAll(store);
        int n = all.stream().mapToInt(Sug::n).max().orElse(0) + 1;
        Sug s = new Sug(n, LocalDate.now().toString(), text, chat, msg, "open", "");
        Files.createDirectories(store.getParent());
        String json = "{\"n\":" + n + ",\"date\":\"" + s.date() + "\",\"text\":" + q(text)
                + ",\"chat\":" + q(chat) + ",\"msg\":" + q(msg) + ",\"status\":\"open\",\"note\":\"\"}\n";
        Files.writeString(store, json,
                Files.exists(store) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        return s;
    }

    static void resolve(Path store, int n, String status, String note) throws IOException {
        List<Sug> all = readAll(store);
        boolean found = false;
        StringBuilder sb = new StringBuilder();
        for (Sug s : all) {
            if (s.n() == n) {
                found = true;
                s = new Sug(s.n(), s.date(), s.text(), s.chat(), s.msg(), status, note);
            }
            sb.append("{\"n\":").append(s.n()).append(",\"date\":\"").append(s.date())
              .append("\",\"text\":").append(q(s.text())).append(",\"chat\":").append(q(s.chat()))
              .append(",\"msg\":").append(q(s.msg())).append(",\"status\":\"").append(s.status())
              .append("\",\"note\":").append(q(s.note())).append("}\n");
        }
        if (!found) { System.err.println("no suggestion #" + n); System.exit(1); }
        Files.writeString(store, sb.toString());
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    // ---- plumbing ----

    static String arg(String[] args, String name, String def) {
        for (int i = 0; i + 1 < args.length; i++)
            if (args[i].equals(name)) return args[i + 1];
        return def;
    }

    static boolean has(String[] args, String name) {
        for (String a : args) if (a.equals(name)) return true;
        return false;
    }

    static String field(String json, String name) {
        Pattern pat = Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        var m = pat.matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    // ---- self-test ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("sug-test");
        Path store = tmp.resolve("s.jsonl");

        Sug a = append(store, "Want me to delete those nine?", "chat1", "m1");
        Sug b = append(store, "Should I refresh the two stale artifacts?", "chat1", "m2");
        check(a.n() == 1 && b.n() == 2, "numbering must increment");

        List<Sug> open = readAll(store).stream().filter(s -> s.status().equals("open")).toList();
        check(open.size() == 2, "both open");

        resolve(store, 1, "done", "deleted 2026-10-07");
        List<Sug> all = readAll(store);
        check(all.get(0).status().equals("done") && all.get(0).note().contains("deleted"),
                "resolve must stick");
        check(all.get(1).status().equals("open"), "other stays open");

        // extract: finds the offers in reply bodies
        Path msgs = tmp.resolve("r.jsonl");
        Files.writeString(msgs, "{\"id\":\"m9\",\"body\":\"Blah blah. Want me to sweep the staging pile? It is safe.\"}\n"
                + "{\"id\":\"m10\",\"body\":\"Done. No offers here at all.\"}\n");
        // (extract prints to stdout; verify the regex directly)
        var m = OFFER.matcher("Blah blah. Want me to sweep the staging pile? It is safe.");
        check(m.find() && m.group().contains("Want me to sweep"), "extract must catch the offer, got: " + m.group());
        var m2 = OFFER.matcher("Done. No offers here at all.");
        check(!m2.find(), "must not invent offers");

        System.out.println("SuggestionTracker self-test: ALL GREEN (log/list/resolve/extract)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
