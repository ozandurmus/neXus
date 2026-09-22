package com.securityexpert.nexus.ui2.service.device.backup;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.springframework.scheduling.support.CronExpression;

/**
 * Pure schedule arithmetic for the backup scheduler (V44): a five-field cron
 * evaluated in UTC. A slot is due when its most recent occurrence lies after
 * the last recorded run and no more than {@code catchUpWindowSeconds} in the
 * past -- a service that was down for a day runs the slot it missed once,
 * not once per minute it was away, and never a slot older than the window.
 */
public final class BackupScheduleDue {

    private BackupScheduleDue() {
    }

    /** Operators write the classic five-field form; Spring's parser wants six (seconds first). Both are accepted. */
    static String normalize(String cron) {
        String[] fields = cron.strip().split("\\s+");
        return fields.length == 5 ? "0 " + String.join(" ", fields) : String.join(" ", fields);
    }

    public static boolean isValidCron(String cron) {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        try {
            CronExpression.parse(normalize(cron));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The slot to run now, if any: the latest cron occurrence at or before {@code now} that the last run does not cover. */
    public static Optional<Instant> dueSlot(String cron, Optional<Instant> lastRun, Instant now, long catchUpWindowSeconds) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(normalize(cron));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        ZonedDateTime windowStart = now.minusSeconds(catchUpWindowSeconds).atZone(ZoneOffset.UTC);
        ZonedDateTime candidate = expression.next(windowStart);
        ZonedDateTime latest = null;
        while (candidate != null && !candidate.toInstant().isAfter(now)) {
            latest = candidate;
            candidate = expression.next(candidate);
        }
        if (latest == null) {
            return Optional.empty();
        }
        Instant slot = latest.toInstant();
        if (lastRun.isPresent() && !lastRun.get().isBefore(slot)) {
            return Optional.empty();
        }
        return Optional.of(slot);
    }
}
