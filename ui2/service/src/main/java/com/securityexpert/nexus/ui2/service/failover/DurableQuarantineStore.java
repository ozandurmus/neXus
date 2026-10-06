package com.securityexpert.nexus.ui2.service.failover;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quarantine store enforcing Compare-And-Set (CAS) acknowledgment tied to the specific execution
 * ID reviewed by operators, backed by the {@code failover_quarantine} table.
 *
 * <p>CF-P0.20 companion: sticky quarantine is the mechanism that keeps a cluster locked after an
 * ambiguous or unrecoverable failover outcome -- it must survive a process restart exactly as
 * durably as the schedule it protects; an in-memory-only quarantine that resets on restart would
 * silently drop the safety guarantee it exists to provide.</p>
 *
 * <p>Each (unit, execution) is an immutable incident. Release updates only the exact
 * incident's acknowledgment fields; another incident is never overwritten or cleared.</p>
 */
@Component
public class DurableQuarantineStore {

    public record QuarantineEntry(
        String clusterRef,
        String executionId,
        Instant quarantinedAt,
        String reason,
        Set<String> quarantinedMemberIds,
        boolean acknowledged,
        String acknowledgedBy,
        String secondApproverId,
        Instant acknowledgedAt,
        String auditReason
    ) {}

    private static final String SELECT_COLUMNS =
        "cluster_ref, execution_id, reason, quarantined_member_ids, quarantined_at, " +
            "acknowledged_at, acknowledged_by, second_approver_id, review_notes, active ";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper memberIdsJsonMapper = new ObjectMapper();

    // In-memory fallback store, used only when no JdbcTemplate is wired (lightweight unit tests).
    private record IncidentKey(String cluster, String execution) {}
    private final Map<IncidentKey, QuarantineEntry> memoryActiveQuarantines = new ConcurrentHashMap<>();
    private final List<QuarantineEntry> memoryAuditHistory = Collections.synchronizedList(new ArrayList<>());

    @Autowired
    public DurableQuarantineStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /** Lightweight unit-test constructor: falls back to an in-process, non-durable store. */
    public DurableQuarantineStore() {
        this.jdbcTemplate = null;
    }

    private boolean isDurable() {
        return jdbcTemplate != null;
    }

    public synchronized void engageQuarantine(
        String clusterRef,
        String executionId,
        String reason,
        Set<String> memberIds
    ) {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Set<String> safeMemberIds = memberIds != null ? Set.copyOf(memberIds) : Set.of();

        if (isDurable()) {
            jdbcTemplate.update(
                "INSERT INTO failover_quarantine " +
                    "(cluster_ref, execution_id, reason, quarantined_member_ids, quarantined_at, " +
                    "acknowledged_at, acknowledged_by, second_approver_id, review_notes, active) " +
                    "VALUES (?, ?, ?, ?::jsonb, ?, NULL, NULL, NULL, NULL, true) " +
                    "ON CONFLICT (cluster_ref, execution_id) DO NOTHING",
                clusterRef, executionId, reason, writeMemberIdsJson(safeMemberIds), Timestamp.from(Instant.now())
            );
            return;
        }

        QuarantineEntry entry = new QuarantineEntry(
            clusterRef, executionId, Instant.now(), reason, safeMemberIds,
            false, null, null, null, null
        );
        IncidentKey key = new IncidentKey(clusterRef, executionId);
        if (memoryAuditHistory.stream().anyMatch(e -> e.clusterRef().equals(clusterRef) && e.executionId().equals(executionId))) return;
        memoryActiveQuarantines.put(key, entry);
        memoryAuditHistory.add(entry);
    }

    public boolean isClusterQuarantined(String clusterRef) {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        if (isDurable()) {
            return queryActiveRow(clusterRef) != null;
        }
        return memoryActiveQuarantines.values().stream().anyMatch(e -> e.clusterRef().equals(clusterRef));
    }

