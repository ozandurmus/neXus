package com.securityexpert.nexus.ui2.service.supportbundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore.UploadRow;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.supportbundle.upload.SupportBundleUploadService;

/**
 * Sweep round 6 leak 4 regression: {@code GET /api/v2/support-bundles/{id}/uploads} withholds {@code error_detail}
 * and {@code remote_status} for generic (or unrecognized) destinations on {@code role:replay_viewer} while keeping
 * {@code error_code} visible, and scrubs echoed withheld upload/bundle references before passing fixed vendor
 * destination texts through {@code PrivacyMaskingResponseBodyAdvice}'s free-text masking.
 */
class SupportBundleUploadControllerTest {

    private static final UUID BUNDLE_ID = UUID.fromString("2b9f0e1a-7c34-4a81-9f22-112233445566");

    private SupportBundleUploadService uploadService;
    private JooqSupportBundleStore bundleStore;
    private SupportBundleUploadController controller;
    private PrivacyMaskingResponseBodyAdvice advice;
    private TopologyNamePseudonymizer names;

    @BeforeEach
    void setUp() {
        byte[] key = "0123456789".repeat(4).substring(0, 32).getBytes(StandardCharsets.UTF_8);
        names = new TopologyNamePseudonymizer(key);
        advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), names);
        uploadService = mock(SupportBundleUploadService.class);
        bundleStore = mock(JooqSupportBundleStore.class);
        controller = new SupportBundleUploadController(uploadService, mock(SupportBundleCleanupService.class), bundleStore);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withholdsGenericUploadStatusAndDetailForViewerAndMasksVendorOnesWhileKeepingErrorCode() {
        String rawDeviceHost = "asa-synth-dc-a";
        String rawDeviceIp = String.join(".", "198", "51", "100", "24");
        names.maskDeviceName(rawDeviceHost, null);

        String sftpHost = "sftp-synth.drop.invalid";
        String sftpUser = "sftpusr-synth";
        String cxdUser = "cxd-synth-acct";
        String bundleCaseRef = "SR-691020304";
        String uploadCaseRef = "TAC-SYNTH01";
        String actor = "tac-synth.ops";

        Instant now = Instant.parse("2026-11-25T15:00:00Z");
        when(bundleStore.find(BUNDLE_ID)).thenReturn(Optional.of(new RequestRow(
                BUNDLE_ID, "dev-asa-1", "cisco_asa", "cisco_asa", "NOW", now,
                bundleCaseRef, "ASA bundle reason token", actor,
                now, now, now, now.plusSeconds(86_400), null, null, 0, null, null, "job-1", "{}",
                "COMPLETED", "COMPLETED")));

        UploadRow vendorRow = new UploadRow(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                BUNDLE_ID,
                List.of(UUID.randomUUID()),
                "cisco_cxd_https",
                "cxd.cisco.com",
                443,
                cxdUser,
                "nexus-support-bundle-cisco_asa.tgz",
                bundleCaseRef,
                "Vendor CXD upload reason",
                actor,
                now, now, now, now,
                "FAILED",
                1,
                512L,
                512L,
                "a".repeat(64),
                "HTTP 201 Created for case " + bundleCaseRef + " user " + cxdUser,
                "REMOTE_REJECTED",
                "Vendor gateway rejected archive from " + rawDeviceHost + " at " + rawDeviceIp + " for " + cxdUser,
                null,
                false);

        UploadRow genericRow = new UploadRow(
                UUID.fromString("66666666-7777-8888-9999-000000000000"),
                BUNDLE_ID,
                List.of(UUID.randomUUID()),
                "generic_sftp",
                sftpHost,
                22,
                sftpUser,
                "nexus-support-bundle-cisco_asa.tgz",
                uploadCaseRef,
                "SFTP drop upload reason",
                actor,
                now, now, now, now,
                "FAILED",
                2,
                512L,
                0L,
                "b".repeat(64),
                "SFTP refused user " + sftpUser + " at " + sftpHost,
                "CONNECTION_FAILED",
                "Could not reach " + sftpHost + ":22 (ConnectException).",
                "SHA256:synthetic-fp",
                true);

        when(uploadService.list(BUNDLE_ID)).thenReturn(List.of(vendorRow, genericRow));

        // 1. Replay viewer (aiview)
        MockHttpServletRequest viewerReq = new MockHttpServletRequest();
        viewerReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        var viewerResp = controller.list(BUNDLE_ID.toString(), viewerReq);
        assertThat(viewerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> viewerBody = (Map<String, Object>) advice.beforeBodyWrite(
                viewerResp.getBody(), null, null, null, new ServletServerHttpRequest(viewerReq), null);

        String text = viewerBody.toString();
        assertThat(text).doesNotContain(
                sftpHost, sftpUser, cxdUser, bundleCaseRef, uploadCaseRef, actor, rawDeviceHost, rawDeviceIp);

        List<Map<String, Object>> viewerUploads = (List<Map<String, Object>>) viewerBody.get("uploads");
        assertThat(viewerUploads).hasSize(2);

        Map<String, Object> maskedVendor = viewerUploads.get(0);
        assertThat(maskedVendor.get("destination_host")).isEqualTo("cxd.cisco.com");
        assertThat(maskedVendor.get("error_code")).isEqualTo("REMOTE_REJECTED");
        assertThat((String) maskedVendor.get("remote_status")).contains("HTTP 201 Created");
        assertThat((String) maskedVendor.get("error_detail")).contains("FW-").doesNotContain(rawDeviceHost, rawDeviceIp, cxdUser);
        assertThat(maskedVendor).doesNotContainKeys("requested_by", "remote_user", "case_ref", "reason", "host_key_fingerprint");

        Map<String, Object> maskedGeneric = viewerUploads.get(1);
        assertThat(maskedGeneric.get("destination_host")).isNull();
        assertThat(maskedGeneric.get("remote_status")).isNull();
        assertThat(maskedGeneric.get("error_detail")).isNull();
        assertThat(maskedGeneric.get("error_code")).isEqualTo("CONNECTION_FAILED");
        assertThat(maskedGeneric).doesNotContainKeys("requested_by", "remote_user", "case_ref", "reason", "host_key_fingerprint");

        // 2. Administrator (non-viewer) still receives raw fields unchanged
        MockHttpServletRequest adminReq = new MockHttpServletRequest();
        adminReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, false);
        var adminResp = controller.list(BUNDLE_ID.toString(), adminReq);
        Map<String, Object> adminBody = (Map<String, Object>) advice.beforeBodyWrite(
                adminResp.getBody(), null, null, null, new ServletServerHttpRequest(adminReq), null);
        List<Map<String, Object>> adminUploads = (List<Map<String, Object>>) adminBody.get("uploads");
        assertThat(adminUploads.get(0).get("remote_status"))
                .isEqualTo("HTTP 201 Created for case " + bundleCaseRef + " user " + cxdUser);
        assertThat(adminUploads.get(1).get("destination_host")).isEqualTo(sftpHost);
        assertThat(adminUploads.get(1).get("remote_status")).isEqualTo("SFTP refused user " + sftpUser + " at " + sftpHost);
        assertThat(adminUploads.get(1).get("error_detail")).isEqualTo("Could not reach " + sftpHost + ":22 (ConnectException).");
        assertThat(adminUploads.get(1).get("remote_user")).isEqualTo(sftpUser);
    }
}
