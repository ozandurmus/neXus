package com.securityexpert.nexus.ui2.integration.failover;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;
import com.securityexpert.nexus.ui2.jobs.failover.model.*;
import com.securityexpert.nexus.ui2.jobs.failover.pilot.FailoverPilotAllowlist;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.*;
import com.securityexpert.nexus.ui2.service.failover.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL schedule serialization and fail-closed admission. No device transport. */
class FailoverScheduleIntegrityTest {
    @TempDir Path directory;

    static PreflightService evidence() {
        return new PreflightService(null, null, null) {
            @Override public PreflightReport getLatestReport(String cluster) {
                throw new AssertionError("A separate cached report must never authorize booking");
            }
            @Override public ClusterEvidenceSnapshot buildSnapshotForCluster(String cluster) {
                Instant now = Instant.now();
                return new ClusterEvidenceSnapshot(cluster, "CLS-ROMEO-01", "CHECK_POINT", "ClusterXL", null,
                    member("synthetic-member-a", "ACTIVE", "STANDBY", now),
                    member("synthetic-member-b", "STANDBY", "ACTIVE", now), now);
            }
            @Override public PreflightReport evaluateSnapshot(ClusterEvidenceSnapshot snapshot) {
                return PreflightReport.fromResults(snapshot.clusterId(), snapshot.maskedClusterName(),
                    snapshot.vendor(), snapshot.haMode(), List.of(CheckResult.pass("synthetic-check",
                        "Synthetic readiness", "Synthetic", EnforcementPolicy.BLOCKING, "PASS")), snapshot);
            }
        };
    }

    private static ClusterMemberEvidence member(String id, String self, String peer, Instant now) {
        return new ClusterMemberEvidence(id, "FW-TANGO-04", self, peer, "ClusterXL", "SYNCHRONIZED",
            0, true, 0, true, List.of(), true, 0, "synthetic-version", "synthetic-policy",
            10, 20, 100, 1000, false, 0, 0, false, true, now);
    }

    static FailoverScheduleService service(JdbcTemplate jdbc, FailoverKeyManagementService keys) {
        var preflight = evidence();
        var authz = new FailoverAuthorizationService(preflight);
        return new FailoverScheduleService(preflight, authz,
            new FailoverExecutionService(authz, preflight, new FailoverPilotAllowlist(), new DurableQuarantineStore()),
            new FailoverScheduleLedger(jdbc), new FailoverBookingAdmissionControl(), keys, jdbc);
    }

    static FailoverScheduleService.ScheduleWindowRequest request(Instant start) {
        return new FailoverScheduleService.ScheduleWindowRequest("synthetic-cluster", FailoverActionKind.CONTROLLED_FAILOVER,
            start, start.plusSeconds(3600), 15, "synthetic-requester", "synthetic-approver",
            "Synthetic maintenance window", "synthetic-nonce");
    }

    static FailoverScheduleEnvelope envelope(FailoverScheduleRecord record) {
        return new FailoverScheduleEnvelope(record.scheduleId(), record.clusterRef(), record.vendor(),
            record.commandFamilyId(), record.actionKind().name(), record.signedMutationTarget(), record.windowStart(),
            record.windowEnd(), record.maxStartDelayMinutes(), record.requesterId(), record.approverId(),
            record.grantId(), record.baselineDigest(), record.clientNonce(),
            record.keyId(), record.algorithmVersion());
    }

