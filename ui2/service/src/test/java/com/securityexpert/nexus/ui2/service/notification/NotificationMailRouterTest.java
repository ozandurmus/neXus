package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class NotificationMailRouterTest {
    private static final Instant FROM = Instant.parse("2026-09-27T09:00:00Z");
    private static final String AT = "2026-09-27 10:00:00";

    private static Result<Record> rows(DSLContext dsl, String[] columns, String[]... values) {
        String[][] data = new String[values.length + 1][];
        data[0] = columns;
        System.arraycopy(values, 0, data, 1, values.length);
        return dsl.fetchFromStringData(data);
    }

    private static DSLContext fixture() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        return DSL.using(new MockConnection(ctx -> {
            String sql = ctx.sql();
            Result<Record> result;
            if (sql.contains("from notification_state")) {
                result = rows(create, new String[] { "key", "value" },
                        new String[] { "c:device-a:CTRL-1", "PASS" }, new String[] { "h:device-a", "reachable" });
            } else if (sql.contains("from audit_log")) {
                result = rows(create, new String[] { "audit_id", "action_id", "row_pk", "occurred_at" },
                        sql.contains("local_login_failure") ? new String[] { "12", "local_login_failure", "synthetic-actor", AT }
                                : new String[] { "11", "device_register", "device-a", AT });
            } else if (sql.contains("configuration_notification n")) {
                result = rows(create, new String[] { "notification_id", "kind", "summary", "created_at", "device_name" },
                        new String[] { "config-1", "configuration_override_detected", "safe summary", AT, "FW-TANGO-04" });
            } else if (sql.contains("compliance_evaluation e")) {
                result = rows(create, new String[] { "device_id", "evaluated_at", "result", "device_name" },
                        new String[] { "device-a", AT,
                                "{\"items\":[{\"controlId\":\"CTRL-1\",\"displayStatus\":\"FAIL\",\"severity\":\"LOW\"}]}", "FW-TANGO-04" });
            } else if (sql.contains("from jobs j") || sql.contains("row_number() over")) {
                if (sql.contains("row_number() over")) {
                    result = rows(create,
                            new String[] { "job_id", "target_device_id", "state", "terminal_reason", "finished_at", "device_name" },
                            new String[] { "job-3", "device-a", "FAILED", "connection timed out", AT, "FW-TANGO-04" },
                            new String[] { "job-2", "device-a", "FAILED", "transport refused", "2026-09-27 09:59:00", "FW-TANGO-04" },
                            new String[] { "job-1", "device-a", "FAILED", "connection failed", "2026-09-27 09:58:00", "FW-TANGO-04" });
                } else if (sql.contains("host key mismatch")) {
                    result = rows(create, new String[] { "job_id", "finished_at", "device_name" });
                } else {
                    result = rows(create, new String[] { "job_id", "job_type", "terminal_reason", "finished_at", "device_name" },
                            new String[] { "job-4", sql.contains("partial:%") ? "cp_backup" : "inventory", "synthetic failure", AT, "FW-TANGO-04" });
                }
            } else {
                result = rows(create, new String[] { "empty" });
            }
            return new MockResult[] { new MockResult(result.size(), result) };
        }), SQLDialect.POSTGRES);
    }

    @Test
    void certificateChangesRaiseDeviceHealthForSuccessfulAndDiscoveryJobs() throws Exception {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            Result<Record> result = ctx.sql().contains("certificate changed")
                    ? rows(create, new String[]{"job_id", "finished_at"}, new String[]{"discovery-job", AT})
                    : rows(create, new String[]{"empty"});
            if (ctx.sql().contains("certificate changed")) assertFalse(ctx.sql().contains("state = 'FAILED'"));
            return new MockResult[]{new MockResult(result.size(), result)};
        }), SQLDialect.POSTGRES);
        var batch = NotificationMailRouter.collect(dsl, "device_health", 0, FROM);
        assertEquals(1, batch.lines().size());
        assertTrue(batch.lines().getFirst().contains("HTTPS certificate changed"));
        assertTrue(batch.lines().getFirst().contains("/operations/jobs/discovery-job"));
    }

    @Test
    void everyTypeQueriesItsSyntheticEventSource() throws Exception {
        DSLContext dsl = fixture();
        for (String type : NotificationRoute.TYPES) {
            if (type.equals("security_scan")) continue; // File-backed source covered by SecurityScanSummaryTest.
            NotificationMailRouter.Batch batch = NotificationMailRouter.collect(dsl, type, 10, FROM);
            assertFalse(batch.lines().isEmpty(), type);
        }
        assertEquals(11, NotificationMailRouter.collect(dsl, "admin_event", 10, FROM).audit());
        assertEquals("FAIL", NotificationMailRouter.collect(dsl, "compliance_regression", 10, FROM)
                .state().get("c:device-a:CTRL-1"));
        assertEquals("unreachable", NotificationMailRouter.collect(dsl, "device_health", 10, FROM)
                .state().get("h:device-a"));
    }

    @Test
    void digestCapsLinesAndRouteRecipientsFallBackToDefaults() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 205; i++) lines.add("event " + i);
        String body = NotificationMailRouter.digest(lines);
        assertTrue(body.contains("event 199\nand 5 more"));
        assertFalse(body.contains("event 200"));
        NotificationSettings settings = settings(25);
        assertEquals(List.of("default@example.test"),
                new NotificationRoute("job_failure", true, "", null, null).effectiveRecipients(settings));
    }

    @Test
    void failedRouteKeepsItsWatermarkAfterAnotherRouteSucceeds() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        List<String> updates = new ArrayList<>();
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String sql = ctx.sql();
            Result<Record> result;
            if (sql.contains("from notification_routes order by type")) {
                result = rows(create, new String[] { "type", "enabled", "recipients", "watermark_audit", "watermark_time" },
                        new String[] { "admin_event", "true", "admin@example.test", "10", AT },
                        new String[] { "job_failure", "true", "admin@example.test", "10", AT });
            } else if (sql.contains("from audit_log")) {
                result = rows(create, new String[] { "audit_id", "action_id", "row_pk", "occurred_at" },
                        new String[] { "11", "role_binding_create", "synthetic-role", "2026-09-27 10:01:00" });
            } else if (sql.contains("from jobs j")) {
                result = rows(create, new String[] { "job_id", "job_type", "terminal_reason", "finished_at", "device_name" },
                        new String[] { "job-1", "inventory", "synthetic failure", "2026-09-27 10:01:00", "FW-TANGO-04" });
            } else {
                if (sql.startsWith("update notification_routes")) updates.add(sql + " " + java.util.Arrays.toString(ctx.bindings()));
                result = rows(create, new String[] { "empty" });
            }
            return new MockResult[] { new MockResult(result.size(), result) };
        }), SQLDialect.POSTGRES);
        NotificationMailRouter.tick(dsl, settings(25), (settings, recipients, subject, body) -> {
            if (subject.contains("Other job failures")) throw new IOException("synthetic refusal");
        });
        assertEquals(1, updates.stream().filter(s -> s.contains("watermark_audit")).count());
        assertTrue(updates.stream().anyMatch(s -> s.contains("last_error") && s.contains("job_failure")));
    }

    @Test
    void fakeRelayAcceptsOneDigestWithoutAuthentication() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept();
                        var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        var out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                    out.write("220 synthetic relay\r\n"); out.flush();
                    StringBuilder message = new StringBuilder();
                    boolean data = false;
                    for (String line; (line = in.readLine()) != null;) {
                        if (data) {
                            if (line.equals(".")) { out.write("250 accepted\r\n"); out.flush(); data = false; }
                            else message.append(line).append('\n');
                        } else if (line.startsWith("EHLO") || line.startsWith("MAIL FROM") || line.startsWith("RCPT TO")) {
                            out.write("250 ok\r\n"); out.flush();
                        } else if (line.equals("DATA")) {
                            data = true; out.write("354 send data\r\n"); out.flush();
                        } else if (line.equals("QUIT")) {
                            out.write("221 bye\r\n"); out.flush(); break;
                        } else throw new AssertionError("unexpected SMTP command");
                    }
                    return message.toString();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            SmtpRelaySender.send(settings(server.getLocalPort()), List.of("admin@example.test"),
                    NotificationMailRouter.subject("admin_event", 1), NotificationMailRouter.digest(List.of("one synthetic event")));
            assertTrue(received.join().contains("one synthetic event"));
        }
    }

    private static NotificationSettings settings(int port) {
        return new NotificationSettings(false, null, 514, "udp", 16, true, "localhost", port, false,
                "nexus@example.test", "default@example.test", false, false, null);
    }
}
