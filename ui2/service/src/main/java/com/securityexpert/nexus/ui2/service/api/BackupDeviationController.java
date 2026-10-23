package com.securityexpert.nexus.ui2.service.api;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.device.configuration.BackupDeviationService;
import com.securityexpert.nexus.ui2.service.device.configuration.BackupDeviationService.DeviceClass;
import com.securityexpert.nexus.ui2.service.device.configuration.MajorDeviationRules;

/**
 * {@code GET /api/v2/backups/deviations} (gate {@code device_backup_read}, unchanged): the Backups screen's "Active
 * major deviations" from stored configuration runs (docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md). Each row's
 * {@code hostname} sits next to its {@code device_id} and {@code cluster_member_ref}, so the replay viewer gets the
 * Devices screen's own pseudonym through {@code PrivacyMaskingResponseBodyAdvice}; no context name (a vsys or VDOM
 * name) and no configuration value is in the body.
 */
@RestController
public final class BackupDeviationController {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    private final BackupDeviationService service;

    public BackupDeviationController(BackupDeviationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping("/api/v2/backups/deviations")
    public ResponseEntity<Map<String, Object>> listDeviations() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toBody(service.evaluate()));
    }

    public static Map<String, Object> toBody(BackupDeviationService.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("window_days", result.window().toDays());
        body.put("evaluated_at", TIMESTAMP.format(result.evaluatedAt()));
        // The card's number: devices with at least one valid comparison in the window (not a run count).
        body.put("total_deviations_checked", result.compared().size());
        body.put("devices_known", result.devicesKnown());
        Map<String, Object> notCompared = new LinkedHashMap<>();
        for (BackupDeviationService.NotComparedReason reason : BackupDeviationService.NotComparedReason.values()) {
            notCompared.put(reason.wire(), result.notCompared().getOrDefault(reason, 0));
        }
        body.put("not_compared", notCompared);
        body.put("major_devices", result.count(DeviceClass.MAJOR));
        body.put("minor_devices", result.count(DeviceClass.MINOR));
        body.put("unchanged_devices", result.count(DeviceClass.NONE));
        body.put("active_major_deviations", result.activeMajor().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("device_id", row.deviceId());
            item.put("hostname", row.hostname().orElse(null));
            item.put("cluster_member_ref", row.clusterMemberRef().orElse(null));
            item.put("vendor", row.vendor());
            item.put("category", row.category().wire());
            item.put("changed_at", TIMESTAMP.format(row.changedAt()));
            item.put("count", row.count());
            item.put("sections", List.copyOf(row.sections()));
            return item;
        }).toList());
        body.put("devices", result.compared().stream().map(device -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("device_id", device.deviceId());
            item.put("vendor", device.vendor());
            item.put("deviation", device.deviceClass().name());
            item.put("last_compared_at", TIMESTAMP.format(device.lastComparedAt()));
            item.put("categories", device.categories().stream().map(MajorDeviationRules.Category::wire).toList());
            return item;
        }).toList());
        body.put("coverage", new LinkedHashMap<>(new java.util.TreeMap<>(MajorDeviationRules.COVERAGE)));
        return body;
    }
}
