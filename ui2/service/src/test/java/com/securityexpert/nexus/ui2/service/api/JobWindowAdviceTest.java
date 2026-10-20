package com.securityexpert.nexus.ui2.service.api;

import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.AdminWindowBypass;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.ManualAdmissionInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JobWindowAdviceTest {
    /** 13:00 Istanbul: outside the 12:00 window, inside business hours. */
    private static final JobWindowPolicy OUTSIDE_IN_HOURS = new JobWindowPolicy(
            Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC), 60,
            new BusinessHours(BusinessHours.DEFAULT_START, BusinessHours.DEFAULT_END));

    @RestController
    static class Submission {
        @PostMapping("/synthetic-job") public void submit() { OUTSIDE_IN_HOURS.requireOpenFor(actor()); }
        private static String actor() {
            return ManualAdmission.current().map(ManualAdmission.Grant::actorFingerprint).orElse("synthetic-operator");
        }
    }
    @Test void manualRefusalHasSafeMessageCodeAndZonedNextStart() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Submission()).setControllerAdvice(new JobWindowAdvice()).build();
        mvc.perform(post("/synthetic-job"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("OUTSIDE_JOB_WINDOW"))
            .andExpect(jsonPath("$.next_window_start").value("2026-10-08T18:00+03:00[Europe/Istanbul]"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Device jobs are available")));
    }

    /** PO 2026-10-20, through the real interceptor: the administrator confirms inside business hours, operators keep the window. */
    @Test void anAdministratorIsAskedToConfirmInsideBusinessHoursAndTheAcknowledgementAdmits() throws Exception {
        var rbac = mock(RbacEvaluator.class);
        when(rbac.evaluate(eq("synthetic-admin"), any(), any())).thenAnswer(call -> new RbacEvaluator.Decision(
                Optional.of(RoleToken.OPERATION_ADMIN).equals(call.getArgument(1)) ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                Optional.of("synthetic-authority"), Optional.empty(), Optional.empty()));
        when(rbac.evaluate(eq("synthetic-operator"), any(), any())).thenAnswer(call -> new RbacEvaluator.Decision(
                Optional.of(RoleToken.OPERATOR).equals(call.getArgument(1)) ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                Optional.of("synthetic-authority"), Optional.empty(), Optional.empty()));
        var mvc = MockMvcBuilders.standaloneSetup(new Submission()).setControllerAdvice(new JobWindowAdvice())
                .addInterceptors(new ManualAdmissionInterceptor(new AdminWindowBypass(rbac))).build();
        mvc.perform(post("/synthetic-job").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-admin"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUSINESS_HOURS_CONFIRM_REQUIRED"))
            .andExpect(jsonPath("$.message").value("Business hours (08:00–20:00 GMT+3). This sends commands to the device. Continue?"))
            .andExpect(jsonPath("$.business_hours_start").value("08:00"))
            .andExpect(jsonPath("$.business_hours_end").value("20:00"))
            .andExpect(jsonPath("$.zone").value("Europe/Istanbul"))
            .andExpect(jsonPath("$.ack_header").value("X-Nexus-Business-Hours-Ack"));
        mvc.perform(post("/synthetic-job").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-admin")
                .header(ManualAdmission.ACK_HEADER, "true"))
            .andExpect(status().isOk());
        mvc.perform(post("/synthetic-job?business_hours_ack=true")
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-admin"))
            .andExpect(status().isOk());
        mvc.perform(post("/synthetic-job").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-operator")
                .header(ManualAdmission.ACK_HEADER, "true"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("OUTSIDE_JOB_WINDOW"));
        org.junit.jupiter.api.Assertions.assertTrue(ManualAdmission.current().isEmpty());
    }
}
