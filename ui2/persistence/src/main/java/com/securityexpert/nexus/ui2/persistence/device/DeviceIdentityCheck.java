package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What the screen needs to say whether a device's identity was verified (PO decision 2026-10-18): the verdict of the
 * last comparison of the presented SSH host key or HTTPS certificate with the pinned one (V165), and whether the
 * device's first-contact identity (serial / hostname baseline) is in conflict ({@code identity_mismatch_state}).
 * Verdicts only: no fingerprint, key or address is ever carried.
 */
public record DeviceIdentityCheck(String deviceId, Optional<String> transport, Optional<String> verdict,
        Optional<Instant> checkedAt, boolean recordedIdentityMismatch) {

    public enum State { VERIFIED, MISMATCH, NOT_VERIFIED }

    public DeviceIdentityCheck {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }

    /** A device with no comparison on record and no open identity conflict. */
    public static DeviceIdentityCheck none(String deviceId) {
        return new DeviceIdentityCheck(deviceId, Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    /**
     * MISMATCH when the device's recorded identity is in conflict or its last pinned comparison failed; VERIFIED only
     * when the last pinned comparison matched; everything else (never compared, first use, a transport with no pin)
     * is NOT_VERIFIED -- absence of a comparison is never read as a match.
     */
    public State state() {
        if (recordedIdentityMismatch || verdict.filter("MISMATCH"::equals).isPresent()) return State.MISMATCH;
        if (verdict.filter("MATCH"::equals).isPresent()) return State.VERIFIED;
        return State.NOT_VERIFIED;
    }

    /** What the state rests on, for the tooltip: the comparison's transport, the recorded identity, or nothing. */
    public String basis() {
        if (recordedIdentityMismatch && verdict.filter("MISMATCH"::equals).isEmpty()) return "RECORDED_IDENTITY";
        return transport.orElse("NONE");
    }
}
