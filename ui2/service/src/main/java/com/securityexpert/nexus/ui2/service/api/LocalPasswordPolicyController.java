package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.securityexpert.nexus.ui2.service.security.LocalPasswordPolicyService;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * {@code GET/PUT /api/v2/config/local-password-policy}: the configurable local-account password policy (off by
 * default). The route map carries {@code local_password_policy_read} / {@code local_password_policy_write}, both
 * {@code role:security_admin}; the write re-checks that permission here and refuses the aiview persona.
 */
@RestController
public final class LocalPasswordPolicyController {
    private final LocalPasswordPolicyService service;
    private final ActionRegistry actions;
    private final RbacEvaluator rbac;

    public LocalPasswordPolicyController(LocalPasswordPolicyService service, ActionRegistry actions, RbacEvaluator rbac) {
        this.service = service;
        this.actions = actions;
        this.rbac = rbac;
    }

    @GetMapping("/api/v2/config/local-password-policy")
    public ResponseEntity<Map<String, Object>> read(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(mayWrite(request)));
    }

    @PutMapping("/api/v2/config/local-password-policy")
    public ResponseEntity<Map<String, Object>> write(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || !mayWrite(request)) {
            return error(new LocalPasswordPolicyService.Refusal(403, "LOCAL_PASSWORD_POLICY_NOT_PERMITTED",
                    "Changing the local password policy needs the Security Admin role.", List.of()));
        }
        var result = service.update(actor, body);
        if (!result.isOk()) {
            return error(result.refusal());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.body());
    }

    boolean mayWrite(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) {
            return false;
        }
        return rbac.evaluateAny(actor,
                actions.find(ActionRegistry.LOCAL_PASSWORD_POLICY_WRITE).orElseThrow().requiredRoleTokens(),
                Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    private static ResponseEntity<Map<String, Object>> error(LocalPasswordPolicyService.Refusal refusal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", refusal.code());
        body.put("error", refusal.code());
        body.put("message", refusal.message());
        body.put("problems", refusal.problems());
        return ResponseEntity.status(HttpStatus.valueOf(refusal.status())).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
