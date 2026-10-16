package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain syslog ({@code /var/log/messages}, {@code journalctl} short output): the number of lines per second and the
 * program names (the tag before {@code :} or {@code [pid]:}) with their line counts. The message text is read only far
 * enough to find the tag and is never kept, so nothing a message says can reach the report.
 *
 * <p>The traditional layout {@code Oct  8 10:00:01 host prog[1]: ...} carries no year: it is the one (previous, current or
 * next year of the reference time) that puts the line nearest the reference, and the report says so.</p>
 */
final class SyslogParser implements LineParser {
    static final Pattern CLASSIC = Pattern.compile("^([A-Z][a-z]{2})\\s+(\\d{1,2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\s+(\\S+)\\s+(.*)$");
    static final Pattern ISO = Pattern.compile(
            "^(?:<\\d{1,3}>\\d?\\s*)?(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+(\\S+)\\s+(.*)$");
    private static final Pattern TAG = Pattern.compile("^([^\\s\\[:]{1,64})(?:\\[\\d+\\])?:");
    static final int MAX_PROGRAMS = 10_000;
    static final String OTHER_PROGRAMS = TelemetryParser.OTHER_PROGRAMS_LABEL;
    static final String NO_TAG = TelemetryParser.NO_TAG_LABEL;

    private record Key(long wall, int offset) {
    }

    private final ParsedTelemetry out;
    private final Long referenceMillis;
    private final Map<Key, long[]> perSecond = new HashMap<>();
    private final Map<String, Long> programs = new HashMap<>();
    private boolean yearNoted;

    SyslogParser(ParsedTelemetry out, Long referenceMillis) {
        this.out = out;
        this.referenceMillis = referenceMillis;
    }

    static boolean looksLikeSyslog(String line) {
        return CLASSIC.matcher(line).matches() || ISO.matcher(line).matches();
    }

    @Override
    public void line(String text) {
        Stamp stamp = null;
        String host = null;
        String rest = null;
        Matcher iso = ISO.matcher(text);
        if (iso.matches()) {
            stamp = TimeText.iso(iso.group(1));
            host = iso.group(2);
            rest = iso.group(3);
        } else {
            Matcher classic = CLASSIC.matcher(text);
            if (classic.matches()) {
                stamp = classic(classic);
                host = classic.group(6);
                rest = classic.group(7);
            }
        }
        if (stamp == null) {
            out.skipped(rest == null && host == null ? "NO_TIMESTAMP" : "BAD_TIME");
            return;
        }
        out.hostName(host);
        if (stamp.explicit()) {
            out.note("EXPLICIT_OFFSETS");
        }
        perSecond.computeIfAbsent(new Key(stamp.wallMillis() / 1000L * 1000L, stamp.offsetSeconds()), k -> new long[1])[0]++;
        Matcher tag = TAG.matcher(rest);
        String program = tag.find() ? tag.group(1) : NO_TAG;
        if (programs.size() >= MAX_PROGRAMS && !programs.containsKey(program)) {
            program = OTHER_PROGRAMS;
        }
        programs.merge(program, 1L, Long::sum);
        out.used();
    }

    private Stamp classic(Matcher m) {
        int month = TimeText.month(m.group(1));
        if (month < 0) {
            return null;
        }
        int day = Integer.parseInt(m.group(2));
        int h = Integer.parseInt(m.group(3));
        int mi = Integer.parseInt(m.group(4));
        int s = Integer.parseInt(m.group(5));
        int referenceYear = 2000;
        long reference = Long.MIN_VALUE;
        if (referenceMillis != null) {
            referenceYear = Instant.ofEpochMilli(referenceMillis).atOffset(ZoneOffset.UTC).getYear();
            reference = referenceMillis;
        }
        long best = Long.MIN_VALUE;
        long bestDistance = Long.MAX_VALUE;
        for (int year = referenceYear - 1; year <= referenceYear + 1; year++) {
            try {
                long wall = TimeText.wall(year, month, day, h, mi, s, 0);
                long distance = reference == Long.MIN_VALUE ? Math.abs(year - 2000) : Math.abs(wall - reference);
                if (distance < bestDistance) {
                    best = wall;
                    bestDistance = distance;
                }
            } catch (DateTimeException invalid) {
                // 29 February in a common year: try the next candidate year
            }
        }
        if (best == Long.MIN_VALUE) {
            return null;
        }
        if (!yearNoted) {
            yearNoted = true;
            out.note("YEAR_FROM_CAPTURE");
        }
        return Stamp.local(best);
    }

    @Override
    public void finish() {
        Map<Key, long[]> sorted = new TreeMap<>((a, b) -> a.wall != b.wall ? Long.compare(a.wall, b.wall)
                : Integer.compare(a.offset, b.offset));
        sorted.putAll(perSecond);
        Series series = out.series("syslog.count", Series.Kind.COUNT);
        for (Map.Entry<Key, long[]> e : sorted.entrySet()) {
            series.add(e.getKey().wall, e.getKey().offset, e.getValue()[0]);
        }
        for (Map.Entry<String, Long> e : programs.entrySet()) {
            out.program(e.getKey(), e.getValue());
        }
        if (!out.hasSamples()) {
            out.reason("NO_SAMPLES");
        }
    }
}
