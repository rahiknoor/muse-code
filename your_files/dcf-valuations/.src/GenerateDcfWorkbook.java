import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.PrintOrientation;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.BarGrouping;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFChartLegend;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.ss.util.CellReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GenerateDcfWorkbook — Java port of generate_31_company_dcf.py.
 *
 * <p>Builds the "DCF Valuation Model.xlsx" workbook from
 * {@code .src/workbook_data.json}: a Cover sheet, a Comparison sheet, an
 * Assumptions sheet, one formula-driven "DCF &lt;ticker&gt;" model sheet per
 * holding, a Disagreements sheet, a Comparison Chart sheet (with a real column
 * chart), a Methodology sheet and a Sources ledger.
 *
 * <p>Every cell value, formula, number format, font, fill, border and
 * alignment mirrors the Python original. After writing, the workbook is
 * passed through the same LibreOffice recalculation script the Python
 * version uses, so formula cells carry cached values for viewers that do
 * not recalculate on open.
 *
 * <p>Usage (defaults read/write the real valuation folder):
 * <pre>
 *   ~/workspace/java/jdk-21/bin/java -Dfile.encoding=UTF-8 -cp "/home/hatch/workspace/lib/poi/*" \
 *       GenerateDcfWorkbook.java [rootDirOverride]
 * </pre>
 * An optional first argument overrides the valuation root (used for testing
 * in /tmp so the real workbook is never touched).
 */
public class GenerateDcfWorkbook {

    // ------------------------------------------------------------------
    // Paths (mirrors the Python script's ROOT / SRC / DATA_PATH / OUT).
    // ------------------------------------------------------------------
    static Path ROOT;
    static Path SRC;
    static Path DATA_PATH;
    static Path OUT;

    /**
     * Home directory: Python's os.path.expanduser("~/...") honors $HOME,
     * but the JVM's user.home comes from the passwd entry (/root here).
     * Always prefer $HOME so ~/workspace resolves to /home/hatch/workspace.
     */
    private static String homeDir() {
        String h = System.getenv("HOME");
        return (h != null && !h.isEmpty()) ? h : System.getProperty("user.home");
    }

    /** Default valuation root: ~/workspace/your_files/dcf-valuations. */
    private static Path defaultRoot() {
        return Path.of(homeDir(), "workspace/your_files/dcf-valuations");
    }

    /**
     * Holdings count derived at render time from the holdings list
     * ({@code stock_watch/watchlist.json} tickers) — never a hardcoded number.
     * Falls back to {@code fallback} when the watchlist is unreadable (e.g. the
     * /tmp test override), so the structural checks still guard completeness.
     */
    private static int holdingsCount(int fallback) {
        try {
            Path ws = ROOT.getParent().getParent();
            String raw = Files.readString(ws.resolve("stock_watch/watchlist.json"), StandardCharsets.UTF_8);
            JSONArray tickers = new JSONObject(raw).getJSONArray("tickers");
            return tickers.length();
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Palette (same hex values as the Python script).
    // ------------------------------------------------------------------
    static final String NAVY = "17324D";
    static final String BLUE = "2F75B5";
    static final String TEAL = "1B7F79";
    static final String LIGHT_TEAL = "DDEFEA";
    static final String LIGHT_BLUE = "EAF2F8";
    static final String GOLD = "E9B949";
    static final String PALE_GOLD = "FFF2CC";
    static final String RED = "C00000";
    static final String PALE_RED = "FCE4D6";
    static final String GREEN = "008000";
    static final String PALE_GREEN = "E2F0D9";
    static final String GRAY = "667085";
    static final String LIGHT_GRAY = "F3F4F6";
    static final String MID_GRAY = "D0D5DD";
    static final String WHITE = "FFFFFF";
    static final String BLACK = "000000";
    static final String DASH = "—";
    static final String ZEBRA = "F8FAFC";
    static final String DARK_GOLD = "7A5C00";

    // Number formats (identical strings to the Python version).
    static final String CURRENCY_FMT = "$#,##0.00;[Red]($#,##0.00);-";
    static final String MM_FMT = "$#,##0.0;[Red]($#,##0.0);-";
    static final String NUMBER_FMT = "#,##0.000;[Red](#,##0.000);-";
    static final String PCT_FMT = "0.0%;[Red](0.0%);-";

    // ------------------------------------------------------------------
    // Workbook-wide state: the workbook itself plus caches so identical
    // styles / fonts / number formats are created only once.
    // ------------------------------------------------------------------
    static XSSFWorkbook wb;
    static CreationHelper helper;
    static final Map<StyleSpec, XSSFCellStyle> STYLE_CACHE = new HashMap<>();
    static final Map<String, XSSFFont> FONT_CACHE = new HashMap<>();
    static final Map<String, Short> FORMAT_CACHE = new HashMap<>();
    static final Map<String, XSSFCellStyle> CLONE_CACHE = new HashMap<>();

    /**
     * Clone a style and apply a fixed modification, caching the result by
     * call-site key plus source style index. Without this, per-cell clones
     * create thousands of duplicate styles, and the resulting styles.xml is
     * so repetitive that POI's zip-bomb detector refuses to open the file.
     * The cached result is style-identical to an uncached clone.
     */
    static XSSFCellStyle modStyle(String key, XSSFCellStyle base,
                                  java.util.function.Consumer<XSSFCellStyle> mod) {
        String ck = key + "|" + base.getIndex();
        XSSFCellStyle hit = CLONE_CACHE.get(ck);
        if (hit != null) {
            return hit;
        }
        XSSFCellStyle s = wb.createCellStyle();
        s.cloneStyleFrom(base);
        mod.accept(s);
        CLONE_CACHE.put(ck, s);
        return s;
    }

    /** Thin MID_GRAY borders on all four sides (shared modStyle lambda). */
    static void thinMidGray(XSSFCellStyle s) {
        XSSFColor b2 = hexColor(MID_GRAY);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setLeftBorderColor(b2);
        s.setRightBorderColor(b2);
        s.setTopBorderColor(b2);
        s.setBottomBorderColor(b2);
    }

    /**
     * Immutable description of a full cell style. Records give us
     * equals/hashCode for free, so identical specs share one POI style.
     * Any field left null means "leave at the POI default".
     */
    record StyleSpec(
            String fontName, Integer fontSize, Boolean bold, Boolean italic,
            Boolean underline, String fontColor, String fillColor,
            Boolean bordered, String borderColor,
            HorizontalAlignment hAlign, VerticalAlignment vAlign,
            Boolean wrap, String numFmt) {

        static StyleSpec blank() {
            return new StyleSpec(null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }

        StyleSpec withFont(String name, int size) {
            return new StyleSpec(name, size, bold, italic, underline, fontColor,
                    fillColor, bordered, borderColor, hAlign, vAlign, wrap, numFmt);
        }

        StyleSpec withBold() {
            return new StyleSpec(fontName, fontSize, true, italic, underline, fontColor,
                    fillColor, bordered, borderColor, hAlign, vAlign, wrap, numFmt);
        }

        StyleSpec withItalic() {
            return new StyleSpec(fontName, fontSize, bold, true, underline, fontColor,
                    fillColor, bordered, borderColor, hAlign, vAlign, wrap, numFmt);
        }

        StyleSpec withUnderline() {
            return new StyleSpec(fontName, fontSize, bold, italic, true, fontColor,
                    fillColor, bordered, borderColor, hAlign, vAlign, wrap, numFmt);
        }

        StyleSpec withFontColor(String hex) {
            return new StyleSpec(fontName, fontSize, bold, italic, underline, hex,
                    fillColor, bordered, borderColor, hAlign, vAlign, wrap, numFmt);
        }

        StyleSpec withFill(String hex) {
            return new StyleSpec(fontName, fontSize, bold, italic, underline, fontColor,
                    hex, bordered, borderColor, hAlign, vAlign, wrap, numFmt);
        }

        StyleSpec withBorder(String hex) {
            return new StyleSpec(fontName, fontSize, bold, italic, underline, fontColor,
                    fillColor, true, hex, hAlign, vAlign, wrap, numFmt);
        }

        StyleSpec withAlign(HorizontalAlignment h, VerticalAlignment v) {
            return new StyleSpec(fontName, fontSize, bold, italic, underline, fontColor,
                    fillColor, bordered, borderColor, h, v, wrap, numFmt);
        }

        StyleSpec withWrap() {
            return new StyleSpec(fontName, fontSize, bold, italic, underline, fontColor,
                    fillColor, bordered, borderColor, hAlign, vAlign, true, numFmt);
        }

        StyleSpec withFormat(String fmt) {
            return new StyleSpec(fontName, fontSize, bold, italic, underline, fontColor,
                    fillColor, bordered, borderColor, hAlign, vAlign, wrap, fmt);
        }
    }

    // ------------------------------------------------------------------
    // Low-level helpers.
    // ------------------------------------------------------------------

    /** "17324D" -> POI color. openpyxl pads 6-digit hex with a "00" alpha
     *  prefix ("FFFFFF" -> rgb "00FFFFFF"); we emit the same 4-byte ARGB so
     *  the XML matches byte-for-byte (Excel/LibreOffice render it opaque). */
    static XSSFColor hexColor(String hex) {
        String h = hex.length() == 8 ? hex.substring(2) : hex;
        byte[] argb = new byte[4];
        argb[0] = 0x00; // openpyxl-style alpha prefix
        for (int i = 0; i < 3; i++) {
            argb[i + 1] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return new XSSFColor(argb, null);
    }

    static XSSFFont cachedFont(String name, int size, boolean bold, boolean italic,
                               boolean underline, String colorHex) {
        String key = name + "|" + size + "|" + bold + "|" + italic + "|" + underline + "|" + colorHex;
        return FONT_CACHE.computeIfAbsent(key, k -> {
            XSSFFont f = wb.createFont();
            f.setFontName(name);
            f.setFontHeightInPoints((short) size);
            f.setBold(bold);
            f.setItalic(italic);
            if (underline) {
                f.setUnderline(FontUnderline.SINGLE);
            }
            if (colorHex != null) {
                f.setColor(hexColor(colorHex));
            }
            return f;
        });
    }

    /** Build (or reuse) the POI cell style for a spec. */
    static XSSFCellStyle style(StyleSpec spec) {        return STYLE_CACHE.computeIfAbsent(spec, s -> {
            XSSFCellStyle st = wb.createCellStyle();
            if (s.fontName() != null) {
                st.setFont(cachedFont(
                        s.fontName(),
                        s.fontSize() != null ? s.fontSize() : 10,
                        Boolean.TRUE.equals(s.bold()),
                        Boolean.TRUE.equals(s.italic()),
                        Boolean.TRUE.equals(s.underline()),
                        s.fontColor()));
            }
            if (s.fillColor() != null) {
                st.setFillForegroundColor(hexColor(s.fillColor()));
                st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (Boolean.TRUE.equals(s.bordered())) {
                XSSFColor bc = hexColor(s.borderColor() != null ? s.borderColor() : MID_GRAY);
                st.setBorderLeft(BorderStyle.THIN);
                st.setBorderRight(BorderStyle.THIN);
                st.setBorderTop(BorderStyle.THIN);
                st.setBorderBottom(BorderStyle.THIN);
                st.setLeftBorderColor(bc);
                st.setRightBorderColor(bc);
                st.setTopBorderColor(bc);
                st.setBottomBorderColor(bc);
            }
            if (s.hAlign() != null) {
                st.setAlignment(s.hAlign());
            }
            if (s.vAlign() != null) {
                st.setVerticalAlignment(s.vAlign());
            }
            if (Boolean.TRUE.equals(s.wrap())) {
                st.setWrapText(true);
            }
            if (s.numFmt() != null) {
                short idx = FORMAT_CACHE.computeIfAbsent(s.numFmt(),
                        f -> wb.createDataFormat().getFormat(f));
                st.setDataFormat(idx);
            }
            return st;
        });
    }

    /** 1-based cell access that creates the row/cell on demand (like ws.cell()). */
    static XSSFCell cell(XSSFSheet ws, int r, int c) {
        XSSFRow row = ws.getRow(r - 1);
        if (row == null) {
            row = ws.createRow(r - 1);
        }
        XSSFCell cell = row.getCell(c - 1);
        if (cell == null) {
            cell = row.createCell(c - 1);
        }
        return cell;
    }

    /** 1-based column number -> "A", "B", ... (like openpyxl's get_column_letter). */
    static String colLetter(int col) {
        StringBuilder sb = new StringBuilder();
        while (col > 0) {
            col--;
            sb.insert(0, (char) ('A' + col % 26));
            col /= 26;
        }
        return sb.toString();
    }

    /** Like openpyxl's quote_sheetname: always single-quote, doubling inner quotes. */
    static String quoteSheetName(String name) {
        return "'" + name.replace("'", "''") + "'";
    }

    static void merge(XSSFSheet ws, int r1, int c1, int r2, int c2) {
        ws.addMergedRegion(new CellRangeAddress(r1 - 1, r2 - 1, c1 - 1, c2 - 1));
    }

    // ------------------------------------------------------------------
    // Sheet-building primitives mirroring the Python helpers.
    // ------------------------------------------------------------------

    /** Page setup shared by every sheet (mirrors base_sheet()). */
    static void baseSheet(XSSFSheet ws, boolean landscape) {
        ws.setDisplayGridlines(false);
        ws.createFreezePane(0, 3); // "A4"
        ws.getPrintSetup().setOrientation(
                landscape ? PrintOrientation.LANDSCAPE : PrintOrientation.PORTRAIT);
        ws.getPrintSetup().setFitWidth((short) 1);
        ws.getPrintSetup().setFitHeight((short) 0);
        ws.setFitToPage(true);
        ws.setMargin(Sheet.LeftMargin, 0.25);
        ws.setMargin(Sheet.RightMargin, 0.25);
        ws.setMargin(Sheet.TopMargin, 0.4);
        ws.setMargin(Sheet.BottomMargin, 0.4);
        ws.setMargin(Sheet.HeaderMargin, 0.2);
        ws.setMargin(Sheet.FooterMargin, 0.2);
        ws.getFooter().setCenter("DCF Valuation Model");
        ws.getFooter().setRight("Page &P of &N");
    }

    /** Big navy title bar + optional italic subtitle (mirrors title()). */
    static void title(XSSFSheet ws, String text, String subtitle, int endCol) {
        merge(ws, 1, 1, 1, endCol);
        XSSFCell c = cell(ws, 1, 1);
        c.setCellValue(text);
        c.setCellStyle(style(StyleSpec.blank()
                .withFont("Liberation Sans", 18).withBold().withFontColor(WHITE)
                .withFill(NAVY)
                .withAlign(HorizontalAlignment.LEFT, VerticalAlignment.CENTER)));
        ws.getRow(0).setHeightInPoints(30);
        if (subtitle != null) {
            merge(ws, 2, 1, 2, endCol);
            XSSFCell c2 = cell(ws, 2, 1);
            c2.setCellValue(subtitle);
            c2.setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 10).withItalic().withFontColor(GRAY)
                    .withWrap()
                    .withAlign(null, VerticalAlignment.TOP)));
            ws.getRow(1).setHeightInPoints(28);
        }
    }

    /** Colored header row (mirrors header_row()). */
    static void headerRow(XSSFSheet ws, int row, int startCol, int endCol, String fill) {
        for (int col = startCol; col <= endCol; col++) {
            XSSFCell c = cell(ws, row, col);
            c.setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 10).withBold().withFontColor(WHITE)
                    .withFill(fill).withBorder(MID_GRAY)
                    .withAlign(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
                    .withWrap()));
        }
        ws.getRow(row - 1).setHeightInPoints(34);
    }

