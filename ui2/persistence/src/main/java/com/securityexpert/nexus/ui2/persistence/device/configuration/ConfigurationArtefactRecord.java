package com.securityexpert.nexus.ui2.persistence.device.configuration;

/**
 * The {@code configuration_artefact} row a {@link ConfigurationRun}'s
 * {@code artefactRef} points at (migration V16, C7 section 4 minimum
 * viable form) -- a thin projection of {@code
 * com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore.
 * ArtefactMetadata} plus the device/job/vendor context the table also
 * carries. Passed to {@link DeviceConfigurationRepository#recordRun}
 * alongside the run so both rows write in one transaction (the run row's
 * foreign key requires the artefact row to exist first).
 */
public record ConfigurationArtefactRecord(
        String artefactRef,
        String deviceId,
        String jobId,
        String vendor,
        String plaintextSha256,
        long plaintextBytes,
        String ciphertextSha256,
        long ciphertextBytes,
        String compression,
        String keyId) {
}
