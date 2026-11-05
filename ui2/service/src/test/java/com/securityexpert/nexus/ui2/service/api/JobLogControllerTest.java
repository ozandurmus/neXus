package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobEvent;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobPage;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

class JobLogControllerTest {
    @Test
    void transcriptFlagRequiresAnExistingTranscriptAndTheCurrentSessionPermission() {
        var jobs = mock(JobLogQueryService.class);
        var rbac = mock(RbacEvaluator.class);
        var controller = new JobLogController(jobs, mock(PrivacyMaskingResponseBodyAdvice.class),
                new ActionRegistry(), rbac);
        var existing = new JobEvent("job-synthetic", "backup", "device-synthetic", null, "COMPLETED", null,
                null, Instant.parse("2026-09-27T10:00:00Z"), null, null, true);
        var absent = new JobEvent("job-no-transcript", "backup", "device-synthetic", null, "COMPLETED", null,
                null, Instant.parse("2026-09-27T10:00:00Z"), null, null, false);
        when(jobs.query(any())).thenReturn(new JobPage(List.of(existing, absent), 1, 50, 2));
        for (String actor : List.of("viewer-only", "replay-viewer", "backup-admin", "security-admin")) {
            boolean granted = actor.endsWith("admin");
            when(rbac.evaluateAny(eq(actor), anySet(), any())).thenReturn(new RbacEvaluator.Decision(
                    granted ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty()));
            var request = new MockHttpServletRequest();
            request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, actor);
            if (actor.equals("replay-viewer")) request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
            var page = controller.listJobs(null, null, null, null, null, null, 1, 50, request).getBody();
            if (granted) assertTrue(page.items().get(0).hasTranscript(), actor);
            else assertFalse(page.items().get(0).hasTranscript(), actor);
            assertFalse(page.items().get(1).hasTranscript(), actor);
        }
        var replayAdmin = new MockHttpServletRequest();
        replayAdmin.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "backup-admin");
        replayAdmin.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        assertFalse(controller.listJobs(null, null, null, null, null, null, 1, 50, replayAdmin)
                .getBody().items().get(0).hasTranscript());
    }

    @Test
    void listJobsAndExportRejectUnknownSortOrOrderWith400AndPassAllowListedSort() {
        var jobs = mock(JobLogQueryService.class);
        var rbac = mock(RbacEvaluator.class);
        var controller = new JobLogController(jobs, mock(PrivacyMaskingResponseBodyAdvice.class),
                new ActionRegistry(), rbac);
        when(jobs.query(any())).thenReturn(new JobPage(List.of(), 1, 50, 0));
        when(jobs.exportCsv(any())).thenReturn("");
        when(jobs.exportRows(any())).thenReturn(List.of());
        var request = new MockHttpServletRequest();

        org.junit.jupiter.api.Assertions.assertEquals(400,
                controller.listJobs(null, null, null, null, null, null, 1, 50, "drop table", "asc", request).getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertEquals(400,
                controller.listJobs(null, null, null, null, null, null, 1, 50, "state", "sideways", request).getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertEquals(400,
                controller.listJobs(null, null, null, null, null, null, 1, 50, null, "desc", request).getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertEquals(400,
                controller.exportCsv(null, null, null, null, null, null, "unknown", "asc", request).getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertEquals(200,
                controller.listJobs(null, null, null, null, null, null, 1, 50, "duration", "desc", request).getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertEquals(200,
                controller.exportCsv(null, null, null, null, null, null, "device", "asc", request).getStatusCode().value());
    }

    @Test
    void replayViewerCsvExportMasksEveryTargetLabelEvenOnesTextMaskingNeverSaw() {
        // Security review 2026-11-05: the CSV was masked as text only, so a cluster, policy container, policy source or
        // packet case label that no earlier masked response had registered in this process reached aiview raw.
        var jobs = mock(JobLogQueryService.class);
        byte[] key = "01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var masking = new PrivacyMaskingResponseBodyAdvice(
                new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(key),
                new com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer(key));
        var controller = new JobLogController(jobs, masking, new ActionRegistry(), mock(RbacEvaluator.class));
        Instant at = Instant.parse("2026-11-05T10:00:00Z");
        when(jobs.exportRows(any())).thenReturn(List.of(
                new JobEvent("j-cls", "pan_failover_readiness", "dev-1", "csv-raw-cluster-zulu", "COMPLETED", "READY",
                        null, at, null, null, false, null, "cluster", "csv-raw-cluster-zulu"),
                new JobEvent("j-dom", "cp_policy_collect", "mgr-1", "csv-raw-domain-yankee", "COMPLETED", "SUCCESS",
                        null, at, null, null, false, null, "policy_container", "run-1"),
                new JobEvent("j-src", "pan_policy_collect", "mgr-2", "csv-raw-source-xray", "COMPLETED", "SUCCESS",
                        null, at, null, null, false, null, "policy_source", "run-2"),
                new JobEvent("j-pkt", "packet_analysis_run", null, "csv-raw-case-whiskey", "COMPLETED", "SUCCESS",
                        null, at, null, null, false, null, "packet_analysis_case", "case-1"),
                new JobEvent("j-dev", "cp_inventory_collect", "dev-2", "csv-raw-host-victor", "FAILED", "FAILURE",
                        "unreachable 192.0.2.77", at, null, null, false, null, "device", null)));
        var replay = new MockHttpServletRequest();
        replay.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);

        var response = controller.exportCsv(null, null, null, null, null, null, null, null, replay);

        org.junit.jupiter.api.Assertions.assertEquals(200, response.getStatusCode().value());
        String csv = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        for (String raw : List.of("csv-raw-cluster-zulu", "csv-raw-domain-yankee", "csv-raw-source-xray",
                "csv-raw-case-whiskey", "csv-raw-host-victor", "192.0.2.77")) {
            assertFalse(csv.contains(raw), "raw value reached the aiview CSV: " + raw);
        }
        assertTrue(csv.contains("CLS-"), csv);
        assertTrue(csv.contains("DOM-"), csv);
        assertTrue(csv.lines().count() == 6, csv);
        org.mockito.Mockito.verify(jobs, org.mockito.Mockito.never()).exportCsv(any());
    }

    @Test
    void nonMaskedCsvExportKeepsTheUnmaskedRendering() {
        var jobs = mock(JobLogQueryService.class);
        var masking = mock(PrivacyMaskingResponseBodyAdvice.class);
        var controller = new JobLogController(jobs, masking, new ActionRegistry(), mock(RbacEvaluator.class));
        when(jobs.exportCsv(any())).thenReturn("job_id\r\n");

        var response = controller.exportCsv(null, null, null, null, null, null, null, null, new MockHttpServletRequest());

        org.junit.jupiter.api.Assertions.assertEquals("job_id\r\n",
                new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        org.mockito.Mockito.verifyNoInteractions(masking);
    }
}
