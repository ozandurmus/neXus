package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.*;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.TelemetryFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The numbered findings of the summary sheet. Each one is a fixed Turkish template filled with computed values, and a
 * template is emitted only when the data it needs exists. There is no free text: a finding states what was measured,
 * before and after the compared window. The only classification is the hop marker ({@code hop-mark}), which repeats the
 * words the approved rule put on each hop ({@link HopVerdict}, PO decision 2026-10-18); no other template applies a
 * threshold of its own.
 */
final class ExpertFindings {
    static final Map<String, String> KIND_TR = Map.of(
            "recovered_loss", "kurtarılmış kayıp",
            "impactful_loss", "etkili kayıp",
            "unrecovered_loss", "kurtarılamayan kayıp",
            "handshake_blocked", "engellenen el sıkışma",
            "confirmed_device_drop", "doğrulanmış cihaz düşürmesi");
    static final Map<String, String> METRIC_TR = Map.of(
            "loss_percent", "ağ kaybı yüzdesi",
            "retrans_percent", "retransmission yüzdesi",
            "latency_p95_ms", "geçiş süresi p95",
            "failed_handshakes", "başarısız el sıkışma",
            "resets", "RST paketi",
            "zero_windows", "sıfır pencere");
    static final Map<String, String> STATUS_TR = Map.of(
            "detected", "tespit edildi",
            "none", "yok",
            "unknown", "belirlenemedi");
    static final Map<String, String> SCOPE_TR = Map.of(
            "network_segment", "ağ segmenti",
            "host_capture_boundary", "host yakalama sınırı",
            "capture_signal", "yakalama belirtisi");

    /**
     * The findings the summary sheet states in its first block, in this fixed order; every other finding goes to the
     * "other observations" block in build order.
     */
    static final List<String> SUMMARY_IDS = List.of("window", "hop-delta", "loss", "retransmission", "rtt-first");

    private ExpertFindings() {
    }

    static List<Finding> build(ExpertReportData d) {
        List<Finding> out = new ArrayList<>();
        add(out, "window", window(d));
        if (d.window.mode() == WindowMode.NO_DATA) {
            add(out, "host-align", hostAlignment(d));
            return out;
        }
        add(out, "hop-delta", hopDelta(d));
        add(out, "hop-mark", hopMarks(d));
        add(out, "hop-forward", hops(d, "forward"));
        add(out, "hop-reverse", hops(d, "reverse"));
        add(out, "rtt", rtt(d));
        add(out, "rtt-first", rttFirst(d));
        add(out, "cycles", cycles(d));
        add(out, "match", match(d));
        add(out, "loss", loss(d));
        add(out, "retransmission", retransmission(d));
        add(out, "rate", rate(d));
        add(out, "clock", clock(d));
        add(out, "longest", longest(d));
        add(out, "ladder", ladder(d));
        hostFindings(d, out);
        return out;
    }

    private static void add(List<Finding> out, String id, String text) {
        if (text != null) {
            out.add(new Finding(id, text));
        }
    }

    // ---------------------------------------------------------------------------------------------- templates

