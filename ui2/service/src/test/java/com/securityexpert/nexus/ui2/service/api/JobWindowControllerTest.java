package com.securityexpert.nexus.ui2.service.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.AdminWindowBypass;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/** PO 2026-10-20: the window endpoint tells the UI the business hours and whether this session bypasses the window. */
class JobWindowControllerTest {
    @Test void administratorsSeeTheBypassOperatorsAndReplayViewersDoNot() throws Exception {
        var rbac = mock(RbacEvaluator.class);
        when(rbac.evaluate(eq("synthetic-admin"), any(), any())).thenAnswer(call -> new RbacEvaluator.Decision(
                Optional.of(RoleToken.SECURITY_ADMIN).equals(call.getArgument(1)) ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                Optional.of("synthetic-authority"), Optional.empty(), Optional.empty()));
        when(rbac.evaluate(eq("synthetic-operator"), any(), any())).thenReturn(new RbacEvaluator.Decision(
                AuthzOutcome.DENIED, Optional.of("synthetic-authority"), Optional.empty(), Optional.empty()));
        var controller = new JobWindowController();
        controller.setAdminWindowBypass(new AdminWindowBypass(rbac));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v2/job-window").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-admin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.admin_window_bypass").value(true))
            .andExpect(jsonPath("$.business_hours.start").value("08:00"))
            .andExpect(jsonPath("$.business_hours.end").value("20:00"))
            .andExpect(jsonPath("$.business_hours.inside").isBoolean())
            .andExpect(jsonPath("$.next_window_start").exists());
        mvc.perform(get("/api/v2/job-window").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-operator"))
            .andExpect(jsonPath("$.admin_window_bypass").value(false));
        mvc.perform(get("/api/v2/job-window").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-admin")
                .requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true))
            .andExpect(jsonPath("$.admin_window_bypass").value(false));
    }
}
