package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver;
import com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver;
import com.securityexpert.nexus.ui2.service.security.SessionHasher;

class SessionStatusControllerTest {

    @Test
    void machineSessionDoesNotRequireItsUnusedPasswordToChange() {
        SessionRepository sessions = mock(SessionRepository.class);
        LocalIdentityResolver identities = mock(LocalIdentityResolver.class);
        LocalRoleTokenResolver roles = mock(LocalRoleTokenResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("ui2_session", "synthetic-cookie")});
        Instant now = Instant.now();
        SessionRecord machine = new SessionRecord(SessionHasher.hash("synthetic-cookie"), "synthetic-actor",
                "synthetic-csrf", SessionState.ACTIVE, now.minusSeconds(10), now,
                now.plusSeconds(600), now.plusSeconds(1800), Optional.empty(), Optional.empty(),
                Optional.empty(), true);
        when(sessions.findBySessionId(machine.sessionId())).thenReturn(Optional.of(machine));
        when(identities.resolve("synthetic-actor")).thenReturn(Optional.of(new LocalCredentialRecord(
                "synthetic-id", "aiview-e2e", null, null, null, 0, 0, 0, 0,
                Optional.empty(), now, now, true, "synthetic-actor", now, true)));
        when(roles.resolve("synthetic-id")).thenReturn(List.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER));
        var controller = new SessionStatusController(sessions, identities, roles, true);
        var rbac = mock(com.securityexpert.nexus.ui2.service.security.RbacEvaluator.class);
        controller.setRbacEvaluator(rbac);
        when(rbac.evaluate(org.mockito.ArgumentMatchers.eq("synthetic-actor"),
                org.mockito.ArgumentMatchers.eq(Optional.of(RoleToken.SECURITY_ADMIN)),org.mockito.ArgumentMatchers.any()))
            .thenReturn(new com.securityexpert.nexus.ui2.service.security.RbacEvaluator.Decision(
                com.securityexpert.nexus.ui2.platform.AuthzOutcome.DENIED,Optional.empty(),Optional.empty(),Optional.empty()));
        var response = controller.status(request);
        assertFalse((Boolean)response.getBody().get("can_read_administration"));
        when(rbac.evaluate(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
            .thenReturn(new com.securityexpert.nexus.ui2.service.security.RbacEvaluator.Decision(
                com.securityexpert.nexus.ui2.platform.AuthzOutcome.PERMITTED,Optional.empty(),Optional.empty(),Optional.empty()));
        assertTrue((Boolean)controller.status(request).getBody().get("can_read_administration"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("must_change_password"));
    }

    @Test
    void statusReturnsUnauthenticatedWhenNoCookie() {
        SessionRepository sessionRepo = mock(SessionRepository.class);
        LocalIdentityResolver identityResolver = mock(LocalIdentityResolver.class);
        LocalRoleTokenResolver roleResolver = mock(LocalRoleTokenResolver.class);

        SessionStatusController controller = new SessionStatusController(sessionRepo, identityResolver, roleResolver, false);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.status(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertFalse((Boolean) response.getBody().get("authenticated"));
        assertNull(response.getBody().get("csrf_token"));
    }

    @Test
    void statusReturnsAuthenticatedWithCsrfToken() {
        SessionRepository sessionRepo = mock(SessionRepository.class);
        LocalIdentityResolver identityResolver = mock(LocalIdentityResolver.class);
        LocalRoleTokenResolver roleResolver = mock(LocalRoleTokenResolver.class);

        SessionStatusController controller = new SessionStatusController(sessionRepo, identityResolver, roleResolver, false);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("ui2_session", "raw_cookie_value")});

        String sessionId = SessionHasher.hash("raw_cookie_value");
        SessionRecord session = new SessionRecord(sessionId, "actor123", "secret_csrf_token",
                SessionState.ACTIVE, Instant.now().minusSeconds(10), Instant.now(),
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                Optional.empty(), Optional.empty(), Optional.empty());

        when(sessionRepo.findBySessionId(sessionId)).thenReturn(Optional.of(session));

        LocalCredentialRecord identity = new LocalCredentialRecord("id123", "User Name", null, null, null,
                0, 0, 0, 0, Optional.empty(), Instant.now(), Instant.now(), false, "admin", Instant.now(), false);
        when(identityResolver.resolve("actor123")).thenReturn(Optional.of(identity));

        when(roleResolver.resolve("id123")).thenReturn(List.of(RoleToken.SECURITY_ADMIN));

        ResponseEntity<Map<String, Object>> response = controller.status(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue((Boolean) response.getBody().get("authenticated"));
        assertEquals("secret_csrf_token", response.getBody().get("csrf_token"));
        assertEquals("User Name", response.getBody().get("display_name"));
        assertEquals(List.of("role:security_admin"), response.getBody().get("role_tokens"));
    }

    private static final String PROFILE = "synthetic-profile";
    private static final String BIND_DN = "CN=Synthetic Person,OU=People,DC=example,DC=local";

    /** A directory session shown through the real status endpoint; {@code displayName} is what V211 stores (null before it). */
    private static Map<String, Object> directoryStatus(String displayName) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var cipher = com.securityexpert.nexus.ui2.platform.GroupReferenceCipher.fromBase64Key(
                java.util.Base64.getEncoder().encodeToString(key), "synthetic-key");
        SessionRepository sessions = mock(SessionRepository.class);
        LocalIdentityResolver identities = mock(LocalIdentityResolver.class);
        var actors = mock(com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository.class);
        var bindings = mock(com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("ui2_session", "synthetic-cookie")});
        Instant now = Instant.now();
        SessionRecord session = new SessionRecord(SessionHasher.hash("synthetic-cookie"), "synthetic-directory-actor", "synthetic-csrf",
                SessionState.ACTIVE, now.minusSeconds(10), now, now.plusSeconds(600), now.plusSeconds(1800),
                Optional.empty(), Optional.empty(), Optional.empty());
        when(sessions.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(identities.resolve("synthetic-directory-actor")).thenReturn(Optional.empty());
        when(actors.find("synthetic-directory-actor")).thenReturn(Optional.of(
                new com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord("synthetic-directory-actor",
                        java.util.Set.of("synthetic-group"), now, now.plusSeconds(300), PROFILE,
                        cipher.encryptDirectory(BIND_DN, PROFILE, com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_PRINCIPAL),
                        cipher.keyId(), displayName)));
        // A role bound to the bind DN: it must still match, whatever name is shown.
        var kind = com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_PRINCIPAL;
        when(bindings.findAllActive()).thenReturn(List.of(new com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord(
                "synthetic-binding", RoleToken.OPERATOR, cipher.encryptDirectory(BIND_DN, PROFILE, kind), cipher.keyId(),
                "synthetic-admin", now, Optional.empty(), Optional.empty(), kind, PROFILE)));
        var controller = new SessionStatusController(sessions, identities, mock(LocalRoleTokenResolver.class), false, actors, bindings, cipher, null);
        var rbac = mock(com.securityexpert.nexus.ui2.service.security.RbacEvaluator.class);
        controller.setRbacEvaluator(rbac);
        when(rbac.evaluate(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.securityexpert.nexus.ui2.service.security.RbacEvaluator.Decision(
                        com.securityexpert.nexus.ui2.platform.AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty()));
        return controller.status(request).getBody();
    }

    @Test
    void directoryUserIsShownByTheAccountNameNotTheBindDnAndTheDnBindingStillGrantsItsRole() {
        Map<String, Object> body = directoryStatus("jdoe");
        assertEquals("jdoe", body.get("display_name"));
        assertEquals(List.of(RoleToken.OPERATOR), body.get("role_tokens"));
        assertFalse(body.toString().contains("CN="), "the bind DN is not part of the response");
    }

    @Test
    void aDirectoryRowWrittenBeforeTheDisplayNameColumnKeepsTheEarlierValueUntilTheNextSignIn() {
        Map<String, Object> body = directoryStatus(null);
        assertEquals(BIND_DN, body.get("display_name"));
        assertEquals(List.of(RoleToken.OPERATOR), body.get("role_tokens"));
    }
}
