package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyService;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyView;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Path analysis stage A3 routes (PATH_ANALYSIS_DESIGN.md §7; PO record 2026-11-03 D-8). {@link GateChainInterceptor}
 * has already applied the action gate ({@code topology_read} open like the inventory read; {@code
 * topology_override_write} and {@code topology_rebuild} security_admin only). On top of that gate, a replay viewer
 * (aiview) is refused every write here even when the same account also holds an admin role (D-8), and its read is the
 * masked projection without the override note.
 */
@RestController
public final class TopologyController {

    private static final Set<String> OVERRIDE_FIELDS = Set.of("action", "link_kind", "a_device_id", "a_context",
            "a_interface_name", "b_device_id", "b_context", "b_interface_name", "assumed_subnet", "reason_code", "note");
    private static final Set<String> REQUIRED_OVERRIDE_FIELDS = Set.of("action", "link_kind", "a_device_id", "a_context",
            "a_interface_name", "b_device_id", "b_context", "b_interface_name", "reason_code");

    private final TopologyService topology;

    public TopologyController(TopologyService topology) {
        this.topology = topology;
    }

    @GetMapping("/api/v2/topology")
    public ResponseEntity<?> read(HttpServletRequest request) {
        if (!PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) {
            topology.requestRefreshCheck(); // debounced background system rebuild; this read is answered as held
        }
        return ok(TopologyView.topology(topology.current(), topology.latestSnapshot()));
    }

    @GetMapping("/api/v2/topology/overrides")
    public ResponseEntity<?> overrides(HttpServletRequest request) {
        return ok(TopologyView.overrides(topology.overrides(), PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)));
    }

    @PostMapping("/api/v2/topology/overrides")
    public ResponseEntity<?> createOverride(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Optional<String> actor = actor(request);
        if (actor.isEmpty()) {
            return refusal(new TopologyService.Refusal("UNAUTHENTICATED", 401));
        }
        if (PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) {
            return refusal(new TopologyService.Refusal("WRONG_ROLE", 403));
        }
        if (body == null || !OVERRIDE_FIELDS.containsAll(body.keySet()) || !body.keySet().containsAll(REQUIRED_OVERRIDE_FIELDS)
                || body.values().stream().anyMatch(v -> v != null && !(v instanceof String))) {
            return refusal(new TopologyService.Refusal("INVALID_REQUEST", 400));
        }
        try {
            var request0 = new TopologyService.OverrideRequest(text(body, "action"), text(body, "link_kind"),
                    text(body, "a_device_id"), text(body, "a_context"), text(body, "a_interface_name"),
                    text(body, "b_device_id"), text(body, "b_context"), text(body, "b_interface_name"),
                    Optional.ofNullable(text(body, "assumed_subnet")), text(body, "reason_code"),
                    Optional.ofNullable(text(body, "note")));
            var created = topology.createOverride(request0, actor.get());
            return ok(TopologyView.override(new TopologyService.OverrideView(created, "PENDING_REBUILD", Optional.empty()),
                    false));
        } catch (TopologyService.Refusal refused) {
            return refusal(refused);
        }
    }

    @PostMapping("/api/v2/topology/overrides/{overrideId}/revoke")
    public ResponseEntity<?> revokeOverride(@PathVariable String overrideId, HttpServletRequest request) {
        Optional<String> actor = actor(request);
        if (actor.isEmpty()) {
            return refusal(new TopologyService.Refusal("UNAUTHENTICATED", 401));
        }
        if (PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) {
            return refusal(new TopologyService.Refusal("WRONG_ROLE", 403));
        }
        try {
            topology.revokeOverride(overrideId, actor.get());
            return ok(Map.of("override_id", overrideId, "override_status", "REVOKED"));
        } catch (TopologyService.Refusal refused) {
            return refusal(refused);
        }
    }

    @PostMapping("/api/v2/topology/rebuild")
    public ResponseEntity<?> rebuild(HttpServletRequest request) {
        Optional<String> actor = actor(request);
        if (actor.isEmpty()) {
            return refusal(new TopologyService.Refusal("UNAUTHENTICATED", 401));
        }
        if (PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) {
            return refusal(new TopologyService.Refusal("WRONG_ROLE", 403));
        }
        try {
            var rebuilt = topology.rebuild(actor.get());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "BUILT");
            body.put("snapshot", TopologyView.header(rebuilt.header()));
            return ok(body);
        } catch (TopologyService.Refusal refused) {
            return refusal(refused);
        }
    }

    private static Optional<String> actor(HttpServletRequest request) {
        return request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE) instanceof String s && !s.isBlank()
                ? Optional.of(s) : Optional.empty();
    }

    private static String text(Map<String, Object> body, String key) {
        return (String) body.get(key);
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** Uniform refusal shape; the code never echoes an input value. */
    private static ResponseEntity<?> refusal(TopologyService.Refusal refused) {
        return ResponseEntity.status(refused.status()).cacheControl(CacheControl.noStore())
                .body(Map.of("code", refused.code()));
    }
}
