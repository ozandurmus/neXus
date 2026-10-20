package com.securityexpert.nexus.ui2.persistence.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.Settings;

/**
 * The settings write is one audited transaction: {@code SET LOCAL app.actor_fingerprint} and
 * {@code SET LOCAL app.action_id = 'system_log_settings_write'} run before the upsert, the upsert binds every column
 * plus the actor as {@code updated_by}, and the row is read back inside the same transaction. The read binds nothing
 * and selects the one row.
 */
class JooqSystemLogSettingsRepositoryTest {

    private static final class Capture implements TransactionBoundary {
        final List<String> statements = new ArrayList<>();
        final List<Object[]> bindings = new ArrayList<>();
        final DSLContext dsl;

        Capture(Function<org.jooq.tools.jdbc.MockExecuteContext, MockResult[]> answer) {
            dsl = DSL.using(new MockConnection(context -> {
                statements.add(context.sql());
                bindings.add(context.bindings());
                return answer.apply(context);
            }), SQLDialect.POSTGRES);
        }

        @Override
        public <T> T inTransaction(Function<DSLContext, T> work) {
            return work.apply(dsl);
        }
    }

    private static MockResult[] storedRow(org.jooq.tools.jdbc.MockExecuteContext context, Settings s, String by) {
        if (!context.sql().toLowerCase(java.util.Locale.ROOT).startsWith("select")) {
            return new MockResult[] {new MockResult(1, DSL.using(SQLDialect.POSTGRES).newResult())};
        }
        Field<Integer> raw = DSL.field(DSL.name("raw_retention_days"), Integer.class);
        Field<Boolean> mds = DSL.field(DSL.name("include_management_servers"), Boolean.class);
        Field<Long> cap = DSL.field(DSL.name("device_cap_bytes"), Long.class);
        Field<Integer> hcp = DSL.field(DSL.name("hcp_retention_days"), Integer.class);
        Field<Integer> spikeDays = DSL.field(DSL.name("spike_data_retention_days"), Integer.class);
        Field<Long> spikeCap = DSL.field(DSL.name("spike_data_cap_bytes"), Long.class);
        Field<Integer> ledger = DSL.field(DSL.name("ledger_retention_days"), Integer.class);
        Field<Boolean> spike = DSL.field(DSL.name("spike_data_enabled"), Boolean.class);
        Field<OffsetDateTime> at = DSL.field(DSL.name("updated_at"), OffsetDateTime.class);
        Field<String> updatedBy = DSL.field(DSL.name("updated_by"), String.class);
        var create = DSL.using(SQLDialect.POSTGRES);
        var result = create.newResult(raw, mds, cap, hcp, spikeDays, spikeCap, ledger, spike, at, updatedBy);
        var row = create.newRecord(raw, mds, cap, hcp, spikeDays, spikeCap, ledger, spike, at, updatedBy);
        row.set(raw, s.rawRetentionDays());
        row.set(mds, s.includeManagementServers());
        row.set(cap, s.deviceCapBytes());
        row.set(hcp, s.hcpRetentionDays());
        row.set(spikeDays, s.spikeDataRetentionDays());
        row.set(spikeCap, s.spikeDataCapBytes());
        row.set(ledger, s.ledgerRetentionDays());
        row.set(spike, s.spikeDataEnabled());
        row.set(at, OffsetDateTime.parse("2026-10-20T09:00:00Z"));
        row.set(updatedBy, by);
        result.add(row);
        return new MockResult[] {new MockResult(1, result)};
    }

    @Test
    void theWriteSetsTheAuditContextBeforeTheUpsertAndReadsTheRowBackInTheSameTransaction() {
        Settings next = new Settings(45, true, 4L * 1024 * 1024 * 1024, 7, 30, Settings.DEFAULT_SPIKE_DATA_CAP_BYTES, 180, true);
        Capture db = new Capture(context -> storedRow(context, next, "af3a9c1e2b7d"));
        var repository = new JooqSystemLogSettingsRepository(db);

        var row = repository.write("af3a9c1e2b7d", next);

        assertEquals(4, db.statements.size(), db.statements.toString());
        assertTrue(db.statements.get(0).startsWith("SET LOCAL app.actor_fingerprint = 'af3a9c1e2b7d'"), db.statements.get(0));
        assertEquals("SET LOCAL app.action_id = 'system_log_settings_write'", db.statements.get(1));
        assertEquals("system_log_settings_write", SystemLogSettingsRepository.WRITE_ACTION);
        String upsert = db.statements.get(2).toLowerCase(java.util.Locale.ROOT);
        assertTrue(upsert.startsWith("insert into system_log_settings"), upsert);
        assertTrue(upsert.contains("on conflict (settings_id) do update set"), upsert);
        assertTrue(upsert.contains("updated_at = now()"), upsert);
        assertEquals(List.of(45, 4L * 1024 * 1024 * 1024, 7, 30, Settings.DEFAULT_SPIKE_DATA_CAP_BYTES, 180, true, true, "af3a9c1e2b7d"),
                List.of(db.bindings.get(2)), "every column and the actor as updated_by are bound, never inlined");
        assertTrue(db.statements.get(3).toLowerCase(java.util.Locale.ROOT).startsWith("select raw_retention_days"), db.statements.get(3));
        assertEquals(next, row.settings());
        assertEquals(Instant.parse("2026-10-20T09:00:00Z"), row.updatedAt());
        assertEquals("af3a9c1e2b7d", row.updatedBy());
    }

    @Test
    void theReadIsOneBoundlessSelectOfTheDefaultRowAndAMissingRowAnswersTheDefaults() {
        Capture stored = new Capture(context -> storedRow(context, Settings.DEFAULTS, "system-log-migration"));
        var row = new JooqSystemLogSettingsRepository(stored).read();
        assertEquals(1, stored.statements.size());
        assertTrue(stored.statements.get(0).contains("where settings_id = 'default'"));
        assertEquals(0, stored.bindings.get(0).length);
        assertEquals(Settings.DEFAULTS, row.settings());
        assertEquals("system-log-migration", row.updatedBy());

        Capture empty = new Capture(context -> new MockResult[] {new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())});
        var missing = new JooqSystemLogSettingsRepository(empty).read();
        assertEquals(Settings.DEFAULTS, missing.settings());
        assertNull(missing.updatedAt());
        assertNull(missing.updatedBy());
    }

    @Test
    void aBlankActorIsRefusedBeforeAnyStatementRuns() {
        Capture db = new Capture(context -> storedRow(context, Settings.DEFAULTS, ""));
        var repository = new JooqSystemLogSettingsRepository(db);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> repository.write(" ", Settings.DEFAULTS));
        assertTrue(db.statements.isEmpty());
    }
}
