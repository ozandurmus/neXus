package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Optional;

/**
 * Narrow observation and enrollment-confirmation write port on {@code devices} for the {@code inventory} and
 * {@code configuration} worker modules ({@code docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md}
 * §2.5.3, §4.2 Step 0).
 *
 * <p>Includes first-contact confirmation ({@link #recordConfirmSuccess}), observed-fact refresh
 * ({@link #refreshObservedFacts}, {@link #clearObservedPlatformFacts}), and HA/cluster relationship updates
 * ({@link #setClusterMemberRef}, {@link #recordHaPairClaim}), without exposing device creation, deletion, or
 * credential/administrative mutations.</p>
 */
public interface DeviceInventoryWriteRepository extends DeviceReadRepository {

    /** Updates a device's confirmed cluster reference only when it differs. */
    default boolean setClusterMemberRef(String deviceId, Optional<String> ref, String actorFingerprint, String actionId) {
        throw new UnsupportedOperationException("setClusterMemberRef");
    }

    /** Store this device's claim and reconcile its pair in one audited transaction. */
    default void recordHaPairClaim(String deviceId, String claim, String actorFingerprint, String actionId) {
        throw new UnsupportedOperationException("recordHaPairClaim");
    }

    /**
     * EC-J3: the confirm job's completion handler moves the row {@code
     * DRAFT -> ENROLLED} with the observed facts, atomically, in one
     * fenced update guarded by {@code WHERE enrollment_state = 'DRAFT'}.
     */
    boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint, String actionId);

    /**
     * PO 2026-09-25: observed facts follow every completed read. Each present value replaces the stored one when it
     * differs (an absent value leaves the stored one alone); the identity baseline (recorded_identity_*) is untouched.
     * True when the row changed.
     */
    default boolean refreshObservedFacts(String deviceId, Optional<String> hostname, Optional<String> model,
            Optional<String> softwareVersion, String actorFingerprint, String actionId) {
        return refreshObservedFacts(deviceId, hostname, model, softwareVersion, Optional.empty(), actorFingerprint, actionId);
    }

    default boolean refreshObservedFacts(String deviceId, Optional<String> hostname, Optional<String> model,
            Optional<String> softwareVersion, Optional<String> haRole, String actorFingerprint, String actionId) {
        return false;
    }

    /**
     * PO 2026-10-21 ({@link PanSystemInfoRetirement}): a Palo Alto firewall's model and software version came only from
     * {@code show system info}, now retired -- a run that could not read them clears them rather than leave the previous
     * value standing as current.
     */
    default boolean clearObservedPlatformFacts(String deviceId, String actorFingerprint, String actionId) {
        return false;
    }
}
