package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.service.device.DeviceWorkspaceReader;

/**
 * Contract §8 test 11 ({@code WorkspaceReadsNoProjectionTable}) and the
 * task's hard rule that the standing PO collection gate is enforced in
 * code. A {@link DataSource}/{@link Connection}/{@link java.sql.Statement}
 * dynamic proxy captures every SQL string {@link DeviceWorkspaceReader}
 * actually sends to the driver -- a real runtime interception, not a source
 * read -- and this test fails if any of them names
 * {@code cp_inventory_projection} or {@code job}/{@code job_steps} (a table
 * a collection producer would also write).
 */
class DeviceWorkspaceProjectionTableGateTest {

    private static Ui2PostgresFixture fixture;
    private static DeviceRepository deviceRepository;
    private static String credentialReferenceId;
    private static final List<String> CAPTURED_SQL = new ArrayList<>();

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("device_workspace_gate");
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(
                DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        deviceRepository = new JooqDeviceRepository(transactionBoundary);
        try (Connection app = fixture.appConnection()) {
            credentialReferenceId = Ui2Rows.insertCredentialReference(app);
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void listDevicesIssuesNoSqlNamingAProjectionOrCollectionTable() {
        CAPTURED_SQL.clear();
        DeviceWorkspaceReader reader = new DeviceWorkspaceReader(sqlCapturingDataSource(fixture.appDataSource()));
        reader.listDevices();

        assertFalse(CAPTURED_SQL.isEmpty(), "the capturing proxy must actually observe SQL for this assertion to prove anything");
        assertNoForbiddenTable();
    }

    @Test
    void readWorkspaceIssuesNoSqlNamingAProjectionOrCollectionTable() {
        String deviceId = DeviceWorkspaceTestRows.opaqueId("dev-gate");
        deviceRepository.registerDraft(new DeviceDraft(deviceId, "v", "manual_registration", true,
                        credentialReferenceId, DeviceWorkspaceTestRows.opaqueId("ep"), "ssh_exec", "addr"),
                Ui2Rows.ACTOR, Ui2Rows.ACTION);

        CAPTURED_SQL.clear();
        DeviceWorkspaceReader reader = new DeviceWorkspaceReader(sqlCapturingDataSource(fixture.appDataSource()));
        reader.readWorkspace(deviceId);

        assertFalse(CAPTURED_SQL.isEmpty(), "the capturing proxy must actually observe SQL for this assertion to prove anything");
        assertNoForbiddenTable();
    }

    private static void assertNoForbiddenTable() {
        List<String> forbiddenTables = List.of("cp_inventory_projection", "job_steps", "job_step_attempt",
                "job_reconciliation", "provenance_records");
        for (String sql : CAPTURED_SQL) {
            String lower = sql.toLowerCase(Locale.ROOT);
            for (String table : forbiddenTables) {
                assertTrue(!lower.contains(table),
                        "the collection gate is violated: SQL referenced " + table + " -> " + sql);
            }
        }
    }

    // -----------------------------------------------------------------
    // A minimal Connection/Statement/PreparedStatement recording proxy.
    // -----------------------------------------------------------------

    private static DataSource sqlCapturingDataSource(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        Connection real = (Connection) method.invoke(delegate, args);
                        return capturingConnection(real);
                    }
                    return method.invoke(delegate, args);
                });
    }

    private static Connection capturingConnection(Connection real) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement") && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                CAPTURED_SQL.add(sql);
            }
            if (method.getName().equals("createStatement")) {
                Object realStatement = method.invoke(real, args);
                return capturingStatement(realStatement);
            }
            return method.invoke(real, args);
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
    }

    private static Object capturingStatement(Object realStatement) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ((method.getName().equals("executeQuery") || method.getName().equals("execute")
                    || method.getName().equals("executeUpdate"))
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                CAPTURED_SQL.add(sql);
            }
            return method.invoke(realStatement, args);
        };
        return Proxy.newProxyInstance(java.sql.Statement.class.getClassLoader(),
                new Class<?>[] {java.sql.Statement.class}, handler);
    }
}