    private static String window(ExpertReportData d) {
        Window w = d.window;
        switch (w.mode()) {
            case ONSET -> {
                OnsetRow o = w.onset();
                String metric = METRIC_TR.getOrDefault(o.metric(), o.metric());
                String value = o.value() == null ? "" : ", değer " + metricValue(o.metric(), o.value())
                        + (o.baseline() == null ? "" : " (taban " + metricValue(o.metric(), o.baseline()) + ")");
                String when = d.utcZone() ? o.localTime() : o.localTime() + " / " + o.utcTime();
                return "Motor ilk olay başlangıcını " + when + " olarak buldu: " + o.segment()
                        + ", ölçüt: " + metric + value + ". Önce/sonra karşılaştırması bu andan bölündü; "
                        + periodSpans(d) + ".";
            }
            case EXPLICIT -> {
                return "Önce/sonra karşılaştırması verilen pencereye göre yapıldı: "
                        + TrFormat.dateTime(w.split(), d.zone) + " – " + TrFormat.dateTime(w.afterEnd(), d.zone) + "; "
                        + periodSpans(d) + ".";
            }
            case NONE -> {
                String status = w.onsetStatus() == null ? "" : STATUS_TR.getOrDefault(w.onsetStatus(), w.onsetStatus());
                return "Motor bu yakalamada bir olay penceresi bulamadı" + (status.isEmpty() ? "" : " (olay başlangıcı durumu: " + status + ")")
                        + "; önce/sonra karşılaştırması yoktur, rakamlar tüm yakalama içindir ("
                        + TrFormat.number(w.periods().get(0).seconds(), 1) + " sn).";
            }
            default -> {
                return w.note();
            }
        }
    }

    private static String periodSpans(ExpertReportData d) {
        List<String> parts = new ArrayList<>();
        for (Period p : d.window.periods()) {
            parts.add(p.label() + " " + TrFormat.number(Math.max(0, p.seconds()), 1) + " sn");
        }
        return String.join(", ", parts);
    }

    private static String hops(ExpertReportData d, String direction) {
        List<String> items = new ArrayList<>();
        for (Seg s : d.segments) {
            if (!direction.equals(s.direction())) {
                continue;
            }
            Stat[] stats = d.hopTransit.get(s.id());
            if (stats == null || !any(stats)) {
                continue;
            }
            items.add(s.label() + (s.deviceHop() ? " (cihaz içi)" : "") + ": ort. "
                    + arrow(stats, Stat::meanMs) + " ms, maks. " + arrow(stats, Stat::maxMs) + " ms");
        }
        if (items.isEmpty()) {
            return null;
        }
        return (direction.equals("forward") ? "İleri" : "Geri") + " yönde komşu kayıt noktaları arasındaki geçiş süresi, "
                + "aynı paketin iki noktadaki zaman farkı (" + head(d) + "): " + String.join("; ", items) + ".";
    }

    /**
     * Where the before to after difference opens: the hop component (RX or TX of one adjacent point pair) whose mean
     * added time grew most, with the largest change among the other hops for comparison. Numbers only: no threshold
     * decides what counts as large, and the sentence names no cause.
     */
    private static String hopDelta(ExpertReportData d) {
        if (!d.split()) {
            return null;
        }
        CycleHop best = null;
        double bestDelta = 0;
        List<Object[]> changes = new ArrayList<>();
        for (CycleHop ch : d.cycleHops) {
            Double before = ch.byPeriod()[0].meanMs();
            Double after = ch.byPeriod()[1].meanMs();
            if (before == null || after == null) {
                continue;
            }
            double delta = after - before;
            changes.add(new Object[] {ch, delta});
            if (best == null || delta > bestDelta) {
                best = ch;
                bestDelta = delta;
            }
        }
        if (best == null) {
            return null;
        }
        Object[] other = null;
        for (Object[] c : changes) {
            CycleHop ch = (CycleHop) c[0];
            if (ch.hopId().equals(best.hopId())) {
                continue;
            }
            if (other == null || Math.abs((double) c[1]) > Math.abs((double) other[1])) {
                other = c;
            }
        }
        String otherText = other == null ? "" : "; diğer hop'larda en büyük değişim " + signedMs((double) other[1]) + " ms";
        if (bestDelta > 0) {
            return "Önce→sonra en büyük artış " + best.label() + " " + best.component() + " bileşeninde: " + signedMs(bestDelta)
                    + " ms" + otherText + ".";
        }
        return "Önce→sonra hiçbir hop bileşeninin ortalaması artmadı; en büyük değişim " + best.label() + " " + best.component()
                + " bileşeninde: " + signedMs(bestDelta) + " ms" + otherText + ".";
    }

