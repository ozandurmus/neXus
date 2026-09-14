package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.Map;

/**
 * SESSION_START baseline: "0159 defines {@code InventoryCapabilityIds.
 * CP_INVENTORY_COLLECT / PAN_INVENTORY_COLLECT} -- until its commit lands,
 * declare the two id strings as constants in the service and switch to
 * 0159's class after rebase."
 */
public final class InventoryCapabilityIds {

    public static final String CP_INVENTORY_COLLECT = "cp_inventory_collect";
    public static final String PAN_INVENTORY_COLLECT = "pan_inventory_collect";

    private static final Map<String, String> BY_VENDOR = Map.of(
            "check_point", CP_INVENTORY_COLLECT,
            "palo_alto", PAN_INVENTORY_COLLECT);

    private InventoryCapabilityIds() {
    }

    /** {@code null} for a vendor hint outside DA-1/DA-2's closed vocabulary. */
    public static String forVendor(String vendorHint) {
        return BY_VENDOR.get(vendorHint);
    }
}
