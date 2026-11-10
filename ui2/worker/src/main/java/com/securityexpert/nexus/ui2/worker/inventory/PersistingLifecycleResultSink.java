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

    private final DeviceLifecycleRepository repository;

    public PersistingLifecycleResultSink(DeviceLifecycleRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
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
                case MC_DEVICE_LICENSE -> license(kind, deviceId, ManagementCenterLicenseParser.parse(member, raw));
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
                case MC_SYSTEM_VERSION, MC_SHOW_LICENSES, FMG_FMUPDATE_DBCONTRACT, INFOBLOX_UPGRADESTATUS_GRID -> {
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
                case MC_SYSTEM_VERSION, MC_SHOW_LICENSES, FMG_FMUPDATE_DBCONTRACT, INFOBLOX_UPGRADESTATUS_GRID -> {
                    // Not parsed, so not recorded.
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[LIFECYCLE_RESULT] storing {0} failed: {1}", kind.token(), e.getClass().getSimpleName());
        }
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
