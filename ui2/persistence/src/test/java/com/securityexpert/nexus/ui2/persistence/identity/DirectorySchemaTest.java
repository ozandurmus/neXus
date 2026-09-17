package com.securityexpert.nexus.ui2.persistence.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.platform.*;

/** Additive/static SQL and synthetic JDBC carrier. Real PostgreSQL constraints/rollback remain a separate gate. */
class DirectorySchemaTest {
    @Test void migrationIsAdditiveExcludesUnprovenBackfillExpiresCachesAndRetainsAuditHistory() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V25__directory_principal_bindings.sql"));
        // Installed jOOQ parser does not support PL/pgSQL trigger bodies; parse additive DDL separately.
        assertDoesNotThrow(() -> DSL.using(SQLDialect.POSTGRES).parser().parse(sql.substring(0, sql.indexOf("CREATE FUNCTION"))));
        assertTrue(sql.contains("DEFAULT 'LEGACY'"));
        assertTrue(sql.contains("'DIRECTORY_GROUP', 'DIRECTORY_PRINCIPAL'"));
        assertTrue(sql.contains("DELETE FROM actor_authz_state"));
        assertFalse(sql.contains("UPDATE role_bindings"));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("DROP "));
        assertTrue(sql.contains("AFTER UPDATE OF state ON sessions"));
        assertTrue(sql.contains("NEW.state IN ('EXPIRED', 'REVOKED')"));
        assertFalse(sql.contains("ON actor_authz_state"));
        assertFalse(sql.contains("fn_audit_capture("));
    }

    @Test void typedWritesAndMutationCallbacksShareOneAuditAndLockCarrierWithoutPlaintextReference() {
        List<String> statements = new ArrayList<>();
        var dsl = DSL.using(new MockConnection(context -> {
            statements.add(context.sql());
            return new MockResult[] { new MockResult(1) };
        }), SQLDialect.POSTGRES);
        var repository = new JooqRoleBindingRepository(new JooqTransactionBoundary(dsl));
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        var cipher = GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key), "synthetic-key");
        String reference = "opaque-synthetic-principal";
        var record = new RoleBindingRecord("synthetic-binding", RoleToken.BACKUP_ADMIN,
                cipher.encryptDirectory(reference, "synthetic-profile", DirectoryBindingKind.DIRECTORY_PRINCIPAL), cipher.keyId(),
                "synthetic-actor", Instant.EPOCH, Optional.empty(), Optional.empty(), DirectoryBindingKind.DIRECTORY_PRINCIPAL, "synthetic-profile");
        assertEquals(record.bindingId(), repository.directoryMutation(tx -> tx.bindings().createDirectory(record, "role_binding_create")));
        assertTrue(statements.get(0).startsWith("lock table role_bindings, actor_authz_state, sessions, local_credentials"));
        assertEquals(1, statements.stream().filter(s -> s.startsWith("SET LOCAL app.actor_fingerprint")).count());
        assertEquals(1, statements.stream().filter(s -> s.startsWith("SET LOCAL app.action_id")).count());
        assertTrue(statements.get(statements.size() - 1).contains("binding_kind, directory_profile_id"));
        assertFalse(statements.toString().contains(reference));
    }
}
