package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.*;
import com.securityexpert.nexus.ui2.packetanalysis.export.XlsxCharts.Box;
import com.securityexpert.nexus.ui2.packetanalysis.export.XlsxCharts.Plot;
import com.securityexpert.nexus.ui2.packetanalysis.export.XlsxStyle.St;
import java.util.ArrayList;
import java.util.List;

/**
 * The Özet (summary) sheet of the expert workbook: the case title, the chain of recording points with its hop
 * markers, the headline findings, the KPI table and the charts that read the Hop Analizi and Zaman Çizelgesi sheets.
 * Written last, after every data sheet, so the chart sources it reads from {@link ExpertWorkbook} are already set.
 */
final class ExpertWorkbookSummary {
    private final ExpertWorkbook w;
    private final ExpertReportData d;
    private final XlsxPage summary;

    ExpertWorkbookSummary(ExpertWorkbook w, ExpertReportData d, XlsxPage summary) {
        this.w = w;
        this.d = d;
        this.summary = summary;
    }

    // ------------------------------------------------------------------------------------------------ Özet

    void write() {
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
                "Zaman dilimi " + d.zoneLabel + ". " + d.points.size() + " kayıt noktası. Analiz penceresi: " + w.rangeText()
                        + ". " + w.windowSentence() + " Rapor yalnız başlık ve zaman alanlarından üretilmiştir; paket içeriği yoktur.",
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
        p.merge(hc, 1, hc, 3, "Gösterge", ExpertWorkbook.header());
        int periods = d.periodCount();
        for (int i = 0; i < periods; i++) {
            Period per = d.window.periods().get(i);
            String label = per.label() + (d.split() ? " (" + TrFormat.number(Math.max(0, per.seconds()), 0) + " sn)" : "");
            p.merge(hc, 4 + 2 * i, hc, 5 + 2 * i, label, w.periodFill(ExpertWorkbook.header(), i));
        }
        if (d.split()) {
            p.merge(hc, 8, hc, 9, "Fark (sonra − önce)", ExpertWorkbook.header());
            p.merge(hc, 10, hc, 11, "Oran (sonra/önce)", ExpertWorkbook.header());
        }
        p.height(hc, 30);
        for (Kpi k : kpis()) {
            p.merge(r, 1, r, 3, k.label, ExpertWorkbook.cell().wrap());
            for (int i = 0; i < periods; i++) {
                Object v = k.values[i];
                p.merge(r, 4 + 2 * i, r, 5 + 2 * i, v == null ? ExpertWorkbook.DASH : v, w.periodFill(ExpertWorkbook.cell().fmt(k.fmt), i)
                        .center());
            }
            if (d.split()) {
                Object a = k.values[0];
                Object b = k.values[1];
                boolean both = a instanceof Number && b instanceof Number;
                double x = both ? ((Number) a).doubleValue() : 0;
                double y = both ? ((Number) b).doubleValue() : 0;
                // the difference is in the row's own unit; the ratio is empty where the earlier value is zero
                p.merge(r, 8, r, 9, both ? (Object) (y - x) : null, ExpertWorkbook.cell().fmt(XlsxStyle.signed(k.fmt)).center());
                p.merge(r, 10, r, 11, both && x != 0 ? (Object) (y / x) : null, ExpertWorkbook.cell().fmt(XlsxStyle.RATIO).center());
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
        boolean chartA = w.hopAFirst >= 0 && w.hopALast >= w.hopAFirst;
        boolean chartB = w.hopBFirst >= 0 && w.hopBLast >= w.hopBFirst;
        if (chartA || chartB) {
            int mid = chartA && chartB ? 1 + 2 * (wide / 2) : (chartA ? lastCol + 1 : 1);
            List<XlsxCharts.Series> a = new ArrayList<>();
            List<XlsxCharts.Series> b = new ArrayList<>();
            for (int i = 0; i < periods; i++) {
                String color = d.split() && i == 1 ? XlsxStyle.ORANGE : XlsxStyle.BLUE;
                if (chartA) {
                    a.add(new XlsxCharts.Series(w.hop.sheet, w.hopHeaderA, w.hopAMeanCol + i, w.hopAFirst, w.hopALast, w.periodLabel(i), color,
                            w.hopAMeanFmt[i]));
                }
                if (chartB) {
                    b.add(new XlsxCharts.Series(w.hop.sheet, w.hopHeaderB, w.hopBMeanCol + i, w.hopBFirst, w.hopBLast, w.periodLabel(i), color,
                            w.hopBMeanFmt[i]));
                }
            }
            if (chartA) {
                XlsxCharts.bars(p.sheet, new Box(1, r, mid, r + 17), "Komşu noktalar arası fark (ort. ms)",
                        w.hop.sheet, 0, w.hopAFirst, w.hopALast, a, "0.0", false, false, true);
            }
            if (chartB) {
                XlsxCharts.bars(p.sheet, new Box(chartA ? mid : 1, r, lastCol + 1, r + 17), "Her noktada ölçülen RTT (ort. ms)",
                        w.hop.sheet, 0, w.hopBFirst, w.hopBLast, b, "0.0", false, false, true);
            }
            r += 18;
        }
        if (d.timeline != null && w.tlFirst >= 0) {
            p.section(r++, "Zaman çizelgesi (" + d.zoneLabel + "): aynı eksen, üç ölçüm");
            int skip = Math.max(1, (int) Math.ceil(d.timeline.count / 12.0));
            Box wideBox;
            if (!w.tlHopMaxCols.isEmpty()) {
                List<XlsxCharts.Series> series = new ArrayList<>();
                for (int i = 0; i < w.tlHopMaxCols.size(); i++) {
                    series.add(new XlsxCharts.Series(w.timeline.sheet, 3, w.tlHopMaxCols.get(i), w.tlFirst, w.tlLast,
                            d.timeline.hopLabels.get(i) + " geçiş maks. ms", XlsxStyle.SERIES[i % XlsxStyle.SERIES.length],
                            w.tlHopMaxFmt.get(i)));
                }
                XlsxCharts.line(p.sheet, new Box(1, r, lastCol + 1, r + 14), "Komşu noktalar arası geçiş, saniyelik maksimum (ms)",
                        w.timeline.sheet, 0, w.tlFirst, w.tlLast, series, "0.0", skip, true, Plot.TIMELINE);
                r += 15;
            }
            XlsxCharts.line(p.sheet, new Box(1, r, lastCol + 1, r + 14),
                    d.firstPoint().code() + " noktasında TCP retransmission işareti / sn", w.timeline.sheet, 0, w.tlFirst, w.tlLast,
                    List.of(new XlsxCharts.Series(w.timeline.sheet, 3, w.tlRetransCol, w.tlFirst, w.tlLast, "retransmission işareti/sn",
                            XlsxStyle.RED, w.tlRetransFmt)), "0", skip, false, Plot.TIMELINE);
            r += 15;
            XlsxCharts.line(p.sheet, new Box(1, r, lastCol + 1, r + 14), d.firstPoint().code() + " noktasında paket hızı (paket/sn)",
                    w.timeline.sheet, 0, w.tlFirst, w.tlLast,
                    List.of(new XlsxCharts.Series(w.timeline.sheet, 3, w.tlPacketsCol, w.tlFirst, w.tlLast, "paket/sn", XlsxStyle.BLUE,
                            w.tlPacketsFmt)),
                    "#,##0", skip, false, Plot.TIMELINE);
            r += 15;
        } else if (d.window.mode() != WindowMode.NO_DATA) {
            p.note(r++, 1, lastCol, d.absent.getOrDefault("timeline", "Zaman çizelgesi verisi yok."), true);
        }
    }

    /** "case title: 5 kayıt noktası, 14 Kasım 2023": the case's own name, its point count and the capture date. */
    private String summaryTitle() {
        String name = d.caseTitle == null ? "Paket analizi" : d.caseTitle;
        String date = d.window.rangeStart() == null ? "" : ", " + TrFormat.dateOf(d.window.rangeStart(), d.zone);
        return name + ": " + d.points.size() + " kayıt noktası" + date;
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
                // one box, one colour: a delay inside the device outranks a clean w.hop next to it
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
        p.note(row + 1, 1, lastCol, "Hop işaretleri: " + w.markerWindowText() + ". Yalnız Yöntem ve Veri sayfasındaki sabit "
                + "eşiklerle üretilir; işaretsiz hop belirsizdir veya ölçümü yoktur, gerekçe Hop Analizi sayfasındadır.", false);
        return true;
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
        for (Seg s : w.forwardSegments()) {
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
