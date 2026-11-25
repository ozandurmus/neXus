package com.securityexpert.nexus.ui2.service.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ChangeState;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationArtefactRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationSummary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationOverride;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationReadKind;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.api.ConfigurationController;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationCollectService;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationQueryService;
import com.securityexpert.nexus.ui2.service.device.configuration.FakeDeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Sweep round 6 leak 1 regression: {@code GET /devices/{id}/configuration} masks VSYS/domain contexts, override
 * {@code element_path} name segments, and {@code panorama_source} names for {@code role:replay_viewer} while keeping
 * structural contexts, category grouping, {@code has_override}, and non-viewer responses intact.
 */
class ConfigurationControllerMaskingTest {

    private TopologyNamePseudonymizer names;
    private PrivacyMaskingResponseBodyAdvice advice;
    private FakeDeviceRepository devices;
    private FakeDeviceConfigurationRepository configRepo;
    private ConfigurationController controller;

    @BeforeEach
    void setUp() {
        byte[] key = String.join("-", "synth", "cfg", "mask", "key", "0032")
                .getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[32];
        System.arraycopy(key, 0, padded, 0, Math.min(key.length, padded.length));
        names = new TopologyNamePseudonymizer(padded);
        advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(padded), names);

        devices = new FakeDeviceRepository();
        devices.byId.put("dev-pan-1", new DeviceRecord("dev-pan-1", "gateway", "palo_alto", "manual",
                Instant.parse("2026-11-25T10:00:00Z"), true, DeviceEnrollmentState.ENROLLED, false, "cred-1"));
        configRepo = new FakeDeviceConfigurationRepository();

