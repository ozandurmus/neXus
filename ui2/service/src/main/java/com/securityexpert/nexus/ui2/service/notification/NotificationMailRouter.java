package com.securityexpert.nexus.ui2.service.notification;

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
    private static final String[] TITLES = {"Administration changes", "Sign-in and access", "Backup failures",
            "Other job failures", "Configuration changes", "Compliance regressions", "Device reachability"};
    private static final String ADMIN_ACTIONS = "'local_credential_create', 'local_password_change', "
            + "'local_credential_admin_password_reset', "
            + "'local_credential_enable', 'local_credential_disable', 'role_binding_create', 'role_binding_revoke', "
            + "'credential_create', 'credential_replace_secret', 'device_credential_set', 'device_secret_set', "
            + "'device_register', 'device_delete', 'device_backup_target_set', "
            + "'notification_settings_update', 'notification_route_update'";
    private static final String LOGIN_ACTIONS = "'local_login_failure', 'machine_session_token_refused', "
            + "'machine_session_roles_refused', 'machine_session_read_only', 'session_login_takeover'";

    private NotificationMailRouter() {}

    static void tick(DSLContext dsl, NotificationSettings settings) {
        tick(dsl, settings, SmtpRelaySender::send);
    }

    @FunctionalInterface
    interface Mailer {
        void send(NotificationSettings settings, List<String> recipients, String subject, String body) throws IOException;
    }

    static void tick(DSLContext dsl, NotificationSettings settings, Mailer mailer) {
        for (Record row : dsl.fetch("select * from notification_routes order by type")) {
            if (!Boolean.TRUE.equals(row.get("enabled", Boolean.class))) continue;
            String type = row.get("type", String.class);
            NotificationRoute route = new NotificationRoute(type, true, row.get("recipients", String.class), null, null);
            List<String> recipients = route.effectiveRecipients(settings);
            if (!settings.smtpEnabled() || settings.smtpHost() == null || settings.smtpHost().isBlank()) continue;
            try {
                if (recipients.isEmpty()) throw new IOException("no recipients configured for this type");
                long audit = row.get("watermark_audit", Long.class) == null ? 0 : row.get("watermark_audit", Long.class);
                Timestamp watermark = row.get("watermark_time", Timestamp.class);
                Instant from = watermark == null ? Instant.EPOCH : watermark.toInstant();
                Batch batch = collect(dsl, type, audit, from);
                if (batch.lines().isEmpty()) {
                    applyState(dsl, batch.state());
                    continue;
                }
                mailer.send(settings, recipients, subject(type, batch.lines().size()), digest(batch.lines()));
                applyState(dsl, batch.state());
                dsl.execute("update notification_routes set watermark_audit = {0}, watermark_time = {1}, "
                        + "last_sent_at = now(), last_error = null where type = {2}", batch.audit(),
                        Timestamp.from(batch.time()), type);
            } catch (Exception e) {
                dsl.execute("update notification_routes set last_error = {0} where type = {1}",
                        e instanceof IOException ? "SMTP relay rejected or unavailable" : "event collection failed", type);
            }
        }
    }

    static String subject(String type, int count) {
        int index = NotificationRoute.TYPES.indexOf(type);
        return "neXus – " + (index < 0 ? type : TITLES[index]) + ": " + count + " event(s)";
    }

    static String digest(List<String> lines) {
        String body = String.join("\n", lines.subList(0, Math.min(200, lines.size())));
        return body + (lines.size() > 200 ? "\nand " + (lines.size() - 200) + " more" : "") + "\n";
    }

    record Batch(List<String> lines, long audit, Instant time, Map<String, String> state) {}

    static Batch collect(DSLContext dsl, String type, long audit, Instant from) throws IOException {
        List<String> lines = new ArrayList<>();
        Map<String, String> state = new LinkedHashMap<>();
        long newestAudit = audit;
        Instant newestTime = from;
        List<Record> rows;
        switch (type) {
            case "admin_event", "login_security" -> {
                rows = dsl.fetch("select audit_id, action_id, row_pk, occurred_at from audit_log "
                        + "where audit_id > {0} and action_id in (" + (type.equals("admin_event") ? ADMIN_ACTIONS : LOGIN_ACTIONS)
                        + ") and (action_id not in ('device_register', 'device_delete') or table_name = 'devices') "
                        + "order by audit_id", audit);
                for (Record r : rows) {
                    newestAudit = Math.max(newestAudit, r.get("audit_id", Long.class));
                    lines.add(at(r, "occurred_at") + "  " + r.get("action_id", String.class)
                            + "  " + safe(r.get("row_pk", String.class)));
                }
            }
            case "backup_failure", "job_failure" -> {
                String backup = "(j.job_type like '%\\_backup' escape '\\' or j.job_type = 'https_vendor_backup')";
                String predicate = type.equals("backup_failure")
                        ? backup + " and (j.state = 'FAILED' or (j.state = 'COMPLETED' and j.terminal_reason like 'partial:%'))"
                        : "not " + backup + " and (j.state = 'FAILED' or "
                            + "(j.job_type = 'cp_cluster_failover' and j.state = 'OUTCOME_UNKNOWN'))";
                rows = dsl.fetch("select j.job_id, j.job_type, j.terminal_reason, j.finished_at, "
                        + "coalesce(d.observed_hostname, j.target_device_id) as device_name from jobs j "
                        + "left join devices d on d.device_id = j.target_device_id where j.finished_at > {0} and "
                        + predicate + " order by j.finished_at, j.job_id", Timestamp.from(from));
                for (Record r : rows) {
                    newestTime = max(newestTime, at(r, "finished_at"));
                    String deviceName = "cp_cluster_failover".equals(r.get("job_type", String.class))
                            ? "CP failover unit" : safe(r.get("device_name", String.class));
                    lines.add(at(r, "finished_at") + "  " + deviceName + "  "
                            + r.get("job_type", String.class) + ": " + safe(r.get("terminal_reason", String.class))
                            + "  /operations/jobs/" + r.get("job_id", String.class));
                }
            }
            case "config_change" -> {
                rows = dsl.fetch("select n.notification_id, n.kind, n.summary, n.created_at, "
                        + "coalesce(d.observed_hostname, n.device_id) as device_name "
                        + "from configuration_notification n left join devices d on d.device_id = n.device_id "
                        + "where n.created_at > {0} order by n.created_at, n.notification_id", Timestamp.from(from));
                for (Record r : rows) {
                    newestTime = max(newestTime, at(r, "created_at"));
                    lines.add(at(r, "created_at") + "  " + safe(r.get("device_name", String.class)) + "  "
                            + r.get("kind", String.class) + ": " + safe(r.get("summary", String.class))
                            + "  (notification " + r.get("notification_id", String.class) + ")");
                }
            }
            case "compliance_regression" -> {
                Map<String, String> previous = states(dsl, "c:");
                rows = dsl.fetch("select e.device_id, e.evaluated_at, e.result::text as result, "
                        + "coalesce(d.observed_hostname, e.device_id) as device_name from compliance_evaluation e "
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
                            lines.add(at(r, "evaluated_at") + "  " + safe(r.get("device_name", String.class))
                                    + "  " + safe(control) + " PASS -> FAIL (" + safe(item.path("severity").asText())
                                    + ")  /compliance");
                        }
                        if (!status.equals(previous.get(key))) state.put(key, status);
                    }
                }
            }
            case "device_health" -> {
                Map<String, String> previous = states(dsl, "h:");
                rows = dsl.fetch("select j.job_id, j.target_device_id, j.state, j.terminal_reason, j.finished_at, "
                        + "coalesce(d.observed_hostname, j.target_device_id) as device_name from ("
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
                        + "coalesce(d.observed_hostname, j.target_device_id) as device_name from jobs j "
                        + "left join devices d on d.device_id = j.target_device_id "
                        + "where j.finished_at > {0} and j.state = 'FAILED' "
                        + "and j.terminal_reason ilike '%host key mismatch%' order by j.finished_at, j.job_id",
                        Timestamp.from(from))) {
                    lines.add(at(r, "finished_at") + "  " + safe(r.get("device_name", String.class))
                            + "  SSH host key mismatch  /operations/jobs/" + r.get("job_id", String.class));
                    newestTime = max(newestTime, at(r, "finished_at"));
                }
                for (var entry : byDevice.entrySet()) {
                    List<Record> recent = entry.getValue();
                    Record latest = recent.get(0);
                    boolean failed = recent.size() == 3 && recent.stream().allMatch(r -> "FAILED".equals(r.get("state", String.class))
                            && transportFailure(r.get("terminal_reason", String.class)));
                    String key = "h:" + entry.getKey();
                    String old = previous.get(key);
                    if (failed && !"unreachable".equals(old)) {
                        lines.add(at(latest, "finished_at") + "  " + safe(latest.get("device_name", String.class))
                                + "  unreachable after three transport failures  /operations/jobs/" + latest.get("job_id", String.class));
                        state.put(key, "unreachable");
                    } else if ("unreachable".equals(old) && "COMPLETED".equals(latest.get("state", String.class))) {
                        lines.add(at(latest, "finished_at") + "  " + safe(latest.get("device_name", String.class))
                                + "  reachable again  /operations/jobs/" + latest.get("job_id", String.class));
                        state.put(key, "reachable");
                    } else if (old == null && !failed) {
                        state.put(key, "reachable");
                    }
                }
            }
            default -> throw new IllegalArgumentException("unknown notification type");
        }
        return new Batch(lines, newestAudit, newestTime, state);
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
