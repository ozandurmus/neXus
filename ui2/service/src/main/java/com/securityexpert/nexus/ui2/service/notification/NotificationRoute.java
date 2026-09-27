package com.securityexpert.nexus.ui2.service.notification;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NotificationRoute(String type, boolean enabled, String recipients,
        @JsonProperty("last_sent_at") Instant lastSentAt,
        @JsonProperty("last_error") String lastError) {

    public static final List<String> TYPES = List.of("admin_event", "login_security", "backup_failure",
            "job_failure", "config_change", "compliance_regression", "device_health");

    public List<String> effectiveRecipients(NotificationSettings settings) {
        return recipients == null || recipients.isBlank() ? settings.recipients() : split(recipients);
    }

    static List<String> split(String value) {
        return value == null ? List.of() : Arrays.stream(value.split("[,;\\s]+"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
