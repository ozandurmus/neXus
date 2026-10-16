package com.securityexpert.nexus.ui2.persistence.packetanalysis;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisJob;

/**
 * V154: case metadata, capture identity, run state and the append-only ledger of the Packet Analysis feature
 * (decision record 2026-10-15 §5/§6). Raw evidence never passes through this class: it carries ids, sizes,
 * digests, states and the engine's masked summary only. Every mutation runs in an audited transaction, and every
 * upload, analysis request, report view, download and deletion lands one {@code packet_analysis_ledger} row in the
 * same transaction as the change it records.
 *
 * <p>V158 adds host telemetry attachments (server-side text files uploaded next to the captures): their identity, what
 * the sniffer detected, and the clock alignment the user confirmed. They change nothing about the analysis itself, so
 * unlike a capture they may be added, aligned and removed while an analysis runs; they are ledgered, audited, counted
 * against the same store cap and removed with the case.</p>
 *
 * <p>Displayed state is derived from {@code jobs.state} as well as from this feature's own rows, so a job the
 * reconciler or an operator ended (cancelled, rejected, failed) never leaves a case looking busy forever.</p>
 */
public final class PacketAnalysisRepository {

    public record CaseRow(String caseId, String title, String createdBy, Instant createdAt, Instant expiresAt,
            String state, long totalBytes, boolean payloadZeroing, int captureCount, String latestJobId) {
    }

    public record CaptureRow(String captureId, String caseId, String displayName, String format, long sizeBytes,
            String sha256, String storageKey, String uploadedBy, Instant uploadedAt) {
    }

    public record TelemetryRow(String telemetryId, String caseId, String displayName, long sizeBytes, String sha256,
            String storageKey, String detectedFormat, String recognitionNote, String timeZone, int offsetSeconds,
            String anchorLocal, String uploadedBy, Instant uploadedAt, String alignedBy, Instant alignedAt) {
    }

    public record RunRow(String jobId, String caseId, String state, String requestedBy, Instant requestedAt,
            Instant startedAt, Instant finishedAt, String engineVersion, String maskedSummaryJson,
            String failureReason) {
    }

    /** Outcome of a write whose refusal reasons the caller turns into HTTP codes. */
    public enum Outcome { DONE, NOT_FOUND, EXPIRED, BUSY, NO_CAPTURES, FORBIDDEN }

    public static final String ACTION_CASE_CREATE = "packet_analysis_case_create";
    public static final String ACTION_UPLOAD = "packet_analysis_upload";
    public static final String ACTION_ANALYZE = "packet_analysis_analyze";
    public static final String ACTION_VIEW = "packet_analysis_report_view";
    public static final String ACTION_DOWNLOAD = "packet_analysis_download";
    public static final String ACTION_DELETE = "packet_analysis_delete";
    public static final String ACTION_PRUNE = "packet_analysis_prune";
    public static final String ACTION_RUN = "packet_analysis_run_progress";
    public static final String ACTION_TELEMETRY_UPLOAD = "packet_analysis_telemetry_upload";
    public static final String ACTION_TELEMETRY_ALIGN = "packet_analysis_telemetry_align";
    public static final String ACTION_TELEMETRY_DELETE = "packet_analysis_telemetry_delete";

