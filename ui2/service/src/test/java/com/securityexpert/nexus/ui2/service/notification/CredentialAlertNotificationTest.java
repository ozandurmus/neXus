package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/** V207 delivery: one syslog ERROR line per new or changed alert, an advancing watermark, and the backup_failure mail line. */
class CredentialAlertNotificationTest {
    private static final String OPENED = "2026-11-12 00:45:10";
    private static final String UPDATED = "2026-11-12 00:46:00";

    private static Result<Record> rows(String[] columns, String[]... values) {
        String[][] data = new String[values.length + 1][];
        data[0] = columns;
        System.arraycopy(values, 0, data, 1, values.length);
        return DSL.using(SQLDialect.POSTGRES).fetchFromStringData(data);
    }

    private static final String[] ALERT_COLUMNS = {"credential_ref_digest", "failed_device_count", "first_failure_at", "changed_at", "closed_at"};

    /** An in-memory notification_state plus a scripted credential_failure_alert table. */
    private static final class Fixture {
        final Map<String, String> state = new HashMap<>();
        final List<String[]> alertRows = new ArrayList<>();
        final List<Object> alertWatermarks = new ArrayList<>();
        final DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String sql = ctx.sql();
            Result<Record> result;
            if (sql.contains("select value from notification_state")) {
                String value = state.get(String.valueOf(ctx.bindings()[0]));
                result = value == null ? rows(new String[] {"value"}) : rows(new String[] {"value"}, new String[] {value});
            } else if (sql.contains("insert into notification_state")) {
                state.put(String.valueOf(ctx.bindings()[0]), String.valueOf(ctx.bindings()[1]));
                return new MockResult[] {new MockResult(1, rows(new String[] {"x"}))};
            } else if (sql.contains("from credential_failure_alert")) {
                alertWatermarks.add(ctx.bindings()[0]);
                result = rows(ALERT_COLUMNS, alertRows.toArray(new String[0][]));
            } else {
                result = rows(new String[] {"empty"});
            }
            return new MockResult[] {new MockResult(result.size(), result)};
        }), SQLDialect.POSTGRES);
    }

    private static NotificationSettings settings() {
        return new NotificationSettings(true, "192.0.2.50", 514, "udp", 16, false, null, 25, false, null, null, false, false,
                Instant.EPOCH, List.of(), NotificationSettings.IDENTITY_MASKED);
    }

    private NotificationForwarder forwarder(Fixture fixture, List<String> sent, boolean failSend) {
        var boundary = new JooqTransactionBoundary(fixture.dsl);
        return new NotificationForwarder(mock(NotificationSettingsStore.class), boundary, null, null)
                .withSyslog((settings, severity, tag, message) -> {
                    if (failSend) throw new IOException("relay down");
                    sent.add(severity + "|" + tag + "|" + message);
                });
    }

    @Test
    void oneErrorLinePerNewAlertAndTheWatermarkAdvances() {
        var fixture = new Fixture();
        fixture.alertRows.add(new String[] {"a1b2c3d4e5f6", "1", OPENED, OPENED, null});
        var sent = new ArrayList<String>();
        var forwarder = forwarder(fixture, sent, false);

        forwarder.forwardCredentialAlerts(settings());

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).startsWith("ERROR|CREDENTIAL_FAILED|backup credential a1b2c3d4e5f6 failed authentication on 1 device(s) since "), sent.get(0));
        assertTrue(sent.get(0).endsWith("; scheduled backups for it are held"), sent.get(0));
        assertTrue(fixture.state.containsKey(NotificationForwarder.CREDENTIAL_ALERT_WATERMARK_KEY));
        Instant watermark = Instant.parse(fixture.state.get(NotificationForwarder.CREDENTIAL_ALERT_WATERMARK_KEY));
        assertEquals(java.sql.Timestamp.valueOf(OPENED).toInstant(), watermark);

        // The next tick asks only for rows newer than the watermark; nothing new, nothing sent.
        fixture.alertRows.clear();
        forwarder.forwardCredentialAlerts(settings());
        assertEquals(1, sent.size());
        assertEquals(java.sql.Timestamp.from(watermark), fixture.alertWatermarks.get(1));
    }

    @Test
    void anUpdateThatChangedTheDeviceCountSendsAgainAndAClosedAlertSendsNothing() {
        var fixture = new Fixture();
        fixture.alertRows.add(new String[] {"a1b2c3d4e5f6", "2", OPENED, UPDATED, null});
        fixture.alertRows.add(new String[] {"0123456789ab", "5", OPENED, "2026-11-12 00:47:00", "2026-11-12 00:47:30"});
        var sent = new ArrayList<String>();
        forwarder(fixture, sent, false).forwardCredentialAlerts(settings());
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("on 2 device(s)"));
        assertFalse(sent.get(0).contains("0123456789ab"));
        assertEquals(java.sql.Timestamp.valueOf("2026-11-12 00:47:00").toInstant(),
                Instant.parse(fixture.state.get(NotificationForwarder.CREDENTIAL_ALERT_WATERMARK_KEY)),
                "the watermark moves past the closed alert too");
    }

    @Test
    void aFailedSendLeavesTheWatermarkWhereItWas() {
        var fixture = new Fixture();
        fixture.alertRows.add(new String[] {"a1b2c3d4e5f6", "1", OPENED, OPENED, null});
        var sent = new ArrayList<String>();
        forwarder(fixture, sent, true).forwardCredentialAlerts(settings());
        assertTrue(sent.isEmpty());
        assertFalse(fixture.state.containsKey(NotificationForwarder.CREDENTIAL_ALERT_WATERMARK_KEY));
    }

    @Test
    void theBackupFailureMailDigestIncludesTheOpenAlert() throws Exception {
        var fixture = new Fixture();
        fixture.alertRows.add(new String[] {"a1b2c3d4e5f6", "3", OPENED, UPDATED, null});
        var batch = NotificationMailRouter.collect(fixture.dsl, "backup_failure", 0, Instant.parse("2026-11-11T00:00:00Z"),
                NotificationIdentity.raw());
        assertEquals(1, batch.lines().size());
        String line = batch.lines().getFirst();
        assertTrue(line.contains("backup credential a1b2c3d4e5f6 failed authentication on 3 device(s) since"), line);
        assertTrue(line.contains("scheduled backups for it are held"), line);
        assertEquals(java.sql.Timestamp.valueOf(UPDATED).toInstant(), batch.time());
    }
}
