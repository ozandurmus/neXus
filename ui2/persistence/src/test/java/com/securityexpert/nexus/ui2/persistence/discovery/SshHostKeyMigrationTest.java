package com.securityexpert.nexus.ui2.persistence.discovery;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SshHostKeyMigrationTest {
    @Test void v147IsTransactionalIdempotentAndAuditsOnlyDerivedFields() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V147__ssh_host_key_warn_mode.sql"));
        for (String table : new String[]{"ssh_trust_settings", "ssh_host_key_observation"}) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table));
            assertTrue(sql.contains("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO ui2_app"));
        }
        assertTrue(sql.contains("DEFAULT 'WARN'"));
        assertTrue(sql.contains("'OPEN', 'ACCEPTED', 'REJECTED'"));
        assertTrue(sql.contains("seen_count > 0"));
        assertTrue(sql.contains("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS ssh_identity_unverified"));
        assertTrue(sql.contains("ALTER TABLE devices ADD COLUMN IF NOT EXISTS ssh_identity_unverified"));
        assertTrue(sql.contains("audit_context_missing"));
        String audit = sql.substring(sql.indexOf("CREATE OR REPLACE FUNCTION"), sql.indexOf("REVOKE ALL"));
        assertFalse(audit.contains("management_address"));
        assertFalse(audit.contains("presented_fingerprint"));
        assertFalse(audit.contains("expected_fingerprint"));
        assertTrue(audit.contains("INSERT INTO audit_log"));
        assertFalse(sql.contains("CONCURRENTLY"));
        assertFalse(sql.contains("COMMIT;"));
    }
}
