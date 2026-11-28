package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * The root identity ({@code nexusadmin}) is bound to every role token, {@code role:replay_viewer} included (ROLE-1,
 * {@code FirstBootIdentitySeedingRunner}). {@link RouteActionGate} must still treat it as the unmasked administrator,
 * exactly as {@link GateChain#isReplayViewer} already excludes root authority; a non-root identity holding
 * {@code role:replay_viewer} (the {@code aiview} persona) stays masked, and an identity that cannot be resolved still
 * fails toward masking.
 */
class RouteActionGateRootReplayViewerTest {

    private static final String ROUTE = "GET /ok";

    private static LocalCredentialRecord record(String identityId) {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        return new LocalCredentialRecord(identityId, identityId, new byte[0], new byte[0], "synthetic", 1, 1, 1, 0,
                Optional.empty(), at, at, true, "synthetic-creator", at, false);
    }

    private static Object replayViewerAttribute(String identityId, boolean root, List<String> tokens, boolean resolverFails)
            throws Exception {
        String cookie = "cookie-" + identityId;
        String actor = LocalMechanism.actorFingerprintFor(identityId);
        Instant now = Instant.now();
        SessionRepository sessions = mock(SessionRepository.class);
        when(sessions.findBySessionId(anyString(), any(Instant.class))).thenReturn(Optional.of(new SessionRecord(
                SessionHasher.hash(cookie), actor, "csrf", SessionState.ACTIVE, now.minusSeconds(60), now,
                now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty(), false)));
        AuthzDecisionRepository decisions = mock(AuthzDecisionRepository.class);
        when(decisions.insert(anyString(), anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn(1L);

        RbacEvaluator rbac = mock(RbacEvaluator.class);
        when(rbac.evaluate(anyString(), any(), any(Instant.class))).thenAnswer(inv -> {
            Optional<String> token = inv.getArgument(1);
            if (root) return RbacEvaluator.Decision.permittedRoot();
            if (token.isEmpty()) return RbacEvaluator.Decision.noApplicableAuthority();
            return tokens.contains(token.get()) ? RbacEvaluator.Decision.permittedLocal("binding")
                    : RbacEvaluator.Decision.deniedLocalUnbound();
        });
        when(rbac.evaluateAny(anyString(), any(), any(Instant.class)))
                .thenReturn(root ? RbacEvaluator.Decision.permittedRoot() : RbacEvaluator.Decision.permittedLocal("binding"));

        LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
        when(credentials.findAll()).thenReturn(List.of(record(identityId)));
        LocalRoleTokenResolver roles = mock(LocalRoleTokenResolver.class);
        if (resolverFails) {
            when(roles.resolve(anyString())).thenThrow(new IllegalStateException("synthetic resolution failure"));
        } else {
            when(roles.resolve(anyString())).thenReturn(tokens);
        }
        GateChain gateChain = new GateChain(sessions, new ActionRegistry(), rbac, decisions, credentials, true);
        RouteActionGate gate = new RouteActionGate(gateChain, Map.of(ROUTE, ActionRegistry.DEVICE_READ), Set.of(),
                new LocalIdentityResolver(credentials), roles);

        var request = new MockHttpServletRequest("GET", "/ok");
        request.setServletPath("/ok");
        request.setCookies(new Cookie("ui2_session", cookie));
        RouteActionGate.Verdict verdict = gate.decide(request, false);
        assertTrue(verdict.permitted(), "the synthetic session must pass the gate chain");
        return verdict.attributes().get(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE);
    }

    @Test
    void theRootIdentityBoundToEveryRoleTokenIsNotTheMaskedPersona() throws Exception {
        assertNull(replayViewerAttribute("identity-root", true, List.of(RoleToken.values()), false));
    }

    @Test
    void aNonRootIdentityHoldingReplayViewerStaysMasked() throws Exception {
        assertEquals(true, replayViewerAttribute("identity-aiview", false,
                List.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER), false));
    }

    @Test
    void aNonRootIdentityWithoutReplayViewerIsNotMasked() throws Exception {
        assertNull(replayViewerAttribute("identity-viewer", false, List.of(RoleToken.VIEWER), false));
    }

    @Test
    void anUnresolvableIdentityStillFailsTowardMasking() throws Exception {
        assertEquals(true, replayViewerAttribute("identity-broken", false, List.of(RoleToken.VIEWER), true));
        assertEquals(true, replayViewerAttribute("identity-root-broken", true, List.of(RoleToken.values()), true));
    }
}
