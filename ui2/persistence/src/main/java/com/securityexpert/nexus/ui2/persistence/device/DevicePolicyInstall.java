package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The security policy a gateway reports as installed (V60): its name, the install time exactly as reported, the same
 * time parsed when the text could be parsed, and when it was read. Every field optional -- UNKNOWN, never guessed.
 */
public record DevicePolicyInstall(String deviceId, Optional<String> policyName, Optional<String> installedAtText,
        Optional<Instant> installedAt, String sourceRead, Optional<Instant> observedAt) {

    public DevicePolicyInstall {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(policyName, "policyName");
        Objects.requireNonNull(installedAtText, "installedAtText");
        Objects.requireNonNull(installedAt, "installedAt");
        Objects.requireNonNull(sourceRead, "sourceRead");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public boolean isEmpty() {
        return policyName.isEmpty() && installedAtText.isEmpty();
    }
}
