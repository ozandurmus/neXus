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
 * <li>mail: one digest per enabled route, with its own watermark;</li>
 * <li>script runs (V266): the syslog line and/or mail each finished script run's record asks for, the outcome recorded
 * on the run ({@link ScriptRunNotifier}).</li>
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

    private ScriptRunNotifier.Mailer scriptRunMailer = SmtpRelaySender::send;

    /** The script-run mail send, replaceable in tests. */
    NotificationForwarder withScriptRunMailer(ScriptRunNotifier.Mailer mailer) {
        this.scriptRunMailer = Objects.requireNonNull(mailer, "mailer");
        return this;
    }

    /** V266: run-end notifications of finished script runs; best-effort, the outcome is recorded on the run. */
    int forwardScriptRuns(NotificationSettings settings, NotificationIdentity identity) {
        try {
            return new ScriptRunNotifier(transactionBoundary, syslog, scriptRunMailer).forward(settings, identity);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SCRIPT_RUN_FAILED] {0}", e.getClass().getSimpleName());
            return 0;
        }
    }

    static final String CREDENTIAL_ALERT_WATERMARK_KEY = "credential_alert_syslog_watermark";
    static final String CREDENTIAL_ALERT_TAG = "CREDENTIAL_FAILED";
    static final String BREAK_GLASS_WATERMARK_KEY = "break_glass_alert_syslog_watermark";
    static final String BREAK_GLASS_TAG = "BREAK_GLASS_LOGIN";

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
            if (settings.syslogEnabled()) forwardBreakGlassAlerts(settings);
            forwardScriptRuns(settings, identity);
            NotificationMailRouter.tick(dsl, settings, identity);
            return null;
        });
    }

    /** Test mails use the newest real events: the last {@value #RECENT_TEST_ROWS} of the past seven days. */
    static final int RECENT_TEST_ROWS = 10;
    /** Audit-based types look back this many audit rows (primary key range, no scan). */
    static final long RECENT_TEST_AUDIT_WINDOW = 20_000;

    /**
     * Renders a "Send test" mail for {@code type} from the newest real events of the past seven days, with the identity
     * mode the live route uses (raw names and addresses when {@code notification_identity} is raw). Read only: no
     * watermark or state moves. Empty when the type has no recent event (the caller then sends the synthetic sample).
     */
    public java.util.Optional<NotificationMailTemplate.RenderedMail> recentEventsMail(String type) {
        NotificationSettings settings = store.read();
        try {
            return transactionBoundary.inTransaction(dsl -> {
                NotificationIdentity identity = NotificationIdentity.of(settings, names, ips);
                identity.warm(dsl);
                Long maxAudit = dsl.fetchOne("select max(audit_id) as m from audit_log").get("m", Long.class);
                long auditFloor = Math.max(0, (maxAudit == null ? 0 : maxAudit) - RECENT_TEST_AUDIT_WINDOW);
                NotificationMailRouter.Batch batch;
                try {
                    batch = NotificationMailRouter.collect(dsl, type, auditFloor,
                            Instant.now().minus(java.time.Duration.ofDays(7)), identity);
                } catch (java.io.IOException | RuntimeException e) {
                    return java.util.Optional.<NotificationMailTemplate.RenderedMail>empty();
                }
                if (batch.lines().isEmpty() && batch.scans().isEmpty()) return java.util.Optional.empty();
                NotificationMailRouter.Batch recent = new NotificationMailRouter.Batch(tail(batch.lines()),
                        tail(batch.rows()), batch.scans(), batch.audit(), batch.time(), java.util.Map.of());
                return java.util.Optional.of(NotificationMailTemplate.render(type, recent, MailBrand.name(),
                        MailBrand.publicBaseUrl(dsl), MailBrand.environment()));
            });
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    private static <T> List<T> tail(List<T> items) {
        return items.size() <= RECENT_TEST_ROWS ? items : List.copyOf(items.subList(items.size() - RECENT_TEST_ROWS, items.size()));
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
                com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_audit", "success");
            } catch (java.io.IOException e) {
                com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_audit", "failure");
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
                    com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_credential_alert", "success");
                } catch (java.io.IOException e) {
                    com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_credential_alert", "failure");
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

    /**
     * Migration plan Step 3: a successful local break-glass sign-in (the root identity while the local-account scope is
     * BREAK_GLASS_ONLY) is an ERROR line of its own, not only an INFORMATIONAL audit row. The line carries the opaque actor
     * fingerprint and the time, never an account name. The watermark is the event id and advances only past lines that were sent;
     * the {@code login_security} mail digest carries the same audit action.
     */
    void forwardBreakGlassAlerts(NotificationSettings settings) {
        List<Record> rows = transactionBoundary.inTransaction(dsl -> {
            Record state = dsl.fetchOne("select value from notification_state where key = {0}", BREAK_GLASS_WATERMARK_KEY);
            long from = state == null ? 0L : Long.parseLong(state.get("value", String.class));
            return dsl.fetch("select event_id, actor_fingerprint, occurred_at from local_break_glass_event "
                    + "where alert and outcome = 'LOGIN_SUCCEEDED' and event_id > {0} order by event_id limit 100", from);
        });
        long sentUpTo = -1;
        for (Record r : rows) {
            String message = "local break-glass sign-in actor=" + r.get("actor_fingerprint", String.class)
                    + " at=" + r.get("occurred_at", Timestamp.class).toInstant();
            try {
                syslog.send(settings, SyslogSender.Severity.ERROR, BREAK_GLASS_TAG, message);
                com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_break_glass", "success");
            } catch (java.io.IOException e) {
                com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_break_glass", "failure");
                LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SYSLOG_FAILED] break-glass alert: {0}", e.getMessage());
                break;
            }
            sentUpTo = r.get("event_id", Long.class);
        }
        if (sentUpTo >= 0) {
            String value = Long.toString(sentUpTo);
            transactionBoundary.inTransaction(dsl -> dsl.execute("insert into notification_state(key, value) values ({0}, {1}) "
                    + "on conflict (key) do update set value = excluded.value, updated_at = now()",
                    BREAK_GLASS_WATERMARK_KEY, value));
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
                    com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_job_failure", "success");
                } catch (java.io.IOException e) {
                    com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_job_failure", "failure");
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
