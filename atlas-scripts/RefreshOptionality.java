// RefreshOptionality.java — writes the "## Optionality gap" section into Atlas
// stock notes from ~/workspace/dcf_csv/optionality_gap.json.
//
// The metric is narrative context shown ALONGSIDE the DCF, never inside it.
// This tool touches only the Optionality section; valuation tables,
// frontmatter, and all blessed values are left alone.
//
// Conventions follow RefreshValuations.java:
//  - homeDir() prefers $HOME (never the JVM user.home property)
//  - every note is sanitized via the Sanitize --batch-stdin child
//    immediately before its vault write
//  - idempotent: rerunning with an unchanged backend produces no diff
//  - run directly with LC_ALL=C.UTF-8 (emoji in the note path)
//
// Section placement: directly after the valuation table, before
// "## Stock Research Hub" — next to the DCF valuation.
//
// Run:
//   LC_ALL=C.UTF-8 ~/workspace/java/jdk-21/bin/java \
//     --class-path ~/workspace/java-shared/classes \
//     ~/workspace/atlas-scripts/RefreshOptionality.java [--base-dir DIR]
//   java ~/workspace/atlas-scripts/RefreshOptionality.java --self-test
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class RefreshOptionality {

    static final String START = "<!-- optionality-gap:start -->";
    static final String END = "<!-- optionality-gap:end -->";

    // Existing generated section, replaced in place (idempotent).
    static final Pattern SECTION = Pattern.compile(
            "<!-- optionality-gap:start -->.*?<!-- optionality-gap:end -->",
            Pattern.DOTALL);

    // Insertion point: just before the hub section heading.
    static final Pattern HUB_HEADING = Pattern.compile(
            "^## (?:Stock Research Hub|Research Hub)$", Pattern.MULTILINE);

    static final String DISCLAIMER =
            "_Narrative context only \u2014 optional bets the filing-grounded "
            + "DCF doesn't capture. Not a valuation, never blended into the DCF._";

    /** Minimal JSON parser: objects -> LinkedHashMap, arrays -> ArrayList,
     *  strings -> String. Numbers/bools/null rejected (backend is text-only). */
    static final class Json {
        private final String s;
        private int pos;
        private Json(String s) { this.s = s; }
        static Object parse(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.skipWs();
            if (j.pos != j.s.length()) throw new IllegalArgumentException("Trailing JSON at " + j.pos);
            return v;
        }
        private void skipWs() {
            while (pos < s.length() && " \t\n\r".indexOf(s.charAt(pos)) >= 0) pos++;
        }
        private Object value() {
            skipWs();
            char c = s.charAt(pos);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            throw new IllegalArgumentException("Non-text JSON value at " + pos);
        }
        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; skipWs();
            if (s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = str();
                skipWs(); pos++; // :
                m.put(key, value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Bad JSON at " + pos);
            }
        }
        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            pos++; skipWs();
            if (s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Bad JSON at " + pos);
            }
        }
        private String str() {
            StringBuilder b = new StringBuilder();
            pos++; // opening quote
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("Bad escape at " + pos);
                    }
                } else b.append(c);
            }
        }
    }

    static Path homeDir() {
        String home = System.getenv("HOME");
        return Paths.get((home == null || home.isEmpty())
                ? System.getProperty("user.home") : home);
    }

    /** One long-lived Sanitize child for the whole run — same protocol as
     *  RefreshValuations.SanitizerBatch: every note is sanitized immediately
     *  before its vault write. A dead child fails loud, never silent. */
    static final class SanitizerBatch implements AutoCloseable {
        private final Process proc;
        private final DataOutputStream out;
        private final DataInputStream in;

        SanitizerBatch() throws IOException {
            String javaBin =
                    Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            String sanitizeJava =
                    homeDir().resolve("workspace/atlas-scripts/Sanitize.java").toString();
            String sharedClasses =
                    homeDir().resolve("workspace/java-shared/classes").toString();
            proc = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                            "--class-path", sharedClasses,
                            sanitizeJava, "--batch-stdin")
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            out = new DataOutputStream(proc.getOutputStream());
            in = new DataInputStream(proc.getInputStream());
        }

        String sanitize(String dirty) throws IOException {
            byte[] bytes = dirty.getBytes(StandardCharsets.UTF_8);
            out.writeBytes("SANITIZE");
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();
            byte[] magic = new byte[2];
            try {
                in.readFully(magic);
            } catch (EOFException e) {
                throw new IOException("sanitizer child exited unexpectedly", e);
            }
            if (magic[0] != 'O' || magic[1] != 'K') {
                throw new IOException("sanitizer protocol error");
            }
            int len = in.readInt();
            if (len < 0 || len > 50_000_000) {
                throw new IOException("sanitizer bad length " + len);
            }
            byte[] buf = new byte[len];
            in.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            proc.destroy();
        }
    }

    /** Render the section block for one ticker. */
    static String renderSection(String flag, String text) {
        return START + "\n"
                + "## Optionality gap\n"
                + DISCLAIMER + "\n\n"
                + "- **Flag:** " + flag + "\n"
                + "- " + text + "\n"
                + END;
    }

    /** Insert or replace the section in note text. Pure function (testable). */
    static String applySection(String noteText, String flag, String text) {
        String block = renderSection(flag, text);
        Matcher m = SECTION.matcher(noteText);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(block));
        }
        Matcher h = HUB_HEADING.matcher(noteText);
        if (h.find()) {
            return noteText.substring(0, h.start()) + block + "\n\n"
                    + noteText.substring(h.start());
        }
        String tail = noteText.endsWith("\n") ? noteText : noteText + "\n";
        return tail + "\n" + block + "\n";
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) { selfTest(); return; }
        Path base = homeDir();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--base-dir")) {
                base = Paths.get(args[i + 1]);
            }
        }
        Path dcf = base.resolve("workspace/dcf_csv");
        Path stocks = base.resolve("workspace/atlas/01 📈 Stocks");

        Map<String, Object> backend = (Map<String, Object>) Json.parse(Files.readString(
                dcf.resolve("optionality_gap.json"), StandardCharsets.UTF_8));
        Map<String, Object> entries = (Map<String, Object>) backend.get("entries");

        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();

        try (SanitizerBatch san = new SanitizerBatch();
             Stream<Path> files = Files.list(stocks)) {
            List<Path> notes = files
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("DCF Valuation Model"))
                    .sorted()
                    .toList();
            for (Path notePath : notes) {
                String file = notePath.getFileName().toString();
                String ticker = file.substring(0, file.length() - 3);
                Object e = entries.get(ticker);
                if (!(e instanceof Map)) {
                    skipped.add(ticker + " (no backend entry)");
                    continue;
                }
                Map<String, Object> eo = (Map<String, Object>) e;
                String text = Files.readString(notePath, StandardCharsets.UTF_8);
                String newText = applySection(text, (String) eo.get("flag"), (String) eo.get("text"));
                if (newText.equals(text)) {
                    unchanged.add(ticker);
                    continue;
                }
                Files.writeString(notePath, san.sanitize(newText), StandardCharsets.UTF_8);
                updated.add(ticker);
            }
        }
        System.out.println("updated: " + updated.size()
                + ", unchanged: " + unchanged.size()
                + ", skipped: " + skipped);
    }

    static void selfTest() {
        SelfTest.reset();
        String note = "---\ntitle: NVDA\n---\n\n# NVDA\n\n| Source | Value |\n|---|---|\n"
                + "| Our DCF | $109.26 |\n\n## Stock Research Hub\n_hub_\n\n## Sources\n";
        String flag = "material";
        String text = "Robotics and sovereign AI are optional bets.";

        String once = applySection(note, flag, text);
        SelfTest.check("section inserted before hub heading",
                once.indexOf("## Optionality gap") < once.indexOf("## Stock Research Hub"));
        SelfTest.checkContains("section carries the flag", once, "- **Flag:** material");
        SelfTest.checkContains("section carries the text", once, text);
        SelfTest.checkContains("section carries the disclaimer", once, "never blended into the DCF");
        SelfTest.checkContains("section has markers", once, START);
        SelfTest.check("valuation table untouched",
                once.contains("| Our DCF | $109.26 |"));

        String twice = applySection(once, flag, text);
        SelfTest.checkEq("idempotent: second apply is a no-op", once, twice);

        String replaced = applySection(once, "limited", "New text.");
        SelfTest.checkContains("replace updates the flag", replaced, "- **Flag:** limited");
        SelfTest.checkNotContains("replace drops the old text", replaced, text);
        SelfTest.checkEq("exactly one section after replace",
                countOccurrences(replaced, START), 1);

        String noHub = "# XYZ\n\nSome text.\n";
        String appended = applySection(noHub, flag, text);
        SelfTest.check("note without hub heading gets section appended",
                appended.endsWith(END + "\n") && appended.contains("## Optionality gap"));

        SelfTest.check("backend sample parses",
                parseSample().equals("moderate"));

        SelfTest.finish();
    }

    static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) != -1) { n++; i += needle.length(); }
        return n;
    }

    @SuppressWarnings("unchecked")
    static String parseSample() {
        Map<String, Object> m = (Map<String, Object>) Json.parse(
                "{\"entries\":{\"QCOM\":{\"flag\":\"moderate\",\"text\":\"t\"}}}");
        return (String) ((Map<String, Object>)
                ((Map<String, Object>) m.get("entries")).get("QCOM")).get("flag");
    }
}
