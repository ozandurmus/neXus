package com.securityexpert.nexus.ui2.service.device.backup;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * {@code POST /devices/{id}/backup/collect} (WORKER.md "Service and
 * screen"): admits {@code cp_gateway_backup} through {@link
 * JobAdmissionService}, mirroring {@code
 * service.device.configuration.ConfigurationCollectService} -- with three
 * refusals configuration collection never needed: BK-12's reason-length
 * gate, BK-1's pilot-device allowlist (empty by default, so every backup is
 * refused until the Product Owner names a device -- WORKER.md: "empty means
 * every backup is refused"), and BK-11's distinct-credential precondition,
 * checked here too so a doomed job never reaches {@code REQUESTED} in the
 * first place.
 */
public final class BackupCollectService {

    public sealed interface Outcome {
        record Admitted(String jobId) implements Outcome {
        }

        record DeviceNotFound() implements Outcome {
        }

        record AdmissionRefused(String code, String reason) implements Outcome {
        }
    }

    private static final int MIN_REASON_LENGTH = 8;

    private final DeviceRepository deviceRepository;
    private final JobAdmissionService jobAdmissionService;
    private final Set<String> pilotAllowlist;
    private final boolean backupCredentialConfigured;
    private final Clock clock;

    public BackupCollectService(DeviceRepository deviceRepository, JobAdmissionService jobAdmissionService,
            Set<String> pilotAllowlist, boolean backupCredentialConfigured) {
        this(deviceRepository, jobAdmissionService, pilotAllowlist, backupCredentialConfigured, Clock.systemUTC());
    }

    BackupCollectService(DeviceRepository deviceRepository, JobAdmissionService jobAdmissionService,
            Set<String> pilotAllowlist, boolean backupCredentialConfigured, Clock clock) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.jobAdmissionService = Objects.requireNonNull(jobAdmissionService, "jobAdmissionService");
        this.pilotAllowlist = Set.copyOf(Objects.requireNonNull(pilotAllowlist, "pilotAllowlist"));
        this.backupCredentialConfigured = backupCredentialConfigured;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Outcome requestCollect(String deviceId, String actorFingerprint, String reason, Optional<String> clientNonce) {
        if (reason == null || reason.strip().length() < MIN_REASON_LENGTH) {
            return new Outcome.AdmissionRefused("REASON_TOO_SHORT",
                    "a backup requires a reason of at least eight characters (BK-12)");
        }

        Optional<DeviceRecord> device = deviceRepository.find(deviceId);
        if (device.isEmpty()) {
            return new Outcome.DeviceNotFound();
        }
        String role = device.get().role();
        if (!"gateway".equals(role)) {
            if ("management_server".equals(role)) {
                return new Outcome.AdmissionRefused("MANAGEMENT_SERVER_UNGATED",
                        "device " + deviceId + " is a management server; its per-vendor read set has not been measured or gated yet, so nothing was issued (14I MS-2)");
            }
            return new Outcome.AdmissionRefused("ROLE_UNRECOGNISED",
                    "device " + deviceId + " carries role '" + role + "', which is not one the product knows how to collect from, so nothing was issued");
        }
        if (!"check_point".equals(device.get().vendorHint())) {
            return new Outcome.AdmissionRefused("VENDOR_UNSUPPORTED", "device " + deviceId + " vendor_hint="
                    + device.get().vendorHint() + " has no registered backup capability (14H BK-9: Check Point "
                    + "gateway only)");
        }
        if (!pilotAllowlist.contains(deviceId)) {
            return new Outcome.AdmissionRefused("DEVICE_NOT_IN_BACKUP_PILOT_ALLOWLIST",
                    "device " + deviceId + " is not on the backup pilot allowlist (14H BK-1) -- refused, never a "
                            + "silent skip");
        }
        if (!backupCredentialConfigured) {
            return new Outcome.AdmissionRefused("BACKUP_CREDENTIAL_NOT_CONFIGURED",
                    "no distinct backup credential is configured for check_point (BK-11: never falls back to the "
                            + "collection credential)");
        }

        String nonce = clientNonce.filter(value -> !value.isBlank()).orElseGet(this::minuteBucket);
        String idempotencyKey = deviceId + ":backup:" + nonce;

        AdmissionResult admission = jobAdmissionService.submit(BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL, deviceId,
                idempotencyKey, actorFingerprint, ActionRegistry.DEVICE_BACKUP_COLLECT);
        return switch (admission) {
            case AdmissionResult.Admitted admitted -> new Outcome.Admitted(admitted.jobId());
            case AdmissionResult.Deduplicated deduplicated -> new Outcome.Admitted(deduplicated.jobId());
            case AdmissionResult.Refused refused -> new Outcome.AdmissionRefused(refused.code(), refused.reason());
        };
    }

    private String minuteBucket() {
        Instant now = Instant.now(clock);
        return String.valueOf(now.getEpochSecond() / 60);
    }
}
