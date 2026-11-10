package com.securityexpert.nexus.ui2.service.lifecycle;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository;

@Service
public final class LifecycleService {
    private final LifecycleCatalogRepository catalog;
    private final DeviceRepository devices;
    private final DeviceLifecycleRepository stored;
    private final DevicePlatformFactsRepository facts;

    public LifecycleService(LifecycleCatalogRepository catalog, DeviceRepository devices,
            DeviceLifecycleRepository stored, DevicePlatformFactsRepository facts) {
        this.catalog = catalog; this.devices = devices; this.stored = stored; this.facts = facts;
    }

    /** The three stored maps and the platform facts are loaded once per request, never per device. */
    private record Loaded(Map<String, List<DeviceLifecycleRepository.Read>> reads,
            Map<String, List<DeviceLifecycleRepository.LicenseItem>> items,
            Map<String, List<DeviceLifecycleRepository.LivePatchItem>> patches,
            Map<String, com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts> facts) {
        LifecycleProjection.Stored of(String id) {
            return new LifecycleProjection.Stored(reads.get(id), items.get(id), patches.get(id), facts.get(id));
        }
    }

    private Loaded load() { return new Loaded(stored.readsByDevice(), stored.licenseItemsByDevice(), stored.livePatchByDevice(), facts.findAll()); }

    public List<Map<String, Object>> fleet(LocalDate today) {
        var entries = catalog.list();
        var inventory = catalog.storedInventory();
        var loaded = load();
        return devices.listAll().stream().map(d -> LifecycleProjection.device(d, entries, inventory.get(d.deviceId()), today, loaded.of(d.deviceId()))).toList();
    }

    public Optional<Map<String, Object>> device(String id) {
        var entries = catalog.list();
        var inventory = catalog.storedInventory();
        var loaded = load();
        return devices.listAll().stream().filter(d -> d.deviceId().equals(id)).findFirst()
                .map(d -> LifecycleProjection.device(d, entries, inventory.get(id), LocalDate.now(ZoneOffset.UTC), loaded.of(id)));
    }

    public static Map<String, Object> summary(List<Map<String, Object>> rows) {
        return Map.ofEntries(Map.entry("devices", rows.size()), Map.entry("past_end_of_support", rows.stream().filter(r -> days(r, Long.MIN_VALUE, -1)).count()),
                Map.entry("within_180_days", rows.stream().filter(r -> days(r, 0, 180)).count()),
                Map.entry("within_365_days", rows.stream().filter(r -> days(r, 0, 365)).count()),
                Map.entry("no_lifecycle_data", rows.stream().filter(r -> "NO_LIFECYCLE_DATA".equals(r.get("status"))).count()),
                Map.entry("licenses_within_60_days", rows.stream().filter(LifecycleService::licenseSoon).count()),
                Map.entry("license_problems", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("license_problem"))).count()),
                Map.entry("licenses_collected", rows.stream().filter(r -> "COLLECTED".equals(r.get("license_status"))).count()),
                Map.entry("live_patch_not_active", rows.stream().filter(LifecycleService::patchNotActive).count()));
    }

    private static boolean patchNotActive(Map<String, Object> row) {
        return row.get("live_patch") instanceof Map<?, ?> patch && patch.get("ready") instanceof Long ready && ready > 0;
    }
    private static boolean days(Map<String, Object> row, long min, long max) {
        return row.get("support_days") instanceof Long days && days >= min && days <= max;
    }

    private static boolean licenseSoon(Map<String, Object> row) {
        if (!(row.get("licenses") instanceof List<?> licenses)) return false;
        return licenses.stream().anyMatch(l -> l instanceof Map<?, ?> license && license.get("days_remaining") instanceof Long days
                && days >= 0 && days <= 60);
    }
}
