package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.security.SessionHasher;
import com.securityexpert.nexus.ui2.service.security.SessionSelfLogoutService;

/**
 * {@code POST /session/logout} (NXS-LOCAL-0152 AC-4): an authenticated
 * identity ending its own session. Paired with {@code GET /session/status}
 * and deliberately outside {@code SecurityWebMvcConfig}'s route map, exactly
 * like {@code /login} and {@code /session/status}: it requires no role and
 * so is never a {@link com.securityexpert.nexus.ui2.service.security.GateChain}
 * -gated action.
 */
@RestController
public final class SessionLogoutController {

    private static final String SESSION_COOKIE_NAME = "ui2_session";

    private final SessionSelfLogoutService logoutService;
    private final SessionCookieWriter cookieWriter;

    public SessionLogoutController(SessionSelfLogoutService logoutService, SessionCookieWriter cookieWriter) {
        this.logoutService = logoutService;
        this.cookieWriter = cookieWriter;
    }

    @PostMapping("/session/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request, HttpServletResponse response) {
        Optional<String> rawCookie = findSessionCookie(request);
        if (rawCookie.isEmpty()) {
            cookieWriter.clear(response);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody());
        }
        String sessionId = SessionHasher.hash(rawCookie.get());
        SessionSelfLogoutService.Result result = logoutService.logout(sessionId,
                Optional.ofNullable(request.getHeader("X-CSRF-Token")));
        if (result instanceof SessionSelfLogoutService.Result.CsrfRejected) {
            // PO 2026-11-15: sign-out requires the CSRF token like every other state change. The session and the cookie
            // are left untouched, so a forged request cannot end a user's session.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody());
        }
        cookieWriter.clear(response);
        if (result instanceof SessionSelfLogoutService.Result.NoActiveSession) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> errorBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "SESSION_INVALID");
        return body;
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
