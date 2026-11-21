package com.securityexpert.nexus.ui2.persistence.supportbundle;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Persistence of vendor uploads and of the cleanup of a leftover work directory (V240). No credential ever reaches this
 * class: the upload row holds the destination, the remote account name, the counters and the remote answer only. The row
 * insert is the audited start (committed before the first byte), the move to a final state the audited end.
 */
public final class JooqSupportBundleUploadStore {

    public static final String ACTION_UPLOAD = "support_bundle_upload";
    public static final String ACTION_CLEANUP = "support_bundle_cleanup_workdir";
    public static final String SYSTEM_ACTOR = "system:support-bundle-upload";

    public record UploadRow(UUID id, UUID requestId, List<UUID> itemIds, String destinationKind, String destinationHost,
            int destinationPort, String remoteUser, String remoteName, String caseRef, String reason, String requestedBy,
            Instant requestedAt, Instant startedAt, Instant finishedAt, Instant heartbeatAt, String state, int attempts,
            Long bytesTotal, long bytesSent, String sha256, String remoteStatus, String errorCode, String errorDetail,
            String hostKeyFingerprint, boolean hostKeyChanged) {
    }

    private static final String COLUMNS = "select id::text as id, request_id::text as request_id, "
            + "array_to_string(item_ids, ',') as item_ids, destination_kind, destination_host, destination_port, remote_user, "
            + "remote_name, case_ref, reason, requested_by, requested_at, started_at, finished_at, heartbeat_at, state, attempts, "
            + "bytes_total, bytes_sent, sha256, remote_status, error_code, error_detail, host_key_fingerprint, host_key_changed "
            + "from support_bundle_upload ";

    private final TransactionBoundary tx;
    private final AuditedTransactionBoundary audited;

    public JooqSupportBundleUploadStore(TransactionBoundary tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.audited = new AuditedTransactionBoundary(tx);
    }

    /**
     * The audited start: the upload row (state RUNNING) and one {@code backup_artefact_retrieval} row per item sent, in one
     * transaction, committed before any vault byte is read. A second running upload of the request violates the unique index.
     */
    public void start(UploadRow row) {
        audited.inTransaction(row.requestedBy(), ACTION_UPLOAD, row.id().toString(), dsl -> {
            dsl.execute("insert into support_bundle_upload(id, request_id, item_ids, destination_kind, destination_host, "
                    + "destination_port, remote_user, remote_name, case_ref, reason, requested_by, requested_at, started_at, "
                    + "heartbeat_at, state) values ({0}::uuid, {1}::uuid, string_to_array({2}, ',')::uuid[], {3}, {4}, {5}, {6}, "
                    + "{7}, {8}, {9}, {10}, now(), now(), now(), 'RUNNING')", row.id().toString(), row.requestId().toString(),
                    row.itemIds().stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse(""), row.destinationKind(),
                    row.destinationHost(), row.destinationPort(), row.remoteUser(), row.remoteName(), row.caseRef(), row.reason(),
                    row.requestedBy());
            dsl.execute("insert into backup_artefact_retrieval(retrieval_id, artefact_id, actor_fingerprint, reason, destination_path) "
                    + "select gen_random_uuid()::text, i.artefact_id, {1}, {2}, {3} from support_bundle_item i "
                    + "where i.request_id = {0}::uuid and i.artefact_id is not null and i.state in ('COMPLETED', 'PARTIAL') "
                    + "and (cardinality({4}::uuid[]) = 0 or i.id = any({4}::uuid[]))",
                    row.requestId().toString(), row.requestedBy(), row.reason(), "support_bundle_uploaded:" + row.destinationKind(),
                    "{" + row.itemIds().stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("") + "}");
            return null;
        });
    }

    /** Heartbeat and counters; never audited (the trigger fires on a state change only). */
    public void progress(UUID id, long bytesSent, Long bytesTotal, String sha256, int attempts) {
        tx.inTransaction(dsl -> dsl.execute("update support_bundle_upload set heartbeat_at = now(), bytes_sent = {1}, "
                + "bytes_total = coalesce({2}::bigint, bytes_total), sha256 = coalesce({3}, sha256), attempts = {4} "
                + "where id = {0}::uuid and state = 'RUNNING'", id.toString(), bytesSent, bytesTotal, sha256, attempts));
    }

