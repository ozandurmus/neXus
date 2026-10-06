package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CpFailoverProjectionTest {
    private static MockHttpServletRequest actor(String actor) {
        var request=new MockHttpServletRequest();
        request.setServletPath("/api/v2/cp-failover/approvals");
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,actor);
        return request;
    }

    @Test
    void approvalProjectionBindsRevisionAndDisclosesNonceOnlyToInitiator() {
        var service=mock(CpFailoverService.class);
        var approval=new JooqCpFailoverRepository.Approval("request-opaque","CLS-ROMEO-01",null,
            Instant.EPOCH,Instant.EPOCH.plusSeconds(3600),"Synthetic drill","principal-opaque",null);
        var binding=new JooqCpFailoverRepository.RequestApproval(approval,1,"unit-opaque","members-opaque",
            JooqCpFailoverRepository.ADMIN_SINGLE,1,"principal-opaque","nonce-opaque");
        when(service.createApprovalRequest(any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(binding);
        var body=new CpFailoverController.ApprovalRequest("cluster-opaque","unit-opaque",Instant.EPOCH,
            Instant.EPOCH.plusSeconds(3600),"Synthetic drill","request-opaque");
        var controller=new CpFailoverController(service);
        var initiator=(Map<?,?>)controller.approve(body,actor("principal-opaque")).getBody();
        assertEquals("request-opaque",initiator.get("requestId"));
        assertEquals(1L,initiator.get("revision"));
        assertEquals("nonce-opaque",initiator.get("executionNonce"));
        assertEquals(true,initiator.get("approved"));
        assertFalse(initiator.containsKey("approvedBy"));
        assertFalse(initiator.containsKey("initiatedBy"));
        var observer=actor("principal-opaque");
        observer.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE,true);
        assertFalse(((Map<?,?>)controller.approve(body,observer).getBody()).containsKey("executionNonce"));
        assertFalse(((Map<?,?>)controller.approve(body,actor("reviewer-opaque")).getBody()).containsKey("executionNonce"));
    }

    @Test
    void rolesAreConfirmedOnlyByPassingChecksAndOnlyKnownEnumsLeaveProjection() {
        var service=mock(CpFailoverService.class);
        var run=new JooqCpFailoverRepository.Run("run-opaque","CLS-ROMEO-01",null,"request-opaque",
            "principal-opaque",Instant.EPOCH,"job-opaque","STOPPED","POSTCHECK","UNKNOWN","1","UNKNOWN");
        when(service.detail("run-opaque","principal-opaque")).thenReturn(Optional.of(new JooqCpFailoverRepository.Detail(run,List.of(
            new JooqCpFailoverRepository.Check("pre","member-opaque",null,1,"PASS","{\"role\":\"ACTIVE\"}",Instant.EPOCH),
            new JooqCpFailoverRepository.Check("post","member-opaque",null,1,"UNKNOWN","{\"role\":\"DOWN\"}",Instant.EPOCH.plusSeconds(1)),
            new JooqCpFailoverRepository.Check("post","other-opaque",null,1,"PASS","{\"role\":\"SYNTHETIC-PRIVATE\"}",Instant.EPOCH.plusSeconds(2))))));
        var view=(Map<?,?>)new CpFailoverController(service).detail("run-opaque",actor("principal-opaque")).getBody();
        assertEquals(Map.of("member-opaque","ACTIVE","other-opaque","UNKNOWN"),view.get("lastConfirmedRoles"));
        assertEquals("NOT_EVALUATED",view.get("sessionContinuity"));
        assertFalse(view.containsKey("mutationPossible"));
    }
}
