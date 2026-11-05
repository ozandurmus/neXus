package com.securityexpert.nexus.ui2.service.report;

import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.RAW_VALUES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.CellKind;

/**
 * Reports R1 contract "Tests": no raw hostname, IP or serial in the bytes of a MASKED PDF, XLSX or CSV -- for every R1
 * report over the synthetic estate, and for a document carrying every identity cell kind. The scan covers the file's
 * raw bytes, every decompressed stream (PDF Flate streams, XLSX zip entries) and the extracted PDF text. Each check is
 * proven non-vacuous: the same scan finds the raw values in the RAW projection of the same document.
 */
class ReportMaskingBytesTest {

    private static final List<ReportRenderer> RENDERERS =
            List.of(new PdfReportRenderer(), new XlsxReportRenderer(), new CsvReportRenderer());

    @ParameterizedTest
    @EnumSource(ReportTemplate.class)
    void maskedFilesOfEveryReportCarryNoRawIdentity(ReportTemplate template) throws Exception {
        ReportDocument doc = ReportBuilder.forTemplate(template).build(ReportFixtures.context(template), new ReportFixtures.Data());
        ReportDocument masked = ReportFixtures.masker().project(doc, ProjectionMode.MASKED);
        ReportDocument raw = ReportFixtures.masker().project(doc, ProjectionMode.RAW);
        for (ReportRenderer renderer : RENDERERS) {
            String maskedScan = scan(renderer.format(), renderer.render(masked));
            for (String value : RAW_VALUES) {
                assertFalse(maskedScan.contains(value), template.id() + " " + renderer.format() + " MASKED leaks a raw value");
            }
            assertTrue(maskedScan.contains("MASKED"), "the projection is stamped in the file");
            String rawScan = scan(renderer.format(), renderer.render(raw));
            assertTrue(rawScan.contains("synthetic-raw"), template.id() + " " + renderer.format()
                    + ": the scan must see raw names in the RAW file, or the masked check proves nothing");
        }
        if (template == ReportTemplate.RPT_02 || template == ReportTemplate.RPT_03 || template == ReportTemplate.RPT_01) {
            assertTrue(scan(ReportFormat.CSV, new CsvReportRenderer().render(masked)).contains("FW-"),
                    "device names are pseudonyms, not blanks");
        }
        if (template == ReportTemplate.RPT_05) {
            assertTrue(scan(ReportFormat.CSV, new CsvReportRenderer().render(masked)).contains("CLS-"));
        }
    }

    @Test
    void everyIdentityCellKindAndFreeTextIsMaskedInEveryFormat() throws Exception {
        ReportDocument doc = identityDocument();
        ReportDocument masked = ReportFixtures.masker().project(doc, ProjectionMode.MASKED);
        ReportDocument raw = ReportFixtures.masker().project(doc, ProjectionMode.RAW);
        List<String> maskedCells = masked.sections().get(0).tables().get(0).rows().get(0).stream().map(Cell::text).toList();
        assertTrue(maskedCells.get(0).startsWith("FW-"), maskedCells.get(0));
        assertTrue(maskedCells.get(1).startsWith("CLS-"), maskedCells.get(1));
        assertTrue(maskedCells.get(3).startsWith("SN-"), maskedCells.get(3));
        assertFalse(maskedCells.get(2).equals(ReportFixtures.IP_1));
        assertTrue(maskedCells.get(4).contains(maskedCells.get(0)), "a known name repeated in prose is rewritten too");
        for (ReportRenderer renderer : RENDERERS) {
            String maskedScan = scan(renderer.format(), renderer.render(masked));
            for (String value : RAW_VALUES) {
                assertFalse(maskedScan.contains(value), renderer.format() + " MASKED leaks a raw value");
            }
            String rawScan = scan(renderer.format(), renderer.render(raw));
            for (String value : List.of(ReportFixtures.HOST_A, ReportFixtures.CLUSTER_1, ReportFixtures.IP_1, ReportFixtures.SERIAL)) {
                assertTrue(rawScan.contains(value), renderer.format() + " scan non-vacuity for one identity kind");
            }
        }
    }

