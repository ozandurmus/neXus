package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.util.Arrays;
import java.util.List;

/**
 * {@code pidstat} reports: the number of process (or, with {@code -t}, thread) rows printed for each interval, and the
 * change of that number from the previous interval. Process names, users and PIDs are not kept.
 */
final class PidstatParser extends Sysstat {
    private int columnCount;
    private boolean haveHeader;
    private Stamp current;
    private long count;
    private long previous = -1;

    PidstatParser(ParsedTelemetry out) {
        super(out);
    }

    /** {@code HH:MM:SS  UID  PID  %usr ... Command} (or {@code USER}, {@code TGID}/{@code TID} variants). */
    static boolean isHeader(String line) {
        Timed t = timed(split(line));
        if (t == null || t.timeOfDay < 0) {
            return false;
        }
        List<String> cols = Arrays.asList(t.rest);
        return cols.contains("Command") && (cols.contains("PID") || cols.contains("TID") || cols.contains("TGID"));
    }

    @Override
    public void line(String text) {
        String line = text.strip();
        if (line.isEmpty()) {
            out.ignored();
            return;
        }
        if (banner(line)) {
            out.ignored();
            return;
        }
        Timed t = timed(split(line));
        if (t == null) {
            out.ignored();
            return;
        }
        if (t.timeOfDay < 0) {
            out.skipped("BAD_TIME");
            return;
        }
        if (isHeader(line)) {
            columnCount = t.rest.length;
            haveHeader = true;
            markSectionStart(Arrays.asList(t.rest));
            out.ignored();
            return;
        }
        if (!haveHeader) {
            out.skipped("NO_HEADER");
            return;
        }
        if (t.rest.length < columnCount) {
            out.skipped("BAD_COLUMN_COUNT");
            return;
        }
        Stamp stamp = stamp(t.timeOfDay);
        if (current != null && stamp.wallMillis() != current.wallMillis()) {
            flush();
        }
        current = stamp;
        count++;
        out.used();
    }

    private void flush() {
        if (current == null) {
            return;
        }
        out.series("pidstat.rows", Series.Kind.GAUGE).add(current, count);
        if (previous >= 0) {
            out.series("pidstat.rows.delta", Series.Kind.COUNT).add(current, count - previous);
        }
        previous = count;
        count = 0;
        current = null;
    }

    @Override
    public void finish() {
        flush();
        if (dateless()) {
            out.base(ParsedTelemetry.TimeBase.DATE_RELATIVE);
        }
        if (!out.hasSamples()) {
            out.reason("NO_SAMPLES");
        }
    }
}