    public boolean blocksMutation(String clusterRef, Set<String> memberIds) {
        if (isDurable()) {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM failover_quarantine q WHERE active AND (cluster_ref=? "
                + "OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(q.quarantined_member_ids) AS m(member_id) "
                + "WHERE ?::jsonb @> jsonb_build_array(m.member_id))))", Boolean.class,
                clusterRef, writeMemberIdsJson(memberIds)));
        }
        return memoryActiveQuarantines.values().stream().anyMatch(e -> e.clusterRef().equals(clusterRef)
            || !Collections.disjoint(e.quarantinedMemberIds(), memberIds));
    }

    public Optional<QuarantineEntry> getActiveQuarantine(String clusterRef) {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        if (isDurable()) {
            return Optional.ofNullable(queryActiveRow(clusterRef));
        }
        return memoryActiveQuarantines.values().stream().filter(e -> e.clusterRef().equals(clusterRef)).findFirst();
    }

    /**
     * CAS acknowledgment: only clears quarantine if the current quarantine matches expectedExecutionId.
     * CF-P0.14: requester and approver are canonicalized (trim + lowercase) before the dual-control
     * comparison, so a trailing space or case difference can no longer defeat the 4-eyes gate.
     */
    public boolean acknowledgeQuarantine(
        String clusterRef,
        String expectedExecutionId,
        String operatorId,
        String secondApproverId,
        String auditReason
    ) {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(expectedExecutionId, "expectedExecutionId must not be null");
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        Objects.requireNonNull(secondApproverId, "secondApproverId must not be null");
        Objects.requireNonNull(auditReason, "auditReason must not be null");

        PrincipalCanonicalization.requireDistinctPrincipals(operatorId, secondApproverId, "Quarantine acknowledgment");
        if (auditReason.trim().length() < 8) {
            throw new IllegalArgumentException("Audit reason must be at least 8 characters");
        }

        if (isDurable()) {
            return acknowledgeQuarantineDurable(clusterRef, expectedExecutionId, operatorId, secondApproverId, auditReason);
        }
        return acknowledgeQuarantineInMemory(clusterRef, expectedExecutionId, operatorId, secondApproverId, auditReason);
    }

    private synchronized boolean acknowledgeQuarantineDurable(
        String clusterRef, String expectedExecutionId, String operatorId, String secondApproverId, String auditReason
    ) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT ui2_release_failover_incident(?, ?, ?, ?, ?)", Boolean.class,
            clusterRef, expectedExecutionId, operatorId, secondApproverId, auditReason));
    }

    private synchronized boolean acknowledgeQuarantineInMemory(
        String clusterRef, String expectedExecutionId, String operatorId, String secondApproverId, String auditReason
    ) {
        QuarantineEntry existing = memoryActiveQuarantines.get(new IncidentKey(clusterRef, expectedExecutionId));
        if (existing == null) {
            return false;
        }
        QuarantineEntry acked = new QuarantineEntry(
            existing.clusterRef(), existing.executionId(), existing.quarantinedAt(), existing.reason(),
            existing.quarantinedMemberIds(), true, operatorId, secondApproverId, Instant.now(), auditReason
        );
        memoryActiveQuarantines.remove(new IncidentKey(clusterRef, expectedExecutionId));
        memoryAuditHistory.replaceAll(e -> e.clusterRef().equals(clusterRef) && e.executionId().equals(expectedExecutionId) ? acked : e);
        return true;
    }

    public List<QuarantineEntry> getAuditHistory() {
        if (isDurable()) {
            return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + "FROM failover_quarantine ORDER BY quarantined_at",
                (rs, rowNum) -> mapRow(rs));
        }
        return List.copyOf(memoryAuditHistory);
    }

    private QuarantineEntry queryActiveRow(String clusterRef) {
        List<QuarantineEntry> rows = jdbcTemplate.query(
            "SELECT " + SELECT_COLUMNS + "FROM failover_quarantine WHERE cluster_ref = ? AND active = true ORDER BY quarantined_at, execution_id",
            (rs, rowNum) -> mapRow(rs), clusterRef);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private QuarantineEntry mapRow(ResultSet rs) throws SQLException {
        Set<String> memberIds = readMemberIdsJson(rs.getString("quarantined_member_ids"));
        Timestamp acknowledgedAt = rs.getTimestamp("acknowledged_at");
        return new QuarantineEntry(
            rs.getString("cluster_ref"),
            rs.getString("execution_id"),
            rs.getTimestamp("quarantined_at").toInstant(),
            rs.getString("reason"),
            Set.copyOf(memberIds),
            !rs.getBoolean("active"),
            rs.getString("acknowledged_by"),
            rs.getString("second_approver_id"),
            acknowledgedAt != null ? acknowledgedAt.toInstant() : null,
            rs.getString("review_notes")
        );
    }

    private String writeMemberIdsJson(Set<String> memberIds) {
        try {
            return memberIdsJsonMapper.writeValueAsString(memberIds);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize quarantined member IDs: " + e.getMessage(), e);
        }
    }

    private Set<String> readMemberIdsJson(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> memberIds = memberIdsJsonMapper.readValue(json, new TypeReference<List<String>>() {});
            return Set.copyOf(memberIds);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize quarantined member IDs: " + e.getMessage(), e);
        }
    }
}
