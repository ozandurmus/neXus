package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;

/**
 * Per-module database roles, step 1 (docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md §4.2 Step 1,
 * §4.3.2), proved against a real PostgreSQL 16 server: V242 grants the eleven NOLOGIN module roles exactly their
 * least-privilege matrix — and nothing else — while {@code ui2_app} keeps what it has.
 *
 * <p>Two complementary proofs. On a representative set of tables every role is checked twice for every privilege:
 * the catalog answer ({@code has_table_privilege}) and the real execution ({@code SET ROLE}, then the statement:
 * SQLState {@code 42501} is the denial, anything else — success or a later constraint/trigger error — means the
 * privilege check passed). Across the whole catalog the structural invariants hold for every table: no role holds
 * TRUNCATE/REFERENCES/TRIGGER, none exceeds {@code ui2_app}, none owns an object or may create one, the
 * credential/identity/audit tables are reachable only as the design says, and every table is covered by the matrix
 * (a table added later without module grants fails here, as design §4.3.2 requires).</p>
 */
class DatabaseRoleGrantMatrixIntegrationTest {

    /** Role column order: {@link Ui2PostgresFixture#MODULE_ROLES}. {@code -} = no privilege. */
    private static final String REPRESENTATIVE_MATRIX = """
            audit_log                      S    -    -    -    -    -    -    -    -    S    S
            audit_redaction_policy         -    -    -    -    -    -    -    -    -    -    -
            flyway_schema_history          -    -    -    -    -    -    -    -    -    -    -
            jobs                           SIUD SU   SU   SU   SIU  SU   SU   SU   -    SIU  SIU
            job_steps                      SIUD SIU  SIU  SIU  SIU  SIU  SIU  SIU  -    SU   SIU
            job_reconciliation             S    -    -    -    -    -    -    -    -    SI   SI
            module_runtime_control         S    S    S    S    S    S    S    S    S    S    S
            endpoint_admission             SIUD SIUD -    SIUD SIUD SIUD SIUD SIUD -    -    SIUD
            runtime_task_lease             SIUD SIUD SIUD SIUD SIUD SIUD SIUD SIUD SIUD SIUD SIUD
            gate_registry                  SIUD S    S    S    S    S    S    S    S    S    S
            provenance_records             SD   SI   SI   SI   SI   SI   SI   SI   -    -    SI
            devices                        SIUD S    -    S    SU   S    S    S    S    S    SU
            endpoints                      SIUD S    -    S    SU   S    S    S    S    S    SU
            credential_references          SIUD S    -    S    S    S    S    S    -    -    S
            credentials                    SIUD S    -    S    S    S    S    S    -    -    S
            management_endpoint_ssh_trust  SIU  SIU  -    SIU  SIU  SIU  SIU  SIU  -    -    SIU
            sessions                       SIUD -    -    -    -    -    -    -    -    -    -
            local_credentials              SIUD -    -    -    -    -    -    -    -    -    -
            rbac_roles                     SIUD -    -    -    -    -    -    -    -    -    -
            pseudonym_registry             SI   -    SI   -    -    -    -    -    -    -    -
            policy_snapshot                SD   SIU  -    -    -    -    -    -    S    -    SIU
            policy_rule_history            SD   S    -    -    -    -    -    -    S    -    S
            policy_collection_request      SIUD SU   -    -    -    -    -    -    -    SIU  SIU
            packet_analysis_run            SIUD -    SUD  -    -    -    -    -    -    -    -
            packet_analysis_ledger         SI   -    SI   -    -    -    -    -    -    -    -
            backup_artefact                SUD  -    -    SIUD -    -    -    S    S    S    SIUD
            backup_policy                  SU   -    -    S    -    -    -    -    -    SU   SU
            failover_run                   SIUD -    -    -    -    -    SU   -    -    SIU  SIU
            failover_schedule_ledger       SI   -    -    -    -    -    SI   -    -    SI   SI
            system_log_chunk               SD   -    -    -    -    SIUD -    -    -    -    SIUD
            path_validation                SI   -    -    -    -    SI   -    -    -    -    SI
            compliance_evaluation          SIUD -    -    -    -    -    -    -    SIUD -    -
            configuration_artefact         SD   -    -    -    -    S    S    SIU  S    -    SIU
            device_inventory_run           SD   -    -    -    SIU  -    S    -    S    S    SIU
            support_bundle_request         SIU  -    -    -    -    -    -    -    -    -    SIU
            artefact_store_refusal         SI   -    -    I    -    I    -    I    -    -    I
            zone_matrix_version            SIU  -    -    -    -    -    -    -    -    -    -
            local_break_glass_event        SI   -    -    -    -    -    -    -    -    -    -
            """;

