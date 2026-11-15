package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.securityexpert.nexus.ui2.persistence.identity.SessionEndReason;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;

/** Migration plan Step 2: the {@code ui2_session} cookie is bridged into the SecurityContext, additively and read-only. */
class Ui2SessionAuthenticationFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final String RAW = "synthetic-cookie-value";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static SessionRecord session(SessionState state, Instant idle, Instant absolute, boolean machine) {
        return new SessionRecord(SessionHasher.hash(RAW), "actor-1", "csrf-secret", state, NOW.minusSeconds(60),
                NOW.minusSeconds(10), idle, absolute, Optional.empty(), Optional.empty(), Optional.empty(), machine);
    }

    private record Seen(Authentication authentication, boolean chainRan) {
    }

    private static Seen run(SessionRepository repository, MockHttpServletRequest request) throws Exception {
        Ui2SessionAuthenticationFilter filter = new Ui2SessionAuthenticationFilter(repository, () -> NOW);
        AtomicReference<Authentication> seen = new AtomicReference<>();
        boolean[] ran = {false};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                ran[0] = true;
                seen.set(SecurityContextHolder.getContext().getAuthentication());
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return new Seen(seen.get(), ran[0]);
    }

    private static MockHttpServletRequest withCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/devices");
        request.setCookies(new Cookie("ui2_session", RAW));
        return request;
    }

    @Test
    void anActiveSessionPopulatesTheContextForTheRequestAndTheContextIsClearedAfterwards() throws Exception {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.findBySessionId(SessionHasher.hash(RAW)))
                .thenReturn(Optional.of(session(SessionState.ACTIVE, NOW.plusSeconds(1800), NOW.plusSeconds(36000), false)));

        Seen seen = run(repository, withCookie());

        assertTrue(seen.chainRan());
        Ui2SessionAuthenticationToken token = (Ui2SessionAuthenticationToken) seen.authentication();
        assertNotNull(token);
        assertTrue(token.isAuthenticated());
        assertEquals("actor-1", token.getPrincipal());
        assertEquals(SessionHasher.hash(RAW), token.sessionId());
        assertFalse(token.machine());
        assertNull(token.getCredentials());
        assertTrue(token.getAuthorities().isEmpty(), "authorization stays in GateChain");
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "cleared after the request");
    }

    @Test
    void aMachineSessionIsMarkedAsOne() throws Exception {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.findBySessionId(SessionHasher.hash(RAW)))
                .thenReturn(Optional.of(session(SessionState.ACTIVE, NOW.plusSeconds(1800), NOW.plusSeconds(36000), true)));
        assertTrue(((Ui2SessionAuthenticationToken) run(repository, withCookie()).authentication()).machine());
    }

    @Test
    void everyOtherCaseLeavesTheRequestUnauthenticatedAndStillRunsTheChain() throws Exception {
        SessionRepository repository = mock(SessionRepository.class);
        String id = SessionHasher.hash(RAW);
        // unknown session
        when(repository.findBySessionId(id)).thenReturn(Optional.empty());
        assertNull(run(repository, withCookie()).authentication());
        // superseded, idle-expired, absolute-expired
        when(repository.findBySessionId(id)).thenReturn(
                Optional.of(session(SessionState.SUPERSEDED, NOW.plusSeconds(1800), NOW.plusSeconds(36000), false)));
        assertNull(run(repository, withCookie()).authentication());
        when(repository.findBySessionId(id)).thenReturn(
                Optional.of(session(SessionState.ACTIVE, NOW.minusSeconds(1), NOW.plusSeconds(36000), false)));
        assertNull(run(repository, withCookie()).authentication());
        when(repository.findBySessionId(id)).thenReturn(
                Optional.of(session(SessionState.ACTIVE, NOW.plusSeconds(1800), NOW.minusSeconds(1), false)));
        assertNull(run(repository, withCookie()).authentication());
        // a failing lookup
        when(repository.findBySessionId(id)).thenThrow(new IllegalStateException("database down"));
        Seen failed = run(repository, withCookie());
        assertNull(failed.authentication());
        assertTrue(failed.chainRan());
    }

    @Test
    void noCookieOrAnEmptyCookieOrAnAssetPathDoesNoLookup() throws Exception {
        SessionRepository repository = mock(SessionRepository.class);
        assertNull(run(repository, new MockHttpServletRequest("GET", "/devices")).authentication());
        MockHttpServletRequest empty = new MockHttpServletRequest("GET", "/devices");
        empty.setCookies(new Cookie("ui2_session", ""));
        assertNull(run(repository, empty).authentication());
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/devices");
        other.setCookies(new Cookie("JSESSIONID", RAW));
        assertNull(run(repository, other).authentication());
        MockHttpServletRequest asset = new MockHttpServletRequest("GET", "/assets/index-x.js");
        asset.setCookies(new Cookie("ui2_session", RAW));
        assertNull(run(repository, asset).authentication());
        verify(repository, never()).findBySessionId(any(String.class));
    }

    @Test
    void theFilterOnlyReadsAndNeverExpiresRevokesOrHeartbeatsASession() throws Exception {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.findBySessionId(SessionHasher.hash(RAW)))
                .thenReturn(Optional.of(session(SessionState.ACTIVE, NOW.minusSeconds(1), NOW.plusSeconds(36000), false)));
        run(repository, withCookie());
        verify(repository, never()).findBySessionId(any(String.class), any(Instant.class));
        verify(repository, never()).expirePastDeadline(any(Instant.class));
        verify(repository, never()).heartbeat(any(), any(), any());
        verify(repository, never()).expire(any(), any(SessionEndReason.class), any());
    }
}
