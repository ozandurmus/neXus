package com.securityexpert.nexus.ui2.service.script;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.scheduling.support.CronExpression;

/**
 * Five-field cron normalization, validation and server-side "next five fires" preview
 * ({@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.1, 3.4 and 3.6).
 */
public final class ScriptSchedule {

    public static final int PREVIEW_FIRE_COUNT = 5;

    /** The zone five-field cron is read in unless {@code UI2_SCRIPT_SCHEDULE_ZONE} names another (PO works in Istanbul). */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Istanbul");

    /** {@code UI2_SCRIPT_SCHEDULE_ZONE} when it is a valid zone id, else {@link #DEFAULT_ZONE}. */
    public static ZoneId zone() {
        return zoneOf(System.getenv("UI2_SCRIPT_SCHEDULE_ZONE"));
    }

    static ZoneId zoneOf(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(configured.strip());
        } catch (RuntimeException e) {
            return DEFAULT_ZONE;
        }
    }

    private ScriptSchedule() {
    }

    /**
     * Validates and normalizes a five-field cron expression ({@code minute hour day-of-month month day-of-week}).
     * Six-field expressions are rejected because the contract specifies five-field cron.
     */
    public static Optional<String> normalizeFiveFieldCron(String cron) {
        if (cron == null || cron.isBlank()) {
            return Optional.empty();
        }
        String[] fields = cron.strip().split("\\s+");
        if (fields.length != 5) {
            return Optional.empty();
        }
        String normalized = String.join(" ", fields);
        try {
            CronExpression.parse("0 " + normalized);
            return Optional.of(normalized);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static boolean isValidFiveFieldCron(String cron) {
        return normalizeFiveFieldCron(cron).isPresent();
    }

    /** Computes the next five fire instants strictly after {@code from} in {@link #zone()}. */
    public static List<Instant> nextFiveFires(String cron, Instant from) {
        return nextFiveFires(cron, from, zone());
    }

    /** Computes the next five fire instants strictly after {@code from} in {@code zone}. */
    public static List<Instant> nextFiveFires(String cron, Instant from, ZoneId zone) {
        Optional<String> normalized = normalizeFiveFieldCron(cron);
        if (normalized.isEmpty() || from == null || zone == null) {
            return List.of();
        }
        CronExpression expression = CronExpression.parse("0 " + normalized.get());
        List<Instant> fires = new ArrayList<>(PREVIEW_FIRE_COUNT);
        ZonedDateTime cursor = from.atZone(zone);
        for (int i = 0; i < PREVIEW_FIRE_COUNT; i++) {
            ZonedDateTime next = expression.next(cursor);
            if (next == null) {
                break;
            }
            fires.add(next.toInstant());
            cursor = next;
        }
        return List.copyOf(fires);
    }
}
