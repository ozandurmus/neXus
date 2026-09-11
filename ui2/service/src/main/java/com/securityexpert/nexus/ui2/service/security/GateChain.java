package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
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

    public GateChain(SessionRepository sessionRepository, ActionRegistry actionRegistry,
            RbacEvaluator rbacEvaluator, AuthzDecisionRepository authzDecisionRepository) {
        this.sessionRepository = sessionRepository;
        this.actionRegistry = actionRegistry;
        this.rbacEvaluator = rbacEvaluator;
        this.authzDecisionRepository = authzDecisionRepository;
    }

    public GateOutcome evaluate(GateRequest request, Instant now) {
        // E1: session authenticity.
        if (request.sessionCookieRawValue().isEmpty()) {
            return refuse("E1", 401, "SESSION_INVALID", "no session cookie presented");
        }
        String sessionId = SessionHasher.hash(request.sessionCookieRawValue().get());
        Optional<SessionRecord> maybeSession = sessionRepository.findBySessionId(sessionId);
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
        String actorFingerprint = session.actorFingerprint();

        // E2: action identity.
        Optional<ActionDescriptor> maybeAction = actionRegistry.find(request.actionId());
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
