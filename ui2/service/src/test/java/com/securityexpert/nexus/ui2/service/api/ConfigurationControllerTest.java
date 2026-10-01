package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.ConfigurationCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ChangeState;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationArtefactRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationOverride;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationReadKind;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationCollectService;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationQueryService;
import com.securityexpert.nexus.ui2.service.device.configuration.FakeDeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.*;
import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * AC-2: {@code GET /devices/{id}/configuration}, {@code GET /devices/{id}/
 * configuration/text}, {@code POST /devices/{id}/configuration/collect} and
 * {@code GET /configuration} -- every shape and status code, including the
 * 404s and the 409 (CONFLICT) a device with no registered row produces on
 * collect. Mirrors {@code InventoryControllerTest}.
 */
class ConfigurationControllerTest {

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

    private static final class InMemoryAdmissionRepository implements JobAdmissionRepository {
        private final Map<String, String> jobsByIdempotencyKey = new HashMap<>();

        @Override
        public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
                String targetDeviceId, String actionClassId, String actorFingerprint, String actionId) {
            if (jobsByIdempotencyKey.containsKey(idempotencyKey)) {
                return Optional.empty();
            }
            jobsByIdempotencyKey.put(idempotencyKey, jobId);
            return Optional.of(jobId);
        }

