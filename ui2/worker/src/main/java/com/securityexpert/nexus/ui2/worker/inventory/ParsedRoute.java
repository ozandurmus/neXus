package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/** One parsed route, before a {@code route_id} is assigned. See {@link ParsedAddress}. */
public record ParsedRoute(String destination, Optional<String> nextHop, Optional<String> interfaceName,
        String protocol, Optional<String> routeTable) {

    public ParsedRoute {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(nextHop, "nextHop");
        Objects.requireNonNull(interfaceName, "interfaceName");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(routeTable, "routeTable");
    }

    public InventoryRoute toInventoryRoute() {
        return new InventoryRoute(UUID.randomUUID().toString(), destination, nextHop, interfaceName, protocol,
                routeTable);
    }
}
