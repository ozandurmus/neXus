package com.securityexpert.nexus.ui2.worker.backup.retention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactDeletionRequests;

/**
 * Carries out the requests the Backups screen records to delete a backup whose device no longer exists (V70; PO,
 * 2026-09-24). The worker owns the artefact store (BK-17), so it removes the encrypted file, then the manifest rows in
 * one audited transaction under the requester's fingerprint. A request whose device has come back, or whose artefact
 * is already gone, is closed with that outcome and nothing is deleted.
 */
public final class OrphanArtefactPurger {

    private static final System.Logger LOG = System.getLogger(OrphanArtefactPurger.class.getName());

    private final BackupArtefactDeletionRequests requests;
    private final Path storeRoot;

    public OrphanArtefactPurger(BackupArtefactDeletionRequests requests, Path storeRoot) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot").toAbsolutePath().normalize();
    }

    public void runOnce() {
        for (BackupArtefactDeletionRequests.Pending p : requests.open()) {
            if (p.recoveryVolumePath().isEmpty()) {
                requests.close(p.artefactId(), p.requester(), "artefact_missing");
                continue;
            }
            if (p.deviceExists()) {
                requests.close(p.artefactId(), p.requester(), "device_exists");
                continue;
            }
            Path file = storeRoot.resolve(p.recoveryVolumePath().get()).normalize();
            if (!file.startsWith(storeRoot)) {
                requests.close(p.artefactId(), p.requester(), "file_remove_failed");
                continue;
            }
            try {
                Files.deleteIfExists(file);
                try {
                    Files.deleteIfExists(file.getParent());
                } catch (IOException notEmpty) {
                    // the device's directory still holds other backups
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "[ORPHAN_PURGE] {0}: file not removed ({1})", p.artefactId(), e.getClass().getSimpleName());
                requests.close(p.artefactId(), p.requester(), "file_remove_failed");
                continue;
            }
            requests.removeRows(p.artefactId(), p.requester());
            LOG.log(System.Logger.Level.INFO, "[ORPHAN_PURGE] {0} removed", p.artefactId());
        }
    }
}
