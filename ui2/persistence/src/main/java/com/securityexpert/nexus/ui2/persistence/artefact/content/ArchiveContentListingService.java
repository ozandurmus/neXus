package com.securityexpert.nexus.ui2.persistence.artefact.content;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.Entry;

/**
 * Lists a just-stored backup archive's entries into V41's tables. Best
 * effort by design: the backup itself is already recorded and verified
 * when this runs, so a listing that fails (not a tar, truncated, too many
 * entries) is recorded as FAILED with its reason and never fails the job.
 */
public final class ArchiveContentListingService {

    private static final Logger LOG = Logger.getLogger(ArchiveContentListingService.class.getName());

    private final ArtefactStore artefactStore;
    private final BackupArtefactEntryRepository entryRepository;

    public ArchiveContentListingService(ArtefactStore artefactStore, BackupArtefactEntryRepository entryRepository) {
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.entryRepository = Objects.requireNonNull(entryRepository, "entryRepository");
    }

    /** @return the number of entries listed, or -1 when the listing was recorded as FAILED */
    public int list(String artefactId, ArtefactRef ref, byte[] wrappedDataKey) {
        try (InputStream decrypted = artefactStore.retrieve(ref, wrappedDataKey, false)) {
            List<Entry> entries = TarEntryLister.list(decrypted);
            entryRepository.recordListed(artefactId, entries);
            LOG.info("[BACKUP] content listing recorded: " + entries.size() + " entries");
            return entries.size();
        } catch (IOException | RuntimeException e) {
            String reason = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            LOG.log(Level.WARNING, "[BACKUP] content listing failed; recorded as FAILED: " + reason);
            try {
                entryRepository.recordFailed(artefactId, reason);
            } catch (RuntimeException persistFailure) {
                LOG.log(Level.WARNING, "[BACKUP] content listing failure could not be recorded either", persistFailure);
            }
            return -1;
        }
    }
}
