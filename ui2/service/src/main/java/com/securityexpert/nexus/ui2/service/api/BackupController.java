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
import org.springframework.web.bind.annotation.PutMapping;
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
    private static final java.util.Set<String> VALID_BACKUP_TYPES = java.util.Set.of("backup", "standard", "snapshot", "device_state");

    public record CollectRequest(@JsonProperty("nonce") String nonce, @JsonProperty("reason") String reason,
            @JsonProperty("type") String type) {
    }

    private final BackupCollectService backupCollectService;
    private final BackupArtefactManifestRepository manifestRepository;

    public BackupController(BackupCollectService backupCollectService,
            BackupArtefactManifestRepository manifestRepository) {
        this.backupCollectService = backupCollectService;
        this.manifestRepository = manifestRepository;
    }

    @PostMapping({"/devices/{deviceId}/backup/collect", "/api/v2/backups/{deviceId}/run"})
    public ResponseEntity<Map<String, Object>> collect(@PathVariable String deviceId,
            @RequestBody(required = false) CollectRequest request, HttpServletRequest servletRequest) {
        // Validate deviceId format / traversal
        if (deviceId == null || deviceId.isBlank() || deviceId.contains("..") || deviceId.contains("/") || deviceId.contains("\\")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "INVALID_DEVICE_ID");
            body.put("code", "MALFORMED_IDENTIFIER");
            body.put("reason", "Device identifier must be a valid non-empty identifier without path characters");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        // Enforce authenticated actor fingerprint (fail-closed)
        String actorFingerprint = actingUser(servletRequest);
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "AUTHENTICATION_REQUIRED");
            body.put("code", "ACTOR_FINGERPRINT_MISSING");
            body.put("reason", "An authenticated actor fingerprint is required for backup operations (Plane 3 Security Gate)");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }

        // Mandatory operator justification (BK-12, min 8 chars)
        if (request == null || request.reason() == null || request.reason().strip().length() < 8) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "ADMISSION_REFUSED");
            body.put("code", "REASON_TOO_SHORT");
            body.put("reason", "A backup operation requires an explicit operator justification of at least 8 characters (BK-12)");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        String reason = request.reason().strip();

        // Validated closed type enum
        String rawType = request.type() == null ? "backup" : request.type().strip().toLowerCase();
        if (!VALID_BACKUP_TYPES.contains(rawType)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "ADMISSION_REFUSED");
            body.put("code", "INVALID_BACKUP_TYPE");
            body.put("reason", "Backup type must be one of: " + VALID_BACKUP_TYPES);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        String type = "standard".equals(rawType) ? "backup" : rawType;

        Optional<String> nonce = Optional.ofNullable(request.nonce()).filter(n -> !n.isBlank());

        BackupCollectService.Outcome outcome = backupCollectService.requestCollect(deviceId, actorFingerprint, reason,
                nonce, type);
        return switch (outcome) {
            case BackupCollectService.Outcome.Admitted admitted -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("job_id", admitted.jobId());
                body.put("status", "ACCEPTED");
                body.put("backup_type", type);
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

    public record CollectAllRequest(@JsonProperty("reason") String reason) {
    }

    /** "Run Fleet Backup": every enrolled backup target, each admitted through the same single-device path. */
    @PostMapping("/backups/collect-all")
    public ResponseEntity<Map<String, Object>> collectAll(@RequestBody(required = false) CollectAllRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = actingUser(servletRequest);
        Map<String, Object> body = new LinkedHashMap<>();
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            body.put("error", "ACTOR_REQUIRED");
            body.put("reason", "An authenticated actor fingerprint is required for backup operations (Plane 3 Security Gate)");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        if (request == null || request.reason() == null || request.reason().strip().length() < 8) {
            body.put("error", "REASON_REQUIRED");
            body.put("reason", "A backup operation requires an explicit operator justification of at least 8 characters (BK-12)");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        BackupCollectService.BulkOutcome outcome = backupCollectService.requestCollectAll(actorFingerprint, request.reason().strip());
        body.put("targets", outcome.targets());
        body.put("admitted", outcome.admitted());
        body.put("refused", outcome.refused());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/devices/{deviceId}/backups")
    public ResponseEntity<Map<String, Object>> deviceBackups(@PathVariable String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.contains("..") || deviceId.contains("/") || deviceId.contains("\\")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "INVALID_DEVICE_ID"));
        }
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

    @GetMapping("/api/v2/backups/policies")
    public ResponseEntity<Map<String, Object>> getBackupPolicy() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policy_id", "default");
        body.put("daily_backup_cron", "0 2 * * *");
        body.put("weekly_snapshot_cron", "0 3 * * 0");
        body.put("backup_retention_days", 14);
        body.put("snapshot_retention_depth", 4);
        body.put("major_alert_enabled", true);
        body.put("storage_capacity", "400Gi");
        return ResponseEntity.ok(body);
    }

    @PutMapping("/api/v2/backups/policies")
    public ResponseEntity<Map<String, Object>> updateBackupPolicy(@RequestBody(required = false) Map<String, Object> policy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "POLICY_IMMUTABLE");
        body.put("code", "READ_ONLY_POLICY");
        body.put("reason", "Backup retention and vault policies are immutable via HTTP API and must be updated via approved cluster configuration.");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @GetMapping("/api/v2/backups/deviations")
    public ResponseEntity<Map<String, Object>> listDeviations() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active_major_deviations", List.of());
        body.put("total_deviations_checked", 0);
        return ResponseEntity.ok(body);
    }

    private static String actingUser(HttpServletRequest servletRequest) {
        if (servletRequest == null) {
            return null;
        }
        return (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static Map<String, Object> toSummaryBody(BackupArtefactSummary summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artefact_id", summary.artefactId());
        body.put("device_id", summary.deviceId());
        body.put("collected_at", TIMESTAMP.format(summary.createdAt()));
        body.put("size_bytes", summary.plaintextBytes());
        String sha = summary.plaintextSha256();
        String prefix = (sha != null && !sha.isBlank())
                ? sha.substring(0, Math.min(DIGEST_PREFIX_LENGTH, sha.length()))
                : "UNKNOWN";
        body.put("digest_prefix", prefix);
        body.put("validation_level", summary.validationLevel());
        body.put("deviation_state", summary.deviationState().orElse(null));
        return body;
    }
}
