package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleRecord;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Enforces booking-time admission control for scheduled maintenance window failovers.
 * Invariants:
 * 1. Maximum scheduling lead time: 7 days.
 * 2. Fleet concurrency is 1: no overlapping [windowStart, executionDeadline) across active fleet schedules.
 * 3. Daily volume cap: max 10 scheduled executions in the exact rolling 24-hour window
 *    {@code [windowStart - 24h, windowStart]} (CF-P1.14 finding 2: the prior check used
 *    {@code [windowStart - 1d, windowStart + 1d]}, a 48-hour window).
 * 4. Inter-failover cooldown: minimum 2 hours between failovers on the same cluster, measured
 *    directionally -- the gap from whichever window ends first to whichever window starts second
 *    (CF-P1.14 finding 3: {@code Duration.between(existing.windowEnd(), windowStart).abs()} measured
 *    end-to-start in one direction only and {@code abs()} masked the true gap for a new window placed
 *    before an existing one).
 *
 * <p>This class validates a caller-supplied snapshot of existing schedules; it does not itself
 * read or lock durable storage. Cross-instance atomicity of the read-then-admit sequence is the
 * enforced-singleton-deployment gap the Engine Final Review names (§9 item 2) until full
 * durability lands -- {@code synchronized} here only serializes concurrent admission checks within
 * one JVM instance.</p>
 */
@Component
public class FailoverBookingAdmissionControl {

    public static final Duration MAX_LEAD_TIME = Duration.ofDays(7);
    public static final Duration MIN_COOLDOWN = Duration.ofHours(2);
    public static final Duration VOLUME_ROLLING_WINDOW = Duration.ofHours(24);
    public static final int MAX_SCHEDULED_PER_24H = 10;

    public synchronized void validateBookingAdmission(
        Instant windowStart,
        Instant windowEnd,
        int maxStartDelayMinutes,
        String clusterRef,
        Collection<FailoverScheduleRecord> existingSchedules
    ) {
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(windowEnd, "windowEnd must not be null");
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(existingSchedules, "existingSchedules must not be null");

        Instant now = Instant.now();

        // 1. Minimum start time (must be in future)
        if (!windowStart.isAfter(now)) {
            throw new IllegalArgumentException("windowStart must be in the future (UTC)");
        }

        // 2. Maximum lead time cap (7 days)
        Instant maxAllowedStart = now.plus(MAX_LEAD_TIME);
        if (windowStart.isAfter(maxAllowedStart)) {
            throw new IllegalArgumentException("Scheduling lead time exceeds maximum allowable window of " +
                MAX_LEAD_TIME.toDays() + " days (requested: " + windowStart + " > max: " + maxAllowedStart + ")");
        }

        // 3. Window duration and delay
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("windowEnd must be strictly after windowStart");
        }
        if (maxStartDelayMinutes <= 0 || maxStartDelayMinutes > 120) {
            throw new IllegalArgumentException("maxStartDelayMinutes must be between 1 and 120 minutes");
        }

        Instant newDeadline = FailoverScheduleRecord.computeExecutionDeadline(windowStart, windowEnd, maxStartDelayMinutes);

        // 4. Overlap Admission Check across fleet (concurrency = 1)
        int count24h = 0;
        // CF-P1.14 finding 2: exact rolling 24-hour window ending at the requested start,
        // not a +/-1-day (48-hour) window.
        Instant rollingWindowStart = windowStart.minus(VOLUME_ROLLING_WINDOW);

        for (FailoverScheduleRecord existing : existingSchedules) {
            if (existing.status().isTerminal() && existing.status() != FailoverScheduleStatus.COMPLETED) {
                continue; // Cancelled or aborted schedules do not block booking
            }

            // Fleet overlap check for active/scheduled windows
            if (existing.status().isRunnable() || existing.status() == FailoverScheduleStatus.CLAIMED_VERIFYING) {
                boolean overlaps = windowStart.isBefore(existing.executionDeadline()) &&
                    existing.windowStart().isBefore(newDeadline);
                if (overlaps) {
                    throw new IllegalStateException("Requested window [" + windowStart + ", " + newDeadline +
                        ") overlaps with an existing scheduled maintenance window " + existing.scheduleId() +
                        " [" + existing.windowStart() + ", " + existing.executionDeadline() + ") under fleet concurrency limit = 1");
                }
            }

            // 5. Inter-failover cooldown on the same cluster (CF-P1.14 finding 3: directional gap,
            // not an absolute-value single-direction measurement).
            if (existing.clusterRef().equals(clusterRef)) {
                Duration gap;
                boolean newWindowAfterExisting = !windowStart.isBefore(existing.windowEnd());
                boolean newWindowBeforeExisting = !windowEnd.isAfter(existing.windowStart());
                if (newWindowAfterExisting) {
                    gap = Duration.between(existing.windowEnd(), windowStart);
                } else if (newWindowBeforeExisting) {
                    gap = Duration.between(windowEnd, existing.windowStart());
                } else {
                    gap = Duration.ZERO; // the windows overlap in time; that alone violates cooldown
                }
                if (gap.compareTo(MIN_COOLDOWN) < 0) {
                    throw new IllegalStateException("Requested window violates minimum inter-failover cooldown of " +
                        MIN_COOLDOWN.toHours() + " hours for cluster " + clusterRef);
                }
            }

            // Volume tracking: existing.windowStart() in [windowStart - 24h, windowStart]
            if (!existing.windowStart().isBefore(rollingWindowStart) && !existing.windowStart().isAfter(windowStart)) {
                count24h++;
            }
        }

        // 6. Volume cap
        if (count24h >= MAX_SCHEDULED_PER_24H) {
            throw new IllegalStateException("Maximum 24-hour fleet execution volume cap reached (" +
                count24h + " >= " + MAX_SCHEDULED_PER_24H + " max executions)");
        }
    }
}
