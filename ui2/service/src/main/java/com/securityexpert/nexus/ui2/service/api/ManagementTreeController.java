package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.management.ManagementTreeService;

/** GET /devices/{id}/management-tree: what a Check Point management server manages (read-only, no device contact). */
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
}
