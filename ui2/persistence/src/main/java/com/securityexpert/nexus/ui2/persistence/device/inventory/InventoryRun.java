package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import java.util.Optional;

/**
 * One completed {@code inventory_collect} job's own {@code
 * device_inventory_run} row (14C D-4) plus every {@link InventoryContext}
 * (physical, and each VSID/vsys) it produced, and every {@link
 * InventoryHaFact} the run recorded (migration V17). {@link
 * DeviceInventoryRepository#recordRun} writes the run row and every child
 * row (interfaces, addresses, routes, HA facts) in one transaction --
 * failure leaves no partial run (14C §5: the parsers/storage/plumbing are
 * built and proven against fixtures before any live run).
 */
public record InventoryRun(String runId, String deviceId, String jobId, Instant collectedAt, int contextCount,
        List<InventoryContext> contexts, List<InventoryHaFact> haFacts, Optional<String> virtualSystems,
        List<GridMember> gridMembers) {

    public InventoryRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(collectedAt, "collectedAt");
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        haFacts = haFacts == null ? List.of() : List.copyOf(haFacts);
        virtualSystems = virtualSystems == null ? Optional.empty() : virtualSystems;
        gridMembers = gridMembers == null ? List.of() : List.copyOf(gridMembers);
    }

    /** Pre-V72 shape: no grid members (every firewall run). */
    public InventoryRun(String runId, String deviceId, String jobId, Instant collectedAt, int contextCount,
            List<InventoryContext> contexts, List<InventoryHaFact> haFacts, Optional<String> virtualSystems) {
        this(runId, deviceId, jobId, collectedAt, contextCount, contexts, haFacts, virtualSystems, List.of());
    }

    public InventoryRun(String runId, String deviceId, String jobId, Instant collectedAt, int contextCount,
            List<InventoryContext> contexts, List<InventoryHaFact> haFacts) {
        this(runId, deviceId, jobId, collectedAt, contextCount, contexts, haFacts, Optional.empty());
    }

    /** Pre-V17 shape, kept so every existing caller that never mentions HA facts keeps compiling unchanged. */
    public InventoryRun(String runId, String deviceId, String jobId, Instant collectedAt, int contextCount,
            List<InventoryContext> contexts) {
        this(runId, deviceId, jobId, collectedAt, contextCount, contexts, List.of(), Optional.empty());
    }
}

