package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.util.List;
import java.util.Optional;

/**
 * {@code device_configuration_run}/{@code device_configuration_index}/
 * {@code device_configuration_override} persistence (migration V16, 14G
 * CG-1..CG-10). Mirrors {@code
 * com.securityexpert.nexus.ui2.persistence.device.inventory.
 * DeviceInventoryRepository}'s own shape.
 */
public interface DeviceConfigurationRepository {

    /**
     * Writes {@code artefact}, {@code run}, and every child index/override
     * row in one transaction (WORKER.md "recordRun with index rows in one
     * transaction"). Never called with a partially-assembled run.
     */
    void recordRun(ConfigurationRun run, ConfigurationArtefactRecord artefact, String actorFingerprint,
            String actionId);

    /** The newest run for one (device, read_kind) pair, or empty if none was ever recorded -- CG-10's change-detection comparison uses this per read kind. */
    Optional<ConfigurationRun> findLatestRun(String deviceId, String readKind);

    /** The newest primary-read-kind run per device, for every device id in {@code deviceIds} that has at least one recorded run -- the Configuration screen's device list. */
    List<ConfigurationRun> findLatestRuns(List<String> deviceIds);

    /** The full detail view for one device: its primary run plus every supplementary run recorded alongside it. */
    ConfigurationRunView findRunView(String deviceId);
}
