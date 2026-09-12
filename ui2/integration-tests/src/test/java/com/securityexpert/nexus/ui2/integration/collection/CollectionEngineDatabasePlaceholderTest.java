package com.securityexpert.nexus.ui2.integration.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.CollectionEngineRows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobReconciler;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.lease.PersistenceJobLeaseRepository;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;

/**
 * B1-4 contract §8 tests 2/3/4/5/6, proved against a <b>real PostgreSQL
 * 16</b> server via {@link Ui2PostgresFixture} (no container runtime
 * required when {@code UI2_TEST_JDBC_URL} is set) mirroring {@code
 * Ui2IntegrationHarnessPlaceholderTest}'s established pattern
 * (B1-1/B1-2/B1-3/B1-4b).
 *
 * <p>The contract's own text marks tests 2-6 "[container]" because, at the
 * time it was written, "a live PostgreSQL instance" implied Testcontainers.
 * That implication does not survive contact with this environment: {@link
 * Ui2PostgresFixture} itself documents that Testcontainers is "the carrier
 * the contract names, not the property it is asserting" and a real
 * PostgreSQL 16 server reached via {@code UI2_TEST_JDBC_URL} is an
 * equally-valid carrier. Every lease/fencing/reconciliation invariant these
 * five tests prove is a database-row-locking and wall-clock property of
 * PostgreSQL itself plus {@code JooqJobLeaseDao}/{@code JobReconciler}
 * (both real, unmodified production code) -- it needs a real server, not a
 * second OS process and not a container.</p>
 *
 * <p>Only {@link #sshExecTrustRuleRejectsUntrustedHostKey()} stays
 * disabled: it is the one test in this class whose subject
 * ({@code SshExecTransport} negotiating a real TCP/SSH handshake against a
 * host presenting an untrusted key) has no in-process substitute reachable
 * without adding a new test dependency (out of this build's file scope,
 * and no embedded SSH server library is already on this module's
 * classpath) -- it genuinely needs a container-hosted SSH endpoint.</p>
 */
class CollectionEngineDatabasePlaceholderTest {

    private static Ui2PostgresFixture fixture;
    private static DataSource appDataSource;
    private static JobLeaseRepository leaseRepository;
    private static String deviceId;

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("collection_engine_lease");
        appDataSource = fixture.appDataSource();

        TransactionBoundary boundary = new JooqTransactionBoundary(DSL.using(appDataSource, SQLDialect.POSTGRES));
        leaseRepository = new PersistenceJobLeaseRepository(new JooqJobLeaseDao(boundary));

        try (Connection app = fixture.appConnection()) {
            String credentialReferenceId = Ui2Rows.insertCredentialReference(app);
            deviceId = Ui2Rows.insertDevice(app, credentialReferenceId, "ENROLLED");
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(CollectionEngineDatabasePlaceholderTest.class);
    }

    // -----------------------------------------------------------------
    // Contract §8 test 2 / C2 §9 criterion 2.
    // -----------------------------------------------------------------

