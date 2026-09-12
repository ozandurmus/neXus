package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;

/**
 * B1-4b contract §8 test 7 ({@code audit_row_written_for_every_mutation}) as
 * it applies to registration's paired {@code devices}/{@code endpoints}
 * INSERT, proved against a real PostgreSQL 16 server through the production
 * {@link JooqDeviceRepository}.
 *
 * <p>The generic trigger mechanism has its own proofs
 * ({@code AuditAtomicityTest}, {@code AuditContextMissingFailsClosedTest});
 * this class proves only the registration-specific assertion those do not:
 * ONE {@code registerDraft} call produces exactly TWO {@code audit_log} rows
 * (one per table) in the SAME transaction, never one without the other.</p>
 */
class DeviceRegistrationAuditRowTest {

    private static Ui2PostgresFixture fixture;
    private static TransactionBoundary transactionBoundary;
    private static DeviceRepository repository;
    private static String credentialReferenceId;

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("device_registration_audit");
        transactionBoundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        repository = new JooqDeviceRepository(transactionBoundary);
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
    void oneRegistrationProducesExactlyTwoAuditRowsInOneTransaction() throws SQLException {
        String deviceId = Ui2Rows.opaqueId("dev-reg");
        String endpointId = Ui2Rows.opaqueId("ep-reg");
        String actor = "registering-actor-fingerprint";
        String action = "device.register_draft";

        String registered = repository.registerDraft(draft(deviceId, endpointId), actor, action);
        assertEquals(deviceId, registered);

        try (Connection app = fixture.appConnection()) {
            assertEquals(1L, Ui2Rows.count(app,
                    "SELECT count(*) FROM devices WHERE device_id = '" + deviceId + "'"));
            assertEquals(1L, Ui2Rows.count(app,
                    "SELECT count(*) FROM endpoints WHERE endpoint_id = '" + endpointId + "'"));

            List<String> auditRows = auditRowsFor(app, actor, action);
            assertEquals(List.of("devices/" + deviceId, "endpoints/" + endpointId), auditRows,
                    "one registerDraft call must produce exactly two audit_log rows -- one per table -- "
                            + "sharing the same actor_fingerprint and action_id");
        }
    }

    @Test
    void aRegistrationThatFailsHalfwayLeavesNeitherRowAndNeitherAuditRow() throws SQLException {
        // The "never one without the other" half. The endpoints INSERT is made
        // to fail (its endpoint_id already exists), so the devices INSERT that
        // already succeeded inside the same transaction must disappear with it.
        String firstDeviceId = Ui2Rows.opaqueId("dev-first");
        String sharedEndpointId = Ui2Rows.opaqueId("ep-shared");
        String actor = "registering-actor-fingerprint";
        String action = "device.register_draft.conflict";

        repository.registerDraft(draft(firstDeviceId, sharedEndpointId), actor, "device.register_draft.first");

        String secondDeviceId = Ui2Rows.opaqueId("dev-second");
        DataAccessException failure = assertThrows(DataAccessException.class,
                () -> repository.registerDraft(draft(secondDeviceId, sharedEndpointId), actor, action));
        assertNotNull(failure.getCause(), "the conflict must surface the underlying SQL failure");

        try (Connection app = fixture.appConnection()) {
            assertEquals(0L, Ui2Rows.count(app,
                    "SELECT count(*) FROM devices WHERE device_id = '" + secondDeviceId + "'"),
                    "the devices INSERT must roll back with the failed endpoints INSERT");
            assertEquals(0L, Ui2Rows.countAuditRows(app, "devices", secondDeviceId),
                    "no audit_log row may survive the rolled-back registration");
            assertEquals(List.of(), auditRowsFor(app, actor, action),
                    "the failed registration must leave no audit_log row under its own action_id");
        }
    }

    @Test
    void aRawDevicesInsertBypassingTheAuditedBoundaryIsRefused() {
        // The plain TransactionBoundary sets no audit context; C1 §3.5's
        // trigger is what makes bypassing AuditedTransactionBoundary
        // fail-closed rather than merely discouraged.
        String deviceId = Ui2Rows.opaqueId("dev-bypass");
        DataAccessException refused = assertThrows(DataAccessException.class,
                () -> transactionBoundary.inTransaction(dsl -> dsl.execute(
                        "insert into devices(device_id, vendor_hint, registration_source, is_test_target, "
                                + "enrollment_state, disabled, credential_reference_id) "
                                + "values ({0}, 'harness-vendor', 'manual', true, 'DRAFT', false, {1})",
                        deviceId, credentialReferenceId)));
        assertTrue(refused.getMessage().contains("audit_context_missing")
                        || (refused.getCause() != null
                                && refused.getCause().getMessage().contains("audit_context_missing")),
                "a mutation that bypasses AuditedTransactionBoundary must be refused by fn_audit_capture()");
    }

    private static DeviceDraft draft(String deviceId, String endpointId) {
        return new DeviceDraft(deviceId, "harness-vendor", "manual", true, credentialReferenceId,
                endpointId, "ssh_exec", "opaque-address-ref");
    }

    /** {@code table_name/row_pk} of every audit row carrying this actor/action pair, in write order. */
    private static List<String> auditRowsFor(Connection app, String actor, String action) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = app.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT table_name, row_pk FROM audit_log WHERE actor_fingerprint = '" + actor
                                + "' AND action_id = '" + action + "' ORDER BY audit_id")) {
            while (result.next()) {
                rows.add(result.getString(1) + "/" + result.getString(2));
            }
        }
        return rows;
    }
}
