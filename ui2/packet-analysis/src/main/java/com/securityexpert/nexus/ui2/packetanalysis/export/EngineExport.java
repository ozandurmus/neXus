package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Sql;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.PyCsv;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloatRepr;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Port of the reference {@code engine_export.py}: bounded native-table export as a ZIP of CSV files or a
 * minimal OOXML {@code .xlsx} workbook using inline strings (never formulas).
 */
public final class EngineExport {
    public static final Map<String, String> TABLES;
    public static final int EXCEL_MAX_ROWS = 1048576;

    static {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("per_second", Sql.get("engine_export", "per_second"));
        t.put("flow_port", Sql.get("engine_export", "flow_port"));
        t.put("periods", Sql.get("engine_export", "periods"));
        t.put("top_cycles", Sql.get("engine_export", "top_cycles"));
        t.put("rx_tx_components", Sql.get("engine_export", "rx_tx_components"));
        t.put("capture_points", Sql.get("engine_export", "capture_points"));
        t.put("flow_filters", Sql.get("engine_export", "flow_filters"));
        t.put("match_miss", Sql.get("engine_export", "match_miss"));
        TABLES = Collections.unmodifiableMap(t);
    }

    private EngineExport() {
    }

    /** Reference {@code column_name(index)}: 1-based column index to Excel column letters (A, B, ..., Z, AA, ...). */
    public static String columnName(int index) {
        StringBuilder result = new StringBuilder();
        long idx = index;
        while (idx > 0) {
            long[] dm = PyMath.divmod(idx - 1, 26);
            idx = dm[0];
            result.insert(0, (char) (65 + dm[1]));
        }
        return result.toString();
    }

    public static byte[] exportTables(Db db, String format) {
        if (!"csv".equals(format) && !"xlsx".equals(format)) {
            throw new IllegalArgumentException("Choose csv or xlsx");
        }
        if (!FieldDiff.truthy(Export.getSetting(db, "report"))
                || db.count(Sql.get("engine_export", "cycle_points_exists")) == 0L) {
            throw new IllegalArgumentException("Run the cycle analysis before exporting engine tables");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            if ("xlsx".equals(format)) {
                StringBuilder overrides = new StringBuilder();
                for (int i = 1; i <= TABLES.size(); i++) {
                    overrides.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                            .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                }
                writeEntry(archive, "[Content_Types].xml",
                        ("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                                + overrides + "</Types>").getBytes(StandardCharsets.UTF_8));
                writeEntry(archive, "_rels/.rels",
                        ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                                + "</Relationships>").getBytes(StandardCharsets.UTF_8));
                StringBuilder sheets = new StringBuilder();
                int sheetIdx = 1;
                for (String name : TABLES.keySet()) {
                    sheets.append("<sheet name=\"").append(name).append("\" sheetId=\"").append(sheetIdx)
                            .append("\" r:id=\"rId").append(sheetIdx).append("\"/>");
                    sheetIdx++;
                }
                writeEntry(archive, "xl/workbook.xml",
                        ("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                                + sheets + "</sheets></workbook>").getBytes(StandardCharsets.UTF_8));
                StringBuilder rels = new StringBuilder();
                for (int i = 1; i <= TABLES.size(); i++) {
                    rels.append("<Relationship Id=\"rId").append(i)
                            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                            .append(i).append(".xml\"/>");
                }
                writeEntry(archive, "xl/_rels/workbook.xml.rels",
                        ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + rels + "</Relationships>").getBytes(StandardCharsets.UTF_8));
            }
            int index = 1;
            for (Map.Entry<String, String> entry : TABLES.entrySet()) {
                String name = entry.getKey();
                String sql = entry.getValue();
                Db.QueryResult qr = db.query(sql);
                List<String> headers = qr.columns();
                String entryName = "csv".equals(format) ? name + ".csv" : "xl/worksheets/sheet" + index + ".xml";
                ZipEntry ze = new ZipEntry(entryName);
                ze.setTime(315532800000L);
                archive.putNextEntry(ze);
                if ("xlsx".equals(format)) {
                    archive.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                            .getBytes(StandardCharsets.UTF_8));
                }
                int rowIndex = 0;
                rowIndex = writeRow(archive, format, rowIndex, headers.toArray());
                for (Object[] values : qr.rows()) {
                    rowIndex = writeRow(archive, format, rowIndex, values);
                }
                if ("xlsx".equals(format)) {
                    archive.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
                }
                archive.closeEntry();
                index++;
            }
            if ("csv".equals(format)) {
                Map<String, Object> readme = new LinkedHashMap<>();
                readme.put("note",
                        "Epoch timestamps; ms durations. Opaque data cycles are not application messages. Frame references map to the saved project; unknown values are empty.");
                readme.put("captures", db.rows(Sql.get("engine_export", "captures")));
                writeEntry(archive, "README.json",
                        PyJsonWriter.dumps(readme, true).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return output.toByteArray();
    }

    public static byte[] exportTables(Path projectPath, String format) {
        try (Db db = Export.openDb(projectPath)) {
            return exportTables(db, format);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] exportTables(ProjectStore project, String format) {
        return exportTables(project.path(), format);
    }

    static int writeRow(ZipOutputStream archive, String format, int prevRowIndex, Object[] values) throws IOException {
        int rowIndex = prevRowIndex + 1;
        if ("csv".equals(format)) {
            Object[] sanitized = new Object[values.length];
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                if (v instanceof String s) {
                    String stripped = lstrip(s);
                    if (stripped.startsWith("=") || stripped.startsWith("+")
                            || stripped.startsWith("-") || stripped.startsWith("@")) {
                        v = "'" + s;
                    }
                }
                sanitized[i] = v;
            }
            StringWriter buffer = new StringWriter();
            PyCsv.writeRow(buffer, sanitized, ',');
            archive.write(buffer.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            if (rowIndex > EXCEL_MAX_ROWS) {
                throw new IllegalArgumentException("XLSX sheet exceeds Excel row limit; use CSV export");
            }
            StringBuilder row = new StringBuilder();
            row.append("<row r=\"").append(rowIndex).append("\">");
            for (int c = 1; c <= values.length; c++) {
                Object v = values[c - 1];
                if (v == null) {
                    continue;
                }
                String address = columnName(c) + rowIndex;
                if (v instanceof Boolean b) {
                    row.append("<c r=\"").append(address).append("\" t=\"b\"><v>").append(b ? 1 : 0).append("</v></c>");
                } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
                    row.append("<c r=\"").append(address).append("\"><v>").append(v).append("</v></c>");
                } else if (v instanceof Double d) {
                    row.append("<c r=\"").append(address).append("\"><v>")
                            .append(PyFloatRepr.repr(d)).append("</v></c>");
                } else {
                    String cleaned = cleanXmlChars(String.valueOf(v));
                    row.append("<c r=\"").append(address)
                            .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(escapeXml(cleaned)).append("</t></is></c>");
                }
            }
            row.append("</row>");
            archive.write(row.toString().getBytes(StandardCharsets.UTF_8));
        }
        return rowIndex;
    }

    private static String lstrip(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    private static String cleanXmlChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 32 || ch == '\n' || ch == '\r' || ch == '\t') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Matches {@code xml.sax.saxutils.escape}: escapes {@code &}, {@code <}, and {@code >}. */
    static String escapeXml(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static void writeEntry(ZipOutputStream archive, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(315532800000L);
        archive.putNextEntry(entry);
        archive.write(content);
        archive.closeEntry();
    }
}
