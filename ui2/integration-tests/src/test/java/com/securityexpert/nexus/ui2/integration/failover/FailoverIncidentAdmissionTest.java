package com.securityexpert.nexus.ui2.integration.failover;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL admission/incident transactions; no worker transport or device is constructed. */
class FailoverIncidentAdmissionTest {
    private record Execution(String run,String job,long epoch,String member) {}
    private static Execution executing(Ui2PostgresFixture fixture,JooqCpFailoverRepository repo,Set<String> members) {
        var decision=request(repo,"unit-dispatch",null,"check_point",members);
        assertEquals("ADMITTED",decision.code());
        mutate(fixture,dsl -> dsl.execute("update module_runtime_control set owner_instance='general-synthetic',"
            + "owner_heartbeat_at=now() where module='general'"));
        var leases=new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(
            new JooqTransactionBoundary(DSL.using(fixture.appDataSource(),SQLDialect.POSTGRES)));
        var job=leases.claimNext("general-synthetic",List.of("cp_cluster_failover"),Duration.ofMinutes(10)).orElseThrow();
        assertTrue(leases.transitionState(job.jobId(),job.leaseEpoch(),"CLAIMED","EXECUTING",Ui2Rows.ACTOR,"synthetic_start"));
        assertTrue(repo.workerState(decision.runId(),job.leaseEpoch(),"FAILING_OVER","FAILING_OVER",null,null,null));
        return new Execution(decision.runId(),job.jobId(),job.leaseEpoch(),members.iterator().next());
    }
    private static JooqCpFailoverRepository.Dispatch prepare(JooqCpFailoverRepository repo,Execution e) {
        return repo.prepareDispatch(e.run(),e.epoch(),0,e.member(),"cp_failover_down","operational-state-change");
    }
    private static long count(Ui2PostgresFixture fixture,String table) {
        return mutate(fixture,dsl -> dsl.fetchOne("select count(*) from "+table).get(0,Long.class));
    }
    private static void expire(Ui2PostgresFixture fixture,Execution e) {
        mutate(fixture,dsl -> dsl.execute("update jobs set lease_expires_at=now()-interval '1 second' where job_id={0}",e.job()));
    }
    private static JooqCpFailoverRepository fault(Ui2PostgresFixture fixture,String prefix,boolean after) {
        var fired=new java.util.concurrent.atomic.AtomicBoolean();
        var listener=new org.jooq.impl.DefaultExecuteListener() {
            private void fail(org.jooq.ExecuteContext ctx,boolean end) {
                if (after==end && ctx.sql()!=null && ctx.sql().startsWith(prefix) && fired.compareAndSet(false,true))
                    throw new IllegalStateException("SYNTHETIC_DB_FAILURE");
            }
            @Override public void executeStart(org.jooq.ExecuteContext ctx) { fail(ctx,false); }
            @Override public void executeEnd(org.jooq.ExecuteContext ctx) { fail(ctx,true); }
        };
        var db=DSL.using(fixture.appDataSource(),SQLDialect.POSTGRES);
        return new JooqCpFailoverRepository(new JooqTransactionBoundary(DSL.using(db.configuration()
            .derive(new org.jooq.impl.DefaultExecuteListenerProvider(listener)))),true);
    }

