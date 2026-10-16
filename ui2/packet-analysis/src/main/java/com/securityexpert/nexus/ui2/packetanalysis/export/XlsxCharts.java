package com.securityexpert.nexus.ui2.packetanalysis.export;

import java.util.List;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFLineProperties;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.AxisCrossBetween;
import org.apache.poi.xddf.usermodel.chart.AxisCrosses;
import org.apache.poi.xddf.usermodel.chart.AxisOrientation;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.BarGrouping;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LayoutMode;
import org.apache.poi.xddf.usermodel.chart.LayoutTarget;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.MarkerStyle;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFManualLayout;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;

/** Native Excel charts (XDDF) over cell ranges of the report's own sheets. */
final class XlsxCharts {
    /** One chart series: a numeric column and the header cell that names it. */
    record Series(XSSFSheet sheet, int titleRow, int col, int firstRow, int lastRow, String title, String color,
            String format) {
        /** A series whose cached values carry the number format of its source cells. */
        Series(XSSFSheet sheet, int titleRow, int col, int firstRow, int lastRow, String title, String color) {
            this(sheet, titleRow, col, firstRow, lastRow, title, color, null);
        }
    }

    private XlsxCharts() {
    }

    /** Where a chart sits on its host sheet: zero-based column/row corner cells. */
    record Box(int col1, int row1, int col2, int row2) {
    }

    /** Plot-area placement shared by charts that must line up on one time axis. */
    record Plot(double x, double y, double w, double h) {
        static final Plot TIMELINE = new Plot(0.07, 0.14, 0.91, 0.58);
    }

