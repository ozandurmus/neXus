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
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.snmp.SnmpPollingSettingsService;

/**
 * Administration > SNMP polling (SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 11, option B). The route map carries
 * the actions: read {@code snmp_polling_settings_read} and write {@code snmp_polling_settings_write}, both
 * {@code role:security_admin}. With {@code UI2_AIVIEW_READONLY_ADMIN} on, aiview reads a masked projection (credential
 * names and references replaced, {@code can_write=false}) and can never write. The read answers {@code can_write}, decided
 * here; the write checks the same permission again before anything is stored.
 */
@RestController
public final class SnmpPollingSettingsController {
    private final SnmpPollingSettingsService service;
    private final ActionRegistry actions;
    private final RbacEvaluator rbac;

    public SnmpPollingSettingsController(SnmpPollingSettingsService service, ActionRegistry actions, RbacEvaluator rbac) {
        this.service = service;
        this.actions = actions;
        this.rbac = rbac;
    }

    @GetMapping("/api/v2/config/snmp-polling")
    public ResponseEntity<Map<String, Object>> read(HttpServletRequest request) {
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(mayWrite(request), masked));
    }

    @PutMapping("/api/v2/config/snmp-polling")
    public ResponseEntity<Map<String, Object>> write(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || !mayWrite(request)) {
            return error(new SnmpPollingSettingsService.Refusal(403, "SNMP_POLLING_SETTINGS_NOT_PERMITTED",
                    "Changing SNMP polling needs the Security Admin role.", List.of()));
        }
        var result = service.update(actor, body);
        if (!result.isOk()) return error(result.refusal());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.body());
    }

    /** The caller may change the settings: holds snmp_polling_settings_write and is not the aiview persona. */
    boolean mayWrite(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) return false;
        return rbac.evaluateAny(actor, actions.find(ActionRegistry.SNMP_POLLING_SETTINGS_WRITE).orElseThrow()
                .requiredRoleTokens(), Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    private static ResponseEntity<Map<String, Object>> error(SnmpPollingSettingsService.Refusal refusal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", refusal.code());
        body.put("error", refusal.code());
        body.put("message", refusal.message());
        body.put("problems", refusal.problems());
        return ResponseEntity.status(HttpStatus.valueOf(refusal.status())).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
