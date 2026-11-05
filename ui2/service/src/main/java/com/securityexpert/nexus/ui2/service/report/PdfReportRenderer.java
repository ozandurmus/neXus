package com.securityexpert.nexus.ui2.service.report;

import java.awt.Color;
import java.util.List;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfStream;
import com.lowagie.text.pdf.PdfWriter;

/**
 * PDF with OpenPDF (Reports R1 contract "Renderers": one pure-Java dependency, no headless browser, no native binary).
 * A4 landscape, the built-in Helvetica faces, a header block (projection, generated, parameters, data as of, notices),
 * each section's figures, tables (header row repeated on every page) and notes, and a footer on every page with the
 * page number, the template and the projection.
 *
 * <p>Content streams are written uncompressed: a report is small, and the masked projection can then be checked byte
 * for byte (the masking tests do, besides extracting the text). The built-in faces cover Latin-1 (WinAnsi) only, so a
 * RAW name with characters outside it renders with gaps; masked names are ASCII.</p>
 */
public final class PdfReportRenderer implements ReportRenderer {

    private static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD);
    private static final Font H2 = new Font(Font.HELVETICA, 12, Font.BOLD);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD);
    private static final Font SMALL = new Font(Font.HELVETICA, 7, Font.NORMAL);
    private static final Color HEADER_FILL = new Color(0xE8, 0xEA, 0xED);
    private static final Color RAW_FILL = new Color(0xFC, 0xE8, 0xE6);
    private static final Color MASKED_FILL = new Color(0xE6, 0xF4, 0xEA);

    @Override
    public ReportFormat format() {
        return ReportFormat.PDF;
    }

    @Override
    public byte[] render(ReportDocument d) {
        ReportRenderer.requireProjected(d);
        CappedBuffer out = new CappedBuffer(MAX_BYTES);
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 42);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setCompressionLevel(PdfStream.NO_COMPRESSION);
            writer.setPageEvent(new Footer(d.templateId() + " " + d.title() + " | " + ReportRenderer.projectionLabel(d)));
            doc.addTitle(d.templateId() + " " + d.title());
            doc.addCreator("neXus Reports");
            doc.open();
            doc.add(new Paragraph(d.templateId() + "  " + d.title(), TITLE));
            doc.add(new Paragraph(d.question(), BODY));
            PdfPTable banner = new PdfPTable(1);
            banner.setWidthPercentage(100);
            banner.setSpacingBefore(6);
            PdfPCell projection = new PdfPCell(new Phrase("Projection: " + ReportRenderer.projectionLabel(d), BOLD));
            projection.setBackgroundColor("RAW".equals(d.projection()) ? RAW_FILL : MASKED_FILL);
            projection.setPadding(5);
            banner.addCell(projection);
            doc.add(banner);
            StringBuilder params = new StringBuilder("Generated " + d.generatedAt() + " (UTC)  |  Audience: " + d.audience());
            d.parameters().forEach((k, v) -> params.append("  |  ").append(k).append(": ").append(v));
            doc.add(new Paragraph(params.toString(), BODY));

            doc.add(heading("Data as of"));
            doc.add(planes(d.dataAsOf()));
            for (String notice : d.notices()) doc.add(new Paragraph(notice, BOLD));

            for (ReportDocument.Section s : d.sections()) {
                doc.add(heading(s.title()));
                if (!s.kpis().isEmpty()) {
                    PdfPTable kpis = new PdfPTable(3);
                    kpis.setWidthPercentage(100);
                    kpis.setWidths(new float[] {4, 3, 1});
                    kpis.setSpacingAfter(6);
                    for (ReportDocument.Kpi k : s.kpis()) {
                        kpis.addCell(cell(k.label(), BODY, null));
                        kpis.addCell(cell(k.value(), BOLD, null));
                        kpis.addCell(cell(k.tone(), SMALL, null));
                    }
                    doc.add(kpis);
                }
                for (ReportDocument.Table t : s.tables()) {
                    doc.add(new Paragraph(t.title(), BOLD));
                    PdfPTable table = new PdfPTable(t.columns().size());
                    table.setWidthPercentage(100);
                    table.setHeaderRows(1);
                    table.setSpacingBefore(3);
                    table.setSpacingAfter(8);
                    for (ReportDocument.Column c : t.columns()) table.addCell(cell(c.label(), BOLD, HEADER_FILL));
                    if (t.rows().isEmpty()) {
                        PdfPCell empty = cell(ReportRenderer.show(t.emptyText()), BODY, null);
                        empty.setColspan(t.columns().size());
                        table.addCell(empty);
                    }
                    for (List<ReportDocument.Cell> row : t.rows()) {
                        for (ReportDocument.Cell c : row) table.addCell(cell(ReportRenderer.show(c.text()), BODY, null));
                    }
                    doc.add(table);
                }
                for (String note : s.notes()) doc.add(new Paragraph(note, SMALL));
            }
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("PDF rendering failed", e);
        } catch (com.lowagie.text.ExceptionConverter e) {
            if (e.getException() instanceof TooLarge tooLarge) throw tooLarge;
            throw e;
        }
        return out.toByteArray();
    }

    private static Paragraph heading(String text) {
        Paragraph p = new Paragraph(text, H2);
        p.setSpacingBefore(10);
        p.setSpacingAfter(4);
        return p;
    }

    private static PdfPTable planes(List<ReportDocument.PlaneAsOf> planes) throws DocumentException {
        PdfPTable t = new PdfPTable(6);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {3, 3, 3, 2, 1, 2});
        for (String h : List.of("Plane", "Newest", "Oldest", "Coverage", "Stale", "Unit")) t.addCell(cell(h, BOLD, HEADER_FILL));
        for (ReportDocument.PlaneAsOf p : planes) {
            t.addCell(cell(p.plane(), BODY, null));
            t.addCell(cell(ReportRenderer.show(ReportJson.iso(p.newest())), BODY, null));
            t.addCell(cell(ReportRenderer.show(ReportJson.iso(p.oldest())), BODY, null));
            t.addCell(cell(p.covered() + " of " + p.total(), BODY, null));
            t.addCell(cell(Integer.toString(p.stale()), BODY, null));
            t.addCell(cell(p.unit(), BODY, null));
        }
        return t;
    }

    private static PdfPCell cell(String text, Font font, Color fill) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setPadding(3);
        if (fill != null) c.setBackgroundColor(fill);
        return c;
    }

    /** "Page n | template | projection" on every page. */
    private static final class Footer extends PdfPageEventHelper {
        private final String text;

        Footer(String text) {
            this.text = text;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Rectangle page = document.getPageSize();
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase(new Chunk("Page " + writer.getPageNumber() + "  |  " + text, SMALL)),
                    document.leftMargin(), page.getBottom() + 20, 0);
        }
    }
}
