package com.securityexpert.nexus.ui2.persistence;

import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class JooqCpFailoverRepositoryTest {
    @ParameterizedTest
    @ValueSource(strings = {"READINESS", "FAILOVER"})
    void readinessRefusesEitherActiveRunKindBeforeAnyInsert(String activeKind) {
        var records = DSL.using(SQLDialect.POSTGRES);
        var runId = DSL.field("run_id", String.class);
        var active = records.newResult(runId);
        active.add(records.newRecord(runId).value1("synthetic-active-" + activeKind));
        var dsl = DSL.using(new MockConnection(query -> {
            String sql = query.sql();
            if (sql.startsWith("SET LOCAL")) return new MockResult[] {new MockResult(0)};
            assertTrue(sql.startsWith("select run_id from failover_run"), "No admission writes for an active unit");
            assertTrue(sql.contains("cluster_ref=?"));
            assertTrue(sql.contains("vs_id is not distinct from ?"));
            assertTrue(sql.contains("vendor=?"));
            assertTrue(sql.contains("state not in ('DONE','STOPPED')"));
            assertFalse(sql.contains("run_kind"), "Both run kinds must share the active-unit fence");
            assertArrayEquals(new Object[] {"CLS-TEST-01", null, "check_point"}, query.bindings());
            return new MockResult[] {new MockResult(1, active)};
        }), SQLDialect.POSTGRES);
        var repository = new JooqCpFailoverRepository(new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext,T> work) {
                return work.apply(dsl);
            }
        });
        var decision = repository.requestReadiness("CLS-TEST-01", null, "actor-1", "FW-TEST-01", "check_point");
        assertEquals("RUN_ALREADY_ACTIVE", decision.code());
        assertNull(decision.runId());
    }
}
