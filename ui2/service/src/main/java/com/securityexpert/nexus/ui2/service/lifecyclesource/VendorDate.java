package com.securityexpert.nexus.ui2.service.lifecyclesource;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict reader of the date forms the vendors' lifecycle pages use (contract section 4). Every form is listed here; any
 * other text is {@link Kind#UNREADABLE} and makes the entry NEEDS_REVIEW. Nothing is guessed:
 * <ul>
 *   <li>a day form ({@code November 18, 2025}, {@code Mar 22, 2027}, {@code July 30th, 2026}, {@code July 31 2025},
 *       {@code 31-Oct-2014}, {@code 31 December 2013}, {@code 2026-11-13}) is a {@link Kind#DAY};</li>
 *   <li>a month form ({@code Oct-2026}, {@code September 2030}, {@code Feb 2030}) is a {@link Kind#MONTH}, stored as the
 *       first day of the month and shown as the month;</li>
 *   <li>a two-digit year ({@code Oct-28}, {@code 31-Dec-18}) and a numeric day/month order ({@code 06/30/2028}) are
 *       ambiguous and {@link Kind#UNREADABLE};</li>
 *   <li>an empty cell and the placeholders {@code N/A}, {@code NA}, {@code -}, {@code TBD}, {@code TBA}, {@code Latest}
 *       are {@link Kind#NONE}: the vendor publishes no date (yet).</li>
 * </ul>
 * One trailing footnote mark ({@code ^}, {@code *}, {@code †}, {@code +}) is removed before reading; nothing else is.
 */
public final class VendorDate {

    public enum Kind { DAY, MONTH, NONE, UNREADABLE }

    public record Read(Kind kind, LocalDate date) {
        static final Read NONE = new Read(Kind.NONE, null);
        static final Read UNREADABLE = new Read(Kind.UNREADABLE, null);
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("january", 1), Map.entry("feb", 2), Map.entry("february", 2),
            Map.entry("mar", 3), Map.entry("march", 3), Map.entry("apr", 4), Map.entry("april", 4), Map.entry("may", 5),
            Map.entry("jun", 6), Map.entry("june", 6), Map.entry("jul", 7), Map.entry("july", 7), Map.entry("aug", 8),
            Map.entry("august", 8), Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("september", 9),
            Map.entry("oct", 10), Map.entry("october", 10), Map.entry("nov", 11), Map.entry("november", 11),
            Map.entry("dec", 12), Map.entry("december", 12));

    private static final Pattern MONTH_DAY_YEAR = Pattern.compile("^([A-Za-z]{3,9})\\.? (\\d{1,2})(?:st|nd|rd|th)?,? (\\d{4})$");
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("^(\\d{1,2})[- ]([A-Za-z]{3,9})[- ](\\d{4})$");
    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern MONTH_YEAR = Pattern.compile("^([A-Za-z]{3,9})\\.?[- ](\\d{4})$");
    private static final Pattern NONE_WORDS = Pattern.compile("^(?:|n/?a|-|–|—|tbd|tba|latest)$", Pattern.CASE_INSENSITIVE);

    private VendorDate() {
    }

    public static Read read(String text) {
        if (text == null) return Read.NONE;
        String value = text.replace(' ', ' ').strip().replaceAll("\\s+", " ");
        value = value.replaceFirst("\\s?[\\^*†+]$", "");
        if (value.length() > 40) return Read.UNREADABLE;
        if (NONE_WORDS.matcher(value).matches()) return Read.NONE;
        try {
            Matcher m = MONTH_DAY_YEAR.matcher(value);
            if (m.matches()) return day(m.group(3), month(m.group(1)), m.group(2));
            m = DAY_MONTH_YEAR.matcher(value);
            if (m.matches()) return day(m.group(3), month(m.group(2)), m.group(1));
            m = ISO.matcher(value);
            if (m.matches()) return day(m.group(1), Integer.parseInt(m.group(2)), m.group(3));
            m = MONTH_YEAR.matcher(value);
            if (m.matches()) {
                Integer month = month(m.group(1));
                return month == null ? Read.UNREADABLE : new Read(Kind.MONTH, LocalDate.of(Integer.parseInt(m.group(2)), month, 1));
            }
        } catch (DateTimeException | NumberFormatException e) {
            return Read.UNREADABLE;
        }
        return Read.UNREADABLE;
    }

    private static Read day(String year, Integer month, String day) {
        if (month == null) return Read.UNREADABLE;
        return new Read(Kind.DAY, LocalDate.of(Integer.parseInt(year), month, Integer.parseInt(day)));
    }

    private static Integer month(String name) {
        return MONTHS.get(name.toLowerCase(Locale.ROOT));
    }
}
