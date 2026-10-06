package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CpFailoverControllerTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "dispatch-a")
    void incidentCodeAndOptionalDispatchReferenceReachTheOperator(String dispatchRef) {
        for (String vendor : new String[]{"check_point", "palo_alto"}) {
            var service=mock(CpFailoverService.class);
            when(service.request("unit-a","unit-a",null,"synthetic-actor",vendor,"request-a",1,"nonce-a",true))
                .thenThrow(new CpFailoverService.Refusal("OPEN_INCIDENT",dispatchRef));
            var request=new MockHttpServletRequest();
            request.setServletPath("/api/v2/"+("check_point".equals(vendor)?"cp":"pan")+"-failover/runs");
            request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"synthetic-actor");
            var result=new CpFailoverController(service).start(new CpFailoverController.RunRequest(
                "unit-a","unit-a",null,"request-a",1,"nonce-a",true),request);
            assertEquals(409,result.getStatusCode().value());
            assertEquals(dispatchRef==null?Map.of("code","OPEN_INCIDENT"):
                Map.of("code","OPEN_INCIDENT","dispatchRef",dispatchRef),result.getBody());
        }
    }
}
