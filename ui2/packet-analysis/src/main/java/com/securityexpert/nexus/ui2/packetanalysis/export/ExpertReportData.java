package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.telemetry.Series;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.TelemetryFormat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The expert report's computed content: everything the workbook shows, with no spreadsheet types. Identity-bearing
 * strings have already passed through the {@link ReportMasker}. Aggregates keep exact integer sums (nanoseconds or
 * microseconds) so a result never depends on the order DuckDB's threads add floating-point numbers in.
 */
final class ExpertReportData {

    enum WindowMode { ONSET, EXPLICIT, NONE, NO_DATA }

    /** An exact integer aggregate: count, sum and maximum in {@code unit} units per millisecond. */
    record Stat(long n, long sum, long max, double unit) {
        static final Stat EMPTY = new Stat(0, 0, 0, 1);

        boolean has() {
            return n > 0;
        }

        Double meanMs() {
            return n == 0 ? null : sum / (double) n / unit;
        }

        Double maxMs() {
            return n == 0 ? null : max / unit;
        }
    }

    record Period(int index, String key, String label, double start, double end) {
        double seconds() {
            return end - start;
        }
    }

    record OnsetRow(double time, String segment, String metric, String scope, Double value, Double baseline,
            Double threshold, String localTime, String utcTime) {
    }

    record Window(WindowMode mode, Double rangeStart, Double rangeEnd, Double split, Double afterEnd,
            List<Period> periods, OnsetRow onset, String onsetStatus, String note) {
    }

    record Pt(String id, String code, String label, String device, String side, String file, Double coverStart,
            Double coverEnd, Long packets, Long excluded, Long truncated, String clock, Double clockUncertaintyMs) {
    }

    record Seg(String id, String label, String direction, boolean deviceHop, String pointA, String pointB, Long matched,
            Double p50, Double p95, Double max, Double uncertaintyMs, Double lossPercent) {
    }

    /** Consecutive capture points on one device, as one box of the chain strip. */
    record Box(String device, List<String> captions) {
    }

    /** RTT of the request/response cycles observed at one capture point. */
    record PointRtt(String pointId, Stat[] byPeriod, long[] stalled) {
    }

    /** Added time per hop and component from the matched cycles (RX forward, TX reverse). */
    record CycleHop(String hopId, String label, String component, Stat[] byPeriod) {
    }

    record MatchRow(String segmentId, String label, String direction, long[] observations, long[] dropped,
            long[] captureMisses, long[] unknown) {
    }

    record PointPackets(String pointId, long[] packets, long[] retransmissions) {
    }

    /** One second of the shared timeline. {@code period} is -1 outside the compared range. */
    static final class Timeline {
        int count;
        double origin;
        double width;
        String[] local;
        String[] utc;
        int[] period;
        long[] packets;          // at the first point
        long[] retransmissions;  // at the first point
        long[] cycles;           // measured cycles at the first point
        Double[] rttMeanMs;      // at the first point
        long[] lossEvents;       // classified loss events, all hops
        List<String> hopLabels = new ArrayList<>();
        List<Double[]> hopMean = new ArrayList<>();
        List<Double[]> hopMax = new ArrayList<>();
    }

    record TopCycle(String flowLabel, double start, String periodLabel, Double totalMs, Double[] rttByPoint,
            Double[] components, Double localMs, int missing) {
    }

    record LadderRow(Long ipId, Double[] relativeMs) {
    }

    record Ladder(String flowLabel, String description, List<String> pointCaptions, List<LadderRow> rows,
            Double firstPointSpanMs, Double lastPointSpanMs) {
    }

    record Source(String name, Long sizeBytes, String sha256) {
    }

    record Finding(String id, String text) {
    }

    /** One attached host telemetry file: what it is, how its clock was aligned, and what became of its lines. */
    record HostFile(String code, String name, Long sizeBytes, String sha256, TelemetryFormat format, String hostName,
            String zoneText, boolean zoneConfirmed, String fileZoneLabel, int offsetSeconds, String anchorText, Double first,
            Double last, Double overlapSeconds, long linesTotal, long linesUsed, long linesIgnored, long linesSkipped,
            String skipText, String status, String statusNote, List<String> notes) {
        boolean placed() {
            return "OK".equals(status);
        }
    }

    /** One column of the host sheet: a series on the capture timeline's second grid. */
    static final class HostColumn {
        final String section;
        final String key;
        final String fileCode;
        final Series.Kind kind;
        final String title;
        final String format;
        final Double[] values;

        HostColumn(String section, String key, String fileCode, Series.Kind kind, String title, String format, Double[] values) {
            this.section = section;
            this.key = key;
            this.fileCode = fileCode;
            this.kind = kind;
            this.title = title;
            this.format = format;
            this.values = values;
        }
    }

    record ProgramRow(String fileCode, String program, long count) {
    }

    /** Everything the host-correlation sheet shows. */
    static final class HostData {
        final List<HostFile> files = new ArrayList<>();
        final List<HostColumn> columns = new ArrayList<>();
        final List<ProgramRow> programs = new ArrayList<>();
        final List<String> limits = new ArrayList<>();
        /** Why the second grid has no columns, when it has none. */
        String gridNote;

        boolean anyPlaced() {
            return files.stream().anyMatch(HostFile::placed);
        }
    }

    // header
    String engineVersion;
    /** The case's title as the caller projected it for the reader; {@code null}: none given. */
    String caseTitle;
    String zoneLabel;
    ZoneId zone;
    double bucketSeconds;
    double stallMs;
    int topCyclesRequested;
    Window window;
    List<Pt> points = new ArrayList<>();
    List<Seg> segments = new ArrayList<>();
    List<Box> chain = new ArrayList<>();

    // aggregates per period (index = Period.index)
    java.util.Map<String, Stat[]> hopTransit = new java.util.LinkedHashMap<>();
    List<PointRtt> pointRtt = new ArrayList<>();
    List<CycleHop> cycleHops = new ArrayList<>();
    List<MatchRow> matchRows = new ArrayList<>();
    List<PointPackets> pointPackets = new ArrayList<>();
    java.util.Map<String, long[]> lossByKind = new java.util.LinkedHashMap<>();

    Timeline timeline;
    List<OnsetRow> onsets = new ArrayList<>();
    List<TopCycle> topCycles = new ArrayList<>();
    Ladder ladder;
    List<Source> sources = new ArrayList<>();
    /** Host telemetry; {@code null} when the case has none attached. */
    HostData host;
    List<String> limitations = new ArrayList<>();
    String verdict;
    List<Finding> findings = new ArrayList<>();
    /** Short reasons why a section has no data (printed as one line in the workbook). */
    java.util.Map<String, String> absent = new java.util.LinkedHashMap<>();

    /** True when the report zone is UTC (a zero fixed offset), so a separate UTC clock would only repeat the local one. */
    boolean utcZone() {
        return zone.getRules().isFixedOffset() && zone.getRules().getOffset(java.time.Instant.EPOCH).getTotalSeconds() == 0;
    }

    boolean split() {
        return window != null && window.periods().size() == 2;
    }

    int periodCount() {
        return window == null ? 0 : window.periods().size();
    }

    Pt firstPoint() {
        return points.isEmpty() ? null : points.get(0);
    }

    Pt lastPoint() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    Pt point(String id) {
        for (Pt p : points) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }
}
