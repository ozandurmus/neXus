package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary stand-in for NXS-LOCAL-0159's persistence-backed {@link
 * DeviceInventoryRepository} (WORKER.md risk: "Blocking on 0159 instead of
 * working against the fake first"). Keeps every run ever recorded per
 * device, in insertion order, so {@link #findLatestRun} can return the most
 * recently recorded one without depending on any collector actually having
 * run yet in this movement's own tests.
 */
public final class InMemoryDeviceInventoryRepository implements DeviceInventoryRepository {

    private final Map<String, List<InventoryRun>> runsByDeviceId = new ConcurrentHashMap<>();

    @Override
    public void recordRun(InventoryRun run) {
        runsByDeviceId.computeIfAbsent(run.deviceId(), key -> new ArrayList<>()).add(run);
    }

    @Override
    public Optional<InventoryRun> findLatestRun(String deviceId) {
        List<InventoryRun> runs = runsByDeviceId.get(deviceId);
        if (runs == null || runs.isEmpty()) {
            return Optional.empty();
        }
        return runs.stream().max(Comparator.comparing(InventoryRun::collectedAt));
    }

    @Override
    public List<InventoryRun> findLatestRuns(List<String> deviceIds) {
        return deviceIds.stream()
                .map(this::findLatestRun)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }
}