    /**
     * The hops the approved rule marked, by word, with the window the words belong to; emitted only when at least one hop
     * carries a word. The reasons are in the hop sheet.
     */
    private static String hopMarks(ExpertReportData d) {
        if (!d.split()) {
            return null;
        }
        List<String> delayed = new ArrayList<>();
        List<String> clean = new ArrayList<>();
        List<String> unmarked = new ArrayList<>();
        for (HopMark m : d.hopMarks) {
            String label = m.label() + (m.deviceHop() ? " (cihaz içi)" : "");
            switch (m.mark()) {
                case DELAY -> delayed.add(label);
                case CLEAN -> clean.add(label);
                default -> unmarked.add(label);
            }
        }
        if (delayed.isEmpty() && clean.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (!delayed.isEmpty()) {
            parts.add("gecikme burada: " + String.join(", ", delayed));
        }
        if (!clean.isEmpty()) {
            parts.add("temiz: " + String.join(", ", clean));
        }
        if (!unmarked.isEmpty()) {
            parts.add("işaretsiz (belirsiz veya ölçüm yok): " + String.join(", ", unmarked));
        }
        return "Hop işaretleri (Yöntem ve Veri sayfasındaki sabit eşiklerle; önce " + TrFormat.number(Math.max(0,
                d.window.periods().get(0).seconds()), 1) + " sn / sonra " + TrFormat.number(Math.max(0,
                d.window.periods().get(1).seconds()), 1) + " sn): " + String.join("; ", parts)
                + ". Bir işaret kök neden değildir; \"temiz\" yalnız gecikme içindir, gerekçeler Hop Analizi sayfasındadır.";
    }

    private static String signedMs(double v) {
        String text = TrFormat.number(Math.abs(v), 3);
        if (text.matches("0(,0+)?")) {
            return text;
        }
        return (v < 0 ? "-" : "+") + text;
    }

    /** The request to response time at the first capture point: mean and maximum, per period. */
    private static String rttFirst(ExpertReportData d) {
        if (d.pointRtt.isEmpty() || !any(d.pointRtt.get(0).byPeriod())) {
            return null;
        }
        PointRtt r = d.pointRtt.get(0);
        Pt p = d.point(r.pointId());
        return "İlk kayıt noktasında (" + p.code() + " " + p.label() + ") istek→cevap süresi (RTT), ortalama (" + head(d) + "): "
                + arrow(r.byPeriod(), Stat::meanMs) + " ms; maksimum: " + arrow(r.byPeriod(), Stat::maxMs) + " ms.";
    }

    private static String rtt(ExpertReportData d) {
        List<String> items = new ArrayList<>();
        for (PointRtt r : d.pointRtt) {
            if (!any(r.byPeriod())) {
                continue;
            }
            Pt p = d.point(r.pointId());
            items.add(p.code() + " " + p.label() + ": " + arrow(r.byPeriod(), Stat::meanMs) + " ms");
        }
        if (items.isEmpty()) {
            return null;
        }
        return "Her kayıt noktasında ölçülen istek→cevap süresi (RTT), ortalama (" + head(d) + "): "
                + String.join("; ", items) + ".";
    }

    private static String cycles(ExpertReportData d) {
        if (d.pointRtt.isEmpty() || !any(d.pointRtt.get(0).byPeriod())) {
            return null;
        }
        PointRtt r = d.pointRtt.get(0);
        Pt p = d.point(r.pointId());
        String counts = join(d, i -> TrFormat.integer(r.byPeriod()[i].n()));
        String stalled = join(d, i -> TrFormat.integer(r.stalled()[i]));
        return "İlk kayıt noktasında (" + p.code() + " " + p.label() + ") ölçülen istek/cevap döngüsü sayısı (" + head(d)
                + "): " + counts + "; bekleme eşiğini (" + TrFormat.ms(d.stallMs) + " ms) aşan döngü: " + stalled + ".";
    }

    private static String match(ExpertReportData d) {
        if (d.matchRows.isEmpty()) {
            return null;
        }
        int n = d.periodCount();
        long[] obs = new long[n];
        long[] dropped = new long[n];
        long[] miss = new long[n];
        long[] unknown = new long[n];
        for (MatchRow r : d.matchRows) {
            if (!"forward".equals(r.direction())) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                obs[i] += r.observations()[i];
                dropped[i] += r.dropped()[i];
                miss[i] += r.captureMisses()[i];
                unknown[i] += r.unknown()[i];
            }
        }
        boolean data = false;
        List<String> parts = new ArrayList<>();
        for (Period p : d.window.periods()) {
            int i = p.index();
            if (obs[i] == 0) {
                parts.add(p.label() + ": gözlem yok");
                continue;
            }
            data = true;
            long unmatched = dropped[i] + miss[i] + unknown[i];
            parts.add(p.label() + ": " + TrFormat.integer(obs[i]) + " gözlemin " + TrFormat.integer(unmatched) + " tanesi ("
                    + TrFormat.percent(100.0 * unmatched / obs[i]) + ") karşı noktada eşleşmedi; bunlardan "
                    + TrFormat.integer(dropped[i]) + " desteklenen kayıp, " + TrFormat.integer(miss[i])
                    + " yakalama kaçırması, " + TrFormat.integer(unknown[i]) + " belirsiz");
        }
        if (!data) {
            return null;
        }
        return "İleri yönde komşu nokta çiftleri arasında eşleşme: " + String.join("; ", parts) + ".";
    }