    @Test void intentAttemptStateAndAuditRollbackTogetherBeforeSend() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_intent_faults")) {
            var repo=repository(fixture); var members=pair(fixture,"unit-dispatch","check_point");
            approve(repo,"unit-dispatch",null,"check_point");
            for (String write:List.of("insert into job_step_attempt","insert into failover_dispatch_intent",
                    "update failover_run set mutation_possible")) for (boolean after:List.of(false,true)) {
                var e=executing(fixture,repo,members);
                long audits=count(fixture,"audit_log");
                assertThrows(RuntimeException.class,() -> prepare(fault(fixture,write,after),e));
                assertEquals(0,count(fixture,"job_step_attempt"));
                assertEquals(0,count(fixture,"failover_dispatch_intent"));
                assertEquals(audits,count(fixture,"audit_log"));
                assertFalse(mutate(fixture,dsl -> dsl.fetchOne("select mutation_possible from failover_run where run_id={0}",e.run())
                    .get(0,Boolean.class)));
                assertTrue(repo.workerState(e.run(),e.epoch(),"STOPPED","PRECHECK","SYNTHETIC_STOP",null,null));
                assertEquals(0,count(fixture,"failover_quarantine"));
            }
        }
    }

    @Test void lostReplyRuntimeFailureAndPersistenceFailureNeverReplayAcrossRestart() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_reply_faults")) {
            var repo=repository(fixture); var members=pair(fixture,"unit-dispatch","check_point");
            approve(repo,"unit-dispatch",null,"check_point");
            for (String write:List.of("update failover_dispatch_intent set dispatch_claimed",
                    "update failover_dispatch_intent set delivery","update job_step_attempt set outcome"))
                for (boolean after:List.of(false,true)) {
                    var e=executing(fixture,repo,members); var intent=prepare(repo,e);
                    var sent=new java.util.concurrent.atomic.AtomicInteger();
                    assertThrows(RuntimeException.class,() -> fault(fixture,write,after).dispatch(intent,() -> {
                        sent.incrementAndGet(); return true;
                    }));
                    assertEquals(write.contains("dispatch_claimed")?0:1,sent.get());
                    var restarted=repository(fixture);
                    assertEquals(0,restarted.reconcileDispatches(),"Unexpired work belongs to the live owner");
                    assertThrows(RuntimeException.class,() -> prepare(restarted,e),"The request cannot create another intent");
                    expire(fixture,e);
                    assertEquals(1,restarted.reconcileDispatches());
                    assertEquals(0,restarted.reconcileDispatches());
                    assertEquals("OUTCOME_UNKNOWN",restarted.detail(e.run()).orElseThrow().run().outcome());
                    assertFalse(restarted.dispatch(intent,() -> {fail("Uncertain dispatch was replayed"); return true;}));
                    assertEquals("UNRESOLVED_DISPATCH",request(restarted,"unit-dispatch",null,"check_point",members).code());
                    assertTrue(release(fixture,"unit-dispatch",e.run()));
                }
            for (boolean runtimeError:List.of(false,true)) {
                var e=executing(fixture,repo,members); var intent=prepare(repo,e);
                assertFalse(repo.dispatch(intent,() -> {if (runtimeError) throw new IllegalStateException("SYNTHETIC_LOST_REPLY"); return false;}));
                assertEquals("OUTCOME_UNKNOWN",repo.detail(e.run()).orElseThrow().run().outcome());
                assertFalse(repo.dispatch(intent,() -> {fail("Repeated send"); return true;}));
                assertTrue(release(fixture,"unit-dispatch",e.run()));
            }
        }
    }

    @Test void replyIsNotObservationAndStaleEpochOrModuleOwnerCannotSend() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_fencing")) {
            var repo=repository(fixture); var members=pair(fixture,"unit-dispatch","check_point");
            approve(repo,"unit-dispatch",null,"check_point");
            var e=executing(fixture,repo,members); var intent=prepare(repo,e);
            assertThrows(RuntimeException.class,() -> repo.prepareDispatch(e.run(),e.epoch()+1,1,e.member(),"cp_failover_up","operational-state-change"));
            assertFalse(repo.dispatch(new JooqCpFailoverRepository.Dispatch(intent.nonce(),e.run(),e.job(),e.epoch()+1),
                () -> {fail("Stale lease epoch sent"); return true;}));
            assertFalse(repo.workerState(e.run(),e.epoch()+1,"DONE","DONE","SUCCEEDED",null,null));
            assertTrue(repo.dispatch(intent,() -> true));
            assertEquals("NOT_OBSERVED",mutate(fixture,dsl -> dsl.fetchOne("select observation from failover_dispatch_intent where nonce={0}",intent.nonce()).get(0,String.class)));
            assertThrows(RuntimeException.class,() -> repo.workerState(e.run(),e.epoch(),"DONE","DONE","SUCCEEDED",null,null));
            assertFalse(repo.dispatch(intent,() -> {fail("Reply must not allow replay"); return true;}));
            assertTrue(repo.confirmDispatch(intent));
            assertTrue(repo.workerState(e.run(),e.epoch(),"RETURNING","RETURNING",null,null,null));
            var returning=repo.prepareDispatch(e.run(),e.epoch(),1,e.member(),"cp_failover_up","operational-state-change");
            mutate(fixture,dsl -> dsl.execute("update module_runtime_control set owner_instance='general-other' where module='general'"));
            assertFalse(repo.dispatch(returning,() -> {fail("Stale module owner sent"); return true;}));
            assertFalse(repo.workerState(e.run(),e.epoch(),"DONE","DONE","SUCCEEDED",null,null));
            assertEquals(0,repo.reconcileDispatches(),"Ownership uncertainty does not steal a live lease");
            expire(fixture,e);
            assertEquals(1,repo.reconcileDispatches());
            assertEquals(1,count(fixture,"failover_quarantine"));
        }
    }

    @Test void secondReplicaCannotReconcileOrReplayAnInFlightDispatch() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_inflight")) {
            var repo=repository(fixture); var members=pair(fixture,"unit-dispatch","check_point");
            approve(repo,"unit-dispatch",null,"check_point");
            var e=executing(fixture,repo,members); var intent=prepare(repo,e);
            var entered=new CountDownLatch(1); var resume=new CountDownLatch(1);
            var pool=Executors.newSingleThreadExecutor();
            try {
                var sender=pool.submit(() -> repo.dispatch(intent,() -> {
                    entered.countDown();
                    try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                    return true;
                }));
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                expire(fixture,e);
                var other=repository(fixture);
                var generic=new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(),SQLDialect.POSTGRES)));
                assertTrue(generic.findExpiredUnconfirmedYes().isEmpty());
                assertFalse(generic.transitionState(e.job(),e.epoch(),"EXECUTING","OUTCOME_UNKNOWN",
                    Ui2Rows.ACTOR,"job_reconcile_outcome_unknown"));
                assertEquals(0,other.reconcileDispatches(),"The sender still owns the dispatch critical section");
                assertFalse(other.dispatch(intent,() -> {fail("Second replica sent"); return true;}));
                assertFalse(other.workerState(e.run(),e.epoch(),"STOPPED","STOPPED","SYNTHETIC",null,null));
                resume.countDown(); assertTrue(sender.get(10,TimeUnit.SECONDS));
                assertEquals(1,other.reconcileDispatches());
                assertEquals(1,count(fixture,"failover_quarantine"));
            } finally { resume.countDown(); pool.shutdownNow(); }
        }
    }

    @Test void terminalStateAttemptAndIncidentAreAtomicUnderFaults() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_terminal_faults")) {
            var repo=repository(fixture); var members=pair(fixture,"unit-dispatch","check_point");
            approve(repo,"unit-dispatch",null,"check_point");
            for (String write:List.of("update failover_dispatch_intent set observation",
                    "update job_step_attempt set outcome","update failover_run set state='STOPPED'",
                    "update jobs set state='OUTCOME_UNKNOWN'")) for (boolean after:List.of(false,true)) {
                var e=executing(fixture,repo,members); prepare(repo,e);
                long incidents=count(fixture,"failover_quarantine"), audits=count(fixture,"audit_log");
                assertThrows(RuntimeException.class,() -> fault(fixture,write,after)
                    .workerState(e.run(),e.epoch(),"STOPPED","STOPPED","SYNTHETIC",null,"SYNTHETIC"));
                assertEquals(incidents,count(fixture,"failover_quarantine"));
                assertEquals(audits,count(fixture,"audit_log"));
                assertEquals("FAILING_OVER",repo.detail(e.run()).orElseThrow().run().state());
                expire(fixture,e); assertEquals(1,repo.reconcileDispatches());
                assertTrue(release(fixture,"unit-dispatch",e.run()));
            }
        }
    }

    @Test void observationAndSuccessfulStateUpdatesRollbackWithTheirAudit() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_observation_faults")) {
            var repo=repository(fixture); var members=pair(fixture,"unit-dispatch","check_point");
            approve(repo,"unit-dispatch",null,"check_point");
            var e=executing(fixture,repo,members); var intent=prepare(repo,e);
            assertTrue(repo.dispatch(intent,() -> true));
            for (boolean after:List.of(false,true)) {
                long audits=count(fixture,"audit_log");
                assertThrows(RuntimeException.class,() -> fault(fixture,
                    "update failover_dispatch_intent set observation='CONFIRMED'",after).confirmDispatch(intent));
                assertEquals(audits,count(fixture,"audit_log"));
                assertEquals("NOT_OBSERVED",mutate(fixture,dsl -> dsl.fetchOne(
                    "select observation from failover_dispatch_intent where nonce={0}",intent.nonce()).get(0,String.class)));
            }
            assertTrue(repo.confirmDispatch(intent));
            for (String write:List.of("update failover_run set state=","update jobs set state="))
                for (boolean after:List.of(false,true)) {
                    long audits=count(fixture,"audit_log");
                    assertThrows(RuntimeException.class,() -> fault(fixture,write,after)
                        .workerState(e.run(),e.epoch(),"DONE","DONE","SUCCEEDED",null,null));
                    assertEquals(audits,count(fixture,"audit_log"));
                    assertEquals("FAILING_OVER",repo.detail(e.run()).orElseThrow().run().state());
                }
            assertTrue(repo.workerState(e.run(),e.epoch(),"DONE","DONE","SUCCEEDED",null,null));
            assertEquals(0,repo.reconcileDispatches());
            assertEquals(0,count(fixture,"failover_quarantine"));
        }
    }
    private static JooqCpFailoverRepository repository(Ui2PostgresFixture fixture) {
        return new JooqCpFailoverRepository(new JooqTransactionBoundary(
            DSL.using(fixture.appDataSource(),SQLDialect.POSTGRES)),true);
    }
    private static <T> T mutate(Ui2PostgresFixture fixture,Function<DSLContext,T> work) {
        return new AuditedTransactionBoundary(new JooqTransactionBoundary(
            DSL.using(fixture.appDataSource(),SQLDialect.POSTGRES)))
            .inTransaction(Ui2Rows.ACTOR,"synthetic_incident_test",work);
    }
    private static Set<String> pair(Ui2PostgresFixture fixture,String unit,String vendor) throws Exception {
        try (var connection=fixture.appConnection()) {
            String credential=Ui2Rows.insertCredentialReference(connection);
            String a=Ui2Rows.insertDevice(connection,credential,"ENROLLED");
            String b=Ui2Rows.insertDevice(connection,credential,"ENROLLED");
            Ui2Rows.insertEndpoint(connection,a); Ui2Rows.insertEndpoint(connection,b);
            mutate(fixture,dsl -> dsl.execute("update devices set vendor_hint={0},role='gateway',cluster_member_ref={1} "
                + "where device_id in ({2},{3})",vendor,unit,a,b));
            return Set.of(a,b);
        }
    }
    private static JooqCpFailoverRepository.Decision request(JooqCpFailoverRepository repo,String unit,
            String vs,String vendor,Set<String> members) {
        var request=repo.createRequestApproval(java.util.UUID.randomUUID().toString(),unit,vs,unit,members,
            Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),"Synthetic incident admission",Ui2Rows.ACTOR,
            vendor,JooqCpFailoverRepository.ADMIN_SINGLE);
        return repo.requestBound(unit,vs,Instant.now(),Ui2Rows.ACTOR,members.iterator().next(),true,vendor,members,
            request.approval().id(),request.revision(),request.executionNonce(),true,request.policy());
    }
    private static boolean release(Ui2PostgresFixture fixture,String unit,String incident) {
        return mutate(fixture,dsl -> dsl.fetchOne("select ui2_release_failover_incident({0},{1},{2},{3},{4}) as released",
            unit,incident,"synthetic-reviewer-a","synthetic-reviewer-b","Reviewed synthetic incident").get("released",Boolean.class));
    }

    @Test void atomicIncidentSurvivesRestartAndBlocksStoppedUnitAndSharedPhysicalMember() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_incidents")) {
            var repo=repository(fixture);
            Set<String> members=pair(fixture,"unit-a","check_point");

            var first=request(repo,"unit-a",null,"check_point",members);
            assertEquals("ADMITTED",first.code());
            assertEquals("WRONG_UNIT",repo.mutationAdmission(first.runId(),"unit-other",null,"check_point",members,true));
            assertEquals("WRONG_UNIT",repo.mutationAdmission(first.runId(),"unit-a","01","check_point",members,true));
            assertEquals(Integer.valueOf(1),mutate(fixture,dsl -> dsl.execute(
                "update failover_run set mutation_possible=true where run_id={0}",first.runId())));

            // An error after the state write rolls back both STOPPED and its incident.
            assertThrows(IllegalStateException.class,() -> mutate(fixture,dsl -> {
                dsl.execute("update failover_run set state='STOPPED' where run_id={0}",first.runId());
                throw new IllegalStateException("synthetic rollback");
            }));
            assertFalse(repo.finished(first.runId()));
            assertEquals(Integer.valueOf(0),mutate(fixture,dsl -> dsl.fetchCount(org.jooq.impl.DSL.table("failover_quarantine"))));
            repo.state(first.runId(),"STOPPED","POSTCHECK","OUTCOME_UNKNOWN",null,"SYNTHETIC_FAILURE");
            assertTrue(repo.finished(first.runId()));

            var restarted=repository(fixture);
            assertEquals("OPEN_INCIDENT",request(restarted,"unit-a",null,"check_point",members).code());
            assertFalse(release(fixture,"unit-a","stale-incident"));
            assertEquals("OPEN_INCIDENT",request(restarted,"unit-a",null,"check_point",members).code());

            // Re-resolving the same physical pair under another unit must not escape an incident.
            mutate(fixture,dsl -> dsl.execute("update devices set cluster_member_ref='unit-alias' where cluster_member_ref='unit-a'"));

            assertEquals("OPEN_INCIDENT",request(restarted,"unit-alias",null,"check_point",members).code());
            assertTrue(release(fixture,"unit-a",first.runId()));
            var second=request(restarted,"unit-alias",null,"check_point",members);
            assertEquals("ADMITTED",second.code());
            assertEquals(Integer.valueOf(1),mutate(fixture,dsl -> dsl.execute(
                "update failover_run set mutation_possible=true where run_id={0}",second.runId())));
            String job=restarted.detail(second.runId()).orElseThrow().run().jobId();
            mutate(fixture,dsl -> dsl.execute("update jobs set state='OUTCOME_UNKNOWN' where job_id={0}",job));
            assertTrue(restarted.finished(second.runId()),"Job failure and incident are one durable transition");
            assertFalse(release(fixture,"unit-a",first.runId()));
            assertEquals("OPEN_INCIDENT",request(repository(fixture),"unit-alias",null,"check_point",members).code());
            assertEquals(Integer.valueOf(2),mutate(fixture,dsl -> dsl.fetchCount(DSL.table("failover_quarantine"))));
            assertThrows(org.jooq.exception.DataAccessException.class,() -> mutate(fixture,dsl ->
                dsl.execute("delete from failover_quarantine where execution_id={0}",first.runId())));
        }
    }

    @Test void databaseSerializesCompetingVendorsAndVsUnitsAndRejectsChangedTargets() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_concurrency")) {
            var repo=repository(fixture);
            var cp=pair(fixture,"unit-cp","check_point");
            var pan=pair(fixture,"unit-pan","palo_alto");



            var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
            var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(() -> { ready.countDown(); assertTrue(start.await(10,TimeUnit.SECONDS));
                    return request(repository(fixture),"unit-cp","01","check_point",cp); });
                var b=pool.submit(() -> { ready.countDown(); assertTrue(start.await(10,TimeUnit.SECONDS));
                    return request(repository(fixture),"unit-pan",null,"palo_alto",pan); });
                assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
                var results=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
                assertEquals(1,results.stream().filter(r -> r.code().equals("ADMITTED")).count());
                assertEquals(1,results.stream().filter(r -> r.code().equals("FLEET_MUTATION_ACTIVE")).count());
                var winner=results.stream().filter(r -> r.code().equals("ADMITTED")).findFirst().orElseThrow();
                repo.state(winner.runId(),"STOPPED","PRECHECK","SYNTHETIC_CANCEL",null,null);
            } finally { start.countDown(); pool.shutdownNow(); }
            var active=request(repo,"unit-cp","01","check_point",cp);
            assertEquals("ADMITTED",active.code());
            assertEquals("FLEET_MUTATION_ACTIVE",request(repo,"unit-cp","02","check_point",cp).code());
            assertThrows(org.jooq.exception.DataAccessException.class,() -> mutate(fixture,dsl ->
                dsl.execute("update failover_run set vs_id='02' where run_id={0}",active.runId())));
            String removed=cp.iterator().next();
            mutate(fixture,dsl -> dsl.execute("update devices set disabled=true where device_id={0}",removed));
            assertEquals("APPROVAL_REVOKED",repository(fixture).mutationAdmission(active.runId(),"unit-cp","01","check_point",cp,true));
            repo.state(active.runId(),"STOPPED","PRECHECK","MEMBER_SET_CHANGED",null,null);
            assertEquals(Integer.valueOf(0),mutate(fixture,dsl -> dsl.fetchCount(DSL.table("failover_quarantine"))));
        }
    }
}
