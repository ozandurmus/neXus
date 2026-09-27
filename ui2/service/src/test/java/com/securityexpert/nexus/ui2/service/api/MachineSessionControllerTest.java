package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;

class MachineSessionControllerTest {
    private final LocalCredentialsRepository identities = mock(LocalCredentialsRepository.class);
    private final LocalRoleTokenResolver roles = mock(LocalRoleTokenResolver.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final LoginFlow flow = new LoginFlow(sessions, Duration.ofMinutes(30), Duration.ofHours(10));
    private final SessionCookieWriter cookies = new SessionCookieWriter(true);
    private final String token = UUID.randomUUID().toString();

    private String digest() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private MachineSessionController controller(String digest) {
        return new MachineSessionController(digest, identities, roles, sessions, flow, cookies);
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
}
