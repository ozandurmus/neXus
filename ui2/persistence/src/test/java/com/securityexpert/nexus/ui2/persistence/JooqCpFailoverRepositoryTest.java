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
    @org.junit.jupiter.api.Test
    void repositoryMutationSwitchDeniesEveryMutationEntryBeforeDatabaseAccess() {
        var repository=new JooqCpFailoverRepository(new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext,T> work) { throw new AssertionError("Database touched"); }
        },false);
        assertEquals("FAILOVER_MUTATION_DISABLED",repository.request("unit",null,java.time.Instant.now(),
            "synthetic-actor","member-a",true).code());
        assertEquals("FAILOVER_MUTATION_DISABLED",repository.startDue("run","member-a"));
        assertEquals("FAILOVER_MUTATION_DISABLED",repository.mutationAdmission("run","unit",null,
            "check_point",java.util.Set.of("member-a","member-b"),true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN_INCIDENT", "FLEET_MUTATION_ACTIVE", "MEMBER_SET_CHANGED"})
    void repositoryRefusesBeforeInsertingARequest(String refusal) {
        var records=DSL.using(SQLDialect.POSTGRES);
        var dsl=DSL.using(new MockConnection(query -> {
            String sql=query.sql();
            if (sql.startsWith("SET LOCAL") || sql.startsWith("select pg_advisory")) return new MockResult[]{new MockResult(0)};
            if (sql.startsWith("select * from failover_approval")) {
                var field=DSL.field("approval_id",String.class);
                var result=records.newResult(field); result.add(records.newRecord(field).value1("approval-a"));
                return new MockResult[]{new MockResult(1,result)};
            }
            if (sql.startsWith("select resolved.device_id")) {
                var id=DSL.field("device_id",String.class); var vendor=DSL.field("vendor_hint",String.class);
                var role=DSL.field("role",String.class); var disabled=DSL.field("disabled",Boolean.class);
                var state=DSL.field("enrollment_state",String.class);
                var result=records.newResult(id,vendor,role,disabled,state);
                for (String member:java.util.List.of("member-a","member-b"))
                    result.add(records.newRecord(id,vendor,role,disabled,state).values(member,"check_point","gateway",false,"ENROLLED"));
                return new MockResult[]{new MockResult(2,result)};
            }
            assertTrue(sql.startsWith("select"),"A refused request must not write");
            var field=DSL.field("run_id",String.class); var result=records.newResult(field);
            if ((sql.contains("from failover_quarantine") && refusal.equals("OPEN_INCIDENT"))
                    || (sql.startsWith("select 1 from failover_run") && refusal.equals("FLEET_MUTATION_ACTIVE")))
                result.add(records.newRecord(field).value1("existing"));
            return new MockResult[]{new MockResult(result.size(),result)};
        }),SQLDialect.POSTGRES);
        var repository=new JooqCpFailoverRepository(new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext,T> work) { return work.apply(dsl); }
        },true);
        var expected=refusal.equals("MEMBER_SET_CHANGED")?java.util.Set.of("member-a","member-old"):
            java.util.Set.of("member-a","member-b");
        assertEquals(refusal,repository.requestBound("unit-a",null,java.time.Instant.now(),"synthetic-actor",
            "member-a",true,"check_point",expected).code());
    }

    @org.junit.jupiter.api.Test
    void readinessAggregatesChecksForLatestRunsInOneQuery() {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var dsl = DSL.using(new MockConnection(query -> {
            count.incrementAndGet();
            String sql = query.sql();
            assertTrue(sql.contains("distinct on (vendor,cluster_ref,coalesce(vs_id,''))"));
            assertTrue(sql.contains("join latest l on l.run_id=c.run_id"));
            assertTrue(sql.contains("where c.phase='pre' group by c.run_id"));
            assertTrue(sql.contains("failed_check,message"));
            assertTrue(sql.contains("r.failed_check,r.message"));
            assertTrue(sql.contains("coalesce(c.checks,'[]'::jsonb)"));
            return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES)
                .newResult(DSL.field("cluster_ref",String.class)))};
        }),SQLDialect.POSTGRES);
        var repository = new JooqCpFailoverRepository(new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext,T> work) { return work.apply(dsl); }
        });
        assertTrue(repository.readinessStatuses().isEmpty());
        assertEquals(1,count.get());
    }

    @org.junit.jupiter.api.Test
    void syncBaselineIsScopedToPreviousReadinessUnitMemberAndOpaqueVs() {
        var dsl=DSL.using(new MockConnection(query -> {
            String sql=query.sql();
            assertTrue(sql.contains("r.vendor=current.vendor and r.cluster_ref=current.cluster_ref"));
            assertTrue(sql.contains("r.vs_id is not distinct from current.vs_id"));
            assertTrue(sql.contains("r.run_kind='READINESS'"));
            assertTrue(sql.contains("r.finished_at<=current.started_at"));
            assertTrue(sql.contains("r.run_id<>current.run_id"));
            assertTrue(sql.contains("c.member_ref=? and c.vs_id is not distinct from ?"));
            assertTrue(sql.contains("c.check_no=9 and c.phase='pre'"));
            assertTrue(sql.contains("jsonb_typeof(c.derived->'lostUpdates')='number'"));
            assertTrue(sql.contains("jsonb_typeof(c.derived->'lostBulkUpdateEvents')='number'"));
            assertArrayEquals(new Object[]{"run-current","member-opaque","01"},query.bindings());
            return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("phase",String.class)))};
        }),SQLDialect.POSTGRES);
        var repository=new JooqCpFailoverRepository(new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext,T> work) { return work.apply(dsl); }
        });
        assertTrue(repository.previousReadinessSync("run-current","member-opaque","01").isEmpty());
    }
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
