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
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;

/**
 * V170 on real PostgreSQL (docs/design/CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md, RATIFIED 2026-10-20): the resume
 * request's eligibility and single use, the two-person approval and expiry, the fleet fence in both directions, the
 * once-per-run RETURNING intent (including the NOT_SENT case), owner loss, and that neither the run row nor the
 * incident is changed by a resume. No worker transport or device is constructed.
 */
class FailoverResumeReturnTest {
    private static final String ADMIN = "synthetic-security-admin", OP_A = "synthetic-operation-admin-a",
        OP_B = "synthetic-operation-admin-b", REASON = "Return the former active to standby";
    private record Execution(String run, String job, long epoch, String member) {}
    private record Worker(String job, long epoch) {}

    @Test void securityAdminResumeIsAdmittedOnceAndHoldsTheFleetFence() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_single")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-resume"); var others = pair(fixture, "unit-other");
            var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, false);
            assertNull(repo.resumeEligibility(e.run()));
            assertEquals("INCIDENT_MISMATCH", requestAdmin(repo, e.run(), "incident-other").code());
            var admitted = requestAdmin(repo, e.run(), e.run());
            assertEquals(JooqCpFailoverRepository.RESUME_ADMITTED, admitted.code());
            var resume = repo.resumeForRun(e.run()).orElseThrow();
            assertEquals("QUEUED", resume.state()); assertEquals(e.member(), resume.formerActiveRef());
            assertEquals(ADMIN, resume.approvedBy()); assertNotNull(resume.jobId());
            var job = mutate(fixture, dsl -> dsl.fetchOne("select job_type,action_class,window_bypass_role,idempotency_key "
                + "from jobs where job_id={0}", resume.jobId()));
            assertEquals("cp_cluster_failover", job.get("job_type", String.class));
            assertEquals("operational-state-change", job.get("action_class", String.class));
            assertEquals("security_admin", job.get("window_bypass_role", String.class));
            assertEquals("cp_failover_resume:" + resume.id(), job.get("idempotency_key", String.class));
            // Single use, in any state.
            assertEquals("RESUME_ALREADY_USED", requestAdmin(repo, e.run(), e.run()).code());
            // The fleet fence: no failover elsewhere, no readiness on the unit, no release while the resume is in flight.
            assertEquals("FLEET_MUTATION_ACTIVE", request(repo, "unit-other", others).code());
            assertEquals("RUN_ALREADY_ACTIVE", repo.requestReadiness("unit-resume", null, ADMIN,
                members.iterator().next(), "check_point").code());
            assertEquals(JooqCpFailoverRepository.INCIDENT_RUN_STILL_OWNED, repo.releaseIncident(e.run(), ADMIN, REASON));
            // The run row stays STOPPED (V136 FAILOVER_TERMINAL is not weakened).
            assertEquals("STOPPED", repo.detail(e.run()).orElseThrow().run().state());
            assertThrows(RuntimeException.class, () -> mutate(fixture, dsl -> dsl.execute(
                "update failover_run set state='PLANNED' where run_id={0}", e.run())));
            // Append-only: the binding cannot be rewritten and the history cannot be deleted.
            assertThrows(RuntimeException.class, () -> mutate(fixture, dsl -> dsl.execute(
                "update failover_resume set former_active_ref='synthetic-other' where resume_id={0}", resume.id())));
            assertThrows(RuntimeException.class, () -> mutate(fixture, dsl -> dsl.execute(
                "delete from failover_resume where resume_id={0}", resume.id())));
            assertEquals(1L, count(fixture, "audit_log where table_name='failover_resume' and action_id='failover_resume_request'"));
        }
    }

    @Test void eligibilityRefusesEachUnprovenStop() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_refusals")) {
            var repo = repository(fixture);
            var running = executing(fixture, repo, "unit-live", pair(fixture, "unit-live"));
            assertEquals("RESUME_RUN_NOT_ELIGIBLE", repo.resumeEligibility(running.run()));
            var down = repo.prepareDispatch(running.run(), running.epoch(), 0, running.member(), "cp_failover_down",
                "operational-state-change");
            // A lost DOWN reply: the DOWN was never confirmed, so neXus does not know it downed this member.
            assertFalse(repo.dispatch(down, () -> false));
            assertEquals("STOPPED", repo.detail(running.run()).orElseThrow().run().state());
            assertEquals("RESUME_DOWN_NOT_CONFIRMED", repo.resumeEligibility(running.run()));
            assertEquals("RESUME_DOWN_NOT_CONFIRMED", requestAdmin(repo, running.run(), running.run()).code());
            assertEquals(0L, count(fixture, "failover_resume"));
            assertEquals("RESUME_RUN_NOT_ELIGIBLE", repo.resumeEligibility("synthetic-missing-run"));
        }
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_refusals_b")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-resume");
            var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, false);
            // Another incident on a member of this unit.
            mutate(fixture, dsl -> dsl.execute("insert into failover_quarantine(cluster_ref,execution_id,reason,quarantined_member_ids) "
                + "values('unit-elsewhere','synthetic-other-run','MUTATION_STOPPED',jsonb_build_array({0}::text))", e.member()));
            assertEquals("OPEN_INCIDENT", repo.resumeEligibility(e.run()));
        }
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_refusals_c")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-resume");
            var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, false);
            mutate(fixture, dsl -> dsl.execute("update devices set disabled=true where device_id={0}", e.member()));
            assertEquals("MEMBERSHIP_CHANGED", repo.resumeEligibility(e.run()));
        }
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_refusals_d")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-resume");
            var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, false);
            assertEquals(JooqCpFailoverRepository.INCIDENT_RELEASED, repo.releaseIncident(e.run(), ADMIN, REASON));
            assertEquals("INCIDENT_NOT_OPEN", repo.resumeEligibility(e.run()));
            var disabled = new JooqCpFailoverRepository(new JooqTransactionBoundary(
                DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)), false, JobWindowTestPolicy.PERMISSIVE);
            assertEquals("FAILOVER_MUTATION_DISABLED", disabled.resumeEligibility(e.run()));
            assertEquals("FAILOVER_MUTATION_DISABLED", disabled.requestResume(e.run(), ADMIN, "security_admin",
                JooqCpFailoverRepository.ADMIN_SINGLE, REASON, e.run(), false).code());
        }
    }

    @Test void twoPersonResumeNeedsADistinctApproverAndExpiresAfterThirtyMinutes() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_two_person")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-resume");
            var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, false);
            var pending = repo.requestResume(e.run(), OP_A, "operation_admin", JooqCpFailoverRepository.TWO_PERSON, REASON, e.run(), false);
            assertEquals(JooqCpFailoverRepository.RESUME_PENDING, pending.code());
            String resumeId = pending.runId();
            assertNull(repo.resumeForRun(e.run()).orElseThrow().jobId(), "A pending request admits no job");
            assertEquals(Duration.ofMinutes(30), Duration.between(repo.resumeForRun(e.run()).orElseThrow().requestedAt(),
                repo.resumeForRun(e.run()).orElseThrow().expiresAt()));
            assertEquals("SELF_APPROVAL", repo.approveResume(e.run(), resumeId, OP_A, "operation_admin", false).code());
            assertEquals("RESUME_NOT_FOUND", repo.approveResume("synthetic-other-run", resumeId, OP_B, "operation_admin", false).code());
            assertEquals(JooqCpFailoverRepository.RESUME_ADMITTED, repo.approveResume(e.run(), resumeId, OP_B, "operation_admin", false).code());
            var resume = repo.resumeForRun(e.run()).orElseThrow();
            assertEquals("QUEUED", resume.state()); assertEquals(OP_B, resume.approvedBy()); assertNotNull(resume.jobId());
            assertEquals("RESUME_NOT_PENDING", repo.approveResume(e.run(), resumeId, OP_B, "operation_admin", false).code());
            assertEquals(1L, count(fixture, "audit_log where table_name='failover_resume' and action_id='failover_resume_second_approval'"));
        }
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_expiry")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-resume");
            var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, false);
            String resumeId = repo.requestResume(e.run(), OP_A, "operation_admin", JooqCpFailoverRepository.TWO_PERSON,
                REASON, e.run(), false).runId();
            try (var admin = fixture.adminConnection(); var statement = admin.createStatement()) {
                // Age the request past its 30 minutes; the append-only guard is bypassed for this setup step only.
                statement.execute("set session_replication_role = replica");
                statement.execute("update failover_resume set requested_at=clock_timestamp()-interval '31 minutes',"
                    + "expires_at=clock_timestamp()-interval '1 minute' where resume_id='" + resumeId + "'");
            }
            assertEquals("RESUME_REQUEST_EXPIRED", repo.approveResume(e.run(), resumeId, OP_B, "operation_admin", false).code());
            assertEquals("STOPPED", repo.resumeForRun(e.run()).orElseThrow().state());
            assertEquals("RESUME_ALREADY_USED", requestAdmin(repo, e.run(), e.run()).code(), "An expired resume is spent");
        }
    }

    @Test void theResumedUpIsTheOnlyPossiblySentReturnOfTheRunAndTheIncidentStaysOpen() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_once")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-resume");
            // The run's own UP was proven NOT_SENT (mutation switch off at UP): V169 opened the incident.
            var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, true);
            assertEquals(1L, count(fixture, "failover_dispatch_intent where step='RETURNING' and delivery='NOT_SENT'"));
            var runBefore = repo.detail(e.run()).orElseThrow().run();
            assertEquals(JooqCpFailoverRepository.RESUME_ADMITTED, requestAdmin(repo, e.run(), e.run()).code());
            var resume = repo.resumeForRun(e.run()).orElseThrow();
            var w = claim(fixture, resume.jobId());
            assertEquals("ADMITTED", repo.resumeAdmission(resume.id(), w.job(), w.epoch(), true));
            assertTrue(repo.resumeState(resume.id(), w.epoch(), "READING_STATE", "READING_STATE", null, null, null));
            assertTrue(repo.resumeState(resume.id(), w.epoch(), "RETURNING", "RETURNING", null, null, null));
            assertEquals("ADMITTED", repo.resumeAdmission(resume.id(), w.job(), w.epoch(), false));
            assertThrows(IllegalStateException.class, () -> repo.prepareResumeDispatch(resume.id(), w.epoch(), 2,
                resume.peerRef(), "cp_failover_up", "operational-state-change"), "only the member this run downed");
            assertThrows(IllegalStateException.class, () -> repo.prepareResumeDispatch(resume.id(), w.epoch(), 2,
                resume.formerActiveRef(), "cp_failover_down", "operational-state-change"), "only cp_failover_up");
            var up = repo.prepareResumeDispatch(resume.id(), w.epoch(), 2, resume.formerActiveRef(), "cp_failover_up",
                "operational-state-change");
            assertThrows(IllegalStateException.class, () -> repo.prepareResumeDispatch(resume.id(), w.epoch(), 3,
                resume.formerActiveRef(), "cp_failover_up", "operational-state-change"), "no second intent");
            // A second possibly-sent RETURNING intent of the run is impossible at the schema level (V170 partial unique).
            var duplicate = assertThrows(org.jooq.exception.DataAccessException.class, () -> mutate(fixture, dsl -> {
                dsl.execute("insert into job_step_attempt(attempt_id,job_id,lease_epoch,step_index,step_kind,attempt_number,"
                    + "action_class,mutation_boundary_crossed,sent_at) values('synthetic-attempt',{0},{1},9,'cp_failover_up',1,"
                    + "'operational-state-change',true,now())", w.job(), w.epoch());
                return dsl.execute("insert into failover_dispatch_intent(nonce,run_id,attempt_id,job_id,lease_epoch,"
                    + "owner_generation,owner_instance,step,member_ref,gate_id) select 'synthetic-nonce',run_id,'synthetic-attempt',"
                    + "job_id,lease_epoch,owner_generation,owner_instance,step,member_ref,gate_id from failover_dispatch_intent "
                    + "where nonce={0}", up.nonce());
            }));
            assertEquals("23505", duplicate.sqlState());
            assertTrue(repo.dispatchResume(up, () -> true));
            assertFalse(repo.dispatchResume(up, () -> { fail("A claimed intent is never resent"); return true; }));
            assertTrue(repo.confirmDispatch(up));
            assertTrue(repo.resumeState(resume.id(), w.epoch(), "POST_RETURN", "POST_RETURN", null, null, null));
            repo.check(e.run(), "post_return", resume.formerActiveRef(), null, 1, "PASS", "{\"role\":\"STANDBY\"}");
            assertTrue(repo.resumeState(resume.id(), w.epoch(), "DONE", "DONE", "SUCCEEDED", null, "RETURN_VERIFIED"));
            var done = repo.resumeForRun(e.run()).orElseThrow();
            assertEquals("DONE", done.state()); assertEquals("RETURN_VERIFIED", done.message());
            assertEquals("COMPLETED", mutate(fixture, dsl -> dsl.fetchOne("select state from jobs where job_id={0}",
                w.job()).get(0, String.class)));
            // The run row is untouched and its incident is still open: release stays a separate manual act.
            var runAfter = repo.detail(e.run()).orElseThrow().run();
            assertEquals(runBefore.state(), runAfter.state()); assertEquals(runBefore.outcome(), runAfter.outcome());
            assertEquals(runBefore.message(), runAfter.message());
            assertEquals(1L, count(fixture, "failover_quarantine where active and execution_id='" + e.run() + "'"));
            assertEquals(1L, count(fixture, "failover_dispatch_intent where step='RETURNING' and delivery<>'NOT_SENT'"));
            for (String action : List.of("failover_resume_request", "failover_resume_admission", "failover_resume_state"))
                assertTrue(count(fixture, "audit_log where table_name='failover_resume' and action_id='" + action + "'") > 0, action);
            assertTrue(count(fixture, "audit_log where table_name='failover_dispatch_intent' and correlation_run_id='" + e.run() + "'") > 0);
            assertEquals(JooqCpFailoverRepository.INCIDENT_RELEASED, repo.releaseIncident(e.run(), ADMIN, REASON));
        }
    }

    @Test void ownerLossOrALostReplyRecordsUncertaintyAndKeepsTheIncident() throws Exception {
        for (boolean ownerLoss : List.of(true, false)) {
            try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_uncertain_" + ownerLoss)) {
                var repo = repository(fixture); var members = pair(fixture, "unit-resume");
                var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, false);
                assertEquals(JooqCpFailoverRepository.RESUME_ADMITTED, requestAdmin(repo, e.run(), e.run()).code());
                var resume = repo.resumeForRun(e.run()).orElseThrow();
                var w = claim(fixture, resume.jobId());
                assertEquals("ADMITTED", repo.resumeAdmission(resume.id(), w.job(), w.epoch(), true));
                assertTrue(repo.resumeState(resume.id(), w.epoch(), "RETURNING", "RETURNING", null, null, null));
                var up = repo.prepareResumeDispatch(resume.id(), w.epoch(), 2, resume.formerActiveRef(), "cp_failover_up",
                    "operational-state-change");
                if (ownerLoss) {
                    assertTrue(repo.dispatchResume(up, () -> true));
                    mutate(fixture, dsl -> dsl.execute("update jobs set lease_expires_at=clock_timestamp()-interval '1 minute' "
                        + "where job_id={0}", w.job()));
                    assertEquals(1, repo.reconcileDispatches());
                } else assertFalse(repo.dispatchResume(up, () -> false));
                var stopped = repo.resumeForRun(e.run()).orElseThrow();
                assertEquals("STOPPED", stopped.state()); assertEquals("OUTCOME_UNKNOWN", stopped.outcome());
                assertEquals("OUTCOME_UNKNOWN", mutate(fixture, dsl -> dsl.fetchOne(
                    "select observation from failover_dispatch_intent where nonce={0}", up.nonce()).get(0, String.class)));
                assertEquals("OUTCOME_UNKNOWN", mutate(fixture, dsl -> dsl.fetchOne("select state from jobs where job_id={0}",
                    w.job()).get(0, String.class)));
                assertEquals(1L, count(fixture, "failover_quarantine where active and execution_id='" + e.run() + "'"));
                assertEquals("STOPPED", repo.detail(e.run()).orElseThrow().run().state());
                assertEquals("RESUME_ALREADY_USED", repo.resumeEligibility(e.run()));
            }
        }
    }

    @Test void aResumeJobThatEndsWithoutTheWorkerIsStoppedAndReleasesTheFence() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_job_end")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-resume"); var others = pair(fixture, "unit-other");
            var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, false);
            assertEquals(JooqCpFailoverRepository.RESUME_ADMITTED, requestAdmin(repo, e.run(), e.run()).code());
            var resume = repo.resumeForRun(e.run()).orElseThrow();
            mutate(fixture, dsl -> dsl.execute("update jobs set state='CANCELLED',finished_at=now() where job_id={0}", resume.jobId()));
            var stopped = repo.resumeForRun(e.run()).orElseThrow();
            assertEquals("STOPPED", stopped.state()); assertEquals("RESUME_JOB_ENDED", stopped.message());
            // The unit stays blocked by the open incident; the estate is free again.
            assertEquals("OPEN_INCIDENT", request(repo, "unit-resume", members).code());
            assertEquals("ADMITTED", request(repo, "unit-other", others).code());
        }
    }

    @Test void insideBusinessHoursTheResumeRequestCarriesItsOwnAcknowledgement() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_resume_business_hours")) {
            var repo = repository(fixture); var members = pair(fixture, "unit-resume");
            var e = stoppedAfterConfirmedDown(fixture, repo, "unit-resume", members, false);
            // 10:00 Europe/Istanbul: inside business hours (PO decision 2026-10-20).
            var daytime = new JooqCpFailoverRepository(new JooqTransactionBoundary(
                DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)), true,
                new com.securityexpert.nexus.ui2.platform.JobWindowPolicy(
                    java.time.Clock.fixed(Instant.parse("2026-10-20T07:00:00Z"), java.time.ZoneOffset.UTC), 60));
            assertEquals(JooqCpFailoverRepository.BUSINESS_HOURS_ACK_REQUIRED, daytime.requestResume(e.run(), ADMIN,
                "security_admin", JooqCpFailoverRepository.ADMIN_SINGLE, REASON, e.run(), false).code());
            assertEquals(0L, count(fixture, "failover_resume"), "Nothing is spent by a missing acknowledgement");
            assertEquals(JooqCpFailoverRepository.RESUME_ADMITTED, daytime.requestResume(e.run(), ADMIN,
                "security_admin", JooqCpFailoverRepository.ADMIN_SINGLE, REASON, e.run(), true).code());
            var resume = daytime.resumeForRun(e.run()).orElseThrow();
            assertTrue(resume.requestedInBusinessHours()); assertTrue(resume.businessHoursAcknowledged());
            var job = mutate(fixture, dsl -> dsl.fetchOne("select submitted_in_business_hours,business_hours_ack_by "
                + "from jobs where job_id={0}", resume.jobId()));
            assertEquals(true, job.get(0, Boolean.class)); assertEquals(ADMIN, job.get(1, String.class));
            // A request inside business hours without its acknowledgement cannot be stored at all.
            assertThrows(RuntimeException.class, () -> mutate(fixture, dsl -> dsl.execute(
                "update failover_resume set business_hours_ack_by=null,business_hours_ack_at=null where resume_id={0}", resume.id())));
        }
    }

    // ---- fixtures ----

    /** A FAILOVER run that confirmed DOWN and stopped before UP; {@code upNotSent}: its UP intent was proven NOT_SENT. */
    private static Execution stoppedAfterConfirmedDown(Ui2PostgresFixture fixture, JooqCpFailoverRepository repo,
            String unit, Set<String> members, boolean upNotSent) {
        var e = executing(fixture, repo, unit, members);
        var down = repo.prepareDispatch(e.run(), e.epoch(), 0, e.member(), "cp_failover_down", "operational-state-change");
        assertTrue(repo.dispatch(down, () -> true));
        assertTrue(repo.confirmDispatch(down));
        assertTrue(repo.workerState(e.run(), e.epoch(), "RETURNING", "RETURNING", null, null, null));
        if (upNotSent) {
            var up = repo.prepareDispatch(e.run(), e.epoch(), 1, e.member(), "cp_failover_up", "operational-state-change");
            assertThrows(JooqCpFailoverRepository.NotSent.class, () -> repo.dispatch(up,
                () -> { throw new JooqCpFailoverRepository.NotSent(); }));
        } else {
            assertTrue(repo.workerState(e.run(), e.epoch(), "STOPPED", "STOPPED", "TRAFFIC_BELOW_TOLERANCE", "8",
                "TRAFFIC_BELOW_TOLERANCE"));
        }
        assertEquals("STOPPED", repo.detail(e.run()).orElseThrow().run().state());
        assertEquals(1L, count(fixture, "failover_quarantine where active and execution_id='" + e.run() + "'"));
        return e;
    }
    private static JooqCpFailoverRepository.Decision requestAdmin(JooqCpFailoverRepository repo, String run, String incident) {
        return repo.requestResume(run, ADMIN, "security_admin", JooqCpFailoverRepository.ADMIN_SINGLE, REASON, incident, false);
    }
    private static Worker claim(Ui2PostgresFixture fixture, String jobId) {
        var leases = new JooqJobLeaseDao(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)),
            JobWindowTestPolicy.PERMISSIVE);
        var job = leases.claimNext("general-synthetic", List.of("cp_cluster_failover"), Duration.ofMinutes(10)).orElseThrow();
        assertEquals(jobId, job.jobId());
        assertTrue(leases.transitionState(job.jobId(), job.leaseEpoch(), "CLAIMED", "EXECUTING", Ui2Rows.ACTOR, "synthetic_start"));
        return new Worker(job.jobId(), job.leaseEpoch());
    }
    private static Execution executing(Ui2PostgresFixture fixture, JooqCpFailoverRepository repo, String unit,
            Set<String> members) {
        var decision = request(repo, unit, members);
        assertEquals("ADMITTED", decision.code());
        mutate(fixture, dsl -> dsl.execute("update module_runtime_control set owner_instance='general-synthetic',"
            + "owner_heartbeat_at=now() where module='general'"));
        var leases = new JooqJobLeaseDao(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)),
            JobWindowTestPolicy.PERMISSIVE);
        var job = leases.claimNext("general-synthetic", List.of("cp_cluster_failover"), Duration.ofMinutes(10)).orElseThrow();
        assertTrue(leases.transitionState(job.jobId(), job.leaseEpoch(), "CLAIMED", "EXECUTING", Ui2Rows.ACTOR, "synthetic_start"));
        assertTrue(repo.workerState(decision.runId(), job.leaseEpoch(), "FAILING_OVER", "FAILING_OVER", null, null, null));
        return new Execution(decision.runId(), job.jobId(), job.leaseEpoch(), members.stream().sorted().findFirst().orElseThrow());
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
            .inTransaction(Ui2Rows.ACTOR, "synthetic_resume_test", work);
    }
    /** Two enrolled Check Point gateways with recorded identities predating every run of the test. */
    private static Set<String> pair(Ui2PostgresFixture fixture, String unit) throws Exception {
        try (var connection = fixture.appConnection()) {
            String credential = Ui2Rows.insertCredentialReference(connection);
            String a = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
            String b = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
            Ui2Rows.insertEndpoint(connection, a); Ui2Rows.insertEndpoint(connection, b);
            mutate(fixture, dsl -> dsl.execute("update devices set vendor_hint='check_point',role='gateway',"
                + "cluster_member_ref={0},recorded_identity_primary='synthetic-identity-'||device_id,"
                + "recorded_identity_recorded_at=now()-interval '1 day' where device_id in ({1},{2})", unit, a, b));
            return Set.of(a, b);
        }
    }
    private static JooqCpFailoverRepository.Decision request(JooqCpFailoverRepository repo, String unit, Set<String> members) {
        var request = repo.createRequestApproval(java.util.UUID.randomUUID().toString(), unit, null, unit, members,
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Synthetic resume test", Ui2Rows.ACTOR,
            "check_point", JooqCpFailoverRepository.ADMIN_SINGLE);
        return repo.requestBound(unit, null, Instant.now(), Ui2Rows.ACTOR, members.iterator().next(), true, "check_point",
            members, request.approval().id(), request.revision(), request.executionNonce(), true, request.policy());
    }
}
