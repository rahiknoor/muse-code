import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

/**
 * A5 — WACC / sector discount-rate audit.
 *
 * The Damodaran-mimicking sector discount table lives in SectorRecal.java
 * (the TABLE list of Row(ticker, sector, w, t, anchor, note)). This verifier
 * reads that table mechanically from the SOURCE FILE — the single source of
 * truth — and checks three things:
 *
 * (a) COVERAGE: every watchlist ticker has a discount assignment. A ticker
 *     with no row and no documented special case is a FAIL — its DCF runs on
 *     an undocumented discount rate.
 * (b) SECTOR AGREEMENT: each row's sector label is checked against an
 *     independent reference (S&P/MSCI GICS sector assignments, encoded
 *     below with their source). A label that disagrees with the reference,
 *     or that carries a judgmental tilt/blend/premium, goes to the
 *     manual-review queue as WARN — ambiguous mappings are never silently
 *     picked, they are queued with evidence.
 * (c) TABLE FRESHNESS: the table mimics Damodaran's cost-of-capital data,
 *     which Damodaran refreshes monthly. The table carries no
 *     machine-readable asof (the vintage, January 2026, lives only in a
 *     source comment) — that missing asof is itself a finding, and the
 *     vintage is stale against the monthly cadence.
 *
 * Reference used: S&P/MSCI GICS (Global Industry Classification Standard)
 * sector assignments as published by standard market-data vendors. Mega-cap
 * GICS sectors are cross-vendor stable; the exceptions (BRK.B, GRAB, IREN)
 * are documented per ticker below and land in the review queue.
 *
 * This verifier REPORTS only. It never writes any backend.
 *
 * Usage:
 *   java --class-path ~/workspace/java-shared/classes WaccSectorAudit.java --self-test
 *   java --class-path ~/workspace/java-shared/classes WaccSectorAudit.java --verify
 */
public class WaccSectorAudit {

    /** One row parsed from SectorRecal.java's TABLE. */
    static final class TableRow {
        String ticker, sector, anchor, note;
        double w, t;
        TableRow(String ticker, String sector, double w, double t, String anchor, String note) {
            this.ticker = ticker; this.sector = sector; this.w = w; this.t = t;
            this.anchor = anchor; this.note = note;
        }
    }

    /** Independent reference: ticker -> GICS sector/industry + expected Damodaran-style keyword. */
    static final class Ref {
        String gics;          // GICS sector / industry group, vendor consensus
        String expected;      // keyword the table's sector label should contain
        String caveat;        // non-null when the reference itself is debatable
        Ref(String gics, String expected, String caveat) {
            this.gics = gics; this.expected = expected; this.caveat = caveat;
        }
        Ref(String gics, String expected) { this(gics, expected, null); }
    }

