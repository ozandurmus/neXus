package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Map;
import java.util.Optional;

/** Port over {@code device_identity_check} (V165) joined with the device's recorded-identity conflict flag. */
public interface DeviceIdentityCheckRepository {

    Optional<DeviceIdentityCheck> find(String deviceId);

    /** Every device, whether or not a comparison was ever recorded for it. */
    Map<String, DeviceIdentityCheck> findAll();

    DeviceIdentityCheckRepository NONE = new DeviceIdentityCheckRepository() {
        @Override
        public Optional<DeviceIdentityCheck> find(String deviceId) {
            return Optional.empty();
        }

        @Override
        public Map<String, DeviceIdentityCheck> findAll() {
            return Map.of();
        }
    };
}
