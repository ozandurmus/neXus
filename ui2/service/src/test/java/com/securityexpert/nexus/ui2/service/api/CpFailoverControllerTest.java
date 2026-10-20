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

    private static com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord member(String id) {
        return new com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord(id,"gateway","check_point",
            com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED,java.util.Optional.empty(),
            java.util.Optional.empty(),java.util.Optional.empty(),java.util.Optional.empty(),java.util.Optional.of("CLS-ROMEO-01"));
    }
    private static String recovery(String first,String second) { return "{\"checkNo\":15,\"memberRef\":\"opaque-1\",\"status\":\"PASS\","
        +"\"derived\":{\"recoveryMode\":\""+first+"\"}},{\"checkNo\":15,\"memberRef\":\"opaque-2\",\"status\":\"PASS\","
        +"\"derived\":{\"recoveryMode\":\""+second+"\"}}"; }

    /** PO decision 2026-10-20 item 2: the unit shows Active Up only when both members' readiness rows say so. */
    @org.junit.jupiter.api.Test
    void unitRecoveryModeIsActiveUpOnlyWhenBothMembersReportIt() {
        Object[][] cases={{"ACTIVE_UP","ACTIVE_UP","ACTIVE_UP"},{"ACTIVE_UP","PRIMARY_UP","PRIMARY_UP"},
            {"ACTIVE_UP","UNKNOWN","UNKNOWN"},{null,null,"UNKNOWN"}};
        for (Object[] c:cases) {
            var service=mock(CpFailoverService.class);
            var unit=new CpFailoverService.Unit("opaque-unit","opaque-unit","CLS-ROMEO-01",null,
                java.util.List.of(member("opaque-1"),member("opaque-2")));
            String rows=c[0]==null?"[]":"["+recovery((String)c[0],(String)c[1])+"]";
            var readiness=new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.ReadinessStatus(
                "CLS-ROMEO-01",null,"check_point","READY",java.time.Instant.EPOCH,null,rows);
            when(service.summary("synthetic-actor")).thenReturn(java.util.List.of(new CpFailoverService.Summary(
                unit,"check_point",false,null,null,null,readiness,false)));
            var request=new MockHttpServletRequest();
            request.setServletPath("/api/v2/cp-failover/summary");
            request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"synthetic-actor");
            var body=(java.util.List<?>)new CpFailoverController(service).summary(request).getBody();
            assertEquals(c[2],((Map<?,?>)body.get(0)).get("recoveryMode"),java.util.Arrays.toString(c));
        }
    }
}
