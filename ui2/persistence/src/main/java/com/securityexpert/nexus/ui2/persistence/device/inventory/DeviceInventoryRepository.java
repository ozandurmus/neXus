package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.util.List;
import java.util.Optional;

/**
 * {@code device_inventory_run}/{@code device_interface}/{@code
 * device_interface_address}/{@code device_route} persistence (migration
 * V13, 14C D-4). The shared read contract NXS-LOCAL-0159 and NXS-LOCAL-0160
 * both build to names this interface's shape and its three methods
 * exactly; changing either without a RELAY_QUESTION breaks 0160's build.
 */
public interface DeviceInventoryRepository {

    /**
     * Writes {@code run} and every child row (per-context interfaces,
     * their addresses, and routes) in one transaction. Never called with a
     * partially-assembled run -- the caller (worker.inventory's executor)
     * only calls this once the full per-context read/parse pass for the
     * job has succeeded. {@code actorFingerprint}/{@code actionId} carry
     * the audit context every mutation against an audited table requires
     * (AGENTS.md F3; this movement's tables are audited exactly like
     * {@code job_step_attempt}/{@code gate_registry}) -- not part of the
     * shared read contract, which only fixes the record shapes and the two
     * read methods below.
     */
    void recordRun(InventoryRun run, String actorFingerprint, String actionId);

    /** The newest run for one device, fully reassembled with its contexts, or empty if none was ever recorded. */
    Optional<InventoryRun> findLatestRun(String deviceId);

    /** The newest run per device, for every device id in {@code deviceIds} that has at least one recorded run. */
    List<InventoryRun> findLatestRuns(List<String> deviceIds);
}
