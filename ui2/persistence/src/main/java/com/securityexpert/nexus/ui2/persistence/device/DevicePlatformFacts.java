package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Platform identity facts of one device (V46): what an audit asks for first.
 * Every fact optional -- a read that did not provide it leaves it empty, and
 * the screen says UNKNOWN (AGENTS.md UNKNOWN / fail-closed law).
 *
 * @param contentVersions vendor content / signature versions by name (Palo
 *        Alto: app, threat, av, wildfire, url); empty for vendors without them
 */
public record DevicePlatformFacts(
        String deviceId,
        Optional<String> serialNumber,
        Optional<String> hotfixLevel,
        Optional<String> platformFamily,
        Map<String, String> contentVersions,
        Optional<String> uptimeText,
        String sourceRead,
        Optional<Instant> observedAt) {

    public DevicePlatformFacts {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(serialNumber, "serialNumber");
        Objects.requireNonNull(hotfixLevel, "hotfixLevel");
        Objects.requireNonNull(platformFamily, "platformFamily");
        contentVersions = contentVersions == null ? Map.of() : Map.copyOf(contentVersions);
        Objects.requireNonNull(uptimeText, "uptimeText");
        Objects.requireNonNull(sourceRead, "sourceRead");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    /** True when the read provided nothing worth recording. */
    public boolean isEmpty() {
        return serialNumber.isEmpty() && hotfixLevel.isEmpty() && platformFamily.isEmpty()
                && contentVersions.isEmpty() && uptimeText.isEmpty();
    }
}
