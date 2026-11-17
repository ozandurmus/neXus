package com.securityexpert.nexus.ui2.service.failover;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.api.CpFailoverController;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** The FortiGate prefix is read-only plus the readiness request: the vendor comes from the path and every write entry point refuses. */
class CpFgtFailoverControllerTest {
    private static final String ACTOR = "synthetic-actor";

    private static CpFailoverService.Unit unit() {
        var member = new DeviceSummaryRecord("opaque-1", "gateway", "fortinet", DeviceEnrollmentState.ENROLLED, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of("primary"), Optional.of("fgt-single|opaque-1"));
        return new CpFailoverService.Unit("opaque-unit", "opaque-unit", "fgt-single|opaque-1", null, List.of(member));
    }

    @Test void theUnitsReadUsesTheFortinetVendorOffersReadinessOnlyAndSurvivesASingleMember() throws Exception {
        var service = mock(CpFailoverService.class);
        var unit = unit();
        when(service.unitsForMember("opaque-1", ACTOR, "fortinet")).thenReturn(List.of(unit));
        when(service.unitReadiness(ACTOR, List.of(unit))).thenReturn(java.util.Map.of());
        when(service.mayRunReadiness(ACTOR)).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service)).build();
        mvc.perform(get("/api/v2/fgt-failover/units").servletPath("/api/v2/fgt-failover/units").param("memberDeviceId", "opaque-1")
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].vendor").value("fortinet"))
            .andExpect(jsonPath("$[0].canApprove").value(false))
            .andExpect(jsonPath("$[0].canStart").value(false))
            .andExpect(jsonPath("$[0].canSchedule").value(false))
            .andExpect(jsonPath("$[0].canRunReadiness").value(true))
            .andExpect(jsonPath("$[0].failoverRefusal").value("FAILOVER_VENDOR_NOT_AVAILABLE"))
            .andExpect(jsonPath("$[0].members.length()").value(1));
    }

    @Test void theReadinessRequestIsAskedForTheFortinetVendor() throws Exception {
        var service = mock(CpFailoverService.class);
        when(service.requestReadiness("opaque-unit", "opaque-unit", ACTOR, "fortinet")).thenReturn("run-1");
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service)).build();
        mvc.perform(post("/api/v2/fgt-failover/units/opaque-unit/readiness").servletPath("/api/v2/fgt-failover/units/opaque-unit/readiness").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clusterId\":\"opaque-unit\",\"unitId\":\"opaque-unit\"}")
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR))
            .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value("run-1"));
    }

    @Test void revokeAndIncidentReleaseRefuseBeforeTheService() throws Exception {
        var service = mock(CpFailoverService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service)).build();
        mvc.perform(post("/api/v2/fgt-failover/approvals/x/revoke").servletPath("/api/v2/fgt-failover/approvals/x/revoke").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAILOVER_VENDOR_NOT_AVAILABLE"));
        mvc.perform(post("/api/v2/fgt-failover/runs/r/incident/release").servletPath("/api/v2/fgt-failover/runs/r/incident/release").contentType(MediaType.APPLICATION_JSON).content("{}")
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAILOVER_VENDOR_NOT_AVAILABLE"));
        verify(service, never()).revoke(anyString(), anyString());
    }
}
