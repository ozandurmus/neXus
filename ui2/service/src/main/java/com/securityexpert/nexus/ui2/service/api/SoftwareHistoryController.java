package com.securityexpert.nexus.ui2.service.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Event;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSoftwareHistoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSoftwareHistoryRepository.Stretch;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

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
    private static final Pattern GENERIC_CP_INSTALL_FILE = Pattern.compile(
            "^install_fw1_R\\d+(?:\\.\\d+)*\\.elg$"
                    + "|^install_SecurePlatform_HOTFIX_R\\d+(?:_\\d+)*_JUMBO_HF_MAIN(?:_\\d+)?\\.log$");

    private final DeviceSoftwareHistoryRepository history;
    private final DeviceRepository devices;
    private final DeviceInstallEventRepository installEvents;
    private final ObjectProvider<TopologyNamePseudonymizer> names;

    public SoftwareHistoryController(DeviceSoftwareHistoryRepository history, DeviceRepository devices,
            DeviceInstallEventRepository installEvents) {
        this(history, devices, installEvents, null);
    }

    @Autowired
    public SoftwareHistoryController(DeviceSoftwareHistoryRepository history, DeviceRepository devices,
            DeviceInstallEventRepository installEvents, ObjectProvider<TopologyNamePseudonymizer> names) {
        this.history = history;
        this.devices = devices;
        this.installEvents = installEvents;
        this.names = names;
    }

    public ResponseEntity<Map<String, Object>> device(String deviceId) {
        return device(deviceId, null);
    }

    @GetMapping("/devices/{deviceId}/software-history")
    public ResponseEntity<Map<String, Object>> device(@PathVariable String deviceId, HttpServletRequest request) {
        if (devices.find(deviceId).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
        }
        boolean masked = request != null && PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Stretch s : history.forDevice(deviceId)) {
            rows.add(row(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", deviceId);
        body.put("stretches", rows);
        // UNKNOWN is the honest answer when neXus has never observed a version for the device.
        body.put("known", !rows.isEmpty());
        // V246: Check Point base/Jumbo install dates from the device's own install log, newest first.
        List<Map<String, Object>> events = new ArrayList<>();
        for (Event e : installEvents.forDevice(deviceId)) {
            events.add(eventRow(e, masked));
        }
        body.put("install_events", events);
        return ResponseEntity.ok(body);
    }

    public Map<String, Object> fleet(Integer days) {
        return fleet(days, null);
    }

    @GetMapping("/api/v2/software-history/changes")
    public Map<String, Object> fleet(@RequestParam(name = "days", required = false) Integer days,
            HttpServletRequest request) {
        boolean masked = request != null && PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
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
        // V246: install-log events; the device prints its own clock, so the cutoff is compared with that clock as a plain
        // local time (UTC "now" minus the window; a few hours of device clock offset is immaterial over days).
        List<Map<String, Object>> events = new ArrayList<>();
        for (Event e : installEvents.installedSince(LocalDateTime.now(ZoneOffset.UTC).minusDays(window), MAX_ROWS)) {
            Map<String, Object> row = eventRow(e, masked);
            row.put("device_id", e.deviceId());
            row.put("hostname", e.hostname().orElse(null));
            events.add(row);
        }
        body.put("install_events", events);
        return body;
    }

    private Map<String, Object> eventRow(Event e, boolean masked) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", e.kind().name());
        row.put("product_version", e.productVersion());
        row.put("jumbo_take", e.jumboTake().orElse(null));
        // The device's own clock, no offset.
        row.put("installed_at_local", e.installedAtLocal().toString());
        row.put("file_name", maskInstallFileName(e.fileName(), masked));
        row.put("first_observed_at", e.firstObservedAt().toString());
        return row;
    }

    private String maskInstallFileName(String fileName, boolean masked) {
        if (!masked || fileName == null || fileName.isBlank() || GENERIC_CP_INSTALL_FILE.matcher(fileName).matches()) {
            return fileName;
        }
        TopologyNamePseudonymizer pseudonymizer = names == null ? null : names.getIfAvailable();
        return pseudonymizer == null ? "install_log_masked" : pseudonymizer.maskPolicyName("install_log", fileName);
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
