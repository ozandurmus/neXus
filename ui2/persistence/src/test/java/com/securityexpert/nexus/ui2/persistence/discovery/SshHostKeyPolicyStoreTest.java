package com.securityexpert.nexus.ui2.persistence.discovery;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.Function;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

class SshHostKeyPolicyStoreTest {
    @Test void warnIsExactlyReadOnlyAndStrictRejectsEveryClass() {
        for (String action : Arrays.asList("read", "recovery-write", "operational-state-change", "configuration-write",
                "policy-deployment", "controlled-restore-write", "unknown", null)) {
            assertEquals("read".equals(action), SshHostKeyPolicyStore.permits("WARN", action, false, false));
            assertFalse(SshHostKeyPolicyStore.permits("STRICT", action, false, false));
            assertFalse(SshHostKeyPolicyStore.permits("WARN", action, true, false));
            assertFalse(SshHostKeyPolicyStore.permits("WARN", action, false, true));
        }
        assertFalse(SshHostKeyPolicyStore.permits(null, "read", false, false));
    }

    @Test void observationAuditsAndFlagsBeforeAllowingAndRejectedDeviceRefuses() {
        var fixture = new Fixture();
        assertTrue(fixture.store.observe("job-fixture", 1, false, "endpoint-fixture", "192.0.2.10", 22,
                "ssh-ed25519", "1".repeat(64), "0".repeat(64)));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("SET LOCAL app.action_id = 'ssh_host_key_mismatch'")));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("ON CONFLICT") && s.contains("seen_count + 1")));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("UPDATE jobs SET ssh_identity_unverified = true")));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("UPDATE devices SET ssh_identity_unverified = true")));
        fixture.rejected = true; fixture.sql.clear();
        assertFalse(fixture.store.observe("job-fixture", 1, false, "endpoint-fixture", "192.0.2.10", 22,
                "ssh-ed25519", "2".repeat(64), "0".repeat(64)));
        assertFalse(fixture.sql.stream().anyMatch(s -> s.contains("UPDATE jobs")));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("INSERT INTO ssh_host_key_observation")));
    }

    @Test void failedAuditCannotAllowAuthentication() {
        var fixture = new Fixture(); fixture.failObservation = true;
        assertThrows(RuntimeException.class, () -> fixture.store.observe("job-fixture", 1, false,
                "endpoint-fixture", "192.0.2.10", 22, "ssh-ed25519", "1".repeat(64), "0".repeat(64)));
        assertFalse(fixture.sql.stream().anyMatch(s -> s.contains("UPDATE jobs")));
    }

    @Test void settingsAreValidatedPersistedAndAudited() {
        var fixture = new Fixture();
        fixture.store.mode("STRICT", "actor-fixture");
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("ssh_trust_mode_write")));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("UPDATE ssh_trust_settings")));
        assertThrows(IllegalArgumentException.class, () -> fixture.store.mode("invalid", "actor-fixture"));
    }

    static final class Fixture {
        final List<String> sql = new ArrayList<>();
        boolean rejected, failObservation;
        final SshHostKeyPolicyStore store;
        Fixture() {
            DSLContext db = DSL.using(new MockConnection(ctx -> {
                String query = ctx.sql(); sql.add(query);
                if (query.startsWith("SELECT mode")) return row(new String[]{"mode"}, new Object[]{"WARN"});
                if (query.startsWith("SELECT target_device_id")) return row(new String[]{"target_device_id", "action_class", "job_type"},
                        new Object[]{"device-fixture", "read", "inventory"});
                if (query.startsWith("SELECT 1")) return rejected ? row(new String[]{"exists"}, new Object[]{1})
                        : new MockResult[]{new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("exists", Integer.class)))};
                if (failObservation && query.startsWith("INSERT INTO ssh_host_key_observation")) throw new java.sql.SQLException("synthetic audit failure");
                return new MockResult[]{new MockResult(1, null)};
            }), SQLDialect.POSTGRES);
            store = new SshHostKeyPolicyStore(new TransactionBoundary() {
                public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(db); }
            });
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    static MockResult[] row(String[] names, Object[] values) {
        Field[] fields = new Field[names.length];
        for (int i = 0; i < names.length; i++) fields[i] = DSL.field(names[i], values[i].getClass());
        var db = DSL.using(SQLDialect.POSTGRES);
        var rows = db.newResult(fields);
        var row = db.newRecord(fields);
        for (int i = 0; i < names.length; i++) row.set(fields[i], values[i]);
        rows.add(row);
        return new MockResult[]{new MockResult(1, rows)};
    }
}
