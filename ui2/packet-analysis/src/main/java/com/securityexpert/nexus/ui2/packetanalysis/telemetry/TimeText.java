package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The timestamp spellings of Linux tools, parsed without any locale or JVM time zone. */
final class TimeText {
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    static final Pattern TIME_OF_DAY = Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?$");
    private static final Pattern ISO = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?\\s*(Z|UTC|GMT|[+-]\\d{2}(?::?\\d{2})?)?$");
    private static final Pattern DATE_CMD = Pattern.compile(
            "^[A-Za-z]{3},? +([A-Za-z]{3}) +(\\d{1,2}) +(\\d{2}):(\\d{2}):(\\d{2})(?:[.,]\\d+)?(?: +([A-Za-z]{1,5}|[+-]\\d{2}(?::?\\d{2})?))? +(\\d{4})$");
    private static final Pattern EPOCH = Pattern.compile("^(\\d{10})(?:\\.(\\d{1,9}))?$");
    private static final Pattern DECORATION = Pattern.compile("^[\\s#=\\-\\[\\]*<>~|]+|[\\s#=\\-\\[\\]*<>~|]+$");

    private TimeText() {
    }

    /** Milliseconds since midnight for {@code HH:MM:SS[.fff]} with an optional {@code AM}/{@code PM}; -1 when invalid. */
    static long timeOfDay(String token, String ampm) {
        Matcher m = TIME_OF_DAY.matcher(token);
        if (!m.matches()) {
            return -1;
        }
        int h = Integer.parseInt(m.group(1));
        int mi = Integer.parseInt(m.group(2));
        int s = Integer.parseInt(m.group(3));
        if (ampm != null) {
            if (h < 1 || h > 12) {
                return -1;
            }
            h = h % 12 + ("PM".equalsIgnoreCase(ampm) ? 12 : 0);
        }
        if (h > 23 || mi > 59 || s > 59) {
            return -1;
        }
        return ((h * 60L + mi) * 60L + s) * 1000L + millis(m.group(4));
    }

    static boolean isAmPm(String token) {
        return "AM".equalsIgnoreCase(token) || "PM".equalsIgnoreCase(token);
    }

    private static long millis(String fraction) {
        if (fraction == null) {
            return 0;
        }
        String f = (fraction + "00").substring(0, 3);
        return Long.parseLong(f);
    }

    static long wall(int year, int month, int day, int hour, int minute, int second, long millis) {
        return LocalDateTime.of(year, month, day, hour, minute, second).toEpochSecond(ZoneOffset.UTC) * 1000L + millis;
    }

    static long midnight(LocalDate date) {
        return date.toEpochDay() * 86_400_000L;
    }

    static int month(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(key)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Offset seconds for {@code Z}, {@code UTC}, {@code GMT}, {@code +03}, {@code +0300}, {@code +03:00}; NO_OFFSET otherwise. */
    static int offset(String text) {
        if (text == null) {
            return Stamp.NO_OFFSET;
        }
        if (text.equals("Z") || text.equals("UTC") || text.equals("GMT")) {
            return 0;
        }
        if (text.length() >= 3 && (text.charAt(0) == '+' || text.charAt(0) == '-') && Character.isDigit(text.charAt(1))) {
            String digits = text.substring(1).replace(":", "");
            if (digits.length() != 2 && digits.length() != 4) {
                return Stamp.NO_OFFSET;
            }
            int hours = Integer.parseInt(digits.substring(0, 2));
            int minutes = digits.length() == 4 ? Integer.parseInt(digits.substring(2)) : 0;
            if (hours > 14 || minutes > 59) {
                return Stamp.NO_OFFSET;
            }
            int seconds = hours * 3600 + minutes * 60;
            return text.charAt(0) == '-' ? -seconds : seconds;
        }
        return Stamp.NO_OFFSET;
    }

    /** {@code 2026-10-08 10:00:01.125}, {@code 2026-10-08T10:00:01+03:00}, {@code ...Z}; {@code null} when it is not that. */
    static Stamp iso(String text) {
        Matcher m = ISO.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        try {
            long wall = wall(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), millis(m.group(7)));
            return new Stamp(wall, offset(m.group(8)));
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    /** The output of {@code date}: {@code Thu Oct  8 10:00:01 UTC 2026}. */
    static Stamp dateCommand(String text) {
        Matcher m = DATE_CMD.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        int month = month(m.group(1));
        if (month < 0) {
            return null;
        }
        try {
            long wall = wall(Integer.parseInt(m.group(7)), month, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), 0);
            return new Stamp(wall, offset(m.group(6)));
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    /** {@code date +%s} or {@code %s.%N}: an absolute instant, so its offset is zero by definition. */
    static Stamp epoch(String text) {
        Matcher m = EPOCH.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        return new Stamp(Long.parseLong(m.group(1)) * 1000L + millis(m.group(2)), 0);
    }

    /** A line that is nothing but a timestamp, possibly framed by {@code #}, {@code =}, {@code -} or brackets. */
    static Stamp stampLine(String line) {
        String core = DECORATION.matcher(line).replaceAll("");
        if (core.isEmpty() || core.length() > 48 || !Character.isLetterOrDigit(core.charAt(0))) {
            return null;
        }
        Stamp stamp = iso(core);
        if (stamp == null) {
            stamp = dateCommand(core);
        }
        if (stamp == null) {
            stamp = epoch(core);
        }
        return stamp;
    }

    /**
     * The date of a sysstat banner: {@code 2026-10-08}, {@code 08.10.2026}, {@code 10/08/2026}, {@code 10/08/26}. A date
     * whose day and month are both 12 or less is read month first (sysstat's default) and flagged through
     * {@code ambiguous[0]}.
     */
    static LocalDate sarDate(String token, boolean[] ambiguous) {
        try {
            if (token.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(token);
            }
            if (token.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}")) {
                String[] p = token.split("\\.");
                return LocalDate.of(year(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            }
            if (token.matches("\\d{1,2}/\\d{1,2}/\\d{2,4}")) {
                String[] p = token.split("/");
                int a = Integer.parseInt(p[0]);
                int b = Integer.parseInt(p[1]);
                int y = year(p[2]);
                if (a > 12) {
                    return LocalDate.of(y, b, a);
                }
                if (b > 12) {
                    return LocalDate.of(y, a, b);
                }
                if (a != b) {
                    ambiguous[0] = true;
                }
                return LocalDate.of(y, a, b);
            }
        } catch (DateTimeException | NumberFormatException invalid) {
            return null;
        }
        return null;
    }

    private static int year(String text) {
        int y = Integer.parseInt(text);
        return text.length() <= 2 ? 2000 + y : y;
    }
}
