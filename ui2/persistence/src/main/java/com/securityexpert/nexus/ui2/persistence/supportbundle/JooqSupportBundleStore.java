package com.securityexpert.nexus.ui2.persistence.supportbundle;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/**
 * Persistence of support bundle requests and items (V230, V274, SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 5.1).
 * Every mutation runs in an audited transaction (actor and action set), as the table triggers require.
 */
public final class JooqSupportBundleStore implements SupportBundleRunStore {

    public static final String ACTION_REQUEST = "support_bundle_request";
    public static final String ACTION_CANCEL = "support_bundle_cancel";
    public static final String ACTION_RETRIEVE = "support_bundle_retrieve";
    public static final String ACTION_DELETE = "support_bundle_delete";
    public static final String ACTION_RUN = "support_bundle_run";
    public static final String WORKER_ACTOR = "system:worker";
    public static final String SYSTEM_RETRY_ACTOR = "system:support-bundle-retry";

    public static final String HOLD_MODULE_DRAIN = "MODULE_DRAIN";
    public static final String HOLD_COLLECTION_WINDOW = "COLLECTION_WINDOW";
    public static final String HOLD_DEVICE_JOB_RUNNING = "DEVICE_JOB_RUNNING";
    public static final String HOLD_HA_PEER_RUNNING = "HA_PEER_RUNNING";
    public static final String HOLD_FLEET_CAP = "FLEET_CAP";
    public static final List<String> CLOSED_HOLD_REASONS = List.of(
            HOLD_MODULE_DRAIN, HOLD_COLLECTION_WINDOW, HOLD_DEVICE_JOB_RUNNING, HOLD_HA_PEER_RUNNING, HOLD_FLEET_CAP);

    /** One request. {@code requestedBy}, {@code reason} and {@code caseRef} are never shown to aiview. */
    public record RequestRow(UUID id, String deviceId, String vendor, String platformRole, String runMode, Instant runAt,
            String caseRef, String reason, String requestedBy, Instant requestedAt, Instant startedAt, Instant finishedAt,
            Instant expiresAt, Instant expiryWarnedAt, Instant downloadedAt, int downloadCount, String deletedBy,
            Instant deletedAt, String jobId, String precheckJson, String state, String outcome,
            UUID previousRequestId, boolean autoRetry, List<String> holdReasons, String retryOutcome) {

        public RequestRow {
            holdReasons = canonicalHoldReasons(holdReasons);
        }

        public RequestRow(UUID id, String deviceId, String vendor, String platformRole, String runMode, Instant runAt,
                String caseRef, String reason, String requestedBy, Instant requestedAt, Instant startedAt, Instant finishedAt,
                Instant expiresAt, Instant expiryWarnedAt, Instant downloadedAt, int downloadCount, String deletedBy,
                Instant deletedAt, String jobId, String precheckJson, String state, String outcome) {
            this(id, deviceId, vendor, platformRole, runMode, runAt, caseRef, reason, requestedBy, requestedAt, startedAt,
                    finishedAt, expiresAt, expiryWarnedAt, downloadedAt, downloadCount, deletedBy, deletedAt, jobId,
                    precheckJson, state, outcome, null, false, List.of(), null);
        }
    }

    public record ItemRow(UUID id, UUID requestId, int ordinal, String kind, Integer vsId, String domainRef, String state,
            String outcomeCode, String outcomeDetail, String archiveEntryName, String artefactId, Long sizeBytes, String sha256,
            String redactionRuleset, List<String> gateIdsUsed, Instant startedAt, Instant finishedAt) {

        public ItemRow(UUID id, UUID requestId, int ordinal, String kind, Integer vsId, String state, String outcomeCode,
                String outcomeDetail, String archiveEntryName, String artefactId, Long sizeBytes, String sha256,
                String redactionRuleset, List<String> gateIdsUsed, Instant startedAt, Instant finishedAt) {
            this(id, requestId, ordinal, kind, vsId, null, state, outcomeCode, outcomeDetail, archiveEntryName, artefactId,
                    sizeBytes, sha256, redactionRuleset, gateIdsUsed, startedAt, finishedAt);
        }
    }

    public record ItemSpec(String kind, Integer vsId, String domainRef) {
        public ItemSpec(String kind, Integer vsId) {
            this(kind, vsId, null);
        }
    }

    /** Where an item's encrypted bytes are, for the download. */
    public record StoredItem(UUID itemId, String kind, String archiveEntryName, String artefactId, String recoveryVolumePath,
            byte[] wrappedDataKey, long plaintextBytes, String sha256, String state, String outcomeCode) {
    }

    private static final String REQUEST_COLUMNS = "select id::text as id, device_id, vendor, platform_role, run_mode, run_at, "
            + "case_ref, reason, requested_by, requested_at, started_at, finished_at, expires_at, expiry_warned_at, "
            + "downloaded_at, download_count, deleted_by, deleted_at, job_id, precheck_json::text as precheck_json, state, "
            + "outcome, previous_request_id::text as previous_request_id, auto_retry, "
            + "array_to_string(hold_reasons, ',') as hold_reasons, retry_outcome from support_bundle_request ";
    private static final String ITEM_COLUMNS = "select id::text as id, request_id::text as request_id, ordinal, kind, vs_id, "
            + "domain_ref, state, outcome_code, outcome_detail, archive_entry_name, artefact_id, size_bytes, sha256, "
            + "redaction_ruleset, array_to_string(gate_ids_used, ',') as gate_ids, started_at, finished_at from support_bundle_item ";

