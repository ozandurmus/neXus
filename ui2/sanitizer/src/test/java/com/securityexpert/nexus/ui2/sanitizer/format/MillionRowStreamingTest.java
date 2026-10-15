package com.securityexpert.nexus.ui2.sanitizer.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLStreamReader;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.sanitizer.Sanitizer;
import com.securityexpert.nexus.ui2.sanitizer.TestVault;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;

/**
 * A million rows of synthetic CSV and XLSX through the sanitizer inside a 256 MB test heap (sanitizer
 * build.gradle.kts): the handlers stream, nothing scales with the file. The data is generated here, never committed.
 */
class MillionRowStreamingTest {

    private static final int ROWS = 1_000_000;

    @TempDir Path dir;
    private final Sanitizer sanitizer = new Sanitizer(TestVault.key(), KnownNames.NONE);

    @Test
    void aMillionCsvRowsStreamInBoundedMemory() throws Exception {
        Path in = dir.resolve("big.csv");
        try (BufferedWriter w = Files.newBufferedWriter(in, StandardCharsets.UTF_8)) {
            w.write("hostname,address,peer,note\n");
            for (int i = 0; i < ROWS; i++) {
                w.write("srv" + i + ",198.51.100." + (i % 256) + ",192.0.2." + (i % 200) + ",\"seen srv" + i + " at 203.0.113."
                        + (i % 100) + "\"\n");
            }
        }
        Path out = dir.resolve("big.out");
        Sanitizer.Result result = sanitizer.sanitize(in, out, TokenSink.DISCARD);
        assertEquals(FileType.CSV, result.type());
        assertEquals(ROWS + 1L, result.units());
        long lines = 0;
        try (BufferedReader r = Files.newBufferedReader(out, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                lines++;
                if (lines == 1) {
                    assertEquals("hostname,address,peer,note", line);
                    continue;
                }
                assertFalse(line.contains("198.51.100.") || line.contains("192.0.2.") || line.contains("203.0.113."), "row " + lines);
                assertFalse(line.startsWith("srv"), "host column replaced");
            }
        }
        assertEquals(ROWS + 1L, lines);
    }

    @Test
    void aMillionXlsxRowsStreamInBoundedMemory() throws Exception {
        Path in = dir.resolve("big.xlsx");
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
            Sheet s = wb.createSheet("hosts");
            Row header = s.createRow(0);
            header.createCell(0).setCellValue("Hostname");
            header.createCell(1).setCellValue("Address");
            header.createCell(2).setCellValue("Count");
            for (int i = 0; i < ROWS; i++) {
                Row row = s.createRow(i + 1);
                row.createCell(0).setCellValue("srv" + i);
                row.createCell(1).setCellValue("198.51.100." + (i % 256));
                row.createCell(2).setCellValue(i);
            }
            try (OutputStream out = Files.newOutputStream(in)) {
                wb.write(out);
            }
            wb.dispose();
        }
        Path out = dir.resolve("big.sanitized");
        Sanitizer.Result result = sanitizer.sanitize(in, out, TokenSink.DISCARD);
        assertEquals(FileType.XLSX, result.type());
        assertEquals((long) ROWS, result.counts().get("ipv4"));
        assertEquals((long) ROWS, result.counts().get("hostname"));
        long rows = 0;
        boolean sawHeader = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.getName().startsWith("xl/worksheets/sheet")) continue;
                XMLStreamReader r = XlsxFormat.inputs().createXMLStreamReader(new NonClosing(zip));
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamReader.START_ELEMENT && r.getLocalName().equals("row")) rows++;
                    if (ev == XMLStreamReader.CHARACTERS) {
                        String t = r.getText();
                        if (t.equals("Hostname")) sawHeader = true;
                        assertFalse(t.startsWith("198.51.100.") || t.matches("srv\\d+"), "original survived");
                    }
                }
            }
        }
        assertTrue(sawHeader);
        assertEquals(ROWS + 1L, rows);
    }

    private static final class NonClosing extends java.io.FilterInputStream {
        NonClosing(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
        }
    }
}