    /**
     * N seeded {@code REQUESTED} jobs, M concurrent worker connections
     * issuing {@code JooqJobLeaseDao}'s real claim statement concurrently
     * against a real PostgreSQL 16 server until all N are claimed: every
     * job claimed by exactly one worker, {@code lease_epoch} is {@code 1}
     * on every fresh claim, and the sum of claims across workers equals N
     * with zero duplicates. The claim statement's own atomicity (no
     * read-then-write anywhere) is separately proved without a database by
     * {@code ClaimIsAtomicNoReadThenWriteTest} (persistence); what this
     * test adds is that {@code FOR UPDATE SKIP LOCKED} actually behaves
     * this way against a real server under real concurrency, not merely
     * that the SQL text has no read-then-write window.
     */
    @Test
    void multiWorkerClaimSafety() throws Exception {
        String capability = "cp.harness.multi-worker-claim";
        int jobCount = 24;
        List<String> jobIds = new ArrayList<>();
        try (Connection app = fixture.appConnection()) {
            for (int i = 0; i < jobCount; i++) {
                jobIds.add(CollectionEngineRows.insertJobWithCapability(app, deviceId, capability));
            }
        }

        int workerCount = 8;
        AtomicInteger totalClaims = new AtomicInteger();
        CopyOnWriteArrayList<ClaimedJob> claims = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < workerCount; w++) {
                String workerId = "worker-" + w;
                futures.add(pool.submit(() -> {
                    while (totalClaims.get() < jobCount) {
                        Optional<ClaimedJob> claimed =
                                leaseRepository.claimNext(workerId, List.of(capability), Duration.ofSeconds(60));
                        if (claimed.isPresent()) {
                            claims.add(claimed.get());
                            totalClaims.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(jobCount, claims.size(), "every claim attempt in this loop must have won exactly one job");
        long distinctJobIds = claims.stream().map(ClaimedJob::jobId).distinct().count();
        assertEquals(jobCount, distinctJobIds, "no job_id may be claimed by more than one worker");
        assertTrue(jobIds.containsAll(claims.stream().map(ClaimedJob::jobId).toList()),
                "every claimed job_id must be one of the seeded jobs");
        for (ClaimedJob claim : claims) {
            assertEquals(1L, claim.leaseEpoch(), "a job's first-ever claim must bump lease_epoch from 0 to 1");
        }
    }

    /**
     * The injected-violation proof this contract test must be shown to
     * actually catch: a hand-rolled claim path using a separate
     * {@code SELECT} then a separate, unconditional {@code UPDATE} (the
     * exact anti-pattern {@code ClaimStatementText}'s javadoc forbids --
     * "there is deliberately no separate SELECT statement... for finding a
     * claimable row") reproducibly double-claims a single job under real
     * concurrency against real PostgreSQL; the same concurrency harness
     * run against the real, atomic {@link JobLeaseRepository#claimNext}
     * does not. Neither production code nor {@code ClaimStatementText}/
     * {@code JooqJobLeaseDao} is touched or reimplemented here -- the
     * broken path is a local test double proving the failure mode the real
     * statement's atomicity rules out.
     */
    @Test
    void multiWorkerClaimSafetyWouldCatchANonAtomicReadThenWriteClaim() throws Exception {
        String brokenCapability = "cp.harness.broken-read-then-write-claim";
        String brokenJobId;
        try (Connection app = fixture.appConnection()) {
            brokenJobId = CollectionEngineRows.insertJobWithCapability(app, deviceId, brokenCapability);
        }

        int racers = 4;
        CyclicBarrier brokenBarrier = new CyclicBarrier(racers);
        ExecutorService brokenPool = Executors.newFixedThreadPool(racers);
        List<Boolean> brokenResults;
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(brokenPool.submit(() -> readThenWriteClaim(brokenBarrier, brokenCapability)));
            }
            brokenResults = collect(futures);
        } finally {
            brokenPool.shutdownNow();
        }
        long brokenSuccesses = brokenResults.stream().filter(Boolean::booleanValue).count();
        assertTrue(brokenSuccesses >= 2, "the injected read-then-write claim path must be provably unsafe -- "
                + "expected at least two racers to both believe they claimed job " + brokenJobId
                + ", saw " + brokenSuccesses);

        // Revert to the real, atomic path under an identical race and
        // confirm exactly one winner -- the same test, the same
        // concurrency shape, only the claim statement itself differs.
        String realCapability = "cp.harness.real-atomic-claim";
        try (Connection app = fixture.appConnection()) {
            CollectionEngineRows.insertJobWithCapability(app, deviceId, realCapability);
        }
        CyclicBarrier realBarrier = new CyclicBarrier(racers);
        ExecutorService realPool = Executors.newFixedThreadPool(racers);
        List<Boolean> realResults;
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                String workerId = "atomic-worker-" + i;
                futures.add(realPool.submit(() -> {
                    realBarrier.await(10, TimeUnit.SECONDS);
                    return leaseRepository.claimNext(workerId, List.of(realCapability), Duration.ofSeconds(60))
                            .isPresent();
                }));
            }
            realResults = collect(futures);
        } finally {
            realPool.shutdownNow();
        }
        long realSuccesses = realResults.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, realSuccesses,
                "the real atomic claim statement must let exactly one racer win the same race the broken path lost");
    }

