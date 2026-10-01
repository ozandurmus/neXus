package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.security.*;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.platform.*;

@RestController
public final class PolicyCollectionController {
    public record CollectRequest(String domainRef) {}
    private final PolicyCollectionService collections;
    private final RbacEvaluator rbac;
    public PolicyCollectionController(PolicyCollectionService collections, RbacEvaluator rbac) {
        this.collections = collections; this.rbac = rbac;
    }
    @GetMapping("/api/v2/policy/sources")
    public ResponseEntity<?> sources(HttpServletRequest request) {
        boolean canCollect = !PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)
            && rbac.evaluateAny(actor(request), Set.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN), Instant.now()).outcome() == AuthzOutcome.PERMITTED;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PolicyResponse(Map.of(
                "sources", collections.sources(), "canCollect", canCollect)));
    }
    @PostMapping("/api/v2/policy/sources/{id}/collect")
    public ResponseEntity<?> collect(@PathVariable String id, @RequestBody CollectRequest body, HttpServletRequest request) {
        try {
            return collections.collect(id, body.domainRef(), actor(request)).<ResponseEntity<?>>map(job ->
                ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("jobId", job)))
                .orElseGet(() -> ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("error", "POLICY_SOURCE_BUSY_OR_INELIGIBLE")));
        } catch (IllegalStateException unavailable) {
            return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("error", "POLICY_GATE_UNAVAILABLE"));
        }
    }
    private static String actor(HttpServletRequest request) {
        return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }
}
