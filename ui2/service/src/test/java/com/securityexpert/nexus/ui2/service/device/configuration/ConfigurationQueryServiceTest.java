package com.securityexpert.nexus.ui2.service.device.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ChangeState;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationArtefactRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationReadKind;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * AC-2: {@code GET /devices/{id}/configuration}, {@code GET /devices/{id}/
 * configuration/text} and {@code GET /configuration} (WORKER.md "Service").
 * Mirrors {@code InventoryQueryServiceTest}.
 */
class ConfigurationQueryServiceTest {

    private static final class FakeDeviceRepository implements DeviceRepository {
        final Map<String, DeviceRecord> byId = new HashMap<>();
        List<DeviceSummaryRecord> summaries = List.of();

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.ofNullable(byId.get(deviceId));
        }

        @Override
        public Optional<EndpointRecord> findEndpoint(String endpointId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState,
                DeviceEnrollmentState toState, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public List<DeviceSummaryRecord> listAll() {
            return summaries;
        }
    }

    private static DeviceRecord device(String deviceId, String vendorHint) {
        return new DeviceRecord(deviceId, "gateway", vendorHint, "manual_registration", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-ref-1");
    }

    private static ConfigurationRun showConfigurationRun(String deviceId, Instant collectedAt, String sanitizedText) {
        return new ConfigurationRun("run-1", deviceId, "job-1", collectedAt, "check_point",
                ConfigurationReadKind.SHOW_CONFIGURATION, true, "hash-1", "hash-1", 100L, "artefact-1", 0,
                Optional.of(sanitizedText), ChangeState.FIRST_RUN, List.of(), List.of(), Optional.empty());
    }

    @Test
    void deviceConfigurationReturnsNotFoundForAnUnknownDevice() {
        ConfigurationQueryService service =
                new ConfigurationQueryService(new FakeDeviceRepository(), new FakeDeviceConfigurationRepository());

        var outcome = service.deviceConfiguration("no-such-device");

        assertTrue(outcome instanceof ConfigurationQueryService.DeviceConfigurationOutcome.NotFound);
    }

    @Test
    void deviceConfigurationReturnsAnEmptyViewWhenTheDeviceHasNeverBeenCollected() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        ConfigurationQueryService service = new ConfigurationQueryService(devices, new FakeDeviceConfigurationRepository());

        var outcome = service.deviceConfiguration("device-1");

        assertTrue(outcome instanceof ConfigurationQueryService.DeviceConfigurationOutcome.Found);
        var found = (ConfigurationQueryService.DeviceConfigurationOutcome.Found) outcome;
        assertTrue(found.view().primaryRun().isEmpty());
        assertEquals(List.of(), found.view().supplementaryRuns());
    }

    @Test
    void deviceConfigurationReturnsThePrimaryRunOnceOneWasRecorded() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        FakeDeviceConfigurationRepository configRepository = new FakeDeviceConfigurationRepository();
        Instant collectedAt = Instant.parse("2026-09-14T12:00:00Z");
        configRepository.recordRun(showConfigurationRun("device-1", collectedAt, "set hostname gw-a\n"),
                new ConfigurationArtefactRecord("artefact-1", "device-1", "job-1", "check_point", "h", 1L, "h", 1L,
                        "none", "v1", new byte[] {1, 2, 3}),
                "actor", "action-1");
        ConfigurationQueryService service = new ConfigurationQueryService(devices, configRepository);

        var outcome = service.deviceConfiguration("device-1");

        var found = (ConfigurationQueryService.DeviceConfigurationOutcome.Found) outcome;
        assertTrue(found.view().primaryRun().isPresent());
        assertEquals("set hostname gw-a\n", found.view().primaryRun().get().sanitizedText().orElseThrow());
    }

    @Test
    void sanitizedTextIsEmptyForAnUnknownDevice() {
        ConfigurationQueryService service =
                new ConfigurationQueryService(new FakeDeviceRepository(), new FakeDeviceConfigurationRepository());

        assertTrue(service.sanitizedText("no-such-device").isEmpty());
    }

    @Test
    void sanitizedTextIsEmptyForANonCheckPointDevice() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "palo_alto"));
        ConfigurationQueryService service = new ConfigurationQueryService(devices, new FakeDeviceConfigurationRepository());

        assertTrue(service.sanitizedText("device-1").isEmpty());
    }

    @Test
    void sanitizedTextReturnsTheCheckPointSanitizedViewOnceRecorded() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        FakeDeviceConfigurationRepository configRepository = new FakeDeviceConfigurationRepository();
        configRepository.recordRun(
                showConfigurationRun("device-1", Instant.parse("2026-09-14T12:00:00Z"), "set hostname gw-a\n"),
                new ConfigurationArtefactRecord("artefact-1", "device-1", "job-1", "check_point", "h", 1L, "h", 1L,
                        "none", "v1", new byte[] {1, 2, 3}),
                "actor", "action-1");
        ConfigurationQueryService service = new ConfigurationQueryService(devices, configRepository);

        assertEquals(Optional.of("set hostname gw-a\n"), service.sanitizedText("device-1"));
    }

    @Test
    void listDevicesReturnsEveryDeviceWithItsLatestPrimaryRunWhenAny() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.summaries = List.of(
                new DeviceSummaryRecord("device-1", "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
                        Optional.of("fw-a"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                new DeviceSummaryRecord("device-2", "gateway", "palo_alto", DeviceEnrollmentState.ENROLLED, Optional.of("fw-b"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        FakeDeviceConfigurationRepository configRepository = new FakeDeviceConfigurationRepository();
        configRepository.recordRun(
                showConfigurationRun("device-1", Instant.parse("2026-09-14T12:00:00Z"), "set hostname gw-a\n"),
                new ConfigurationArtefactRecord("artefact-1", "device-1", "job-1", "check_point", "h", 1L, "h", 1L,
                        "none", "v1", new byte[] {1, 2, 3}),
                "actor", "action-1");
        ConfigurationQueryService service = new ConfigurationQueryService(devices, configRepository);

        List<ConfigurationQueryService.DeviceListEntry> entries = service.listDevices();

        assertEquals(2, entries.size());
        var device1Entry = entries.stream().filter(e -> e.deviceId().equals("device-1")).findFirst().orElseThrow();
        assertTrue(device1Entry.latestRun().isPresent());
        var device2Entry = entries.stream().filter(e -> e.deviceId().equals("device-2")).findFirst().orElseThrow();
        assertTrue(device2Entry.latestRun().isEmpty(), "device-2 has never been collected");
    }
}