    private static final List<String> TABLE_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    /** Tables nobody but the schema owner touches at runtime (design §2.3.1). */
    private static final Set<String> NO_RUNTIME_ACCESS = Set.of("flyway_schema_history", "audit_redaction_policy");

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("role_grant_matrix");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    // -----------------------------------------------------------------
    // Roles
    // -----------------------------------------------------------------

    @Test
    void moduleRolesAreNoLoginPasswordlessAndAttributeless() throws SQLException {
        try (Connection admin = fixture.adminConnection(); Statement statement = admin.createStatement()) {
            for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                try (ResultSet row = statement.executeQuery("SELECT r.rolcanlogin, r.rolsuper, r.rolcreatedb, "
                        + "r.rolcreaterole, r.rolreplication, r.rolbypassrls, a.rolpassword IS NULL "
                        + "FROM pg_roles r JOIN pg_authid a ON a.oid = r.oid WHERE r.rolname = '" + role + "'")) {
                    assertTrue(row.next(), role + " must exist");
                    assertFalse(row.getBoolean(1), role + " must be NOLOGIN until a credential is delivered");
                    assertFalse(row.getBoolean(2), role + " must not be superuser");
                    assertFalse(row.getBoolean(3), role + " must not create databases");
                    assertFalse(row.getBoolean(4), role + " must not create roles");
                    assertFalse(row.getBoolean(5), role + " must not replicate");
                    assertFalse(row.getBoolean(6), role + " must not bypass row-level security");
                    assertTrue(row.getBoolean(7), role + " must carry no password");
                }
            }
        }
    }

    @Test
    void migrationSqlIsAdditiveAndCarriesNoSecret() throws IOException {
        String sql = Files.readString(Ui2PostgresFixture.migrationDirectory()
                .resolve("V242__module_database_roles_grants.sql"));
        StringBuilder code = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.stripLeading().startsWith("--")) {
                code.append(line).append('\n');
            }
        }
        String body = code.toString().toUpperCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("CREATE ROLE", "ALTER ROLE", "DROP ", "WITH PASSWORD", "ENCRYPTED PASSWORD", "PASSWORD '", " LOGIN", "TRUNCATE",
                "TO UI2_APP", "FROM UI2_APP", "ALTER TABLE", "ALTER FUNCTION", "CREATE TRIGGER", "ALTER DEFAULT")) {
            assertFalse(body.contains(forbidden), "V242 must not contain: " + forbidden.strip());
        }
        // The only REVOKE is the one that keeps the re-apply function away from PUBLIC.
        assertEquals(1, body.split("REVOKE ", -1).length - 1, "V242 may contain exactly one REVOKE");
        assertTrue(body.contains("REVOKE ALL ON FUNCTION UI2_APPLY_MODULE_ROLE_GRANTS(TEXT) FROM PUBLIC"));
    }

    // -----------------------------------------------------------------
    // Representative tables: catalog answer and real execution
    // -----------------------------------------------------------------

    @Test
    void representativeTables_catalogPrivilegesMatchTheMatrixExactly() throws SQLException {
        Map<String, String[]> expected = representativeMatrix();
        List<String> mismatches = new ArrayList<>();
        try (Connection admin = fixture.adminDatabaseConnection();
                PreparedStatement check = admin.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
            for (Map.Entry<String, String[]> table : expected.entrySet()) {
                for (int i = 0; i < Ui2PostgresFixture.MODULE_ROLES.size(); i++) {
                    String role = Ui2PostgresFixture.MODULE_ROLES.get(i);
                    Set<String> allowed = privileges(table.getValue()[i]);
                    for (String privilege : TABLE_PRIVILEGES) {
                        check.setString(1, role);
                        check.setString(2, "public." + table.getKey());
                        check.setString(3, privilege);
                        try (ResultSet row = check.executeQuery()) {
                            row.next();
                            if (row.getBoolean(1) != allowed.contains(privilege)) {
                                mismatches.add(role + " " + privilege + " on " + table.getKey()
                                        + ": expected " + allowed.contains(privilege));
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches, "role/table privileges that differ from the design matrix");
    }

    @Test
    void representativeTables_realStatementsAreAllowedOrDeniedExactlyAsGranted() throws SQLException {
        Map<String, String[]> expected = representativeMatrix();
        List<String> mismatches = new ArrayList<>();
        try (Connection admin = fixture.adminDatabaseConnection()) {
            admin.setAutoCommit(false);
            Map<String, String> firstColumn = new LinkedHashMap<>();
            try (Statement statement = admin.createStatement()) {
                // Row triggers off: this test proves the table privilege itself. Several INVOKER trigger functions
                // read other planes (design finding F1) and would otherwise add their own 42501 to the probe.
                statement.execute("SET session_replication_role = replica");
                for (String table : expected.keySet()) {
                    try (ResultSet row = statement.executeQuery("SELECT attname FROM pg_attribute WHERE attrelid = "
                            + "'public." + table + "'::regclass AND attnum > 0 AND NOT attisdropped "
                            + "ORDER BY attnum LIMIT 1")) {
                        row.next();
                        firstColumn.put(table, row.getString(1));
                    }
                }
            }
            for (int i = 0; i < Ui2PostgresFixture.MODULE_ROLES.size(); i++) {
                String role = Ui2PostgresFixture.MODULE_ROLES.get(i);
                try (Statement statement = admin.createStatement()) {
                    statement.execute("SET ROLE " + role);
                    for (Map.Entry<String, String[]> table : expected.entrySet()) {
                        Set<String> allowed = privileges(table.getValue()[i]);
                        String name = "public." + table.getKey();
                        String column = "\"" + firstColumn.get(table.getKey()) + "\"";
                        Map<String, String> probes = new LinkedHashMap<>();
                        probes.put("SELECT", "SELECT count(*) FROM " + name);
                        probes.put("INSERT", "INSERT INTO " + name + " DEFAULT VALUES");
                        probes.put("UPDATE", "UPDATE " + name + " SET " + column + " = DEFAULT WHERE false");
                        probes.put("DELETE", "DELETE FROM " + name + " WHERE false");
                        probes.put("TRUNCATE", "TRUNCATE " + name);
                        for (Map.Entry<String, String> probe : probes.entrySet()) {
                            boolean granted = !denied(admin, statement, probe.getValue());
                            if (granted != allowed.contains(probe.getKey())) {
                                mismatches.add(role + " " + probe.getKey() + " on " + table.getKey()
                                        + ": expected " + (allowed.contains(probe.getKey()) ? "allowed" : "42501"));
                            }
                        }
                    }
                    statement.execute("RESET ROLE");
                }
            }
            admin.rollback();
        }
        assertEquals(List.of(), mismatches, "statements whose outcome differs from the grant matrix");
    }

    @Test
    void realRowWork_serviceWritesAudited_workersAndOutsidersAreRefused() throws SQLException {
        String credentialReference;
        String device;
        try (Connection migrate = fixture.migrateConnection()) {
            credentialReference = Ui2Rows.insertCredentialReference(migrate);
            device = Ui2Rows.insertDevice(migrate, credentialReference, "DRAFT");
        }
        try (Connection admin = fixture.adminDatabaseConnection()) {
            admin.setAutoCommit(false);
            try (Statement statement = admin.createStatement()) {
                // ui2_service: reads and updates a device; the audit trigger (SECURITY DEFINER) writes audit_log
                // although ui2_service itself cannot.
                statement.execute("SET ROLE ui2_service");
                Ui2Rows.setAuditContext(admin, "synthetic-actor", "role_grant_matrix.update");
                assertEquals(1, statement.executeUpdate(
                        "UPDATE devices SET disabled = true WHERE device_id = '" + device + "'"));
                assertTrue(Ui2Rows.countAuditRows(admin, "devices", device) >= 1,
                        "the device update must have been audited");
                assertTrue(denied(admin, statement, "INSERT INTO audit_log DEFAULT VALUES"),
                        "ui2_service must not write audit_log directly");
                assertTrue(denied(admin, statement, "DELETE FROM audit_log WHERE false"));
                assertTrue(denied(admin, statement, "SELECT 1 FROM audit_redaction_policy"));
                statement.execute("RESET ROLE");

                // ui2_policy: reads the device, cannot change or delete it, cannot read a plane it does not own.
                statement.execute("SET ROLE ui2_policy");
                try (ResultSet row = statement.executeQuery(
                        "SELECT count(*) FROM devices WHERE device_id = '" + device + "'")) {
                    row.next();
                    assertEquals(1L, row.getLong(1));
                }
                assertTrue(denied(admin, statement, "UPDATE devices SET disabled = false WHERE device_id = '" + device + "'"));
                assertTrue(denied(admin, statement, "DELETE FROM devices WHERE device_id = '" + device + "'"));
                assertTrue(denied(admin, statement, "SELECT 1 FROM packet_analysis_case"));
                assertTrue(denied(admin, statement, "SELECT 1 FROM failover_approval"));
                assertTrue(denied(admin, statement, "SELECT 1 FROM sessions"));
                statement.execute("RESET ROLE");

                // ui2_packet_analysis: the highest-isolation role sees none of the device, credential or identity planes.
                statement.execute("SET ROLE ui2_packet_analysis");
                for (String table : List.of("devices", "endpoints", "credentials", "credential_references",
                        "sessions", "local_credentials", "policy_snapshot", "failover_approval")) {
                    assertTrue(denied(admin, statement, "SELECT 1 FROM " + table), "packet_analysis reads " + table);
                }
                assertFalse(denied(admin, statement, "SELECT 1 FROM packet_analysis_run"));
                statement.execute("RESET ROLE");

                // No role can change the schema, whatever its table grants.
                for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                    statement.execute("SET ROLE " + role);
                    assertTrue(denied(admin, statement, "CREATE TABLE public.module_role_must_not_create (x TEXT)"), role);
                    assertTrue(denied(admin, statement, "DROP TABLE public.devices"), role);
                    assertTrue(denied(admin, statement, "ALTER TABLE public.devices ADD COLUMN x TEXT"), role);
                    assertTrue(denied(admin, statement, "CREATE TRIGGER module_role_trg AFTER INSERT ON public.devices "
                            + "FOR EACH ROW EXECUTE FUNCTION public.fn_audit_capture('device_id')"), role);
                    assertTrue(denied(admin, statement, "CREATE ROLE module_role_must_not_create"), role);
                    statement.execute("RESET ROLE");
                }
            }
            admin.rollback();
        }
    }

    // -----------------------------------------------------------------
    // Whole catalog
    // -----------------------------------------------------------------

    @Test
    void everyTableIsCoveredByTheMatrixOrDeclaredNoRuntimeAccess() throws SQLException {
        List<String> uncovered = new ArrayList<>();
        try (Connection admin = fixture.adminDatabaseConnection(); Statement statement = admin.createStatement()) {
            for (String table : publicTables(statement)) {
                boolean any = false;
                for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                    any |= hasAny(statement, role, table, List.of("SELECT", "INSERT", "UPDATE", "DELETE"));
                }
                if (any == NO_RUNTIME_ACCESS.contains(table)) {
                    uncovered.add(table + (any ? " (declared no-runtime-access but granted)" : " (no module grants)"));
                }
            }
        }
        assertEquals(List.of(), uncovered, "every public table needs module grants in its migration and a matrix "
                + "entry (design §4.3.2); only " + NO_RUNTIME_ACCESS + " are schema-owner-only");
    }

    @Test
    void noModuleRoleExceedsUi2AppOrHoldsTruncateReferencesTriggerOrOwnsAnything() throws SQLException {
        List<String> violations = new ArrayList<>();
        try (Connection admin = fixture.adminDatabaseConnection(); Statement statement = admin.createStatement()) {
            for (String table : publicTables(statement)) {
                for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                    for (String privilege : List.of("TRUNCATE", "REFERENCES", "TRIGGER")) {
                        if (has(statement, role, table, privilege)) {
                            violations.add(role + " holds " + privilege + " on " + table);
                        }
                    }
                    for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                        if (has(statement, role, table, privilege) && !has(statement, "ui2_app", table, privilege)) {
                            violations.add(role + " holds " + privilege + " on " + table + " that ui2_app lacks");
                        }
                    }
                }
            }
            for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                assertFalse(scalarBoolean(statement, "SELECT has_schema_privilege('" + role + "', 'public', 'CREATE')"),
                        role + " must not create objects in schema public");
                assertTrue(scalarBoolean(statement, "SELECT has_schema_privilege('" + role + "', 'public', 'USAGE')"),
                        role + " needs schema usage");
                assertEquals(0L, scalarLong(statement, "SELECT count(*) FROM pg_class WHERE relowner = '" + role
                        + "'::regrole"), role + " must own no relation");
                assertEquals(0L, scalarLong(statement, "SELECT count(*) FROM pg_proc WHERE proowner = '" + role
                        + "'::regrole"), role + " must own no function");
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void credentialIdentityAndAuditPlanesAreReachableOnlyAsTheDesignAllows() throws SQLException {
        List<String> violations = new ArrayList<>();
        List<String> serviceOnlyWrites = List.of("credentials", "credential_references", "device_secret_reference",
                "secrets_metadata", "local_credentials", "sessions", "role_bindings", "rbac_roles",
                "rbac_role_permissions", "ldap_settings", "sanitize_vault_entry", "pseudonym_registry");
        List<String> credentialPlane = List.of("credentials", "credential_references", "device_secret_reference",
                "secrets_metadata", "management_endpoint_ssh_trust", "ssh_host_key_observation",
                "https_endpoint_cert_trust", "management_endpoint_pan_cert_trust", "ssh_trust_settings");
        try (Connection admin = fixture.adminDatabaseConnection(); Statement statement = admin.createStatement()) {
            for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                if (!role.equals("ui2_service")) {
                    for (String table : serviceOnlyWrites) {
                        if (role.equals("ui2_packet_analysis") && table.equals("pseudonym_registry")) {
                            continue; // design Q3: append-only mint, checked below
                        }
                        if (hasAny(statement, role, table, List.of("INSERT", "UPDATE", "DELETE"))) {
                            violations.add(role + " writes " + table);
                        }
                    }
                    for (String table : List.of("devices", "endpoints")) {
                        if (has(statement, role, table, "DELETE")) {
                            violations.add(role + " deletes from " + table);
                        }
                    }
                    for (String table : List.of("sessions", "local_credentials", "role_bindings", "rbac_roles",
                            "ldap_settings", "sanitize_vault_entry", "root_identity")) {
                        if (has(statement, role, table, "SELECT")) {
                            violations.add(role + " reads " + table);
                        }
                    }
                }
                if (List.of("ui2_packet_analysis", "ui2_compliance", "ui2_scheduler").contains(role)) {
                    for (String table : credentialPlane) {
                        if (hasAny(statement, role, table, List.of("SELECT", "INSERT", "UPDATE", "DELETE"))) {
                            violations.add(role + " reaches credential-plane table " + table);
                        }
                    }
                }
                if (role.equals("ui2_packet_analysis")) {
                    for (String table : List.of("devices", "endpoints", "endpoint_admission", "backup_artefact")) {
                        if (hasAny(statement, role, table, List.of("SELECT", "INSERT", "UPDATE", "DELETE"))) {
                            violations.add(role + " reaches " + table);
                        }
                    }
                }
                if (role.equals("ui2_compliance") && hasAny(statement, role, "endpoint_admission",
                        List.of("SELECT", "INSERT", "UPDATE", "DELETE"))) {
                    violations.add(role + " reaches endpoint_admission");
                }
                for (String immutable : List.of("flyway_schema_history", "audit_redaction_policy")) {
                    if (hasAny(statement, role, immutable, List.of("SELECT", "INSERT", "UPDATE", "DELETE"))) {
                        violations.add(role + " reaches " + immutable);
                    }
                }
                for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
                    if (has(statement, role, "audit_log", privilege)) {
                        violations.add(role + " holds " + privilege + " on audit_log");
                    }
                }
                boolean readsAudit = has(statement, role, "audit_log", "SELECT");
                boolean mayReadAudit = List.of("ui2_service", "ui2_scheduler", "ui2_worker_general").contains(role);
                if (readsAudit != mayReadAudit) {
                    violations.add(role + " audit_log SELECT is " + readsAudit);
                }
            }
            // pseudonym_registry: append-only for the two roles that mint (design Q3 keeps packet_analysis minting).
            for (String role : List.of("ui2_service", "ui2_packet_analysis")) {
                assertTrue(has(statement, role, "pseudonym_registry", "INSERT"), role);
                assertFalse(hasAny(statement, role, "pseudonym_registry", List.of("UPDATE", "DELETE")), role);
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void sequencesFunctionsAndColumnGrantsFollowTheMatrix() throws SQLException {
        try (Connection admin = fixture.adminDatabaseConnection(); Statement statement = admin.createStatement()) {
            // endpoint_permit_epoch: the device-contacting modules only.
            Set<String> epoch = Set.of("ui2_service", "ui2_policy", "ui2_backup", "ui2_inventory", "ui2_diagnostics",
                    "ui2_failover", "ui2_configuration", "ui2_worker_general");
            for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                assertEquals(epoch.contains(role), scalarBoolean(statement,
                        "SELECT has_sequence_privilege('" + role + "', 'public.endpoint_permit_epoch', 'USAGE')"), role);
                assertFalse(scalarBoolean(statement,
                        "SELECT has_sequence_privilege('" + role + "', 'public.endpoint_permit_epoch', 'UPDATE')"), role);
            }
            // module_runtime_control: column-level UPDATE of the runtime metrics, never the table, never the key.
            List<String> runtimeColumns = List.of("owner_heartbeat_at", "owner_instance", "drain_ack_at",
                    "drain_ack_generation", "pool_active", "pool_idle", "pool_pending", "pool_timeouts",
                    "pool_wait_nanos", "admission_grants", "admission_wait_ms", "drain_requested", "drain_generation");
            for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                boolean worker = !role.equals("ui2_service");
                assertFalse(has(statement, role, "module_runtime_control", "UPDATE"), role + " table-level UPDATE");
                for (String column : runtimeColumns) {
                    assertEquals(worker, scalarBoolean(statement, "SELECT has_column_privilege('" + role
                            + "', 'public.module_runtime_control', '" + column + "', 'UPDATE')"), role + " " + column);
                }
                for (String column : List.of("module", "effective_owner", "fallback_enabled", "generation")) {
                    assertFalse(scalarBoolean(statement, "SELECT has_column_privilege('" + role
                            + "', 'public.module_runtime_control', '" + column + "', 'UPDATE')"), role + " " + column);
                }
            }
            // Audit RPC functions: ui2_service only (PUBLIC was revoked when they were created).
            for (String function : List.of("audit_machine_session_event(text, text)", "audit_report_download(uuid)",
                    "audit_support_bundle_download(uuid)")) {
                for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                    assertEquals(role.equals("ui2_service"), scalarBoolean(statement,
                            "SELECT has_function_privilege('" + role + "', 'public." + function + "', 'EXECUTE')"),
                            role + " " + function);
                }
            }
            // The re-apply function is for the schema owner only: no module role and not ui2_app.
            for (String role : combine(Ui2PostgresFixture.MODULE_ROLES, List.of("ui2_app"))) {
                assertFalse(scalarBoolean(statement, "SELECT has_function_privilege('" + role
                        + "', 'public.ui2_apply_module_role_grants(text)', 'EXECUTE')"), role);
            }
        }
    }


    @Test
    void inClusterHookSqlCreatesNoLoginRolesAndTheGrantStatementRuns() throws Exception {
        java.nio.file.Path configMap = java.nio.file.Paths.get(System.getProperty("user.dir")).getParent().getParent()
                .resolve("deploy/ui2/10-configmap.yaml");
        String roleSql = heredoc(Files.readString(configMap), "UI2_ROLES_SQL");
        String grantSql = heredoc(Files.readString(configMap), "UI2_GRANTS_SQL");
        assertTrue(roleSql.contains("NOLOGIN") && !roleSql.toUpperCase(java.util.Locale.ROOT).contains("PASSWORD"),
                "the hook must create the module roles NOLOGIN and without a credential");
        // Same statement with a throwaway prefix, so the real roles are untouched and the creation path is exercised.
        String probeSql = roleSql.replace("'ui2_", "'zzhook_");
        List<String> created = new ArrayList<>();
        for (String suffix : List.of("service", "policy", "packet_analysis", "backup", "inventory", "diagnostics",
                "failover", "configuration", "compliance", "scheduler", "worker_general")) {
            created.add("zzhook_" + suffix);
        }
        try (Connection admin = fixture.adminConnection(); Statement statement = admin.createStatement()) {
            try {
                statement.execute(probeSql);
                statement.execute(probeSql); // idempotent: a second start changes nothing
                for (String role : created) {
                    try (ResultSet row = statement.executeQuery("SELECT rolcanlogin, rolsuper, rolcreaterole, "
                            + "rolbypassrls FROM pg_roles WHERE rolname = '" + role + "'")) {
                        assertTrue(row.next(), role);
                        assertFalse(row.getBoolean(1) || row.getBoolean(2) || row.getBoolean(3) || row.getBoolean(4), role);
                    }
                }
            } finally {
                for (String role : created) {
                    statement.execute("DROP ROLE IF EXISTS " + role);
                }
            }
        }
        try (Connection admin = fixture.adminDatabaseConnection(); Statement statement = admin.createStatement()) {
            statement.execute(grantSql); // the real roles exist in the fixture: applies, returns without error
        }
    }

    // -----------------------------------------------------------------
    // Idempotence, absent roles, dry run
    // -----------------------------------------------------------------

    @Test
    void reapplyingIsIdempotentAndCountsNoMissingRole() throws SQLException {
        try (Connection migrate = fixture.migrateConnection(); Statement statement = migrate.createStatement()) {
            String before = privilegeSnapshot(statement);
            assertEquals(0L, scalarLong(statement, "SELECT ui2_apply_module_role_grants()"));
            assertEquals(0L, scalarLong(statement, "SELECT ui2_apply_module_role_grants()"));
            assertEquals(before, privilegeSnapshot(statement), "re-applying the grants must change no privilege");
        }
    }

    @Test
    void rolesThatDoNotExistAreSkippedWithoutErrorAndWithoutAnyGrant() throws SQLException {
        try (Connection migrate = fixture.migrateConnection(); Statement statement = migrate.createStatement()) {
            String before = privilegeSnapshot(statement);
            assertEquals((long) Ui2PostgresFixture.MODULE_ROLES.size(),
                    scalarLong(statement, "SELECT ui2_apply_module_role_grants('absent_module_')"));
            assertEquals(before, privilegeSnapshot(statement));
        }
    }

    @Test
    void dryRunInsideARolledBackTransactionLeavesNoTrace() throws SQLException {
        try (Connection migrate = fixture.migrateConnection(); Statement statement = migrate.createStatement()) {
            String before = privilegeSnapshot(statement);
            migrate.setAutoCommit(false);
            statement.execute("SELECT ui2_apply_module_role_grants()");
            migrate.rollback();
            migrate.setAutoCommit(true);
            assertEquals(before, privilegeSnapshot(statement));
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** The SQL between {@code <<'<tag>'} and the closing tag line of the DB hook, with the YAML indent removed. */
    private static String heredoc(String configMap, String tag) {
        int start = configMap.indexOf("<<'" + tag + "'");
        int bodyStart = configMap.indexOf('\n', start) + 1;
        int end = configMap.indexOf("\n    " + tag, bodyStart);
        assertTrue(start >= 0 && end > bodyStart, "hook block " + tag + " not found");
        StringBuilder sql = new StringBuilder();
        for (String line : configMap.substring(bodyStart, end).split("\n")) {
            sql.append(line.startsWith("    ") ? line.substring(4) : line).append('\n');
        }
        return sql.toString();
    }

    private static Map<String, String[]> representativeMatrix() {
        Map<String, String[]> matrix = new LinkedHashMap<>();
        for (String line : REPRESENTATIVE_MATRIX.strip().split("\n")) {
            String[] tokens = line.strip().split("\\s+");
            assertEquals(1 + Ui2PostgresFixture.MODULE_ROLES.size(), tokens.length, "matrix row: " + line);
            String[] roles = new String[Ui2PostgresFixture.MODULE_ROLES.size()];
            System.arraycopy(tokens, 1, roles, 0, roles.length);
            matrix.put(tokens[0], roles);
        }
        return matrix;
    }

    private static Set<String> privileges(String letters) {
        Set<String> privileges = new TreeSet<>();
        if (letters.contains("S")) privileges.add("SELECT");
        if (letters.contains("I")) privileges.add("INSERT");
        if (letters.contains("U")) privileges.add("UPDATE");
        if (letters.contains("D")) privileges.add("DELETE");
        return privileges;
    }

    /** Runs {@code sql} under a savepoint and reports whether it was refused with 42501 (insufficient_privilege). */
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

    private static List<String> publicTables(Statement statement) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                + "ORDER BY tablename")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    private static boolean has(Statement statement, String role, String table, String privilege) throws SQLException {
        return scalarBoolean(statement, "SELECT has_table_privilege('" + role + "', 'public." + table + "', '"
                + privilege + "')");
    }

    private static boolean hasAny(Statement statement, String role, String table, List<String> privileges)
            throws SQLException {
        for (String privilege : privileges) {
            if (has(statement, role, table, privilege)) {
                return true;
            }
        }
        return false;
    }

    private static boolean scalarBoolean(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getBoolean(1);
        }
    }

    private static long scalarLong(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static List<String> combine(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** Every ACL in the schema: relations, columns, functions and the schema itself, as one comparable string. */
    private static String privilegeSnapshot(Statement statement) throws SQLException {
        StringBuilder snapshot = new StringBuilder();
        try (ResultSet rows = statement.executeQuery(
                "SELECT 'rel ' || c.relname || ' ' || coalesce(c.relacl::text, '-') FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' "
                        + "UNION ALL SELECT 'col ' || c.relname || '.' || a.attname || ' ' || a.attacl::text "
                        + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' "
                        + "AND a.attacl IS NOT NULL "
                        + "UNION ALL SELECT 'fn ' || p.oid::regprocedure::text || ' ' || coalesce(p.proacl::text, '-') "
                        + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' "
                        + "UNION ALL SELECT 'ns ' || coalesce(nspacl::text, '-') FROM pg_namespace "
                        + "WHERE nspname = 'public' "
                        + "UNION ALL SELECT 'db ' || coalesce(datacl::text, '-') FROM pg_database "
                        + "WHERE datname = current_database() ORDER BY 1")) {
            while (rows.next()) {
                snapshot.append(rows.getString(1)).append('\n');
            }
        }
        return snapshot.toString();
    }
}
