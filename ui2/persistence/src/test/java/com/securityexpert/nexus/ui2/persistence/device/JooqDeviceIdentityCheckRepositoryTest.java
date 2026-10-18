package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceIdentityCheck.State;

/** The read side of the header's identity badge: every device appears, with or without a recorded comparison. */
class JooqDeviceIdentityCheckRepositoryTest {
    private final List<String> sql = new ArrayList<>();

    private JooqDeviceIdentityCheckRepository repository() {
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            var rows = DSL.using(SQLDialect.POSTGRES).fetchFromStringData(new String[][]{
                {"device_id", "identity_mismatch_state", "transport", "verdict", "checked_at"},
                {"matched", "NONE", "SSH_HOST_KEY", "MATCH", "2026-10-18 06:13:03+00"},
                {"changed", "NONE", "HTTPS_CERTIFICATE", "MISMATCH", "2026-10-18 06:13:03+00"},
                {"never", "NONE", null, null, null},
                {"conflict", "OPEN", null, null, null}});
            return new MockResult[]{new MockResult(rows.size(), rows)};
        }), SQLDialect.POSTGRES);
        return new JooqDeviceIdentityCheckRepository(new TransactionBoundary() {
            public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(dsl); }
        });
    }

    @Test
    void everyDeviceIsReadEvenWhenNoComparisonWasRecordedForIt() {
        var all = repository().findAll();
        assertEquals(State.VERIFIED, all.get("matched").state());
        assertEquals(State.MISMATCH, all.get("changed").state());
        assertEquals(State.NOT_VERIFIED, all.get("never").state());
        assertEquals(State.MISMATCH, all.get("conflict").state());
        assertTrue(sql.getFirst().contains("from devices d left join device_identity_check c"),
                "a left join keeps a device that was never compared");
    }

    @Test
    void theRecordedTimeIsCarriedForTheHeaderTooltip() {
        var matched = repository().findAll().get("matched");
        assertEquals("2026-10-18T06:13:03Z", matched.checkedAt().orElseThrow().toString());
    }
}
