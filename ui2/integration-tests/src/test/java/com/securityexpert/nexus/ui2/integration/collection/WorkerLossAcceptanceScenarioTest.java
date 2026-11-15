package com.securityexpert.nexus.ui2.integration.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.integration.support.CollectionEngineRows;
import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.jobs.evidence.EvidenceWriterPort;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.executor.JobReconciler;
import com.securityexpert.nexus.ui2.jobs.executor.StepExecutor;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.lease.PersistenceJobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.parsing.ParsedStepResult;
import com.securityexpert.nexus.ui2.jobs.stepattempt.PersistenceJobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobStepAttemptDao;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * Acceptance scenario B (B1-11, acceptance sentence A-2): a worker dies while it holds a job claim.
 *
 * <p>The real {@link StepExecutor}, real lease/step-attempt persistence and the real {@link JobReconciler} run
 * against a real PostgreSQL 16. Only the device is a double: a {@link DeviceTransport} that counts every
 * {@code exec} and can park inside it. "Dying" means the executor thread stays parked in the device call and
 * the worker never heartbeats again (no process is started or killed). The lease then expires naturally and
 * the reconciler's own sweep classifies the job; a second worker keeps polling the real claim statement.</p>
 */
class WorkerLossAcceptanceScenarioTest {

    private static final String CAPABILITY = "cp_inventory_collect";
    private static final List<String> ELIGIBLE = List.of(CAPABILITY);
    private static final Duration LEASE = Duration.ofSeconds(3);
    private static final ConnectionTarget TARGET = new ConnectionTarget("endpoint-synthetic", "192.0.2.10", 22);
    private static final ConnectSpec CONNECT = new ConnectSpec("cred-synthetic", "trust-synthetic", Optional.empty());

