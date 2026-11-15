package com.securityexpert.nexus.ui2.persistence.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

/** The settings write is one audited transaction under local_password_policy_write; the default is off. */
class JooqLocalPasswordPolicyRepositoryTest {

    private static final class Capture implements TransactionBoundary {
        final List<String> statements = new ArrayList<>();
        final List<Object[]> bindings = new ArrayList<>();
        final DSLContext dsl;

        Capture(LocalPasswordPolicySettings stored) {
            dsl = DSL.using(new MockConnection(context -> {
                statements.add(context.sql());
                bindings.add(context.bindings());
                if (!context.sql().toLowerCase(java.util.Locale.ROOT).startsWith("select")) {
                    return new MockResult[] {new MockResult(1, DSL.using(SQLDialect.POSTGRES).newResult())};
                }
                var create = DSL.using(SQLDialect.POSTGRES);
                Field<Boolean> enabled = DSL.field(DSL.name("enabled"), Boolean.class);
                Field<Integer> min = DSL.field(DSL.name("min_length"), Integer.class);
                Field<Boolean> up = DSL.field(DSL.name("require_upper"), Boolean.class);
                Field<Boolean> lo = DSL.field(DSL.name("require_lower"), Boolean.class);
                Field<Boolean> di = DSL.field(DSL.name("require_digit"), Boolean.class);
                Field<Boolean> sy = DSL.field(DSL.name("require_symbol"), Boolean.class);
                Field<Integer> hist = DSL.field(DSL.name("history_count"), Integer.class);
                Field<Integer> age = DSL.field(DSL.name("max_age_days"), Integer.class);
                Field<OffsetDateTime> at = DSL.field(DSL.name("updated_at"), OffsetDateTime.class);
                Field<String> by = DSL.field(DSL.name("updated_by"), String.class);
                var result = create.newResult(enabled, min, up, lo, di, sy, hist, age, at, by);
                var row = create.newRecord(enabled, min, up, lo, di, sy, hist, age, at, by);
                row.set(enabled, stored.enabled());
                row.set(min, stored.minLength());
                row.set(up, stored.requireUpper());
                row.set(lo, stored.requireLower());
                row.set(di, stored.requireDigit());
                row.set(sy, stored.requireSymbol());
                row.set(hist, stored.historyCount());
                row.set(age, stored.maxAgeDays());
                row.set(at, OffsetDateTime.parse("2026-11-14T09:00:00Z"));
                row.set(by, "af3a9c1e2b7d");
                result.add(row);
                return new MockResult[] {new MockResult(1, result)};
            }), SQLDialect.POSTGRES);
        }

        @Override
        public <T> T inTransaction(Function<DSLContext, T> work) {
            return work.apply(dsl);
        }
    }

    @Test
    void theWriteSetsTheAuditContextBeforeTheUpsertAndReadsTheRowBack() {
        var next = new LocalPasswordPolicySettings(true, 14, true, false, true, false, 5, 90);
        Capture db = new Capture(next);
        var row = new JooqLocalPasswordPolicyRepository(db).write("af3a9c1e2b7d", next);

        assertEquals(4, db.statements.size(), db.statements.toString());
        assertTrue(db.statements.get(0).startsWith("SET LOCAL app.actor_fingerprint = 'af3a9c1e2b7d'"));
        assertEquals("SET LOCAL app.action_id = 'local_password_policy_write'", db.statements.get(1));
        assertEquals("local_password_policy_write", LocalPasswordPolicyRepository.WRITE_ACTION);
        String upsert = db.statements.get(2).toLowerCase(java.util.Locale.ROOT);
        assertTrue(upsert.startsWith("insert into local_password_policy_settings"), upsert);
        assertTrue(upsert.contains("on conflict (settings_id) do update set"), upsert);
        assertEquals(List.of(true, 14, true, false, true, false, 5, 90, "af3a9c1e2b7d"), List.of(db.bindings.get(2)));
        assertEquals(next, row.settings());
        assertEquals(Instant.parse("2026-11-14T09:00:00Z"), row.updatedAt());
    }

    @Test
    void theDefaultsAreOffAtTheOriginalFloor() {
        var d = LocalPasswordPolicySettings.DEFAULTS;
        assertEquals(false, d.enabled());
        assertEquals(12, d.minLength());
        assertEquals(0, d.historyCount());
        assertEquals(0, d.maxAgeDays());
    }

    @Test
    void aBlankActorIsRefusedBeforeAnyStatementRuns() {
        Capture db = new Capture(LocalPasswordPolicySettings.DEFAULTS);
        var repository = new JooqLocalPasswordPolicyRepository(db);
        assertThrows(IllegalArgumentException.class, () -> repository.write(" ", LocalPasswordPolicySettings.DEFAULTS));
        assertTrue(db.statements.isEmpty());
    }
}
