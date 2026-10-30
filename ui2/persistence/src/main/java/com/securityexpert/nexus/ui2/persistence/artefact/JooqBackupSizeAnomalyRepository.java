package com.securityexpert.nexus.ui2.persistence.artefact;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * V180 backup size anomalies (PO decision 2026-10-30, docs/design/BACKUP_SIZE_ANOMALY_RETENTION.md) over
 * {@code backup_deviation_record} rows of class {@code size_anomaly}: the worker's ingest check, the Backups card's
 * read, and the security_admin's "accept new size".
 *
 * <p>The pin is derived, not a flag: an unresolved row's {@code previous_artefact_id} is the device's last good
 * backup, and {@link JooqRetentionQueries} never offers it (nor the newest unresolved anomalous copy) for pruning.
 * Resolving the rows is the only way to release the pin.</p>
 */
public final class JooqBackupSizeAnomalyRepository {

    public static final String DEVIATION_CLASS = "size_anomaly";
    public static final String ACTION_DETECTED = "backup_size_anomaly_detected";

    /** True for {@code alias}'s artefact while it is an unresolved anomalous copy. */
    static String isOpenAnomaly(String alias) {
        return "exists (select 1 from backup_deviation_record d where d.deviation_class = 'size_anomaly' "
                + "and d.resolved_at is null and d.artefact_id = " + alias + ".artefact_id)";
    }

    /** True for {@code alias}'s artefact while an unresolved anomaly pins it as the last good backup. */
    static String isPinnedLastGood(String alias) {
        return "exists (select 1 from backup_deviation_record d where d.deviation_class = 'size_anomaly' "
                + "and d.resolved_at is null and d.previous_artefact_id = " + alias + ".artefact_id)";
    }

    /** A recorded anomaly, as the ingest check returns it. */
    public record Detected(String deviationId, String artefactId, String lastGoodArtefactId,
            BackupSizeAnomalyRule.Verdict verdict) {
    }

    /** One device's open anomaly, for the Backups card: the newest unresolved row and how many copies are open. */
    public record OpenAnomaly(String deviceId, String deviationId, String artefactId, String lastGoodArtefactId,
            String direction, long newBytes, long medianBytes, double ratio, String summary, Instant detectedAt,
            int openCopies) {
    }

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary audited;

    public JooqBackupSizeAnomalyRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.audited = new AuditedTransactionBoundary(transactionBoundary);
    }

    /**
     * The ingest check for one just-recorded artefact. Only a live standard backup is checked, against the device's
     * {@link BackupSizeAnomalyRule#HISTORY} newest earlier live standard backups that are not open anomalies and are
     * not older than an accepted anomaly (an accepted size restarts the history); the newest of those is the last good
     * one the record pins. A per-device advisory lock keeps two concurrent ingests
     * of the same device from both reading the other as history.
     */
    public Optional<Detected> inspect(String artefactId, String actorFingerprint) {
        Objects.requireNonNull(artefactId, "artefactId");
        return audited.inTransaction(actorFingerprint, ACTION_DETECTED, dsl -> {
            var artefact = dsl.fetchOptional("select a.device_id, a.plaintext_bytes, a.created_at from backup_artefact a "
                    + "where a.artefact_id = {0} and a.artefact_class = 'backup' "
                    + "and coalesce(a.backup_type, 'standard') = 'standard'" + JooqRetentionQueries.andNotRemoved("a"),
                    artefactId);
            if (artefact.isEmpty()) {
                return Optional.<Detected>empty();
            }
            String deviceId = artefact.get().get("device_id", String.class);
            long newBytes = artefact.get().get("plaintext_bytes", Long.class);
            dsl.execute("select pg_advisory_xact_lock(hashtext('backup_size_anomaly:' || {0}))", deviceId);
            if (dsl.fetchOptional("select 1 from backup_deviation_record where deviation_class = 'size_anomaly' "
                    + "and artefact_id = {0}", artefactId).isPresent()) {
                return Optional.<Detected>empty();
            }
            var history = dsl.fetch("select h.artefact_id, h.plaintext_bytes from backup_artefact h, backup_artefact n "
                    + "where n.artefact_id = {0} and h.device_id = n.device_id and h.artefact_id <> n.artefact_id "
                    + "and h.artefact_class = 'backup' and coalesce(h.backup_type, 'standard') = 'standard' "
                    + "and (h.created_at, h.artefact_id) < (n.created_at, n.artefact_id)"
                    + JooqRetentionQueries.andNotRemoved("h") + " and not " + isOpenAnomaly("h")
                    // An accepted size is the new normal: history restarts at the accepted anomalous copies, so the
                    // old size no longer counts (and too little history again means no anomaly).
                    + " and not exists (select 1 from backup_deviation_record r join backup_artefact ra "
                    + "on ra.artefact_id = r.artefact_id where r.device_id = n.device_id "
                    + "and r.deviation_class = 'size_anomaly' and r.resolved_at is not null "
                    + "and (h.created_at, h.artefact_id) < (ra.created_at, ra.artefact_id))"
                    + " order by h.created_at desc, h.artefact_id desc limit {1}",
                    artefactId, BackupSizeAnomalyRule.HISTORY);
            List<Long> sizes = history.map(row -> row.get("plaintext_bytes", Long.class));
            Optional<BackupSizeAnomalyRule.Verdict> verdict = BackupSizeAnomalyRule.evaluate(newBytes, sizes);
            if (verdict.isEmpty()) {
                return Optional.<Detected>empty();
            }
            String lastGood = history.get(0).get("artefact_id", String.class);
            String deviationId = UUID.randomUUID().toString();
            BackupSizeAnomalyRule.Verdict v = verdict.get();
            dsl.execute("insert into backup_deviation_record(deviation_id, artefact_id, previous_artefact_id, device_id, "
                    + "deviation_class, summary, details) values ({0}, {1}, {2}, {3}, 'size_anomaly', {4}, "
                    + "jsonb_build_object('direction', {5}::text, 'plaintext_bytes', {6}::bigint, "
                    + "'median_bytes', {7}::bigint, 'ratio', round({8}::numeric, 3), 'history_count', {9}::int, "
                    + "'growth_factor', {10}::int, 'shrink_percent', {11}::int, 'min_change_bytes', {12}::bigint))",
                    deviationId, artefactId, lastGood, deviceId, v.summary(), v.direction().wire(), v.newBytes(),
                    v.medianBytes(), v.ratio(), BackupSizeAnomalyRule.HISTORY, BackupSizeAnomalyRule.GROWTH_FACTOR,
                    BackupSizeAnomalyRule.SHRINK_PERCENT, BackupSizeAnomalyRule.MIN_CHANGE_BYTES);
            return Optional.of(new Detected(deviationId, artefactId, lastGood, v));
        });
    }

    /** One row per device with an unresolved size anomaly, newest first. */
    public List<OpenAnomaly> openAnomalies() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from (select d.device_id, d.deviation_id, d.artefact_id, d.previous_artefact_id, d.summary, "
                        + "d.detected_at, d.details->>'direction' as direction, (d.details->>'plaintext_bytes')::bigint as new_bytes, "
                        + "(d.details->>'median_bytes')::bigint as median_bytes, (d.details->>'ratio')::float8 as ratio, "
                        + "count(*) over (partition by d.device_id) as open_copies, "
                        + "row_number() over (partition by d.device_id order by d.detected_at desc, d.deviation_id desc) as rank "
                        + "from backup_deviation_record d where d.deviation_class = 'size_anomaly' and d.resolved_at is null) o "
                        + "where o.rank = 1 order by o.detected_at desc, o.device_id")
                .map(row -> new OpenAnomaly(row.get("device_id", String.class), row.get("deviation_id", String.class),
                        row.get("artefact_id", String.class), row.get("previous_artefact_id", String.class),
                        row.get("direction", String.class), orZero(row.get("new_bytes", Long.class)),
                        orZero(row.get("median_bytes", Long.class)),
                        row.get("ratio", Double.class) == null ? 0d : row.get("ratio", Double.class),
                        row.get("summary", String.class), row.get("detected_at", Timestamp.class).toInstant(),
                        row.get("open_copies", Integer.class))));
    }

    /**
     * "Accept new size": every unresolved size anomaly of the device is resolved with the actor and the reason, which
     * releases the last-good pin; the formerly anomalous backups become normal history and the age rule applies again.
     *
     * @return how many rows were resolved (0: nothing was open)
     */
    public int accept(String deviceId, String actorFingerprint, String actionId, String reason) {
        Objects.requireNonNull(deviceId, "deviceId");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        return audited.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update backup_deviation_record set resolved_at = now(), resolved_by = {1}, resolution_reason = {2} "
                        + "where device_id = {0} and deviation_class = 'size_anomaly' and resolved_at is null",
                deviceId, actorFingerprint, reason.strip()));
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
