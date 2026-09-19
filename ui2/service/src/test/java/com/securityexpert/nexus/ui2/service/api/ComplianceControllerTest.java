package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.compliance.ComplianceService;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationQueryService;
import com.securityexpert.nexus.ui2.service.device.configuration.FakeDeviceConfigurationRepository;

class ComplianceControllerTest {

    static class FakeDeviceRepository implements DeviceRepository {
        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.empty();
        }

        @Override
        public Optional<EndpointRecord> findEndpoint(String endpointId) {
            return Optional.empty();
        }

        @Override
        public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) {
            return Optional.empty();
        }

        @Override
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            return "dev-1";
        }

        @Override
        public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState, DeviceEnrollmentState toState, String actorFingerprint, String actionId) {
            return true;
        }

        @Override
        public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
            return true;
        }

        @Override
        public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
            return true;
        }

        @Override
        public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint, String actionId) {
            return true;
        }

        @Override
        public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) {
            return Optional.empty();
        }

        @Override
        public List<DeviceSummaryRecord> listAll() {
            return List.of();
        }
    }

    @Test
    void testComplianceOverviewAndControlsEndpoints() {
        FakeDeviceRepository fakeRepo = new FakeDeviceRepository();
        FakeDeviceConfigurationRepository fakeConfigRepo = new FakeDeviceConfigurationRepository();
        ConfigurationQueryService queryService = new ConfigurationQueryService(fakeRepo, fakeConfigRepo);
        ComplianceService complianceService = new ComplianceService(queryService, fakeRepo);
        ComplianceController controller = new ComplianceController(complianceService);

        ResponseEntity<Map<String, Object>> overviewRes = controller.getOverview();
        assertEquals(HttpStatus.OK, overviewRes.getStatusCode());
        assertNotNull(overviewRes.getBody());
        assertTrue(overviewRes.getBody().containsKey("total_firewalls"));
        assertTrue(overviewRes.getBody().containsKey("frameworks"));

        ResponseEntity<Map<String, Object>> controlsRes = controller.getControls();
        assertEquals(HttpStatus.OK, controlsRes.getStatusCode());
        assertNotNull(controlsRes.getBody());
        assertTrue(controlsRes.getBody().containsKey("controls"));
    }
}
