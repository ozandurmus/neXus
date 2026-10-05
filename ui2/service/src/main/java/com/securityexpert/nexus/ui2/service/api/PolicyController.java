package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public final class PolicyController {
    private final PolicyQueryService policies;
    public PolicyController(PolicyQueryService policies) { this.policies = policies; }

    @GetMapping("/api/v2/policy/tree")
    public ResponseEntity<?> tree(@RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String container, @RequestParam(defaultValue = "") String device) {
        if (!container.isEmpty() && source.isEmpty()) return error(HttpStatus.BAD_REQUEST);
        return ok(policies.tree(source, container, device));
    }
    @GetMapping("/api/v2/policy/devices")
    public ResponseEntity<?> catalog() {
        var catalog = policies.catalog();
        var devices = catalog.stream().flatMap(p -> p.targets().stream()).distinct().toList();
        return ok(new PolicyResponse(Map.of("policies", policies.catalogViews(),
                "devices", devices.stream().map(t -> Map.of("deviceId", t.deviceId(), "name", t.name(), "context", t.context(), "syncStatus", t.syncStatus())).toList())));
    }
    @GetMapping("/api/v2/policy/devices/{id}")
    public ResponseEntity<?> device(@PathVariable String id, @RequestParam(defaultValue = "") String policy,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        var assigned = policies.catalog().stream().filter(p -> p.targets().stream().anyMatch(t -> t.deviceId().equals(id))).toList();
        if (assigned.isEmpty()) return ok(new PolicyResponse(Map.of("policies", List.of(), "sections", List.of(), "total", 0)));
        var chosen = policy.isEmpty() ? assigned.get(0) : assigned.stream().filter(p -> p.id().equals(policy)).findFirst().orElse(null);
        if (chosen == null) return error(HttpStatus.NOT_FOUND);
        var response = page(chosen.id(), page, q, request);
        if (response.getBody() instanceof PolicyResponse payload) {
            Map<String, Object> body = new LinkedHashMap<>(payload.body());
            body.put("policies", assigned.stream().map(policies::metadata).toList());
            return ok(new PolicyResponse(body));
        }
        return response;
    }
    /** Management policies remain navigable before any target has been assigned. */
    @GetMapping("/api/v2/policy/policies/{id}")
    public ResponseEntity<?> filteredPage(@PathVariable String id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String hitFilter,
            @RequestParam(defaultValue = "90") int days, HttpServletRequest request) {
        if (!Set.of("", "never", "inactive").contains(hitFilter) || days < 1 || days > 36500) return error(HttpStatus.BAD_REQUEST);
        return page(id, page, q, request, hitFilter, days);
    }
    public ResponseEntity<?> page(String id, int page, String q, HttpServletRequest request) { return page(id, page, q, request, "", 90); }
    private ResponseEntity<?> page(String id, int page, String q, HttpServletRequest request, String hitFilter, int days) {
        if (page < 0 || q.length() > 1000) return error(HttpStatus.BAD_REQUEST);
        try {
            return policies.find(id).<ResponseEntity<?>>map(snapshot -> ok(hitFilter.isEmpty()
                    ? policies.page(snapshot, page, q, PrivacyMaskingResponseBodyAdvice.isReplayViewer(request))
                    : policies.page(snapshot, page, q, PrivacyMaskingResponseBodyAdvice.isReplayViewer(request), hitFilter, days))).orElseGet(() -> error(HttpStatus.NOT_FOUND));
        } catch (IllegalArgumentException invalid) { return error(HttpStatus.BAD_REQUEST); }
    }
    @GetMapping("/api/v2/policy/policies/{id}/history")
    public ResponseEntity<?> history(@PathVariable String id, @RequestParam(defaultValue = "") String rule,
            @RequestParam(defaultValue = "0") int page) {
        if (page < 0) return error(HttpStatus.BAD_REQUEST);
        if (policies.find(id).isEmpty()) return error(HttpStatus.NOT_FOUND);
        return ok(policies.history(id, rule, page));
    }
    @GetMapping("/api/v2/policy/objects/{id}")
    public ResponseEntity<?> object(@PathVariable String id, @RequestParam(defaultValue = "") String device,
            @RequestParam(defaultValue = "") String policy) {
        if (device.isEmpty() && policy.isEmpty()) return error(HttpStatus.BAD_REQUEST);
        List<String> eligible = policies.catalog().stream()
                .filter(p -> (policy.isEmpty() || p.id().equals(policy)) && (device.isEmpty() || p.targets().stream().anyMatch(t -> t.deviceId().equals(device))))
                .map(PolicySnapshot.Metadata::id).toList();
        for (String candidate : eligible) {
            var snapshot = policies.find(candidate);
            if (snapshot.isPresent() && snapshot.get().objects().containsKey(id)) return ok(policies.object(snapshot.get(), id));
        }
        return error(HttpStatus.NOT_FOUND);
    }
    @GetMapping("/api/v2/policy/domains/{id}/objects")
    public ResponseEntity<?> objects(@PathVariable String id, @RequestParam String source,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String type, @RequestParam(defaultValue = "") String hygiene, HttpServletRequest request) {
        if (!validDomain(source, page, q) || !Set.of("", "host", "network", "range", "group", "service", "service-group", "time", "access-role", "dynamic", "dns-domain", "zone").contains(type)
                || !Set.of("", "unused", "duplicates", "empty", "single").contains(hygiene)) return error(HttpStatus.BAD_REQUEST);
        return policies.domainObjects(source, id, page, q, type, hygiene, PrivacyMaskingResponseBodyAdvice.isReplayViewer(request))
            .<ResponseEntity<?>>map(PolicyController::ok).orElseGet(() -> error(HttpStatus.NOT_FOUND));
    }
    @GetMapping("/api/v2/policy/domains/{id}/objects/{uid}/usage")
    public ResponseEntity<?> usage(@PathVariable String id, @PathVariable String uid, @RequestParam String source,
            @RequestParam(defaultValue = "0") int page) {
        if (!validDomain(source, page, "")) return error(HttpStatus.BAD_REQUEST);
        return policies.objectUsage(source, id, uid, page).<ResponseEntity<?>>map(PolicyController::ok).orElseGet(() -> error(HttpStatus.NOT_FOUND));
    }
    @GetMapping("/api/v2/policy/domains/{id}/objects/duplicates")
    public ResponseEntity<?> duplicates(@PathVariable String id, @RequestParam String source,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        if (!validDomain(source, page, q)) return error(HttpStatus.BAD_REQUEST);
        return policies.objectDuplicates(source, id, page, q, PrivacyMaskingResponseBodyAdvice.isReplayViewer(request))
            .<ResponseEntity<?>>map(PolicyController::ok).orElseGet(() -> error(HttpStatus.NOT_FOUND));
    }
    @GetMapping("/api/v2/policy/domains/{id}/installation")
    public ResponseEntity<?> installation(@PathVariable String id, @RequestParam String source,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String policy, HttpServletRequest request) {
        if (!validDomain(source, page, q)) return error(HttpStatus.BAD_REQUEST);
        return policies.installations(source, id, page, q, policy, PrivacyMaskingResponseBodyAdvice.isReplayViewer(request))
            .<ResponseEntity<?>>map(PolicyController::ok).orElseGet(() -> error(HttpStatus.NOT_FOUND));
    }
    private static boolean validDomain(String source, int page, String q) { return !source.isBlank() && page >= 0 && q.length() <= 1000; }
    @GetMapping("/api/v2/policy/domains/{id}/unused")
    public ResponseEntity<?> unused(@PathVariable String id, @RequestParam String source,
            @RequestParam(defaultValue = "0") int page) { return inventoryView(id, source, "unused", page); }
    @GetMapping("/api/v2/policy/domains/{id}/gateways")
    public ResponseEntity<?> gateways(@PathVariable String id, @RequestParam String source,
            @RequestParam(defaultValue = "0") int page) { return inventoryView(id, source, "gateways", page); }
    @GetMapping("/api/v2/policy/domains/{id}/hits")
    public ResponseEntity<?> hits(@PathVariable String id, @RequestParam String source,
            @RequestParam(defaultValue = "0") int page) {
        if (source.isBlank() || page < 0) return error(HttpStatus.BAD_REQUEST);
        return policies.domainHits(source, id, page).<ResponseEntity<?>>map(PolicyController::ok)
            .orElseGet(() -> error(HttpStatus.NOT_FOUND));
    }
    private ResponseEntity<?> inventoryView(String id, String source, String view, int page) {
        if (source.isBlank() || page < 0) return error(HttpStatus.BAD_REQUEST);
        return policies.domainInventory(source, id, view, page).<ResponseEntity<?>>map(PolicyController::ok)
            .orElseGet(() -> error(HttpStatus.NOT_FOUND));
    }

    private static ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static ResponseEntity<?> error(HttpStatus status) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", status.name())); }
}
