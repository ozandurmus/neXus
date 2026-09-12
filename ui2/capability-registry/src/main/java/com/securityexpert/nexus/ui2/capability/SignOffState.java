package com.securityexpert.nexus.ui2.capability;

/**
 * {@code gate_registry.sign_off_state} (C4 §3.2). Only {@link #SIGNED_OFF}
 * resolves {@code KNOWN} (C4 §3.3 step 5); every other value resolves
 * {@code UNKNOWN}, named with the specific state (C4 §3.3 step 5's
 * {@code SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION} precedent, worked
 * against the real {@code rb3b_freespace_read} row in C4 §3.4).
 */
public enum SignOffState {
    DRAFTED,
    SIGNED_OFF,
    SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION,
    BLOCKED,
    SUPERSEDED;

    public static SignOffState fromColumnValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("gate_registry.sign_off_state must not be null");
        }
        try {
            return SignOffState.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unrecognized gate_registry.sign_off_state: " + value, e);
        }
    }
}
