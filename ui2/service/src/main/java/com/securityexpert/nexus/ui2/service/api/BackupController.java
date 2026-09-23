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
import com.securityexpert.nexus.ui2.service.device.backup.BackupDownloadService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * {@code POST /devices/{id}/backup/collect}, {@code GET /devices/{id}/backups}
 * and {@code GET /backups} (WORKER.md "Service and screen"). 14H BK-14 /
 * 14I OR-1: no listing field is ever an artefact byte, a store path or a
 * decrypt affordance -- {@link #toSummaryBody} carries only a digest
 * prefix, never the full digest's own storage-path implication and never
 * {@code recovery_volume_path}. The one route that does return bytes,
 * {@link #download}, exists by PO decision record 2026-09-22 and is
 * role-gated and audited before the first byte.
 */
@RestController
public final class BackupController {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;
    private static final int DIGEST_PREFIX_LENGTH = 12;
    private static final java.util.Set<String> VALID_BACKUP_TYPES = java.util.Set.of("backup", "standard", "snapshot", "device_state", "mds_export");

    public record CollectRequest(@JsonProperty("nonce") String nonce, @JsonProperty("reason") String reason,
            @JsonProperty("type") String type) {
    }

    private final BackupCollectService backupCollectService;
    private final BackupArtefactManifestRepository manifestRepository;
    private final BackupDownloadService backupDownloadService;
    private final com.securityexpert.nexus.ui2.persistence.artefact.BackupBaselineRepository baselineRepository;

    public BackupController(BackupCollectService backupCollectService,
            BackupArtefactManifestRepository manifestRepository) {
        this(backupCollectService, manifestRepository, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BackupController(BackupCollectService backupCollectService,
            BackupArtefactManifestRepository manifestRepository, BackupDownloadService backupDownloadService,
            com.securityexpert.nexus.ui2.persistence.artefact.BackupBaselineRepository baselineRepository) {
        this.backupCollectService = backupCollectService;
        this.manifestRepository = manifestRepository;
        this.backupDownloadService = backupDownloadService;
        this.baselineRepository = baselineRepository;
    }

    public record DownloadRequest(@JsonProperty("reason") String reason) {
    }

    /**
     * PO decision record 2026-09-22: the one route that returns artefact
     * bytes. Gated by {@code device_backup_retrieve} ({@code role:backup_admin}
     * + CSRF) at the route map; the audit row is written before the first
     * byte by {@link BackupDownloadService}. The file name carries the
     * vendor, the opaque id's prefix and the collection time -- never a
     * hostname or an address.
     */
    @PostMapping("/backups/{artefactId}/download")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> download(
            @PathVariable String artefactId,
            @RequestBody(required = false) DownloadRequest request, HttpServletRequest servletRequest) {
        if (artefactId == null || !ARTEFACT_ID.matcher(artefactId).matches()) {
            return refusal(HttpStatus.BAD_REQUEST, "INVALID_ARTEFACT_ID", "MALFORMED_IDENTIFIER",
                    "Artefact identifier must be an opaque id");
        }
        String actorFingerprint = actingUser(servletRequest);
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return refusal(HttpStatus.FORBIDDEN, "AUTHENTICATION_REQUIRED", "ACTOR_FINGERPRINT_MISSING",
                    "An authenticated actor fingerprint is required to download a backup");
        }
        if (backupDownloadService == null) {
            return refusal(HttpStatus.SERVICE_UNAVAILABLE, "DOWNLOAD_UNAVAILABLE", "ARTEFACT_STORE_NOT_MOUNTED",
                    "This service instance has no artefact store mounted");
        }
        String reason = request == null ? null : request.reason();
        BackupDownloadService.Outcome outcome = backupDownloadService.prepare(actorFingerprint, artefactId, reason);
        return switch (outcome) {
            case BackupDownloadService.Outcome.Ready ready -> {
                String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(java.time.ZoneOffset.UTC)
                        .format(ready.collectedAt());
                String fileName = "nexus-backup-" + ready.vendor() + "-" + ready.artefactId().substring(0, 8) + "-"
                        + stamp + ".tgz";
                org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = out -> {
                    try (java.io.InputStream in = ready.stream()) {
                        in.transferTo(out);
                    }
                };
                ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                        .header("Content-Type", "application/gzip")
                        .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                        .header("X-Artefact-Id", ready.artefactId())
                        .header("Cache-Control", "no-store");
                if (ready.plaintextBytes() >= 0) {
                    builder = builder.header("Content-Length", Long.toString(ready.plaintextBytes()));
                }
                yield builder.body(body);
            }
            case BackupDownloadService.Outcome.ReasonTooShort ignored ->
                    refusal(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "REASON_TOO_SHORT",
                            "A reason of at least 8 characters is required (BK-12)");
            case BackupDownloadService.Outcome.ArtefactNotFound ignored ->
                    refusal(HttpStatus.NOT_FOUND, "ARTEFACT_NOT_FOUND", "ARTEFACT_NOT_FOUND",
                            "No backup artefact with that id");
            case BackupDownloadService.Outcome.AuditRefused refused ->
                    refusal(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_REFUSED", "AUDIT_REFUSED",
                            "The retrieval could not be audited; no bytes were sent");
            case BackupDownloadService.Outcome.StoreUnavailable unavailable ->
                    refusal(HttpStatus.SERVICE_UNAVAILABLE, "DOWNLOAD_UNAVAILABLE", "ARTEFACT_STORE_NOT_MOUNTED",
                            unavailable.reason());
            case BackupDownloadService.Outcome.IoFailure failure ->
                    // The store's own reason (e.g. "stored in the pre-streaming format ... take a new backup")
                    // is the operator's next action; it carries no path and no secret.
                    refusal(HttpStatus.INTERNAL_SERVER_ERROR, "IO_FAILURE", "IO_FAILURE",
                            "The artefact could not be read from the store: " + failure.reason());
        };
    }

    private static final java.util.regex.Pattern ARTEFACT_ID = java.util.regex.Pattern.compile("[A-Za-z0-9-]{8,64}");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * A refusal on the download route, written as a streaming JSON body. The route's declared
     * return type must be {@code ResponseEntity<StreamingResponseBody>} for Spring to stream the
     * archive at all (measured live 2026-09-22: declared as {@code ResponseEntity<?>}, every
     * download answered 500 "No converter"), so the refusals share that body type.
     */
    private static ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> refusal(
            HttpStatus status, String error, String code, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("code", code);
        body.put("reason", reason);
        byte[] json;
        try {
            json = JSON.writeValueAsBytes(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json = ("{\"error\":\"" + error + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        byte[] payload = json;
        return ResponseEntity.status(status).header("Content-Type", "application/json").body(out -> out.write(payload));
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
        // V44: the device's declared reference point, so the history view can mark it and compare against it.
        body.put("baseline_artefact_id", baselineRepository == null ? null
                : baselineRepository.find(deviceId).map(b -> b.artefactId()).orElse(null));
        body.put("backups", rows.stream().map(BackupController::toSummaryBody).toList());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/backups")
    public ResponseEntity<Map<String, Object>> fleetBackups() {
        List<BackupArtefactSummary> rows = manifestRepository.findAll(ArtefactClass.BACKUP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("backups", rows.stream().map(BackupController::toSummaryBody).toList());
        body.put("baselines", baselineRepository == null ? Map.of() : baselineRepository.findAll());
        return ResponseEntity.ok(body);
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
        body.put("vendor", summary.vendor());
        body.put("artefact_class", summary.artefactClass());
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
