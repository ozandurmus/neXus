package com.securityexpert.nexus.ui2.service.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSoftwareHistoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSoftwareHistoryRepository.Stretch;

/**
 * "When was this device upgraded?" from neXus's own observations (V243). Read-only; both routes sit behind
 * {@code device_read} like the Lifecycle view, and the bodies are plain maps so the replay-viewer masking advice
 * pseudonymizes {@code device_id} and {@code hostname} exactly as on every other device response.
 */
@RestController
public final class SoftwareHistoryController {

    static final int DEFAULT_DAYS = 90;
    static final int MAX_DAYS = 3650;
    static final int MAX_ROWS = 1000;

    private final DeviceSoftwareHistoryRepository history;
    private final DeviceRepository devices;

    public SoftwareHistoryController(DeviceSoftwareHistoryRepository history, DeviceRepository devices) {
        this.history = history;
        this.devices = devices;
    }

    @GetMapping("/devices/{deviceId}/software-history")
    public ResponseEntity<Map<String, Object>> device(@PathVariable String deviceId) {
        if (devices.find(deviceId).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Stretch s : history.forDevice(deviceId)) {
            rows.add(row(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", deviceId);
        body.put("stretches", rows);
        // UNKNOWN is the honest answer when neXus has never observed a version for the device.
        body.put("known", !rows.isEmpty());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v2/software-history/changes")
    public Map<String, Object> fleet(@RequestParam(name = "days", required = false) Integer days) {
        int window = days == null ? DEFAULT_DAYS : Math.max(1, Math.min(MAX_DAYS, days));
        Instant since = Instant.now().minus(Duration.ofDays(window));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Stretch s : history.changesSince(since, MAX_ROWS)) {
            Map<String, Object> row = row(s);
            row.put("hostname", s.hostname().orElse(null));
            row.put("from_version", s.previousSoftwareVersion().orElse(null));
            row.put("from_hotfix_level", s.previousHotfixLevel().orElse(null));
            row.put("to_version", s.softwareVersion());
            row.put("to_hotfix_level", s.hotfixLevel().orElse(null));
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", window);
        body.put("since", since.toString());
        body.put("changes", rows);
        return body;
    }

    private static Map<String, Object> row(Stretch s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("device_id", s.deviceId());
        row.put("vendor", s.vendor().orElse(null));
        row.put("software_version", s.softwareVersion());
        row.put("hotfix_level", s.hotfixLevel().orElse(null));
        row.put("first_seen_at", s.firstSeenAt().toString());
        row.put("last_seen_at", s.lastSeenAt().toString());
        row.put("previous_software_version", s.previousSoftwareVersion().orElse(null));
        row.put("previous_hotfix_level", s.previousHotfixLevel().orElse(null));
        // The change happened after window_start (last time the previous version was seen) and by window_end.
        row.put("window_start", s.previousLastSeenAt().map(Instant::toString).orElse(null));
        row.put("window_end", s.isChange() ? s.firstSeenAt().toString() : null);
        row.put("source", s.source());
        return row;
    }
}
