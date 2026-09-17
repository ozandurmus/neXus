package com.securityexpert.nexus.ui2.integration.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.FirstBootIdentityRoleBindingSeeder;
import com.securityexpert.nexus.ui2.persistence.identity.JooqLocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;
import com.securityexpert.nexus.ui2.service.boot.BootstrapCredentialDefaults;
import com.securityexpert.nexus.ui2.service.boot.FirstBootIdentitySeedingRunner;

/**
 * C3B contract (docs/design/UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md,
 * FROZEN 2026-09-13, BOOT-5 corrected the same day) §4 acceptance tests 4,
 * 5 and 6, proved against a real PostgreSQL 16 server (this environment
 * cannot run this class -- no Docker/UI2_TEST_JDBC_URL here -- but it must
 * compile and is the real-environment half of
 * {@code FirstBootIdentitySeedingRunnerTest}'s in-memory-fake tests in
 * {@code service:test}, in particular proving the same-transaction
 * guarantee a fake {@link TransactionBoundary} cannot: a real jOOQ
 * savepoint, not merely a sequence of independently-committing calls).
 */
class FirstBootIdentitySeedingIntegrationTest {

    private static Ui2PostgresFixture fixture;
    private static LocalCredentialsRepository repository;
    private static RoleBindingRepository roleBindingRepository;
    private static GroupReferenceCipher cipher;
    private static FirstBootIdentitySeedingRunner runner;
    private static final String GROUP_REFERENCE_KEY_ID = "test-key-id";

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("first_boot_identity_seeding");
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        repository = new JooqLocalCredentialsRepository(transactionBoundary);
        roleBindingRepository = new JooqRoleBindingRepository(transactionBoundary);
        cipher = testCipher();
        FirstBootIdentityRoleBindingSeeder seeder = new FirstBootIdentityRoleBindingSeeder(transactionBoundary,
                repository, roleBindingRepository, cipher, GROUP_REFERENCE_KEY_ID);
        runner = new FirstBootIdentitySeedingRunner(repository, seeder);
    }

    private static GroupReferenceCipher testCipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void firstBootOnAnEmptyDatabaseAuditsEveryIdentityAndBindingToTheBootstrapActor() throws SQLException {
        runner.run(null);

        assertTrue(repository.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).isPresent());
        assertTrue(repository.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).isPresent());

        try (Connection app = fixture.appConnection()) {
            String nexusadminId = repository.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).orElseThrow()
                    .localIdentityId();
            String claudeadminId = repository.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).orElseThrow()
                    .localIdentityId();

            // AC-4: one audit_log INSERT row per created identity, attributed
            // to the reserved bootstrap marker, carrying no credential
            // material (fn_audit_capture_local_credentials' own §8 allowlist
            // already guarantees the "no credential material" half).
            assertEquals(1, Ui2Rows.count(app, "SELECT count(*) FROM audit_log WHERE table_name = 'local_credentials' "
                    + "AND row_pk = '" + nexusadminId + "' AND operation = 'INSERT' AND actor_fingerprint = '"
                    + SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR + "'"));
            assertEquals(1, Ui2Rows.count(app, "SELECT count(*) FROM audit_log WHERE table_name = 'local_credentials' "
                    + "AND row_pk = '" + claudeadminId + "' AND operation = 'INSERT' AND actor_fingerprint = '"
                    + SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR + "'"));

            // BOOT-5a/AC-3: nexusadmin is bound to every role token (ROLE-1's
            // "full administrative capability"), claudeadmin to role:viewer
            // only (ROLE-3): 6 + 1 = 7 rows total, every one created by the
            // reserved bootstrap actor and itself audited (BOOT-4).
            int expectedTotalBindings = RoleToken.values().length + 1;
            assertEquals(expectedTotalBindings, Ui2Rows.count(app, "SELECT count(*) FROM role_bindings"));
            assertEquals(expectedTotalBindings,
                    Ui2Rows.count(app, "SELECT count(*) FROM role_bindings WHERE created_by_actor_fingerprint = '"
                            + SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR + "' AND revoked_at IS NULL"));
            assertEquals(expectedTotalBindings,
                    Ui2Rows.count(app, "SELECT count(*) FROM audit_log WHERE table_name = 'role_bindings' "
                            + "AND operation = 'INSERT' AND actor_fingerprint = '"
                            + SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR + "'"));

            // Per-identity membership, proved by decrypting each row's
            // self-reference (C3A §7.1) rather than trusting the row count
            // alone: every one of the 6 tokens resolves to nexusadmin, and
            // role:viewer resolves to both nexusadmin (part of "full") and
            // claudeadmin, and no other token resolves to claudeadmin at all.
            for (String token : RoleToken.values()) {
                List<String> boundIdentityIds = roleBindingRepository.findActiveByToken(token).stream()
                        .map(binding -> cipher.decrypt(binding.groupReferenceEncrypted()))
                        .toList();
                assertTrue(boundIdentityIds.contains(nexusadminId),
                        "AC-1: nexusadmin must hold " + token + " for full administrative capability");
                if (RoleToken.VIEWER.equals(token)) {
                    assertTrue(boundIdentityIds.contains(claudeadminId), "AC-2: claudeadmin must hold role:viewer");
                    assertEquals(2, boundIdentityIds.size());
                } else {
                    assertTrue(!boundIdentityIds.contains(claudeadminId),
                            "AC-2: claudeadmin must hold no token beyond role:viewer -- found " + token);
                    assertEquals(1, boundIdentityIds.size());
                }
            }
        }
    }
}
