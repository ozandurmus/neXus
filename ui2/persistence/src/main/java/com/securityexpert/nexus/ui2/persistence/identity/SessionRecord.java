package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * A read view of one {@code sessions} row (C3 §3.1).
 */
public record SessionRecord(
        String sessionId,
        String actorFingerprint,
        String csrfSecret,
        SessionState state,
        Instant createdAt,
        Instant lastSeenAt,
        Instant idleDeadlineAt,
        Instant absoluteExpiresAt,
        Optional<String> supersededBySessionId,
        Optional<String> endedByActorFingerprint,
        Optional<SessionEndReason> endReason) {

    public boolean isActive(Instant asOf) {
        return state == SessionState.ACTIVE
                && asOf.isBefore(idleDeadlineAt)
                && asOf.isBefore(absoluteExpiresAt);
    }
}
