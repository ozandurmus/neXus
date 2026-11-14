package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChain;
import com.securityexpert.nexus.ui2.service.security.GateOutcome;
import com.securityexpert.nexus.ui2.service.security.GateRequest;
import com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver;
import com.securityexpert.nexus.ui2.service.security.LocalMechanism;
import com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

class MachineSessionControllerTest {
    private final LocalCredentialsRepository identities = mock(LocalCredentialsRepository.class);
    private final LocalRoleTokenResolver roles = mock(LocalRoleTokenResolver.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final LoginFlow flow = new LoginFlow(sessions, Duration.ofMinutes(30), Duration.ofHours(10));
    private final SessionCookieWriter cookies = new SessionCookieWriter(true);
    private final String token = UUID.randomUUID().toString();
    private final String probeToken = UUID.randomUUID().toString();

    private String digest() throws Exception {
        return digestOf(token);
    }

    private static String digestOf(String raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private MachineSessionController controller(String digest) {
        return new MachineSessionController(digest, identities, roles, sessions, flow, cookies);
    }

    private MachineSessionController controller(String e2eDigest, String probeDigest) {
        return new MachineSessionController(e2eDigest, probeDigest, identities, roles, sessions, flow, cookies);
    }

    @Test
    void disabledAndWrongTokenNeverCreateSession() throws Exception {
        assertEquals(404, controller("").login(token, new MockHttpServletResponse()).getStatusCode().value());
        assertEquals(403, controller(digest()).login(UUID.randomUUID().toString(),
                new MockHttpServletResponse()).getStatusCode().value());
        verify(sessions).auditMachineRefusal("machine_session_token_refused");
        verify(sessions, never()).createMachineActive(anyString(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void exactRolesCreateShortMachineSessionAndNormalCookie() throws Exception {
        var identity = new LocalCredentialRecord("synthetic-id", "aiview-e2e", null, null, null,
                0, 0, 0, 0, Optional.empty(), Instant.now(), Instant.now(), true,
                "synthetic-actor", Instant.now(), false);
        when(identities.findByName("aiview-e2e")).thenReturn(Optional.of(identity));
        when(roles.resolve("synthetic-id")).thenReturn(List.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER));
        when(sessions.findActiveByActor(anyString(), any(Instant.class))).thenReturn(Optional.empty());
        when(sessions.createMachineActive(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Instant now = invocation.getArgument(3);
                    Duration idle = invocation.getArgument(4);
                    Duration absolute = invocation.getArgument(5);
                    assertEquals(Duration.ofMinutes(30), idle);
                    assertEquals(Duration.ofMinutes(30), absolute);
                    return new SessionRecord(invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2), SessionState.ACTIVE, now, now, now.plus(idle),
                            now.plus(absolute), Optional.empty(), Optional.empty(), Optional.empty(), true);
                });
        var response = new MockHttpServletResponse();
        var result = controller(digest()).login(token, response);
        assertEquals(200, result.getStatusCode().value());
        assertNotNull(result.getBody().get("csrf_token"));
        assertEquals("ui2_session", response.getCookies()[0].getName());
        assertTrue(response.getCookies()[0].isHttpOnly());
        assertTrue(response.getCookies()[0].getSecure());
        when(roles.resolve("synthetic-id")).thenReturn(List.of(RoleToken.VIEWER,
                RoleToken.REPLAY_VIEWER, RoleToken.OPERATOR));
        assertEquals(403, controller(digest()).login(token, new MockHttpServletResponse())
                .getStatusCode().value());
        verify(sessions).auditMachineRefusal("machine_session_roles_refused");
    }

    @Test
    void machineLoginSupersedesExistingSession() {
        Instant now = Instant.now();
        SessionRecord prior = new SessionRecord("synthetic-prior", "synthetic-actor", "synthetic-csrf",
                SessionState.ACTIVE, now.minusSeconds(20), now.minusSeconds(20), now.plusSeconds(600),
                now.plusSeconds(1800), Optional.empty(), Optional.empty(), Optional.empty(), true);
        when(sessions.findActiveByActor("synthetic-actor", now)).thenReturn(Optional.of(prior));
        when(sessions.takeoverMachine(eq(prior.sessionId()), anyString(), eq("synthetic-actor"),
                anyString(), eq(now), eq(Duration.ofMinutes(30)), eq(Duration.ofMinutes(30))))
                .thenAnswer(invocation -> new SessionRecord(invocation.getArgument(1), "synthetic-actor",
                        invocation.getArgument(3), SessionState.ACTIVE, now, now, now.plusSeconds(1800),
                        now.plusSeconds(1800), Optional.empty(), Optional.empty(), Optional.empty(), true));
        assertTrue(flow.machineLogin("synthetic-actor", now).session().machine());
        verify(sessions).takeoverMachine(eq(prior.sessionId()), anyString(), eq("synthetic-actor"),
                anyString(), eq(now), eq(Duration.ofMinutes(30)), eq(Duration.ofMinutes(30)));
    }

    @Test
    void secondPurposeDoesNotEndFirstSessionUnderOneTokenOrSeparateProbeToken() throws Exception {
        var identity = new LocalCredentialRecord("synthetic-id", "aiview-e2e", null, null, null,
                0, 0, 0, 0, Optional.empty(), Instant.now(), Instant.now(), true,
                "synthetic-actor", Instant.now(), true);
        when(identities.findByName("aiview-e2e")).thenReturn(Optional.of(identity));
        when(identities.findAll()).thenReturn(List.of(identity));
        when(roles.resolve("synthetic-id")).thenReturn(List.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER));

        Map<String, SessionRecord> activeByActor = new HashMap<>();
        Map<String, SessionRecord> bySessionId = new HashMap<>();
        when(sessions.findActiveByActor(anyString(), any(Instant.class)))
                .thenAnswer(inv -> Optional.ofNullable(activeByActor.get(inv.getArgument(0)))
                        .filter(s -> s.state() == SessionState.ACTIVE));
        when(sessions.findBySessionId(anyString(), any(Instant.class)))
                .thenAnswer(inv -> Optional.ofNullable(bySessionId.get(inv.getArgument(0))));
        when(sessions.createMachineActive(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    String sid = inv.getArgument(0);
                    String actor = inv.getArgument(1);
                    String csrf = inv.getArgument(2);
                    Instant now = inv.getArgument(3);
                    Duration idle = inv.getArgument(4);
                    Duration abs = inv.getArgument(5);
                    SessionRecord created = new SessionRecord(sid, actor, csrf, SessionState.ACTIVE,
                            now, now, now.plus(idle), now.plus(abs),
                            Optional.empty(), Optional.empty(), Optional.empty(), true);
                    activeByActor.put(actor, created);
                    bySessionId.put(sid, created);
                    return created;
                });
        when(sessions.takeoverMachine(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    String priorId = inv.getArgument(0);
                    String newId = inv.getArgument(1);
                    String actor = inv.getArgument(2);
                    String csrf = inv.getArgument(3);
                    Instant now = inv.getArgument(4);
                    Duration idle = inv.getArgument(5);
                    Duration abs = inv.getArgument(6);
                    SessionRecord prior = bySessionId.get(priorId);
                    bySessionId.put(priorId, new SessionRecord(prior.sessionId(), prior.actorFingerprint(),
                            prior.csrfSecret(), SessionState.SUPERSEDED, prior.createdAt(), prior.lastSeenAt(),
                            prior.idleDeadlineAt(), prior.absoluteExpiresAt(), Optional.of(newId),
                            Optional.empty(), Optional.empty(), true));
                    SessionRecord created = new SessionRecord(newId, actor, csrf, SessionState.ACTIVE,
                            now, now, now.plus(idle), now.plus(abs),
                            Optional.empty(), Optional.empty(), Optional.empty(), true);
                    activeByActor.put(actor, created);
                    bySessionId.put(newId, created);
                    return created;
                });

        String e2eActor = LocalMechanism.actorFingerprintFor("synthetic-id");
        String probeActor = LocalMechanism.probeActorFingerprintFor("synthetic-id");
        assertNotEquals(e2eActor, probeActor);

        // 1. Single-token per-purpose isolation: e2e login then probe login.
        var single = controller(digest());
        assertEquals(200, single.login(token, new MockHttpServletResponse()).getStatusCode().value());
        SessionRecord e2eSession = activeByActor.get(e2eActor);
        assertNotNull(e2eSession);
        assertEquals(SessionState.ACTIVE, bySessionId.get(e2eSession.sessionId()).state());

        assertEquals(200, single.login(token, MachineSessionController.PURPOSE_PROBE,
                new MockHttpServletResponse()).getStatusCode().value());
        SessionRecord firstProbeSession = activeByActor.get(probeActor);
        assertNotNull(firstProbeSession);
        assertNotEquals(e2eSession.sessionId(), firstProbeSession.sessionId());
        // The running e2e session is untouched and still ACTIVE.
        assertEquals(SessionState.ACTIVE, bySessionId.get(e2eSession.sessionId()).state());
        verify(sessions, never()).takeoverMachine(eq(e2eSession.sessionId()), anyString(), anyString(),
                anyString(), any(), any(), any());

        // 2. Separate probe token: supersedes the prior probe session, never the e2e session.
        var dual = controller(digest(), digestOf(probeToken));
        assertEquals(200, dual.login(probeToken, new MockHttpServletResponse()).getStatusCode().value());
        SessionRecord secondProbeSession = activeByActor.get(probeActor);
        assertNotEquals(firstProbeSession.sessionId(), secondProbeSession.sessionId());
        assertEquals(SessionState.SUPERSEDED, bySessionId.get(firstProbeSession.sessionId()).state());
        assertEquals(SessionState.ACTIVE, bySessionId.get(secondProbeSession.sessionId()).state());
        assertEquals(SessionState.ACTIVE, bySessionId.get(e2eSession.sessionId()).state());
        verify(sessions).takeoverMachine(eq(firstProbeSession.sessionId()), eq(secondProbeSession.sessionId()),
                eq(probeActor), anyString(), any(), eq(Duration.ofMinutes(30)), eq(Duration.ofMinutes(30)));

        // 3. Both actors resolve back to the same local identity and keep every machine-session restriction.
        var resolver = new LocalIdentityResolver(identities);
        assertEquals(Optional.of(identity), resolver.resolve(e2eActor));
        assertEquals(Optional.of(identity), resolver.resolve(probeActor));
    }

    @Test
    void probeSessionEnforcesReadOnlyRestrictionsAndAuditsRefusals() throws Exception {
        var identity = new LocalCredentialRecord("synthetic-id", "aiview-e2e", null, null, null,
                0, 0, 0, 0, Optional.empty(), Instant.now(), Instant.now(), true,
                "synthetic-actor", Instant.now(), true);
        when(identities.findByName("aiview-e2e")).thenReturn(Optional.of(identity));
        when(identities.findAll()).thenReturn(List.of(identity));
        when(roles.resolve("synthetic-id")).thenReturn(List.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER));
        when(sessions.findActiveByActor(anyString(), any(Instant.class))).thenReturn(Optional.empty());
        Map<String, SessionRecord> bySessionId = new HashMap<>();
        when(sessions.findBySessionId(anyString(), any(Instant.class)))
                .thenAnswer(inv -> Optional.ofNullable(bySessionId.get(inv.getArgument(0))));
        when(sessions.createMachineActive(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Instant now = inv.getArgument(3);
                    Duration idle = inv.getArgument(4);
                    Duration abs = inv.getArgument(5);
                    var created = new SessionRecord(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                            SessionState.ACTIVE, now, now, now.plus(idle), now.plus(abs),
                            Optional.empty(), Optional.empty(), Optional.empty(), true);
                    bySessionId.put(created.sessionId(), created);
                    return created;
                });

        var dual = controller(digest(), digestOf(probeToken));
        // Probe token cannot claim e2e purpose; unknown purposes and shared digests are refused and audited.
        assertEquals(403, dual.login(probeToken, MachineSessionController.PURPOSE_E2E,
                new MockHttpServletResponse()).getStatusCode().value());
        assertEquals(403, dual.login(token, "admin", new MockHttpServletResponse()).getStatusCode().value());
        assertEquals(403, controller(digest(), digest()).login(token,
                new MockHttpServletResponse()).getStatusCode().value());
        verify(sessions, times(3)).auditMachineRefusal("machine_session_token_refused");

        // Probe login succeeds with exact roles and its session is restricted by GateChain on writes.
        var probeResp = new MockHttpServletResponse();
        assertEquals(200, dual.login(probeToken, probeResp).getStatusCode().value());
        String rawCookie = probeResp.getCookies()[0].getValue();
        var chain = new GateChain(sessions, new ActionRegistry(), mock(RbacEvaluator.class),
                mock(AuthzDecisionRepository.class), identities, true);
        var outcome = chain.evaluate(new GateRequest("DELETE", Optional.of(rawCookie),
                Optional.of("csrf"), Optional.of("https://ui2.example"), ActionRegistry.DEVICE_DELETE,
                Optional.empty()), Instant.now());
        assertInstanceOf(GateOutcome.Refused.class, outcome);
        assertEquals(403, ((GateOutcome.Refused) outcome).httpStatus());
        assertEquals("MACHINE_SESSION_READ_ONLY", ((GateOutcome.Refused) outcome).body().get("error"));
        verify(sessions).auditMachineRefusal("machine_session_read_only");
    }
}
