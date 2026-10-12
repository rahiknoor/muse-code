import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.file.*;

/**
 * Bakes the DCF workbook's formulas into static values and converts
 * the result to PDF for NotebookLM (which rejects .xlsx uploads).
 *
 * Why this exists: converting the live-formula workbook with LibreOffice
 * dropped 14 Comparison-sheet values (rendered as dashes) because the
 * converter mis-recalculated formulas. A values-only snapshot has nothing
 * left to calculate, so the PDF faithfully matches the .xlsx.
 *
 * The real workbook is never touched; this only produces
 * dcf_csv/DCF Valuation Model.pdf.
 */
public class BakeWorkbookPdf {
    public static void main(String[] args) throws Exception {
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) home = "/home/hatch";
        String xlsx = home + "/workspace/your_files/dcf-valuations/DCF Valuation Model.xlsx";
        String pdfOut = home + "/workspace/dcf_csv/DCF Valuation Model.pdf";
        Path tmpDir = Files.createTempDirectory("wbpdf");
        String baked = tmpDir.resolve("workbook-values.xlsx").toString();

        int bakedCount = 0;
        try (XSSFWorkbook wb = new XSSFWorkbook(new FileInputStream(xlsx))) {
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            for (Sheet sh : wb) {
                for (Row row : sh) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.FORMULA) {
                            CellValue v;
                            try {
                                v = ev.evaluate(cell);
                            } catch (Exception e) {
                                continue; // leave unevaluatable formulas as-is
                            }
                            if (v == null) continue;
                            switch (v.getCellType()) {
                                case NUMERIC: cell.setCellValue(v.getNumberValue()); break;
                                case STRING: cell.setCellValue(v.getStringValue()); break;
                                case BOOLEAN: cell.setCellValue(v.getBooleanValue()); break;
                                case ERROR: cell.setCellErrorValue(v.getErrorValue()); break;
                                default: cell.setBlank();
                            }
                            bakedCount++;
                        }
                    }
                }
            }
            try (FileOutputStream fos = new FileOutputStream(baked)) {
                wb.write(fos);
            }
        }
        System.out.println("baked " + bakedCount + " formula cells to values");

        ProcessBuilder pb = new ProcessBuilder("soffice", "--headless",
                "--convert-to", "pdf", "--outdir", tmpDir.toString(), baked);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        for (String line : out.split("\n")) {
            if (line.contains("->")) System.out.println("  " + line.trim());
        }
        if (rc != 0) throw new RuntimeException("soffice failed rc=" + rc);
        Path pdf = tmpDir.resolve("workbook-values.pdf");
        if (!Files.exists(pdf)) throw new RuntimeException("pdf not produced");
        Files.move(pdf, Paths.get(pdfOut), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("wrote " + pdfOut + " (" + Files.size(Paths.get(pdfOut)) + " bytes)");
    }
}
