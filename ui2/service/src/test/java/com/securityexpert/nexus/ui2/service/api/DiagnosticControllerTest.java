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
        var response = controller.targets(request);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, ((Map<?, ?>)response.getBody()).get("canExecute"));
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

    @Test
    void acceptsGateIdAndParameterWithoutBrowserCommandText() {
        var service=mock(DiagnosticService.class);
        var controller=new DiagnosticController(service,
            mock(com.securityexpert.nexus.ui2.service.security.RbacEvaluator.class));
        var request=mock(HttpServletRequest.class);
        when(request.getAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE))
            .thenReturn("synthetic-actor");
        when(service.submitRead("device-1","fmg_ssh_fmnetwork_interface_detail","port5",
                "00000000-0000-0000-0000-000000000001","synthetic-actor"))
            .thenReturn(new com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Admitted("job-1"));
        var response=controller.run(Map.of("device_id","device-1","gate_id","fmg_ssh_fmnetwork_interface_detail",
            "parameter","port5","request_id","00000000-0000-0000-0000-000000000001"),request);
        assertEquals(202,response.getStatusCode().value());
    }
    @Test
    void maskedSessionRunsOnlyResolvedReadsAndGetsForbiddenForRejectedGates() {
        var service = mock(DiagnosticService.class);
        var controller = new DiagnosticController(service, mock(com.securityexpert.nexus.ui2.service.security.RbacEvaluator.class));
        var request = mock(HttpServletRequest.class);
        when(request.getAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);
        when(request.getAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE)).thenReturn("synthetic-actor");
        String id = "00000000-0000-0000-0000-000000000001";
        when(service.submitRead("device-1", "cp_inventory_cphaprob_stat", null, id, "synthetic-actor", true))
                .thenReturn(new com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Admitted("job-1"));
        assertEquals(202, controller.run(Map.of("device_id", "device-1", "gate_id", "cp_inventory_cphaprob_stat", "request_id", id), request).getStatusCode().value());
        for (String gate : new String[]{"cp_failover_down", "cp_failover_up", "unknown_gate", "class_1_gate"}) {
            when(service.submitRead("device-1", gate, null, id, "synthetic-actor", true))
                    .thenReturn(new com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Refused("DIAGNOSTIC_RUN_NOT_PERMITTED", "Read only"));
            var refused = controller.run(Map.of("device_id", "device-1", "gate_id", gate, "request_id", id), request);
            assertEquals(403, refused.getStatusCode().value());
            assertEquals("DIAGNOSTIC_RUN_NOT_PERMITTED", ((Map<?, ?>)refused.getBody()).get("code"));
        }
    }

    @Test
    void maskedSessionCannotUsePortFormOrCommandText() {
        var service = mock(DiagnosticService.class);
        var controller = new DiagnosticController(service, mock(com.securityexpert.nexus.ui2.service.security.RbacEvaluator.class));
        var request = mock(HttpServletRequest.class);
        when(request.getAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);
        for (var body : java.util.List.of(
                Map.<String,Object>of("device_id", "device-1", "port", "port5", "request_id", "synthetic-id"),
                Map.<String,Object>of("device_id", "device-1", "gate_id", "cp_failover_down", "command", "clusterXL_admin down"))) {
            var response = controller.run(body, request);
            assertEquals(403, response.getStatusCode().value());
            assertEquals("DIAGNOSTIC_RUN_NOT_PERMITTED", ((Map<?, ?>)response.getBody()).get("code"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void administratorPortFormRemainsAvailable() {
        var service = mock(DiagnosticService.class);
        var controller = new DiagnosticController(service, mock(com.securityexpert.nexus.ui2.service.security.RbacEvaluator.class));
        var request = mock(HttpServletRequest.class);
        when(request.getAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE)).thenReturn("synthetic-actor");
        when(service.submit("device-1", "port5", "synthetic-id", "synthetic-actor"))
                .thenReturn(new com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Admitted("job-1"));
        assertEquals(202, controller.run(Map.of("device_id", "device-1", "port", "port5", "request_id", "synthetic-id"), request).getStatusCode().value());
    }
    @Test
    void administratorNonReadGateKeepsExistingConflictResponse() {
        var service = mock(DiagnosticService.class);
        var controller = new DiagnosticController(service, mock(com.securityexpert.nexus.ui2.service.security.RbacEvaluator.class));
        var request = mock(HttpServletRequest.class);
        when(request.getAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE)).thenReturn("synthetic-actor");
        String id = "00000000-0000-0000-0000-000000000001";
        when(service.submitRead("device-1", "cp_failover_down", null, id, "synthetic-actor"))
                .thenReturn(new com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Refused("DIAGNOSTIC_UNAVAILABLE", "Unavailable"));
        assertEquals(409, controller.run(Map.of("device_id", "device-1", "gate_id", "cp_failover_down", "request_id", id), request).getStatusCode().value());
    }
}
