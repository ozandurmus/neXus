package com.securityexpert.nexus.ui2.worker.inventory.policy;

import java.time.Instant;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall;

/** What one inventory contact read about the installed policy (V60), before the device id is attached. */
public record PolicyInstallRead(Optional<String> policyName, Optional<String> installedAtText, Optional<Instant> installedAt,
        String sourceRead) {

    public static final PolicyInstallRead NONE = new PolicyInstallRead(Optional.empty(), Optional.empty(), Optional.empty(), "none");

    public DevicePolicyInstall forDevice(String deviceId) {
        return new DevicePolicyInstall(deviceId, policyName, installedAtText, installedAt, sourceRead, Optional.empty());
    }
}
