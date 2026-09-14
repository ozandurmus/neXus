package com.securityexpert.nexus.ui2.service.api;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository.BackupArtefactSummary;
import com.securityexpert.nexus.ui2.service.device.backup.BackupCollectService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * {@code POST /devices/{id}/backup/collect}, {@code GET /devices/{id}/backups}
 * and {@code GET /backups} (WORKER.md "Service and screen"). 14H BK-14 /
 * 14I OR-1: no field here is ever an artefact byte, a store path or a
 * decrypt affordance -- {@link #toSummaryBody} carries only a digest
 * prefix, never the full digest's own storage-path implication and never
 * {@code recovery_volume_path}.
 */
@RestController
public final class BackupController {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;
    private static final int DIGEST_PREFIX_LENGTH = 12;

    public record CollectRequest(@JsonProperty("nonce") String nonce, @JsonProperty("reason") String reason) {
    }

    private final BackupCollectService backupCollectService;
    private final BackupArtefactManifestRepository manifestRepository;

    public BackupController(BackupCollectService backupCollectService,
            BackupArtefactManifestRepository manifestRepository) {
        this.backupCollectService = backupCollectService;
        this.manifestRepository = manifestRepository;
    }

    @PostMapping("/devices/{deviceId}/backup/collect")
    public ResponseEntity<Map<String, Object>> collect(@PathVariable String deviceId,
            @RequestBody(required = false) CollectRequest request, HttpServletRequest servletRequest) {
        String actorFingerprint = actingUser(servletRequest);
        Optional<String> nonce = request == null ? Optional.empty() : Optional.ofNullable(request.nonce());
        String reason = request == null ? null : request.reason();
        BackupCollectService.Outcome outcome = backupCollectService.requestCollect(deviceId, actorFingerprint, reason,
                nonce);
        return switch (outcome) {
            case BackupCollectService.Outcome.Admitted admitted -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("job_id", admitted.jobId());
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
            }
            case BackupCollectService.Outcome.DeviceNotFound notFound -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "ADMISSION_REFUSED");
                body.put("code", "DEVICE_NOT_FOUND");
                body.put("reason", "no device row for device_id=" + deviceId);
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
            case BackupCollectService.Outcome.AdmissionRefused refused -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "ADMISSION_REFUSED");
                body.put("code", refused.code());
                body.put("reason", refused.reason());
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
        };
    }

    @GetMapping("/devices/{deviceId}/backups")
    public ResponseEntity<Map<String, Object>> deviceBackups(@PathVariable String deviceId) {
        List<BackupArtefactSummary> rows = manifestRepository.findByDevice(deviceId, ArtefactClass.BACKUP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", deviceId);
        body.put("backups", rows.stream().map(BackupController::toSummaryBody).toList());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/backups")
    public ResponseEntity<Map<String, Object>> fleetBackups() {
        List<BackupArtefactSummary> rows = manifestRepository.findAll(ArtefactClass.BACKUP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("backups", rows.stream().map(BackupController::toSummaryBody).toList());
        return ResponseEntity.ok(body);
    }

    private static String actingUser(HttpServletRequest servletRequest) {
        return (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static Map<String, Object> toSummaryBody(BackupArtefactSummary summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artefact_id", summary.artefactId());
        body.put("device_id", summary.deviceId());
        body.put("collected_at", TIMESTAMP.format(summary.createdAt()));
        body.put("size_bytes", summary.plaintextBytes());
        body.put("digest_prefix", summary.plaintextSha256().substring(0,
                Math.min(DIGEST_PREFIX_LENGTH, summary.plaintextSha256().length())));
        body.put("validation_level", summary.validationLevel());
        body.put("deviation_state", summary.deviationState().orElse(null));
        return body;
    }
}
