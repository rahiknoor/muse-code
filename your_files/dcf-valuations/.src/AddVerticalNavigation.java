import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTBookView;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTBookViews;

/**
 * AddVerticalNavigation — Java port of add_vertical_navigation.py.
 *
 * <p>Rebuilds the workbook from the pristine pre-navigation backup and
 * inserts a "Navigation" sheet at the front: a vertical index of every
 * section with internal hyperlinks, styled in the workbook's navy/blue
 * palette. Each existing sheet's A1 title becomes a return link to the
 * Navigation page (with an explanatory comment), and the long horizontal
 * sheet-tab strip is hidden so the vertical page is the primary
 * navigation.
 *
 * <p>Regeneration is idempotent: the backup is copied from the live book
 * only once, and every run rebuilds from that pristine copy.
 *
 * <p>Usage:
 * <pre>
 *   ~/workspace/java/jdk-21/bin/java -Dfile.encoding=UTF-8 -cp "/home/hatch/workspace/lib/poi/*" \
 *       AddVerticalNavigation.java [rootDirOverride]
 * </pre>
 */
public class AddVerticalNavigation {

    // Palette (same restrained navy/blue set as the Python script).
    static final String NAVY = "17324D";
    static final String BLUE = "2F75B5";
    static final String LIGHT_BLUE = "EAF2F8";
    static final String LIGHT_GRAY = "F8FAFC";
    static final String GRAY = "667085";
    static final String WHITE = "FFFFFF";
    static final String BLACK = "000000";
    static final String THIN_GRAY = "D9E2F3";

    static XSSFWorkbook wb;
    static CreationHelper helper;
    static XSSFSheet nav;

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
        Path root = args.length > 0
                ? Path.of(args[0])
                : Path.of(homeDir(), "workspace/your_files/dcf-valuations");
        Path book = root.resolve("31-Company DCF Valuation Model.xlsx");
        Path backup = root.resolve(".src/31-Company DCF Valuation Model.before-vertical-navigation.xlsx");

        if (!Files.exists(book)) {
            throw new java.io.FileNotFoundException(book.toString());
        }
        if (!Files.exists(backup)) {
            Files.copy(book, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }

        // Keep regeneration idempotent by rebuilding from the pristine pre-navigation source.
        try (FileInputStream fis = new FileInputStream(backup.toFile())) {
            wb = new XSSFWorkbook(fis);
        }
        helper = wb.getCreationHelper();
        int navIdx = wb.getSheetIndex("Navigation");
        if (navIdx >= 0) {
            wb.removeSheetAt(navIdx);
        }

        nav = wb.createSheet("Navigation");
        wb.setSheetOrder("Navigation", 0);
        nav.setDisplayGridlines(false);
        nav.createFreezePane(0, 4); // "A5"
        nav.setTabColor(hexColor(NAVY));

        merge(1, 1, 1, 6);
        XSSFCell h1 = cell(1, 1);
        h1.setCellValue("DCF Model Navigation");
        h1.setCellStyle(style("Liberation Sans", 18, true, false, false, WHITE, NAVY,
                null, null, HorizontalAlignment.LEFT, VerticalAlignment.CENTER, false));
        nav.getRow(0).setHeightInPoints(28);

        merge(2, 1, 2, 6); // A2:F2 (mirrors the Python's 'A2:F2')
        XSSFCell h2 = cell(2, 1);
        h2.setCellValue(
                "Choose a section from the left-side list below. On any worksheet, click its title in A1 to return here.");
        h2.setCellStyle(style("Liberation Sans", 10, false, true, false, GRAY,
                null, null, null, null, VerticalAlignment.TOP, true));
        nav.getRow(1).setHeightInPoints(30);

        int[] widths = {14, 34, 25, 18, 18, 18};
        for (int i = 0; i < widths.length; i++) {
            nav.setColumnWidth(i, (int) Math.round(widths[i] * 256));
        }

        section(4, "OVERVIEW");
        String[][] overview = {
            {"Cover", "Cover", "Model scope and reading guide"},
            {"Comparison", "Comparison", "All valuation sources in one table"},
            {"Assumptions", "Assumptions", "Company inputs and source notes"},
            {"Comparison Chart", "Comparison Chart", "Visual comparison of values"},
            {"Disagreements", "Disagreements", "Large DCF differences and assumption checks"},
        };
        int r = 5;
        for (String[] row : overview) {
            addLink(r, row[0], row[1], row[2], "");
            r++;
        }

        section(11, "COMPANY DCF MODELS");
        List<String> companySheets = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            String name = wb.getSheetName(i);
            if (name.startsWith("DCF ")) {
                companySheets.add(name);
            }
        }
        r = 12;
        for (String sheet : companySheets) {
            String ticker = sheet.substring("DCF ".length()); // removeprefix("DCF ")
            String titleVal = firstCellString(wb.getSheet(sheet));
            String company = (titleVal != null ? titleVal : sheet).split(" \\(")[0];
            addLink(r, company, sheet, "10-year fixed-methodology model", ticker);
            r++;
        }

