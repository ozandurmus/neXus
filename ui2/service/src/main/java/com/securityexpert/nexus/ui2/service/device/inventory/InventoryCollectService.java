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

    private static final Map<String, String> CAPABILITY_BY_VENDOR = Map.of(
            "check_point", InventoryCapabilityIds.CP_INVENTORY_COLLECT,
            "palo_alto", InventoryCapabilityIds.PAN_INVENTORY_COLLECT);

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
        if ("management_server".equals(device.get().role())) {
            return new Outcome.AdmissionRefused("MANAGEMENT_SERVER_UNGATED",
                    "device " + deviceId + " is a management server; its per-vendor read set has not been measured or gated yet, so nothing was issued (14I MS-2)");
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

    private String minuteBucket() {
        Instant now = Instant.now(clock);
        return String.valueOf(now.getEpochSecond() / 60);
    }
}
