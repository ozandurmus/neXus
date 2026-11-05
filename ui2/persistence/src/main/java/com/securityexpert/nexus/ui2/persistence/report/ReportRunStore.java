package com.securityexpert.nexus.ui2.persistence.report;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;

/**
 * The {@code report_run} table (V190, Reports R1 contract "Schema") and the report artefacts' manifest rows.
 *
 * <p>Audit (contract "Audit"): the terminal transition ({@link #markSucceeded}, {@link #markFailed}) writes the one
 * audit row of a generate through {@code trg_audit_report_run}; {@link #auditDownload} writes the one audit row of a
 * download through {@code audit_report_download} and must be called before the first byte is streamed.</p>
 */
public interface ReportRunStore {

    String ACTION_GENERATE = "report_generate";
    String ACTION_DOWNLOAD = "report_download";
    String ACTION_INTERRUPTED = "report_run_interrupted";

    record RunRow(UUID runId, String templateId, String state, String projectionMode, String format,
            String parametersJson, String dataAsOfJson, String artefactId, Long plaintextBytes, String errorCode,
            String requestedBy, Instant requestedAt, Instant finishedAt, Instant expiresAt) {
    }

    /** Where a live (not retention-removed) report artefact is held; empty once the janitor removed it. */
    record ArtefactLocation(String artefactId, String recoveryVolumePath, byte[] wrappedDataKey, long plaintextBytes) {
    }

    void insertQueued(RunRow row);

    void markRunning(UUID runId);

    /** One transaction: the manifest row (and its {@code created} ledger row), then the run's SUCCEEDED state. */
    void markSucceeded(UUID runId, BackupArtefactManifestRecord manifest, String dataAsOfJson, String actor);

    void markFailed(UUID runId, String errorCode, String dataAsOfJson, String actor);

    /** Runs left QUEUED or RUNNING by a stopped service become FAILED with this code; returns how many. */
    int failUnfinished(String errorCode, String actor);

    Optional<RunRow> find(UUID runId);

    List<RunRow> list(int limit);

    Optional<ArtefactLocation> artefact(String artefactId);

    /** The download audit row; throws when it cannot be written, and the download is then refused. */
    void auditDownload(UUID runId, String actor);
}