        @Override
        public Optional<String> createRequestedIfAbsentForRun(String jobId, String idempotencyKey,
                String capabilityId, String targetRunId, String actionClassId, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Optional<String> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(jobsByIdempotencyKey.get(idempotencyKey));
        }
    }

    private static DeviceRecord device(String deviceId, String vendorHint) {
        return new DeviceRecord(deviceId, "gateway", vendorHint, "manual_registration", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-ref-1");
    }

    private static ConfigurationRun showConfigurationRun(String deviceId, Instant collectedAt) {
        ConfigurationOverride override = new ConfigurationOverride("physical", "arp", "arp/10.0.0.1", Optional.empty());
        ConfigurationIndexEntry index = new ConfigurationIndexEntry("physical", "arp", Optional.empty(), 1);
        return new ConfigurationRun("run-1", deviceId, "job-1", collectedAt, "check_point",
                ConfigurationReadKind.SHOW_CONFIGURATION, true, "hash-1", "hash-1", 100L, "artefact-1", 2,
                Optional.of("set hostname gw-a\n"), ChangeState.FIRST_RUN, List.of(index), List.of(override),
                Optional.empty());
    }

    /** Satisfies {@link ConfigurationController}'s constructor for the GET-only tests below, which never invoke it. */
    private static ConfigurationCollectService unusedCollectService() {
        CapabilityRegistry emptyRegistry = CapabilityRegistry.of(List.of());
        JobAdmissionService admissionService =
                new JobAdmissionService(emptyRegistry, deviceId -> Optional.empty(), new InMemoryAdmissionRepository());
        return new ConfigurationCollectService(new FakeDeviceRepository(), admissionService);
    }

    private static Capability configurationCapability(String capabilityId, String vendor) {
        CapabilityStep exec = new CapabilityStep(StepKind.EXEC, "clish", "show configuration", false,
                Optional.of(ActionClass.CLASS_0_READ), Optional.of("^.*$"), Optional.of(30));
        CapabilitySpec spec = new CapabilitySpec(capabilityId, vendor, "cp_gaia_gateway", TransportKind.SSH_EXEC,
                MaturityState.CAP_OFFLINE, List.of(exec), List.of(), "UNKNOWN", List.of(), false);
        GateRegistryPort gates = key -> List.of(new GateRow("gate_ok", vendor, "cp_gaia_gateway", "clish",
                "SSH_EXEC", "show configuration", ActionClass.CLASS_0_READ, SignOffState.SIGNED_OFF, 30, null, null,
                null, null, null, List.of(), "test"));
        return new CapabilityRegistryLoader(gates).load(spec);
    }

    private static ConfigurationController controllerWithRealCollectService(FakeDeviceRepository devices) {
        CapabilityRegistry registry = CapabilityRegistry.of(
                List.of(configurationCapability(ConfigurationCapabilityIds.CP_CONFIGURATION_COLLECT, "check_point")));
        DeviceEnrollmentReadPort enrollmentReadPort = deviceId -> devices.find(deviceId)
                .map(d -> new DeviceEnrollmentSnapshot(deviceId, d.enrollmentState(), d.disabled()));
        JobAdmissionService admissionService =
                new JobAdmissionService(registry, enrollmentReadPort, new InMemoryAdmissionRepository());
        ConfigurationCollectService collectService = new ConfigurationCollectService(devices, admissionService);
        ConfigurationQueryService queryService = new ConfigurationQueryService(devices, new FakeDeviceConfigurationRepository());
        return controller(queryService, collectService);
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-actor");
        request.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "synthetic-session");
        return request;
    }

    private static ConfigurationController controller(ConfigurationQueryService query, ConfigurationCollectService collect) {
        var rbac = mock(RbacEvaluator.class);
        when(rbac.evaluate(any(), any(), any())).thenReturn(new RbacEvaluator.Decision(
                AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty()));
        return new ConfigurationController(query, collect, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
    }

    @Test
    void textReadableUsesTheCallersRoleAndRecordsTheAffordanceDecision() {
        var devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        var query = new ConfigurationQueryService(devices, new FakeDeviceConfigurationRepository());
        var identities = mock(LocalIdentityResolver.class);
        var roles = mock(LocalRoleTokenResolver.class);
        var identity = mock(LocalCredentialRecord.class);
        when(identity.localIdentityId()).thenReturn("synthetic-identity");
        when(identities.resolve("synthetic-actor")).thenReturn(Optional.of(identity));
        var rbac = new RbacEvaluator(mock(RoleBindingRepository.class), mock(ActorAuthzStateRepository.class),
                null, identities, roles, null);
        var decisions = mock(AuthzDecisionRepository.class);
        var controller = new ConfigurationController(query, unusedCollectService(), new ActionRegistry(), rbac, decisions);
        assertEquals(ActionRegistry.DEVICE_CONFIGURATION_TEXT_READ,
                SecurityWebMvcConfigTestAccess.actionIdFor("GET /devices/*/configuration/text"));
        for (String role : List.of(RoleToken.REPLAY_VIEWER, RoleToken.ONBOARDING_ADMIN)) {
            var binding = mock(RoleBindingRecord.class);
            when(binding.bindingId()).thenReturn("synthetic-binding");
            when(roles.resolveBinding(eq("synthetic-identity"), anyString()))
                    .thenAnswer(call -> role.equals(call.getArgument(1)) ? Optional.of(binding) : Optional.empty());
            boolean permitted = RoleToken.ONBOARDING_ADMIN.equals(role);
            var response = controller.getDeviceConfiguration("device-1", request());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(permitted, response.getBody().get("text_readable"));
            assertEquals("no-store", response.getHeaders().getCacheControl());
            verify(decisions).insert(eq("synthetic-session"), eq("synthetic-actor"),
                    eq(ActionRegistry.DEVICE_CONFIGURATION_TEXT_READ), eq(Optional.of("device-1")),
                    eq(permitted ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED), any(), any(), any());
        }
    }

    // -- GET /devices/{id}/configuration -------------------------------------

    @Test
    void getDeviceConfigurationReturns404ForAnUnknownDevice() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        ConfigurationQueryService queryService = new ConfigurationQueryService(devices, new FakeDeviceConfigurationRepository());
        ConfigurationController controller = controller(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.getDeviceConfiguration("no-such-device", request());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().get("error"));
    }

    @Test
    void getDeviceConfigurationReturns200WithNullFieldsWhenNeverCollected() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        ConfigurationQueryService queryService = new ConfigurationQueryService(devices, new FakeDeviceConfigurationRepository());
        ConfigurationController controller = controller(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.getDeviceConfiguration("device-1", request());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals("device-1", body.get("device_id"));
        assertEquals(null, body.get("collected_at"));
        assertEquals(false, body.get("sanitized_text_available"));
        assertEquals(List.of(), body.get("index"));
        assertEquals(List.of(), body.get("overrides"));
        assertEquals(List.of(), body.get("supplementary_runs"));
    }

    @Test
    void getDeviceConfigurationReturnsThePrimaryRunShapedExactlyAsTheContract() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        FakeDeviceConfigurationRepository configRepository = new FakeDeviceConfigurationRepository();
        configRepository.recordRun(showConfigurationRun("device-1", Instant.parse("2026-09-14T12:00:00Z")),
                new ConfigurationArtefactRecord("artefact-1", "device-1", "job-1", "check_point", "h", 1L, "h", 1L,
                        "none", "v1", new byte[] {1, 2, 3}),
                "actor", "action-1");
        ConfigurationQueryService queryService = new ConfigurationQueryService(devices, configRepository);
        ConfigurationController controller = controller(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.getDeviceConfiguration("device-1", request());

        Map<String, Object> body = response.getBody();
        assertEquals("2026-09-14T12:00:00Z", body.get("collected_at"));
        assertEquals("check_point", body.get("vendor"));
        assertEquals(ConfigurationReadKind.SHOW_CONFIGURATION, body.get("read_kind"));
        assertEquals(2, body.get("withheld_line_count"));
        assertEquals(true, body.get("sanitized_text_available"));
        assertEquals(null, body.get("change_deviation_summary"), "first_run carries no deviation summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> index = (List<Map<String, Object>>) body.get("index");
        assertEquals(1, index.size());
        assertEquals(true, index.get(0).get("has_override"), "AC-2: the arp category carries the recorded override");
    }

    @Test
    void getDeviceConfigurationExposesDeviationSummaryForChangedRun() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        FakeDeviceConfigurationRepository configRepository = new FakeDeviceConfigurationRepository();

        var devEntry = new com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry(
                "physical", "arp",
                java.util.Optional.empty(),
                com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry.DeviationKind.RECOUNTED,
                1, 2);
        var summary = new com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationSummary(
                com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationSummary.Status.COMPUTED,
                List.of(devEntry));
        ConfigurationRun changedRun = new ConfigurationRun("run-2", "device-1", "job-2",
                Instant.parse("2026-09-14T13:00:00Z"), "check_point",
                ConfigurationReadKind.SHOW_CONFIGURATION, true, "hash-2", "hash-2", 100L, "artefact-2", 0,
                Optional.of("text"), ChangeState.CHANGED, List.of(), List.of(), Optional.of(summary));

        configRepository.recordRun(changedRun,
                new ConfigurationArtefactRecord("artefact-2", "device-1", "job-2", "check_point", "h2", 1L, "h2", 1L,
                        "none", "v1", new byte[] {1, 2, 3}),
                "actor", "action-2");
        ConfigurationQueryService queryService = new ConfigurationQueryService(devices, configRepository);
        ConfigurationController controller = controller(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.getDeviceConfiguration("device-1", request());

        Map<String, Object> body = response.getBody();
        assertEquals("changed", body.get("change_state"));
        @SuppressWarnings("unchecked")
        Map<String, Object> deviationSummary = (Map<String, Object>) body.get("change_deviation_summary");
        assertEquals("computed", deviationSummary.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) deviationSummary.get("entries");
        assertEquals(1, entries.size());
        assertEquals("physical", entries.get(0).get("context"));
        assertEquals("arp", entries.get(0).get("section"));
        assertEquals("recounted", entries.get(0).get("kind"));
        assertEquals(1, entries.get(0).get("old_count"));
        assertEquals(2, entries.get(0).get("new_count"));
    }

    // -- GET /devices/{id}/configuration/text --------------------------------

    @Test
    void getDeviceConfigurationTextReturns404WhenThereIsNoSanitizedTextYet() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        ConfigurationQueryService queryService = new ConfigurationQueryService(devices, new FakeDeviceConfigurationRepository());
        ConfigurationController controller = controller(queryService, unusedCollectService());

        ResponseEntity<String> response = controller.getDeviceConfigurationText("device-1");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getDeviceConfigurationTextReturns200WithTheSanitizedText() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        FakeDeviceConfigurationRepository configRepository = new FakeDeviceConfigurationRepository();
        configRepository.recordRun(showConfigurationRun("device-1", Instant.parse("2026-09-14T12:00:00Z")),
                new ConfigurationArtefactRecord("artefact-1", "device-1", "job-1", "check_point", "h", 1L, "h", 1L,
                        "none", "v1", new byte[] {1, 2, 3}),
                "actor", "action-1");
        ConfigurationQueryService queryService = new ConfigurationQueryService(devices, configRepository);
        ConfigurationController controller = controller(queryService, unusedCollectService());

        ResponseEntity<String> response = controller.getDeviceConfigurationText("device-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("set hostname gw-a\n", response.getBody());
    }

    // -- POST /devices/{id}/configuration/collect ----------------------------

    @Test
    void collectReturns202WithJobIdOnAdmission() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.byId.put("device-1", device("device-1", "check_point"));
        ConfigurationController controller = controllerWithRealCollectService(devices);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1");

        ResponseEntity<Map<String, Object>> response =
                controller.collect("device-1", new ConfigurationController.CollectRequest("nonce-1"), servletRequest);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertTrue(response.getBody().containsKey("job_id"));
    }

    /** AC-2: the 409 a device with no registered row (and so no admissible capability) produces. */
    @Test
    void collectReturns409AdmissionRefusedForAnUnknownDevice() {
        ConfigurationController controller = controllerWithRealCollectService(new FakeDeviceRepository());
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1");

        ResponseEntity<Map<String, Object>> response = controller.collect("no-such-device", null, servletRequest);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("ADMISSION_REFUSED", response.getBody().get("error"));
        assertEquals("DEVICE_NOT_FOUND", response.getBody().get("code"));
    }

    // -- GET /configuration ---------------------------------------------------

    @Test
    void listConfigurationsReturns200WithEveryDevice() {
        FakeDeviceRepository devices = new FakeDeviceRepository();
        devices.summaries = List.of(new DeviceSummaryRecord("device-1", "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        ConfigurationQueryService queryService = new ConfigurationQueryService(devices, new FakeDeviceConfigurationRepository());
        ConfigurationController controller = controller(queryService, unusedCollectService());

        ResponseEntity<Map<String, Object>> response = controller.listConfigurations();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devicesBody = (List<Map<String, Object>>) response.getBody().get("devices");
        assertEquals(1, devicesBody.size());
        assertEquals("device-1", devicesBody.get(0).get("device_id"));
    }
}
