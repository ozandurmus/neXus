package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads one stored {@code cp_sar_daily} text (yesterday's {@code /var/log/sa/sar<DD>}) into per-day counters: the
 * p95 of CPU busy ({@code 100 - %idle} of the {@code all} row) and, when the report carries a memory section, the
 * p95 of {@code %memused}. Nothing else is kept; the text is read once as a stream and never held whole.
 *
 * <p><b>Shape relied on</b> (DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md section 4.1; the values themselves
 * were masked in the measurement, so the column vocabulary below is the sysstat text form and is marked as such):</p>
 * <ul>
 *   <li>line 1: {@code Linux <kernel> (<host>)<TAB><date><TAB><arch> (<N> CPU)}; a blank line follows;</li>
 *   <li>a section header: a {@code HH:MM:SS} time (24-hour; no AM/PM token was measured) and 11 more
 *       whitespace-separated column names ({@code CPU} plus ten value columns on all 53 devices);</li>
 *   <li>then one {@code all} row and one row per CPU, each starting with the time; a sample is one {@code all} row;</li>
 *   <li>sections are separated by blank lines and each has its own header; {@code Average:} rows close a section.</li>
 * </ul>
 * The CPU section is recognised by a header that holds {@code CPU} and {@code %idle}; the memory section by a header
 * that holds {@code %memused} (sysstat {@code sar -r}; <b>not measured</b> on this fleet, so a report without it
 * yields a memory p95 of {@code null}, shown as UNKNOWN). A row whose column count differs from its header, or whose
 * value does not parse, is counted in {@code unparsedRows} and skipped. Decimal commas are accepted beside decimal
 * points (locale of the device is not known).
 */
public final class SarDailyText {
    /** A header line starts with a 24-hour time and names its columns; nothing in the time position may be a value. */
    private static final Pattern TIME = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}$");
    /** A line longer than this is not a sar row and is skipped unread beyond the bound. */
    static final int MAX_LINE_CHARS = 4096;

    /** The counters of one sar day; {@code memUsedP95} is null when the report has no memory section. */
    public record Summary(int cpuCount, int cpuSamples, Double cpuBusyP95, Double cpuBusyMax, int memSamples, Double memUsedP95,
            int unparsedRows, boolean headerSeen) {
    }

    private SarDailyText() {
    }

    public static Summary parse(InputStream in) throws IOException {
        List<Double> busy = new ArrayList<>();
        List<Double> mem = new ArrayList<>();
        int cpuCount = -1;
        int unparsed = 0;
        boolean headerSeen = false;
        String[] header = null;
        int idleIndex = -1;
        int memUsedIndex = -1;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1), 64 * 1024)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_LINE_CHARS) {
                    unparsed++;
                    continue;
                }
                if (first) {
                    first = false;
                    if (line.startsWith("Linux ")) {
                        headerSeen = true;
                        cpuCount = cpuCountOf(line);
                        continue;
                    }
                }
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    header = null;
                    idleIndex = -1;
                    memUsedIndex = -1;
                    continue;
                }
                String[] columns = trimmed.split("\\s+");
                if (!TIME.matcher(columns[0]).matches()) {
                    // "Average:" rows and anything else without a time close nothing and count as nothing.
                    continue;
                }
                if (header == null) {
                    header = columns;
                    idleIndex = indexOf(header, "%idle");
                    memUsedIndex = indexOf(header, "%memused");
                    continue;
                }
                if (columns.length != header.length) {
                    unparsed++;
                    continue;
                }
                if (idleIndex > 0 && header.length > 1 && "CPU".equals(header[1])) {
                    if (!"all".equals(columns[1])) continue; // per-CPU rows: the p95 is of the whole device
                    Double idle = value(columns[idleIndex]);
                    if (idle == null || idle < 0 || idle > 100) {
                        unparsed++;
                        continue;
                    }
                    busy.add(100.0 - idle);
                } else if (memUsedIndex > 0) {
                    Double used = value(columns[memUsedIndex]);
                    if (used == null || used < 0 || used > 100) {
                        unparsed++;
                        continue;
                    }
                    mem.add(used);
                }
            }
        }
        return new Summary(cpuCount, busy.size(), p95(busy), busy.isEmpty() ? null : Collections.max(busy), mem.size(),
                p95(mem), unparsed, headerSeen);
    }

    /** The CPU count in the first line's trailing {@code (<N> CPU)}; -1 when the shape is not there. */
    static int cpuCountOf(String firstLine) {
        int close = firstLine.lastIndexOf(" CPU)");
        if (close < 0) return -1;
        int open = firstLine.lastIndexOf('(', close);
        if (open < 0) return -1;
        try {
            int n = Integer.parseInt(firstLine.substring(open + 1, close).strip());
            return n > 0 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Nearest-rank p95: the value at rank {@code ceil(0.95 * n)} (1-based) of the ascending sorted sample, so it is
     * always one of the samples and never interpolated; null for an empty sample.
     */
    public static Double p95(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size(), rank) - 1));
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equalsIgnoreCase(header[i])) return i;
        }
        return -1;
    }

    private static Double value(String text) {
        try {
            return Double.parseDouble(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
