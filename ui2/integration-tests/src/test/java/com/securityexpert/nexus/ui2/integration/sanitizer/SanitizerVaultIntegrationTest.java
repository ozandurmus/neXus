package com.securityexpert.nexus.ui2.integration.sanitizer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.sanitizer.SanitizerService;
import com.securityexpert.nexus.ui2.service.sanitizer.VaultCrypto;

/**
 * V156 against a real PostgreSQL 16 (DATA_SANITIZER_CONTRACT.md §2 "Reversible on request"): a sanitize job writes its
 * vault through the migrated schema with one audit row per statement, no vault or audit row holds an original or a
 * token, a revert finds and unseals only within its scope, vault rows are immutable, and the expiry pruner deletes the
 * entries, shreds the key and ledgers the prune. Synthetic values only.
 */
class SanitizerVaultIntegrationTest {

    private static final String LOG = "Oct 16 12:00:01 corefw kernel: DROP SRC=192.0.2.10 DST=198.51.100.20 host www.example.com\n";
    private static final List<String> ORIGINALS = List.of("192.0.2.10", "198.51.100.20", "corefw", "example");

    @TempDir Path work;

    @Test
    void sanitizeWritesASealedAuditedVaultThatRevertsAndPrunes() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("sanitizer_vault"); var app = fixture.appConnection()) {
            var tx = new JooqTransactionBoundary(DSL.using(app, SQLDialect.POSTGRES));
            var repository = new SanitizerRepository(tx);
            byte[] privacyKey = new byte[32];
            Arrays.fill(privacyKey, (byte) 9);
            var crypto = new VaultCrypto(privacyKey, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
            var service = new SanitizerService(repository, crypto, privacyKey, () -> KnownNames.NONE, work, 1 << 20, 90,
                    Duration.ofMinutes(60), new Direct());

            String jobId = service.startSanitize("synthetic-operator", new ByteArrayInputStream(LOG.getBytes(StandardCharsets.UTF_8)));
            var job = repository.findJob(jobId).orElseThrow();
            assertEquals("SUCCEEDED", job.state());
            assertTrue(job.vaultEntries() > 0);
            long entries = count(app, "select count(*) from sanitize_vault_entry where job_id = '" + jobId + "'");
            assertEquals(job.vaultEntries(), entries);

            // Encryption at rest: no vault row, job row or audit row holds an original or a token.
            String sanitized;
            try (InputStream in = service.openOutput(jobId, "synthetic-operator", false)) {
                sanitized = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            List<String> tokens = Arrays.stream(sanitized.split("[ =\\n]")).filter(t -> t.startsWith("h-") || t.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")).toList();
            assertFalse(tokens.isEmpty());
            String everything = dump(app, "select encode(lookup_key, 'escape') || encode(sealed_original, 'escape') from sanitize_vault_entry")
                    + dump(app, "select coalesce(after_state::text, '') || coalesce(before_state::text, '') from audit_log "
                            + "where table_name like 'sanitize%'")
                    + dump(app, "select row_to_json(j)::text from sanitize_job j");
            for (String value : ORIGINALS) assertFalse(everything.contains(value), "an original is stored in clear");
            for (String token : tokens) assertFalse(everything.contains(token), "a token is stored in clear");
            assertEquals(1, count(app, "select count(*) from audit_log where table_name = 'sanitize_vault_entry' and row_pk = '" + jobId + "'"),
                    "one audit row per vault statement, carrying the entry count");

            // Revert, scoped to the creator's own vaults: another operator's revert finds nothing.
            String revertOwn = service.startRevert("synthetic-operator", new ByteArrayInputStream(("AI: " + sanitized).getBytes(StandardCharsets.UTF_8)),
                    SanitizerService.Scope.OWN_JOBS, null, false);
            try (InputStream in = service.openRevertOutput(revertOwn, "synthetic-operator", false)) {
                assertEquals("AI: " + LOG, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            String revertOther = service.startRevert("synthetic-other", new ByteArrayInputStream(sanitized.getBytes(StandardCharsets.UTF_8)),
                    SanitizerService.Scope.OWN_JOBS, null, false);
            try (InputStream in = service.openRevertOutput(revertOther, "synthetic-other", false)) {
                assertEquals(sanitized, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            // Vault rows are immutable, even for the application role.
            SQLException refused = assertThrows(SQLException.class, () -> execute(app,
                    "begin; select set_config('app.actor_fingerprint', 'synthetic', true); select set_config('app.action_id', 'x', true); "
                            + "update sanitize_vault_entry set entry_kind = 'label'"));
            assertNotNull(refused.getSQLState());
            execute(app, "rollback");

            // Expiry: entries deleted, key shredded, prune ledgered.
            try (var admin = fixture.migrateConnection()) {
                execute(admin, "begin; select set_config('app.actor_fingerprint', 'system:test', true); "
                        + "select set_config('app.action_id', 'test_expire', true); update sanitize_job set created_at = now() - interval '91 days', "
                        + "vault_expires_at = now() - interval '1 day' where job_id = '" + jobId + "'; commit");
            }
            assertEquals(1, repository.pruneExpiredVaults(10));
            assertEquals(0, count(app, "select count(*) from sanitize_vault_entry where job_id = '" + jobId + "'"));
            assertEquals(1, count(app, "select count(*) from sanitize_job where job_id = '" + jobId + "' and wrapped_data_key is null "
                    + "and vault_pruned_at is not null"));
            assertEquals(1, count(app, "select count(*) from sanitize_ledger where job_id = '" + jobId + "' and event = 'VAULT_PRUNED' "
                    + "and entries = " + entries));

            // A failed job prunes its vault and shreds its wrapped key immediately (F-04), not after 90 days.
            String failedJobId = service.startSanitize("synthetic-operator",
                    new ByteArrayInputStream(new byte[] {(byte) 0xd4, (byte) 0xc3, (byte) 0xb2, (byte) 0xa1, 1}));
            assertEquals("FAILED", repository.findJob(failedJobId).orElseThrow().state());
            assertEquals(0, count(app, "select count(*) from sanitize_vault_entry where job_id = '" + failedJobId + "'"));
            assertEquals(1, count(app, "select count(*) from sanitize_job where job_id = '" + failedJobId
                    + "' and wrapped_data_key is null and vault_pruned_at is not null"));
            assertEquals(1, count(app, "select count(*) from sanitize_ledger where job_id = '" + failedJobId
                    + "' and event = 'VAULT_PRUNED'"));

            try (var stillEmpty = Files.list(work)) {
                assertEquals(0, stillEmpty.count(), "no temporary file survives its job");
            }
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String dump(Connection c, String sql) throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.append(rs.getString(1)).append('\n');
        }
        return out.toString();
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static final class Direct extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
