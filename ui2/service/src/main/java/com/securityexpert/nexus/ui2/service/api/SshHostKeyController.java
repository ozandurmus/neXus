package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.discovery.JooqManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.SshHostKeyPolicyStore;
import com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointSshTrustService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

@RestController
public final class SshHostKeyController {
    private final SshHostKeyPolicyStore store;
    public SshHostKeyController(TransactionBoundary transactions) { store = new SshHostKeyPolicyStore(transactions); }

    public record ModeRequest(String mode) {}
    public record AcceptRequest(String confirmation) {}

    @GetMapping("/api/v2/ssh-trust")
    public Map<String, Object> read(@RequestParam(value = "device_id", required = false) String deviceId) {
        return Map.of("mode", store.mode(), "observations", store.observations(deviceId));
    }

    @PutMapping("/api/v2/ssh-trust/mode")
    public ResponseEntity<?> mode(@RequestBody ModeRequest body, HttpServletRequest request) {
        if (body == null || !("WARN".equals(body.mode()) || "STRICT".equals(body.mode())))
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_MODE"));
        store.mode(body.mode(), actor(request));
        return ResponseEntity.ok(Map.of("mode", store.mode()));
    }

    @PostMapping("/api/v2/ssh-trust/observations/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable("id") String id, @RequestBody AcceptRequest body, HttpServletRequest request) {
        if (body == null || !"ACCEPT NEW SSH KEY".equals(body.confirmation()))
            return ResponseEntity.badRequest().body(Map.of("error", "EXPLICIT_CONFIRMATION_REQUIRED"));
        return resolve(id, actor(request), true);
    }

    @PostMapping("/api/v2/ssh-trust/observations/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable("id") String id, HttpServletRequest request) {
        return resolve(id, actor(request), false);
    }

    private ResponseEntity<?> resolve(String id, String actor, boolean accept) {
        try {
            boolean resolved = store.resolve(id, actor, accept, value ->
                new ManagementEndpointSshTrustService(new JooqManagementEndpointSshTrustRepository(value.transaction()))
                    .enroll(actor, value.address(), value.port(), value.algorithm(), value.fingerprint(),
                            value.seenAt(), true, true) == ManagementEndpointSshTrustService.Outcome.MATCH);
            return resolved ? ResponseEntity.ok(Map.of("status", accept ? "ACCEPTED" : "REJECTED"))
                    : ResponseEntity.status(409).body(Map.of("error", "OBSERVATION_STALE_OR_MISSING"));
        } catch (RuntimeException failure) {
            return ResponseEntity.status(409).body(Map.of("error", "TRUST_RESOLUTION_FAILED"));
        }
    }
    private static String actor(HttpServletRequest request) {
        return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }
}
