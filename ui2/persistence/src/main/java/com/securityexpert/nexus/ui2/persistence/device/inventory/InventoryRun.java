package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One completed {@code inventory_collect} job's own {@code
 * device_inventory_run} row (14C D-4) plus every {@link InventoryContext}
 * (physical, and each VSID/vsys) it produced. {@link
 * DeviceInventoryRepository#recordRun} writes the run row and every child
 * row (interfaces, addresses, routes) in one transaction -- failure leaves
 * no partial run (14C §5: the parsers/storage/plumbing are built and
 * proven against fixtures before any live run).
 */
public record InventoryRun(String runId, String deviceId, String jobId, Instant collectedAt, int contextCount,
        List<InventoryContext> contexts) {

    public InventoryRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(collectedAt, "collectedAt");
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
    }
}
