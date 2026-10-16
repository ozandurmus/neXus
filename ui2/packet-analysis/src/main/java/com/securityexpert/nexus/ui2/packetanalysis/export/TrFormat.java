package com.securityexpert.nexus.ui2.packetanalysis.export;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/** Turkish number and date text for the expert report; independent of the JVM's default locale. */
final class TrFormat {
    private static final String[] MONTHS = {"Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos",
        "Eylül", "Ekim", "Kasım", "Aralık"};

    private TrFormat() {
    }

    private static DecimalFormat format(String pattern) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setDecimalSeparator(',');
        symbols.setGroupingSeparator('.');
        DecimalFormat f = new DecimalFormat(pattern, symbols);
        f.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return f;
    }

    static String integer(long v) {
        return format("#,##0").format(v);
    }

    static String number(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "–";
        }
        String pattern = decimals <= 0 ? "#,##0" : "#,##0." + "0".repeat(decimals);
        String out = format(pattern).format(v);
        return "-0".equals(out) || out.matches("-0(,0+)?") ? out.substring(1) : out;
    }

    /** Milliseconds with a precision that grows as the value shrinks. */
    static String ms(Double v) {
        if (v == null) {
            return "–";
        }
        double a = Math.abs(v);
        return number(v, a < 10 ? 3 : a < 1000 ? 2 : 1);
    }

    static String percent(Double v) {
        return v == null ? "–" : number(v, v != 0 && Math.abs(v) < 0.1 ? 3 : 2) + "%";
    }

    static String epochToLocal(double epoch, ZoneId zone, boolean withDate) {
        ZonedDateTime t = ZonedDateTime.ofInstant(instantOf(epoch), zone);
        String clock = String.format(Locale.ROOT, "%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
        return withDate ? String.format(Locale.ROOT, "%02d.%02d %s", t.getDayOfMonth(), t.getMonthValue(), clock) : clock;
    }

    /** "16 Ekim 2026 13:00:12 (+03:00)" for the zone's own wall clock. */
    static String dateTime(double epoch, ZoneId zone) {
        ZonedDateTime t = ZonedDateTime.ofInstant(instantOf(epoch), zone);
        return date(t) + " " + String.format(Locale.ROOT, "%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond())
                + " (" + t.getOffset().getId().replace("Z", "+00:00") + ")";
    }

    static String date(ZonedDateTime t) {
        return t.getDayOfMonth() + " " + MONTHS[t.getMonthValue() - 1] + " " + t.getYear();
    }

    static String dateOf(double epoch, ZoneId zone) {
        return date(ZonedDateTime.ofInstant(instantOf(epoch), zone));
    }

    static Instant instantOf(double epoch) {
        long seconds = (long) Math.floor(epoch);
        long nanos = Math.round((epoch - seconds) * 1e9);
        return Instant.ofEpochSecond(seconds, nanos);
    }
}
