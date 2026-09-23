package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.service.management.ManagementTreeService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** What a management server manages (read-only, no device contact), and the "not an issue here" decision on an item. */
@RestController
public final class ManagementTreeController {
    private final ManagementTreeService service;

    public ManagementTreeController(ManagementTreeService service) {
        this.service = service;
    }

    @GetMapping("/devices/{deviceId}/management-tree")
    public ResponseEntity<Map<String, Object>> tree(@PathVariable String deviceId) {
        return service.tree(deviceId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "NOT_A_MANAGEMENT_SERVER")));
    }

    public record AckRequest(@JsonProperty("token") String token, @JsonProperty("acknowledge") Boolean acknowledge,
            @JsonProperty("reason") String reason) {
    }

    @PostMapping("/devices/{deviceId}/management-tree/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledge(@PathVariable String deviceId, @RequestBody AckRequest request,
            HttpServletRequest servletRequest) {
        if (request == null || request.token() == null || request.acknowledge() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
        }
        String actor = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        return switch (service.acknowledge(deviceId, request.token(), request.acknowledge(), request.reason(), actor)) {
            case OK -> ResponseEntity.ok(Map.of("ok", true));
            case NOT_A_MANAGEMENT_SERVER -> ResponseEntity.status(404).body(Map.of("error", "NOT_A_MANAGEMENT_SERVER"));
            case UNKNOWN_ITEM -> ResponseEntity.status(404).body(Map.of("error", "UNKNOWN_ITEM"));
            case INVALID_REASON -> ResponseEntity.badRequest().body(Map.of("error", "REASON_REQUIRED"));
        };
    }
}
