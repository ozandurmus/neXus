package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository.BackupPolicy;
import com.securityexpert.nexus.ui2.service.device.backup.BackupScheduleDue;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * V44: the backup policy as a real, audited row. Until now GET answered
 * constants and PUT answered 405 -- the screen's "Save Policy" changed
 * nothing. PUT is {@code device_backup_policy_set} ({@code role:backup_admin}).
 */
@RestController
public final class BackupPolicyController {

    public record UpdateRequest(
            @JsonProperty("schedule_enabled") Boolean scheduleEnabled,
            @JsonProperty("daily_backup_cron") String dailyBackupCron,
            @JsonProperty("backup_retention_days") Integer backupRetentionDays,
            @JsonProperty("snapshot_retention_depth") Integer snapshotRetentionDepth) {
    }

    private final BackupPolicyRepository policyRepository;

    public BackupPolicyController(BackupPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @GetMapping("/api/v2/backups/policies")
    public ResponseEntity<Map<String, Object>> get() {
        Optional<BackupPolicy> policy = policyRepository.find();
        if (policy.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "POLICY_NOT_SEEDED"));
        }
        return ResponseEntity.ok(toBody(policy.get()));
    }

    @PutMapping("/api/v2/backups/policies")
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) UpdateRequest request,
            HttpServletRequest servletRequest) {
        Map<String, Object> body = new LinkedHashMap<>();
        String actor = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || actor.isBlank()) {
            body.put("error", "ACTOR_REQUIRED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        Optional<String> invalid = validate(request);
        if (invalid.isPresent()) {
            body.put("error", "VALIDATION_FAILED");
            body.put("reason", invalid.get());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        boolean updated = policyRepository.update(request.scheduleEnabled(), request.dailyBackupCron().strip(),
                request.backupRetentionDays(), request.snapshotRetentionDepth(), actor, ActionRegistry.DEVICE_BACKUP_POLICY_SET);
        if (!updated) {
            body.put("error", "POLICY_NOT_SEEDED");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        return ResponseEntity.ok(policyRepository.find().map(BackupPolicyController::toBody).orElse(Map.of()));
    }

    public static Optional<String> validate(UpdateRequest request) {
        if (request == null) {
            return Optional.of("a body is required");
        }
        if (request.scheduleEnabled() == null) {
            return Optional.of("schedule_enabled (true|false) is required");
        }
        if (request.dailyBackupCron() == null || !BackupScheduleDue.isValidCron(request.dailyBackupCron())) {
            return Optional.of("daily_backup_cron must be a valid five-field cron expression (UTC), e.g. 0 2 * * *");
        }
        if (request.backupRetentionDays() == null || request.backupRetentionDays() < 1 || request.backupRetentionDays() > 3650) {
            return Optional.of("backup_retention_days must be between 1 and 3650");
        }
        if (request.snapshotRetentionDepth() == null || request.snapshotRetentionDepth() < 1 || request.snapshotRetentionDepth() > 100) {
            return Optional.of("snapshot_retention_depth must be between 1 and 100");
        }
        return Optional.empty();
    }

    private static Map<String, Object> toBody(BackupPolicy policy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policy_id", policy.policyId());
        body.put("schedule_enabled", policy.scheduleEnabled());
        body.put("daily_backup_cron", policy.dailyBackupCron());
        body.put("backup_retention_days", policy.backupRetentionDays());
        body.put("snapshot_retention_depth", policy.snapshotRetentionDepth());
        body.put("last_scheduled_run_at", policy.lastScheduledRunAt().map(Object::toString).orElse(null));
        body.put("updated_at", policy.updatedAt().toString());
        return body;
    }
}
