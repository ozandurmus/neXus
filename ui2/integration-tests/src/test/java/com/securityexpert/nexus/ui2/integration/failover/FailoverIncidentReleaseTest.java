package com.securityexpert.nexus.ui2.integration.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/**
 * V169 on real PostgreSQL (docs/design/PO_DECISION_RECORD_2026_10_20_FAILOVER_PILOT_P0.md): the switch-off-at-UP
 * incident, the audited security_admin release, the estate-wide fence of unresolved sends, and check 15.
 * No worker transport or device is constructed.
 */
class FailoverIncidentReleaseTest {
    private static final String ADMIN = "synthetic-security-admin";
    private static final String REASON = "Former active returned to standby out of band; roles verified";
    private record Execution(String run, String job, long epoch, String member) {}

    @Test void switchOffAtUpAfterConfirmedDownOpensAnIncidentAndTheReleaseIsAuditedOnTheIncident() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_release_switch_off")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-dispatch");
            var e = executing(fixture, repo, "unit-dispatch", members);
            var down = repo.prepareDispatch(e.run(), e.epoch(), 0, e.member(), "cp_failover_down", "operational-state-change");
            assertTrue(repo.dispatch(down, () -> true));
            assertTrue(repo.confirmDispatch(down));
            assertTrue(repo.workerState(e.run(), e.epoch(), "RETURNING", "RETURNING", null, null, null));
            var up = repo.prepareDispatch(e.run(), e.epoch(), 1, e.member(), "cp_failover_up", "operational-state-change");
            assertThrows(JooqCpFailoverRepository.NotSent.class, () -> repo.dispatch(up,
                () -> { throw new JooqCpFailoverRepository.NotSent(); }));
            var run = repo.detail(e.run()).orElseThrow().run();
            assertEquals("STOPPED", run.state());
            assertEquals("MUTATION_DISABLED_BEFORE_SEND", run.outcome());
            // Recovery-contract review §9: the member is left DOWN, so the incident must exist (V169 §3).
            assertEquals(1L, count(fixture, "failover_quarantine where active and execution_id='" + e.run() + "'"));
            assertEquals("OPEN_INCIDENT", request(repo, "unit-dispatch", members).code());
            var listed = repo.openIncidents();
            assertEquals(1, listed.size());
            assertEquals(e.run(), listed.get(0).runId());
            assertTrue(listed.get(0).incidentActive());
            assertFalse(listed.get(0).blocksEstate(), "A confirmed DOWN and a NOT_SENT UP leave no unresolved send");

            long audits = count(fixture, "audit_log where table_name='failover_quarantine'");
            assertEquals(JooqCpFailoverRepository.INCIDENT_RELEASED, repo.releaseIncident(e.run(), ADMIN, REASON));
            var row = mutate(fixture, dsl -> dsl.fetchOne("select active,acknowledged_by,second_approver_id,review_notes,"
                + "acknowledged_at is not null as stamped from failover_quarantine where execution_id={0}", e.run()));
            assertEquals(false, row.get("active", Boolean.class));
            assertEquals(ADMIN, row.get("acknowledged_by", String.class));
            assertEquals(ADMIN, row.get("second_approver_id", String.class));
            assertEquals(REASON, row.get("review_notes", String.class));
            assertEquals(true, row.get("stamped", Boolean.class));
            assertEquals(audits + 1, count(fixture, "audit_log where table_name='failover_quarantine'"));
            var audit = mutate(fixture, dsl -> dsl.fetchOne("select actor_fingerprint,action_id,correlation_run_id,"
                + "after_state->>'review_notes' as notes from audit_log where table_name='failover_quarantine' "
                + "order by audit_id desc limit 1"));
            assertEquals(ADMIN, audit.get("actor_fingerprint", String.class));
            assertEquals("failover_incident_release", audit.get("action_id", String.class));
            assertEquals(e.run(), audit.get("correlation_run_id", String.class));
            assertNotEquals(REASON, audit.get("notes", String.class), "The free-text reason is redacted in audit_log");
            assertEquals(JooqCpFailoverRepository.INCIDENT_NOT_FOUND, repo.releaseIncident(e.run(), ADMIN, REASON));
            assertTrue(repo.openIncidents().isEmpty());
            assertEquals("ADMITTED", request(repo, "unit-dispatch", members).code());
        }
    }

    @Test void stopBeforeAnySendStillOpensNoIncident() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_release_not_sent_first")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-dispatch");
            var e = executing(fixture, repo, "unit-dispatch", members);
            var down = repo.prepareDispatch(e.run(), e.epoch(), 0, e.member(), "cp_failover_down", "operational-state-change");
            assertThrows(JooqCpFailoverRepository.NotSent.class, () -> repo.dispatch(down,
                () -> { throw new JooqCpFailoverRepository.NotSent(); }));
            assertEquals(0L, count(fixture, "failover_quarantine"));
            assertEquals(JooqCpFailoverRepository.INCIDENT_NOT_FOUND, repo.releaseIncident(e.run(), ADMIN, REASON));
        }
    }

    @Test void unresolvedSendsBlockTheEstateUntilTheSameReleaseRecordsThemAsUncertain() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_release_estate")) {
            var repo = repository(fixture);
            var members = pair(fixture, "unit-dispatch"); var others = pair(fixture, "unit-other");
            var e = executing(fixture, repo, "unit-dispatch", members);
            var down = repo.prepareDispatch(e.run(), e.epoch(), 0, e.member(), "cp_failover_down", "operational-state-change");
            assertTrue(repo.dispatch(down, () -> true));
            // The job ends before any observation: V136 stops the run and opens the incident; the send stays unresolved.
            mutate(fixture, dsl -> dsl.execute("update jobs set state='FAILED',outcome='FAILED',finished_at=now() "
                + "where job_id={0}", e.job()));
            assertEquals("STOPPED", repo.detail(e.run()).orElseThrow().run().state());
            assertEquals("UNRESOLVED_DISPATCH", request(repo, "unit-other", others).code());
            // Releasing only the incident row (the pre-V169 path) leaves the estate fenced by the open send.
            Boolean legacyReleased = mutate(fixture, dsl -> dsl.fetchOne("select ui2_release_failover_incident({0},{1},{2},{3},{4}) as r",
                "unit-dispatch", e.run(), ADMIN, ADMIN, REASON).get("r", Boolean.class));
            assertEquals(Boolean.TRUE, legacyReleased);
            assertEquals("UNRESOLVED_DISPATCH", request(repo, "unit-other", others).code());
            var listed = repo.openIncidents();
            assertEquals(1, listed.size());
            assertEquals("UNRESOLVED_DISPATCH", listed.get(0).reason());
            assertFalse(listed.get(0).incidentActive());
            assertTrue(listed.get(0).blocksEstate());
            assertEquals(List.of(down.nonce()), listed.get(0).unresolvedDispatchRefs());

            assertEquals(JooqCpFailoverRepository.INCIDENT_RELEASED, repo.releaseIncident(e.run(), ADMIN, REASON));
            assertEquals("OUTCOME_UNKNOWN", mutate(fixture, dsl -> dsl.fetchOne(
                "select observation from failover_dispatch_intent where nonce={0}", down.nonce()).get(0, String.class)));
            assertEquals("OUTCOME_UNKNOWN", mutate(fixture, dsl -> dsl.fetchOne("select a.outcome from job_step_attempt a "
                + "join failover_dispatch_intent i on i.attempt_id=a.attempt_id where i.nonce={0}", down.nonce()).get(0, String.class)));
            assertTrue(repo.openIncidents().isEmpty());
            assertFalse(repo.dispatch(down, () -> { fail("A released send is never replayed"); return true; }));
            assertEquals("ADMITTED", request(repo, "unit-other", others).code());
        }
    }

    @Test void uncertainSendWithIncidentIsReleasedTogetherAndALiveRunIsRefused() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_release_live")) {
            var repo = repository(fixture);
            var members = pair(fixture, "unit-dispatch"); var others = pair(fixture, "unit-other");
            var e = executing(fixture, repo, "unit-dispatch", members);
            assertEquals(JooqCpFailoverRepository.INCIDENT_RUN_NOT_TERMINAL, repo.releaseIncident(e.run(), ADMIN, REASON));
            var down = repo.prepareDispatch(e.run(), e.epoch(), 0, e.member(), "cp_failover_down", "operational-state-change");
            assertFalse(repo.dispatch(down, () -> false));
            assertEquals("OUTCOME_UNKNOWN", repo.detail(e.run()).orElseThrow().run().outcome());
            assertEquals("OPEN_INCIDENT", request(repo, "unit-dispatch", members).code());
            assertEquals("UNRESOLVED_DISPATCH", request(repo, "unit-other", others).code());
            assertTrue(repo.openIncidents().get(0).blocksEstate());
            assertEquals(JooqCpFailoverRepository.INCIDENT_RELEASED, repo.releaseIncident(e.run(), ADMIN, REASON));
            assertEquals("ADMITTED", request(repo, "unit-other", others).code());
        }
    }

    @Test void recoveryModeCheckFifteenIsStorable() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_check_fifteen")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-dispatch");
            var e = executing(fixture, repo, "unit-dispatch", members);
            repo.check(e.run(), "pre", e.member(), null, 15, "FAIL", "{\"recoveryMode\":\"PRIMARY_UP\"}");
            assertEquals(1L, count(fixture, "failover_check_result where check_no=15"));
            assertThrows(RuntimeException.class, () -> repo.check(e.run(), "pre", e.member(), null, 16, "PASS", "{}"));
        }
    }

    private static Execution executing(Ui2PostgresFixture fixture, JooqCpFailoverRepository repo, String unit,
            Set<String> members) {
        var decision = request(repo, unit, members);
        assertEquals("ADMITTED", decision.code());
        mutate(fixture, dsl -> dsl.execute("update module_runtime_control set owner_instance='general-synthetic',"
            + "owner_heartbeat_at=now() where module='general'"));
        var leases = new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(
            new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)), JobWindowTestPolicy.PERMISSIVE);
        var job = leases.claimNext("general-synthetic", List.of("cp_cluster_failover"), Duration.ofMinutes(10)).orElseThrow();
        assertTrue(leases.transitionState(job.jobId(), job.leaseEpoch(), "CLAIMED", "EXECUTING", Ui2Rows.ACTOR, "synthetic_start"));
        assertTrue(repo.workerState(decision.runId(), job.leaseEpoch(), "FAILING_OVER", "FAILING_OVER", null, null, null));
        return new Execution(decision.runId(), job.jobId(), job.leaseEpoch(), members.iterator().next());
    }
    private static long count(Ui2PostgresFixture fixture, String tableAndFilter) {
        return mutate(fixture, dsl -> dsl.fetchOne("select count(*) from " + tableAndFilter).get(0, Long.class));
    }
    private static JooqCpFailoverRepository repository(Ui2PostgresFixture fixture) {
        return new JooqCpFailoverRepository(new JooqTransactionBoundary(
            DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)), true, JobWindowTestPolicy.PERMISSIVE);
    }
    private static <T> T mutate(Ui2PostgresFixture fixture, Function<DSLContext, T> work) {
        return new AuditedTransactionBoundary(new JooqTransactionBoundary(
            DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)))
            .inTransaction(Ui2Rows.ACTOR, "synthetic_incident_test", work);
    }
    private static Set<String> pair(Ui2PostgresFixture fixture, String unit) throws Exception {
        try (var connection = fixture.appConnection()) {
            String credential = Ui2Rows.insertCredentialReference(connection);
            String a = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
            String b = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
            Ui2Rows.insertEndpoint(connection, a); Ui2Rows.insertEndpoint(connection, b);
            mutate(fixture, dsl -> dsl.execute("update devices set vendor_hint='check_point',role='gateway',"
                + "cluster_member_ref={0} where device_id in ({1},{2})", unit, a, b));
            return Set.of(a, b);
        }
    }
    private static JooqCpFailoverRepository.Decision request(JooqCpFailoverRepository repo, String unit, Set<String> members) {
        var request = repo.createRequestApproval(java.util.UUID.randomUUID().toString(), unit, null, unit, members,
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Synthetic incident release", Ui2Rows.ACTOR,
            "check_point", JooqCpFailoverRepository.ADMIN_SINGLE);
        return repo.requestBound(unit, null, Instant.now(), Ui2Rows.ACTOR, members.iterator().next(), true, "check_point",
            members, request.approval().id(), request.revision(), request.executionNonce(), true, request.policy());
    }
}
