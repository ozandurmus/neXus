package com.securityexpert.nexus.ui2.service.api;
// 14I MS-1

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

import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.BackupDisposition;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.DeleteResult;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.service.device.DeviceAddSingleService;
import com.securityexpert.nexus.ui2.service.device.DeviceDeletionService;
import com.securityexpert.nexus.ui2.service.device.DeviceQueryService;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Single-device add and its two read routes (WORKER.md "Routes" --
 * NXS-LOCAL-0158 closes the three gaps NXS-LOCAL-0157's SESSION_CLOSE
 * named). Gated by {@link GateChainInterceptor} through {@code
 * SecurityWebMvcConfig}'s route map -- this class performs no authorization
 * check of its own. Every JSON key below is exactly what {@code
 * adminApi.ts} (the fixed side of this contract) declares.
 */
@RestController
public final class DeviceRegistrationController {

    public record AddSingleRequest(
            @JsonProperty("address") String address,
            @JsonProperty("role") String role,
            @JsonProperty("vendor") String vendor,
            @JsonProperty("credential_reference_id") String credentialReferenceId,
            @JsonProperty("export_passphrase_credential_reference_id") String exportPassphraseCredentialReferenceId) {
    }

    public record DeleteRequest(@JsonProperty("backup_disposition") BackupDisposition backupDisposition) {
    }

    private final DeviceAddSingleService deviceAddSingleService;
    private final DeviceDeletionService deviceDeletionService;
    private final DeviceQueryService deviceQueryService;
    private final DevicePlatformFactsRepository platformFactsRepository;
    private com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstallRepository policyInstallRepository =
            com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstallRepository.NONE;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPolicyInstallRepository(com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstallRepository repository) {
        this.policyInstallRepository = repository;
    }

