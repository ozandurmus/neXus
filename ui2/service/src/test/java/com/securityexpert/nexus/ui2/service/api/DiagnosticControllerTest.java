package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

import com.securityexpert.nexus.ui2.service.device.diagnostic.DiagnosticService;

class DiagnosticControllerTest {
    @Test
    void readAccessRequiresAdminOrMaskedProjection() {
        DiagnosticService service = mock(DiagnosticService.class);
        var rbac = mock(com.securityexpert.nexus.ui2.service.security.RbacEvaluator.class);
        var controller = new DiagnosticController(service, rbac);
        var request = mock(HttpServletRequest.class);
        when(request.getAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE))
                .thenReturn("synthetic-actor");
        when(rbac.evaluate(eq("synthetic-actor"), any(), any())).thenReturn(
                new com.securityexpert.nexus.ui2.service.security.RbacEvaluator.Decision(
                        com.securityexpert.nexus.ui2.platform.AuthzOutcome.DENIED,
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.targets(request));
        verifyNoInteractions(service);
        when(request.getAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE))
                .thenReturn(true);
        when(service.targets(true)).thenReturn(java.util.List.of());
        assertEquals(200, controller.targets(request).getStatusCode().value());
        verify(service).targets(true);
    }

    @Test
    void refusesCommandTextBeforeAdmission() {
        DiagnosticService service = mock(DiagnosticService.class);
        var controller = new DiagnosticController(service,
                mock(com.securityexpert.nexus.ui2.service.security.RbacEvaluator.class));
        var response = controller.run(Map.of("device_id", "device-1", "port", "port5",
                "request_id", "00000000-0000-0000-0000-000000000001", "command", "execute reboot"),
                mock(HttpServletRequest.class));
        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(service);
    }
}
