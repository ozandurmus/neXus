package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.List;

/**
 * READ CONTRACT: one collected context ({@code "physical"} or a VSID/vsys
 * id) within one {@link InventoryRun}. See {@link InventoryAddress}.
 */
public record InventoryContext(String context, List<InventoryInterface> interfaces, List<InventoryRoute> routes) {

    public static final String PHYSICAL = "physical";
}
