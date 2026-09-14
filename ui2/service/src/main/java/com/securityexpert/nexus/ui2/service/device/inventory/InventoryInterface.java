package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.List;
import java.util.Optional;

/** READ CONTRACT: one collected interface, within one {@link InventoryContext}. See {@link InventoryAddress}. */
public record InventoryInterface(
        String name,
        Optional<String> parent,
        String kind,
        String state,
        List<InventoryAddress> addresses) {

    public static final String KIND_PHYSICAL = "physical";
    public static final String KIND_VLAN = "vlan";
    public static final String KIND_SUBINTERFACE = "subinterface";
    public static final String KIND_LOOPBACK = "loopback";
    public static final String KIND_BOND = "bond";
    public static final String KIND_TUNNEL = "tunnel";
    public static final String KIND_OTHER = "other";

    public static final String STATE_UP = "up";
    public static final String STATE_DOWN = "down";
    public static final String STATE_UNKNOWN = "unknown";
}
