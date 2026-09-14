package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Set;

/**
 * The two {@code inventory_collect} job-kind capabilities (14C D-1, D-2):
 * one per vendor. Unlike {@link ConfirmCapabilityIds}, neither is
 * admissible against a {@code DRAFT} device -- 14C D-1: "admitted by
 * JobAdmissionService only against ENROLLED, non-disabled devices (F4
 * refusal for DRAFT unchanged; 14B EC-J1 stays the sole exception)". This
 * class exists only so a worker's own claim-time re-check and the eligible
 * {@code job_type} set {@link com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop}
 * (and its inventory sibling) claims against share one definition, the
 * same pattern {@link ConfirmCapabilityIds} already establishes.
 */
public final class InventoryCapabilityIds {

    public static final String CP_INVENTORY_COLLECT = "cp_inventory_collect";
    public static final String PAN_INVENTORY_COLLECT = "pan_inventory_collect";

    public static final Set<String> ALL = Set.of(CP_INVENTORY_COLLECT, PAN_INVENTORY_COLLECT);

    private InventoryCapabilityIds() {
    }

    public static boolean isInventoryCapability(String capabilityId) {
        return ALL.contains(capabilityId);
    }
}
