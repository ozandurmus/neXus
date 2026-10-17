package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** {@link SystemLogRetentionRepository} over V160's tables and the shared artefact retention ledger. */
public final class JooqSystemLogRetentionRepository implements SystemLogRetentionRepository {
    static final String ACTOR = "system:worker";
    static final String ACTION = "system_log_retention_prune";

    private static final String CHUNK_COLUMNS = "select 'system_log_chunk' as tbl, c.chunk_id as row_id, c.device_id, "
            + "c.source_key, c.artefact_ref as artefact_id, a.recovery_volume_path, c.ciphertext_bytes, "
            + "coalesce(a.plaintext_bytes, c.bytes) as plaintext_bytes, c.collected_at "
            + "from system_log_chunk c left join backup_artefact a on a.artefact_id = c.artefact_ref ";
    private static final String FILE_COLUMNS = "select 'system_log_file' as tbl, f.file_id as row_id, f.device_id, "
            + "f.kind as source_key, f.artefact_ref as artefact_id, a.recovery_volume_path, f.ciphertext_bytes, "
            + "coalesce(a.plaintext_bytes, f.size) as plaintext_bytes, f.collected_at "
            + "from system_log_file f left join backup_artefact a on a.artefact_id = f.artefact_ref ";

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqSystemLogRetentionRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    @Override
    public List<Candidate> expiredChunks(int days, int limit) {
        return transactions.inTransaction(dsl -> dsl.fetch(CHUNK_COLUMNS + "where c.pruned_at is null "
                + "and c.collected_at < now() - ({0} || ' days')::interval order by c.collected_at, c.chunk_id limit {1}",
                String.valueOf(days), limit).map(JooqSystemLogRetentionRepository::candidate));
    }

    @Override
    public List<Candidate> expiredFiles(String kind, int days, int limit) {
        return transactions.inTransaction(dsl -> dsl.fetch(FILE_COLUMNS + "where f.pruned_at is null and f.kind = {0} "
                + "and f.collected_at < now() - ({1} || ' days')::interval order by f.collected_at, f.file_id limit {2}",
                kind, String.valueOf(days), limit).map(JooqSystemLogRetentionRepository::candidate));
    }

    @Override
    public List<DeviceUsage> devicesOverCap(long capBytes) {
        return transactions.inTransaction(dsl -> dsl.fetch("select device_id, sum(bytes) as bytes from ("
                + "select device_id, ciphertext_bytes as bytes from system_log_chunk where pruned_at is null "
                + "union all select device_id, ciphertext_bytes from system_log_file where pruned_at is null "
                + "and kind = 'cp_sar_daily') u group by device_id having sum(bytes) > {0} order by device_id", capBytes)
                .map(r -> new DeviceUsage(r.get("device_id", String.class), r.get("bytes", Long.class))));
    }

    @Override
    public List<Candidate> oldestCapped(String deviceId, int limit) {
        return transactions.inTransaction(dsl -> dsl.fetch("select * from (" + CHUNK_COLUMNS
                + "where c.pruned_at is null and c.device_id = {0} union all " + FILE_COLUMNS
                + "where f.pruned_at is null and f.device_id = {0} and f.kind = 'cp_sar_daily') u "
                + "order by collected_at, row_id limit {1}", deviceId, limit).map(JooqSystemLogRetentionRepository::candidate));
    }

    @Override
    public boolean markPruned(Candidate candidate, String reason) {
        if (!"RETENTION".equals(reason) && !"RETENTION_SHORTENED_BY_CAP".equals(reason))
            throw new IllegalArgumentException("prune reason");
        return audited.inTransaction(ACTOR, ACTION, dsl -> {
            int updated = Candidate.CHUNK.equals(candidate.table())
                    ? dsl.execute("update system_log_chunk set pruned_at = now(), prune_reason = {0} where chunk_id = {1} "
                            + "and pruned_at is null", reason, candidate.rowId())
                    : dsl.execute("update system_log_file set pruned_at = now() where file_id = {0} and pruned_at is null",
                            candidate.rowId());
            if (updated != 1) return false;
            if (candidate.artefactId() != null)
                dsl.execute("insert into artefact_retention_ledger(ledger_id, artefact_id, event, retention_tier) "
                        + "select {0}, a.artefact_id, 'removed', a.retention_tier from backup_artefact a "
                        + "where a.artefact_id = {1} and not exists (select 1 from artefact_retention_ledger l "
                        + "where l.artefact_id = a.artefact_id and l.event = 'removed')",
                        UUID.randomUUID().toString(), candidate.artefactId());
            return true;
        });
    }

    @Override
    public void recordCapGap(String passId, String deviceId, String sourceKey, long prunedPlaintextBytes, String detailJson) {
        audited.inTransaction(ACTOR, ACTION, dsl -> dsl.execute("insert into system_log_gap(gap_id, device_id, source_key, "
                + "job_id, kind, known_lost_bytes, pending_bytes, extent_unknown, detail) values ({0}, {1}, {2}, {3}, "
                + "'RETENTION_SHORTENED_BY_CAP', {4}, 0, false, cast({5} as jsonb))", UUID.randomUUID().toString(), deviceId,
                sourceKey, passId, prunedPlaintextBytes, detailJson));
    }

    @Override
    public int pruneLedgerRows(int days) {
        return audited.inTransaction(ACTOR, ACTION, dsl -> {
            Timestamp cutoff = dsl.fetchOne("select now() - ({0} || ' days')::interval as t", String.valueOf(days))
                    .get("t", Timestamp.class);
            int n = dsl.execute("delete from system_log_gap where created_at < {0}", cutoff);
            n += dsl.execute("delete from system_log_run where created_at < {0}", cutoff);
            n += dsl.execute("delete from device_crash_alert where created_at < {0}", cutoff);
            n += dsl.execute("delete from device_crash_entry where removed_at is not null and removed_at < {0}", cutoff);
            return n;
        });
    }

    private static Candidate candidate(org.jooq.Record r) {
        Timestamp at = r.get("collected_at", Timestamp.class);
        return new Candidate(r.get("tbl", String.class), r.get("row_id", String.class), r.get("device_id", String.class),
                r.get("source_key", String.class), r.get("artefact_id", String.class),
                r.get("recovery_volume_path", String.class), r.get("ciphertext_bytes", Long.class),
                r.get("plaintext_bytes", Long.class), at == null ? null : at.toInstant());
    }
}