    /** The audited end. {@code state} is COMPLETED or FAILED; only a RUNNING row can end. */
    public boolean finish(UUID id, String actor, String state, long bytesSent, Long bytesTotal, String sha256, String remoteStatus,
            String errorCode, String errorDetail, String hostKeyFingerprint, boolean hostKeyChanged, int attempts) {
        return audited.inTransaction(actor, ACTION_UPLOAD, id.toString(), dsl -> dsl.execute(
                "update support_bundle_upload set state = {1}, finished_at = now(), bytes_sent = {2}, "
                        + "bytes_total = coalesce({3}::bigint, bytes_total), sha256 = coalesce({4}, sha256), remote_status = {5}, "
                        + "error_code = {6}, error_detail = left({7}, 500), host_key_fingerprint = {8}, host_key_changed = {9}, "
                        + "attempts = {10} where id = {0}::uuid and state = 'RUNNING'", id.toString(), state, bytesSent,
                bytesTotal, sha256, remoteStatus, errorCode, errorDetail, hostKeyFingerprint, hostKeyChanged, attempts) == 1);
    }

    /** A running row nobody has touched for {@code staleAfterSeconds} belongs to a process that is gone: INTERRUPTED. */
    public int reapStale(long staleAfterSeconds) {
        return audited.inTransaction(SYSTEM_ACTOR, ACTION_UPLOAD, "support_bundle_upload_reap", dsl -> dsl.execute(
                "update support_bundle_upload set state = 'INTERRUPTED', finished_at = now(), error_code = 'SERVICE_INTERRUPTED', "
                        + "error_detail = 'The Overwatch service stopped while the upload was running; the remote copy may be partial' "
                        + "where state = 'RUNNING' and heartbeat_at < now() - make_interval(secs => {0})", staleAfterSeconds));
    }

    public Optional<UploadRow> find(UUID id) {
        return tx.inTransaction(dsl -> dsl.fetchOptional(COLUMNS + "where id = {0}::uuid", id.toString()).map(JooqSupportBundleUploadStore::map));
    }

    public List<UploadRow> list(UUID requestId) {
        return tx.inTransaction(dsl -> dsl.fetch(COLUMNS + "where request_id = {0}::uuid order by requested_at desc, id limit 50",
                requestId.toString()).map(JooqSupportBundleUploadStore::map));
    }

    // ------------------------------------------------------------------ host keys

    /**
     * Records the key a destination presented. Returns true when the destination had a different key on record (the upload
     * then warns); a first sight is not a change. Never refuses anything.
     */
    public boolean recordHostKey(String actor, String host, int port, String keyType, String fingerprintSha256) {
        return audited.inTransaction(actor, ACTION_UPLOAD, host + ":" + port, dsl -> {
            boolean otherKnown = !dsl.fetch("select 1 from support_bundle_upload_host_key where host = {0} and port = {1} "
                    + "and fingerprint_sha256 <> {2} limit 1", host, port, fingerprintSha256).isEmpty();
            boolean thisKnown = !dsl.fetch("select 1 from support_bundle_upload_host_key where host = {0} and port = {1} "
                    + "and fingerprint_sha256 = {2}", host, port, fingerprintSha256).isEmpty();
            dsl.execute("insert into support_bundle_upload_host_key(host, port, fingerprint_sha256, key_type) values ({0}, {1}, {2}, {3}) "
                    + "on conflict on constraint uq_support_bundle_upload_host_key do update set last_seen_at = now()", host, port,
                    fingerprintSha256, keyType);
            return otherKnown && !thisKnown;
        });
    }

    // ------------------------------------------------------------------ work directory cleanup

    public record CleanupTarget(UUID requestId, String deviceId, String vendor, String platformRole, String workDirPath, String state) {
    }

