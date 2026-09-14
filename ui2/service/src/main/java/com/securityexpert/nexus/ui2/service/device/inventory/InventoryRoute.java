package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.Optional;

/** READ CONTRACT: one collected route, within one {@link InventoryContext}. See {@link InventoryAddress}. */
public record InventoryRoute(
        String destination,
        Optional<String> nextHop,
        Optional<String> interfaceName,
        String protocol,
        Optional<String> table) {

    public static final String PROTOCOL_STATIC = "static";
    public static final String PROTOCOL_CONNECTED = "connected";
    public static final String PROTOCOL_DEFAULT = "default";
    public static final String PROTOCOL_OSPF = "ospf";
    public static final String PROTOCOL_BGP = "bgp";
    public static final String PROTOCOL_RIP = "rip";
    public static final String PROTOCOL_HOST = "host";
    public static final String PROTOCOL_KERNEL = "kernel";
    public static final String PROTOCOL_UNKNOWN = "unknown";
}
