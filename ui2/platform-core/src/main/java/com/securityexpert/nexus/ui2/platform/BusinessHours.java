package com.securityexpert.nexus.ui2.platform;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Business hours (PO decision 2026-10-20,
 * {@code docs/design/PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md}): every day,
 * {@code [start, end)} in {@link JobWindowPolicy#ZONE}. Inside them an administrator's manual device job needs an
 * explicit acknowledgement; outside them it needs none. This class is the single source of truth for the hours;
 * {@code UI2_BUSINESS_HOURS=HH:mm-HH:mm} overrides the default {@code 08:00-20:00}.
 */
public final class BusinessHours {
    public static final LocalTime DEFAULT_START = LocalTime.of(8, 0);
    public static final LocalTime DEFAULT_END = LocalTime.of(20, 0);
    public static final String ENVIRONMENT = "UI2_BUSINESS_HOURS";
    public static final BusinessHours SYSTEM = parse(System.getenv(ENVIRONMENT));

    private final LocalTime start;
    private final LocalTime end;

    public BusinessHours(LocalTime start, LocalTime end) {
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) throw new IllegalArgumentException(ENVIRONMENT + " start must be before its end");
    }

    /** {@code null} or blank means the default; anything else must read {@code HH:mm-HH:mm}. */
    public static BusinessHours parse(String value) {
        if (value == null || value.isBlank()) return new BusinessHours(DEFAULT_START, DEFAULT_END);
        String[] parts = value.trim().split("-");
        if (parts.length != 2) throw new IllegalArgumentException(ENVIRONMENT + " must read HH:mm-HH:mm");
        return new BusinessHours(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()));
    }

    public LocalTime start() { return start; }
    public LocalTime end() { return end; }

    public boolean inside(Instant instant) {
        LocalTime local = instant.atZone(JobWindowPolicy.ZONE_ID).toLocalTime();
        return !local.isBefore(start) && local.isBefore(end);
    }

    /** {@code 08:00–20:00}. */
    public String label() { return start + "–" + end; }

    /** The words the UI and the CLI show before an administrator confirms (product language: English). */
    public String confirmMessage() {
        return "Business hours (" + label() + " GMT+3). This sends commands to the device. Continue?";
    }
}
