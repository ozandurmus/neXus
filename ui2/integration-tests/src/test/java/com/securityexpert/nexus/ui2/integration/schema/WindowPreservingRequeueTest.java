package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/**
 * V263 window-preserving requeue (deploy-freeze Phase 1): a job claimed inside its window and then deferred by a drain
 * or admission contention stays claimable after the window ends, within a bounded grace; a never-claimed job does not.
 */
class WindowPreservingRequeueTest {
    private static final List<String> TYPES = List.of("cp_inventory_collect");
    private static final Duration LEASE = Duration.ofMinutes(10);

    /** Three hours into the previous six-hour slot: its 60-minute window ended hours ago and it is not 02:00-02:59. */
    private static JobWindowPolicy closedWindow() {
        var probe = new JobWindowPolicy(Clock.systemUTC(), 60, JobWindowTestPolicy.NEVER_BUSINESS_HOURS);
        Instant previousSlot = probe.slotStart(Instant.now().minus(Duration.ofHours(6))).toInstant();
        var policy = new JobWindowPolicy(Clock.fixed(previousSlot.plus(Duration.ofHours(3)), java.time.ZoneOffset.UTC), 60,
            JobWindowTestPolicy.NEVER_BUSINESS_HOURS);
        assertFalse(policy.isOpen());
        return policy;
    }

    private record Rig(JooqTransactionBoundary tx, AuditedTransactionBoundary audited, JooqJobRecordDao jobs,
            JooqJobLeaseDao open, JooqJobLeaseDao closed, EndpointAdmissionRepository admission) {
        void sql(String statement, Object... args) {
            audited.inTransaction("synthetic-actor", "requeue_fixture", db -> db.execute(statement, args));
        }
        boolean bool(String query, Object... args) {
            return tx.<Boolean>inTransaction(db -> db.fetchOne(query, args).get(0, Boolean.class));
        }
        String submit(String job) {
            return jobs.insertRequestedIfAbsentForRun(job, job + "-key", "cp_inventory_collect", "synthetic-run", "read",
                "cp_inventory_collect", "synthetic-actor", "fixture").orElseThrow();
        }
        /** Claims inside a window, pretends that window ended {@code windowEndedAgo} ago, then defers (drain yield). */
        void claimInWindowThenDefer(String job, String windowEndedAgo) {
            long epoch = open.claimNext("general-pod-a", TYPES, LEASE).orElseThrow().leaseEpoch();
            assertTrue(bool("select admitted_window_end is not null and resume_eligible_until is null from jobs where job_id={0}", job));
            sql("update jobs set admitted_window_end=now()-cast({1} as interval) where job_id={0}", job, windowEndedAgo);
            admission.defer(job, epoch);
            assertTrue(bool("select state='REQUESTED' from jobs where job_id={0}", job));
            sql("update jobs set admission_not_before=null where job_id={0}", job); // Skip the 10 s contention back-off.
        }
    }

