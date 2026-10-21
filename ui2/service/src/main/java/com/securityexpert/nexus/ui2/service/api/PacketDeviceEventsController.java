package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketDeviceEventsResponse;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketDeviceEventsService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code GET /api/v2/packet-analysis/cases/{caseId}/device-events}: Packet Analysis > case > Device events. Gated like
 * the case read ({@code packet_analysis_read}); the stored-log facts of a linked device are shown only to a session
 * that also holds {@code system_log_read} (otherwise the row says NOT_PERMITTED). The aiview persona gets the masked
 * projection, applied by the service. Read-only: no device command, no write.
 */
@RestController
public final class PacketDeviceEventsController {
    private final PacketDeviceEventsService service;
    private final ActionRegistry actions;
    private final RbacEvaluator rbac;

    public PacketDeviceEventsController(PacketDeviceEventsService service, ActionRegistry actions, RbacEvaluator rbac) {
        this.service = service;
        this.actions = actions;
        this.rbac = rbac;
    }

    @GetMapping("/api/v2/packet-analysis/cases/{caseId}/device-events")
    public ResponseEntity<PacketDeviceEventsResponse> deviceEvents(@PathVariable String caseId,
            @RequestParam(defaultValue = "") String finding, @RequestParam(name = "window_seconds", defaultValue = "0") int windowSeconds,
            HttpServletRequest request) {
        var query = new PacketDeviceEventsService.Request(caseId, finding, windowSeconds);
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        var permissions = new PacketDeviceEventsService.Permissions(mayReadSystemLogs(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.deviceEvents(query, masked, permissions));
    }

    /** The caller holds system_log_read (operator and above, and the aiview persona), so the stored-log facts may be read. */
    boolean mayReadSystemLogs(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null) return false;
        return rbac.evaluateAny(actor, actions.find(ActionRegistry.SYSTEM_LOG_READ).orElseThrow().requiredRoleTokens(),
                Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    @ExceptionHandler(PacketDeviceEventsService.RefusedException.class)
    public ResponseEntity<Map<String, Object>> refused(PacketDeviceEventsService.RefusedException refused) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", refused.refusal.code());
        body.put("error", refused.refusal.code());
        body.put("reason", refused.refusal.message());
        body.put("message", refused.refusal.message());
        return ResponseEntity.status(refused.refusal.status()).cacheControl(CacheControl.noStore()).body(body);
    }
}
