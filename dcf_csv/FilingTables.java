// FilingTables.java
// Table-aware SEC filing HTML extractor (idea: audit item #6, approved 2026-09-28).
//
// The SEC pipeline flattens filing HTML into plain text ("strip HTML tags"),
// destroying financial-statement table structure — the root cause of the brittle
// regex workarounds in the figure audit. This tool parses the filing's real
// <table> elements with jsoup and emits the statements as JSON/CSV with headers
// preserved, so DCF figure extraction reads row x column instead of guessing
// from flattened text.
//
// Why jsoup and not an HF layout model: EDGAR tables are already
// machine-readable HTML; a probabilistic vision model would be strictly worse.
//
// Usage:
//     export JAVA_HOME=~/workspace/java/jdk-21; export PATH=$JAVA_HOME/bin:$PATH
//     CP=~/workspace/lib/jsoup/jsoup-1.18.3.jar
//     java -cp "$CP" ~/workspace/dcf_csv/FilingTables.java <filing.htm> > out.json
//     java -cp "$CP" ~/workspace/dcf_csv/FilingTables.java <filing.htm> --csv > out.csv
//     java -cp "$CP" ~/workspace/dcf_csv/FilingTables.java <filing.htm> --fields
//         # DCF input fields (ocf/capex/shares/cash/debt) as JSON, latest column
//     java -cp "$CP:~/workspace/java-shared/classes" \
//          ~/workspace/dcf_csv/FilingTables.java --self-test
//     java -cp "$CP:~/workspace/java-shared/classes" \
//          ~/workspace/dcf_csv/FilingTables.java --compare
//         # head-to-head: legacy regex-on-.txt vs table-aware extraction,
//         # scored against XBRL ground truth (MSFT FY2026 10-K)
//
// The old flat-text path (.txt) stays live as fallback. --fields output was
// proven to match-and-beat it against XBRL ground truth on 2026-09-28
// (run --compare to reproduce: old 2/5, new 5/5), so the table-aware path
// is now primary for DCF figure extraction in the filing-watch job.

