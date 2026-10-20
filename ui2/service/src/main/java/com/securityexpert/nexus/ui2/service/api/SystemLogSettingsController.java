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
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogSettingsService;

/**
 * Administration › Platform › System logs (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 14: {@code syslog-settings},
 * {@code syslog-settings-set}). The route map carries the actions: the read is {@code system_log_settings_read} and the
 * write {@code system_log_settings_write}, both {@code role:security_admin}, neither open to the aiview persona. The
 * read answers {@code can_write} (the caller's own write permission, decided here, never in the browser) so the
 * screen knows whether to offer edit controls; the write checks the same permission again before anything is stored.
 */
@RestController
public final class SystemLogSettingsController {
    private final SystemLogSettingsService service;
    private final ActionRegistry actions;
    private final RbacEvaluator rbac;

    public SystemLogSettingsController(SystemLogSettingsService service, ActionRegistry actions, RbacEvaluator rbac) {
        this.service = service;
        this.actions = actions;
        this.rbac = rbac;
    }

    @GetMapping("/api/v2/config/system-logs")
    public ResponseEntity<Map<String, Object>> read(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(mayWrite(request)));
    }

    @PutMapping("/api/v2/config/system-logs")
    public ResponseEntity<Map<String, Object>> write(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || !mayWrite(request)) {
            return error(new SystemLogSettingsService.Refusal(403, "SYSTEM_LOG_SETTINGS_NOT_PERMITTED",
                    "Changing the system-log settings needs the Security Admin role.", java.util.List.of()));
        }
        var result = service.update(actor, body);
        if (!result.isOk()) return error(result.refusal());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.body());
    }

    /** The caller may change the settings: holds system_log_settings_write and is not the aiview persona. */
    boolean mayWrite(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) return false;
        return rbac.evaluateAny(actor, actions.find(ActionRegistry.SYSTEM_LOG_SETTINGS_WRITE).orElseThrow().requiredRoleTokens(),
                Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    private static ResponseEntity<Map<String, Object>> error(SystemLogSettingsService.Refusal refusal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", refusal.code());
        body.put("error", refusal.code());
        body.put("message", refusal.message());
        body.put("problems", refusal.problems());
        return ResponseEntity.status(HttpStatus.valueOf(refusal.status())).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
