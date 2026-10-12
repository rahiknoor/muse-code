import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Semantic "ask my vault" search over the Atlas markdown notes.
 *
 * <p>Retrieval only: it embeds the question with the local Xenova MiniLM ONNX
 * model (via vault_search/embed.py, CPU-only), ranks the prebuilt chunk index
 * (vault_search/index.json) by cosine similarity, and prints the top matching
 * notes with scores. It never writes to the vault and never generates answers
 * from the model — it only finds which notes are relevant.
 *
 * <p>Rebuild the index after vault content changes:
 * {@code vault_search/.venv/bin/python vault_search/build_index.py}
 *
 * <p>Usage:
 * <pre>
 *   java VaultSearch.java "which stocks are deep value right now?"
 *   java VaultSearch.java --top 10 "question"
 * </pre>
 *
 * <p>Note: run with {@code LC_ALL=C.UTF-8} so the JVM can traverse the
 * emoji-named vault folders (this VM's default locale is POSIX/ASCII).
 */
public class VaultSearch {

    static final String HOME = homeDir();
    static final Path SEARCH_DIR = Path.of(HOME, "workspace", "atlas-scripts", "vault_search");
    static final Path INDEX = SEARCH_DIR.resolve("index.json");

    public static void main(String[] args) throws Exception {
        List<String> argv = new ArrayList<>(List.of(args));
        int top = 5;
        int ti = argv.indexOf("--top");
        if (ti >= 0 && ti + 1 < argv.size()) {
            top = Integer.parseInt(argv.remove(ti + 1));
            argv.remove(ti);
        }
        if (argv.isEmpty()) {
            System.out.println("Usage: java VaultSearch.java [--top N] \"question\"");
            System.exit(1);
        }
        String question = String.join(" ", argv);

        double[] qvec = embedQuery(question);
        List<Map<String, Object>> records = loadIndex();

        // Max chunk score per note (path), keeping the best chunk's snippet.
        Map<String, Double> bestScore = new LinkedHashMap<>();
        Map<String, Map<String, Object>> bestRec = new LinkedHashMap<>();
        for (Map<String, Object> r : records) {
            @SuppressWarnings("unchecked")
            List<Object> emb = (List<Object>) r.get("embedding");
            double score = dot(qvec, emb);
            String path = (String) r.get("path");
            if (!bestScore.containsKey(path) || score > bestScore.get(path)) {
                bestScore.put(path, score);
                bestRec.put(path, r);
            }
        }

        List<String> ranked = new ArrayList<>(bestScore.keySet());
        ranked.sort(Comparator.comparingDouble(bestScore::get).reversed());

        System.out.println("question: " + question);
        System.out.println("notes indexed: " + bestScore.size());
        int n = Math.min(top, ranked.size());
        for (int i = 0; i < n; i++) {
            String path = ranked.get(i);
            Map<String, Object> r = bestRec.get(path);
            System.out.printf("%d. %s  (score %.3f)%n   %s%n   ...%s%n",
                    i + 1, r.get("title"), bestScore.get(path), path,
                    ((String) r.get("text")).replaceAll("\\s+", " "));
        }
    }

    /** Embed the question via the Python ONNX helper; returns the 384-d vector. */
    static double[] embedQuery(String question) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                SEARCH_DIR.resolve(".venv/bin/python").toString(),
                SEARCH_DIR.resolve("embed.py").toString(),
                question);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        if (code != 0 || out.isEmpty()) {
            throw new IOException("embed.py failed (exit " + code + "): " + err);
        }
        List<Object> arr = Json.arr(Json.parse(out));
        double[] v = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = ((Number) arr.get(i)).doubleValue();
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> loadIndex() throws IOException {
        String text = Files.readString(INDEX, StandardCharsets.UTF_8);
        List<Object> arr = Json.arr(Json.parse(text));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : arr) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    static double dot(double[] a, List<Object> b) {
        double s = 0;
        int n = Math.min(a.length, b.size());
        for (int i = 0; i < n; i++) {
            s += a[i] * ((Number) b.get(i)).doubleValue();
        }
        return s;
    }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    // ------------------------------------------------------------------
    /** Minimal JSON parser: objects, arrays, strings, numbers, true/false/null. */
    static final class Json {
        static Object parse(String text) {
            Parser p = new Parser(text);
            Object v = p.value();
            p.ws();
            if (p.pos != text.length()) throw new RuntimeException("trailing data");
            return v;
        }

        @SuppressWarnings("unchecked")
        static List<Object> arr(Object v) {
            return (List<Object>) v;
        }

        static final class Parser {
            final String t;
            int pos;

            Parser(String s) {
                t = s;
            }

            void ws() {
                while (pos < t.length() && " \t\n\r".indexOf(t.charAt(pos)) >= 0) pos++;
            }

            Object value() {
                ws();
                char c = t.charAt(pos);
                return switch (c) {
                    case '{' -> obj();
                    case '[' -> array();
                    case '"' -> str();
                    case 't' -> lit("true", Boolean.TRUE);
                    case 'f' -> lit("false", Boolean.FALSE);
                    case 'n' -> lit("null", null);
                    default -> num();
                };
            }

            Object lit(String word, Object v) {
                if (!t.startsWith(word, pos)) throw new RuntimeException("bad literal");
                pos += word.length();
                return v;
            }

            Map<String, Object> obj() {
                pos++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (t.charAt(pos) == '}') {
                    pos++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (t.charAt(pos) != ':') throw new RuntimeException("expected :");
                    pos++;
                    m.put(k, value());
                    ws();
                    char c = t.charAt(pos++);
                    if (c == '}') return m;
                    if (c != ',') throw new RuntimeException("expected , or }");
                }
            }

            List<Object> array() {
                pos++;
                List<Object> l = new ArrayList<>();
                ws();
                if (t.charAt(pos) == ']') {
                    pos++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    char c = t.charAt(pos++);
                    if (c == ']') return l;
                    if (c != ',') throw new RuntimeException("expected , or ]");
                }
            }

            String str() {
                pos++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = t.charAt(pos++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        char e = t.charAt(pos++);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> {
                                sb.append((char) Integer.parseInt(t.substring(pos, pos + 4), 16));
                                pos += 4;
                            }
                            default -> throw new RuntimeException("bad escape");
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }

            Object num() {
                int s = pos;
                while (pos < t.length() && "-+0123456789.eE".indexOf(t.charAt(pos)) >= 0) pos++;
                return Double.parseDouble(t.substring(s, pos));
            }
        }
    }
}
