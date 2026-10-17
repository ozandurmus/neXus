package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactManifestRepository;

/**
 * Both halves of the system-log phase B storage: the Palo Alto source write ({@link #record}) and the Check Point
 * byte-offset, listing and whole-file writes of {@link CheckPointSystemLogRepository}. Every write is one audited
 * transaction fenced to the job's current lease ({@link #FENCE}).
 */
public final class JooqSystemLogCollectionRepository implements CheckPointSystemLogRepository {
    static final String ACTOR = "system:worker";
    static final String ACTION = "system_log_collect_record";
    /** The lease fence of every write: the job is EXECUTING under this epoch, owned by a live worker, on this device. */
    static final String FENCE = "exists (select 1 from jobs j where j.job_id = {0} and j.lease_epoch = {1} "
            + "and j.state = 'EXECUTING' and j.target_device_id = {2} and ui2_job_owner_valid(j.job_id, j.lease_epoch))";
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
                "select raw_retention_days, include_management_servers, device_cap_bytes, hcp_retention_days, "
                        + "spike_data_retention_days, spike_data_cap_bytes, ledger_retention_days, spike_data_enabled "
                        + "from system_log_settings where settings_id = 'default'")
                .map(r -> new Settings(r.get("raw_retention_days", Integer.class),
                        Boolean.TRUE.equals(r.get("include_management_servers", Boolean.class)),
                        r.get("device_cap_bytes", Long.class), r.get("hcp_retention_days", Integer.class),
                        r.get("spike_data_retention_days", Integer.class), r.get("spike_data_cap_bytes", Long.class),
                        r.get("ledger_retention_days", Integer.class),
                        Boolean.TRUE.equals(r.get("spike_data_enabled", Boolean.class))))
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
                                + "redaction_count, flags, artefact_ref, wrapped_data_key, plaintext_hmac, ciphertext_bytes) "
                                + "values ({0}, {1}, {2}, {3}, cast({4} as date), {5}, null, {6}, {7}, {8}, {9}, "
                                + "cast({10} as timestamp), cast({11} as timestamp), {12}, cast({13} as text[]), {14}, {15}, "
                                + "{16}, {17})",
                        chunk.chunkId(), deviceId, sourceKey, jobId, chunk.logDay().toString(), chunk.fileNameHmac(),
                        chunk.startOffset(), chunk.endOffset(), chunk.bytes(), chunk.lines(), chunk.firstLineTime(),
                        chunk.lastLineTime(), chunk.redactionCount(), textArray(chunk.flags()), manifest.artefactId(),
                        manifest.wrappedDataKey(), chunk.plaintextHmac(), manifest.ciphertextBytes());
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

    // ------------------------------------------------------------------ Check Point (CheckPointSystemLogRepository)

    @Override
    public Optional<CpCursor> findCpCursor(String deviceId, String sourceKey) {
        return transactions.inTransaction(dsl -> {
            var row = dsl.fetchOptional("select inode, offset_bytes, anchor_hmac, device_epoch_at_read from system_log_cursor "
                    + "where device_id = {0} and source_key = {1} and inode is not null", deviceId, sourceKey);
            if (row.isEmpty()) return Optional.<CpCursor>empty();
            List<SnapshotEntry> snapshot = dsl.fetch("select e->>'name' as name, e->>'inode' as inode, "
                            + "(e->>'size')::bigint as size, (e->>'mtime')::bigint as mtime "
                            + "from system_log_cursor c, jsonb_array_elements(c.family_snapshot) with ordinality as x(e, n) "
                            + "where c.device_id = {0} and c.source_key = {1} order by x.n", deviceId, sourceKey)
                    .stream().map(r -> new SnapshotEntry(r.get("name", String.class), r.get("inode", String.class),
                            r.get("size", Long.class), r.get("mtime", Long.class))).toList();
            var r = row.get();
            return Optional.of(new CpCursor(r.get("inode", String.class), r.get("offset_bytes", Long.class),
                    r.get("anchor_hmac", String.class), snapshot, r.get("device_epoch_at_read", Long.class)));
        });
    }

    @Override
    public ChunkWrite recordChunk(String jobId, long leaseEpoch, String deviceId, String sourceKey, CpChunk cpChunk,
            CpCursor cursor, List<Gap> gaps) {
        return audited.inTransaction(ACTOR, ACTION, dsl -> {
            if (!fenced(dsl, jobId, leaseEpoch, deviceId)) return ChunkWrite.NOT_RECORDED;
            var chunk = cpChunk.chunk();
            var manifest = chunk.manifest();
            // The chunk row goes first: a true duplicate (same file, offset and content HMAC: V161 key) inserts nothing and
            // leaves no orphan manifest; the cursor still advances because those bytes are already held.
            int inserted = dsl.execute("insert into system_log_chunk(chunk_id, device_id, source_key, job_id, log_day, "
                            + "file_name_hmac, inode, start_offset, end_offset, bytes, lines, first_line_time, last_line_time, "
                            + "redaction_count, redaction_rule_set, flags, artefact_ref, wrapped_data_key, plaintext_hmac, "
                            + "ciphertext_bytes) values ({0}, {1}, {2}, {3}, cast({4} as date), {5}, {6}, {7}, {8}, {9}, {10}, "
                            + "cast({11} as timestamp), cast({12} as timestamp), {13}, {14}, cast({15} as text[]), {16}, {17}, "
                            + "{18}, {19}) on conflict do nothing",
                    chunk.chunkId(), deviceId, sourceKey, jobId, chunk.logDay().toString(), chunk.fileNameHmac(),
                    cpChunk.inode(), chunk.startOffset(), chunk.endOffset(), chunk.bytes(), chunk.lines(),
                    chunk.firstLineTime(), chunk.lastLineTime(), chunk.redactionCount(), cpChunk.redactionRuleSet(),
                    textArray(chunk.flags()), manifest.artefactId(), manifest.wrappedDataKey(), chunk.plaintextHmac(),
                    manifest.ciphertextBytes());
            ChunkWrite outcome = ChunkWrite.STORED;
            if (inserted != 1) {
                boolean same = dsl.fetchExists(dsl.selectOne().from("system_log_chunk").where(
                        "device_id = {0} and source_key = {1} and inode = {2} and start_offset = {3} and plaintext_hmac = {4}",
                        deviceId, sourceKey, cpChunk.inode(), chunk.startOffset(), chunk.plaintextHmac()));
                if (!same) return ChunkWrite.IDENTITY_CONFLICT;
                outcome = ChunkWrite.DUPLICATE;
            } else {
                JooqBackupArtefactManifestRepository.insert(dsl, manifest);
            }
            upsertCpCursor(dsl, deviceId, sourceKey, cursor, jobId);
            insertGaps(dsl, deviceId, sourceKey, jobId, gaps);
            return outcome;
        });
    }

    @Override
    public boolean recordCpSource(String jobId, long leaseEpoch, String deviceId, String sourceKey, Run run,
            Optional<CpCursor> cursor, List<Gap> gaps) {
        return audited.inTransaction(ACTOR, ACTION, dsl -> {
            if (!insertRun(dsl, jobId, leaseEpoch, deviceId, sourceKey, run)) return false;
            cursor.ifPresent(c -> upsertCpCursor(dsl, deviceId, sourceKey, c, jobId));
            insertGaps(dsl, deviceId, sourceKey, jobId, gaps);
            return true;
        });
    }

    @Override
    public Optional<String> lastOutcome(String deviceId, String sourceKey) {
        return transactions.inTransaction(dsl -> dsl.fetchOptional("select outcome from system_log_run where device_id = {0} "
                + "and source_key = {1} order by created_at desc, run_id desc limit 1", deviceId, sourceKey)
                .map(r -> r.get("outcome", String.class)));
    }

    @Override
    public boolean crashBaselineTaken(String deviceId) {
        return transactions.inTransaction(dsl -> dsl.fetchExists(dsl.selectOne().from("system_log_run").where(
                "device_id = {0} and source_key = 'cp_crash_listing' and outcome <> 'UNKNOWN'", deviceId)));
    }

    @Override
    public List<CrashEntry> liveCrashEntries(String deviceId) {
        return transactions.inTransaction(dsl -> dsl.fetch("select entry_id, directory, name_hmac, process, size, mtime "
                + "from device_crash_entry where device_id = {0} and removed_at is null order by entry_id", deviceId)
                .stream().map(r -> new CrashEntry(r.get("entry_id", String.class), r.get("directory", String.class),
                        r.get("name_hmac", String.class), r.get("process", String.class), r.get("size", Long.class),
                        r.get("mtime", Long.class))).toList());
    }

    @Override
    public boolean recordCrashListing(String jobId, long leaseEpoch, String deviceId, Run run, CrashListingWrite write) {
        return audited.inTransaction(ACTOR, ACTION, dsl -> {
            if (!insertRun(dsl, jobId, leaseEpoch, deviceId, "cp_crash_listing", run)) return false;
            for (NewCrashEntry e : write.inserted()) {
                // A file that comes back with the same name and mtime re-opens its row instead of a second one.
                dsl.execute("insert into device_crash_entry(entry_id, device_id, directory, name_hmac, process, size, mtime, "
                                + "baseline, first_seen_job, last_seen_job) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {8}) "
                                + "on conflict (device_id, directory, name_hmac, mtime) do update set removed_at = null, "
                                + "size = excluded.size, last_seen_job = excluded.last_seen_job",
                        e.entryId(), deviceId, e.directory(), e.nameHmac(), e.process(), e.size(), e.mtime(), e.baseline(), jobId);
            }
            for (CrashEntry e : write.seen()) {
                dsl.execute("update device_crash_entry set last_seen_job = {0}, size = {1} where entry_id = {2} and device_id = {3}",
                        jobId, e.size(), e.entryId(), deviceId);
            }
            for (String removed : write.removedEntryIds()) {
                dsl.execute("update device_crash_entry set removed_at = now(), last_seen_job = {0} where entry_id = {1} "
                        + "and device_id = {2} and removed_at is null", jobId, removed, deviceId);
            }
            for (CrashAlert a : write.alerts()) {
                dsl.execute("insert into device_crash_alert(alert_id, device_id, process, file_count, newest_mtime, job_id) "
                        + "values ({0}, {1}, {2}, {3}, {4}, {5})", a.alertId(), deviceId, a.process(), a.fileCount(),
                        a.newestMtime(), jobId);
            }
            return true;
        });
    }

    @Override
    public boolean fileStored(String deviceId, String kind, String remoteNameHmac, long mtime) {
        return transactions.inTransaction(dsl -> dsl.fetchExists(dsl.selectOne().from("system_log_file").where(
                "device_id = {0} and kind = {1} and remote_name_hmac = {2} and mtime = {3}", deviceId, kind, remoteNameHmac,
                mtime)));
    }

    @Override
    public boolean recordFile(String jobId, long leaseEpoch, String deviceId, String sourceKey, Run run,
            Optional<FileRow> file, List<Gap> gaps) {
        return audited.inTransaction(ACTOR, ACTION, dsl -> {
            if (!insertRun(dsl, jobId, leaseEpoch, deviceId, sourceKey, run)) return false;
            if (file.isPresent()) {
                FileRow f = file.get();
                JooqBackupArtefactManifestRepository.insert(dsl, f.manifest());
                dsl.execute("insert into system_log_file(file_id, device_id, kind, job_id, remote_name_hmac, name_time, size, "
                                + "mtime, status, flags, artefact_ref, wrapped_data_key, content_hmac, ciphertext_bytes, expires_at) "
                                + "values ({0}, {1}, {2}, {3}, {4}, cast({5} as timestamp), {6}, {7}, {8}, cast({9} as text[]), "
                                + "{10}, {11}, {12}, {13}, {14})",
                        f.fileId(), deviceId, f.kind(), jobId, f.remoteNameHmac(), f.nameTime(), f.size(), f.mtime(),
                        f.status(), textArray(f.flags()), f.manifest().artefactId(), f.manifest().wrappedDataKey(), f.contentHmac(),
                        f.manifest().ciphertextBytes(), java.sql.Timestamp.from(f.expiresAt()));
            }
            insertGaps(dsl, deviceId, sourceKey, jobId, gaps);
            return true;
        });
    }

    private static boolean fenced(org.jooq.DSLContext dsl, String jobId, long leaseEpoch, String deviceId) {
        return dsl.fetchOne("select " + FENCE + " as ok", jobId, leaseEpoch, deviceId).get("ok", Boolean.class);
    }

    /** The run row carries the fence and the one-row-per-(job, source) rule; false writes nothing else. */
    private static boolean insertRun(org.jooq.DSLContext dsl, String jobId, long leaseEpoch, String deviceId, String sourceKey,
            Run run) {
        return dsl.execute("insert into system_log_run(run_id, device_id, job_id, source_key, outcome, bytes_read, bytes_stored, "
                        + "reads_issued, duration_ms, detail) select {3}, {2}, {0}, {4}, {5}, {6}, {7}, {8}, {9}, cast({10} as jsonb) "
                        + "where " + FENCE + " on conflict (job_id, source_key) do nothing",
                jobId, leaseEpoch, deviceId, UUID.randomUUID().toString(), sourceKey, run.outcome(), run.bytesRead(),
                run.bytesStored(), run.readsIssued(), run.durationMs(), run.detailJson()) == 1;
    }

    private static void upsertCpCursor(org.jooq.DSLContext dsl, String deviceId, String sourceKey, CpCursor cursor,
            String jobId) {
        dsl.execute("insert into system_log_cursor(device_id, source_key, inode, offset_bytes, anchor_hmac, family_snapshot, "
                        + "device_epoch_at_read, updated_job_id) values ({0}, {1}, {2}, {3}, {4}, cast({5} as jsonb), {6}, {7}) "
                        + "on conflict (device_id, source_key) do update set inode = excluded.inode, "
                        + "offset_bytes = excluded.offset_bytes, anchor_hmac = excluded.anchor_hmac, "
                        + "family_snapshot = excluded.family_snapshot, device_epoch_at_read = excluded.device_epoch_at_read, "
                        + "updated_job_id = excluded.updated_job_id, updated_at = now()",
                deviceId, sourceKey, cursor.inode(), cursor.offsetBytes(), cursor.anchorHmac(),
                snapshotJson(cursor.familySnapshot()), cursor.deviceEpochAtRead(), jobId);
    }

    private static void insertGaps(org.jooq.DSLContext dsl, String deviceId, String sourceKey, String jobId, List<Gap> gaps) {
        for (Gap gap : gaps) {
            dsl.execute("insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, known_lost_bytes, "
                            + "pending_bytes, extent_unknown, window_start_epoch, window_end_epoch, detail) "
                            + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, cast({10} as jsonb))",
                    UUID.randomUUID().toString(), deviceId, sourceKey, jobId, gap.kind(), gap.knownLostBytes(),
                    gap.pendingBytes(), gap.extentUnknown(), gap.windowStartEpoch(), gap.windowEndEpoch(), gap.detailJson());
        }
    }

    /** The family snapshot as a JSON array of {name, inode, size, mtime}; names are JSON-escaped. */
    static String snapshotJson(List<SnapshotEntry> entries) {
        StringBuilder out = new StringBuilder("[");
        for (SnapshotEntry e : entries) {
            if (out.length() > 1) out.append(',');
            out.append("{\"name\":").append(jsonString(e.name())).append(",\"inode\":\"").append(e.inode())
                    .append("\",\"size\":").append(e.size()).append(",\"mtime\":").append(e.mtime()).append('}');
        }
        return out.append(']').toString();
    }

    static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
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
