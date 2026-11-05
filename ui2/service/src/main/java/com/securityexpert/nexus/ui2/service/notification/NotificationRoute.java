package com.securityexpert.nexus.ui2.service.notification;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NotificationRoute(String type, boolean enabled, String recipients,
        @JsonProperty("last_sent_at") Instant lastSentAt,
        @JsonProperty("last_error") String lastError) {

    /** The one source of route types and their digest titles; a new type is added here only. */
    static final Map<String, String> TITLES = titles();

    public static final List<String> TYPES = List.copyOf(TITLES.keySet());

    private static Map<String, String> titles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("admin_event", "Administration changes");
        m.put("login_security", "Sign-in and access");
        m.put("backup_failure", "Backup failures");
        m.put("job_failure", "Other job failures");
        m.put("config_change", "Configuration changes");
        m.put("compliance_regression", "Compliance regressions");
        m.put("device_health", "Device reachability");
        m.put("security_scan", "Security scans");
        m.put("device_crash_file", "Device crash and dump files");
        m.put("backup_size_anomaly", "Backup size deviations");
        return java.util.Collections.unmodifiableMap(m);
    }

    public List<String> effectiveRecipients(NotificationSettings settings) {
        return recipients == null || recipients.isBlank() ? settings.recipients() : split(recipients);
    }

    static List<String> split(String value) {
        return value == null ? List.of() : Arrays.stream(value.split("[,;\\s]+"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
