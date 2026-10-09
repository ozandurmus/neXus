package com.securityexpert.nexus.ui2.service.lifecycle;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository;

@Service
public final class LifecycleService {
    private final LifecycleCatalogRepository catalog;
    private final DeviceRepository devices;

    public LifecycleService(LifecycleCatalogRepository catalog, DeviceRepository devices) {
        this.catalog = catalog; this.devices = devices;
    }

    public List<Map<String, Object>> fleet(LocalDate today) {
        var entries = catalog.list();
        var inventory = catalog.storedInventory();
        return devices.listAll().stream().map(d -> LifecycleProjection.device(d, entries, inventory.get(d.deviceId()), today)).toList();
    }

    public Optional<Map<String, Object>> device(String id) {
        var entries = catalog.list();
        var inventory = catalog.storedInventory();
        return devices.listAll().stream().filter(d -> d.deviceId().equals(id)).findFirst()
                .map(d -> LifecycleProjection.device(d, entries, inventory.get(id), LocalDate.now(ZoneOffset.UTC)));
    }

    public static Map<String, Object> summary(List<Map<String, Object>> rows) {
        return Map.of("devices", rows.size(), "past_end_of_support", rows.stream().filter(r -> days(r, Long.MIN_VALUE, -1)).count(),
                "within_180_days", rows.stream().filter(r -> days(r, 0, 180)).count(),
                "within_365_days", rows.stream().filter(r -> days(r, 0, 365)).count(),
                "no_lifecycle_data", rows.stream().filter(r -> "NO_LIFECYCLE_DATA".equals(r.get("status"))).count(),
                "licenses_within_60_days", rows.stream().filter(LifecycleService::licenseSoon).count());
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
