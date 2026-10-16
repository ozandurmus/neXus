package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseQueries;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.*;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes the expert report content from a finished case database: the stored report (settings.report), the stored
 * topology and the engine's own tables ({@code observation_matches}, {@code cycle_*}, {@code adjacent_match_rates},
 * {@code obs}, {@code events}, {@code flow_summary}). Nothing is estimated here and no threshold is introduced: every
 * number is a count, a sum, a mean or a maximum of engine rows, taken over exact integer sums (DETERMINISM.md).
 */
final class ExpertReportBuilder {
    static final List<String> LOSS_KINDS = List.of("recovered_loss", "impactful_loss", "unrecovered_loss",
            "handshake_blocked", "confirmed_device_drop");
    private static final double NS_PER_MS = 1_000_000.0;
    private static final double US_PER_MS = 1_000.0;
    private static final int MAX_LADDER_COPIES = 12;
    private static final int MAX_ONSET_ROWS = 50;
    private static final Set<String> EVENT_SCOPES = Set.of("network_segment", "host_capture_boundary");

    private final Db db;
    private final ExpertReportOptions options;
    private final ReportMasker masker;
    private final ExpertReportData data = new ExpertReportData();
    private final Map<String, String> maskedNames = new LinkedHashMap<>();
    private Map<String, Object> report;
    private Topology topology;
    private List<Map<String, Object>> reportSegments = List.of();

    private ExpertReportBuilder(Db db, ExpertReportOptions options) {
        this.db = db;
        this.options = options;
        this.masker = options.masker();
    }

    @SuppressWarnings("unchecked")
    static ExpertReportData build(Db db, ExpertReportOptions options) {
        ExpertReportBuilder b = new ExpertReportBuilder(db, options);
        Object stored = CaseQueries.settingGet(db, "report");
        if (!(stored instanceof Map<?, ?> m) || m.isEmpty()) {
            throw new IllegalArgumentException("Run analysis before exporting");
        }
        b.report = (Map<String, Object>) stored;
        b.topology = CaseQueries.loadTopology(db);
        b.run();
        return b.data;
    }

    // ------------------------------------------------------------------------------------------------ steps

    private void run() {
        data.engineVersion = str(report.get("engine_version"));
        data.caseTitle = options.caseTitle();
        data.topCyclesRequested = options.topCycles();
        setZone();
        readTimeseriesMeta();
        readPoints();
        readSources();
        // every name is registered with the masker now; engine text may be masked
        data.verdict = masker.text(str(report.get("verdict")));
        readSegments();
        readChain();
        readOnsets();
        chooseWindow();
        for (Object limitation : list(report.get("limitations"))) {
            data.limitations.add(masker.text(str(limitation)));
        }
        if (data.window.mode() != WindowMode.NO_DATA) {
            readHopTransit();
            readPointRtt();
            readCycleHops();
            readMatchRows();
            readPointPackets();
            readLoss();
            readTimeline();
            readTopCycles();
            readLadder();
        }
        if (!options.telemetry().isEmpty()) {
            data.host = HostCorrelationBuilder.build(data, options, this::name);
        }
        data.findings = ExpertFindings.build(data);
    }

    private void setZone() {
        ZoneId zone = ZoneOffset.UTC;
        String name = topology.reportTimezone;
        if (name != null && !name.isBlank()) {
            try {
                zone = ZoneId.of(name.strip());
            } catch (DateTimeException ignored) {
                zone = ZoneOffset.UTC;
            }
        }
        data.zone = zone;
        data.zoneLabel = zone.getId().equals("Z") ? "UTC" : zone.getId();
    }

    private double bucketOrigin;
    private int bucketCount;

    private void readTimeseriesMeta() {
        Map<String, Object> ts = map(report.get("timeseries"));
        Double width = dbl(ts.get("bucket_seconds"));
        data.bucketSeconds = width != null && width > 0 ? width : 1.0;
        Double start = dbl(ts.get("start"));
        Long count = lng(ts.get("bucket_count"));
        bucketOrigin = start == null ? 0 : start;
        bucketCount = start == null || count == null ? 0 : (int) Math.min(count, 200_000L);
        Double stall = dbl(map(report.get("cycles")).get("stall_ms"));
        data.stallMs = stall == null ? topology.stallMs : stall;
    }

    private String name(String raw) {
        if (raw == null) {
            return null;
        }
        return maskedNames.computeIfAbsent(raw, masker::name);
    }

