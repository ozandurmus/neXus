package com.securityexpert.nexus.ui2.persistence.changes;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * {@link ChangeFeedRepository} as plain parameterised SQL over the existing tables (no new table, no migration). Every
 * query is read-only, bounded by {@code [from, to)} on an indexed time column and by {@code limit} (1..5000), and
 * selects index columns and counters only: no {@code file_name_hmac}, no {@code path_hmac}, no log text.
 */
public final class JooqChangeFeedRepository implements ChangeFeedRepository {
    private static final String TIME = "'YYYY-MM-DD HH24:MI:SS'";
    private final TransactionBoundary transactions;

    public JooqChangeFeedRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public List<JobRow> jobs(Instant from, Instant to, Optional<String> deviceId, int limit) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select job_id, job_type, target_device_id, target_kind, target_ref, submitted_by_actor_fingerprint, state, "
                        + "outcome, terminal_reason, submitted_at, finished_at from jobs "
                        + "where submitted_at >= {0} and submitted_at < {1} "
                        + "and (cast({2} as text) is null or target_device_id = {2}) "
                        + "order by submitted_at desc, job_id limit {3}",
                at(from), at(to), deviceId.orElse(null), bounded(limit))
                .map(r -> new JobRow(r.get("job_id", String.class), r.get("job_type", String.class),
                        r.get("target_device_id", String.class), r.get("target_kind", String.class), r.get("target_ref", String.class),
                        r.get("submitted_by_actor_fingerprint", String.class), r.get("state", String.class),
                        r.get("outcome", String.class), r.get("terminal_reason", String.class), instant(r, "submitted_at"),
                        instant(r, "finished_at"))));
    }

    @Override
    public List<PolicyInstallRow> policyInstalls(Instant from, Instant to, Optional<String> deviceId, int limit) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select device_id, policy_name, installed_at_text, installed_at, source_read, observed_at from device_policy_install "
                        + "where installed_at is not null and installed_at >= {0} and installed_at < {1} "
                        + "and (cast({2} as text) is null or device_id = {2}) "
                        + "order by installed_at desc, device_id limit {3}",
                at(from), at(to), deviceId.orElse(null), bounded(limit))
                .map(r -> new PolicyInstallRow(r.get("device_id", String.class), r.get("policy_name", String.class),
                        r.get("installed_at_text", String.class), instant(r, "installed_at"), r.get("source_read", String.class),
                        instant(r, "observed_at"))));
    }

    @Override
    public List<CrashAlertRow> crashAlerts(Instant from, Instant to, Optional<String> deviceId, int limit) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select alert_id, device_id, process, file_count, newest_mtime, job_id, created_at from device_crash_alert "
                        + "where created_at >= {0} and created_at < {1} "
                        + "and (cast({2} as text) is null or device_id = {2}) "
                        + "order by created_at desc, alert_id limit {3}",
                at(from), at(to), deviceId.orElse(null), bounded(limit))
                .map(r -> new CrashAlertRow(r.get("alert_id", String.class), r.get("device_id", String.class),
                        r.get("process", String.class), (int) number(r, "file_count"), number(r, "newest_mtime"),
                        r.get("job_id", String.class), instant(r, "created_at"))));
    }

    @Override
    public List<EvidenceRow> evidence(Instant from, Instant to, int limit) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select evidence_id, source_id, vendor, evidence_kind, container_ref, job_id, observed_at, entry_ref, "
                        + "to_char(change_time, " + TIME + ") as change_time, time_basis, admin_ref, command_class, result_class, "
                        + "added_count, modified_count, deleted_count, object_changes_truncated from policy_change_evidence "
                        + "where observed_at >= {0} and observed_at < {1} "
                        + "order by coalesce(change_time, observed_at at time zone 'UTC') desc, evidence_id limit {2}",
                at(from), at(to), bounded(limit))
                .map(r -> new EvidenceRow(r.get("evidence_id", String.class), r.get("source_id", String.class),
                        r.get("vendor", String.class), r.get("evidence_kind", String.class), r.get("container_ref", String.class),
                        r.get("job_id", String.class), instant(r, "observed_at"), r.get("entry_ref", String.class),
                        r.get("change_time", String.class), r.get("time_basis", String.class), r.get("admin_ref", String.class),
                        r.get("command_class", String.class), r.get("result_class", String.class),
                        r.get("added_count", Integer.class), r.get("modified_count", Integer.class),
                        r.get("deleted_count", Integer.class), Boolean.TRUE.equals(r.get("object_changes_truncated", Boolean.class)))));
    }

    @Override
    public long evidenceCount(String evidenceKind) {
        return transactions.inTransaction(dsl -> dsl.fetchOne(
                "select count(*) as n from policy_change_evidence where evidence_kind = {0}", evidenceKind).get("n", Long.class));
    }

    @Override
    public List<ChunkRow> chunks(String sourceKey, Instant from, Instant to, Optional<String> deviceId, int limit) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select c.chunk_id, c.device_id, c.source_key, c.job_id, c.collected_at, c.bytes, c.lines, c.artefact_ref, "
                        + "c.wrapped_data_key, a.recovery_volume_path from system_log_chunk c "
                        + "left join backup_artefact a on a.artefact_id = c.artefact_ref "
                        + "where c.source_key = {0} and c.pruned_at is null and c.collected_at >= {1} and c.collected_at < {2} "
                        + "and (cast({3} as text) is null or c.device_id = {3}) "
                        + "order by c.collected_at desc, c.start_offset desc, c.chunk_id limit {4}",
                sourceKey, at(from), at(to), deviceId.orElse(null), bounded(limit))
                .map(r -> new ChunkRow(r.get("chunk_id", String.class), r.get("device_id", String.class),
                        r.get("source_key", String.class), r.get("job_id", String.class), instant(r, "collected_at"),
                        number(r, "bytes"), number(r, "lines"), r.get("artefact_ref", String.class),
                        r.get("wrapped_data_key", byte[].class), r.get("recovery_volume_path", String.class))));
    }

    private static int bounded(int limit) {
        return Math.max(1, Math.min(limit, 5000));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private static Instant instant(Record r, String column) {
        OffsetDateTime value = r.get(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static long number(Record r, String column) {
        Long value = r.get(column, Long.class);
        return value == null ? 0 : value;
    }
}
