package com.securityexpert.nexus.ui2.service.lifecycle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryEstate;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryReadService;

@Service
public final class LifecycleService {
    private static final Logger LOG = LoggerFactory.getLogger(LifecycleService.class);

    private final LifecycleCatalogRepository catalog;
    private final DeviceRepository devices;
    private final DeviceLifecycleRepository stored;
    private final DevicePlatformFactsRepository facts;
    private final AdvisoryReadService advisories;

    /** Without the advisory read service the payload carries no {@code vulnerabilities} object (the pre-V203 shape). */
    public LifecycleService(LifecycleCatalogRepository catalog, DeviceRepository devices,
            DeviceLifecycleRepository stored, DevicePlatformFactsRepository facts) {
        this(catalog, devices, stored, facts, null);
    }

    @Autowired
    public LifecycleService(LifecycleCatalogRepository catalog, DeviceRepository devices,
            DeviceLifecycleRepository stored, DevicePlatformFactsRepository facts, AdvisoryReadService advisories) {
        this.catalog = catalog; this.devices = devices; this.stored = stored; this.facts = facts; this.advisories = advisories;
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
        var all = devices.listAll();
        var rows = all.stream().map(d -> LifecycleProjection.device(d, entries, inventory.get(d.deviceId()), today, loaded.of(d.deviceId()))).toList();
        addVulnerabilities(all, rows, inventory);
        return rows;
    }

    /**
     * Adds the {@code vulnerabilities} object of each device (ADVISORY_MATCHING_CONTRACT.md section 8). The advisory data is
     * loaded once for the whole fleet. Model and version are the stored inventory values the rest of this payload shows.
     */
    private void addVulnerabilities(List<com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord> all,
            List<Map<String, Object>> rows, Map<String, LifecycleCatalogRepository.StoredInventory> inventory) {
        if (advisories == null) return;
        try {
            var facts = all.stream().map(d -> {
                var inv = inventory.get(d.deviceId());
                return new AdvisoryEstate.DeviceFacts(d.deviceId(), d.vendorHint(), d.role(), inv == null ? null : inv.model(),
                        inv == null ? null : inv.version(), d.observedHostname().orElse(null));
            }).toList();
            var byDevice = advisories.vulnerabilities(facts, Instant.now());
            for (var row : rows) row.put("vulnerabilities", byDevice.get((String) row.get("device_id")));
        } catch (RuntimeException e) {
            // The advisory store is an addition to this view: a failure there must not take the Lifecycle view down.
            LOG.error("lifecycle vulnerabilities code=ADVISORY_READ_FAILED type={}", e.getClass().getSimpleName());
        }
    }

    public Optional<Map<String, Object>> device(String id) {
        var entries = catalog.list();
        var inventory = catalog.storedInventory();
        var loaded = load();
        return devices.listAll().stream().filter(d -> d.deviceId().equals(id)).findFirst()
                .map(d -> {
                    var row = LifecycleProjection.device(d, entries, inventory.get(id), LocalDate.now(ZoneOffset.UTC), loaded.of(id));
                    addVulnerabilities(List.of(d), List.of(row), inventory);
                    return row;
                });
    }

    public static Map<String, Object> summary(List<Map<String, Object>> rows) {
        var summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("devices", rows.size());
        summary.put("past_end_of_support", rows.stream().filter(r -> days(r, Long.MIN_VALUE, -1)).count());
        summary.put("within_180_days", rows.stream().filter(r -> days(r, 0, 180)).count());
        summary.put("within_365_days", rows.stream().filter(r -> days(r, 0, 365)).count());
        summary.put("no_lifecycle_data", rows.stream().filter(r -> "NO_LIFECYCLE_DATA".equals(r.get("status"))).count());
        summary.put("licenses_within_60_days", rows.stream().filter(LifecycleService::licenseSoon).count());
        summary.put("license_problems", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("license_problem"))).count());
        summary.put("licenses_collected", rows.stream().filter(r -> "COLLECTED".equals(r.get("license_status"))).count());
        summary.put("live_patch_not_active", rows.stream().filter(LifecycleService::patchNotActive).count());
        summary.put("live_patch_missing", rows.stream().filter(r -> r.get("live_patch") instanceof Map<?, ?> patch
                && Boolean.TRUE.equals(patch.get("missing"))).count());
        // Advisory matching (ADVISORY_MATCHING_CONTRACT.md section 8). The oldest source age is the largest age among the
        // sources the estate's devices were compared against; null when no source has ever loaded.
        summary.put("vuln_devices_exploited", rows.stream().filter(r -> vulnerabilities(r, "affected_exploited") > 0).count());
        summary.put("vuln_devices_affected", rows.stream().filter(r -> vulnerabilities(r, "affected") > 0).count());
        summary.put("vuln_devices_not_evaluated", rows.stream().filter(r -> r.get("vulnerabilities") instanceof Map<?, ?> v
                && "NOT_EVALUATED".equals(v.get("state"))).count());
        summary.put("vuln_oldest_source_age_days", oldestSourceAge(rows));
        return summary;
    }

    private static long vulnerabilities(Map<String, Object> row, String key) {
        return row.get("vulnerabilities") instanceof Map<?, ?> v && v.get(key) instanceof Integer n ? n : 0;
    }

    private static Object oldestSourceAge(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("vulnerabilities")).filter(v -> v instanceof Map<?, ?>)
                .map(v -> ((Map<?, ?>) v).get("compared")).filter(c -> c instanceof Map<?, ?>)
                .map(c -> ((Map<?, ?>) c).get("age_days")).filter(a -> a instanceof Long).map(a -> (Long) a)
                .max(Long::compare).orElse(null);
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
