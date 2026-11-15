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

    @Test void supportBundleCapabilitiesWarnAndContinueOnAMismatchAndNothingElseClass1Does() {
        for (String capability : List.of("cp_support_bundle", "pan_support_bundle", "asa_support_bundle", "fgt_support_bundle")) {
            assertTrue(SshHostKeyPolicyStore.permits("WARN", "recovery-write", capability, false, false), capability);
            assertFalse(SshHostKeyPolicyStore.permits("STRICT", "recovery-write", capability, false, false), capability);
            assertFalse(SshHostKeyPolicyStore.permits("WARN", "recovery-write", capability, true, false), capability);
            assertFalse(SshHostKeyPolicyStore.permits("WARN", "recovery-write", capability, false, true), capability);
            // only the write class the support bundle holds; a heavier class never rides on the capability name
            for (String other : List.of("operational-state-change", "configuration-write", "policy-deployment", "controlled-restore-write", "unknown"))
                assertFalse(SshHostKeyPolicyStore.permits("WARN", other, capability, false, false), other);
        }
        // the MDS export and every other class 1 job still refuse
        for (String capability : Arrays.asList("cp_mds_export", "cp_snapshot_backup", "inventory", "", null))
            assertFalse(SshHostKeyPolicyStore.permits("WARN", "recovery-write", capability, false, false), String.valueOf(capability));
        assertFalse(SshHostKeyPolicyStore.permits("WARN", "recovery-write", false, false));
    }

    @Test void aSupportBundleJobIsAdmittedAndFlaggedOnAMismatchWhileAnotherClass1JobIsNot() {
        var fixture = new Fixture();
        fixture.jobActionClass = "recovery-write"; fixture.jobCapability = "cp_support_bundle";
        assertTrue(fixture.store.observe("job-fixture", 1, false, "endpoint-fixture", "192.0.2.10", 22,
                "ssh-ed25519", "1".repeat(64), "0".repeat(64)));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("UPDATE jobs SET ssh_identity_unverified = true")));
        var other = new Fixture();
        other.jobActionClass = "recovery-write"; other.jobCapability = "cp_mds_export";
        assertFalse(other.store.observe("job-fixture", 1, false, "endpoint-fixture", "192.0.2.10", 22,
                "ssh-ed25519", "1".repeat(64), "0".repeat(64)));
        assertFalse(other.sql.stream().anyMatch(s -> s.contains("UPDATE jobs")));
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

    @Test void aMismatchIsRecordedAsTheDevicesLastComparison() {
        var fixture = new Fixture();
        fixture.store.observe("job-fixture", 1, false, "endpoint-fixture", "192.0.2.10", 22,
                "ssh-ed25519", "1".repeat(64), "0".repeat(64));
        assertEquals(List.of(List.of("SSH_HOST_KEY", "MISMATCH", "device-fixture")), fixture.identityChecks);
        assertTrue(fixture.identityChecks.stream().flatMap(List::stream).noneMatch(b -> b.contains("1".repeat(64))),
                "no fingerprint is stored with the verdict");
    }

    @Test void aMatchedOrFirstUseConnectionIsRecordedAndNeverFromTheTrustDecision() {
        var fixture = new Fixture();
        fixture.store.recordPinnedComparison("job-fixture", 1, "endpoint-fixture", false);
        fixture.store.recordPinnedComparison("job-fixture", 1, "endpoint-fixture", true);
        assertEquals(List.of(List.of("SSH_HOST_KEY", "MATCH", "device-fixture"),
                List.of("SSH_HOST_KEY", "FIRST_USE", "device-fixture")), fixture.identityChecks,
                "a key pinned by this very connection had nothing to be compared with, so it is not a match");
    }

    @Test void aFailedRecordingIsSwallowedSoTheConnectionIsUnaffected() {
        var fixture = new Fixture(); fixture.failIdentityRecording = true;
        assertDoesNotThrow(() -> fixture.store.recordPinnedComparison("job-fixture", 1, "endpoint-fixture", false));
        assertTrue(fixture.store.observe("job-fixture", 1, false, "endpoint-fixture", "192.0.2.10", 22,
                "ssh-ed25519", "1".repeat(64), "0".repeat(64)), "WARN mode still admits a read after a failed record");
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
        boolean rejected, failObservation, failIdentityRecording;
        String jobActionClass = "read", jobCapability = "inventory_read";
        final List<List<String>> identityChecks = new ArrayList<>();
        final SshHostKeyPolicyStore store;
        Fixture() {
            DSLContext db = DSL.using(new MockConnection(ctx -> {
                String query = ctx.sql(); sql.add(query);
                if (query.startsWith("SELECT mode")) return row(new String[]{"mode"}, new Object[]{"WARN"});
                if (query.startsWith("SELECT target_device_id")) return row(new String[]{"target_device_id", "action_class", "job_type", "capability_id"},
                        new Object[]{"device-fixture", jobActionClass, "inventory", jobCapability});
                if (query.startsWith("SELECT 1")) return rejected ? row(new String[]{"exists"}, new Object[]{1})
                        : new MockResult[]{new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("exists", Integer.class)))};
                if (failObservation && query.startsWith("INSERT INTO ssh_host_key_observation")) throw new java.sql.SQLException("synthetic audit failure");
                if (query.startsWith("INSERT INTO device_identity_check")) {
                    if (failIdentityRecording) throw new java.sql.SQLException("synthetic: table missing");
                    identityChecks.add(Arrays.stream(ctx.bindings()).map(String::valueOf).toList());
                }
                if (query.startsWith("SELECT target_device_id FROM jobs")) return row(new String[]{"target_device_id"}, new Object[]{"device-fixture"});
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
