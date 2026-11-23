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

    private static com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord pairMember(String id,String vendor,String role) {
        return new com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord(id,"gateway",vendor,
            com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED,java.util.Optional.empty(),
            java.util.Optional.empty(),java.util.Optional.empty(),java.util.Optional.ofNullable(role),java.util.Optional.of("CLS-ROMEO-02"));
    }
    /** Cisco ASA and FortiGate pairs carry the stored-role cluster view; Check Point rows do not. */
    @org.junit.jupiter.api.Test
    void listedAsaAndFortiGatePairsCarryTheHaView() {
        Object[][] cases={{"cisco_asa","active","standby","HEALTHY"},{"cisco_asa","active",null,"UNKNOWN"},
            {"fortinet","primary","primary","DEGRADED"},{"fortinet","primary","secondary","HEALTHY"}};
        for (Object[] c:cases) {
            var service=mock(CpFailoverService.class);
            var unit=new CpFailoverService.Unit("opaque-unit","opaque-unit","CLS-ROMEO-02",null,java.util.List.of(
                pairMember("opaque-1",(String)c[0],(String)c[1]),pairMember("opaque-2",(String)c[0],(String)c[2])));
            when(service.summary("synthetic-actor")).thenReturn(java.util.List.of(new CpFailoverService.Summary(
                unit,(String)c[0],false,null,null,null,null,false,false)));
            var request=new MockHttpServletRequest();
            request.setServletPath("/api/v2/cp-failover/summary");
            request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"synthetic-actor");
            var body=(java.util.List<?>)new CpFailoverController(service).summary(request).getBody();
            var view=(Map<?,?>)((Map<?,?>)body.get(0)).get("haView");
            assertEquals(c[3],view.get("state"));
            assertEquals("UNKNOWN",view.get("sync"));
        }
        var service=mock(CpFailoverService.class);
        var unit=new CpFailoverService.Unit("opaque-unit","opaque-unit","CLS-ROMEO-01",null,
            java.util.List.of(member("opaque-1"),member("opaque-2")));
        when(service.summary("synthetic-actor")).thenReturn(java.util.List.of(new CpFailoverService.Summary(
            unit,"check_point",false,null,null,null,null,false)));
        var request=new MockHttpServletRequest();
        request.setServletPath("/api/v2/cp-failover/summary");
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"synthetic-actor");
        assertFalse(((Map<?,?>)((java.util.List<?>)new CpFailoverController(service).summary(request).getBody()).get(0)).containsKey("haView"));
    }

    /**
     * PO_DECISION_RECORD_2026_11_23_PAN_IDLE_SESSION_SYNC.md: a READY Palo Alto unit whose check 5 is WARN (idle pair) is
     * offered (no refusal); the row keeps WARN and the warning text. A NOT_READY unit stays refused.
     */
    @org.junit.jupiter.api.Test
    void panIdleSessionSyncWarningDoesNotBlockTheStartOffer() {
        var service=mock(CpFailoverService.class);
        var unit=new CpFailoverService.Unit("opaque-pan","opaque-pan","CLS-ROMEO-02",null,java.util.List.of(
            pairMember("pan-1","palo_alto","active"),pairMember("pan-2","palo_alto","passive")));
        String facts="{\"reason\":\"NO_SESSIONS_TO_SYNCHRONIZE\",\"missing\":[],\"unrecognised\":[],"
            +"\"active\":{\"enabled\":true,\"messages\":{\"session setup\":{\"enable\":true,\"sent\":0,\"recv\":0}}}}";
        String checks="[{\"memberRef\":\"pan-1\",\"checkNo\":5,\"status\":\"WARN\",\"derived\":"+facts+"},"
            +"{\"memberRef\":\"pan-2\",\"checkNo\":5,\"status\":\"WARN\",\"derived\":"+facts+"}]";
        var ready=new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.ReadinessStatus(
            "CLS-ROMEO-02",null,"palo_alto","READY",java.time.Instant.EPOCH,null,checks,"READY");
        var notReady=new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.ReadinessStatus(
            "CLS-ROMEO-02",null,"palo_alto","NOT_READY",java.time.Instant.EPOCH,"Session synchronization",
            checks.replace("WARN","FAIL"),"CHECK_NOT_READY");
        when(service.summary("synthetic-actor")).thenReturn(java.util.List.of(
            new CpFailoverService.Summary(unit,"palo_alto",false,null,null,null,ready,true,true),
            new CpFailoverService.Summary(unit,"palo_alto",false,null,null,null,notReady,true,true)));
        var request=new MockHttpServletRequest();
        request.setServletPath("/api/v2/pan-failover/summary");
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"synthetic-actor");
        var rows=(java.util.List<?>)new CpFailoverController(service).summary(request).getBody();
        var warned=(Map<?,?>)rows.get(0);
        assertNull(warned.get("refusalReason"),"a check 5 warning does not block the start");
        var readiness=(Map<?,?>)warned.get("readiness");
        assertEquals("READY",readiness.get("status"));
        var row=(Map<?,?>)((java.util.List<?>)readiness.get("checks")).get(0);
        assertEquals("WARN",row.get("result"));
        assertEquals(true,row.get("blocking"));
        assertTrue(row.get("summary").toString().startsWith("No sessions to synchronize (0 active sessions on both members)"));
        assertEquals("CHECK_NOT_READY",((Map<?,?>)rows.get(1)).get("refusalReason"));
    }

    /** ASA and FortiGate summary and run-detail responses carry read-only check-1 roles, mode and failoverRefusal. */
    @org.junit.jupiter.api.Test
    void asaAndFortigateSummaryAndDetailProjectObservedRolesModeAndFailoverRefusal() {
        var service=mock(CpFailoverService.class);
        var asaUnit=new CpFailoverService.Unit("opaque-asa","opaque-asa","CLS-ROMEO-02",null,java.util.List.of(
            pairMember("opaque-1","cisco_asa","active"),pairMember("opaque-2","cisco_asa","standby")));
        String asaChecks="[{\"memberRef\":\"opaque-1\",\"checkNo\":1,\"status\":\"PASS\",\"derived\":{\"reason\":\"ROLES_OK\",\"failoverOn\":true,\"oneActiveOneStandbyReady\":true}},"
            +"{\"memberRef\":\"opaque-2\",\"checkNo\":1,\"status\":\"PASS\",\"derived\":{\"reason\":\"ROLES_OK\",\"failoverOn\":true,\"oneActiveOneStandbyReady\":true}},"
            +"{\"memberRef\":\"opaque-1\",\"checkNo\":10,\"status\":\"NOT_EVALUATED\",\"derived\":{\"reason\":\"NOT_ACTIVE_MEMBER\"}},"
            +"{\"memberRef\":\"opaque-2\",\"checkNo\":10,\"status\":\"PASS\",\"derived\":{\"trafficBytesPerSecond\":1000,\"arpEntries\":12}}]";
        var asaReadiness=new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.ReadinessStatus(
            "CLS-ROMEO-02",null,"cisco_asa","READY",java.time.Instant.EPOCH,null,asaChecks,"ALL_CHECKS_PASSED");
        var fgtUnit=new CpFailoverService.Unit("opaque-fgt","opaque-fgt","CLS-ROMEO-03",null,java.util.List.of(
            pairMember("fgt-1","fortinet","primary"),pairMember("fgt-2","fortinet","secondary")));
        String fgtChecks="[{\"memberRef\":\"fgt-1\",\"checkNo\":1,\"status\":\"PASS\",\"derived\":{\"reason\":\"HA_OK\",\"mode\":\"A-P\"}}]";
        var fgtReadiness=new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.ReadinessStatus(
            "CLS-ROMEO-03",null,"fortinet","READY",java.time.Instant.EPOCH,null,fgtChecks,"ALL_CHECKS_PASSED");
        when(service.summary("synthetic-actor")).thenReturn(java.util.List.of(
            new CpFailoverService.Summary(asaUnit,"cisco_asa",false,null,null,null,asaReadiness,true,true),
            new CpFailoverService.Summary(fgtUnit,"fortinet",false,null,null,null,fgtReadiness,true,true)));
        var request=new MockHttpServletRequest();
        request.setServletPath("/api/v2/cp-failover/summary");
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"synthetic-actor");
        var rows=(java.util.List<?>)new CpFailoverController(service).summary(request).getBody();
        var asaRow=(Map<?,?>)rows.get(0);
        assertEquals("active-passive",asaRow.get("mode"));
        assertEquals("FAILOVER_VENDOR_NOT_AVAILABLE",asaRow.get("failoverRefusal"));
        assertNotNull(asaRow.get("haView"));
        var asaCheckRows=(java.util.List<?>)((Map<?,?>)asaRow.get("readiness")).get("checks");
        assertEquals("STANDBY",((Map<?,?>)((Map<?,?>)asaCheckRows.get(0)).get("derived")).get("role"));
        assertEquals("ACTIVE",((Map<?,?>)((Map<?,?>)asaCheckRows.get(1)).get("derived")).get("role"));
        var fgtRow=(Map<?,?>)rows.get(1);
        assertEquals("active-passive",fgtRow.get("mode"));
        assertEquals("FAILOVER_VENDOR_NOT_AVAILABLE",fgtRow.get("failoverRefusal"));
        var fgtCheckRows=(java.util.List<?>)((Map<?,?>)fgtRow.get("readiness")).get("checks");
        assertEquals(null,((Map<?,?>)((Map<?,?>)fgtCheckRows.get(0)).get("derived")).get("role"), "no role is inferred for FortiGate");

        var run=new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.Run("run-asa","CLS-ROMEO-02",null,
            null,"synthetic-actor",java.time.Instant.EPOCH,"job-asa","DONE","DONE","READY",null,"ALL_CHECKS_PASSED","cisco_asa","READINESS");
        when(service.detail("run-asa","synthetic-actor")).thenReturn(java.util.Optional.of(
            new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.Detail(run,java.util.List.of(
                new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.Check("pre","opaque-1",null,1,"PASS",
                    "{\"reason\":\"ROLES_OK\",\"failoverOn\":true,\"oneActiveOneStandbyReady\":true}",java.time.Instant.EPOCH),
                new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.Check("pre","opaque-2",null,1,"PASS",
                    "{\"reason\":\"ROLES_OK\",\"failoverOn\":true,\"oneActiveOneStandbyReady\":true}",java.time.Instant.EPOCH),
                new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.Check("pre","opaque-1",null,10,"NOT_EVALUATED",
                    "{\"reason\":\"NOT_ACTIVE_MEMBER\"}",java.time.Instant.EPOCH)))));
        var detailReq=new MockHttpServletRequest();
        detailReq.setServletPath("/api/v2/asa-failover/runs/run-asa");
        detailReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"synthetic-actor");
        var detail=(Map<?,?>)new CpFailoverController(service).detail("run-asa",detailReq).getBody();
        assertEquals(Map.of("opaque-1","STANDBY","opaque-2","ACTIVE"),detail.get("lastConfirmedRoles"));
    }
}
