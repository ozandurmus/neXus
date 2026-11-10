package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared text and date helpers of the lifecycle parsers. Every parser is pure and total: nothing here throws on any input.
 * Label and detail rules mirror the schema checks of V200 (label {@code ^[A-Za-z0-9 _./+()&,:-]{1,120}$}, detail the same
 * set plus {@code =}, 160 characters); a text that cannot satisfy them gives {@code null} and the caller skips the item.
 */
final class LifecycleText {

    static final int MAX_CHARS = 1_000_000;
    static final int MAX_LINE = 4_000;
    static final int MAX_LINES = 20_000;
    static final int LABEL_MAX = 120;
    static final int DETAIL_MAX = 160;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9 _./+()&,:-]{1,120}");
    private static final Pattern DETAIL = Pattern.compile("[A-Za-z0-9 _./+()&,:=-]{1,160}");
    private static final Pattern ISO_PREFIX = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(?:[T ].*)?", Pattern.DOTALL);
    private static final Pattern SLASH_DATE = Pattern.compile("(\\d{4})/(\\d{1,2})/(\\d{1,2})");
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("(\\d{1,2})([A-Za-z]{3})(\\d{4})");
    private static final Pattern MONTH_DAY_COMMA_YEAR = Pattern.compile("([A-Za-z]{3,9})\\.? (\\d{1,2}),? (\\d{4})");
    private static final Pattern WEEKDAY_MONTH_DAY_YEAR = Pattern.compile("(?:[A-Za-z]{3,9} )?([A-Za-z]{3,9}) (\\d{1,2}) (\\d{4})");
    private static final Map<String, Month> MONTHS = months();

    private LifecycleText() {
    }

    private static Map<String, Month> months() {
        Map<String, Month> map = new HashMap<>();
        for (Month month : Month.values()) {
            String full = month.name().toLowerCase(Locale.ROOT);
            map.put(full, month);
            map.put(full.substring(0, 3), month);
        }
        map.put("sept", Month.SEPTEMBER);
        return map;
    }

    /** The lines of an answer, bounded in count and length; null gives none. */
    static List<String> lines(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        String bounded = raw.length() > MAX_CHARS ? raw.substring(0, MAX_CHARS) : raw;
        List<String> out = new ArrayList<>();
        for (String line : bounded.split("\\R", -1)) {
            out.add(line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line);
            if (out.size() >= MAX_LINES) {
                break;
            }
        }
        return out;
    }

    /** Leading and trailing white space removed, inner runs collapsed to one space. */
    static String collapse(String text) {
        return text == null ? "" : WHITESPACE.matcher(text.strip()).replaceAll(" ");
    }

    /** A clean label, or null. */
    static String label(String text) {
        String s = cut(collapse(text), LABEL_MAX);
        return LABEL.matcher(s).matches() ? s : null;
    }

    /** A clean detail, or null. */
    static String detail(String text) {
        String s = cut(collapse(text), DETAIL_MAX);
        return DETAIL.matcher(s).matches() ? s : null;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max).strip() : s;
    }

    static boolean isDigits(String s) {
        if (s == null || s.isEmpty() || s.length() > 9) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static LocalDate of(int year, Month month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static LocalDate of(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** {@code yyyy-MM-dd}, or a longer ISO timestamp read by its first ten characters; null otherwise. */
    static LocalDate isoDate(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = ISO_PREFIX.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        return of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    /** Check Point {@code dMMMyyyy} (30Dec2026; one-digit days accepted). */
    static LocalDate checkPointDate(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = DAY_MONTH_YEAR.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        Month month = MONTHS.get(m.group(2).toLowerCase(Locale.ROOT));
        return month == null ? null : of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)));
    }

    /** True when the text has the shape of a Check Point date, whether or not it is a real day. */
    static boolean looksLikeCheckPointDate(String text) {
        return text != null && DAY_MONTH_YEAR.matcher(text.strip()).matches();
    }

    /** FortiGate {@code EEE MMM d yyyy}; the weekday is ignored, white space is collapsed first. */
    static LocalDate fortinetDate(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = WEEKDAY_MONTH_DAY_YEAR.matcher(collapse(text));
        if (!m.matches()) {
            return null;
        }
        Month month = MONTHS.get(m.group(1).toLowerCase(Locale.ROOT));
        return month == null ? null : of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(2)));
    }

    /** ASA {@code MMM d yyyy} (three-letter month, English). */
    static LocalDate asaDate(String text) {
        if (text == null) {
            return null;
        }
        String[] p = collapse(text).split(" ");
        if (p.length != 3 || p[0].length() != 3 || !isDigits(p[1]) || p[2].length() != 4 || !isDigits(p[2])) {
            return null;
        }
        Month month = MONTHS.get(p[0].toLowerCase(Locale.ROOT));
        return month == null ? null : of(Integer.parseInt(p[2]), month, Integer.parseInt(p[1]));
    }

    /**
     * Palo Alto: {@code MMMM dd, yyyy}, {@code MMM dd, yyyy}, {@code yyyy/MM/dd}, {@code yyyy-MM-dd}. {@code Never} is
     * handled by the caller (see {@link #isNever}).
     */
    static LocalDate panDate(String text) {
        if (text == null) {
            return null;
        }
        String s = collapse(text);
        Matcher m = MONTH_DAY_COMMA_YEAR.matcher(s);
        if (m.matches()) {
            Month month = MONTHS.get(m.group(1).toLowerCase(Locale.ROOT));
            return month == null ? null : of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(2)));
        }
        m = SLASH_DATE.matcher(s);
        if (m.matches()) {
            return of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        return isoDateExact(s);
    }

    private static LocalDate isoDateExact(String s) {
        return s.length() == 10 ? isoDate(s) : null;
    }

    static boolean isNever(String text) {
        return text != null && collapse(text).equalsIgnoreCase("never");
    }
}
