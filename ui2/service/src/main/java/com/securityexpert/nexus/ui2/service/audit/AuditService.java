package com.securityexpert.nexus.ui2.service.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.persistence.audit.AuditListFilters;
import com.securityexpert.nexus.ui2.persistence.audit.AuditLogRepository;
import com.securityexpert.nexus.ui2.persistence.audit.AuditLogRow;
import com.securityexpert.nexus.ui2.persistence.audit.AuditPage;

/**
 * Orchestrates the read-only Audit screen (`UI2_0_B1_08_AUDIT_LOGS_SCREEN_
 * CONTRACT.md`): validates the §4.1 filter bounds, resolves {@code read_own}
 * vs {@code read_all} into a repository-level scope filter, and projects a
 * detail row through {@link AuditFieldProjector}. Holds no authorization
 * decision of its own -- {@code scopeAll} is decided by the caller
 * ({@code AuditController}, from the already-gated {@code E4} outcome), this
 * class only ever narrows a query, never widens one.
 */
public final class AuditService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public static final String REASON_UNKNOWN_TABLE_NAME = "unknown_table_name";
    public static final String REASON_UNKNOWN_OPERATION = "unknown_operation";
    public static final String REASON_OCCURRED_TO_BEFORE_FROM = "occurred_to_before_occurred_from";
    /** §4.1/§5.2: {@code actor_fingerprint} is "accepted only for ui2.audit.read_all". */
    public static final String REASON_ACTOR_FINGERPRINT_NOT_ACCEPTED_FOR_READ_OWN =
            "actor_not_in_required_group";

    private static final Set<String> CLOSED_OPERATIONS = Set.of("INSERT", "UPDATE", "DELETE");

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(AuditLogRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public record ListRequest(
            boolean scopeAll,
            String callerActorFingerprint,
            Optional<String> tableName,
            Optional<String> rowPk,
            Optional<String> operation,
            Optional<String> actorFingerprint,
            Optional<String> actionId,
            Optional<String> correlationRunId,
            Optional<Instant> occurredFrom,
            Optional<Instant> occurredTo,
            Optional<Long> cursor,
            int requestedLimit) {
    }

    public sealed interface ListOutcome {
        record Ok(List<AuditEntrySummary> entries, Optional<Long> nextCursor) implements ListOutcome {
        }

        /** A §4.1 filter bound was violated -- always a {@code 400 VALIDATION_FAILED}, never a database round trip. */
        record ValidationFailed(String reasonCode) implements ListOutcome {
        }

        /**
         * §4.1/§5.2: {@code actor_fingerprint} is "accepted only for
         * ui2.audit.read_all" -- under {@code read_own} this is not a
         * malformed request, it is the same {@code 403 ACTION_REFUSED}
         * shape (§4.3) an RBAC refusal uses, carrying the closed
         * {@code actor_not_in_required_group} reason_code.
         */
        record ScopeRefused(String reasonCode) implements ListOutcome {
        }
    }

    public ListOutcome list(ListRequest request) {
        if (!request.scopeAll() && request.actorFingerprint().isPresent()) {
            return new ListOutcome.ScopeRefused(REASON_ACTOR_FINGERPRINT_NOT_ACCEPTED_FOR_READ_OWN);
        }
        if (request.tableName().isPresent()
                && !AuditPresentationAllowlist.auditedTables().contains(request.tableName().get())) {
            return new ListOutcome.ValidationFailed(REASON_UNKNOWN_TABLE_NAME);
        }
        if (request.operation().isPresent() && !CLOSED_OPERATIONS.contains(request.operation().get())) {
            return new ListOutcome.ValidationFailed(REASON_UNKNOWN_OPERATION);
        }
        if (request.occurredFrom().isPresent() && request.occurredTo().isPresent()
                && request.occurredTo().get().isBefore(request.occurredFrom().get())) {
            return new ListOutcome.ValidationFailed(REASON_OCCURRED_TO_BEFORE_FROM);
        }

        int limit = Math.min(Math.max(request.requestedLimit(), 1), MAX_LIMIT);
        Optional<String> scopeActorFingerprint =
                request.scopeAll() ? Optional.empty() : Optional.of(request.callerActorFingerprint());
        AuditListFilters filters = new AuditListFilters(scopeActorFingerprint, request.tableName(), request.rowPk(),
                request.operation(), request.actorFingerprint(), request.actionId(), request.correlationRunId(),
                request.occurredFrom(), request.occurredTo());

        AuditPage page = repository.findPage(filters, request.cursor(), limit);
        List<AuditEntrySummary> entries = page.entries().stream().map(AuditService::toSummary).toList();
        return new ListOutcome.Ok(entries, page.nextCursor());
    }

    public sealed interface DetailOutcome {
        record Ok(AuditEntryDetail detail) implements DetailOutcome {
        }

        /** Also returned when {@code auditId} exists but is out of the caller's scope (§4.2: {@code 404}, never {@code 403}). */
        record NotFound() implements DetailOutcome {
        }
    }

    public DetailOutcome get(boolean scopeAll, String callerActorFingerprint, long auditId) {
        Optional<String> scopeActorFingerprint = scopeAll ? Optional.empty() : Optional.of(callerActorFingerprint);
        Optional<AuditLogRow> row = repository.findById(auditId, scopeActorFingerprint);
        if (row.isEmpty()) {
            return new DetailOutcome.NotFound();
        }
        return new DetailOutcome.Ok(toDetail(row.get()));
    }

    private AuditEntryDetail toDetail(AuditLogRow row) {
        AuditRedactionPolicy policy = AuditRedactionPolicy.of(repository.findRedactionPolicy().stream()
                .map(policyRow -> new AuditRedactionPolicyEntry(
                        policyRow.tableName(), policyRow.columnName(), policyRow.tier(), policyRow.reason()))
                .toList());
        AuditFieldProjector projector = new AuditFieldProjector(policy);
        JsonNode before = parse(row.beforeStateJson());
        JsonNode after = parse(row.afterStateJson());
        Map<String, AuditFieldProjection> beforeFields = projector.project(row.tableName(), before);
        Map<String, AuditFieldProjection> afterFields = projector.project(row.tableName(), after);
        Map<String, AuditChangeState> changeStates = "UPDATE".equals(row.operation())
                ? projector.compareChangeStates(row.tableName(), before, after)
                : Map.of();
        return new AuditEntryDetail(toSummary(row), beforeFields, afterFields, changeStates);
    }

    private JsonNode parse(Optional<String> json) {
        if (json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(json.get());
        } catch (Exception e) {
            // AC-1/§7: never fall back to serving the raw text this failed to parse.
            throw new IllegalStateException("an audit_log snapshot was not valid JSON", e);
        }
    }

    private static AuditEntrySummary toSummary(AuditLogRow row) {
        return new AuditEntrySummary(row.auditId(), row.tableName(), row.rowPk(), row.operation(),
                row.actorFingerprint(), row.actionId(), row.occurredAt(), row.correlationRunId());
    }
}
