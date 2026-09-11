package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.DeviceStatePort;
import com.securityexpert.nexus.ui2.platform.Result;

/**
 * The {@link DeviceStatePort} implementation (adjudication F7, F12: the
 * port is declared in {@code platform-core}, implemented here). Built over
 * {@link DeviceRepository} rather than a fresh SQL statement, so the same
 * conditional-update guard ({@link DeviceRepository#transitionEnrollmentState})
 * backs both the onboarding confirm flow and this executor-facing path --
 * one legal-transition table, one enforcement point.
 */
public final class JooqDeviceStatePort implements DeviceStatePort {

    private final DeviceRepository deviceRepository;

    public JooqDeviceStatePort(DeviceRepository deviceRepository) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
    }

    @Override
    public Result<DeviceEnrollmentState> recordContactOutcome(String deviceId, boolean contactSucceeded,
            String actorFingerprint, String actionId) {
        Optional<DeviceRecord> maybeDevice = deviceRepository.find(deviceId);
        if (maybeDevice.isEmpty()) {
            return Result.err("DEVICE_NOT_FOUND", "no device row for the given device_id");
        }
        DeviceEnrollmentState current = maybeDevice.get().enrollmentState();
        DeviceEnrollmentState target = contactSucceeded ? DeviceEnrollmentState.ENROLLED
                : DeviceEnrollmentState.UNREACHABLE;

        if (current == DeviceEnrollmentState.DRAFT) {
            // DRAFT -> ENROLLED is legal in the enum's general transition
            // table, but only via the separate, authorized "confirm"
            // action (contract §3/§4) -- never via a contact outcome. This
            // movement's own registration flow never hands a DRAFT device
            // to an executor in the first place (F6); this is the
            // defensive second refusal for a caller that tried anyway.
            return Result.err("DRAFT_DEVICE_NOT_CONTACTABLE",
                    "a DRAFT device is confirmed via the enrollment-confirm action, never a contact outcome");
        }

        if (!current.canTransitionTo(target)) {
            // Contract §3: "no other transition is legal" -- in particular
            // a DRAFT device (never handed to an executor by this
            // movement's own paths) is refused here rather than silently
            // moved, and a contact outcome that would be a no-op
            // (ENROLLED -> ENROLLED on repeated success) is refused rather
            // than pretending a transition happened.
            return Result.err("ILLEGAL_ENROLLMENT_TRANSITION",
                    "cannot transition from " + current + " to " + target);
        }

        boolean applied = deviceRepository.transitionEnrollmentState(deviceId, current, target, actorFingerprint,
                actionId);
        if (!applied) {
            // Lost a race with a concurrent transition out of `current` --
            // fail closed rather than overwrite whatever state won.
            return Result.err("ENROLLMENT_STATE_CHANGED_CONCURRENTLY",
                    "device enrollment_state no longer matched " + current + " when the update ran");
        }
        return Result.ok(target);
    }
}
