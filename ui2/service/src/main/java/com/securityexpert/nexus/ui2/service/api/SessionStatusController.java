package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.service.security.SessionHasher;

/**
 * {@code GET /session/status}: the frontend's own pre-authorization check
 * ("the application must not render any product screen until a session
 * exists", this movement's brief / C3A contract §2's flow). Deliberately
 * absent from {@code SecurityWebMvcConfig}'s route map, exactly like
 * {@code /login} and {@code /login/resolve}: it runs before -- and instead
 * of -- the {@code E1}-{@code E6} chain, since its entire purpose is to
 * answer "is there a session" for a caller that does not yet know.
 *
 * <p>This performs only session-validity reads ({@code E1}'s own checks,
 * duplicated in miniature rather than reused, since {@link com.securityexpert.nexus.ui2.service.security.GateChain}
 * always continues into {@code E2}'s action lookup) -- it mutates nothing
 * and requires no RBAC decision.</p>
 */
@RestController
public final class SessionStatusController {

    private static final String SESSION_COOKIE_NAME = "ui2_session";

    private final SessionRepository sessionRepository;

    public SessionStatusController(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @GetMapping("/session/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        Optional<String> rawCookie = findSessionCookie(request);
        boolean authenticated = rawCookie.isPresent() && isActive(rawCookie.get());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", authenticated);
        return authenticated
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    private boolean isActive(String rawCookieValue) {
        String sessionId = SessionHasher.hash(rawCookieValue);
        Optional<SessionRecord> session = sessionRepository.findBySessionId(sessionId);
        return session.map(s -> s.isActive(Instant.now())).orElse(false);
    }

    private static Optional<String> findSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
