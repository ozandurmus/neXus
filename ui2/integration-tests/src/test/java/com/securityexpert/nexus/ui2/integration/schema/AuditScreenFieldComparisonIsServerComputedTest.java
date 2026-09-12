package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.service.audit.AuditChangeState;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjector;
import com.securityexpert.nexus.ui2.service.audit.AuditRedactionPolicy;

/**
 * Contract §8 test 5 / AC part of §3.2: updates a redacted column and an
 * unredacted column on one audited row; asserts {@code CHANGED} and
 * {@code UNCHANGED} appear per key, {@code NOT_EVALUABLE} appears for an
 * {@code INSERT} row's {@code before_fields}, and no digest appears in the
 * comparison payload -- proving {@code B1-2a} §2's change-detection property
 * survives §3.2's projection.
 */
class AuditScreenFieldComparisonIsServerComputedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_screen_comparison");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void updateReportsChangedAndUnchangedPerKeyWithoutADigest() throws Exception {
        try (Connection app = fixture.appConnection()) {
            String sessionId = insertSession(app, "csrf-secret-original");
            updateSession(app, sessionId, "csrf-secret-updated", "REVOKED");

            AuditRedactionPolicy policy = AuditProjectionTestSupport.loadPolicy(app);
            AuditFieldProjector projector = new AuditFieldProjector(policy);

            // INSERT row: before_state is NULL, so every key is NOT_EVALUABLE.
            JsonNode insertBefore = AuditProjectionTestSupport.beforeStateOf(app, "sessions", sessionId, 1);
            JsonNode insertAfter = AuditProjectionTestSupport.afterStateOf(app, "sessions", sessionId, 1);
            Map<String, AuditChangeState> insertComparison =
                    projector.compareChangeStates("sessions", insertBefore, insertAfter);
            assertEquals(AuditChangeState.NOT_EVALUABLE, insertComparison.get("csrf_secret"),
                    "an INSERT row's before_fields have nothing to compare against");
            assertEquals(AuditChangeState.NOT_EVALUABLE, insertComparison.get("state"));

            // UPDATE row: csrf_secret changed (different digest), state changed
            // (ACTIVE -> ENDED); actor_fingerprint is unchanged.
            JsonNode updateBefore = AuditProjectionTestSupport.beforeStateOf(app, "sessions", sessionId, 2);
            JsonNode updateAfter = AuditProjectionTestSupport.afterStateOf(app, "sessions", sessionId, 2);
            Map<String, AuditChangeState> updateComparison =
                    projector.compareChangeStates("sessions", updateBefore, updateAfter);

            assertEquals(AuditChangeState.CHANGED, updateComparison.get("csrf_secret"),
                    "the redacted column's two different digests must compare CHANGED");
            assertEquals(AuditChangeState.CHANGED, updateComparison.get("state"));
            assertEquals(AuditChangeState.UNCHANGED, updateComparison.get("actor_fingerprint"));

            String serializedComparison = MAPPER.writeValueAsString(updateComparison);
            assertFalse(serializedComparison.contains("sha256:"),
                    "the comparison payload must report only the relationship, never a digest");
            assertFalse(serializedComparison.contains("csrf-secret-original"));
            assertFalse(serializedComparison.contains("csrf-secret-updated"));
        }
    }

    /**
     * Non-vacuity proof: comparing the two raw (undigested) plaintext values
     * directly -- the defect this mechanism must never regress to -- would
     * put the plaintext in the comparison inputs. Demonstrating that the
     * *stored* snapshot already holds only digests (never the plaintext) is
     * what makes the CHANGED assertion above meaningful rather than trivial.
     */
    @Test
    void theComparedSnapshotValuesAreAlreadyDigestsNeverPlaintext() throws Exception {
        try (Connection app = fixture.appConnection()) {
            String sessionId = insertSession(app, "csrf-secret-nonvacuity");
            updateSession(app, sessionId, "csrf-secret-nonvacuity-2", "ACTIVE");

            JsonNode updateAfter = AuditProjectionTestSupport.afterStateOf(app, "sessions", sessionId, 2);
            String storedValue = updateAfter.get("csrf_secret").asText();
            assertNotNull(storedValue);
            assertTrue(storedValue.startsWith("sha256:"),
                    "sanity: the value the comparison operates on is already a digest, "
                            + "so CHANGED/UNCHANGED never depends on comparing plaintext");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }

    private static String insertSession(Connection app, String csrfSecret) throws Exception {
        String sessionId = Ui2Rows.opaqueId("sess");
        // ux_sessions_one_active_per_actor allows only one ACTIVE session per
        // actor, so each test-scoped session needs its own actor identity.
        String actor = Ui2Rows.opaqueId("actor");
        Instant now = Instant.now();
        app.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(app, actor, "harness.audit_screen");
            try (Statement statement = app.createStatement()) {
                statement.execute("INSERT INTO sessions(session_id, actor_fingerprint, csrf_secret, state, "
                        + "idle_deadline_at, absolute_expires_at) VALUES ('" + sessionId + "', '"
                        + actor + "', '" + csrfSecret + "', 'ACTIVE', '"
                        + now.plus(15, ChronoUnit.MINUTES) + "', '" + now.plus(8, ChronoUnit.HOURS) + "')");
            }
            app.commit();
        } catch (Exception e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(true);
        }
        return sessionId;
    }

    private static void updateSession(Connection app, String sessionId, String newCsrfSecret, String newState)
            throws Exception {
        app.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.audit_screen");
            try (Statement statement = app.createStatement()) {
                statement.execute("UPDATE sessions SET csrf_secret = '" + newCsrfSecret + "', state = '"
                        + newState + "' WHERE session_id = '" + sessionId + "'");
            }
            app.commit();
        } catch (Exception e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(true);
        }
    }
}
