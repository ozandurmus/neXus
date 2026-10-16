package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a telemetry file's clock maps onto the capture timeline, as the user confirmed it: the time zone its wall clock is
 * in, an extra clock offset in seconds (added to the file's time after the zone is applied), and, for a file that
 * states no date or no time of its own, the local time of its first line. Nothing here is guessed: a file with no zone
 * is read as UTC and the report says that was an assumption.
 *
 * @param zone          zone the file's zone-less timestamps are written in
 * @param zoneConfirmed true when the user stated the zone; false means UTC was assumed
 * @param offsetSeconds added to the file's time (for example +3 when the server clock runs 3 s behind the capture clocks)
 * @param anchorLocal   {@code yyyy-MM-dd HH:mm:ss} (in {@code zone}) of the first line, or {@code null}
 */
public record Alignment(ZoneId zone, boolean zoneConfirmed, int offsetSeconds, String anchorLocal) {
    public static final int MAX_OFFSET_SECONDS = 86_400;
    private static final Pattern ZONE_TEXT = Pattern.compile("^[A-Za-z0-9_+:/-]{1,40}$");
    private static final Pattern ANCHOR = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?$");

    public Alignment {
        if (zone == null) {
            throw new IllegalArgumentException("zone");
        }
        if (Math.abs(offsetSeconds) > MAX_OFFSET_SECONDS) {
            throw new IllegalArgumentException("Clock offset must be within one day");
        }
        if (anchorLocal != null) {
            anchorMillis(anchorLocal);
        }
    }

    /** UTC, unconfirmed: what a file with no recorded alignment gets. */
    public static Alignment assumedUtc() {
        return new Alignment(ZoneOffset.UTC, false, 0, null);
    }

    /**
     * @param zoneText IANA id or fixed offset ({@code Europe/Istanbul}, {@code UTC}, {@code +03:00}); {@code null} or blank
     *                 keeps the UTC assumption
     * @throws IllegalArgumentException for an unknown zone, an offset over a day or an unreadable anchor
     */
    public static Alignment of(String zoneText, int offsetSeconds, String anchorLocal) {
        ZoneId zone = ZoneOffset.UTC;
        boolean confirmed = false;
        if (zoneText != null && !zoneText.isBlank()) {
            zone = parseZone(zoneText);
            confirmed = true;
        }
        String anchor = anchorLocal == null || anchorLocal.isBlank() ? null : anchorLocal.strip();
        if (anchor != null) {
            anchorMillis(anchor);
        }
        return new Alignment(zone, confirmed, offsetSeconds, anchor);
    }

    public static ZoneId parseZone(String text) {
        String t = text.strip();
        if (!ZONE_TEXT.matcher(t).matches()) {
            throw new IllegalArgumentException("Time zone must be an IANA id or an offset such as +03:00");
        }
        try {
            return ZoneId.of(t);
        } catch (DateTimeException unknown) {
            throw new IllegalArgumentException("Unknown time zone");
        }
    }

    /** Wall-clock milliseconds (as if UTC) of the anchor text. */
    public static long anchorMillis(String text) {
        Matcher m = ANCHOR.matcher(text.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("Anchor must look like 2026-10-08 10:00:00");
        }
        try {
            long ms = m.group(7) == null ? 0 : Long.parseLong((m.group(7) + "00").substring(0, 3));
            return TimeText.wall(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), ms);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("Anchor is not a valid date and time");
        }
    }

    /** Short name of the zone for the report: {@code UTC} or the zone id. */
    public String zoneLabel() {
        String id = zone.getId();
        return id.equals("Z") ? "UTC" : id;
    }

    int zoneOffsetSeconds(long wallMillis) {
        if (zone instanceof ZoneOffset fixed) {
            return fixed.getTotalSeconds();
        }
        return zone.getRules().getOffset(LocalDateTime.ofEpochSecond(Math.floorDiv(wallMillis, 1000L), 0, ZoneOffset.UTC))
                .getTotalSeconds();
    }
}
