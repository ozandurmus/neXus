package com.securityexpert.nexus.ui2.service.supportbundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.privacy.MaskingDeviceDirectory;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * V274 controller and masking checks: catalogue wait flags, request-again payload fields, bidirectional retry links,
 * closed-vocabulary {@code not_started_summary}, and {@code aiview} withholding/refusal invariants.
 */
class SupportBundleControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void exposesCatalogueWaitStateRetryLinksAndNotStartedSummaryWhileKeepingAiviewMaskedAndReadOnly() {
        Instant windowInstant = Instant.parse("2026-11-25T21:15:00Z"); // 00:15 Istanbul -> inside 00:00-01:00 window
        DeviceRepository devices = mock(DeviceRepository.class);
        when(devices.find("dev-cp")).thenReturn(Optional.of(new DeviceRecord(
                "dev-cp", "gateway", "check_point", "manual", windowInstant, false, DeviceEnrollmentState.ENROLLED, false, "cred")));
        JobAdmissionService admission = mock(JobAdmissionService.class);
        when(admission.submit(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AdmissionResult.Admitted("job-again"));

        JooqSupportBundleStore store = mock(JooqSupportBundleStore.class);
        when(store.isSupportBundleModuleDraining()).thenReturn(true);
        SupportBundleDownloadService downloads = mock(SupportBundleDownloadService.class);
        when(downloads.pseudonym("dev-cp")).thenReturn("FW-TANGO-04");
        JooqSupportBundleUploadStore uploadStore = mock(JooqSupportBundleUploadStore.class);

        SupportBundleService service = new SupportBundleService(devices, admission, store,
                Clock.fixed(windowInstant, ZoneOffset.UTC), new BusinessHours(LocalTime.of(8, 0), LocalTime.of(20, 0)));
        SupportBundleController controller = new SupportBundleController(service, downloads, store, uploadStore);

        // 1. Catalogue reports active drain and open collection window
        var cat = controller.catalogue();
        assertThat(cat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cat.getBody()).containsEntry("module_draining", true)
                .containsEntry("collection_window_open", true)
                .containsEntry("collection_window_ends_at", "2026-11-25T22:00:00Z");

        // 2. Detail and list expose retry links + closed summary, withholding sensitive fields for aiview even on a cold pod
        UUID origId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID retryId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        Instant expiredAt = Instant.parse("2026-11-25T23:46:00Z");
        RequestRow orig = new RequestRow(origId, "dev-cp", "check_point", "cp_gaia_gateway", "NOW",
                expiredAt.minus(Duration.ofHours(3)), "SR-SECRET-99", "operator reason secret", "actor-secret",
                expiredAt.minus(Duration.ofHours(3)), null, expiredAt, expiredAt.plus(Duration.ofDays(14)),
                null, null, 0, null, null, "job-old", "{}", "FAILED", "NOT_STARTED_DEVICE_BUSY",
                null, false, List.of("MODULE_DRAIN", "COLLECTION_WINDOW"), "RETRY_SCHEDULED");
        ItemRow item = new ItemRow(UUID.randomUUID(), origId, 1, "cp_cpinfo", 2, "cma-secret-domain",
                "CANCELLED", "NOT_STARTED_DEVICE_BUSY", null, null, null, null, null, "none", List.of(), null, expiredAt);
        when(store.find(origId)).thenReturn(Optional.of(orig));
        when(store.items(origId)).thenReturn(List.of(item));
        when(store.followUps(List.of(origId))).thenReturn(Map.of(origId, retryId));
        when(uploadStore.cleanupStatuses(List.of(origId))).thenReturn(Map.of());

        byte[] key = "0123456789".repeat(4).substring(0, 32).getBytes(StandardCharsets.UTF_8);
        TopologyNamePseudonymizer coldTopo = new TopologyNamePseudonymizer(key);
        coldTopo.setDeviceDirectory(() -> List.<MaskingDeviceDirectory.Entry>of());
        PrivacyMaskingResponseBodyAdvice advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), coldTopo);

        MockHttpServletRequest viewerReq = new MockHttpServletRequest();
        viewerReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        var viewerResp = controller.get(origId.toString(), viewerReq);
        Map<String, Object> masked = (Map<String, Object>) advice.beforeBodyWrite(
                viewerResp.getBody(), null, null, null, new ServletServerHttpRequest(viewerReq), null);
        assertThat(masked.get("retry_request_id")).isEqualTo(retryId.toString());
        assertThat(masked.get("hold_reasons")).isEqualTo(List.of("MODULE_DRAIN", "COLLECTION_WINDOW"));
        assertThat((String) masked.get("not_started_summary"))
                .contains("held by a module drain and a collection window")
                .contains("2026-11-25T23:46:00Z");
        assertThat(masked.toString()).doesNotContain("SR-SECRET-99", "operator reason secret", "actor-secret", "cma-secret-domain");
        List<Map<String, Object>> viewerItems = (List<Map<String, Object>>) masked.get("items");
        assertThat(viewerItems.get(0)).containsEntry("vs_id", 2).doesNotContainKey("domain_ref");

        // 3. Replay viewer is refused on request submission; security_admin succeeds with previous_request_id, vs_ids, domain_ref
        viewerReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "aiview-actor");
        var viewerPost = controller.request("dev-cp", new SupportBundleController.RequestBody_(
                List.of("cp_cpinfo"), "2026-11-26T01:00:00Z", "SR-1", "Re-request reason",
                origId.toString(), List.of(2), "cma-domain"), viewerReq);
        assertThat(viewerPost.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        when(store.virtualSystemIds("dev-cp")).thenReturn(List.of(2));
        MockHttpServletRequest adminReq = new MockHttpServletRequest();
        adminReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, false);
        adminReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "admin-actor");
        var adminPost = controller.request("dev-cp", new SupportBundleController.RequestBody_(
                List.of("cp_cpinfo"), "2026-11-26T01:00:00Z", "SR-1", "Re-request reason",
                origId.toString(), List.of(2), "cma-domain"), adminReq);
        assertThat(adminPost.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(store).attachJob(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("job-again"),
                org.mockito.ArgumentMatchers.eq("admin-actor"));
    }
}
