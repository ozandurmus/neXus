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

    /** V64: vendors whose backup runs over HTTPS (one capability, routed by vendor in the worker). */
    static final java.util.Set<String> HTTPS_VENDORS = java.util.Set.of("infoblox", "radware", "bluecoat", "pulse_secure");

    public Outcome requestCollect(String deviceId, String actorFingerprint, String reason, Optional<String> clientNonce) {
        return requestCollect(deviceId, actorFingerprint, reason, clientNonce, "backup");
    }

    public Outcome requestCollect(String deviceId, String actorFingerprint, String reason, Optional<String> clientNonce,
            String backupType) {
        if (reason == null || reason.strip().length() < MIN_REASON_LENGTH) {
            return new Outcome.AdmissionRefused("REASON_TOO_SHORT",
                    "a backup requires a reason of at least eight characters (BK-12)");
        }

        Optional<DeviceRecord> device = deviceRepository.find(deviceId);
        if (device.isEmpty()) {
            return new Outcome.DeviceNotFound();
        }
        String role = device.get().role();
        // A Check Point management server (MDS) takes the same Gaia backup a gateway does (PO, 2026-09-23: "normal
        // backup"); its MDS export (mds_backup) is a separate type. Other management servers stay unmeasured.
        boolean checkPointManagement = "management_server".equals(role) && "check_point".equals(device.get().vendorHint());
        // PO 2026-09-25: a Panorama takes the Palo Alto backup (device-state, else its running configuration XML).
        boolean panorama = "management_server".equals(role) && "palo_alto".equals(device.get().vendorHint());
        // V64: appliances backed up over HTTPS (Infoblox, Radware) carry role "appliance"
        boolean httpsVendor = HTTPS_VENDORS.contains(device.get().vendorHint());
        // V69: a Radware management server is a Cyber Controller; its own configuration backup is pushed to HOST-A's
        // SFTP receiver (the DefensePro devices it manages are backed up through it, V67).
        boolean cyberController = "radware".equals(device.get().vendorHint()) && "management_server".equals(role);
        // PO 2026-09-25: a Symantec Management Center backs up the ProxySGs it manages (show configuration through it).
        boolean managementCenter = "bluecoat".equals(device.get().vendorHint()) && "management_server".equals(role);
        if (!cyberController && !managementCenter && httpsVendor && "management_server".equals(role)) {
            return new Outcome.AdmissionRefused("MANAGEMENT_SERVER_UNGATED", "device " + deviceId + " is a management "
                    + "server of a vendor with no gated backup for it, so nothing was issued");
        }
        if (!"gateway".equals(role) && !"firewall".equals(role) && !checkPointManagement && !httpsVendor && !panorama) {
            if ("management_server".equals(role)) {
                return new Outcome.AdmissionRefused("MANAGEMENT_SERVER_UNGATED",
                        "device " + deviceId + " is a management server; its per-vendor read set has not been measured or gated yet, so nothing was issued (14I MS-2)");
            }
            return new Outcome.AdmissionRefused("ROLE_UNRECOGNISED",
                    "device " + deviceId + " carries role '" + role + "', which is not one the product knows how to collect from, so nothing was issued");
        }
        String vendorHint = device.get().vendorHint();
        boolean ciscoAsa = "cisco_asa".equals(vendorHint);
        boolean fortiGate = "fortinet".equals(vendorHint) && "gateway".equals(role);
        if (!"check_point".equals(vendorHint) && !"palo_alto".equals(vendorHint) && !httpsVendor && !ciscoAsa && !fortiGate) {
            return new Outcome.AdmissionRefused("VENDOR_UNSUPPORTED", "device " + deviceId + " vendor_hint="
                    + vendorHint + " has no registered backup capability (14H BK-9: Check Point gateway only)");
        }
        // Product Owner, 2026-09-22: targets are chosen on the Backups screen (devices.backup_target);
        // the env allowlist stays honoured for an operator who still sets it, never as the only door.
        if (!device.get().backupTarget() && !pilotAllowlist.contains(deviceId)) {
            return new Outcome.AdmissionRefused("DEVICE_NOT_A_BACKUP_TARGET",
                    "device " + deviceId + " is not a backup target (Backups > Backup targets) -- refused, never a "
                            + "silent skip");
        }
        if ("check_point".equals(vendorHint) && !backupCredentialConfigured) {
            return new Outcome.AdmissionRefused("BACKUP_CREDENTIAL_NOT_CONFIGURED",
                    "no distinct backup credential is configured for check_point (BK-11: never falls back to the "
                            + "collection credential)");
        }

        String capabilityId;
        if (cyberController) {
            capabilityId = BackupCapabilityIds.RDW_CC_CONFIG_BACKUP;
        } else if (httpsVendor) {
            capabilityId = BackupCapabilityIds.HTTPS_VENDOR_BACKUP;
        } else if (ciscoAsa) {
            capabilityId = BackupCapabilityIds.ASA_CONFIG_BACKUP;
        } else if (fortiGate) {
            capabilityId = BackupCapabilityIds.FGT_CONFIG_BACKUP;
        } else if ("mds_export".equalsIgnoreCase(backupType)) {
            if (!checkPointManagement) {
                return new Outcome.AdmissionRefused("MDS_EXPORT_NOT_APPLICABLE",
                        "an MDS export applies only to a Check Point Multi-Domain Server; device " + deviceId + " is not one");
            }
            capabilityId = BackupCapabilityIds.CP_MDS_EXPORT;
        } else if ("palo_alto".equals(vendorHint)) {
            capabilityId = BackupCapabilityIds.PAN_DEVICE_STATE_BACKUP;
        } else if ("snapshot".equalsIgnoreCase(backupType)) {
            capabilityId = BackupCapabilityIds.CP_GAIA_SNAPSHOT;
        } else {
            capabilityId = BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL;
        }

        String nonce = clientNonce.filter(value -> !value.isBlank()).orElseGet(this::minuteBucket);
        String idempotencyKey = deviceId + ":" + capabilityId + ":" + nonce;

        AdmissionResult admission = jobAdmissionService.submit(capabilityId, deviceId,
                idempotencyKey, actorFingerprint, ActionRegistry.DEVICE_BACKUP_COLLECT);
        return switch (admission) {
            case AdmissionResult.Admitted admitted -> new Outcome.Admitted(admitted.jobId());
            case AdmissionResult.Deduplicated deduplicated -> new Outcome.Admitted(deduplicated.jobId());
            case AdmissionResult.Refused refused -> new Outcome.AdmissionRefused(refused.code(), refused.reason());
        };
    }

    /** Counts only, so a bulk request does not return a fleet's opaque device identifiers. */
    public record BulkOutcome(int targets, int admitted, int refused) {
    }

    /** "Run Fleet Backup": one backup per enrolled, non-disabled backup target -- nothing else is touched. */
    public BulkOutcome requestCollectAll(String actorFingerprint, String reason) {
        int targets = 0;
        int admitted = 0;
        for (var device : deviceRepository.listAll()) {
            if (!device.backupTarget()
                    || device.enrollmentState() != com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED) {
                continue;
            }
            targets++;
            if (requestCollect(device.deviceId(), actorFingerprint, reason, Optional.empty()) instanceof Outcome.Admitted) {
                admitted++;
            }
        }
        return new BulkOutcome(targets, admitted, targets - admitted);
    }

    private String minuteBucket() {
        Instant now = Instant.now(clock);
        return String.valueOf(now.getEpochSecond() / 60);
    }
}
