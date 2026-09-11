package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link AuthzDecisionRepository}. */
public final class JooqAuthzDecisionRepository implements AuthzDecisionRepository {

    private final TransactionBoundary transactionBoundary;

    public JooqAuthzDecisionRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public long insert(String sessionId, String actorFingerprint, String actionId, Optional<String> targetRef,
            AuthzOutcome outcome, Optional<String> authority, Optional<String> reasonCode,
            Optional<String> bindingId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch(
                    "insert into authz_decisions(session_id, actor_fingerprint, action_id, target_ref, "
                            + "outcome, authority, reason_code, binding_id) "
                            + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}) returning decision_id",
                    sessionId, actorFingerprint, actionId, targetRef.orElse(null), outcome.name(),
                    authority.orElse(null), reasonCode.orElse(null), bindingId.orElse(null));
            return rows.get(0).get("decision_id", Long.class);
        });
    }
}
