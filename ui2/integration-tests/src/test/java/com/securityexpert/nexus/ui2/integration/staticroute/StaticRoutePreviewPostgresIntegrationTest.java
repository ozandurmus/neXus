package com.securityexpert.nexus.ui2.integration.staticroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.admission.PersistenceJobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.device.PersistenceDeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.BeforePathObservation;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreCheckVerdict;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreviewPlanSet;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidatedRequest;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.JooqDeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.persistence.staticroute.JooqStaticRoutePreviewRepository;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository.PreviewRow;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService.PreviewSubmissionRequest;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService.SubmissionOutcome;

/**
 * Real PostgreSQL 16 integration test for {@link JooqStaticRoutePreviewRepository} and
 * {@link StaticRoutePreviewService} against V252 ({@code srp-} {@code preview_id} CHECK) and V275
 * ({@code inverse_plan_json} JSONB array CHECK).
 */
class StaticRoutePreviewPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static CapabilityRegistry staticRouteRegistry() {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec(StaticRoutePreview.CAPABILITY, "static_route_preview", "reviewed_read",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(connect), List.of(disconnect),
                "UNKNOWN", List.of(), false);
        Capability capability = new CapabilityRegistryLoader(key -> List.of()).load(spec);
        return CapabilityRegistry.of(List.of(capability));
    }

    private static String seedCheckPointGateway(Connection db) throws SQLException {
        String credId = Ui2Rows.insertCredentialReference(db);
        String deviceId = Ui2Rows.opaqueId("dev");
        String endpointId = Ui2Rows.opaqueId("ep");
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO devices(device_id, vendor_hint, registration_source, is_test_target, "
                            + "enrollment_state, disabled, credential_reference_id, "
                            + Ui2Rows.DEVICE_REQUIRED_COLUMNS + ") VALUES (?, 'check_point', 'manual', true, "
                            + "'ENROLLED', false, ?, " + Ui2Rows.DEVICE_REQUIRED_VALUES + ")")) {
                ps.setString(1, deviceId);
                ps.setString(2, credId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO endpoints(endpoint_id, device_id, transport_kind, address_ref) "
                            + "VALUES (?, ?, 'ssh_exec', '192.0.2.2')")) {
                ps.setString(1, endpointId);
                ps.setString(2, deviceId);
                ps.executeUpdate();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
        return deviceId;
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitAttachMarkRunningRecordOutcomeAndGetPreviewSucceedEndToEndInPostgres16() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("static_route_e2e")) {
            String deviceId;
            try (Connection db = fixture.migrateConnection()) {
                deviceId = seedCheckPointGateway(db);
            }

            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var previewRepo = new JooqStaticRoutePreviewRepository(tx);
            var deviceRepo = new JooqDeviceRepository(tx);
            var inventoryRepo = new JooqDeviceInventoryRepository(tx);
            var jobRecordDao = new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE);
            var admission = new JobAdmissionService(
                    staticRouteRegistry(),
                    new PersistenceDeviceEnrollmentReadPort(deviceRepo),
                    new PersistenceJobAdmissionRepository(jobRecordDao));
            var pseudonymizer = new TopologyNamePseudonymizer(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            var service = new StaticRoutePreviewService(
                    previewRepo, deviceRepo, inventoryRepo, admission, JobWindowTestPolicy.PERMISSIVE, pseudonymizer);

            // 1. create + attachJob through StaticRoutePreviewService.submitPreview()
            PreviewSubmissionRequest request = new PreviewSubmissionRequest(
                    "pg-it-srp-idem-0001",
                    deviceId,
                    "check_point",
                    "gaia-default",
                    "ADD",
                    "192.0.2.128/25",
                    "192.0.2.1",
                    null,
                    null,
                    null,
                    2,
                    null,
                    "pg integration preview",
                    "192.0.2.129",
                    "CHG-2026-0275",
                    "PG16 end-to-end static-route preview check");

            SubmissionOutcome submitted = service.submitPreview(request, "actor-pg-it", false);
            assertFalse(submitted.deduplicated());
            String previewId = (String) submitted.body().get("preview_id");
            String jobId = (String) submitted.body().get("job_id");
            assertNotNull(previewId);
            assertTrue(StaticRoutePreview.isValidPreviewId(previewId), "preview_id must match srp-<uuid>: " + previewId);
            assertNotNull(jobId, "job_id must be attached in PostgreSQL");
            assertEquals(StaticRoutePreviewRepository.STATE_QUEUED, submitted.body().get("state"));
            assertEquals(Boolean.FALSE, submitted.body().get("mutation_possible"));

            List<Map<String, Object>> initialInverse = (List<Map<String, Object>>) submitted.body().get("inverse_plan");
            assertNotNull(initialInverse);
            assertEquals(2, initialInverse.size(), "Check Point ADD inverse plan has ROLLBACK_WRITE + ROLLBACK_SAVE");

            // Idempotent replay hits the persisted row
            SubmissionOutcome replay = service.submitPreview(request, "actor-pg-it", true);
            assertTrue(replay.deduplicated());
            assertEquals(previewId, replay.body().get("preview_id"));
            assertNull(replay.body().get("requested_by"));

            // 2. Claim the job and markRunning() under a real PostgreSQL lease
            var leases = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE);
            var claimed = leases.claimNext("general-synthetic", List.of(StaticRoutePreview.CAPABILITY), Duration.ofMinutes(10))
                    .orElseThrow();
            assertEquals(jobId, claimed.jobId());
            long epoch = claimed.leaseEpoch();
            assertTrue(leases.transitionState(jobId, epoch, "CLAIMED", "EXECUTING",
                    "system:static-route-r0-worker", "static_route_preview_start"));

            assertTrue(previewRepo.markRunning(jobId, epoch));
            PreviewRow running = previewRepo.find(previewId).orElseThrow();
            assertEquals(StaticRoutePreviewRepository.STATE_RUNNING, running.state());

            // 3. recordOutcome() writing a JSON array to inverse_plan_json
            ValidatedRequest validated = StaticRoutePreview.validateRequest(
                    deviceId, "check_point", "gaia-default", "ADD", "192.0.2.128/25", "192.0.2.1",
                    null, null, null, 2, null, "pg integration preview", "192.0.2.129",
                    "CHG-2026-0275", "PG16 end-to-end static-route preview check");
            PreviewPlanSet plans = StaticRoutePreview.compilePreviewPlans(
                    validated, List.of(deviceId), Optional.empty(), Optional.empty());
            List<PreCheckVerdict> stageBChecks = List.of(
                    new PreCheckVerdict(1, "TARGET_REACHABLE_AND_READY", "STAGE_B", "PASS", "SSH_SESSION_READY", "ok"),
                    new PreCheckVerdict(3, "DEFAULT_ROUTE_GUARD", "STAGE_B", "PASS", "NON_DEFAULT_PREFIX", "ok"));
            BeforePathObservation beforePath = new BeforePathObservation(
                    "192.0.2.129", "OBSERVED", "PATH_RECORDED", "0.0.0.0/0", "192.0.2.1", "eth0", "static",
                    "a".repeat(64));

            String preChecksJson = JSON.writeValueAsString(stageBChecks);
            String beforePathJson = JSON.writeValueAsString(beforePath);
            String commandPlanJson = JSON.writeValueAsString(plans);
            String inversePlanJson = JSON.writeValueAsString(plans.inverseSteps());
            assertTrue(inversePlanJson.startsWith("["));

            assertTrue(previewRepo.recordOutcome(
                    jobId,
                    epoch,
                    StaticRoutePreviewRepository.STATE_DONE,
                    StaticRoutePreviewRepository.VERDICT_PASS,
                    null,
                    "b".repeat(64),
                    preChecksJson,
                    beforePathJson,
                    commandPlanJson,
                    inversePlanJson));
            assertTrue(leases.transitionState(jobId, epoch, "EXECUTING", "COMPLETED",
                    "system:static-route-r0-worker", "PREVIEW_PASS"));

            // 4. getPreview() unmasked, masked, and bare-UUID refusal
            Map<String, Object> fetched = service.getPreview(previewId, false).orElseThrow();
            assertEquals(previewId, fetched.get("preview_id"));
            assertEquals(StaticRoutePreviewRepository.STATE_DONE, fetched.get("state"));
            assertEquals(StaticRoutePreviewRepository.VERDICT_PASS, fetched.get("verdict"));
            assertEquals("b".repeat(64), fetched.get("pre_route_table_sha256"));
            assertNotNull(fetched.get("completed_at"));
            Map<String, Object> fetchedBeforePath = (Map<String, Object>) fetched.get("before_path");
            assertEquals("0.0.0.0/0", fetchedBeforePath.get("selected_prefix"));
            List<Map<String, Object>> fetchedInverse = (List<Map<String, Object>>) fetched.get("inverse_plan");
            assertEquals(2, fetchedInverse.size());
            assertEquals("ROLLBACK_WRITE", fetchedInverse.get(0).get("phase"));
            assertEquals("ROLLBACK_SAVE", fetchedInverse.get(1).get("phase"));

            Map<String, Object> masked = service.getPreview(previewId, true).orElseThrow();
            assertNull(masked.get("requested_by"));
            assertNull(masked.get("device_comment"));
            assertNull(masked.get("change_ticket_ref"));
            assertNull(masked.get("reason"));
            assertEquals(2, ((List<Map<String, Object>>) masked.get("inverse_plan")).size());

            // Bare UUID without srp- prefix is rejected
            assertTrue(service.getPreview(previewId.substring(4), false).isEmpty());
        }
    }

    @Test
    void v275EnforcesArrayConstraintAndIsRepeatableInRollbackTransaction() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("static_route_v275")) {
            String deviceId;
            try (Connection db = fixture.migrateConnection()) {
                // V275 is repeatable inside BEGIN/ROLLBACK
                String v275;
                try (var in = getClass().getResourceAsStream("/db/migration/V275__static_route_preview_inverse_plan_array.sql")) {
                    assertNotNull(in, "V275 migration resource must be on the classpath");
                    v275 = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                db.setAutoCommit(false);
                try (var st = db.createStatement()) {
                    st.execute(v275);
                    st.execute(v275);
                }
                db.rollback();
                db.setAutoCommit(true);

                // Verify the old 'object' constraint is gone and the validated 'array' constraint is present
                assertEquals(0L, Ui2Rows.count(db,
                        "SELECT count(*) FROM pg_constraint "
                                + "WHERE conrelid = 'public.static_route_preview'::regclass AND contype = 'c' "
                                + "AND pg_get_constraintdef(oid) LIKE '%inverse_plan_json%' "
                                + "AND pg_get_constraintdef(oid) LIKE '%''object''%'"));
                assertEquals(1L, Ui2Rows.count(db,
                        "SELECT count(*) FROM pg_constraint "
                                + "WHERE conrelid = 'public.static_route_preview'::regclass AND contype = 'c' "
                                + "AND conname = 'chk_static_route_preview_inverse_plan_array' "
                                + "AND convalidated = true"));

                deviceId = seedCheckPointGateway(db);
            }

            try (Connection app = fixture.appConnection()) {
                // Reject bare UUID (V252 preview_id CHECK)
                SQLException badId = assertThrows(SQLException.class, () -> insertRawPreview(
                        app, UUID.randomUUID().toString(), "idem-bad-id", deviceId, "'[]'::jsonb"));
                assertEquals("23514", badId.getSQLState());

                // Reject JSON object for inverse_plan_json (V275 CHECK)
                SQLException badInverse = assertThrows(SQLException.class, () -> insertRawPreview(
                        app, "srp-" + UUID.randomUUID(), "idem-bad-inverse", deviceId, "'{}'::jsonb"));
                assertEquals("23514", badInverse.getSQLState());

                // Accept srp-<uuid> + JSON array
                insertRawPreview(app, "srp-" + UUID.randomUUID(), "idem-good-array", deviceId, "'[]'::jsonb");
            }
        }
    }

    private static void insertRawPreview(
            Connection conn, String previewId, String idemKey, String deviceId, String inversePlanLiteral)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO static_route_preview(preview_id, idempotency_key, request_payload_sha256, requested_by, "
                        + "unit_id, target_device_id, vendor, routing_context, operation, destination_cidr, nexthop_ip, "
                        + "representative_host, state, verdict, pre_checks_json, command_plan_json, inverse_plan_json) "
                        + "VALUES (?, ?, ?, 'actor-1', ?, ?, 'check_point', 'gaia-default', 'ADD', '192.0.2.128/25', "
                        + "'192.0.2.1', '192.0.2.129', 'QUEUED', 'PENDING', '[]'::jsonb, '{}'::jsonb, "
                        + inversePlanLiteral + ")")) {
            ps.setString(1, previewId);
            ps.setString(2, idemKey);
            ps.setString(3, "4".repeat(64));
            ps.setString(4, deviceId);
            ps.setString(5, deviceId);
            ps.executeUpdate();
        }
    }
}
