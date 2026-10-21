package com.securityexpert.nexus.ui2.sanitizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.sanitizer.core.IdentityTokens;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.core.SerialRule;

/**
 * XLSX columns under a serial header (2026-10-21): every value below the header -- shared and inline strings, a
 * formula's cached result, and numbers, which the text rules never see -- becomes the inventory's one-way {@code SN-}
 * token; the workbook stays valid and a revert leaves the tokens as they are.
 *
 * <p>Synthetic values only, built at runtime from seeded generators; the key is {@link TestVault#key()}. Leak checks
 * assert booleans over every XML part of the output, so a failure never prints a value.</p>
 */
class XlsxSerialColumnsTest {

    private final Mappers mappers = new Mappers(SanitizerKey.of(TestVault.key()), KnownNames.NONE);
    private final IdentityTokens tokens = new IdentityTokens(TestVault.key());

    @TempDir
    Path dir;

    private static String digits(int length, long seed) {
        Random random = new Random(seed);
        StringBuilder sb = new StringBuilder(length);
        sb.append((char) ('1' + random.nextInt(9)));
        for (int i = 1; i < length; i++) sb.append((char) ('0' + random.nextInt(10)));
        return sb.toString();
    }

    private static String unzippedText(Path xlsx) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (ZipFile z = new ZipFile(xlsx.toFile())) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                try (InputStream in = z.getInputStream(e)) {
                    in.transferTo(all);
                }
            }
        }
        return all.toString(StandardCharsets.UTF_8);
    }

    /** The stored text of one cell's {@code <v>} in the first sheet, as the writer put it (a number's own form). */
    private static String rawValue(Path xlsx, String ref) throws IOException {
        String sheet;
        try (ZipFile z = new ZipFile(xlsx.toFile()); InputStream in = z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml"))) {
            sheet = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("<c r=\"" + ref + "\"[^>]*>(?:<f>[^<]*</f>)?<v>([^<]*)</v>").matcher(sheet);
        assertTrue(m.find(), "the test workbook carries the cell");
        return m.group(1);
    }

    private static boolean containsAny(String text, String... values) {
        for (String v : values) if (text.contains(v)) return true;
        return false;
    }

    @Test
    void serialColumnsAreMaskedWhateverTheCellTypeAndTheWorkbookStaysValid() throws Exception {
        String number = digits(12, 1);
        String alnum = "FX" + digits(10, 2);
        String shared = "QZ" + digits(9, 3);
        String count = digits(4, 4);
        Path in = dir.resolve("inventory.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Units");
            Row h = s.createRow(0);
            String[] headers = {"Name", "  SERIAL   NUMBER ", "S/N", "Mgmt", "Count", "seri no"};
            for (int c = 0; c < headers.length; c++) h.createCell(c).setCellValue(headers[c]);
            Row r1 = s.createRow(1);
            r1.createCell(0).setCellValue("unit a");
            r1.createCell(1).setCellValue(Double.parseDouble(number));
            r1.createCell(2).setCellValue(alnum);
            r1.createCell(3).setCellValue("192.0.2.10");
            r1.createCell(4).setCellValue(Double.parseDouble(count));
            r1.createCell(5).setCellValue(shared);
            Row r2 = s.createRow(2);
            r2.createCell(0).setCellValue("unit b");
            r2.createCell(1).setCellFormula("B2+0");
            r2.getCell(1).setCellValue(Double.parseDouble(number));
            r2.createCell(2).setCellValue(true);
            r2.createCell(4).setCellValue(Double.parseDouble(count));
            r2.createCell(5).setCellValue(shared); // the same shared string as row 2
            Row r3 = s.createRow(3);
            r3.createCell(0).setCellValue("unit c");
            r3.createCell(1); // blank, styled cell: stays blank
            try (OutputStream out = Files.newOutputStream(in)) {
                wb.write(out);
            }
        }
        String rawNumber = rawValue(in, "B2");
        String rawFormula = rawValue(in, "B3");
        assertTrue(unzippedText(in).contains(rawNumber), "precondition: the number is stored as written");

        TestVault vault = new TestVault();
        Sanitizer engine = new Sanitizer(mappers);
        Path out = dir.resolve("inventory.sanitized.xlsx");
        engine.sanitize(in, out, vault);

        String xml = unzippedText(out);
        assertFalse(containsAny(xml, number, alnum, shared, rawNumber, rawFormula), "a serial survived in a part (value withheld)");
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(out))) {
            Sheet s = wb.getSheetAt(0);
            assertEquals("  SERIAL   NUMBER ", s.getRow(0).getCell(1).getStringCellValue(), "the header is kept");
            Cell numeric = s.getRow(1).getCell(1);
            assertEquals(CellType.STRING, numeric.getCellType(), "a number cell became a string cell");
            assertEquals(tokens.serial(rawNumber), numeric.getStringCellValue(), "the inventory's SN- token");
            assertEquals(tokens.serial(alnum), s.getRow(1).getCell(2).getStringCellValue());
            assertEquals(tokens.serial(shared), s.getRow(1).getCell(5).getStringCellValue(), "a shared string under a serial header");
            assertEquals(tokens.serial(shared), s.getRow(2).getCell(5).getStringCellValue());
            Cell formula = s.getRow(2).getCell(1);
            assertEquals(CellType.FORMULA, formula.getCellType(), "the formula is kept");
            assertEquals("B2+0", formula.getCellFormula());
            assertEquals(CellType.STRING, formula.getCachedFormulaResultType(), "its cached result is the token");
            assertEquals(tokens.serial(rawFormula), formula.getStringCellValue());
            assertEquals(CellType.BOOLEAN, s.getRow(2).getCell(2).getCellType(), "a boolean is not a serial");
            assertEquals(CellType.BLANK, s.getRow(3).getCell(1).getCellType(), "a blank cell stays blank");
            assertEquals(Double.parseDouble(count), s.getRow(1).getCell(4).getNumericCellValue(), "numbers elsewhere untouched");
            assertEquals(CellType.NUMERIC, s.getRow(2).getCell(4).getCellType());
            assertFalse(s.getRow(1).getCell(3).getStringCellValue().contains("192.0.2.10"), "the text rules still run");
        }

        Path back = dir.resolve("inventory.reverted.xlsx");
        engine.revert(out, back, vault);
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(back))) {
            Sheet s = wb.getSheetAt(0);
            assertEquals("192.0.2.10", s.getRow(1).getCell(3).getStringCellValue(), "vault tokens revert");
            assertEquals(tokens.serial(rawNumber), s.getRow(1).getCell(1).getStringCellValue(), "a serial token is one way");
            assertEquals(tokens.serial(alnum), s.getRow(1).getCell(2).getStringCellValue());
            assertEquals(CellType.FORMULA, s.getRow(2).getCell(1).getCellType());
        }
        assertFalse(containsAny(unzippedText(back), number, alnum, shared, rawNumber), "a revert never brings a serial back");
    }

    @Test
    void inlineStringAndNumberCellsOfAStreamedWorkbookAreMaskedToo() throws Exception {
        String inline = "PX" + digits(10, 5);
        String number = digits(10, 6);
        Path in = dir.resolve("streamed.xlsx");
        try (SXSSFWorkbook wb = new SXSSFWorkbook()) { // writes inline strings, not shared ones
            Sheet s = wb.createSheet("Units");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Host");
            h.createCell(1).setCellValue("S.N.");
            h.createCell(2).setCellValue("Serial #");
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("unit");
            r.createCell(1).setCellValue(inline);
            r.createCell(2).setCellValue(Double.parseDouble(number));
            try (OutputStream out = Files.newOutputStream(in)) {
                wb.write(out);
            }
            wb.dispose();
        }
        String rawNumber = rawValue(in, "C2");
        Path out = dir.resolve("streamed.sanitized.xlsx");
        new Sanitizer(mappers).sanitize(in, out, new TestVault());
        assertFalse(containsAny(unzippedText(out), inline, number, rawNumber), "a serial survived (value withheld)");
        try (InputStream stream = Files.newInputStream(out); XSSFWorkbook wb = new XSSFWorkbook(stream)) {
            Row r = wb.getSheetAt(0).getRow(1);
            assertEquals(tokens.serial(inline), r.getCell(1).getStringCellValue());
            assertEquals(tokens.serial(rawNumber), r.getCell(2).getStringCellValue());
            assertEquals(CellType.STRING, r.getCell(2).getCellType());
        }
    }

    @Test
    void serialHeadersAreRecognisedInAnyCaseAndSpacing() {
        for (String key : new String[] {"serial", "  SERIAL   NUMBER ", "Serial #", "serial_no.", "serialnumber", "SerialNo",
            "sn", "SN", "S/N", "S / N", "S.N.", "seri no", "Seri No.", "SERİ NO", "Seri Numarası", "serial-nr"}) {
            assertTrue(SerialRule.isSerialKey(key), "a serial header");
        }
        for (String key : new String[] {"serials_seen", "snmp", "sensor", "description", "", "name", "serial console",
            "sn mode", "seriously"}) {
            assertFalse(SerialRule.isSerialKey(key), "not a serial header");
        }
    }
}
