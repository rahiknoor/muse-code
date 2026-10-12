// FilingDiff.java
// Quarter-over-quarter SEC filing change detection for the filing watch.
//
// Given a newly-downloaded filing (.txt, tag-stripped) and the company's prior
// filing on disk, produces a short human-readable diff report:
//   1. MD&A narrative diff   (10-K Item 7 / 10-Q Item 2 — the same disclosure)
//   2. Risk-factors diff     (Item 1A)
//   3. XBRL facts delta table for the DCF-relevant concepts, read from the
//      on-disk companyfacts snapshot — no network, nothing refetched.
//
// Section extraction follows the dgunning/edgartools pattern: locate the ITEM
// header line, read until the next ITEM header line.
//
// Usage:
//   java FilingDiff.java --company amd \
//       --new 10-Q_2026-08-05_AMD.txt --prior 10-K_2026-02-04_AMD.txt \
//       [--out ~/workspace/sec_filings/amd/filingdiff_10-Q_2026-08-05.txt]
//   java FilingDiff.java --self-test
//     (self-test needs the shared helpers on the class path:
//      java --class-path ~/workspace/java-shared/classes FilingDiff.java --self-test)
//
// WATCH INTEGRATION (no new cron — rides sec-filing-watch-dcf-updates):
//   Insert as step 6a2, right after the new filing's .htm is downloaded and the
//   .txt stripped (6a), before the DCF-input extraction (6c):
//     6a2. Run FilingDiff for the company:
//            java --class-path ~/workspace/java-shared/classes \
//              ~/workspace/goals/quarterly-sec-filing-watch-with-dcf-updates/hidden_files/FilingDiff.java \
//              --company <dir> --new <new .txt> --prior <previous .txt on disk> \
//              --out ~/workspace/sec_filings/<dir>/filingdiff_<new>.txt
//          Append the report text to the step-6h Stock Research side-chat message
//          under a "What changed" heading. If a section is missing or an XBRL
//          concept untagged, the tool says so — never invents.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FilingDiff {

    // ------------------------------------------------------------------
    // Section header patterns (dgunning/edgartools-style: ITEM line -> next ITEM line).
    // The .txt files are tag-stripped, so headers look like
    //   "| ITEM 2. | MANAGEMENT'S DISCUSSION AND ANALYSIS ..."  (AMD)
    //   "Item 2. Management's Discussion and Analysis ..."      (NVDA)

    static final Pattern MDA_START_10K = Pattern.compile(
            "^(?:\\|\\s*)?ITEM\\s+7\\s*\\.?\\s*\\|?\\s*MANAGEMENT", Pattern.CASE_INSENSITIVE);
    static final Pattern MDA_START_10Q = Pattern.compile(
            "^(?:\\|\\s*)?ITEM\\s+2\\s*\\.?\\s*\\|?\\s*MANAGEMENT", Pattern.CASE_INSENSITIVE);
    static final Pattern MDA_START_20F = Pattern.compile(
            "^(?:\\|\\s*)?ITEM\\s+5\\s*\\.?\\s*\\|?\\s*OPERATING AND FINANCIAL",
            Pattern.CASE_INSENSITIVE);
    static final Pattern RISK_START = Pattern.compile(
            "^(?:\\|\\s*)?ITEM\\s+1A\\s*\\.?\\s*\\|?\\s*RISK", Pattern.CASE_INSENSITIVE);
    static final Pattern ITEM_END = Pattern.compile(
            "^(?:\\|\\s*)?ITEM\\s+\\d+[A-Z]?\\s*\\.", Pattern.CASE_INSENSITIVE);
    static final Pattern FILING_NAME = Pattern.compile(
            "^(10-K|10-Q|20-F|6-K)_(\\d{4}-\\d{2}-\\d{2})_");

    static final int MAX_PARAS_FOR_LCS = 4000;
    static final int SHOW_NEW = 4, SHOW_REMOVED = 4, SHOW_REWORDED = 3;
    static final int PARA_PREVIEW = 600;

    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        if (a.containsKey("self-test")) {
            selfTest();
            return;
        }
        String company = required(a, "company");
        String newFile = required(a, "new");
        String priorFile = required(a, "prior");

        Path dir = Path.of(home(), "workspace", "sec_filings", company);
        Path newPath = dir.resolve(newFile);
        Path priorPath = dir.resolve(priorFile);
        if (!Files.isRegularFile(newPath)) die("new filing not found: " + newPath);
        if (!Files.isRegularFile(priorPath)) die("prior filing not found: " + priorPath);

        FilingMeta newMeta = parseFilingName(newFile);
        FilingMeta priorMeta = parseFilingName(priorFile);

        List<String> newLines = Files.readAllLines(newPath, StandardCharsets.UTF_8);
        List<String> priorLines = Files.readAllLines(priorPath, StandardCharsets.UTF_8);

        StringBuilder out = new StringBuilder();
        out.append("FilingDiff — ").append(company.toUpperCase()).append('\n');
        out.append("  new:   ").append(newMeta.form).append(" filed ").append(newMeta.filed)
                .append(" (").append(newFile).append(")\n");
        out.append("  prior: ").append(priorMeta.form).append(" filed ").append(priorMeta.filed)
                .append(" (").append(priorFile).append(")\n");

        // [1] MD&A
        out.append("\n[1] MD&A");
        Section newMda = extractMda(newLines, newMeta.form);
        Section priorMda = extractMda(priorLines, priorMeta.form);
        out.append(" — ").append(label(newMeta.form, "Item " + mdaItem(newMeta.form)))
                .append(" vs ").append(label(priorMeta.form, "Item " + mdaItem(priorMeta.form)))
                .append('\n');
        appendSectionDiff(out, newMda, priorMda);

        // [2] Risk factors
        out.append("\n[2] Risk factors — Item 1A vs Item 1A\n");
        Section newRisk = extractSection(newLines, RISK_START);
        Section priorRisk = extractSection(priorLines, RISK_START);
        appendSectionDiff(out, newRisk, priorRisk);

        // [3] XBRL delta (new filing vs previous same-form filing, from companyfacts)
        out.append("\n[3] XBRL delta — DCF fields\n");
        out.append(xbrlDelta(dir, newMeta));

        String report = out.toString();
        if (a.containsKey("out")) {
            Path outPath = Path.of(a.get("out"));
            Files.writeString(outPath, report, StandardCharsets.UTF_8);
            System.err.println("wrote " + outPath);
        }
        System.out.print(report);
    }

    // ------------------------------------------------------------------
    // Section extraction

    static class Section {
        boolean found;
        String headerLine = "";
        List<String> paras = new ArrayList<>();
        int startLine = -1, endLine = -1;
    }

    static String mdaItem(String form) {
        return switch (form) {
            case "10-K" -> "7";
            case "20-F" -> "5";
            default -> "2"; // 10-Q
        };
    }

    static String label(String form, String item) {
        return item + " (" + form + ")";
    }

    static Section extractMda(List<String> lines, String form) {
        Pattern start = switch (form) {
            case "10-K" -> MDA_START_10K;
            case "20-F" -> MDA_START_20F;
            case "6-K" -> null; // 6-Ks carry no MD&A
            default -> MDA_START_10Q;
        };
        if (start == null) return notFound();
        return extractSection(lines, start);
    }

    static Section notFound() {
        return new Section(); // found == false
    }

    static Section extractSection(List<String> lines, Pattern start) {
        Section s = new Section();
        for (int i = 0; i < lines.size(); i++) {
            if (start.matcher(lines.get(i)).find()) {
                s.startLine = i;
                s.headerLine = lines.get(i).trim();
                break;
            }
        }
        if (s.startLine < 0) return s;
        int end = lines.size();
        for (int i = s.startLine + 1; i < lines.size(); i++) {
            if (ITEM_END.matcher(lines.get(i)).find()) {
                end = i;
                break;
            }
        }
        s.endLine = end;
        s.found = true;
        // Paragraphs: the .txt is tag-stripped, so layout varies — AMD uses blank
        // lines between paragraphs, NVDA wraps at ~100 chars with no blanks.
        // Reconstruct paragraphs from wrapped lines (dgunning/edgartools-style
        // text blocks): a line not ending in terminal punctuation continues the
        // paragraph; headings, bullets and table rows always break.
        s.paras = splitParas(lines, s.startLine + 1, end);
        return s;
    }

    static final Pattern BULLET = Pattern.compile(
            "^(\\?|[\\u2022\\u25AA\\u25CF\\u25CB\\-\\u2013\\u2014*])\\s+|^\\([a-z0-9]+\\)\\s+|^\\d+[.)]\\s+");
    static final Pattern TERMINAL = Pattern.compile(".*[.?!:;]['\"\\u201D\\u2019)]?$");

    static List<String> splitParas(List<String> lines, int from, int to) {
        List<String> paras = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String prev = null;
        for (int i = from; i < to; i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty()) {
                flushPara(cur, paras);
                prev = null;
                continue;
            }
            if (t.startsWith("|")) {
                // Table row: standalone paragraph, never glued to prose.
                flushPara(cur, paras);
                paras.add(t);
                prev = null;
                continue;
            }
            if (cur.length() > 0 && prev != null) {
                boolean boundary = TERMINAL.matcher(prev).matches()
                        || BULLET.matcher(t).find()
                        || (prev.length() < 50 && t.length() >= 50)   // heading -> body
                        || (prev.length() >= 80 && t.length() < 50);  // body -> heading
                if (boundary) flushPara(cur, paras);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(t);
            prev = t;
        }
        flushPara(cur, paras);
        return paras;
    }

    static void flushPara(StringBuilder cur, List<String> paras) {
        String p = cur.toString().replaceAll("\\s+", " ").trim();
        cur.setLength(0);
        if (p.isEmpty()) return;
        // Page-break artifacts ("Table of Contents" / "Table of Conten t s") land
        // inside sections — standalone or glued to the next paragraph — and would
        // otherwise swamp the diff as fake changes. A paragraph starting with
        // these words mid-section is virtually always such an artifact.
        p = p.replaceFirst("(?i)^table\\s+of\\s+conten\\s*t\\s*s\\b\\s*", "").trim();
        if (p.isEmpty()) return;
        paras.add(p);
    }

    // ------------------------------------------------------------------
    // Paragraph diff (LCS + reword pairing)

    static class DiffResult {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String[]> reworded = new ArrayList<>(); // [old, new]
        int matched;
    }

    static DiffResult diffParas(List<String> oldP, List<String> newP) {
        DiffResult d = new DiffResult();
        int n = oldP.size(), m = newP.size();
        if (n > MAX_PARAS_FOR_LCS || m > MAX_PARAS_FOR_LCS) {
            // Fallback: set-based, no pairing.
            Set<String> oldSet = new HashSet<>(oldP), newSet = new HashSet<>(newP);
            for (String p : newP) if (!oldSet.contains(p)) d.added.add(p);
            for (String p : oldP) if (!newSet.contains(p)) d.removed.add(p);
            d.matched = n - d.removed.size();
            return d;
        }
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                dp[i][j] = oldP.get(i).equals(newP.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (oldP.get(i).equals(newP.get(j))) { d.matched++; i++; j++; }
            else if (dp[i + 1][j] >= dp[i][j + 1]) d.removed.add(oldP.get(i++));
            else d.added.add(newP.get(j++));
        }
        while (i < n) d.removed.add(oldP.get(i++));
        while (j < m) d.added.add(newP.get(j++));

        // Pair removed<->added as "reworded" when word overlap is high (greedy).
        boolean[] used = new boolean[d.added.size()];
        List<String> stillRemoved = new ArrayList<>();
        for (String r : d.removed) {
            int best = -1;
            double bestScore = 0.5;
            for (int k = 0; k < d.added.size(); k++) {
                if (used[k]) continue;
                double s = wordOverlap(r, d.added.get(k));
                if (s > bestScore) { bestScore = s; best = k; }
            }
            if (best >= 0) {
                used[best] = true;
                d.reworded.add(new String[]{r, d.added.get(best)});
            } else {
                stillRemoved.add(r);
            }
        }
        d.removed = stillRemoved;
        List<String> stillAdded = new ArrayList<>();
        for (int k = 0; k < d.added.size(); k++) if (!used[k]) stillAdded.add(d.added.get(k));
        d.added = stillAdded;
        return d;
    }

    static double wordOverlap(String a, String b) {
        Set<String> wa = words(a), wb = words(b);
        if (wa.isEmpty() || wb.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(wa);
        inter.retainAll(wb);
        return 2.0 * inter.size() / (wa.size() + wb.size());
    }

    static Set<String> words(String s) {
        Set<String> w = new HashSet<>();
        for (String t : s.toLowerCase().split("[^a-z0-9]+"))
            if (t.length() > 2) w.add(t);
        return w;
    }

    static int wordCount(List<String> paras) {
        int c = 0;
        for (String p : paras) c += p.split("\\s+").length;
        return c;
    }

    static void appendSectionDiff(StringBuilder out, Section ns, Section ps) {
        if (!ns.found || !ps.found) {
            out.append("  section not found in ")
                    .append(!ns.found ? "new filing" : "")
                    .append(!ns.found && !ps.found ? " and " : "")
                    .append(!ps.found ? "prior filing" : "")
                    .append(" — skipping.\n");
            return;
        }
        DiffResult d = diffParas(ps.paras, ns.paras);
        int oldP = ps.paras.size(), newP = ns.paras.size();
        int pct = (oldP + newP) == 0 ? 100
                : (int) Math.round(200.0 * d.matched / (oldP + newP));
        out.append("  new: ").append(newP).append(" paras / ")
                .append(String.format("%,d", wordCount(ns.paras))).append(" words")
                .append(" · prior: ").append(oldP).append(" paras / ")
                .append(String.format("%,d", wordCount(ps.paras))).append(" words")
                .append(" · ").append(pct).append("% unchanged\n");
        out.append("  +").append(d.added.size()).append(" new · -")
                .append(d.removed.size()).append(" removed · ~")
                .append(d.reworded.size()).append(" reworded\n");
        if (!d.added.isEmpty()) {
            out.append("  NEW:\n");
            for (int k = 0; k < Math.min(SHOW_NEW, d.added.size()); k++)
                out.append("  > ").append(preview(d.added.get(k))).append('\n');
            if (d.added.size() > SHOW_NEW)
                out.append("  … and ").append(d.added.size() - SHOW_NEW).append(" more\n");
        }
        if (!d.removed.isEmpty()) {
            out.append("  REMOVED:\n");
            for (int k = 0; k < Math.min(SHOW_REMOVED, d.removed.size()); k++)
                out.append("  > ").append(preview(d.removed.get(k))).append('\n');
            if (d.removed.size() > SHOW_REMOVED)
                out.append("  … and ").append(d.removed.size() - SHOW_REMOVED).append(" more\n");
        }
        if (!d.reworded.isEmpty()) {
            out.append("  REWORDED:\n");
            for (int k = 0; k < Math.min(SHOW_REWORDED, d.reworded.size()); k++) {
                out.append("  - ").append(preview(d.reworded.get(k)[0])).append('\n');
                out.append("  + ").append(preview(d.reworded.get(k)[1])).append('\n');
            }
            if (d.reworded.size() > SHOW_REWORDED)
                out.append("  … and ").append(d.reworded.size() - SHOW_REWORDED).append(" more\n");
        }
    }

    static String preview(String p) {
        String one = p.replaceAll("\\s+", " ").trim();
        return one.length() <= PARA_PREVIEW ? one : one.substring(0, PARA_PREVIEW) + "…";
    }

    // ------------------------------------------------------------------
    // XBRL delta from the on-disk companyfacts snapshot

    static class FilingMeta {
        String form, filed;
    }

    static FilingMeta parseFilingName(String name) {
        Matcher m = FILING_NAME.matcher(name);
        if (!m.find()) die("cannot parse filing name: " + name);
        FilingMeta fm = new FilingMeta();
        fm.form = m.group(1);
        fm.filed = m.group(2);
        return fm;
    }

    // DCF concept, period kind, then XBRL tags in fallback order.
    static final String[][] CONCEPTS = {
        {"Revenue", "duration",
            "Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet"},
        {"Operating cash flow", "duration",
            "NetCashProvidedByUsedInOperatingActivities"},
        {"Capex", "duration",
            "PaymentsToAcquirePropertyPlantAndEquipment"},
        {"Cash & equivalents", "instant",
            "CashAndCashEquivalentsAtCarryingValue"},
        {"Total debt", "instant-computed"},
        {"Diluted shares (avg)", "duration",
            "WeightedAverageNumberOfDilutedSharesOutstanding"},
        {"Shares outstanding", "instant",
            "CommonStockSharesOutstanding"},
    };

    static class Fact {
        double val;
        String unit; // "USD" or "shares"
        String start, end, fp;
    }

    static String xbrlDelta(Path dir, FilingMeta newMeta) throws IOException {
        Path cf = findCompanyFacts(dir);
        StringBuilder out = new StringBuilder();
        if (cf == null) {
            out.append("  no companyfacts snapshot on disk — XBRL delta unavailable.\n");
            return out.toString();
        }
        List<XbrlFacts.Fact> facts = companyFacts(cf);
        // Previous same-form filing: the latest filed date < new filing's, same form.
        String prevFiled = prevSameFormFiled(facts, newMeta);
        String cmpLabel = prevFiled == null
                ? "no earlier " + newMeta.form + " in snapshot"
                : newMeta.form + " filed " + prevFiled;
        out.append("  new ").append(newMeta.form).append(" filed ").append(newMeta.filed)
                .append(" vs ").append(cmpLabel).append('\n');
        out.append(String.format("  %-22s %-26s %-26s %s\n",
                "Concept", "New", "Prior", "Δ"));
        for (String[] c : CONCEPTS) {
            String name = c[0], kind = c[1];
            Fact nf = "instant-computed".equals(kind)
                    ? totalDebt(facts, newMeta.form, newMeta.filed)
                    : headlineFact(facts, c, newMeta.form, newMeta.filed);
            Fact pf = prevFiled == null ? null
                    : "instant-computed".equals(kind)
                    ? totalDebt(facts, newMeta.form, prevFiled)
                    : headlineFact(facts, c, newMeta.form, prevFiled);
            out.append(String.format("  %-22s %-26s %-26s %s\n",
                    name, fmtFact(nf, "instant-computed".equals(kind)),
                    fmtFact(pf, "instant-computed".equals(kind)), deltaPct(nf, pf, kind)));
        }
        return out.toString();
    }

    static Path findCompanyFacts(Path dir) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "companyfacts_*.json")) {
            for (Path p : ds) return p;
        }
        return null;
    }

    // Tidy XBRL fact table (XbrlFacts, java-shared): one row per fact entry.
    // Selection policy in the methods below is unchanged — only the JSON
    // navigation moved into the shared helper.
    static List<XbrlFacts.Fact> companyFacts(Path cf) throws IOException {
        return XbrlFacts.load(cf);
    }

    /** Latest filed date < newFiled for the same form, or null. */
    static String prevSameFormFiled(List<XbrlFacts.Fact> facts, FilingMeta nm) {
        String best = null;
        for (XbrlFacts.Fact r : facts) {
            if (!"us-gaap".equals(r.taxonomy())) continue;
            if (nm.form.equals(r.form())) {
                String f = r.filed();
                if (f != null && f.compareTo(nm.filed) < 0
                        && (best == null || f.compareTo(best) > 0)) best = f;
            }
        }
        return best;
    }

    /** Headline entry for a concept: latest period end; duration prefers the quarter over YTD. */
    static Fact headlineFact(List<XbrlFacts.Fact> facts, String[] concept,
                             String form, String filed) {
        boolean duration = "duration".equals(concept[1]);
        for (int t = 2; t < concept.length; t++) {
            List<XbrlFacts.Fact> rows = XbrlFacts.forConcept(facts, "us-gaap", concept[t]);
            Fact best = null;
            for (XbrlFacts.Fact r : rows) {
                if (!form.equals(r.form()) || !filed.equals(r.filed())) continue;
                if (r.end() == null) continue; // the old code NPE'd in better() here
                Fact f = toFact(r);
                if (best == null || better(f, best, duration)) best = f;
            }
            if (best != null) return best;
        }
        return null;
    }

    static boolean better(Fact f, Fact best, boolean duration) {
        int c = f.end.compareTo(best.end);
        if (c != 0) return c > 0;
        if (duration && f.start != null && best.start != null) {
            // Same end: prefer the shorter span (quarter beats YTD).
            return spanDays(f) < spanDays(best);
        }
        return false;
    }

    static long spanDays(Fact f) {
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(f.start), LocalDate.parse(f.end));
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    static Fact toFact(XbrlFacts.Fact r) {
        Fact f = new Fact();
        f.val = r.val();
        f.unit = r.unit();
        f.start = r.start();
        f.end = r.end();
        f.fp = r.fp();
        return f;
    }

    /** Total debt = DebtCurrent + LongTermDebtNoncurrent (fallback LongTermDebt). */
    static Fact totalDebt(List<XbrlFacts.Fact> facts, String form, String filed) {
        Fact cur = headlineFact(facts,
                new String[]{"x", "instant", "DebtCurrent"}, form, filed);
        Fact lt = headlineFact(facts,
                new String[]{"x", "instant", "LongTermDebtNoncurrent", "LongTermDebt"}, form, filed);
        if (cur == null || lt == null) return null;
        Fact f = new Fact();
        f.val = cur.val + lt.val;
        f.unit = "USD";
        f.end = cur.end.compareTo(lt.end) >= 0 ? cur.end : lt.end;
        f.fp = cur.fp != null ? cur.fp : lt.fp;
        return f;
    }

    static String fmtFact(Fact f) {
        return fmtFact(f, false);
    }

    static String fmtFact(Fact f, boolean computed) {
        if (f == null) return computed ? "n/a (components untagged)" : "n/a";
        return fmtVal(f.val, f.unit) + " (" + periodLabel(f) + ")";
    }

    static String periodLabel(Fact f) {
        String yy = f.end != null && f.end.length() >= 4 ? "'" + f.end.substring(2, 4) : "";
        if (f.start != null) {
            // Duration fact: label by actual span, not by the fp tag alone —
            // a 10-Q cash-flow statement is usually 6-month YTD, not a quarter.
            long d = spanDays(f);
            if (d > 300) return "FY" + yy;
            if (d > 120) return "6mo " + f.end;
            if (d > 40) return (f.fp != null ? f.fp : "Q") + yy;
            return f.end != null ? f.end : "?";
        }
        if (f.fp == null) return f.end != null ? f.end : "?";
        if ("FY".equals(f.fp)) return "FY" + yy;
        return f.fp + yy;
    }

    static String fmtVal(double v, String unit) {
        String pre = "USD".equals(unit) ? "$" : "";
        double a = Math.abs(v);
        String s;
        if (a >= 1e9) s = String.format("%.2fB", v / 1e9);
        else if (a >= 1e6) s = String.format("%.1fM", v / 1e6);
        else if (a >= 1e3) s = String.format("%.0fK", v / 1e3);
        else s = String.format("%.0f", v);
        return pre + s;
    }

    static String deltaPct(Fact nf, Fact pf, String kind) {
        if (nf == null || pf == null) return "n/a";
        if (pf.val == 0) return "n/a";
        if (kind.startsWith("duration")) {
            // Only compare like-for-like periods (quarter vs quarter, year vs year).
            if (nf.start == null || pf.start == null) return "n/a";
            long d1 = spanDays(nf), d2 = spanDays(pf);
            if (d1 == Long.MAX_VALUE || d2 == Long.MAX_VALUE) return "n/a";
            if (Math.abs(d1 - d2) > 7) return "n/a (periods differ)";
        }
        double pct = 100.0 * (nf.val - pf.val) / Math.abs(pf.val);
        return String.format("%+.1f%%", pct);
    }

    // ------------------------------------------------------------------
    // Minimal JSON parser (objects keep insertion order)

    static final class Json {
        static Object parse(String t) {
            P p = new P(t);
            Object v = p.value();
            p.ws();
            if (p.i != t.length()) throw new IllegalArgumentException("trailing data");
            return v;
        }

        static final class P {
            final String t;
            int i;
            P(String t) { this.t = t; }
            void ws() { while (i < t.length() && " \t\n\r".indexOf(t.charAt(i)) >= 0) i++; }
            char peek() {
                if (i >= t.length()) throw new IllegalArgumentException("eof");
                return t.charAt(i);
            }
            Object value() {
                ws();
                char c = peek();
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return str();
                if (c == 't') { lit("true"); return Boolean.TRUE; }
                if (c == 'f') { lit("false"); return Boolean.FALSE; }
                if (c == 'n') { lit("null"); return null; }
                return num();
            }
            void lit(String s) {
                if (!t.startsWith(s, i)) throw new IllegalArgumentException("bad literal");
                i += s.length();
            }
            Map<String, Object> obj() {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek() == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (peek() != ':') throw new IllegalArgumentException("expected :");
                    i++;
                    m.put(k, value());
                    ws();
                    char c = peek();
                    if (c == ',') { i++; continue; }
                    if (c == '}') { i++; return m; }
                    throw new IllegalArgumentException("expected , or }");
                }
            }
            List<Object> arr() {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek() == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    char c = peek();
                    if (c == ',') { i++; continue; }
                    if (c == ']') { i++; return l; }
                    throw new IllegalArgumentException("expected , or ]");
                }
            }
            String str() {
                i++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = t.charAt(i++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        char e = t.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                sb.append((char) Integer.parseInt(t.substring(i, i + 4), 16));
                                i += 4;
                                break;
                            default: throw new IllegalArgumentException("bad escape");
                        }
                    } else sb.append(c);
                }
            }
            Object num() {
                int s = i;
                while (i < t.length() && "-+0123456789.eE".indexOf(t.charAt(i)) >= 0) i++;
                String n = t.substring(s, i);
                return (n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0)
                        ? (Object) Double.parseDouble(n) : Long.parseLong(n);
            }
        }
    }

    // ------------------------------------------------------------------
    // Args / helpers

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                String v = (i + 1 < args.length && !args[i + 1].startsWith("--"))
                        ? args[++i] : "true";
                m.put(k, v);
            }
        }
        return m;
    }

    static String required(Map<String, String> a, String k) {
        String v = a.get(k);
        if (v == null || v.isEmpty()) die("missing --" + k);
        return v;
    }

    static String home() {
        String h = System.getenv("HOME");
        if (h == null || h.isEmpty()) die("HOME not set");
        return h;
    }

    static void die(String msg) {
        System.err.println("FilingDiff: " + msg);
        System.exit(1);
        throw new IllegalStateException(msg);
    }

    // ------------------------------------------------------------------
    // Self-test (shared helpers; run with java-shared on the class path)

    static void selfTest() throws Exception {
        SelfTest.reset();
        Path t = Files.createTempDirectory("filingdiff-test");

        // --- section extraction on synthetic filings
        Path oldF = t.resolve("10-Q_2026-05-06_TST.txt");
        Path newF = t.resolve("10-Q_2026-08-05_TST.txt");
        Files.writeString(oldF,
                "Table of Contents\n\nITEM 1. FINANCIAL STATEMENTS\n\nsome numbers\n\n"
                + "ITEM 2. MANAGEMENT'S DISCUSSION AND ANALYSIS\n\n"
                + "Revenue grew in the quarter.\n\n"
                + "Costs were flat year over year.\n\n"
                + "We expect growth to continue at a moderate pace overall.\n\n"
                + "ITEM 3. QUANTITATIVE DISCLOSURES\n\nnothing\n\n"
                + "ITEM 1A. RISK FACTORS\n\n"
                + "We face competition in all markets.\n\n"
                + "ITEM 2. UNREGISTERED SALES\n\nnone\n");
        Files.writeString(newF,
                "Table of Contents\n\nITEM 1. FINANCIAL STATEMENTS\n\nsome numbers\n\n"
                + "ITEM 2. MANAGEMENT'S DISCUSSION AND ANALYSIS\n\n"
                + "Revenue grew strongly in the quarter.\n\n"
                + "Costs were flat year over year.\n\n"
                + "We launched a brand new product line this quarter.\n\n"
                + "ITEM 3. QUANTITATIVE DISCLOSURES\n\nnothing\n\n"
                + "ITEM 1A. RISK FACTORS\n\n"
                + "We face competition in all markets.\n\n"
                + "ITEM 2. UNREGISTERED SALES\n\nnone\n");
        List<String> oldL = Files.readAllLines(oldF, StandardCharsets.UTF_8);
        List<String> newL = Files.readAllLines(newF, StandardCharsets.UTF_8);
        Section om = extractMda(oldL, "10-Q"), nm = extractMda(newL, "10-Q");
        SelfTest.check("mda found in both", om.found && nm.found);
        SelfTest.checkEq("mda old paras", 3, om.paras.size());
        SelfTest.checkEq("mda new paras", 3, nm.paras.size());
        SelfTest.check("mda stops before ITEM 3",
                nm.paras.stream().noneMatch(p -> p.contains("QUANTITATIVE")));
        DiffResult d = diffParas(om.paras, nm.paras);
        SelfTest.checkEq("one reworded + one added", 2, d.added.size() + d.reworded.size());
        SelfTest.check("reword detected",
                d.reworded.size() == 1
                        && d.reworded.get(0)[0].contains("Revenue grew in the quarter.")
                        && d.reworded.get(0)[1].contains("strongly"));
        SelfTest.check("genuinely-removed para kept separate",
                d.removed.stream().anyMatch(p -> p.contains("moderate pace")));
        SelfTest.check("new para detected",
                d.added.stream().anyMatch(p -> p.contains("brand new product")));
        Section or_ = extractSection(oldL, RISK_START), nr = extractSection(newL, RISK_START);
        DiffResult rd = diffParas(or_.paras, nr.paras);
        SelfTest.check("identical risk section: no changes",
                rd.added.isEmpty() && rd.removed.isEmpty() && rd.reworded.isEmpty());

        // --- filing name parsing
        FilingMeta fm = parseFilingName("10-Q_2026-08-05_AMD.txt");
        SelfTest.checkEq("form", "10-Q", fm.form);
        SelfTest.checkEq("filed", "2026-08-05", fm.filed);

        // --- XBRL delta on a synthetic companyfacts snapshot
        Path cf = t.resolve("companyfacts_CIK0000000000.json");
        Files.writeString(cf, "{"
                + "\"facts\":{\"us-gaap\":{"
                + "\"Revenues\":{\"units\":{\"USD\":["
                + "{\"val\":7000,\"form\":\"10-Q\",\"filed\":\"2026-05-06\",\"fy\":2026,\"fp\":\"Q1\",\"start\":\"2026-01-01\",\"end\":\"2026-03-31\"},"
                + "{\"val\":7700,\"form\":\"10-Q\",\"filed\":\"2026-08-05\",\"fy\":2026,\"fp\":\"Q2\",\"start\":\"2026-04-01\",\"end\":\"2026-06-30\"},"
                + "{\"val\":14000,\"form\":\"10-Q\",\"filed\":\"2026-08-05\",\"fy\":2026,\"fp\":\"Q2\",\"start\":\"2026-01-01\",\"end\":\"2026-06-30\"}"
                + "]}},"
                + "\"CashAndCashEquivalentsAtCarryingValue\":{\"units\":{\"USD\":["
                + "{\"val\":5000,\"form\":\"10-Q\",\"filed\":\"2026-05-06\",\"end\":\"2026-03-31\",\"fp\":\"Q1\"},"
                + "{\"val\":6000,\"form\":\"10-Q\",\"filed\":\"2026-08-05\",\"end\":\"2026-06-30\",\"fp\":\"Q2\"}"
                + "]}},"
                + "\"DebtCurrent\":{\"units\":{\"USD\":["
                + "{\"val\":1000,\"form\":\"10-Q\",\"filed\":\"2026-08-05\",\"end\":\"2026-06-30\",\"fp\":\"Q2\"}]}},"
                + "\"LongTermDebtNoncurrent\":{\"units\":{\"USD\":["
                + "{\"val\":4000,\"form\":\"10-Q\",\"filed\":\"2026-08-05\",\"end\":\"2026-06-30\",\"fp\":\"Q2\"},"
                + "{\"val\":4500,\"form\":\"10-Q\",\"filed\":\"2026-05-06\",\"end\":\"2026-03-31\",\"fp\":\"Q1\"}]}}"
                + "}}}");
        List<XbrlFacts.Fact> facts = companyFacts(cf);
        FilingMeta nm2 = new FilingMeta();
        nm2.form = "10-Q";
        nm2.filed = "2026-08-05";
        SelfTest.checkEq("prev same-form filed", "2026-05-06", prevSameFormFiled(facts, nm2));
        String[] revC = CONCEPTS[0];
        Fact nf = headlineFact(facts, revC, "10-Q", "2026-08-05");
        SelfTest.check("quarter preferred over YTD", nf != null && nf.val == 7700.0);
        Fact pf = headlineFact(facts, revC, "10-Q", "2026-05-06");
        SelfTest.checkEq("revenue delta", "+10.0%", deltaPct(nf, pf, "duration"));
        Fact debt = totalDebt(facts, "10-Q", "2026-08-05");
        SelfTest.check("total debt = 1000+4000", debt != null && debt.val == 5000.0);
        Fact debtOld = totalDebt(facts, "10-Q", "2026-05-06");
        SelfTest.check("debt missing current -> null", debtOld == null);
        SelfTest.checkEq("fmt", "$7.70B", fmtVal(7700000000.0, "USD"));

        // --- duration mismatch -> n/a, never a bogus %
        Fact q = new Fact(); q.val = 7700; q.start = "2026-04-01"; q.end = "2026-06-30";
        Fact y = new Fact(); y.val = 30000; y.start = "2026-01-01"; y.end = "2026-12-31";
        SelfTest.check("quarter vs year -> n/a", deltaPct(q, y, "duration").startsWith("n/a"));

        // --- page-break noise paras are dropped from sections
        List<String> noisy = List.of(
                "ITEM 2. MANAGEMENT'S DISCUSSION AND ANALYSIS", "",
                "Table of Contents", "",
                "Real content here.", "",
                "Table of Conten t s", "",
                "ITEM 3. QUANTITATIVE DISCLOSURES");
        Section nz = extractMda(noisy, "10-Q");
        SelfTest.check("toc noise filtered",
                nz.found && nz.paras.size() == 1 && nz.paras.get(0).equals("Real content here."));

        // --- duration labels follow the actual span
        Fact h = new Fact(); h.start = "2025-12-28"; h.end = "2026-06-27"; h.fp = "Q2";
        SelfTest.checkEq("6mo label", "6mo 2026-06-27", periodLabel(h));
        Fact qq = new Fact(); qq.start = "2026-04-01"; qq.end = "2026-06-30"; qq.fp = "Q2";
        SelfTest.checkEq("quarter label", "Q2'26", periodLabel(qq));

        // --- wrapped-line paragraph reconstruction (NVDA-style: no blank lines)
        List<String> wrapped = List.of(
                "Item 2. Management's Discussion and Analysis",
                "Forward-Looking Statements",
                "This report contains forward-looking statements within the",
                "meaning of Section 27A. These statements involve risks.",
                "Overview",
                "Revenue was strong this quarter across all of our major segments.",
                "? First bullet item here",
                "? Second bullet item here",
                "Item 3. Other");
        Section wz = extractMda(wrapped, "10-Q");
        SelfTest.check("wrapped lines joined",
                wz.found && wz.paras.stream().anyMatch(p ->
                        p.equals("This report contains forward-looking statements within the meaning of Section 27A. These statements involve risks.")));
        SelfTest.check("bullets split",
                wz.paras.stream().anyMatch(p -> p.equals("? First bullet item here"))
                        && wz.paras.stream().anyMatch(p -> p.equals("? Second bullet item here")));
        SelfTest.check("heading kept separate",
                wz.paras.stream().anyMatch(p -> p.equals("Overview")));

        SelfTest.finish();
    }
}
