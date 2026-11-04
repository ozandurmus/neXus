package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/**
 * One parsed route, before a {@code route_id} is assigned. See {@link ParsedAddress}. The V184 facts ({@code active},
 * {@code ecmp}, {@code metric}, {@code nextHopKind}) are empty unless the parser established them from the read's own
 * structure -- empty is UNKNOWN.
 */
public record ParsedRoute(String destination, Optional<String> nextHop, Optional<String> interfaceName,
        String protocol, Optional<String> routeTable, Optional<Boolean> active, Optional<Boolean> ecmp,
        Optional<Long> metric, Optional<String> nextHopKind) {

    public ParsedRoute {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(nextHop, "nextHop");
        Objects.requireNonNull(interfaceName, "interfaceName");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(routeTable, "routeTable");
        active = active == null ? Optional.empty() : active;
        ecmp = ecmp == null ? Optional.empty() : ecmp;
        metric = metric == null ? Optional.empty() : metric;
        nextHopKind = nextHopKind == null ? Optional.empty() : nextHopKind;
    }

    /** No V184 fact established. */
    public ParsedRoute(String destination, Optional<String> nextHop, Optional<String> interfaceName,
            String protocol, Optional<String> routeTable) {
        this(destination, nextHop, interfaceName, protocol, routeTable, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    public InventoryRoute toInventoryRoute() {
        return new InventoryRoute(UUID.randomUUID().toString(), destination, nextHop, interfaceName, protocol,
                routeTable, active, ecmp, metric, nextHopKind);
    }
}