import java.io.File;
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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class FilingTables {

    static final String JSOUP_JAR = homeDir() + "/workspace/lib/jsoup/jsoup-1.18.3.jar";

    // ------------------------------------------------------------------
    // Model.
    // ------------------------------------------------------------------
    static final class Row {
        String label;
        List<Double> values = new ArrayList<>(); // null = blank/dash in the cell
        boolean aligned;                          // values.size() == columns.size()
    }

    static final class Statement {
        String key;            // income | balance | cashflow | equity | comprehensive
        String title;          // heading text as found
        String units = "";     // millions | thousands | billions | ""
        List<String> columns = new ArrayList<>(); // year labels, latest first
        List<Row> rows = new ArrayList<>();
        int unalignedRows;
    }

    // ------------------------------------------------------------------
    // Statement detection.
    // ------------------------------------------------------------------
    /** key -> label patterns (lower-cased heading text). */
    static String classifyHeading(String t) {
        String l = t.toLowerCase().replaceAll("\\s+", " ").trim();
        if (l.length() > 120) return null;
        boolean hasCashFlow = l.contains("cash flow");
        boolean hasBalance = l.contains("balance sheet");
        boolean hasIncome = l.contains("income statement") || l.contains("statements of income")
                || l.contains("statement of income") || l.contains("statements of operations");
        boolean hasEquity = (l.contains("stockholder") || l.contains("shareholder"))
                && l.contains("statement") && !l.contains("liabilities");
        // (the balance sheet's "LIABILITIES AND STOCKHOLDERS' EQUITY" section
        // header must not match — it names no statement)
        boolean hasComprehensive = l.contains("comprehensive income");
        if (hasComprehensive) return "comprehensive";
        if (hasCashFlow) return "cashflow";
        if (hasBalance) return "balance";
        if (hasEquity) return "equity";
        if (hasIncome) return "income";
        return null;
    }

    /** Find each statement's table: first heading match per key, then the next table. */
    static List<Statement> detectStatements(Document doc) {
        List<Statement> out = new ArrayList<>();
        List<String> foundKeys = new ArrayList<>();
        List<Element> all = doc.getAllElements();
        for (int i = 0; i < all.size(); i++) {
            Element el = all.get(i);
            // Skip table-of-contents links (href="#...") — they name statements but
            // the next table after them is not the statement.
            if (el.tagName().equals("a") && el.attr("href").startsWith("#")) continue;
            String key = classifyHeading(el.text());
            if (key == null || foundKeys.contains(key)) continue;
            // Find the next <table> in document order.
            Element table = null;
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(j).tagName().equals("table")) { table = all.get(j); break; }
            }
            if (table == null) continue;
            Statement s = parseStatement(table, key, el.text().trim());
            if (s.columns.size() >= 2 && countValueRows(s) >= 3) {
                out.add(s);
                foundKeys.add(key);
            }
            // else: heading was a false positive (e.g. an index entry); keep scanning.
        }
        return out;
    }

    static int countValueRows(Statement s) {
        int n = 0;
        for (Row r : s.rows) if (r.aligned) n++;
        return n;
    }

    // ------------------------------------------------------------------
    // Table parsing.
    // ------------------------------------------------------------------
    static final Pattern YEAR_CELL = Pattern.compile("^\\s*(19|20)\\d\\d\\s*$");
    static final Pattern UNITS_PAT =
            Pattern.compile("(?i)\\(in (millions|thousands|billions)");
    // Ordered token scan over the joined cell text: parenthesized negatives,
    // $-prefixed amounts, plain numbers, then dash/blank placeholders.
    // find() (not full-match) so "(115,948 )" split across cells still matches.
    static final Pattern TOKEN = Pattern.compile(
            "\\(\\s*\\$?\\s*[\\d,]+(?:\\.\\d+)?\\s*\\)"
            + "|\\$\\s*[\\d,]+(?:\\.\\d+)?"
            + "|[\\d,]+(?:\\.\\d+)?"
            + "|—|–|--|(?<![\\d,\\(])-((?![\\d,]))"
            + "|\\bN/A\\b|\\bNM\\b",
            Pattern.CASE_INSENSITIVE);
    static final Pattern NULL_TOKEN = Pattern.compile(
            "^(—|–|--|-|N/A|NM)$", Pattern.CASE_INSENSITIVE);

    static Statement parseStatement(Element table, String key, String title) {
        Statement s = new Statement();
        s.key = key;
        s.title = title.replaceAll("\\s+", " ").trim();

        // Collect rows (skip nested tables' rows and invisible spacer rows).
        List<List<String>> grid = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            Element nearest = tr.closest("table");
            if (nearest == null || !nearest.equals(table)) continue;
            if (tr.attr("style").contains("collapse")) continue; // spacer row
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.select("td, th")) {
                Element nc = cell.closest("table");
                if (nc == null || !nc.equals(table)) continue;
                Element c = cell.clone();
                c.select("sup").remove(); // footnote markers
                String t = c.text().replace(' ', ' ').trim();
                cells.add(t);
            }
            if (!cells.isEmpty()) grid.add(cells);
        }

        // Units note + header-year row.
        int headerIdx = -1;
        for (int i = 0; i < Math.min(grid.size(), 6); i++) {
            String joined = String.join(" ", grid.get(i));
            Matcher um = UNITS_PAT.matcher(joined);
            if (um.find() && s.units.isEmpty()) s.units = um.group(1).toLowerCase();
            List<String> years = new ArrayList<>();
            for (String c : grid.get(i)) {
                if (YEAR_CELL.matcher(c).matches()) years.add(c.trim());
            }
            if (years.size() >= 2 && headerIdx < 0) {
                s.columns.addAll(years);
                headerIdx = i;
            }
        }

        // Data rows: label = first cell, values = number tokens in order.
        for (int i = 0; i < grid.size(); i++) {
            if (i == headerIdx) continue;
            List<String> cells = grid.get(i);
            String label = cells.get(0).replaceAll("\\s+", " ").trim();
            String rest = String.join(" ", cells.subList(1, cells.size()));
            if (label.isEmpty() && rest.trim().isEmpty()) continue;
            if (UNITS_PAT.matcher(label + " " + rest).find() && label.length() < 60
                    && countTokens(rest) == 0) continue; // units note row
            Row r = new Row();
            r.label = label.isEmpty() ? "(unlabeled)" : label.replaceAll(":\\s*$", "");
            List<Double> vals = extractTokens(rest);
            r.values.addAll(vals);
            r.aligned = !s.columns.isEmpty() && vals.size() == s.columns.size();
            if (!r.aligned) s.unalignedRows++;
            s.rows.add(r);
        }
        return s;
    }

    static int countTokens(String text) {
        Matcher m = TOKEN.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** Extract number tokens in document order; dashes become null placeholders. */
    static List<Double> extractTokens(String text) {
        List<Double> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String tok = m.group();
            if (NULL_TOKEN.matcher(tok).matches()) out.add(null);
            else out.add(parseNumber(tok));
        }
        return out;
    }

    static Double parseNumber(String tok) {
        String t = tok.replace("$", "").replace(",", "").replaceAll("\\s+", "");
        boolean neg = t.startsWith("(") && t.endsWith(")");
        if (neg) t = t.substring(1, t.length() - 1);
        try {
            double v = Double.parseDouble(t);
            return neg ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    // ------------------------------------------------------------------
    // DCF field extraction (the "new path" for figure extraction).
    // ------------------------------------------------------------------
    static final class FieldSpec {
        final String key, stmtKey;
        final Pattern label;
        final boolean abs;
        FieldSpec(String key, String stmtKey, String labelPat, boolean abs) {
            this.key = key; this.stmtKey = stmtKey;
            this.label = Pattern.compile(labelPat, Pattern.CASE_INSENSITIVE);
            this.abs = abs;
        }
    }

    static final List<FieldSpec> FIELDS = List.of(
        new FieldSpec("ocf", "cashflow",
            "net cash (from|provided by).*operat", false),
        new FieldSpec("capex", "cashflow",
            "additions to property and equipment|purchases of property|capital expenditures", true),
        // shares handled specially below (label is a bare "Diluted" under the
        // "Weighted average shares outstanding" header; a second "Diluted" row
        // holds EPS and must not match)
        new FieldSpec("cash", "balance",
            "^cash and cash equivalents$", false)
    );

    /** Latest-column value per DCF field; debt = long-term + current portion. */
    static Map<String, Map<String, Object>> extractFields(List<Statement> stmts) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, Statement> byKey = new LinkedHashMap<>();
        for (Statement s : stmts) byKey.putIfAbsent(s.key, s);
        for (FieldSpec f : FIELDS) {
            Statement s = byKey.get(f.stmtKey);
            Map<String, Object> hit = new LinkedHashMap<>();
            if (s != null && !s.columns.isEmpty()) {
                for (Row r : s.rows) {
                    if (!r.aligned || r.values.isEmpty()) continue;
                    if (f.label.matcher(r.label).find()) {
                        Double v = r.values.get(0);
                        if (v == null) continue;
                        if (f.abs) v = Math.abs(v);
                        hit.put("value", v);
                        hit.put("label", r.label);
                        hit.put("column", s.columns.get(0));
                        hit.put("units", s.units);
                        hit.put("statement", s.title);
                        break;
                    }
                }
            }
            out.put(f.key, hit);
        }
        // Total debt = long-term debt + current portion of long-term debt (balance sheet).
        Statement b = byKey.get("balance");
        Map<String, Object> debt = new LinkedHashMap<>();
        if (b != null && !b.columns.isEmpty()) {
            double total = 0; boolean any = false;
            StringBuilder labels = new StringBuilder();
            for (Row r : b.rows) {
                if (!r.aligned || r.values.isEmpty() || r.values.get(0) == null) continue;
                String l = r.label.toLowerCase();
                boolean isLtd = l.equals("long-term debt")
                        || l.equals("long-term debt, net of current portion");
                boolean isCurrent = l.contains("current portion of long-term debt")
                        || l.equals("short-term debt") || l.equals("current maturities of long-term debt");
                if (isLtd || isCurrent) {
                    total += Math.abs(r.values.get(0));
                    any = true;
                    if (labels.length() > 0) labels.append(" + ");
                    labels.append(r.label);
                }
            }
            if (any) {
                debt.put("value", total);
                debt.put("label", labels.toString());
                debt.put("column", b.columns.get(0));
                debt.put("units", b.units);
                debt.put("statement", b.title);
            }
        }
        // Diluted shares: the "Diluted" row under the "Weighted average shares
        // outstanding" header (the income statement also has a "Diluted" EPS
        // row — share counts are in the thousands, EPS is < 100).
        Map<String, Object> shares = new LinkedHashMap<>();
        Statement inc = byKey.get("income");
        if (inc != null && !inc.columns.isEmpty()) {
            boolean inSharesSection = false;
            for (Row r : inc.rows) {
                String l = r.label.toLowerCase();
                if (l.contains("weighted average shares")) { inSharesSection = true; continue; }
                if (inSharesSection && l.equals("diluted") && r.aligned && !r.values.isEmpty()
                        && r.values.get(0) != null && r.values.get(0) > 100) {
                    shares.put("value", r.values.get(0));
                    shares.put("label", r.label + " (weighted-average shares)");
                    shares.put("column", inc.columns.get(0));
                    shares.put("units", inc.units);
                    shares.put("statement", inc.title);
                    break;
                }
            }
        }
        // Preserve field order: ocf, capex, shares, cash, debt.
        Map<String, Map<String, Object>> ordered = new LinkedHashMap<>();
        for (String k : List.of("ocf", "capex")) ordered.put(k, out.get(k));
        ordered.put("shares", shares);
        ordered.put("cash", out.get("cash"));
        ordered.put("debt", debt);
        return ordered;
    }

    // ------------------------------------------------------------------
    // Output.
    // ------------------------------------------------------------------
    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    static String statementsJson(String filing, List<Statement> stmts) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"filing\": ").append(jsonEscape(filing)).append(",\n");
        sb.append("  \"statements\": [\n");
        for (int si = 0; si < stmts.size(); si++) {
            Statement s = stmts.get(si);
            sb.append("    {\"key\": ").append(jsonEscape(s.key));
            sb.append(", \"title\": ").append(jsonEscape(s.title));
            sb.append(", \"units\": ").append(jsonEscape(s.units));
            sb.append(", \"columns\": [");
            for (int i = 0; i < s.columns.size(); i++)
                sb.append(i > 0 ? ", " : "").append(jsonEscape(s.columns.get(i)));
            sb.append("], \"rows\": [\n");
            for (int ri = 0; ri < s.rows.size(); ri++) {
                Row r = s.rows.get(ri);
                sb.append("      {\"label\": ").append(jsonEscape(r.label));
                sb.append(", \"values\": [");
                for (int i = 0; i < r.values.size(); i++) {
                    if (i > 0) sb.append(", ");
                    Double v = r.values.get(i);
                    sb.append(v == null ? "null" : (v == Math.rint(v) ? String.valueOf(v.longValue()) : v.toString()));
                }
                sb.append("]}").append(ri + 1 < s.rows.size() ? ",\n" : "\n");
            }
            sb.append("    ]}").append(si + 1 < stmts.size() ? ",\n" : "\n");
        }
        return sb.append("  ]\n}\n").toString();
    }

    static String fieldsJson(Map<String, Map<String, Object>> fields) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Map<String, Object>> e : fields.entrySet()) {
            sb.append("  ").append(jsonEscape(e.getKey())).append(": {");
            int j = 0;
            for (Map.Entry<String, Object> f : e.getValue().entrySet()) {
                if (j++ > 0) sb.append(", ");
                sb.append(jsonEscape(f.getKey())).append(": ");
                Object v = f.getValue();
                if (v instanceof Double) {
                    double d = (Double) v;
                    sb.append(d == Math.rint(d) ? String.valueOf((long) d) : v.toString());
                } else sb.append(jsonEscape(String.valueOf(v)));
            }
            sb.append("}").append(++i < fields.size() ? ",\n" : "\n");
        }
        return sb.append("}\n").toString();
    }

    static String statementsCsv(String filing, List<Statement> stmts) {
        StringBuilder sb = new StringBuilder("filing,statement,units,label");
        // header uses the max column set across statements
        List<String> allCols = new ArrayList<>();
        for (Statement s : stmts) for (String c : s.columns) if (!allCols.contains(c)) allCols.add(c);
        for (String c : allCols) sb.append(",").append(csvCell(c));
        sb.append("\n");
        for (Statement s : stmts) {
            for (Row r : s.rows) {
                sb.append(csvCell(filing)).append(",").append(csvCell(s.key)).append(",")
                  .append(csvCell(s.units)).append(",").append(csvCell(r.label));
                for (int i = 0; i < allCols.size(); i++) {
                    sb.append(",");
                    int idx = s.columns.indexOf(allCols.get(i));
                    if (idx >= 0 && idx < r.values.size()) {
                        Double v = r.values.get(idx);
                        if (v != null) sb.append(v == Math.rint(v) ? String.valueOf(v.longValue()) : v);
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    static String csvCell(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ------------------------------------------------------------------
    // Self-test: MSFT FY2026 10-K — figures cross-checked against
    // sec_filings/dcf_inputs_all.json (human-verified) and XBRL companyfacts.
    // ------------------------------------------------------------------
    static void selfTest() throws Exception {
        Path htm = Paths.get(homeDir(),
                "workspace/sec_filings/microsoft/10-K_2026-07-29_MSFT.htm");
        SelfTest.check("msft 10-K exists", Files.exists(htm));
        Document doc = Jsoup.parse(htm.toFile(), "UTF-8");
        List<Statement> stmts = detectStatements(doc);
        SelfTest.check("income detected", stmts.stream().anyMatch(s -> s.key.equals("income")));
        SelfTest.check("balance detected", stmts.stream().anyMatch(s -> s.key.equals("balance")));
        SelfTest.check("cashflow detected", stmts.stream().anyMatch(s -> s.key.equals("cashflow")));
        Statement income = stmts.stream().filter(s -> s.key.equals("income")).findFirst().orElse(null);
        if (income != null) {
            SelfTest.checkEq("income columns", "[2026, 2025, 2024]", income.columns.toString());
            SelfTest.check("income units=millions", income.units.equals("millions"));
            Row rev = findRow(income, "total revenue");
            if (rev != null && rev.aligned)
                SelfTest.checkEq("FY2026 total revenue", 331839L, rev.values.get(0).longValue());
            else SelfTest.check("total revenue row aligned", false);
        }
        Map<String, Map<String, Object>> f = extractFields(stmts);
        SelfTest.checkEq("ocf", 182935L, longOf(f, "ocf"));
        SelfTest.checkEq("capex", 115948L, longOf(f, "capex"));
        SelfTest.checkEq("cash", 20935L, longOf(f, "cash"));
        SelfTest.checkEq("shares", 7453L, longOf(f, "shares"));
        SelfTest.checkEq("debt", 40294L, longOf(f, "debt"));
        // negatives: capex row shows (115,948) in the filing
        Statement cf = stmts.stream().filter(s -> s.key.equals("cashflow")).findFirst().orElse(null);
        if (cf != null) {
            Row cx = findRow(cf, "additions to property and equipment");
            SelfTest.check("capex raw negative", cx != null && cx.aligned && cx.values.get(0) < 0);
        }
        SelfTest.finish();
    }

    static Row findRow(Statement s, String labelPart) {
        for (Row r : s.rows)
            if (r.label.toLowerCase().contains(labelPart)) return r;
        return null;
    }

    static Long longOf(Map<String, Map<String, Object>> f, String key) {
        Object v = f.getOrDefault(key, Map.of()).get("value");
        return v instanceof Double ? ((Double) v).longValue() : null;
    }

    // ------------------------------------------------------------------
    // Head-to-head comparison: legacy regex-on-.txt vs table-aware
    // extraction, scored against XBRL ground truth (MSFT FY2026 10-K).
    // This is the durable, Java-only record of the 2026-09-28 finding
    // (old 2/5, new 5/5) — no temporary scripts involved.
    // ------------------------------------------------------------------

    /** XBRL ground truth, USD millions, verified 2026-09-28 against
     *  sec_filings/microsoft/companyfacts_CIK0000789019.json:
     *  NetCashProvidedByUsedInOperatingActivities, PaymentsToAcquire-
     *  PropertyPlantAndEquipment, CashAndCashEquivalentsAtCarryingValue,
     *  WeightedAverageNumberOfDilutedSharesOutstanding (CY2026 frame),
     *  LongTermDebt (incl. current portion). */
    static final Map<String, Long> TRUTH_MSFT = Map.of(
            "ocf", 182935L, "capex", 115948L, "cash", 20935L,
            "shares", 7453L, "debt", 40294L);

    /** Legacy extractor: label regex on whitespace-collapsed flat text,
     *  first number tokens after the label. This is the exact algorithm
     *  the retired flat-text path used — kept frozen so the comparison
     *  stays an honest record of what was replaced. */
    static List<Double> legacyExtract(String flat, String labelPat) {
        List<Double> vals = new ArrayList<>();
        Matcher lm = Pattern.compile(labelPat, Pattern.CASE_INSENSITIVE).matcher(flat);
        if (!lm.find()) return vals;
        String seg = flat.substring(lm.end(), Math.min(flat.length(), lm.end() + 1500));
        Matcher tm = Pattern.compile(
                "\\(\\s*\\$?\\s*[\\d,]+(?:\\.\\d+)?\\s*\\)|\\$?\\s*[\\d,]+(?:\\.\\d+)?")
                .matcher(seg);
        while (tm.find() && vals.size() < 3) {
            String t = tm.group();
            boolean neg = t.strip().startsWith("(");
            String body = t.replace("(", "").replace(")", "")
                    .replace("$", "").replace(",", "").trim();
            if (body.isEmpty() || !body.matches(".*\\d.*")) continue;
            double v = Double.parseDouble(body);
            vals.add(neg ? -v : v);
        }
        return vals;
    }

    static Double firstOf(List<Double> l) { return l.isEmpty() ? null : l.get(0); }

    static boolean closeEnough(Double a, long b) {
        return a != null && Math.abs(a - b) <= Math.max(1, Math.abs(b) * 0.005);
    }

    static String fmtNum(Double v) {
        if (v == null) return "null";
        return v == Math.rint(v) ? String.valueOf(v.longValue()) : v.toString();
    }

    static void comparePaths() throws Exception {
        Path dir = Paths.get(homeDir(), "workspace/sec_filings/microsoft");
        Path htm = dir.resolve("10-K_2026-07-29_MSFT.htm");
        Path txt = dir.resolve("10-K_2026-07-29_MSFT.txt");
        SelfTest.check("compare: 10-K .htm exists", Files.exists(htm));
        SelfTest.check("compare: 10-K .txt exists", Files.exists(txt));

        // OLD PATH: label regexes on the flat .txt.
        String flat = Files.readString(txt, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        Map<String, Double> old = new LinkedHashMap<>();
        old.put("ocf", firstOf(legacyExtract(flat, "Net cash from operations")));
        old.put("capex", firstOf(legacyExtract(flat, "Additions to property and equipment")));
        old.put("cash", firstOf(legacyExtract(flat, "Cash and cash equivalents(?!\\s*,)")));
        old.put("shares", firstOf(legacyExtract(flat, "Weighted average shares outstanding")));
        Double dlt = firstOf(legacyExtract(flat, "(?<!Current portion of )Long-term debt"));
        Double dcp = firstOf(legacyExtract(flat, "Current portion of long-term debt"));
        old.put("debt", (dlt == null ? 0 : dlt) + (dcp == null ? 0 : dcp));

        // NEW PATH: table-aware extraction from the .htm.
        Document doc = Jsoup.parse(htm.toFile(), "UTF-8");
        Map<String, Map<String, Object>> fields = extractFields(detectStatements(doc));
        Map<String, Double> neo = new LinkedHashMap<>();
        for (String k : List.of("ocf", "capex", "shares", "cash", "debt")) {
            Object v = fields.getOrDefault(k, Map.of()).get("value");
            neo.put(k, v instanceof Double ? (Double) v : null);
        }

        // Score both against XBRL ground truth.
        System.out.printf("%-8s%10s%12s%12s  %-6s %s%n",
                "field", "truth", "old(txt)", "new(tbl)", "old-ok", "new-ok");
        int oldScore = 0, newScore = 0;
        for (String k : List.of("ocf", "capex", "shares", "cash", "debt")) {
            long t = TRUTH_MSFT.get(k);
            boolean okOld = closeEnough(old.get(k), t);
            boolean okNew = closeEnough(neo.get(k), t);
            if (okOld) oldScore++;
            if (okNew) newScore++;
            System.out.printf("%-8s%10d%12s%12s  %-6s %s%n",
                    k, t, fmtNum(old.get(k)), fmtNum(neo.get(k)), okOld, okNew);
        }
        System.out.printf("%nold path: %d/5 correct   new path: %d/5 correct%n", oldScore, newScore);

        SelfTest.check("compare: legacy path reproduces 2/5 baseline", oldScore == 2);
        SelfTest.check("compare: table-aware path 5/5 vs XBRL truth", newScore == 5);
        SelfTest.check("compare: table-aware path beats legacy path", newScore > oldScore);
        SelfTest.finish();
    }

    // ------------------------------------------------------------------
    // Main.
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        List<String> argv = new ArrayList<>(List.of(args));
        if (argv.contains("--self-test")) { selfTest(); return; }
        if (argv.contains("--compare")) { comparePaths(); return; }
        boolean csv = argv.remove("--csv");
        boolean fields = argv.remove("--fields");
        String out = null;
        for (int i = 0; i < argv.size(); i++) {
            if (argv.get(i).equals("--out") && i + 1 < argv.size()) { out = argv.get(i + 1); argv.remove(i + 1); argv.remove(i); break; }
        }
        if (argv.isEmpty()) {
            System.err.println("Usage: FilingTables.java <filing.htm> [--csv] [--fields] [--out path] [--self-test] [--compare]");
            System.exit(2);
        }
        Path p = Paths.get(argv.get(0));
        Document doc = Jsoup.parse(p.toFile(), "UTF-8");
        List<Statement> stmts = detectStatements(doc);
        String result = fields ? fieldsJson(extractFields(stmts))
                : csv ? statementsCsv(p.getFileName().toString(), stmts)
                : statementsJson(p.getFileName().toString(), stmts);
        if (out != null) Files.writeString(Paths.get(out), result, StandardCharsets.UTF_8);
        else System.out.print(result);
        if (stmts.isEmpty()) {
            System.err.println("WARNING: no financial statements detected in " + p.getFileName());
            System.exit(3);
        }
    }
}