    private void readPoints() {
        Map<String, Row> captureNames = new LinkedHashMap<>();
        if (db.tableExists("captures")) {
            for (Row r : db.rows("SELECT id,name FROM captures ORDER BY id")) {
                captureNames.put(r.str("id"), r);
            }
        }
        Map<String, Map<String, Object>> coverage = byPoint(report.get("coverage"));
        Map<String, Map<String, Object>> quality = byPoint(report.get("quality"));
        Map<String, Object> clocks = map(report.get("clocks"));
        List<String> order = new ArrayList<>(new LinkedHashSet<>(topology.forward));
        for (String id : topology.reverse) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        Map<String, Topology.Point> byId = new LinkedHashMap<>();
        for (Topology.Point p : topology.points) {
            byId.put(p.id(), p);
        }
        // Names are registered with the masker before any text is masked.
        for (String id : order) {
            Topology.Point p = byId.get(id);
            if (p != null) {
                name(p.label());
                name(p.device());
                Row capture = captureNames.get(p.captureId());
                if (capture != null) {
                    name(capture.str("name"));
                }
            }
        }
        int index = 1;
        for (String id : order) {
            Topology.Point p = byId.get(id);
            if (p == null) {
                continue;
            }
            Row capture = captureNames.get(p.captureId());
            Map<String, Object> cov = coverage.getOrDefault(id, Map.of());
            Map<String, Object> q = quality.getOrDefault(id, Map.of());
            Map<String, Object> clock = map(clocks.get(p.captureId()));
            Double uncertainty = dbl(clock.get("uncertainty"));
            data.points.add(new Pt(id, "P" + index++, name(blankTo(p.label(), id)), name(blankTo(p.device(), p.label())),
                    p.side(), capture == null ? null : name(capture.str("name")), dbl(cov.get("start")),
                    dbl(cov.get("end")), lng(cov.get("packets")), lng(q.get("excluded")), lng(q.get("truncated")),
                    str(clock.get("confidence")), uncertainty == null ? null : uncertainty * 1000.0));
        }
    }

    private void readSegments() {
        reportSegments = new ArrayList<>();
        for (Object s : list(report.get("segments"))) {
            if (s instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> segment = (Map<String, Object>) m;
                reportSegments.add(segment);
            }
        }
        for (Map<String, Object> s : reportSegments) {
            String a = str(s.get("point_a"));
            String b = str(s.get("point_b"));
            Pt pa = data.point(a);
            Pt pb = data.point(b);
            if (pa == null || pb == null) {
                continue;
            }
            data.segments.add(new Seg(str(s.get("id")), pa.label() + " → " + pb.label(), str(s.get("direction")),
                    "device".equals(s.get("location")), a, b, lng(s.get("matched")), dbl(s.get("p50_ms")),
                    dbl(s.get("p95_ms")), dbl(s.get("max_ms")), dbl(s.get("latency_uncertainty_ms")),
                    dbl(s.get("loss_percent"))));
        }
    }

    private void readChain() {
        String currentDevice = null;
        List<String> captions = new ArrayList<>();
        for (Pt p : data.points) {
            if (currentDevice != null && !currentDevice.equals(p.device())) {
                data.chain.add(new Box(currentDevice, List.copyOf(captions)));
                captions = new ArrayList<>();
            }
            currentDevice = p.device();
            captions.add(caption(p));
        }
        if (currentDevice != null) {
            data.chain.add(new Box(currentDevice, List.copyOf(captions)));
        }
    }

    private static String caption(Pt p) {
        String side = switch (p.side() == null ? "" : p.side()) {
            case "ingress" -> "giriş";
            case "egress" -> "çıkış";
            default -> "";
        };
        if (!side.isEmpty()) {
            return p.code() + " " + side;
        }
        return p.label() != null && !p.label().equals(p.device()) ? p.code() + " " + p.label() : p.code();
    }

