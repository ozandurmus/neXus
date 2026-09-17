package com.securityexpert.nexus.ui2.service.device.backup;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupJobAuthorizationRepository;
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
 * first place. NXS-LOCAL-0224 (BK-12/BW-4): once every check above passes,
 * the actor and reason it verified are recorded to {@code
 * backup_job_authorization} (migration V23) so the worker's own claim-time
 * re-check has immutable evidence to read back, rather than trusting a
 * possibly stale admission decision.
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
    /** BK-12/BW-4: the {@code backup_job_authorization} action id a fresh admission's evidence row is recorded under. */
    private static final String ACTION_AUTHORIZATION_RECORDED = "backup_job_authorization_recorded";

    private final DeviceRepository deviceRepository;
    private final JobAdmissionService jobAdmissionService;
    private final Set<String> pilotAllowlist;
    private final boolean backupCredentialConfigured;
    private final BackupJobAuthorizationRepository authorizationRepository;
    private final Clock clock;

    public BackupCollectService(DeviceRepository deviceRepository, JobAdmissionService jobAdmissionService,
            Set<String> pilotAllowlist, boolean backupCredentialConfigured,
            BackupJobAuthorizationRepository authorizationRepository) {
        this(deviceRepository, jobAdmissionService, pilotAllowlist, backupCredentialConfigured,
                authorizationRepository, Clock.systemUTC());
    }

    BackupCollectService(DeviceRepository deviceRepository, JobAdmissionService jobAdmissionService,
            Set<String> pilotAllowlist, boolean backupCredentialConfigured,
            BackupJobAuthorizationRepository authorizationRepository, Clock clock) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.jobAdmissionService = Objects.requireNonNull(jobAdmissionService, "jobAdmissionService");
        this.pilotAllowlist = Set.copyOf(Objects.requireNonNull(pilotAllowlist, "pilotAllowlist"));
        this.backupCredentialConfigured = backupCredentialConfigured;
        this.authorizationRepository = Objects.requireNonNull(authorizationRepository, "authorizationRepository");
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
            case AdmissionResult.Admitted admitted -> {
                // BK-12/BW-4: the immutable evidence of what this admission verified --
                // written once, for the worker's own claim-time re-check to read back.
                // A deduplicated retry (below) reuses the original job and its original
                // evidence row; it never overwrites it with a second request's reason.
                authorizationRepository.record(admitted.jobId(), deviceId, actorFingerprint, reason,
                        ACTION_AUTHORIZATION_RECORDED);
                yield new Outcome.Admitted(admitted.jobId());
            }
            case AdmissionResult.Deduplicated deduplicated -> new Outcome.Admitted(deduplicated.jobId());
            case AdmissionResult.Refused refused -> new Outcome.AdmissionRefused(refused.code(), refused.reason());
        };
    }

    private String minuteBucket() {
        Instant now = Instant.now(clock);
        return String.valueOf(now.getEpochSecond() / 60);
    }
}
