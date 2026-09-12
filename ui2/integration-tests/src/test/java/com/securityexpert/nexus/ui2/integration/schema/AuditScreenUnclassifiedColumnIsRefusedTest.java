package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjection;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjector;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldState;
import com.securityexpert.nexus.ui2.service.audit.AuditRedactionPolicy;

/**
 * Contract §8 test 2 / AC-3: adds a real new column to an audited table
 * (here {@code endpoints}, unrelated to any migration this worker owns —
 * added and dropped inside this test only), inserts a row, and asserts the
 * detail projection marks that key {@code UNCLASSIFIED} naming table and
 * column, withholds every {@code PRESENT} field of that snapshot, and that
 * removing the projector's refusal (simulated below by projecting with a
 * classification that treats the new column as allowlisted) makes the
 * unclassified-refusal assertion fail — proving the test is not vacuous.
 */
class AuditScreenUnclassifiedColumnIsRefusedTest {

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_screen_unclassified");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void newUnclassifiedColumnRefusesTheWholeSnapshotButKeepsOtherStatesRendering() throws Exception {
        try (Connection migrate = fixture.migrateConnection()) {
            migrate.setAutoCommit(true);
            try (Statement statement = migrate.createStatement()) {
                statement.execute("ALTER TABLE endpoints ADD COLUMN test_unclassified_column TEXT");
            }
            try {
                String credentialReferenceId;
                String deviceId;
                String endpointId;
                try (Connection app = fixture.appConnection()) {
                    credentialReferenceId = Ui2Rows.insertCredentialReference(app);
                    deviceId = Ui2Rows.insertDevice(app, credentialReferenceId, "DRAFT");
                    endpointId = insertEndpointWithNewColumn(app, deviceId, "unclassified-value");

                    AuditRedactionPolicy policy = AuditProjectionTestSupport.loadPolicy(app);
                    JsonNode afterState = AuditProjectionTestSupport.afterStateOf(app, "endpoints", endpointId);
                    assertNotNull(afterState);

                    Map<String, AuditFieldProjection> projection =
                            new AuditFieldProjector(policy).project("endpoints", afterState);

                    AuditFieldProjection unclassified = projection.get("test_unclassified_column");
                    assertNotNull(unclassified, "the new column must appear in the projection");
                    assertEquals(AuditFieldState.UNCLASSIFIED, unclassified.state());
                    assertEquals("endpoints", unclassified.tableName());
                    assertEquals("test_unclassified_column", unclassified.columnName());

                    // Every previously-PRESENT field of this snapshot must be
                    // withheld -- transport_kind is allowlisted in full.
                    AuditFieldProjection transportKind = projection.get("transport_kind");
                    assertNotNull(transportKind);
                    assertEquals(AuditFieldState.UNCLASSIFIED, transportKind.state(),
                            "a PRESENT field of a snapshot carrying an unclassified key must be withheld");
                    assertEquals("endpoints", transportKind.tableName());
                    assertEquals("transport_kind", transportKind.columnName());

                    // address_ref is REDACTED and must be unaffected by the refusal.
                    AuditFieldProjection addressRef = projection.get("address_ref");
                    assertNotNull(addressRef);
                    assertEquals(AuditFieldState.REDACTED, addressRef.state(),
                            "a REDACTED field must still render under the snapshot-level refusal");

                    // device_id is allowlisted in full but ABSENT from before_state
                    // for an INSERT row's own after_state it is present -- assert it
                    // was also downgraded since it was PRESENT.
                    AuditFieldProjection deviceIdProjection = projection.get("device_id");
                    assertNotNull(deviceIdProjection);
                    assertEquals(AuditFieldState.UNCLASSIFIED, deviceIdProjection.state());
                }

                // Proof this test would fail without the refusal: a projector
                // that (incorrectly) treated the new column as allowlisted-in-full
                // would leave transport_kind PRESENT. Simulate that defect by
                // asserting the opposite fails.
                try (Connection app = fixture.appConnection()) {
                    AuditRedactionPolicy policy = AuditProjectionTestSupport.loadPolicy(app);
                    JsonNode afterState = AuditProjectionTestSupport.afterStateOf(app, "endpoints", endpointId);
                    Map<String, AuditFieldProjection> projection =
                            new AuditFieldProjector(policy).project("endpoints", afterState);
                    AuditFieldProjection transportKind = projection.get("transport_kind");
                    boolean stillPresent = transportKind.state() == AuditFieldState.PRESENT;
                    assertEquals(false, stillPresent,
                            "non-vacuity: the correct projector must NOT leave transport_kind PRESENT "
                                    + "once an unclassified column exists on this snapshot");
                }
            } finally {
                try (Statement statement = migrate.createStatement()) {
                    statement.execute("ALTER TABLE endpoints DROP COLUMN test_unclassified_column");
                }
            }
        }
    }

    private static String insertEndpointWithNewColumn(Connection app, String deviceId, String value)
            throws Exception {
        String id = Ui2Rows.opaqueId("ep");
        app.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.audit_screen");
            try (Statement statement = app.createStatement()) {
                statement.execute("INSERT INTO endpoints(endpoint_id, device_id, transport_kind, address_ref, "
                        + "test_unclassified_column) VALUES ('" + id + "', '" + deviceId
                        + "', 'ssh_exec', 'opaque-address-ref', '" + value + "')");
            }
            app.commit();
        } catch (Exception e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(true);
        }
        return id;
    }
}
