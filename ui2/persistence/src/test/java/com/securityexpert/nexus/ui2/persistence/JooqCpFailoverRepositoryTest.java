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
        assertThrows(IllegalStateException.class,() -> repository.prepareDispatch("run",1,0,
            "member-a","cp_failover_down","operational-state-change"));
        assertThrows(IllegalStateException.class,() -> repository.dispatch(
            new JooqCpFailoverRepository.Dispatch("nonce","run","job",1),() -> {fail("Transport touched"); return true;}));
    }

    @org.junit.jupiter.api.Test
    void notSentCommitsClosedIntentAndTerminalRunInTheSameTransaction() {
        var db=new DispatchDatabase();
        assertThrows(JooqCpFailoverRepository.NotSent.class,() -> db.repository.dispatch(db.intent,
            () -> { throw new JooqCpFailoverRepository.NotSent(); }));
        assertEquals(2,db.transactions);
        assertTrue(db.writes.stream().allMatch(sql -> sql.startsWith("2:")));
        assertTrue(db.writes.stream().anyMatch(sql -> sql.contains("delivery='NOT_SENT'")));
        assertTrue(db.writes.stream().anyMatch(sql -> sql.contains("state='STOPPED',outcome='MUTATION_DISABLED_BEFORE_SEND'")));
        assertTrue(db.writes.stream().noneMatch(sql -> sql.contains("OUTCOME_UNKNOWN") || sql.contains("set observation=")));
    }

    @org.junit.jupiter.api.Test
    void transportExceptionStillPersistsUnknownDeliveryAndOutcome() {
        var db=new DispatchDatabase();
        assertFalse(db.repository.dispatch(db.intent,() -> { throw new IllegalStateException("SYNTHETIC_TRANSPORT_FAILURE"); }));
        assertTrue(db.writes.stream().anyMatch(sql -> sql.contains("set observation='OUTCOME_UNKNOWN'")));
        assertTrue(db.writes.stream().anyMatch(sql -> sql.contains("state='STOPPED',outcome='OUTCOME_UNKNOWN'")));
        assertTrue(db.writes.stream().noneMatch(sql -> sql.contains("delivery='NOT_SENT'")));
    }

    private static final class DispatchDatabase implements TransactionBoundary {
        final JooqFailoverApprovalTest.Database admission=new JooqFailoverApprovalTest.Database();
        final java.util.List<String> writes=new java.util.ArrayList<>();
        final JooqCpFailoverRepository.Dispatch intent=new JooqCpFailoverRepository.Dispatch("nonce-a","run-a","job-a",1);
        final JooqCpFailoverRepository repository=new JooqCpFailoverRepository(this,true);
        int transactions;
        final DSLContext sql=DSL.using(new MockConnection(query -> {
            String statement=query.sql();
            if (statement.startsWith("select pg_try_advisory")) return row(java.util.Map.of("acquired",true));
            if (statement.startsWith("select j.job_id")) return row(java.util.Map.of("job_id","job-a"));
            if (statement.startsWith("select * from failover_dispatch_intent")) return row(java.util.Map.of(
                "attempt_id","attempt-a","step","FAILING_OVER","member_ref","member-a",
                "owner_instance","general-synthetic","owner_generation",1L));
            if (statement.startsWith("select r.* from failover_run")) {
                admission.run.put("step","FAILING_OVER");
                return row(admission.run);
            }
            if (statement.startsWith("update failover_dispatch_intent set dispatch_claimed")) return new MockResult[]{new MockResult(1)};
            if (statement.startsWith("update ")) {
                writes.add(transactions+":"+statement);
                return new MockResult[]{new MockResult(1)};
            }
            if (statement.startsWith("select 1 from failover_dispatch_intent"))
                assertTrue(statement.contains("delivery<>'NOT_SENT'"),"Closed cancellations must not fence admission");
            return admission.execute(query);
        }),SQLDialect.POSTGRES);
        @Override public <T> T inTransaction(Function<DSLContext,T> work) {
            transactions++;
            return work.apply(sql);
        }
        private MockResult[] row(java.util.Map<String,Object> values) {
            var records=DSL.using(SQLDialect.POSTGRES);
            org.jooq.Field<?>[] fields=values.entrySet().stream().map(e -> DSL.field(e.getKey(),
                e.getValue()==null?String.class:e.getValue().getClass())).toArray(org.jooq.Field[]::new);
            var rows=records.newResult(fields);
            var record=records.newRecord(fields); record.fromArray(values.values().toArray()); rows.add(record);
            return new MockResult[]{new MockResult(1,rows)};
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "true,false,false,OPEN_INCIDENT",
        "true,true,false,OPEN_INCIDENT",
        "true,true,true,OPEN_INCIDENT",
        "false,true,false,UNRESOLVED_DISPATCH",
        "false,false,false,FLEET_MUTATION_ACTIVE"
    })
    void repositoryRefusesBeforeInsertingARequest(boolean incident, boolean unresolved,
            boolean relatedDispatch, String expected) {
        var db = new JooqFailoverApprovalTest.Database();
        db.incident = incident ? "incident-a" : null;
        db.unresolvedDispatch = unresolved;
        db.dispatchRef = relatedDispatch ? "dispatch-a" : null;
        db.fleetMutation = true;
        var decision = db.start("nonce-a");
        assertEquals(expected, decision.code());
        assertEquals(relatedDispatch ? "dispatch-a" : null, decision.dispatchRef());
        assertNull(decision.runId());
        assertEquals(expected, db.dispatch(), "Worker admission must use the same cause precedence");
        assertEquals(0, db.insertedRuns);
        assertEquals(0, db.insertedJobs);
        assertEquals(0, db.writes);
    }

    @org.junit.jupiter.api.Test
    void unboundWindowCannotAuthorizeAnotherRun() {
        var repository=new JooqCpFailoverRepository(new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext,T> work) { throw new AssertionError("Database touched"); }
        },true);
        assertEquals("REQUEST_BINDING_REQUIRED",repository.requestBound("unit-a",null,java.time.Instant.now(),
            "synthetic-actor","member-a",true,"check_point",java.util.Set.of("member-a","member-b")).code());
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
        }, false, new com.securityexpert.nexus.ui2.platform.JobWindowPolicy(
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-08T09:00:00Z"), java.time.ZoneOffset.UTC), 60));
        var decision = repository.requestReadiness("CLS-TEST-01", null, "actor-1", "FW-TEST-01", "check_point");
        assertEquals("RUN_ALREADY_ACTIVE", decision.code());
        assertNull(decision.runId());
    }
}
