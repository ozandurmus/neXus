package com.securityexpert.nexus.ui2.integration.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.JooqLocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalIdentityBootstrap;
import com.securityexpert.nexus.ui2.platform.LocalIdentityBootstrapPort;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;
import com.securityexpert.nexus.ui2.service.boot.BootstrapCredentialDefaults;
import com.securityexpert.nexus.ui2.service.boot.FirstBootIdentitySeedingRunner;

/**
 * C3B contract (docs/design/UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md,
 * FROZEN 2026-09-13) §4 acceptance tests 4 and 5, proved against a real
 * PostgreSQL 16 server (this environment cannot run this class -- no
 * Docker/UI2_TEST_JDBC_URL here -- but it must compile and is the
 * real-environment half of {@code FirstBootIdentitySeedingRunnerTest}'s
 * in-memory-fake tests in {@code service:test}).
 */
class FirstBootIdentitySeedingIntegrationTest {

    private static Ui2PostgresFixture fixture;
    private static LocalCredentialsRepository repository;
    private static FirstBootIdentitySeedingRunner runner;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("first_boot_identity_seeding");
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        repository = new JooqLocalCredentialsRepository(transactionBoundary);
        LocalIdentityBootstrapPort bootstrap = new LocalIdentityBootstrap(repository);
        runner = new FirstBootIdentitySeedingRunner(repository, bootstrap);
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void firstBootOnAnEmptyDatabaseAuditsBothIdentitiesToTheBootstrapActorAndCreatesNoRoleBindingsRow()
            throws SQLException {
        runner.run(null);

        assertTrue(repository.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).isPresent());
        assertTrue(repository.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).isPresent());

        try (Connection app = fixture.appConnection()) {
            String nexusadminId = repository.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).orElseThrow()
                    .localIdentityId();
            String claudeadminId = repository.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).orElseThrow()
                    .localIdentityId();

            // AC-6: one audit_log INSERT row per created identity, attributed
            // to the reserved bootstrap marker, carrying no credential
            // material (fn_audit_capture_local_credentials' own §8 allowlist
            // already guarantees the "no credential material" half).
            assertEquals(1, Ui2Rows.count(app, "SELECT count(*) FROM audit_log WHERE table_name = 'local_credentials' "
                    + "AND row_pk = '" + nexusadminId + "' AND operation = 'INSERT' AND actor_fingerprint = '"
                    + SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR + "'"));
            assertEquals(1, Ui2Rows.count(app, "SELECT count(*) FROM audit_log WHERE table_name = 'local_credentials' "
                    + "AND row_pk = '" + claudeadminId + "' AND operation = 'INSERT' AND actor_fingerprint = '"
                    + SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR + "'"));

            // AC-7/BOOT-5: identities only -- role_bindings stays empty.
            assertEquals(0, Ui2Rows.count(app, "SELECT count(*) FROM role_bindings"));
        }
    }
}