    @Test
    void renderersRefuseAnUnprojectedDocumentAndTheMaskerProjectsOnce() {
        ReportDocument doc = identityDocument();
        for (ReportRenderer renderer : RENDERERS) {
            assertThrows(IllegalStateException.class, () -> renderer.render(doc), renderer.format().name());
        }
        assertThrows(IllegalStateException.class, () -> ReportJson.toMap(doc));
        ReportDocument masked = ReportFixtures.masker().project(doc, ProjectionMode.MASKED);
        assertThrows(IllegalStateException.class, () -> ReportFixtures.masker().project(masked, ProjectionMode.MASKED));
        assertEquals("MASKED", masked.projection());
    }

    @Test
    void theOnScreenJsonIsTheSameProjectionAndCarriesNoMaskerReference() throws Exception {
        ReportDocument doc = ReportBuilder.forTemplate(ReportTemplate.RPT_02)
                .build(ReportFixtures.context(ReportTemplate.RPT_02), new ReportFixtures.Data());
        Map<String, Object> json = ReportJson.toMap(ReportFixtures.masker().project(doc, ProjectionMode.MASKED));
        String text = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(json);
        for (String value : RAW_VALUES) assertFalse(text.contains(value), "on-screen JSON leaks a raw value");
        assertFalse(text.contains("dev-a"), "device ids (masker references) are never serialized");
        assertFalse(text.contains("\"ref\""));
    }

    static ReportDocument identityDocument() {
        var table = new ReportDocument.Table("ids", "Identities",
                ReportBuilder.columns("device", "Device", "cluster", "Cluster", "address", "Address", "serial", "Serial",
                        "note", "Note"),
                List.of(List.of(Cell.device(ReportFixtures.HOST_A, "dev-a", null), Cell.cluster(ReportFixtures.CLUSTER_1),
                        new Cell(ReportFixtures.IP_1, CellKind.ADDRESS, null),
                        new Cell(ReportFixtures.SERIAL, CellKind.SERIAL, null),
                        Cell.text("Reached " + ReportFixtures.HOST_A + " at " + ReportFixtures.IP_2))), "none");
        var section = new ReportDocument.Section("s", "Identities", List.of(), List.of(), List.of(table), List.of());
        return new ReportDocument("RPT-01", "Identity test", "Executive", "q", Instant.parse("2026-11-05T09:00:00Z"),
                ReportDocument.UNPROJECTED, Map.of("from", "2026-10-30"), List.of(), List.of(), List.of(section));
    }

    /** Everything a reader of the file could get at: raw bytes, decompressed streams/entries, extracted PDF text. */
    static String scan(ReportFormat format, byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder(new String(bytes, StandardCharsets.ISO_8859_1));
        switch (format) {
            case XLSX -> {
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        out.append('\n').append(entry.getName()).append('\n')
                                .append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            case PDF -> {
                for (byte[] stream : flateStreams(bytes)) out.append('\n').append(new String(stream, StandardCharsets.ISO_8859_1));
                PdfReader reader = new PdfReader(bytes);
                PdfTextExtractor extractor = new PdfTextExtractor(reader);
                for (int page = 1; page <= reader.getNumberOfPages(); page++) out.append('\n').append(extractor.getTextFromPage(page));
                reader.close();
            }
            default -> out.append('\n').append(new String(bytes, StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** Inflates every stream that inflates (Flate); uncompressed streams are already in the raw bytes. */
    static List<byte[]> flateStreams(byte[] pdf) {
        List<byte[]> out = new ArrayList<>();
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        int from = 0;
        while ((from = text.indexOf("stream", from)) >= 0) {
            int start = from + "stream".length();
            if (start < text.length() && text.charAt(start) == '\r') start++;
            if (start < text.length() && text.charAt(start) == '\n') start++;
            int end = text.indexOf("endstream", start);
            if (end < 0) break;
            try {
                Inflater inflater = new Inflater();
                inflater.setInput(pdf, start, end - start);
                ByteArrayOutputStream inflated = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                while (!inflater.finished() && !inflater.needsInput()) {
                    int n = inflater.inflate(buf);
                    if (n == 0 && inflater.needsDictionary()) break;
                    inflated.write(buf, 0, n);
                }
                inflater.end();
                if (inflated.size() > 0) out.add(inflated.toByteArray());
            } catch (java.util.zip.DataFormatException ignored) {
                // not a Flate stream
            }
            from = end + "endstream".length();
        }
        return out;
    }
}
