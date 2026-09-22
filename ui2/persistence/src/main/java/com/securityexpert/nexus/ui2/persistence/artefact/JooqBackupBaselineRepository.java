package com.securityexpert.nexus.ui2.persistence.artefact;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqBackupBaselineRepository implements BackupBaselineRepository {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary audited;

    public JooqBackupBaselineRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.audited = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<Baseline> find(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOptional(
                "select device_id, artefact_id, set_at from backup_baseline where device_id = {0}", deviceId)
                .map(row -> new Baseline(row.get("device_id", String.class), row.get("artefact_id", String.class),
                        row.get("set_at", Timestamp.class).toInstant())));
    }

    @Override
    public Map<String, String> findAll() {
        return transactionBoundary.inTransaction(dsl -> {
            Map<String, String> out = new LinkedHashMap<>();
            dsl.fetch("select device_id, artefact_id from backup_baseline")
                    .forEach(row -> out.put(row.get("device_id", String.class), row.get("artefact_id", String.class)));
            return out;
        });
    }

    @Override
    public void set(String deviceId, String artefactId, String actorFingerprint, String actionId) {
        audited.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "insert into backup_baseline(device_id, artefact_id, set_at) values ({0}, {1}, now()) "
                        + "on conflict (device_id) do update set artefact_id = excluded.artefact_id, set_at = now()",
                deviceId, artefactId));
    }

    @Override
    public boolean clear(String deviceId, String actorFingerprint, String actionId) {
        return audited.inTransaction(actorFingerprint, actionId,
                dsl -> dsl.execute("delete from backup_baseline where device_id = {0}", deviceId)) == 1;
    }
}
