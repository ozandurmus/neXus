package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository;
import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.Kind;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.AsaSmartLicensingParser;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.CheckPointLicenseParser;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.CheckPointLivePatchParser;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.FortiGateAutoupdateParser;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.FortiGateContractParser;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.InfobloxGridwideLicenseParser;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.LicenseParse;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.LivePatchParse;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ManagementCenterLicenseParser;
import com.securityexpert.nexus.ui2.worker.inventory.lifecycle.PaloAltoLicenseParser;

/**
 * Parses a lifecycle answer in memory and stores the facts through {@link DeviceLifecycleRepository}
 * (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 3). The raw answer is never kept, logged or passed on. A kind
 * the contract lists as "measured but not parsed" is ignored, and so is a null or blank device id. A storage failure is
 * logged by exception class only and never rethrown past the executor's guard.
 */
public final class PersistingLifecycleResultSink implements LifecycleResultSink {

    private static final System.Logger LOG = System.getLogger(PersistingLifecycleResultSink.class.getName());
    private static final Pattern REASON = Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
    private static final java.util.Set<String> NOT_COLLECTED = java.util.Set.of("REFUSED", "FAILED", "SKIPPED", "EMPTY");

    /** The platform facts key and source read of the Infoblox NIOS version (amendment A-2026-11-10). */
    public static final String NIOS_VERSION_KEY = "nios_version";
    public static final String NIOS_VERSION_SOURCE = "infoblox_upgradestatus_grid";
    /** Source read of the version taken from the member-level entries (V201). */
    public static final String NIOS_VERSION_VNODE_SOURCE = "infoblox_upgradestatus_vnode";

    private final DeviceLifecycleRepository repository;
    private final com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository platformFacts;

    public PersistingLifecycleResultSink(DeviceLifecycleRepository repository) {
        this(repository, null);
    }