    private static final String ACTIVE_JOB = "j.state in ('REQUESTED','CLAIMED','EXECUTING')";
    /** The run's state as an operator should read it: a run whose job ended without the worker finishing is FAILED. */
    private static final String RUN_STATE = "case when r.state in ('REQUESTED','RUNNING') and not (" + ACTIVE_JOB
            + ") then 'FAILED' when r.state = 'REQUESTED' and j.state <> 'REQUESTED' then 'RUNNING' else r.state end";
    private static final String RUN_REASON = "case when r.state in ('REQUESTED','RUNNING') and not (" + ACTIVE_JOB
            + ") then 'JOB_' || j.state else r.failure_reason end";
    private static final String RUN_COLUMNS = "select r.job_id, r.case_id, " + RUN_STATE + " as state, r.requested_by, "
            + "r.requested_at, r.started_at, coalesce(r.finished_at, case when not (" + ACTIVE_JOB + ") then j.finished_at end) "
            + "as finished_at, r.engine_version, r.masked_summary::text as masked_summary, " + RUN_REASON
            + " as failure_reason from packet_analysis_run r join jobs j on j.job_id = r.job_id ";
    private static final String CASE_COLUMNS = "select c.case_id, c.title, c.created_by, c.created_at, c.expires_at, "
            + "c.total_bytes, c.payload_zeroing, "
            + "(select count(*) from packet_analysis_capture p where p.case_id = c.case_id) as capture_count, "
            + "latest.job_id as latest_job_id, "
            + "case when latest.job_id is null then 'OPEN' "
            + "     when latest.state = 'REQUESTED' then 'QUEUED' when latest.state = 'RUNNING' then 'ANALYZING' "
            + "     when latest.state = 'COMPLETED' then 'ANALYZED' else 'FAILED' end as state "
            + "from packet_analysis_case c left join lateral (" + RUN_COLUMNS
            + "where r.case_id = c.case_id order by r.requested_at desc, r.job_id desc limit 1) latest on true ";

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public PacketAnalysisRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    // ---- cases ---------------------------------------------------------------------------------------------

    public CaseRow createCase(String caseId, String title, String actor, boolean payloadZeroing) {
        return audited.inTransaction(actor, ACTION_CASE_CREATE, db -> {
            db.execute("insert into packet_analysis_case(case_id, title, created_by, created_at, expires_at, state, "
                    + "payload_zeroing) values ({0}, {1}, {2}, now(), now() + interval '7 days', 'OPEN', {3})",
                    caseId, title, actor, payloadZeroing);
            ledger(db, caseId, null, null, "CASE_CREATED", actor, null);
            return db.fetch(CASE_COLUMNS + "where c.case_id = {0}", caseId).map(PacketAnalysisRepository::toCase).get(0);
        });
    }

    /** Unexpired cases, newest first. An expired case is gone for every reader even before the pruner runs. */
    public List<CaseRow> listCases(int limit) {
        return transactions.inTransaction(db -> db.fetch(CASE_COLUMNS + "where c.expires_at > now() "
                + "order by c.created_at desc, c.case_id limit {0}", Math.max(1, Math.min(limit, 500)))
                .map(PacketAnalysisRepository::toCase));
    }

    public Optional<CaseRow> findCase(String caseId) {
        return transactions.inTransaction(db -> db.fetch(CASE_COLUMNS + "where c.case_id = {0} and c.expires_at > now()",
                caseId).stream().findFirst().map(PacketAnalysisRepository::toCase));
    }

    /** Bytes of every capture still on record -- the store's accounted size. */
    public long storedCaptureBytes() {
        return transactions.inTransaction(db -> {
            Long value = db.fetchOne("select coalesce(sum(size_bytes), 0)::bigint from packet_analysis_capture").get(0, Long.class);
            return value == null ? 0L : value;
        });
    }

    // ---- captures ------------------------------------------------------------------------------------------

    public List<CaptureRow> captures(String caseId) {
        return transactions.inTransaction(db -> db.fetch("select * from packet_analysis_capture where case_id = {0} "
                + "order by uploaded_at, capture_id", caseId).map(PacketAnalysisRepository::toCapture));
    }

    public Optional<CaptureRow> findCapture(String captureId) {
        return transactions.inTransaction(db -> db.fetch("select p.* from packet_analysis_capture p join packet_analysis_case c "
                + "on c.case_id = p.case_id where p.capture_id = {0} and c.expires_at > now()", captureId)
                .stream().findFirst().map(PacketAnalysisRepository::toCapture));
    }

    /** Records an uploaded capture. Refused while an analysis of the case is queued or running, or once it expired. */
    public Outcome addCapture(CaptureRow capture, String actor) {
        return audited.inTransaction(actor, ACTION_UPLOAD, db -> {
            Outcome refusal = lockForChange(db, capture.caseId());
            if (refusal != Outcome.DONE) return refusal;
            db.execute("insert into packet_analysis_capture(capture_id, case_id, display_name, capture_format, size_bytes, "
                    + "sha256, storage_key, uploaded_by) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7})",
                    capture.captureId(), capture.caseId(), capture.displayName(), capture.format(), capture.sizeBytes(),
                    capture.sha256(), capture.storageKey(), actor);
            db.execute("update packet_analysis_case set total_bytes = total_bytes + {1}, state = 'OPEN' where case_id = {0}",
                    capture.caseId(), capture.sizeBytes());
            ledger(db, capture.caseId(), capture.captureId(), null, "CAPTURE_UPLOADED", actor, capture.sizeBytes());
            return Outcome.DONE;
        });
    }

