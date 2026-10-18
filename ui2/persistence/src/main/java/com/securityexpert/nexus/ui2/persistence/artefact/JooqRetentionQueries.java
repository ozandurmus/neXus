package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.List;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * V44: the retention pruner's reads and the tombstone write over the real
 * tables (V164 adds the configuration archive class). An artefact is "removed" when its ledger carries a {@code removed}
 * row; the manifest row stays (append-only recovery record) and every listing
 * excludes removed artefacts. A device's baseline ({@code backup_baseline}) is
 * never offered for pruning.
 */
public final class JooqRetentionQueries {

    public record Candidate(String artefactId, String deviceId, String recoveryVolumePath, long ciphertextBytes,
            String retentionTier, String backupType) {
    }

    private static final java.util.regex.Pattern SQL_ALIAS = java.util.regex.Pattern.compile("[a-z_][a-z0-9_]*");

    private static final String NOT_REMOVED = andNotRemoved("a");
    private static final String NOT_BASELINE =
            " and not exists (select 1 from backup_baseline b where b.artefact_id = a.artefact_id)";
    private static final String COLUMNS =
            "select a.artefact_id, a.device_id, a.recovery_volume_path, a.ciphertext_bytes, a.retention_tier, coalesce(a.backup_type, 'standard') as backup_type ";

    /**
     * The liveness predicate, with its leading {@code " and "}: true while the {@code backup_artefact} row under
     * {@code alias} has no {@code removed} ledger row. A pruned artefact keeps its manifest row, so every read that
     * counts, sums or lists stored artefacts must carry this predicate; without it a screen reports bytes that are no
     * longer on the volume (2026-10-18: the System storage panel showed 887 GB for a 240 GB store).
     */
    public static String andNotRemoved(String alias) {
        // "l" is the ledger's own alias inside the predicate; an outer "l" would be shadowed and match itself.
        if (alias == null || !SQL_ALIAS.matcher(alias).matches() || "l".equals(alias)) {
            throw new IllegalArgumentException("alias must be a plain SQL identifier other than l");
        }
        return " and not exists (select 1 from artefact_retention_ledger l where l.artefact_id = " + alias
                + ".artefact_id and l.event = 'removed')";
    }

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary audited;

    public JooqRetentionQueries(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.audited = new AuditedTransactionBoundary(transactionBoundary);
    }

    public List<Candidate> expiredStandardBackups(int days) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(COLUMNS
                + "from backup_artefact a where a.artefact_class = 'backup' and coalesce(a.backup_type, 'standard') = 'standard' "
                + "and a.created_at < now() - ({0} || ' days')::interval" + NOT_REMOVED + NOT_BASELINE
                + " order by a.created_at", String.valueOf(days)).map(JooqRetentionQueries::toCandidate));
    }

    /**
     * Configuration archives older than {@code days} (PO decision 2026-10-18: 30 days), by {@code created_at}. The
     * device's latest configuration archive is excluded by {@link #isLatestLiveConfigurationArchive}, which the
     * pruner asks again per row, not here: a list read once could go stale between rows.
     */
    public List<Candidate> expiredConfigurationArchives(int days) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(COLUMNS
                + "from backup_artefact a where a.artefact_class = 'configuration' "
                + "and a.created_at < now() - ({0} || ' days')::interval" + NOT_REMOVED + NOT_BASELINE
                + " order by a.created_at, a.artefact_id", String.valueOf(days)).map(JooqRetentionQueries::toCandidate));
    }

    /**
     * True when no live configuration archive of the same device is newer than this one (ties broken by id), or when
     * the row is unknown: the answer errs towards keeping. Unlike counting "other live archives", this stays true for
     * the newest archive even if an older one could not be deleted.
     */
    public boolean isLatestLiveConfigurationArchive(String artefactId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOptional(
                "select not exists (select 1 from backup_artefact n where n.device_id = a.device_id "
                        + "and n.artefact_class = 'configuration' and (n.created_at, n.artefact_id) > (a.created_at, a.artefact_id)"
                        + andNotRemoved("n") + ") from backup_artefact a where a.artefact_id = {0} and a.artefact_class = 'configuration'",
                artefactId).map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class))).orElse(true));
    }

    public List<Candidate> snapshotsBeyondDepth(int depth) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(COLUMNS
                + "from (select *, row_number() over (partition by device_id order by created_at desc) as rank "
                + "      from backup_artefact x where x.artefact_class = 'backup' and coalesce(x.backup_type, 'standard') = 'snapshot'"
                + andNotRemoved("x") + ") a "
                + "where a.rank > {0}" + NOT_BASELINE + " order by a.created_at", depth)
                .map(JooqRetentionQueries::toCandidate));
    }

    /** Audited: the {@code removed} ledger row, and the derived listing rows go with the bytes. */
    public void recordRemoved(String ledgerId, String artefactId, String retentionTier, String actorFingerprint, String actionId) {
        audited.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("insert into artefact_retention_ledger(ledger_id, artefact_id, event, retention_tier) values ({0}, {1}, 'removed', {2})",
                    ledgerId, artefactId, retentionTier);
            dsl.execute("delete from backup_artefact_entry where artefact_id = {0}", artefactId);
            dsl.execute("delete from backup_artefact_content_listing where artefact_id = {0}", artefactId);
            return null;
        });
    }

    public int otherLiveBackupsOfDevice(String deviceId, String artefactId) {
        Integer others = transactionBoundary.inTransaction(dsl -> dsl.fetchOne(
                "select count(*) from backup_artefact a where a.device_id = {0} and a.artefact_class = 'backup' "
                        + "and a.artefact_id <> {1}" + NOT_REMOVED, deviceId, artefactId).get(0, Integer.class));
        return others == null ? 0 : others;
    }

    /**
     * Live bytes of the backup vault: backup and configuration artefacts. System-log artefacts (V160 class
     * {@code system_log}) share the table but have their own retention and per-device cap (phase B contract section
     * 5.3, {@code SystemLogRetentionPruner}); counting them here would let log volume eat the backup budget.
     */
    public long liveCiphertextBytes() {
        Long sum = transactionBoundary.inTransaction(dsl -> dsl.fetchOne(
                "select coalesce(sum(a.ciphertext_bytes), 0) from backup_artefact a where a.artefact_class <> 'system_log'"
                        + NOT_REMOVED).get(0, Long.class));
        return sum == null ? 0L : sum;
    }

    private static Candidate toCandidate(org.jooq.Record row) {
        return new Candidate(row.get("artefact_id", String.class), row.get("device_id", String.class),
                row.get("recovery_volume_path", String.class), row.get("ciphertext_bytes", Long.class),
                row.get("retention_tier", String.class), row.get("backup_type", String.class));
    }
}
