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
}