    // ---- host telemetry (V158) -----------------------------------------------------------------------------

    public List<TelemetryRow> telemetry(String caseId) {
        return transactions.inTransaction(db -> db.fetch("select * from packet_analysis_host_telemetry where case_id = {0} "
                + "order by uploaded_at, telemetry_id", caseId).map(PacketAnalysisRepository::toTelemetry));
    }

    public Optional<TelemetryRow> findTelemetry(String telemetryId) {
        return transactions.inTransaction(db -> db.fetch("select t.* from packet_analysis_host_telemetry t "
                + "join packet_analysis_case c on c.case_id = t.case_id where t.telemetry_id = {0} and c.expires_at > now()",
                telemetryId).stream().findFirst().map(PacketAnalysisRepository::toTelemetry));
    }

    /** Records an uploaded telemetry file. Refused once the case expired; allowed while an analysis runs. */
    public Outcome addTelemetry(TelemetryRow row, String actor) {
        return audited.inTransaction(actor, ACTION_TELEMETRY_UPLOAD, db -> {
            Outcome refusal = lockLive(db, row.caseId());
            if (refusal != Outcome.DONE) return refusal;
            db.execute("insert into packet_analysis_host_telemetry(telemetry_id, case_id, display_name, size_bytes, sha256, "
                    + "storage_key, detected_format, recognition_note, time_zone, offset_seconds, anchor_local, uploaded_by) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11})",
                    row.telemetryId(), row.caseId(), row.displayName(), row.sizeBytes(), row.sha256(), row.storageKey(),
                    row.detectedFormat(), row.recognitionNote(), row.timeZone(), row.offsetSeconds(), row.anchorLocal(), actor);
            ledger(db, row.caseId(), row.telemetryId(), null, "TELEMETRY_UPLOADED", actor, row.sizeBytes());
            return Outcome.DONE;
        });
    }

    /**
     * Sets the clock alignment of one file: {@code timeZone} {@code null} means "not confirmed" (the report reads the
     * file as UTC and says so). The values are validated by the caller; the table's checks refuse anything malformed.
     */
    public Outcome setTelemetryAlignment(String telemetryId, String timeZone, int offsetSeconds, String anchorLocal, String actor) {
        return audited.inTransaction(actor, ACTION_TELEMETRY_ALIGN, db -> {
            var row = db.fetch("select case_id from packet_analysis_host_telemetry where telemetry_id = {0}", telemetryId);
            if (row.isEmpty()) return Outcome.NOT_FOUND;
            String caseId = row.get(0).get("case_id", String.class);
            Outcome refusal = lockLive(db, caseId);
            if (refusal != Outcome.DONE) return refusal;
            db.execute("update packet_analysis_host_telemetry set time_zone = {1}, offset_seconds = {2}, anchor_local = {3}, "
                    + "aligned_by = {4}, aligned_at = now() where telemetry_id = {0}", telemetryId, timeZone, offsetSeconds,
                    anchorLocal, actor);
            ledger(db, caseId, telemetryId, null, "TELEMETRY_ALIGNED", actor, null);
            return Outcome.DONE;
        });
    }

    /** Deletes one telemetry file: the file first (under the case lock), then its row and the ledger row. */
    public Outcome deleteTelemetry(String telemetryId, String actor, Runnable removeFile) {
        return audited.inTransaction(actor, ACTION_TELEMETRY_DELETE, db -> {
            var row = db.fetch("select case_id, size_bytes from packet_analysis_host_telemetry where telemetry_id = {0}", telemetryId);
            if (row.isEmpty()) return Outcome.NOT_FOUND;
            String caseId = row.get(0).get("case_id", String.class);
            Long size = row.get(0).get("size_bytes", Long.class);
            Outcome refusal = lockLive(db, caseId);
            if (refusal != Outcome.DONE) return refusal;
            removeFile.run();
            db.execute("delete from packet_analysis_host_telemetry where telemetry_id = {0}", telemetryId);
            ledger(db, caseId, telemetryId, null, "TELEMETRY_DELETED", actor, size);
            return Outcome.DONE;
        });
    }

