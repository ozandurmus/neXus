package com.securityexpert.nexus.ui2.service.api;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.identity.ReplaySessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.SessionHasher;
import com.securityexpert.nexus.ui2.service.security.ReplayViewerBoundary;

/** Deliberate, E1-E4-gated activation; deactivation ends the exclusive session. */
@RestController
public final class ReplaySessionController {
    private final ReplaySessionRepository replaySessions;
    private final SessionRepository sessions;

    public ReplaySessionController(ReplaySessionRepository replaySessions, SessionRepository sessions) {
        this.replaySessions = replaySessions;
        this.sessions = sessions;
    }

    @PostMapping("/session/replay/activate")
    public ResponseEntity<Map<String, Object>> activate(HttpServletRequest request, HttpServletResponse response) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String rawCookie = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            replaySessions.activate(sessionId(request), SessionHasher.hash(rawCookie), actor(request), Instant.now());
        } catch (RuntimeException failure) {
            return ResponseEntity.status(409).body(ReplayViewerBoundary.refusal());
        }
        LoginController.setSessionCookie(response, rawCookie);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/session/replay/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate(HttpServletRequest request, HttpServletResponse response) {
        try {
            sessions.revoke(sessionId(request), actor(request), ActionRegistry.REPLAY_DEACTIVATE);
        } catch (RuntimeException failure) {
            return ResponseEntity.status(409).body(ReplayViewerBoundary.refusal());
        }
        Cookie cookie = new Cookie("ui2_session", "");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String sessionId(HttpServletRequest request) {
        return (String) request.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE);
    }

    private static String actor(HttpServletRequest request) {
        return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }
}