    private static String loss(ExpertReportData d) {
        if (d.lossByKind.isEmpty()) {
            return null;
        }
        int n = d.periodCount();
        long[] total = new long[n];
        List<String> kinds = new ArrayList<>();
        for (Map.Entry<String, long[]> e : d.lossByKind.entrySet()) {
            boolean seen = false;
            for (int i = 0; i < n; i++) {
                total[i] += e.getValue()[i];
                seen |= e.getValue()[i] > 0;
            }
            if (seen) {
                kinds.add(KIND_TR.get(e.getKey()) + " " + join(d, i -> TrFormat.integer(e.getValue()[i])));
            }
        }
        return "Motorun desteklediği kayıp olayları (" + head(d) + "): toplam " + join(d, i -> TrFormat.integer(total[i]))
                + (kinds.isEmpty() ? "" : "; " + String.join("; ", kinds)) + ".";
    }

    private static String retransmission(ExpertReportData d) {
        Pt first = d.firstPoint();
        if (first == null || d.pointPackets.isEmpty() || d.timeline == null) {
            return null;
        }
        PointPackets pp = d.pointPackets.get(0);
        long all = 0;
        for (long v : pp.packets()) {
            all += v;
        }
        if (all == 0) {
            return null;
        }
        String counts = join(d, i -> TrFormat.integer(pp.retransmissions()[i]));
        String rates = join(d, i -> TrFormat.number(pp.retransmissions()[i] / Math.max(1e-9, d.window.periods().get(i).seconds()), 2));
        Timeline t = d.timeline;
        int peak = -1;
        for (int i = 0; i < t.count; i++) {
            if (t.retransmissions[i] > 0 && (peak < 0 || t.retransmissions[i] > t.retransmissions[peak])) {
                peak = i;
            }
        }
        String peakText = peak < 0 ? "" : " En yoğun saniye " + t.local[peak] + " (" + d.zoneLabel + "): "
                + TrFormat.integer(t.retransmissions[peak]) + ".";
        return "İlk kayıt noktasında (" + first.code() + " " + first.label() + ") TCP retransmission işareti (" + head(d)
                + "): " + counts + "; saniyede ortalama " + rates + "." + peakText;
    }

