package com.securityexpert.nexus.ui2.service.device;

/**
 * The device workspace's rendering of {@code devices.enrollment_state}
 * (contract {@code UI2_0_B1_09_DEVICE_WORKSPACE_CONTRACT.md} §5.1). Each
 * constant's {@link #copy()} states only what the row records, never what
 * the device is doing now ("configuration intent != runtime truth",
 * AGENTS.md) -- {@link #ENROLLED}'s copy in particular makes no reachability
 * claim.
 *
 * <p>Deliberately carries no timestamp, tone, or health field: §5.1's "no
 * freshness claim without a freshness fact" rule is enforced by this type
 * never having such a component, not by a caller choosing not to render
 * one it could otherwise read.</p>
 */
public enum EnrollmentStateView {

    DRAFT("Registered. Not confirmed reachable. Never collected from."),
    ENROLLED("A past authorized confirmation succeeded."),
    UNREACHABLE("The most recent recorded contact failed."),
    DEGRADED("Reachable at last contact, with a non-fatal capability-level signal recorded."),
    /**
     * Contract §5.1's fail-closed outcome for any {@code enrollment_state}
     * value outside the four-value closed vocabulary. Never defaulted to
     * {@link #ENROLLED}, never hidden from a list.
     */
    NOT_EVALUABLE("The recorded enrollment state is not recognized. No action on this device is treated as eligible.");

    private final String copy;

    EnrollmentStateView(String copy) {
        this.copy = copy;
    }

    /** Plain-language copy for this state -- attributes the state to a recorded transition, never a self-description. */
    public String copy() {
        return copy;
    }

    /**
     * Contract §5.1: an out-of-vocabulary row renders {@code NOT_EVALUABLE}
     * with no eligible affordance. Fails closed on any raw value this
     * enum does not itself declare -- it does not defer to
     * {@code DeviceEnrollmentState.fromColumnValue}, which throws instead
     * of rendering; a render path must never throw on an unrecognized row.
     */
    public static EnrollmentStateView fromColumnValue(String rawValue) {
        if (rawValue == null) {
            return NOT_EVALUABLE;
        }
        for (EnrollmentStateView value : values()) {
            if (value.name().equals(rawValue)) {
                return value;
            }
        }
        return NOT_EVALUABLE;
    }

    /**
     * Whether a device in this state is one this screen would ever treat a
     * device-scoped action as eligible against (contract §5.1's
     * {@code NOT_EVALUABLE} row: "no affordance treats it as eligible").
     * This is a read-model-level signal only -- no action this movement's
     * registry declares currently consults it (§6.4/§7: the device-scoped
     * actions that would consult it are owned and served elsewhere, by a
     * screen this contract does not ship); wiring it into a real
     * per-action {@code E5}/{@code E6} check is outside this layer's
     * boundary.
     */
    public boolean permitsDeviceScopedActionEligibility() {
        return this != NOT_EVALUABLE;
    }
}