    // Source: S&P/MSCI GICS assignments as carried by standard market-data
    // vendors (S&P Capital IQ / exchange listings). Stable for mega-caps;
    // debatable cases carry an explicit caveat and always land in the
    // manual-review queue.
    static final Map<String, Ref> GICS = new LinkedHashMap<>();
    static {
        Ref semi = new Ref("Information Technology / Semiconductors", "semiconductor");
        for (String t : new String[]{"AMD","NVDA","AVGO","INTC","QCOM"}) GICS.put(t, semi);
        Ref sysApp = new Ref("Information Technology / Software", "software (sys/app)");
        for (String t : new String[]{"MSFT","CRM","NOW","ORCL"}) GICS.put(t, sysApp);
        // Damodaran's own sector is "Software (Internet)"; GICS says
        // Communication Services. Deliberate convention — flagged for review.
        Ref internet = new Ref("Communication Services / Interactive Media & Services",
                "software (internet)", "taxonomy mismatch: GICS Communication Services vs Damodaran Software (Internet)");
        GICS.put("GOOG", internet);
        GICS.put("META", internet);
        GICS.put("PLTR", new Ref("Information Technology / Software", "software"));
        Ref pharma = new Ref("Health Care / Pharmaceuticals", "pharma");
        for (String t : new String[]{"LLY","JNJ","PFE"}) GICS.put(t, pharma);
        GICS.put("UNH", new Ref("Health Care / Health Care Providers & Services", "healthcare support"));
        Ref oil = new Ref("Energy / Oil, Gas & Consumable Fuels", "integrated oil");
        GICS.put("CVX", oil);
        GICS.put("XOM", oil);
        GICS.put("NFLX", new Ref("Communication Services / Entertainment", "entertainment"));
        GICS.put("AMZN", new Ref("Consumer Discretionary / Broadline Retail", "retail"));
        GICS.put("RH", new Ref("Consumer Discretionary / Specialty Retail", "retail"));
        GICS.put("CELH", new Ref("Consumer Staples / Beverages", "beverage"));
        GICS.put("WH", new Ref("Consumer Discretionary / Hotels, Resorts & Cruise Lines", "hotel"));
        GICS.put("TSLA", new Ref("Consumer Discretionary / Automobiles", "auto"));
        GICS.put("JPM", new Ref("Financials / Banks", "bank"));
        GICS.put("SOFI", new Ref("Financials / Banks", "bank",
                "SoFi Bank N.A. holds a bank charter; some vendors still show Consumer Finance"));
        GICS.put("BRK.B", new Ref("Financials / Multi-line Insurance", "insurance",
                "conglomerate with no single GICS home; vendors vary"));
        Ref pay = new Ref("Financials / Transaction & Payment Processing Services", "payments network");
        GICS.put("V", pay);
        GICS.put("MA", pay);
        GICS.put("GRAB", new Ref("Industrials / Ground Transportation", "transport",
                "ride-hail super-app; vendors split it across Industrials/Tech — no consensus"));
        GICS.put("IREN", new Ref("unassigned — BTC miner + AI-datacenter hybrid", "none",
                "no vendor consensus: split across IT / Financials / Energy screens"));
        GICS.put("QXO", new Ref("Industrials / Trading Companies & Distributors", "building",
                "building-products distributor (post-Beacon/TopBuild)"));
    }

    // Judgmental adjustments in the table note/anchor always earn a review
    // line, even when the sector label agrees with the reference.
    static final String[] TILT_WORDS = {"tilt", "blend", "premium", "crp"};

    // The documented special case: IREN is deliberately OUT of the table.
    static final String IREN_OMISSION_MARKER = "IREN omitted";
    static final String IREN_OMISSION_EVIDENCE =
            "SectorRecal.java comment: \"IREN omitted (no benchmark; BTC/AI-infra special case keeps its 15%)\"";

    static final String TABLE_FILE = "SectorRecal.java";
    static final String VINTAGE_MARKER = "Damodaran's January 2026 US cost-of-capital table";
    static final LocalDate TABLE_VINTAGE = LocalDate.of(2026, 1, 1);

    static String home() {
        return System.getenv("HOME");
    }

    // ---- table parsing (reads the source of truth, never a copy) --------

    static final Pattern ROW = Pattern.compile(
            "\\s*new Row\\(\"([^\"]+)\",\\s*\"([^\"]+)\",\\s*([\\d.]+),\\s*([\\d.]+),\\s*\"([^\"]*)\",\\s*\"([^\"]*)\"\\)");

    /** Parses SectorRecal.java TABLE into ticker -> row. Empty map on total failure. */
    static Map<String, TableRow> parseTable(String source) {
        Map<String, TableRow> rows = new LinkedHashMap<>();
        Matcher m = ROW.matcher(source);
        while (m.find()) {
            rows.put(m.group(1), new TableRow(m.group(1), m.group(2),
                    Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                    m.group(5), m.group(6)));
        }
        return rows;
    }

