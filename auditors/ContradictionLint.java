import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;

/// Contradiction Lint — Rahik's auditor fleet, auditor #13.
///
/// ONE TASK: find candidate contradictions between vault notes —
/// status opposition (live vs paused, dropped vs followed), number
/// mismatches on the same metric in prose, stale claims a newer dated
/// note supersedes, and orphan pages with no inbound wikilinks.
///
/// READ-ONLY: zero write paths in code — no note edits, no git operations.
/// Findings end in structured suggestions
/// (severity | location | expected vs actual | evidence | suggestion);
/// acting on them needs Rahik's word.
///
/// v1 is deterministic (no LLM calls). Every hit is a CANDIDATE, not a
/// verdict — prose nuance can trip the patterns, so each one needs a
/// human read before anything changes. Named-decision extraction beyond
/// approve/reject-style status verbs is deferred to v2.
public class ContradictionLint {

    static final int FINDING_CAP = 20;
    static final int ORPHAN_CAP = 10;
    static final int MIN_SENTENCE = 20;
    static final int MAX_SENTENCE = 400;

    /// status word -> its opposite (both directions present)
    static final Map<String, String> ANTONYM = new LinkedHashMap<>();
    static {
        String[][] pairs = {
            {"live", "paused"}, {"dropped", "followed"},
            {"enabled", "disabled"}, {"active", "archived"},
            {"approved", "rejected"}, {"open", "closed"},
            {"current", "stale"}, {"current", "deprecated"},
        };
        for (String[] p : pairs) {
            ANTONYM.put(p[0], p[1]);
            ANTONYM.put(p[1], p[0]);
        }
    }
    static final Set<String> STATUS_WORDS = ANTONYM.keySet();

    /// status words that also work as main verbs ("Rahik dropped Tina")
    static final Set<String> MAIN_VERB_STATUS = Set.of(
        "approved", "rejected", "dropped", "followed",
        "paused", "enabled", "disabled", "archived");

    static final Pattern STATUS_VERB = Pattern.compile(
        "\\b(is|are|was|were|has been|have been|remains|remain|stays|stay)\\b"
        + "[\\s\\S]{0,60}?\\b(" + String.join("|", STATUS_WORDS) + ")\\b",
        Pattern.CASE_INSENSITIVE);
    static final Pattern MAIN_VERB = Pattern.compile(
        "\\b(" + String.join("|", MAIN_VERB_STATUS) + ")\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern NUM = Pattern.compile(
        "\\$\\s?([\\d,]+(?:\\.\\d+)?)|(\\d[\\d,]*(?:\\.\\d+)?)\\s*%");
    static final Pattern DATE = Pattern.compile(
        "\\b(20\\d\\d)-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])\\b");
    static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|#]+)(?:#[^\\]]*)?(?:\\|[^\\]]*)?\\]\\]");
    static final Pattern NEG = Pattern.compile(
        "\\b(not|never|no longer|isn't|aren't|wasn't|weren't|didn't|doesn't|don't)\\b",
        Pattern.CASE_INSENSITIVE);
    /// v2: metric keywords with real word boundaries — "dcf" must not match
    /// file paths like dcf_csv/ (the _ kills \b) or path segments like a/dcf/b
    static final Pattern METRIC_PATTERN = Pattern.compile(
        "\\b(fair value|intrinsic value|price target|target price|"
        + "margin of safety|buy zone|upside|worth|dcf)\\b",
        Pattern.CASE_INSENSITIVE);
    /// v2: "followed by X" is temporal/sequential ("followed by the scattering"),
    /// not a status claim about the entity
    static final Pattern FOLLOWED_BY = Pattern.compile("^\\s+by\\b", Pattern.CASE_INSENSITIVE);

