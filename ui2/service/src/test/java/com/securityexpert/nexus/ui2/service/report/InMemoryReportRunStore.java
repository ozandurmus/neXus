package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.report.ReportRunStore;

/**
 * An in-memory {@link ReportRunStore} that records the audit rows V190 would write: one per terminal transition
 * ({@code trg_audit_report_run}) and one per {@link #auditDownload} ({@code audit_report_download}), in call order.
 */
final class InMemoryReportRunStore implements ReportRunStore {

    record AuditRow(String action, UUID runId, String actor, String templateId, String format, String projection,
            String parametersJson, String artefactId, String state) {
    }

    final Map<UUID, RunRow> rows = new LinkedHashMap<>();
    final Map<String, BackupArtefactManifestRecord> artefacts = new LinkedHashMap<>();
    final Set<String> removed = new java.util.HashSet<>();
    final List<AuditRow> audit = new CopyOnWriteArrayList<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    boolean failAudit;

    @Override
    public synchronized void insertQueued(RunRow row) {
        rows.put(row.runId(), row);
        events.add("insert");
    }

    @Override
    public synchronized void markRunning(UUID runId) {
        RunRow r = rows.get(runId);
        rows.put(runId, with(r, "RUNNING", r.artefactId(), r.plaintextBytes(), null, r.dataAsOfJson(), null));
    }

    @Override
    public synchronized void markSucceeded(UUID runId, BackupArtefactManifestRecord manifest, String dataAsOfJson, String actor) {
        artefacts.put(manifest.artefactId(), manifest);
        RunRow r = with(rows.get(runId), "SUCCEEDED", manifest.artefactId(), manifest.plaintextBytes(), null, dataAsOfJson,
                Instant.now());
        rows.put(runId, r);
        record(ACTION_GENERATE, r, actor);
    }

    @Override
    public synchronized void markFailed(UUID runId, String errorCode, String dataAsOfJson, String actor) {
        RunRow r = with(rows.get(runId), "FAILED", null, null, errorCode, dataAsOfJson, Instant.now());
        rows.put(runId, r);
        record(ACTION_GENERATE, r, actor);
    }

    @Override
    public synchronized int failUnfinished(String errorCode, String actor) {
        int n = 0;
        for (RunRow r : List.copyOf(rows.values())) {
            if (r.state().equals("QUEUED") || r.state().equals("RUNNING")) {
                markFailed(r.runId(), errorCode, r.dataAsOfJson(), actor);
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized Optional<RunRow> find(UUID runId) {
        return Optional.ofNullable(rows.get(runId));
    }

    @Override
    public synchronized List<RunRow> list(int limit) {
        List<RunRow> all = new ArrayList<>(rows.values());
        all.sort(Comparator.comparing(RunRow::requestedAt).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public synchronized Optional<ArtefactLocation> artefact(String artefactId) {
        var m = artefacts.get(artefactId);
        if (m == null || removed.contains(artefactId)) return Optional.empty();
        return Optional.of(new ArtefactLocation(m.artefactId(), m.recoveryVolumePath(), m.wrappedDataKey(), m.plaintextBytes()));
    }

    @Override
    public void auditDownload(UUID runId, String actor) {
        if (failAudit) throw new IllegalStateException("audit_context_missing");
        events.add("audit-download");
        record(ACTION_DOWNLOAD, rows.get(runId), actor);
    }

    private void record(String action, RunRow r, String actor) {
        audit.add(new AuditRow(action, r.runId(), actor, r.templateId(), r.format(), r.projectionMode(), r.parametersJson(),
                r.artefactId(), r.state()));
    }

    private static RunRow with(RunRow r, String state, String artefactId, Long bytes, String error, String dataAsOf,
            Instant finished) {
        return new RunRow(r.runId(), r.templateId(), state, r.projectionMode(), r.format(), r.parametersJson(), dataAsOf,
                artefactId, bytes, error, r.requestedBy(), r.requestedAt(), finished, r.expiresAt());
    }

    /** Ages a run past its retention. */
    synchronized void expire(UUID runId) {
        RunRow r = rows.get(runId);
        rows.put(runId, new RunRow(r.runId(), r.templateId(), r.state(), r.projectionMode(), r.format(), r.parametersJson(),
                r.dataAsOfJson(), r.artefactId(), r.plaintextBytes(), r.errorCode(), r.requestedBy(),
                r.requestedAt().minusSeconds(31L * 86400), r.finishedAt(), Instant.now().minusSeconds(60)));
    }
}