    /** The request a cleanup would act on; empty when it is not eligible (no recorded directory, already clean, still active). */
    public Optional<CleanupTarget> cleanupTarget(UUID requestId) {
        return tx.inTransaction(dsl -> dsl.fetchOptional("select id::text as id, device_id, vendor, platform_role, work_dir_path, state "
                + "from support_bundle_request where id = {0}::uuid and work_dir_path is not null and work_dir_cleaned = false "
                + "and state not in ('QUEUED', 'RUNNING') and cleanup_state is distinct from 'REQUESTED'", requestId.toString())
                .map(r -> new CleanupTarget(UUID.fromString(r.get("id", String.class)), r.get("device_id", String.class),
                        r.get("vendor", String.class), r.get("platform_role", String.class), r.get("work_dir_path", String.class),
                        r.get("state", String.class))));
    }

    /** What the screen shows about a request's leftover directory: whether one is recorded and uncleaned, and the last cleanup. */
    public record CleanupStatus(boolean pending, String state, String outcome) {
    }

    public java.util.Map<UUID, CleanupStatus> cleanupStatuses(java.util.Collection<UUID> requestIds) {
        if (requestIds.isEmpty()) return java.util.Map.of();
        String ids = requestIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
        return tx.inTransaction(dsl -> {
            java.util.Map<UUID, CleanupStatus> out = new java.util.HashMap<>();
            for (var r : dsl.fetch("select id::text as id, (work_dir_path is not null and work_dir_cleaned = false) as pending, "
                    + "cleanup_state, cleanup_outcome from support_bundle_request where id = any(string_to_array({0}, ',')::uuid[])", ids)) {
                out.put(UUID.fromString(r.get("id", String.class)), new CleanupStatus(Boolean.TRUE.equals(r.get("pending", Boolean.class)),
                        r.get("cleanup_state", String.class), r.get("cleanup_outcome", String.class)));
            }
            return out;
        });
    }

    /** Marks the cleanup requested (atomic: a second click finds it already requested). */
    public boolean markCleanupRequested(UUID requestId, String actor, String reason) {
        return audited.inTransaction(actor, ACTION_CLEANUP, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set cleanup_state = 'REQUESTED', cleanup_requested_by = {1}, cleanup_requested_at = now(), "
                        + "cleanup_reason = {2}, cleanup_outcome = null, cleanup_job_id = null where id = {0}::uuid "
                        + "and work_dir_path is not null and work_dir_cleaned = false and state not in ('QUEUED', 'RUNNING') "
                        + "and cleanup_state is distinct from 'REQUESTED'", requestId.toString(), actor, reason) == 1);
    }

    public void attachCleanupJob(UUID requestId, String jobId, String actor) {
        audited.inTransaction(actor, ACTION_CLEANUP, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set cleanup_job_id = {1} where id = {0}::uuid and cleanup_state = 'REQUESTED'",
                requestId.toString(), jobId));
    }

    /** Admission refused: the request is not left waiting for a job that never existed. */
    public void failCleanupRequest(UUID requestId, String actor, String outcome) {
        audited.inTransaction(actor, ACTION_CLEANUP, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set cleanup_state = 'FAILED', cleanup_outcome = {1} where id = {0}::uuid "
                        + "and cleanup_state = 'REQUESTED'", requestId.toString(), outcome));
    }

    private static UploadRow map(Record r) {
        String ids = r.get("item_ids", String.class);
        List<UUID> itemIds = ids == null || ids.isBlank() ? List.of()
                : java.util.Arrays.stream(ids.split(",")).map(UUID::fromString).toList();
        return new UploadRow(UUID.fromString(r.get("id", String.class)), UUID.fromString(r.get("request_id", String.class)), itemIds,
                r.get("destination_kind", String.class), r.get("destination_host", String.class), r.get("destination_port", Integer.class),
                r.get("remote_user", String.class), r.get("remote_name", String.class), r.get("case_ref", String.class),
                r.get("reason", String.class), r.get("requested_by", String.class), instant(r.get("requested_at", Timestamp.class)),
                instant(r.get("started_at", Timestamp.class)), instant(r.get("finished_at", Timestamp.class)),
                instant(r.get("heartbeat_at", Timestamp.class)), r.get("state", String.class), r.get("attempts", Integer.class),
                r.get("bytes_total", Long.class), r.get("bytes_sent", Long.class), r.get("sha256", String.class),
                r.get("remote_status", String.class), r.get("error_code", String.class), r.get("error_detail", String.class),
                r.get("host_key_fingerprint", String.class), Boolean.TRUE.equals(r.get("host_key_changed", Boolean.class)));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
