package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * {@link SystemLogReadRepository} over V160/V161. Plain, parameterised SQL like the other system-log repositories; every
 * query is read-only and selects index columns and counters only (never {@code file_name_hmac}, {@code remote_name_hmac},
 * {@code name_hmac}, {@code inode}, {@code anchor_hmac}, {@code family_snapshot}, {@code detail} or a content digest).
 */
public final class JooqSystemLogReadRepository implements SystemLogReadRepository {
    private static final String TIME = "'YYYY-MM-DD HH24:MI:SS'";
    private final TransactionBoundary transactions;

    public JooqSystemLogReadRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public List<SourceRow> sources(String deviceId) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "with src as (select source_key from system_log_cursor where device_id = {0} "
                        + "union select source_key from system_log_run where device_id = {0} "
                        + "union select source_key from system_log_chunk where device_id = {0}), "
                        + "last_run as (select distinct on (source_key) source_key, job_id, outcome, bytes_read, bytes_stored, "
                        + "created_at from system_log_run where device_id = {0} order by source_key, created_at desc, run_id), "
                        + "chunk_stats as (select source_key, count(*) as chunks, coalesce(sum(bytes), 0) as bytes, "
                        + "coalesce(sum(lines), 0) as lines, max(collected_at) as newest from system_log_chunk "
                        + "where device_id = {0} and pruned_at is null group by source_key) "
                        + "select s.source_key, (c.device_id is not null) as has_cursor, c.offset_bytes, "
                        + "c.device_epoch_at_read, c.pan_last_receive_time, coalesce(c.fast_rotating, false) as fast_rotating, "
                        + "c.retention_horizon_seconds, c.updated_at as cursor_updated_at, r.outcome as last_outcome, "
                        + "r.created_at as last_run_at, r.job_id as last_job_id, r.bytes_read as last_bytes_read, "
                        + "r.bytes_stored as last_bytes_stored, "
                        + "(select coalesce(sum(g.pending_bytes), 0) from system_log_gap g where g.device_id = {0} "
                        + "and g.source_key = s.source_key and g.job_id = r.job_id and g.kind = 'BACKLOG') as pending_bytes, "
                        + "coalesce(k.chunks, 0) as live_chunks, coalesce(k.bytes, 0) as live_bytes, "
                        + "coalesce(k.lines, 0) as live_lines, k.newest as newest_chunk_at "
                        + "from src s left join system_log_cursor c on c.device_id = {0} and c.source_key = s.source_key "
                        + "left join last_run r on r.source_key = s.source_key "
                        + "left join chunk_stats k on k.source_key = s.source_key order by s.source_key", deviceId)
                .map(r -> new SourceRow(r.get("source_key", String.class), Boolean.TRUE.equals(r.get("has_cursor", Boolean.class)),
                        r.get("offset_bytes", Long.class), r.get("device_epoch_at_read", Long.class),
                        r.get("pan_last_receive_time", String.class), Boolean.TRUE.equals(r.get("fast_rotating", Boolean.class)),
                        r.get("retention_horizon_seconds", Long.class), instant(r, "cursor_updated_at"),
                        r.get("last_outcome", String.class), instant(r, "last_run_at"), r.get("last_job_id", String.class),
                        r.get("last_bytes_read", Long.class), r.get("last_bytes_stored", Long.class),
                        number(r, "pending_bytes"), number(r, "live_chunks"), number(r, "live_bytes"), number(r, "live_lines"),
                        instant(r, "newest_chunk_at"))));
    }

    @Override
    public Optional<LastJob> lastJob(String deviceId) {
        return transactions.inTransaction(dsl -> {
            var newest = dsl.fetchOptional("select job_id, created_at from system_log_run where device_id = {0} "
                    + "order by created_at desc, run_id limit 1", deviceId);
            if (newest.isEmpty()) return Optional.empty();
            String jobId = newest.get().get("job_id", String.class);
            Map<String, Integer> outcomes = new LinkedHashMap<>();
            dsl.fetch("select outcome, count(*) as n from system_log_run where device_id = {0} and job_id = {1} "
                    + "group by outcome order by outcome", deviceId, jobId)
                    .forEach(r -> outcomes.put(r.get("outcome", String.class), (int) number(r, "n")));
            var window = dsl.fetchOne("select to_char(min(first_line_time), " + TIME + ") as w_from, "
                    + "to_char(max(last_line_time), " + TIME + ") as w_to from system_log_chunk "
                    + "where device_id = {0} and job_id = {1}", deviceId, jobId);
            return Optional.of(new LastJob(jobId, instant(newest.get(), "created_at"), outcomes,
                    window.get("w_from", String.class), window.get("w_to", String.class)));
        });
    }

    @Override
    public List<ChunkRow> chunks(String deviceId, Optional<String> sourceKey, Optional<String> day, int limit) {
        int bounded = Math.max(1, Math.min(limit, 1000));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select c.chunk_id, c.source_key, c.job_id, c.collected_at, cast(c.log_day as text) as log_day, "
                        + "c.start_offset, c.end_offset, c.bytes, c.lines, to_char(c.first_line_time, " + TIME + ") as first_line_time, "
                        + "to_char(c.last_line_time, " + TIME + ") as last_line_time, c.redaction_count, c.redaction_rule_set, "
                        + "array_to_string(c.flags, ',') as flags, c.ciphertext_bytes, c.pruned_at, c.prune_reason, r.outcome "
                        + "from system_log_chunk c left join system_log_run r on r.job_id = c.job_id and r.source_key = c.source_key "
                        + "where c.device_id = {0} and (cast({1} as text) is null or c.source_key = {1}) "
                        + "and (cast({2} as text) is null or c.log_day = cast({2} as date)) "
                        + "order by c.collected_at desc, c.start_offset desc, c.chunk_id limit {3}",
                deviceId, sourceKey.orElse(null), day.orElse(null), bounded)
                .map(r -> new ChunkRow(r.get("chunk_id", String.class), r.get("source_key", String.class),
                        r.get("job_id", String.class), instant(r, "collected_at"), r.get("log_day", String.class),
                        number(r, "start_offset"), number(r, "end_offset"), number(r, "bytes"), number(r, "lines"),
                        r.get("first_line_time", String.class), r.get("last_line_time", String.class),
                        (int) number(r, "redaction_count"), r.get("redaction_rule_set", String.class),
                        csv(r.get("flags", String.class)), number(r, "ciphertext_bytes"), instant(r, "pruned_at"),
                        r.get("prune_reason", String.class), r.get("outcome", String.class))));
    }

    @Override
    public Optional<ChunkRef> findChunk(String chunkId) {
        return transactions.inTransaction(dsl -> dsl.fetchOptional(
                "select c.chunk_id, c.device_id, c.source_key, c.bytes, c.lines, c.pruned_at, c.artefact_ref, "
                        + "c.wrapped_data_key, a.recovery_volume_path from system_log_chunk c "
                        + "left join backup_artefact a on a.artefact_id = c.artefact_ref where c.chunk_id = {0}", chunkId)
                .map(r -> new ChunkRef(r.get("chunk_id", String.class), r.get("device_id", String.class),
                        r.get("source_key", String.class), number(r, "bytes"), number(r, "lines"), instant(r, "pruned_at"),
                        r.get("artefact_ref", String.class), r.get("recovery_volume_path", String.class),
                        r.get("wrapped_data_key", byte[].class))));
    }

    @Override
    public List<GapRow> gaps(Optional<String> deviceId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 1000));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select gap_id, device_id, source_key, job_id, kind, known_lost_bytes, pending_bytes, extent_unknown, "
                        + "window_start_epoch, window_end_epoch, created_at from system_log_gap "
                        + "where (cast({0} as text) is null or device_id = {0}) order by created_at desc, gap_id limit {1}",
                deviceId.orElse(null), bounded)
                .map(r -> new GapRow(r.get("gap_id", String.class), r.get("device_id", String.class),
                        r.get("source_key", String.class), r.get("job_id", String.class), r.get("kind", String.class),
                        number(r, "known_lost_bytes"), number(r, "pending_bytes"),
                        Boolean.TRUE.equals(r.get("extent_unknown", Boolean.class)), r.get("window_start_epoch", Long.class),
                        r.get("window_end_epoch", Long.class), instant(r, "created_at"))));
    }

    @Override
    public List<CrashRow> crashEntries(Optional<String> deviceId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 1000));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select entry_id, device_id, directory, process, size, mtime, baseline, first_seen_at, removed_at "
                        + "from device_crash_entry where (cast({0} as text) is null or device_id = {0}) "
                        + "order by mtime desc, entry_id limit {1}", deviceId.orElse(null), bounded)
                .map(r -> new CrashRow(r.get("entry_id", String.class), r.get("device_id", String.class),
                        r.get("directory", String.class), r.get("process", String.class), number(r, "size"),
                        number(r, "mtime"), Boolean.TRUE.equals(r.get("baseline", Boolean.class)),
                        instant(r, "first_seen_at"), instant(r, "removed_at"))));
    }

    @Override
    public List<CrashAlertRow> crashAlerts(Optional<String> deviceId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select alert_id, device_id, process, file_count, newest_mtime, job_id, created_at, notified_at "
                        + "from device_crash_alert where (cast({0} as text) is null or device_id = {0}) "
                        + "order by created_at desc, alert_id limit {1}", deviceId.orElse(null), bounded)
                .map(r -> new CrashAlertRow(r.get("alert_id", String.class), r.get("device_id", String.class),
                        r.get("process", String.class), (int) number(r, "file_count"), number(r, "newest_mtime"),
                        r.get("job_id", String.class), instant(r, "created_at"), instant(r, "notified_at"))));
    }

    @Override
    public List<FileRow> hcpFiles(String deviceId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select file_id, kind, to_char(name_time, " + TIME + ") as name_time, size, mtime, status, "
                        + "array_to_string(flags, ',') as flags, collected_at, expires_at, pruned_at, ciphertext_bytes "
                        + "from system_log_file where device_id = {0} and kind = 'cp_hcp_report' "
                        + "order by collected_at desc, file_id limit {1}", deviceId, bounded)
                .map(r -> new FileRow(r.get("file_id", String.class), r.get("kind", String.class),
                        r.get("name_time", String.class), number(r, "size"), number(r, "mtime"), r.get("status", String.class),
                        csv(r.get("flags", String.class)), instant(r, "collected_at"), instant(r, "expires_at"),
                        instant(r, "pruned_at"), number(r, "ciphertext_bytes"))));
    }

    @Override
    public List<SpikeDirectoryRow> spikeDirectories(String deviceId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select d.directory_id, d.name_class, d.mtime, d.state, d.member_count, d.fetched_files, d.member_bytes, "
                        + "d.fetched_bytes, d.members::text as members_json, d.job_id, d.recorded_at, f.expires_at, f.pruned_at, "
                        + "f.ciphertext_bytes from system_log_spike_directory d "
                        + "left join system_log_file f on f.file_id = d.file_id "
                        + "where d.device_id = {0} and d.state <> 'BASELINE' "
                        + "order by d.recorded_at desc, d.directory_id limit {1}", deviceId, bounded)
                .map(r -> new SpikeDirectoryRow(r.get("directory_id", String.class), r.get("name_class", String.class),
                        number(r, "mtime"), r.get("state", String.class), r.get("member_count", Integer.class),
                        r.get("fetched_files", Integer.class), number(r, "member_bytes"), number(r, "fetched_bytes"),
                        r.get("members_json", String.class), r.get("job_id", String.class), instant(r, "recorded_at"),
                        instant(r, "expires_at"), instant(r, "pruned_at"), r.get("ciphertext_bytes", Long.class))));
    }

    @Override
    public Map<String, Long> spikeDirectoryCounts(String deviceId) {
        return transactions.inTransaction(dsl -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            dsl.fetch("select state, count(*) as n from system_log_spike_directory where device_id = {0} "
                    + "group by state order by state", deviceId)
                    .forEach(r -> counts.put(r.get("state", String.class), number(r, "n")));
            return counts;
        });
    }

    private static java.time.Instant instant(Record r, String column) {
        OffsetDateTime value = r.get(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static long number(Record r, String column) {
        Long value = r.get(column, Long.class);
        return value == null ? 0L : value;
    }

    private static List<String> csv(String value) {
        if (value == null || value.isEmpty()) return List.of();
        return new ArrayList<>(Arrays.asList(value.split(",")));
    }
}
