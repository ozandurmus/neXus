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
        return ok(new PolicyResponse(Map.of("policies", catalog.stream().map(policies::metadata).toList(),
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
    public ResponseEntity<?> page(@PathVariable String id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        if (page < 0 || q.length() > 200) return error(HttpStatus.BAD_REQUEST);
        return policies.find(id).<ResponseEntity<?>>map(snapshot -> ok(policies.page(snapshot, page, q,
                PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)))).orElseGet(() -> error(HttpStatus.NOT_FOUND));
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
    private static ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static ResponseEntity<?> error(HttpStatus status) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", status.name())); }
}