    private final TransactionBoundary tx;
    private final AuditedTransactionBoundary audited;

    public JooqSupportBundleStore(TransactionBoundary tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.audited = new AuditedTransactionBoundary(tx);
    }

    // ------------------------------------------------------------------ service side

    /** Inserts the request and its items (ordinal in list order). A second active request for the device violates the unique index. */
    public void insert(RequestRow row, List<ItemSpec> items) {
        String holdCsv = String.join(",", canonicalHoldReasons(row.holdReasons()));
        audited.inTransaction(row.requestedBy(), ACTION_REQUEST, row.id().toString(), dsl -> {
            dsl.execute("insert into support_bundle_request(id, device_id, vendor, platform_role, run_mode, run_at, case_ref, "
                    + "reason, requested_by, requested_at, expires_at, previous_request_id, auto_retry, hold_reasons, state) values "
                    + "({0}::uuid, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}::uuid, {12}, "
                    + "case when {13} = '' then '{}'::text[] else string_to_array({13}, ',')::text[] end, 'QUEUED')",
                    row.id().toString(), row.deviceId(), row.vendor(), row.platformRole(),
                    row.runMode(), Timestamp.from(row.runAt()), row.caseRef(), row.reason(), row.requestedBy(),
                    Timestamp.from(row.requestedAt()), Timestamp.from(row.expiresAt()),
                    row.previousRequestId() == null ? null : row.previousRequestId().toString(),
                    row.autoRetry(), holdCsv);
            int ordinal = 1;
            for (ItemSpec item : items) {
                dsl.execute("insert into support_bundle_item(id, request_id, ordinal, kind, vs_id, domain_ref, state) values "
                        + "({0}::uuid, {1}::uuid, {2}, {3}, {4}, {5}, 'QUEUED')", UUID.randomUUID().toString(),
                        row.id().toString(), ordinal++, item.kind(), item.vsId(), item.domainRef());
            }
            return null;
        });
    }

