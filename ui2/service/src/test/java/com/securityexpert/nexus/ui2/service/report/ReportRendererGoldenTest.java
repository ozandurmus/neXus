package com.securityexpert.nexus.ui2.service.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;

/** Reports R1 contract "Tests": renderer golden tests (CSV byte-exact; XLSX cells and stable bytes; PDF text). */
class ReportRendererGoldenTest {

    static ReportDocument golden() {
        var table = new ReportDocument.Table("t", "Targets", ReportBuilder.columns("a", "Device", "b", "State"),
                List.of(List.of(Cell.text("-1+2"), Cell.status("STALE"))), "none");
        var section = new ReportDocument.Section("coverage", "Backup coverage", List.of(),
                List.of(new ReportDocument.Kpi("Backup targets", "3", "INFO")), List.of(table), List.of("Note one"));
        var plane = new ReportDocument.PlaneAsOf("backup", Instant.parse("2026-11-05T06:00:00Z"),
                Instant.parse("2026-11-04T03:00:00Z"), 2, 3, 1, "backup targets");
        var doc = new ReportDocument("RPT-03", "Backup coverage and anomalies", "Operations", "Q?",
                Instant.parse("2026-11-05T09:00:00Z"), ReportDocument.UNPROJECTED,
                new java.util.LinkedHashMap<>(Map.of("from", "2026-10-30")), List.of(plane),
                List.of("=cmd|' /C calc'!A0"), List.of(section));
        return ReportFixtures.masker().project(withTo(doc), ProjectionMode.MASKED);
    }

    private static ReportDocument withTo(ReportDocument d) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("from", "2026-10-30");
        params.put("to", "2026-11-05");
        return new ReportDocument(d.templateId(), d.title(), d.audience(), d.question(), d.generatedAt(), d.projection(),
                params, d.dataAsOf(), d.notices(), d.sections());
    }

    @Test
    void csvIsRfc4180WithFormulaEscapingByteForByte() {
        String expected = String.join("\r\n",
                "\"Report\",\"RPT-03 Backup coverage and anomalies\",\"\",\"\"",
                "\"Projection\",\"MASKED (AIView pseudonyms)\",\"\",\"\"",
                "\"Generated\",\"2026-11-05T09:00:00Z\",\"\",\"\"",
                "\"Parameter from\",\"2026-10-30\",\"\",\"\"",
                "\"Parameter to\",\"2026-11-05\",\"\",\"\"",
                "\"Data as of: backup\",\"newest 2026-11-05T06:00:00Z; oldest 2026-11-04T03:00:00Z; coverage 2 of 3 backup targets; stale 1\",\"\",\"\"",
                "\"Notice\",\"'=cmd|' /C calc'!A0\",\"\",\"\"",
                "\"\",\"\",\"\",\"\"",
                "\"Section\",\"Backup coverage\",\"\",\"\"",
                "\"Figure\",\"Backup targets\",\"3\",\"INFO\"",
                "\"\",\"\",\"\",\"\"",
                "\"Table\",\"Backup coverage - Targets\",\"\",\"\"",
                "\"Device\",\"State\",\"\",\"\"",
                "\"'-1+2\",\"STALE\",\"\",\"\"",
                "\"Note\",\"Note one\",\"\",\"\"") + "\r\n";
        assertEquals(expected, new String(new CsvReportRenderer().render(golden()), StandardCharsets.UTF_8));
    }

    @Test
    void csvCellGuardsEveryFormulaLead() {
        assertEquals("\"'=1+1\"", CsvReportRenderer.cell("=1+1"));
        assertEquals("\"'+1\"", CsvReportRenderer.cell("+1"));
        assertEquals("\"'-1\"", CsvReportRenderer.cell("-1"));
        assertEquals("\"'@SUM(A1)\"", CsvReportRenderer.cell("@SUM(A1)"));
        assertEquals("\"'\tx\"", CsvReportRenderer.cell("\tx"));
        assertEquals("\"' =1\"", CsvReportRenderer.cell(" =1"));
        assertEquals("\"a \"\"b\"\"\"", CsvReportRenderer.cell("a \"b\""));
        assertEquals("\"FW-TANGO-04\"", CsvReportRenderer.cell("FW-TANGO-04"));
    }

    @Test
    void xlsxHasASummarySheetAndOneSheetPerTableWithStringCellsAndStableBytes() throws Exception {
        byte[] first = new XlsxReportRenderer().render(golden());
        byte[] second = new XlsxReportRenderer().render(golden());
        assertArrayEquals(first, second, "the same document gives the same workbook bytes");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(first))) {
            assertEquals(2, wb.getNumberOfSheets());
            assertEquals("Summary", wb.getSheetName(0));
            assertEquals("Targets", wb.getSheetName(1));
            var summary = wb.getSheetAt(0);
            assertEquals("RPT-03 Backup coverage and anomalies", summary.getRow(0).getCell(0).getStringCellValue());
            assertEquals("MASKED (AIView pseudonyms)", summary.getRow(1).getCell(1).getStringCellValue());
            var targets = wb.getSheetAt(1);
            assertEquals("Backup coverage - Targets", targets.getRow(0).getCell(0).getStringCellValue());
            assertEquals(List.of("Device", "State"), cells(targets.getRow(1)));
            assertEquals(List.of("-1+2", "STALE"), cells(targets.getRow(2)));
            for (var sheet : wb) for (var row : sheet) for (var cell : row) {
                assertEquals(CellType.STRING, cell.getCellType(), "no formula and no number cell is ever written");
            }
        }
    }

    @Test
    void pdfCarriesTheHeaderTablesAndFooterAsText() throws Exception {
        byte[] pdf = new PdfReportRenderer().render(golden());
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.ISO_8859_1));
        PdfReader reader = new PdfReader(pdf);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        for (String expected : List.of("Backup coverage and anomalies", "Projection: MASKED (AIView pseudonyms)", "Data as of",
                "2 of 3", "Backup coverage", "Targets", "Device", "STALE", "Note one", "Page 1", "from: 2026-10-30")) {
            assertTrue(text.contains(expected), "PDF text lacks: " + expected);
        }
    }

    @Test
    void aReportBeyondTwentyFiveMebibytesFailsAndIsNeverTruncated() {
        String mebibyte = "x".repeat(1024 * 1024);
        List<List<Cell>> rows = new ArrayList<>();
        for (int i = 0; i < 26; i++) rows.add(List.of(Cell.text(mebibyte)));
        var table = new ReportDocument.Table("big", "Big", ReportBuilder.columns("v", "Value"), rows, "none");
        var doc = new ReportDocument("RPT-03", "Big", "Operations", "q", Instant.parse("2026-11-05T09:00:00Z"), "RAW",
                Map.of(), List.of(), List.of(), List.of(new ReportDocument.Section("s", "S", List.of(), List.of(),
                        List.of(table), List.of())));
        assertThrows(ReportRenderer.TooLarge.class, () -> new CsvReportRenderer().render(doc));
        var buffer = new ReportRenderer.CappedBuffer(10);
        buffer.write(new byte[10], 0, 10);
        assertThrows(ReportRenderer.TooLarge.class, () -> buffer.write(1));
        assertEquals(25L * 1024 * 1024, ReportRenderer.MAX_BYTES);
    }

    private static List<String> cells(org.apache.poi.ss.usermodel.Row row) {
        List<String> out = new ArrayList<>();
        for (var cell : row) out.add(cell.getStringCellValue());
        return out;
    }
}
