package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.View;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Zone matrix, delivery step 1 (docs/design/ZONE_MATRIX_CONTRACT.md sections 3 and 10.1, FROZEN 2026-11-13). The gate
 * admits each route by its action (SecurityWebMvcConfig); this controller adds the second checks the contract names: the
 * blocks view needs {@code zone_matrix_zones_read}, the masked persona never sees or changes blocks, and the response
 * carries the caller's affordances so the screen computes no visibility of its own.
 */
@RestController
public final class ZoneMatrixController {
    private final ZoneMatrixService service;
    private final RbacEvaluator rbac;
    private final ActionRegistry actions;

    public ZoneMatrixController(ZoneMatrixService service, RbacEvaluator rbac, ActionRegistry actions) {
        this.service = service; this.rbac = rbac; this.actions = actions;
    }

    public record ZoneRequest(Long revision, String name, String description, String role, java.util.List<String> blocks) {}
    public record RevisionRequest(Long revision) {}
    public record LookupRequest(String address, String version_id) {}

    @GetMapping("/api/v2/zone-matrix/versions")
    public ResponseEntity<?> versions(HttpServletRequest request) {
        return run(() -> Map.of("versions", service.versions(masked(request) ? View.MASKED : View.NAMES, reviewer(request)), "can", can(request)));
    }

    @GetMapping("/api/v2/zone-matrix/versions/{id}")
    public ResponseEntity<?> version(@PathVariable String id, @RequestParam(name = "blocks", defaultValue = "0") String blocks,
            HttpServletRequest request) {
        return run(() -> with(service.version(id, view(request, blocks), actor(request), reviewer(request)), request));
    }

    @GetMapping("/api/v2/zone-matrix/draft")
    public ResponseEntity<?> draft(@RequestParam(name = "blocks", defaultValue = "0") String blocks, HttpServletRequest request) {
        return run(() -> with(service.draft(view(request, blocks), actor(request), masked(request), reviewer(request)), request));
    }

    @PostMapping("/api/v2/zone-matrix/lookup")
    public ResponseEntity<?> lookup(@RequestBody LookupRequest body, HttpServletRequest request) {
        return run(() -> {
            if (masked(request)) throw readOnly();
            return service.lookup(actor(request), body == null ? null : body.address(), body == null ? null : body.version_id());
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/zones")
    public ResponseEntity<?> createZone(@RequestBody ZoneRequest body, HttpServletRequest request) {
        return run(() -> { writable(request); return service.createZone(actor(request), revision(body), input(body)); });
    }

    @PutMapping("/api/v2/zone-matrix/draft/zones/{zoneId}")
    public ResponseEntity<?> updateZone(@PathVariable String zoneId, @RequestBody ZoneRequest body, HttpServletRequest request) {
        return run(() -> { writable(request); return service.updateZone(actor(request), revision(body), zoneId, input(body)); });
    }

    @PostMapping("/api/v2/zone-matrix/draft/zones/{zoneId}/delete")
    public ResponseEntity<?> deleteZone(@PathVariable String zoneId, @RequestBody RevisionRequest body, HttpServletRequest request) {
        return run(() -> { writable(request); return service.deleteZone(actor(request), body == null ? null : body.revision(), zoneId); });
    }

    @PostMapping("/api/v2/zone-matrix/draft/discard")
    public ResponseEntity<?> discard(@RequestBody RevisionRequest body, HttpServletRequest request) {
        return run(() -> {
            writable(request);
            if (body == null || body.revision() == null) throw new Refused(400, "INVALID_REQUEST", "revision is required");
            return service.discardDraft(actor(request), body.revision());
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/publish")
    public ResponseEntity<?> publish(@RequestBody ZoneMatrixService.PublishRequest body, HttpServletRequest request) {
        return run(() -> service.publish(actor(request), masked(request), body));
    }

    private View view(HttpServletRequest request, String blocks) {
        if (masked(request)) return View.MASKED;
        if (!"1".equals(blocks)) return View.NAMES;
        if (!permitted(request, ActionRegistry.ZONE_MATRIX_ZONES_READ))
            throw new Refused(403, "ZONE_BLOCKS_NOT_PERMITTED", "viewing zone blocks needs zone_matrix_zones_read");
        return View.BLOCKS;
    }

    /** Editors and publishers review drafts; the masked persona never does. */
    private boolean reviewer(HttpServletRequest request) {
        return !masked(request) && (permitted(request, ActionRegistry.ZONE_MATRIX_EDIT) || permitted(request, ActionRegistry.ZONE_MATRIX_PUBLISH));
    }

    private void writable(HttpServletRequest request) { if (masked(request)) throw readOnly(); }

    private static Long revision(ZoneRequest body) { return body == null ? null : body.revision(); }

    private static ZoneMatrixService.ZoneInput input(ZoneRequest body) {
        if (body == null) throw new Refused(400, "INVALID_ZONE", "zone body missing");
        return new ZoneMatrixService.ZoneInput(body.name(), body.description(), body.role(), body.blocks());
    }

    private Map<String, Object> with(Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("can", can(request));
        return out;
    }

    /** The caller's affordances, from the same actions the gate uses. */
    private Map<String, Boolean> can(HttpServletRequest request) {
        boolean masked = masked(request);
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put("view_blocks", !masked && permitted(request, ActionRegistry.ZONE_MATRIX_ZONES_READ));
        out.put("edit", !masked && permitted(request, ActionRegistry.ZONE_MATRIX_EDIT));
        out.put("publish", !masked && permitted(request, ActionRegistry.ZONE_MATRIX_PUBLISH));
        return out;
    }

    private boolean permitted(HttpServletRequest request, String actionId) {
        var action = actions.find(actionId).orElseThrow();
        return rbac.evaluateAny(actor(request), action.requiredRoleTokens(), Instant.now()).outcome().proceeds();
    }

    private static ResponseEntity<?> run(Supplier<Object> work) {
        try {
            return ResponseEntity.ok(work.get());
        } catch (Refused refused) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", refused.code);
            body.put("reason", refused.getMessage());
            body.putAll(refused.details);
            return ResponseEntity.status(refused.status).body(body);
        }
    }

    private static Refused readOnly() { return new Refused(403, "READ_ONLY_PERSONA", "the masked persona cannot change or look up zones"); }

    private static String actor(HttpServletRequest request) { return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE); }

    private static boolean masked(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));
    }
}
