package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository.BackupArtefactSummary;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupBaselineRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

import jakarta.servlet.http.HttpServletRequest;

/** V44: {@code PUT /devices/{id}/backup-baseline} with {@code {artefact_id}} (or null to clear); the artefact must belong to the device. */
@RestController
public final class BackupBaselineController {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    public record SetRequest(@JsonProperty("artefact_id") String artefactId) {
    }

    private final BackupBaselineRepository baselineRepository;
    private final BackupArtefactManifestRepository manifestRepository;

    public BackupBaselineController(BackupBaselineRepository baselineRepository,
            BackupArtefactManifestRepository manifestRepository) {
        this.baselineRepository = baselineRepository;
        this.manifestRepository = manifestRepository;
    }

    @PutMapping("/devices/{deviceId}/backup-baseline")
    public ResponseEntity<Map<String, Object>> set(@PathVariable String deviceId,
            @RequestBody(required = false) SetRequest request, HttpServletRequest servletRequest) {
        Map<String, Object> body = new LinkedHashMap<>();
        String actor = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || actor.isBlank()) {
            body.put("error", "ACTOR_REQUIRED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        if (deviceId == null || !ID.matcher(deviceId).matches()) {
            body.put("error", "INVALID_DEVICE_ID");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        body.put("device_id", deviceId);
        if (request == null || request.artefactId() == null || request.artefactId().isBlank()) {
            boolean cleared = baselineRepository.clear(deviceId, actor, ActionRegistry.DEVICE_BACKUP_BASELINE_SET);
            body.put("baseline_artefact_id", null);
            body.put("changed", cleared);
            return ResponseEntity.ok(body);
        }
        String artefactId = request.artefactId().strip();
        if (!ID.matcher(artefactId).matches()) {
            body.put("error", "INVALID_ARTEFACT_ID");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        Optional<BackupArtefactSummary> artefact = manifestRepository.findSummary(artefactId);
        if (artefact.isEmpty()) {
            body.put("error", "ARTEFACT_NOT_FOUND");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        if (!artefact.get().deviceId().equals(deviceId)) {
            body.put("error", "ARTEFACT_NOT_OF_DEVICE");
            body.put("reason", "a baseline must be one of the device's own backups");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        baselineRepository.set(deviceId, artefactId, actor, ActionRegistry.DEVICE_BACKUP_BASELINE_SET);
        body.put("baseline_artefact_id", artefactId);
        body.put("changed", true);
        return ResponseEntity.ok(body);
    }
}