    private static String rate(ExpertReportData d) {
        Pt first = d.firstPoint();
        if (first == null || d.pointPackets.isEmpty()) {
            return null;
        }
        PointPackets pp = d.pointPackets.get(0);
        long all = 0;
        for (long v : pp.packets()) {
            all += v;
        }
        if (all == 0) {
            return null;
        }
        return "İlk kayıt noktasında paket hızı, ortalama (" + head(d) + "): "
                + join(d, i -> TrFormat.number(pp.packets()[i] / Math.max(1e-9, d.window.periods().get(i).seconds()), 1))
                + " paket/sn.";
    }

    private static String clock(ExpertReportData d) {
        Double max = null;
        for (Seg s : d.segments) {
            if (s.uncertaintyMs() != null && (max == null || s.uncertaintyMs() > max)) {
                max = s.uncertaintyMs();
            }
        }
        if (max == null) {
            return null;
        }
        return "Komşu nokta çiftleri arasındaki saat belirsizliği en çok ±" + TrFormat.ms(max)
                + " ms'dir; geçiş süreleri bu belirsizlik içinde yorumlanmalıdır.";
    }

    private static String longest(ExpertReportData d) {
        if (d.topCycles.isEmpty()) {
            return null;
        }
        TopCycle c = d.topCycles.get(0);
        Pt first = d.firstPoint();
        return "En uzun istek/cevap döngüsü: " + c.flowLabel() + ", " + TrFormat.epochToLocal(c.start(), d.zone, false)
                + " (" + d.zoneLabel + ") saatinde, ilk kayıt noktasında (" + first.code() + ") " + TrFormat.ms(c.totalMs())
                + " ms; hop kırılımı Örnek Olaylar › A sayfasındadır.";
    }

    private static String ladder(ExpertReportData d) {
        Ladder l = d.ladder;
        if (l == null) {
            return null;
        }
        Pt first = d.firstPoint();
        Pt last = d.lastPoint();
        return "En çok retransmission görülen akışta (" + l.flowLabel() + ") " + l.description() + ": ilk kayıt noktasında ("
                + first.code() + ") kopyalar arası toplam " + TrFormat.ms(l.firstPointSpanMs()) + " ms, son kayıt noktasında ("
                + last.code() + ") " + TrFormat.ms(l.lastPointSpanMs()) + " ms (Örnek Olaylar › B).";
    }

    // ---------------------------------------------------------------------------------------------- host telemetry

    private static String hostAlignment(ExpertReportData d) {
        HostData h = d.host;
        if (h == null || h.files.isEmpty()) {
            return null;
        }
        List<String> formats = new ArrayList<>();
        List<String> zones = new ArrayList<>();
        int recognised = 0;
        int placed = 0;
        for (HostFile f : h.files) {
            if (f.format() != TelemetryFormat.UNKNOWN) {
                recognised++;
                if (!formats.contains(f.format().label())) {
                    formats.add(f.format().label());
                }
                zones.add(f.code() + ": " + f.zoneText() + (f.offsetSeconds() != 0 ? ", ek saat farkı "
                        + (f.offsetSeconds() > 0 ? "+" : "") + f.offsetSeconds() + " sn" : ""));
            }
            if (f.placed()) {
                placed++;
            }
        }
        int other = h.files.size() - recognised;
        return "Sunucu verisi: " + h.files.size() + " dosya eklendi; " + recognised + " dosya tanındı"
                + (formats.isEmpty() ? "" : " (" + String.join(", ", formats) + ")") + ", " + other
                + " dosya tanınmadı. Saat hizalaması: " + (zones.isEmpty() ? "yok" : String.join("; ", zones))
                + ". Yakalama aralığına yerleştirilen dosya: " + placed + " (Sunucu Korelasyonu › A).";
    }

