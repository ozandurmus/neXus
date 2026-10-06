package com.securityexpert.nexus.ui2.integration.failover;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL admission/incident transactions; no worker transport or device is constructed. */
class FailoverIncidentAdmissionTest {
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
    private static void approve(JooqCpFailoverRepository repo,String unit,String vs,String vendor) {
        repo.createApproval(unit,vs,Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),
            "Synthetic incident admission",Ui2Rows.ACTOR,vendor);
    }
    private static JooqCpFailoverRepository.Decision request(JooqCpFailoverRepository repo,String unit,
            String vs,String vendor,Set<String> members) {
        return repo.requestBound(unit,vs,Instant.now(),Ui2Rows.ACTOR,members.iterator().next(),true,vendor,members);
    }
    private static boolean release(Ui2PostgresFixture fixture,String unit,String incident) {
        return mutate(fixture,dsl -> dsl.fetchOne("select ui2_release_failover_incident({0},{1},{2},{3},{4}) as released",
            unit,incident,"synthetic-reviewer-a","synthetic-reviewer-b","Reviewed synthetic incident").get("released",Boolean.class));
    }

    @Test void atomicIncidentSurvivesRestartAndBlocksStoppedUnitAndSharedPhysicalMember() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_incidents")) {
            var repo=repository(fixture);
            Set<String> members=pair(fixture,"unit-a","check_point");
            approve(repo,"unit-a",null,"check_point");
            var first=request(repo,"unit-a",null,"check_point",members);
            assertEquals("ADMITTED",first.code());
            assertEquals("WRONG_UNIT",repo.mutationAdmission(first.runId(),"unit-other",null,"check_point",members,true));
            assertEquals("WRONG_UNIT",repo.mutationAdmission(first.runId(),"unit-a","01","check_point",members,true));
            assertEquals("ADMITTED",repo.mutationAdmission(first.runId(),"unit-a",null,"check_point",members,true));

            // An error after the state write rolls back both STOPPED and its incident.
            assertThrows(IllegalStateException.class,() -> mutate(fixture,dsl -> {
                dsl.execute("update failover_run set state='STOPPED' where run_id={0}",first.runId());
                throw new IllegalStateException("synthetic rollback");
            }));
            assertFalse(repo.finished(first.runId()));
            assertEquals(0,mutate(fixture,dsl -> dsl.fetchCount(org.jooq.impl.DSL.table("failover_quarantine"))));
            repo.state(first.runId(),"STOPPED","POSTCHECK","OUTCOME_UNKNOWN",null,"SYNTHETIC_FAILURE");
            assertTrue(repo.finished(first.runId()));

            var restarted=repository(fixture);
            assertEquals("OPEN_INCIDENT",request(restarted,"unit-a",null,"check_point",members).code());
            assertFalse(release(fixture,"unit-a","stale-incident"));
            assertEquals("OPEN_INCIDENT",request(restarted,"unit-a",null,"check_point",members).code());

            // Re-resolving the same physical pair under another unit must not escape an incident.
            mutate(fixture,dsl -> dsl.execute("update devices set cluster_member_ref='unit-alias' where cluster_member_ref='unit-a'"));
            approve(restarted,"unit-alias",null,"check_point");
            assertEquals("OPEN_INCIDENT",request(restarted,"unit-alias",null,"check_point",members).code());
            assertTrue(release(fixture,"unit-a",first.runId()));
            var second=request(restarted,"unit-alias",null,"check_point",members);
            assertEquals("ADMITTED",second.code());
            assertEquals("ADMITTED",restarted.mutationAdmission(second.runId(),"unit-alias",null,"check_point",members,true));
            String job=restarted.detail(second.runId()).orElseThrow().run().jobId();
            mutate(fixture,dsl -> dsl.execute("update jobs set state='OUTCOME_UNKNOWN' where job_id={0}",job));
            assertTrue(restarted.finished(second.runId()),"Job failure and incident are one durable transition");
            assertFalse(release(fixture,"unit-a",first.runId()));
            assertEquals("OPEN_INCIDENT",request(repository(fixture),"unit-alias",null,"check_point",members).code());
            assertEquals(2,mutate(fixture,dsl -> dsl.fetchCount(DSL.table("failover_quarantine"))));
            assertThrows(org.jooq.exception.DataAccessException.class,() -> mutate(fixture,dsl ->
                dsl.execute("delete from failover_quarantine where execution_id={0}",first.runId())));
        }
    }

    @Test void databaseSerializesCompetingVendorsAndVsUnitsAndRejectsChangedTargets() throws Exception {
        try (var fixture=Ui2PostgresFixture.createAndMigrate("failover_concurrency")) {
            var repo=repository(fixture);
            var cp=pair(fixture,"unit-cp","check_point");
            var pan=pair(fixture,"unit-pan","palo_alto");
            approve(repo,"unit-cp","01","check_point");
            approve(repo,"unit-cp","02","check_point");
            approve(repo,"unit-pan",null,"palo_alto");
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
            assertEquals("MEMBER_SET_CHANGED",repository(fixture).mutationAdmission(active.runId(),"unit-cp","01","check_point",cp,true));
            repo.state(active.runId(),"STOPPED","PRECHECK","MEMBER_SET_CHANGED",null,null);
            assertEquals(0,mutate(fixture,dsl -> dsl.fetchCount(DSL.table("failover_quarantine"))));
        }
    }
}
