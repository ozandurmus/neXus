package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link JobRecordDao}. */
public final class JooqJobRecordDao implements JobRecordDao {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqJobRecordDao(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = transactionBoundary;
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<String> insertRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
            String targetDeviceId, String actionClass, String jobType, String actorFingerprint, String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            org.jooq.Result<Record> rows = dsl.fetch(
                    "insert into jobs(job_id, job_type, capability_id, target_device_id, target_kind, "
                            + "submitted_by_actor_fingerprint, submitted_at, idempotency_key, action_class, state) "
                            + "values ({0}, {1}, {2}, {3}, 'device', {4}, {5}, {6}, {7}, 'REQUESTED') "
                            + "on conflict (idempotency_key) do nothing "
                            + "returning job_id",
                    jobId, jobType, capabilityId, targetDeviceId, actorFingerprint, Timestamp.from(Instant.now()),
                    idempotencyKey, actionClass);
            return rows.stream().findFirst().map(r -> r.get("job_id", String.class));
        });
    }

    @Override
    public Optional<String> insertRequestedIfAbsentForRun(String jobId, String idempotencyKey, String capabilityId,
            String targetRunId, String actionClass, String jobType, String actorFingerprint, String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            org.jooq.Result<Record> rows = dsl.fetch(
                    "insert into jobs(job_id, job_type, capability_id, target_ref, target_kind, "
                            + "submitted_by_actor_fingerprint, submitted_at, idempotency_key, action_class, state) "
                            + "values ({0}, {1}, {2}, {3}, 'discovery_run', {4}, {5}, {6}, {7}, 'REQUESTED') "
                            + "on conflict (idempotency_key) do nothing "
                            + "returning job_id",
                    jobId, jobType, capabilityId, targetRunId, actorFingerprint, Timestamp.from(Instant.now()),
                    idempotencyKey, actionClass);
            return rows.stream().findFirst().map(r -> r.get("job_id", String.class));
        });
    }

    @Override
    public Optional<String> findJobIdByIdempotencyKey(String idempotencyKey) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select job_id from jobs where idempotency_key = {0}", idempotencyKey)
                .stream().findFirst().map(r -> r.get("job_id", String.class)));
    }

    @Override
    public Optional<JobRow> find(String jobId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from jobs where job_id = {0}", jobId)
                .stream().findFirst().map(JooqJobRecordDao::toRow));
    }

    @Override
    public Optional<JobRow> findMostRecentByTargetDeviceId(String targetDeviceId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from jobs where target_device_id = {0} order by submitted_at desc limit 1",
                targetDeviceId)
                .stream().findFirst().map(JooqJobRecordDao::toRow));
    }

    @Override
    public DiagnosticAdmission insertDiagnostic(String jobId, String idempotencyKey, String targetDeviceId,
            String port, String actorFingerprint, String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            Record locked = dsl.fetchOne("select device_id from devices where device_id = {0} for update", targetDeviceId);
            if (locked == null) {
                return new DiagnosticAdmission("DEVICE_NOT_FOUND", null);
            }
            Record prior = dsl.fetchOne("select job_id, target_device_id, diagnostic_port from jobs where idempotency_key = {0}",
                    idempotencyKey);
            if (prior != null) {
                boolean same = targetDeviceId.equals(prior.get("target_device_id", String.class))
                        && port.equals(prior.get("diagnostic_port", String.class));
                return new DiagnosticAdmission(same ? "DEDUPLICATED" : "IDEMPOTENCY_CONFLICT",
                        same ? prior.get("job_id", String.class) : null);
            }
            Boolean active = dsl.fetchOne("select exists(select 1 from jobs where target_device_id = {0} "
                    + "and capability_id in ('fmg_interface_detail','diagnostic_read') and state in ('REQUESTED', 'CLAIMED', 'EXECUTING')) as active",
                    targetDeviceId).get("active", Boolean.class);
            if (Boolean.TRUE.equals(active)) {
                return new DiagnosticAdmission("DIAGNOSTIC_IN_FLIGHT", null);
            }
            Boolean recent = dsl.fetchOne("select exists(select 1 from jobs where target_device_id = {0} "
                    + "and capability_id in ('fmg_interface_detail','diagnostic_read') and submitted_at > now() - interval '1 minute') as recent",
                    targetDeviceId).get("recent", Boolean.class);
            if (Boolean.TRUE.equals(recent)) {
                return new DiagnosticAdmission("RATE_LIMITED", null);
            }
            Record inserted = dsl.fetchOne("insert into jobs(job_id, job_type, capability_id, target_device_id, target_kind, "
                    + "submitted_by_actor_fingerprint, submitted_at, idempotency_key, action_class, state, diagnostic_port, diagnostic_gate_revision) "
                    + "values ({0}, 'fmg_interface_detail', 'fmg_interface_detail', {1}, 'device', {2}, now(), {3}, "
                    + "'read', 'REQUESTED', {4}, 90) on conflict (idempotency_key) do nothing returning job_id",
                    jobId, targetDeviceId, actorFingerprint, idempotencyKey, port);
            if (inserted == null) {
                return new DiagnosticAdmission("IDEMPOTENCY_CONFLICT", null);
            }
            return new DiagnosticAdmission("ADMITTED", inserted.get("job_id", String.class));
        });
    }

    @Override
    public Optional<DiagnosticJob> findDiagnostic(String jobId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from jobs where job_id = {0} and capability_id in ('fmg_interface_detail','diagnostic_read')", jobId)
                .stream().findFirst().map(JooqJobRecordDao::toDiagnostic));
    }

    private static DiagnosticJob toDiagnostic(Record r) {
        String port = r.get("diagnostic_port", String.class);
        String command = r.get("diagnostic_command", String.class);
        return new DiagnosticJob(r.get("job_id", String.class), r.get("target_device_id", String.class), port,
                r.get("state", String.class), r.get("diagnostic_status_token", String.class),
                r.get("diagnostic_status_token", String.class) != null && !"ABSENT".equals(r.get("diagnostic_status_token", String.class)),
                r.get("diagnostic_line_count", Integer.class), r.get("diagnostic_shape_id", String.class),
                r.get("diagnostic_masked_output", String.class), command != null ? command : "diagnose fmnetwork interface detail " + port,
                r.get("submitted_by_actor_fingerprint", String.class),
                r.get("submitted_at", java.time.OffsetDateTime.class).toInstant(), r.get("diagnostic_exit_status", Integer.class));
    }

    @Override
    public java.util.List<DiagnosticJob> diagnosticHistory(String deviceId, int offset) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from jobs where capability_id in ('fmg_interface_detail','diagnostic_read') "
                + "and ({0}::text is null or target_device_id = {0}) order by submitted_at desc, job_id desc limit 50 offset {1}",
                deviceId, Math.max(0, offset)).stream().map(JooqJobRecordDao::toDiagnostic).toList());
    }

    @Override
    public Optional<DiagnosticOutputRef> diagnosticOutput(String jobId, String actor) {
        return auditedTransactionBoundary.inTransaction(actor, "diagnostic_output_viewed", dsl -> {
            var r = dsl.fetchOne("update jobs set diagnostic_viewed_at=now(), diagnostic_viewed_by={1} "
                + "where job_id={0} and capability_id='diagnostic_read' and diagnostic_output_ref is not null "
                + "returning diagnostic_output_ref, diagnostic_output_key", jobId, actor);
            return r == null ? Optional.empty() : Optional.of(new DiagnosticOutputRef(
                r.get("diagnostic_output_ref", String.class), r.get("diagnostic_output_key", byte[].class)));
        });
    }

    @Override
    public boolean writeDiagnosticOutput(String jobId, String reference, byte[] key, int exitStatus, int lines) {
        return auditedTransactionBoundary.inTransaction("system:worker", "diagnostic_output_recorded", dsl ->
            dsl.execute("update jobs set diagnostic_output_ref={1}, diagnostic_output_key={2}, diagnostic_exit_status={3}, "
                + "diagnostic_line_count={4} where job_id={0} and capability_id='diagnostic_read' and diagnostic_output_ref is null",
                jobId, reference, key, exitStatus, Math.min(256, lines)) == 1);
    }

    @Override
    public DiagnosticAdmission insertDiagnosticRead(String jobId, String idempotencyKey, String deviceId, String command, String actor) {
        return auditedTransactionBoundary.inTransaction(actor, "diagnostic_read_submitted", dsl -> {
            if (dsl.fetchOne("select device_id from devices where device_id={0} for update", deviceId) == null)
                return new DiagnosticAdmission("DEVICE_NOT_FOUND", null);
            var prior = dsl.fetchOne("select job_id,target_device_id,diagnostic_command,submitted_by_actor_fingerprint from jobs where idempotency_key={0}", idempotencyKey);
            if (prior != null) {
                boolean same = actor.equals(prior.get("submitted_by_actor_fingerprint", String.class)) && deviceId.equals(prior.get("target_device_id", String.class)) && command.equals(prior.get("diagnostic_command", String.class));
                return new DiagnosticAdmission(same ? "DEDUPLICATED" : "IDEMPOTENCY_CONFLICT", same ? prior.get("job_id", String.class) : null);
            }
            if (Boolean.TRUE.equals(dsl.fetchOne("select exists(select 1 from jobs where target_device_id={0} "
                + "and capability_id in ('diagnostic_read','fmg_interface_detail') "
                + "and (state in ('REQUESTED','CLAIMED','EXECUTING') or submitted_at > now()-interval '1 minute')) as busy", deviceId).get("busy", Boolean.class)))
                return new DiagnosticAdmission("RATE_LIMITED_OR_RUNNING", null);
            var inserted = dsl.fetchOne("insert into jobs(job_id,job_type,capability_id,target_device_id,target_kind,submitted_by_actor_fingerprint,"
                + "submitted_at,idempotency_key,action_class,state,diagnostic_command) values ({0},'diagnostic_read','diagnostic_read',{1},'device',"
                + "{2},now(),{3},'read','REQUESTED',{4}) on conflict(idempotency_key) do nothing returning job_id", jobId,deviceId,actor,idempotencyKey,command);
            return inserted == null ? new DiagnosticAdmission("IDEMPOTENCY_CONFLICT", null) : new DiagnosticAdmission("ADMITTED", jobId);
        });
    }

    @Override
    public boolean writeDiagnosticResult(String jobId, String statusToken, int lineCount, String shapeId, String maskedOutput) {
        return auditedTransactionBoundary.inTransaction("system:worker", "diagnostic_safe_result", dsl -> dsl.execute(
                "update jobs set diagnostic_status_token = {0}, diagnostic_line_count = {1}, diagnostic_shape_id = {2}, "
                        + "diagnostic_masked_output = {3} where job_id = {4} and capability_id = 'fmg_interface_detail'",
                statusToken, lineCount, shapeId, maskedOutput, jobId)) == 1;
    }

    private static JobRow toRow(Record row) {
        return new JobRow(
                row.get("job_id", String.class),
                row.get("capability_id", String.class),
                row.get("target_device_id", String.class),
                row.get("action_class", String.class),
                row.get("state", String.class),
                row.get("lease_worker_id", String.class),
                row.get("lease_epoch", Long.class) == null ? 0L : row.get("lease_epoch", Long.class),
                row.get("outcome", String.class),
                row.get("terminal_reason", String.class),
                row.get("target_kind", String.class),
                row.get("target_ref", String.class));
    }
}
