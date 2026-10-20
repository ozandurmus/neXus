package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/** PO decision 2026-10-20: who gets the administrator's window bypass, and that it lives for one request only. */
class ManualAdmissionInterceptorTest {
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final AdminWindowBypass bypass = new AdminWindowBypass(rbac);
    private final ManualAdmissionInterceptor interceptor = new ManualAdmissionInterceptor(bypass);

    @AfterEach void clear() { ManualAdmission.close(); }

    private void roles(String actor, Set<String> tokens, String authority) {
        when(rbac.evaluate(eq(actor), any(), any())).thenAnswer(call -> {
            Optional<String> token = call.getArgument(1);
            return new RbacEvaluator.Decision(token.filter(tokens::contains).isPresent() ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                    Optional.of(authority), Optional.empty(), Optional.empty());
        });
    }

    @Test void onlySecurityAndOperationAdminsHoldTheBypassNeverAReplayViewer() {
        roles("sec", Set.of(RoleToken.SECURITY_ADMIN), "synthetic-authority");
        roles("ops", Set.of(RoleToken.OPERATION_ADMIN), "synthetic-authority");
        roles("both", Set.of(RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN), "synthetic-authority");
        roles("operator", Set.of(RoleToken.OPERATOR, RoleToken.VIEWER), "synthetic-authority");
        roles("aiview", Set.of(RoleToken.REPLAY_VIEWER), "synthetic-authority");
        roles("aiview-admin", Set.of(RoleToken.REPLAY_VIEWER, RoleToken.SECURITY_ADMIN), "synthetic-authority");
        assertEquals(Optional.of(ManualAdmission.SECURITY_ADMIN), bypass.role("sec"));
        assertEquals(Optional.of(ManualAdmission.OPERATION_ADMIN), bypass.role("ops"));
        assertEquals(Optional.of(ManualAdmission.SECURITY_ADMIN), bypass.role("both"));
        assertEquals(Optional.empty(), bypass.role("operator"));
        assertEquals(Optional.empty(), bypass.role("aiview"));
        assertEquals(Optional.empty(), bypass.role("aiview-admin"));
        assertEquals(Optional.empty(), bypass.role(null));
    }

    @Test void theRootAdministratorIsAnAdminNotAReplayViewerAndAnUnevaluableActorGetsNoBypass() {
        when(rbac.evaluate(eq("root"), any(), any())).thenReturn(new RbacEvaluator.Decision(AuthzOutcome.PERMITTED,
                Optional.of(RbacEvaluator.ROOT_AUTHORITY), Optional.empty(), Optional.empty()));
        assertEquals(Optional.of(ManualAdmission.SECURITY_ADMIN), bypass.role("root"));
        when(rbac.evaluate(eq("broken"), any(), any())).thenThrow(new IllegalStateException("directory down"));
        assertEquals(Optional.empty(), bypass.role("broken"));
    }

    private MockHttpServletRequest request(String method, String actor) {
        var request = new MockHttpServletRequest(method, "/devices/device-1/inventory/collect");
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, actor);
        return request;
    }

    @Test void aMutatingAdminRequestOpensTheGrantWithTheAcknowledgementAndCompletionClosesIt() {
        roles("sec", Set.of(RoleToken.SECURITY_ADMIN), "synthetic-authority");
        var response = new MockHttpServletResponse();
        var plain = request("POST", "sec");
        assertTrue(interceptor.preHandle(plain, response, new Object()));
        assertEquals(Optional.of(new ManualAdmission.Grant("sec", ManualAdmission.SECURITY_ADMIN, false)), ManualAdmission.current());
        interceptor.afterCompletion(plain, response, new Object(), null);
        assertTrue(ManualAdmission.current().isEmpty());

        var header = request("POST", "sec");
        header.addHeader(ManualAdmission.ACK_HEADER, "true");
        interceptor.preHandle(header, response, new Object());
        assertTrue(ManualAdmission.current().orElseThrow().acknowledged());
        interceptor.afterCompletion(header, response, new Object(), new RuntimeException("handler failed"));
        assertTrue(ManualAdmission.current().isEmpty(), "closed even when the handler threw");

        var query = request("PUT", "sec");
        query.setQueryString("reason=drill&business_hours_ack=true");
        interceptor.preHandle(query, response, new Object());
        assertTrue(ManualAdmission.current().orElseThrow().acknowledged());
        interceptor.afterCompletion(query, response, new Object(), null);

        var notTrue = request("POST", "sec");
        notTrue.setQueryString("business_hours_ack=yes");
        notTrue.addHeader(ManualAdmission.ACK_HEADER, "1");
        interceptor.preHandle(notTrue, response, new Object());
        assertFalse(ManualAdmission.current().orElseThrow().acknowledged());
    }

    @Test void readsOperatorsReplayViewersAndUnauthenticatedRequestsNeverGetAGrant() {
        roles("sec", Set.of(RoleToken.SECURITY_ADMIN), "synthetic-authority");
        roles("operator", Set.of(RoleToken.OPERATOR), "synthetic-authority");
        var response = new MockHttpServletResponse();
        interceptor.preHandle(request("GET", "sec"), response, new Object());
        assertTrue(ManualAdmission.current().isEmpty());
        interceptor.preHandle(request("POST", "operator"), response, new Object());
        assertTrue(ManualAdmission.current().isEmpty());
        var replay = request("POST", "sec");
        replay.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        interceptor.preHandle(replay, response, new Object());
        assertTrue(ManualAdmission.current().isEmpty());
        interceptor.preHandle(new MockHttpServletRequest("POST", "/login"), response, new Object());
        assertTrue(ManualAdmission.current().isEmpty());
    }

    @Test void aGrantLeftOnAPooledThreadIsClearedByTheNextRequest() {
        ManualAdmission.open(new ManualAdmission.Grant("stale", ManualAdmission.OPERATION_ADMIN, true));
        when(rbac.evaluate(anyString(), any(), any())).thenReturn(new RbacEvaluator.Decision(AuthzOutcome.DENIED,
                Optional.of("synthetic-authority"), Optional.empty(), Optional.empty()));
        interceptor.preHandle(request("POST", "operator"), new MockHttpServletResponse(), new Object());
        assertTrue(ManualAdmission.current().isEmpty());
    }

    @Test void theGrantNeverReachesAnotherThread() throws Exception {
        roles("sec", Set.of(RoleToken.SECURITY_ADMIN), "synthetic-authority");
        interceptor.preHandle(request("POST", "sec"), new MockHttpServletResponse(), new Object());
        var seen = new AtomicReference<Optional<ManualAdmission.Grant>>();
        var worker = new Thread(() -> seen.set(ManualAdmission.current()));
        worker.start();
        worker.join();
        assertEquals(Optional.empty(), seen.get(), "schedulers and workers never inherit an administrator's grant");
    }
}