    private static void hostFindings(ExpertReportData d, List<Finding> out) {
        HostData h = d.host;
        if (h == null) {
            return;
        }
        add(out, "host-align", hostAlignment(d));
        Timeline t = d.timeline;
        if (t == null || h.columns.isEmpty()) {
            return;
        }
        for (String key : List.of("vmstat.r", "sar.runq")) {
            for (HostColumn c : columns(h, key)) {
                add(out, "host-runq", level(d, c));
            }
        }
        for (String key : List.of("vmstat.sy", "sar.cpu.sys")) {
            for (HostColumn c : columns(h, key)) {
                add(out, "host-cpu", level(d, c));
            }
        }
        for (HostColumn c : columns(h, "sar.retrans/s")) {
            add(out, "host-retrans", level(d, c));
        }
        List<HostColumn> qdisc = new ArrayList<>();
        boolean qdiscRose = false;
        for (HostColumn c : h.columns) {
            if (c.key.startsWith("qdisc.dropped|")) {
                qdisc.add(c);
                qdiscRose |= firstPositive(c) >= 0;
            }
        }
        for (HostColumn c : qdisc) {
            if (firstPositive(c) >= 0) {
                add(out, "host-qdisc", increase(d, c));
            }
        }
        if (!qdisc.isEmpty() && !qdiscRose) {
            List<String> titles = new ArrayList<>();
            for (HostColumn c : qdisc) {
                titles.add(c.title);
            }
            add(out, "host-qdisc", "Sunucuda qdisc drop sayaçlarında yakalama aralığında artış yok (" + String.join(", ", titles) + ").");
        }
        for (HostColumn c : h.columns) {
            if (c.key.startsWith("softnet.")) {
                add(out, "host-softnet", increase(d, c));
            }
        }
        for (HostColumn c : columns(h, "pidstat.rows")) {
            add(out, "host-pidstat", pidstat(d, c, columns(h, "pidstat.rows.delta").stream()
                    .filter(x -> x.fileCode.equals(c.fileCode)).findFirst().orElse(null)));
        }
        for (HostColumn c : columns(h, "syslog.count")) {
            add(out, "host-syslog", syslog(d, c));
        }
    }

