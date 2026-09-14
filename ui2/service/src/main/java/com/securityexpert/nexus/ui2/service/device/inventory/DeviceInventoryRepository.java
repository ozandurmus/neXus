package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.List;
import java.util.Optional;

/**
 * READ CONTRACT shared by NXS-LOCAL-0159 and NXS-LOCAL-0160: {@code
 * recordRun} writes one run with all children in one transaction;
 * {@code findLatestRun}/{@code findLatestRuns} read the most recent run per
 * device. NXS-LOCAL-0159 owns the real, persistence-backed implementation
 * (migration V13); {@link InMemoryDeviceInventoryRepository} is this
 * movement's own stand-in, used until that lane's first commit lands
 * (WORKER.md: "work against your own in-memory fake... shaped exactly as
 * the contract; then rebase or cherry-pick 0159's persistence commit and
 * drop the fake from main code, keep it in tests").
 */
public interface DeviceInventoryRepository {

    void recordRun(InventoryRun run);

    Optional<InventoryRun> findLatestRun(String deviceId);

    List<InventoryRun> findLatestRuns(List<String> deviceIds);
}