    /** The installed policy (V60): name, install time as reported and parsed, and when it was read; null when unread. */
    static void putPolicyInstall(Map<String, Object> body,
            Optional<com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall> p) {
        body.put("policy_name", p.flatMap(com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall::policyName).orElse(null));
        body.put("policy_installed_at", p.flatMap(com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall::installedAt)
                .map(Object::toString).orElse(null));
        body.put("policy_installed_at_text", p.flatMap(com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall::installedAtText)
                .orElse(null));
        body.put("policy_read_at", p.flatMap(com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall::observedAt)
                .map(Object::toString).orElse(null));
    }
    private com.securityexpert.nexus.ui2.service.overview.OverviewService overviewService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOverviewService(com.securityexpert.nexus.ui2.service.overview.OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    public DeviceRegistrationController(DeviceAddSingleService deviceAddSingleService,
            DeviceDeletionService deviceDeletionService,
            DeviceQueryService deviceQueryService) {
        this(deviceAddSingleService, deviceDeletionService, deviceQueryService, DevicePlatformFactsRepository.NONE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeviceRegistrationController(DeviceAddSingleService deviceAddSingleService,
            DeviceDeletionService deviceDeletionService,
            DeviceQueryService deviceQueryService,
            DevicePlatformFactsRepository platformFactsRepository) {
        this.deviceAddSingleService = deviceAddSingleService;
        this.deviceDeletionService = deviceDeletionService;
        this.deviceQueryService = deviceQueryService;
        this.platformFactsRepository = platformFactsRepository;
    }

    @PostMapping("/devices/add-single")
    public ResponseEntity<Map<String, Object>> addSingle(@RequestBody AddSingleRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = actingUser(servletRequest);
        DeviceAddSingleService.Outcome outcome = deviceAddSingleService.addSingle(actorFingerprint, request.role(), request.address(),
                request.vendor(), request.credentialReferenceId(),
                java.util.Optional.ofNullable(request.exportPassphraseCredentialReferenceId()));
        return switch (outcome) {
            case DeviceAddSingleService.Outcome.Admitted admitted -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("device_id", admitted.deviceId());
                body.put("job_id", admitted.jobId());
                body.put("enrollment_state", "DRAFT");
                yield ResponseEntity.ok(body);
            }
            case DeviceAddSingleService.Outcome.ValidationFailed failed -> {
                // codes only, never an address or credential (2026-09-24: refusals left no trace to diagnose from)
                java.util.logging.Logger.getLogger(DeviceRegistrationController.class.getName())
                        .info("[DEVICE_ADD] refused VALIDATION_FAILED " + failed.reasonCode());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "VALIDATION_FAILED");
                body.put("reason_code", failed.reasonCode());
                yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
            }
            case DeviceAddSingleService.Outcome.AdmissionRefused refused -> {
                java.util.logging.Logger.getLogger(DeviceRegistrationController.class.getName())
                        .info("[DEVICE_ADD] refused ADMISSION_REFUSED " + refused.code());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "ADMISSION_REFUSED");
                body.put("code", refused.code());
                body.put("reason", refused.reason());
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
        };
    }

    @PostMapping("/devices/{deviceId}/delete")
    public ResponseEntity<Map<String, Object>> deleteDevice(@PathVariable String deviceId,
            @RequestBody(required = false) DeleteRequest request,
            HttpServletRequest servletRequest) {
        BackupDisposition disposition = request == null ? null : request.backupDisposition();
        DeleteResult result = deviceDeletionService.deleteDevice(
                deviceId, disposition, actingUser(servletRequest), ActionRegistry.DEVICE_DELETE);
        if (result.dispositionRequired()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "BACKUP_DISPOSITION_REQUIRED",
                    "backup_artefact_count", result.backupArtefactCount()));
        }
        if (!result.deleted()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND"));
        }
        return ResponseEntity.ok(Map.of(
                "deleted", true,
                "device_id", deviceId,
                "backup_artefact_count", result.backupArtefactCount()));
    }

    @PostMapping("/devices/{deviceId}/confirm")
    public ResponseEntity<Map<String, Object>> retryConfirm(@PathVariable String deviceId,
            HttpServletRequest servletRequest) {
        String actorFingerprint = actingUser(servletRequest);
        DeviceAddSingleService.Outcome outcome = deviceAddSingleService.retryConfirm(deviceId, actorFingerprint);
        return switch (outcome) {
            case DeviceAddSingleService.Outcome.Admitted admitted -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("admitted", true);
                body.put("device_id", admitted.deviceId());
                body.put("job_id", admitted.jobId());
                yield ResponseEntity.ok(body);
            }
            case DeviceAddSingleService.Outcome.ValidationFailed failed -> {
                // codes only, never an address or credential (2026-09-24: refusals left no trace to diagnose from)
                java.util.logging.Logger.getLogger(DeviceRegistrationController.class.getName())
                        .info("[DEVICE_ADD] refused VALIDATION_FAILED " + failed.reasonCode());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "VALIDATION_FAILED");
                body.put("reason_code", failed.reasonCode());
                yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
            }
            case DeviceAddSingleService.Outcome.AdmissionRefused refused -> {
                java.util.logging.Logger.getLogger(DeviceRegistrationController.class.getName())
                        .info("[DEVICE_ADD] refused ADMISSION_REFUSED " + refused.code());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "ADMISSION_REFUSED");
                body.put("code", refused.code());
                body.put("reason", refused.reason());
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
        };
    }

    @GetMapping("/devices/{deviceId}")
    public ResponseEntity<Map<String, Object>> getDevice(@PathVariable String deviceId) {
        DeviceQueryService.DetailOutcome outcome = deviceQueryService.deviceDetail(deviceId);
        if (outcome instanceof DeviceQueryService.DetailOutcome.NotFound) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "NOT_FOUND");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        DeviceQueryService.DetailOutcome.Found found = (DeviceQueryService.DetailOutcome.Found) outcome;
        Map<String, Object> body = toDetailBody(found.device(), found.facts(), found.job());
        putPlatformFacts(body, platformFactsRepository.find(deviceId));
        putPolicyInstall(body, policyInstallRepository.find(deviceId));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/devices")
    public ResponseEntity<Map<String, Object>> listDevices() {
        Map<String, DevicePlatformFacts> platformFacts = platformFactsRepository.findAll();
        Map<String, com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall> policies = policyInstallRepository.findAll();
        Map<String, java.time.Instant> inventoryAt = overviewService == null ? Map.of() : overviewService.latestInventoryAll();
        List<Map<String, Object>> devices = deviceQueryService.listDevices().stream()
                .map(summary -> {
                    Map<String, Object> body = toSummaryBody(summary);
                    putPlatformFacts(body, Optional.ofNullable(platformFacts.get(summary.deviceId())));
                    putPolicyInstall(body, Optional.ofNullable(policies.get(summary.deviceId())));
                    java.time.Instant inv = inventoryAt.get(summary.deviceId());
                    body.put("inventory_collected_at", inv == null ? null : inv.toString());
                    return body;
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("devices", devices);
        return ResponseEntity.ok(body);
    }

    private static String actingUser(HttpServletRequest servletRequest) {
        return (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static Map<String, Object> toDetailBody(DeviceRecord device, DeviceConfirmFacts facts,
            Optional<JobRow> job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", device.deviceId());
        body.put("role", device.role());
        body.put("vendor_hint", device.vendorHint());
        body.put("enrollment_state", device.enrollmentState().name());
        body.put("disabled", device.disabled());
        body.put("facts", toFactsBody(facts));
        body.put("peer_follow_outcome", facts.peerFollowOutcome());
        body.put("peer_follow_reason", facts.peerFollowReason().orElse(null));
        body.put("identity_mismatch_state", facts.identityMismatchState());
        body.put("cluster_member_ref", facts.clusterMemberRef().orElse(null));
        body.put("job", job.map(DeviceRegistrationController::toJobBody).orElse(null));
        return body;
    }

    /** {@code null} until the confirm has observed at least one fact -- never an object of all-null fields. */
    private static Map<String, Object> toFactsBody(DeviceConfirmFacts facts) {
        if (facts.observedHostname().isEmpty() && facts.observedModel().isEmpty()
                && facts.observedSoftwareVersion().isEmpty() && facts.observedHaRole().isEmpty()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hostname", facts.observedHostname().orElse(null));
        body.put("model", facts.observedModel().orElse(null));
        body.put("software_version", facts.observedSoftwareVersion().orElse(null));
        body.put("ha_role", facts.observedHaRole().orElse(null));
        return body;
    }

    private static Map<String, Object> toJobBody(JobRow jobRow) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", jobRow.jobId());
        body.put("state", jobRow.state());
        body.put("outcome", jobRow.outcome());
        body.put("terminal_reason", jobRow.terminalReason());
        return body;
    }

    /**
     * Platform identity facts (V46, PLATFORM_IDENTITY_FACTS_CONTRACT §2): always present as keys so the
     * screen can say UNKNOWN; {@code serial_number} is masked for the AIView persona by the body advice.
     */
    static void putPlatformFacts(Map<String, Object> body, Optional<DevicePlatformFacts> facts) {
        body.put("serial_number", facts.flatMap(DevicePlatformFacts::serialNumber).orElse(null));
        body.put("hotfix_level", facts.flatMap(DevicePlatformFacts::hotfixLevel).orElse(null));
        body.put("platform_family", facts.flatMap(DevicePlatformFacts::platformFamily).orElse(null));
        body.put("content_versions", facts.map(DevicePlatformFacts::contentVersions).filter(m -> !m.isEmpty()).orElse(null));
        body.put("uptime_text", facts.flatMap(DevicePlatformFacts::uptimeText).orElse(null));
        body.put("platform_facts_observed_at", facts.flatMap(DevicePlatformFacts::observedAt).map(Object::toString).orElse(null));
        // Amendment A-2026-09-23 (PLATFORM_IDENTITY_FACTS_CONTRACT §2): which read produced these facts -- a read-kind
        // token such as cp_show_asset_system_cpinfo_hotfixes_uptime, never device output.
        body.put("platform_facts_source", facts.map(DevicePlatformFacts::sourceRead).orElse(null));
    }

    private static Map<String, Object> toSummaryBody(DeviceSummaryRecord summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", summary.deviceId());
        body.put("role", summary.role());
        body.put("vendor_hint", summary.vendorHint());
        body.put("backup_target", summary.backupTarget());
        body.put("enrollment_state", summary.enrollmentState().name());
        body.put("hostname", summary.observedHostname().orElse(null));
        body.put("model", summary.observedModel().orElse(null));
        body.put("software_version", summary.observedSoftwareVersion().orElse(null));
        body.put("ha_role", summary.observedHaRole().orElse(null));
        body.put("cluster_member_ref", summary.clusterMemberRef().orElse(null));
        body.put("latest_job_state", summary.latestJobState().orElse(null));
        body.put("latest_job_type", summary.latestJobType().orElse(null));
        body.put("latest_job_terminal_reason", summary.latestJobTerminalReason().orElse(null));
        body.put("virtual_systems", summary.virtualSystems().orElse(null));
        body.put("management_ip", summary.managementIp().orElse(null));
        body.put("ip_addresses", summary.ipAddresses().orElse(null));
        return body;
    }
}