    static boolean hasTilt(String anchor, String note) {
        String s = (anchor + " " + note).toLowerCase(Locale.ROOT);
        for (String w : TILT_WORDS) if (s.contains(w)) return true;
        return false;
    }

    static String tiltWords(String anchor, String note) {
        String s = (anchor + " " + note).toLowerCase(Locale.ROOT);
        List<String> hit = new ArrayList<>();
        for (String w : TILT_WORDS) if (s.contains(w)) hit.add(w);
        return String.join(",", hit);
    }

    // ---- the audit ------------------------------------------------------

    static List<Finding> runVerify(LocalDate today, Path dcfCsv, List<String> watchlist) {
        List<Finding> out = new ArrayList<>();
        Path tableFile = dcfCsv.resolve(TABLE_FILE);
        if (!Files.exists(tableFile)) {
            out.add(Finding.fail("A5", "-", "sector_table",
                    "readable " + TABLE_FILE, "file missing", tableFile.toString(),
                    "restore SectorRecal.java; the discount table is the audit's input"));
            return out;
        }
        String source;
        try {
            source = Files.readString(tableFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            out.add(Finding.fail("A5", "-", "sector_table",
                    "readable " + TABLE_FILE, "read error: " + e.getMessage(),
                    tableFile.toString(), "fix file permissions/encoding"));
            return out;
        }
        Map<String, TableRow> table = parseTable(source);
        if (table.isEmpty()) {
            out.add(Finding.fail("A5", "-", "sector_table.parse",
                    ">= 1 parsed Row(...)", "0 rows parsed",
                    TABLE_FILE + " (Row format may have changed)",
                    "update WaccSectorAudit.ROW to the new format"));
            return out;
        }
        out.add(Finding.info("A5", "-", "sector_table.parse",
                "parsed", table.size() + " rows from " + TABLE_FILE,
                TABLE_FILE, "none"));

        // (a) COVERAGE: every watchlist ticker needs an assignment.
        int uncovered = 0;
        for (String t : watchlist) {
            if (table.containsKey(t)) continue;
            if (t.equals("IREN")) {
                // Documented special case — verify the documentation still exists.
                if (source.contains(IREN_OMISSION_MARKER)) {
                    out.add(Finding.info("A5", "IREN", "sector_table.coverage",
                            "documented special case", IREN_OMISSION_EVIDENCE,
                            TABLE_FILE, "none"));
                } else {
                    out.add(Finding.fail("A5", "IREN", "sector_table.coverage",
                            "table row OR documented omission", "neither",
                            TABLE_FILE, "restore the IREN omission comment or add a row"));
                }
                continue;
            }
            uncovered++;
            out.add(Finding.fail("A5", t, "sector_table.coverage",
                    "a sector discount row (or documented special case)",
                    "no row in SectorRecal.TABLE",
                    TABLE_FILE + "; watchlist ticker with no discount assignment",
                    "assign a Damodaran sector via SectorRecal through the proposal pipeline " +
                    "(proposals, never a hand-edit)"));
        }

        // (b) SECTOR AGREEMENT + tilt/blend review queue.
        int queued = 0;
        for (Map.Entry<String, TableRow> e : table.entrySet()) {
            String t = e.getKey();
            TableRow r = e.getValue();
            Ref ref = GICS.get(t);
            if (ref == null) {
                out.add(Finding.warn("A5", t, "sector_assignment.review_queue",
                        "GICS reference entry", "no reference encoded",
                        TABLE_FILE + " row sector=\"" + r.sector + "\"",
                        "add a GICS reference for " + t + " in WaccSectorAudit"));
                queued++;
                continue;
            }
            boolean labelAgrees = r.sector.toLowerCase(Locale.ROOT)
                    .contains(ref.expected.toLowerCase(Locale.ROOT));
            boolean tilt = hasTilt(r.anchor, r.note);
            if (!labelAgrees || tilt || ref.caveat != null) {
                queued++;
                String why = !labelAgrees ? "label-vs-GICS mismatch"
                        : tilt ? "judgmental adjustment (" + tiltWords(r.anchor, r.note) + ")"
                        : "reference-ambiguous sector";
                out.add(Finding.warn("A5", t, "sector_assignment.review_queue",
                        "GICS " + ref.gics + (labelAgrees ? "" : " (label should contain \"" + ref.expected + "\")"),
                        "table sector=\"" + r.sector + "\" w=" + r.w + " — " + why
                                + (ref.caveat != null ? "; " + ref.caveat : ""),
                        TABLE_FILE + " anchor=\"" + r.anchor + "\" note=\"" + r.note + "\"",
                        "manual review: confirm the mapping and record the decision in the table note"));
            }
        }
        out.add(Finding.info("A5", "-", "sector_assignment.review_queue",
                "summary", queued + " rows queued for manual review, " + uncovered + " tickers uncovered",
                TABLE_FILE, "none"));

        // (c) TABLE FRESHNESS: Damodaran refreshes monthly; the table has no
        // machine-readable asof — the vintage lives only in a comment.
        if (!source.contains(VINTAGE_MARKER)) {
            out.add(Finding.fail("A5", "-", "sector_table.damodaran_vintage",
                    "documented Damodaran vintage", "vintage comment missing from " + TABLE_FILE,
                    TABLE_FILE, "restore the vintage documentation"));
        } else {
            long ageMonths = ChronoUnit.MONTHS.between(TABLE_VINTAGE, today.withDayOfMonth(1));
            out.add(Finding.warn("A5", "-", "sector_table.table_asof",
                    "machine-readable asof (yyyy-MM)", "none — vintage only in a source comment",
                    TABLE_FILE + ": \"" + VINTAGE_MARKER + "\"",
                    "add a TABLE_ASOF constant to SectorRecal.java"));
            out.add(Finding.warn("A5", "-", "sector_table.damodaran_vintage",
                    "<= 1 month behind Damodaran's monthly refresh cadence",
                    "January 2026 vintage, ~" + ageMonths + " months old",
                    TABLE_FILE + ": \"" + VINTAGE_MARKER + "\"",
                    "re-pull Damodaran's US cost-of-capital dataset; re-run SectorRecal via the proposal pipeline"));
        }
        return out;
    }

    static List<String> readWatchlist(Path dcfCsv) throws Exception {
        String t = Files.readString(dcfCsv.resolve("../stock_watch/watchlist.json").normalize(),
                StandardCharsets.UTF_8);
        List<String> tickers = new ArrayList<>();
        Matcher m = Pattern.compile("\"([A-Z][A-Z0-9.]{0,6})\"").matcher(t);
        // The watchlist file's "tickers" array is the only all-caps string
        // list; guard by only accepting matches inside the tickers block.
        int arrayStart = t.indexOf("\"tickers\"");
        int arrayEnd = t.indexOf("]", arrayStart);
        m.region(arrayStart, arrayEnd);
        while (m.find()) tickers.add(m.group(1));
        return tickers;
    }

    // ---- self-test ------------------------------------------------------

    static void selfTest() {
        // Parser: the exact Row(...) shape from SectorRecal.java.
        String src = "static final List<Row> TABLE = List.of(\n"
                + "  new Row(\"AMD\",  \"semiconductor\",        0.1050, 0.030, \"Damodaran 10.55%\", \"at benchmark\"),\n"
                + "  new Row(\"GRAB\", \"internet + SEA country risk\", 0.1225, 0.030, \"internet 10.66% + ~1.6% CRP\", \"was 11.5%\")\n"
                + ");";
        Map<String, TableRow> rows = parseTable(src);
        SelfTest.checkEq("parses 2 rows", 2, rows.size());
        SelfTest.checkEq("AMD sector", "semiconductor", rows.get("AMD").sector);
        SelfTest.checkEq("AMD w", 0.1050, rows.get("AMD").w);
        SelfTest.checkEq("GRAB anchor", "internet 10.66% + ~1.6% CRP", rows.get("GRAB").anchor);
        SelfTest.checkEq("garbage parses to 0", 0, parseTable("no rows here").size());
        // Tilt detection.
        SelfTest.check("tilt word found", hasTilt("Damodaran 5.07% + transition-risk tilt", "was 8.0%"));
        SelfTest.check("blend word found", hasTilt("retail 7.27% / internet 10.66% blend", "x"));
        SelfTest.check("clean row no tilt", !hasTilt("Damodaran 10.55%", "at benchmark"));
        SelfTest.checkEq("tilt words listed", "tilt", tiltWords("x tilt y", "z"));
        // Agreement logic on synthetic rows.
        TableRow amd = rows.get("AMD");
        SelfTest.check("AMD label agrees with GICS ref",
                amd.sector.toLowerCase(Locale.ROOT).contains(GICS.get("AMD").expected.toLowerCase(Locale.ROOT)));
        TableRow grab = rows.get("GRAB");
        SelfTest.check("GRAB label disagrees with GICS ref",
                !grab.sector.toLowerCase(Locale.ROOT).contains(GICS.get("GRAB").expected.toLowerCase(Locale.ROOT)));
        // Every watchlist-shaped ticker used in tests has a GICS entry.
        for (String t : new String[]{"AMD","NVDA","MSFT","GOOG","JPM","BRK.B","GRAB","IREN","QXO"})
            SelfTest.check("GICS ref present for " + t, GICS.containsKey(t));
        // Reference covers all 32 watchlist tickers.
        SelfTest.checkEq("GICS covers 32 tickers", 32, GICS.size());
        // Vintage arithmetic is deterministic.
        long months = ChronoUnit.MONTHS.between(TABLE_VINTAGE, LocalDate.of(2026, 9, 29).withDayOfMonth(1));
        SelfTest.checkEq("vintage age Sep 2026", 8L, months);
        // Finding.split: FAIL -> failures (exit 1), WARN -> infos.
        List<Finding> f = new ArrayList<>();
        f.add(Finding.warn("A5", "GRAB", "sector_assignment.review_queue", "x", "y", "e", "r"));
        f.add(Finding.fail("A5", "QXO", "sector_table.coverage", "x", "y", "e", "r"));
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();
        Finding.split(f, infos, failures);
        SelfTest.checkEq("split infos", 1, infos.size());
        SelfTest.checkEq("split failures", 1, failures.size());
        SelfTest.check("anyFail", Finding.anyFail(f));
        SelfTest.finish();
    }

    // ---- entry point ----------------------------------------------------

    public static void main(String[] args) throws Exception {
        boolean test = args.length == 1 && args[0].equals("--self-test");
        boolean verify = args.length == 1 && args[0].equals("--verify");
        if (!test && !verify) {
            System.out.println("WaccSectorAudit.java — A5 audit: sector discount table coverage/agreement/freshness.");
            System.out.println("  java WaccSectorAudit.java --self-test   unit checks (prove-can-fail)");
            System.out.println("  java WaccSectorAudit.java --verify      audit SectorRecal table vs GICS reference");
            System.exit(2);
            return;
        }
        if (test) {
            selfTest();
            return;
        }
        Path dcfCsv = Paths.get(home(), "workspace", "dcf_csv");
        List<String> watchlist = readWatchlist(dcfCsv);
        List<Finding> findings = runVerify(LocalDate.now(), dcfCsv, watchlist);
        List<String> infos = new ArrayList<>(), failures = new ArrayList<>();
        Finding.split(findings, infos, failures);
        Verify.report("VERIFY OK: every watchlist ticker has a sector discount assignment (review queue in INFO)",
                "VERIFY FAILED: sector discount coverage problems",
                infos, failures);
    }
}
