package com.securityexpert.nexus.ui2.persistence.artefact;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One {@code backup_artefact} manifest row (BK-16 / C7 section 3.2).
 *
 * <p>Section 3.3's version-locking refusal is enforced in this record's own
 * compact constructor, not in the repository: a {@code check_point}
 * artefact whose {@code softwareVersion} cannot be resolved refuses to
 * construct at all, so {@link BackupArtefactManifestRepository#record}
 * never runs and zero rows are ever written for it. {@code palo_alto}
 * carries no such requirement -- its {@code softwareVersion} may be empty,
 * which persists as {@code NULL}.</p>
 *
 * <p>{@code backupType} is the {@code backup_artefact.backup_type} column (V30): {@code snapshot} for a Gaia snapshot
 * image, {@code standard} for everything else. The retention pruner keys on it -- a snapshot is kept by the policy's
 * snapshot depth (newest N per device), a standard backup by its age -- so a snapshot recorded as {@code standard}
 * would silently escape the depth rule. The 18-component constructor records {@code standard}.</p>
 */
public record BackupArtefactManifestRecord(
        String artefactId,
        String deviceId,
        Optional<String> virtualSystemRef,
        String artefactClass,
        String vendor,
        Optional<String> softwareVersion,
        String hostnameFingerprint,
        String plaintextSha256,
        long plaintextBytes,
        String ciphertextSha256,
        long ciphertextBytes,
        String keyId,
        byte[] wrappedDataKey,
        ArtefactValidation validation,
        String retentionTier,
        Optional<Instant> expiresAt,
        String recoveryVolumePath,
        Optional<String> deviationState,
        String backupType) {

    public static final String VENDOR_CHECK_POINT = "check_point";
    public static final String VENDOR_PALO_ALTO = "palo_alto";

    /** 14I DV-1's closed vocabulary -- {@link #deviationState}, empty for a path (configuration) that records its own change state elsewhere. */
    public static final String DEVIATION_UNCHANGED = "unchanged";
    public static final String DEVIATION_CHANGED = "changed";
    public static final String DEVIATION_FIRST = "first";

    /** The V30 {@code backup_type} vocabulary (the column's check constraint). */
    public static final String BACKUP_TYPE_STANDARD = "standard";
    public static final String BACKUP_TYPE_SNAPSHOT = "snapshot";

    /** Every artefact that is not a Gaia snapshot: records {@link #BACKUP_TYPE_STANDARD}. */
    public BackupArtefactManifestRecord(String artefactId, String deviceId, Optional<String> virtualSystemRef,
            String artefactClass, String vendor, Optional<String> softwareVersion, String hostnameFingerprint,
            String plaintextSha256, long plaintextBytes, String ciphertextSha256, long ciphertextBytes, String keyId,
            byte[] wrappedDataKey, ArtefactValidation validation, String retentionTier, Optional<Instant> expiresAt,
            String recoveryVolumePath, Optional<String> deviationState) {
        this(artefactId, deviceId, virtualSystemRef, artefactClass, vendor, softwareVersion, hostnameFingerprint,
                plaintextSha256, plaintextBytes, ciphertextSha256, ciphertextBytes, keyId, wrappedDataKey, validation,
                retentionTier, expiresAt, recoveryVolumePath, deviationState, BACKUP_TYPE_STANDARD);
    }

    public BackupArtefactManifestRecord {
        Objects.requireNonNull(artefactId, "artefactId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(virtualSystemRef, "virtualSystemRef");
        Objects.requireNonNull(artefactClass, "artefactClass");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(softwareVersion, "softwareVersion");
        Objects.requireNonNull(hostnameFingerprint, "hostnameFingerprint");
        Objects.requireNonNull(plaintextSha256, "plaintextSha256");
        Objects.requireNonNull(ciphertextSha256, "ciphertextSha256");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(wrappedDataKey, "wrappedDataKey");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(retentionTier, "retentionTier");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(recoveryVolumePath, "recoveryVolumePath");
        Objects.requireNonNull(deviationState, "deviationState");
        Objects.requireNonNull(backupType, "backupType");
        if (!BACKUP_TYPE_STANDARD.equals(backupType) && !BACKUP_TYPE_SNAPSHOT.equals(backupType)) {
            throw new IllegalArgumentException("backupType must be standard or snapshot");
        }
        if (BACKUP_TYPE_SNAPSHOT.equals(backupType) && !ArtefactClass.BACKUP.equals(artefactClass)) {
            throw new IllegalArgumentException("only a backup-class artefact can be a snapshot");
        }

        // C7 section 3.3: refused before any byte is written -- this
        // constructor is the earliest possible point, and throwing here
        // guarantees the repository's insert statement never runs, so
        // backup_artefact keeps zero rows for the refused attempt.
        if (VENDOR_CHECK_POINT.equals(vendor) && softwareVersion.isEmpty()) {
            throw new IllegalStateException("backup_artefact_version_unresolvable: a check_point artefact "
                    + "requires a resolvable software_version before any manifest row is written "
                    + "(C7 section 3.3) -- artefact_id=" + artefactId);
        }
    }
}
