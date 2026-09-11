package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;

import com.securityexpert.nexus.ui2.persistence.identity.SessionEndReason;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;

/**
 * The reconciler (C3 §3.3 rows 4-5): {@code ACTIVE} → {@code EXPIRED}
 * on idle timeout or absolute lifetime, attributed to the reserved
 * {@code system:session_reconciler} actor (audited identically to a human
 * action, C3 §3.4). No container/scheduler runtime is wired to invoke this
 * in this slice (no container runtime here) — {@link #reconcileOnce} is
 * the unit the scheduler module or a Routine calls on its own cadence.
 */
public final class SessionReconciler {

    public static final String ACTION_IDLE_TIMEOUT = "session_reconcile_idle_timeout";
    public static final String ACTION_ABSOLUTE_LIFETIME = "session_reconcile_absolute_lifetime";

    private final SessionRepository sessionRepository;

    public SessionReconciler(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /** @return how many sessions were transitioned to {@code EXPIRED} */
    public int reconcileOnce(Instant now) {
        int count = 0;
        for (SessionRecord session : sessionRepository.findActivePastDeadline(now)) {
            boolean idleExpired = !now.isBefore(session.idleDeadlineAt());
            SessionEndReason reason = idleExpired ? SessionEndReason.IDLE_TIMEOUT : SessionEndReason.ABSOLUTE_LIFETIME;
            String actionId = idleExpired ? ACTION_IDLE_TIMEOUT : ACTION_ABSOLUTE_LIFETIME;
            sessionRepository.expire(session.sessionId(), reason, actionId);
            count++;
        }
        return count;
    }
}
