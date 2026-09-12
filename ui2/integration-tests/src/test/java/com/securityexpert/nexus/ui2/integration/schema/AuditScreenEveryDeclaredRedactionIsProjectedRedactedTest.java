package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjection;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjector;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldState;
import com.securityexpert.nexus.ui2.service.audit.AuditRedactionPolicy;

/**
 * Contract §8 test 4: one assertion per row of {@code audit_redaction_policy}
 * (naming its table and column, not a blanket scan, mirroring {@code B1-2a}
 * §6.4). Fails if any declared redaction projects as {@code PRESENT}. Loads
 * the policy from a real, migrated PostgreSQL 16 server -- the same seven
 * columns V5 seeds -- so a future edit to the seed data is caught here too.
 */
class AuditScreenEveryDeclaredRedactionIsProjectedRedactedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Ui2PostgresFixture fixture;
    private static AuditRedactionPolicy policy;

    @BeforeAll
    static void migrate() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_screen_every_redaction");
        try (Connection app = fixture.appConnection()) {
            policy = AuditProjectionTestSupport.loadPolicy(app);
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void sessionsCsrfSecretProjectsRedacted() {
        assertRedacted("sessions", "csrf_secret");
    }

    @Test
    void roleBindingsGroupReferenceEncryptedProjectsRedacted() {
        assertRedacted("role_bindings", "group_reference_encrypted");
    }

    @Test
    void credentialReferencesBackendPointerProjectsRedacted() {
        assertRedacted("credential_references", "backend_pointer");
    }

    @Test
    void secretsMetadataReferencePointerProjectsRedacted() {
        assertRedacted("secrets_metadata", "reference_pointer");
    }

    @Test
    void endpointsAddressRefProjectsRedacted() {
        assertRedacted("endpoints", "address_ref");
    }

    @Test
    void jobStepAttemptCapturedVariablesProjectsRedacted() {
        assertRedacted("job_step_attempt", "captured_variables");
    }

    @Test
    void jobReconciliationEvidenceProjectsRedacted() {
        assertRedacted("job_reconciliation", "evidence");
    }

    private static void assertRedacted(String table, String column) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put(column, "synthetic-nonnull-value-for-" + table + "." + column);
        AuditFieldProjection projection = new AuditFieldProjector(policy)
                .project(table, (JsonNode) snapshot)
                .get(column);
        assertEquals(AuditFieldState.REDACTED, projection.state(),
                table + "." + column + " is declared redacted by V5 and must never project PRESENT");
    }
}
