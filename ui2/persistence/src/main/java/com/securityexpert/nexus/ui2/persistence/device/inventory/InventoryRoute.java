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
 *
 * <p>V184 (path analysis A1, PO record 2026-11-03 D-1): {@code active}, {@code ecmp}, {@code metric} and {@code
 * nextHopKind} are empty when the read did not establish them -- empty is UNKNOWN, never "false", "0" or a default
 * kind.</p>
 */
public record InventoryRoute(String routeId, String destination, Optional<String> nextHop,
        Optional<String> interfaceName, String protocol, Optional<String> routeTable, Optional<Boolean> active,
        Optional<Boolean> ecmp, Optional<Long> metric, Optional<String> nextHopKind) {

    public static final String PROTOCOL_STATIC = "static";
    public static final String PROTOCOL_CONNECTED = "connected";
    public static final String PROTOCOL_DEFAULT = "default";
    public static final String PROTOCOL_OSPF = "ospf";
    public static final String PROTOCOL_BGP = "bgp";
    public static final String PROTOCOL_RIP = "rip";
    public static final String PROTOCOL_HOST = "host";
    public static final String PROTOCOL_KERNEL = "kernel";
    public static final String PROTOCOL_UNKNOWN = "unknown";

    /** V184 {@code next_hop_kind}: forwarded to an address. */
    public static final String NEXT_HOP_IP = "IP";
    /** V184 {@code next_hop_kind}: the destination is on the attached link. */
    public static final String NEXT_HOP_CONNECTED = "CONNECTED";
    /** V184 {@code next_hop_kind}: the route drops matching traffic (Check Point {@code blackhole}). */
    public static final String NEXT_HOP_DISCARD = "DISCARD";
    /** V184 {@code next_hop_kind}: handed to another virtual router. */
    public static final String NEXT_HOP_NEXT_VR = "NEXT_VR";
    public static final java.util.Set<String> NEXT_HOP_KINDS =
            java.util.Set.of(NEXT_HOP_IP, NEXT_HOP_CONNECTED, NEXT_HOP_DISCARD, NEXT_HOP_NEXT_VR);

    public InventoryRoute {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(nextHop, "nextHop");
        Objects.requireNonNull(interfaceName, "interfaceName");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(routeTable, "routeTable");
        active = active == null ? Optional.empty() : active;
        ecmp = ecmp == null ? Optional.empty() : ecmp;
        metric = metric == null ? Optional.empty() : metric.filter(m -> m >= 0 && m <= 4_294_967_295L);
        nextHopKind = nextHopKind == null ? Optional.empty() : nextHopKind.filter(NEXT_HOP_KINDS::contains);
    }

    /** Pre-V184 shape: active, ECMP, metric and next-hop kind not established (UNKNOWN). */
    public InventoryRoute(String routeId, String destination, Optional<String> nextHop,
            Optional<String> interfaceName, String protocol, Optional<String> routeTable) {
        this(routeId, destination, nextHop, interfaceName, protocol, routeTable, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }
}