    private static List<HostColumn> columns(HostData h, String key) {
        List<HostColumn> out = new ArrayList<>();
        for (HostColumn c : h.columns) {
            if (c.key.equals(key)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Per-period figures of one column: the mean of its filled buckets, or the sum for counters and counts. */
    private static Double[] byPeriod(ExpertReportData d, HostColumn c, boolean sum) {
        Timeline t = d.timeline;
        int n = d.periodCount();
        double[] total = new double[n];
        int[] count = new int[n];
        for (int i = 0; i < t.count; i++) {
            int p = t.period[i];
            if (p >= 0 && p < n && c.values[i] != null) {
                total[p] += c.values[i];
                count[p]++;
            }
        }
        Double[] out = new Double[n];
        for (int p = 0; p < n; p++) {
            out[p] = count[p] == 0 ? null : (sum ? total[p] : total[p] / count[p]);
        }
        return out;
    }

    private static int maxIndex(HostColumn c) {
        int best = -1;
        for (int i = 0; i < c.values.length; i++) {
            if (c.values[i] != null && (best < 0 || c.values[i] > c.values[best])) {
                best = i;
            }
        }
        return best;
    }

    private static int firstPositive(HostColumn c) {
        for (int i = 0; i < c.values.length; i++) {
            if (c.values[i] != null && c.values[i] > 0) {
                return i;
            }
        }
        return -1;
    }

    private static String arrowOf(Double[] values, java.util.function.Function<Double, String> format) {
        List<String> parts = new ArrayList<>();
        for (Double v : values) {
            parts.add(v == null ? "–" : format.apply(v));
        }
        return String.join(" → ", parts);
    }

    private static String numberText(double v) {
        return TrFormat.number(v, v == Math.rint(v) ? 0 : 2);
    }

    private static String meanText(double v) {
        return TrFormat.number(v, 1);
    }

    private static String clockOf(ExpertReportData d, int bucket) {
        Timeline t = d.timeline;
        return t.local[bucket] + " (" + d.zoneLabel + ")" + (d.zoneLabel.equals("UTC") ? "" : " / " + t.utc[bucket] + " UTC");
    }

    private static String level(ExpertReportData d, HostColumn c) {
        Double[] means = byPeriod(d, c, false);
        int peak = maxIndex(c);
        if (peak < 0) {
            return null;
        }
        return "Sunucuda " + c.title + " ortalaması (" + head(d) + "): " + arrowOf(means, ExpertFindings::meanText)
                + "; zirve " + numberText(c.values[peak]) + ", saat " + clockOf(d, peak) + ".";
    }

    private static String increase(ExpertReportData d, HostColumn c) {
        Double[] totals = byPeriod(d, c, true);
        int first = firstPositive(c);
        return "Sunucuda " + c.title + " toplam artışı (" + head(d) + "): " + arrowOf(totals, ExpertFindings::numberText)
                + (first < 0 ? "; aralıkta artış yok." : "; ilk artış saat " + clockOf(d, first) + ".");
    }

    private static String pidstat(ExpertReportData d, HostColumn rows, HostColumn delta) {
        Double[] means = byPeriod(d, rows, false);
        String text = "Sunucuda " + rows.title + " ortalaması (" + head(d) + "): " + arrowOf(means, ExpertFindings::meanText);
        if (delta != null) {
            int peak = maxIndex(delta);
            if (peak >= 0) {
                text += "; önceki örneğe göre en büyük artış " + (delta.values[peak] > 0 ? "+" : "") + numberText(delta.values[peak])
                        + ", saat " + clockOf(d, peak);
            }
        }
        return text + ".";
    }

    private static String syslog(ExpertReportData d, HostColumn c) {
        Timeline t = d.timeline;
        Double[] perSecond = byPeriod(d, c, true);
        Double[] totals = new Double[perSecond.length];
        for (int i = 0; i < totals.length; i++) {
            totals[i] = perSecond[i] == null ? null : perSecond[i] * t.width;
        }
        int peak = maxIndex(c);
        String text = "Sunucuda " + c.fileCode + " syslog satır sayısı toplamı (" + head(d) + "): "
                + arrowOf(totals, ExpertFindings::numberText);
        if (peak >= 0) {
            text += "; saniyede en çok " + numberText(c.values[peak]) + ", saat " + clockOf(d, peak);
        }
        List<String> programs = new ArrayList<>();
        for (ProgramRow row : d.host.programs) {
            if (row.fileCode().equals(c.fileCode) && programs.size() < 3) {
                programs.add(row.program() + " (" + TrFormat.integer(row.count()) + ")");
            }
        }
        if (!programs.isEmpty()) {
            text += "; en sık program etiketleri (tüm dosya): " + String.join(", ", programs);
        }
        return text + ".";
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static String head(ExpertReportData d) {
        return d.periodCount() == 2 ? "önce → sonra" : "tüm yakalama";
    }

    private static boolean any(Stat[] stats) {
        for (Stat s : stats) {
            if (s.has()) {
                return true;
            }
        }
        return false;
    }

    private static String arrow(Stat[] stats, java.util.function.Function<Stat, Double> f) {
        List<String> parts = new ArrayList<>();
        for (Stat s : stats) {
            parts.add(TrFormat.ms(f.apply(s)));
        }
        return String.join(" → ", parts);
    }

    private static String join(ExpertReportData d, java.util.function.IntFunction<String> f) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < d.periodCount(); i++) {
            parts.add(f.apply(i));
        }
        return String.join(" → ", parts);
    }

    static String metricValue(String metric, double v) {
        if (metric == null) {
            return TrFormat.number(v, 3);
        }
        if (metric.endsWith("_percent")) {
            return TrFormat.percent(v);
        }
        if (metric.endsWith("_ms")) {
            return TrFormat.ms(v) + " ms";
        }
        return TrFormat.number(v, v == Math.rint(v) ? 0 : 3);
    }
}
