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
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Syslog forwarding and seven independent SMTP routes, every 60 s:
 * <ul>
 * <li>audit events to syslog: table, operation, action, actor fingerprint and time -- never the before/after
 * row image (it can carry device identities);</li>
 * <li>job failures: one syslog message per failed job when selected;</li>
 * <li>credential failure alerts (V207): one ERROR line per newly opened alert and per update that changes its failed
 * device count, with its own watermark ({@code notification_state});</li>
 * <li>mail: one digest per enabled route, with its own watermark.</li>
 * </ul>
 * Watermarks advance only after a successful send; a single syslog run forwards at most 500 audit events
 * and 200 failures.
 */
@Service
public class NotificationForwarder {

    private static final System.Logger LOG = System.getLogger(NotificationForwarder.class.getName());

    private final NotificationSettingsStore store;
    private final TransactionBoundary transactionBoundary;
    private final TopologyNamePseudonymizer names;
    private final SubnetPreservingIpMasker ips;
    private SyslogSink syslog = SyslogSender::send;

    /** The syslog send, replaceable in tests. */
    @FunctionalInterface
    interface SyslogSink {
        void send(NotificationSettings settings, SyslogSender.Severity severity, String msgId, String message)
                throws java.io.IOException;
    }

    NotificationForwarder withSyslog(SyslogSink sink) {
        this.syslog = Objects.requireNonNull(sink, "sink");
        return this;
    }

    static final String CREDENTIAL_ALERT_WATERMARK_KEY = "credential_alert_syslog_watermark";
    static final String CREDENTIAL_ALERT_TAG = "CREDENTIAL_FAILED";

    /** {@code names} / {@code ips} may be null (unavailable): masked mode then sends fixed labels only. */
    public NotificationForwarder(NotificationSettingsStore store, TransactionBoundary transactionBoundary,
            TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.names = names;
        this.ips = ips;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        NotificationSettings settings;
        try {
            settings = store.read();
        } catch (RuntimeException e) {
            return;
        }
        transactionBoundary.inTransaction(dsl -> {
            if (!Boolean.TRUE.equals(dsl.fetchValue("select pg_try_advisory_xact_lock(10010051)", Boolean.class))) return null;
            if (settings.syslogEnabled() && settings.forwardAuditToSyslog()) forwardAudit(settings);
            NotificationIdentity identity = NotificationIdentity.of(settings, names, ips);
            if (settings.notifyJobFailure() && settings.syslogEnabled()) forwardJobFailures(settings, identity);
            if (settings.syslogEnabled()) forwardCredentialAlerts(settings);
            NotificationMailRouter.tick(dsl, settings, identity);
            return null;
        });
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
                syslog.send(settings, SyslogSender.Severity.INFORMATIONAL, "AUDIT", message);
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

    /**
     * V207: a backup credential failed authentication. One ERROR line per alert that opened or whose failed device
     * count changed since the watermark (an update that changes nothing sends nothing); the watermark advances only past
     * lines that were sent. The line names the credential by its digest, never its reference, and no device.
     */
    void forwardCredentialAlerts(NotificationSettings settings) {
        List<Record> rows = transactionBoundary.inTransaction(dsl -> {
            Record state = dsl.fetchOne("select value from notification_state where key = {0}", CREDENTIAL_ALERT_WATERMARK_KEY);
            Instant from = state == null ? Instant.EPOCH : Instant.parse(state.get("value", String.class));
            return dsl.fetch("select credential_ref_digest, failed_device_count, first_failure_at, changed_at, closed_at "
                    + "from credential_failure_alert where changed_at > {0} order by changed_at, alert_id limit 100",
                    Timestamp.from(from));
        });
        if (rows.isEmpty()) {
            return;
        }
        Instant sentUpTo = null;
        for (Record r : rows) {
            Instant changed = r.get("changed_at", Timestamp.class).toInstant();
            if (r.get("closed_at") == null) {
                String message = "backup credential " + r.get("credential_ref_digest", String.class)
                        + " failed authentication on " + r.get("failed_device_count", Integer.class) + " device(s) since "
                        + r.get("first_failure_at", Timestamp.class).toInstant() + "; scheduled backups for it are held";
                try {
                    syslog.send(settings, SyslogSender.Severity.ERROR, CREDENTIAL_ALERT_TAG, message);
                } catch (java.io.IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SYSLOG_FAILED] credential alert: {0}", e.getMessage());
                    break;
                }
            }
            sentUpTo = changed;
        }
        if (sentUpTo != null) {
            String value = sentUpTo.toString();
            transactionBoundary.inTransaction(dsl -> dsl.execute("insert into notification_state(key, value) values ({0}, {1}) "
                    + "on conflict (key) do update set value = excluded.value, updated_at = now()",
                    CREDENTIAL_ALERT_WATERMARK_KEY, value));
        }
    }

    void forwardJobFailures(NotificationSettings settings, NotificationIdentity identity) {
        Instant from = store.jobFailureWatermark();
        List<Record> rows = transactionBoundary.inTransaction(dsl -> {
            identity.warm(dsl);
            return dsl.fetch(
                "select j.job_id, j.job_type, j.target_device_id, d.observed_hostname, j.terminal_reason, j.finished_at "
                        + "from jobs j left join devices d on d.device_id = j.target_device_id "
                        + "where (j.state = 'FAILED' or (j.job_type in ('cp_cluster_failover','pan_cluster_failover','cp_mds_export') "
                        + "and j.state = 'OUTCOME_UNKNOWN')) and j.finished_at > {0} "
                        + "order by j.finished_at limit 200",
                Timestamp.from(from));
        });
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
            String device = "cp_cluster_failover".equals(r.get("job_type", String.class)) ? "CP failover unit"
                    : "pan_cluster_failover".equals(r.get("job_type", String.class)) ? "PAN failover unit"
                    : identity.isRaw() ? (r.get("observed_hostname", String.class) != null
                            ? r.get("observed_hostname", String.class) : r.get("target_device_id", String.class))
                    : identity.device(r, "target_device_id");
            // Mask first, then clamp: clamping first could leave a half hostname that no longer matches.
            String reason = identity.text(r.get("terminal_reason", String.class));
            lines.add(finished + "  " + r.get("job_type", String.class) + "  " + device + "  "
                    + (reason == null ? "" : reason.length() > 300 ? reason.substring(0, 300) + "..." : reason)
                    + "  (job " + r.get("job_id", String.class) + ")");
        }
        boolean delivered = true;
        if (settings.syslogEnabled()) {
            for (String line : lines) {
                try {
                    syslog.send(settings, SyslogSender.Severity.ERROR, "JOB_FAILED", line);
                } catch (java.io.IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SYSLOG_FAILED] job failure: {0}", e.getMessage());
                    delivered = false;
                    break;
                }
            }
        }
        if (delivered) {
            store.setJobFailureWatermark(newest);
        }
    }
}
