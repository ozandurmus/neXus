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
        String recoveryVolumePath) {

    public static final String VENDOR_CHECK_POINT = "check_point";
    public static final String VENDOR_PALO_ALTO = "palo_alto";

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
