package com.securityexpert.nexus.ui2.integration.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimStatementText;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;

class PerDeviceJobClaimTest {
    private static final String INVENTORY = "pan_inventory_collect";
    private static final String BACKUP = "pan_device_state_backup";
    private static final List<String> CAPABILITIES = List.of(INVENTORY, BACKUP);
    private static final Duration LEASE = Duration.ofMinutes(10);

    @ParameterizedTest
    @ValueSource(strings = {"CLAIMED", "EXECUTING"})
    void sameDeviceWaitsUntilCompletion(String state) throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("device_claim_completion")) {
            var tx = transaction(fixture);
            seedPair(fixture, false);
            var leases = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE);
            var first = leases.claimNext("general-first", CAPABILITIES, LEASE).orElseThrow();
            assertEquals("inventory", first.jobId());
            if (state.equals("EXECUTING")) assertTrue(leases.transitionState(first.jobId(), first.leaseEpoch(),
                    "CLAIMED", state, Ui2Rows.ACTOR, "fixture.execute"));
            assertTrue(leases.claimNext("general-second", CAPABILITIES, LEASE).isEmpty());
            assertTrue(leases.transitionState(first.jobId(), first.leaseEpoch(), state, "COMPLETED",
                    Ui2Rows.ACTOR, "fixture.complete"));
            assertEquals("backup", leases.claimNext("general-second", CAPABILITIES, LEASE).orElseThrow().jobId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLAIMED", "EXECUTING"})
    void expiredLeaseReleasesDeviceWithoutWaitingForReconciliation(String state) throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("device_claim_expiry")) {
            var tx = transaction(fixture);
            seedPair(fixture, false);
            var leases = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE);
            leases.claimNext("general-first", CAPABILITIES, LEASE).orElseThrow();
            new AuditedTransactionBoundary(tx).inTransaction(Ui2Rows.ACTOR, "fixture.expire", db -> db.execute(
                    "update jobs set state={0},lease_expires_at=now()-interval '1 second' where job_id='inventory'", state));
            assertEquals("backup", leases.claimNext("general-second", CAPABILITIES, LEASE).orElseThrow().jobId());
        }
    }

    @Test void missingLeaseExpiryFailsClosed() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("device_claim_missing_expiry")) {
            var tx = transaction(fixture);
            seedPair(fixture, false);
            var leases = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE);
            leases.claimNext("general-first", CAPABILITIES, LEASE).orElseThrow();
            new AuditedTransactionBoundary(tx).inTransaction(Ui2Rows.ACTOR, "fixture.missing_expiry", db -> db.execute(
                    "update jobs set lease_expires_at=null where job_id='inventory'"));
            assertTrue(leases.claimNext("general-second", CAPABILITIES, LEASE).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitingWorkerUsesSnapshotAfterFirstClaimCommits(boolean differentDevices) throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("device_claim_race")) {
            seedPair(fixture, differentDevices);
            var pool = Executors.newSingleThreadExecutor();
            try (var first = fixture.appConnection()) {
                first.setAutoCommit(false);
                Ui2Rows.setAuditContext(first, Ui2Rows.ACTOR, "fixture.claim");
                var db = DSL.using(first, SQLDialect.POSTGRES);
                db.execute("SELECT pg_advisory_xact_lock(294611)");
                assertEquals("inventory", db.fetch(ClaimStatementText.SQL, "general-first", "600",
                        INVENTORY, "infinity", "-infinity", "infinity",
                        String.join(",", ScheduledBackupWindowPolicy.FLEET_JOB_TYPES), ScheduledBackupWindowPolicy.ACTOR,
                        ScheduledBackupWindowPolicy.MDS_ACTOR, ScheduledBackupWindowPolicy.MDS_EXPORT)
                        .getFirst().get("job_id", String.class));
                var waiter = pool.submit(() -> new JooqJobLeaseDao(transaction(fixture), JobWindowTestPolicy.PERMISSIVE)
                        .claimNext("general-second", List.of(BACKUP), LEASE));
                // Observe the actual database lock wait, not a timing-dependent thread sleep.
                awaitClaimWaiter(fixture);
                first.commit();
                var second = waiter.get(10, TimeUnit.SECONDS);
                assertEquals(differentDevices, second.isPresent());
                if (differentDevices) assertEquals("backup", second.orElseThrow().jobId());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test void deferredJobStaysRequestedAfterWindowEndAndRunsInNextWindow() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("device_claim_window")) {
            var tx = transaction(fixture);
            seedPair(fixture, false);
            var leases = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE);
            var first = leases.claimNext("general-first", CAPABILITIES, LEASE).orElseThrow();
            assertTrue(leases.claimNext("general-second", CAPABILITIES, LEASE).isEmpty());
            assertTrue(leases.transitionState(first.jobId(), first.leaseEpoch(), "CLAIMED", "COMPLETED",
                    Ui2Rows.ACTOR, "fixture.complete"));
            new AuditedTransactionBoundary(tx).inTransaction(Ui2Rows.ACTOR, "fixture.closed_window", db -> {
                db.execute("SELECT pg_advisory_xact_lock(294611)");
                assertTrue(db.fetch(ClaimStatementText.SQL, "general-second", "600", BACKUP, "-infinity",
                        "-infinity", "infinity", String.join(",", ScheduledBackupWindowPolicy.FLEET_JOB_TYPES),
                        ScheduledBackupWindowPolicy.ACTOR, ScheduledBackupWindowPolicy.MDS_ACTOR,
                        ScheduledBackupWindowPolicy.MDS_EXPORT).isEmpty());
                assertEquals("REQUESTED", db.fetchOne("select state from jobs where job_id='backup'").get(0, String.class));
                return null;
            });
            assertEquals("backup", leases.claimNext("general-second", CAPABILITIES, LEASE).orElseThrow().jobId());
        }
    }

    @Test void jobsWithoutDeviceAreNotSerializedTogether() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("device_claim_null_target")) {
            var tx = transaction(fixture);
            var jobs = new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE);
            for (String id : List.of("run-first", "run-second")) jobs.insertRequestedIfAbsentForRun(id, id,
                    "cp_policy_collect", "synthetic-run", "read", "cp_policy_collect", Ui2Rows.ACTOR, "fixture").orElseThrow();
            var leases = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE);
            var first = leases.claimNext("general-first", List.of("cp_policy_collect"), LEASE).orElseThrow();
            var second = leases.claimNext("general-second", List.of("cp_policy_collect"), LEASE).orElseThrow();
            assertNotEquals(first.jobId(), second.jobId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cp_cluster_failover", "pan_cluster_failover", "cp_failover_readiness", "pan_failover_readiness"})
    void failoverAndReadinessAlsoWaitBehindDeviceBackup(String capability) throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("device_claim_failover")) {
            String device = insertDevice(fixture);
            var tx = transaction(fixture);
            var jobs = new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE);
            jobs.insertRequestedIfAbsent("backup", "backup", BACKUP, device, "read", BACKUP, Ui2Rows.ACTOR, "fixture");
            jobs.insertRequestedIfAbsent("failover", "failover", capability, device, "read", capability, Ui2Rows.ACTOR, "fixture");
            var leases = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE);
            var backup = leases.claimNext("general-first", List.of(BACKUP), LEASE).orElseThrow();
            assertTrue(leases.claimNext("general-second", List.of(capability), LEASE).isEmpty());
            assertTrue(leases.transitionState(backup.jobId(), backup.leaseEpoch(), "CLAIMED", "COMPLETED",
                    Ui2Rows.ACTOR, "fixture.complete"));
            assertEquals("failover", leases.claimNext("general-second", List.of(capability), LEASE).orElseThrow().jobId());
        }
    }

    private static JooqTransactionBoundary transaction(Ui2PostgresFixture fixture) {
        return new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
    }

    private static String insertDevice(Ui2PostgresFixture fixture) throws Exception {
        try (var connection = fixture.appConnection()) {
            return Ui2Rows.insertDevice(connection, Ui2Rows.insertCredentialReference(connection), "ENROLLED");
        }
    }

    private static void seedPair(Ui2PostgresFixture fixture, boolean differentDevices) throws Exception {
        String first = insertDevice(fixture);
        String second = differentDevices ? insertDevice(fixture) : first;
        var jobs = new JooqJobRecordDao(transaction(fixture), JobWindowTestPolicy.PERMISSIVE);
        jobs.insertRequestedIfAbsent("inventory", "inventory", INVENTORY, first, "read", INVENTORY, Ui2Rows.ACTOR, "fixture").orElseThrow();
        jobs.insertRequestedIfAbsent("backup", "backup", BACKUP, second, "read", BACKUP, Ui2Rows.ACTOR, "fixture").orElseThrow();
    }

    private static void awaitClaimWaiter(Ui2PostgresFixture fixture) throws Exception {
        try (Connection observer = fixture.appConnection(); var statement = observer.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (var rows = statement.executeQuery("select exists(select 1 from pg_locks where locktype='advisory' "
                        + "and classid=0 and objid=294611 and not granted "
                        + "and database=(select oid from pg_database where datname=current_database()))")) {
                    rows.next();
                    if (rows.getBoolean(1)) return;
                }
                Thread.sleep(10);
            }
            fail("Second worker never waited for the claim transaction lock");
        }
    }
}
