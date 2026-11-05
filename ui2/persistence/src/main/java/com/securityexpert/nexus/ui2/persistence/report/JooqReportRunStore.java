package com.securityexpert.nexus.ui2.persistence.report;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;

/** jOOQ-backed {@link ReportRunStore} over {@code report_run} and {@code backup_artefact} (V190). */
public final class JooqReportRunStore implements ReportRunStore {

    private static final String COLUMNS = "select run_id::text as run_id, template_id, state, projection_mode, format, "
            + "parameters_json::text as parameters_json, data_as_of_json::text as data_as_of_json, artefact_id, "
            + "plaintext_bytes, error_code, requested_by, requested_at, finished_at, expires_at from report_run ";

    private final TransactionBoundary tx;
    private final AuditedTransactionBoundary audited;

    public JooqReportRunStore(TransactionBoundary tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.audited = new AuditedTransactionBoundary(tx);
    }

    @Override
    public void insertQueued(RunRow row) {
        tx.inTransaction(dsl -> dsl.execute("insert into report_run(run_id, template_id, state, projection_mode, format, "
                + "parameters_json, data_as_of_json, requested_by, requested_at, expires_at) values ({0}::uuid, {1}, "
                + "'QUEUED', {2}, {3}, {4}::jsonb, '{}'::jsonb, {5}, {6}, {7})",
                row.runId().toString(), row.templateId(), row.projectionMode(), row.format(), row.parametersJson(),
                row.requestedBy(), Timestamp.from(row.requestedAt()), Timestamp.from(row.expiresAt())));
    }

    @Override
    public void markRunning(UUID runId) {
        tx.inTransaction(dsl -> dsl.execute("update report_run set state = 'RUNNING' where run_id = {0}::uuid "
                + "and state = 'QUEUED'", runId.toString()));
    }

    @Override
    public void markSucceeded(UUID runId, BackupArtefactManifestRecord manifest, String dataAsOfJson, String actor) {
        if (!ArtefactClass.REPORT.equals(manifest.artefactClass())) {
            throw new IllegalArgumentException("a report run stores only artefacts of class report");
        }
        audited.inTransaction(actor, ACTION_GENERATE, runId.toString(), dsl -> {
            JooqBackupArtefactManifestRepository.insert(dsl, manifest);
            int updated = dsl.execute("update report_run set state = 'SUCCEEDED', artefact_id = {1}, plaintext_bytes = {2}, "
                    + "data_as_of_json = {3}::jsonb, finished_at = clock_timestamp() where run_id = {0}::uuid "
                    + "and state in ('QUEUED', 'RUNNING')", runId.toString(), manifest.artefactId(),
                    manifest.plaintextBytes(), dataAsOfJson);
            if (updated != 1) throw new IllegalStateException("report run is not open: " + runId);
            return null;
        });
    }

    @Override
    public void markFailed(UUID runId, String errorCode, String dataAsOfJson, String actor) {
        audited.inTransaction(actor, ACTION_GENERATE, runId.toString(), dsl -> dsl.execute(
                "update report_run set state = 'FAILED', error_code = {1}, data_as_of_json = {2}::jsonb, "
                        + "finished_at = clock_timestamp() where run_id = {0}::uuid and state in ('QUEUED', 'RUNNING')",
                runId.toString(), errorCode, dataAsOfJson == null ? "{}" : dataAsOfJson));
    }

    @Override
    public int failUnfinished(String errorCode, String actor) {
        return audited.inTransaction(actor, ACTION_INTERRUPTED, dsl -> dsl.execute(
                "update report_run set state = 'FAILED', error_code = {0}, finished_at = clock_timestamp() "
                        + "where state in ('QUEUED', 'RUNNING')", errorCode));
    }

    @Override
    public Optional<RunRow> find(UUID runId) {
        return tx.inTransaction(dsl -> dsl.fetchOptional(COLUMNS + "where run_id = {0}::uuid", runId.toString())
                .map(JooqReportRunStore::toRow));
    }

    @Override
    public List<RunRow> list(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        return tx.inTransaction(dsl -> dsl.fetch(COLUMNS + "order by requested_at desc, run_id limit {0}", bounded)
                .map(JooqReportRunStore::toRow));
    }

    @Override
    public Optional<ArtefactLocation> artefact(String artefactId) {
        return tx.inTransaction(dsl -> dsl.fetchOptional("select a.artefact_id, a.recovery_volume_path, a.wrapped_data_key, "
                + "a.plaintext_bytes from backup_artefact a where a.artefact_id = {0} and a.artefact_class = 'report'"
                + JooqRetentionQueries.andNotRemoved("a"), artefactId)
                .map(r -> new ArtefactLocation(r.get("artefact_id", String.class), r.get("recovery_volume_path", String.class),
                        r.get("wrapped_data_key", byte[].class), r.get("plaintext_bytes", Long.class))));
    }

    @Override
    public void auditDownload(UUID runId, String actor) {
        audited.inTransaction(actor, ACTION_DOWNLOAD, runId.toString(),
                dsl -> dsl.fetch("select audit_report_download({0}::uuid)", runId.toString()));
    }

    private static RunRow toRow(Record r) {
        return new RunRow(UUID.fromString(r.get("run_id", String.class)), r.get("template_id", String.class),
                r.get("state", String.class), r.get("projection_mode", String.class), r.get("format", String.class),
                r.get("parameters_json", String.class), r.get("data_as_of_json", String.class),
                r.get("artefact_id", String.class), r.get("plaintext_bytes", Long.class), r.get("error_code", String.class),
                r.get("requested_by", String.class), instant(r.get("requested_at", Timestamp.class)),
                instant(r.get("finished_at", Timestamp.class)), instant(r.get("expires_at", Timestamp.class)));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
