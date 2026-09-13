package com.securityexpert.nexus.ui2.service.security;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;

/**
 * An authenticated identity ending its OWN session (NXS-LOCAL-0152 AC-4).
 * Distinct from {@code POST /sessions/revoke}
 * ({@link com.securityexpert.nexus.ui2.service.api.SessionAdminController}),
 * which is C3 §3.3 row 6's {@code role:security_admin}-ends-another-identity
 * path, gated by {@code role:security_admin} through {@link GateChain} --
 * that endpoint is not overloaded here, since handing every ordinary
 * identity that administrative capability (even scoped to "itself") would
 * require bypassing its RBAC gate for a self-target, which is exactly the
 * risk WORKER.md names.
 *
 * <p>C3 §3.1/§3.3 defines a closed, DB-enforced {@code end_reason}
 * vocabulary and states its transition table is exhaustive ("no other
 * transition exists") -- neither names a self-initiated sign-out. Rather
 * than adding a new closed-vocabulary value (a FROZEN-contract change), this
 * reuses {@link SessionRepository#revoke}'s existing {@code ACTIVE ->
 * REVOKED} transition unchanged, distinguished at the audit layer only:
 * {@link #ACTION_SESSION_LOGOUT_SELF} (not {@code ActionRegistry.SESSION_REVOKE})
 * as the action id, and {@code ended_by_actor_fingerprint} always equal to
 * the ending session's own actor (an admin-caused revoke always names a
 * different actor). This gap is flagged to the Product Owner via
 * RELAY_QUESTION (see SESSION_CLOSE); this is the zero-schema-change
 * resolution pending confirmation.</p>
 */
public final class SessionSelfLogoutService {

    /** The audited action id this transition runs under -- distinct from {@code ActionRegistry.SESSION_REVOKE}. */
    public static final String ACTION_SESSION_LOGOUT_SELF = "session_logout_self";

    public sealed interface Result {
        record Ok() implements Result {
        }

        /** No session row, or not ACTIVE -- the cookie is stale either way. */
        record NoActiveSession() implements Result {
        }
    }

    private final SessionRepository sessionRepository;

    public SessionSelfLogoutService(SessionRepository sessionRepository) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
    }

    public Result logout(String sessionId) {
        Optional<SessionRecord> session = sessionRepository.findBySessionId(sessionId);
        if (session.isEmpty() || session.get().state() != SessionState.ACTIVE) {
            return new Result.NoActiveSession();
        }
        sessionRepository.revoke(sessionId, session.get().actorFingerprint(), ACTION_SESSION_LOGOUT_SELF);
        return new Result.Ok();
    }
}
