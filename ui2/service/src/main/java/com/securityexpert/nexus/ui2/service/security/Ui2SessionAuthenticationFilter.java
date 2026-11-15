package com.securityexpert.nexus.ui2.service.security;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;

/**
 * Spring Security migration Step 2 (docs/design/SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md): bridges the {@code ui2_session}
 * cookie into the {@code SecurityContext}. It reads the cookie, hashes it with {@link SessionHasher}, looks the session up
 * and, when it is ACTIVE and inside both deadlines, sets a {@link Ui2SessionAuthenticationToken}; the context is cleared
 * in a {@code finally}.
 *
 * <p>Purely additive: it never refuses, redirects, writes a header or a cookie, and never changes the session. The lookup is
 * the plain read ({@code findBySessionId(id)}), not the expiry-on-read variant, so it cannot move a session to EXPIRED or write
 * an audit row on a route {@link GateChain} would not have touched. A lookup failure leaves the request unauthenticated and
 * the request continues exactly as before. {@link GateChain} (E1 to E6) stays the only authority.</p>
 */
public final class Ui2SessionAuthenticationFilter extends OncePerRequestFilter {

    static final String SESSION_COOKIE_NAME = "ui2_session";

    private final SessionRepository sessions;
    private final Supplier<java.time.Instant> clock;

    public Ui2SessionAuthenticationFilter(SessionRepository sessions, Supplier<java.time.Instant> clock) {
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            authenticate(request).ifPresent(token -> SecurityContextHolder.getContext().setAuthentication(token));
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<Ui2SessionAuthenticationToken> authenticate(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/assets/")) {
            return Optional.empty(); // hashed static files: no session lookup for them
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                return lookup(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private Optional<Ui2SessionAuthenticationToken> lookup(String rawCookieValue) {
        try {
            String sessionId = SessionHasher.hash(rawCookieValue);
            Optional<SessionRecord> record = sessions.findBySessionId(sessionId);
            if (record.isPresent() && record.get().isActive(clock.get())) {
                return Optional.of(new Ui2SessionAuthenticationToken(record.get().actorFingerprint(), sessionId,
                        record.get().machine()));
            }
        } catch (RuntimeException e) {
            // Unauthenticated, as before this filter existed; the gate chain decides what that means for the route.
        }
        return Optional.empty();
    }
}
