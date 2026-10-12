import java.io.FileInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AuditWorkbook — Java port of audit_workbook.py.
 *
 * <p>Audits the 31-Company DCF Valuation Model workbook against
 * {@code .src/workbook_data.json}: sheet inventory and tab order,
 * per-company Comparison columns (ticker/market/notebooklm/creator/analyst
 * hardcoded; fixed as a live formula to the model tab), the DCF formula
 * skeleton on every model tab, and — strongest check — an INDEPENDENT Java
 * replication of the 10-year fixed-methodology model for all 31 companies
 * vs the supplied fixed values. No cached Excel values needed; works on a
 * freshly generated file.
 *
 * <p>Success prints exactly:
 * <pre>
 * AUDIT_OK — 38 sheets / 31 model tabs; 31/31 independent DCF replications match supplied fixed values; all Comparison links live.
 * </pre>
 * Any failure throws AssertionError with a Python-style repr of the
 * failure list, mirroring {@code assert not fail, fail}.
 *
 * <p>Usage:
 * <pre>
 *   ~/workspace/java/jdk-21/bin/java -Dfile.encoding=UTF-8 -cp "/home/hatch/workspace/lib/poi/*" \
 *       AuditWorkbook.java [rootDirOverride]
 * </pre>
 */
public class AuditWorkbook {

