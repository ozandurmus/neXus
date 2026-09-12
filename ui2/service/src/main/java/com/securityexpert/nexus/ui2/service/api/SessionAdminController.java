package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * {@code role:security_admin} ends another session (C3 §3.3 row 6):
 * {@code ACTIVE} → {@code REVOKED(revoked_by_admin, ended_by=...)}, gated
 * by {@link GateChainInterceptor} (E1-E4 require {@code role:security_admin}
 * for {@link ActionRegistry#SESSION_REVOKE}).
 */
@RestController
public final class SessionAdminController {

    public record RevokeRequest(String sessionId) {
    }

    private final SessionRepository sessionRepository;

    public SessionAdminController(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @PostMapping("/sessions/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@RequestBody RevokeRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        sessionRepository.revoke(request.sessionId(), actorFingerprint, ActionRegistry.SESSION_REVOKE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return ResponseEntity.ok(body);
    }
}
