package com.securityexpert.nexus.ui2.worker.systemlog;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A parsed log-line time: the device's local wall-clock reading, basis {@code DEVICE_LOCAL}, never converted to
 * another zone (no source prints a zone, contract section 4.7), or {@link #UNKNOWN}.
 */
public record LineTime(boolean known, LocalDateTime value) {
    public static final LineTime UNKNOWN = new LineTime(false, null);
    private static final DateTimeFormatter TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public LineTime {
        if (known == (value == null)) {
            throw new IllegalArgumentException("a known time needs a value and an unknown one must not have it");
        }
    }

    public static LineTime of(LocalDateTime value) {
        return new LineTime(true, value);
    }

    /** The stored text form ({@code yyyy-MM-dd HH:mm:ss}), or null when unknown. */
    public String text() {
        return known ? TEXT.format(value) : null;
    }
}
