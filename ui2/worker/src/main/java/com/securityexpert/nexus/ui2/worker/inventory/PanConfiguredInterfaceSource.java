package com.securityexpert.nexus.ui2.worker.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.configuration.LatestEffectiveRunningReader;

/**
 * PO 2026-10-24: the stored effective-running configuration a PAN-OS 12 firewall's interfaces are derived from (no device
 * command). Empty when the device has no stored effective-running run; an {@link IOException} when one is recorded but
 * cannot be read back.
 */
@FunctionalInterface
public interface PanConfiguredInterfaceSource {

    /** The caller closes the stream. */
    Optional<InputStream> openLatestEffectiveRunning(String deviceId) throws IOException;

    /** No configuration store wired: every PAN-OS 12 firewall's interfaces are UNKNOWN ("no stored configuration"). */
    PanConfiguredInterfaceSource NONE = deviceId -> Optional.empty();

    /** The newest stored effective-running document, decrypted (and decompressed) from the artefact store. */
    static PanConfiguredInterfaceSource stored(LatestEffectiveRunningReader reader, ArtefactStore store) {
        return deviceId -> {
            Optional<LatestEffectiveRunningReader.Stored> latest = reader.latest(deviceId);
            if (latest.isEmpty()) {
                return Optional.empty();
            }
            LatestEffectiveRunningReader.Stored stored = latest.get();
            return Optional.of(store.retrieve(new ArtefactRef(stored.artefactRef()), stored.wrappedDataKey(), stored.gzip()));
        };
    }
}
