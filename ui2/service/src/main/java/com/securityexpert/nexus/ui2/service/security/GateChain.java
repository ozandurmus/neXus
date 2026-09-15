package com.securityexpert.nexus.ui2.service.security;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;

/**
 * The {@code E1}-{@code E6} chain, run as one ordered pipeline no route can
 * bypass (contract §7, C3 §6.1, AC-4). Each link refuses independently
 * before a later link ever runs; {@code E3} is never re-evaluated inside
 * {@code E4} (test 12, {@code H5c}).
 *
 * <p>{@code E7} is out of scope (composition boundary, C3 §6.2) — this
 * class stops at {@code E6}.</p>
 */
public final class GateChain {

    private final SessionRepository sessionRepository;
    private final ActionRegistry actionRegistry;
    private final RbacEvaluator rbacEvaluator;
    private final AuthzDecisionRepository authzDecisionRepository;
    /**
     * NXS-LOCAL-0152's own must-change-password gate -- not part of the
     * frozen {@code E1}-{@code E6} contract, so it is {@code null} for
     * every caller that predates this movement (the legacy 4-arg
     * constructor below), which disables the gate entirely.
     */
    private final LocalCredentialsRepository localCredentialsRepository;
    /** PO directive 2026-09-14: the first-login password-change gate is a configurable posture, off by default. */
    private final boolean enforcePasswordChangeOnFirstLogin;

    public GateChain(SessionRepository sessionRepository, ActionRegistry actionRegistry,
            RbacEvaluator rbacEvaluator, AuthzDecisionRepository authzDecisionRepository) {
        this(sessionRepository, actionRegistry, rbacEvaluator, authzDecisionRepository, null);
    }

    /**
     * @param localCredentialsRepository resolves whether the session's
     *     local identity still holds its seeded password (WORKER.md
     *     "Server enforcement", NXS-LOCAL-0152) -- {@code null} disables
     *     the check.
     */
    public GateChain(SessionRepository sessionRepository, ActionRegistry actionRegistry,
            RbacEvaluator rbacEvaluator, AuthzDecisionRepository authzDecisionRepository,
            LocalCredentialsRepository localCredentialsRepository) {
        this(sessionRepository, actionRegistry, rbacEvaluator, authzDecisionRepository, localCredentialsRepository, true);
    }

    /** @param enforcePasswordChangeOnFirstLogin {@code false} (the development default) disables the must-change gate entirely. */
    public GateChain(SessionRepository sessionRepository, ActionRegistry actionRegistry,
            RbacEvaluator rbacEvaluator, AuthzDecisionRepository authzDecisionRepository,
            LocalCredentialsRepository localCredentialsRepository, boolean enforcePasswordChangeOnFirstLogin) {
        this.sessionRepository = sessionRepository;
        this.actionRegistry = actionRegistry;
        this.rbacEvaluator = rbacEvaluator;
        this.authzDecisionRepository = authzDecisionRepository;
        this.localCredentialsRepository = localCredentialsRepository;
        this.enforcePasswordChangeOnFirstLogin = enforcePasswordChangeOnFirstLogin;
    }

    public GateOutcome evaluate(GateRequest request, Instant now) {
        return evaluate(request, now, actorFingerprint -> request.actionId());
    }

