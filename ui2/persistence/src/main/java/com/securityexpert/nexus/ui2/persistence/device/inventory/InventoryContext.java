package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.util.List;
import java.util.Objects;

/**
 * One collection context within a run -- {@code "physical"} for a
 * standalone gateway/firewall or a ClusterXL member's own physical
 * context, a VSID (Check Point VSX) or a vsys id (Palo Alto) otherwise
 * (14C D-1: "cluster and VSX/vsys are target modifiers on the same job,
 * never separate jobs"; the shared read contract's own {@code contexts[]}
 * shape). Not a {@code device_interface}/{@code device_route} table of its
 * own -- {@link DeviceInventoryRepository} flattens {@link #interfaces()}
 * and {@link #routes()} into those two tables' own {@code context} column
 * on write, and regroups by that column on read, so this record is the one
 * shape shared by the write path, the read path and (unchanged) the shared
 * HTTP read contract's own nesting.
 */
public record InventoryContext(String context, List<InventoryInterface> interfaces, List<InventoryRoute> routes) {

    public static final String PHYSICAL = "physical";

    public InventoryContext {
        Objects.requireNonNull(context, "context");
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
