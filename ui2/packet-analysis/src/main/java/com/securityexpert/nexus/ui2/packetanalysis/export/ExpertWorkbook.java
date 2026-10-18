package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.*;
import com.securityexpert.nexus.ui2.packetanalysis.export.XlsxCharts.Box;
import com.securityexpert.nexus.ui2.packetanalysis.export.XlsxCharts.Plot;
import com.securityexpert.nexus.ui2.packetanalysis.export.XlsxStyle.St;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.Series;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.TelemetryFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule;
import org.apache.poi.xssf.usermodel.XSSFColorScaleFormatting;
import org.apache.poi.xssf.usermodel.XSSFSheetConditionalFormatting;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Renders {@link ExpertReportData} as the expert workbook: Özet, Zaman Çizelgesi, Hop Analizi, Örnek Olaylar and
 * Yöntem ve Veri, in that order. Every value is a computed constant (no formulas), every chart is a native Excel
 * chart over cells of these sheets, and the document properties are fixed, so the bytes depend on the data only.
 */
final class ExpertWorkbook {
    static final String SUMMARY = "Özet";
    static final String TIMELINE = "Zaman Çizelgesi";
    static final String HOP = "Hop Analizi";
    static final String SAMPLES = "Örnek Olaylar";
    static final String HOST = "Sunucu Korelasyonu";
    static final String METHOD = "Yöntem ve Veri";
    private static final Instant FIXED_TIME = Instant.parse("2000-01-01T00:00:00Z");
    private static final String DASH = "–";

    private final ExpertReportData d;
    private final XSSFWorkbook wb = new XSSFWorkbook();
    private final XlsxStyle styles = new XlsxStyle(wb);
    private XlsxPage summary;
    private XlsxPage timeline;
    private XlsxPage hop;
    private XlsxPage samples;
    private XlsxPage host;
    private XlsxPage method;

    // chart sources recorded while the data sheets are written
    private int tlFirst = -1;
    private int tlLast = -1;
    private int tlRetransCol = -1;
    private int tlPacketsCol = -1;
    private final List<Integer> tlHopMaxCols = new ArrayList<>();
    private final List<String> tlHopMaxFmt = new ArrayList<>();
    private String tlRetransFmt;
    private String tlPacketsFmt;
    private String[] hopAMeanFmt;
    private String[] hopBMeanFmt;
    private int hopAFirst = -1;
    private int hopALast = -1;
    private int hopAMeanCol = -1;
    private int hopBFirst = -1;
    private int hopBLast = -1;
    private int hopBMeanCol = -1;
    private int hopHeaderA = -1;
    private int hopHeaderB = -1;

    private ExpertWorkbook(ExpertReportData data) {
        this.d = data;
    }

    static byte[] render(ExpertReportData data) {
        return new ExpertWorkbook(data).build();
    }

    // ------------------------------------------------------------------------------------------------ assembly

    private byte[] build() {
        summary = new XlsxPage(wb.createSheet(SUMMARY), styles);
        timeline = new XlsxPage(wb.createSheet(TIMELINE), styles);
        hop = new XlsxPage(wb.createSheet(HOP), styles);
        samples = new XlsxPage(wb.createSheet(SAMPLES), styles);
        if (d.host != null) {
            host = new XlsxPage(wb.createSheet(HOST), styles);
        }
        method = new XlsxPage(wb.createSheet(METHOD), styles);
        writeTimeline();
        writeHop();
        writeSamples();
        if (host != null) {
            writeHost();
        }
        writeMethod();
        writeSummary();
        summary.sheet.setTabColor(XlsxStyle.color(XlsxStyle.ORANGE));
        wb.setActiveSheet(0);
        wb.getProperties().getCoreProperties().setCreated(Optional.of(java.util.Date.from(FIXED_TIME)));
        wb.getProperties().getCoreProperties().setModified(Optional.of(java.util.Date.from(FIXED_TIME)));
        wb.getProperties().getCoreProperties().setCreator("neXus Packet Analysis");
        wb.getProperties().getCoreProperties().setLastModifiedByUser("neXus Packet Analysis");
        wb.getProperties().getCoreProperties().setTitle("Packet Analysis uzman raporu");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            wb.write(out);
            wb.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return DeterministicZip.repack(out.toByteArray());
    }

    /** "case title: 5 kayıt noktası, 14 Kasım 2023": the case's own name, its point count and the capture date. */
    private String summaryTitle() {
        String name = d.caseTitle == null ? "Paket analizi" : d.caseTitle;
        String date = d.window.rangeStart() == null ? "" : ", " + TrFormat.dateOf(d.window.rangeStart(), d.zone);
        return name + ": " + d.points.size() + " kayıt noktası" + date;
    }

    private String periodLabel(int i) {
        return d.window.periods().get(i).label();
    }

    private St periodFill(St st, int i) {
        if (!d.split()) {
            return st;
        }
        return st.fill(i == 0 ? XlsxStyle.BEFORE_FILL : XlsxStyle.AFTER_FILL);
    }

    private static St cell() {
        return St.base().border();
    }

    private static St header() {
        return St.base().bold().fill(XlsxStyle.HDR_FILL).border().center();
    }

    private void headers(XlsxPage p, int row, int col, List<String> labels, double[] widths) {
        for (int i = 0; i < labels.size(); i++) {
            p.put(row, col + i, labels.get(i), header());
            if (widths != null && i < widths.length) {
                p.width(col + i, widths[i]);
            }
        }
        p.height(row, 42);
    }

