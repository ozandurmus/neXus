package com.securityexpert.nexus.ui2.platform;

/**
 * The device-state port (adjudication F7, F12). A device state change
 * never aborts a run already {@code EXECUTING}: B1-4's executor calls this
 * port <b>after</b> its attempt record is written, inside {@code C2}
 * §5.1's own mutation boundary. Declared here in {@code platform-core}
 * (no project dependencies, {@code DIR-1}) so {@code worker} can depend on
 * it without reaching {@code persistence} directly; {@code persistence}
 * supplies the only implementation (F12).
 *
 * <p>This movement ({@code B1-4b}) declares and implements the port; it
 * does not call it -- there is no executor yet ({@code B1-4}, sequenced
 * after this movement per the adjudication's implementation order).</p>
 */
public interface DeviceStatePort {

    /**
     * Records one contact attempt's outcome and applies the resulting
     * {@code enrollment_state} transition (contract §3): a failure moves
     * {@code ENROLLED}/{@code DEGRADED} to {@code UNREACHABLE}; a success
     * moves {@code UNREACHABLE}/{@code DEGRADED} to {@code ENROLLED}. A
     * device not currently in one of those source states (in particular
     * {@code DRAFT}, which this movement's onboarding flow never hands to
     * an executor) is refused rather than silently transitioned --
     * "no other transition is legal" fails closed here too.
     *
     * @param deviceId          the opaque device identifier
     * @param contactSucceeded  the executor's own outcome for this attempt
     * @param actorFingerprint  the reserved worker actor (F3 part two;
     *                          this port never sets it, only carries it
     *                          through to the audited transaction)
     * @param actionId          the closed action-registry id authorizing
     *                          this mutation
     * @return the new state on success, or an {@code Err} naming why no
     *         transition was applied (never a silent no-op)
     */
    Result<DeviceEnrollmentState> recordContactOutcome(String deviceId, boolean contactSucceeded,
            String actorFingerprint, String actionId);
}
