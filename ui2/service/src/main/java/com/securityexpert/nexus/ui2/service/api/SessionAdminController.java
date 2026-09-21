package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.service.audit.AuditPresentationAllowlist;
import com.securityexpert.nexus.ui2.service.boot.LocalAuthenticationConfiguration;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver;

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
    private final LocalIdentityResolver localIdentityResolver;

    public SessionAdminController(SessionRepository sessionRepository, LocalIdentityResolver localIdentityResolver) {
        this.sessionRepository = sessionRepository;
        this.localIdentityResolver = localIdentityResolver;
    }

    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> list() {
        List<SessionRecord> sessions = sessionRepository.findActive(Instant.now());
        Map<String, String> identityLabels = new LinkedHashMap<>();
        for (SessionRecord session : sessions) {
            localIdentityResolver.resolve(session.actorFingerprint())
                    .ifPresent(identity -> identityLabels.put(session.sessionId(), identity.localIdentityName()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", sessions.stream().map(SessionAdminController::toBody).toList());
        body.put("identity_labels", identityLabels);
        body.put("idle_timeout_seconds", LocalAuthenticationConfiguration.idleTimeout().toSeconds());
        body.put("absolute_lifetime_seconds", LocalAuthenticationConfiguration.absoluteLifetime().toSeconds());
        return ResponseEntity.ok(body);
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

    private static Map<String, Object> toBody(SessionRecord session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", session.sessionId());
        body.put("actor_fingerprint", session.actorFingerprint());
        body.put("created_at", session.createdAt());
        body.put("last_seen_at", session.lastSeenAt());
        body.put("idle_deadline_at", session.idleDeadlineAt());
        body.put("absolute_expires_at", session.absoluteExpiresAt());
        body.put("state", session.state().name());
        body.put("end_reason", session.endReason().map(reason -> reason.column()).orElse(null));
        body.put("ended_by_actor_fingerprint", session.endedByActorFingerprint().orElse(null));
        body.put("superseded_by_session_id", session.supersededBySessionId().orElse(null));
        if (!body.keySet().equals(AuditPresentationAllowlist.columnsOf("sessions"))) {
            throw new IllegalStateException("session presentation columns diverged from the audit allowlist");
        }
        return body;
    }
}
