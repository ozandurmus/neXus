package com.securityexpert.nexus.ui2.service.report;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * XLSX with the existing Apache POI (Reports R1 contract "Renderers"). A "Summary" sheet carries the header (report,
 * projection, generated, parameters, data as of, notices) and every section's figures and notes; each table gets its
 * own sheet. Every value is written as a string cell, never a formula. The package is repacked with fixed entry times
 * and fixed document properties, so the same document gives the same bytes.
 */
public final class XlsxReportRenderer implements ReportRenderer {

    private static final Instant FIXED_TIME = Instant.parse("2000-01-01T00:00:00Z");
    private static final long FIXED_ZIP_TIME = FIXED_TIME.toEpochMilli();

    @Override
    public ReportFormat format() {
        return ReportFormat.XLSX;
    }

    @Override
    public byte[] render(ReportDocument d) {
        ReportRenderer.requireProjected(d);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle bold = wb.createCellStyle();
            bold.setFont(boldFont);

            XSSFSheet summary = wb.createSheet("Summary");
            int[] r = {0};
            put(summary, r, bold, d.templateId() + " " + d.title());
            put(summary, r, null, "Projection", ReportRenderer.projectionLabel(d));
            put(summary, r, null, "Generated", d.generatedAt().toString());
            put(summary, r, null, "Audience", d.audience());
            put(summary, r, null, "Question", d.question());
            d.parameters().forEach((k, v) -> put(summary, r, null, "Parameter " + k, v));
            r[0]++;
            put(summary, r, bold, "Data as of", "Newest", "Oldest", "Covered", "Total", "Stale", "Unit");
            for (ReportDocument.PlaneAsOf p : d.dataAsOf()) {
                put(summary, r, null, p.plane(), ReportRenderer.show(ReportJson.iso(p.newest())),
                        ReportRenderer.show(ReportJson.iso(p.oldest())), Integer.toString(p.covered()),
                        Integer.toString(p.total()), Integer.toString(p.stale()), p.unit());
            }
            if (!d.notices().isEmpty()) {
                r[0]++;
                for (String notice : d.notices()) put(summary, r, null, "Notice", notice);
            }
            Set<String> sheetNames = new HashSet<>(Set.of("summary"));
            for (ReportDocument.Section s : d.sections()) {
                r[0]++;
                put(summary, r, bold, s.title());
                for (ReportDocument.Kpi k : s.kpis()) put(summary, r, null, k.label(), k.value(), k.tone());
                for (String note : s.notes()) put(summary, r, null, "Note", note);
                for (ReportDocument.Table t : s.tables()) {
                    XSSFSheet sheet = wb.createSheet(sheetName(t.title(), sheetNames));
                    int[] tr = {0};
                    put(sheet, tr, bold, s.title() + " - " + t.title());
                    put(sheet, tr, bold, t.columns().stream().map(ReportDocument.Column::label).toArray(String[]::new));
                    if (t.rows().isEmpty()) put(sheet, tr, null, ReportRenderer.show(t.emptyText()));
                    for (List<ReportDocument.Cell> row : t.rows()) {
                        put(sheet, tr, null, row.stream().map(c -> ReportRenderer.show(c.text())).toArray(String[]::new));
                    }
                    for (int c = 0; c < t.columns().size(); c++) sheet.setColumnWidth(c, 24 * 256);
                    sheet.createFreezePane(0, 2);
                }
            }
            summary.setColumnWidth(0, 36 * 256);
            for (int c = 1; c < 7; c++) summary.setColumnWidth(c, 26 * 256);
            var props = wb.getProperties().getCoreProperties();
            props.setCreated(Optional.of(java.util.Date.from(FIXED_TIME)));
            props.setModified(Optional.of(java.util.Date.from(FIXED_TIME)));
            props.setCreator("neXus Reports");
            props.setLastModifiedByUser("neXus Reports");
            props.setTitle(d.templateId() + " " + d.title());
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            wb.write(raw);
            return repack(raw.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void put(XSSFSheet sheet, int[] row, CellStyle style, String... values) {
        Row r = sheet.createRow(row[0]++);
        for (int i = 0; i < values.length; i++) {
            var cell = r.createCell(i);
            cell.setCellValue(values[i] == null ? "" : values[i]);
            if (style != null) cell.setCellStyle(style);
        }
    }

    /** A unique sheet name of at most 31 characters without the characters Excel forbids. */
    static String sheetName(String title, Set<String> used) {
        String base = title.replaceAll("[\\\\/?*\\[\\]:']", " ").strip();
        if (base.isEmpty()) base = "Table";
        if (base.length() > 28) base = base.substring(0, 28).strip();
        String name = base;
        for (int i = 2; used.contains(name.toLowerCase(java.util.Locale.ROOT)); i++) name = base + " " + i;
        used.add(name.toLowerCase(java.util.Locale.ROOT));
        return name;
    }

    /** Same entries, same order, fixed timestamps; capped at the report size limit. */
    static byte[] repack(byte[] zip) throws IOException {
        CappedBuffer out = new CappedBuffer(MAX_BYTES);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
                ZipOutputStream zo = new ZipOutputStream(out)) {
            zo.setLevel(9);
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(FIXED_ZIP_TIME);
                zo.putNextEntry(copy);
                in.transferTo(zo);
                zo.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