    /** Deliberately unsafe: SELECT a REQUESTED job, then UPDATE it with no state guard and no atomicity. */
    private static boolean readThenWriteClaim(CyclicBarrier barrier, String capabilityId) throws Exception {
        try (Connection connection = appDataSource.getConnection()) {
            barrier.await(10, TimeUnit.SECONDS);
            String jobId = null;
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT job_id FROM jobs WHERE state = 'REQUESTED' AND capability_id = '"
                                    + capabilityId + "' LIMIT 1")) {
                if (rows.next()) {
                    jobId = rows.getString(1);
                }
            }
            if (jobId == null) {
                return false;
            }
            // Deliberately widen the read-then-write window this claim
            // path lacks any guard against, so the race reliably
            // reproduces rather than depending on scheduler luck.
            Thread.sleep(50);
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Ui2Rows.setAuditContext(connection, Ui2Rows.ACTOR, "harness.broken-claim");
                int updated;
                try (Statement statement = connection.createStatement()) {
                    updated = statement.executeUpdate("UPDATE jobs SET state = 'CLAIMED' WHERE job_id = '" + jobId
                            + "'");
                }
                connection.commit();
                return updated == 1;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private static <T> List<T> collect(List<Future<T>> futures) throws Exception {
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(15, TimeUnit.SECONDS));
        }
        return results;
    }

    // -----------------------------------------------------------------
    // Contract §8 test 3 / C2 §9 criterion 3.
    // -----------------------------------------------------------------

    /**
     * A job is claimed (epoch 1), then a second worker's re-claim is
     * simulated exactly as the contract's own Javadoc specifies -- "bump
     * {@code lease_epoch} directly against real PostgreSQL" -- rather than
     * waiting out a natural expiry (that natural-expiry variant is {@link
     * #leaseExpiryIsNotDoubleClaimed()}). The first worker then attempts a
     * fencing-guarded state write using its now-stale epoch: the write
     * must affect zero rows, and the job's real state/epoch must be
     * exactly what the second worker left behind -- proving the first
     * worker's write had no effect at all, not merely that it "failed."
     */
    @Test
    void fencingTokenRejectsZombieWriter() throws Exception {
        String capability = "cp.harness.fencing-zombie-writer";
        String jobId;
        try (Connection app = fixture.appConnection()) {
            jobId = CollectionEngineRows.insertJobWithCapability(app, deviceId, capability);
        }

        Optional<ClaimedJob> claimed =
                leaseRepository.claimNext("worker-1", List.of(capability), Duration.ofSeconds(60));
        long staleEpoch = claimed.orElseThrow().leaseEpoch();
        assertEquals(1L, staleEpoch);

        // Simulate a second worker's re-claim of the same job (the
        // contract's own artificially-bumped-epoch variant). Uses the
        // migrate-role connection to this fixture's own database --
        // fixture.adminConnection() is the bootstrap superuser connection
        // to the *server's* admin database, not this test's schema.
        try (Connection migrate = fixture.migrateConnection()) {
            migrate.setAutoCommit(false);
            Ui2Rows.setAuditContext(migrate, "worker-2", "harness.simulated-reclaim");
            try (Statement statement = migrate.createStatement()) {
                statement.execute("UPDATE jobs SET lease_epoch = lease_epoch + 1, lease_worker_id = 'worker-2' "
                        + "WHERE job_id = '" + jobId + "'");
            }
            migrate.commit();
        }

        // The zombie's stale-epoch write must affect zero rows.
        boolean zombieWroteAnything = leaseRepository.transitionState(jobId, staleEpoch, JobState.CLAIMED,
                JobState.EXECUTING, Ui2Rows.ACTOR, "harness.zombie-write");
        assertFalse(zombieWroteAnything, "a stale-epoch write from the fenced-out worker must affect zero rows");

        try (Connection app = fixture.appConnection()) {
            assertEquals("CLAIMED", CollectionEngineRows.jobState(app, jobId),
                    "the zombie's write must not have changed job state at all");
            assertEquals(2L, CollectionEngineRows.jobLeaseEpoch(app, jobId),
                    "lease_epoch must remain exactly what the second worker's re-claim left it at");
        }

        // Proves the mechanism, not merely the failure: the second
        // worker's own (correct) epoch does succeed at the same transition.
        boolean secondWorkerWrote = leaseRepository.transitionState(jobId, 2L, JobState.CLAIMED, JobState.EXECUTING,
                Ui2Rows.ACTOR, "harness.second-worker-write");
        assertTrue(secondWorkerWrote, "the current epoch holder's write must succeed at the same transition");
    }

    // -----------------------------------------------------------------
    // Contract §8 test 4 / C2 §9 criterion 4.
    // -----------------------------------------------------------------

    /**
     * Three real lease-expiry scenarios, each proved with a genuinely
     * zero-second lease against real PostgreSQL (real wall-clock expiry,
     * not a directly-bumped epoch and not a mocked clock -- {@code
     * JooqJobLeaseDao} reads {@code now()} in SQL, never {@code
     * Instant.now()} in Java, so there is no clock port to inject here;
     * see the class Javadoc's testability note). A lease duration of zero
     * seconds makes {@code lease_expires_at} equal to the claim's own
     * {@code now()}, and the sub-millisecond scheduling gap before this
     * test's own next statement is already enough for a later {@code
     * now() > lease_expires_at} to hold -- no {@code Thread.sleep} is
     * required for the invariant itself; a single bounded 50ms sleep is
     * added only as a safety margin against clock-read granularity, not to
     * make a race reproduce.
     */
    @Test
    void leaseExpiryBranchesByMutationBoundary() throws Exception {
        String capability = "cp.harness.lease-expiry-branches";
        String noAttemptJobId;
        String allBoundaryNoJobId;
        String unconfirmedYesJobId;
        try (Connection app = fixture.appConnection()) {
            noAttemptJobId = CollectionEngineRows.insertJobWithCapability(app, deviceId, capability);
            allBoundaryNoJobId = CollectionEngineRows.insertJobWithCapability(app, deviceId, capability);
            unconfirmedYesJobId = CollectionEngineRows.insertJobWithCapability(app, deviceId, capability);
        }

        long noAttemptEpoch = claimWithZeroSecondLease(noAttemptJobId, capability, "worker-no-attempt");
        long allBoundaryNoEpoch = claimWithZeroSecondLease(allBoundaryNoJobId, capability, "worker-boundary-no");
        long unconfirmedYesEpoch = claimWithZeroSecondLease(unconfirmedYesJobId, capability, "worker-unconfirmed-yes");

        // Scenario 1: no attempt row at all -- stays CLAIMED.
        // (nothing to insert)

        // Scenario 2: every attempt row's boundary is false -- move to EXECUTING first.
        assertTrue(leaseRepository.transitionState(allBoundaryNoJobId, allBoundaryNoEpoch, JobState.CLAIMED,
                JobState.EXECUTING, Ui2Rows.ACTOR, "harness.to-executing"));
        try (Connection app = fixture.appConnection()) {
            CollectionEngineRows.insertJobStepAttempt(app, allBoundaryNoJobId, allBoundaryNoEpoch, false, false);
        }

        // Scenario 3: one attempt row's boundary is true, outcome unrecorded.
        assertTrue(leaseRepository.transitionState(unconfirmedYesJobId, unconfirmedYesEpoch, JobState.CLAIMED,
                JobState.EXECUTING, Ui2Rows.ACTOR, "harness.to-executing"));
        try (Connection app = fixture.appConnection()) {
            CollectionEngineRows.insertJobStepAttempt(app, unconfirmedYesJobId, unconfirmedYesEpoch, true, false);
        }

        // Safety margin only -- see Javadoc; not required for the
        // zero-second lease to have already expired.
        Thread.sleep(50);

        // reconcileOnce() scans the whole jobs table, not just this test's
        // own rows, so its per-branch counts are asserted as "at least
        // one" (this test's own job) rather than "exactly one": a sibling
        // test's own already-expired fixture can legitimately still be
        // sitting in the same shared per-class database when tests run in
        // an order this class does not control. The three per-job state
        // assertions right below are what actually proves this test's own
        // three scenarios landed correctly.
        JobReconciler reconciler = new JobReconciler(leaseRepository);
        JobReconciler.ReconciliationSummary summary = reconciler.reconcileOnce();
        assertTrue(summary.requeuedNoAttempt() >= 1);
        assertTrue(summary.requeuedAllBoundaryNo() >= 1);
        assertTrue(summary.outcomeUnknown() >= 1);

        try (Connection app = fixture.appConnection()) {
            assertEquals("REQUESTED", CollectionEngineRows.jobState(app, noAttemptJobId),
                    "CLAIMED, expired, no attempt row -> REQUESTED (C2 §4.4-1)");
            assertEquals("REQUESTED", CollectionEngineRows.jobState(app, allBoundaryNoJobId),
                    "EXECUTING, expired, every attempt boundary=false -> REQUESTED (C2 §4.4-2)");
            assertEquals("OUTCOME_UNKNOWN", CollectionEngineRows.jobState(app, unconfirmedYesJobId),
                    "EXECUTING, expired, one unconfirmed boundary=true -> OUTCOME_UNKNOWN (C2 §4.4-3)");
        }
    }

    // -----------------------------------------------------------------
    // Contract §8 test 5 / AC-5 -- the database-observable half.
    // -----------------------------------------------------------------

    /**
     * The full contract-5 scenario names a real {@code ssh_exec} adapter
     * against a container-hosted SSH endpoint and an actually-killed
     * worker OS process. Per AGENTS.md's evidence laws, "worker loss" is
     * a durable database state, not an OS-process event: a worker that
     * commits the mutation-boundary row and is killed before writing an
     * outcome is, from the database's point of view, indistinguishable
     * from a worker that commits the same row and then simply never calls
     * back -- both leave exactly one {@code job_step_attempt} row with
     * {@code mutation_boundary_crossed = true} and {@code outcome NULL}
     * under an expired lease. This test reproduces that state directly
     * (no SSH, no killed process) and proves the two invariants AC-5
     * actually asks the database layer to guarantee: the job settles
     * {@code OUTCOME_UNKNOWN} and is <b>never</b> reclaimed afterward, no
     * matter how many times the reconciler runs -- and exactly one attempt
     * row exists for it, standing in for "the endpoint's connection log
     * shows the command was received exactly once." <b>Not proved here:</b>
     * that the real {@code ssh_exec} adapter actually commits the boundary
     * row before sending the command against a real socket, or that an
     * actual killed JVM leaves the same row shape -- those need the
     * container-hosted SSH endpoint and stay out of scope, same as {@link
     * #sshExecTrustRuleRejectsUntrustedHostKey()}.
     */
    @Test
    void workerKilledMidStepLandsInOutcomeUnknownNoSecondContact() throws Exception {
        String capability = "cp.harness.worker-killed-mid-step";
        String jobId;
        try (Connection app = fixture.appConnection()) {
            jobId = CollectionEngineRows.insertJobWithCapability(app, deviceId, capability);
        }

        long epoch = claimWithZeroSecondLease(jobId, capability, "worker-killed");
        assertTrue(leaseRepository.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING,
                Ui2Rows.ACTOR, "harness.to-executing"));
        try (Connection app = fixture.appConnection()) {
            // The durable fact the crash matrix depends on: the boundary
            // row committed. The worker is "killed" here in the only sense
            // the database can ever observe -- no further write for this
            // job_id ever follows this one.
            CollectionEngineRows.insertJobStepAttempt(app, jobId, epoch, true, false);
        }
        Thread.sleep(50);

        JobReconciler reconciler = new JobReconciler(leaseRepository);
        JobReconciler.ReconciliationSummary first = reconciler.reconcileOnce();
        // >= 1, not == 1 -- see leaseExpiryBranchesByMutationBoundary's
        // comment: reconcileOnce() scans the whole shared per-class jobs
        // table, not just this test's own row.
        assertTrue(first.outcomeUnknown() >= 1);

        try (Connection app = fixture.appConnection()) {
            assertEquals("OUTCOME_UNKNOWN", CollectionEngineRows.jobState(app, jobId));
            assertEquals(1L, CollectionEngineRows.attemptCount(app, jobId),
                    "the device must be shown contacted exactly once -- exactly one attempt row, ever");
        }

        // Never reclaimed, no matter how many more times the reconciler
        // runs, and never selectable by a fresh claim for the same
        // capability either.
        for (int i = 0; i < 25; i++) {
            reconciler.reconcileOnce();
        }
        Optional<ClaimedJob> neverReclaimed =
                leaseRepository.claimNext("worker-scavenger", List.of(capability), Duration.ofSeconds(60));
        assertTrue(neverReclaimed.isEmpty(), "an OUTCOME_UNKNOWN job must never become claimable again");

        try (Connection app = fixture.appConnection()) {
            assertEquals("OUTCOME_UNKNOWN", CollectionEngineRows.jobState(app, jobId),
                    "state must still be OUTCOME_UNKNOWN after repeated reconciler polls -- no automatic exit");
            assertEquals(1L, CollectionEngineRows.attemptCount(app, jobId),
                    "no second contact must ever have been made for this job_id");
        }
    }

    // -----------------------------------------------------------------
    // Contract §8 test 6 / C2 §9 criterion 2 (naturally expiring variant).
    // -----------------------------------------------------------------

    /**
     * The naturally-expiring sibling of {@link #fencingTokenRejectsZombieWriter()}:
     * instead of bumping {@code lease_epoch} directly, a claimed job's
     * zero-second lease is left to actually expire, the reconciler requeues
     * it to {@code REQUESTED}, and then M workers race the real atomic
     * claim statement for it concurrently. Repeated across several
     * expire/requeue/race cycles so the assertion rests on more than one
     * observation: at most one worker ever wins each cycle, and every won
     * job_id is the one job under test -- never a duplicate.
     */
    @Test
    void leaseExpiryIsNotDoubleClaimed() throws Exception {
        String capability = "cp.harness.lease-expiry-not-double-claimed";
        String jobId;
        try (Connection app = fixture.appConnection()) {
            jobId = CollectionEngineRows.insertJobWithCapability(app, deviceId, capability);
        }

        int cycles = 5;
        int racersPerCycle = 6;
        // Only the first cycle needs an explicit seed claim -- from cycle 1
        // onward the previous cycle's own race winner claimed with the same
        // zero-second lease, so the job is already CLAIMED-and-expired
        // again by construction.
        claimWithZeroSecondLease(jobId, capability, "seed-worker-0");
        for (int cycle = 0; cycle < cycles; cycle++) {
            Thread.sleep(50);
            new JobReconciler(leaseRepository).reconcileOnce();
            try (Connection app = fixture.appConnection()) {
                assertEquals("REQUESTED", CollectionEngineRows.jobState(app, jobId),
                        "a naturally expired, no-attempt CLAIMED job must requeue before the race");
            }

            CyclicBarrier barrier = new CyclicBarrier(racersPerCycle);
            ExecutorService pool = Executors.newFixedThreadPool(racersPerCycle);
            List<ClaimedJob> winners = new CopyOnWriteArrayList<>();
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < racersPerCycle; i++) {
                    String workerId = "racer-" + cycle + "-" + i;
                    futures.add(pool.submit(() -> {
                        try {
                            barrier.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        // Zero-second lease again: whichever racer wins
                        // leaves the job CLAIMED-and-already-expiring, so
                        // the next cycle's own expiry/reconcile/race is
                        // ready without a fresh seed claim.
                        leaseRepository.claimNext(workerId, List.of(capability), Duration.ZERO)
                                .ifPresent(winners::add);
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(15, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, winners.size(), "exactly one racer must win a naturally expired lease, cycle " + cycle);
            assertEquals(jobId, winners.get(0).jobId());
        }
    }

    // -----------------------------------------------------------------
    // Contract §8 test 11 / AC-10 -- stays disabled.
    // -----------------------------------------------------------------

    @Test
    @Disabled("requires a live container-hosted SSH endpoint to negotiate a real TCP/SSH handshake against; "
            + "no in-process substitute is reachable without adding a new test dependency, which is out of "
            + "this build's file scope")
    void sshExecTrustRuleRejectsUntrustedHostKey() {
        // Contract §8 test 11 / AC-10. A container-capable host must prove:
        // SshExecTransport#connect against a container-hosted SSH endpoint
        // presenting an untrusted host key returns ConnectResult.
        // HostKeyRejected -- never succeeds, never returns an ambiguous
        // outcome. The pure trust decision (given a presented fingerprint
        // and a resolved expected fingerprint, no socket involved) is
        // proved without a container by
        // com.securityexpert.nexus.ui2.worker.transport.ssh.
        // HostKeyVerifierTest (worker) -- what a container adds is proving
        // SshExecTransport actually wires JSch's HostKeyRepository hook to
        // that same decision over a real TCP handshake.
        fail("intentionally disabled -- see @Disabled reason");
    }

    // -----------------------------------------------------------------
    // Small helpers
    // -----------------------------------------------------------------

    /**
     * Claims a job with a lease duration of zero seconds -- {@code
     * lease_expires_at} lands at the claim's own {@code now()}, so the
     * lease is already expired (or expires within microseconds) without
     * ever bumping the epoch directly or mocking a clock.
     */
    private static long claimWithZeroSecondLease(String jobId, String capability, String workerId) {
        Optional<ClaimedJob> claimed = leaseRepository.claimNext(workerId, List.of(capability), Duration.ZERO);
        ClaimedJob job = claimed.orElseThrow(() -> new AssertionError("expected to claim job " + jobId));
        assertEquals(jobId, job.jobId());
        return job.leaseEpoch();
    }
}
