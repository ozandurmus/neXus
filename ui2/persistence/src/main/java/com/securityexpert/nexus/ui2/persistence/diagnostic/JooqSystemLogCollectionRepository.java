package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactManifestRepository;

public final class JooqSystemLogCollectionRepository implements SystemLogCollectionRepository {
    static final String ACTOR = "system:worker";
    static final String ACTION = "system_log_collect_record";
    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqSystemLogCollectionRepository(TransactionBoundary transactions) {
        this.transactions = transactions;
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    @Override
    public Optional<PanCursor> findPanCursor(String deviceId, String sourceKey) {
        return transactions.inTransaction(dsl -> {
            var row = dsl.fetchOptional("select pan_last_receive_time from system_log_cursor where device_id = {0} "
                    + "and source_key = {1} and pan_last_receive_time is not null", deviceId, sourceKey);
            if (row.isEmpty()) return Optional.<PanCursor>empty();
            List<String> seqnos = dsl.fetch("select jsonb_array_elements_text(coalesce(pan_boundary_seqnos, '[]'::jsonb)) as seqno "
                    + "from system_log_cursor where device_id = {0} and source_key = {1}", deviceId, sourceKey)
                    .stream().map(r -> r.get("seqno", String.class)).toList();
            return Optional.of(new PanCursor(row.get().get("pan_last_receive_time", String.class), seqnos));
        });
    }

    @Override
    public Settings settings() {
        return transactions.inTransaction(dsl -> dsl.fetchOptional(
                "select raw_retention_days, include_management_servers from system_log_settings where settings_id = 'default'")
                .map(r -> new Settings(r.get("raw_retention_days", Integer.class),
                        Boolean.TRUE.equals(r.get("include_management_servers", Boolean.class))))
                .orElse(Settings.DEFAULTS));
    }

    @Override
    public boolean record(String jobId, long leaseEpoch, String deviceId, String sourceKey, SourceWrite write) {
        return audited.inTransaction(ACTOR, ACTION, dsl -> {
            var run = write.run();
            // The run row goes first and carries the lease fence: a stale worker or a replay inserts nothing, and then
            // nothing else of this write is applied.
            int fenced = dsl.execute(
                    "insert into system_log_run(run_id, device_id, job_id, source_key, outcome, bytes_read, bytes_stored, "
                            + "reads_issued, duration_ms, detail) "
                            + "select {0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, cast({9} as jsonb) where exists "
                            + "(select 1 from jobs j where j.job_id = {2} and j.lease_epoch = {10} and j.state = 'EXECUTING' "
                            + "and j.target_device_id = {1} and ui2_job_owner_valid(j.job_id, j.lease_epoch)) "
                            + "on conflict (job_id, source_key) do nothing",
                    UUID.randomUUID().toString(), deviceId, jobId, sourceKey, run.outcome(), run.bytesRead(),
                    run.bytesStored(), run.readsIssued(), run.durationMs(), run.detailJson(), leaseEpoch);
            if (fenced != 1) return false;
            write.chunk().ifPresent(chunk -> {
                var manifest = chunk.manifest();
                JooqBackupArtefactManifestRepository.insert(dsl, manifest);
                dsl.execute("insert into system_log_chunk(chunk_id, device_id, source_key, job_id, log_day, file_name_hmac, "
                                + "inode, start_offset, end_offset, bytes, lines, first_line_time, last_line_time, "
                                + "redaction_count, flags, artefact_ref, wrapped_data_key, plaintext_sha256, ciphertext_bytes) "
                                + "values ({0}, {1}, {2}, {3}, cast({4} as date), {5}, null, {6}, {7}, {8}, {9}, "
                                + "cast({10} as timestamp), cast({11} as timestamp), {12}, cast({13} as text[]), {14}, {15}, "
                                + "{16}, {17})",
                        chunk.chunkId(), deviceId, sourceKey, jobId, chunk.logDay().toString(), chunk.fileNameHmac(),
                        chunk.startOffset(), chunk.endOffset(), chunk.bytes(), chunk.lines(), chunk.firstLineTime(),
                        chunk.lastLineTime(), chunk.redactionCount(), textArray(chunk.flags()), manifest.artefactId(),
                        manifest.wrappedDataKey(), manifest.plaintextSha256(), manifest.ciphertextBytes());
            });
            write.cursor().ifPresent(cursor -> dsl.execute(
                    "insert into system_log_cursor(device_id, source_key, pan_last_receive_time, pan_boundary_seqnos, "
                            + "updated_job_id) values ({0}, {1}, {2}, cast({3} as jsonb), {4}) "
                            + "on conflict (device_id, source_key) do update set "
                            + "pan_last_receive_time = excluded.pan_last_receive_time, "
                            + "pan_boundary_seqnos = excluded.pan_boundary_seqnos, updated_job_id = excluded.updated_job_id, "
                            + "updated_at = now()",
                    deviceId, sourceKey, cursor.lastReceiveTime(), jsonArray(cursor.boundarySeqnos()), jobId));
            for (Gap gap : write.gaps()) {
                dsl.execute("insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, known_lost_bytes, "
                                + "pending_bytes, extent_unknown, window_start_epoch, window_end_epoch, detail) "
                                + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, cast({10} as jsonb))",
                        UUID.randomUUID().toString(), deviceId, sourceKey, jobId, gap.kind(), gap.knownLostBytes(),
                        gap.pendingBytes(), gap.extentUnknown(), gap.windowStartEpoch(), gap.windowEndEpoch(),
                        gap.detailJson());
            }
            return true;
        });
    }

    /** Seqnos are digits only (checked by {@link PanCursor}); a JSON array of strings needs no escaping. */
    static String jsonArray(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    /** Flags are the fixed uppercase vocabulary of the chunk table's check; anything else fails the insert. */
    static String textArray(List<String> values) {
        for (String value : values)
            if (!value.matches("[A-Z_]{1,32}")) throw new IllegalArgumentException("chunk flag shape");
        return values.stream().collect(Collectors.joining(",", "{", "}"));
    }
}