    // ---- runs ----------------------------------------------------------------------------------------------

    /**
     * Admits one {@code packet_analysis_run} job for the case: the job row ({@code target_kind
     * packet_analysis_case}), the run row and the ledger row commit together, or none does.
     */
    public Outcome requestAnalysis(String caseId, String jobId, String actor) {
        return audited.inTransaction(actor, ACTION_ANALYZE, db -> {
            Outcome refusal = lockForChange(db, caseId);
            if (refusal != Outcome.DONE) return refusal;
            Long captures = db.fetchOne("select count(*)::bigint from packet_analysis_capture where case_id = {0}", caseId)
                    .get(0, Long.class);
            if (captures == null || captures == 0) return Outcome.NO_CAPTURES;
            db.execute("insert into jobs(job_id, job_type, capability_id, target_ref, target_kind, "
                    + "submitted_by_actor_fingerprint, submitted_at, idempotency_key, action_class, state) "
                    + "values ({0}, {1}, {1}, {2}, {3}, {4}, now(), {5}, 'read', 'REQUESTED')",
                    jobId, PacketAnalysisJob.CAPABILITY, caseId, PacketAnalysisJob.TARGET_KIND, actor,
                    "packet-analysis:" + jobId);
            db.execute("insert into packet_analysis_run(job_id, case_id, state, requested_by) values ({0}, {1}, 'REQUESTED', {2})",
                    jobId, caseId, actor);
            db.execute("update packet_analysis_case set state = 'QUEUED' where case_id = {0}", caseId);
            ledger(db, caseId, null, jobId, "ANALYSIS_REQUESTED", actor, null);
            return Outcome.DONE;
        });
    }

    public List<RunRow> runs(String caseId) {
        return transactions.inTransaction(db -> db.fetch(RUN_COLUMNS + "where r.case_id = {0} "
                + "order by r.requested_at desc, r.job_id desc limit 50", caseId).map(PacketAnalysisRepository::toRun));
    }

    public Optional<RunRow> findRun(String jobId) {
        return transactions.inTransaction(db -> db.fetch(RUN_COLUMNS + "join packet_analysis_case c on c.case_id = r.case_id "
                + "where r.job_id = {0} and c.expires_at > now()", jobId).stream().findFirst().map(PacketAnalysisRepository::toRun));
    }

    // ---- ledger-only reads ---------------------------------------------------------------------------------

    /** A view or a download: recorded before the first byte is served; a failure here refuses the read. */
    public void recordAccess(String caseId, String captureId, String jobId, String event, String actor, Long bytes) {
        String action = "CAPTURE_DOWNLOADED".equals(event) ? ACTION_DOWNLOAD : ACTION_VIEW;
        audited.inTransaction(actor, action, db -> {
            ledger(db, caseId, captureId, jobId, event, actor, bytes);
            return null;
        });
    }

    // ---- deletion ------------------------------------------------------------------------------------------

    /**
     * Deletes a case: under the case row lock, with no analysis queued or running, {@code removeFiles} runs first
     * and the rows and the ledger row are written only when it returned; a failure rolls everything back, so the
     * record never claims a deletion that did not happen. {@code requireOwner} limits a deletion to the case's
     * creator; an administrator passes {@code null}.
     */
    public Outcome deleteCase(String caseId, String actor, String requireOwner, Runnable removeFiles) {
        return audited.inTransaction(actor, ACTION_DELETE, db -> {
            var row = db.fetch("select created_by from packet_analysis_case where case_id = {0} for update", caseId);
            if (row.isEmpty()) return Outcome.NOT_FOUND;
            if (requireOwner != null && !requireOwner.equals(row.get(0).get("created_by", String.class))) return Outcome.FORBIDDEN;
            if (activeJob(db, caseId)) return Outcome.BUSY;
            removeFiles.run();
            long bytes = removeRows(db, caseId);
            ledger(db, caseId, null, null, "CASE_DELETED", actor, bytes);
            return Outcome.DONE;
        });
    }

