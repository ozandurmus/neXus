package com.securityexpert.nexus.ui2.service.notification;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One independent SMTP digest and watermark per configured notification type. */
final class NotificationMailRouter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(NotificationMailRouter.class.getName());
    private static final java.util.Set<String> WARNED_TYPES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String ADMIN_ACTIONS = "'local_credential_create', 'local_password_change', "
            + "'local_credential_admin_password_reset', "
            + "'local_credential_enable', 'local_credential_disable', 'role_binding_create', 'role_binding_revoke', "
            + "'credential_create', 'credential_replace_secret', 'device_credential_set', 'device_secret_set', "
            + "'device_register', 'device_delete', 'device_backup_target_set', "
            + "'notification_settings_update', 'notification_route_update', 'local_auth_mode_write'";
    private static final String LOGIN_ACTIONS = "'local_login_failure', 'machine_session_token_refused', "
            + "'machine_session_roles_refused', 'machine_session_read_only', 'session_login_takeover', "
            + "'local_break_glass_login_succeeded', 'local_non_break_glass_refused'";

    private NotificationMailRouter() {}

    static void tick(DSLContext dsl, NotificationSettings settings, NotificationIdentity identity) {
        tick(dsl, settings, identity, new Mailer() {
            @Override
            public void send(NotificationSettings s, List<String> r, String subject, String body) throws IOException {
                SmtpRelaySender.send(s, r, subject, body);
            }

            @Override
            public void send(NotificationSettings s, List<String> r, String subject, String textBody, String htmlBody)
                    throws IOException {
                SmtpRelaySender.send(s, r, subject, textBody, htmlBody);
            }
        });
    }

    @FunctionalInterface
    interface Mailer {
        void send(NotificationSettings settings, List<String> recipients, String subject, String body) throws IOException;

        default void send(NotificationSettings settings, List<String> recipients, String subject, String textBody,
                String htmlBody) throws IOException {
            send(settings, recipients, subject, textBody);
        }
    }

    static void tick(DSLContext dsl, NotificationSettings settings, NotificationIdentity identity, Mailer mailer) {
        identity.warm(dsl);
        String baseUrl = MailBrand.publicBaseUrl(dsl);
        String environment = MailBrand.environment();
        for (Record row : dsl.fetch("select * from notification_routes order by type")) {
            if (!Boolean.TRUE.equals(row.get("enabled", Boolean.class))) continue;
            String type = row.get("type", String.class);
            if (!NotificationRoute.TYPES.contains(type)) {
                warnSkippedOnce(type);
                continue;
            }
            NotificationRoute route = new NotificationRoute(type, true, row.get("recipients", String.class), null, null);
            List<String> recipients = route.effectiveRecipients(settings);
            if (!settings.smtpEnabled() || settings.smtpHost() == null || settings.smtpHost().isBlank()) continue;
            try {
                if (recipients.isEmpty()) throw new IOException("no recipients configured for this type");
                long audit = row.get("watermark_audit", Long.class) == null ? 0 : row.get("watermark_audit", Long.class);
                Timestamp watermark = row.get("watermark_time", Timestamp.class);
                Instant from = watermark == null ? Instant.EPOCH : watermark.toInstant();
                Batch batch;
                try {
                    batch = collect(dsl, type, audit, from, identity);
                } catch (IOException e) {
                    // A collector's own read failure is not a relay failure; say which (no file contents, no values).
                    com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend(type, "failure");
                    dsl.execute("update notification_routes set last_error = {0} where type = {1}",
                            "event collection failed: " + safeReason(e), type);
                    continue;
                }
                if (batch.lines().isEmpty()) {
                    applyState(dsl, batch.state());
                    continue;
                }
                NotificationMailTemplate.RenderedMail mail = NotificationMailTemplate.render(
                        type, batch, MailBrand.name(), baseUrl, environment);
                mailer.send(settings, recipients, mail.subject(), mail.text(), mail.html());
                com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend(type, "success");
                applyState(dsl, batch.state());
                dsl.execute("update notification_routes set watermark_audit = {0}, watermark_time = {1}, "
                        + "last_sent_at = now(), last_error = null where type = {2}", batch.audit(),
                        Timestamp.from(batch.time()), type);
            } catch (UnhandledTypeException e) {
                warnSkippedOnce(type);
            } catch (Exception e) {
                com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend(type, "failure");
                dsl.execute("update notification_routes set last_error = {0} where type = {1}",
                        e instanceof IOException ? "SMTP relay rejected or unavailable" : "event collection failed", type);
            }
        }
    }

    private static String safeReason(IOException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        message = message.replaceAll("[\\r\\n]", " ").strip();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    /** A route type without a collector: skipped, never an error on the route. */
    static final class UnhandledTypeException extends RuntimeException {
        UnhandledTypeException() { super("no collector for this notification type", null, false, false); }
    }

    private static void warnSkippedOnce(String type) {
        if (WARNED_TYPES.add(String.valueOf(type))) {
            LOG.log(System.Logger.Level.WARNING,
                    "[NOTIFY_ROUTE_TYPE_SKIPPED] notification type {0} has no handler; skipped", type);
        }
    }

    static void resetWarnedTypesForTest() { WARNED_TYPES.clear(); }

    static String subject(String type, int count) {
        return MailBrand.name() + " – " + NotificationRoute.TITLES.getOrDefault(type, type) + ": " + count + " event(s)";
    }

    static String digest(List<String> lines) {
        String body = String.join("\n", lines.subList(0, Math.min(200, lines.size())));
        return body + (lines.size() > 200 ? "\nand " + (lines.size() - 200) + " more" : "") + "\n";
    }

    record Batch(List<String> lines, List<List<String>> rows, List<SecurityScanSummary.ScanReport> scans, long audit,
            Instant time, Map<String, String> state) {
        Batch(List<String> lines, long audit, Instant time, Map<String, String> state) {
            this(lines, List.of(), List.of(), audit, time, state);
        }

        Batch(List<String> lines, List<List<String>> rows, long audit, Instant time, Map<String, String> state) {
            this(lines, rows, List.of(), audit, time, state);
        }
    }

    static Batch collect(DSLContext dsl, String type, long audit, Instant from, NotificationIdentity identity)
            throws IOException {
        List<String> lines = new ArrayList<>();
        List<List<String>> structuredRows = new ArrayList<>();
        Map<String, String> state = new LinkedHashMap<>();
        long newestAudit = audit;
        Instant newestTime = from;
        List<Record> rows;
        switch (type) {
            case "security_scan" -> {
                return SecurityScanSummary.collect(java.nio.file.Path.of(OverwatchEnv.getOrDefault(
                        "UI2_SECURITY_SUMMARY_DIR", "/var/lib/nexus-security/notifications")), from);
            }
            case "admin_event", "login_security" -> {
                rows = dsl.fetch("select audit_id, action_id, row_pk, occurred_at from audit_log "
                        + "where audit_id > {0} and action_id in (" + (type.equals("admin_event") ? ADMIN_ACTIONS : LOGIN_ACTIONS)
                        + ") and (action_id not in ('device_register', 'device_delete') or table_name = 'devices') "
                        + "order by audit_id", audit);
                for (Record r : rows) {
                    newestAudit = Math.max(newestAudit, r.get("audit_id", Long.class));
                    String action = r.get("action_id", String.class);
                    // device_register / device_delete key the row by the device id: a pseudonym derived from it, not the id.
                    String rowPk = "device_register".equals(action) || "device_delete".equals(action)
                            ? identity.deviceKey(r.get("row_pk", String.class))
                            : identity.text(r.get("row_pk", String.class));
                    Instant occurredAt = at(r, "occurred_at");
                    String safeRowPk = safe(rowPk);
                    lines.add(occurredAt + "  " + action + "  " + safeRowPk);
                    structuredRows.add(List.of(occurredAt.toString(), safe(action), safeRowPk));
                }
            }
            case "backup_failure", "job_failure" -> {
                // The MDS export is a backup (cp_mds_export); its OUTCOME_UNKNOWN (ceiling reached, contact lost, cancelled
                // while mds_backup may still run) alerts like a failure (MDS fork storm fix contract C.3).
                String backup = "(j.job_type like '%\\_backup' escape '\\' or j.job_type in ('https_vendor_backup', 'cp_mds_export'))";
                String predicate = type.equals("backup_failure")
                        ? backup + " and (j.state = 'FAILED' or (j.job_type = 'cp_mds_export' and j.state = 'OUTCOME_UNKNOWN')"
                            + " or (j.state = 'COMPLETED' and j.terminal_reason like 'partial:%'))"
                        : "not " + backup + " and (j.state = 'FAILED' or "
                            + "(j.job_type in ('cp_cluster_failover','pan_cluster_failover') and j.state = 'OUTCOME_UNKNOWN'))";
                rows = dsl.fetch("select j.job_id, j.job_type, j.terminal_reason, j.finished_at, "
                        + "coalesce(d.observed_hostname, j.target_device_id) as device_name, d.observed_hostname as observed_hostname, "
                        + "j.target_device_id as target_device_id from jobs j "
                        + "left join devices d on d.device_id = j.target_device_id where j.finished_at > {0} and "
                        + predicate + " order by j.finished_at, j.job_id", Timestamp.from(from));
                for (Record r : rows) {
                    Instant finishedAt = at(r, "finished_at");
                    newestTime = max(newestTime, finishedAt);
                    String jobType = r.get("job_type", String.class);
                    String deviceName = "cp_cluster_failover".equals(jobType)
                            ? "CP failover unit" : "pan_cluster_failover".equals(jobType)
                            ? "PAN failover unit" : safe(identity.device(r, "target_device_id"));
                    String reason = safe(identity.text(r.get("terminal_reason", String.class)));
                    String ref = "/operations/jobs/" + r.get("job_id", String.class);
                    lines.add(finishedAt + "  " + deviceName + "  " + jobType + ": " + reason + "  " + ref);
                    structuredRows.add(List.of(finishedAt.toString(), deviceName, safe(jobType), reason, safe(ref)));
                }
                if (type.equals("backup_failure")) {
                    // V207: an open backup credential failure alert that opened, or whose failed device count changed, since the watermark.
                    for (Record r : dsl.fetch("select credential_ref_digest, failed_device_count, first_failure_at, changed_at "
                            + "from credential_failure_alert where closed_at is null and changed_at > {0} "
                            + "order by changed_at, alert_id", Timestamp.from(from))) {
                        Instant changedAt = at(r, "changed_at");
                        Instant firstFailureAt = at(r, "first_failure_at");
                        newestTime = max(newestTime, changedAt);
                        String digest = r.get("credential_ref_digest", String.class);
                        Integer count = r.get("failed_device_count", Integer.class);
                        String detail = "failed authentication on " + count
                                + " device(s) since " + firstFailureAt
                                + "; scheduled backups for it are held";
                        lines.add(changedAt + "  backup credential " + digest + " " + detail + "  /backups");
                        structuredRows.add(List.of(changedAt.toString(), "backup credential " + safe(digest),
                                "credential_failure_alert", detail, "/backups"));
                    }
                }
            }
            case "config_change" -> {
                // Override notifications (CG-7d) plus every primary configuration read that differs from the previous
                // read of the same device (change_state 'changed'); a first read is not a change. Reads look back one day at most.
                rows = dsl.fetch("select * from ("
                        + "select n.notification_id, n.kind, n.summary, n.created_at, "
                        + "coalesce(d.observed_hostname, n.device_id) as device_name, d.observed_hostname as observed_hostname, "
                        + "n.device_id as device_id "
                        + "from configuration_notification n left join devices d on d.device_id = n.device_id "
                        + "where n.created_at > {0} "
                        + "union all "
                        + "select r.run_id, 'configuration_changed', "
                        + "'configuration differs from the previous read (' || r.vendor || ', ' || r.read_kind || ')', "
                        + "r.created_at, coalesce(d.observed_hostname, r.device_id), d.observed_hostname, r.device_id "
                        + "from device_configuration_run r left join devices d on d.device_id = r.device_id "
                        // An old watermark (this source was added 2026-11-19) must not replay months of changes.
                        + "where r.change_state = 'changed' and r.is_primary "
                        + "and r.created_at > greatest({0}, now() - interval '1 day')"
                        + ") c order by c.created_at, c.notification_id", Timestamp.from(from));
                for (Record r : rows) {
                    Instant createdAt = at(r, "created_at");
                    newestTime = max(newestTime, createdAt);
                    String device = safe(identity.device(r, "device_id"));
                    String kind = r.get("kind", String.class);
                    String summary = safe(identity.text(r.get("summary", String.class)));
                    String notificationId = r.get("notification_id", String.class);
                    lines.add(createdAt + "  " + device + "  " + kind + ": " + summary + "  (notification " + notificationId + ")");
                    structuredRows.add(List.of(createdAt.toString(), device, safe(kind), summary, safe(notificationId)));
                }
            }
            case "device_crash_file" -> {
                // One alert row per (device, run, process); the route watermark is the alert creation time (contract §7).
                rows = dsl.fetch("select a.alert_id, a.device_id, a.process, a.file_count, a.newest_mtime, a.job_id, a.created_at, "
                        + "coalesce(d.observed_hostname, a.device_id) as device_name, d.observed_hostname as observed_hostname "
                        + "from device_crash_alert a left join devices d on d.device_id = a.device_id "
                        + "where a.created_at > {0} order by a.created_at, a.alert_id", Timestamp.from(from));
                for (Record r : rows) {
                    Instant createdAt = at(r, "created_at");
                    newestTime = max(newestTime, createdAt);
                    String device = safe(identity.device(r, "device_id"));
                    Integer fileCount = r.get("file_count", Integer.class);
                    String process = safe(r.get("process", String.class));
                    Instant newestMtime = Instant.ofEpochSecond(r.get("newest_mtime", Long.class));
                    String ref = "/operations/jobs/" + r.get("job_id", String.class);
                    lines.add(createdAt + "  " + device + "  " + fileCount + " new crash/dump file(s), process "
                            + process + ", newest at device time " + newestMtime + "  " + ref);
                    structuredRows.add(List.of(createdAt.toString(), device, String.valueOf(fileCount), process,
                            newestMtime.toString(), safe(ref)));
                }
            }
            case "backup_size_anomaly" -> {
                // One row per alert (PO 2026-11-04): own-history size_anomaly, fleet_large, firewall_logs. The summary
                // is generated from sizes, ratios and percentages only; the route watermark is the row's detected_at.
                rows = dsl.fetch("select d.deviation_id, d.deviation_class, d.summary, d.detected_at, "
                        + "coalesce(v.observed_hostname, d.device_id) as device_name, v.observed_hostname as observed_hostname, "
                        + "d.device_id as device_id "
                        + "from backup_deviation_record d left join devices v on v.device_id = d.device_id "
                        + "where d.deviation_class in ('size_anomaly', 'fleet_large', 'firewall_logs') "
                        + "and d.detected_at > {0} order by d.detected_at, d.deviation_id", Timestamp.from(from));
                for (Record r : rows) {
                    Instant detectedAt = at(r, "detected_at");
                    newestTime = max(newestTime, detectedAt);
                    String device = safe(identity.device(r, "device_id"));
                    String deviationClass = r.get("deviation_class", String.class);
                    String summary = safe(identity.text(r.get("summary", String.class)));
                    lines.add(detectedAt + "  " + device + "  " + deviationClass + ": " + summary + "  /backups");
                    structuredRows.add(List.of(detectedAt.toString(), device, safe(deviationClass), summary, "/backups"));
                }
            }
            case "compliance_regression" -> {
                Map<String, String> previous = states(dsl, "c:");
                rows = dsl.fetch("select e.device_id, e.evaluated_at, e.result::text as result, "
                        + "coalesce(d.observed_hostname, e.device_id) as device_name, d.observed_hostname as observed_hostname "
                        + "from compliance_evaluation e "
                        + "left join devices d on d.device_id = e.device_id");
                for (Record r : rows) {
                    JsonNode items = JSON.readTree(r.get("result", String.class)).path("items");
                    if (!items.isArray()) continue;
                    for (JsonNode item : items) {
                        String control = item.path("controlId").asText("");
                        String status = item.path("displayStatus").asText("");
                        if (control.isEmpty() || status.isEmpty()) continue;
                        String key = "c:" + r.get("device_id", String.class) + ":" + control;
                        if ("PASS".equals(previous.get(key)) && "FAIL".equals(status)) {
                            Instant evaluatedAt = at(r, "evaluated_at");
                            String device = safe(identity.device(r, "device_id"));
                            String safeControl = safe(control);
                            String severity = safe(item.path("severity").asText());
                            lines.add(evaluatedAt + "  " + device + "  " + safeControl
                                    + " PASS -> FAIL (" + severity + ")  /compliance");
                            structuredRows.add(List.of(evaluatedAt.toString(), device, safeControl,
                                    "PASS -> FAIL", severity, "/compliance"));
                        }
                        if (!status.equals(previous.get(key))) state.put(key, status);
                    }
                }
            }
            case "device_health" -> {
                Map<String, String> previous = states(dsl, "h:");
                rows = dsl.fetch("select j.job_id, j.target_device_id, j.state, j.terminal_reason, j.finished_at, "
                        + "coalesce(d.observed_hostname, j.target_device_id) as device_name, d.observed_hostname as observed_hostname from ("
                        + "select job_id, target_device_id, state, terminal_reason, finished_at, "
                        + "row_number() over (partition by target_device_id order by finished_at desc, job_id desc) as rn "
                        + "from jobs where finished_at is not null) j "
                        + "left join devices d on d.device_id = j.target_device_id where j.rn <= 3 "
                        + "order by j.target_device_id, j.finished_at desc, j.job_id desc");
                Map<String, List<Record>> byDevice = new LinkedHashMap<>();
                for (Record r : rows) {
                    String device = r.get("target_device_id", String.class);
                    List<Record> recent = byDevice.computeIfAbsent(device, unused -> new ArrayList<>());
                    if (recent.size() < 3) recent.add(r);
                }
                for (Record r : dsl.fetch("select j.job_id, j.finished_at, "
                        + "coalesce(d.observed_hostname, j.target_device_id) as device_name, d.observed_hostname as observed_hostname, "
                        + "j.target_device_id as target_device_id from jobs j "
                        + "left join devices d on d.device_id = j.target_device_id "
                        + "where j.finished_at > {0} and j.state = 'FAILED' "
                        + "and j.terminal_reason ilike '%host key mismatch%' order by j.finished_at, j.job_id",
                        Timestamp.from(from))) {
                    Instant finishedAt = at(r, "finished_at");
                    String device = safe(identity.device(r, "target_device_id"));
                    String ref = "/operations/jobs/" + r.get("job_id", String.class);
                    lines.add(finishedAt + "  " + device + "  SSH host key mismatch  " + ref);
                    structuredRows.add(List.of(finishedAt.toString(), device, "SSH host key mismatch", safe(ref)));
                    newestTime = max(newestTime, finishedAt);
                }
                for (Record r : dsl.fetch("select job_id, finished_at from jobs where finished_at > {0} "
                        + "and terminal_reason ilike '%certificate changed%' order by finished_at, job_id", Timestamp.from(from))) {
                    Instant finishedAt = at(r, "finished_at");
                    String ref = "/operations/jobs/" + r.get("job_id", String.class);
                    lines.add(finishedAt + "  HTTPS certificate changed  " + ref);
                    structuredRows.add(List.of(finishedAt.toString(), "discovery / endpoint",
                            "HTTPS certificate changed", safe(ref)));
                    newestTime = max(newestTime, finishedAt);
                }
                for (var entry : byDevice.entrySet()) {
                    List<Record> recent = entry.getValue();
                    Record latest = recent.get(0);
                    boolean failed = recent.size() == 3 && recent.stream().allMatch(r -> "FAILED".equals(r.get("state", String.class))
                            && transportFailure(r.get("terminal_reason", String.class)));
                    String key = "h:" + entry.getKey();
                    String old = previous.get(key);
                    if (failed && !"unreachable".equals(old)) {
                        Instant finishedAt = at(latest, "finished_at");
                        String device = safe(identity.device(latest, "target_device_id"));
                        String ref = "/operations/jobs/" + latest.get("job_id", String.class);
                        lines.add(finishedAt + "  " + device + "  unreachable after three transport failures  " + ref);
                        structuredRows.add(List.of(finishedAt.toString(), device,
                                "unreachable after three transport failures", safe(ref)));
                        state.put(key, "unreachable");
                    } else if ("unreachable".equals(old) && "COMPLETED".equals(latest.get("state", String.class))) {
                        Instant finishedAt = at(latest, "finished_at");
                        String device = safe(identity.device(latest, "target_device_id"));
                        String ref = "/operations/jobs/" + latest.get("job_id", String.class);
                        lines.add(finishedAt + "  " + device + "  reachable again  " + ref);
                        structuredRows.add(List.of(finishedAt.toString(), device, "reachable again", safe(ref)));
                        state.put(key, "reachable");
                    } else if (old == null && !failed) {
                        state.put(key, "reachable");
                    }
                }
            }
            default -> throw new UnhandledTypeException();
        }
        return new Batch(lines, structuredRows, newestAudit, newestTime, state);
    }

    private static Map<String, String> states(DSLContext dsl, String prefix) {
        Map<String, String> out = new HashMap<>();
        for (Record r : dsl.fetch("select key, value from notification_state where key like {0}", prefix + "%")) {
            out.put(r.get("key", String.class), r.get("value", String.class));
        }
        return out;
    }

    private static void applyState(DSLContext dsl, Map<String, String> state) {
        state.forEach((key, value) -> dsl.execute("insert into notification_state(key, value) values ({0}, {1}) "
                + "on conflict (key) do update set value = excluded.value, updated_at = now() "
                + "where notification_state.value is distinct from excluded.value", key, value));
    }

    private static Instant at(Record r, String column) {
        return r.get(column, Timestamp.class).toInstant();
    }

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static String safe(String text) {
        if (text == null) return "";
        String singleLine = text.replace('\r', ' ').replace('\n', ' ').strip();
        return singleLine.length() > 300 ? singleLine.substring(0, 300) + "..." : singleLine;
    }

    private static boolean transportFailure(String reason) {
        if (reason == null) return false;
        String value = reason.toLowerCase(java.util.Locale.ROOT);
        return value.contains("connect") || value.contains("transport") || value.contains("timed out")
                || value.contains("host key mismatch");
    }
}
