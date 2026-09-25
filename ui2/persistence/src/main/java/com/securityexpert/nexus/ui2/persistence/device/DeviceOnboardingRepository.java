package com.securityexpert.nexus.ui2.persistence.device;

import java.util.List;
import java.util.Optional;

/** V77 {@code device_onboarding}; every write is audited under the caller's actor and action. */
public interface DeviceOnboardingRepository {

    /** Starts (or restarts) the flow at the identity step with the confirm job just admitted. */
    void start(String deviceId, String source, String confirmJobId, String actorFingerprint, String actionId);

    Optional<DeviceOnboarding> find(String deviceId);

    List<DeviceOnboarding> findRunning();

    /** All rows that are not COMPLETED (RUNNING or STOPPED), for the device list. */
    List<DeviceOnboarding> findOpen();

    /**
     * Moves a row that is still at {@code expectedStep}/{@code expectedJobId} to its next position.
     *
     * @return false when another writer moved it first (nothing written)
     */
    boolean advance(String deviceId, String expectedStep, Optional<String> expectedJobId, String state, String step,
            Optional<String> stepJobId, Optional<String> reason, String skipped, String actorFingerprint, String actionId);

    DeviceOnboardingRepository NONE = new DeviceOnboardingRepository() {
        @Override
        public void start(String deviceId, String source, String confirmJobId, String actorFingerprint, String actionId) {
        }

        @Override
        public Optional<DeviceOnboarding> find(String deviceId) {
            return Optional.empty();
        }

        @Override
        public List<DeviceOnboarding> findRunning() {
            return List.of();
        }

        @Override
        public List<DeviceOnboarding> findOpen() {
            return List.of();
        }

        @Override
        public boolean advance(String deviceId, String expectedStep, Optional<String> expectedJobId, String state,
                String step, Optional<String> stepJobId, Optional<String> reason, String skipped,
                String actorFingerprint, String actionId) {
            return false;
        }
    };
}
