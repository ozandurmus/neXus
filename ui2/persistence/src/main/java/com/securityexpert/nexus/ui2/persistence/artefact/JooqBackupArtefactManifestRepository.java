package com.securityexpert.nexus.ui2.persistence.artefact;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link BackupArtefactManifestRepository} (migration V17/V18,
 * BK-16, BK-18, 14I DV-1). Mirrors {@code
 * com.securityexpert.nexus.ui2.persistence.device.configuration.
 * JooqDeviceConfigurationRepository}'s own shape: one audited transaction
 * writes the manifest row and its ledger event together; the read methods
 * use the plain {@link TransactionBoundary} directly (no audit context
 * needed for a read).
 */
public final class JooqBackupArtefactManifestRepository implements BackupArtefactManifestRepository {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqBackupArtefactManifestRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public void record(BackupArtefactManifestRecord manifest, String actorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("insert into backup_artefact(artefact_id, device_id, virtual_system_ref, artefact_class, "
                    + "vendor, software_version, hostname_fingerprint, plaintext_sha256, plaintext_bytes, "
                    + "ciphertext_sha256, ciphertext_bytes, key_id, wrapped_data_key, validation, retention_tier, "
                    + "expires_at, recovery_volume_path, deviation_state) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}, {13}::jsonb, "
                    + "{14}, {15}, {16}, {17})",
                    manifest.artefactId(), manifest.deviceId(), manifest.virtualSystemRef().orElse(null),
                    manifest.artefactClass(), manifest.vendor(), manifest.softwareVersion().orElse(null),
                    manifest.hostnameFingerprint(), manifest.plaintextSha256(), manifest.plaintextBytes(),
                    manifest.ciphertextSha256(), manifest.ciphertextBytes(), manifest.keyId(),
                    manifest.wrappedDataKey(), manifest.validation().toJson(), manifest.retentionTier(),
                    manifest.expiresAt().map(Timestamp::from).orElse(null), manifest.recoveryVolumePath(),
                    manifest.deviationState().orElse(null));

            // BK-18: the ledger is written whenever an artefact is created.
            dsl.execute("insert into artefact_retention_ledger(ledger_id, artefact_id, event, retention_tier) "
                    + "values ({0}, {1}, {2}, {3})",
                    UUID.randomUUID().toString(), manifest.artefactId(), "created", manifest.retentionTier());
            return null;
        });
    }

    @Override
    public Optional<PlaintextDigestSummary> findLatestPlaintextDigest(String deviceId, String artefactClass) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOptional(
                "select artefact_id, plaintext_sha256 from backup_artefact where device_id = {0} "
                        + "and artefact_class = {1} order by created_at desc limit 1",
                deviceId, artefactClass)
                .map(row -> new PlaintextDigestSummary(row.get("artefact_id", String.class),
                        row.get("plaintext_sha256", String.class))));
    }

    @Override
    public List<BackupArtefactSummary> findByDevice(String deviceId, String artefactClass) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select artefact_id, device_id, created_at, plaintext_bytes, plaintext_sha256, "
                        + "validation->>'level_reached' as validation_level, deviation_state "
                        + "from backup_artefact where device_id = {0} and artefact_class = {1} "
                        + "order by created_at desc",
                deviceId, artefactClass)
                .stream().map(JooqBackupArtefactManifestRepository::toSummary).toList());
    }

    @Override
    public List<BackupArtefactSummary> findAll(String artefactClass) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select artefact_id, device_id, created_at, plaintext_bytes, plaintext_sha256, "
                        + "validation->>'level_reached' as validation_level, deviation_state "
                        + "from backup_artefact where artefact_class = {0} order by created_at desc",
                artefactClass)
                .stream().map(JooqBackupArtefactManifestRepository::toSummary).toList());
    }

    @Override
    public Optional<RetrievalManifest> findForRetrieval(String artefactId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOptional(
                "select artefact_id, wrapped_data_key, recovery_volume_path from backup_artefact "
                        + "where artefact_id = {0}", artefactId)
                .map(row -> new RetrievalManifest(row.get("artefact_id", String.class),
                        row.get("wrapped_data_key", byte[].class),
                        row.get("recovery_volume_path", String.class))));
    }

    private static BackupArtefactSummary toSummary(Record row) {
        return new BackupArtefactSummary(row.get("artefact_id", String.class), row.get("device_id", String.class),
                row.get("created_at", java.sql.Timestamp.class).toInstant(),
                row.get("plaintext_bytes", Long.class), row.get("plaintext_sha256", String.class),
                row.get("validation_level", String.class),
                Optional.ofNullable(row.get("deviation_state", String.class)));
    }
}