    @Test void nanosecondsRoundTripWithoutChangingSignedContent() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("schedule_precision")) {
            var jdbc = new JdbcTemplate(fixture.appDataSource());
            FailoverKeyManagementService.initializeKey(directory.resolve("schedule.key"));
            var keys = new FailoverKeyManagementService(directory.resolve("schedule.key"));
            var service = service(jdbc, keys);
            Instant start = Instant.now().plusSeconds(3600).with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789);
            var booked = service.scheduleMaintenanceWindow(request(start));
            var restartedKeys = new FailoverKeyManagementService(directory.resolve("schedule.key"));
            var reloaded = service(jdbc, restartedKeys).getSchedule(booked.scheduleId()).orElseThrow();
            assertEquals(123456000, reloaded.windowStart().getNano());
            assertEquals(booked, reloaded);
            assertEquals(keys.currentKeyId().orElseThrow(), reloaded.keyId());
            assertEquals(FailoverScheduleEnvelope.DEFAULT_ALG_VERSION, reloaded.algorithmVersion());
            assertTrue(reloaded.baselineSummary().matchesStoredDigest(reloaded.baselineDigest()));
            assertTrue(restartedKeys.resolveCryptoService(reloaded.keyId(), reloaded.algorithmVersion()).orElseThrow().verifyEnvelope(envelope(reloaded), reloaded.envelopeSignature()));
        }
    }

    /**
     * Since 0326f009 ("Disable alternate failover dispatch and default mutation to denied") generic
     * scheduled dispatch is refused unconditionally, before the schedule row is read: no environment
     * switch re-enables it ({@link FailoverMutationSwitch#refuseGenericExecution()}). The tamper
     * detection this test used to drive therefore cannot be reached; what the real database must
     * still prove is that a tampered row is never dispatched and the refusal writes nothing.
     */
    @Test void tamperedRowsAreRefusedBeforeAnyScheduleReadOrWrite() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("schedule_tamper")) {
            var jdbc = new JdbcTemplate(fixture.appDataSource());
            FailoverKeyManagementService.initializeKey(directory.resolve("schedule.key"));
            var keys = new FailoverKeyManagementService(directory.resolve("schedule.key"));
            var service = service(jdbc, keys);
            for (String alteration : List.of(
                "baseline_digest = 'different-digest'",
                "baseline_json = jsonb_set(baseline_json, '{activeMemberId}', '\"other-member\"')",
                "baseline_json = jsonb_set(baseline_json, '{clusterRef}', '\"other-cluster\"')",
                "baseline_json = baseline_json - 'policyHash'",
                "baseline_json = baseline_json - 'transitionCounter'",
                "baseline_format_version = 1")) {
                var booked = service.scheduleMaintenanceWindow(request(Instant.now().plusMillis(300)));
                jdbc.update("UPDATE failover_schedules SET " + alteration + " WHERE schedule_id = ?", booked.scheduleId());
                TimeUnit.MILLISECONDS.sleep(350);
                assertGenericDispatchRefusedWithoutWrites(service(jdbc, keys), jdbc, booked.scheduleId());
                releaseFleetWindow(jdbc, booked.scheduleId());
            }
        }
    }

    /** As above, for a persisted key id or algorithm the restarted service cannot resolve. */
    @Test void unresolvableKeyRowsAreRefusedBeforeAnyScheduleReadOrWrite() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("schedule_key")) {
            var jdbc = new JdbcTemplate(fixture.appDataSource());
            Path key = directory.resolve("schedule.key");
            FailoverKeyManagementService.initializeKey(key);
            var keys = new FailoverKeyManagementService(key);
            for (String field : List.of("key_id", "algorithm_version", "lost-storage")) {
                var booked = service(jdbc, keys).scheduleMaintenanceWindow(request(Instant.now().plusMillis(300)));
                if (field.equals("lost-storage")) {
                    java.nio.file.Files.delete(key);
                } else {
                    jdbc.update("UPDATE failover_schedules SET " + field + " = 'unknown' WHERE schedule_id = ?", booked.scheduleId());
                }
                TimeUnit.MILLISECONDS.sleep(350);
                assertGenericDispatchRefusedWithoutWrites(service(jdbc, new FailoverKeyManagementService(key)), jdbc,
                    booked.scheduleId());
                releaseFleetWindow(jdbc, booked.scheduleId());
            }
        }
    }

    /**
     * Fixture cleanup only: a refused schedule stays SCHEDULED and keeps its fleet window (concurrency
     * 1, V135 exclusion), so the next iteration's booking would overlap it. Retire it directly.
     */
    static void releaseFleetWindow(JdbcTemplate jdbc, String scheduleId) {
        assertEquals(1, jdbc.update("UPDATE failover_schedules SET status = 'CANCELLED' WHERE schedule_id = ?", scheduleId));
    }

    /**
     * The generic dispatch refusal: {@code GENERIC_FAILOVER_EXECUTION_DISABLED}, the row still
     * {@code SCHEDULED}, no grant consumed and no ledger entry beyond the booking itself.
     */
    static void assertGenericDispatchRefusedWithoutWrites(FailoverScheduleService service, JdbcTemplate jdbc,
                                                         String scheduleId) {
        int ledgerRows = jdbc.queryForObject("SELECT count(*) FROM failover_schedule_ledger WHERE schedule_id = ?",
            Integer.class, scheduleId);
        var refused = assertThrows(SecurityException.class,
            () -> service.dispatchScheduledExecution(scheduleId, "synthetic-scheduler"));
        assertEquals(FailoverMutationSwitch.GENERIC_DISABLED, refused.getMessage());
        assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM failover_schedules WHERE schedule_id = ?",
            String.class, scheduleId));
        assertEquals(ledgerRows, jdbc.queryForObject("SELECT count(*) FROM failover_schedule_ledger WHERE schedule_id = ?",
            Integer.class, scheduleId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM failover_grant_consumption", Integer.class));
    }
}
