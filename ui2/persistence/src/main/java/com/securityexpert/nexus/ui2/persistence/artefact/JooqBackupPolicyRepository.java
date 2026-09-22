package com.securityexpert.nexus.ui2.persistence.artefact;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqBackupPolicyRepository implements BackupPolicyRepository {

    private static final String POLICY_ID = "default";

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary audited;

    public JooqBackupPolicyRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.audited = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<BackupPolicy> find() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOptional(
                "select policy_id, schedule_enabled, daily_backup_cron, backup_retention_days, snapshot_retention_depth, "
                        + "last_scheduled_run_at, updated_at from backup_policy where policy_id = {0}", POLICY_ID)
                .map(row -> new BackupPolicy(row.get("policy_id", String.class),
                        Boolean.TRUE.equals(row.get("schedule_enabled", Boolean.class)),
                        row.get("daily_backup_cron", String.class), row.get("backup_retention_days", Integer.class),
                        row.get("snapshot_retention_depth", Integer.class),
                        Optional.ofNullable(row.get("last_scheduled_run_at", Timestamp.class)).map(Timestamp::toInstant),
                        row.get("updated_at", Timestamp.class).toInstant())));
    }

    @Override
    public boolean update(boolean scheduleEnabled, String dailyBackupCron, int backupRetentionDays,
            int snapshotRetentionDepth, String actorFingerprint, String actionId) {
        int updated = audited.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update backup_policy set schedule_enabled = {0}, daily_backup_cron = {1}, backup_retention_days = {2}, "
                        + "snapshot_retention_depth = {3}, updated_at = now() where policy_id = {4}",
                scheduleEnabled, dailyBackupCron, backupRetentionDays, snapshotRetentionDepth, POLICY_ID));
        return updated == 1;
    }

    @Override
    public boolean claimScheduledRun(Instant dueSince, Instant runAt, String actorFingerprint, String actionId) {
        int updated = audited.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update backup_policy set last_scheduled_run_at = {0} where policy_id = {1} and schedule_enabled "
                        + "and (last_scheduled_run_at is null or last_scheduled_run_at < {2})",
                Timestamp.from(runAt), POLICY_ID, Timestamp.from(dueSince)));
        return updated == 1;
    }
}
