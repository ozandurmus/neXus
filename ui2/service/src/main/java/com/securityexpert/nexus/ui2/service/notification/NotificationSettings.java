package com.securityexpert.nexus.ui2.service.notification;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** V51 {@code notification_settings}: syslog target, SMTP relay, and which triggers are on. */
public record NotificationSettings(
        @JsonProperty("syslog_enabled") boolean syslogEnabled,
        @JsonProperty("syslog_host") String syslogHost,
        @JsonProperty("syslog_port") int syslogPort,
        @JsonProperty("syslog_protocol") String syslogProtocol,
        @JsonProperty("syslog_facility") int syslogFacility,
        @JsonProperty("smtp_enabled") boolean smtpEnabled,
        @JsonProperty("smtp_host") String smtpHost,
        @JsonProperty("smtp_port") int smtpPort,
        @JsonProperty("smtp_starttls") boolean smtpStarttls,
        @JsonProperty("smtp_from") String smtpFrom,
        @JsonProperty("smtp_to") String smtpTo,
        @JsonProperty("forward_audit_to_syslog") boolean forwardAuditToSyslog,
        @JsonProperty("notify_job_failure") boolean notifyJobFailure,
        @JsonProperty("updated_at") Instant updatedAt,
        List<NotificationRoute> routes,
        @JsonProperty("notification_identity") String notificationIdentity) {

    /** V193 values: device identities in outbound mail and syslog are pseudonyms, or today's raw values. */
    public static final String IDENTITY_MASKED = "masked";
    public static final String IDENTITY_RAW = "raw";

    /** A missing value (an older client, a legacy row) is the safe default, never raw. */
    public NotificationSettings {
        if (notificationIdentity == null) {
            notificationIdentity = IDENTITY_MASKED;
        }
    }

    public NotificationSettings(boolean syslogEnabled, String syslogHost, int syslogPort, String syslogProtocol,
            int syslogFacility, boolean smtpEnabled, String smtpHost, int smtpPort, boolean smtpStarttls,
            String smtpFrom, String smtpTo, boolean forwardAuditToSyslog, boolean notifyJobFailure, Instant updatedAt,
            List<NotificationRoute> routes) {
        this(syslogEnabled, syslogHost, syslogPort, syslogProtocol, syslogFacility, smtpEnabled, smtpHost,
                smtpPort, smtpStarttls, smtpFrom, smtpTo, forwardAuditToSyslog, notifyJobFailure, updatedAt, routes,
                IDENTITY_MASKED);
    }

    public NotificationSettings(boolean syslogEnabled, String syslogHost, int syslogPort, String syslogProtocol,
            int syslogFacility, boolean smtpEnabled, String smtpHost, int smtpPort, boolean smtpStarttls,
            String smtpFrom, String smtpTo, boolean forwardAuditToSyslog, boolean notifyJobFailure, Instant updatedAt) {
        this(syslogEnabled, syslogHost, syslogPort, syslogProtocol, syslogFacility, smtpEnabled, smtpHost,
                smtpPort, smtpStarttls, smtpFrom, smtpTo, forwardAuditToSyslog, notifyJobFailure, updatedAt, List.of(), IDENTITY_MASKED);
    }

    public boolean rawIdentity() {
        return IDENTITY_RAW.equals(notificationIdentity);
    }

    public NotificationSettings withNotificationIdentity(String value) {
        return new NotificationSettings(syslogEnabled, syslogHost, syslogPort, syslogProtocol, syslogFacility,
                smtpEnabled, smtpHost, smtpPort, smtpStarttls, smtpFrom, smtpTo, forwardAuditToSyslog,
                notifyJobFailure, updatedAt, routes, value);
    }

    public List<String> recipients() {
        if (smtpTo == null || smtpTo.isBlank()) {
            return List.of();
        }
        return NotificationRoute.split(smtpTo);
    }

    /** Refusal reasons for a save, empty when the settings are coherent. */
    public List<String> problems() {
        java.util.ArrayList<String> problems = new java.util.ArrayList<>();
        if (syslogEnabled && (syslogHost == null || syslogHost.isBlank())) {
            problems.add("syslog is enabled but has no host");
        }
        if (syslogPort < 1 || syslogPort > 65535 || smtpPort < 1 || smtpPort > 65535) {
            problems.add("ports must be between 1 and 65535");
        }
        if (!"udp".equals(syslogProtocol) && !"tcp".equals(syslogProtocol)) {
            problems.add("syslog protocol must be udp or tcp");
        }
        if (!IDENTITY_MASKED.equals(notificationIdentity) && !IDENTITY_RAW.equals(notificationIdentity)) {
            problems.add("notification identity must be masked or raw");
        }
        if (syslogFacility < 0 || syslogFacility > 23) {
            problems.add("syslog facility must be between 0 and 23");
        }
        if (smtpEnabled && (smtpHost == null || smtpHost.isBlank() || smtpFrom == null || smtpFrom.isBlank()
                || ((routes == null || routes.isEmpty()) && recipients().isEmpty()))) {
            problems.add("SMTP is enabled but host, from or recipients are missing");
        }
        for (String address : recipients()) {
            if (!address.matches("[^@\\s<>\"]+@[^@\\s<>\"]+")) {
                problems.add("not an e-mail address: " + address);
            }
        }
        if (smtpFrom != null && !smtpFrom.isBlank() && !smtpFrom.strip().matches("[^@\\s<>\"]+@[^@\\s<>\"]+")) {
            problems.add("the from address is not an e-mail address");
        }
        if (routes != null && !routes.isEmpty()) {
            if (routes.size() != NotificationRoute.TYPES.size()
                    || !routes.stream().map(NotificationRoute::type).toList().containsAll(NotificationRoute.TYPES)
                    || routes.stream().map(NotificationRoute::type).distinct().count() != NotificationRoute.TYPES.size()) {
                problems.add("all notification types must be provided once");
            }
            for (NotificationRoute route : routes) {
                if (route.type() == null || !NotificationRoute.TYPES.contains(route.type())) continue;
                for (String address : NotificationRoute.split(route.recipients())) {
                    if (!address.matches("[^@\\s<>\"]+@[^@\\s<>\"]+")) {
                        problems.add(route.type() + " recipients: not an e-mail address: " + address);
                    }
                }
                if (smtpEnabled && route.enabled() && route.effectiveRecipients(this).isEmpty()) {
                    problems.add(route.type() + " recipients are missing");
                }
            }
        }
        return problems;
    }
}
