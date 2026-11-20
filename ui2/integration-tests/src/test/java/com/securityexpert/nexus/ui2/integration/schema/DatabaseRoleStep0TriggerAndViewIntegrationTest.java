package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.LatestEffectiveRunningReader;
import com.securityexpert.nexus.ui2.persistence.runtime.ModuleRuntimeRepository;

/**
 * Per-module database roles, step 0 (M1 = V259,
 * {@code docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md} §2.2, §2.5.1, §4.2 Step 0, §6.2 F1-F3),
 * proved against a real PostgreSQL 16 server with row triggers enabled ({@code session_replication_role = origin}):
 *
 * <ol>
 *   <li>Every trigger function in {@code public} is {@code SECURITY DEFINER} with
 *       {@code search_path = pg_catalog, public} and {@code EXECUTE} revoked from {@code PUBLIC}.</li>
 *   <li>Representative writes per module role succeed with all triggers enabled even when the trigger touches another
 *       plane's table ({@code jobs} completion firing failover/readiness/resume/config-sync triggers,
 *       {@code failover_run} insertion by {@code ui2_scheduler} firing admission/guard/resume-fence triggers,
 *       {@code policy_snapshot} insertion by {@code ui2_policy} firing {@code fn_capture_policy_rule_history},
 *       {@code discovery_candidate} and {@code devices} updates by {@code ui2_inventory} firing failover approval
 *       invalidation triggers, and {@code module_runtime_control} updates firing
 *       {@code ui2_record_instance_heartbeat}).</li>
 *   <li>{@link ModuleRuntimeRepository#heartbeat(String, String)} and
 *       {@link ModuleRuntimeRepository#runTask(String, String, String, Runnable)} succeed under {@code SET ROLE} for
 *       worker roles including {@code ui2_packet_analysis} and {@code ui2_policy}.</li>
 *   <li>The Step 0 {@code SECURITY DEFINER} projection views ({@code v_latest_effective_running_config},
 *       {@code v_compliance_device_evidence}, {@code v_active_pseudonym_lookup}) and helper functions
 *       ({@code ui2_current_role_module()}, {@code ui2_schema_version_check(text)},
 *       {@code ui2_capability_owner_module(text)}) work under the target module roles without widening underlying
 *       table grants.</li>
 * </ol>
 */
