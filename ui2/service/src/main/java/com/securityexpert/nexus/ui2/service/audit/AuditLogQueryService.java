package com.securityexpert.nexus.ui2.service.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** The bounded audit-log summary; snapshot JSON never leaves the database on this route. */
@Service
public final class AuditLogQueryService {

    private static final int RECENT_LIMIT = 100;

    public record AuditEvent(long id, @JsonProperty("occurred_at") Instant occurredAt,
            String actor, String action, String outcome, String target) {
    }

    private final TransactionBoundary transactionBoundary;

    public AuditLogQueryService(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    public List<AuditEvent> recent() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select audit_id, occurred_at, actor_fingerprint, action_id, operation, row_pk "
                        + "from audit_log order by occurred_at desc limit {0}", RECENT_LIMIT)
                .map(row -> new AuditEvent(
                        row.get("audit_id", Long.class),
                        row.get("occurred_at", Timestamp.class).toInstant(),
                        row.get("actor_fingerprint", String.class),
                        row.get("action_id", String.class),
                        row.get("operation", String.class),
                        row.get("row_pk", String.class))));
    }
}
