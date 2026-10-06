package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverDeviceExecutor;
import com.securityexpert.nexus.ui2.jobs.failover.pilot.FailoverPilotAllowlist;
import com.securityexpert.nexus.ui2.service.failover.*;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FailoverExecutionControllerTest {
    @Test void genericControllerNeverDispatchesEitherActionEvenWithAuthenticatedPayload() {
        var service=mock(FailoverExecutionService.class);
        var controller=new FailoverExecutionController(service);
        var request=new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"synthetic-actor");
        for (var action:FailoverActionKind.values()) {
            var response=controller.executeFailover("synthetic-unit",
                new FailoverExecutionController.ExecutePayload("synthetic-token","synthetic-nonce",action.name()),request);
            assertEquals(HttpStatus.FORBIDDEN,response.getStatusCode());
            assertEquals(FailoverMutationSwitch.GENERIC_DISABLED,response.getBody().get("error"));
        }
        assertEquals(HttpStatus.FORBIDDEN,controller.executeFailover("synthetic-unit",null,null).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test void genericServiceRefusesManualAndScheduledDispatchEvenWithRegisteredTransport() {
        var authz=mock(FailoverAuthorizationService.class);
        var preflight=mock(PreflightService.class);
        var quarantine=mock(DurableQuarantineStore.class);
        var service=new FailoverExecutionService(authz,preflight,new FailoverPilotAllowlist(),quarantine);
        var executor=mock(FailoverDeviceExecutor.class);
        when(executor.supportedVendor()).thenReturn("CHECK_POINT");
        service.registerExecutor(executor);
        clearInvocations(executor);
        for (var action:FailoverActionKind.values()) {
            assertEquals(FailoverMutationSwitch.GENERIC_DISABLED,assertThrows(SecurityException.class,
                () -> service.executeFailover("synthetic-unit","synthetic-token","synthetic-nonce",action,"synthetic-actor")).getMessage());
            assertEquals(FailoverMutationSwitch.GENERIC_DISABLED,assertThrows(SecurityException.class,
                () -> service.executeScheduledFailover("synthetic-unit","synthetic-token","synthetic-nonce",action,
                    "synthetic-actor","synthetic-member",Instant.now().plusSeconds(60),null)).getMessage());
        }
        verifyNoInteractions(authz,preflight,quarantine,executor);
    }
}
