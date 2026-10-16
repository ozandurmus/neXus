package com.securityexpert.nexus.ui2.packetanalysis.export;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** The expert report's palette and cell styles (the reference report's colours, Arial 10), created in first-use order. */
final class XlsxStyle {
    static final String BLUE = "2A78D6";
    static final String ORANGE = "EB6834";
    static final String AQUA = "1BAF7A";
    static final String YELLOW = "EDA100";
    static final String MAGENTA = "E87BA4";
    static final String GREEN = "008300";
    static final String VIOLET = "6250D6";
    static final String RED = "E34948";
    static final String HDR_FILL = "E7E6E3";
    static final String BOX_FILL = "F3F2EF";
    static final String NOTE_FILL = "FBF7E6";
    static final String FINDING_FILL = "EAF1FB";
    static final String BEFORE_FILL = "DCE9F8";
    static final String AFTER_FILL = "FBE1D6";
    static final String BORDER = "C9C7C2";
    static final String MUTED = "52514E";
    static final String[] SERIES = {BLUE, ORANGE, AQUA, YELLOW, MAGENTA, VIOLET, GREEN, RED};

    /** Number formats by kind of value; no numeric cell of the report is left in General. */
    static final String MS = "0.000";
    /** Milliseconds in a column whose values are all 100 or more. */
    static final String MS1 = "0.0";
    static final String PCT = "0.0%";
    static final String INT = "#,##0";
    static final String RATE = "#,##0.0";
    static final String RATIO = "0.00\"×\"";
    /** Identifiers that happen to be numbers (IP ID): no grouping, no decimals. */
    static final String ID = "0";

    /** Milliseconds: {@code 0.000}, or {@code 0.0} when the column holds values and every one of them is 100 or more. */
    static String ms(Iterable<?> column) {
        boolean any = false;
        for (Object v : column) {
            if (!(v instanceof Number n) || Double.isNaN(n.doubleValue())) {
                continue;
            }
            any = true;
            if (Math.abs(n.doubleValue()) < 100) {
                return MS;
            }
        }
        return any ? MS1 : MS;
    }

    /** The signed form of a format: a plus sign on increases, a minus sign on decreases, none on zero. */
    static String signed(String format) {
        String zero = format.equals(INT) ? "0" : format;
        return "+" + format + ";-" + format + ";" + zero;
    }

    record St(boolean strong, int pt, String ink, String bg, boolean boxed, String numFmt, boolean wrapped,
            HorizontalAlignment h, VerticalAlignment v) {
        static St base() {
            return new St(false, 10, null, null, false, null, false, null, null);
        }

        St bold() {
            return new St(true, pt, ink, bg, boxed, numFmt, wrapped, h, v);
        }

        St size(int value) {
            return new St(strong, value, ink, bg, boxed, numFmt, wrapped, h, v);
        }

        St color(String value) {
            return new St(strong, pt, value, bg, boxed, numFmt, wrapped, h, v);
        }

        St fill(String value) {
            return new St(strong, pt, ink, value, boxed, numFmt, wrapped, h, v);
        }

        St border() {
            return new St(strong, pt, ink, bg, true, numFmt, wrapped, h, v);
        }

        St fmt(String value) {
            return new St(strong, pt, ink, bg, boxed, value, wrapped, h, v);
        }

        St wrap() {
            return new St(strong, pt, ink, bg, boxed, numFmt, true, h, VerticalAlignment.TOP);
        }

        St center() {
            return new St(strong, pt, ink, bg, boxed, numFmt, true, HorizontalAlignment.CENTER, VerticalAlignment.CENTER);
        }
    }

    private final XSSFWorkbook workbook;
    private final Map<St, XSSFCellStyle> styles = new LinkedHashMap<>();
    private final Map<String, XSSFFont> fonts = new LinkedHashMap<>();

    XlsxStyle(XSSFWorkbook workbook) {
        this.workbook = workbook;
    }

    /** Opaque ARGB: Excel wants the eight-digit form in styles and conditional formats. */
    static XSSFColor color(String hex) {
        return new XSSFColor(new byte[] {(byte) 0xFF, (byte) Integer.parseInt(hex.substring(0, 2), 16),
            (byte) Integer.parseInt(hex.substring(2, 4), 16), (byte) Integer.parseInt(hex.substring(4, 6), 16)}, null);
    }

    XSSFCellStyle get(St spec) {
        return styles.computeIfAbsent(spec, this::create);
    }

    private XSSFCellStyle create(St spec) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFont(font(spec));
        if (spec.bg() != null) {
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setFillForegroundColor(color(spec.bg()));
        }
        if (spec.boxed()) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            XSSFColor c = color(BORDER);
            style.setTopBorderColor(c);
            style.setBottomBorderColor(c);
            style.setLeftBorderColor(c);
            style.setRightBorderColor(c);
        }
        if (spec.numFmt() != null) {
            style.setDataFormat(workbook.createDataFormat().getFormat(spec.numFmt()));
        }
        style.setWrapText(spec.wrapped());
        if (spec.h() != null) {
            style.setAlignment(spec.h());
        }
        if (spec.v() != null) {
            style.setVerticalAlignment(spec.v());
        }
        return style;
    }

    private XSSFFont font(St spec) {
        String key = spec.strong() + "|" + spec.pt() + "|" + spec.ink();
        return fonts.computeIfAbsent(key, k -> {
            XSSFFont f = workbook.createFont();
            f.setFontName("Arial");
            f.setFontHeightInPoints((short) spec.pt());
            f.setBold(spec.strong());
            if (spec.ink() != null) {
                f.setColor(color(spec.ink()));
            }
            return f;
        });
    }
}
