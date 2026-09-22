package com.securityexpert.nexus.ui2.service.device.backup;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactRetrievalRepository;

/**
 * PO decision record 2026-09-22 (backup HTTP download with RBAC): the one
 * service-side path that decrypts a backup artefact. The role gate
 * ({@code role:backup_admin}) is the route's own E4 evaluation, not
 * re-checked here; what this class owns is the order of operations --
 * reason checked, manifest found, <b>audit row written</b>, and only then
 * the ciphertext opened. An audit write that fails refuses the download
 * with zero bytes sent (14I OR-3, unchanged by the decision).
 */
public final class BackupDownloadService {

    private static final Logger LOG = Logger.getLogger(BackupDownloadService.class.getName());
    static final int MIN_REASON_LENGTH = 8;
    static final String ACTION_DOWNLOADED = "backup_artefact_downloaded";
    static final String DESTINATION_BROWSER = "browser";

    public sealed interface Outcome {
        /** The caller owns {@code stream} and must close it. */
        record Ready(String artefactId, String vendor, long plaintextBytes, Instant collectedAt, InputStream stream)
                implements Outcome {
        }

        record ReasonTooShort() implements Outcome {
        }

        record ArtefactNotFound() implements Outcome {
        }

        record AuditRefused(String reason) implements Outcome {
        }

        /** The service pod has no artefact-store key/volume mounted -- a deployment fact, reported as such. */
        record StoreUnavailable(String reason) implements Outcome {
        }

        record IoFailure(String reason) implements Outcome {
        }
    }

    private final ArtefactStore artefactStore; // null when the service has no store mounted
    private final BackupArtefactManifestRepository manifestRepository;
    private final BackupArtefactRetrievalRepository retrievalRepository;

    public BackupDownloadService(ArtefactStore artefactStore, BackupArtefactManifestRepository manifestRepository,
            BackupArtefactRetrievalRepository retrievalRepository) {
        this.artefactStore = artefactStore;
        this.manifestRepository = Objects.requireNonNull(manifestRepository, "manifestRepository");
        this.retrievalRepository = Objects.requireNonNull(retrievalRepository, "retrievalRepository");
    }

    public Outcome prepare(String actorFingerprint, String artefactId, String reason) {
        if (reason == null || reason.strip().length() < MIN_REASON_LENGTH) {
            return new Outcome.ReasonTooShort();
        }
        if (artefactStore == null) {
            return new Outcome.StoreUnavailable(
                    "the service has no artefact store mounted (UI2_ARTEFACT_STORE_KEY_FILE / UI2_ARTEFACT_STORE_ROOT)");
        }
        Optional<BackupArtefactManifestRepository.RetrievalManifest> manifest =
                manifestRepository.findForRetrieval(artefactId);
        if (manifest.isEmpty()) {
            return new Outcome.ArtefactNotFound();
        }
        Optional<BackupArtefactManifestRepository.BackupArtefactSummary> summary =
                manifestRepository.findSummary(artefactId);
        // Audit first: no byte is read before the retrieval is on record.
        try {
            retrievalRepository.record(UUID.randomUUID().toString(), artefactId, reason.strip(), DESTINATION_BROWSER,
                    actorFingerprint, ACTION_DOWNLOADED);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "retrieval audit row could not be written; download refused", e);
            return new Outcome.AuditRefused(String.valueOf(e.getMessage()));
        }
        try {
            InputStream stream = artefactStore.retrieve(new ArtefactRef(manifest.get().recoveryVolumePath()),
                    manifest.get().wrappedDataKey(), false);
            return new Outcome.Ready(artefactId, summary.map(s -> s.vendor()).orElse("unknown"),
                    summary.map(s -> s.plaintextBytes()).orElse(-1L),
                    summary.map(s -> s.createdAt()).orElse(Instant.EPOCH), stream);
        } catch (IOException e) {
            return new Outcome.IoFailure(String.valueOf(e.getMessage()));
        }
    }
}