    public void attachJob(UUID requestId, String jobId, String actor) {
        audited.inTransaction(actor, ACTION_REQUEST, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set job_id = {1} where id = {0}::uuid and job_id is null",
                requestId.toString(), jobId));
    }

    /** "Run at a time": the job is claimable only from {@code notBefore} (V128 admission_not_before). */
    public void deferJob(String jobId, Instant notBefore, String actor) {
        audited.inTransaction(actor, ACTION_REQUEST, jobId, dsl -> dsl.execute(
                "update jobs set admission_not_before = {1} where job_id = {0} and state = 'REQUESTED'", jobId,
                Timestamp.from(notBefore)));
    }

    /** A request that never reached a job (admission refused) is closed as FAILED, so it does not hold the device. */
    public void failQueued(UUID requestId, String actor, String outcomeDetail) {
        audited.inTransaction(actor, ACTION_REQUEST, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set state = 'FAILED', outcome = 'FAILED', finished_at = now() "
                        + "where id = {0}::uuid and state = 'QUEUED'", requestId.toString()));
    }

    /** Cancels a queued request (and its unclaimed job if one was attached); true when it was still queued. */
    public boolean cancelQueued(UUID requestId, String actor) {
        return audited.inTransaction(actor, ACTION_CANCEL, requestId.toString(), dsl -> {
            int n = dsl.execute("update support_bundle_request set state = 'CANCELLED', outcome = 'CANCELLED', "
                    + "finished_at = now() where id = {0}::uuid and state = 'QUEUED'", requestId.toString());
            if (n == 1) {
                dsl.execute("update support_bundle_item set state = 'CANCELLED', finished_at = now() "
                        + "where request_id = {0}::uuid and state = 'QUEUED'", requestId.toString());
                dsl.execute("update jobs set state = 'CANCELLED', outcome = 'CANCELLED', "
                        + "terminal_reason = 'CANCELLED_BY_OPERATOR', finished_at = now() "
                        + "where job_id = (select job_id from support_bundle_request where id = {0}::uuid) "
                        + "and state = 'REQUESTED'", requestId.toString());
            }
            return n == 1;
        });
    }

    /**
     * PO 2026-11-15 (Q2): deleting a bundle marks the request DELETED at once (no download is possible afterwards); the
     * worker's retention janitor removes the encrypted files (the service's store is read-only) and writes the ledger rows.
     */
    public boolean markDeleted(UUID requestId, String actor, String reason) {
        return audited.inTransaction(actor, ACTION_DELETE, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set state = 'DELETED', deleted_by = {1}, deleted_at = now(), deleted_reason = {2} "
                        + "where id = {0}::uuid and state in ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED')",
                requestId.toString(), actor, reason) == 1);
    }

    /**
     * The audit rows (before the first byte) and the download counters, in one transaction: the request's own audit row,
     * and one {@code backup_artefact_retrieval} row per stored item with the operator's reason. Throws when any cannot be
     * written; the caller then sends nothing.
     */
    public void recordDownload(UUID requestId, String actor, String reason, String mode) {
        audited.inTransaction(actor, ACTION_RETRIEVE, requestId.toString(), dsl -> {
            dsl.fetch("select audit_support_bundle_download({0}::uuid)", requestId.toString());
            dsl.execute("insert into backup_artefact_retrieval(retrieval_id, artefact_id, actor_fingerprint, reason, destination_path) "
                    + "select gen_random_uuid()::text, i.artefact_id, {1}, {2}, {3} from support_bundle_item i "
                    + "where i.request_id = {0}::uuid and i.artefact_id is not null and i.state in ('COMPLETED', 'PARTIAL')",
                    requestId.toString(), actor, reason, "support_bundle_downloaded" + ("sanitized".equals(mode) ? "_sanitized" : ""));
            dsl.execute("update support_bundle_request set downloaded_at = now(), downloaded_by = {1}, "
                    + "download_count = download_count + 1 where id = {0}::uuid", requestId.toString(), actor);
            return null;
        });
    }

    public Optional<RequestRow> find(UUID id) {
        return tx.inTransaction(dsl -> dsl.fetchOptional(REQUEST_COLUMNS + "where id = {0}::uuid", id.toString())
                .map(JooqSupportBundleStore::toRequest));
    }

    @Override
    public Optional<RequestRow> findByJob(String jobId) {
        return tx.inTransaction(dsl -> dsl.fetchOptional(REQUEST_COLUMNS + "where job_id = {0}", jobId)
                .map(JooqSupportBundleStore::toRequest));
    }

    public List<RequestRow> list(Optional<String> deviceId, Optional<String> state, int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        return tx.inTransaction(dsl -> dsl.fetch(REQUEST_COLUMNS + "where ({0}::text is null or device_id = {0}) "
                + "and ({1}::text is null or state = {1}) order by requested_at desc, id limit {2}",
                deviceId.orElse(null), state.orElse(null), bounded).map(JooqSupportBundleStore::toRequest));
    }

    @Override
    public List<ItemRow> items(UUID requestId) {
        return tx.inTransaction(dsl -> dsl.fetch(ITEM_COLUMNS + "where request_id = {0}::uuid order by ordinal",
                requestId.toString()).map(JooqSupportBundleStore::toItem));
    }

    /** The items of a request that stored an artefact, with their encrypted location, in ordinal order. */
    public List<StoredItem> storedItems(UUID requestId) {
        return tx.inTransaction(dsl -> dsl.fetch("select i.id::text as id, i.kind, i.archive_entry_name, i.artefact_id, "
                + "a.recovery_volume_path, a.wrapped_data_key, a.plaintext_bytes, i.sha256, i.state, i.outcome_code "
                + "from support_bundle_item i join backup_artefact a on a.artefact_id = i.artefact_id "
                + "where i.request_id = {0}::uuid and i.state in ('COMPLETED', 'PARTIAL')"
                + JooqRetentionQueries.andNotRemoved("a") + " order by i.ordinal", requestId.toString())
                .map(r -> new StoredItem(UUID.fromString(r.get("id", String.class)), r.get("kind", String.class),
                        r.get("archive_entry_name", String.class), r.get("artefact_id", String.class),
                        r.get("recovery_volume_path", String.class), r.get("wrapped_data_key", byte[].class),
                        r.get("plaintext_bytes", Long.class), r.get("sha256", String.class), r.get("state", String.class),
                        r.get("outcome_code", String.class))));
    }

    /** Live (not deleted, not expired, not failed) requests of a device: the per-device cap of three. */
    public int liveRequestCount(String deviceId) {
        Integer n = tx.inTransaction(dsl -> dsl.fetchOne("select count(*) from support_bundle_request where device_id = {0} "
                + "and state in ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL')", deviceId).get(0, Integer.class));
        return n == null ? 0 : n;
    }

    public Optional<RequestRow> oldestLive(String deviceId) {
        return tx.inTransaction(dsl -> dsl.fetchOptional(REQUEST_COLUMNS + "where device_id = {0} and state in "
                + "('COMPLETED', 'PARTIAL') order by requested_at, id limit 1", deviceId).map(JooqSupportBundleStore::toRequest));
    }

    /** Live ciphertext bytes of support bundle artefacts: the fleet quota (50 GiB), outside the backup budget. */
    public long liveBundleBytes() {
        Long sum = tx.inTransaction(dsl -> dsl.fetchOne("select coalesce(sum(a.ciphertext_bytes), 0) from backup_artefact a "
                + "where a.artefact_class = 'support_bundle'" + JooqRetentionQueries.andNotRemoved("a")).get(0, Long.class));
        return sum == null ? 0L : sum;
    }

    /** Support bundle jobs running or queued fleet-wide (device-busy and fleet-cap guards read this later). */
    public int runningCount() {
        Integer n = tx.inTransaction(dsl -> dsl.fetchOne("select count(*) from support_bundle_request where state = 'RUNNING'")
                .get(0, Integer.class));
        return n == null ? 0 : n;
    }

    /** True while the diagnostics module or its effective worker owner is draining (V274 pre-submit warning and hold reason). */
    public boolean isSupportBundleModuleDraining() {
        Boolean draining = tx.inTransaction(dsl -> dsl.fetchOne(
                "select exists (select 1 from module_runtime_control m join module_runtime_control r on r.module = m.effective_owner "
                        + "where m.module in ('diagnostics', 'general') and (m.drain_requested or r.drain_requested))")
                .get(0, Boolean.class));
        return Boolean.TRUE.equals(draining);
    }

    /** Closed hold reasons active right now for {@code deviceId} when a request is submitted for immediate execution. */
    public List<String> currentHoldReasons(String deviceId, boolean collectionWindowOpen) {
        return tx.inTransaction(dsl -> {
            List<String> out = new ArrayList<>();
            Boolean draining = dsl.fetchOne(
                    "select exists (select 1 from module_runtime_control m join module_runtime_control r on r.module = m.effective_owner "
                            + "where m.module in ('diagnostics', 'general') and (m.drain_requested or r.drain_requested))")
                    .get(0, Boolean.class);
            if (Boolean.TRUE.equals(draining)) out.add(HOLD_MODULE_DRAIN);
            if (collectionWindowOpen) out.add(HOLD_COLLECTION_WINDOW);
            if (deviceId != null) {
                Boolean busy = dsl.fetchOne(
                        "select exists (select 1 from jobs where target_device_id = {0} and state in ('CLAIMED', 'EXECUTING') "
                                + "and (lease_expires_at is null or lease_expires_at >= now()))", deviceId)
                        .get(0, Boolean.class);
                if (Boolean.TRUE.equals(busy)) out.add(HOLD_DEVICE_JOB_RUNNING);
            }
            return canonicalHoldReasons(out);
        });
    }

    /** Maps each parent request id in {@code requestIds} to its newest follow-up request id (manual Request again or auto-retry). */
    public Map<UUID, UUID> followUps(Collection<UUID> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) return Map.of();
        String csv = requestIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
        return tx.inTransaction(dsl -> {
            Map<UUID, UUID> out = new LinkedHashMap<>();
            for (var r : dsl.fetch("select distinct on (previous_request_id) previous_request_id::text as parent_id, "
                    + "id::text as child_id from support_bundle_request "
                    + "where previous_request_id = any(string_to_array({0}, ',')::uuid[]) "
                    + "order by previous_request_id, requested_at desc, id", csv)) {
                out.put(UUID.fromString(r.get("parent_id", String.class)), UUID.fromString(r.get("child_id", String.class)));
            }
            return out;
        });
    }

    /** True when at least one follow-up request already points to {@code requestId}. */
    public boolean hasFollowUp(UUID requestId) {
        return !tx.inTransaction(dsl -> dsl.fetch(
                "select 1 from support_bundle_request where previous_request_id = {0}::uuid limit 1",
                requestId.toString())).isEmpty();
    }

    /** Expired-busy requests that never started, are not themselves auto-retries, and have not yet been evaluated for auto-retry. */
    public List<RequestRow> autoRetryCandidates(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return tx.inTransaction(dsl -> dsl.fetch(REQUEST_COLUMNS
                + "where state = 'FAILED' and outcome = 'NOT_STARTED_DEVICE_BUSY' and started_at is null "
                + "and auto_retry = false and retry_outcome is null order by finished_at asc nulls last, requested_at asc limit {0}",
                bounded).map(JooqSupportBundleStore::toRequest));
    }

    /** Atomically claims one-shot automatic retry evaluation for {@code requestId}; false if another pod already claimed it. */
    public boolean claimAutoRetryEvaluation(UUID requestId) {
        return audited.inTransaction(SYSTEM_RETRY_ACTOR, ACTION_REQUEST, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set retry_outcome = 'EVALUATING' where id = {0}::uuid "
                        + "and state = 'FAILED' and outcome = 'NOT_STARTED_DEVICE_BUSY' and started_at is null "
                        + "and auto_retry = false and retry_outcome is null", requestId.toString()) == 1);
    }

    /** Records the closed outcome of automatic retry evaluation on {@code requestId}. */
    public void finishAutoRetryEvaluation(UUID requestId, String actor, String retryOutcome) {
        audited.inTransaction(actor, ACTION_REQUEST, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set retry_outcome = {1} where id = {0}::uuid",
                requestId.toString(), retryOutcome));
    }

    // ------------------------------------------------------------------ worker side

    /** QUEUED to RUNNING; false when the request is not queued (cancelled meanwhile). */
    @Override
    public boolean markRunning(UUID requestId) {
        return audited.inTransaction(WORKER_ACTOR, ACTION_RUN, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set state = 'RUNNING', started_at = now() where id = {0}::uuid "
                        + "and state = 'QUEUED'", requestId.toString()) == 1);
    }

    @Override
    public void markItemRunning(UUID itemId) {
        tx.inTransaction(dsl -> dsl.execute("update support_bundle_item set state = 'RUNNING', started_at = now() "
                + "where id = {0}::uuid and state = 'QUEUED'", itemId.toString()));
    }

    /** Records an item's end state without an artefact (a refusal, an absent tool, a failure). */
    @Override
    public void finishItem(UUID itemId, String state, String outcomeCode, String outcomeDetail, List<String> gateIds) {
        audited.inTransaction(WORKER_ACTOR, ACTION_RUN, itemId.toString(), dsl -> dsl.execute(
                "update support_bundle_item set state = {1}, outcome_code = {2}, outcome_detail = {3}, "
                        + "gate_ids_used = string_to_array({4}, ',')::text[], finished_at = now() where id = {0}::uuid",
                itemId.toString(), state, outcomeCode, outcomeDetail, String.join(",", gateIds)));
    }

    /** Stores the item's artefact manifest and its end state in one transaction. */
    @Override
    public void completeItem(UUID itemId, BackupArtefactManifestRecord manifest, String state, String outcomeCode,
            String archiveEntryName, String redactionRuleset, List<String> gateIds) {
        audited.inTransaction(WORKER_ACTOR, ACTION_RUN, itemId.toString(), dsl -> {
            JooqBackupArtefactManifestRepository.insert(dsl, manifest);
            dsl.execute("update support_bundle_item set state = {1}, outcome_code = {2}, archive_entry_name = {3}, "
                    + "artefact_id = {4}, size_bytes = {5}, sha256 = {6}, redaction_ruleset = {7}, "
                    + "gate_ids_used = string_to_array({8}, ',')::text[], finished_at = now() where id = {0}::uuid",
                    itemId.toString(), state, outcomeCode, archiveEntryName, manifest.artefactId(),
                    manifest.plaintextBytes(), manifest.plaintextSha256(), redactionRuleset, String.join(",", gateIds));
            return null;
        });
    }

    /** Ends the request; the 14-day expiry counts from now (PO 2026-11-15, Q2). */
    @Override
    public void finishRequest(UUID requestId, String state, String outcome, String precheckJson, Instant expiresAt) {
        audited.inTransaction(WORKER_ACTOR, ACTION_RUN, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set state = {1}, outcome = {2}, precheck_json = {3}::jsonb, "
                        + "finished_at = now(), expires_at = {4} where id = {0}::uuid and state in ('QUEUED', 'RUNNING')",
                requestId.toString(), state, outcome, precheckJson == null ? "{}" : precheckJson,
                Timestamp.from(expiresAt)));
    }

    /** The newest stored system-log HCP report of a device, no older than {@code notBefore}: the zero-contact source. */
    @Override
    public Optional<HcpCopySource> newestStoredHcpReport(String deviceId, Instant notBefore) {
        return tx.inTransaction(dsl -> dsl.fetchOptional("select artefact_ref, wrapped_data_key, size from system_log_file "
                + "where device_id = {0} and kind = 'cp_hcp_report' and status = 'STORED' and pruned_at is null "
                + "and collected_at >= {1} order by collected_at desc limit 1", deviceId, Timestamp.from(notBefore))
                .map(r -> new HcpCopySource(r.get("artefact_ref", String.class), r.get("wrapped_data_key", byte[].class),
                        r.get("size", Long.class))));
    }

    public record HcpCopySource(String artefactRef, byte[] wrappedDataKey, long size) {
    }

    /** A stored configuration artefact: where its encrypted bytes are and how they were compressed. */
    public record StoredConfiguration(String artefactRef, byte[] wrappedDataKey, boolean gzip) {
    }

    @Override
    public Optional<StoredConfiguration> latestFortiGateConfiguration(String deviceId) {
        return tx.inTransaction(dsl -> dsl.fetchOptional("select r.artefact_ref, a.wrapped_data_key, c.compression "
                + "from device_configuration_run r "
                + "join configuration_artefact c on c.artefact_ref = r.artefact_ref "
                + "join backup_artefact a on a.recovery_volume_path = r.artefact_ref and a.device_id = r.device_id "
                + "where r.device_id = {0} and r.read_kind = 'show_configuration' "
                + "and not exists (select 1 from artefact_retention_ledger l where l.artefact_id = a.artefact_id and l.event = 'removed') "
                + "order by r.collected_at desc limit 1", deviceId)
                .map(r -> new StoredConfiguration(r.get("artefact_ref", String.class), r.get("wrapped_data_key", byte[].class),
                        "gzip".equals(r.get("compression", String.class)))));
    }

    @Override
    public void markWorkDir(UUID requestId, String workDirPath) {
        audited.inTransaction(WORKER_ACTOR, ACTION_RUN, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set work_dir_path = {1}, work_dir_cleaned = false where id = {0}::uuid",
                requestId.toString(), workDirPath));
    }

    @Override
    public void markWorkDirCleaned(UUID requestId) {
        audited.inTransaction(WORKER_ACTOR, ACTION_RUN, requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set work_dir_cleaned = true where id = {0}::uuid", requestId.toString()));
    }

    /** What a cleanup job removes: the directory recorded for the request, nothing an operator typed. */
    public record CleanupWork(UUID requestId, String deviceId, String workDir) {
    }

    @Override
    public Optional<CleanupWork> findCleanupByJob(String jobId) {
        return tx.inTransaction(dsl -> dsl.fetchOptional("select id::text as id, device_id, work_dir_path from support_bundle_request "
                + "where cleanup_job_id = {0} and cleanup_state = 'REQUESTED' and work_dir_path is not null "
                + "and work_dir_cleaned = false", jobId)
                .map(r -> new CleanupWork(UUID.fromString(r.get("id", String.class)), r.get("device_id", String.class),
                        r.get("work_dir_path", String.class))));
    }

    @Override
    public void finishCleanup(UUID requestId, boolean removed, String outcome) {
        audited.inTransaction(WORKER_ACTOR, "support_bundle_cleanup_workdir", requestId.toString(), dsl -> dsl.execute(
                "update support_bundle_request set cleanup_state = {1}, cleanup_outcome = {2}, "
                        + "work_dir_cleaned = work_dir_cleaned or {3} where id = {0}::uuid and cleanup_state = 'REQUESTED'",
                requestId.toString(), removed ? "DONE" : "FAILED", outcome, removed));
    }

    @Override
    public boolean multiDomainServer(String deviceId) {
        return !tx.inTransaction(dsl -> dsl.fetch("select 1 from cp_domain_server_placement where source_id = {0} limit 1",
                deviceId)).isEmpty();
    }

    @Override
    public List<Integer> virtualSystemIds(String deviceId) {
        return tx.inTransaction(dsl -> dsl.fetch("select distinct i.context from device_interface i where i.run_id = "
                + "(select run_id from device_inventory_run where device_id = {0} order by collected_at desc limit 1) "
                + "and i.context ~ '^[0-9]{1,4}$'", deviceId)).stream().map(r -> Integer.parseInt(r.get(0, String.class)))
                .filter(v -> v >= 0 && v <= 4095).distinct().sorted().toList();
    }

    // ------------------------------------------------------------------ claim time admission (step S6)

    public static final java.time.Duration HA_PEER_DEFER = java.time.Duration.ofMinutes(10);
    public static final java.time.Duration FLEET_CAP_DEFER = java.time.Duration.ofMinutes(5);
    public static final java.time.Duration GIVE_UP_AFTER = java.time.Duration.ofHours(3);
    public static final int HEAVY_FLEET_CAP = 2;
    private static final String BUNDLE_CAPABILITIES = "'cp_support_bundle', 'pan_support_bundle', 'asa_support_bundle', 'fgt_support_bundle'";

    /** What the claim loop does with a support bundle job it has just claimed. */
    public sealed interface ClaimDecision {
        record Proceed() implements ClaimDecision {
        }

        /** Put the job back; claimable again from {@code until}. {@code reason} is one of HA_PEER_RUNNING, FLEET_CAP. */
        record Defer(Instant until, String reason) implements ClaimDecision {
        }

        /** Not started within three hours of its run time: the request fails as NOT_STARTED_DEVICE_BUSY. */
        record GiveUp() implements ClaimDecision {
        }
    }

    /**
     * Claim time admission of a support bundle job (design 5.8; PO 2026-11-15, step S6). The claim statement itself never hands a
     * job out while another job of the same device is CLAIMED or EXECUTING (device busy). This adds, for the job just claimed:
     * give up after three hours; defer by ten minutes while a support bundle job runs on another member of the same cluster
     * (membership: the stored {@code cluster_member_ref}; none means single); defer by five minutes while two recovery-write
     * (heavy) support bundle jobs already run fleet-wide. Two jobs claimed in the same instant are ordered by
     * (submitted_at, job_id): a job counts the others that are EXECUTING and the CLAIMED ones that rank before it, so exactly
     * one of two peers, and at most two heavy jobs, pass.
     */
    public ClaimDecision claimDecision(String jobId, Instant now, int fleetCap) {
        return tx.inTransaction(dsl -> {
            var req = dsl.fetchOne("select r.run_at, j.target_device_id, j.action_class from support_bundle_request r "
                    + "join jobs j on j.job_id = r.job_id where r.job_id = {0} and r.state = 'QUEUED'", jobId);
            if (req == null) return new ClaimDecision.Proceed();
            Instant runAt = req.get("run_at", Timestamp.class).toInstant();
            if (now.isAfter(runAt.plus(GIVE_UP_AFTER))) return new ClaimDecision.GiveUp();
            String device = req.get("target_device_id", String.class);
            String others = "from jobs o where o.job_id <> {0} and o.capability_id in (" + BUNDLE_CAPABILITIES + ") "
                    + "and (o.state = 'EXECUTING' or (o.state = 'CLAIMED' and (o.submitted_at, o.job_id) < "
                    + "(select m.submitted_at, m.job_id from jobs m where m.job_id = {0})))";
            Integer peers = device == null ? 0 : dsl.fetchOne("select count(*) " + others
                    + " and exists (select 1 from devices od join devices md on md.device_id = {1} "
                    + "where od.device_id = o.target_device_id and od.device_id <> md.device_id "
                    + "and md.cluster_member_ref is not null and od.cluster_member_ref = md.cluster_member_ref)",
                    jobId, device).get(0, Integer.class);
            if (peers != null && peers > 0) return new ClaimDecision.Defer(now.plus(HA_PEER_DEFER), HOLD_HA_PEER_RUNNING);
            if ("recovery-write".equals(req.get("action_class", String.class))) {
                Integer heavy = dsl.fetchOne("select count(*) " + others + " and o.action_class = 'recovery-write'", jobId)
                        .get(0, Integer.class);
                if (heavy != null && heavy >= fleetCap) return new ClaimDecision.Defer(now.plus(FLEET_CAP_DEFER), HOLD_FLEET_CAP);
            }
            return new ClaimDecision.Proceed();
        });
    }

    /** Puts a claimed job back to REQUESTED, claimable from {@code until}; false when it is no longer this epoch's CLAIMED job. */
    public boolean deferClaimed(String jobId, long epoch, Instant until) {
        return deferClaimed(jobId, epoch, until, null);
    }

    /** As {@link #deferClaimed(String, long, Instant)}, recording the closed deferral reason on the request once. */
    public boolean deferClaimed(String jobId, long epoch, Instant until, String reason) {
        return audited.inTransaction(WORKER_ACTOR, ACTION_RUN, jobId, dsl -> {
            int n = dsl.execute(
                    "update jobs set state = 'REQUESTED', admission_not_before = {2}, admission_deferrals = admission_deferrals + 1, "
                            + "lease_worker_id = null, lease_expires_at = null where job_id = {0} and lease_epoch = {1} and state = 'CLAIMED'",
                    jobId, epoch, Timestamp.from(until));
            if (n == 1 && reason != null && CLOSED_HOLD_REASONS.contains(reason)) {
                dsl.execute("update support_bundle_request set hold_reasons = array_append(hold_reasons, {1}) "
                        + "where job_id = {0} and state = 'QUEUED' and not ({1} = any(hold_reasons))", jobId, reason);
            }
            return n == 1;
        });
    }

    /** Ends a claimed job that was not started within three hours: the job is cancelled, the request FAILED (NOT_STARTED_DEVICE_BUSY). */
    public boolean giveUpClaimed(String jobId, long epoch) {
        return giveUpClaimed(jobId, epoch, Instant.now());
    }

    public boolean giveUpClaimed(String jobId, long epoch, Instant now) {
        return audited.inTransaction(WORKER_ACTOR, ACTION_RUN, jobId, dsl -> {
            int n = dsl.execute("update jobs set state = 'CANCELLED', outcome = 'CANCELLED', terminal_reason = 'NOT_STARTED_DEVICE_BUSY', "
                    + "finished_at = now() where job_id = {0} and lease_epoch = {1} and state = 'CLAIMED'", jobId, epoch);
            if (n == 1) closeNotStarted(dsl, jobId, now);
            return n == 1;
        });
    }

    /**
     * Gives up the requests whose job was never claimed (a busy device never freed up) three hours after their run time.
     * Returns how many requests it closed.
     */
    public int giveUpStale(Instant now) {
        return audited.inTransaction(WORKER_ACTOR, ACTION_RUN, "support_bundle_give_up", dsl -> {
            var stale = dsl.fetch("select j.job_id from jobs j join support_bundle_request r on r.job_id = j.job_id "
                    + "where j.state = 'REQUESTED' and r.state = 'QUEUED' and r.run_at < {0}", Timestamp.from(now.minus(GIVE_UP_AFTER)));
            int closed = 0;
            for (var row : stale) {
                String jobId = row.get(0, String.class);
                int n = dsl.execute("update jobs set state = 'CANCELLED', outcome = 'CANCELLED', terminal_reason = 'NOT_STARTED_DEVICE_BUSY', "
                        + "finished_at = now() where job_id = {0} and state = 'REQUESTED'", jobId);
                if (n == 1) { closeNotStarted(dsl, jobId, now); closed++; }
            }
            return closed;
        });
    }

    private static void closeNotStarted(org.jooq.DSLContext dsl, String jobId, Instant now) {
        var req = dsl.fetchOne("select id::text as id, device_id, run_at, array_to_string(hold_reasons, ',') as hold_reasons "
                + "from support_bundle_request where job_id = {0} and state = 'QUEUED'", jobId);
        if (req == null) return;
        String requestId = req.get("id", String.class);
        String deviceId = req.get("device_id", String.class);
        Instant runAt = req.get("run_at", Timestamp.class).toInstant();
        List<String> reasons = new ArrayList<>(parseHoldReasons(req.get("hold_reasons", String.class)));
        Boolean drainObserved = dsl.fetchOne(
                "select exists (select 1 from module_runtime_control m join module_runtime_control r on r.module = m.effective_owner "
                        + "where m.module in ('diagnostics', 'general') and (m.drain_requested or r.drain_requested "
                        + "or m.drain_requested_at >= {0} or r.drain_requested_at >= {0} "
                        + "or m.drain_ack_at >= {0} or r.drain_ack_at >= {0}))",
                Timestamp.from(runAt)).get(0, Boolean.class);
        if (Boolean.TRUE.equals(drainObserved)) reasons.add(HOLD_MODULE_DRAIN);
        if (collectionWindowOverlapped(runAt, now, JobWindowPolicy.SYSTEM)) reasons.add(HOLD_COLLECTION_WINDOW);
        if (deviceId != null) {
            Boolean deviceBusy = dsl.fetchOne(
                    "select exists (select 1 from jobs o where o.target_device_id = {0} and o.job_id <> {1} "
                            + "and (o.state in ('CLAIMED', 'EXECUTING') or (o.finished_at >= {2} and o.submitted_at <= {3})))",
                    deviceId, jobId, Timestamp.from(runAt), Timestamp.from(now)).get(0, Boolean.class);
            if (Boolean.TRUE.equals(deviceBusy)) reasons.add(HOLD_DEVICE_JOB_RUNNING);
        }
        String mergedCsv = String.join(",", canonicalHoldReasons(reasons));
        dsl.execute("update support_bundle_item set state = 'CANCELLED', outcome_code = 'NOT_STARTED_DEVICE_BUSY', finished_at = now() "
                + "where state = 'QUEUED' and request_id = {0}::uuid", requestId);
        dsl.execute("update support_bundle_request set state = 'FAILED', outcome = 'NOT_STARTED_DEVICE_BUSY', "
                + "hold_reasons = case when {1} = '' then '{}'::text[] else string_to_array({1}, ',')::text[] end, "
                + "finished_at = now() where id = {0}::uuid and state = 'QUEUED'", requestId, mergedCsv);
    }

    /** True when {@code [from, to]} overlapped at least one scheduled 00/06/12/18 collection window. */
    public static boolean collectionWindowOverlapped(Instant from, Instant to, JobWindowPolicy windows) {
        if (from == null || to == null) return false;
        Instant start = from.isBefore(to) ? from : to;
        Instant end = from.isBefore(to) ? to : from;
        if (start.equals(end)) return windows.isOpen(start);
        ZonedDateTime slot = windows.slotStart(start);
        while (slot.toInstant().isBefore(end)) {
            Instant slotStart = slot.toInstant();
            Instant slotEnd = slot.plusMinutes(windows.windowMinutes()).toInstant();
            if (start.isBefore(slotEnd) && end.isAfter(slotStart)) return true;
            slot = slot.plusHours(6);
        }
        return false;
    }

    public static List<String> canonicalHoldReasons(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Set<String> present = new LinkedHashSet<>(raw);
        return CLOSED_HOLD_REASONS.stream().filter(present::contains).toList();
    }

    private static List<String> parseHoldReasons(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return canonicalHoldReasons(List.of(csv.split(",")));
    }

    // ------------------------------------------------------------------ mapping

    private static RequestRow toRequest(Record r) {
        String prev = r.get("previous_request_id", String.class);
        return new RequestRow(UUID.fromString(r.get("id", String.class)), r.get("device_id", String.class),
                r.get("vendor", String.class), r.get("platform_role", String.class), r.get("run_mode", String.class),
                instant(r.get("run_at", Timestamp.class)), r.get("case_ref", String.class), r.get("reason", String.class),
                r.get("requested_by", String.class), instant(r.get("requested_at", Timestamp.class)),
                instant(r.get("started_at", Timestamp.class)), instant(r.get("finished_at", Timestamp.class)),
                instant(r.get("expires_at", Timestamp.class)), instant(r.get("expiry_warned_at", Timestamp.class)),
                instant(r.get("downloaded_at", Timestamp.class)), r.get("download_count", Integer.class),
                r.get("deleted_by", String.class), instant(r.get("deleted_at", Timestamp.class)), r.get("job_id", String.class),
                r.get("precheck_json", String.class), r.get("state", String.class), r.get("outcome", String.class),
                prev == null || prev.isBlank() ? null : UUID.fromString(prev),
                Boolean.TRUE.equals(r.get("auto_retry", Boolean.class)),
                parseHoldReasons(r.get("hold_reasons", String.class)),
                r.get("retry_outcome", String.class));
    }

    private static ItemRow toItem(Record r) {
        String gates = r.get("gate_ids", String.class);
        List<String> gateList = new ArrayList<>();
        if (gates != null && !gates.isBlank()) gateList.addAll(List.of(gates.split(",")));
        return new ItemRow(UUID.fromString(r.get("id", String.class)), UUID.fromString(r.get("request_id", String.class)),
                r.get("ordinal", Integer.class), r.get("kind", String.class), r.get("vs_id", Integer.class),
                r.get("domain_ref", String.class), r.get("state", String.class), r.get("outcome_code", String.class),
                r.get("outcome_detail", String.class), r.get("archive_entry_name", String.class),
                r.get("artefact_id", String.class), r.get("size_bytes", Long.class), r.get("sha256", String.class),
                r.get("redaction_ruleset", String.class), List.copyOf(gateList),
                instant(r.get("started_at", Timestamp.class)), instant(r.get("finished_at", Timestamp.class)));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
