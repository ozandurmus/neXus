package com.securityexpert.nexus.ui2.service.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jooq.Record;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * The two first-release triggers, every 60 s, each off until switched on:
 * <ul>
 * <li>audit events to syslog: table, operation, action, actor fingerprint and time -- never the before/after
 * row image (it can carry device identities);</li>
 * <li>job failures: one syslog message per failed job and one mail per tick listing them (job type, device,
 * reason), device identifiers as stored -- a mail goes to the operators' own relay.</li>
 * </ul>
 * Watermarks advance only after a successful send, so an unreachable target repeats nothing and loses nothing;
 * a single run forwards at most 500 audit events and 200 failures.
 */
@Service
public class NotificationForwarder {

    private static final System.Logger LOG = System.getLogger(NotificationForwarder.class.getName());

    private final NotificationSettingsStore store;
    private final TransactionBoundary transactionBoundary;

    public NotificationForwarder(NotificationSettingsStore store, TransactionBoundary transactionBoundary) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        NotificationSettings settings;
        try {
            settings = store.read();
        } catch (RuntimeException e) {
            return;
        }
        if (settings.syslogEnabled() && settings.forwardAuditToSyslog()) {
            forwardAudit(settings);
        }
        if (settings.notifyJobFailure() && (settings.syslogEnabled() || settings.smtpEnabled())) {
            forwardJobFailures(settings);
        }
    }

    void forwardAudit(NotificationSettings settings) {
        long from = store.auditWatermark();
        List<Record> rows = transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select audit_id, table_name, operation, action_id, actor_fingerprint, occurred_at from audit_log "
                        + "where audit_id > {0} order by audit_id limit 500", from));
        long last = from;
        for (Record r : rows) {
            String message = "audit table=" + r.get("table_name", String.class) + " op=" + r.get("operation", String.class)
                    + " action=" + r.get("action_id", String.class) + " actor=" + r.get("actor_fingerprint", String.class)
                    + " at=" + r.get("occurred_at", Timestamp.class).toInstant();
            try {
                SyslogSender.send(settings, SyslogSender.Severity.INFORMATIONAL, "AUDIT", message);
            } catch (java.io.IOException e) {
                LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SYSLOG_FAILED] audit forwarding stopped at {0}: {1}", last, e.getMessage());
                break;
            }
            last = r.get("audit_id", Long.class);
        }
        if (last != from) {
            store.setAuditWatermark(last);
        }
    }

    void forwardJobFailures(NotificationSettings settings) {
        Instant from = store.jobFailureWatermark();
        List<Record> rows = transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select j.job_id, j.job_type, j.target_device_id, d.observed_hostname, j.terminal_reason, j.finished_at "
                        + "from jobs j left join devices d on d.device_id = j.target_device_id "
                        + "where j.state = 'FAILED' and j.finished_at > {0} order by j.finished_at limit 200",
                Timestamp.from(from)));
        if (rows.isEmpty()) {
            return;
        }
        Instant newest = from;
        List<String> lines = new ArrayList<>();
        for (Record r : rows) {
            Instant finished = r.get("finished_at", Timestamp.class).toInstant();
            if (finished.isAfter(newest)) {
                newest = finished;
            }
            String device = r.get("observed_hostname", String.class) != null ? r.get("observed_hostname", String.class)
                    : r.get("target_device_id", String.class);
            String reason = r.get("terminal_reason", String.class);
            lines.add(finished + "  " + r.get("job_type", String.class) + "  " + device + "  "
                    + (reason == null ? "" : reason.length() > 300 ? reason.substring(0, 300) + "..." : reason)
                    + "  (job " + r.get("job_id", String.class) + ")");
        }
        boolean delivered = true;
        if (settings.syslogEnabled()) {
            for (String line : lines) {
                try {
                    SyslogSender.send(settings, SyslogSender.Severity.ERROR, "JOB_FAILED", line);
                } catch (java.io.IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SYSLOG_FAILED] job failure: {0}", e.getMessage());
                    delivered = false;
                    break;
                }
            }
        }
        if (settings.smtpEnabled()) {
            try {
                SmtpRelaySender.send(settings, "neXus: " + lines.size() + " job(s) failed",
                        "The following neXus jobs failed:\n\n" + String.join("\n", lines)
                                + "\n\nOpen Operations > Jobs for the details.\n");
            } catch (java.io.IOException e) {
                LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SMTP_FAILED] job failure mail: {0}", e.getMessage());
                delivered = false;
            }
        }
        if (delivered) {
            store.setJobFailureWatermark(newest);
        }
    }
}