    /** {@code platformFacts}: where the Infoblox NIOS version is recorded; null: that read is not stored. */
    public PersistingLifecycleResultSink(DeviceLifecycleRepository repository,
            com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository platformFacts) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.platformFacts = platformFacts;
    }

    @Override
    public void answer(Kind kind, String deviceId, String member, String raw) {
        if (kind == null || deviceId == null || deviceId.isBlank()) {
            return;
        }
        try {
            switch (kind) {
                case CP_CPLIC_PRINT, CP_SPARK_SHOW_LICENSE -> license(kind, deviceId, CheckPointLicenseParser.parse(kind.token(), raw));
                case ASA_SHOW_LICENSE_ALL -> license(kind, deviceId, AsaSmartLicensingParser.parse(raw));
                case MC_DEVICE_LICENSE -> {
                    LicenseParse parsed = ManagementCenterLicenseParser.parse(member, raw);
                    // One answer per managed device lands in one read row: a later device without components must not
                    // overwrite the row of devices that had them.
                    if (member == null || member.isBlank() || "COLLECTED".equals(parsed.outcome().name())) {
                        license(kind, deviceId, parsed);
                    }
                }
                case INFOBLOX_LICENSE_GRIDWIDE -> license(kind, deviceId, InfobloxGridwideLicenseParser.parse(raw));
                case PAN_LICENSE_INFO -> license(kind, deviceId, PaloAltoLicenseParser.parseLicenseInfo(raw));
                case PAN_SUPPORT_INFO -> license(kind, deviceId, PaloAltoLicenseParser.parseSupportInfo(raw));
                case FGT_UPDATE_INFO_CONTRACT -> license(kind, deviceId, FortiGateContractParser.parse(raw));
                case FGT_AUTOUPDATE_VERSIONS -> license(kind, deviceId, FortiGateAutoupdateParser.parse(raw));
                case CP_CPLP_LIST -> {
                    LivePatchParse parsed = CheckPointLivePatchParser.parse(raw);
                    repository.recordLivePatchRead(deviceId, kind.token(), parsed.outcome().name(), parseReason(parsed.outcome().name()),
                            parsed.items());
                }
                case INFOBLOX_UPGRADESTATUS_GRID -> niosVersion(deviceId, raw);
                case INFOBLOX_UPGRADESTATUS_VNODE -> niosVersionVnode(deviceId, raw);
                case MC_SYSTEM_VERSION, MC_SHOW_LICENSES, FMG_FMUPDATE_DBCONTRACT -> {
                    // Measured but not parsed in the contract: their shape line is still logged by the executor.
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[LIFECYCLE_RESULT] storing {0} failed: {1}", kind.token(), e.getClass().getSimpleName());
        }
    }

    @Override
    public void notCollected(Kind kind, String deviceId, String outcome, String reason) {
        if (kind == null || deviceId == null || deviceId.isBlank() || outcome == null || !NOT_COLLECTED.contains(outcome)) {
            return;
        }
        String cleanReason = cleanReason(reason);
        try {
            switch (kind) {
                case CP_CPLIC_PRINT, CP_SPARK_SHOW_LICENSE, ASA_SHOW_LICENSE_ALL, MC_DEVICE_LICENSE, INFOBLOX_LICENSE_GRIDWIDE,
                        PAN_LICENSE_INFO, PAN_SUPPORT_INFO, FGT_UPDATE_INFO_CONTRACT, FGT_AUTOUPDATE_VERSIONS ->
                    repository.recordLicenseRead(deviceId, kind.token(), outcome, cleanReason, List.of());
                case CP_CPLP_LIST -> repository.recordLivePatchRead(deviceId, kind.token(), outcome, cleanReason, List.of());
                case MC_SYSTEM_VERSION, MC_SHOW_LICENSES, FMG_FMUPDATE_DBCONTRACT, INFOBLOX_UPGRADESTATUS_GRID,
                        INFOBLOX_UPGRADESTATUS_VNODE -> {
                    // Not parsed, so not recorded.
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[LIFECYCLE_RESULT] storing {0} failed: {1}", kind.token(), e.getClass().getSimpleName());
        }
    }

    /**
     * The grid's NIOS version, kept as a platform fact of the grid manager's device row. An Infoblox device has no other
     * platform facts writer, so the row holds this one value; an answer without a usable version writes nothing and leaves
     * the last recorded version in place.
     */
    private void niosVersion(String deviceId, String raw) {
        if (platformFacts == null) {
            return;
        }
        com.securityexpert.nexus.ui2.worker.inventory.lifecycle.InfobloxUpgradeStatusParser.currentVersion(raw).ifPresent(version ->
                platformFacts.record(new com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts(deviceId,
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Map.of(NIOS_VERSION_KEY, version), java.util.Optional.empty(), NIOS_VERSION_SOURCE,
                        java.util.Optional.empty())));
    }

    /** The member-level versions: one distinct version as is, several joined with ", "; none writes nothing. */
    private void niosVersionVnode(String deviceId, String raw) {
        if (platformFacts == null) {
            return;
        }
        List<String> versions = com.securityexpert.nexus.ui2.worker.inventory.lifecycle.InfobloxUpgradeStatusParser.currentVersions(raw);
        if (versions.isEmpty()) {
            return;
        }
        platformFacts.record(new com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts(deviceId,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Map.of(NIOS_VERSION_KEY, String.join(", ", versions)), java.util.Optional.empty(),
                NIOS_VERSION_VNODE_SOURCE, java.util.Optional.empty()));
    }

    private void license(Kind kind, String deviceId, LicenseParse parsed) {
        repository.recordLicenseRead(deviceId, kind.token(), parsed.outcome().name(), parseReason(parsed.outcome().name()), parsed.items());
    }

    private static String parseReason(String outcome) {
        return switch (outcome) {
            case "UNPARSED" -> "no_recognised_line";
            case "EMPTY" -> "blank_or_no_items";
            default -> null;
        };
    }

    /** A reason that satisfies the schema check (null stays null; anything else outside the set becomes underscores). */
    static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String s = reason.strip().replaceAll("[^A-Za-z0-9_.:-]", "_");
        s = s.length() > 80 ? s.substring(0, 80) : s;
        return REASON.matcher(s).matches() ? s : null;
    }
}