    /**
     * Applies the body font to every valued cell still on the default font
     * (mirrors set_body_font()).
     */
    static void setBodyFont(XSSFSheet ws) {
        for (org.apache.poi.ss.usermodel.Row row : ws) {
            if (row.getRowNum() == 0) {
                continue; // never touch the title row
            }
            for (org.apache.poi.ss.usermodel.Cell cell : row) {
                if (cell.getCellType() == CellType.BLANK) {
                    continue;
                }
                XSSFCell xcell = (XSSFCell) cell;
                XSSFFont f = xcell.getCellStyle().getFont();
                String name = f.getFontName();
                if (name == null || name.equals("Calibri")) {
                    String colorHex = null;
                    XSSFColor xc = f.getXSSFColor();
                    if (xc != null && xc.getRGB() != null) {
                        byte[] rgb = xc.getRGB();
                        colorHex = String.format("%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
                    }
                    if (colorHex == null) {
                        colorHex = BLACK; // openpyxl default (theme) color -> black
                    }
                    // Keep the cell's existing fill/border/alignment/number
                    // format; only the font changes (mirrors the Python).
                    final boolean fb = f.getBold();
                    final boolean fi = f.getItalic();
                    final String fch = colorHex;
                    String fkey = "bodyfont|" + fb + "|" + fi + "|" + fch;
                    XSSFCellStyle st = modStyle(fkey, xcell.getCellStyle(), s -> {
                        s.setFont(cachedFont("Liberation Sans", 10, fb,
                                fi, false, fch));
                    });
                    xcell.setCellStyle(st);
                }
            }
        }
    }

    /** Cell comment authored by "Muse" (mirrors add_comment()). */
    static void addComment(XSSFSheet ws, XSSFCell cell, String text) {
        XSSFDrawing drawing = ws.createDrawingPatriarch();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setRow1(cell.getRowIndex());
        anchor.setCol2(cell.getColumnIndex() + 2);
        anchor.setRow2(cell.getRowIndex() + 4);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(text));
        comment.setAuthor("Muse");
        cell.setCellComment(comment);
    }

    /**
     * Blue hardcoded input cell; null becomes "—" (mirrors set_hardcoded()).
     */
    static void setHardcoded(XSSFSheet ws, XSSFCell cell, Object value,
                             String source, String numFormat) {
        StyleSpec spec = StyleSpec.blank()
                .withFont("Liberation Sans", 10).withFontColor(BLUE);
        if (value == null) {
            cell.setCellValue(DASH);
        } else if (value instanceof Number n) {
            setNumber(cell, n);
            if (numFormat != null) {
                spec = spec.withFormat(numFormat);
            }
        } else {
            cell.setCellValue(value.toString());
        }
        cell.setCellStyle(style(spec));
        if (source != null) {
            addComment(ws, cell, source);
        }
    }

    /**
     * Write a JSON number to a cell, mirroring openpyxl's serialization:
     * a whole-number float is written without the decimal point ("6735",
     * not "6735.0"), so doubles go through the raw value when integral.
     */
    static void setNumber(XSSFCell cell, Number n) {
        double d = n.doubleValue();
        if (!Double.isInfinite(d) && !Double.isNaN(d)
                && d == Math.rint(d) && Math.abs(d) < 1e15) {
            cell.setCellType(CellType.NUMERIC);
            cell.getCTCell().setV(Long.toString((long) d));
        } else {
            cell.setCellValue(d);
        }
    }

    /** Write a plain int (projection year, methodology index) like openpyxl. */
    static void setNumber(XSSFCell cell, int v) {
        cell.setCellType(CellType.NUMERIC);
        cell.getCTCell().setV(Integer.toString(v));
    }

    /**
     * Formula cell: black text, or green when it links across sheets
     * (mirrors set_formula()). The formula is given WITH the leading "=",
     * exactly as in the Python script.
     */
    static void setFormula(XSSFSheet ws, XSSFCell cell, String formulaWithEq,
                           String source, String numFormat, boolean crossSheet) {
        String formula = formulaWithEq.startsWith("=")
                ? formulaWithEq.substring(1) : formulaWithEq;
        cell.setCellFormula(formula);
        StyleSpec spec = StyleSpec.blank()
                .withFont("Liberation Sans", 10)
                .withFontColor(crossSheet ? GREEN : BLACK);
        if (numFormat != null) {
            spec = spec.withFormat(numFormat);
        }
        cell.setCellStyle(style(spec));
        if (source != null) {
            addComment(ws, cell, source);
        }
    }

    /** Internal document hyperlink, e.g. "'DCF NVDA'!A1" (mirrors "#'...'!A1"). */
    static void setDocHyperlink(XSSFCell cell, String sheetName, String cellRef) {
        Hyperlink link = helper.createHyperlink(HyperlinkType.DOCUMENT);
        link.setAddress(quoteSheetName(sheetName) + "!" + cellRef);
        cell.setHyperlink(link);
    }

    // ------------------------------------------------------------------
    // Entry point.
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        ROOT = args.length > 0
                ? Path.of(args[0])
                : defaultRoot();
        SRC = ROOT.resolve(".src");
        DATA_PATH = SRC.resolve("workbook_data.json");
        OUT = ROOT.resolve("DCF Valuation Model.xlsx");