class DatabaseRoleStep0TriggerAndViewIntegrationTest {

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("role_step0_triggers");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void everyTriggerAndSecurityDefinerFunctionHasSearchPathPinnedAndPublicRevoked() throws SQLException {
        List<String> violations = new ArrayList<>();
        try (Connection admin = fixture.adminDatabaseConnection(); Statement statement = admin.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT p.oid::regprocedure::text AS sig, p.prosecdef, "
                            + "coalesce(array_to_string(p.proconfig, ';'), '') AS cfg, "
                            + "has_function_privilege('public', p.oid, 'EXECUTE') AS pub_exec, "
                            + "(p.prorettype = 'pg_catalog.trigger'::regtype) AS is_trigger "
                            + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                            + "WHERE n.nspname = 'public' ORDER BY 1")) {
                while (rows.next()) {
                    String sig = rows.getString("sig");
                    boolean secDef = rows.getBoolean("prosecdef");
                    String cfg = rows.getString("cfg");
                    boolean pubExec = rows.getBoolean("pub_exec");
                    boolean isTrigger = rows.getBoolean("is_trigger");
                    if (isTrigger && !secDef) {
                        violations.add("trigger function is still SECURITY INVOKER: " + sig);
                    }
                    if (secDef && !cfg.contains("search_path=pg_catalog, public")) {
                        violations.add("SECURITY DEFINER function lacks search_path=pg_catalog, public: "
                                + sig + " (" + cfg + ")");
                    }
                    if ((isTrigger || secDef) && pubExec) {
                        violations.add("function still grants EXECUTE to PUBLIC: " + sig);
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void helperFunctionsMapRolesVerifySchemaVersionAndCoverSupportBundleCapabilities() throws SQLException {
        try (Connection admin = fixture.adminDatabaseConnection()) {
            admin.setAutoCommit(false);
            try (Statement statement = admin.createStatement()) {
                for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                    statement.execute("SET ROLE " + role);
                    String expectedModule = role.equals("ui2_worker_general")
                            ? "general"
                            : role.substring("ui2_".length());
                    try (ResultSet row = statement.executeQuery("SELECT ui2_current_role_module()")) {
                        row.next();
                        assertEquals(expectedModule, row.getString(1), role);
                    }
                    assertTrue(scalarBoolean(statement, "SELECT ui2_schema_version_check('259')"), role);
                    assertTrue(scalarBoolean(statement, "SELECT ui2_schema_version_check('V259')"), role);
                    assertFalse(scalarBoolean(statement, "SELECT ui2_schema_version_check('999999')"), role);
                    assertTrue(denied(admin, statement, "SELECT count(*) FROM flyway_schema_history"),
                            role + " must not read flyway_schema_history directly");
                    statement.execute("RESET ROLE");
                }

                // Finding F3: every support-bundle capability resolves to 'diagnostics' via ui2_capability_owner_module.
                statement.execute("SET ROLE ui2_diagnostics");
                for (String capability : List.of("cp_support_bundle", "pan_support_bundle", "asa_support_bundle",
                        "fgt_support_bundle", "path_validation", "static_route_preview")) {
                    try (ResultSet row = statement.executeQuery(
                            "SELECT ui2_capability_owner_module('" + capability + "')")) {
                        row.next();
                        assertEquals("diagnostics", row.getString(1), capability);
                    }
                }
                statement.execute("RESET ROLE");
            }
            admin.rollback();
            admin.setAutoCommit(true);
        }
    }

    @Test
    void packetAnalysisAndPolicyWorkersCanHeartbeatAcquireTaskLeasesAndTransitionJobsWithTriggersEnabled()
            throws SQLException {
        String credRef;
        String deviceId;
        String paCaseId = Ui2Rows.opaqueId("case-pa");
        String paJobId = Ui2Rows.opaqueId("job-pa");
        String policyJobId = Ui2Rows.opaqueId("job-pol");
        try (Connection migrate = fixture.migrateConnection()) {
            credRef = Ui2Rows.insertCredentialReference(migrate);
            deviceId = Ui2Rows.insertDevice(migrate, credRef, "ENROLLED");
            migrate.setAutoCommit(false);
            Ui2Rows.setAuditContext(migrate, Ui2Rows.ACTOR, "step0.seed.jobs");
            try (Statement statement = migrate.createStatement()) {
                statement.execute("INSERT INTO jobs(job_id, job_type, capability_id, target_kind, target_ref, "
                        + "submitted_by_actor_fingerprint, state, action_class) VALUES ('" + paJobId
                        + "', 'packet_analysis_run', 'packet_analysis_run', 'packet_analysis_case', '"
                        + paCaseId + "', '" + Ui2Rows.ACTOR + "', 'REQUESTED', 'read')");
                statement.execute("INSERT INTO jobs(job_id, job_type, capability_id, target_kind, target_device_id, "
                        + "submitted_by_actor_fingerprint, state, action_class) VALUES ('" + policyJobId
                        + "', 'cp_policy_collect', 'cp_policy_collect', 'device', '" + deviceId + "', '"
                        + Ui2Rows.ACTOR + "', 'REQUESTED', 'read')");
                statement.execute("UPDATE module_runtime_control SET effective_owner = 'policy', fallback_enabled = false "
                        + "WHERE module = 'policy'");
            }
            migrate.commit();
            migrate.setAutoCommit(true);
        }

        try (Connection admin = fixture.adminDatabaseConnection()) {
            var tx = new JooqTransactionBoundary(DSL.using(admin, SQLDialect.POSTGRES));

            // 1. ui2_packet_analysis: heartbeat, task lease (using ui2_next_task_lease_epoch), and job transitions
            try (Statement statement = admin.createStatement()) {
                statement.execute("SET ROLE ui2_packet_analysis");
            }
            var paRuntime = new ModuleRuntimeRepository(tx);
            assertTrue(paRuntime.heartbeat("packet_analysis", "packet_analysis-pod-1"));
            paRuntime.poolStats("packet_analysis", "packet_analysis-pod-1", 1, 3, 0, 0L, 100L);
            AtomicBoolean paTaskRan = new AtomicBoolean();
            paRuntime.runTask("packet_analysis", "packet_analysis-pod-1", "PacketAnalysisRetentionPruner.prune",
                    () -> paTaskRan.set(true));
            assertTrue(paTaskRan.get(), "ui2_packet_analysis must acquire and release runtime_task_lease");

            admin.setAutoCommit(false);
            try (Statement statement = admin.createStatement()) {
                Ui2Rows.setAuditContext(admin, "system:worker", "step0.pa.job");
                assertEquals(1, statement.executeUpdate("UPDATE jobs SET state = 'CLAIMED', "
                        + "lease_worker_id = 'packet_analysis-pod-1', lease_epoch = 1, lease_owner_generation = 1, "
                        + "lease_expires_at = now() + interval '60 seconds' WHERE job_id = '" + paJobId + "'"));
                assertEquals(1, statement.executeUpdate("UPDATE jobs SET state = 'EXECUTING' "
                        + "WHERE job_id = '" + paJobId + "'"));
                assertEquals(1, statement.executeUpdate("UPDATE jobs SET state = 'COMPLETED', finished_at = now() "
                        + "WHERE job_id = '" + paJobId + "'"));
                statement.execute("RESET ROLE");
            }
            admin.commit();
            admin.setAutoCommit(true);

            // 2. ui2_policy: heartbeat, task lease, job transition to FAILED (fires ui2_failover_job_failure,
            //    ui2_failover_readiness_job_terminal, ui2_failover_resume_job_end, ui2_pan_config_sync_job_end),
            //    and policy_snapshot INSERT (fires fn_capture_policy_rule_history).
            try (Statement statement = admin.createStatement()) {
                statement.execute("SET ROLE ui2_policy");
            }
            var policyRuntime = new ModuleRuntimeRepository(tx);
            assertTrue(policyRuntime.heartbeat("policy", "policy-pod-1"));
            AtomicBoolean policyTaskRan = new AtomicBoolean();
            policyRuntime.runTask("policy", "policy-pod-1", "PolicyWorkerTask.housekeeping",
                    () -> policyTaskRan.set(true));
            assertTrue(policyTaskRan.get(), "ui2_policy must acquire and release runtime_task_lease");

            admin.setAutoCommit(false);
            try (Statement statement = admin.createStatement()) {
                Ui2Rows.setAuditContext(admin, "system:worker", "step0.policy.job");
                assertEquals(1, statement.executeUpdate("UPDATE jobs SET state = 'CLAIMED', "
                        + "lease_worker_id = 'policy-pod-1', lease_epoch = 2, lease_owner_generation = 1, "
                        + "lease_expires_at = now() + interval '60 seconds' WHERE job_id = '" + policyJobId + "'"));
                assertEquals(1, statement.executeUpdate("UPDATE jobs SET state = 'FAILED', "
                        + "terminal_reason = 'SYNTHETIC_FAILURE', finished_at = now() "
                        + "WHERE job_id = '" + policyJobId + "'"));

                // INSERT INTO policy_snapshot fires fn_capture_policy_rule_history (writes policy_rule_history
                // and policy_rule_history_baseline, where ui2_policy holds SELECT only).
                String snapshotId = Ui2Rows.opaqueId("psnap");
                assertEquals(1, statement.executeUpdate("INSERT INTO policy_snapshot("
                        + "policy_id, collected_at, metadata, snapshot) VALUES ('"
                        + snapshotId + "', now(), '{\"vendor\":\"check_point\",\"managementId\":\""
                        + deviceId + "\"}'::jsonb, "
                        + "'{\"rules\":[{\"uid\":\"rule-1\",\"ruleNumber\":1,\"name\":\"Allow-Synthetic\","
                        + "\"action\":\"Accept\",\"enabled\":true}]}'::jsonb)"));

                // Finding F2: ui2_policy can update devices.ssh_identity_unverified, but cannot update disabled.
                assertEquals(1, statement.executeUpdate("UPDATE devices SET ssh_identity_unverified = true "
                        + "WHERE device_id = '" + deviceId + "'"));
                assertTrue(denied(admin, statement,
                        "UPDATE devices SET disabled = true WHERE device_id = '" + deviceId + "'"));
                statement.execute("RESET ROLE");
            }
            admin.rollback();
            admin.setAutoCommit(true);
        }
    }

    @Test
    void schedulerInventoryAndConfigurationWritesSucceedWithCrossDomainTriggersEnabled() throws SQLException {
        String credRef;
        String deviceA;
        String readinessJobId = Ui2Rows.opaqueId("job-rdy");
        String discRunId = Ui2Rows.opaqueId("drun");
        try (Connection migrate = fixture.migrateConnection()) {
            credRef = Ui2Rows.insertCredentialReference(migrate);
            deviceA = Ui2Rows.insertDevice(migrate, credRef, "ENROLLED");
            migrate.setAutoCommit(false);
            Ui2Rows.setAuditContext(migrate, Ui2Rows.ACTOR, "step0.seed.failover");
            try (Statement statement = migrate.createStatement()) {
                statement.execute("INSERT INTO jobs(job_id, job_type, target_kind, target_device_id, "
                        + "submitted_by_actor_fingerprint, state, action_class) VALUES ('" + readinessJobId
                        + "', 'cp_failover_readiness', 'device', '" + deviceA + "', '" + Ui2Rows.ACTOR
                        + "', 'COMPLETED', 'read')");
            }
            migrate.commit();
            migrate.setAutoCommit(true);
        }

        try (Connection admin = fixture.adminDatabaseConnection()) {
            admin.setAutoCommit(false);
            try (Statement statement = admin.createStatement()) {
                // 1. Finding F1 proof: ui2_scheduler inserts a READINESS failover_run with row triggers enabled
                //    (fires ui2_failover_dispatch_admission, ui2_failover_run_guard, ui2_failover_run_resume_fence).
                statement.execute("SET ROLE ui2_scheduler");
                Ui2Rows.setAuditContext(admin, "system:scheduler", "step0.scheduler.failover_run");
                String runId = Ui2Rows.opaqueId("frun");
                assertEquals(1, statement.executeUpdate("INSERT INTO failover_run("
                        + "run_id, cluster_ref, vendor, run_kind, requested_by, scheduled_for, job_id, state, step) "
                        + "VALUES ('" + runId + "', 'CLS-ROMEO-01', 'check_point', 'READINESS', "
                        + "'system:scheduler', now(), '" + readinessJobId + "', 'PLANNED', 'PRECHECK')"));
                statement.execute("RESET ROLE");

                // 2. ui2_inventory: updates devices membership columns and inserts discovery_candidate
                //    (fires ui2_failover_membership_approval_invalidate and ui2_failover_discovery_approval_invalidate,
                //    which update failover_approval even though ui2_inventory has no direct grant on failover_approval).
                statement.execute("SET ROLE ui2_inventory");
                Ui2Rows.setAuditContext(admin, "system:worker", "step0.inventory.triggers");
                assertEquals(1, statement.executeUpdate("UPDATE devices SET cluster_member_ref = 'CLS-ROMEO-01', "
                        + "observed_hostname = 'FW-TANGO-04' WHERE device_id = '" + deviceA + "'"));
                assertEquals(1, statement.executeUpdate("INSERT INTO discovery_run("
                        + "run_id, vendor, management_address, credential_reference_id, "
                        + "requested_by_actor_fingerprint, state) VALUES ('"
                        + discRunId + "', 'check_point', '192.0.2.10', '" + credRef
                        + "', 'system:worker', 'FINISHED')"));
                assertEquals(1, statement.executeUpdate("INSERT INTO discovery_candidate("
                        + "candidate_id, run_id, vendor, stable_identifier, kind, display_name, "
                        + "cluster_reference, importable) VALUES ('"
                        + Ui2Rows.opaqueId("dcand") + "', '" + discRunId
                        + "', 'check_point', 'id-tango-04', 'gateway', 'FW-TANGO-04', 'CLS-ROMEO-01', true)"));
                statement.execute("RESET ROLE");

                // 3. ui2_configuration: column-level UPDATE on devices for observed facts, HA pair claim, and
                //    ssh_identity_unverified succeeds, while administrative column UPDATE or DELETE is denied (42501).
                statement.execute("SET ROLE ui2_configuration");
                Ui2Rows.setAuditContext(admin, "system:worker", "step0.configuration.columns");
                assertEquals(1, statement.executeUpdate("UPDATE devices SET observed_hostname = 'FW-TANGO-04', "
                        + "observed_model = 'PA-5220', observed_software_version = '11.1.4', "
                        + "observed_ha_role = 'active', ha_pair_claim = 'CLS-ROMEO-01', "
                        + "ssh_identity_unverified = false WHERE device_id = '" + deviceA + "'"));
                assertTrue(denied(admin, statement,
                        "UPDATE devices SET disabled = true WHERE device_id = '" + deviceA + "'"));
                assertTrue(denied(admin, statement,
                        "UPDATE devices SET credential_reference_id = '" + credRef
                                + "' WHERE device_id = '" + deviceA + "'"));
                assertTrue(denied(admin, statement,
                        "DELETE FROM devices WHERE device_id = '" + deviceA + "'"));
                statement.execute("RESET ROLE");
            }
            admin.rollback();
            admin.setAutoCommit(true);
        }
    }

    @Test
    void projectionViewsProvideNarrowReadAccessWithoutWideningBaseTableGrants() throws SQLException {
        String credRef;
        String deviceId;
        String jobId;
        String artefactRef = "configuration/2026/11/20/sha256-" + "a".repeat(64) + ".enc";
        String artefactId = Ui2Rows.opaqueId("art");
        String cfgRunId = Ui2Rows.opaqueId("crun");
        try (Connection migrate = fixture.migrateConnection()) {
            credRef = Ui2Rows.insertCredentialReference(migrate);
            deviceId = Ui2Rows.insertDevice(migrate, credRef, "ENROLLED");
            jobId = Ui2Rows.insertJob(migrate, deviceId);
            migrate.setAutoCommit(false);
            Ui2Rows.setAuditContext(migrate, Ui2Rows.ACTOR, "step0.seed.views");
            try (Statement statement = migrate.createStatement()) {
                statement.execute("INSERT INTO configuration_artefact("
                        + "artefact_ref, device_id, job_id, vendor, plaintext_sha256, plaintext_bytes, "
                        + "ciphertext_sha256, ciphertext_bytes, compression, key_id) VALUES ('"
                        + artefactRef + "', '" + deviceId + "', '" + jobId + "', 'palo_alto', '"
                        + "b".repeat(64) + "', 128, '" + "c".repeat(64) + "', 156, 'gzip', 'artefact_key_v1')");
                statement.execute("INSERT INTO device_configuration_run("
                        + "run_id, device_id, job_id, collected_at, vendor, read_kind, is_primary, "
                        + "canonical_hash, raw_hash, raw_bytes, artefact_ref, change_state) VALUES ('"
                        + cfgRunId + "', '" + deviceId + "', '" + jobId + "', now(), 'palo_alto', "
                        + "'effective_running', true, '" + "b".repeat(64) + "', '" + "b".repeat(64)
                        + "', 128, '" + artefactRef + "', 'first_run')");
                statement.execute("INSERT INTO backup_artefact("
                        + "artefact_id, device_id, artefact_class, vendor, software_version, hostname_fingerprint, "
                        + "plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, "
                        + "wrapped_data_key, validation, retention_tier, recovery_volume_path) VALUES ('"
                        + artefactId + "', '" + deviceId + "', 'configuration', 'palo_alto', '12.1.1', '"
                        + "d".repeat(64) + "', '" + "b".repeat(64) + "', 128, '" + "c".repeat(64)
                        + "', 156, 'artefact_key_v1', decode('01020304', 'hex'), "
                        + "'{\"level_reached\":\"V1\",\"restore_proven\":false}'::jsonb, 'daily', '"
                        + artefactRef + "')");
                statement.execute("INSERT INTO pseudonym_registry(kind, raw_key, pseudonym) VALUES "
                        + "('device', 'hmac-tango-04', 'FW-TANGO-04') ON CONFLICT DO NOTHING");
            }
            migrate.commit();
            migrate.setAutoCommit(true);
        }

        try (Connection admin = fixture.adminDatabaseConnection(); Statement statement = admin.createStatement()) {
            // 1. ui2_inventory reads LatestEffectiveRunningReader via v_latest_effective_running_config
            //    even though direct SELECT on backup_artefact and artefact_retention_ledger is denied (42501).
            statement.execute("SET ROLE ui2_inventory");
            var reader = new LatestEffectiveRunningReader(
                    new JooqTransactionBoundary(DSL.using(admin, SQLDialect.POSTGRES)));
            var stored = reader.latest(deviceId);
            assertTrue(stored.isPresent(), "ui2_inventory must read latest effective-running config via view");
            assertEquals(artefactRef, stored.get().artefactRef());
            assertTrue(stored.get().gzip());

            admin.setAutoCommit(false);
            assertTrue(denied(admin, statement, "SELECT count(*) FROM backup_artefact"),
                    "ui2_inventory must not hold direct SELECT on backup_artefact");
            assertTrue(denied(admin, statement, "SELECT count(*) FROM artefact_retention_ledger"),
                    "ui2_inventory must not hold direct SELECT on artefact_retention_ledger");
            statement.execute("RESET ROLE");

            // 2. ui2_compliance reads v_compliance_device_evidence while credentials/endpoint_admission are denied.
            statement.execute("SET ROLE ui2_compliance");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT latest_configuration_run_id FROM v_compliance_device_evidence WHERE device_id = '"
                            + deviceId + "'")) {
                assertTrue(rows.next());
                assertEquals(cfgRunId, rows.getString(1));
            }
            assertTrue(denied(admin, statement, "SELECT count(*) FROM credentials"));
            assertTrue(denied(admin, statement, "SELECT count(*) FROM endpoint_admission"));
            statement.execute("RESET ROLE");

            // 3. ui2_packet_analysis reads v_active_pseudonym_lookup while v_latest_effective_running_config
            //    and v_compliance_device_evidence are denied.
            statement.execute("SET ROLE ui2_packet_analysis");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT pseudonym FROM v_active_pseudonym_lookup WHERE raw_key = 'hmac-tango-04'")) {
                assertTrue(rows.next());
                assertEquals("FW-TANGO-04", rows.getString(1));
            }
            assertTrue(denied(admin, statement, "SELECT count(*) FROM v_latest_effective_running_config"));
            assertTrue(denied(admin, statement, "SELECT count(*) FROM v_compliance_device_evidence"));
            statement.execute("RESET ROLE");

            // 4. Views are read-only for every module role.
            for (String role : Set.of("ui2_inventory", "ui2_compliance", "ui2_packet_analysis")) {
                statement.execute("SET ROLE " + role);
                assertTrue(denied(admin, statement, "DELETE FROM v_active_pseudonym_lookup"));
                statement.execute("RESET ROLE");
            }
            admin.rollback();
            admin.setAutoCommit(true);
        }
    }

    private static boolean denied(Connection connection, Statement statement, String sql) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            statement.execute(sql);
            return false;
        } catch (SQLException e) {
            return "42501".equals(e.getSQLState());
        } finally {
            connection.rollback(savepoint);
        }
    }

    private static boolean scalarBoolean(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getBoolean(1);
        }
    }
}