    @SuppressWarnings("unchecked")
    private void readOnsets() {
        Map<String, Object> onsets = map(report.get("onsets"));
        data.onsets = new ArrayList<>();
        List<OnsetRow> rows = new ArrayList<>();
        for (Object item : list(onsets.get("items"))) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> o = (Map<String, Object>) item;
            Double time = dbl(o.get("time"));
            if (time == null) {
                continue;
            }
            String segmentId = str(o.get("segment"));
            Seg seg = null;
            for (Seg s : data.segments) {
                if (s.id().equals(segmentId)) {
                    seg = s;
                }
            }
            String label = seg != null ? seg.label() : masker.text(str(o.get("label")));
            String metric = str(o.get("metric"));
            rows.add(new OnsetRow(time, label, metric, str(o.get("scope")), dbl(o.get("value")),
                    dbl(o.get("baseline_value")), dbl(o.get("threshold")), TrFormat.dateTime(time, data.zone),
                    TrFormat.dateTime(time, ZoneOffset.UTC)));
        }
        rows.sort(Comparator.comparingDouble(OnsetRow::time).thenComparing(r -> r.segment() == null ? "" : r.segment())
                .thenComparing(r -> r.metric() == null ? "" : r.metric()));
        data.onsets = rows.size() > MAX_ONSET_ROWS ? new ArrayList<>(rows.subList(0, MAX_ONSET_ROWS)) : rows;
        // The engine's own event window (cycle periods) comes from its segment and host-boundary onsets; capture signals
        // such as retransmission percentages only count when no such onset exists.
        onsetFirst = null;
        for (OnsetRow r : rows) {
            if (EVENT_SCOPES.contains(r.scope())) {
                onsetFirst = r;
                break;
            }
        }
        if (onsetFirst == null && !rows.isEmpty()) {
            onsetFirst = rows.get(0);
        }
        onsetStatus = str(onsets.get("status"));
    }

    private OnsetRow onsetFirst;
    private String onsetStatus;

    private void chooseWindow() {
        Map<String, Object> w = map(report.get("window"));
        Double lo = first(dbl(w.get("common_start")), dbl(w.get("start")), bucketCount > 0 ? bucketOrigin : null);
        Double hi = first(dbl(w.get("common_end")), dbl(w.get("end")),
                bucketCount > 0 ? bucketOrigin + bucketCount * data.bucketSeconds : null);
        if (lo == null || hi == null || hi <= lo) {
            data.window = new Window(WindowMode.NO_DATA, null, null, null, null, List.of(), null, onsetStatus,
                    "Analiz penceresi bulunamadı; bu rapor için ölçüm yok.");
            return;
        }
        WindowMode mode;
        Double split = null;
        Double afterEnd = hi;
        String note;
        if (options.windowStart() != null) {
            mode = WindowMode.EXPLICIT;
            split = options.windowStart();
            afterEnd = options.windowEnd() == null ? hi : Math.min(options.windowEnd(), hi);
            if (split >= hi || afterEnd <= lo || afterEnd <= split) {
                throw new IllegalArgumentException("Window is outside the analysed capture range");
            }
            note = "Önce/sonra karşılaştırması verilen pencereye göre yapıldı.";
        } else if (onsetFirst != null && onsetFirst.time() < hi) {
            mode = WindowMode.ONSET;
            split = onsetFirst.time();
            note = "Önce/sonra karşılaştırması motorun bulduğu ilk olay başlangıcından bölündü.";
        } else {
            mode = WindowMode.NONE;
            note = "Motor bu yakalamada bir olay penceresi bulamadı; rakamlar tüm yakalama içindir.";
        }
        List<Period> periods = new ArrayList<>();
        if (mode == WindowMode.NONE) {
            periods.add(new Period(0, "all", "Tüm yakalama", lo, Math.nextUp(hi)));
        } else {
            double beforeEnd = Math.max(lo, Math.min(split, hi));
            periods.add(new Period(0, "before", "Önce", lo, beforeEnd));
            double end = options.windowEnd() == null || afterEnd >= hi ? Math.nextUp(hi) : afterEnd;
            periods.add(new Period(1, "after", "Sonra", Math.max(split, lo), end));
        }
        data.window = new Window(mode, lo, hi, split, afterEnd, List.copyOf(periods), onsetFirst, onsetStatus, note);
    }

    private void readSources() {
        Map<String, ExpertReportOptions.SourceCapture> provided = new LinkedHashMap<>();
        for (ExpertReportOptions.SourceCapture s : options.sources()) {
            provided.put(s.name(), s);
        }
        Set<String> listed = new TreeSet<>(provided.keySet());
        if (db.tableExists("captures")) {
            for (Row r : db.rows("SELECT name FROM captures ORDER BY name,id")) {
                listed.add(r.str("name"));
            }
        }
        for (String raw : listed) {
            ExpertReportOptions.SourceCapture s = provided.get(raw);
            String sha = s == null || masker.masking() ? null : s.sha256();
            data.sources.add(new Source(name(raw), s == null ? null : s.sizeBytes(), sha));
        }
    }

    // ------------------------------------------------------------------------------------------------ SQL helpers

    private boolean hasTable(String name) {
        return db.tableExists(name);
    }

    /** SQL text and parameters that map a time column to a period index; NULL outside every period. */
    private String periodCase(String column, List<Object> params) {
        List<Period> periods = data.window.periods();
        if (data.window.mode() == WindowMode.NONE) {
            return "0";
        }
        StringBuilder sql = new StringBuilder("CASE");
        for (Period p : periods) {
            sql.append(" WHEN ").append(column).append(">=? AND ").append(column).append("<? THEN ").append(p.index());
            params.add(p.start());
            params.add(p.end());
        }
        return sql.append(" END").toString();
    }

    private boolean needsAlignedClock() {
        return data.window.mode() != WindowMode.NONE;
    }

    private String segmentCondition(List<Seg> segments, List<Object> params, String prefix) {
        List<String> parts = new ArrayList<>();
        for (Seg s : segments) {
            parts.add("(" + prefix + "point_a=? AND " + prefix + "point_b=? AND " + prefix + "direction=?)");
            params.add(s.pointA());
            params.add(s.pointB());
            params.add(s.direction());
        }
        return String.join(" OR ", parts);
    }

    private Stat[] emptyStats(double unit) {
        Stat[] out = new Stat[data.periodCount()];
        java.util.Arrays.fill(out, new Stat(0, 0, 0, unit));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ aggregates

    private void readHopTransit() {
        if (data.segments.isEmpty() || !hasTable("observation_matches")) {
            data.absent.put("hop", "Komşu noktalar arası eşleşmiş paket bulunamadı.");
            return;
        }
        List<Object> bound = new ArrayList<>();
        String period = periodCase("m.time_a", bound);
        List<String> ids = pointIds(data.segments);
        String marks = marks(ids.size());
        bound.addAll(ids);
        bound.addAll(ids);
        String cond = segmentCondition(data.segments, bound, "m.");
        String sql = "SELECT point_a,point_b,direction,p,count(*)::BIGINT AS n,sum(ns)::BIGINT AS s,max(ns)::BIGINT AS mx FROM ("
                + "SELECT m.point_a,m.point_b,m.direction," + period + " AS p,round((m.time_b-m.time_a)*1e9)::BIGINT AS ns "
                + "FROM observation_matches m WHERE m.point_a IN (" + marks + ") AND m.point_b IN (" + marks + ") AND ("
                + cond + ")) WHERE p IS NOT NULL AND ns IS NOT NULL GROUP BY 1,2,3,4 ORDER BY 1,2,3,4";
        for (Seg s : data.segments) {
            data.hopTransit.put(s.id(), emptyStats(NS_PER_MS));
        }
        boolean any = false;
        for (Row r : db.rows(sql, bound.toArray())) {
            Seg seg = segment(r.str("point_a"), r.str("point_b"), r.str("direction"));
            if (seg == null) {
                continue;
            }
            int p = r.lng("p").intValue();
            data.hopTransit.get(seg.id())[p] = new Stat(r.lng("n"), r.lng("s"), r.lng("mx"), NS_PER_MS);
            any = true;
        }
        if (!any) {
            data.absent.put("hop", "Komşu noktalar arası eşleşmiş paket bulunamadı.");
        }
    }

    private static List<String> pointIds(List<Seg> segments) {
        Set<String> ids = new TreeSet<>();
        for (Seg s : segments) {
            ids.add(s.pointA());
            ids.add(s.pointB());
        }
        return new ArrayList<>(ids);
    }

    private static String marks(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private Seg segment(String a, String b, String direction) {
        for (Seg s : data.segments) {
            if (s.pointA().equals(a) && s.pointB().equals(b) && s.direction().equals(direction)) {
                return s;
            }
        }
        return null;
    }

    private void readPointRtt() {
        if (!hasTable("cycle_points")) {
            data.absent.put("rtt", "Bu analizde istek/cevap döngüsü hesaplanmadı.");
            return;
        }
        List<Object> params = new ArrayList<>();
        String period = periodCase("request_first", params);
        String aligned = needsAlignedClock() ? " AND clock_aligned" : "";
        String sql = "SELECT point,p,count(*)::BIGINT AS n,sum(us)::BIGINT AS s,max(us)::BIGINT AS mx,"
                + "count(*) FILTER(WHERE stalled)::BIGINT AS st FROM (SELECT point,stalled,round(cycle_ms*1000)::BIGINT AS us,"
                + period + " AS p FROM cycle_points WHERE reason IS NULL AND cycle_ms IS NOT NULL" + aligned
                + ") WHERE p IS NOT NULL GROUP BY 1,2 ORDER BY 1,2";
        Map<String, Stat[]> stats = new LinkedHashMap<>();
        Map<String, long[]> stalled = new LinkedHashMap<>();
        for (Pt p : data.points) {
            stats.put(p.id(), emptyStats(US_PER_MS));
            stalled.put(p.id(), new long[data.periodCount()]);
        }
        boolean any = false;
        for (Row r : db.rows(sql, params.toArray())) {
            String point = r.str("point");
            if (!stats.containsKey(point)) {
                continue;
            }
            int p = r.lng("p").intValue();
            stats.get(point)[p] = new Stat(r.lng("n"), r.lng("s"), r.lng("mx"), US_PER_MS);
            stalled.get(point)[p] = r.lng("st");
            any = true;
        }
        for (Pt p : data.points) {
            data.pointRtt.add(new PointRtt(p.id(), stats.get(p.id()), stalled.get(p.id())));
        }
        if (!any) {
            data.absent.put("rtt", "Ölçülebilen istek/cevap döngüsü bulunamadı.");
        }
    }

    private void readCycleHops() {
        if (!hasTable("cycle_pairs")) {
            return;
        }
        List<Object> params = new ArrayList<>();
        String period = periodCase("time", params);
        String aligned = "";
        String sql = "SELECT hop,component,p,count(*)::BIGINT AS n,sum(us)::BIGINT AS s,max(us)::BIGINT AS mx FROM ("
                + "SELECT hop,component,round(added_ms*1000)::BIGINT AS us," + period + " AS p FROM cycle_pairs "
                + "WHERE reason IS NULL AND added_ms IS NOT NULL" + aligned + ") WHERE p IS NOT NULL GROUP BY 1,2,3 ORDER BY 1,2,3";
        Map<String, Stat[]> byKey = new LinkedHashMap<>();
        for (Row r : db.rows(sql, params.toArray())) {
            String key = r.str("hop") + "|" + r.str("component");
            byKey.computeIfAbsent(key, k -> emptyStats(US_PER_MS))[r.lng("p").intValue()] =
                    new Stat(r.lng("n"), r.lng("s"), r.lng("mx"), US_PER_MS);
        }
        for (Seg s : data.segments) {
            for (String component : List.of("RX", "TX")) {
                Stat[] stats = byKey.get(s.id() + "|" + component);
                if (stats != null) {
                    data.cycleHops.add(new CycleHop(s.id(), s.label(), component, stats));
                }
            }
        }
    }

    private void readMatchRows() {
        if (!hasTable("adjacent_match_rates")) {
            data.absent.put("match", "Eşleşme kapsamı tablosu bu analizde yok.");
            return;
        }
        List<Object> params = new ArrayList<>();
        String period = periodCase("start", params);
        String sql = "SELECT point_a,point_b,direction,p,sum(observations)::BIGINT AS o,sum(dropped)::BIGINT AS d,"
                + "sum(capture_misses)::BIGINT AS c,sum(unknown)::BIGINT AS u FROM (SELECT point_a,point_b,direction,"
                + "observations,dropped,capture_misses,unknown," + period + " AS p FROM adjacent_match_rates) "
                + "WHERE p IS NOT NULL GROUP BY 1,2,3,4 ORDER BY 1,2,3,4";
        Map<String, long[][]> acc = new LinkedHashMap<>();
        for (Row r : db.rows(sql, params.toArray())) {
            Seg seg = segment(r.str("point_a"), r.str("point_b"), r.str("direction"));
            if (seg == null) {
                continue;
            }
            long[][] a = acc.computeIfAbsent(seg.id(), k -> new long[4][data.periodCount()]);
            int p = r.lng("p").intValue();
            a[0][p] = r.lng("o");
            a[1][p] = r.lng("d");
            a[2][p] = r.lng("c");
            a[3][p] = r.lng("u");
        }
        for (Seg s : data.segments) {
            long[][] a = acc.get(s.id());
            if (a != null) {
                data.matchRows.add(new MatchRow(s.id(), s.label(), s.direction(), a[0], a[1], a[2], a[3]));
            }
        }
        if (data.matchRows.isEmpty()) {
            data.absent.put("match", "Eşleşme kapsamı için gözlem bulunamadı.");
        }
    }

    private void readPointPackets() {
        if (!hasTable("obs")) {
            data.absent.put("packets", "Paket tablosu bu analizde yok.");
            for (Pt p : data.points) {
                data.pointPackets.add(new PointPackets(p.id(), new long[data.periodCount()], new long[data.periodCount()]));
            }
            return;
        }
        List<Object> params = new ArrayList<>();
        String period = periodCase("corrected", params);
        String sql = "SELECT point,p,count(*)::BIGINT AS n,count(*) FILTER(WHERE retrans)::BIGINT AS r FROM (SELECT point,retrans,"
                + period + " AS p FROM obs) WHERE p IS NOT NULL GROUP BY 1,2 ORDER BY 1,2";
        Map<String, long[][]> acc = new LinkedHashMap<>();
        for (Pt p : data.points) {
            acc.put(p.id(), new long[2][data.periodCount()]);
        }
        for (Row r : db.rows(sql, params.toArray())) {
            long[][] a = acc.get(r.str("point"));
            if (a != null) {
                a[0][r.lng("p").intValue()] = r.lng("n");
                a[1][r.lng("p").intValue()] = r.lng("r");
            }
        }
        for (Pt p : data.points) {
            data.pointPackets.add(new PointPackets(p.id(), acc.get(p.id())[0], acc.get(p.id())[1]));
        }
    }

    private void readLoss() {
        if (!hasTable("events")) {
            return;
        }
        List<Object> params = new ArrayList<>();
        String period = periodCase("ts", params);
        String sql = "SELECT kind,p,count(*)::BIGINT AS n FROM (SELECT kind," + period + " AS p FROM events WHERE kind IN ("
                + "'recovered_loss','impactful_loss','unrecovered_loss','handshake_blocked','confirmed_device_drop')) "
                + "WHERE p IS NOT NULL GROUP BY 1,2 ORDER BY 1,2";
        for (String kind : LOSS_KINDS) {
            data.lossByKind.put(kind, new long[data.periodCount()]);
        }
        for (Row r : db.rows(sql, params.toArray())) {
            data.lossByKind.get(r.str("kind"))[r.lng("p").intValue()] = r.lng("n");
        }
    }

    private void readTimeline() {
        if (bucketCount <= 0) {
            data.absent.put("timeline", "Saniyelik zaman çizelgesi için kova bulunamadı.");
            return;
        }
        Timeline t = new Timeline();
        t.count = bucketCount;
        t.origin = bucketOrigin;
        t.width = data.bucketSeconds;
        t.local = new String[t.count];
        t.utc = new String[t.count];
        t.period = new int[t.count];
        t.packets = new long[t.count];
        t.retransmissions = new long[t.count];
        t.cycles = new long[t.count];
        t.rttMeanMs = new Double[t.count];
        t.lossEvents = new long[t.count];
        double stop = t.origin + t.count * t.width;
        boolean multiDay = !TrFormat.dateOf(t.origin, data.zone).equals(TrFormat.dateOf(stop - 1e-6, data.zone));
        for (int i = 0; i < t.count; i++) {
            double start = t.origin + i * t.width;
            t.local[i] = TrFormat.epochToLocal(start, data.zone, multiDay);
            t.utc[i] = TrFormat.epochToLocal(start, ZoneOffset.UTC, multiDay);
            t.period[i] = -1;
            if (data.window.mode() == WindowMode.NONE) {
                t.period[i] = 0;
            } else {
                for (Period p : data.window.periods()) {
                    if (start >= p.start() && start < p.end()) {
                        t.period[i] = p.index();
                    }
                }
            }
        }
        data.timeline = t;
        Pt first = data.firstPoint();
        if (first != null && hasTable("obs")) {
            for (Row r : db.rows("SELECT floor((corrected-?)/?)::BIGINT AS b,count(*)::BIGINT AS n,"
                    + "count(*) FILTER(WHERE retrans)::BIGINT AS r FROM obs WHERE point=? AND corrected>=? AND corrected<? "
                    + "GROUP BY 1 ORDER BY 1", t.origin, t.width, first.id(), t.origin, stop)) {
                int b = r.lng("b").intValue();
                if (b >= 0 && b < t.count) {
                    t.packets[b] = r.lng("n");
                    t.retransmissions[b] = r.lng("r");
                }
            }
            if (hasTable("cycle_points")) {
                for (Row r : db.rows("SELECT floor((request_first-?)/?)::BIGINT AS b,count(*)::BIGINT AS n,"
                        + "sum(round(cycle_ms*1000))::BIGINT AS s FROM cycle_points WHERE point=? AND reason IS NULL "
                        + "AND cycle_ms IS NOT NULL AND request_first>=? AND request_first<? GROUP BY 1 ORDER BY 1",
                        t.origin, t.width, first.id(), t.origin, stop)) {
                    int b = r.lng("b").intValue();
                    if (b >= 0 && b < t.count) {
                        t.cycles[b] = r.lng("n");
                        t.rttMeanMs[b] = r.lng("s") / (double) r.lng("n") / US_PER_MS;
                    }
                }
            }
        }
        if (hasTable("events")) {
            for (Row r : db.rows("SELECT floor((ts-?)/?)::BIGINT AS b,count(*)::BIGINT AS n FROM events WHERE kind IN ("
                    + "'recovered_loss','impactful_loss','unrecovered_loss','handshake_blocked','confirmed_device_drop') "
                    + "AND ts>=? AND ts<? GROUP BY 1 ORDER BY 1", t.origin, t.width, t.origin, stop)) {
                int b = r.lng("b").intValue();
                if (b >= 0 && b < t.count) {
                    t.lossEvents[b] = r.lng("n");
                }
            }
        }
        List<Seg> forward = new ArrayList<>();
        for (Seg s : data.segments) {
            if ("forward".equals(s.direction())) {
                forward.add(s);
            }
        }
        if (!forward.isEmpty() && hasTable("observation_matches")) {
            List<Object> bound = new ArrayList<>();
            bound.add(t.origin);
            bound.add(t.width);
            List<String> ids = pointIds(forward);
            String marks = marks(ids.size());
            bound.addAll(ids);
            bound.addAll(ids);
            String cond = segmentCondition(forward, bound, "m.");
            bound.add(t.origin);
            bound.add(stop);
            String sql = "SELECT point_a,point_b,direction,b,count(*)::BIGINT AS n,sum(ns)::BIGINT AS s,max(ns)::BIGINT AS mx FROM ("
                    + "SELECT m.point_a,m.point_b,m.direction,floor((m.time_a-?)/?)::BIGINT AS b,"
                    + "round((m.time_b-m.time_a)*1e9)::BIGINT AS ns FROM observation_matches m WHERE m.point_a IN (" + marks
                    + ") AND m.point_b IN (" + marks + ") AND (" + cond + ") AND m.time_a>=? AND m.time_a<?) "
                    + "WHERE ns IS NOT NULL GROUP BY 1,2,3,4 ORDER BY 1,2,3,4";
            Map<String, Double[][]> perHop = new LinkedHashMap<>();
            for (Row r : db.rows(sql, bound.toArray())) {
                Seg seg = segment(r.str("point_a"), r.str("point_b"), r.str("direction"));
                int b = r.lng("b").intValue();
                if (seg == null || b < 0 || b >= t.count) {
                    continue;
                }
                Double[][] arrays = perHop.computeIfAbsent(seg.id(), k -> new Double[2][t.count]);
                arrays[0][b] = r.lng("s") / (double) r.lng("n") / NS_PER_MS;
                arrays[1][b] = r.lng("mx") / NS_PER_MS;
            }
            for (Seg s : forward) {
                Double[][] arrays = perHop.get(s.id());
                if (arrays != null) {
                    t.hopLabels.add(s.label());
                    t.hopMean.add(arrays[0]);
                    t.hopMax.add(arrays[1]);
                }
            }
        }
    }

    private final Map<String, Integer> flowOrdinals = new HashMap<>();

    private String flowLabel(String flow, Object port) {
        int ordinal = flowOrdinals.computeIfAbsent(flow, k -> flowOrdinals.size() + 1);
        String suffix = port == null ? "" : " / " + port;
        if (masker.masking()) {
            return "Akış " + ordinal + suffix;
        }
        return (flow.length() > 8 ? flow.substring(0, 8) : flow) + suffix;
    }

    private void readTopCycles() {
        if (!hasTable("cycle_points") || data.points.isEmpty()) {
            data.absent.put("top", "Bu analizde istek/cevap döngüsü hesaplanmadı.");
            return;
        }
        List<Row> top = db.rows("SELECT cycle_id,min(flow) AS flow,max(cycle_ms) AS max_ms FROM cycle_points "
                + "WHERE reason IS NULL AND cycle_ms IS NOT NULL GROUP BY cycle_id ORDER BY max_ms DESC,cycle_id LIMIT ?",
                (long) options.topCycles());
        if (top.isEmpty()) {
            data.absent.put("top", "Ölçülebilen istek/cevap döngüsü bulunamadı.");
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Row r : top) {
            ids.add(r.str("cycle_id"));
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<String, Map<String, Row>> perCycle = new LinkedHashMap<>();
        for (Row r : db.rows("SELECT cycle_id,point,min(cycle_ms) AS cycle_ms,min(request_first) AS rf,min(port) AS port,"
                + "min(flow) AS flow,bool_and(clock_aligned) AS aligned FROM cycle_points WHERE reason IS NULL "
                + "AND cycle_ms IS NOT NULL AND cycle_id IN (" + marks + ") GROUP BY 1,2 ORDER BY 1,2", ids.toArray())) {
            perCycle.computeIfAbsent(r.str("cycle_id"), k -> new LinkedHashMap<>()).put(r.str("point"), r);
        }
        // Flow ordinals follow the (total) order of the top query, so masked labels are stable across runs.
        Pt firstPoint = data.firstPoint();
        for (Row cycle : top) {
            Map<String, Row> byPoint = perCycle.getOrDefault(cycle.str("cycle_id"), Map.of());
            Row anyRow = null;
            for (Pt p : data.points) {
                if (byPoint.containsKey(p.id())) {
                    anyRow = byPoint.get(p.id());
                    break;
                }
            }
            if (anyRow == null) {
                continue;
            }
            Double[] rtt = new Double[data.points.size()];
            int missing = 0;
            for (int i = 0; i < rtt.length; i++) {
                Row r = byPoint.get(data.points.get(i).id());
                rtt[i] = r == null ? null : r.dbl("cycle_ms");
                if (rtt[i] == null) {
                    missing++;
                }
            }
            Double[] components = new Double[Math.max(0, rtt.length - 1)];
            for (int i = 0; i < components.length; i++) {
                components[i] = rtt[i] == null || rtt[i + 1] == null ? null : rtt[i] - rtt[i + 1];
            }
            Row startRow = byPoint.containsKey(firstPoint.id()) ? byPoint.get(firstPoint.id()) : anyRow;
            double start = startRow.dbl("rf");
            data.topCycles.add(new TopCycle(flowLabel(anyRow.str("flow"), anyRow.get("port")), start,
                    periodLabelOf(start, startRow.bool("aligned")), rtt[0] != null ? rtt[0] : cycle.dbl("max_ms"), rtt,
                    components, rtt[rtt.length - 1], missing));
        }
        if (data.topCycles.isEmpty()) {
            data.absent.put("top", "Ölçülebilen istek/cevap döngüsü bulunamadı.");
        }
    }

    private String periodLabelOf(double time, boolean aligned) {
        if (data.window.mode() == WindowMode.NONE) {
            return "";
        }
        if (!aligned) {
            return "–";
        }
        for (Period p : data.window.periods()) {
            if (time >= p.start() && time < p.end()) {
                return p.label();
            }
        }
        return "–";
    }

    private void readLadder() {
        if (!hasTable("flow_summary") || !hasTable("obs") || data.points.isEmpty()) {
            data.absent.put("ladder", "Retransmission merdiveni için akış verisi yok.");
            return;
        }
        List<Row> flows = db.rows("SELECT flow FROM flow_summary WHERE retrans_observations>0 "
                + "ORDER BY retrans_observations DESC,flow LIMIT 1");
        if (flows.isEmpty()) {
            data.absent.put("ladder", "Bu analizde retransmission gözlenmedi; merdiven oluşturulmadı.");
            return;
        }
        String flow = flows.get(0).str("flow");
        List<Row> group = db.rows("SELECT direction,seq,length,max(c)::BIGINT AS copies FROM (SELECT point,direction,seq,length,"
                + "count(*) AS c FROM obs WHERE flow=? AND proto='TCP' AND length>0 GROUP BY 1,2,3,4 HAVING count(*)>1) "
                + "GROUP BY 1,2,3 ORDER BY copies DESC,direction,seq,length LIMIT 1", flow);
        if (group.isEmpty()) {
            data.absent.put("ladder", "Aynı veri parçasının birden fazla kopyası gözlenmedi; merdiven oluşturulmadı.");
            return;
        }
        Row g = group.get(0);
        List<Row> copies = db.rows("SELECT point,corrected,ipid,k FROM (SELECT point,corrected,ipid,"
                + "row_number() OVER(PARTITION BY point ORDER BY corrected,frame) AS k FROM obs WHERE flow=? AND direction=? "
                + "AND seq=? AND length=? AND proto='TCP' AND corrected IS NOT NULL) WHERE k<=? ORDER BY point,k",
                flow, g.str("direction"), g.lng("seq"), g.lng("length"), (long) MAX_LADDER_COPIES);
        int points = data.points.size();
        Map<Integer, Double[]> relative = new java.util.TreeMap<>();
        Map<Integer, Long> ipId = new java.util.TreeMap<>();
        Map<String, Double> firstTime = new HashMap<>();
        Map<String, Integer> pointIndex = new HashMap<>();
        for (int i = 0; i < points; i++) {
            pointIndex.put(data.points.get(i).id(), i);
        }
        for (Row r : copies) {
            Integer pi = pointIndex.get(r.str("point"));
            if (pi == null) {
                continue;
            }
            int k = r.lng("k").intValue();
            firstTime.putIfAbsent(r.str("point"), r.dbl("corrected"));
            Double[] row = relative.computeIfAbsent(k, x -> new Double[points]);
            row[pi] = (r.dbl("corrected") - firstTime.get(r.str("point"))) * 1000.0;
        }
        for (Pt p : data.points) {
            for (Row r : copies) {
                if (r.str("point").equals(p.id())) {
                    ipId.putIfAbsent(r.lng("k").intValue(), r.lng("ipid"));
                }
            }
        }
        List<LadderRow> rows = new ArrayList<>();
        for (Map.Entry<Integer, Double[]> e : relative.entrySet()) {
            rows.add(new LadderRow(ipId.get(e.getKey()), e.getValue()));
        }
        if (rows.size() < 2) {
            data.absent.put("ladder", "Aynı veri parçasının birden fazla kopyası gözlenmedi; merdiven oluşturulmadı.");
            return;
        }
        List<String> captions = new ArrayList<>();
        for (Pt p : data.points) {
            captions.add(p.code() + " " + p.label());
        }
        Double firstSpan = null;
        Double lastSpan = null;
        for (LadderRow row : rows) {
            if (row.relativeMs()[0] != null) {
                firstSpan = row.relativeMs()[0];
            }
            if (row.relativeMs()[points - 1] != null) {
                lastSpan = row.relativeMs()[points - 1];
            }
        }
        String direction = "forward".equals(g.str("direction")) ? "ileri" : "geri";
        data.ladder = new Ladder(flowLabel(flow, null), direction + " yönde " + g.lng("length")
                + " baytlık veri parçasının " + g.lng("copies") + " kopyası", captions, rows, firstSpan, lastSpan);
    }

    // ------------------------------------------------------------------------------------------------ value helpers

    private static Double first(Double... values) {
        for (Double v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> list(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Double dbl(Object v) {
        return v instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    private static Long lng(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Map<String, Map<String, Object>> byPoint(Object rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Object o : list(rows)) {
            Map<String, Object> m = map(o);
            if (m.get("point") != null) {
                out.put(String.valueOf(m.get("point")), m);
            }
        }
        return out;
    }
}
