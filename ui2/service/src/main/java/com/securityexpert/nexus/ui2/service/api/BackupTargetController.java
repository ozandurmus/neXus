package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code PUT /devices/{id}/backup-target} -- Backups > Backup targets (Product Owner, 2026-09-22:
 * targets are chosen in the product, never by hand in the database). An audited devices UPDATE
 * under {@link ActionRegistry#DEVICE_BACKUP_TARGET_SET}; it never issues a backup itself.
 */
@RestController
public final class BackupTargetController {

    public record SetRequest(@JsonProperty("enabled") Boolean enabled) {
    }

    private final DeviceRepository deviceRepository;

    public BackupTargetController(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @PutMapping("/devices/{deviceId}/backup-target")
    public ResponseEntity<Map<String, Object>> set(@PathVariable String deviceId,
            @RequestBody(required = false) SetRequest request, HttpServletRequest servletRequest) {
        Map<String, Object> body = new LinkedHashMap<>();
        String actor = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || actor.isBlank()) {
            body.put("error", "ACTOR_REQUIRED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        if (request == null || request.enabled() == null) {
            body.put("error", "VALIDATION_FAILED");
            body.put("reason", "enabled (true|false) is required");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        if (deviceRepository.find(deviceId).isEmpty()) {
            body.put("error", "NOT_FOUND");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        boolean changed = deviceRepository.setBackupTarget(deviceId, request.enabled(), actor,
                ActionRegistry.DEVICE_BACKUP_TARGET_SET);
        body.put("device_id", deviceId);
        body.put("backup_target", request.enabled());
        body.put("changed", changed);
        return ResponseEntity.ok(body);
    }
}
