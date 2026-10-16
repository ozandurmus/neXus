package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one telemetry file contained: its detected format, the metric series, and honest counts of the lines used, ignored
 * (blank, headers, sections the report does not use) and skipped (malformed, fail-closed per line). A line is either used
 * whole or skipped whole; nothing is half-applied.
 */
public final class ParsedTelemetry {
    /** What the stamps of the series are relative to. */
    public enum TimeBase {
        /** {@code wall} is a full wall-clock reading. */
        ABSOLUTE,
        /** {@code wall} is the time since midnight of a date the file does not state (the alignment's anchor supplies it). */
        DATE_RELATIVE,
        /** {@code wall} is the time since the first line (the alignment's anchor supplies that time). */
        START_RELATIVE
    }

    private TelemetryFormat format = TelemetryFormat.UNKNOWN;
    private String reason;
    private String hostName;
    private String fileZoneLabel;
    private TimeBase base = TimeBase.ABSOLUTE;
    private long linesTotal;
    private long linesUsed;
    private long linesIgnored;
    private long linesSkipped;
    private boolean partial;
    private final Map<String, Long> skipReasons = new TreeMap<>();
    private final Map<String, Long> notes = new TreeMap<>();
    private final Map<String, Series> series = new LinkedHashMap<>();
    private final Map<String, Long> programs = new TreeMap<>();
    private final long[] samples = new long[1];

    // ---- results ------------------------------------------------------------------------------------------

    public TelemetryFormat format() {
        return format;
    }

    /** Closed code when the file could not be used (EMPTY, NO_KNOWN_FORMAT, NO_TIMESTAMPS, NEEDS_ANCHOR_TIME, NEEDS_ANCHOR_DATE). */
    public String reason() {
        return reason;
    }

    public String hostName() {
        return hostName;
    }

    /** The zone label the file itself prints (vmstat -t's header), or {@code null}. */
    public String fileZoneLabel() {
        return fileZoneLabel;
    }

    public TimeBase base() {
        return base;
    }

    public long linesTotal() {
        return linesTotal;
    }

    public long linesUsed() {
        return linesUsed;
    }

    public long linesIgnored() {
        return linesIgnored;
    }

    public long linesSkipped() {
        return linesSkipped;
    }

    /** True when only the head of the file was examined (an unrecognised file). */
    public boolean partial() {
        return partial;
    }

    public Map<String, Long> skipReasons() {
        return Collections.unmodifiableMap(skipReasons);
    }

    public Map<String, Long> notes() {
        return Collections.unmodifiableMap(notes);
    }

    public Map<String, Series> series() {
        return Collections.unmodifiableMap(series);
    }

    /** Syslog program names with their line counts (sorted by name; callers rank them). */
    public Map<String, Long> programs() {
        return Collections.unmodifiableMap(programs);
    }

    public boolean recognised() {
        return format != TelemetryFormat.UNKNOWN;
    }

    /** True when at least one sample can be placed in time (given an anchor where {@link #reason()} asks for one). */
    public boolean hasSamples() {
        for (Series s : series.values()) {
            if (s.size() > 0) {
                return true;
            }
        }
        return false;
    }

    // ---- building (package-private) -----------------------------------------------------------------------

    void format(TelemetryFormat value) {
        this.format = value;
    }

    void reason(String value) {
        this.reason = value;
    }

    void hostName(String value) {
        if (hostName == null && value != null && !value.isBlank()) {
            hostName = value.length() > 128 ? value.substring(0, 128) : value;
        }
    }

    void fileZoneLabel(String value) {
        this.fileZoneLabel = value;
    }

    void base(TimeBase value) {
        this.base = value;
    }

    void partial(boolean value) {
        this.partial = value;
    }

    void total() {
        linesTotal++;
    }

    void used() {
        linesUsed++;
    }

    void ignored() {
        linesIgnored++;
    }

    void skipped(String code) {
        linesSkipped++;
        skipReasons.merge(code, 1L, Long::sum);
    }

    void note(String code) {
        notes.merge(code, 1L, Long::sum);
    }

    Series series(String key, Series.Kind kind) {
        return series.computeIfAbsent(key, k -> new Series(k, kind, samples));
    }

    /** Samples held over all series (a file is cut off at {@link TelemetryParser#MAX_SAMPLES}). */
    long samples() {
        return samples[0];
    }

    void program(String name, long count) {
        programs.merge(name, count, Long::sum);
    }
}
