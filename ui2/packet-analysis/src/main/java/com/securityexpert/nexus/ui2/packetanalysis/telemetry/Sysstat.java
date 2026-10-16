package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared reading of sysstat's text output (sar and pidstat): the banner line with host and date, 12 or 24 hour clocks,
 * header lines, and the midnight rollover of a section. Header lines are recognised by their column names, never by
 * position, so any mix of {@code sar -u -q -w -n DEV,TCP,ETCP} blocks and {@code sar -A} is read the same way.
 */
abstract class Sysstat implements LineParser {
    static final Pattern BANNER = Pattern.compile(
            "^Linux\\s+\\S+\\s+\\(([^)]*)\\)\\s+(\\S+)(?:\\s+_\\S+_)?\\s+\\((\\d+)\\s+CPU\\)\\s*$");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NUMBER = Pattern.compile("^[+-]?(?:\\d+(?:[.,]\\d+)?|\\.\\d+|nan|inf|infinity)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FINITE = Pattern.compile("^[+-]?(?:\\d+(?:[.,]\\d+)?|\\.\\d+)$");

    protected final ParsedTelemetry out;
    private LocalDate date;
    private int dayOffset;
    private long lastTimeOfDay = -1;
    private List<String> section = List.of();

    Sysstat(ParsedTelemetry out) {
        this.out = out;
    }

    static String[] split(String text) {
        return SPACES.split(text.strip());
    }

    static boolean isNumber(String token) {
        return NUMBER.matcher(token).matches();
    }

    /** A finite decimal ({@code 12.50} or {@code 12,50}); {@code NaN} when the token is anything else. */
    static double number(String token) {
        if (!FINITE.matcher(token).matches()) {
            return Double.NaN;
        }
        return Double.parseDouble(token.replace(',', '.'));
    }

    static int nonNumeric(String[] tokens, int from) {
        int count = 0;
        for (int i = from; i < tokens.length; i++) {
            if (!isNumber(tokens[i])) {
                count++;
            }
        }
        return count;
    }

    /** Reads a banner line; true when the line was one. */
    protected final boolean banner(String line) {
        Matcher m = BANNER.matcher(line.strip());
        if (!m.matches()) {
            return false;
        }
        out.hostName(m.group(1));
        boolean[] ambiguous = new boolean[1];
        LocalDate parsed = TimeText.sarDate(m.group(2), ambiguous);
        if (parsed == null) {
            out.note("BANNER_DATE_UNREADABLE");
        } else {
            date = parsed;
            dayOffset = 0;
            lastTimeOfDay = -1;
            out.base(ParsedTelemetry.TimeBase.ABSOLUTE);
            if (ambiguous[0]) {
                out.note("DATE_ASSUMED_MONTH_FIRST");
            }
        }
        return true;
    }

    /** True when the banner gave no date, so the alignment's anchor has to. */
    protected final boolean dateless() {
        return date == null;
    }

    protected final void markSectionStart(List<String> columns) {
        if (!columns.equals(section)) {
            section = columns;
            dayOffset = 0;
            lastTimeOfDay = -1;
        }
    }

    /** The stamp of a row at {@code timeOfDay}; a time earlier than the previous row's means the clock passed midnight. */
    protected final Stamp stamp(long timeOfDay) {
        if (lastTimeOfDay >= 0 && timeOfDay < lastTimeOfDay) {
            dayOffset++;
            out.note("MIDNIGHT_ROLLOVER");
        }
        lastTimeOfDay = timeOfDay;
        long relative = dayOffset * 86_400_000L + timeOfDay;
        if (date == null) {
            out.base(ParsedTelemetry.TimeBase.DATE_RELATIVE);
            return Stamp.local(relative);
        }
        return Stamp.local(TimeText.midnight(date) + relative);
    }

    /** The tokens of a line after its leading clock reading, or {@code null} when the line does not start with one. */
    protected static final class Timed {
        final long timeOfDay;
        final String[] rest;

        Timed(long timeOfDay, String[] rest) {
            this.timeOfDay = timeOfDay;
            this.rest = rest;
        }
    }

    /** {@code null}: not a timed line. A timed line with an unreadable clock returns {@code timeOfDay == -1}. */
    protected static Timed timed(String[] tokens) {
        if (tokens.length == 0 || !TimeText.TIME_OF_DAY.matcher(tokens[0]).matches()) {
            return null;
        }
        int next = 1;
        String ampm = null;
        if (tokens.length > 1 && TimeText.isAmPm(tokens[1])) {
            ampm = tokens[1];
            next = 2;
        }
        return new Timed(TimeText.timeOfDay(tokens[0], ampm), Arrays.copyOfRange(tokens, next, tokens.length));
    }

    /** Column positions by name for one header line. */
    protected static Map<String, Integer> index(String[] columns) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < columns.length; i++) {
            map.putIfAbsent(columns[i], i);
        }
        return map;
    }

    protected static Map<String, String> metrics(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
