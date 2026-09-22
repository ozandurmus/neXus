package com.securityexpert.nexus.ui2.persistence.artefact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code backup_artefact} / {@code artefact_retention_ledger} persistence
 * (migration V17, BK-16, BK-18). One artefact-class-agnostic manifest table
 * for every artefact this store holds -- the configuration path records
 * {@code artefact_class = 'configuration'} rows here alongside its own
 * authoritative {@code configuration_artefact} table; a future backup
 * capability records {@code artefact_class = 'backup'} rows the same way.
 */
public interface BackupArtefactManifestRepository {

    /**
     * Writes the manifest row and its retention-ledger {@code created}
     * event in one audited transaction (BK-18: the ledger is written
     * "whenever an artefact is created"). {@code manifest}'s own compact
     * constructor already refused construction for an unresolvable
     * check_point software version (C7 section 3.3), so a call reaching
     * this method has already passed that gate.
     */
    void record(BackupArtefactManifestRecord manifest, String actorFingerprint, String actionId);

    /** 14I DV-1: the newest artefact of {@code artefactClass} for {@code deviceId}, by plaintext digest only -- the comparison basis for the run just completing, or empty for a device's first artefact of this class. */
    Optional<PlaintextDigestSummary> findLatestPlaintextDigest(String deviceId, String artefactClass);

    /** WORKER.md "Service and screen": the manifest rows one device's backup panel shows -- never a path, never bytes. */
    List<BackupArtefactSummary> findByDevice(String deviceId, String artefactClass);

    /** WORKER.md "GET /backups for the fleet view". */
    List<BackupArtefactSummary> findAll(String artefactClass);

    /** 14I OR-2: the wrapped data key an operator retrieval needs to decrypt through the artefact store. */
    Optional<RetrievalManifest> findForRetrieval(String artefactId);

    record PlaintextDigestSummary(String artefactId, String plaintextSha256) {
    }

    record RetrievalManifest(String artefactId, String recoveryVolumePath, byte[] wrappedDataKey) {
    }

    record BackupArtefactSummary(String artefactId, String deviceId, Instant createdAt, long plaintextBytes,
            String plaintextSha256, String validationLevel, Optional<String> deviationState, String vendor,
            String artefactClass) {
        public BackupArtefactSummary(String artefactId, String deviceId, Instant createdAt, long plaintextBytes,
                String plaintextSha256, String validationLevel, Optional<String> deviationState) {
            this(artefactId, deviceId, createdAt, plaintextBytes, plaintextSha256, validationLevel, deviationState,
                    "unknown", "unknown");
        }
    }
}