    private static Rig rig(Ui2PostgresFixture fixture) {
        var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        return new Rig(tx, new AuditedTransactionBoundary(tx), new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE),
            new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE), new JooqJobLeaseDao(tx, closedWindow()),
            new EndpointAdmissionRepository(tx));
    }

    @Test void deferredInWindowJobIsClaimedAfterTheWindowEndsWithinTheGrace() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("requeue_grace")) {
            var rig = rig(fixture);
            rig.submit("deferred-job");
            rig.claimInWindowThenDefer("deferred-job", "5 minutes");
            // Grace: 30 minutes from the later of the window end and the defer, i.e. from now here.
            assertTrue(rig.bool("select resume_eligible_until between now()+interval '29 minutes' and now()+interval '31 minutes' "
                + "from jobs where job_id={0}", "deferred-job"));
            var resumed = rig.closed().claimNext("general-pod-a", TYPES, LEASE).orElseThrow();
            assertEquals("deferred-job", resumed.jobId());
            // A claim through the active grace clears the grace and keeps the admitted window end (the two-hour cap anchor).
            assertTrue(rig.bool("select resume_eligible_until is null and admitted_window_end < now() from jobs where job_id={0}",
                "deferred-job"));
        }
    }

    @Test void neverClaimedJobPastItsWindowStaysUnclaimable() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("requeue_never_claimed")) {
            var rig = rig(fixture);
            rig.submit("queued-job");
            assertTrue(rig.closed().claimNext("general-pod-a", TYPES, LEASE).isEmpty());
            assertTrue(rig.bool("select state='REQUESTED' and admitted_window_end is null and resume_eligible_until is null "
                + "from jobs where job_id={0}", "queued-job"));
            assertEquals("queued-job", rig.open().claimNext("general-pod-a", TYPES, LEASE).orElseThrow().jobId());
        }
    }

    /**
     * The general worker's unit-boundary yield end to end at the database: a drain of the owning module makes
     * openingAllowed false for an executing read job, the claim loop's defer returns it to REQUESTED without spending
     * retry budget, and after the drain clears it is claimed again although its window has ended.
     */
    @Test void drainYieldOfAReadJobRequeuesItAndItResumesAfterTheWindowWithinTheGrace() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("requeue_drain_yield")) {
            var rig = rig(fixture);
            rig.submit("read-job");
            long epoch = rig.open().claimNext("general-pod-a", TYPES, LEASE).orElseThrow().leaseEpoch();
            assertTrue(rig.open().transitionState("read-job", epoch, "CLAIMED", "EXECUTING", "system:worker", "job_execute"));
            rig.sql("update jobs set admitted_window_end=now()-interval '2 minutes' where job_id={0}", "read-job");
            var owner = new EndpointAdmissionRepository.Owner("read-job", epoch, null, 0, "general", "general-pod-a",
                rig.tx().<Long>inTransaction(db -> db.fetchOne("select lease_owner_generation from jobs where job_id={0}", "read-job")
                    .get(0, Long.class)), "INVENTORY", false);
            assertTrue(rig.admission().openingAllowed(owner));
            release(fixture, "update module_runtime_control set drain_requested=true,drain_generation=drain_generation+1 where module='general'");
            assertFalse(rig.admission().openingAllowed(owner)); // EndpointRuntime.yieldAtUnitBoundary throws AdmissionWait here.
            rig.admission().defer("read-job", epoch);
            assertTrue(rig.bool("select state='REQUESTED' and admission_deferrals=1 and resume_eligible_until>now() from jobs where job_id={0}", "read-job"));
            assertEquals(0L, new JooqJobLeaseDao(rig.tx(), JobWindowTestPolicy.PERMISSIVE).retryBudgetUsed("read-job", epoch));
            rig.sql("update jobs set admission_not_before=null where job_id={0}", "read-job");
            assertTrue(rig.closed().claimNext("general-pod-a", TYPES, LEASE).isEmpty()); // Still draining: nothing claims.
            release(fixture, "update module_runtime_control set drain_requested=false where module='general'");
            assertEquals("read-job", rig.closed().claimNext("general-pod-a", TYPES, LEASE).orElseThrow().jobId());
        }
    }

    private static void release(Ui2PostgresFixture fixture, String sql) throws Exception {
        try (var connection = fixture.migrateConnection()) {
            connection.setAutoCommit(false);
            com.securityexpert.nexus.ui2.integration.support.Ui2Rows.setAuditContext(connection, "synthetic-release", "module_release");
            try (var statement = connection.createStatement()) {
                statement.execute("select pg_advisory_xact_lock(294611)");
                statement.executeUpdate(sql);
            }
            connection.commit();
        }
    }

    @Test void graceExpiryAndTheTwoHourCapEndClaimability() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("requeue_grace_expiry")) {
            var rig = rig(fixture);
            rig.submit("expired-job");
            rig.claimInWindowThenDefer("expired-job", "5 minutes");
            rig.sql("update jobs set resume_eligible_until=now()-interval '1 second' where job_id={0}", "expired-job");
            assertTrue(rig.closed().claimNext("general-pod-a", TYPES, LEASE).isEmpty());
            // D4: a claim in a later open window (grace expired) refreshes the admitted window end; it is never stale.
            assertEquals("expired-job", rig.open().claimNext("general-pod-a", TYPES, LEASE).orElseThrow().jobId());
            assertTrue(rig.bool("select admitted_window_end > now() and resume_eligible_until is null from jobs where job_id={0}",
                "expired-job"));

            // Window ended 100 minutes ago: the two-hour cap binds, leaving 20 minutes of grace.
            rig.submit("near-cap-job");
            rig.claimInWindowThenDefer("near-cap-job", "100 minutes");
            assertTrue(rig.bool("select resume_eligible_until = admitted_window_end + interval '2 hours' "
                + "and resume_eligible_until > now() from jobs where job_id={0}", "near-cap-job"));
            assertEquals("near-cap-job", rig.closed().claimNext("general-pod-a", TYPES, LEASE).orElseThrow().jobId());

            // Window ended three hours ago: the two-hour cap puts the grace an hour in the past at defer time.
            rig.submit("capped-job");
            rig.claimInWindowThenDefer("capped-job", "3 hours");
            assertTrue(rig.bool("select resume_eligible_until = admitted_window_end + interval '2 hours' "
                + "and resume_eligible_until < now() from jobs where job_id={0}", "capped-job"));
            assertTrue(rig.closed().claimNext("general-pod-a", TYPES, LEASE).isEmpty());
            // Inside a window the ordinary rule still applies.
            assertEquals("capped-job", rig.open().claimNext("general-pod-a", TYPES, LEASE).orElseThrow().jobId());
        }
    }

    /**
     * D4: a job the reconciler returned to REQUESTED (no defer, so no grace) keeps a stale admitted window end from its
     * earlier claim; the next in-window claim must refresh it, so a defer after that claim gets a real grace.
     */
    @Test void reconcilerRequeuedJobGetsAFreshWindowEndAndAFullGraceOnItsNextDefer() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("requeue_reconciled")) {
            var rig = rig(fixture);
            rig.submit("requeued-job");
            long first = rig.open().claimNext("general-pod-a", TYPES, LEASE).orElseThrow().leaseEpoch();
            // What JobReconciler leaves behind: REQUESTED, no lease, no grace, the old window end still recorded.
            rig.sql("update jobs set state='REQUESTED',lease_worker_id=null,lease_expires_at=null,"
                + "admitted_window_end=now()-interval '5 hours' where job_id={0} and lease_epoch={1}", "requeued-job", first);
            long second = rig.open().claimNext("general-pod-a", TYPES, LEASE).orElseThrow().leaseEpoch();
            assertTrue(rig.bool("select admitted_window_end > now() from jobs where job_id={0}", "requeued-job"));
            rig.admission().defer("requeued-job", second);
            assertTrue(rig.bool("select resume_eligible_until > now() from jobs where job_id={0}", "requeued-job"));
        }
    }
}
