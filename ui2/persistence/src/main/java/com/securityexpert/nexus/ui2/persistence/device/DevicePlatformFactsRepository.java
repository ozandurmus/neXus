package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Map;
import java.util.Optional;

/** V46 {@code device_platform_facts}: one row per device, overwritten by every successful read. */
public interface DevicePlatformFactsRepository {

    /** Records the facts, replacing the device's previous row; an empty facts object is not written. */
    void record(DevicePlatformFacts facts);

    Optional<DevicePlatformFacts> find(String deviceId);

    /** Every device's facts keyed by device id -- one query for the device list. */
    Map<String, DevicePlatformFacts> findAll();

    /** A repository that records nothing and knows nothing -- for wiring that predates V46 and for tests. */
    DevicePlatformFactsRepository NONE = new DevicePlatformFactsRepository() {
        @Override
        public void record(DevicePlatformFacts facts) {
        }

        @Override
        public Optional<DevicePlatformFacts> find(String deviceId) {
            return Optional.empty();
        }

        @Override
        public Map<String, DevicePlatformFacts> findAll() {
            return Map.of();
        }
    };
}