    /**
     * Same {@code E1}-{@code E6} chain, except {@code E2}'s action id is
     * resolved from the authenticated {@code actorFingerprint} (never from
     * anything the browser sent) instead of being fixed on {@code request}.
     * Audit's {@code ui2.audit.read_own}/{@code ui2.audit.read_all} split
     * (`UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md` §2, §5.1: "the front end
     * computes no scope of its own... decided by the server on every
     * request") is the reason this overload exists: a route whose gated
     * action depends on which role the actor holds cannot be expressed by
     * {@code SecurityWebMvcConfig}'s static one-route-to-one-action map.
     * {@code E1} still runs exactly once (one heartbeat, one session read);
     * {@code actionIdResolver} runs after {@code E1} succeeds and before
     * {@code E2}, and {@code E4} still writes exactly one
     * {@code authz_decisions} row, for whichever action the resolver named.
     */
    public GateOutcome evaluate(GateRequest request, Instant now, Function<String, String> actionIdResolver) {
        // E1: session authenticity.
        if (request.sessionCookieRawValue().isEmpty()) {
            return refuse("E1", 401, "SESSION_INVALID", "no session cookie presented");
        }
        String sessionId = SessionHasher.hash(request.sessionCookieRawValue().get());
        Optional<SessionRecord> maybeSession = sessionRepository.findBySessionId(sessionId, now);
        if (maybeSession.isEmpty()) {
            return refuse("E1", 401, "SESSION_INVALID", "no matching session row");
        }
        SessionRecord session = maybeSession.get();
        if (session.state() != SessionState.ACTIVE) {
            return refuse("E1", 401, endReasonCode(session), "session is not ACTIVE: " + session.state());
        }
        if (!now.isBefore(session.idleDeadlineAt())) {
            return refuse("E1", 401, "SESSION_EXPIRED", "idle_deadline_at elapsed");
        }
        if (!now.isBefore(session.absoluteExpiresAt())) {
            return refuse("E1", 401, "SESSION_EXPIRED", "absolute_expires_at elapsed");
        }
        if (request.isStateChanging()) {
            if (request.csrfHeader().isEmpty() || !request.csrfHeader().get().equals(session.csrfSecret())) {
                return refuse("E1", 401, "SESSION_INVALID", "CSRF token missing or mismatched");
            }
            if (request.origin().isEmpty()) {
                return refuse("E1", 401, "SESSION_INVALID", "request origin missing on a state-changing method");
            }
        }
        sessionRepository.heartbeat(sessionId, now, Duration.between(session.lastSeenAt(), session.idleDeadlineAt()));
        String actorFingerprint = session.actorFingerprint();

        // Must-change-password gate (NXS-LOCAL-0152, WORKER.md "Server
        // enforcement"; not one of the frozen E1-E6 gates, runs immediately
        // after E1 since it is itself a session-validity property). A local
        // identity that still holds its seeded password may reach only the
        // password-change, session-status and sign-out paths -- none of
        // which are registered actions, so they never reach this chain at
        // all; every registered (gated) action is refused here with a
        // distinct, non-identity-bearing status. A non-local actor
        // fingerprint matches no local_credentials row and is never
        // restricted by this gate.
        if (enforcePasswordChangeOnFirstLogin && localCredentialsRepository != null
                && new LocalIdentityResolver(localCredentialsRepository)
                .resolve(actorFingerprint).map(LocalCredentialRecord::mustChangePassword).orElse(false)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "PASSWORD_CHANGE_REQUIRED");
            return new GateOutcome.Refused("PWD", 403, body);
        }

        // E2: action identity.
        String actionId = actionIdResolver.apply(actorFingerprint);
        Optional<ActionDescriptor> maybeAction = actionRegistry.find(actionId);
        if (maybeAction.isEmpty()) {
            return refuse("E2", 404, "ACTION_UNKNOWN", "action_id not present in the registry");
        }
        ActionDescriptor action = maybeAction.get();

        // E3: taxonomy admissibility -- class 1 refused unconditionally,
        // every role, and this decision is final: E4 never runs for this
        // request (test 12, H5c).
        if (!action.consoleSubmittable()) {
            return refuseAction("E3", 403, "ACTION_REFUSED", action.actionId(), "recovery_write_not_console_submittable");
        }

        // E4: the four D7 outcomes.
        RbacEvaluator.Decision decision = rbacEvaluator.evaluate(actorFingerprint, action.requiredRoleToken(), now);
        long decisionId = authzDecisionRepository.insert(sessionId, actorFingerprint, action.actionId(),
                request.targetRef(), decision.outcome(), decision.authority(), decision.reasonCode(),
                decision.bindingId());
        if (!decision.outcome().proceeds()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "ACTION_REFUSED");
            body.put("action_id", action.actionId());
            body.put("outcome", decision.outcome().name());
            decision.authority().ifPresent(a -> body.put("authority", a));
            decision.reasonCode().ifPresent(r -> body.put("reason_code", r));
            body.put("decision_id", decisionId);
            return new GateOutcome.Refused("E4", 403, body);
        }

        // E5/E6: action-specific subject/target integrity and
        // prerequisites (C4's scope). No action this movement registers
        // declares one; a future C4-backed registry entry supplies its own
        // check here without changing this chain's shape.

        return new GateOutcome.Proceed(sessionId, actorFingerprint);
    }

    private static String endReasonCode(SessionRecord session) {
        return switch (session.state()) {
            case SUPERSEDED -> "SESSION_SUPERSEDED";
            case EXPIRED -> "SESSION_EXPIRED";
            case REVOKED -> "SESSION_REVOKED";
            case ACTIVE -> "SESSION_INVALID"; // unreachable: guarded by the caller
        };
    }

    private static GateOutcome.Refused refuse(String gate, int status, String errorCode, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorCode);
        body.put("reason", reason);
        return new GateOutcome.Refused(gate, status, body);
    }

    private static GateOutcome.Refused refuseAction(String gate, int status, String errorCode, String actionId,
            String reasonCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorCode);
        body.put("action_id", actionId);
        body.put("reason_code", reasonCode);
        return new GateOutcome.Refused(gate, status, body);
    }
}