        int refRow = 12 + companySheets.size() + 1;
        section(refRow, "REFERENCE");
        addLink(refRow + 1, "Methodology", "Methodology",
                "Formulas and vetted methodology sources", "");
        addLink(refRow + 2, "Sources", "Sources", "Company-specific source trail", "");

        nav.setAutoFilter(new CellRangeAddress(10, 10 + companySheets.size() + 1, 0, 2));
        nav.getCTWorksheet().getSheetViews().getSheetViewArray(0).setZoomScale(90);
        wb.setPrintArea(0, "A1:F" + (refRow + 2));
        nav.setFitToPage(true);
        nav.getPrintSetup().setFitWidth((short) 1);
        nav.getPrintSetup().setFitHeight((short) 1);
        nav.setMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin, 0.3);
        nav.setMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin, 0.3);
        nav.setMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin, 0.5);
        nav.setMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin, 0.5);
        // openpyxl's PageMargins default header/footer to 0.5 (POI uses 0.3).
        nav.setMargin(org.apache.poi.ss.usermodel.Sheet.HeaderMargin, 0.5);
        nav.setMargin(org.apache.poi.ss.usermodel.Sheet.FooterMargin, 0.5);

        // Make each existing sheet's title a return link without moving cells or formulas.
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            XSSFSheet ws = wb.getSheetAt(i);
            if (ws.getSheetName().equals("Navigation")) {
                continue;
            }
            XSSFRow row0 = ws.getRow(0);
            XSSFCell titleCell = row0 == null ? null : row0.getCell(0);
            if (cellString(titleCell) == null) {
                continue;
            }
            setSheetLink(titleCell, "Navigation");
            addComment(ws, titleCell,
                    "Click this title to return to the vertical Navigation page.");
            XSSFFont oldFont = titleCell.getCellStyle().getFont();
            XSSFFont newFont = wb.createFont();
            newFont.setFontName(oldFont.getFontName());
            newFont.setFontHeightInPoints(oldFont.getFontHeightInPoints());
            newFont.setBold(oldFont.getBold());
            newFont.setItalic(oldFont.getItalic());
            newFont.setUnderline(FontUnderline.SINGLE);
            if (oldFont.getXSSFColor() != null) {
                newFont.setColor(oldFont.getXSSFColor());
            }
            XSSFCellStyle newStyle = wb.createCellStyle();
            newStyle.cloneStyleFrom(titleCell.getCellStyle());
            newStyle.setFont(newFont);
            titleCell.setCellStyle(newStyle);
        }

        // Use the vertical page as the workbook's primary navigation and
        // remove the long horizontal tab strip.
        wb.setActiveSheet(0);
        CTBookViews views = wb.getCTWorkbook().getBookViews();
        if (views != null && views.sizeOfWorkbookViewArray() > 0) {
            CTBookView view = views.getWorkbookViewArray(0);
            view.setActiveTab(0);
            view.setShowSheetTabs(false);
        }

        wb.getProperties().getCoreProperties()
                .setTitle("31-Company DCF Valuation Model");
        try (FileOutputStream fos = new FileOutputStream(book.toFile())) {
            wb.write(fos);
        }
        System.out.println(book);
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    static XSSFColor hexColor(String hex) {
        // openpyxl pads 6-digit hex with a "00" alpha prefix
        // ("FFFFFF" -> rgb "00FFFFFF"); emit the same 4-byte ARGB.
        byte[] argb = new byte[4];
        argb[0] = 0x00;
        for (int i = 0; i < 3; i++) {
            argb[i + 1] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new XSSFColor(argb, null);
    }

    /** 1-based cell access on the Navigation sheet, creating as needed. */
    static XSSFCell cell(int r, int c) {
        XSSFRow row = nav.getRow(r - 1);
        if (row == null) {
            row = nav.createRow(r - 1);
        }
        XSSFCell cell = row.getCell(c - 1);
        return cell == null ? row.createCell(c - 1) : cell;
    }

    static void merge(int r1, int c1, int r2, int c2) {
        nav.addMergedRegion(new CellRangeAddress(r1 - 1, r2 - 1, c1 - 1, c2 - 1));
    }

    static String quoteSheetName(String name) {
        return "'" + name.replace("'", "''") + "'";
    }

    /**
     * Internal "#'Sheet'!A1" hyperlink (mirrors openpyxl's
     * `cell.hyperlink = "#'Sheet'!A1"`). POI's DOCUMENT type writes it as
     * the hyperlink's location attribute, which Excel resolves as an
     * internal link; the destination string matches openpyxl's exactly.
     * (POI's URL type would emit openpyxl's relationship form, but its URI
     * validation rejects sheet names containing spaces.)
     */
    static void setSheetLink(XSSFCell cell, String sheetName) {
        Hyperlink link = helper.createHyperlink(HyperlinkType.DOCUMENT);
        link.setAddress("#" + quoteSheetName(sheetName) + "!A1");
        cell.setHyperlink(link);
    }

    /** Null-safe string value of a cell (mirrors openpyxl's `.value`). */
    static String cellString(XSSFCell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case FORMULA -> switch (cell.getCachedFormulaResultType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> String.valueOf(cell.getNumericCellValue());
                default -> null;
            };
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            default -> null;
        };
    }

    /** String value of a sheet's A1 cell, or null. */
    static String firstCellString(XSSFSheet ws) {
        XSSFRow row0 = ws.getRow(0);
        return cellString(row0 == null ? null : row0.getCell(0));
    }

    /**
     * Full style builder for this script's small style vocabulary.
     * (null fill/border args mean "leave unset", mirroring openpyxl.)
     */
    static XSSFCellStyle style(String fontName, int fontSize, boolean bold,
                              boolean italic, boolean underline, String fontColor,
                              String fillColor, String borderColor, String bottomBorderColor,
                              HorizontalAlignment hAlign, VerticalAlignment vAlign,
                              boolean wrap) {
        XSSFCellStyle st = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontName(fontName);
        f.setFontHeightInPoints((short) fontSize);
        f.setBold(bold);
        f.setItalic(italic);
        if (underline) {
            f.setUnderline(FontUnderline.SINGLE);
        }
        if (fontColor != null) {
            f.setColor(hexColor(fontColor));
        }
        st.setFont(f);
        if (fillColor != null) {
            st.setFillForegroundColor(hexColor(fillColor));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (borderColor != null) {
            // full thin border (mirrors openpyxl's Border with all sides)
            st.setBorderLeft(BorderStyle.THIN);
            st.setBorderRight(BorderStyle.THIN);
            st.setBorderTop(BorderStyle.THIN);
            st.setBorderBottom(BorderStyle.THIN);
            XSSFColor bc = hexColor(borderColor);
            st.setLeftBorderColor(bc);
            st.setRightBorderColor(bc);
            st.setTopBorderColor(bc);
            st.setBottomBorderColor(bc);
        }
        if (bottomBorderColor != null) {
            st.setBorderBottom(BorderStyle.THIN);
            st.setBottomBorderColor(hexColor(bottomBorderColor));
        }
        if (hAlign != null) {
            st.setAlignment(hAlign);
        }
        if (vAlign != null) {
            st.setVerticalAlignment(vAlign);
        }
        if (wrap) {
            // openpyxl omits wrapText when false; only set it when true.
            st.setWrapText(true);
        }
        return st;
    }

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

    /** Blue section banner spanning all six columns (mirrors section()). */
    static void section(int row, String title) {
        merge(row, 1, row, 6);
        XSSFCell c = cell(row, 1);
        c.setCellValue(title);
        c.setCellStyle(style("Liberation Sans", 11, true, false, false, WHITE, BLUE,
                null, null, HorizontalAlignment.LEFT, VerticalAlignment.CENTER, false));
        nav.getRow(row - 1).setHeightInPoints(21);
    }

    /**
     * One index row: ticker, hyperlink label, detail (mirrors add_link()).
     * Zebra striping and the bottom border apply to all six columns; the
     * label cell keeps its blue underlined hyperlink font.
     */
    static void addLink(int row, String label, String sheet, String detail, String ticker) {
        if (!ticker.isEmpty()) {
            cell(row, 1).setCellValue(ticker);
        }
        XSSFCell linkCell = cell(row, 2);
        linkCell.setCellValue(label);
        // Internal link encoded exactly like openpyxl: an external
        // relationship whose target is "#'Sheet'!A1" (POI's URL hyperlink
        // type rejects spaces via URI validation, so build it directly).
        setSheetLink(linkCell, sheet);
        XSSFCellStyle linkStyle = wb.createCellStyle();
        linkStyle.setFont(linkFont());
        linkCell.setCellStyle(linkStyle);
        cell(row, 3).setCellValue(detail);

        // Python stripes even rows light gray and paints odd rows solid
        // white (not "no fill").
        String stripe = (row % 2 == 0) ? LIGHT_GRAY : WHITE;
        for (int col = 1; col <= 6; col++) {
            XSSFCell c = cell(row, col);
            // Clone the cell's current style and only add fill/border/
            // alignment. Python's `if c.font == Font():` guard never fires
            // (a bare Font() is all-None, never equal to the default Calibri),
            // so non-link cells keep the default font — replicate that.
            XSSFCellStyle st = wb.createCellStyle();
            st.cloneStyleFrom(c.getCellStyle());
            st.setFillForegroundColor(hexColor(stripe));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            st.setBorderBottom(BorderStyle.THIN);
            st.setBottomBorderColor(hexColor(THIN_GRAY));
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            c.setCellStyle(st);
        }
        nav.getRow(row - 1).setHeightInPoints(detail.isEmpty() ? 22 : 26);
    }

    /** Blue, bold, single-underlined hyperlink font (shared by all labels). */
    static XSSFFont linkFont() {
        XSSFFont f = wb.createFont();
        f.setFontName("Liberation Sans");
        f.setFontHeightInPoints((short) 10);
        f.setBold(true);
        f.setUnderline(FontUnderline.SINGLE);
        f.setColor(hexColor(BLUE));
        return f;
    }
}
