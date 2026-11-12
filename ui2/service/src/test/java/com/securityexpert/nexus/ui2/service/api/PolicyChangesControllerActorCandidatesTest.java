package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.service.policy.PolicyChangesService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** PO D4/D6 (2026-11-12): the candidate administrators of an AMBIGUOUS change go to authorised roles only, never to aiview. */
class PolicyChangesControllerActorCandidatesTest {
    private final PolicyChangesService service = mock(PolicyChangesService.class);
    private final PolicyChangesController controller = new PolicyChangesController(service);

    @Test void anAuthorisedRoleReceivesTheCandidates() {
        when(service.actorCandidates(5L)).thenReturn(List.of("admin-a", "admin-b"));
        var response = controller.actorCandidates(5L, new MockHttpServletRequest());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("revision", "5", "candidates", List.of("admin-a", "admin-b")), response.getBody());
        assertTrue(String.valueOf(response.getHeaders().getCacheControl()).contains("no-store"));
    }

    @Test void theMaskedPersonaIsRefusedAndTheServiceIsNeverAsked() {
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, Boolean.TRUE);
        var response = controller.actorCandidates(5L, request);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(Map.of("error", "WITHHELD_IN_AIVIEW"), response.getBody());
        verifyNoInteractions(service);
    }
}
