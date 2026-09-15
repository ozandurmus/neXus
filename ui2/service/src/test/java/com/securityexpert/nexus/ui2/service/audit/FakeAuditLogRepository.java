package com.securityexpert.nexus.ui2.service.audit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.audit.AuditListFilters;
import com.securityexpert.nexus.ui2.persistence.audit.AuditLogRepository;
import com.securityexpert.nexus.ui2.persistence.audit.AuditLogRow;
import com.securityexpert.nexus.ui2.persistence.audit.AuditPage;
import com.securityexpert.nexus.ui2.persistence.audit.AuditRedactionPolicyRow;

/**
 * In-memory {@link AuditLogRepository}, filtering/ordering exactly as the
 * real SQL does. Public so {@code service.api.AuditControllerTest} can share
 * it rather than reimplementing the same fake.
 */
public final class FakeAuditLogRepository implements AuditLogRepository {

    public final List<AuditLogRow> rows = new ArrayList<>();
    public final List<AuditRedactionPolicyRow> policy = new ArrayList<>();
    public int lastRequestedLimit = -1;

    @Override
    public AuditPage findPage(AuditListFilters filters, Optional<Long> cursorExclusive, int limit) {
        lastRequestedLimit = limit;
        List<AuditLogRow> matched = rows.stream()
                .filter(row -> filters.scopeActorFingerprint().map(f -> f.equals(row.actorFingerprint())).orElse(true))
                .filter(row -> filters.tableName().map(f -> f.equals(row.tableName())).orElse(true))
                .filter(row -> filters.rowPk().map(f -> f.equals(row.rowPk())).orElse(true))
                .filter(row -> filters.operation().map(f -> f.equals(row.operation())).orElse(true))
                .filter(row -> filters.actorFingerprint().map(f -> f.equals(row.actorFingerprint())).orElse(true))
                .filter(row -> filters.actionId().map(f -> f.equals(row.actionId())).orElse(true))
                .filter(row -> filters.correlationRunId()
                        .map(f -> row.correlationRunId().map(f::equals).orElse(false)).orElse(true))
                .filter(row -> filters.occurredFrom().map(f -> !row.occurredAt().isBefore(f)).orElse(true))
                .filter(row -> filters.occurredTo().map(f -> row.occurredAt().isBefore(f)).orElse(true))
                .filter(row -> cursorExclusive.map(c -> row.auditId() < c).orElse(true))
                .sorted(Comparator.comparingLong(AuditLogRow::auditId).reversed())
                .toList();

        boolean hasMore = matched.size() > limit;
        List<AuditLogRow> page = hasMore ? matched.subList(0, limit) : matched;
        Optional<Long> nextCursor = hasMore ? Optional.of(page.get(page.size() - 1).auditId()) : Optional.empty();
        return new AuditPage(List.copyOf(page), nextCursor);
    }

    @Override
    public Optional<AuditLogRow> findById(long auditId, Optional<String> scopeActorFingerprint) {
        return rows.stream()
                .filter(row -> row.auditId() == auditId)
                .filter(row -> scopeActorFingerprint.map(f -> f.equals(row.actorFingerprint())).orElse(true))
                .findFirst();
    }

    @Override
    public List<AuditRedactionPolicyRow> findRedactionPolicy() {
        return List.copyOf(policy);
    }
}
