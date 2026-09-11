package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;

/**
 * {@code authz_decisions} persistence port (C3 §5.3). Append-only by
 * construction and grant; never wrapped in {@code AuditedTransactionBoundary}
 * -- this table has no {@code fn_audit_capture()} trigger of its own (C3
 * §5.3: "it needs no trigger... like {@code audit_log}, it is append-only
 * by construction and grant").
 */
public interface AuthzDecisionRepository {

    /** @return the created row's {@code decision_id} */
    long insert(String sessionId, String actorFingerprint, String actionId, Optional<String> targetRef,
            AuthzOutcome outcome, Optional<String> authority, Optional<String> reasonCode,
            Optional<String> bindingId);
}
