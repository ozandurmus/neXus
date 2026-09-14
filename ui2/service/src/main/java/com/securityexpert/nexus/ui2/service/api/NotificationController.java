package com.securityexpert.nexus.ui2.service.api;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationNotification;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationNotificationRepository;

/**
 * {@code GET /notifications} (14G CG-7d): the minimal notification surface
 * this movement adds -- no functional one existed before it (WORKER.md
 * scope note). Backs the shell badge; the Configuration screen's own
 * per-category "override" marker is served from {@code
 * ConfigurationController}'s {@code overrides} field instead, so this
 * route stays a simple recent-notifications feed.
 */
@RestController
public final class NotificationController {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;
    private static final int DEFAULT_LIMIT = 50;

    private final ConfigurationNotificationRepository notificationRepository;

    public NotificationController(ConfigurationNotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping("/notifications")
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notifications", notificationRepository.listRecent(DEFAULT_LIMIT).stream()
                .map(NotificationController::toBody)
                .toList());
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> toBody(ConfigurationNotification notification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notification_id", notification.notificationId());
        body.put("device_id", notification.deviceId());
        body.put("run_id", notification.runId());
        body.put("kind", notification.kind());
        body.put("summary", notification.summary());
        body.put("override_paths", notification.overridePaths());
        body.put("created_at", TIMESTAMP.format(notification.createdAt()));
        body.put("read_at", notification.readAt().map(TIMESTAMP::format).orElse(null));
        return body;
    }
}
