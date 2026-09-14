package com.securityexpert.nexus.ui2.worker.configuration;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationArtefactRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationOverride;

/**
 * One read kind's collected output, before {@code
 * worker.configuration.ConfigurationJobExecutor} wraps it into a
 * persistence-ready {@code ConfigurationRun} (run id, job id, collected-at
 * and change-state are all filled in at that layer, not here -- mirrors
 * {@code worker.inventory.InventoryResult.Completed}'s own division of
 * labor).
 */
public record ConfigurationRunData(
        String readKind,
        boolean primary,
        String canonicalHash,
        int withheldLineCount,
        Optional<String> sanitizedText,
        List<ConfigurationIndexEntry> index,
        List<ConfigurationOverride> overrides,
        ConfigurationArtefactRecord artefact) {

    public String rawHash() {
        return artefact.plaintextSha256();
    }

    public long rawBytes() {
        return artefact.plaintextBytes();
    }
}
