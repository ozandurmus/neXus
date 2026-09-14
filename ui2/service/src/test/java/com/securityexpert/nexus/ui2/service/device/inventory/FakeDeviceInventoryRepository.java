package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;

/** Test-only in-memory {@link DeviceInventoryRepository} fake; the real bean is {@code JooqDeviceInventoryRepository}. */
public final class FakeDeviceInventoryRepository implements DeviceInventoryRepository {

    private final Map<String, List<InventoryRun>> runsByDeviceId = new ConcurrentHashMap<>();

    @Override
    public void recordRun(InventoryRun run, String actorFingerprint, String actionId) {
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
