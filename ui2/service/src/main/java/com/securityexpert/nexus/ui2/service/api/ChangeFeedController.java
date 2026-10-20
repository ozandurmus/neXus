package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.changes.ChangeFeedResponse;
import com.securityexpert.nexus.ui2.service.changes.ChangeFeedService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code GET /api/v2/changes/feed}: Operations > Change feed. Gated like Policy > Changes ({@code policy_read}); the
 * two sources parsed from stored system logs are shown only to a session that also holds {@code system_log_read}
 * (otherwise the coverage says NOT_PERMITTED). The aiview persona gets the masked projection, applied by the service.
 */
@RestController
public final class ChangeFeedController {
    private final ChangeFeedService feed;
    private final ActionRegistry actions;
    private final RbacEvaluator rbac;

    public ChangeFeedController(ChangeFeedService feed, ActionRegistry actions, RbacEvaluator rbac) {
        this.feed = feed;
        this.actions = actions;
        this.rbac = rbac;
    }

    @GetMapping("/api/v2/changes/feed")
    public ResponseEntity<ChangeFeedResponse> feed(@RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String kinds, @RequestParam(defaultValue = "") String vendor,
            @RequestParam(defaultValue = "") String device, @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "0") int limit, HttpServletRequest request) {
        var query = new ChangeFeedService.Request(from, to, kinds, vendor, device, cursor, limit);
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        var permissions = new ChangeFeedService.Permissions(mayReadSystemLogs(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(feed.feed(query, masked, permissions));
    }

    /** The caller holds system_log_read (operator and above, and the aiview persona), so the stored-log sources may be read. */
    boolean mayReadSystemLogs(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null) return false;
        return rbac.evaluateAny(actor, actions.find(ActionRegistry.SYSTEM_LOG_READ).orElseThrow().requiredRoleTokens(),
                Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    @ExceptionHandler(ChangeFeedService.RefusedException.class)
    public ResponseEntity<Map<String, Object>> refused(ChangeFeedService.RefusedException refused) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", refused.refusal.code());
        body.put("error", refused.refusal.code());
        body.put("message", refused.refusal.message());
        return ResponseEntity.status(refused.refusal.status()).cacheControl(CacheControl.noStore()).body(body);
    }
}
