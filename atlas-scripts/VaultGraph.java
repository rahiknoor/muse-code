// VaultGraph.java — supplementary knowledge-graph retrieval aid for the Atlas vault.
//
// Reads every Markdown note under ~/workspace/atlas/ and emits entity-relation-entity
// triples to vault_graph.json. Inspired by HippoRAG (Gutierrez et al., NeurIPS 2024):
// hippocampal-style indexing over entity-relation triples enables multi-hop
// "path-finding" retrieval across notes in a single step.
//
// This is a SUPPLEMENTARY retrieval aid, not a second memory system. It reads the
// vault; it never writes notes, never touches backends, and is NOT wired into any
// query path yet. Where it plugs in later: VaultSearch.java (atlas-scripts) could
// consult vault_graph.json alongside its text search so a query like "which creators
// cover NVDA-adjacent stocks" resolves creator->stock->topic hops without N reads.
//
// Conventions:
//  - homeDir() prefers $HOME (never the JVM user.home property)
//  - run directly with LC_ALL=C.UTF-8 (emoji in note paths)
//  - --build is a full rebuild (the vault is small); rerun after notes change
//
// Relations emitted:
//  - (note, "links-to", target)   from [[wikilinks]] / [[target|alias]]
//  - (note, "tagged", tag)        from frontmatter tags: [...]
//  - (note, "ticker", TICKER)     from frontmatter ticker:
// Notes are identified by vault-relative path without the .md suffix.
//
// Run:
//   LC_ALL=C.UTF-8 ~/workspace/java/jdk-21/bin/java \
//     --class-path ~/workspace/java-shared/classes \
//     ~/workspace/atlas-scripts/VaultGraph.java --build
//   java ~/workspace/atlas-scripts/VaultGraph.java --self-test
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class VaultGraph {

    static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]+)?\\]\\]");
    static final Pattern FRONTMATTER = Pattern.compile("\\A---\\r?\\n(.*?)\\r?\\n---", Pattern.DOTALL);
    static final Pattern TICKER_LINE = Pattern.compile("^ticker:\\s*(\\S+)\\s*$", Pattern.MULTILINE);
    static final Pattern TAGS_LINE = Pattern.compile("^tags:\\s*\\[(.*)\\]\\s*$", Pattern.MULTILINE);

    /** Vault-relative note id, e.g. "01 \uD83D\uDCC8 Stocks/NVDA". */
    static String noteId(Path vault, Path note) {
        String rel = vault.relativize(note).toString();
        return rel.endsWith(".md") ? rel.substring(0, rel.length() - 3) : rel;
    }

    /** Extracts triples from one note's text. */
    static List<String[]> triplesFor(String noteId, String text) {
        List<String[]> out = new ArrayList<>();
        Matcher fm = FRONTMATTER.matcher(text);
        String head = fm.find() ? fm.group(1) : "";
        String body = fm.find(0) ? text.substring(fm.end()) : text;

        Matcher tk = TICKER_LINE.matcher(head);
        if (tk.find()) out.add(new String[]{noteId, "ticker", tk.group(1).trim()});

        Matcher tg = TAGS_LINE.matcher(head);
        if (tg.find()) {
            for (String tag : tg.group(1).split(",")) {
                tag = tag.trim().replaceAll("^[\"']|[\"']$", "");
                if (!tag.isEmpty()) out.add(new String[]{noteId, "tagged", tag});
            }
        }

        Matcher wl = WIKILINK.matcher(body);
        while (wl.find()) {
            String target = wl.group(1).trim();
            if (!target.isEmpty()) out.add(new String[]{noteId, "links-to", target});
        }
        return out;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** Full rebuild: scan the vault, dedupe triples, write the JSON graph. */
    static int build(Path vault, Path out) throws IOException {
        return build(vault, out, referenceOnlyTopDirs(loadReferenceOnlyDirs()));
    }

    static int build(Path vault, Path out, Set<String> excludedTopDirs) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> triples = new ArrayList<>();
        List<Path> notes = new ArrayList<>();
        try (Stream<Path> s = Files.walk(vault)) {
            s.filter(p -> p.toString().endsWith(".md") && !isExcluded(vault, p, excludedTopDirs)).sorted().forEach(notes::add);
        }
        for (Path note : notes) {
            String id = noteId(vault, note);
            String text = Files.readString(note, StandardCharsets.UTF_8);
            for (String[] t : triplesFor(id, text)) {
                String key = t[0] + "\u0001" + t[1] + "\u0001" + t[2];
                if (seen.add(key)) triples.add(t);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"generated\":\"").append(Instant.now()).append("\",");
        sb.append("\"vault\":\"").append(escape(vault.toString())).append("\",");
        sb.append("\"notes\":").append(notes.size()).append(",");
        sb.append("\"triples\":[");
        for (int i = 0; i < triples.size(); i++) {
            String[] t = triples.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"s\":\"").append(escape(t[0])).append("\",");
            sb.append("\"r\":\"").append(escape(t[1])).append("\",");
            sb.append("\"o\":\"").append(escape(t[2])).append("\"}");
        }
        sb.append("]}");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("INFO: notes=" + notes.size() + " triples=" + triples.size());
        System.out.println("INFO: wrote " + out);
        return 0;
    }

    static Path homeDir() {
        String h = System.getenv("HOME");
        if (h == null || h.isEmpty()) throw new IllegalStateException("$HOME is not set");
        return Paths.get(h);
    }

    /**
     * Reference corpora excluded from graph extraction (standing rule 2026-10-07):
     * full-text scripture kept in the vault is for Rahik's reading, not for entity
     * mining — extracting Allah/Moses/etc. triples would pollute the stock/research
     * graph. The study notes (dossiers) outside these dirs are still scanned.
     */
    static List<String> loadReferenceOnlyDirs() {
        Path f = homeDir().resolve("workspace/atlas-scripts/reference_only_dirs.txt");
        return loadReferenceOnlyDirs(f);
    }

    static List<String> loadReferenceOnlyDirs(Path f) {
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
            return out;
        } catch (IOException e) {
            return List.of(); // fail-open: missing file = scan everything (today's behavior)
        }
    }

    /** Top-level vault dir names excluded, derived from the workspace-relative entries. */
    static Set<String> referenceOnlyTopDirs(List<String> entries) {
        Set<String> out = new HashSet<>();
        for (String e : entries) {
            if (!e.startsWith("workspace/atlas/")) continue; // other tools' entries (e.g. SecretsAudit)
            String p = e.substring("workspace/atlas/".length());
            int i = p.indexOf('/');
            out.add(i < 0 ? p : p.substring(0, i));
        }
        return out;
    }

    static boolean isExcluded(Path vault, Path p, Set<String> excludedTopDirs) {
        Path rel = vault.relativize(p);
        return rel.getNameCount() > 0 && excludedTopDirs.contains(rel.getName(0).toString());
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        Path vault = homeDir().resolve("workspace/atlas");
        Path out = homeDir().resolve("workspace/atlas-scripts/vault_graph.json");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--build")) continue;
            if (args[i].equals("--vault") && i + 1 < args.length) vault = Paths.get(args[++i]);
            if (args[i].equals("--out") && i + 1 < args.length) out = Paths.get(args[++i]);
        }
        System.exit(build(vault, out));
    }

    // ---- self-test ----
    static void selfTest() throws Exception {
        Path tmp = Files.createTempDirectory("vaultgraph-test");
        Path vault = tmp.resolve("vault");
        Files.createDirectories(vault.resolve("Stocks"));
        Files.writeString(vault.resolve("Stocks/NVDA.md"),
                "---\ntitle: NVDA\nticker: NVDA\ntags: [stock, ira]\n---\n\n# NVDA\n\nSee [[Jensen Huang]] and [[Stocks/AVGO|Broadcom]].\n",
                StandardCharsets.UTF_8);
        Files.writeString(vault.resolve("Home.md"),
                "# Home\n\nNo frontmatter here. Link to [[Stocks/NVDA]].\n",
                StandardCharsets.UTF_8);
        // Excluded reference corpus: must never enter the graph.
        Files.createDirectories(vault.resolve("10 \uD83D\uDCC6 Quran"));
        Files.writeString(vault.resolve("10 \uD83D\uDCC6 Quran/Quran 001.md"),
                "---\ntitle: Al-Fatihah\nticker: NVDA\n---\n\n# Al-Fatihah\n\nSee [[Jensen Huang]].\n",
                StandardCharsets.UTF_8);

        List<String[]> t1 = triplesFor("Stocks/NVDA",
                Files.readString(vault.resolve("Stocks/NVDA.md"), StandardCharsets.UTF_8));
        SelfTest.checkEq("ticker triple", "Stocks/NVDA, ticker, NVDA", t1.get(0)[0] + ", " + t1.get(0)[1] + ", " + t1.get(0)[2]);
        SelfTest.check("tagged stock", t1.stream().anyMatch(t -> t[1].equals("tagged") && t[2].equals("stock")));
        SelfTest.check("tagged ira", t1.stream().anyMatch(t -> t[1].equals("tagged") && t[2].equals("ira")));
        SelfTest.check("links-to Jensen Huang", t1.stream().anyMatch(t -> t[1].equals("links-to") && t[2].equals("Jensen Huang")));
        SelfTest.check("aliased wikilink target", t1.stream().anyMatch(t -> t[1].equals("links-to") && t[2].equals("Stocks/AVGO")));
        SelfTest.checkEq("NVDA triple count", 5, t1.size());

        List<String[]> t2 = triplesFor("Home",
                Files.readString(vault.resolve("Home.md"), StandardCharsets.UTF_8));
        SelfTest.checkEq("note without frontmatter: only wikilink", 1, t2.size());
        SelfTest.check("home links-to", t2.get(0)[1].equals("links-to") && t2.get(0)[2].equals("Stocks/NVDA"));

        SelfTest.checkEq("noteId strips .md", "Stocks/NVDA", noteId(vault, vault.resolve("Stocks/NVDA.md")));

        Path out = tmp.resolve("graph.json");
        build(vault, out, referenceOnlyTopDirs(List.of("workspace/atlas/10 \uD83D\uDCC6 Quran")));
        String json = Files.readString(out, StandardCharsets.UTF_8);
        SelfTest.checkContains("graph json has triples array", json, "\"triples\":[");
        SelfTest.checkContains("graph json has ticker triple", json, "\"r\":\"ticker\"");
        SelfTest.check("no duplicate triples", countOccurrences(json, "\"s\":\"Stocks/NVDA\",\"r\":\"tagged\",\"o\":\"stock\"") == 1);
        SelfTest.check("excluded corpus absent from graph", !json.contains("Quran 001")
                && !json.contains("10 \uD83D\uDCC6 Quran"));

        // Loader: comments/blanks skipped, entries kept verbatim.
        Path exclFile = tmp.resolve("reference_only_dirs.txt");
        Files.writeString(exclFile, "# comment\n\nworkspace/atlas/10 \uD83D\uDCC6 Quran\nworkspace/youtube/transcripts\n",
                StandardCharsets.UTF_8);
        List<String> loaded = loadReferenceOnlyDirs(exclFile);
        SelfTest.checkEq("loader keeps 2 entries", 2, loaded.size());
        SelfTest.checkEq("loader verbatim", "workspace/atlas/10 \uD83D\uDCC6 Quran", loaded.get(0));
        Set<String> tops = referenceOnlyTopDirs(loaded);
        SelfTest.check("topdir strips atlas prefix", tops.contains("10 \uD83D\uDCC6 Quran"));
        SelfTest.check("non-atlas entries skipped by vault tools", !tops.contains("workspace"));
        SelfTest.checkEq("missing file fails open", 0, loadReferenceOnlyDirs(tmp.resolve("nope.txt")).size());

        SelfTest.finish();
    }

    static int countOccurrences(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
