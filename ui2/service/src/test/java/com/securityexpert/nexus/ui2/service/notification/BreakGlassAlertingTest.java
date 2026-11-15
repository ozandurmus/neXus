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

/** Migration plan Step 3: a root break-glass sign-in is one ERROR syslog line with an advancing watermark; the mail digest carries its audit action. */
class BreakGlassAlertingTest {
    private static Result<Record> rows(String[] columns, String[]... values) {
        String[][] data = new String[values.length + 1][];
        data[0] = columns;
        System.arraycopy(values, 0, data, 1, values.length);
        return DSL.using(SQLDialect.POSTGRES).fetchFromStringData(data);
    }

    private static final class Fixture {
        final Map<String, String> state = new HashMap<>();
        final List<String[]> events = new ArrayList<>();
        final List<Object> asked = new ArrayList<>();
        final DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String sql = ctx.sql();
            Result<Record> result;
            if (sql.contains("select value from notification_state")) {
                String value = state.get(String.valueOf(ctx.bindings()[0]));
                result = value == null ? rows(new String[] {"value"}) : rows(new String[] {"value"}, new String[] {value});
            } else if (sql.contains("insert into notification_state")) {
                state.put(String.valueOf(ctx.bindings()[0]), String.valueOf(ctx.bindings()[1]));
                return new MockResult[] {new MockResult(1, rows(new String[] {"x"}))};
            } else if (sql.contains("from local_break_glass_event")) {
                asked.add(ctx.bindings()[0]);
                result = rows(new String[] {"event_id", "actor_fingerprint", "occurred_at"}, events.toArray(new String[0][]));
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

    private static NotificationForwarder forwarder(Fixture fixture, List<String> sent, boolean failSend) {
        return new NotificationForwarder(mock(NotificationSettingsStore.class), new JooqTransactionBoundary(fixture.dsl), null, null)
                .withSyslog((s, severity, tag, message) -> {
                    if (failSend) throw new IOException("relay down");
                    sent.add(severity + "|" + tag + "|" + message);
                });
    }

    @Test
    void oneErrorLinePerRootBreakGlassSignInAndTheWatermarkAdvances() {
        var fixture = new Fixture();
        fixture.events.add(new String[] {"7", "0123456789ab", "2026-11-15 12:00:00"});
        fixture.events.add(new String[] {"9", "0123456789ab", "2026-11-15 12:30:00"});
        var sent = new ArrayList<String>();
        var forwarder = forwarder(fixture, sent, false);

        forwarder.forwardBreakGlassAlerts(settings());

        assertEquals(2, sent.size());
        assertTrue(sent.get(0).startsWith("ERROR|BREAK_GLASS_LOGIN|local break-glass sign-in actor=0123456789ab at="), sent.get(0));
        assertEquals("9", fixture.state.get(NotificationForwarder.BREAK_GLASS_WATERMARK_KEY));

        fixture.events.clear();
        forwarder.forwardBreakGlassAlerts(settings());
        assertEquals(2, sent.size());
        assertEquals(9L, ((Number) fixture.asked.get(1)).longValue(), "the next read starts after the watermark");
    }

    @Test
    void aFailedSendLeavesTheWatermarkWhereItWas() {
        var fixture = new Fixture();
        fixture.events.add(new String[] {"7", "0123456789ab", "2026-11-15 12:00:00"});
        var sent = new ArrayList<String>();
        forwarder(fixture, sent, true).forwardBreakGlassAlerts(settings());
        assertTrue(sent.isEmpty());
        assertFalse(fixture.state.containsKey(NotificationForwarder.BREAK_GLASS_WATERMARK_KEY));
    }

    @Test
    void theLoginSecurityMailDigestCarriesTheBreakGlassActions() throws Exception {
        var field = NotificationMailRouter.class.getDeclaredField("LOGIN_ACTIONS");
        field.setAccessible(true);
        String actions = (String) field.get(null);
        assertTrue(actions.contains("'local_break_glass_login_succeeded'"));
        assertTrue(actions.contains("'local_non_break_glass_refused'"));
    }
}
