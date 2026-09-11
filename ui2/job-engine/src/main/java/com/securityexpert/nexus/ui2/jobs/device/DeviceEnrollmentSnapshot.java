package com.securityexpert.nexus.ui2.jobs.device;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * The read-only view of one device's enrollment that job admission (F4)
 * and {@code C2} §6 check 5's claim-time re-check (F6) both consult.
 * Carries no sensitive field (no {@code address_ref}, no credential
 * reference) -- only what an admission/claim decision needs.
 */
public record DeviceEnrollmentSnapshot(String deviceId, DeviceEnrollmentState enrollmentState, boolean disabled) {

    /**
     * Contract §3: {@code DRAFT} never permits collection or a job, and a
     * disabled device never does either, regardless of its enrollment
     * state (F6's two-point enforcement reads this at both admission and
     * claim time).
     */
    public boolean permitsReadCollection() {
        return !disabled && enrollmentState.permitsReadCollection();
    }
}
