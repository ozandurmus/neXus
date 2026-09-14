package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** One {@code configuration_notification} row (14G CG-7d): one per device per run that carried a local override. */
public record ConfigurationNotification(String notificationId, String deviceId, String runId, String kind,
        String summary, List<String> overridePaths, Instant createdAt, Optional<Instant> readAt) {

    public static final String KIND_CONFIGURATION_OVERRIDE_DETECTED = "configuration_override_detected";

    public ConfigurationNotification {
        overridePaths = overridePaths == null ? List.of() : List.copyOf(overridePaths);
        readAt = readAt == null ? Optional.empty() : readAt;
    }
}
