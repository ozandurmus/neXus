package com.securityexpert.nexus.ui2.service.report;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * The staleness scale every R1 report uses (contract "Stale data"). The single product-wide freshness scale is UI
 * review item B1, which has not landed; until it does, the existing Operations HA-readiness banner threshold applies
 * ({@code READINESS_STALE_AFTER_MS} in {@code HaReadinessList.tsx}: 24 hours). Stale data is labelled stale; a status is
 * never changed into "pass" or "ready" by age, and stale is never folded into fresh.
 */
public final class Freshness {

    /** HA-readiness banner threshold (24 h), standing in for B1. */
    public static final Duration STALE_AFTER = Duration.ofHours(24);

    private Freshness() {
    }

    public static boolean stale(Instant at, Instant now) {
        return at != null && Duration.between(at, now).compareTo(STALE_AFTER) > 0;
    }

    /** "Data as of" for one plane over the units' evidence times; a missing time is a unit without evidence. */
    public static ReportDocument.PlaneAsOf plane(String plane, Collection<Instant> evidence, int total, Instant now,
            String unit) {
        Objects.requireNonNull(evidence, "evidence");
        Instant newest = null;
        Instant oldest = null;
        int covered = 0;
        int stale = 0;
        for (Instant at : evidence) {
            if (at == null) continue;
            covered++;
            if (stale(at, now)) stale++;
            if (newest == null || at.isAfter(newest)) newest = at;
            if (oldest == null || at.isBefore(oldest)) oldest = at;
        }
        return new ReportDocument.PlaneAsOf(plane, newest, oldest, covered, total, stale, unit);
    }

    /** A compact age for tables: "45 min", "5 h", "3 d"; UNKNOWN without a time. */
    public static String age(Instant at, Instant now) {
        if (at == null) return "UNKNOWN";
        long minutes = Math.max(0, Duration.between(at, now).toMinutes());
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        if (hours < 48) return hours + " h";
        return (hours / 24) + " d";
    }

    /** One decimal place, the Overview's own rounding; "UNKNOWN" for an empty denominator (never 0% or 100%). */
    public static String pct(long numerator, long denominator) {
        if (denominator <= 0) return "UNKNOWN";
        return (Math.round(numerator * 1000.0 / denominator) / 10.0) + "%";
    }
}
