package com.securityexpert.nexus.ui2.service.device.configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationReadKind;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRunView;
import com.securityexpert.nexus.ui2.persistence.device.configuration.DeviceConfigurationRepository;

/**
 * {@code GET /devices/{id}/configuration}, {@code GET /devices/{id}/
 * configuration/text} and {@code GET /configuration} (WORKER.md
 * "Service"): a thin read composition over {@link
 * DeviceConfigurationRepository}, mirroring {@code
 * service.device.inventory.InventoryQueryService}'s own shape.
 */
public final class ConfigurationQueryService {

    public sealed interface DeviceConfigurationOutcome {
        record Found(String deviceId, ConfigurationRunView view) implements DeviceConfigurationOutcome {
        }

        record NotFound() implements DeviceConfigurationOutcome {
        }
    }

    public record DeviceListEntry(String deviceId, Optional<String> hostname, String vendor,
            Optional<ConfigurationRun> latestRun) {
    }

    private final DeviceRepository deviceRepository;
    private final DeviceConfigurationRepository deviceConfigurationRepository;

    public ConfigurationQueryService(DeviceRepository deviceRepository,
            DeviceConfigurationRepository deviceConfigurationRepository) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.deviceConfigurationRepository =
                Objects.requireNonNull(deviceConfigurationRepository, "deviceConfigurationRepository");
    }

    /** 404 when {@code deviceId} is unknown; 200 with an empty view when it has no run yet. */
    public DeviceConfigurationOutcome deviceConfiguration(String deviceId) {
        if (deviceRepository.find(deviceId).isEmpty()) {
            return new DeviceConfigurationOutcome.NotFound();
        }
        return new DeviceConfigurationOutcome.Found(deviceId, deviceConfigurationRepository.findRunView(deviceId));
    }

    /** {@code GET /devices/{id}/configuration/text}: the Check Point sanitized text, or empty (404) for any other case. */
    public Optional<String> sanitizedText(String deviceId) {
        Optional<DeviceRecord> device = deviceRepository.find(deviceId);
        if (device.isEmpty() || !"check_point".equals(device.get().vendorHint())) {
            return Optional.empty();
        }
        return deviceConfigurationRepository.findLatestRun(deviceId, ConfigurationReadKind.SHOW_CONFIGURATION)
                .flatMap(ConfigurationRun::sanitizedText);
    }

    /** {@code GET /configuration}: every device, its vendor, and its latest primary run if any. */
    public List<DeviceListEntry> listDevices() {
        List<DeviceSummaryRecord> devices = deviceRepository.listAll();
        List<String> deviceIds = devices.stream().map(DeviceSummaryRecord::deviceId).toList();
        Map<String, ConfigurationRun> latestByDeviceId = new LinkedHashMap<>();
        for (ConfigurationRun run : deviceConfigurationRepository.findLatestRuns(deviceIds)) {
            latestByDeviceId.put(run.deviceId(), run);
        }
        return devices.stream()
                .map(device -> new DeviceListEntry(device.deviceId(), device.observedHostname(), device.vendorHint(),
                        Optional.ofNullable(latestByDeviceId.get(device.deviceId()))))
                .toList();
    }
}
