package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjection;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjector;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldState;
import com.securityexpert.nexus.ui2.service.audit.AuditRedactionPolicy;

/**
 * Contract §8 test 1 / AC-1: inserts a {@code sessions} row carrying a known
 * test {@code csrf_secret}, projects the resulting audit row's snapshot with
 * the production {@link AuditFieldProjector}, and asserts the *serialized*
 * projection contains neither the value nor the string {@code sha256:},
 * while still projecting as {@code REDACTED} with the policy's own
 * {@code reason}.
 *
 * <p>Proves the presentation boundary of §3.2 for a Tier 1 column, at the
 * projection layer named in scope — this movement adds no HTTP controller,
 * so "reaches the payload" is proved on the object the movement actually
 * ships: the fully serialized {@link AuditFieldProjection} map, which is
 * exactly what a future controller would hand to Jackson unmodified.</p>
 */
class AuditScreenRedactedValueNeverReachesPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_screen_redacted");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void redactedCsrfSecretNeverReachesTheSerializedProjection() throws Exception {
        String csrfSecret = "synthetic-csrf-secret-" + Ui2Rows.opaqueId("v");

        try (Connection app = fixture.appConnection()) {
            String sessionId = insertSession(app, csrfSecret);

            AuditRedactionPolicy policy = AuditProjectionTestSupport.loadPolicy(app);
            JsonNode afterState = AuditProjectionTestSupport.afterStateOf(app, "sessions", sessionId);
            assertNotNull(afterState, "the audit row must exist");

            AuditFieldProjector projector = new AuditFieldProjector(policy);
            Map<String, AuditFieldProjection> projection = projector.project("sessions", afterState);

            AuditFieldProjection csrfProjection = projection.get("csrf_secret");
            assertNotNull(csrfProjection, "csrf_secret must appear in the projection");
            assertEquals(AuditFieldState.REDACTED, csrfProjection.state());
            assertEquals(1, csrfProjection.tier());
            assertFalse(csrfProjection.reason() == null || csrfProjection.reason().isBlank(),
                    "a REDACTED field must carry the policy's own reason");

            String serialized = MAPPER.writeValueAsString(projection);
            assertFalse(serialized.contains(csrfSecret),
                    "the serialized projection must never contain the raw secret value");
            assertFalse(serialized.contains("sha256:"),
                    "the serialized projection must never contain the digest string either");
        }
    }

    /**
     * Non-vacuity proof: if the projector is made to leak the raw value (the
     * bug this test exists to catch), the assertion above must fail. Proven
     * by re-running the same scenario against a deliberately unredacted
     * projection built directly from the raw snapshot, and reverting.
     */
    @Test
    void proofOfNonVacuity_aLeakingProjectionIsCaughtByTheSameAssertion() throws Exception {
        String csrfSecret = "synthetic-csrf-secret-" + Ui2Rows.opaqueId("leak");
        try (Connection app = fixture.appConnection()) {
            String sessionId = insertSession(app, csrfSecret);
            JsonNode afterState = AuditProjectionTestSupport.afterStateOf(app, "sessions", sessionId);

            // Simulate what a defective projector would produce: the raw
            // (already-digested-at-storage) value copied straight through
            // as PRESENT instead of REDACTED. The digest itself is not the
            // plaintext, so this proves the *test's own sensitivity* to a
            // leak using the after_state's own stored digest string, which
            // must never appear in a correct projection's serialized form.
            String storedDigest = afterState.get("csrf_secret").asText();
            Map<String, AuditFieldProjection> leaking = Map.of(
                    "csrf_secret", AuditFieldProjection.present(afterState.get("csrf_secret")));
            String serializedLeak = MAPPER.writeValueAsString(leaking);

            assertTrue(serializedLeak.contains(storedDigest),
                    "sanity: the deliberately-leaking projection must contain the digest, "
                            + "proving the assertion below is not vacuous");
            assertTrue(serializedLeak.contains("sha256:"));

            // The real projector, run on the same snapshot, must not.
            AuditRedactionPolicy policy = AuditProjectionTestSupport.loadPolicy(app);
            Map<String, AuditFieldProjection> correct = new AuditFieldProjector(policy).project("sessions",
                    afterState);
            String serializedCorrect = MAPPER.writeValueAsString(correct);
            assertFalse(serializedCorrect.contains(storedDigest));
            assertFalse(serializedCorrect.contains("sha256:"));
        }
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
}