    /** Cases past {@code expires_at} with no queued or running analysis, oldest first. */
    public List<String> expiredCases(int limit) {
        return transactions.inTransaction(db -> db.fetch("select c.case_id from packet_analysis_case c where c.expires_at <= now() "
                + "and not exists (select 1 from packet_analysis_run r join jobs j on j.job_id = r.job_id "
                + "where r.case_id = c.case_id and " + ACTIVE_JOB + ") order by c.expires_at, c.case_id limit {0}", limit)
                .map(r -> r.get("case_id", String.class)));
    }

    /**
     * The retention pruner's deletion of one expired case, with the same ordering as {@link #deleteCase}: files
     * first, then rows and the {@code CASE_PRUNED} tombstone (truthful, like backup retention's ledger).
     */
    public boolean prune(String caseId, String actor, Runnable removeFiles) {
        return audited.inTransaction(actor, ACTION_PRUNE, db -> {
            if (db.fetch("select 1 from packet_analysis_case where case_id = {0} and expires_at <= now() for update", caseId).isEmpty())
                return false;
            if (activeJob(db, caseId)) return false;
            removeFiles.run();
            long bytes = removeRows(db, caseId);
            ledger(db, caseId, null, null, "CASE_PRUNED", actor, bytes);
            return true;
        });
    }

    // ---- worker progress (fenced on the job lease) ---------------------------------------------------------

    /** The case and captures a claimed job analyses; empty when the case is gone. */
    public Optional<RunRow> runForJob(String jobId) {
        return transactions.inTransaction(db -> db.fetch(RUN_COLUMNS + "where r.job_id = {0}", jobId).stream().findFirst()
                .map(PacketAnalysisRepository::toRun));
    }

    public Optional<CaseRow> caseForWorker(String caseId) {
        return transactions.inTransaction(db -> db.fetch(CASE_COLUMNS + "where c.case_id = {0}", caseId).stream().findFirst()
                .map(PacketAnalysisRepository::toCase));
    }

    public boolean markRunning(String jobId, long epoch, String actor) {
        return audited.inTransaction(actor, ACTION_RUN, db -> fenced(db, jobId, epoch,
                "update packet_analysis_run set state = 'RUNNING', started_at = now() where job_id = {0} and state = 'REQUESTED'")
                && touchCase(db, jobId, "ANALYZING"));
    }

    public boolean complete(String jobId, long epoch, String engineVersion, String maskedSummaryJson, String actor) {
        return audited.inTransaction(actor, ACTION_RUN, db -> fenced(db, jobId, epoch,
                "update packet_analysis_run set state = 'COMPLETED', finished_at = now(), engine_version = {2}, "
                        + "masked_summary = {3}::jsonb where job_id = {0} and state = 'RUNNING'", engineVersion, maskedSummaryJson)
                && touchCase(db, jobId, "ANALYZED"));
    }

