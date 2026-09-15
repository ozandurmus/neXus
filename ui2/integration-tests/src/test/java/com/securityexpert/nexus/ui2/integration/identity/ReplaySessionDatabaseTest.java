package com.securityexpert.nexus.ui2.integration.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.JooqSessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.ReplaySessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.service.security.ReplayProjector;

/** Required real PostgreSQL carrier: missing infrastructure fails, never skips. */
class ReplaySessionDatabaseTest {
    @Test
    void atomicTakeoverDurableCustodyAndRealActorAudit() throws Exception {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.createAndMigrate("replay_session")) {
            var dsl = DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES);
            var transactions = new JooqTransactionBoundary(dsl);
            var sessions = new JooqSessionRepository(transactions);
            byte[] wrappingKey = new byte[32];
            new SecureRandom().nextBytes(wrappingKey);
            var cipher = GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(wrappingKey));
            var replay = new ReplaySessionRepository(transactions, cipher, "synthetic-wrapping-key");
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var prior = sessions.createActive("ordinary-s1", "synthetic-real-actor", "synthetic-csrf",
                    now, Duration.ofMinutes(5), Duration.ofHours(1), "harness.replay.setup");
            replay.activate("ordinary-s1", "replay-s2", "synthetic-real-actor", now);
            assertEquals(SessionState.SUPERSEDED, sessions.findBySessionId("ordinary-s1").orElseThrow().state());
            assertEquals("replay-s2", sessions.findBySessionId("ordinary-s1").orElseThrow().supersededBySessionId().orElseThrow());
            assertTrue(replay.isReplay("replay-s2"));
            var active = sessions.findBySessionId("replay-s2").orElseThrow();
            assertEquals(prior.absoluteExpiresAt(), active.absoluteExpiresAt());
            assertEquals(prior.csrfSecret(), active.csrfSecret());
            assertEquals(1, dsl.fetchValue("select count(*) from sessions where state = 'ACTIVE'", Integer.class));
            byte[] key = replay.projectionKey();
            String label = new ReplayProjector(key).pseudonym("device", "synthetic-device-0007");
            assertThrows(RuntimeException.class, () -> replay.activate("ordinary-s1", "replay-invalid", "synthetic-real-actor", now));
            assertTrue(sessions.findBySessionId("replay-invalid").isEmpty());
            sessions.revoke("replay-s2", "synthetic-real-actor", "replay_session_deactivate");
            sessions.createActive("ordinary-s3", "synthetic-real-actor", "synthetic-csrf-2",
                    now, Duration.ofMinutes(5), Duration.ofHours(1), "harness.replay.setup");
            replay.activate("ordinary-s3", "replay-s4", "synthetic-real-actor", now);
            var restarted = new ReplaySessionRepository(transactions, cipher, "synthetic-wrapping-key");
            assertEquals(label, new ReplayProjector(restarted.projectionKey()).pseudonym("device", "synthetic-device-0007"));
            assertArrayEquals(key, restarted.projectionKey());
            assertEquals(1, dsl.fetchValue("select count(*) from replay_projection_key", Integer.class));
            var audit = dsl.fetch("select actor_fingerprint, before_state, after_state from audit_log "
                    + "where action_id in ('replay_session_activate', 'replay_session_deactivate')");
            assertFalse(audit.isEmpty());
            for (var row : audit) {
                assertEquals("synthetic-real-actor", row.get("actor_fingerprint", String.class));
                String json = row.toString();
                assertFalse(json.contains(Base64.getEncoder().encodeToString(key)));
                assertFalse(json.contains("synthetic-csrf"));
            }
            assertThrows(RuntimeException.class, () -> dsl.execute("update replay_projection_key set encrypted_key = encrypted_key"));
            assertThrows(RuntimeException.class, () -> dsl.execute("delete from replay_projection_key"));
        }
    }
}
