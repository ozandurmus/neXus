package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import com.securityexpert.nexus.ui2.integration.support.*;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.*;
import com.securityexpert.nexus.ui2.persistence.runtime.*;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository.Owner;

class ModuleEndpointRuntimeTest {
    @Test void independentPodsAndDistinctDeviceRowsShareOneAddressBudgetAndExpiryIsFailClosed() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("module_endpoint_runtime")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var audited = new AuditedTransactionBoundary(tx);
            String first, second;
            try (var connection = fixture.appConnection()) {
                var credential = Ui2Rows.insertCredentialReference(connection);
                first = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
                second = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
            }
            audited.inTransaction("synthetic-actor", "endpoint_fixture", db -> {
                db.execute("insert into endpoints(endpoint_id,device_id,transport_kind,address_ref) values "
                    + "('endpoint-first',{0},'SSH_EXEC','MANAGER.EXAMPLE.INVALID'),('endpoint-second',{1},'SSH_EXEC','manager.example.invalid:22')", first, second);
                return null;
            });
            var devices = new com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository(tx);
            String keyA = EndpointAddress.key(devices.findEndpointByDeviceId(first).orElseThrow().addressRef(), 22);
            String keyB = EndpointAddress.key(devices.findEndpointByDeviceId(second).orElseThrow().addressRef(), 22);
            assertEquals(keyA, keyB);
            var jobs = new JooqJobRecordDao(tx);
            jobs.insertRequestedIfAbsent("job-a", "key-a", "cp_inventory_collect", first, "read", "cp_inventory_collect", "synthetic-actor", "fixture").orElseThrow();
            jobs.insertRequestedIfAbsent("job-b", "key-b", "cp_configuration_collect", second, "read", "cp_configuration_collect", "synthetic-actor", "fixture").orElseThrow();
            var leases = new JooqJobLeaseDao(tx);
            long epochA = leases.claimNext("general-pod-a", List.of("cp_inventory_collect"), Duration.ofMinutes(10)).orElseThrow().leaseEpoch();
            long epochB = leases.claimNext("general-pod-b", List.of("cp_configuration_collect"), Duration.ofMinutes(10)).orElseThrow().leaseEpoch();
            var podA = new EndpointAdmissionRepository(tx);
            var podB = new EndpointAdmissionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            var ownerA = new Owner("job-a", epochA, null, 0, "general", "general-pod-a", 1, "INVENTORY", false);
            var ownerB = new Owner("job-b", epochB, null, 0, "general", "general-pod-b", 1, "CONFIGURATION", false);
            var start = new CyclicBarrier(2);
            var threads = Executors.newFixedThreadPool(2);
            try {
                var a = threads.submit(() -> { start.await(); return podA.acquire("ticket-a", keyA, "session-a", ownerA); });
                var b = threads.submit(() -> { start.await(); return podB.acquire("ticket-b", keyB, "session-b", ownerB); });
                var permitA = a.get(10, TimeUnit.SECONDS); var permitB = b.get(10, TimeUnit.SECONDS);
                assertNotEquals(permitA.isPresent(), permitB.isPresent());
                var held = permitA.isPresent() ? permitA.orElseThrow() : permitB.orElseThrow();
                var repository = permitA.isPresent() ? podA : podB;
                audited.inTransaction("synthetic-actor", "expire_permit", db -> db.execute(
                    "update endpoint_admission set expires_at=now()-interval '1 second' where request_id={0}", held.requestId()));
                assertTrue((permitA.isPresent() ? podB.acquire("ticket-b", keyB, "session-b", ownerB)
                    : podA.acquire("ticket-a", keyA, "session-a", ownerA)).isEmpty());
                assertEquals("QUARANTINED", tx.inTransaction(db -> db.fetchOne("select state from endpoint_admission where request_id={0}", held.requestId()).get(0, String.class)));
                assertFalse(repository.renew(held, permitA.isPresent() ? ownerA : ownerB));
                assertTrue(repository.releaseClosed(held)); // Explicit simulated closure, never TTL-only reclaim.
                var successor = (permitA.isPresent() ? podB.acquire("ticket-b", keyB, "session-b", ownerB)
                    : podA.acquire("ticket-a", keyA, "session-a", ownerA)).orElseThrow();
                assertFalse(repository.releaseClosed(held));
                assertTrue(successor.epoch() > held.epoch());
            } finally { threads.shutdownNow(); }
        }
    }

    @Test void drainAndClaimSerializeAndTheApplicationCannotChangeFallbackOwnership() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("module_drain")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var jobs = new JooqJobRecordDao(tx);
            jobs.insertRequestedIfAbsentForRun("policy-job", "policy-key", "cp_policy_collect", "synthetic-run", "read",
                "cp_policy_collect", "synthetic-actor", "fixture").orElseThrow();
            jobs.insertRequestedIfAbsentForRun("bad-job", "bad-key", "cp_policy_collect", "synthetic-run", "read",
                "pan_policy_collect", "synthetic-actor", "fixture").orElseThrow();
            var leases = new JooqJobLeaseDao(tx);
            assertTrue(leases.claimNext("policy-pod", List.of("cp_policy_collect"), Duration.ofMinutes(10)).isEmpty());
            try (var migrate = fixture.migrateConnection()) {
                migrate.setAutoCommit(false);
                Ui2Rows.setAuditContext(migrate, "synthetic-release", "policy_fixture_handover");
                try (var statement = migrate.createStatement()) {
                    statement.executeUpdate("update module_runtime_control set effective_owner='policy',fallback_enabled=false where module='policy'");
                }
                migrate.commit();
            }
            var runtime = new ModuleRuntimeRepository(tx);
            assertTrue(runtime.heartbeat("policy", "policy-pod"));
            releaseSql(fixture, "update module_runtime_control set drain_requested=true,drain_generation=drain_generation+1 where module='policy'");
            assertTrue(runtime.heartbeat("policy", "policy-pod"));
            assertTrue(leases.claimNext("policy-pod", List.of("cp_policy_collect"), Duration.ofMinutes(10)).isEmpty());
            assertEquals(1L, tx.inTransaction(db -> db.fetchOne("select drain_ack_generation from module_runtime_control where module='policy'").get(0, Long.class)));
            try (var app = fixture.appConnection(); var statement = app.createStatement()) {
                assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("update module_runtime_control set fallback_enabled=true,effective_owner='general' where module='policy'"));
            }
            try (var migrate = fixture.migrateConnection()) {
                migrate.setAutoCommit(false);
                Ui2Rows.setAuditContext(migrate, "synthetic-release", "drain_clear");
                try (var statement = migrate.createStatement()) { statement.executeUpdate("update module_runtime_control set drain_requested=false where module='policy'"); }
                migrate.commit();
            }
            assertEquals("policy-job", leases.claimNext("policy-pod", List.of("cp_policy_collect"), Duration.ofMinutes(10)).orElseThrow().jobId());
            assertTrue(leases.claimNext("policy-other", List.of("cp_policy_collect"), Duration.ofMinutes(10)).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"general", "policy"})
    void plainRestartAdmitsNewJobsAndPreservesInFlightLeaseFencing(String role) throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("module_restart_" + role)) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            if (role.equals("policy")) releaseSql(fixture,
                "update module_runtime_control set effective_owner='policy',fallback_enabled=false where module='policy'");
            var old = new ModuleRuntimeRepository(tx);
            assertTrue(old.heartbeat(role, role + "-old"));
            var jobs = new JooqJobRecordDao(tx);
            jobs.insertRequestedIfAbsentForRun("in-flight", "key-first", "cp_policy_collect", "synthetic-run", "read",
                "cp_policy_collect", "synthetic-actor", "fixture").orElseThrow();
            var leases = new JooqJobLeaseDao(tx);
            long epoch = leases.claimNext(role + "-old", List.of("cp_policy_collect"), Duration.ofMinutes(10))
                .orElseThrow().leaseEpoch();
            long generation = old.generation("in-flight", epoch);
            assertTrue(leases.heartbeat("in-flight", epoch, Duration.ofMinutes(10)));

            old.releaseOwnership(role, role + "-old");
            assertTrue(tx.inTransaction(db -> db.fetchOne("select owner_instance is null and owner_heartbeat_at is null "
                + "and not drain_requested and drain_generation=0 from module_runtime_control where module={0}", role).get(0, Boolean.class)));
            assertEquals(generation, old.generation("in-flight", epoch));
            var successor = new ModuleRuntimeRepository(tx);
            assertTrue(successor.heartbeat(role, role + "-new"));
            assertFalse(old.heartbeat(role, role + "-old"));
            old.releaseOwnership(role, role + "-old"); // A late shutdown cannot clear the new owner.
            assertTrue(tx.inTransaction(db -> db.fetchOne("select owner_instance={0} from module_runtime_control where module={1}",
                role + "-new", role).get(0, Boolean.class)));
            assertFalse(leases.heartbeat("in-flight", epoch, Duration.ofMinutes(10)));
            assertFalse(leases.heartbeat("in-flight", epoch + 1, Duration.ofMinutes(10)));
            assertTrue(leases.claimNext(role + "-new", List.of("cp_policy_collect"), Duration.ofMinutes(10)).isEmpty());

            jobs.insertRequestedIfAbsentForRun("after-restart", "key-second", "cp_policy_collect", "synthetic-run", "read",
                "cp_policy_collect", "synthetic-actor", "fixture").orElseThrow();
            assertEquals("after-restart", leases.claimNext(role + "-new", List.of("cp_policy_collect"), Duration.ofMinutes(10))
                .orElseThrow().jobId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"general", "policy"})
    void deployDrainSurvivesRestartUntilGenerationMatchedClear(String role) throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("module_deploy_restart_" + role)) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            if (role.equals("policy")) releaseSql(fixture,
                "update module_runtime_control set effective_owner='policy',fallback_enabled=false where module='policy'");
            var old = new ModuleRuntimeRepository(tx);
            assertTrue(old.heartbeat(role, role + "-old"));
            releaseSql(fixture, "update module_runtime_control set drain_requested=true,drain_generation=7,"
                + "drain_ack_at=null,drain_ack_generation=null,last_reason='synthetic-release',last_snapshot_id='synthetic-snapshot' where module='" + role + "'");
            old.releaseOwnership(role, role + "-old");
            var successor = new ModuleRuntimeRepository(tx);
            assertTrue(successor.heartbeat(role, role + "-new"));
            assertTrue(tx.inTransaction(db -> db.fetchOne("select drain_requested and drain_generation=7 "
                + "and drain_ack_generation=7 and last_reason='synthetic-release' and last_snapshot_id='synthetic-snapshot' "
                + "from module_runtime_control where module={0}", role).get(0, Boolean.class)));
            var jobs = new JooqJobRecordDao(tx);
            jobs.insertRequestedIfAbsentForRun("drained-job", "drained-key", "cp_policy_collect", "synthetic-run", "read",
                "cp_policy_collect", "synthetic-actor", "fixture").orElseThrow();
            var leases = new JooqJobLeaseDao(tx);
            assertTrue(leases.claimNext(role + "-new", List.of("cp_policy_collect"), Duration.ofMinutes(10)).isEmpty());
            String clear = "update module_runtime_control set drain_requested=false,drain_ack_at=null,drain_ack_generation=null,"
                + "owner_instance=null,owner_heartbeat_at=null where module='" + role + "' and drain_generation=";
            releaseSql(fixture, clear + "6");
            assertTrue(leases.claimNext(role + "-new", List.of("cp_policy_collect"), Duration.ofMinutes(10)).isEmpty());
            releaseSql(fixture, clear + "7");
            assertTrue(successor.heartbeat(role, role + "-new"));
            assertEquals("drained-job", leases.claimNext(role + "-new", List.of("cp_policy_collect"), Duration.ofMinutes(10))
                .orElseThrow().jobId());
        }
    }

    private static void releaseSql(Ui2PostgresFixture fixture, String sql) throws Exception {
        try (var connection = fixture.migrateConnection()) {
            connection.setAutoCommit(false);
            Ui2Rows.setAuditContext(connection, "synthetic-release", "module_release");
            try (var statement = connection.createStatement()) {
                statement.execute("select pg_advisory_xact_lock(294611)");
                statement.executeUpdate(sql);
            }
            connection.commit();
        }
    }
    @Test void migrationSeedsGeneralAndRecoversMissingOrStaleOwnersIdempotently() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("module_ownership_safety")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            assertEquals(9, tx.inTransaction(db -> db.fetchOne(
                "select count(*)::int from module_runtime_control where effective_owner='general' and fallback_enabled").get(0, Integer.class)));
            var jobs = new JooqJobRecordDao(tx);
            jobs.insertRequestedIfAbsentForRun("fallback-job", "fallback-key", "cp_policy_collect", "synthetic-run", "read",
                "cp_policy_collect", "synthetic-actor", "fixture").orElseThrow();
            assertEquals("fallback-job", new JooqJobLeaseDao(tx).claimNext("general-synthetic", List.of("cp_policy_collect"),
                Duration.ofMinutes(10)).orElseThrow().jobId());
            try (var migrate = fixture.migrateConnection()) {
                migrate.setAutoCommit(false);
                Ui2Rows.setAuditContext(migrate, "synthetic-release", "owner_recovery_fixture");
                try (var statement = migrate.createStatement()) {
                    statement.executeUpdate("update jobs set state='COMPLETED' where job_id='fallback-job'");
                    statement.executeUpdate("update module_runtime_control set effective_owner=module,fallback_enabled=false,"
                        + "owner_instance=module||'-synthetic',owner_heartbeat_at=case when module='backup' then now() "
                        + "when module='policy' then now()-interval '61 seconds' else null end "
                        + "where module in ('policy','scheduler','backup')");
                    String recovery;
                    try (var input = getClass().getResourceAsStream("/db/migration/V129__module_ownership_safety.sql")) {
                        assertNotNull(input);
                        recovery = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                    statement.execute(recovery);
                    try (var rows = statement.executeQuery("select count(*) from module_runtime_control "
                        + "where module in ('policy','scheduler') and effective_owner='general' and fallback_enabled and owner_instance is null")) {
                        assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
                    }
                    long generation;
                    try (var rows = statement.executeQuery("select generation from module_runtime_control where module='policy'")) {
                        assertTrue(rows.next()); generation = rows.getLong(1);
                    }
                    statement.execute(recovery);
                    try (var rows = statement.executeQuery("select generation from module_runtime_control where module='policy'")) {
                        assertTrue(rows.next()); assertEquals(generation, rows.getLong(1));
                    }
                    try (var rows = statement.executeQuery("select effective_owner='backup' and not fallback_enabled "
                        + "and owner_instance is not null from module_runtime_control where module='backup'")) {
                        assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
                    }
                }
                migrate.commit();
            }
        }
    }

}
