package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.util.Objects;
import java.util.Optional;

/**
 * One {@code device_route} row (14C D-4). {@code protocol} preserves the
 * vendor's own token (Check Point {@code proto} field; Palo Alto {@code
 * flags} letter) rather than collapsing every dynamic protocol into a
 * single "dynamic" bucket (14C §4's named correction) -- {@code "unknown"}
 * only when the token itself cannot be classified into the closed
 * vocabulary below, never as a default for an unmeasured shape.
 */
public record InventoryRoute(String routeId, String destination, Optional<String> nextHop,
        Optional<String> interfaceName, String protocol, Optional<String> routeTable) {

    public static final String PROTOCOL_STATIC = "static";
    public static final String PROTOCOL_CONNECTED = "connected";
    public static final String PROTOCOL_DEFAULT = "default";
    public static final String PROTOCOL_OSPF = "ospf";
    public static final String PROTOCOL_BGP = "bgp";
    public static final String PROTOCOL_RIP = "rip";
    public static final String PROTOCOL_HOST = "host";
    public static final String PROTOCOL_KERNEL = "kernel";
    public static final String PROTOCOL_UNKNOWN = "unknown";

    public InventoryRoute {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(nextHop, "nextHop");
        Objects.requireNonNull(interfaceName, "interfaceName");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(routeTable, "routeTable");
    }
}
