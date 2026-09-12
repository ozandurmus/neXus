package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;

/**
 * Job admission (adjudication F4, F6): "a submission against a capability
 * whose gate is unresolved, or against a DRAFT or disabled device, is
 * refused BEFORE a job row ever reaches REQUESTED." Every check below is
 * evaluated in the fixed order listed, and the first failure refuses the
 * submission -- no job row is ever created for a refused submission
 * (contrast {@code C2} §6's claim-time battery, whose first failure still
 * transitions an already-{@code CLAIMED} row to {@code REJECTED}; here,
 * refusal means "never admitted" in the first place).
 *
 * <p><b>Evaluation order</b> (contract §3, adjudication F4):</p>
 * <ol>
 *   <li><b>Capability exists and is execution-eligible</b> ({@code
 *       CapabilityRegistry#isExecutionEligible}, C4 §3.5) -- refuses
 *       {@code CAPABILITY_UNKNOWN} or {@code CAPABILITY_NOT_EXECUTION_
 *       ELIGIBLE} (the latter covers both "capability not registered at
 *       all" and "registered but one step's gate is {@code UNKNOWN}"; C4
 *       §3.5 states these are refused identically at admission).</li>
 *   <li><b>Target device is enrolled and not disabled</b> ({@link
 *       DeviceEnrollmentReadPort}, F6) -- refuses {@code DEVICE_NOT_FOUND},
 *       {@code DEVICE_DRAFT}, or {@code DEVICE_DISABLED}. Re-checked again
 *       at claim time (F6's second enforcement point) because a device can
 *       be disabled, or degrade, between admission and claim.</li>
 *   <li><b>Idempotency</b> (C2 §2.3) -- a client-supplied key that already
 *       names a job returns {@link AdmissionResult.Deduplicated} rather
 *       than creating a second row; no key is server-generated
 *       ({@link UUID#randomUUID()}) so this step never itself refuses.</li>
 * </ol>
 */
public final class JobAdmissionService {

    private final CapabilityRegistry capabilityRegistry;
    private final DeviceEnrollmentReadPort deviceEnrollmentReadPort;
    private final JobAdmissionRepository repository;

    public JobAdmissionService(CapabilityRegistry capabilityRegistry, DeviceEnrollmentReadPort deviceEnrollmentReadPort,
            JobAdmissionRepository repository) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry");
        this.deviceEnrollmentReadPort = Objects.requireNonNull(deviceEnrollmentReadPort, "deviceEnrollmentReadPort");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public AdmissionResult submit(String capabilityId, String targetDeviceId, String clientIdempotencyKey,
            String actorFingerprint, String actionId) {
        // 1. Capability execution-eligibility (C4 §3.5).
        Optional<Capability> capability = capabilityRegistry.find(capabilityId);
        if (capability.isEmpty()) {
            return new AdmissionResult.Refused("CAPABILITY_UNKNOWN",
                    "no registered capability with capability_id=" + capabilityId);
        }
        if (!capability.get().executionEligible()) {
            return new AdmissionResult.Refused("CAPABILITY_NOT_EXECUTION_ELIGIBLE",
                    "capability " + capabilityId + " has at least one step whose gate resolution is UNKNOWN "
                            + "(C4 §3.5) -- never a member of the set admission is allowed to dispatch");
        }

        // 2. Device enrollment (F6, first enforcement point).
        Optional<DeviceEnrollmentSnapshot> device = deviceEnrollmentReadPort.findEnrollment(targetDeviceId);
        if (device.isEmpty()) {
            return new AdmissionResult.Refused("DEVICE_NOT_FOUND", "no device row for device_id=" + targetDeviceId);
        }
        if (device.get().disabled()) {
            return new AdmissionResult.Refused("DEVICE_DISABLED", "device " + targetDeviceId + " is disabled");
        }
        if (!device.get().permitsReadCollection()) {
            return new AdmissionResult.Refused("DEVICE_NOT_ELIGIBLE",
                    "device " + targetDeviceId + " enrollment_state=" + device.get().enrollmentState()
                            + " does not permit a job to be created against it (DRAFT is refused here)");
        }

        // 3. Idempotency (C2 §2.3): server-generates a key when the caller
        // supplies none; a collision on an existing key de-duplicates
        // rather than creating a second row.
        String idempotencyKey = clientIdempotencyKey != null && !clientIdempotencyKey.isBlank()
                ? clientIdempotencyKey
                : UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();

        Optional<String> created = repository.createRequestedIfAbsent(jobId, idempotencyKey, capabilityId,
                targetDeviceId, capability.get().resolvedActionClassOrDeclaredClass().id(), actorFingerprint,
                actionId);
        if (created.isPresent()) {
            return new AdmissionResult.Admitted(created.get());
        }
        // Lost the idempotency-key race (or a genuine client retry):
        // resolve to whichever job already holds that key rather than
        // ever creating a second row for the same key.
        return repository.findByIdempotencyKey(idempotencyKey)
                .<AdmissionResult>map(AdmissionResult.Deduplicated::new)
                .orElse(new AdmissionResult.Refused("IDEMPOTENCY_KEY_CONFLICT",
                        "a job already exists for idempotency_key=" + idempotencyKey
                                + " but it could not be re-read"));
    }
}