    static XSSFChart line(XSSFSheet host, Box box, String title, XSSFSheet catSheet, int catCol, int firstRow, int lastRow,
            List<Series> series, String numberFormat, int tickSkip, boolean legend, Plot plot) {
        XSSFChart chart = create(host, box, title);
        XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
        bottom.setVisible(true);
        XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
        left.setVisible(true);
        left.setNumberFormat(numberFormat);
        left.setCrossBetween(AxisCrossBetween.BETWEEN);
        chart.getCTChart().getPlotArea().getValAxArray(0).getNumFmt().setSourceLinked(false);
        XDDFLineChartData data = (XDDFLineChartData) chart.createData(ChartTypes.LINE, bottom, left);
        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(catSheet,
                new CellRangeAddress(firstRow, lastRow, catCol, catCol));
        for (Series s : series) {
            XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(s.sheet(),
                    new CellRangeAddress(s.firstRow(), s.lastRow(), s.col(), s.col()));
            if (s.format() != null) {
                values.setFormatCode(s.format());
            }
            XDDFLineChartData.Series ser = (XDDFLineChartData.Series) data.addSeries(cats, values);
            ser.setTitle(s.title(), new CellReference(s.sheet().getSheetName(), s.titleRow(), s.col(), true, true));
            ser.setMarkerStyle(MarkerStyle.NONE);
            ser.setSmooth(false);
            XDDFLineProperties line = new XDDFLineProperties();
            line.setWidth(2.0);
            line.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb(s.color()))));
            ser.setLineProperties(line);
        }
        chart.plot(data);
        tickSkip(chart, tickSkip);
        finish(chart, legend, plot);
        return chart;
    }

    static XSSFChart bars(XSSFSheet host, Box box, String title, XSSFSheet catSheet, int catCol, int firstRow, int lastRow,
            List<Series> series, String numberFormat, boolean horizontal, boolean stacked, boolean legend) {
        XSSFChart chart = create(host, box, title);
        XDDFCategoryAxis bottom = chart.createCategoryAxis(horizontal ? AxisPosition.LEFT : AxisPosition.BOTTOM);
        bottom.setVisible(true);
        XDDFValueAxis left = chart.createValueAxis(horizontal ? AxisPosition.BOTTOM : AxisPosition.LEFT);
        left.setVisible(true);
        left.setNumberFormat(numberFormat);
        left.setCrossBetween(AxisCrossBetween.BETWEEN);
        chart.getCTChart().getPlotArea().getValAxArray(0).getNumFmt().setSourceLinked(false);
        if (horizontal) {
            bottom.setOrientation(AxisOrientation.MAX_MIN);
            left.setCrosses(AxisCrosses.MAX);
        }
        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottom, left);
        data.setBarDirection(horizontal ? BarDirection.BAR : BarDirection.COL);
        data.setBarGrouping(stacked ? BarGrouping.STACKED : BarGrouping.CLUSTERED);
        data.setGapWidth(stacked ? 40 : 60);
        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(catSheet,
                new CellRangeAddress(firstRow, lastRow, catCol, catCol));
        for (Series s : series) {
            XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(s.sheet(),
                    new CellRangeAddress(s.firstRow(), s.lastRow(), s.col(), s.col()));
            if (s.format() != null) {
                values.setFormatCode(s.format());
            }
            XDDFBarChartData.Series ser = (XDDFBarChartData.Series) data.addSeries(cats, values);
            ser.setTitle(s.title(), new CellReference(s.sheet().getSheetName(), s.titleRow(), s.col(), true, true));
            ser.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb(s.color()))));
        }
        chart.plot(data);
        if (stacked) {
            insertValueElement(chart.getCTChart().getPlotArea().getBarChartArray(0), "axId", "overlap", 100);
        }
        finish(chart, legend, null);
        return chart;
    }

    private static XSSFChart create(XSSFSheet host, Box box, String title) {
        XSSFDrawing drawing = host.getDrawingPatriarch() != null ? host.getDrawingPatriarch() : host.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, box.col1(), box.row1(), box.col2(), box.row2());
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);
        return chart;
    }

    private static void finish(XSSFChart chart, boolean legend, Plot plot) {
        if (legend) {
            chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        }
        if (plot != null) {
            XDDFManualLayout layout = chart.getOrAddManualLayout();
            layout.setTarget(LayoutTarget.INNER);
            layout.setXMode(LayoutMode.EDGE);
            layout.setYMode(LayoutMode.EDGE);
            layout.setX(plot.x());
            layout.setY(plot.y());
            layout.setWidthRatio(plot.w());
            layout.setHeightRatio(plot.h());
        }
    }

    private static final String CHART_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart";

    /**
     * POI's trimmed schema bundle (poi-ooxml-lite) lacks the classes for a few chart elements, so those are inserted
     * at their schema position through the XML cursor: a {@code <c:name val="n"/>} element before the first child
     * called {@code before}.
     */
    private static boolean insertValueElement(org.apache.xmlbeans.XmlObject parent, String before, String name, int value) {
        try (var cursor = parent.newCursor()) {
            if (!cursor.toChild(new javax.xml.namespace.QName(CHART_NS, before))) {
                return false;
            }
            cursor.beginElement(new javax.xml.namespace.QName(CHART_NS, name));
            cursor.insertAttributeWithValue("val", Integer.toString(value));
            return true;
        }
    }

    /** Label and tick-mark interval of the category axis (schema position: after auto, last child of POI's axis). */
    private static void tickSkip(XSSFChart chart, int skip) {
        if (skip <= 1) {
            return;
        }
        var catAx = chart.getCTChart().getPlotArea().getCatAxArray(0);
        try (var cursor = catAx.newCursor()) {
            cursor.toEndToken();
            for (String name : new String[] {"tickLblSkip", "tickMarkSkip"}) {
                cursor.beginElement(new javax.xml.namespace.QName(CHART_NS, name));
                cursor.insertAttributeWithValue("val", Integer.toString(skip));
                cursor.toNextToken();
            }
        }
    }

    private static byte[] rgb(String hex) {
        return new byte[] {(byte) Integer.parseInt(hex.substring(0, 2), 16), (byte) Integer.parseInt(hex.substring(2, 4), 16),
            (byte) Integer.parseInt(hex.substring(4, 6), 16)};
    }
}
