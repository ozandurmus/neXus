package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Set;

/**
 * PO_DECISION_RECORD_2026_09_14B (FROZEN) EC-J1: "exactly one job kind is
 * admissible against a {@code DRAFT} device: the enrollment confirm."
 * {@link JobAdmissionService} consults this closed set to grant the one
 * narrow exception EC-J1 authorizes; every other capability id keeps F4's
 * unconditional {@code DRAFT} refusal (EC-J2), unchanged. Declared here,
 * in {@code job-engine}, so both the admission check and a worker's own
 * claim-time re-check (mirroring the same rule) share one definition
 * rather than each re-deriving it.
 */
public final class ConfirmCapabilityIds {

    public static final String DEVICE_CONFIRM_CHECK_POINT = "device_confirm_check_point";
    public static final String DEVICE_CONFIRM_PALO_ALTO = "device_confirm_palo_alto";

    /** Vendors reached over HTTPS (V64): Infoblox, Radware -- routed by the device's vendor. */
    public static final String DEVICE_CONFIRM_HTTPS = "device_confirm_https";

    /** Cisco ASA over SSH (V78, CISCO_ASA_CONTRACT.md): show version on an interactive shell. */
    public static final String DEVICE_CONFIRM_CISCO_ASA = "device_confirm_cisco_asa";

    public static final Set<String> ALL = Set.of(DEVICE_CONFIRM_CHECK_POINT, DEVICE_CONFIRM_PALO_ALTO, DEVICE_CONFIRM_HTTPS,
            DEVICE_CONFIRM_CISCO_ASA);

    private ConfirmCapabilityIds() {
    }

    public static boolean isConfirmCapability(String capabilityId) {
        return ALL.contains(capabilityId);
    }
}
