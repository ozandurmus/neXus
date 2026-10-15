package com.securityexpert.nexus.ui2.integration.failover;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.jobs.failover.authz.*;
import com.securityexpert.nexus.ui2.jobs.failover.execution.*;
import com.securityexpert.nexus.ui2.jobs.failover.pilot.FailoverPilotAllowlist;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.*;
import com.securityexpert.nexus.ui2.service.failover.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL concurrency and rollback tests, with no device executor or transport. */
class FailoverScheduleTransactionTest {
    @TempDir Path directory;

    private FailoverKeyManagementService keys() {
        Path path = directory.resolve("schedule.key");
        FailoverKeyManagementService.initializeKey(path);
        return new FailoverKeyManagementService(path);
    }

    @Test void competingBookingsAndConcurrentAppendsUseOneDatabaseOrder() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("schedule_races")) {
            var jdbc = new JdbcTemplate(fixture.appDataSource());
            var keys = keys();
            var first = FailoverScheduleIntegrityTest.service(jdbc, keys);
            var second = FailoverScheduleIntegrityTest.service(new JdbcTemplate(fixture.appDataSource()), keys);
            var request = FailoverScheduleIntegrityTest.request(Instant.now().plusSeconds(3600));
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> bookings = new ArrayList<>();
                for (var service : List.of(first, second)) {
                    bookings.add(pool.submit(() -> {
                        start.await();
                        try {
                            service.scheduleMaintenanceWindow(request);
                            return true;
                        } catch (RuntimeException conflict) {
                            return false;
                        }
                    }));
                }
                start.countDown();
                int admitted = 0;
                for (var booking : bookings) {
                    if (booking.get(10, TimeUnit.SECONDS)) admitted++;
                }
                assertEquals(1, admitted);
                assertEquals(1, count(jdbc, "failover_schedules"));
                assertEquals(1, count(jdbc, "failover_schedule_ledger"));
                String id = jdbc.queryForObject("SELECT schedule_id FROM failover_schedules", String.class);
                List<Future<?>> appends = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    int attempt = i;
                    appends.add(pool.submit(() -> new FailoverScheduleLedger(new JdbcTemplate(fixture.appDataSource()))
                        .recordTransition(id, FailoverScheduleStatus.SCHEDULED, FailoverScheduleStatus.SCHEDULED,
                            "synthetic-attempt-" + attempt, "synthetic-actor", "Framed | details: ü")));
                }
                for (var append : appends) append.get(10, TimeUnit.SECONDS);
                var restarted = new FailoverScheduleLedger(new JdbcTemplate(fixture.appDataSource()));
                assertEquals(25, restarted.getTransitionsForSchedule(id).size());
                assertTrue(restarted.verifyChainIntegrity());
                assertTrue(restarted.getTransitionsForSchedule(id).stream()
                    .allMatch(entry -> entry.timestamp().getNano() % 1000 == 0 && entry.formatVersion() == 2));
                assertThrows(RuntimeException.class, () -> jdbc.update("DELETE FROM failover_schedule_ledger"));
                assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE failover_schedule_ledger SET details = 'changed'"));
                assertThrows(RuntimeException.class, () -> jdbc.execute("TRUNCATE failover_schedule_ledger"));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test void faultsRollbackBookingCancellationAndGrantBoundaryTogether() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("schedule_rollback")) {
            var jdbc = new JdbcTemplate(fixture.appDataSource());
            var keys = keys();
            AtomicReference<String> fault = new AtomicReference<>("BOOKING");
            var ledger = new FailoverScheduleLedger(jdbc) {
                @Override public TransitionEntry recordTransition(String id, FailoverScheduleStatus from,
                    FailoverScheduleStatus to, String attempt, String actor, String details) {
                    var entry = super.recordTransition(id, from, to, attempt, actor, details);
                    if ((fault.get().equals("BOOKING") && from == null)
                        || (fault.get().equals("CANCEL") && to == FailoverScheduleStatus.CANCELLED)
                        || (fault.get().equals("LEDGER") && to == FailoverScheduleStatus.DISPATCHING)) {
                        throw new IllegalStateException("SYNTHETIC_CRASH_AFTER_LEDGER");
                    }
                    return entry;
                }
                @Override public void consumeGrant(String grant, String schedule, String attempt) {
                    super.consumeGrant(grant, schedule, attempt);
                    if (fault.get().equals("GRANT")) throw new IllegalStateException("SYNTHETIC_CRASH_AFTER_GRANT");
                }
            };
            var service = fakeDispatchService(jdbc, keys, ledger, () -> {
                throw new AssertionError("No boundary failure may reach dispatch");
            });
            assertThrows(RuntimeException.class, () -> service.scheduleMaintenanceWindow(
                FailoverScheduleIntegrityTest.request(Instant.now().plusSeconds(3600))));
            assertEquals(0, count(jdbc, "failover_schedules"));
            assertEquals(0, count(jdbc, "failover_schedule_ledger"));
            fault.set("NONE");
            var cancel = service.scheduleMaintenanceWindow(FailoverScheduleIntegrityTest.request(Instant.now().plusSeconds(3600)));
            fault.set("CANCEL");
            assertThrows(RuntimeException.class, () -> service.cancelSchedule(cancel.scheduleId(), "synthetic-actor", "Synthetic cancel"));
            assertEquals(FailoverScheduleStatus.SCHEDULED, service.getSchedule(cancel.scheduleId()).orElseThrow().status());
            assertEquals(1, count(jdbc, "failover_schedule_ledger"));
            fault.set("NONE");
            service.cancelSchedule(cancel.scheduleId(), "synthetic-actor", "Synthetic cancel");
            // Generic dispatch is refused before the grant and ledger boundaries (0326f009), so a
            // fault armed at either boundary is never reached and nothing is written.
            for (String stage : List.of("GRANT", "LEDGER")) {
                var booked = service.scheduleMaintenanceWindow(FailoverScheduleIntegrityTest.request(Instant.now().plusMillis(300)));
                TimeUnit.MILLISECONDS.sleep(350);
                fault.set(stage);
                FailoverScheduleIntegrityTest.assertGenericDispatchRefusedWithoutWrites(service, jdbc, booked.scheduleId());
                assertTrue(ledger.getTransitionsForSchedule(booked.scheduleId()).stream()
                    .noneMatch(entry -> entry.toStatus() == FailoverScheduleStatus.DISPATCHING));
                assertTrue(ledger.verifyChainIntegrity());
                fault.set("NONE");
                FailoverScheduleIntegrityTest.releaseFleetWindow(jdbc, booked.scheduleId());
            }
        }
    }

    /**
     * Two replicas racing to dispatch the same due schedule. Generic dispatch is refused on both
     * before the durable fence is taken (0326f009), even with the scheduled-execution flag forced on
     * and an executor that would otherwise reach its boundary: neither replica reaches the boundary,
     * the schedule stays SCHEDULED and no grant is consumed.
     */
    @Test void competingDispatchIsRefusedOnEveryReplicaBeforeTheDurableFence() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("schedule_dispatch")) {
            var jdbc = new JdbcTemplate(fixture.appDataSource());
            var keys = keys();
            var ledger = new FailoverScheduleLedger(jdbc);
            var first = fakeDispatchService(jdbc, keys, ledger, () -> {
                throw new AssertionError("Generic dispatch must never reach the mutation boundary");
            });
            var secondJdbc = new JdbcTemplate(fixture.appDataSource());
            var second = fakeDispatchService(secondJdbc, keys, new FailoverScheduleLedger(secondJdbc), () -> {
                    throw new AssertionError("A second replica must never reach dispatch");
                });
            var booked = first.scheduleMaintenanceWindow(FailoverScheduleIntegrityTest.request(Instant.now().plusMillis(300)));
            TimeUnit.MILLISECONDS.sleep(350);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> racers = new ArrayList<>();
                for (var service : List.of(first, second)) {
                    racers.add(pool.submit(() -> {
                        start.await();
                        FailoverScheduleIntegrityTest.assertGenericDispatchRefusedWithoutWrites(service, jdbc,
                            booked.scheduleId());
                        return null;
                    }));
                }
                start.countDown();
                for (var racer : racers) racer.get(10, TimeUnit.SECONDS);
                assertTrue(ledger.getTransitionsForSchedule(booked.scheduleId()).stream()
                    .noneMatch(entry -> entry.toStatus() == FailoverScheduleStatus.DISPATCHING));
                assertTrue(ledger.verifyChainIntegrity());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test void genericDispatchIsDisabledByDefault() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("schedule_disabled")) {
            var jdbc = new JdbcTemplate(fixture.appDataSource());
            var service = FailoverScheduleIntegrityTest.service(jdbc, keys());
            var booked = service.scheduleMaintenanceWindow(FailoverScheduleIntegrityTest.request(Instant.now().plusMillis(300)));
            TimeUnit.MILLISECONDS.sleep(350);
            // 0326f009: refused outright (GENERIC_FAILOVER_EXECUTION_DISABLED) rather than recorded as an
            // aborted schedule; the row is left untouched.
            FailoverScheduleIntegrityTest.assertGenericDispatchRefusedWithoutWrites(service, jdbc, booked.scheduleId());
        }
    }

    @Test void legacyHistoryIsPreservedAcrossAnExplicitFormatBoundary() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("schedule_legacy")) {
            org.flywaydb.core.Flyway.configure().dataSource(fixture.migrateDataSource())
                .locations("filesystem:" + Ui2PostgresFixture.migrationDirectory()).target("131").load().migrate();
            var migrationJdbc = new JdbcTemplate(fixture.migrateDataSource());
            Instant timestamp = Instant.parse("2026-10-06T10:00:00.123456Z");
            String genesis = "0".repeat(64);
            String payload = genesis + "|0|synthetic-legacy|NULL|SCHEDULED|NONE|synthetic-actor|legacy|" + timestamp;
            String legacyHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            migrationJdbc.update("INSERT INTO failover_schedule_ledger " +
                "(entry_index, prev_hash, entry_hash, schedule_id, to_status, attempt_id, actor_id, details, created_at) " +
                "VALUES (0, ?, ?, 'synthetic-legacy', 'SCHEDULED', 'NONE', 'synthetic-actor', 'legacy', ?)",
                genesis, legacyHash, timestamp.atOffset(java.time.ZoneOffset.UTC));
            migrationJdbc.update("INSERT INTO failover_schedules " +
                "(schedule_id, cluster_ref, masked_cluster_name, vendor, command_family_id, action_kind, " +
                "signed_mutation_target, window_start, window_end, max_start_delay_minutes, execution_deadline, " +
                "requester_id, approver_id, grant_id, baseline_digest, baseline_json, envelope_signature, " +
                "status, client_nonce, scheduled_at) VALUES " +
                "('synthetic-legacy', 'synthetic-cluster', 'CLS-ROMEO-01', 'CHECK_POINT', 'synthetic-family', " +
                "'CONTROLLED_FAILOVER', 'synthetic-member', now() + interval '1 hour', now() + interval '2 hours', " +
                "15, now() + interval '75 minutes', 'synthetic-requester', 'synthetic-approver', 'synthetic-grant', " +
                "'legacy-digest', '{}'::jsonb, 'legacy-signature', 'SCHEDULED', 'synthetic-nonce', now())");
            fixture.runFlyway();
            var jdbc = new JdbcTemplate(fixture.appDataSource());
            assertEquals("ABORTED_TAMPERED", jdbc.queryForObject("SELECT status FROM failover_schedules", String.class));
            assertEquals("legacy-signature", jdbc.queryForObject("SELECT envelope_signature FROM failover_schedules", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT baseline_format_version FROM failover_schedules", Integer.class));
            assertNull(jdbc.queryForObject("SELECT key_id FROM failover_schedules", String.class));
            var ledger = new FailoverScheduleLedger(jdbc);
            var boundary = ledger.recordTransition("synthetic-legacy", FailoverScheduleStatus.SCHEDULED,
                FailoverScheduleStatus.ABORTED_TAMPERED, null, "synthetic-actor", "Format boundary");
            assertEquals(legacyHash, boundary.prevHash());
            assertEquals(2, boundary.formatVersion());
            assertEquals(1, ledger.getTransitionsForSchedule("synthetic-legacy").get(0).formatVersion());
            assertTrue(new FailoverScheduleLedger(jdbc).verifyChainIntegrity());
        }
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static FailoverScheduleService fakeDispatchService(JdbcTemplate jdbc, FailoverKeyManagementService keys,
                                                               FailoverScheduleLedger ledger, Runnable boundary) throws Exception {
        var evidence = FailoverScheduleIntegrityTest.evidence();
        var authz = new FailoverAuthorizationService(evidence) {
            @Override public FourEyesAuthorizationResult authorizeFailover(FailoverAuthorizationRequest request) {
                return new FourEyesAuthorizationResult.Authorized(new FailoverLeaseToken("synthetic-token",
                    request.clusterRef(), "synthetic-requester", "synthetic-approver", "synthetic-digest",
                    Instant.now(), Instant.now().plusSeconds(60), "synthetic-signature"));
            }
        };
        var execution = new FailoverExecutionService(authz, evidence, new FailoverPilotAllowlist(), new DurableQuarantineStore()) {
            @Override public FailoverExecutionResult executeScheduledFailover(String cluster, String token, String nonce,
                FailoverActionKind action, String actor, String target, Instant deadline, ScheduledPreMutationGate gate) {
                var result = gate.evaluate(evidence.buildSnapshotForCluster(cluster));
                if (result.isPass()) boundary.run();
                return new FailoverExecutionResult("synthetic-execution", cluster, "CLS-ROMEO-01", "CHECK_POINT",
                    action, FailoverExecutionState.ABORTED_PRE_MUTATION, Instant.now(), null, Instant.now(),
                    target, "FW-TANGO-04", "synthetic-requester", "synthetic-approver", null, null, null, false,
                    "Synthetic pre-mutation refusal");
            }
        };
        var service = new FailoverScheduleService(evidence, authz, execution, ledger,
            new FailoverBookingAdmissionControl(), keys, jdbc);
        var enabled = FailoverScheduleService.class.getDeclaredField("scheduledExecutionEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(service, true); // Test fixture only: the executor above cannot issue commands.
        return service;
    }
}
