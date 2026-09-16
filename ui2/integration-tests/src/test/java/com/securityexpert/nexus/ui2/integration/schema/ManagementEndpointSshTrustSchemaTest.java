package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.discovery.JooqManagementEndpointSshTrustRepository;

class ManagementEndpointSshTrustSchemaTest {
    @Test
    void migrationIncludesAtomicUniquenessDeferredSupersessionAndNarrowAudit() throws Exception {
        Path path = Path.of("../service/src/main/resources/db/migration/V24__management_endpoint_ssh_trust.sql");
        String sql = Files.readString(path);
        assertTrue(sql.contains("CREATE UNIQUE INDEX management_endpoint_ssh_trust_active"));
        assertTrue(sql.contains("WHERE status = 'ACTIVE'"));
        assertTrue(sql.contains("DEFERRABLE INITIALLY DEFERRED"));
        assertTrue(sql.contains("trust_history_immutable"));
        assertTrue(sql.contains("SECURITY DEFINER SET search_path"));
        String projection = sql.substring(sql.indexOf("safe_after :="), sql.indexOf("INSERT INTO audit_log"));
        assertFalse(projection.contains("fingerprint_sha256"));
        assertFalse(projection.contains("management_address"));
        assertFalse(projection.contains("authorized_by"));
    }

    @Test
    void databaseAppliesMigrationAndRetainsRotationWithSanitizedAuditAndRollback() throws Exception {
        // No container or host access in this dispatch. A separately provisioned synthetic DB is required.
        assumeTrue(System.getenv("UI2_TEST_JDBC_URL") != null, "UNVERIFIED: no authorized database carrier");
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.create("c10_trust")) {
            assertTrue(fixture.runFlyway().migrations.stream().anyMatch(m -> "24".equals(m.version)));
            TransactionBoundary boundary = new TransactionBoundary() {
                public <T> T inTransaction(Function<DSLContext, T> work) {
                    try (Connection connection = fixture.appConnection()) {
                        connection.setAutoCommit(false);
                        try {
                            T result = work.apply(DSL.using(connection, SQLDialect.POSTGRES));
                            connection.commit(); return result;
                        } catch (RuntimeException | SQLException e) {
                            connection.rollback(); throw new IllegalStateException("synthetic transaction refused", e);
                        }
                    } catch (SQLException e) { throw new IllegalStateException("synthetic DB unavailable", e); }
                }
            };
            var repository = new JooqManagementEndpointSshTrustRepository(boundary);
            assertTrue(repository.enroll("fixture-management", 2222, "ssh-ed25519", "0".repeat(64), "fixture-actor", Instant.EPOCH, false));
            assertFalse(repository.enroll("fixture-management", 2222, "ssh-ed25519", "1".repeat(64), "fixture-actor", Instant.EPOCH, false));
            assertTrue(repository.enroll("fixture-management", 2222, "ssh-ed25519", "1".repeat(64), "fixture-actor", Instant.EPOCH, true));
            assertEquals(java.util.Optional.of("1".repeat(64)), repository.findActiveFingerprint("fixture-management", 2222, "ssh-ed25519"));
            assertEquals(java.util.Optional.empty(), repository.findActiveFingerprint("fixture-management", 22, "ssh-ed25519"));
            assertThrows(RuntimeException.class, () -> repository.enroll("fixture-management", 2222, "ssh-ed25519", "invalid", "fixture-actor", Instant.EPOCH, true));
            assertEquals(java.util.Optional.of("1".repeat(64)), repository.findActiveFingerprint("fixture-management", 2222, "ssh-ed25519"));
            boundary.inTransaction(dsl -> {
                assertEquals(2, dsl.fetchOne("SELECT count(*) FROM management_endpoint_ssh_trust").get(0, Integer.class));
                assertEquals(1, dsl.fetchOne("SELECT count(*) FROM management_endpoint_ssh_trust WHERE status = 'ACTIVE'").get(0, Integer.class));
                var audit = dsl.fetch("SELECT before_state::text, after_state::text FROM audit_log WHERE table_name = 'management_endpoint_ssh_trust'");
                assertEquals(3, audit.size());
                assertFalse(audit.toString().contains("fixture-management"));
                assertFalse(audit.toString().contains("0".repeat(64)));
                assertFalse(audit.toString().contains("1".repeat(64)));
                assertTrue(audit.toString().contains("RE-ENROLLED"));
                return null;
            });
        }
    }
}
