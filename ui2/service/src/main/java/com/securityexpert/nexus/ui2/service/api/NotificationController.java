package com.securityexpert.nexus.ui2.service.api;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationNotification;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationNotificationRepository;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

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
    private final TopologyNamePseudonymizer names;

    public NotificationController(ConfigurationNotificationRepository notificationRepository,
            TopologyNamePseudonymizer names) {
        this.notificationRepository = notificationRepository;
        this.names = names;
    }

    @GetMapping("/notifications")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notifications", notificationRepository.listRecent(DEFAULT_LIMIT).stream()
                .map(n -> toBody(n, masked, names))
                .toList());
        return ResponseEntity.ok(body);
    }

    /**
     * An override path is {@code category/category/<entry name>}: the entry name is a configuration object, zone or
     * rule name the device owner chose, which the free-text pass of the masking advice only catches when the name was
     * first met on another screen. The replay viewer gets the category (a closed element name) and a keyed pseudonym of
     * the rest, so the same element reads the same everywhere and no name leaves.
     */
    static List<String> overridePaths(List<String> paths, boolean masked, TopologyNamePseudonymizer names) {
        if (!masked) return paths;
        return paths.stream().map(path -> {
            int slash = path.indexOf('/');
            String category = slash > 0 ? path.substring(0, slash) : "";
            if (!category.matches("[A-Za-z0-9_-]{1,40}")) return names.maskPolicyName("config-element", path);
            return category + "/" + names.maskPolicyName("config-element", path.substring(slash + 1));
        }).toList();
    }

    static Map<String, Object> toBody(ConfigurationNotification notification, boolean masked,
            TopologyNamePseudonymizer names) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notification_id", notification.notificationId());
        body.put("device_id", notification.deviceId());
        body.put("run_id", notification.runId());
        body.put("kind", notification.kind());
        body.put("summary", notification.summary());
        body.put("override_paths", overridePaths(notification.overridePaths(), masked, names));
        body.put("created_at", TIMESTAMP.format(notification.createdAt()));
        body.put("read_at", notification.readAt().map(TIMESTAMP::format).orElse(null));
        return body;
    }
}
