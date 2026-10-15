package com.securityexpert.nexus.ui2.sanitizer.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.sanitizer.Sanitizer;
import com.securityexpert.nexus.ui2.sanitizer.TestVault;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKey;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKind;

/**
 * Sanitize then revert for every text format (DATA_SANITIZER_CONTRACT.md §2 "Reversible on request"): text, CSV and
 * JSON come back byte for byte, XLSX cell for cell; every sanitized output is scanned for every original the vault
 * recorded ("no original survives"). Type detection is by content only.
 */
class RoundTripTest {

    @TempDir Path dir;
    private final TestVault vault = new TestVault();
    private final Sanitizer sanitizer = new Sanitizer(TestVault.key(), KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04")));

    static final String LOG = String.join("\r\n",
            "Oct 16 12:00:01 corefw kernel: DROP SRC=192.0.2.10 DST=198.51.100.20 MAC=00:1a:2b:3c:4d:5e",
            "<34>1 2026-10-16T12:00:02Z edge-fw-01 sshd - - Accepted for admin from 203.0.113.7 port 51515",
            "route 198.51.100.0/24 via 192.0.2.1 dev eth0; v6 2001:db8:10::/48 via fe80::1%eth0",
            "user john.doe@example.com opened https://portal.example.com:8443/login?next=/ from [2001:db8::42]:443",
            "firmware v1.2.3.4 oid 1.3.6.1.4.1 at 12:30:45 netmask 255.255.255.0 bcast 255.255.255.255",
            "DNS www.example.com A 192.0.2.80, PTR 80.2.0.192.in-addr.arpa; mixed Mail.Example.Com UPPER WWW.EXAMPLE.COM",
            "") + "tail without newline 192.0.2.99";

    @Test
    void textComesBackByteForByteAndNoOriginalSurvives() throws Exception {
        byte[] original = LOG.getBytes(StandardCharsets.UTF_8);
        byte[] sanitized = sanitizeBytes(original, "log.txt");
        assertNoOriginal(sanitized);
        assertTrue(new String(sanitized, StandardCharsets.UTF_8).contains("\r\n"), "line terminators kept");
        assertTrue(new String(sanitized, StandardCharsets.UTF_8).contains("FW-TANGO-04"));
        assertArrayEquals(original, revertBytes(sanitized));
    }

    @Test
    void legacyCodePageTextIsByteTransparent() throws Exception {
        byte[] original = ("Kullanıcı bağlantısı 192.0.2.5 çözüldü\n").getBytes(Charset.forName("windows-1254"));
        byte[] sanitized = sanitizeBytes(original, "tr.log");
        assertFalse(new String(sanitized, StandardCharsets.ISO_8859_1).contains("192.0.2.5"));
        assertArrayEquals(original, revertBytes(sanitized));
    }

    @Test
    void csvKeepsQuotingDelimitersAndHostColumns() throws Exception {
        String csv = "﻿Hostname;Address;Note;Count\r\n"
                + "corefw;192.0.2.10;\"uplink to 198.51.100.1; \"\"primary\"\"\";5\r\n"
                + "branchfw;\"2001:db8::7\";\"multi\nline www.example.com\";6\r\n"
                + "unknown;203.0.113.0/24;;7\r\n";
        byte[] original = csv.getBytes(StandardCharsets.UTF_8);
        Path in = write("table.data", original);
        assertEquals(FileType.CSV, Detection.of(in).type());
        assertEquals(';', Detection.of(in).delimiter());
        byte[] sanitized = sanitizeBytes(original, "table.data");
        String text = new String(sanitized, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿Hostname;Address;Note;Count\r\n"), "header kept");
        assertTrue(text.matches("(?s).*\r\nh-[0-9a-f]{10};[0-9.]+;\"uplink to [0-9.]+; \"\"primary\"\"\";5\r\n.*"), text);
        assertTrue(text.contains("\r\nunknown;"), "stop words are no host names");
        assertNoOriginal(sanitized);
        assertArrayEquals(original, revertBytes(sanitized));
    }

    @Test
    void jsonKeepsKeysWhitespaceAndEscapes() throws Exception {
        String json = "{\n  \"hostname\": \"corefw\",\n  \"192.0.2.1\": \"key kept\",\n  \"peers\": [\"198.51.100.2\", "
                + "\"fw \\u0041 www.example.com\\n\", 42, true, null, {\"mac\": \"00-1A-2B-3C-4D-5E\"}],\n"
                + "  \"note\": \"slash \\/ and \\\"quotes\\\" around 203.0.113.9\"\n}\n{\"ndjson\": \"192.0.2.77\"}\n";
        byte[] original = json.getBytes(StandardCharsets.UTF_8);
        Path in = write("x.bin", original);
        assertEquals(FileType.JSON, Detection.of(in).type());
        byte[] sanitized = sanitizeBytes(original, "x.bin");
        String text = new String(sanitized, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"192.0.2.1\": \"key kept\""), "keys are structure, not values");
        assertTrue(text.matches("(?s)\\{\n  \"hostname\": \"h-[0-9a-f]{10}\",.*"), text);
        assertTrue(text.contains("\"fw \\u0041 h-"), "raw escapes before the change are kept");
        assertFalse(text.contains("198.51.100.2\""));
        assertArrayEquals(original, revertBytes(sanitized));
    }

    @Test
    void invalidJsonIsTreatedAsTextNotRefused() throws Exception {
        Path in = write("broken.json", "{\"a\": 192.0.2.1,,}".getBytes(StandardCharsets.UTF_8));
        assertEquals(FileType.TEXT, Detection.of(in).type());
        assertFalse(new String(sanitizeBytes(Files.readAllBytes(in), "broken2.json"), StandardCharsets.UTF_8).contains("192.0.2.1"));
    }

    @Test
    void xlsxCellsSharedInlineFormulaAndHostColumnsRoundTrip() throws Exception {
        Path in = dir.resolve("book.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Devices");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Device");
            h.createCell(1).setCellValue("Management IP");
            h.createCell(2).setCellValue("Notes");
            h.createCell(3).setCellValue("Ports");
            Object[][] rows = {
                    {"corefw", "192.0.2.10", "peer 198.51.100.1 and www.example.com", 22},
                    {"edge-fw-01", "2001:db8::7", "owner john.doe@example.com", 443},
                    {"branchfw", "203.0.113.0/24", "plain text", 8443}};
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r + 1);
                for (int c = 0; c < 4; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Integer i) row.createCell(c).setCellValue(i);
                    else row.createCell(c).setCellValue((String) v);
                }
            }
            s.createRow(4).createCell(2).setCellFormula("IF(A2=\"corefw\",\"192.0.2.10\",\"none\")");
            s.getRow(4).getCell(2).setCellValue("192.0.2.10");
            try (OutputStream out = Files.newOutputStream(in)) {
                wb.write(out);
            }
        }
        assertEquals(FileType.XLSX, Detection.of(in).type());
        Path out = dir.resolve("book.sanitized");
        Sanitizer.Result result = sanitizer.sanitize(in, out, vault);
        assertEquals(FileType.XLSX, result.type());
        assertNoOriginal(unzippedText(out));
        List<String> sanitizedCells = cells(out);
        assertTrue(sanitizedCells.get(4).matches("h-[0-9a-f]{10}"), "bare host under the Device header: " + sanitizedCells.get(4));
        assertEquals("FW-TANGO-04", sanitizedCells.get(8));
        assertEquals("Device", sanitizedCells.get(0));
        assertEquals("22.0", sanitizedCells.get(7), "numbers untouched");
        Path back = dir.resolve("book.reverted");
        sanitizer.revert(out, back, vault);
        assertEquals(cells(in), cells(back), "every cell value comes back");
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(out))) {
            String formula = wb.getSheetAt(0).getRow(4).getCell(2).getCellFormula();
            assertTrue(formula.startsWith("IF(A2=\"h-"), formula);
            assertFalse(formula.contains("192.0.2.10"));
        }
    }

    @Test
    void unsupportedAndDangerousInputsAreRefusedByContent() throws Exception {
        assertThrows(UnsupportedInputException.class, () -> Detection.of(write("empty.txt", new byte[0])));
        assertThrows(UnsupportedInputException.class,
                () -> Detection.of(write("capture.pcap", new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 3})));
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write("192.0.2.1".getBytes(StandardCharsets.UTF_8));
        }
        assertThrows(UnsupportedInputException.class, () -> Detection.of(write("book.xlsx", zip.toByteArray())));
        // A renamed CSV is still CSV, a renamed log is still text: names never decide.
        assertEquals(FileType.TEXT, Detection.of(write("data.xlsx", "just 192.0.2.1 text\n".getBytes(StandardCharsets.UTF_8))).type());
    }

    @Test
    void revertNeverInventsOriginalsForUnknownTokens() throws Exception {
        byte[] sanitized = sanitizeBytes("a 192.0.2.10 b".getBytes(StandardCharsets.UTF_8), "one.txt");
        String token = new String(sanitized, StandardCharsets.UTF_8).split(" ")[1];
        String foreign = "x " + token + " 198.51.100.250 h-abcdefabcd FW-ZULU-09 " + token.replace(".", ":") + "\n";
        String back = new String(revertBytes(foreign.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertEquals("x 192.0.2.10 198.51.100.250 h-abcdefabcd FW-ZULU-09 " + token.replace(".", ":") + "\n", back);
        assertNotEquals(0, vault.lookups);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private Path write(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private byte[] sanitizeBytes(byte[] input, String name) throws IOException {
        Path in = write(name, input);
        Path out = dir.resolve(name + ".out");
        sanitizer.sanitize(in, out, vault);
        return Files.readAllBytes(out);
    }

    private byte[] revertBytes(byte[] input) throws IOException {
        Path in = write("returned-" + System.nanoTime(), input);
        Path out = dir.resolve(in.getFileName() + ".back");
        sanitizer.revert(in, out, vault);
        return Files.readAllBytes(out);
    }

    /** Every original the vault recorded (addresses, MACs, names of four or more characters) is absent. */
    private void assertNoOriginal(byte[] output) {
        String text = new String(output, StandardCharsets.ISO_8859_1) + "\n" + new String(output, StandardCharsets.UTF_8);
        List<String> survivors = new ArrayList<>();
        for (Map.Entry<VaultKey, String> e : vault.entries.entrySet()) {
            String original = e.getValue();
            if (e.getKey().kind() == VaultKind.LABEL && original.length() < 4) continue;
            if (containsToken(text, original)) survivors.add(e.getKey().kind().name());
        }
        assertEquals(List.of(), survivors, "kinds of originals still present in the output");
    }

    private static boolean containsToken(String text, String value) {
        int at = text.indexOf(value);
        while (at >= 0) {
            boolean before = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            int end = at + value.length();
            boolean after = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (before && after) return true;
            at = text.indexOf(value, at + 1);
        }
        return false;
    }

    private static byte[] unzippedText(Path xlsx) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(xlsx))) {
            while (z.getNextEntry() != null) {
                all.write(z.readAllBytes());
                all.write('\n');
            }
        }
        return all.toByteArray();
    }

    private static List<String> cells(Path xlsx) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(xlsx); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            for (Row row : wb.getSheetAt(0)) {
                for (Cell cell : row) {
                    out.add(cell.getCellType() == CellType.FORMULA ? cell.getStringCellValue()
                            : cell.getCellType() == CellType.NUMERIC ? String.valueOf(cell.getNumericCellValue())
                            : cell.getStringCellValue());
                }
            }
        }
        return out;
    }
}
