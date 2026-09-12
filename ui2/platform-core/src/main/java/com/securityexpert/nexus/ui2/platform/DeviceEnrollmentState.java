package com.securityexpert.nexus.ui2.platform;

import java.util.Set;

/**
 * {@code devices.enrollment_state}'s closed vocabulary (B1-4b contract §3,
 * adjudication {@code UI2_0_B1_ADJUDICATION_2026_09_12.md} §2 answer 7:
 * {@code DRAFT}, {@code ENROLLED}, {@code UNREACHABLE}, {@code DEGRADED} --
 * {@code disabled} is a separate boolean column, never a fifth value here).
 *
 * <p>Declared in {@code platform-core} (no project dependencies, {@code
 * DIR-1}) so both {@code job-engine} (the read port a job admission/claim
 * check consults, adjudication F12) and {@code persistence}/{@code service}
 * can share one closed type without a dependency cycle.</p>
 */
public enum DeviceEnrollmentState {
    DRAFT,
    ENROLLED,
    UNREACHABLE,
    DEGRADED;

    /**
     * Contract §3: {@code DRAFT} never permits collection or a job;
     * {@code ENROLLED}/{@code DEGRADED} are eligible for read-class
     * collection; {@code UNREACHABLE} permits display/submission only,
     * judged on outcome, never inferred from the label. This method
     * answers only the {@code DRAFT}-never-collected half of that rule
     * (test 1) -- it is not itself the admission/claim check (B1-4's own
     * scope, adjudication F4/F6).
     */
    public boolean permitsReadCollection() {
        return this == ENROLLED || this == DEGRADED;
    }

    /**
     * Contract §3's transition table, minus the two rows that are not a
     * same-column state change ({@code DRAFT -> deleted} is a row
     * deletion; {@code any -> disabled} is the separate boolean column).
     * "No other transition is legal; an unrecognized value fails closed"
     * (§3) -- this method is the single place that fact is encoded, so a
     * caller can never invent a transition this table does not list.
     */
    public boolean canTransitionTo(DeviceEnrollmentState target) {
        return switch (this) {
            case DRAFT -> target == ENROLLED;
            case ENROLLED -> target == UNREACHABLE || target == DEGRADED;
            case UNREACHABLE -> target == ENROLLED;
            case DEGRADED -> target == UNREACHABLE || target == ENROLLED;
        };
    }

    private static final Set<String> COLUMN_VALUES = Set.of("DRAFT", "ENROLLED", "UNREACHABLE", "DEGRADED");

    /**
     * Fail-closed parse from the database column value (AGENTS.md
     * UNKNOWN/fail-closed law; contract §3: "an unrecognized value fails
     * closed, never defaulting to ENROLLED"). Throws rather than guessing.
     */
    public static DeviceEnrollmentState fromColumnValue(String columnValue) {
        if (columnValue == null || !COLUMN_VALUES.contains(columnValue)) {
            throw new IllegalStateException(
                    "unrecognized devices.enrollment_state value -- failing closed, never defaulting to ENROLLED");
        }
        return DeviceEnrollmentState.valueOf(columnValue);
    }
}