        JSONObject data = new JSONObject(
                Files.readString(DATA_PATH, StandardCharsets.UTF_8));
        JSONArray companies = data.getJSONArray("companies");
        Map<String, JSONObject> companyByTicker = new HashMap<>();
        List<JSONObject> computable = new ArrayList<>();
        for (int i = 0; i < companies.length(); i++) {
            JSONObject c = companies.getJSONObject(i);
            companyByTicker.put(c.getString("ticker"), c);
            JSONObject a = c.optJSONObject("assumptions");
            if (!c.isNull("fixed") && a != null && !a.isNull("base")) {
                computable.add(c);
            }
        }
        int expectedHoldings = holdingsCount(companies.length());
        if (companies.length() != expectedHoldings) {
            throw new IllegalStateException("expected " + expectedHoldings
                    + " companies (holdings list), got " + companies.length());
        }
        // Idea #41: structural checks instead of hardcoded counts. The holdings
        // count is derived at render time from stock_watch/watchlist.json (that
        // requirement stays), but the number of disagreements and chart companies
        // legitimately changes as values are verified — asserting exact counts
        // broke the rebuild twice. So: the holding tickers themselves must be
        // unique, and every disagreement ticker must be one of the holdings
        // (no duplicates, no stray tickers). Any company that cannot be computed
        // is named so the fix is obvious.
        Set<String> seenTickers = new HashSet<>();
        for (int i = 0; i < companies.length(); i++) {
            String t = companies.getJSONObject(i).getString("ticker");
            if (!seenTickers.add(t)) {
                throw new IllegalStateException("duplicate company ticker \"" + t + "\"");
            }
        }
        assertTickersKnown(companies, disagreementTickers(data), "disagreement", true);
        if (computable.size() != expectedHoldings) {
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < companies.length(); i++) {
                JSONObject c = companies.getJSONObject(i);
                if (!computable.contains(c)) {
                    missing.add(c.getString("ticker"));
                }
            }
            throw new IllegalStateException(
                    "expected " + expectedHoldings + " computable companies, got " + computable.size()
                            + "; missing inputs for: " + String.join(", ", missing));
        }

        wb = new XSSFWorkbook();
        helper = wb.getCreationHelper();
        if (wb.getNumberOfSheets() > 0) {
            wb.removeSheetAt(0); // drop the default blank sheet (mirrors wb.remove(wb.active))
        }
        // POI's baked-in default font color is indexed 8; openpyxl's is
        // theme 1. Align it so unstyled cells serialize identically.
        XSSFFont defaultFont = wb.getFontAt((short) 0);
        if (defaultFont != null) {
            defaultFont.getCTFont().addNewColor().setTheme(1);
        }
        wb.getProperties().getCoreProperties().setTitle(data.getString("title"));
        wb.getProperties().getCoreProperties().setSubjectProperty(
                "Auditable DCF valuation comparison with mid-year discounting and five-year growth fade");
        wb.getProperties().getCoreProperties().setCreator("Muse");
        wb.getProperties().getCoreProperties().setDescription(
                "Roth IRA equity valuation model as of 2026-09-27; market prices at 2026-09-25 close.");
        // Idea #40: derived-file stamp in the document properties, so the
        // workbook file itself declares it must never be used as an input.
        // \u2014 is the em dash, escaped so this source stays pure ASCII.
        wb.getProperties().getCoreProperties().setKeywords(
                "derived from workbook_data.json via RebuildAll \u2014 do not edit, do not read as input");
        // Calculation properties: fullCalcOnLoad + forceFullCalc + auto (mirrors the try/except block).
        wb.setForceFormulaRecalculation(true);
        org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCalcPr calcPr =
                wb.getCTWorkbook().isSetCalcPr()
                        ? wb.getCTWorkbook().getCalcPr()
                        : wb.getCTWorkbook().addNewCalcPr();
        calcPr.setFullCalcOnLoad(true);
        calcPr.setForceFullCalc(true);
        calcPr.setCalcMode(
                org.openxmlformats.schemas.spreadsheetml.x2006.main.STCalcMode.AUTO);

        Map<String, Integer> compRow = new HashMap<>();
        Map<String, Integer> assumpRow = new HashMap<>();

        buildCover(data);
        buildComparison(data, companies, compRow);
        buildAssumptions(data, companies, assumpRow);
        buildModelSheets(data, companies, computable, assumpRow);
        buildDisagreements(data, companyByTicker, compRow, assumpRow);
        buildChartSheet(data, companies, compRow);
        buildMethodology(data);
        buildSources(data, companies, compRow, assumpRow);

        // Global styling, print titles (mirrors the final loop; the
        // `add_sort_condition if False` line is a deliberate no-op, skipped).
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            XSSFSheet ws = wb.getSheetAt(i);
            setBodyFont(ws);
            ws.setFitToPage(true);
            String t = ws.getSheetName();
            if (t.equals("Comparison") || t.equals("Assumptions")
                    || t.equals("Comparison Chart") || t.equals("Sources")) {
                ws.setRepeatingRows(CellRangeAddress.valueOf("1:3"));
            } else {
                ws.setRepeatingRows(CellRangeAddress.valueOf("1:2"));
            }
        }

        // Tab ordering: overview, models (original order), methodology/sources.
        List<String> order = new ArrayList<>(List.of(
                "Cover", "Comparison", "Assumptions", "Comparison Chart", "Disagreements"));
        for (JSONObject c : computable) {
            order.add("DCF " + c.getString("ticker"));
        }
        order.add("Methodology");
        order.add("Sources");
        for (int i = 0; i < order.size(); i++) {
            wb.setSheetOrder(order.get(i), i);
        }
        wb.setActiveSheet(0);

        try (FileOutputStream fos = new FileOutputStream(OUT.toFile())) {
            wb.write(fos);
        }

        // Cache every formula result before delivery (mirrors the subprocess
        // call to recalc_xlsx.py; same script, same arguments).
        recalcWithLibreOffice(OUT);

        System.out.println("Wrote and recalculated " + OUT
                + " with " + wb.getNumberOfSheets() + " sheets and "
                + computable.size() + " DCF models.");
    }

    /** Tickers of the disagreement entries, in file order. */
    static List<String> disagreementTickers(JSONObject data) {
        List<String> tickers = new ArrayList<>();
        JSONArray disagreements = data.getJSONArray("disagreements");
        for (int i = 0; i < disagreements.length(); i++) {
            tickers.add(disagreements.getJSONObject(i).getString("ticker"));
        }
        return tickers;
    }

    /**
     * Idea #41: structural ticker check. The watchlist is exactly 31
     * companies (that requirement stays), but any sub-list the builder
     * consumes — disagreements, chart companies — legitimately changes size
     * as values get verified, so we check SHAPE, not COUNT: every ticker
     * must be one of the 31 watchlist companies, with no duplicates. The old
     * hardcoded counts (16 disagreements, 20 chart companies) broke the
     * rebuild twice when the data legitimately changed; this cannot.
     *
     * @param companies the 31 watchlist company objects (each has "ticker")
     * @param tickers   the sub-list tickers to validate
     * @param kind      label used in error messages ("disagreement", "chart")
     * @param allowEmpty whether an empty sub-list is acceptable
     */
    static void assertTickersKnown(JSONArray companies, List<String> tickers,
                                   String kind, boolean allowEmpty) {
        Set<String> known = new HashSet<>();
        for (int i = 0; i < companies.length(); i++) {
            known.add(companies.getJSONObject(i).getString("ticker"));
        }
        if (tickers.isEmpty() && !allowEmpty) {
            throw new IllegalStateException(
                    "expected at least one " + kind + " company, got none");
        }
        Set<String> seen = new HashSet<>();
        for (String t : tickers) {
            if (!known.contains(t)) {
                throw new IllegalStateException(kind + " ticker \"" + t
                        + "\" is not one of the 31 watchlist companies");
            }
            if (!seen.add(t)) {
                throw new IllegalStateException(
                        "duplicate " + kind + " ticker \"" + t + "\"");
            }
        }
    }

    /** Runs the shared LibreOffice recalculation script and checks its report. */
    static void recalcWithLibreOffice(Path xlsx) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "python3",
                "/opt/hatch/skills/artifacts/scripts/recalc_xlsx.py",
                xlsx.toString(), "300");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Workbook recalculation failed, exit=" + exit + ": " + out);
        }
        JSONObject report = new JSONObject(out);
        if (!"success".equals(report.optString("status")) || report.optInt("total_errors", 0) != 0) {
            throw new RuntimeException("Workbook recalculation failed: " + report);
        }
    }

    // ------------------------------------------------------------------
    // Cover sheet.
    // ------------------------------------------------------------------
    static void buildCover(JSONObject data) {
        XSSFSheet ws = wb.createSheet("Cover");
        baseSheet(ws, false);
        ws.setTabColor(hexColor(NAVY));
        title(ws, data.getString("title"),
                "A formula-driven comparison of 31 Roth IRA equities", 8);

        String[][] coverRows = {
            {"Market prices as of", data.getString("market_asof")},
            {"DCF inputs and comparisons as of", data.getString("dcf_asof")},
            {"Universe", "31 Roth IRA equity holdings; options and cash excluded"},
            {"DCF methodology", "10-year DCF with mid-year discounting, sustained growth capped at 15%, and a linear five-year growth fade to terminal growth"},
            {"Model coverage", "31 formula-driven company tabs covering every holding"},
            {"How to read", "Blue text = hardcoded source input; green text = cross-sheet link; black text = formula; — = not recorded / unavailable"},
            {"Important", "Educational research model only — not investment advice. DCF outputs are highly sensitive to growth, discount rate, terminal growth, margins, capital structure, and share count."},
        };
        int r = 4;
        for (String[] row : coverRows) {
            XSSFCell label = cell(ws, r, 1);
            label.setCellValue(row[0]);
            label.setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 11).withBold().withFontColor(NAVY)
                    .withFill(LIGHT_BLUE).withBorder(MID_GRAY)));
            merge(ws, r, 2, r, 8);
            XSSFCell val = cell(ws, r, 2);
            val.setCellValue(row[1]);
            val.setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 11).withFontColor(BLACK)
                    .withBorder(MID_GRAY).withWrap()
                    .withAlign(null, VerticalAlignment.TOP)));
            ws.getRow(r - 1).setHeightInPoints((r == 7 || r == 9 || r == 10) ? 42 : 28);
            r++;
        }
        int[] widths = {28, 16, 16, 16, 16, 16, 16, 16};
        for (int i = 0; i < widths.length; i++) {
            ws.setColumnWidth(i, (int) Math.round(widths[i] * 256));
        }
        // Cover intentionally has no freeze pane (mirrors ws.freeze_panes = None).
        ws.getCTWorksheet().getSheetViews().getSheetViewArray(0).unsetPane();
    }

    // ------------------------------------------------------------------
    // Comparison sheet.
    // ------------------------------------------------------------------
    static Object jsonVal(JSONObject o, String key) {
        if (o.isNull(key)) {
            return null;
        }
        // Return the raw org.json Number (Integer/Long vs Double) so
        // setNumber() can preserve Python's int-vs-float serialization.
        return o.get(key);
    }

    static void buildComparison(JSONObject data, JSONArray companies,
                                Map<String, Integer> compRow) {
        XSSFSheet ws = wb.createSheet("Comparison");
        baseSheet(ws, true);
        ws.setTabColor(hexColor(TEAL));
        title(ws, "Valuation Comparison",
                "All source columns use supplied values exactly; formula cells recalculate from the workbook model.", 12);

        // Rahik's reshaped order (2026-09-29, 12 columns): Ticker | Market |
        // Our DCF | Buy below (one column, on Our DCF — the number the
        // gap-watch trades on) | Muse speculation price | Growth outlook |
        // NotebookLM grounded | NotebookLM speculation price | Forward tilt |
        // Creator | Projection price (analyst target) | Website consensus.
        // No Outside voice column (backend-only speculation input). No
        // per-price buy-belows (only Our DCF gets one). No Company column
        // (ticker is the identifier). No Creator buy price, Average, Upside,
        // or flag columns.
        String[] headers = {"Ticker", "Market close\n2026-09-25",
                "Our\nDCF", "Buy below",
                "Muse speculation\nprice",
                "Growth\noutlook",
                "NotebookLM",
                "NotebookLM speculation\nprice",
                "Forward\ntilt",
                "Creator\nvalues",
                "Projection price\n(analyst target)",
                "Website\nconsensus"};
        for (int i = 0; i < headers.length; i++) {
            cell(ws, 3, i + 1).setCellValue(headers[i]);
        }
        headerRow(ws, 3, 1, 12, NAVY);

        // Required margin of safety per ticker (buy-below display).
        JSONObject marginDoc = readMarginRequirements();

        int r = 4;
        for (int i = 0; i < companies.length(); i++, r++) {
            JSONObject c = companies.getJSONObject(i);
            String ticker = c.getString("ticker");
            compRow.put(ticker, r);
            double req = reqMargin(marginDoc, ticker);

            cell(ws, r, 1).setCellValue(ticker);
            cell(ws, r, 1).setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 10).withBold().withFontColor(NAVY)));

            setHardcoded(ws, cell(ws, r, 2), jsonVal(c, "market"),
                    "Supplied input dataset: Friday market close on 2026-09-25 ("
                            + data.getString("market_asof")
                            + "). External market-data URL was not supplied.",
                    CURRENCY_FMT);
            // Our DCF (formula from the DCF tab) + the single buy-below.
            if (!c.isNull("fixed")) {
                String tab = quoteSheetName("DCF " + ticker);
                setFormula(ws, cell(ws, r, 3), "=ROUND(" + tab + "!$B$36,2)",
                        "Calculated on DCF " + ticker
                                + " from Assumptions using the DCF methodology.",
                        CURRENCY_FMT, true);
            } else {
                JSONObject a = c.optJSONObject("assumptions");
                String reason = (a != null && !a.isNull("unavailable"))
                        ? a.getString("unavailable")
                        : "DCF not computed in supplied input data.";
                setHardcoded(ws, cell(ws, r, 3), null, reason, CURRENCY_FMT);
            }
            setHardcoded(ws, cell(ws, r, 4), buyBelowVal(jsonVal(c, "fixed"), req),
                    "Buy below: Our DCF x (1 - required margin). The ONLY buy-below column — "
                            + "the number the gap-watch trades on. Display only; gap-watch alerting "
                            + "stays on the base our DCF.",
                    CURRENCY_FMT);
            // Muse speculation price (Rahik's 4-result design, 2026-09-29):
            // ours pulled halfway toward the outside median, nudged by the
            // growth outlook. Display-only, no buy-below.
            Object museSpec = jsonVal(c, "muse_speculation");
            setHardcoded(ws, cell(ws, r, 5), museSpec,
                    "Muse speculation price: our DCF pulled halfway toward the outside median "
                            + "(median of the available creator / analyst-target / website-consensus "
                            + "values) plus the growth-outlook nudge. Display-only; not a blessed value.",
                    CURRENCY_FMT);
            // Growth outlook (Rahik 2026-09-28): Muse's per-ticker growth
            // assessment from the deep-research forward-earnings brief, shown
            // beside the Muse DCF. "not yet researched" until that ticker's
            // brief lands — never a plausible fill. Not a blessed value.
            String gOut = c.isNull("growth_outlook") ? null
                    : c.optString("growth_outlook", null);
            String gThesis = c.isNull("growth_thesis") ? null
                    : c.optString("growth_thesis", null);
            String gVal = gOut == null ? "not yet researched"
                    : (gThesis == null || gThesis.isEmpty() ? gOut
                            : gOut + " \u2014 " + gThesis);
            setHardcoded(ws, cell(ws, r, 6), gVal,
                    "Muse's growth assessment from the deep-research forward-earnings brief: "
                            + "High/Moderate/Low plus a one-line basis. 'Not yet researched' until that "
                            + "ticker's brief lands; never a plausible fill. Not a blessed value.",
                    null);
            // NotebookLM grounded.
            Object nbRaw = c.isNull("notebooklm") ? null : c.get("notebooklm");
            Object nb = (nbRaw instanceof Number) ? (Number) nbRaw : null;
            String nbNote = "NotebookLM independent intrinsic value supplied in workbook_data.json; "
                    + "underlying NotebookLM assumptions were not recorded.";
            if (nbRaw instanceof String) {
                nbNote = (String) nbRaw;
            }
            setHardcoded(ws, cell(ws, r, 7), nb, nbNote, CURRENCY_FMT);
            // NotebookLM speculation price (RESERVED slot).
            // Architecture rule (Rahik 2026-09-29): derived by NotebookLM
            // itself — never computed here. Blank until its re-ask response
            // is verified and blessed.
            Object nlmSpec = jsonVal(c, "nlm_speculation");
            setHardcoded(ws, cell(ws, r, 8), nlmSpec,
                    "NotebookLM speculation price: DERIVED BY NOTEBOOKLM ITSELF — reserved slot, "
                            + "blank until its re-ask response is verified and blessed. Never computed here. "
                            + "Display-only; not a blessed value.",
                    CURRENCY_FMT);
            // Forward tilt.
            Object fwdRaw = c.isNull("notebooklm_forward") ? null : c.get("notebooklm_forward");
            Object fwd = (fwdRaw instanceof Number) ? (Number) fwdRaw : null;
            String fwdNote = "NotebookLM forward-tilt intrinsic value supplied in workbook_data.json: "
                    + "filings/history DCF with a modest forward-looking tilt from management guidance "
                    + "and product-cycle momentum; blank until verified.";
            if (fwdRaw instanceof String) {
                fwdNote = (String) fwdRaw;
            }
            setHardcoded(ws, cell(ws, r, 9), fwd, fwdNote, CURRENCY_FMT);
            // Creator values.
            Object creator = jsonVal(c, "creator");
            setHardcoded(ws, cell(ws, r, 10), creator,
                    !c.isNull("creator_note") ? c.getString("creator_note")
                            : "Creator value not recorded in supplied input data.",
                    CURRENCY_FMT);
            // Projection price (analyst target).
            Object analyst = jsonVal(c, "analyst");
            setHardcoded(ws, cell(ws, r, 11), analyst,
                    "Projection price (analyst target) not recorded in supplied input data.", CURRENCY_FMT);
            // Website consensus (its own column, never blended).
            Object web = jsonVal(c, "website_consensus");
            setHardcoded(ws, cell(ws, r, 12), web,
                    "Website consensus: median of ValueInvesting.io, Simply Wall St, Finbox, "
                            + "and ValueSense DCF values. Its own column, never blended with the others. "
                            + "Display-only; not a blessed value.",
                    CURRENCY_FMT);

            for (int col = 1; col <= 12; col++) {
                XSSFCell cc = cell(ws, r, col);
                XSSFCellStyle old = cc.getCellStyle();
                boolean left = (col == 1 || col == 6);
                XSSFCellStyle st = modStyle(
                        left ? "comp-border-left" : "comp-border-center", old, s -> {
                            thinMidGray(s);
                            s.setVerticalAlignment(VerticalAlignment.CENTER);
                            s.setAlignment(left ? HorizontalAlignment.LEFT
                                    : HorizontalAlignment.CENTER);
                            s.setWrapText(true);
                        });
                cc.setCellStyle(st);
            }
            if (r % 2 == 0) {
                for (int col = 1; col <= 12; col++) {
                    XSSFCell cc = cell(ws, r, col);
                    XSSFCellStyle old = cc.getCellStyle();
                    if (old.getFillPattern() == FillPatternType.NO_FILL) {
                        XSSFCellStyle st = modStyle("comp-zebra", old, s -> {
                            s.setFillForegroundColor(hexColor(ZEBRA));
                            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                        });
                        cc.setCellStyle(st);
                    }
                }
            }
            ws.getRow(r - 1).setHeightInPoints(
                    c.isNull("growth_thesis") ? 26 : 40);
        }

        int end = 3 + companies.length();
        ws.setAutoFilter(new CellRangeAddress(2, end - 1, 0, 11));
        ws.createFreezePane(2, 3); // "C4"
        int[] widths = {10, 15, 14, 14, 16, 40, 14, 16, 14, 14, 16, 16};
        for (int i = 0; i < widths.length; i++) {
            ws.setColumnWidth(i, (int) Math.round(widths[i] * 256));
        }

        // Conditional formatting: highlight positive buy-below gaps (removed
        // with the Average/Upside columns in the 2026-09-29 reshape).
    }

    /** Buy-below value for the workbook: intrinsic x (1 - required margin);
     *  null when the value is missing, zero, or negative. */
    static Object buyBelowVal(Object v, double req) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d > 0) return d * (1.0 - req);
        }
        return null;
    }

    /** Required margin of safety for one ticker from
     *  dcf_csv/margin_requirements.json (default 0.20). */
    static double reqMargin(JSONObject marginDoc, String ticker) {
        if (marginDoc != null) {
            JSONObject tickers = marginDoc.optJSONObject("tickers");
            if (tickers != null && tickers.has(ticker)) {
                return tickers.optDouble(ticker, 0.20);
            }
            return marginDoc.optDouble("default", 0.20);
        }
        return 0.20;
    }

    static JSONObject readMarginRequirements() {
        try {
            Path p = ROOT.resolve("dcf_csv/margin_requirements.json");
            return new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("margin requirements unavailable (" + e
                    + ") — buy-below columns use default 20%");
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Assumptions sheet.
    // ------------------------------------------------------------------
    static void buildAssumptions(JSONObject data, JSONArray companies,
                                 Map<String, Integer> assumpRow) {
        XSSFSheet ws = wb.createSheet("Assumptions");
        baseSheet(ws, true);
        ws.setTabColor(hexColor(GOLD));
        title(ws, "Model Assumptions",
                "All monetary values are USD millions unless labeled per share. "
                        + "Blue cells are supplied inputs; — means unavailable, with the reason in the final column.",
                14);

        String[] aHeaders = {"Company", "Ticker", "Model kind", "Base FCF / revenue",
                "OCF", "Capex", "Growth g", "WACC / CoE w", "Terminal growth t",
                "Diluted shares (M)", "Net debt (M)", "Start margin", "Year-5 margin",
                "Source note / unavailable reason"};
        for (int i = 0; i < aHeaders.length; i++) {
            cell(ws, 3, i + 1).setCellValue(aHeaders[i]);
        }
        headerRow(ws, 3, 1, 14, DARK_GOLD);

        int r = 4;
        for (int i = 0; i < companies.length(); i++, r++) {
            JSONObject c = companies.getJSONObject(i);
            String ticker = c.getString("ticker");
            assumpRow.put(ticker, r);
            JSONObject a = c.optJSONObject("assumptions");

            cell(ws, r, 1).setCellValue(c.getString("name"));
            cell(ws, r, 1).setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 10).withFontColor(BLACK)));
            cell(ws, r, 2).setCellValue(ticker);
            cell(ws, r, 2).setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 10).withBold().withFontColor(NAVY)));

            if (a != null && !a.isNull("base")) {
                String[] keys = {"kind", "base", "ocf", "capex", "g", "w", "t",
                        "shares", "netdebt", "start_margin", "end_margin"};
                String src = (!a.isNull("src")) ? a.getString("src") : "Supplied input dataset.";
                for (int k = 0; k < keys.length; k++) {
                    int col = 3 + k;
                    String fmt = (col == 7 || col == 8 || col == 9 || col == 12 || col == 13)
                            ? PCT_FMT
                            : ((col == 4 || col == 5 || col == 6 || col == 11) ? MM_FMT
                                    : (col == 10 ? NUMBER_FMT : null));
                    setHardcoded(ws, cell(ws, r, col), jsonVal(a, keys[k]), src, fmt);
                }
                cell(ws, r, 14).setCellValue(src);
                cell(ws, r, 14).setCellStyle(style(StyleSpec.blank()
                        .withFont("Liberation Sans", 9).withFontColor(BLUE)));
                addComment(ws, cell(ws, r, 14),
                        "Source note copied exactly from the supplied input dataset.");
            } else {
                String reason = (a != null && !a.isNull("unavailable"))
                        ? a.getString("unavailable")
                        : "No DCF assumptions were recorded in the supplied input data.";
                for (int col = 3; col <= 13; col++) {
                    setHardcoded(ws, cell(ws, r, col), null, reason, null);
                }
                cell(ws, r, 14).setCellValue(reason);
                cell(ws, r, 14).setCellStyle(style(StyleSpec.blank()
                        .withFont("Liberation Sans", 9).withFontColor(BLUE)));
            }
            for (int col = 1; col <= 14; col++) {
                XSSFCell cc = cell(ws, r, col);
                XSSFCellStyle old = cc.getCellStyle();
                boolean left = (col == 1 || col == 14);
                XSSFCellStyle st = modStyle(
                        left ? "assump-border-left" : "assump-border-center", old, s -> {
                            thinMidGray(s);
                            s.setVerticalAlignment(VerticalAlignment.TOP);
                            s.setAlignment(left ? HorizontalAlignment.LEFT
                                    : HorizontalAlignment.CENTER);
                            s.setWrapText(true);
                        });
                cc.setCellStyle(st);
            }
            ws.getRow(r - 1).setHeightInPoints(52);
        }

        int end = 3 + companies.length();
        ws.setAutoFilter(new CellRangeAddress(2, end - 1, 0, 13));
        ws.createFreezePane(2, 3); // "C4"
        int[] aWidths = {27, 10, 12, 18, 13, 13, 12, 14, 14, 18, 16, 13, 14, 62};
        for (int i = 0; i < aWidths.length; i++) {
            ws.setColumnWidth(i, (int) Math.round(aWidths[i] * 256));
        }
    }

    // ------------------------------------------------------------------
    // 31 formula-driven company model sheets.
    // ------------------------------------------------------------------
    static void buildModelSheets(JSONObject data, JSONArray companies,
                                 List<JSONObject> computable,
                                 Map<String, Integer> assumpRow) {
        for (JSONObject c : computable) {
            JSONObject a = c.getJSONObject("assumptions");
            String tkr = c.getString("ticker");
            int ar = assumpRow.get(tkr);
            XSSFSheet ws = wb.createSheet("DCF " + tkr);
            baseSheet(ws, true);
            ws.setTabColor(hexColor(BLUE));
            title(ws, c.getString("name") + " (" + tkr + ") — DCF",
                    "10-year model; mid-year discounting; sustained growth capped at 15%; Years 6–10 growth fades linearly to terminal growth.",
                    8);

            String[] labels = {"Model kind", "Base FCF / revenue ($M)", "Initial growth g",
                    "Discount rate w", "Terminal growth t", "Diluted shares (M)",
                    "Net debt ($M)", "Start FCF margin", "Year-5 FCF margin"};
            int[] assCols = {3, 4, 7, 8, 9, 10, 11, 12, 13};
            for (int k = 0; k < labels.length; k++) {
                int idx = 4 + k; // mirrors enumerate(..., 4)
                int acol = assCols[k];
                XSSFCell lab = cell(ws, idx, 1);
                lab.setCellValue(labels[k]);
                lab.setCellStyle(style(StyleSpec.blank()
                        .withFont("Liberation Sans", 10).withBold().withFontColor(NAVY)
                        .withFill(LIGHT_BLUE).withBorder(MID_GRAY)));
                String fmt = (idx == 6 || idx == 7 || idx == 8 || idx == 11 || idx == 12)
                        ? PCT_FMT
                        : ((idx == 5 || idx == 10) ? MM_FMT
                                : (idx == 9 ? NUMBER_FMT : null));
                XSSFCell val = cell(ws, idx, 2);
                setFormula(ws, val,
                        "=Assumptions!$" + colLetter(acol) + "$" + ar,
                        "Linked to Assumptions!" + colLetter(acol) + ar + ".",
                        fmt, true);
                XSSFCellStyle old = val.getCellStyle();
                XSSFCellStyle st = modStyle("model-assump-border", old,
                        GenerateDcfWorkbook::thinMidGray);
                val.setCellStyle(st);
            }

            // Assumption source note (merged E4:H7).
            XSSFCell srcLab = cell(ws, 4, 4);
            srcLab.setCellValue("Assumption source");
            srcLab.setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 10).withBold().withFontColor(NAVY)
                    .withFill(LIGHT_BLUE).withBorder(MID_GRAY)));
            merge(ws, 4, 5, 7, 8);
            XSSFCell srcVal = cell(ws, 4, 5);
            setFormula(ws, srcVal, "=Assumptions!$N$" + ar,
                    "Linked to Assumptions!N" + ar + ".", null, true);
            XSSFCellStyle oldSrc = srcVal.getCellStyle();
            XSSFCellStyle stSrc = modStyle("model-srcnote", oldSrc, s -> {
                s.setWrapText(true);
                s.setVerticalAlignment(VerticalAlignment.TOP);
                thinMidGray(s);
            });
            srcVal.setCellStyle(stSrc);

            // Projection table header (row 14).
            String[] pHeaders = {"Year", "Growth", "Revenue / base ($M)", "FCF margin",
                    "FCF ($M)", "Discount exponent", "Discount factor", "PV of FCF ($M)"};
            for (int i = 0; i < pHeaders.length; i++) {
                cell(ws, 14, i + 1).setCellValue(pHeaders[i]);
            }
            headerRow(ws, 14, 1, 8, TEAL);

            boolean isRev = "REV".equals(a.getString("kind"));
            for (int y = 1; y <= 10; y++) {
                int r = 14 + y;
                setNumber(cell(ws, r, 1), y);
                String growthFormula = y <= 5 ? "=MIN($B$6,0.15)"
                        : "=MIN($B$6,0.15)-(" + y + "-5)*(MIN($B$6,0.15)-$B$8)/5";
                setFormula(ws, cell(ws, r, 2), growthFormula,
                        "Guardrail 2026-09-28: years 1–5 use min(g, 15%); years 6–10 fade linearly from the capped rate to t.",
                        PCT_FMT, false);
                if (isRev) {
                    String revFormula = y == 1 ? "=$B$5*(1+B15)" : "=C" + (r - 1) + "*(1+B" + r + ")";
                    String marginFormula = y <= 5
                            ? "=$B$11+($B$12-$B$11)*" + y + "/5" : "=$B$12";
                    setFormula(ws, cell(ws, r, 3), revFormula,
                            "Revenue projection formula.", MM_FMT, false);
                    setFormula(ws, cell(ws, r, 4), marginFormula,
                            "Margin ramps linearly from start to year-5 margin, then holds.",
                            PCT_FMT, false);
                    setFormula(ws, cell(ws, r, 5), "=C" + r + "*D" + r,
                            "FCF = revenue × FCF margin.", MM_FMT, false);
                } else {
                    setFormula(ws, cell(ws, r, 3), "=\"—\"",
                            "Not used for FCFF/FCFE model.", null, false);
                    setFormula(ws, cell(ws, r, 4), "=\"—\"",
                            "Not used for FCFF/FCFE model.", null, false);
                    String fcfFormula = y == 1 ? "=$B$5*(1+B15)"
                            : "=E" + (r - 1) + "*(1+B" + r + ")";
                    setFormula(ws, cell(ws, r, 5), fcfFormula,
                            "FCF projection formula.", MM_FMT, false);
                }
                setFormula(ws, cell(ws, r, 6), "=" + y + "-0.5",
                        "Mid-year discount exponent y - 0.5.", "0.0", false);
                setFormula(ws, cell(ws, r, 7), "=1/(1+$B$7)^F" + r,
                        "Mid-year discount factor.", "0.0000", false);
                setFormula(ws, cell(ws, r, 8), "=E" + r + "*G" + r,
                        "PV of projected FCF.", MM_FMT, false);
                for (int col = 1; col <= 8; col++) {
                    XSSFCell cc = cell(ws, r, col);
                    XSSFCellStyle o = cc.getCellStyle();
                    XSSFCellStyle s = modStyle("model-proj-border", o, ms -> {
                        thinMidGray(ms);
                        ms.setAlignment(HorizontalAlignment.CENTER);
                        ms.setVerticalAlignment(VerticalAlignment.CENTER);
                    });
                    cc.setCellStyle(s);
                }
            }

            String[][] summary = {
                {"30", "PV of Years 1–10 FCF ($M)", "=SUM(H15:H24)"},
                {"31", "Terminal value at Year 10 ($M)", "=E24*(1+$B$8)/($B$7-$B$8)"},
                {"32", "PV of terminal value ($M)", "=B31/(1+$B$7)^9.5"},
                {"33", "Enterprise value ($M)", "=B30+B32"},
                {"34", "Equity value ($M)", "=IF($B$4=\"FCFE\",B33,B33-$B$10)"},
                {"35", "Diluted shares (M)", "=B9"},
                {"36", "Intrinsic value / share", "=B34/B35"},
            };
            for (String[] srow : summary) {
                int r = Integer.parseInt(srow[0]);
                XSSFCell lab = cell(ws, r, 1);
                lab.setCellValue(srow[1]);
                lab.setCellStyle(style(StyleSpec.blank()
                        .withFont("Liberation Sans", 10).withBold().withFontColor(NAVY)
                        .withFill(LIGHT_BLUE).withBorder(MID_GRAY)));
                String fmt = r == 36 ? CURRENCY_FMT : (r == 35 ? NUMBER_FMT : MM_FMT);
                XSSFCell val = cell(ws, r, 2);
                setFormula(ws, val, srow[2],
                        "Derived by the DCF methodology documented on Methodology.",
                        fmt, false);
                XSSFCellStyle o = val.getCellStyle();
                XSSFCellStyle s = modStyle("model-sum-border", o,
                        GenerateDcfWorkbook::thinMidGray);
                val.setCellStyle(s);
            }
            // Highlight the intrinsic-value row (mirrors the PALE_GOLD overrides).
            for (int col = 1; col <= 2; col++) {
                XSSFCell cc = cell(ws, 36, col);
                XSSFCellStyle o = cc.getCellStyle();
                XSSFCellStyle s = modStyle("model-r36-fill", o, ms -> {
                    ms.setFillForegroundColor(hexColor(PALE_GOLD));
                    ms.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                });
                cc.setCellStyle(s);
            }
            {
                XSSFCell a36 = cell(ws, 36, 1);
                XSSFCellStyle o = a36.getCellStyle();
                XSSFCellStyle s = modStyle("model-r36-font-navy", o, ms ->
                        ms.setFont(cachedFont("Liberation Sans", 11, true,
                                false, false, NAVY)));
                a36.setCellStyle(s);
                XSSFCell b36 = cell(ws, 36, 2);
                XSSFCellStyle o2 = b36.getCellStyle();
                XSSFCellStyle s2 = modStyle("model-r36-font-black", o2, ms ->
                        ms.setFont(cachedFont("Liberation Sans", 11, true,
                                false, false, BLACK)));
                b36.setCellStyle(s2);
            }

            // Practitioner guardrail (2026-09-28): visible terminal-value
            // share warning. Warning only — the value is never altered.
            XSSFCell tvLab = cell(ws, 37, 1);
            tvLab.setCellValue("Terminal-value share of EV (warn if > 75%)");
            tvLab.setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 10).withBold().withFontColor(NAVY)
                    .withFill(LIGHT_BLUE).withBorder(MID_GRAY)));
            setFormula(ws, cell(ws, 37, 2), "=IF(B33=0,\"—\",B32/B33)",
                    "Guardrail 2026-09-28: PV of terminal value / enterprise value. "
                            + "Above 75% the valuation is a terminal-value bet. "
                            + "Warning only; the value is unchanged.",
                    PCT_FMT, false);
            setFormula(ws, cell(ws, 37, 3),
                    "=IF(AND(ISNUMBER(B37),B37>0.75),\"WARNING: TERMINAL-VALUE BET\",\"\")",
                    "Visible flag when the terminal value exceeds 75% of EV.",
                    null, false);

            // Check vs the supplied value.
            cell(ws, 38, 1).setCellValue("Check vs supplied value");
            setHardcoded(ws, cell(ws, 38, 2), jsonVal(c, "fixed"),
                    "Supplied rounded Muse DCF used as a two-decimal check.",
                    CURRENCY_FMT);
            setFormula(ws, cell(ws, 39, 1), "=\"Calculated minus supplied\"", null, null, false);
            setFormula(ws, cell(ws, 39, 2), "=B36-B38",
                    "Should be less than $0.005 in magnitude because the supplied value is rounded to cents.",
                    CURRENCY_FMT, false);
            for (int r = 38; r <= 39; r++) {
                for (int col = 1; col <= 2; col++) {
                    XSSFCell cc = cell(ws, r, col);
                    XSSFCellStyle o = cc.getCellStyle();
                    XSSFCellStyle s = modStyle("model-check-border", o,
                            GenerateDcfWorkbook::thinMidGray);
                    cc.setCellStyle(s);
                }
            }

            int[] mWidths = {28, 17, 21, 17, 17, 18, 17, 19};
            for (int i = 0; i < mWidths.length; i++) {
                ws.setColumnWidth(i, (int) Math.round(mWidths[i] * 256));
            }
            ws.createFreezePane(0, 14); // "A15"
            ws.setAutoFilter(new CellRangeAddress(13, 23, 0, 7));
            wb.setPrintArea(wb.getSheetIndex(ws), "A1:H39");
        }
    }

    // ------------------------------------------------------------------
    // Disagreements sheet.
    // ------------------------------------------------------------------
    static void buildDisagreements(JSONObject data,
                                   Map<String, JSONObject> companyByTicker,
                                   Map<String, Integer> compRow,
                                   Map<String, Integer> assumpRow) {
        XSSFSheet ws = wb.createSheet("Disagreements");
        baseSheet(ws, true);
        ws.setTabColor(hexColor(RED));
        title(ws, "DCF Disagreement Spotlight",
                "Muse vs NotebookLM; only differences over 15%, plus IREN sign flip. "
                        + "NotebookLM assumptions were not recorded.",
                6);

        String[][] fields = {
            {"Intrinsic value / share", "value"},
            {"Difference", "diff"},
            {"Model kind", "kind"},
            {"Base FCF / revenue ($M)", "base"},
            {"OCF ($M)", "ocf"},
            {"Capex ($M)", "capex"},
            {"Initial growth g", "g"},
            {"WACC / CoE w", "w"},
            {"Terminal growth t", "t"},
            {"Diluted shares (M)", "shares"},
            {"Net debt ($M)", "netdebt"},
            {"Start margin", "start_margin"},
            {"Year-5 margin", "end_margin"},
            {"Assumption source", "src"},
        };
        Map<String, Integer> colmap = new HashMap<>(Map.of(
                "kind", 3, "base", 4, "ocf", 5, "capex", 6, "g", 7, "w", 8,
                "t", 9, "shares", 10, "netdebt", 11));
        colmap.put("start_margin", 12);
        colmap.put("end_margin", 13);
        colmap.put("src", 14);

        int start = 4;
        JSONArray disagreements = data.getJSONArray("disagreements");
        for (int d = 0; d < disagreements.length(); d++) {
            JSONObject dg = disagreements.getJSONObject(d);
            String ticker = dg.getString("ticker");
            JSONObject c = companyByTicker.get(ticker);
            int ar = assumpRow.get(ticker);
            int cr = compRow.get(ticker);

            merge(ws, start, 1, start, 6);
            XSSFCell blockTitle = cell(ws, start, 1);
            blockTitle.setCellValue(c.getString("name") + " (" + ticker + ")");
            blockTitle.setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 12).withBold().withFontColor(WHITE)
                    .withFill(NAVY)
                    .withAlign(null, VerticalAlignment.CENTER)));
            ws.getRow(start - 1).setHeightInPoints(24);

            cell(ws, start + 1, 1).setCellValue("Field");
            merge(ws, start + 1, 2, start + 1, 3);
            cell(ws, start + 1, 2).setCellValue("Our DCF / assumptions");
            merge(ws, start + 1, 4, start + 1, 6);
            cell(ws, start + 1, 4).setCellValue("NotebookLM");
            headerRow(ws, start + 1, 1, 6, TEAL);

            for (int fi = 0; fi < fields.length; fi++) {
                int i = start + 2 + fi;
                String label = fields[fi][0];
                String key = fields[fi][1];
                XSSFCell lab = cell(ws, i, 1);
                lab.setCellValue(label);
                lab.setCellStyle(style(StyleSpec.blank()
                        .withFont("Liberation Sans", 9).withBold().withFontColor(NAVY)
                        .withFill(LIGHT_BLUE)));
                merge(ws, i, 2, i, 3);
                merge(ws, i, 4, i, 6);
                XSSFCell left = cell(ws, i, 2);
                XSSFCell right = cell(ws, i, 4);
                if (key.equals("value")) {
                    setFormula(ws, left, "=Comparison!$C$" + cr,
                            "Linked to Comparison Our DCF.", CURRENCY_FMT, true);
                    setFormula(ws, right, "=Comparison!$G$" + cr,
                            "Linked to Comparison NotebookLM value.", CURRENCY_FMT, true);
                } else if (key.equals("diff")) {
                    if (ticker.equals("IREN")) {
                        setFormula(ws, left, "=\"Sign flip\"",
                                "Our DCF is negative while NotebookLM is positive.",
                                null, false);
                    } else {
                        setFormula(ws, left,
                                "=ABS(Comparison!$C$" + cr + "-Comparison!$G$" + cr
                                        + ")/Comparison!$G$" + cr,
                                "Absolute difference divided by NotebookLM value.",
                                PCT_FMT, false);
                    }
                    right.setCellValue(dg.optString("notebooklm_assumptions", ""));
                    right.setCellStyle(style(StyleSpec.blank()
                            .withFont("Liberation Sans", 9).withFontColor(BLUE)));
                } else {
                    int ac = colmap.get(key);
                    String fmt = (key.equals("g") || key.equals("w") || key.equals("t")
                            || key.equals("start_margin") || key.equals("end_margin"))
                            ? PCT_FMT
                            : ((key.equals("base") || key.equals("ocf")
                                    || key.equals("capex") || key.equals("netdebt")) ? MM_FMT
                                    : (key.equals("shares") ? NUMBER_FMT : null));
                    setFormula(ws, left,
                            "=Assumptions!$" + colLetter(ac) + "$" + ar,
                            "Linked to Assumptions!" + colLetter(ac) + ar + ".",
                            fmt, true);
                    right.setCellValue(dg.optString("notebooklm_assumptions", ""));
                    right.setCellStyle(style(StyleSpec.blank()
                            .withFont("Liberation Sans", 9).withFontColor(BLUE)));
                }
                for (int col = 1; col <= 6; col++) {
                    XSSFCell cc = cell(ws, i, col);
                    XSSFCellStyle o = cc.getCellStyle();
                    XSSFCellStyle s = modStyle("disag-border", o,
                            GenerateDcfWorkbook::thinMidGray);
                    cc.setCellStyle(s);
                }
                XSSFCellStyle ol = left.getCellStyle();
                XSSFCellStyle sl = modStyle("disag-wrap", ol, s -> {
                    s.setWrapText(true);
                    s.setVerticalAlignment(VerticalAlignment.TOP);
                });
                left.setCellStyle(sl);
                XSSFCellStyle orr = right.getCellStyle();
                XSSFCellStyle sr = modStyle("disag-wrap", orr, s -> {
                    s.setWrapText(true);
                    s.setVerticalAlignment(VerticalAlignment.TOP);
                });
                right.setCellStyle(sr);
                ws.getRow(i - 1).setHeightInPoints(key.equals("src") ? 48 : 28);
            }
            start += fields.length + 3;
        }

        int[] dWidths = {27, 23, 23, 25, 25, 25};
        for (int i = 0; i < dWidths.length; i++) {
            ws.setColumnWidth(i, (int) Math.round(dWidths[i] * 256));
        }
        ws.createFreezePane(0, 3); // "A4"
        wb.setPrintArea(wb.getSheetIndex(ws), "A1:F" + (start - 2));
    }

    // ------------------------------------------------------------------
    // Comparison Chart sheet (with a real clustered column chart).
    // ------------------------------------------------------------------
    static void buildChartSheet(JSONObject data, JSONArray companies,
                                Map<String, Integer> compRow) {
        XSSFSheet ws = wb.createSheet("Comparison Chart");
        baseSheet(ws, true);
        ws.setTabColor(hexColor(TEAL));

        List<JSONObject> chartCompanies = new ArrayList<>();
        for (int i = 0; i < companies.length(); i++) {
            JSONObject c = companies.getJSONObject(i);
            if (!c.isNull("fixed") && !c.isNull("notebooklm")
                    && c.get("notebooklm") instanceof Number) {
                chartCompanies.add(c);
            }
        }
        // Idea #41: structural, not a hardcoded count — the chart is valid for
        // any non-empty subset of the 31 watchlist tickers. The subtitle names
        // the real count so it can never go stale again.
        List<String> chartTickers = new ArrayList<>();
        for (JSONObject c : chartCompanies) {
            chartTickers.add(c.getString("ticker"));
        }
        assertTickersKnown(companies, chartTickers, "chart", false);
        title(ws, "Muse DCF vs NotebookLM",
                chartCompanies.size()
                        + " companies with verified values from both sources; values are linked to Comparison.",
                4);

        String[] headers = {"Ticker", "Muse DCF", "NotebookLM", "Absolute difference %"};
        for (int i = 0; i < headers.length; i++) {
            cell(ws, 3, i + 1).setCellValue(headers[i]);
        }
        headerRow(ws, 3, 1, 4, TEAL);

        int r = 4;
        for (JSONObject c : chartCompanies) {
            int cr = compRow.get(c.getString("ticker"));
            cell(ws, r, 1).setCellValue(c.getString("ticker"));
            cell(ws, r, 1).setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 10).withBold().withFontColor(NAVY)));
            setFormula(ws, cell(ws, r, 2), "=Comparison!$C$" + cr,
                    "Linked to Comparison Our DCF.", CURRENCY_FMT, true);
            setFormula(ws, cell(ws, r, 3), "=Comparison!$G$" + cr,
                    "Linked to Comparison NotebookLM value.", CURRENCY_FMT, true);
            setFormula(ws, cell(ws, r, 4), "=ABS(B" + r + "-C" + r + ")/C" + r,
                    "Formula difference as a percentage of NotebookLM.", PCT_FMT, false);
            for (int col = 1; col <= 4; col++) {
                XSSFCell cc = cell(ws, r, col);
                XSSFCellStyle o = cc.getCellStyle();
                XSSFCellStyle s = modStyle("chart-border-center", o, ms -> {
                    thinMidGray(ms);
                    ms.setAlignment(HorizontalAlignment.CENTER);
                });
                cc.setCellStyle(s);
            }
            r++;
        }
        int[] cWidths = {12, 19, 18, 22};
        for (int i = 0; i < cWidths.length; i++) {
            ws.setColumnWidth(i, (int) Math.round(cWidths[i] * 256));
        }

        // Clustered column chart (mirrors openpyxl's BarChart: type col,
        // style 10, clustered, overlap 0, legend at bottom, titles from row 3).
        int n = chartCompanies.size();
        XSSFDrawing drawing = ws.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 5, 2, 20, 22); // F3:U23
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("Intrinsic Value per Share: Muse vs NotebookLM");
        chart.setTitleOverlay(false);
        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);
        legend.setOverlay(false);
        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        bottomAxis.setTitle("Ticker");
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setTitle("USD per share");
        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(
                ws, new CellRangeAddress(3, 3 + n - 1, 0, 0));   // A4:A22
        XDDFNumericalDataSource<Double> vals1 = XDDFDataSourcesFactory.fromNumericCellRange(
                ws, new CellRangeAddress(3, 3 + n - 1, 1, 1));   // B4:B22
        XDDFNumericalDataSource<Double> vals2 = XDDFDataSourcesFactory.fromNumericCellRange(
                ws, new CellRangeAddress(3, 3 + n - 1, 2, 2));   // C4:C22
        XDDFBarChartData chartData =
                (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        chartData.setBarDirection(BarDirection.COL);
        chartData.setBarGrouping(BarGrouping.CLUSTERED);
        chartData.setOverlap((byte) 0);
        chartData.setVaryColors(false);
        XDDFChartData.Series s1 = chartData.addSeries(cats, vals1);
        s1.setTitle(null, new CellReference("'Comparison Chart'!$B$3"));
        XDDFChartData.Series s2 = chartData.addSeries(cats, vals2);
        s2.setTitle(null, new CellReference("'Comparison Chart'!$C$3"));
        chart.plot(chartData);
        // Chart style 10, like openpyxl's chart.style = 10.
        chart.getCTChartSpace().addNewStyle().setVal((short) 10);

        ws.createFreezePane(0, 3); // "A4"
        wb.setPrintArea(wb.getSheetIndex(ws), "A1:U" + (3 + n));
    }

    // ------------------------------------------------------------------
    // Methodology sheet.
    // ------------------------------------------------------------------
    static void buildMethodology(JSONObject data) {
        XSSFSheet ws = wb.createSheet("Methodology");
        baseSheet(ws, false);
        ws.setTabColor(hexColor(NAVY));
        title(ws, "DCF Methodology",
                "Formula specification and vetted reference set preserved from the supplied methodology data.",
                5);

        JSONObject m = data.getJSONObject("methodology");
        String[][] methodRows = {
            {"Years 1–5", m.getString("years_1_5")},
            {"Years 6–10", m.getString("years_6_10")},
            {"Mid-year discounting", m.getString("discounting")},
            {"Terminal value", m.getString("terminal")},
            {"Enterprise to equity", m.getString("equity")},
            {"Intrinsic value", m.getString("iv")},
            {"GRAB revenue model", m.getString("grab")},
            {"SOFI FCFE model", m.getString("sofi")},
            {"What changed vs base", "The fixed method replaces end-of-year discounting with mid-year discounting and replaces an abrupt terminal-growth step with a five-year linear fade during Years 6–10."},
        };
        cell(ws, 3, 1).setCellValue("Component");
        merge(ws, 3, 2, 3, 5);
        cell(ws, 3, 2).setCellValue("Specification");
        headerRow(ws, 3, 1, 5, NAVY);
        int r = 4;
        for (String[] row : methodRows) {
            XSSFCell k = cell(ws, r, 1);
            k.setCellValue(row[0]);
            k.setCellStyle(style(StyleSpec.blank()
                    .withFont("Liberation Sans", 10).withBold().withFontColor(NAVY)
                    .withFill(LIGHT_BLUE)));
            merge(ws, r, 2, r, 5);
            XSSFCell v = cell(ws, r, 2);
            v.setCellValue(row[1]);
            XSSFCellStyle vs = modStyle("method-wrap", v.getCellStyle(), s -> {
                s.setWrapText(true);
                s.setVerticalAlignment(VerticalAlignment.TOP);
            });
            v.setCellStyle(vs);
            for (int col = 1; col <= 5; col++) {
                XSSFCell cc = cell(ws, r, col);
                XSSFCellStyle o = cc.getCellStyle();
                XSSFCellStyle s = modStyle("method-border", o,
                        GenerateDcfWorkbook::thinMidGray);
                cc.setCellStyle(s);
            }
            ws.getRow(r - 1).setHeightInPoints((r >= 9 && r <= 12) ? 48 : 34);
            r++;
        }

        int refStart = 15;
        merge(ws, refStart, 1, refStart, 5);
        XSSFCell refTitle = cell(ws, refStart, 1);
        refTitle.setCellValue("Vetted methodology sources");
        refTitle.setCellStyle(style(StyleSpec.blank()
                .withFont("Liberation Sans", 12).withBold().withFontColor(WHITE)
                .withFill(TEAL)));
        String[] refs = m.getString("sources_anchored").split(";");
        for (int i = 0; i < refs.length; i++) {
            int rr = refStart + 1 + i;
            setNumber(cell(ws, rr, 1), i + 1);
            merge(ws, rr, 2, rr, 5);
            XSSFCell rc = cell(ws, rr, 2);
            rc.setCellValue(refs[i].strip());
            XSSFCellStyle rs = modStyle("method-ref-wrap", rc.getCellStyle(),
                    s -> s.setWrapText(true));
            rc.setCellStyle(rs);
            // Blue font for the reference text (applied after, like the Python).
            XSSFCellStyle rs2 = modStyle("method-ref-blue", rc.getCellStyle(),
                    s -> s.setFont(cachedFont("Liberation Sans", 10, false,
                            false, false, BLUE)));
            rc.setCellStyle(rs2);
            for (int col = 1; col <= 5; col++) {
                XSSFCell cc = cell(ws, rr, col);
                XSSFCellStyle o = cc.getCellStyle();
                XSSFCellStyle s = modStyle("method-ref-border", o,
                        GenerateDcfWorkbook::thinMidGray);
                cc.setCellStyle(s);
            }
        }
        int noteRow = refStart + refs.length + 2;
        merge(ws, noteRow, 1, noteRow, 5);
        XSSFCell note = cell(ws, noteRow, 1);
        note.setCellValue("The supplied source dataset provided reference names but no external URLs; "
                + "none were invented. Company-specific filing/source notes appear on Assumptions and Sources.");
        note.setCellStyle(style(StyleSpec.blank()
                .withFont("Liberation Sans", 9).withItalic().withFontColor(GRAY)
                .withWrap()));
        ws.getRow(noteRow - 1).setHeightInPoints(34);

        int[] mWidths = {26, 24, 24, 24, 24};
        for (int i = 0; i < mWidths.length; i++) {
            ws.setColumnWidth(i, (int) Math.round(mWidths[i] * 256));
        }
        ws.createFreezePane(0, 3); // "A4"
    }

    // ------------------------------------------------------------------
    // Sources ledger sheet.
    // ------------------------------------------------------------------
    static void buildSources(JSONObject data, JSONArray companies,
                             Map<String, Integer> compRow,
                             Map<String, Integer> assumpRow) {
        XSSFSheet ws = wb.createSheet("Sources");
        baseSheet(ws, true);
        ws.setTabColor(hexColor(GRAY));
        title(ws, "Source Ledger",
                "One row per valuation input, assumption, or derived output. "
                        + "External URLs were not supplied; exact source notes are preserved without guessing links.",
                7);

        String[] sHeaders = {"Company / ticker", "Field", "Value", "Unit / format",
                "Source type", "Source detail", "Workbook location"};
        for (int i = 0; i < sHeaders.length; i++) {
            cell(ws, 3, i + 1).setCellValue(sHeaders[i]);
        }
        headerRow(ws, 3, 1, 7, GRAY);

        List<Object[]> ledger = new ArrayList<>();
        for (int i = 0; i < companies.length(); i++) {
            JSONObject c = companies.getJSONObject(i);
            String who = c.getString("name") + " (" + c.getString("ticker") + ")";
            int cr = compRow.get(c.getString("ticker"));
            ledger.add(new Object[]{who, "Market close", orDash(jsonVal(c, "market")),
                    "USD/share", "Supplied market input",
                    "Friday close 2026-09-25; " + data.getString("market_asof")
                            + ". External URL not supplied.",
                    "Comparison!B" + cr});
                        String dcfDetail;
            if (!c.isNull("fixed")) {
                dcfDetail = "Formula-driven Muse DCF on company model tab using the assumption source below.";
            } else {
                JSONObject a = c.optJSONObject("assumptions");
                dcfDetail = (a != null && !a.isNull("unavailable"))
                        ? a.getString("unavailable") : "Not computed in supplied data.";
            }
            ledger.add(new Object[]{who, "Our DCF", orDash(jsonVal(c, "fixed")),
                    "USD/share", "DCF formula / supplied check", dcfDetail,
                    "Comparison!C" + cr});
            String gOut = c.isNull("growth_outlook") ? null
                    : c.optString("growth_outlook", null);
            String gThesis = c.isNull("growth_thesis") ? null
                    : c.optString("growth_thesis", null);
            String gVal = gOut == null ? "not yet researched"
                    : (gThesis == null || gThesis.isEmpty() ? gOut
                            : gOut + " \u2014 " + gThesis);
            ledger.add(new Object[]{who, "Buy below", orDash(buyBelowVal(jsonVal(c, "fixed"), reqMargin(readMarginRequirements(), c.getString("ticker")))),
                    "USD/share", "Workbook formula",
                    "Our DCF x (1 - required margin). The only buy-below column.",
                    "Comparison!D" + cr});
            ledger.add(new Object[]{who, "Muse speculation price", orDash(jsonVal(c, "muse_speculation")),
                    "USD/share", "Supplied Muse output",
                    "Our DCF pulled halfway toward the outside median plus the growth-outlook nudge. Display-only.",
                    "Comparison!E" + cr});
            ledger.add(new Object[]{who, "Growth outlook", gVal,
                    "text", "Muse assessment",
                    "Muse's growth assessment from the deep-research forward-earnings brief: "
                            + "High/Moderate/Low plus a one-line basis. 'Not yet researched' until "
                            + "that ticker's brief lands; never a plausible fill. Not a blessed value.",
                    "Comparison!F" + cr});
            Object fwdRaw = c.isNull("notebooklm_forward") ? null : c.get("notebooklm_forward");
            Object fwdVal = (fwdRaw instanceof Number) ? ((Number) fwdRaw).doubleValue() : DASH;
            String fwdDetail;
            if (fwdVal instanceof Number) {
                fwdDetail = "NotebookLM forward-tilt value from workbook_data.json: filings/history "
                        + "DCF with a modest forward-looking tilt; NotebookLM assumptions not recorded.";
            } else {
                fwdDetail = (fwdRaw != null) ? fwdRaw.toString()
                        : "Not recorded in supplied input data.";
            }
            ledger.add(new Object[]{who, "NotebookLM forward tilt", fwdVal, "USD/share",
                    "Supplied NotebookLM output", fwdDetail, "Comparison!I" + cr});
            Object nbRaw = c.isNull("notebooklm") ? null : c.get("notebooklm");
            Object nbVal = (nbRaw instanceof Number) ? ((Number) nbRaw).doubleValue() : DASH;
            String nbDetail;
            if (nbVal instanceof Number) {
                nbDetail = "NotebookLM independent value from workbook_data.json; NotebookLM assumptions not recorded.";
            } else {
                nbDetail = (nbRaw != null) ? nbRaw.toString()
                        : "Not recorded in supplied input data.";
            }
            ledger.add(new Object[]{who, "NotebookLM", nbVal, "USD/share",
                    "Supplied NotebookLM output", nbDetail, "Comparison!G" + cr});
            ledger.add(new Object[]{who, "NotebookLM speculation price", orDash(jsonVal(c, "nlm_speculation")),
                    "USD/share", "Supplied NotebookLM output",
                    "DERIVED BY NOTEBOOKLM ITSELF — reserved slot, blank until verified and blessed.",
                    "Comparison!H" + cr});
            ledger.add(new Object[]{who, "Creator value", orDash(jsonVal(c, "creator")),
                    "USD/share", "Creator value",
                    !c.isNull("creator_note") ? c.getString("creator_note")
                            : "Not recorded in supplied input data.",
                    "Comparison!J" + cr});
            ledger.add(new Object[]{who, "Projection price (analyst target)",
                    orDash(jsonVal(c, "analyst")),
                    "USD/share", "Projection price (analyst target)",
                    "Not recorded in supplied input data.", "Comparison!K" + cr});
            ledger.add(new Object[]{who, "Website consensus",
                    orDash(jsonVal(c, "website_consensus")),
                    "USD/share", "Website consensus",
                    "Median of ValueInvesting.io, Simply Wall St, Finbox, and ValueSense DCF values. "
                            + "Its own column, never blended.",
                    "Comparison!L" + cr});
            JSONObject a = c.optJSONObject("assumptions");
            if (a != null && !a.isNull("base")) {
                int ar = assumpRow.get(c.getString("ticker"));
                String src = !a.isNull("src") ? a.getString("src") : "Supplied input dataset.";
                String[][] afields = {
                    {"Model kind", "kind", "text"}, {"Base FCF / revenue", "base", "USD M"},
                    {"Operating cash flow", "ocf", "USD M"},
                    {"Capital expenditure", "capex", "USD M"},
                    {"Initial growth", "g", "percent"},
                    {"Discount rate / CoE", "w", "percent"},
                    {"Terminal growth", "t", "percent"},
                    {"Diluted shares", "shares", "M shares"},
                    {"Net debt", "netdebt", "USD M"},
                    {"Start margin", "start_margin", "percent"},
                    {"Year-5 margin", "end_margin", "percent"},
                };
                for (int k = 0; k < afields.length; k++) {
                    ledger.add(new Object[]{who, afields[k][0],
                            orDash(jsonVal(a, afields[k][1])), afields[k][2],
                            "Supplied model assumption", src,
                            "Assumptions!" + colLetter(3 + k) + ar});
                }
            }
        }

        int r = 4;
        for (Object[] row : ledger) {
            for (int col = 0; col < row.length; col++) {
                XSSFCell cc = cell(ws, r, col + 1);
                Object val = row[col];
                // Mirror: the "Value" column is blue when it holds a number.
                boolean numericBlue = (col == 2) && (val instanceof Number);
                if (val instanceof Number n) {
                    setNumber(cc, n);
                } else {
                    cc.setCellValue(val.toString());
                }
                XSSFCellStyle o = cc.getCellStyle();
                boolean blue = numericBlue;
                XSSFCellStyle s = modStyle(
                        blue ? "ledger-blue" : "ledger-black", o, ms -> {
                            ms.setFont(cachedFont("Liberation Sans", 9, false,
                                    false, false, blue ? BLUE : BLACK));
                            thinMidGray(ms);
                            ms.setWrapText(true);
                            ms.setVerticalAlignment(VerticalAlignment.TOP);
                        });
                cc.setCellStyle(s);
            }
            ws.getRow(r - 1).setHeightInPoints(42);
            r++;
        }
        ws.setAutoFilter(new CellRangeAddress(2, 2 + ledger.size(), 0, 6));
        ws.createFreezePane(0, 3); // "A4"
        int[] sWidths = {31, 23, 17, 15, 24, 68, 22};
        for (int i = 0; i < sWidths.length; i++) {
            ws.setColumnWidth(i, (int) Math.round(sWidths[i] * 256));
        }
    }

    /** null -> "—", otherwise the value unchanged (mirrors the Python ledger). */
    static Object orDash(Object v) {
        return v == null ? DASH : v;
    }
}
