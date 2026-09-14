package com.securityexpert.nexus.ui2.persistence.artefact;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link BackupArtefactManifestRepository} (migration V17,
 * BK-16, BK-18). Mirrors {@code
 * com.securityexpert.nexus.ui2.persistence.device.configuration.
 * JooqDeviceConfigurationRepository}'s own shape: one audited transaction
 * writes the manifest row and its ledger event together.
 */
public final class JooqBackupArtefactManifestRepository implements BackupArtefactManifestRepository {

    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqBackupArtefactManifestRepository(TransactionBoundary transactionBoundary) {
        Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public void record(BackupArtefactManifestRecord manifest, String actorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("insert into backup_artefact(artefact_id, device_id, virtual_system_ref, artefact_class, "
                    + "vendor, software_version, hostname_fingerprint, plaintext_sha256, plaintext_bytes, "
                    + "ciphertext_sha256, ciphertext_bytes, key_id, wrapped_data_key, validation, retention_tier, "
                    + "expires_at, recovery_volume_path) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}, {13}::jsonb, "
                    + "{14}, {15}, {16})",
                    manifest.artefactId(), manifest.deviceId(), manifest.virtualSystemRef().orElse(null),
                    manifest.artefactClass(), manifest.vendor(), manifest.softwareVersion().orElse(null),
                    manifest.hostnameFingerprint(), manifest.plaintextSha256(), manifest.plaintextBytes(),
                    manifest.ciphertextSha256(), manifest.ciphertextBytes(), manifest.keyId(),
                    manifest.wrappedDataKey(), manifest.validation().toJson(), manifest.retentionTier(),
                    manifest.expiresAt().map(Timestamp::from).orElse(null), manifest.recoveryVolumePath());

            // BK-18: the ledger is written whenever an artefact is created.
            dsl.execute("insert into artefact_retention_ledger(ledger_id, artefact_id, event, retention_tier) "
                    + "values ({0}, {1}, {2}, {3})",
                    UUID.randomUUID().toString(), manifest.artefactId(), "created", manifest.retentionTier());
            return null;
        });
    }
}
