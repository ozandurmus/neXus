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

    /**
     * A Palo Alto firewall's run whose {@code type=version} answer was unusable (or never obtained): {@code show system
     * info} is retired, so serial, family, content versions and uptime are not collected. Recorded anyway (source {@code pan_show_system_info_retired}) so the
     * previous run's values are replaced by UNKNOWN, never left standing as current.
     */
    public static PlatformFactsRead paloAltoFirewallNotCollected() {
        return new PlatformFactsRead(Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Optional.empty(),
                com.securityexpert.nexus.ui2.persistence.device.PanSystemInfoRetirement.SOURCE_READ);
    }

    /**
     * A Palo Alto firewall's run since PO 2026-10-21 ({@code PanTypeVersion}): the serial from the documented
     * {@code type=version} request, source {@code pan_type_version}. Family, content versions, uptime and hotfix are not
     * in that response and stay empty (UNKNOWN); model and PAN-OS version travel as the observed identity.
     */
    public static PlatformFactsRead paloAltoFirewallTypeVersion(
            com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoTypeVersionParser.Info info) {
        return new PlatformFactsRead(info.serial(), Optional.empty(), Optional.empty(), Map.of(), Optional.empty(),
                com.securityexpert.nexus.ui2.persistence.device.PanTypeVersion.SOURCE_READ);
    }

    /** True when this read came from the firewall type=version request. */
    public boolean typeVersion() {
        return com.securityexpert.nexus.ui2.persistence.device.PanTypeVersion.SOURCE_READ.equals(sourceRead);
    }

    /** True when this read is the retired-command record: nothing was read, on purpose. */
    public boolean systemInfoRetired() {
        return com.securityexpert.nexus.ui2.persistence.device.PanSystemInfoRetirement.SOURCE_READ.equals(sourceRead);
    }

    /** From the Palo Alto {@code show system info} read (a Panorama only, since 2026-10-21): serial, family, content versions, uptime. */
    public static PlatformFactsRead paloAlto(com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoSystemInfoParser.SystemInfo info) {
        return new PlatformFactsRead(nonBlank(info.serial()), Optional.empty(), info.family(), info.contentVersions(),
                info.uptime(), "pan_show_system_info");
    }
}