    /**
     * Home directory: Python's os.path.expanduser("~/...") honors $HOME,
     * but the JVM's user.home comes from the passwd entry (/root here).
     * Always prefer $HOME so ~/workspace resolves to /home/hatch/workspace.
     */
    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    public static void main(String[] args) throws Exception {
        // Force UTF-8 stdout so the em-dash in AUDIT_OK prints correctly
        // even when the shell locale is POSIX (the -Dfile.encoding flag
        // alone does not set stdout.encoding on this JVM).
        System.setOut(new java.io.PrintStream(System.out, true,
                StandardCharsets.UTF_8));
        Path root = args.length > 0
                ? Path.of(args[0])
                : Path.of(homeDir(), "workspace/your_files/dcf-valuations");
        JSONObject data = new JSONObject(
                Files.readString(root.resolve(".src/workbook_data.json"), StandardCharsets.UTF_8));
        JSONArray companies = data.getJSONArray("companies");
        Path p = root.resolve("31-Company DCF Valuation Model.xlsx");

        // Formulas: POI reads the stored formula string (no leading "=")
        // and the cached value left by the recalculation step, exactly like
        // openpyxl's data_only=False / data_only=True pair.
        List<String> fail = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new FileInputStream(p.toFile()))) {

            // 1. sheet inventory and tab order
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                names.add(wb.getSheetName(i));
            }
            if (names.size() != 38) {
                fail.add(pyRepr("sheets", names.size()));
            }
            long modelTabs = names.stream().filter(s -> s.startsWith("DCF ")).count();
            if (modelTabs != 31) {
                fail.add(pyRepr("model-tabs", names));
            }
            List<String> expected = new ArrayList<>(List.of(
                    "Cover", "Comparison", "Assumptions", "Comparison Chart", "Disagreements"));
            for (int i = 0; i < companies.length(); i++) {
                expected.add("DCF " + companies.getJSONObject(i).getString("ticker"));
            }
            expected.add("Methodology");
            expected.add("Sources");
            if (!names.equals(expected)) {
                fail.add("('tab-order',)");
            }

            // 2. per-company checks (Comparison rows start at row 4, 1-based)
            XSSFSheet comparison = wb.getSheet("Comparison");
            for (int i = 0; i < companies.length(); i++) {
                JSONObject c = companies.getJSONObject(i);
                String t = c.getString("ticker");
                int r = 4 + i; // 1-based row, mirrors enumerate(companies, 4)
                if (!t.equals(cached(comparison, r, 2))) {
                    fail.add(pyRepr(t, "ticker"));
                }
                if (!doubleEquals(cached(comparison, r, 3), c.getDouble("market"))) {
                    fail.add(pyRepr(t, "market"));
                }
                XSSFCell fixedCell = cell(comparison, r, 4);
                String fixedF = fixedCell.getCellType() == CellType.FORMULA
                        ? "=" + fixedCell.getCellFormula() : "";
                if (!fixedF.equals("=ROUND('DCF " + t + "'!$B$36,2)")) {
                    fail.add(pyRepr(t, "fixed-formula"));
                }
                double rep = replicate(c);
                if (Math.abs(rep - c.getDouble("fixed")) > 0.0051) {
                    fail.add("('" + t + "', 'model-math', "
                            + pyFloat(round4(rep)) + ", " + pyFloat(c.getDouble("fixed")) + ")");
                }
                XSSFCell b36 = cell(wb.getSheet("DCF " + t), 36, 2);
                String b36f = b36.getCellType() == CellType.FORMULA
                        ? "=" + b36.getCellFormula() : "";
                if (!b36f.equals("=B34/B35")) {
                    fail.add(pyRepr(t, "B36-formula"));
                }
                Object nbRaw = c.isNull("notebooklm") ? null : c.get("notebooklm");
                Object nbExpected = (nbRaw instanceof Number)
                        ? ((Number) nbRaw).doubleValue() : "—";
                if (!valueEquals(cached(comparison, r, 5), nbExpected)) {
                    fail.add(pyRepr(t, "notebooklm"));
                }
                Object creatorExpected = c.isNull("creator") ? "—"
                        : ((Number) c.get("creator")).doubleValue();
                if (!valueEquals(cached(comparison, r, 6), creatorExpected)) {
                    fail.add(pyRepr(t, "creator"));
                }
                // Column 8 is "Creator buy price" (added to the workbook 2026-09-27;
                // free text from workbook_data.json, or "—" when none). The
                // original audit predates this column, so it is checked here.
                Object buyExpected = c.isNull("creator_buy_price") ? "—"
                        : c.getString("creator_buy_price");
                if (!buyExpected.equals(cached(comparison, r, 7))) {
                    fail.add(pyRepr(t, "buy-price"));
                }
                Object analystExpected = c.isNull("analyst") ? "—"
                        : ((Number) c.get("analyst")).doubleValue();
                if (!valueEquals(cached(comparison, r, 8), analystExpected)) {
                    fail.add(pyRepr(t, "analyst"));
                }
            }

            // 3. chart, disagreements — idea #41: data-driven, never hardcoded.
            // The chart holds one row per company that has both a fixed DCF
            // and a verified NotebookLM value. The sheet has 3 header rows
            // (title, subtitle, column headers — all 1-based in the builder)
            // plus one row per chart company, so the expected row count is
            // derived from the data, not a magic number.
            int chartCompanies = 0;
            for (int i = 0; i < companies.length(); i++) {
                JSONObject c = companies.getJSONObject(i);
                if (!c.isNull("fixed") && !c.isNull("notebooklm")
                        && c.get("notebooklm") instanceof Number) {
                    chartCompanies++;
                }
            }
            XSSFSheet chartSheet = wb.getSheet("Comparison Chart");
            if (chartSheet.getLastRowNum() + 1 != 3 + chartCompanies) {
                fail.add(pyRepr("chart-rows", chartSheet.getLastRowNum() + 1));
            }
            // The Disagreements sheet must hold at least one block per
            // disagreement entry in the data — an exact count is never
            // asserted (the count legitimately changes as values verify).
            int disagreementCount = data.getJSONArray("disagreements").length();
            int parenBlocks = 0;
            for (org.apache.poi.ss.usermodel.Row row : wb.getSheet("Disagreements")) {
                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                    Object v = cachedValue((XSSFCell) cell);
                    if (v instanceof String s && s.endsWith(")")) {
                        parenBlocks++;
                    }
                }
            }
            if (parenBlocks < disagreementCount) {
                fail.add(pyRepr("disagreement-blocks", parenBlocks));
            }
        }

        if (!fail.isEmpty()) {
            throw new AssertionError("[" + String.join(", ", fail) + "]");
        }
        System.out.println("AUDIT_OK — 38 sheets / 31 model tabs; 31/31 independent DCF "
                + "replications match supplied fixed values; all Comparison links live.");
    }

    /**
     * Independent replication of the fixed methodology: 10-year FCF
     * projection, linear 5-year growth fade (years 6-10) to terminal growth,
     * mid-year discounting, Gordon terminal value. Mirrors replicate().
     */
    static double replicate(JSONObject c) {
        JSONObject a = c.getJSONObject("assumptions");
        double base = a.getDouble("base");
        double g = a.getDouble("g");
        double w = a.getDouble("w");
        double t = a.getDouble("t");
        double shares = a.getDouble("shares");
        double debt = a.getDouble("netdebt");
        String kind = a.getString("kind");
        double sm = a.isNull("start_margin") ? 0 : a.getDouble("start_margin");
        double em = a.isNull("end_margin") ? 0 : a.getDouble("end_margin");
        double pvSum = 0.0;
        double rev = 0.0;
        double fcf = 0.0;
        // Practitioner guardrail (2026-09-28): sustained growth capped at 15%.
        double gc = Math.min(g, 0.15);
        for (int y = 1; y <= 10; y++) {
            double gy = y <= 5 ? gc : gc - (y - 5) * (gc - t) / 5;
            if (kind.equals("REV")) {
                rev = (y == 1) ? base * (1 + gy) : rev * (1 + gy);
                double m = y <= 5 ? sm + (em - sm) * y / 5 : em;
                fcf = rev * m;
            } else {
                fcf = (y == 1) ? base * (1 + gy) : fcf * (1 + gy);
            }
            pvSum += fcf / Math.pow(1 + w, y - 0.5);
        }
        double tv = fcf * (1 + t) / (w - t);
        double ev = pvSum + tv / Math.pow(1 + w, 9.5);
        double eq = kind.equals("FCFE") ? ev : ev - debt;
        return eq / shares;
    }

    // ------------------------------------------------------------------
    // Cell helpers.
    // ------------------------------------------------------------------

    /** 1-based cell access (the cell is expected to exist). */
    static XSSFCell cell(XSSFSheet ws, int r, int c) {
        return ws.getRow(r - 1).getCell(c - 1);
    }

    /**
     * Cached display value of a cell: for formula cells this is the value
     * left by the recalculation step (mirrors openpyxl data_only=True).
     */
    static Object cachedValue(XSSFCell cell) {
        if (cell == null) {
            return null;
        }
        CellType t = cell.getCellType();
        if (t == CellType.FORMULA) {
            t = cell.getCachedFormulaResultType();
        }
        return switch (t) {
            case NUMERIC -> cell.getNumericCellValue();
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            default -> null;
        };
    }

    static Object cached(XSSFSheet ws, int r, int c) {
        org.apache.poi.ss.usermodel.Row row = ws.getRow(r - 1);
        if (row == null) {
            return null;
        }
        return cachedValue((XSSFCell) row.getCell(c - 1));
    }

    static boolean doubleEquals(Object cached, double expected) {
        return cached instanceof Double d && d == expected;
    }

    static boolean valueEquals(Object cached, Object expected) {
        if (expected instanceof Double e) {
            return cached instanceof Double d && d.equals(e);
        }
        return expected.equals(cached);
    }

    // ------------------------------------------------------------------
    // Python-repr helpers for the failure list (only used when failing).
    // ------------------------------------------------------------------

    /** Like Python's round(x, 4) — banker's rounding, as Python does. */
    static double round4(double x) {
        return BigDecimal.valueOf(x).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python repr() of a float, e.g. 117.64 (never "117.640"). */
    static String pyFloat(double x) {
        if (x == Math.rint(x) && !Double.isInfinite(x) && Math.abs(x) < 1e16) {
            return String.valueOf((long) x) + ".0";
        }
        return BigDecimal.valueOf(x).stripTrailingZeros().toPlainString();
    }

    static String pyStr(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    static String pyRepr(String a, Object b) {
        String bs = b instanceof String s ? pyStr(s)
                : b instanceof List<?> l ? pyList(l)
                : String.valueOf(b);
        return "(" + pyStr(a) + ", " + bs + ")";
    }

    static String pyList(List<?> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object o = l.get(i);
            sb.append(o instanceof String s ? pyStr(s) : String.valueOf(o));
        }
        return sb.append("]").toString();
    }
}
