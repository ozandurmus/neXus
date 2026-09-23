package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;

/**
 * Platform identity facts one inventory contact read, before the device id is
 * known to the executor (PLATFORM_IDENTITY_FACTS_CONTRACT §1). The job
 * executor turns it into the persisted {@link DevicePlatformFacts}.
 */
public record PlatformFactsRead(
        Optional<String> serialNumber,
        Optional<String> hotfixLevel,
        Optional<String> platformFamily,
        Map<String, String> contentVersions,
        Optional<String> uptimeText,
        String sourceRead,
        com.securityexpert.nexus.ui2.worker.inventory.policy.PolicyInstallRead policyInstall) {

    public PlatformFactsRead {
        contentVersions = contentVersions == null ? Map.of() : Map.copyOf(contentVersions);
        policyInstall = policyInstall == null ? com.securityexpert.nexus.ui2.worker.inventory.policy.PolicyInstallRead.NONE : policyInstall;
    }

    public PlatformFactsRead(Optional<String> serialNumber, Optional<String> hotfixLevel, Optional<String> platformFamily,
            Map<String, String> contentVersions, Optional<String> uptimeText, String sourceRead) {
        this(serialNumber, hotfixLevel, platformFamily, contentVersions, uptimeText, sourceRead, null);
    }

    public PlatformFactsRead withPolicyInstall(com.securityexpert.nexus.ui2.worker.inventory.policy.PolicyInstallRead read) {
        return new PlatformFactsRead(serialNumber, hotfixLevel, platformFamily, contentVersions, uptimeText, sourceRead, read);
    }

    public DevicePlatformFacts forDevice(String deviceId) {
        return new DevicePlatformFacts(deviceId, serialNumber, hotfixLevel, platformFamily, contentVersions, uptimeText,
                sourceRead, Optional.empty());
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    /** From the Palo Alto {@code show system info} read: serial, family, content versions, uptime. */
    public static PlatformFactsRead paloAlto(com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoSystemInfoParser.SystemInfo info) {
        return new PlatformFactsRead(nonBlank(info.serial()), Optional.empty(), info.family(), info.contentVersions(),
                info.uptime(), "pan_show_system_info");
    }
}