        RbacEvaluator rbac = mock(RbacEvaluator.class);
        when(rbac.evaluate(any(), any(), any())).thenReturn(
                new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty()));
        ConfigurationCollectService collect = new ConfigurationCollectService(
                devices,
                new JobAdmissionService(CapabilityRegistry.of(List.of()), id -> Optional.empty(), new NoopAdmission()));
        controller = new ConfigurationController(
                new ConfigurationQueryService(devices, configRepo),
                collect,
                new ActionRegistry(),
                rbac,
                mock(AuthzDecisionRepository.class),
                names);
    }

    @Test
    @SuppressWarnings("unchecked")
    void masksConfigurationContextsElementPathsAndPanoramaSourcesForReplayViewerOnly() {
        String rawVs = "vs-synth-payments";
        String rawObj = "obj-synth-paydb";
        String rawIface = "vl-synth-card";
        String rawTpl = "tpl-synth-edge";
        String rawDg = "dg-synth-cardholder";
        String rawStack = "stk-synth-branch";

        List<ConfigurationIndexEntry> index = List.of(
                new ConfigurationIndexEntry(rawVs, "address", Optional.of("local"), 3),
                new ConfigurationIndexEntry("network", "interface", Optional.of("tpl"), 2));
        List<ConfigurationOverride> overrides = List.of(
                new ConfigurationOverride(rawVs, "address", "address/" + rawObj, Optional.of("template:" + rawTpl)),
                new ConfigurationOverride("network", "interface", "interface/" + rawIface,
                        Optional.of("device-group:" + rawDg)),
                new ConfigurationOverride(rawVs, "interface", "interface/ethernet1/1.210",
                        Optional.of("template-stack:" + rawStack)));
        ConfigurationDeviationSummary deviation = new ConfigurationDeviationSummary(
                ConfigurationDeviationSummary.Status.COMPUTED,
                List.of(
                        new ConfigurationDeviationEntry(rawVs, "rulebase", Optional.of("dg"),
                                ConfigurationDeviationEntry.DeviationKind.RECOUNTED, 4, 5),
                        new ConfigurationDeviationEntry("network", "interface", Optional.of("tpl"),
                                ConfigurationDeviationEntry.DeviationKind.RECOUNTED, 1, 2)));

        ConfigurationRun primary = new ConfigurationRun(
                "run-pan-1", "dev-pan-1", "job-pan-1", Instant.parse("2026-11-25T12:00:00Z"),
                "palo_alto", ConfigurationReadKind.EFFECTIVE_RUNNING, true, "a".repeat(64), "a".repeat(64),
                512L, "art-pan-1", 0, Optional.empty(), ChangeState.CHANGED, index, overrides, Optional.of(deviation));
        configRepo.recordRun(primary,
                new ConfigurationArtefactRecord("art-pan-1", "dev-pan-1", "job-pan-1", "palo_alto",
                        "a".repeat(64), 512L, "a".repeat(64), 512L, "gzip", "v1", new byte[] {1}),
                "actor-1", "act-1");

        // 1. Replay viewer receives masked contexts, element_paths and panorama_sources while preserving shape
        MockHttpServletRequest viewerReq = new MockHttpServletRequest();
        viewerReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "aiview");
        viewerReq.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "sess-viewer");
        viewerReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);

        var viewerResp = controller.getDeviceConfiguration("dev-pan-1", viewerReq);
        assertThat(viewerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> viewerBody = (Map<String, Object>) advice.beforeBodyWrite(
                viewerResp.getBody(), null, null, null, new ServletServerHttpRequest(viewerReq), null);

        String serialized = viewerBody.toString();
        assertThat(serialized).doesNotContain(rawVs, rawObj, rawIface, rawTpl, rawDg, rawStack, "ethernet1", "1.210");

        List<Map<String, Object>> maskedIndex = (List<Map<String, Object>>) viewerBody.get("index");
        assertThat(maskedIndex).hasSize(2);
        assertThat((String) maskedIndex.get(0).get("context")).startsWith("VS-");
        assertThat(maskedIndex.get(0).get("has_override")).isEqualTo(true);
        assertThat(maskedIndex.get(1).get("context")).isEqualTo("network");
        assertThat(maskedIndex.get(1).get("has_override")).isEqualTo(true);

        List<Map<String, Object>> maskedOverrides = (List<Map<String, Object>>) viewerBody.get("overrides");
        assertThat(maskedOverrides).hasSize(3);
        assertThat(maskedOverrides.get(0).get("context")).isEqualTo(maskedIndex.get(0).get("context"));
        assertThat((String) maskedOverrides.get(0).get("element_path")).startsWith("address/ADDR-");
        assertThat((String) maskedOverrides.get(0).get("panorama_source")).startsWith("template:DOM-");

        assertThat(maskedOverrides.get(1).get("context")).isEqualTo("network");
        assertThat((String) maskedOverrides.get(1).get("element_path")).startsWith("interface/OBJ-");
        assertThat((String) maskedOverrides.get(1).get("panorama_source")).startsWith("device-group:DOM-");

        String multiSegPath = (String) maskedOverrides.get(2).get("element_path");
        assertThat(multiSegPath.split("/", -1)).hasSize(3);
        assertThat(multiSegPath).startsWith("interface/OBJ-");
        assertThat((String) maskedOverrides.get(2).get("panorama_source")).startsWith("template-stack:DOM-");

        Map<String, Object> maskedDev = (Map<String, Object>) viewerBody.get("change_deviation_summary");
        List<Map<String, Object>> devEntries = (List<Map<String, Object>>) maskedDev.get("entries");
        assertThat(devEntries.get(0).get("context")).isEqualTo(maskedIndex.get(0).get("context"));
        assertThat(devEntries.get(0).get("category")).isEqualTo("access_rules");
        assertThat(devEntries.get(1).get("context")).isEqualTo("network");
        assertThat(devEntries.get(1).get("category")).isEqualTo("interfaces");

        // 2. Non-viewer role receives exact raw values unchanged
        MockHttpServletRequest adminReq = new MockHttpServletRequest();
        adminReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "sec-admin");
        adminReq.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "sess-admin");
        adminReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, false);

        var adminResp = controller.getDeviceConfiguration("dev-pan-1", adminReq);
        Map<String, Object> adminBody = (Map<String, Object>) advice.beforeBodyWrite(
                adminResp.getBody(), null, null, null, new ServletServerHttpRequest(adminReq), null);
        List<Map<String, Object>> rawOverrides = (List<Map<String, Object>>) adminBody.get("overrides");
        assertThat(rawOverrides.get(0).get("context")).isEqualTo(rawVs);
        assertThat(rawOverrides.get(0).get("element_path")).isEqualTo("address/" + rawObj);
        assertThat(rawOverrides.get(0).get("panorama_source")).isEqualTo("template:" + rawTpl);
    }

    @Test
    void unexpectedShapesAndMissingPseudonymizerFailClosed() {
        assertThat(ConfigurationController.maskElementPath("zone", "unslashed-secret-zone", names))
                .startsWith("ZONE-")
                .doesNotContain("unslashed-secret-zone");
        assertThat(ConfigurationController.maskPanoramaSource("bare-panorama-label", names))
                .startsWith("DOM-")
                .doesNotContain("bare-panorama-label");
        assertThat(ConfigurationController.maskPanoramaSource("template:", names))
                .startsWith("DOM-");

        RbacEvaluator rbac = mock(RbacEvaluator.class);
        when(rbac.evaluate(any(), any(), any())).thenReturn(
                new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty()));
        ConfigurationCollectService collect = new ConfigurationCollectService(
                devices,
                new JobAdmissionService(CapabilityRegistry.of(List.of()), id -> Optional.empty(), new NoopAdmission()));
        ConfigurationController unconfigured = new ConfigurationController(
                new ConfigurationQueryService(devices, configRepo), collect, new ActionRegistry(), rbac,
                mock(AuthzDecisionRepository.class));

        MockHttpServletRequest viewerReq = new MockHttpServletRequest();
        viewerReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        assertThrows(IllegalStateException.class, () -> unconfigured.getDeviceConfiguration("dev-pan-1", viewerReq));
    }

    private static final class FakeDeviceRepository implements DeviceRepository {
        final Map<String, DeviceRecord> byId = new HashMap<>();

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.ofNullable(byId.get(deviceId));
        }

        @Override
        public Optional<EndpointRecord> findEndpoint(String endpointId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState,
                DeviceEnrollmentState toState, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DeviceSummaryRecord> listAll() {
            return List.of();
        }
    }

    private static final class NoopAdmission implements JobAdmissionRepository {
        @Override
        public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
                String targetDeviceId, String actionClassId, String actorFingerprint, String actionId) {
            return Optional.of(jobId);
        }

        @Override
        public Optional<String> createRequestedIfAbsentForRun(String jobId, String idempotencyKey, String capabilityId,
                String targetRunId, String actionClassId, String actorFingerprint, String actionId) {
            return Optional.of(jobId);
        }

        @Override
        public Optional<String> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }
    }
}
