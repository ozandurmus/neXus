package com.securityexpert.nexus.ui2.service.report;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactValidation;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;

/**
 * Writes a rendered report file into the existing encrypted artefact store (Reports R1 contract "Storage"): class
 * {@code report}, a fresh data key per file (the store's own envelope), at most {@link ReportRenderer#MAX_BYTES}, and
 * an {@code expires_at} 30 days out that the retention janitor honours. Returns the manifest row; the caller records it
 * in the same transaction as the run's SUCCEEDED state.
 */
public final class ReportArtefactWriter {

    public static final String RETENTION_TIER = "report_30d";
    static final String NO_HOSTNAME = "not_applicable";

    /** The service has no artefact store mounted; the run fails with {@code STORE_UNAVAILABLE}. */
    public static final class StoreUnavailable extends RuntimeException {
        public StoreUnavailable() {
            super("the service has no artefact store mounted (UI2_ARTEFACT_STORE_KEY_FILE / UI2_ARTEFACT_STORE_ROOT)");
        }
    }

    private final ArtefactStore store;

    public ReportArtefactWriter(ArtefactStore storeOrNull) {
        this.store = storeOrNull;
    }

    public boolean available() {
        return store != null;
    }

    public BackupArtefactManifestRecord write(UUID runId, byte[] bytes, Instant expiresAt) throws IOException {
        if (store == null) throw new StoreUnavailable();
        if (bytes.length > ReportRenderer.MAX_BYTES) throw new ReportRenderer.TooLarge(ReportRenderer.MAX_BYTES);
        ArtefactStore.ArtefactMetadata meta;
        try (ArtefactStore.ArtefactHandle handle = store.open(ArtefactClass.REPORT_DEVICE_ID, runId.toString(),
                ArtefactClass.REPORT_VENDOR, false)) {
            handle.sink().write(bytes);
            meta = handle.finish();
        }
        return new BackupArtefactManifestRecord(UUID.randomUUID().toString(), ArtefactClass.REPORT_DEVICE_ID,
                Optional.empty(), ArtefactClass.REPORT, ArtefactClass.REPORT_VENDOR, Optional.empty(), NO_HOSTNAME,
                meta.plaintextSha256(), meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(),
                meta.wrappedDataKey(), ArtefactValidation.reachedWithoutRestore(ArtefactValidation.NOT_APPLICABLE),
                RETENTION_TIER, Optional.of(expiresAt), meta.ref().value(), Optional.empty());
    }
}
