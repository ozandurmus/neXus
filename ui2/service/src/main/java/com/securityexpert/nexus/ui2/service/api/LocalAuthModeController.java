package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.LocalAuthModeService;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * {@code GET/PUT /api/v2/config/local-auth-mode}: the local-account scope, migration plan Step 3 (ALL_LOCAL by
 * default). The route map carries {@code local_auth_mode_read} / {@code local_auth_mode_write}, both
 * {@code role:security_admin}; the write re-checks that permission here and refuses the aiview persona.
 */
@RestController
public final class LocalAuthModeController {
    private final LocalAuthModeService service;
    private final ActionRegistry actions;
    private final RbacEvaluator rbac;

    public LocalAuthModeController(LocalAuthModeService service, ActionRegistry actions, RbacEvaluator rbac) {
        this.service = service;
        this.actions = actions;
        this.rbac = rbac;
    }

    @GetMapping("/api/v2/config/local-auth-mode")
    public ResponseEntity<Map<String, Object>> read(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(mayWrite(request)));
    }

    @PutMapping("/api/v2/config/local-auth-mode")
    public ResponseEntity<Map<String, Object>> write(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || !mayWrite(request)) {
            return error(new LocalAuthModeService.Refusal(403, "LOCAL_AUTH_MODE_NOT_PERMITTED",
                    "Changing the local account mode needs the Security Admin role."));
        }
        var result = service.update(actor, body);
        if (!result.isOk()) {
            return error(result.refusal());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.body());
    }

    /**
     * Holds {@code local_auth_mode_write} and is not the aiview persona. The root identity (nexusadmin) also carries the
     * replay-viewer binding, so it is recognised by its root authority first: it is the break-glass identity this switch
     * must stay reachable for, and the replay-viewer mask must not take it away.
     */
    boolean mayWrite(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null) {
            return false;
        }
        var decision = rbac.evaluateAny(actor,
                actions.find(ActionRegistry.LOCAL_AUTH_MODE_WRITE).orElseThrow().requiredRoleTokens(), Instant.now());
        if (decision.outcome() != AuthzOutcome.PERMITTED) {
            return false;
        }
        return RbacEvaluator.ROOT_AUTHORITY.equals(decision.authority().orElse(null))
                || !PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
    }

    private static ResponseEntity<Map<String, Object>> error(LocalAuthModeService.Refusal refusal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", refusal.code());
        body.put("error", refusal.code());
        body.put("message", refusal.message());
        return ResponseEntity.status(HttpStatus.valueOf(refusal.status())).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