    public boolean fail(String jobId, long epoch, String reason, String actor) {
        return audited.inTransaction(actor, ACTION_RUN, db -> fenced(db, jobId, epoch,
                "update packet_analysis_run set state = 'FAILED', finished_at = now(), failure_reason = {2} "
                        + "where job_id = {0} and state in ('REQUESTED', 'RUNNING')", reason)
                && touchCase(db, jobId, "FAILED"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static boolean fenced(DSLContext db, String jobId, long epoch, String update, Object... extra) {
        Object[] args = new Object[2 + extra.length];
        args[0] = jobId;
        args[1] = epoch;
        System.arraycopy(extra, 0, args, 2, extra.length);
        return db.execute(update + " and exists (select 1 from jobs where job_id = {0} and lease_epoch = {1} "
                + "and state = 'EXECUTING' and ui2_job_owner_valid(job_id, lease_epoch))", args) == 1;
    }

    private static boolean touchCase(DSLContext db, String jobId, String state) {
        db.execute("update packet_analysis_case set state = {1} where case_id = (select case_id from packet_analysis_run "
                + "where job_id = {0})", jobId, state);
        return true;
    }

    private static Outcome lockForChange(DSLContext db, String caseId) {
        var row = db.fetch("select expires_at > now() as live from packet_analysis_case where case_id = {0} for update", caseId);
        if (row.isEmpty()) return Outcome.NOT_FOUND;
        if (!Boolean.TRUE.equals(row.get(0).get("live", Boolean.class))) return Outcome.EXPIRED;
        return activeJob(db, caseId) ? Outcome.BUSY : Outcome.DONE;
    }

    /** The case row lock for a change that does not touch the analysis: only existence and expiry are checked. */
    private static Outcome lockLive(DSLContext db, String caseId) {
        var row = db.fetch("select expires_at > now() as live from packet_analysis_case where case_id = {0} for update", caseId);
        if (row.isEmpty()) return Outcome.NOT_FOUND;
        return Boolean.TRUE.equals(row.get(0).get("live", Boolean.class)) ? Outcome.DONE : Outcome.EXPIRED;
    }

    private static boolean activeJob(DSLContext db, String caseId) {
        return !db.fetch("select 1 from packet_analysis_run r join jobs j on j.job_id = r.job_id where r.case_id = {0} and "
                + ACTIVE_JOB, caseId).isEmpty();
    }

    private static long removeRows(DSLContext db, String caseId) {
        Long bytes = db.fetchOne("select total_bytes from packet_analysis_case where case_id = {0}", caseId).get(0, Long.class);
        db.execute("delete from packet_analysis_host_telemetry where case_id = {0}", caseId);
        db.execute("delete from packet_analysis_run where case_id = {0}", caseId);
        db.execute("delete from packet_analysis_capture where case_id = {0}", caseId);
        db.execute("delete from packet_analysis_case where case_id = {0}", caseId);
        return bytes == null ? 0L : bytes;
    }

    private static void ledger(DSLContext db, String caseId, String captureId, String jobId, String event, String actor, Long bytes) {
        db.execute("insert into packet_analysis_ledger(ledger_id, case_id, capture_id, job_id, event, actor_fingerprint, bytes) "
                + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6})", UUID.randomUUID().toString(), caseId, captureId, jobId, event,
                actor, bytes);
    }

    private static Instant instant(Record row, String column) {
        OffsetDateTime value = row.get(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static CaseRow toCase(Record r) {
        Long bytes = r.get("total_bytes", Long.class);
        Long count = r.get("capture_count", Long.class);
        return new CaseRow(r.get("case_id", String.class), r.get("title", String.class), r.get("created_by", String.class),
                instant(r, "created_at"), instant(r, "expires_at"), r.get("state", String.class), bytes == null ? 0 : bytes,
                Boolean.TRUE.equals(r.get("payload_zeroing", Boolean.class)), count == null ? 0 : count.intValue(),
                r.get("latest_job_id", String.class));
    }

    private static CaptureRow toCapture(Record r) {
        Long size = r.get("size_bytes", Long.class);
        return new CaptureRow(r.get("capture_id", String.class), r.get("case_id", String.class),
                r.get("display_name", String.class), r.get("capture_format", String.class), size == null ? 0 : size,
                r.get("sha256", String.class), r.get("storage_key", String.class), r.get("uploaded_by", String.class),
                instant(r, "uploaded_at"));
    }

    private static TelemetryRow toTelemetry(Record r) {
        Long size = r.get("size_bytes", Long.class);
        Integer offset = r.get("offset_seconds", Integer.class);
        return new TelemetryRow(r.get("telemetry_id", String.class), r.get("case_id", String.class),
                r.get("display_name", String.class), size == null ? 0 : size, r.get("sha256", String.class),
                r.get("storage_key", String.class), r.get("detected_format", String.class),
                r.get("recognition_note", String.class), r.get("time_zone", String.class), offset == null ? 0 : offset,
                r.get("anchor_local", String.class), r.get("uploaded_by", String.class), instant(r, "uploaded_at"),
                r.get("aligned_by", String.class), instant(r, "aligned_at"));
    }

    private static RunRow toRun(Record r) {
        return new RunRow(r.get("job_id", String.class), r.get("case_id", String.class), r.get("state", String.class),
                r.get("requested_by", String.class), instant(r, "requested_at"), instant(r, "started_at"),
                instant(r, "finished_at"), r.get("engine_version", String.class), r.get("masked_summary", String.class),
                r.get("failure_reason", String.class));
    }
}
