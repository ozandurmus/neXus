package com.securityexpert.nexus.ui2.service.device.backup;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.content.ArchiveContentListingService;

/** V41 "List now": re-runs the content listing for one artefact on the service. */
public final class BackupRelistService {

    public sealed interface Outcome {
        record Listed(int entryCount) implements Outcome {
        }

        record ListingFailed() implements Outcome {
        }

        record ArtefactNotFound() implements Outcome {
        }

        record StoreUnavailable() implements Outcome {
        }
    }

    private final BackupArtefactManifestRepository manifestRepository;
    private final ArchiveContentListingService lister; // null without a store

    public BackupRelistService(BackupArtefactManifestRepository manifestRepository, ArchiveContentListingService lister) {
        this.manifestRepository = Objects.requireNonNull(manifestRepository, "manifestRepository");
        this.lister = lister;
    }

    public Outcome relist(String artefactId) {
        if (lister == null) {
            return new Outcome.StoreUnavailable();
        }
        Optional<BackupArtefactManifestRepository.RetrievalManifest> manifest = manifestRepository.findForRetrieval(artefactId);
        if (manifest.isEmpty()) {
            return new Outcome.ArtefactNotFound();
        }
        int count = lister.list(artefactId, new ArtefactRef(manifest.get().recoveryVolumePath()), manifest.get().wrappedDataKey());
        return count < 0 ? new Outcome.ListingFailed() : new Outcome.Listed(count);
    }
}
