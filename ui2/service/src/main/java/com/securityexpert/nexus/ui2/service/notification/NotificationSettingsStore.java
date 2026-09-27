package com.securityexpert.nexus.ui2.service.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.List;

import org.jooq.Record;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** The single {@code notification_settings} row and the forwarders' watermarks. */
@Service
public class NotificationSettingsStore {

    private final TransactionBoundary transactionBoundary;

    public NotificationSettingsStore(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    public NotificationSettings read() {
        return transactionBoundary.inTransaction(dsl -> toSettings(dsl.fetchOne(
                "select * from notification_settings where settings_id = 'default'"),
                dsl.fetch("select type, enabled, recipients, last_sent_at, last_error from notification_routes order by type")
                        .stream().map(r -> new NotificationRoute(r.get("type", String.class),
                                Boolean.TRUE.equals(r.get("enabled", Boolean.class)), r.get("recipients", String.class),
                                instant(r.get("last_sent_at", Timestamp.class)), r.get("last_error", String.class))).toList()));
    }

    public void save(NotificationSettings s, String actorFingerprint) {
        transactionBoundary.inTransaction(dsl -> {
            dsl.execute("update notification_settings set "
                + "syslog_enabled = {0}, syslog_host = {1}, syslog_port = {2}, syslog_protocol = {3}, syslog_facility = {4}, "
                + "smtp_enabled = {5}, smtp_host = {6}, smtp_port = {7}, smtp_starttls = {8}, smtp_from = {9}, smtp_to = {10}, "
                + "forward_audit_to_syslog = {11}, notify_job_failure = {12}, updated_at = now(), updated_by = {13} "
                + "where settings_id = 'default'",
                s.syslogEnabled(), blankToNull(s.syslogHost()), s.syslogPort(), s.syslogProtocol(), s.syslogFacility(),
                s.smtpEnabled(), blankToNull(s.smtpHost()), s.smtpPort(), s.smtpStarttls(), blankToNull(s.smtpFrom()),
                blankToNull(s.smtpTo()), s.forwardAuditToSyslog(), s.notifyJobFailure(), actorFingerprint);
            for (NotificationRoute route : s.routes()) {
                dsl.execute("update notification_routes set enabled = {0}, recipients = {1}, updated_at = now(), "
                        + "updated_by = {2} where type = {3}", route.enabled(), blankToNull(route.recipients()),
                        actorFingerprint, route.type());
            }
            return null;
        });
    }

    public long auditWatermark() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOne(
                "select audit_watermark from notification_settings where settings_id = 'default'").get(0, Long.class));
    }

    public void setAuditWatermark(long value) {
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "update notification_settings set audit_watermark = {0} where settings_id = 'default'", value));
    }

    public Instant jobFailureWatermark() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOne(
                "select job_failure_watermark from notification_settings where settings_id = 'default'")
                .get(0, Timestamp.class).toInstant());
    }

    public void setJobFailureWatermark(Instant value) {
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "update notification_settings set job_failure_watermark = {0} where settings_id = 'default'",
                Timestamp.from(value)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static NotificationSettings toSettings(Record r, List<NotificationRoute> routes) {
        Timestamp updated = r.get("updated_at", Timestamp.class);
        return new NotificationSettings(
                Boolean.TRUE.equals(r.get("syslog_enabled", Boolean.class)), r.get("syslog_host", String.class),
                r.get("syslog_port", Integer.class), r.get("syslog_protocol", String.class), r.get("syslog_facility", Integer.class),
                Boolean.TRUE.equals(r.get("smtp_enabled", Boolean.class)), r.get("smtp_host", String.class),
                r.get("smtp_port", Integer.class), Boolean.TRUE.equals(r.get("smtp_starttls", Boolean.class)),
                r.get("smtp_from", String.class), r.get("smtp_to", String.class),
                Boolean.TRUE.equals(r.get("forward_audit_to_syslog", Boolean.class)),
                Boolean.TRUE.equals(r.get("notify_job_failure", Boolean.class)),
                instant(updated), routes);
    }
}
