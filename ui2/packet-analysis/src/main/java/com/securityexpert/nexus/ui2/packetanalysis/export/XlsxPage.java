package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.export.XlsxStyle.St;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;

/** A worksheet with the small writing helpers every sheet of the report uses. */
final class XlsxPage {
    final XSSFSheet sheet;
    private final XlsxStyle styles;
    private final double[] widths = new double[1024];

    XlsxPage(XSSFSheet sheet, XlsxStyle styles) {
        this.sheet = sheet;
        this.styles = styles;
        java.util.Arrays.fill(widths, 8.43);
        sheet.setDisplayGridlines(false);
    }

    static String clean(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length() && out.length() < 32000; i++) {
            char ch = text.charAt(i);
            if (ch >= 32 || ch == '\n' || ch == '\t') {
                out.append(ch);
            }
        }
        return out.toString();
    }

    Cell put(int r, int c, Object value, St st) {
        Row row = sheet.getRow(r) != null ? sheet.getRow(r) : sheet.createRow(r);
        Cell cell = row.createCell(c);
        if (value instanceof String s) {
            cell.setCellValue(clean(s));
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b);
        }
        cell.setCellStyle(styles.get(st));
        return cell;
    }

    /** Merges a range and gives every cell in it the same style, so borders and fills cover the whole block. */
    void merge(int r1, int c1, int r2, int c2, Object value, St st) {
        for (int r = r1; r <= r2; r++) {
            for (int c = c1; c <= c2; c++) {
                put(r, c, r == r1 && c == c1 ? value : null, st);
            }
        }
        if (r1 != r2 || c1 != c2) {
            sheet.addMergedRegion(new CellRangeAddress(r1, r2, c1, c2));
        }
    }

    void width(int col, double chars) {
        if (col < widths.length) {
            widths[col] = chars;
        }
        sheet.setColumnWidth(col, (int) Math.round(chars * 256));
    }

    double spanWidth(int c1, int c2) {
        double total = 0;
        for (int c = c1; c <= c2; c++) {
            total += c < widths.length ? widths[c] : 8.43;
        }
        return total;
    }

    void height(int row, double points) {
        Row r = sheet.getRow(row) != null ? sheet.getRow(row) : sheet.createRow(row);
        r.setHeightInPoints((float) Math.min(409, points));
    }

    /** Height that fits {@code text} wrapped inside {@code chars} width units. */
    static double wrappedHeight(String text, double chars, double perLine) {
        double usable = Math.max(8, chars * 1.05);
        int lines = 0;
        for (String part : text.split("\n", -1)) {
            lines += Math.max(1, (int) Math.ceil(part.length() / usable));
        }
        return Math.max(perLine + 4, lines * perLine + 6);
    }

    void title(String text, String subtitle, int subtitleSpanCols) {
        put(0, 0, text, St.base().size(16).bold());
        height(0, 24);
        if (subtitle != null) {
            merge(1, 0, 1, Math.max(0, subtitleSpanCols - 1), subtitle, St.base().color(XlsxStyle.MUTED).wrap());
            height(1, wrappedHeight(subtitle, spanWidth(0, Math.max(0, subtitleSpanCols - 1)), 13));
        }
    }

    void section(int row, String text) {
        put(row, 0, text, St.base().size(12).bold());
        height(row, 18);
    }

    void note(int row, int c1, int c2, String text, boolean highlight) {
        St st = St.base().color(XlsxStyle.MUTED).wrap();
        if (highlight) {
            st = st.fill(XlsxStyle.NOTE_FILL);
        }
        merge(row, c1, row, c2, text, st);
        height(row, wrappedHeight(text, spanWidth(c1, c2), 13));
    }
}