    private static void scale(XlsxPage p, int r1, int r2, int col, String lo, String hi) {
        if (r2 < r1) {
            return;
        }
        XSSFSheetConditionalFormatting scf = p.sheet.getSheetConditionalFormatting();
        XSSFConditionalFormattingRule rule = scf.createConditionalFormattingColorScaleRule();
        XSSFColorScaleFormatting cs = rule.getColorScaleFormatting();
        cs.setNumControlPoints(2);
        cs.getThresholds()[0].setRangeType(ConditionalFormattingThreshold.RangeType.MIN);
        cs.getThresholds()[1].setRangeType(ConditionalFormattingThreshold.RangeType.MAX);
        cs.setColors(new XSSFColor[] {XlsxStyle.color(lo), XlsxStyle.color(hi)});
        scf.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(r1, r2, col, col)}, rule);
    }

    private List<Seg> forwardSegments() {
        List<Seg> out = new ArrayList<>();
        for (Seg s : d.segments) {
            if ("forward".equals(s.direction())) {
                out.add(s);
            }
        }
        return out;
    }

    private List<Seg> orderedSegments() {
        List<Seg> out = forwardSegments();
        for (Seg s : d.segments) {
            if (!"forward".equals(s.direction())) {
                out.add(s);
            }
        }
        return out;
    }

    private String windowSentence() {
        return d.window.note();
    }

    private String rangeText() {
        Window w = d.window;
        if (w.rangeStart() == null) {
            return "Analiz penceresi yok";
        }
        return TrFormat.dateTime(w.rangeStart(), d.zone) + " – " + TrFormat.dateTime(w.rangeEnd(), d.zone);
    }

    // ------------------------------------------------------------------------------------------------ Zaman Çizelgesi

    /** A separate UTC column tells something only when the report zone differs from UTC. */
    private boolean utcColumn() {
        return !d.utcZone();
    }

    private void writeTimeline() {
        XlsxPage p = timeline;
        Timeline t = d.timeline;
        boolean utc = utcColumn();
        p.width(0, 11);
        p.width(1, utc ? 9 : 8);
        if (utc) {
            p.width(2, 8);
        }
        p.title("Zaman çizelgesi: saniyelik ölçümler", t == null ? null
                : TrFormat.dateOf(t.origin, d.zone) + " (" + d.zoneLabel + "). Bütün kayıt noktaları aynı saniye ızgarasındadır. "
                + "'Dönem' yalnız karşılaştırılan aralıkta dolu. Geçiş sütunları ileri yöndeki komşu nokta çiftleri içindir "
                + "(aynı paketin iki noktadaki zaman farkı). " + windowSentence(), 12);
        if (t == null) {
            p.note(3, 0, 11, d.absent.getOrDefault("timeline", "Zaman çizelgesi verisi yok."), true);
            return;
        }
        Pt first = d.firstPoint();
        List<String> labels = new ArrayList<>(List.of("Saat (" + d.zoneLabel + ")"));
        List<Double> widths = new ArrayList<>(List.of(11.0));
        if (utc) {
            labels.add("UTC");
            widths.add(9.0);
        }
        labels.addAll(List.of("Dönem", first.code() + " paket/sn", first.code() + " eşleşmiş döngü/sn",
                first.code() + " RTT ort. ms", first.code() + " retransmission işareti/sn", "Kayıp olayı/sn (tüm hop'lar)"));
        widths.addAll(List.of(8.0, 13.0, 13.0, 13.0, 15.0, 15.0));
        for (String hopLabel : t.hopLabels) {
            labels.add(hopLabel + " geçiş ort. ms");
            labels.add(hopLabel + " geçiş maks. ms");
            widths.add(15.0);
            widths.add(15.0);
        }
        int headerRow = 3;
        double[] w = new double[widths.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = widths.get(i);
        }
        headers(p, headerRow, 0, labels, w);
        p.height(headerRow, 56);
        int lead = utc ? 3 : 2; // clock, [UTC], period
        p.sheet.createFreezePane(lead, headerRow + 1);
        tlFirst = headerRow + 1;
        tlLast = headerRow + t.count;
        tlPacketsCol = lead;
        tlRetransCol = lead + 3;
        boolean wholeSeconds = t.width == 1.0;
        String perSecond = wholeSeconds ? XlsxStyle.INT : XlsxStyle.RATE;
        String rttFmt = XlsxStyle.ms(java.util.Arrays.asList(t.rttMeanMs));
        List<String> meanFmt = new ArrayList<>();
        List<String> maxFmt = new ArrayList<>();
        for (int h = 0; h < t.hopLabels.size(); h++) {
            meanFmt.add(XlsxStyle.ms(java.util.Arrays.asList(t.hopMean.get(h))));
            maxFmt.add(XlsxStyle.ms(java.util.Arrays.asList(t.hopMax.get(h))));
        }
        for (int i = 0; i < t.count; i++) {
            int r = tlFirst + i;
            p.put(r, 0, t.local[i], cell());
            if (utc) {
                p.put(r, 1, t.utc[i], cell());
            }
            St periodStyle = cell();
            String periodText = null;
            if (t.period[i] >= 0 && d.split()) {
                periodText = periodLabel(t.period[i]);
                periodStyle = periodFill(periodStyle, t.period[i]);
            }
            p.put(r, lead - 1, periodText, periodStyle);
            p.put(r, lead, t.packets[i] / t.width, cell().fmt(perSecond));
            p.put(r, lead + 1, t.cycles[i] / t.width, cell().fmt(perSecond));
            p.put(r, lead + 2, t.rttMeanMs[i], cell().fmt(rttFmt));
            p.put(r, lead + 3, t.retransmissions[i] / t.width, cell().fmt(perSecond));
            p.put(r, lead + 4, t.lossEvents[i] / t.width, cell().fmt(perSecond));
            for (int h = 0; h < t.hopLabels.size(); h++) {
                p.put(r, lead + 5 + 2 * h, t.hopMean.get(h)[i], cell().fmt(meanFmt.get(h)));
                p.put(r, lead + 6 + 2 * h, t.hopMax.get(h)[i], cell().fmt(maxFmt.get(h)));
            }
        }
        for (int h = 0; h < t.hopLabels.size(); h++) {
            tlHopMaxCols.add(lead + 6 + 2 * h);
            tlHopMaxFmt.add(maxFmt.get(h));
            scale(p, tlFirst, tlLast, lead + 6 + 2 * h, "FFFFFF", XlsxStyle.ORANGE);
        }
        tlRetransFmt = perSecond;
        tlPacketsFmt = perSecond;
        scale(p, tlFirst, tlLast, lead + 3, "FFFFFF", XlsxStyle.ORANGE);
        scale(p, tlFirst, tlLast, lead, XlsxStyle.BEFORE_FILL, "FFFFFF");
        // engine onsets, to the right of the table
        int col = lead + 5 + 2 * t.hopLabels.size() + 1;
        p.put(headerRow - 1, col, "Motor olay başlangıçları", St.base().size(12).bold());
        if (d.onsets.isEmpty()) {
            p.merge(headerRow, col, headerRow, col + 3, "Motor bu analizde olay başlangıcı bulamadı.",
                    St.base().color(XlsxStyle.MUTED).wrap().border());
            for (int c = 0; c < 4; c++) {
                p.width(col + c, 18);
            }
        } else {
            headers(p, headerRow, col, List.of("Zaman (" + d.zoneLabel + ")", "Hop", "Ölçüt", "Kapsam", "Değer", "Taban", "Eşik"),
                    new double[] {30, 30, 22, 18, 12, 12, 12});
            int r = headerRow + 1;
            for (OnsetRow o : d.onsets) {
                p.put(r, col, o.localTime(), cell());
                p.put(r, col + 1, o.segment(), cell().wrap());
                p.put(r, col + 2, ExpertFindings.METRIC_TR.getOrDefault(o.metric(), o.metric()), cell());
                p.put(r, col + 3, ExpertFindings.SCOPE_TR.getOrDefault(o.scope(), o.scope()), cell());
                String fmt = onsetFormat(o);
                p.put(r, col + 4, o.value(), cell().fmt(fmt));
                p.put(r, col + 5, o.baseline(), cell().fmt(fmt));
                p.put(r, col + 6, o.threshold(), cell().fmt(fmt));
                r++;
            }
        }
    }

    /** Milliseconds, percent (the engine states them in percent units) or a count/rate, by the metric's own name. */
    private static String onsetFormat(OnsetRow o) {
        String metric = o.metric();
        if (metric != null && metric.endsWith("_ms")) {
            return XlsxStyle.ms(java.util.Arrays.asList(o.value(), o.baseline(), o.threshold()));
        }
        if (metric != null && metric.endsWith("_percent")) {
            return "0.0\"%\"";
        }
        return XlsxStyle.RATE;
    }

    // ------------------------------------------------------------------------------------------------ Hop Analizi

    private void writeHop() {
        XlsxPage p = hop;
        int periods = d.periodCount();
        p.width(0, 36);
        p.width(1, 11);
        p.width(2, 14);
        for (int c = 3; c < 16; c++) {
            p.width(c, 13);
        }
        p.title("Hop analizi: fark hangi iki kayıt noktası arasında oluşuyor?",
                "Her satır komşu iki kayıt noktası arasındaki farktır. Aynı paket iki noktada ölçüldüğü için sabit saat ofseti "
                        + "iptal olur; değerler cihaz işleme + hat gecikmesini birlikte kapsar. Hop işaretleri yalnız Yöntem ve Veri "
                        + "sayfasındaki sabit eşiklerle üretilir; kök neden hükmü değildir. " + windowSentence(), 12);
        int r = 3;
        r = writeHopMarks(p, r);
        p.section(r++, "A. Komşu noktalar arası fark (ms; eşleşmiş paket sayısıyla ağırlıklı ortalama)");
        if (d.absent.containsKey("hop")) {
            p.note(r++, 0, 11, d.absent.get("hop"), true);
        } else {
            List<String> labels = new ArrayList<>(List.of("Hop", "Yön", "Tür"));
            for (int i = 0; i < periods; i++) {
                labels.add(periodLabel(i) + " eşleşen paket");
            }
            hopAMeanCol = labels.size();
            for (int i = 0; i < periods; i++) {
                labels.add(periodLabel(i) + " ort. ms");
            }
            for (int i = 0; i < periods; i++) {
                labels.add(periodLabel(i) + " maks. ms");
            }
            labels.add("p50 ms (tüm yakalama)");
            labels.add("p95 ms (tüm yakalama)");
            labels.add("Saat belirsizliği ± ms");
            if (d.split()) {
                labels.add("Değişim: sonra − önce, ort. ms");
            }
            hopHeaderA = r;
            headers(p, r, 0, labels, null);
            r++;
            hopAFirst = r;
            int forwardCount = forwardSegments().size();
            List<Seg> ordered = orderedSegments();
            String[] meanFmt = new String[periods];
            String[] maxFmt = new String[periods];
            for (int i = 0; i < periods; i++) {
                List<Double> means = new ArrayList<>();
                List<Double> maxes = new ArrayList<>();
                for (Seg s : ordered) {
                    Stat[] stats = d.hopTransit.get(s.id());
                    means.add(stats == null ? null : stats[i].meanMs());
                    maxes.add(stats == null ? null : stats[i].maxMs());
                }
                meanFmt[i] = XlsxStyle.ms(means);
                maxFmt[i] = XlsxStyle.ms(maxes);
            }
            hopAMeanFmt = meanFmt;
            List<Double> p50 = new ArrayList<>();
            List<Double> p95 = new ArrayList<>();
            List<Double> clock = new ArrayList<>();
            List<Double> diffs = new ArrayList<>();
            for (Seg s : ordered) {
                p50.add(s.p50());
                p95.add(s.p95());
                clock.add(s.uncertaintyMs());
                Stat[] stats = d.hopTransit.get(s.id());
                Double a = stats == null || !d.split() ? null : stats[0].meanMs();
                Double b = stats == null || !d.split() ? null : stats[1].meanMs();
                diffs.add(a == null || b == null ? null : b - a);
            }
            String p50Fmt = XlsxStyle.ms(p50);
            String p95Fmt = XlsxStyle.ms(p95);
            String clockFmt = XlsxStyle.ms(clock);
            String diffFmt = XlsxStyle.signed(XlsxStyle.ms(diffs));
            for (Seg s : ordered) {
                Stat[] stats = d.hopTransit.get(s.id());
                p.put(r, 0, s.label(), cell());
                p.put(r, 1, "forward".equals(s.direction()) ? "ileri" : "geri", cell());
                p.put(r, 2, s.deviceHop() ? "Cihaz içi" : "Hat / komşu", cell());
                int c = 3;
                for (int i = 0; i < periods; i++) {
                    p.put(r, c++, stats != null && stats[i].has() ? (Object) stats[i].n() : null, cell().fmt(XlsxStyle.INT));
                }
                for (int i = 0; i < periods; i++) {
                    p.put(r, c++, stats == null ? null : stats[i].meanMs(), cell().fmt(meanFmt[i]));
                }
                for (int i = 0; i < periods; i++) {
                    p.put(r, c++, stats == null ? null : stats[i].maxMs(), cell().fmt(maxFmt[i]));
                }
                p.put(r, c++, s.p50(), cell().fmt(p50Fmt));
                p.put(r, c++, s.p95(), cell().fmt(p95Fmt));
                p.put(r, c++, s.uncertaintyMs(), cell().fmt(clockFmt));
                if (d.split()) {
                    Double a = stats == null ? null : stats[0].meanMs();
                    Double b = stats == null ? null : stats[1].meanMs();
                    p.put(r, c, a == null || b == null ? null : (Object) (b - a), cell().fmt(diffFmt));
                }
                r++;
            }
            hopALast = hopAFirst + forwardCount - 1;
            p.note(r++, 0, 11, "Negatif küçük farklar korunmuştur; fiziksel negatif gecikme değil, saat belirsizliği ve yakalama yönü etkisidir. "
                    + "p50/p95 motorun tüm yakalama için hesapladığı değerlerdir (dönemlerden bağımsız).", false);
        }
        r++;

        p.section(r++, "B. Her kayıt noktasında ölçülen istek→cevap süresi (RTT, ms)");
        if (d.absent.containsKey("rtt")) {
            p.note(r++, 0, 11, d.absent.get("rtt"), true);
        } else {
            List<String> labels = new ArrayList<>(List.of("Kayıt noktası"));
            for (int i = 0; i < periods; i++) {
                labels.add(periodLabel(i) + " döngü");
            }
            hopBMeanCol = labels.size();
            for (int i = 0; i < periods; i++) {
                labels.add(periodLabel(i) + " ort. ms");
            }
            for (int i = 0; i < periods; i++) {
                labels.add(periodLabel(i) + " maks. ms");
            }
            if (d.split()) {
                labels.add("Sonra / önce (ort.)");
            }
            hopHeaderB = r;
            headers(p, r, 0, labels, null);
            r++;
            hopBFirst = r;
            String[] meanFmt = new String[periods];
            String[] maxFmt = new String[periods];
            for (int i = 0; i < periods; i++) {
                List<Double> means = new ArrayList<>();
                List<Double> maxes = new ArrayList<>();
                for (PointRtt pr : d.pointRtt) {
                    means.add(pr.byPeriod()[i].meanMs());
                    maxes.add(pr.byPeriod()[i].maxMs());
                }
                meanFmt[i] = XlsxStyle.ms(means);
                maxFmt[i] = XlsxStyle.ms(maxes);
            }
            hopBMeanFmt = meanFmt;
            for (PointRtt pr : d.pointRtt) {
                Pt pt = d.point(pr.pointId());
                p.put(r, 0, pt.code() + " " + pt.label(), cell());
                int c = 1;
                for (int i = 0; i < periods; i++) {
                    p.put(r, c++, pr.byPeriod()[i].has() ? (Object) pr.byPeriod()[i].n() : null, cell().fmt(XlsxStyle.INT));
                }
                for (int i = 0; i < periods; i++) {
                    p.put(r, c++, pr.byPeriod()[i].meanMs(), cell().fmt(meanFmt[i]));
                }
                for (int i = 0; i < periods; i++) {
                    p.put(r, c++, pr.byPeriod()[i].maxMs(), cell().fmt(maxFmt[i]));
                }
                if (d.split()) {
                    Double a = pr.byPeriod()[0].meanMs();
                    Double b = pr.byPeriod()[1].meanMs();
                    p.put(r, c, a == null || b == null || a == 0 ? null : (Object) (b / a), cell().fmt(XlsxStyle.RATIO));
                }
                r++;
            }
            hopBLast = r - 1;
            p.note(r++, 0, 11, "Okuma: istemciye en yakın nokta toplam gecikmeyi görür; sonraki noktaların RTT'si küçülür. "
                    + "İki komşu noktanın RTT farkı aralarındaki gidiş+dönüş katkısıdır.", false);
        }
        r++;
        if (hopAMeanCol >= 0 && hopBMeanCol >= 0) {
            r += 18; // two charts side by side are placed here
            chartRowHop = r - 18;
        }

        p.section(r++, "C. Döngü bazlı hop katkısı (istek RX / cevap TX; kurtarma beklemesini içerir)");
        if (d.cycleHops.isEmpty()) {
            p.note(r++, 0, 11, "Bu analizde döngü bazlı hop katkısı hesaplanamadı.", true);
        } else {
            List<String> labels = new ArrayList<>(List.of("Hop", "Bileşen"));
            for (int i = 0; i < periods; i++) {
                labels.add(periodLabel(i) + " döngü");
            }
            for (int i = 0; i < periods; i++) {
                labels.add(periodLabel(i) + " ort. ms");
            }
            for (int i = 0; i < periods; i++) {
                labels.add(periodLabel(i) + " maks. ms");
            }
            headers(p, r++, 0, labels, null);
            String[] meanFmt = new String[periods];
            String[] maxFmt = new String[periods];
            for (int i = 0; i < periods; i++) {
                List<Double> means = new ArrayList<>();
                List<Double> maxes = new ArrayList<>();
                for (CycleHop ch : d.cycleHops) {
                    means.add(ch.byPeriod()[i].meanMs());
                    maxes.add(ch.byPeriod()[i].maxMs());
                }
                meanFmt[i] = XlsxStyle.ms(means);
                maxFmt[i] = XlsxStyle.ms(maxes);
            }
            for (CycleHop ch : d.cycleHops) {
                p.put(r, 0, ch.label(), cell());
                p.put(r, 1, ch.component(), cell());
                int c = 2;
                for (int i = 0; i < periods; i++) {
                    p.put(r, c++, ch.byPeriod()[i].has() ? (Object) ch.byPeriod()[i].n() : null, cell().fmt(XlsxStyle.INT));
                }
                for (int i = 0; i < periods; i++) {
                    p.put(r, c++, ch.byPeriod()[i].meanMs(), cell().fmt(meanFmt[i]));
                }
                for (int i = 0; i < periods; i++) {
                    p.put(r, c++, ch.byPeriod()[i].maxMs(), cell().fmt(maxFmt[i]));
                }
                r++;
            }
        }
        r++;

        p.section(r++, "D. Kayıt eşleşmesi: bir noktada görülen paket karşı noktada var mı?");
        if (d.absent.containsKey("match")) {
            p.note(r++, 0, 11, d.absent.get("match"), true);
        } else {
            headers(p, r++, 0, List.of("Hop", "Yön", "Dönem", "Kaynak gözlem", "Eşleşmeyen", "Eşleşmeyen %",
                    "Desteklenen kayıp", "Kayıp %", "Yakalama kaçırması", "Belirsiz"), null);
            int first = r;
            for (MatchRow m : d.matchRows) {
                for (int i = 0; i < periods; i++) {
                    long obs = m.observations()[i];
                    long unmatched = m.dropped()[i] + m.captureMisses()[i] + m.unknown()[i];
                    p.put(r, 0, m.label(), cell());
                    p.put(r, 1, "forward".equals(m.direction()) ? "ileri" : "geri", cell());
                    p.put(r, 2, d.split() ? periodLabel(i) : "Tüm yakalama", periodFill(cell(), i));
                    p.put(r, 3, obs, cell().fmt(XlsxStyle.INT));
                    p.put(r, 4, unmatched, cell().fmt(XlsxStyle.INT));
                    p.put(r, 5, obs == 0 ? null : (Object) ((double) unmatched / obs), cell().fmt(XlsxStyle.PCT));
                    p.put(r, 6, m.dropped()[i], cell().fmt(XlsxStyle.INT));
                    p.put(r, 7, obs == 0 ? null : (Object) ((double) m.dropped()[i] / obs), cell().fmt(XlsxStyle.PCT));
                    p.put(r, 8, m.captureMisses()[i], cell().fmt(XlsxStyle.INT));
                    p.put(r, 9, m.unknown()[i], cell().fmt(XlsxStyle.INT));
                    r++;
                }
            }
            scale(p, first, r - 1, 5, "FFFFFF", XlsxStyle.ORANGE);
            scale(p, first, r - 1, 7, "FFFFFF", XlsxStyle.ORANGE);
            p.note(r++, 0, 11, "Okuma kuralı: 'Eşleşmeyen' kayıp demek değildir (SPAN, offload, yeniden segmentasyon, yakalama kaçırması). "
                    + "Kayıp için 'Desteklenen kayıp' sütununa bakın; bu motorun kurtarılmış/etkili/kurtarılamayan kayıp olarak sınıflandırdığı olaylardır.", false);
        }
        r++;

        p.section(r++, "E. Kayıt noktası bazında paket ve retransmission işareti");
        List<String> labels = new ArrayList<>(List.of("Kayıt noktası"));
        for (int i = 0; i < periods; i++) {
            labels.add(periodLabel(i) + " paket");
        }
        for (int i = 0; i < periods; i++) {
            labels.add(periodLabel(i) + " retransmission işareti");
        }
        headers(p, r++, 0, labels, null);
        for (PointPackets pp : d.pointPackets) {
            Pt pt = d.point(pp.pointId());
            p.put(r, 0, pt.code() + " " + pt.label(), cell());
            int c = 1;
            for (int i = 0; i < periods; i++) {
                p.put(r, c++, pp.packets()[i], cell().fmt(XlsxStyle.INT));
            }
            for (int i = 0; i < periods; i++) {
                p.put(r, c++, pp.retransmissions()[i], cell().fmt(XlsxStyle.INT));
            }
            r++;
        }
        p.note(r, 0, 11, "Retransmission işareti tshark'ın işaretlediği paket sayısıdır; yerel bir TCP belirtisidir, hop'a atfedilmez.", false);

        if (chartRowHop >= 0) {
            XlsxPage page = hop;
            int mid = 5;
            List<XlsxCharts.Series> a = new ArrayList<>();
            List<XlsxCharts.Series> b = new ArrayList<>();
            for (int i = 0; i < periods; i++) {
                String color = i == 0 && d.split() ? XlsxStyle.BLUE : d.split() ? XlsxStyle.ORANGE : XlsxStyle.BLUE;
                a.add(new XlsxCharts.Series(page.sheet, hopHeaderA, hopAMeanCol + i, hopAFirst, hopALast,
                        periodLabel(i), color, hopAMeanFmt == null ? null : hopAMeanFmt[i]));
                b.add(new XlsxCharts.Series(page.sheet, hopHeaderB, hopBMeanCol + i, hopBFirst, hopBLast,
                        periodLabel(i), color, hopBMeanFmt == null ? null : hopBMeanFmt[i]));
            }
            if (hopALast >= hopAFirst) {
                XlsxCharts.bars(page.sheet, new Box(0, chartRowHop, mid, chartRowHop + 17),
                        "Komşu noktalar arası fark (ort. ms)", page.sheet, 0, hopAFirst, hopALast, a, "0.0", false, false, true);
            }
            XlsxCharts.bars(page.sheet, new Box(mid, chartRowHop, mid + 7, chartRowHop + 17),
                    "Her noktada ölçülen RTT (ort. ms)", page.sheet, 0, hopBFirst, hopBLast, b, "0.0", false, false, true);
        }
    }

    private int chartRowHop = -1;

    // ------------------------------------------------------------------------------------------------ Örnek Olaylar

    private void writeSamples() {
        XlsxPage p = samples;
        p.width(0, 22);
        p.width(1, 15);
        p.width(2, 9);
        for (int c = 3; c < 20; c++) {
            p.width(c, 13);
        }
        p.title("Örnek olaylar: en uzun bekleyen işlemlerde süre nereye gidiyor?",
                "Her satır bir istek/cevap döngüsüdür; kayıt noktalarında ölçülmüştür. Kırılım sütunları komşu noktalar arasındaki "
                        + "RTT farkıdır ve toplamı ilk noktada ölçülen RTT'ye eşittir.", 11);
        int r = 3;
        p.section(r++, "A. En uzun " + d.topCyclesRequested + " işlem (sıralama: kayıt noktalarındaki en büyük RTT)");
        if (d.absent.containsKey("top") || d.topCycles.isEmpty()) {
            p.note(r++, 0, 10, d.absent.getOrDefault("top", "Bu analizde işlem verisi yok."), true);
        } else {
            List<String> labels = new ArrayList<>(List.of("Akış / port", "Başlangıç (" + d.zoneLabel + ")", "Dönem",
                    d.firstPoint().code() + " RTT ms"));
            for (int i = 0; i + 1 < d.points.size(); i++) {
                labels.add(d.points.get(i).code() + " → " + d.points.get(i + 1).code() + " ms");
            }
            labels.add(d.lastPoint().code() + " yerel ms");
            labels.add("Eksik nokta");
            labels.add("Etiket");
            headers(p, r, 0, labels, null);
            int header = r++;
            int first = r;
            int labelCol = labels.size() - 1;
            // one millisecond format per column: a column whose values are all 100 or more drops the decimals' tail
            int parts = d.points.size(); // hop components between the points, then the last point's own part
            String totalFmt = XlsxStyle.ms(d.topCycles.stream().map(TopCycle::totalMs).toList());
            String[] partFmt = new String[parts];
            for (int k = 0; k < parts; k++) {
                List<Double> column = new ArrayList<>();
                for (TopCycle c : d.topCycles) {
                    column.add(k < c.components().length ? c.components()[k] : null);
                }
                partFmt[k] = XlsxStyle.ms(column);
            }
            String localFmt = XlsxStyle.ms(d.topCycles.stream().map(TopCycle::localMs).toList());
            for (TopCycle c : d.topCycles) {
                p.put(r, 0, c.flowLabel(), cell());
                p.put(r, 1, TrFormat.epochToLocal(c.start(), d.zone, false), cell());
                p.put(r, 2, c.periodLabel(), cell());
                p.put(r, 3, c.totalMs(), cell().fmt(totalFmt));
                int col = 4;
                int k = 0;
                for (Double comp : c.components()) {
                    p.put(r, col++, comp, cell().fmt(partFmt[Math.min(k++, parts - 1)]));
                }
                p.put(r, col++, c.localMs(), cell().fmt(localFmt));
                p.put(r, col++, c.missing(), cell().fmt(XlsxStyle.INT));
                p.put(r, col, "#" + (r - first + 1) + " @ " + TrFormat.epochToLocal(c.start(), d.zone, false), cell());
                r++;
            }
            int last = r - 1;
            p.width(labelCol, 20);
            p.note(r++, 0, 10, "Bir noktada ölçülmeyen döngü için ilgili kırılım boş kalır; 'Eksik nokta' ölçülemeyen nokta sayısıdır. "
                    + "Negatif küçük farklar saat belirsizliğindendir ve korunmuştur.", false);
            r++;
            int shown = Math.min(10, last - first + 1);
            // the stacked bars use the per-hop columns only (the components and the last point's own part), never the
            // total RTT in column D: the total is the sum of the stack
            int hopColumns = labelCol - 1 - 4; // columns 4 .. labelCol - 2 (the last two are the missing-point count and the label)
            List<XlsxCharts.Series> series = new ArrayList<>();
            for (int i = 0; i < hopColumns; i++) {
                int col = 4 + i;
                series.add(new XlsxCharts.Series(p.sheet, header, col, first, first + shown - 1, labels.get(col),
                        XlsxStyle.SERIES[i % XlsxStyle.SERIES.length], i < parts - 1 ? partFmt[i] : localFmt));
            }
            XlsxCharts.bars(p.sheet, new Box(0, r, 9, r + 21), "En uzun " + shown + " işlem: RTT kırılımı (ms)", p.sheet,
                    labelCol, first, first + shown - 1, series, "0", true, true, true);
            r += 23;
        }
        r++;
        p.section(r++, "B. Retransmission merdiveni");
        Ladder l = d.ladder;
        if (l == null) {
            p.note(r, 0, 10, d.absent.getOrDefault("ladder", "Retransmission merdiveni için veri yok."), true);
            return;
        }
        p.note(r++, 0, 10, l.flowLabel() + ": " + l.description() + ". Her sütun kendi kayıt noktasındaki ilk kopyaya göre bağıl zamandır "
                + "(saat ofseti etkisizdir).", true);
        List<String> labels = new ArrayList<>(List.of("Kopya", "IP ID"));
        for (String caption : l.pointCaptions()) {
            labels.add(caption + " ms");
        }
        headers(p, r, 0, labels, null);
        int header = r++;
        int first = r;
        int k = 1;
        String[] ladderFmt = new String[l.pointCaptions().size()];
        for (int i = 0; i < ladderFmt.length; i++) {
            List<Double> column = new ArrayList<>();
            for (LadderRow row : l.rows()) {
                column.add(i < row.relativeMs().length ? row.relativeMs()[i] : null);
            }
            ladderFmt[i] = XlsxStyle.ms(column);
        }
        for (LadderRow row : l.rows()) {
            p.put(r, 0, "#" + k++, cell());
            p.put(r, 1, row.ipId(), cell().fmt(XlsxStyle.ID));
            for (int i = 0; i < row.relativeMs().length; i++) {
                p.put(r, 2 + i, row.relativeMs()[i], cell().fmt(ladderFmt[i]));
            }
            r++;
        }
        int last = r - 1;
        r += 1;
        List<XlsxCharts.Series> series = new ArrayList<>();
        int firstCol = 2;
        int lastCol = 2 + l.pointCaptions().size() - 1;
        series.add(new XlsxCharts.Series(p.sheet, header, firstCol, first, last, labels.get(firstCol), XlsxStyle.BLUE));
        if (lastCol > firstCol) {
            series.add(new XlsxCharts.Series(p.sheet, header, lastCol, first, last, labels.get(lastCol), XlsxStyle.ORANGE));
        }
        XlsxCharts.bars(p.sheet, new Box(0, r, 8, r + 16), "Kopyaların bağıl zamanı: ilk ve son kayıt noktası (ms)", p.sheet, 0,
                first, last, series, "0", false, false, true);
    }

    // ------------------------------------------------------------------------------------------------ Sunucu Korelasyonu

    private static final String[][] HOST_SECTIONS = {
        {HostCorrelationBuilder.B, "B. vmstat ve sar: CPU, çalışma kuyruğu, süreç"},
        {HostCorrelationBuilder.C, "C. sar TCP ve ağ arayüzleri"},
        {HostCorrelationBuilder.D, "D. qdisc / softnet sayaç artışları"},
        {HostCorrelationBuilder.E, "E. pidstat süreç satırı sayısı"},
        {HostCorrelationBuilder.F, "F. syslog saniyelik kayıt sayısı"},
    };

    private record ChartPick(String title, String color, double[] normalised) {
    }

    private void writeHost() {
        XlsxPage p = host;
        HostData h = d.host;
        Timeline t = d.timeline;
        boolean utc = utcColumn();
        int lead = utc ? 3 : 2; // clock, [UTC], period
        boolean assumed = h.files.stream().anyMatch(f -> f.format() != TelemetryFormat.UNKNOWN && !f.zoneConfirmed());
        p.title("Sunucu tarafı korelasyon: sunucu göstergeleri yakalama zaman çizelgesiyle aynı saniye ızgarasında",
                "Kaynak: vakaya yüklenen sunucu metin dosyaları (sar, vmstat, pidstat, qdisc/softnet anlık görüntüleri, syslog). "
                        + "Rakamlar dosyalardan okunan değerlerdir; bu sayfa bir hüküm içermez. Saatler " + d.zoneLabel
                        + " olarak verilir" + (utc ? " (UTC sütunu yanında)." : ".")
                        + (assumed ? " Saat dilimi teyit edilmeyen dosyalarda UTC varsayılmıştır (A)." : ""), 12);
        int r = 3;
        p.section(r++, "A. Saat eşlemesi");
        p.note(r++, 0, 9, "Eşleme: yakalama saati = dosyadaki saat (aşağıdaki saat dilimiyle UTC'ye çevrilir) + ek saat farkı. "
                + "Saat dilimi kullanıcı tarafından teyit edilmemişse UTC varsayılır ve bu açıkça yazılır.", false);
        for (HostFile f : h.files) {
            r = hostFileBlock(p, f, r);
        }
        if (!h.programs.isEmpty()) {
            p.section(r++, "F. syslog: en sık program etiketleri (tüm dosya; ad maskelenebilir, mesaj metni yoktur)");
            headers(p, r++, 0, List.of("Dosya", "Satır"), null);
            p.merge(r - 1, 2, r - 1, 4, "Program etiketi", header());
            for (ProgramRow row : h.programs) {
                p.put(r, 0, row.fileCode(), cell());
                p.put(r, 1, row.count(), cell().fmt(XlsxStyle.INT));
                p.merge(r, 2, r, 4, row.program(), cell());
                r++;
            }
            r++;
        }
        if (t == null || h.columns.isEmpty()) {
            p.note(r, 0, 9, h.gridNote == null ? "Sunucu göstergesi yerleştirilemedi." : h.gridNote, true);
            return;
        }

        // grid geometry
        int firstDataCol = lead;
        int lastDataCol = firstDataCol + h.columns.size() - 1;
        int chartBlock = r;
        List<ChartPick> picks = chartPicks(h, t);
        r += picks.isEmpty() ? 0 : 17;
        int groupRow = r++;
        int headerRow = r++;
        int first = r;
        int last = first + t.count - 1;
        // group titles over runs of columns of one section
        p.put(groupRow, 0, "Saniye ızgarası (Zaman Çizelgesi ile aynı satırlar)", St.base().bold());
        for (String[] section : HOST_SECTIONS) {
            int lo = -1;
            int hi = -1;
            for (int i = 0; i < h.columns.size(); i++) {
                if (h.columns.get(i).section.equals(section[0])) {
                    lo = lo < 0 ? i : lo;
                    hi = i;
                }
            }
            if (lo >= 0) {
                p.merge(groupRow, firstDataCol + lo, groupRow, firstDataCol + hi, section[1],
                        St.base().bold().fill(XlsxStyle.FINDING_FILL).border().center());
            }
        }
        p.height(groupRow, 30);
        List<String> labels = new ArrayList<>(List.of("Saat (" + d.zoneLabel + ")"));
        if (utc) {
            labels.add("UTC");
        }
        labels.add("Dönem");
        for (HostColumn c : h.columns) {
            labels.add(c.title);
        }
        double[] widths = new double[labels.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = i == 0 ? 11 : utc && i == 1 ? 9 : i == lead - 1 ? 8 : 15;
        }
        headers(p, headerRow, 0, labels, widths);
        p.height(headerRow, 56);
        p.sheet.createFreezePane(lead, 0);
        for (int i = 0; i < t.count; i++) {
            int row = first + i;
            p.put(row, 0, t.local[i], cell());
            if (utc) {
                p.put(row, 1, t.utc[i], cell());
            }
            St periodStyle = cell();
            String periodText = null;
            if (t.period[i] >= 0 && d.split()) {
                periodText = periodLabel(t.period[i]);
                periodStyle = periodFill(periodStyle, t.period[i]);
            }
            p.put(row, lead - 1, periodText, periodStyle);
            for (int k = 0; k < h.columns.size(); k++) {
                HostColumn c = h.columns.get(k);
                p.put(row, firstDataCol + k, c.values[i], cell().fmt(c.format));
            }
        }
        for (int k = 0; k < h.columns.size(); k++) {
            HostColumn c = h.columns.get(k);
            if (c.kind != Series.Kind.GAUGE || c.key.equals("sar.retrans/s") || c.key.equals("vmstat.r") || c.key.equals("sar.runq")) {
                scale(p, first, last, firstDataCol + k, "FFFFFF", XlsxStyle.ORANGE);
            }
        }
        // chart: every series scaled to its own maximum so that series of very different size share one axis
        if (!picks.isEmpty()) {
            int helperHeader = lastDataCol + 2;
            p.put(groupRow, helperHeader, "Grafik girdisi: her seri kendi en büyük değerine göre %", St.base().bold());
            List<XlsxCharts.Series> series = new ArrayList<>();
            for (int i = 0; i < picks.size(); i++) {
                ChartPick pick = picks.get(i);
                int col = helperHeader + i;
                p.width(col, 15);
                p.put(headerRow, col, pick.title(), header());
                for (int k = 0; k < t.count; k++) {
                    p.put(first + k, col, Double.isNaN(pick.normalised()[k]) ? null : (Object) pick.normalised()[k],
                            cell().fmt(XlsxStyle.RATE).color(XlsxStyle.MUTED));
                }
                series.add(new XlsxCharts.Series(p.sheet, headerRow, col, first, last, pick.title(), pick.color(), XlsxStyle.RATE));
            }
            p.section(chartBlock, "Sunucu göstergeleri ve yakalama aynı zaman ekseninde (her seri kendi en büyük değerine göre %)");
            XlsxCharts.line(p.sheet, new Box(0, chartBlock + 1, 14, chartBlock + 16),
                    "Sunucu göstergeleri ve yakalama, saniyelik (her seri kendi en büyük değerine göre %)", p.sheet, 0, first, last,
                    series, "0", Math.max(1, (int) Math.ceil(t.count / 12.0)), true, Plot.TIMELINE);
        }
    }

    private List<ChartPick> chartPicks(HostData h, Timeline t) {
        List<ChartPick> out = new ArrayList<>();
        double[] capture = new double[t.count];
        double max = 0;
        for (int i = 0; i < t.count; i++) {
            capture[i] = t.retransmissions[i] / t.width;
            max = Math.max(max, capture[i]);
        }
        if (max > 0) {
            out.add(new ChartPick("Yakalama: retransmission işareti/sn (%)", XlsxStyle.RED, normalise(capture, max)));
        }
        String[] wanted = {"vmstat.r", "sar.runq", "sar.retrans/s", "qdisc.dropped|", "pidstat.rows.delta", "syslog.count"};
        String[] colors = {XlsxStyle.BLUE, XlsxStyle.BLUE, XlsxStyle.ORANGE, XlsxStyle.VIOLET, XlsxStyle.GREEN, XlsxStyle.YELLOW};
        boolean haveRunq = false;
        for (int w = 0; w < wanted.length && out.size() < 6; w++) {
            HostColumn pick = null;
            double best = -1;
            for (HostColumn c : h.columns) {
                boolean match = wanted[w].endsWith("|") ? c.key.startsWith(wanted[w]) : c.key.equals(wanted[w]);
                if (!match || w == 1 && haveRunq) {
                    continue;
                }
                double peak = 0;
                for (Double v : c.values) {
                    peak = Math.max(peak, v == null ? 0 : v);
                }
                if (peak > best) {
                    best = peak;
                    pick = c;
                }
            }
            if (pick == null || best <= 0) {
                continue;
            }
            haveRunq |= w == 0;
            double[] values = new double[t.count];
            for (int i = 0; i < values.length; i++) {
                values[i] = pick.values[i] == null ? Double.NaN : pick.values[i];
            }
            out.add(new ChartPick(pick.title + " (%)", colors[w], normalise(values, best)));
        }
        return out;
    }

    private static double[] normalise(double[] values, double max) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Double.isNaN(values[i]) ? Double.NaN : Math.round(values[i] / max * 1000.0) / 10.0;
        }
        return out;
    }

    private int hostFileBlock(XlsxPage p, HostFile f, int start) {
        int r = start;
        String head = f.code() + " · " + f.name() + " — " + (f.placed() ? "yakalama aralığına yerleştirildi" : "kullanılamadı");
        p.merge(r, 0, r, 9, head, St.base().bold().fill(f.placed() ? XlsxStyle.FINDING_FILL : XlsxStyle.NOTE_FILL).border());
        r++;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Biçim (içerikten)", f.format() == TelemetryFormat.UNKNOWN ? "tanınmadı" : f.format().label()});
        rows.add(new String[] {"Sunucu adı", f.hostName() == null ? DASH : f.hostName()});
        rows.add(new String[] {"Boyut", f.sizeBytes() == null ? DASH : TrFormat.integer(f.sizeBytes()) + " bayt"
                + (f.sha256() == null ? "" : " · SHA-256 " + f.sha256())});
        rows.add(new String[] {"Kullanılan saat dilimi", f.zoneText()});
        rows.add(new String[] {"Dosyanın kendi saat etiketi", f.fileZoneLabel() == null ? DASH : f.fileZoneLabel()});
        rows.add(new String[] {"Ek saat farkı (sn)", f.offsetSeconds() == 0 ? "0" : (f.offsetSeconds() > 0 ? "+" : "") + f.offsetSeconds()});
        rows.add(new String[] {"Verilen başlangıç (ilk örnek)", f.anchorText() == null ? DASH : f.anchorText() + " (" + f.zoneText().replaceAll(" \\(.*", "") + ")"});
        rows.add(new String[] {"Dosyadaki ilk örnek", f.first() == null ? DASH : when(f.first())});
        rows.add(new String[] {"Dosyadaki son örnek", f.last() == null ? DASH : when(f.last())});
        rows.add(new String[] {"Yakalama aralığıyla örtüşme", f.overlapSeconds() == null ? DASH
                : f.overlapSeconds() <= 0 ? "yok" : TrFormat.number(f.overlapSeconds(), 0) + " sn"});
        rows.add(new String[] {"Satırlar", f.format() == TelemetryFormat.UNKNOWN
                ? "incelenen ilk satır sayısı " + TrFormat.integer(f.linesTotal())
                : "toplam " + TrFormat.integer(f.linesTotal()) + " · kullanılan " + TrFormat.integer(f.linesUsed())
                        + " · yok sayılan (boş, başlık, kullanılmayan blok) " + TrFormat.integer(f.linesIgnored()) + " · atlanan (bozuk) "
                        + TrFormat.integer(f.linesSkipped())});
        if (f.skipText() != null) {
            rows.add(new String[] {"Atlanan satır nedenleri", f.skipText()});
        }
        rows.add(new String[] {"Durum", f.placed() ? "Kullanıldı" : f.statusNote()});
        for (String note : f.notes()) {
            rows.add(new String[] {"Not", note});
        }
        for (String[] row : rows) {
            p.merge(r, 0, r, 2, row[0], cell().bold().wrap());
            p.merge(r, 3, r, 9, row[1], cell().wrap());
            p.height(r, XlsxPage.wrappedHeight(row[1], p.spanWidth(3, 9), 13));
            r++;
        }
        return r + 1;
    }

    private String when(double epoch) {
        String local = TrFormat.dateTime(epoch, d.zone);
        return d.zoneLabel.equals("UTC") ? local : local + " · UTC " + TrFormat.epochToLocal(epoch, java.time.ZoneOffset.UTC, false);
    }

    // ------------------------------------------------------------------------------------------------ Yöntem ve Veri

    private void writeMethod() {
        XlsxPage p = method;
        p.width(0, 9);
        p.width(1, 30);
        p.width(2, 40);
        p.width(3, 44);
        p.width(4, 26);
        for (int c = 5; c < 11; c++) {
            p.width(c, 16);
        }
        p.title("Yöntem, terimler ve veri kaynakları", null, 1);
        int r = 2;
        p.section(r++, "A. Kayıt noktaları");
        headers(p, r++, 0, List.of("Nokta", "Kısa ad", "Cihaz", "Kaynak yakalama dosyası", "Yön", "Kapsam başlangıcı", "Kapsam sonu",
                "Paket", "Dışlanan paket", "Saat modeli", "Saat belirsizliği ± ms"), null);
        String uncertaintyFmt = XlsxStyle.ms(d.points.stream().map(Pt::clockUncertaintyMs).toList());
        for (Pt pt : d.points) {
            p.put(r, 0, pt.code(), cell());
            p.put(r, 1, pt.label(), cell());
            p.put(r, 2, pt.device(), cell());
            p.put(r, 3, pt.file(), cell());
            p.put(r, 4, sideText(pt.side()), cell());
            p.put(r, 5, pt.coverStart() == null ? null : TrFormat.epochToLocal(pt.coverStart(), d.zone, false), cell());
            p.put(r, 6, pt.coverEnd() == null ? null : TrFormat.epochToLocal(pt.coverEnd(), d.zone, false), cell());
            p.put(r, 7, pt.packets(), cell().fmt(XlsxStyle.INT));
            p.put(r, 8, pt.excluded(), cell().fmt(XlsxStyle.INT));
            p.put(r, 9, clockText(pt.clock()), cell());
            p.put(r, 10, pt.clockUncertaintyMs(), cell().fmt(uncertaintyFmt));
            r++;
        }
        p.note(r++, 0, 4, "Kapsam saatleri " + d.zoneLabel + " zamanındadır.", false);
        r++;

        p.section(r++, "B. Terim sözlüğü (proje terimi → standart karşılık)");
        headers(p, r++, 1, List.of("Proje terimi", "Standart karşılık", "Tanım", "Sınır"), null);
        for (String[] t : GLOSSARY) {
            for (int i = 0; i < 4; i++) {
                p.put(r, 1 + i, t[i], cell().wrap());
            }
            p.height(r, Math.max(42, XlsxPage.wrappedHeight(t[2], 44, 13)));
            r++;
        }
        r++;

        r = writeMarkerThresholds(p, r);

        p.section(r++, "D. Sınırlar ve dikkat noktaları");
        for (String limit : LIMITS) {
            if (d.host != null && limit.startsWith("Sunucu (host) tarafı günlükleri")) {
                continue; // the case carries host telemetry: the Sunucu Korelasyonu sheet says what was used and how
            }
            p.note(r++, 0, 4, "• " + limit, false);
        }
        if (d.host != null) {
            for (String limit : d.host.limits) {
                p.note(r++, 0, 4, "• " + limit, false);
            }
        }
        if (!d.limitations.isEmpty()) {
            p.put(r++, 0, "Motorun belirttiği sınırlar (özgün metin):", St.base().bold());
            for (String limit : d.limitations) {
                p.note(r++, 0, 4, "• " + limit, false);
            }
        }
        r++;

        p.section(r++, "E. Kaynak dosya bütünlüğü (SHA-256)");
        List<Source> listed = new ArrayList<>(d.sources);
        if (d.host != null) {
            for (HostFile f : d.host.files) {
                listed.add(new Source("Sunucu verisi " + f.code() + ": " + f.name(), f.sizeBytes(), f.sha256()));
            }
        }
        if (listed.isEmpty()) {
            p.note(r++, 0, 4, "Bu çalıştırmada kaynak yakalama dosyası bilgisi verilmedi.", true);
        } else {
            headers(p, r++, 1, List.of("Dosya", "Boyut (bayt)", "SHA-256"), null);
            for (Source s : listed) {
                p.put(r, 1, s.name(), cell());
                p.put(r, 2, s.sizeBytes(), cell().fmt(XlsxStyle.INT));
                p.put(r, 3, s.sha256() == null ? DASH : s.sha256(), cell());
                r++;
            }
            if (listed.stream().allMatch(s -> s.sha256() == null)) {
                p.note(r++, 0, 4, "Bu görünümde SHA-256 değerleri gösterilmez (maskeli proje görünümüyle aynı kural).", false);
            }
        }
        r++;

        p.section(r++, "F. Rapor parametreleri");
        String[][] params = {
            {"Motor sürümü", d.engineVersion == null ? DASH : d.engineVersion},
            {"Motorun kendi özeti (değiştirilmemiş)", d.verdict == null ? DASH : d.verdict},
            {"Zaman dilimi", d.zoneLabel},
            {"Kova genişliği (sn)", TrFormat.number(d.bucketSeconds, 1)},
            {"Karşılaştırma kipi", modeText()},
            {"Analiz penceresi", rangeText()},
            {"Döngü bekleme eşiği (ms)", TrFormat.ms(d.stallMs)},
            {"Listelenen en uzun işlem sayısı", Integer.toString(d.topCyclesRequested)},
        };
        for (String[] param : params) {
            p.put(r, 1, param[0], cell().bold());
            p.merge(r, 2, r, 3, param[1], cell().wrap());
            r++;
        }
    }

    /** The marker rule's fixed values, one row each, and the rule in one sentence (proposal section 8). */
    private int writeMarkerThresholds(XlsxPage p, int r) {
        p.section(r++, "C. Hop işaretleri: sabit eşikler (PO kararı 2026-10-18)");
        headers(p, r++, 1, List.of("Değer", "Eşik", "Anlamı"), null);
        String[][] rows = {
            {"Gecikme eşiği", TrFormat.number(HopVerdict.DELAY_MS, 0) + " ms",
                "Ortalama en az bu kadar artmalı; önceki pencerenin ortancasından en az bu kadar sonra gelen paket \"geç kalan\" sayılır."},
            {"Oran", HopVerdict.RATIO + "×",
                "Sonraki ortalama önceki ortalamanın en az bu katı olmalı (önceki ortalama " + TrFormat.number(HopVerdict.RATIO_FLOOR_MS, 1)
                        + " ms'den küçükse " + TrFormat.number(HopVerdict.RATIO_FLOOR_MS, 1) + " ms alınır)."},
            {"Örnek sayısı", Long.toString(HopVerdict.MIN_SAMPLES),
                "Bir sinyal her iki pencerede en az bu kadar örnekle değerlendirilir; gecikme için sonraki pencerede en az bu kadar geç kalan paket gerekir."},
            {"Geç kalan payının artışı", HopVerdict.SHARE_GROWTH_PER_HUNDRED + " puan",
                "Gecikme için geç kalan paketlerin payı en az bu kadar artmalı; temiz için bu artış bunun altında kalmalı."},
            {"Saat çarpanı", TrFormat.number(HopVerdict.CLOCK_FACTOR, 0) + "×",
                "Artış hop saat belirsizliğinin bu katından büyük olmalı; temiz için bu kat " + TrFormat.number(HopVerdict.DELAY_MS, 0)
                        + " ms'den küçük olmalı (aksi halde belirsiz)."},
            {"Temiz sınırı", TrFormat.number(HopVerdict.CLEAN_MS, 0) + " ms",
                "Temiz için ortalama fark ±bu değerin içinde kalmalı ve önceki ortancanın bu kadar üstüne çıkan paketlerin payı artmamalı."},
        };
        for (String[] row : rows) {
            p.put(r, 1, row[0], cell().bold());
            p.put(r, 2, row[1], cell());
            p.put(r, 3, row[2], cell().wrap());
            p.height(r, Math.max(16, XlsxPage.wrappedHeight(row[2], 44, 13)));
            r++;
        }
        p.note(r++, 0, 4, "\"gecikme burada\" = ortalama geçiş süresi en az 5 ms ve 2 kat arttı, artış hop'un saat belirsizliğinin 2 "
                + "katından büyük, en az 10 paket önceki ortancanın 5 ms üstüne çıktı ve bu paketlerin payı en az 1 puan arttı. "
                + "\"temiz\" = ortalama fark ±1 ms içinde, geç kalan paket payı artmadı ve ölçüm 5 ms'lik bir değişimi görebilecek "
                + "hassasiyette. Diğer her durum belirsizdir.", false);
        p.note(r++, 0, 4, "Sinyaller: ileri ve geri yön geçiş süresi (aynı paketin iki komşu noktadaki zaman farkı) ile istek RX / "
                + "cevap TX döngü bileşenleri. Bir sinyal gecikme gösterirse hop \"gecikme burada\" olur; \"temiz\" için "
                + "değerlendirilen her sinyal temiz olmalıdır; yeterli örneği olmayan sinyal değerlendirilmez. Ortanca, önceki "
                + "pencerenin örneklerinin tam ortancasıdır; ortalamalar tam sayı toplamlarıyla hesaplanır.", false);
        return r + 1;
    }

    private String modeText() {
        return switch (d.window.mode()) {
            case ONSET -> "Motorun ilk olay başlangıcı";
            case EXPLICIT -> "Verilen pencere";
            case NONE -> "Olay penceresi yok: tüm yakalama";
            case NO_DATA -> "Analiz penceresi yok";
        };
    }

    private static String sideText(String side) {
        if (side == null) {
            return DASH;
        }
        return switch (side) {
            case "ingress" -> "giriş";
            case "egress" -> "çıkış";
            case "both" -> "çift yön";
            default -> side;
        };
    }

    private static String clockText(String confidence) {
        if (confidence == null) {
            return DASH;
        }
        return switch (confidence) {
            case "reference" -> "referans saat";
            case "estimated" -> "kestirilmiş";
            default -> confidence;
        };
    }

    private static final String[][] GLOSSARY = {
        {"Döngü", "İstek/cevap döngüsü, RTT (opak TCP turu)",
            "Aynı kayıt noktasında istemci veri patlamasının ilk paketi ile ardından gelen ilk sunucu veri patlaması arasındaki süre.",
            "Çözümlenmiş bir uygulama isteği değildir; pipelining, akış ve eksik turlar belirsizdir. Capture'ın gördüğü yerel saatle ölçülür."},
        {"Geçiş (komşu fark)", "Hop transit / one-way residence",
            "Aynı paketin iki komşu kayıt noktasında görülme zamanı farkı (motorun paket eşleştirmesiyle).",
            "Saat belirsizliği ve minimum yol simetrisi varsayımı farka yansır; aynı cihazın giriş/çıkış noktaları arasında cihaz içi bekleme, aksi halde hat + cihaz gecikmesidir."},
        {"Hop RTT farkı", "Bidirectional residence difference",
            "Aynı istek/cevap çiftinin iki noktadaki RTT farkı; gidiş+dönüş birlikte.",
            "Tek yön RX/TX gecikmesi bu farktan ayrıştırılmaz."},
        {"Eşleşmeyen gözlem", "Unmatched observation",
            "Kaynak noktadaki paketin karşı noktada eşleştirilememesi.",
            "Kayıp demek değildir: SPAN, offload (TSO/GRO), VLAN, yeniden segmentasyon ve yakalama kaçırması da bu sonucu verir."},
        {"Desteklenen kayıp", "Supported loss event",
            "Motorun kurtarılmış, etkili veya kurtarılamayan kayıp, engellenen el sıkışma ya da doğrulanmış cihaz düşürmesi olarak sınıflandırdığı olay.",
            "Yakalama kaçırması ve belirsiz görünüm ağ kaybı sayılmaz."},
        {"Retransmission işareti", "TCP retransmission (analysis flag)",
            "tshark'ın retransmission olarak işaretlediği paket sayısı.",
            "Yerel bir TCP belirtisidir; tetikleyen uç görülmez ve hop'a atfedilmez."},
        {"Önce / Sonra", "Baseline / event window",
            "Önce: karşılaştırılan aralığın başından bölme anına kadar; Sonra: bölme anından aralığın sonuna kadar.",
            "Bölme anı motorun ilk olay başlangıcıdır ya da verilen pencerenin başlangıcıdır; saniyelik kovalar başlangıç zamanına göre atanır."},
        {"Saat belirsizliği", "Clock uncertainty",
            "Yakalama saatlerinin birbirine göre kestirilen ofsetinin belirsizliği.",
            "Bu değerden küçük farklar saat belirsizliği içindedir ve tek başına yorumlanmamalıdır."},
    };

    private static final String[] LIMITS = {
        "Eşleşme kapsamı bu yakalamalardaki paketlerle sınırlıdır; tüm trafiğin örneklemi değildir.",
        "Zaman damgaları yakalama noktalarının saatlerinden okunur; motor saat ofsetini minimum yol simetrisi varsayımıyla kestirir. Farklar ilgili belirsizlik içinde yorumlanmalıdır.",
        "Negatif küçük farklar korunmuştur; fiziksel negatif gecikme değil, saat belirsizliği ve yakalama yönü etkisidir.",
        "SPAN düşüşü/aşırı abonelik sayaçları ve zaman damgasının üretildiği yer (NIC veya çekirdek) bilinmemektedir; farklar bu etkileri birlikte kapsar.",
        "Sunucu (host) tarafı günlükleri (CPU, süreç, kuyruk sayaçları) bu analize dahil edilmemiştir; rapor yalnız paket yakalamalarından türetilmiştir.",
        "Rapor yalnız başlık ve zaman alanlarından üretilmiştir; paket içeriği (payload) rapora alınmamıştır.",
        "Hop işaretleri (temiz / gecikme burada) yalnız Yöntem sayfasındaki sabit eşiklerle üretilir; kök neden, kayıp veya yeniden iletim hükmü değildir.",
        "\"temiz\" ölçülen bir gecikme artışı olmadığını söyler; kayıp, engellenen bağlantı veya yakalama kaçırması olmadığını söylemez. Bir işaret karşılaştırılan pencereye bağlıdır ve pencereyle birlikte okunur.",
        "Bir saat sıçraması (NTP adımı, sanal makine duraklaması) gecikme gibi görünür ve saat belirsizliği kuralıyla ayrılamaz; eşikler sentetik yakalamalarla seçilmiş, gerçek olaylarla doğrulanmamıştır.",
    };

    // ------------------------------------------------------------------------------------------------ hop markers

    /** "Pencere: önce 12,0 sn / sonra 28,0 sn": the window every marker was computed for. */
    private String markerWindowText() {
        if (!d.split()) {
            return "Pencere: önce/sonra karşılaştırması yok";
        }
        return "Pencere: önce " + TrFormat.number(Math.max(0, d.window.periods().get(0).seconds()), 1) + " sn / sonra "
                + TrFormat.number(Math.max(0, d.window.periods().get(1).seconds()), 1) + " sn";
    }

    private static St markStyle(HopVerdict.Mark mark) {
        St st = St.base().size(9).bold().center().wrap();
        return mark == HopVerdict.Mark.DELAY ? st.color(XlsxStyle.RED) : st.color(XlsxStyle.GREEN);
    }

    private int boxOf(String pointId) {
        for (int j = 0; j < d.chain.size(); j++) {
            if (d.chain.get(j).pointIds().contains(pointId)) {
                return j;
            }
        }
        return -1;
    }

    /**
     * The marker row under the chain strip: the word of a hop between two devices under its arrow, the words of hops
     * inside one device ("cihaz içi") in that device's box, and one line with the window and where the reasons are.
     * belirsiz and ölçüm yok print no word. Returns whether anything was written.
     */
    private boolean writeChainMarkers(XlsxPage p, int row, int lastCol) {
        String[] arrow = new String[d.chain.size()];
        HopVerdict.Mark[] arrowMark = new HopVerdict.Mark[d.chain.size()];
        List<List<String>> inside = new ArrayList<>();
        List<HopVerdict.Mark> insideMark = new ArrayList<>();
        for (int j = 0; j < d.chain.size(); j++) {
            inside.add(new ArrayList<>());
            insideMark.add(null);
        }
        boolean any = false;
        for (HopMark m : d.hopMarks) {
            String word = m.mark().stripWord();
            int a = boxOf(m.pointA());
            int b = boxOf(m.pointB());
            if (word == null || a < 0 || b < 0) {
                continue;
            }
            if (a == b) {
                Pt pa = d.point(m.pointA());
                Pt pb = d.point(m.pointB());
                inside.get(a).add(pa.code() + "→" + pb.code() + " " + word);
                // one box, one colour: a delay inside the device outranks a clean hop next to it
                if (insideMark.get(a) != HopVerdict.Mark.DELAY) {
                    insideMark.set(a, m.mark());
                }
                any = true;
            } else if (b == a + 1 && arrow[a] == null) {
                arrow[a] = word;
                arrowMark[a] = m.mark();
                any = true;
            }
        }
        if (!any) {
            return false;
        }
        for (int j = 0; j < d.chain.size(); j++) {
            if (!inside.get(j).isEmpty()) {
                p.put(row, 1 + 2 * j, String.join(" · ", inside.get(j)), markStyle(insideMark.get(j)));
            }
            if (arrow[j] != null) {
                p.width(2 + 2 * j, 10);
                p.put(row, 2 + 2 * j, arrow[j], markStyle(arrowMark[j]));
            }
        }
        p.height(row, 26);
        p.note(row + 1, 1, lastCol, "Hop işaretleri: " + markerWindowText() + ". Yalnız Yöntem ve Veri sayfasındaki sabit "
                + "eşiklerle üretilir; işaretsiz hop belirsizdir veya ölçümü yoktur, gerekçe Hop Analizi sayfasındadır.", false);
        return true;
    }

    /** The hop decision table at the top of the hop sheet: one row per hop with Karar, Gerekçe and the window. */
    private int writeHopMarks(XlsxPage p, int r) {
        p.section(r++, "Hop işaretleri (temiz / gecikme burada)");
        if (d.hopMarks.isEmpty()) {
            p.note(r++, 0, 11, "Komşu kayıt noktası çifti olmadığı için hop işareti yok.", true);
            return r + 1;
        }
        p.put(r, 0, "Hop", header());
        p.put(r, 1, "Tür", header());
        p.put(r, 2, "Karar", header());
        p.merge(r, 3, r, 10, "Gerekçe", header());
        p.merge(r, 11, r, 12, "Pencere", header());
        p.height(r, 30);
        r++;
        String window = markerWindowText();
        for (HopMark m : d.hopMarks) {
            p.put(r, 0, m.label(), cell().wrap());
            p.put(r, 1, m.deviceHop() ? "Cihaz içi" : "Hat / komşu", cell());
            St word = cell().bold().center();
            if (m.mark() == HopVerdict.Mark.DELAY) {
                word = word.color(XlsxStyle.RED);
            } else if (m.mark() == HopVerdict.Mark.CLEAN) {
                word = word.color(XlsxStyle.GREEN);
            }
            p.put(r, 2, m.mark().word, word);
            p.merge(r, 3, r, 10, m.reason(), cell().wrap());
            p.merge(r, 11, r, 12, window, cell().wrap());
            p.height(r, Math.max(XlsxPage.wrappedHeight(m.reason(), p.spanWidth(3, 10), 13),
                    XlsxPage.wrappedHeight(m.label(), p.spanWidth(0, 0), 13)));
            r++;
        }
        p.note(r++, 0, 12, "Karar yalnız Yöntem ve Veri sayfasındaki sabit eşiklerle üretilir (bölüm C); kök neden, kayıp veya "
                + "yeniden iletim hükmü değildir. \"temiz\" yalnız gecikme içindir: ölçülen bir gecikme artışı olmadığını söyler, "
                + "kayıp, engellenen bağlantı veya yakalama kaçırması olmadığını söylemez; eşleşme ve kayıp rakamları bu "
                + "sayfanın D bölümündedir. Belirsiz ve ölçümü olmayan hop'lar zincir şeridinde işaretsizdir.", false);
        return r + 1;
    }

    // ------------------------------------------------------------------------------------------------ Özet

    private void writeSummary() {
        XlsxPage p = summary;
        int wide = Math.max(6, d.chain.size());
        int lastCol = 2 * wide - 1;
        p.width(0, 2);
        for (int j = 0; j < wide; j++) {
            p.width(1 + 2 * j, 18);
            if (2 + 2 * j <= lastCol) {
                p.width(2 + 2 * j, 4);
            }
        }
        p.title(summaryTitle(),
                "Zaman dilimi " + d.zoneLabel + ". " + d.points.size() + " kayıt noktası. Analiz penceresi: " + rangeText()
                        + ". " + windowSentence() + " Rapor yalnız başlık ve zaman alanlarından üretilmiştir; paket içeriği yoktur.",
                lastCol + 1);
        int r = 3;
        p.section(r++, "Zincir ve kayıt noktaları");
        if (d.chain.isEmpty()) {
            p.note(r++, 1, lastCol, "Topolojide kayıt noktası bulunamadı.", true);
        } else {
            for (int j = 0; j < d.chain.size(); j++) {
                ExpertReportData.Box b = d.chain.get(j);
                p.put(r, 1 + 2 * j, b.device(), St.base().bold().size(11).fill(XlsxStyle.BOX_FILL).border().center());
                p.put(r + 1, 1 + 2 * j, String.join(" · ", b.captions()),
                        St.base().size(9).color(XlsxStyle.MUTED).fill(XlsxStyle.BOX_FILL).border().center());
                if (j + 1 < d.chain.size()) {
                    p.put(r, 2 + 2 * j, "⇄", St.base().size(14).center());
                }
            }
            p.height(r, 30);
            p.height(r + 1, 30);
            r += writeChainMarkers(p, r + 2, lastCol) ? 5 : 3;
        }
        p.section(r++, "Bulgular");
        // at most five on this page, in a fixed order; the rest sit below the indicator table
        List<Finding> headline = new ArrayList<>();
        List<Finding> others = new ArrayList<>();
        for (String id : ExpertFindings.SUMMARY_IDS) {
            for (Finding f : d.findings) {
                if (f.id().equals(id)) {
                    headline.add(f);
                    break;
                }
            }
        }
        for (Finding f : d.findings) {
            if (!headline.contains(f)) {
                others.add(f);
            }
        }
        int n = 1;
        for (Finding f : headline) {
            String text = n++ + ". " + f.text();
            p.merge(r, 1, r, lastCol, text, St.base().wrap().fill(XlsxStyle.FINDING_FILL));
            p.height(r, XlsxPage.wrappedHeight(text, p.spanWidth(1, lastCol), 13));
            r++;
        }
        if (headline.isEmpty()) {
            p.note(r++, 1, lastCol, "Bu analizde bulgu üretecek veri yok.", true);
        }
        r++;

        // KPI table
        p.section(r++, d.split() ? "Önce / Sonra göstergeleri" : "Göstergeler (tüm yakalama)");
        int hc = r++;
        p.merge(hc, 1, hc, 3, "Gösterge", header());
        int periods = d.periodCount();
        for (int i = 0; i < periods; i++) {
            Period per = d.window.periods().get(i);
            String label = per.label() + (d.split() ? " (" + TrFormat.number(Math.max(0, per.seconds()), 0) + " sn)" : "");
            p.merge(hc, 4 + 2 * i, hc, 5 + 2 * i, label, periodFill(header(), i));
        }
        if (d.split()) {
            p.merge(hc, 8, hc, 9, "Fark (sonra − önce)", header());
            p.merge(hc, 10, hc, 11, "Oran (sonra/önce)", header());
        }
        p.height(hc, 30);
        for (Kpi k : kpis()) {
            p.merge(r, 1, r, 3, k.label, cell().wrap());
            for (int i = 0; i < periods; i++) {
                Object v = k.values[i];
                p.merge(r, 4 + 2 * i, r, 5 + 2 * i, v == null ? DASH : v, periodFill(cell().fmt(k.fmt), i)
                        .center());
            }
            if (d.split()) {
                Object a = k.values[0];
                Object b = k.values[1];
                boolean both = a instanceof Number && b instanceof Number;
                double x = both ? ((Number) a).doubleValue() : 0;
                double y = both ? ((Number) b).doubleValue() : 0;
                // the difference is in the row's own unit; the ratio is empty where the earlier value is zero
                p.merge(r, 8, r, 9, both ? (Object) (y - x) : null, cell().fmt(XlsxStyle.signed(k.fmt)).center());
                p.merge(r, 10, r, 11, both && x != 0 ? (Object) (y / x) : null, cell().fmt(XlsxStyle.RATIO).center());
            }
            p.height(r, XlsxPage.wrappedHeight(k.label, p.spanWidth(1, 3), 13));
            r++;
        }
        p.note(r++, 1, lastCol, "Kaynaklar: Zaman Çizelgesi (saniyelik toplamlar), Hop Analizi (eşleşmiş paket ve döngü ortalamaları). "
                + "Ortalamalar örnek ağırlıklıdır; hesaplar tam sayı toplamlarıyla yapılır.", false);
        r += 1;

        if (!others.isEmpty()) {
            p.put(r, 0, "Diğer gözlemler", St.base().size(11).bold());
            p.height(r, 16);
            r++;
            for (Finding f : others) {
                String text = "• " + f.text();
                p.merge(r, 1, r, lastCol, text, St.base().size(9).color(XlsxStyle.MUTED).wrap());
                p.height(r, XlsxPage.wrappedHeight(text, p.spanWidth(1, lastCol) * 1.1, 12));
                r++;
            }
            r++;
        }

        // charts
        boolean chartA = hopAFirst >= 0 && hopALast >= hopAFirst;
        boolean chartB = hopBFirst >= 0 && hopBLast >= hopBFirst;
        if (chartA || chartB) {
            int mid = chartA && chartB ? 1 + 2 * (wide / 2) : (chartA ? lastCol + 1 : 1);
            List<XlsxCharts.Series> a = new ArrayList<>();
            List<XlsxCharts.Series> b = new ArrayList<>();
            for (int i = 0; i < periods; i++) {
                String color = d.split() && i == 1 ? XlsxStyle.ORANGE : XlsxStyle.BLUE;
                if (chartA) {
                    a.add(new XlsxCharts.Series(hop.sheet, hopHeaderA, hopAMeanCol + i, hopAFirst, hopALast, periodLabel(i), color,
                            hopAMeanFmt[i]));
                }
                if (chartB) {
                    b.add(new XlsxCharts.Series(hop.sheet, hopHeaderB, hopBMeanCol + i, hopBFirst, hopBLast, periodLabel(i), color,
                            hopBMeanFmt[i]));
                }
            }
            if (chartA) {
                XlsxCharts.bars(p.sheet, new Box(1, r, mid, r + 17), "Komşu noktalar arası fark (ort. ms)",
                        hop.sheet, 0, hopAFirst, hopALast, a, "0.0", false, false, true);
            }
            if (chartB) {
                XlsxCharts.bars(p.sheet, new Box(chartA ? mid : 1, r, lastCol + 1, r + 17), "Her noktada ölçülen RTT (ort. ms)",
                        hop.sheet, 0, hopBFirst, hopBLast, b, "0.0", false, false, true);
            }
            r += 18;
        }
        if (d.timeline != null && tlFirst >= 0) {
            p.section(r++, "Zaman çizelgesi (" + d.zoneLabel + "): aynı eksen, üç ölçüm");
            int skip = Math.max(1, (int) Math.ceil(d.timeline.count / 12.0));
            Box wideBox;
            if (!tlHopMaxCols.isEmpty()) {
                List<XlsxCharts.Series> series = new ArrayList<>();
                for (int i = 0; i < tlHopMaxCols.size(); i++) {
                    series.add(new XlsxCharts.Series(timeline.sheet, 3, tlHopMaxCols.get(i), tlFirst, tlLast,
                            d.timeline.hopLabels.get(i) + " geçiş maks. ms", XlsxStyle.SERIES[i % XlsxStyle.SERIES.length],
                            tlHopMaxFmt.get(i)));
                }
                XlsxCharts.line(p.sheet, new Box(1, r, lastCol + 1, r + 14), "Komşu noktalar arası geçiş, saniyelik maksimum (ms)",
                        timeline.sheet, 0, tlFirst, tlLast, series, "0.0", skip, true, Plot.TIMELINE);
                r += 15;
            }
            XlsxCharts.line(p.sheet, new Box(1, r, lastCol + 1, r + 14),
                    d.firstPoint().code() + " noktasında TCP retransmission işareti / sn", timeline.sheet, 0, tlFirst, tlLast,
                    List.of(new XlsxCharts.Series(timeline.sheet, 3, tlRetransCol, tlFirst, tlLast, "retransmission işareti/sn",
                            XlsxStyle.RED, tlRetransFmt)), "0", skip, false, Plot.TIMELINE);
            r += 15;
            XlsxCharts.line(p.sheet, new Box(1, r, lastCol + 1, r + 14), d.firstPoint().code() + " noktasında paket hızı (paket/sn)",
                    timeline.sheet, 0, tlFirst, tlLast,
                    List.of(new XlsxCharts.Series(timeline.sheet, 3, tlPacketsCol, tlFirst, tlLast, "paket/sn", XlsxStyle.BLUE,
                            tlPacketsFmt)),
                    "#,##0", skip, false, Plot.TIMELINE);
            r += 15;
        } else if (d.window.mode() != WindowMode.NO_DATA) {
            p.note(r++, 1, lastCol, d.absent.getOrDefault("timeline", "Zaman çizelgesi verisi yok."), true);
        }
    }

    private static final class Kpi {
        final String label;
        final Object[] values;
        final String fmt;

        Kpi(String label, Object[] values, String fmt) {
            this.label = label;
            this.values = values;
            this.fmt = fmt;
        }

        boolean hasData() {
            for (Object v : values) {
                if (v != null) {
                    return true;
                }
            }
            return false;
        }
    }

    private List<Kpi> kpis() {
        List<Kpi> out = new ArrayList<>();
        int n = d.periodCount();
        Pt first = d.firstPoint();
        Pt last = d.lastPoint();
        if (first == null) {
            return out;
        }
        PointRtt rtt0 = d.pointRtt.isEmpty() ? null : d.pointRtt.get(0);
        if (rtt0 != null) {
            Object[] cycles = new Object[n];
            Object[] stalled = new Object[n];
            Object[] mean = new Object[n];
            Object[] max = new Object[n];
            for (int i = 0; i < n; i++) {
                Stat s = rtt0.byPeriod()[i];
                cycles[i] = s.has() ? (Object) s.n() : null;
                stalled[i] = s.has() ? (Object) rtt0.stalled()[i] : null;
                mean[i] = s.meanMs();
                max[i] = s.maxMs();
            }
            add(out, new Kpi("Eşleşmiş istek/cevap döngüsü (" + first.code() + ")", cycles, XlsxStyle.INT));
            add(out, new Kpi("Bekleme eşiğini (" + TrFormat.ms(d.stallMs) + " ms) aşan döngü (" + first.code() + ")", stalled,
                    XlsxStyle.INT));
            add(out, new Kpi(first.code() + " RTT, ort. ms", mean, XlsxStyle.ms(java.util.Arrays.asList(mean))));
            add(out, new Kpi(first.code() + " RTT, maks. ms", max, XlsxStyle.ms(java.util.Arrays.asList(max))));
        }
        if (d.pointRtt.size() > 1) {
            PointRtt rttN = d.pointRtt.get(d.pointRtt.size() - 1);
            Object[] mean = new Object[n];
            for (int i = 0; i < n; i++) {
                mean[i] = rttN.byPeriod()[i].meanMs();
            }
            add(out, new Kpi(last.code() + " RTT, ort. ms", mean, XlsxStyle.ms(java.util.Arrays.asList(mean))));
        }
        if (!d.pointPackets.isEmpty()) {
            PointPackets pp = d.pointPackets.get(0);
            Object[] rate = new Object[n];
            Object[] retrans = new Object[n];
            for (int i = 0; i < n; i++) {
                double seconds = Math.max(1e-9, d.window.periods().get(i).seconds());
                rate[i] = pp.packets()[i] == 0 ? null : (Object) (pp.packets()[i] / seconds);
                retrans[i] = pp.packets()[i] == 0 ? null : (Object) pp.retransmissions()[i];
            }
            add(out, new Kpi(first.code() + " paket hızı, ort. (paket/sn)", rate, XlsxStyle.RATE));
            add(out, new Kpi("TCP retransmission işareti (" + first.code() + ")", retrans, XlsxStyle.INT));
        }
        for (Seg s : forwardSegments()) {
            Stat[] stats = d.hopTransit.get(s.id());
            if (stats == null) {
                continue;
            }
            Object[] mean = new Object[n];
            Object[] max = new Object[n];
            for (int i = 0; i < n; i++) {
                mean[i] = stats[i].meanMs();
                max[i] = stats[i].maxMs();
            }
            String suffix = s.deviceHop() ? " (cihaz içi)" : "";
            add(out, new Kpi(s.label() + suffix + ": geçiş ort. ms", mean, XlsxStyle.ms(java.util.Arrays.asList(mean))));
            add(out, new Kpi(s.label() + suffix + ": geçiş maks. ms", max, XlsxStyle.ms(java.util.Arrays.asList(max))));
        }
        if (!d.lossByKind.isEmpty()) {
            Object[] loss = new Object[n];
            for (int i = 0; i < n; i++) {
                long total = 0;
                for (long[] v : d.lossByKind.values()) {
                    total += v[i];
                }
                loss[i] = total;
            }
            add(out, new Kpi("Desteklenen kayıp olayı (toplam)", loss, XlsxStyle.INT));
        }
        if (!d.matchRows.isEmpty()) {
            Object[] share = new Object[n];
            for (int i = 0; i < n; i++) {
                long obs = 0;
                long unmatched = 0;
                for (MatchRow m : d.matchRows) {
                    if ("forward".equals(m.direction())) {
                        obs += m.observations()[i];
                        unmatched += m.dropped()[i] + m.captureMisses()[i] + m.unknown()[i];
                    }
                }
                share[i] = obs == 0 ? null : (Object) ((double) unmatched / obs);
            }
            add(out, new Kpi("Eşleşmeyen gözlem oranı, ileri yön", share, XlsxStyle.PCT));
        }
        return out;
    }

    private static void add(List<Kpi> out, Kpi k) {
        if (k.hasData()) {
            out.add(k);
        }
    }
}
