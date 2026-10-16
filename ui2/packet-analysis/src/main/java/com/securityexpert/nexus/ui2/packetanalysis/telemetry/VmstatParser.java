package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.util.Map;
import java.util.Set;

/**
 * {@code vmstat} with or without {@code -t}. Columns are found by the header's names. With {@code -t} each row ends in a
 * date and a time and the header's last word is the zone label vmstat prints; without it the rows carry no time and are
 * placed one second apart from a start time the alignment must supply (the first printed row, which vmstat fills with
 * averages since boot, is the start and is not used as a sample).
 */
final class VmstatParser implements LineParser {
    private static final Set<String> KNOWN = Set.of("r", "b", "swpd", "free", "buff", "cache", "inact", "active", "si", "so",
            "bi", "bo", "in", "cs", "us", "sy", "id", "wa", "st", "gu");
    private static final Map<String, String> METRICS = Map.ofEntries(Map.entry("r", "vmstat.r"), Map.entry("b", "vmstat.b"),
            Map.entry("in", "vmstat.in"), Map.entry("cs", "vmstat.cs"), Map.entry("us", "vmstat.us"),
            Map.entry("sy", "vmstat.sy"), Map.entry("id", "vmstat.id"), Map.entry("wa", "vmstat.wa"),
            Map.entry("st", "vmstat.st"));

    private final ParsedTelemetry out;
    private String[] columns;
    private boolean stamped;
    private long rowIndex = -1;
    private boolean firstRowSeen;
    private boolean intervalNoted;

    VmstatParser(ParsedTelemetry out) {
        this.out = out;
    }

    static boolean isHeader(String line) {
        String[] t = Sysstat.split(line);
        if (t.length < 8 || !t[0].equals("r") || !t[1].equals("b")) {
            return false;
        }
        int known = 0;
        for (String token : t) {
            if (KNOWN.contains(token)) {
                known++;
            }
        }
        return known >= 8;
    }

    @Override
    public void line(String text) {
        String line = text.strip();
        if (line.isEmpty() || line.startsWith("procs")) {
            out.ignored();
            return;
        }
        String[] tokens = Sysstat.split(line);
        if (isHeader(line)) {
            int known = 0;
            while (known < tokens.length && KNOWN.contains(tokens[known])) {
                known++;
            }
            columns = java.util.Arrays.copyOf(tokens, known);
            if (known < tokens.length) {
                stamped = true;
                out.fileZoneLabel(tokens[tokens.length - 1]);
            }
            out.ignored();
            return;
        }
        if (!Sysstat.isNumber(tokens[0])) {
            out.ignored();
            return;
        }
        if (columns == null) {
            out.skipped("NO_HEADER");
            return;
        }
        rowIndex++;
        int expected = columns.length + (stamped ? 2 : 0);
        if (tokens.length != expected) {
            out.skipped("BAD_COLUMN_COUNT");
            return;
        }
        if (!firstRowSeen) {
            // The first row of `vmstat <interval>` is the average since boot, not a sample of one interval.
            firstRowSeen = true;
            out.note("FIRST_ROW_SKIPPED");
            out.ignored();
            if (!stamped) {
                out.base(ParsedTelemetry.TimeBase.START_RELATIVE);
            }
            return;
        }
        Stamp stamp;
        if (stamped) {
            stamp = TimeText.iso(tokens[columns.length] + " " + tokens[columns.length + 1]);
            if (stamp == null || stamp.explicit()) {
                out.skipped("BAD_TIME");
                return;
            }
        } else {
            out.base(ParsedTelemetry.TimeBase.START_RELATIVE);
            stamp = Stamp.local(rowIndex * 1000L);
        }
        double[] values = new double[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (METRICS.containsKey(columns[i])) {
                double v = Sysstat.number(tokens[i]);
                if (Double.isNaN(v)) {
                    out.skipped("BAD_NUMBER");
                    return;
                }
                values[i] = v;
            }
        }
        for (int i = 0; i < columns.length; i++) {
            String key = METRICS.get(columns[i]);
            if (key != null) {
                out.series(key, Series.Kind.GAUGE).add(stamp, values[i]);
            }
        }
        if (!stamped && !intervalNoted) {
            intervalNoted = true;
            out.note("INTERVAL_ASSUMED_1S");
        }
        out.used();
    }

    @Override
    public void finish() {
        if (!stamped && out.hasSamples()) {
            out.base(ParsedTelemetry.TimeBase.START_RELATIVE);
        }
        if (!out.hasSamples()) {
            out.reason("NO_SAMPLES");
        }
    }
}