    record NoteEntry(String path, String title, List<String> aliases) {}
    record Claim(String note, String entity, String sentence, String kind,
                 String detail, boolean negated, double value, String unit) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            runSelfTest();
            return;
        }
        Path home = Paths.get(home());
        Path atlas = home.resolve("workspace/atlas");
        Path catalog = home.resolve("workspace/atlas-index/vault-index.jsonl");
        List<String> refEntries = loadReferenceOnlyDirs(home);
        List<String> findings = runAudit(home, atlas, catalog, refEntries);
        if (findings.isEmpty()) {
            System.out.println("CONTRADICTION LINT: CLEAN — no candidate contradictions.");
        } else {
            System.out.println("CONTRADICTION LINT: " + findings.size()
                + " candidate finding(s) — read-only, nothing changed. Each needs a human read.");
            for (String f : findings) System.out.println("\n---\n" + f);
        }
    }

    /** Full pipeline; returns structured findings. Pure function of its inputs. */
    static List<String> runAudit(Path home, Path atlasRoot, Path catalogPath,
                                 List<String> refEntries) throws IOException {
        List<NoteEntry> catalog = loadCatalog(catalogPath);
        Map<String, String> nameToTitle = new LinkedHashMap<>();
        Map<String, String> titleToPath = new LinkedHashMap<>();
        for (NoteEntry n : catalog) {
            titleToPath.putIfAbsent(n.title(), n.path());
            nameToTitle.putIfAbsent(n.title().toLowerCase(), n.title());
            for (String a : n.aliases()) nameToTitle.putIfAbsent(a.toLowerCase(), n.title());
        }
        Pattern mention = buildMentionPattern(nameToTitle.keySet());
        Map<String, String> bodies = loadBodies(home, atlasRoot, catalog, refEntries);
        Map<String, String> noteDates = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bodies.entrySet()) noteDates.put(e.getKey(), noteDate(e.getValue()));
        List<Claim> claims = extractClaims(bodies, nameToTitle, mention);
        List<String> out = findOppositions(claims, noteDates);
        if (out.size() < FINDING_CAP) {
            out.addAll(findOrphans(bodies, catalog, nameToTitle, titleToPath,
                FINDING_CAP - out.size()));
        }
        return out.stream().limit(FINDING_CAP).toList();
    }

    // ---- catalog ----

    static List<NoteEntry> loadCatalog(Path catalogPath) throws IOException {
        List<NoteEntry> out = new ArrayList<>();
        if (!Files.isRegularFile(catalogPath)) return out;
        for (String line : Files.readAllLines(catalogPath, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            JSONObject o = new JSONObject(line);
            List<String> aliases = new ArrayList<>();
            JSONArray ja = o.optJSONArray("aliases");
            if (ja != null) for (int i = 0; i < ja.length(); i++) aliases.add(ja.getString(i));
            out.add(new NoteEntry(o.getString("path"), o.getString("title"), aliases));
        }
        return out;
    }

    static Pattern buildMentionPattern(Set<String> names) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder sb = new StringBuilder("(?<![\\p{L}\\p{N}_])(?:");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(sorted.get(i)));
        }
        sb.append(")(?![\\p{L}\\p{N}_])");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    // ---- bodies ----

    static Map<String, String> loadBodies(Path home, Path atlasRoot, List<NoteEntry> catalog,
                                         List<String> refEntries) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> tops = referenceOnlyTopDirs(home, atlasRoot, refEntries);
        for (NoteEntry n : catalog) {
            Path p = atlasRoot.resolve(n.path());
            if (!Files.isRegularFile(p) || isExcludedDir(atlasRoot, p, tops)) continue;
            out.put(n.path(), stripFrontmatter(Files.readString(p, StandardCharsets.UTF_8)));
        }
        return out;
    }

    static String stripFrontmatter(String text) {
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) text = text.substring(end + 4);
        }
        return text;
    }

    /** Latest explicit YYYY-MM-DD in the note text, or null. Never file mtime. */
    static String noteDate(String body) {
        String best = null;
        Matcher m = DATE.matcher(body);
        while (m.find()) {
            if (best == null || m.group(0).compareTo(best) > 0) best = m.group(0);
        }
        return best;
    }

    static List<String> sentences(String body) {
        List<String> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            String t = line.trim()
                .replaceAll("!\\[\\[[^\\]]*\\]\\]", " ")
                .replaceAll("[*_`>]", " ");
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("|") || t.startsWith("```")) continue;
            for (String s : t.split("(?<=[.!?])\\s+")) {
                s = s.replaceAll("\\s+", " ").trim();
                if (s.length() >= MIN_SENTENCE) {
                    out.add(s.length() > MAX_SENTENCE ? s.substring(0, MAX_SENTENCE) : s);
                }
            }
        }
        return out;
    }

    // ---- claim extraction ----

    static List<Claim> extractClaims(Map<String, String> bodies,
                                    Map<String, String> nameToTitle, Pattern mention) {
        List<Claim> claims = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : bodies.entrySet()) {
            String note = e.getKey();
            for (String s : sentences(e.getValue())) {
                Set<String> entities = new LinkedHashSet<>();
                List<String> entTitles = new ArrayList<>();
                List<Integer> entPos = new ArrayList<>();
                Matcher mm = mention.matcher(s);
                while (mm.find()) {
                    String t = nameToTitle.get(mm.group().toLowerCase());
                    if (t != null && entities.add(t)) { entTitles.add(t); entPos.add(mm.start()); }
                }
                if (entities.isEmpty()) continue;
                String lower = s.toLowerCase();
                // status claims: "is/are/was ... <status>" or main-verb use
                Matcher sm = STATUS_VERB.matcher(s);
                while (sm.find()) {
                    String word = sm.group(2).toLowerCase();
                    if (word.equals("followed")
                        && FOLLOWED_BY.matcher(s.substring(Math.min(sm.end(2), s.length()))).find())
                        continue;
                    boolean neg = negatedBefore(lower, lower.indexOf(word, sm.start(2)));
                    addClaim(claims, seen, note, entities, s, "STATUS", word, neg, 0, "");
                }
                Matcher vm = MAIN_VERB.matcher(s);
                while (vm.find()) {
                    String word = vm.group(1).toLowerCase();
                    if (word.equals("followed")
                        && FOLLOWED_BY.matcher(s.substring(Math.min(vm.end(1), s.length()))).find())
                        continue;
                    boolean neg = negatedBefore(lower, vm.start(1));
                    addClaim(claims, seen, note, entities, s, "STATUS", word, neg, 0, "");
                }
                // number claims: metric keyword + $ or % figure, number nearest the metric,
                // attached to the entity nearest the number (v2: not every entity in the sentence)
                String metric = null;
                int metricIdx = -1;
                Matcher metm = METRIC_PATTERN.matcher(s);
                while (metm.find()) {
                    int a = metm.start(), b = metm.end();
                    boolean pathy = (a > 0 && (s.charAt(a - 1) == '/' || s.charAt(a - 1) == '\\'))
                        || (b < s.length() && (s.charAt(b) == '/' || s.charAt(b) == '\\'));
                    if (!pathy) { metric = metm.group(1).toLowerCase(); metricIdx = a; break; }
                }
                if (metric != null) {
                    Matcher nm = NUM.matcher(s);
                    double bestV = 0;
                    String bestU = "";
                    int bestD = Integer.MAX_VALUE;
                    int bestNS = -1;
                    boolean found = false;
                    while (nm.find()) {
                        String raw = nm.group(1) != null ? nm.group(1) : nm.group(2);
                        double v = Double.parseDouble(raw.replace(",", ""));
                        String u = nm.group(1) != null ? "$" : "%";
                        int d = Math.abs(nm.start() - metricIdx);
                        if (d < bestD) { bestD = d; bestV = v; bestU = u; bestNS = nm.start(); found = true; }
                    }
                    if (found) {
                        String bestEnt = null;
                        int bestED = Integer.MAX_VALUE;
                        for (int k = 0; k < entTitles.size(); k++) {
                            int d = Math.abs(entPos.get(k) - bestNS);
                            if (d < bestED) { bestED = d; bestEnt = entTitles.get(k); }
                        }
                        if (bestEnt != null) addClaim(claims, seen, note, Set.of(bestEnt), s,
                            "NUMBER", metric, false, bestV, bestU);
                    }
                }
            }
        }
        return claims;
    }

    static boolean negatedBefore(String lower, int idx) {
        if (idx < 0) return false;
        String before = lower.substring(Math.max(0, idx - 40), idx);
        return NEG.matcher(before).find();
    }

    static void addClaim(List<Claim> claims, Set<String> seen, String note, Set<String> entities,
                        String sentence, String kind, String detail, boolean negated,
                        double value, String unit) {
        for (String ent : entities) {
            String key = note + "|" + ent + "|" + sentence + "|" + kind + "|" + detail + "|" + negated;
            if (seen.add(key)) claims.add(new Claim(note, ent, sentence, kind, detail, negated, value, unit));
        }
    }

    // ---- opposition ----

    static List<String> findOppositions(List<Claim> claims, Map<String, String> noteDates) {
        Map<String, List<Claim>> byEntity = new TreeMap<>();
        for (Claim c : claims) byEntity.computeIfAbsent(c.entity(), k -> new ArrayList<>()).add(c);
        List<String> out = new ArrayList<>();
        Set<String> pairSeen = new LinkedHashSet<>();
        for (Map.Entry<String, List<Claim>> e : byEntity.entrySet()) {
            List<Claim> cl = e.getValue();
            for (int i = 0; i < cl.size(); i++) {
                for (int j = i + 1; j < cl.size(); j++) {
                    Claim a = cl.get(i), b = cl.get(j);
                    if (a.note().equals(b.note()) || !a.kind().equals(b.kind())) continue;
                    String key = e.getKey() + "|" + a.kind() + "|"
                        + (a.note().compareTo(b.note()) < 0 ? a.note() + "|" + b.note() : b.note() + "|" + a.note());
                    if (!pairSeen.add(key)) continue;
                    String finding = oppose(e.getKey(), a, b, noteDates);
                    if (finding != null) out.add(finding);
                }
            }
        }
        out.sort((x, y) -> {
            int sx = x.startsWith("[MED]") ? 0 : 1, sy = y.startsWith("[MED]") ? 0 : 1;
            return sx != sy ? sx - sy : x.compareTo(y);
        });
        return out;
    }

    /** Null when the pair does not oppose; otherwise a structured finding. */
    static String oppose(String entity, Claim a, Claim b, Map<String, String> noteDates) {
        String nA = a.note(), nB = b.note();
        if (a.kind().equals("STATUS")) {
            boolean clash = a.detail().equals(b.detail()) && a.negated() != b.negated()
                || ANTONYM.getOrDefault(a.detail(), "").equals(b.detail());
            if (!clash) return null;
            String dA = noteDates.get(nA), dB = noteDates.get(nB);
            String stale = staleNote(nA, dA, nB, dB);
            return "[MED] status opposition | " + entity + " — " + nA + " <> " + nB + "\n"
                + "  expected: one current status per entity\n"
                + "  actual: \"" + a.detail() + "\"" + (a.negated() ? " (negated)" : "")
                + " vs \"" + b.detail() + "\"" + (b.negated() ? " (negated)" : "") + "\n"
                + "  evidence: \"" + nA + "\": \"" + a.sentence() + "\"\n"
                + "           \"" + nB + "\": \"" + b.sentence() + "\""
                + (stale.isEmpty() ? "" : "\n  " + stale) + "\n"
                + "  suggestion: read both notes and decide which status is current; annotate the stale one — say the word and it gets proposed properly.";
        }
        // NUMBER
        if (!a.detail().equals(b.detail()) || !a.unit().equals(b.unit())) return null;
        if (Math.abs(a.value() - b.value()) <= 0.005) return null;
        String dA = noteDates.get(nA), dB = noteDates.get(nB);
        String stale = staleNote(nA, dA, nB, dB);
        return "[LOW] number mismatch | " + entity + " — " + nA + " <> " + nB + "\n"
            + "  expected: one figure per metric in prose\n"
            + "  actual: " + a.detail() + " " + a.unit() + fmtNum(a.value())
            + " vs " + b.unit() + fmtNum(b.value()) + " (same metric, different numbers)\n"
            + "  evidence: \"" + nA + "\": \"" + a.sentence() + "\"\n"
            + "           \"" + nB + "\": \"" + b.sentence() + "\""
            + (stale.isEmpty() ? "" : "\n  " + stale) + "\n"
            + "  suggestion: verify whether this is a genuine conflict or an update over time; if the newer note supersedes, annotate the older one — say the word and it gets proposed properly.";
    }

    /** Stale line only when BOTH notes carry explicit dates that differ. Never mtime. */
    static String staleNote(String nA, String dA, String nB, String dB) {
        if (dA == null || dB == null || dA.equals(dB)) return "";
        String older = dA.compareTo(dB) < 0 ? nA + " (" + dA + ")" : nB + " (" + dB + ")";
        String newer = dA.compareTo(dB) < 0 ? nB + " (" + dB + ")" : nA + " (" + dA + ")";
        return "stale candidate: \"" + older + "\" looks superseded by \"" + newer
            + "\" — recency from explicit note dates, never file mtime.";
    }

    static String fmtNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ---- orphans ----

    static List<String> findOrphans(Map<String, String> bodies, List<NoteEntry> catalog,
                                    Map<String, String> nameToTitle, Map<String, String> titleToPath,
                                    int cap) {
        Map<String, Set<String>> inbound = new LinkedHashMap<>();
        for (String p : bodies.keySet()) inbound.put(p, new LinkedHashSet<>());
        for (Map.Entry<String, String> e : bodies.entrySet()) {
            Matcher m = WIKILINK.matcher(e.getValue());
            while (m.find()) {
                String target = resolveTarget(m.group(1).trim(), catalog, nameToTitle, titleToPath);
                if (target != null && !target.equals(e.getKey()) && inbound.containsKey(target)) {
                    inbound.get(target).add(e.getKey());
                }
            }
        }
        List<String> out = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : inbound.entrySet()) {
            if (e.getValue().isEmpty()) orphans.add(e.getKey());
        }
        orphans.sort(String::compareTo);
        for (String o : orphans.stream().limit(Math.min(cap, ORPHAN_CAP)).toList()) {
            out.add("[LOW] orphan page (no inbound wikilinks) | " + o + "\n"
                + "  expected: every note reachable from the link graph\n"
                + "  actual: zero inbound [[wikilinks]] from any other scanned note\n"
                + "  evidence: inbound-link scan over " + bodies.size() + " notes\n"
                + "  suggestion: link it from a relevant index/dashboard note, or leave it if intentionally standalone — say the word and it gets proposed properly.");
        }
        return out;
    }

    static String resolveTarget(String raw, List<NoteEntry> catalog,
                                Map<String, String> nameToTitle, Map<String, String> titleToPath) {
        if (raw.isEmpty()) return null;
        String low = raw.toLowerCase();
        if (nameToTitle.containsKey(low)) return titleToPath.get(nameToTitle.get(low));
        for (NoteEntry n : catalog) {
            String p = n.path().replace('\\', '/');
            if (p.equalsIgnoreCase(raw) || p.toLowerCase().endsWith("/" + low)) return n.path();
        }
        return null;
    }

    // ---- plumbing (same exclusion set as DuplicateContentAudit) ----

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) throw new IllegalStateException("HOME not set");
        return h;
    }

    /**
     * Reference corpora excluded from scanning (standing rule 2026-10-07):
     * scripture kept in the vault is for reading; mining it for claims
     * would waste compute and risk false-positive contradictions.
     */
    static List<String> loadReferenceOnlyDirs(Path home) {
        Path f = home.resolve("workspace/atlas-scripts/reference_only_dirs.txt");
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
            return out;
        } catch (IOException e) {
            return List.of(); // fail-open
        }
    }

    /** Top-level dir names under root excluded by the workspace-relative entries. */
    static Set<String> referenceOnlyTopDirs(Path home, Path root, List<String> entries) {
        String rootRel = home.relativize(root).toString().replace('\\', '/');
        Set<String> tops = new LinkedHashSet<>();
        for (String e : entries) {
            if (e.startsWith(rootRel + "/")) {
                String p = e.substring(rootRel.length() + 1);
                int i = p.indexOf('/');
                tops.add(i < 0 ? p : p.substring(0, i));
            }
        }
        return tops;
    }

    static boolean isExcludedDir(Path root, Path p, Set<String> tops) {
        Path rel = root.relativize(p);
        return rel.getNameCount() > 0 && tops.contains(rel.getName(0).toString());
    }

    // ---- self-test ----

    static void runSelfTest() throws Exception {
        Path tmp = Files.createTempDirectory("contradiction-test");
        Path home = tmp;
        Path atlas = tmp.resolve("workspace/atlas");
        Files.createDirectories(atlas);
        Path idxDir = tmp.resolve("atlas-index");
        Files.createDirectories(idxDir);

        Files.writeString(atlas.resolve("a.md"),
            "# A\n\nThe Foo Service is live as of 2026-10-01.\n\nThe Foo Service fair value is $10 per share.\n");
        Files.writeString(atlas.resolve("b.md"),
            "# B\n\nUpdate 2026-10-09: the Foo Service is paused until further notice.\n");
        Files.writeString(atlas.resolve("c.md"),
            "# C\n\nAnalyst note: the Foo Service fair value is $20 per share.\n");
        Files.writeString(atlas.resolve("e.md"),
            "# Bar Widget\n\nA standalone page about the Bar Widget with nothing linking to it.\n");
        Files.writeString(atlas.resolve("f.md"),
            "# Linker\n\nSee [[Foo Service]] for the main writeup.\n");
        // clean pair: agreement must stay quiet
        Files.writeString(atlas.resolve("h.md"), "# H\n\nThe Baz Tool is enabled.\n");
        Files.writeString(atlas.resolve("i.md"), "# I\n\nThe Baz Tool is enabled and working well.\n");
        // planted contradiction inside the excluded corpus — must be ignored
        Path excl = atlas.resolve("10 \uD83D\uDCD6 Quran");
        Files.createDirectories(excl);
        Files.writeString(excl.resolve("q.md"), "# Q\n\nThe Foo Service is disabled forever.\n");

        StringBuilder cat = new StringBuilder();
        cat.append("{\"path\":\"a.md\",\"title\":\"Foo Service\",\"aliases\":[\"Foo\"],\"summary\":\"s\"}\n");
        cat.append("{\"path\":\"b.md\",\"title\":\"Foo Status B\",\"aliases\":[],\"summary\":\"s\"}\n");
        cat.append("{\"path\":\"c.md\",\"title\":\"Foo Analyst\",\"aliases\":[],\"summary\":\"s\"}\n");
        cat.append("{\"path\":\"e.md\",\"title\":\"Bar Widget\",\"aliases\":[],\"summary\":\"s\"}\n");
        cat.append("{\"path\":\"f.md\",\"title\":\"Linker\",\"aliases\":[],\"summary\":\"s\"}\n");
        cat.append("{\"path\":\"h.md\",\"title\":\"Baz Tool\",\"aliases\":[],\"summary\":\"s\"}\n");
        cat.append("{\"path\":\"i.md\",\"title\":\"Baz Notes\",\"aliases\":[],\"summary\":\"s\"}\n");
        Files.writeString(idxDir.resolve("vault-index.jsonl"), cat.toString(), StandardCharsets.UTF_8);

        List<String> entries = List.of("workspace/atlas/10 \uD83D\uDCD6 Quran");
        List<String> findings = runAudit(home, atlas, idxDir.resolve("vault-index.jsonl"), entries);

        check(findings.stream().anyMatch(f -> f.contains("a.md") && f.contains("b.md")
                && f.contains("live") && f.contains("paused")),
            "must flag live-vs-paused opposition a.md <> b.md, got: " + findings);
        check(findings.stream().anyMatch(f -> f.toLowerCase().contains("stale candidate")
                && f.contains("a.md") && f.contains("2026-10-01")),
            "must mark older dated note stale (a.md 2026-10-01 superseded), got: " + findings);
        check(findings.stream().anyMatch(f -> f.contains("a.md") && f.contains("c.md")
                && f.contains("$10") && f.contains("$20")),
            "must flag fair-value number mismatch a.md <> c.md, got: " + findings);
        check(findings.stream().anyMatch(f -> f.contains("orphan") && f.contains("e.md")),
            "must list orphan e.md (Bar Widget), got: " + findings);
        check(findings.stream().noneMatch(f -> f.contains("a.md") && f.contains("orphan")),
            "a.md has inbound [[Foo Service]] link from f.md — must not be orphan");
        check(findings.stream().noneMatch(f -> f.contains("Quran") || f.contains("q.md")),
            "excluded corpus must not appear in findings");
        check(findings.stream().noneMatch(f -> f.contains("Baz")),
            "agreeing clean pair h.md/i.md must stay quiet");

        // prove-it-can-fail direction: a corpus with no opposition at all stays clean
        Path tmp2 = Files.createTempDirectory("contradiction-clean");
        Path a2 = tmp2.resolve("workspace/atlas");
        Files.createDirectories(a2);
        Files.writeString(a2.resolve("x.md"), "# X\n\nThe Zed Service is live.\n");
        Files.writeString(a2.resolve("y.md"), "# Y\n\nThe Zed Service is live and healthy.\n");
        Path idx2 = tmp2.resolve("atlas-index");
        Files.createDirectories(idx2);
        Files.writeString(idx2.resolve("vault-index.jsonl"),
            "{\"path\":\"x.md\",\"title\":\"Zed Service\",\"aliases\":[],\"summary\":\"s\"}\n"
            + "{\"path\":\"y.md\",\"title\":\"Zed Notes\",\"aliases\":[],\"summary\":\"s\"}\n",
            StandardCharsets.UTF_8);
        List<String> clean = runAudit(tmp2, a2, idx2.resolve("vault-index.jsonl"), List.of());
        List<String> nonOrphan = clean.stream().filter(f -> !f.contains("orphan")).toList();
        check(nonOrphan.isEmpty(), "clean agreeing corpus must produce no opposition findings, got: " + nonOrphan);

        System.out.println("ContradictionLint self-test: ALL GREEN ("
            + findings.size() + " findings on planted corpus, clean corpus quiet)");
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }
}
