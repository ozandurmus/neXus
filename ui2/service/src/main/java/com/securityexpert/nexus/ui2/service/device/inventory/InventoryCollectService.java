package com.securityexpert.nexus.ui2.service.device.inventory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.InventoryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * {@code POST /devices/{id}/inventory/collect} (WORKER.md "Routes"): admits
 * the vendor's inventory-collect capability through {@link
 * JobAdmissionService}, exactly as {@link
 * com.securityexpert.nexus.ui2.service.device.DeviceAddSingleService}
 * admits the enrollment confirm -- but this device already exists, so
 * there is no paired write to roll back on refusal and no shared
 * transaction is needed.
 */
public final class InventoryCollectService {

    public sealed interface Outcome {
        record Admitted(String jobId) implements Outcome {
        }

        record DeviceNotFound() implements Outcome {
        }

        record AdmissionRefused(String code, String reason) implements Outcome {
        }
    }

    /** Counts only, so a bulk request does not return a fleet's opaque device identifiers. */
    public record BulkOutcome(int enrolledDevices, int admitted, int refused) {
    }

    private static final Map<String, String> CAPABILITY_BY_VENDOR = Map.of(
            "check_point", InventoryCapabilityIds.CP_INVENTORY_COLLECT,
            "palo_alto", InventoryCapabilityIds.PAN_INVENTORY_COLLECT,
            // PO 2026-09-25: HTTPS vendors collect through their own inventory job (grid members / managed devices).
            "infoblox", InventoryCapabilityIds.HTTPS_INVENTORY_COLLECT,
            "radware", InventoryCapabilityIds.HTTPS_INVENTORY_COLLECT,
            "bluecoat", InventoryCapabilityIds.HTTPS_INVENTORY_COLLECT);
    /** Vendors whose appliances and management servers are collected over HTTPS (V64 role "appliance"). */
    private static final java.util.Set<String> HTTPS_VENDORS = java.util.Set.of("infoblox", "radware", "bluecoat");

    private final DeviceRepository deviceRepository;
    private final JobAdmissionService jobAdmissionService;
    private final Clock clock;

    public InventoryCollectService(DeviceRepository deviceRepository, JobAdmissionService jobAdmissionService) {
        this(deviceRepository, jobAdmissionService, Clock.systemUTC());
    }

    InventoryCollectService(DeviceRepository deviceRepository, JobAdmissionService jobAdmissionService,
            Clock clock) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.jobAdmissionService = Objects.requireNonNull(jobAdmissionService, "jobAdmissionService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param clientNonce WORKER.md "Routes": "idempotency key deviceId +
     *                    ':inventory:' + a per-request nonce the client
     *                    sends (or the minute bucket if absent)"
     */
    public Outcome requestCollect(String deviceId, String actorFingerprint, Optional<String> clientNonce) {
        Optional<DeviceRecord> device = deviceRepository.find(deviceId);
        if (device.isEmpty()) {
            return new Outcome.DeviceNotFound();
        }
        String role = device.get().role();
        String vendorHint = String.valueOf(device.get().vendorHint()).toLowerCase(java.util.Locale.ROOT);
        // PO 2026-09-25: an Infoblox Grid Manager (appliance) and a Radware Cyber Controller (management server) have an
        // HTTPS inventory read; a DefensePro appliance has none gated yet, so it is refused here rather than failing nightly.
        boolean httpsCollectable = ("infoblox".equals(vendorHint) && "appliance".equals(role))
                || ("radware".equals(vendorHint) && ("management_server".equals(role) || "appliance".equals(role)))
                || ("bluecoat".equals(vendorHint) && "management_server".equals(role));
        if (!"gateway".equals(role) && !httpsCollectable) {
            if (HTTPS_VENDORS.contains(vendorHint) && "appliance".equals(role)) {
                return new Outcome.AdmissionRefused("VENDOR_READ_SET_UNGATED",
                        "device " + deviceId + " is a " + vendorHint + " appliance whose inventory reads are not measured or gated yet, so nothing was issued");
            }
            // PO 2026-09-22: a Check Point management server (SMS / MDS) is a Gaia host -- its interfaces,
            // routes and platform identity come from the same Expert reads the gateway path issues (fw
            // getifs, ip route, cpinfo, uptime, show asset system), all gated; the cluster probe answers
            // "standalone". Panorama is not a Gaia host: its read set is still unmeasured (14I MS-2).
            if ("management_server".equals(role) && !"check_point".equalsIgnoreCase(device.get().vendorHint())) {
                return new Outcome.AdmissionRefused("MANAGEMENT_SERVER_UNGATED",
                        "device " + deviceId + " is a management server; its per-vendor read set has not been measured or gated yet, so nothing was issued (14I MS-2)");
            }
            if (!"management_server".equals(role)) {
            return new Outcome.AdmissionRefused("ROLE_UNRECOGNISED",
                    "device " + deviceId + " carries role '" + role + "', which is not one the product knows how to collect from, so nothing was issued");
            }
        }
        String capabilityId = CAPABILITY_BY_VENDOR.get(device.get().vendorHint());
        if (capabilityId == null) {
            return new Outcome.AdmissionRefused("VENDOR_UNSUPPORTED",
                    "device " + deviceId + " vendor_hint=" + device.get().vendorHint()
                            + " has no registered inventory-collect capability");
        }

        String nonce = clientNonce.filter(value -> !value.isBlank()).orElseGet(this::minuteBucket);
        String idempotencyKey = deviceId + ":inventory:" + nonce;

        AdmissionResult admission = jobAdmissionService.submit(capabilityId, deviceId, idempotencyKey,
                actorFingerprint, ActionRegistry.DEVICE_INVENTORY_COLLECT);
        return switch (admission) {
            case AdmissionResult.Admitted admitted -> new Outcome.Admitted(admitted.jobId());
            case AdmissionResult.Deduplicated deduplicated -> new Outcome.Admitted(deduplicated.jobId());
            case AdmissionResult.Refused refused -> new Outcome.AdmissionRefused(refused.code(), refused.reason());
        };
    }

    /** Admits one read-only inventory job per currently enrolled device. */
    public BulkOutcome requestCollectAll(String actorFingerprint) {
        int enrolledDevices = 0;
        int admitted = 0;
        for (var device : deviceRepository.listAll()) {
            if (device.enrollmentState() != com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED) {
                continue;
            }
            enrolledDevices++;
            if (requestCollect(device.deviceId(), actorFingerprint, Optional.empty()) instanceof Outcome.Admitted) {
                admitted++;
            }
        }
        return new BulkOutcome(enrolledDevices, admitted, enrolledDevices - admitted);
    }

    private String minuteBucket() {
        Instant now = Instant.now(clock);
        return String.valueOf(now.getEpochSecond() / 60);
    }
}