    private static Ui2PostgresFixture fixture;
    private static JobLeaseRepository leases;
    private static PersistenceJobStepAttemptRepository attempts;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("worker_loss_acceptance");
        var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        leases = new PersistenceJobLeaseRepository(new JooqJobLeaseDao(boundary, JobWindowTestPolicy.PERMISSIVE));
        attempts = new PersistenceJobStepAttemptRepository(new JooqJobStepAttemptDao(boundary));
    }

    @AfterAll
    static void drop() {
        if (fixture != null) fixture.close();
    }

    /** Worker killed after the mutation boundary committed and before any device answer: OUTCOME_UNKNOWN, one contact, ever. */
    @Test
    void workerLostAfterBoundaryCommitLandsInOutcomeUnknownWithOneDeviceContact() throws Exception {
        String device = newDevice();
        String jobId = newJob(device);
        var deviceDouble = new CountingTransport(true);

        ClaimedJob claim = leases.claimNext("general-worker-a", ELIGIBLE, LEASE).orElseThrow();
        assertEquals(jobId, claim.jobId());
        assertEquals(1L, claim.leaseEpoch());
        CompletableFuture<JobOutcome> workerA = CompletableFuture.supplyAsync(() -> executor(deviceDouble).execute(
                jobId, claim.leaseEpoch(), writeCapability(), device, TARGET, CONNECT, step -> output -> matched()));

        // The worker is now parked inside the device call. PA-1: the boundary fact is already durable.
        assertTrue(deviceDouble.entered.await(20, TimeUnit.SECONDS), "worker A never reached the device call");
        try (Connection db = fixture.appConnection()) {
            assertEquals("EXECUTING", CollectionEngineRows.jobState(db, jobId));
            assertEquals(1L, Ui2Rows.count(db, "select count(*) from job_step_attempt where job_id='" + jobId
                    + "' and step_index=1 and mutation_boundary_crossed and outcome is null and sent_at is not null"));
        }

        // Worker A is "dead": no heartbeat, no outcome. The sweep runs against the real lease expiry (PA-2),
        // while worker B polls the real claim statement the whole time (PA-3).
        var reconciler = new JobReconciler(leases);
        int polls = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String state;
        do {
            reconciler.reconcileOnce();
            assertTrue(leases.claimNext("general-worker-b", ELIGIBLE, LEASE).isEmpty(), "worker B must never get this job");
            polls++;
            try (Connection db = fixture.appConnection()) {
                state = CollectionEngineRows.jobState(db, jobId);
            }
            if (state.equals("EXECUTING")) Thread.sleep(250);
        } while (state.equals("EXECUTING") && System.nanoTime() < deadline);
        assertEquals("OUTCOME_UNKNOWN", state, "sweep never classified the orphaned job after " + polls + " polls");
        for (int i = 0; i < 100; i++) {
            reconciler.reconcileOnce();
            assertTrue(leases.claimNext("general-worker-b", ELIGIBLE, LEASE).isEmpty());
        }

        // PA-4/PA-6: one device exec, one exec attempt row, still unconfirmed.
        assertEquals(1, deviceDouble.execCalls.get());
        long reconcilerAuditId;
        try (Connection db = fixture.appConnection()) {
            assertEquals(1L, Ui2Rows.count(db, "select count(*) from job_step_attempt where job_id='" + jobId + "' and step_index=1"));
            assertEquals(0L, Ui2Rows.count(db, "select count(*) from job_step_attempt where job_id='" + jobId
                    + "' and step_index=1 and outcome is not null"));
            assertEquals(0L, Ui2Rows.count(db, "select count(*) from job_reconciliation where job_id='" + jobId + "'"));
            reconcilerAuditId = Ui2Rows.count(db, "select max(audit_id) from audit_log where table_name='jobs' and row_pk='"
                    + jobId + "' and action_id='job_reconcile_outcome_unknown'");
        }

        // The dead worker wakes up late and tries to record the device answer: refused, nothing changes.
        deviceDouble.release.countDown();
        JobOutcome late = workerA.get(20, TimeUnit.SECONDS);
        assertTrue(late instanceof JobOutcome.ZombieStopped, "late worker must stop, got " + late);
        assertEquals(1, deviceDouble.execCalls.get(), "a stopped worker must never contact the device again");
        try (Connection db = fixture.appConnection()) {
            assertEquals("OUTCOME_UNKNOWN", CollectionEngineRows.jobState(db, jobId));
            assertEquals(0L, Ui2Rows.count(db, "select count(*) from job_step_attempt where job_id='" + jobId
                    + "' and step_index=1 and outcome is not null"), "late outcome write must be refused");
            assertEquals(0L, Ui2Rows.count(db, "select count(*) from audit_log where table_name='job_step_attempt' "
                    + "and action_id='job_step_attempt_outcome' and after_state->>'job_id'='" + jobId + "' "
                    + "and (after_state->>'step_index')::int=1"));
            // History: who did what, in order. Claim, execute, the durable boundary write, then the sweep.
            List<String> jobActions = actions(db, "jobs", jobId);
            assertEquals(List.of("job_claim", "job_claim_to_executing", "job_reconcile_outcome_unknown"),
                    jobActions.stream().filter(a -> a.startsWith("job_")).toList(), jobActions.toString());
            assertTrue(Ui2Rows.count(db, "select count(*) from audit_log where table_name='job_step_attempt' "
                    + "and action_id='job_step_attempt_boundary_crossed' and after_state->>'job_id'='" + jobId
                    + "' and audit_id < " + reconcilerAuditId) == 1, "boundary write must precede the sweep in the audit log");
            assertEquals(1L, Ui2Rows.count(db, "select count(*) from audit_log where table_name='jobs' and row_pk='"
                    + jobId + "' and after_state->>'lease_worker_id'='general-worker-a'"
                    + " and before_state->>'state'='REQUESTED' and after_state->>'state'='CLAIMED'"));
        }
        for (int i = 0; i < 20; i++) assertTrue(leases.claimNext("general-worker-b", ELIGIBLE, LEASE).isEmpty());
    }

    /** Worker dies before any device contact (claimed only, or pre-contact row only): requeued once, second worker runs it, one contact in total. */
    @Test
    void workerLostBeforeContactIsRequeuedAndRunsExactlyOnce() throws Exception {
        for (boolean preContactRowWritten : List.of(false, true)) {
            String device = newDevice();
            String jobId = newJob(device);
            ClaimedJob first = leases.claimNext("general-worker-a", ELIGIBLE, Duration.ofSeconds(1)).orElseThrow();
            assertEquals(jobId, first.jobId());
            String staleAttempt = null;
            if (preContactRowWritten) {
                assertTrue(leases.transitionState(jobId, first.leaseEpoch(), JobState.CLAIMED, JobState.EXECUTING,
                        Ui2Rows.ACTOR, "fixture.execute"));
                staleAttempt = attempts.insertPreContact(jobId, first.leaseEpoch(), 1, "EXEC", "read", 1);
            }
            // Worker A is gone. Wait for the real lease expiry and the sweep.
            var reconciler = new JobReconciler(leases);
            String state;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            do {
                Thread.sleep(250);
                reconciler.reconcileOnce();
                try (Connection db = fixture.appConnection()) {
                    state = CollectionEngineRows.jobState(db, jobId);
                }
            } while (!state.equals("REQUESTED") && System.nanoTime() < deadline);
            assertEquals("REQUESTED", state);

            ClaimedJob second = leases.claimNext("general-worker-b", ELIGIBLE, Duration.ofSeconds(60)).orElseThrow();
            assertEquals(jobId, second.jobId());
            assertEquals(2L, second.leaseEpoch());

            // The first worker is fenced out the moment the job is re-claimed.
            assertFalse(leases.transitionState(jobId, first.leaseEpoch(), JobState.CLAIMED, JobState.EXECUTING,
                    Ui2Rows.ACTOR, "fixture.zombie"));
            if (staleAttempt != null) {
                assertFalse(attempts.markBoundaryCrossed(staleAttempt, first.leaseEpoch()));
                assertFalse(attempts.writeOutcome(staleAttempt, first.leaseEpoch(), "MATCHED", null, true, 1L, 1L, "00"));
            }

            var deviceDouble = new CountingTransport(false);
            JobOutcome outcome = executor(deviceDouble).execute(jobId, second.leaseEpoch(), readCapability(), device,
                    TARGET, CONNECT, step -> output -> matched());
            assertTrue(outcome instanceof JobOutcome.Completed, "got " + outcome);
            assertEquals(1, deviceDouble.execCalls.get());
            assertTrue(leases.claimNext("general-worker-c", ELIGIBLE, LEASE).isEmpty(), "a finished job is never claimed again");

            try (Connection db = fixture.appConnection()) {
                assertEquals("COMPLETED", CollectionEngineRows.jobState(db, jobId));
                assertEquals(2L, CollectionEngineRows.jobLeaseEpoch(db, jobId));
                assertEquals(0L, Ui2Rows.count(db, "select count(*) from job_step_attempt where job_id='" + jobId
                        + "' and lease_epoch=1 and mutation_boundary_crossed"));
                List<String> jobActions = actions(db, "jobs", jobId).stream().filter(a -> a.startsWith("job_")).toList();
                assertTrue(jobActions.contains("job_reconcile_requeue"), jobActions.toString());
                assertTrue(jobActions.indexOf("job_reconcile_requeue") < jobActions.lastIndexOf("job_claim_to_executing"),
                        jobActions.toString());
                assertEquals("job_completed", jobActions.get(jobActions.size() - 1));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> actions(Connection db, String table, String rowPk) throws Exception {
        var out = new java.util.ArrayList<String>();
        try (var st = db.createStatement(); var rows = st.executeQuery("select action_id from audit_log where table_name='"
                + table + "' and row_pk='" + rowPk + "' order by audit_id")) {
            while (rows.next()) out.add(rows.getString(1));
        }
        return out;
    }

    private static String newDevice() throws Exception {
        try (Connection db = fixture.appConnection()) {
            return Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
        }
    }

    private static String newJob(String device) throws Exception {
        try (Connection db = fixture.appConnection()) {
            return CollectionEngineRows.insertJobWithCapability(db, device, CAPABILITY);
        }
    }

    private static ParsedStepResult matched() {
        return new ParsedStepResult.Matched(java.util.Map.of());
    }

    private static StepExecutor executor(DeviceTransport transport) {
        EvidenceWriterPort noEvidence = (attemptId, epoch, provenance, outcome) -> { };
        return new StepExecutor(leases, attempts, noEvidence,
                id -> Optional.of(new DeviceEnrollmentSnapshot(id, DeviceEnrollmentState.ENROLLED, false)),
                transport, null);
    }

    /** Synthetic device: counts every exec; with {@code parkInExec} the first exec blocks until released. */
    private static final class CountingTransport implements DeviceTransport {
        final AtomicInteger execCalls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final boolean parkInExec;

        CountingTransport(boolean parkInExec) {
            this.parkInExec = parkInExec;
        }

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            return new ConnectResult.Authenticated(() -> "synthetic-session");
        }

        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            execCalls.incrementAndGet();
            if (parkInExec) {
                entered.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ExecResult.Completed("OK", 0);
        }

        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override public void disconnect(TransportSession session) { }
    }

    private static Capability readCapability() {
        return capability("synthetic_read", "show synthetic", "clish", ActionClass.CLASS_0_READ, 30);
    }

    private static Capability writeCapability() {
        return capability("synthetic_write", "add synthetic backup", "expert_via_clish_c",
                ActionClass.CLASS_1_RECOVERY_WRITE, 900);
    }

    private static Capability capability(String id, String command, String shell, ActionClass actionClass, int timeoutS) {
        var connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        var exec = new CapabilityStep(StepKind.EXEC, shell, command, false, Optional.of(actionClass),
                Optional.of("^OK.*$"), Optional.of(timeoutS));
        var disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        var spec = new CapabilitySpec(id, "check_point", "cp_gaia_gateway", TransportKind.SSH_EXEC,
                MaturityState.CAP_OFFLINE, List.of(connect, exec), List.of(disconnect), "UNKNOWN", List.of(), false);
        GateRegistryPort gates = key -> key.commandKey().equals(command)
                ? List.of(new GateRow(id + "_gate", "check_point", "cp_gaia_gateway", shell, "SSH_EXEC", command,
                        actionClass, SignOffState.SIGNED_OFF, timeoutS, null, null, null, null, null, List.of(),
                        "synthetic fixture"))
                : List.of();
        return new CapabilityRegistryLoader(gates).load(spec);
    }
}
