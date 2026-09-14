package com.securityexpert.nexus.ui2.service.device.inventory;

import java.time.Instant;
import java.util.List;

/**
 * READ CONTRACT: one device's collected inventory run (migration V13's
 * {@code device_inventory_run} plus its child rows, flattened). See
 * {@link InventoryAddress}.
 */
public record InventoryRun(String runId, String deviceId, String jobId, Instant collectedAt,
        List<InventoryContext> contexts) {
}
