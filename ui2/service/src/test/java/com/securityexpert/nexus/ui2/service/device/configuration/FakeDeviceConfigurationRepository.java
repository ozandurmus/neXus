package com.securityexpert.nexus.ui2.service.device.configuration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationArtefactRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRunView;
import com.securityexpert.nexus.ui2.persistence.device.configuration.DeviceConfigurationRepository;

/**
 * Test-only in-memory {@link DeviceConfigurationRepository} fake; the real
 * bean is {@code JooqDeviceConfigurationRepository}. Mirrors {@code
 * service.device.inventory.FakeDeviceInventoryRepository}'s own shape,
 * including {@link #findRunView}'s "latest non-primary run per read kind"
 * semantics, which {@code JooqDeviceConfigurationRepository#findRunView}
 * fixes.
 */
public final class FakeDeviceConfigurationRepository implements DeviceConfigurationRepository {

    private final Map<String, List<ConfigurationRun>> runsByDeviceId = new ConcurrentHashMap<>();

    @Override
    public void recordRun(ConfigurationRun run, ConfigurationArtefactRecord artefact, String actorFingerprint,
            String actionId) {
        runsByDeviceId.computeIfAbsent(run.deviceId(), key -> new ArrayList<>()).add(run);
    }

    @Override
    public Optional<ConfigurationRun> findLatestRun(String deviceId, String readKind) {
        return runsFor(deviceId).stream()
                .filter(run -> run.readKind().equals(readKind))
                .max(Comparator.comparing(ConfigurationRun::collectedAt));
    }

    @Override
    public List<ConfigurationRun> findLatestRuns(List<String> deviceIds) {
        List<ConfigurationRun> result = new ArrayList<>();
        for (String deviceId : deviceIds) {
            latestPrimary(deviceId).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    @Override
    public ConfigurationRunView findRunView(String deviceId) {
        Optional<ConfigurationRun> primary = latestPrimary(deviceId);
        Map<String, ConfigurationRun> latestSupplementaryByReadKind = new LinkedHashMap<>();
        for (ConfigurationRun run : runsFor(deviceId)) {
            if (run.primary()) {
                continue;
            }
            ConfigurationRun existing = latestSupplementaryByReadKind.get(run.readKind());
            if (existing == null || run.collectedAt().isAfter(existing.collectedAt())) {
                latestSupplementaryByReadKind.put(run.readKind(), run);
            }
        }
        return new ConfigurationRunView(deviceId, primary, List.copyOf(latestSupplementaryByReadKind.values()));
    }

    private Optional<ConfigurationRun> latestPrimary(String deviceId) {
        return runsFor(deviceId).stream()
                .filter(ConfigurationRun::primary)
                .max(Comparator.comparing(ConfigurationRun::collectedAt));
    }

    private List<ConfigurationRun> runsFor(String deviceId) {
        return runsByDeviceId.getOrDefault(deviceId, List.of());
    }
}
