package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device-local line times of the analysed sources (design sections 1.2 and 4.1; times stay {@code DEVICE_LOCAL}, never
 * converted). {@code cp_messages}: {@code <Mon> <DD> <HH:MM:SS> <YYYY> <host> ...} (phase B findings, all 1,060 sampled
 * lines). {@code pan_system}: the {@code receive_time} field, {@code YYYY/MM/DD HH:MM:SS}.
 */
public final class LogLineTimes {
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final Pattern GAIA = Pattern.compile(
            "^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2}) (\\d{4})\\b");
    private static final Pattern PAN = Pattern.compile(
            "\"receive_time\"\\s*:\\s*\"(\\d{4})/(\\d{2})/(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\"");

    private LogLineTimes() {
    }

    /** The device-local time of a {@code cp_messages} line, or null when the line has no readable time. */
    public static LocalDateTime gaia(String line) {
        Matcher m = GAIA.matcher(line);
        if (!m.find()) return null;
        int month = 0;
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(m.group(1))) month = i + 1;
        }
        return of(Integer.parseInt(m.group(6)), month, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
    }

    /** The device-local {@code receive_time} of a {@code pan_system} entry, or null. */
    public static LocalDateTime pan(String line) {
        Matcher m = PAN.matcher(line);
        if (!m.find()) return null;
        return of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
    }

    private static LocalDateTime of(int year, int month, int day, int hour, int minute, int second) {
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }
}
