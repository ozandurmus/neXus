package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Map;
import java.util.Optional;

/** Port over {@code device_policy_install} (V60). */
public interface DevicePolicyInstallRepository {

    void record(DevicePolicyInstall install);

    Optional<DevicePolicyInstall> find(String deviceId);

    Map<String, DevicePolicyInstall> findAll();

    DevicePolicyInstallRepository NONE = new DevicePolicyInstallRepository() {
        @Override
        public void record(DevicePolicyInstall install) {
        }

        @Override
        public Optional<DevicePolicyInstall> find(String deviceId) {
            return Optional.empty();
        }

        @Override
        public Map<String, DevicePolicyInstall> findAll() {
            return Map.of();
        }
    };
}
