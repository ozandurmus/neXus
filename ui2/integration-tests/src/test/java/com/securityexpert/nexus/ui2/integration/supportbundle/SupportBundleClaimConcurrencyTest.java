package com.securityexpert.nexus.ui2.integration.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ClaimDecision;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemSpec;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;

/**
 * Step S6, claim time admission against a real PostgreSQL 16: an HA peer serializes, the fleet runs at most two heavy
 * (recovery-write) bundles, two jobs claimed in the same instant never both pass, a late job is given up after three hours.
 */
class SupportBundleClaimConcurrencyTest {
    private static final String ADMIN = "ACTOR-ALPHA";

    private static void exec(Connection db, String sql) throws SQLException {
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var st = db.createStatement()) { st.execute(sql); }
            db.commit();
        } finally { db.setAutoCommit(true); }
    }

    private static String value(Connection db, String sql) throws SQLException {
        try (var st = db.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }

    private static String job(Connection db, String device, String state, String actionClass, String submittedAt) throws SQLException {
        String id = Ui2Rows.opaqueId("job");
        exec(db, "INSERT INTO jobs(job_id, job_type, capability_id, target_kind, target_device_id, submitted_by_actor_fingerprint, state, "
                + "action_class, submitted_at) VALUES ('" + id + "', 'cp_support_bundle', 'cp_support_bundle', 'device', '" + device + "', '"
                + ADMIN + "', '" + state + "', '" + actionClass + "', " + submittedAt + ")");
        return id;
    }

    private static RequestRow request(String device, UUID id, Instant runAt) {
        Instant now = Instant.now();
        return new RequestRow(id, device, "check_point", "cp_gaia_gateway", "NOW", runAt, null, "TAC case preparation", ADMIN, now, null,
                null, now.plusSeconds(14 * 86400), null, null, 0, null, null, null, "{}", "QUEUED", null);
    }

    private UUID bundle(JooqSupportBundleStore store, String device, String jobId, Instant runAt) {
        UUID id = UUID.randomUUID();
        store.insert(request(device, id, runAt), List.of(new ItemSpec("cp_hcp_run", null)));
        store.attachJob(id, jobId, ADMIN);
        return id;
    }

    @Test
    void haPeersSerializeTheFleetCapIsTwoHeavyRunsAndALateJobIsGivenUp() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("support_bundle_claim")) {
            fixture.runFlyway();
            String[] devices = new String[6];
            try (Connection db = fixture.migrateConnection()) {
                String cred = Ui2Rows.insertCredentialReference(db);
                for (int i = 0; i < devices.length; i++) devices[i] = Ui2Rows.insertDevice(db, cred, "ENROLLED");
                // devices 0 and 1 are members of one cluster; device 5 is alone
                exec(db, "UPDATE devices SET cluster_member_ref = 'cluster-synthetic-1' WHERE device_id IN ('" + devices[0] + "', '" + devices[1] + "')");
            }
            var store = new JooqSupportBundleStore(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            Instant now = Instant.now();

            // 1. HA peer: the first member EXECUTING, the second member just claimed is deferred ten minutes
            String j0, j1;
            try (Connection db = fixture.migrateConnection()) {
                j0 = job(db, devices[0], "EXECUTING", "recovery-write", "now() - interval '5 minutes'");
                j1 = job(db, devices[1], "CLAIMED", "recovery-write", "now() - interval '4 minutes'");
            }
            UUID r0 = bundle(store, devices[0], j0, now);
            bundle(store, devices[1], j1, now);
            var peer = assertInstanceOf(ClaimDecision.Defer.class, store.claimDecision(j1, now, 2));
            assertEquals("HA_PEER_RUNNING", peer.reason());
            assertEquals(now.plus(Duration.ofMinutes(10)), peer.until());
            // the member that is already running is never held back by its peer
            assertInstanceOf(ClaimDecision.Proceed.class, store.claimDecision(j0, now, 2));

            // 2. two peers claimed in the same instant: exactly one passes (the earlier submitted)
            try (Connection db = fixture.migrateConnection()) { exec(db, "UPDATE jobs SET state = 'CLAIMED' WHERE job_id = '" + j0 + "'"); }
            assertInstanceOf(ClaimDecision.Proceed.class, store.claimDecision(j0, now, 2));
            assertInstanceOf(ClaimDecision.Defer.class, store.claimDecision(j1, now, 2));

            // deferring puts the claimed job back, claimable only from the defer time
            try (Connection db = fixture.migrateConnection()) { exec(db, "UPDATE jobs SET lease_epoch = 1, lease_worker_id = 'w' WHERE job_id = '" + j1 + "'"); }
            try (Connection db = fixture.migrateConnection()) {
                assertEquals("CLAIMED|1", value(db, "select state || '|' || lease_epoch from jobs where job_id = '" + j1 + "'"));
            }
            boolean deferred = false;
            try { deferred = store.deferClaimed(j1, 1, now.plus(Duration.ofMinutes(10))); }
            catch (RuntimeException e) { throw new AssertionError(String.valueOf(e), e); }
            assertTrue(deferred);
            try (Connection db = fixture.migrateConnection()) {
                assertEquals("REQUESTED", value(db, "select state from jobs where job_id = '" + j1 + "'"));
                assertEquals("1", value(db, "select admission_deferrals from jobs where job_id = '" + j1 + "'"));
                assertEquals("t", value(db, "select (admission_not_before > now() + interval '9 minutes') from jobs where job_id = '" + j1 + "'"));
            }

            // 3. fleet cap: two heavy runs executing (devices 2 and 3), a third heavy claim on device 4 defers five minutes
            String j2, j3, j4, j5;
            try (Connection db = fixture.migrateConnection()) {
                j2 = job(db, devices[2], "EXECUTING", "recovery-write", "now() - interval '3 minutes'");
                j3 = job(db, devices[3], "EXECUTING", "recovery-write", "now() - interval '2 minutes'");
                j4 = job(db, devices[4], "CLAIMED", "recovery-write", "now() - interval '1 minutes'");
                j5 = job(db, devices[5], "CLAIMED", "read", "now()");
            }
            bundle(store, devices[2], j2, now);
            bundle(store, devices[3], j3, now);
            bundle(store, devices[4], j4, now);
            bundle(store, devices[5], j5, now);
            var cap = assertInstanceOf(ClaimDecision.Defer.class, store.claimDecision(j4, now, 2));
            assertEquals("FLEET_CAP", cap.reason());
            assertEquals(now.plus(Duration.ofMinutes(5)), cap.until());
            // a read only bundle is not a heavy run: the cap does not hold it
            assertInstanceOf(ClaimDecision.Proceed.class, store.claimDecision(j5, now, 2));
            // the two executing runs and the claimed first peer (also heavy) are three: a cap of four lets it through
            assertInstanceOf(ClaimDecision.Proceed.class, store.claimDecision(j4, now, 4));

            // 4. give up after three hours: claimed late, and never claimed at all
            assertInstanceOf(ClaimDecision.GiveUp.class, store.claimDecision(j5, now.plus(Duration.ofHours(3)).plusSeconds(60), 2));
            try (Connection db = fixture.migrateConnection()) { exec(db, "UPDATE jobs SET lease_epoch = 1 WHERE job_id = '" + j5 + "'"); }
            assertTrue(store.giveUpClaimed(j5, 1));
            try (Connection db = fixture.migrateConnection()) {
                assertEquals("CANCELLED", value(db, "select state from jobs where job_id = '" + j5 + "'"));
                assertEquals("FAILED", value(db, "select state from support_bundle_request where job_id = '" + j5 + "'"));
                assertEquals("NOT_STARTED_DEVICE_BUSY", value(db, "select outcome from support_bundle_request where job_id = '" + j5 + "'"));
                assertEquals("CANCELLED", value(db, "select i.state from support_bundle_item i join support_bundle_request r on r.id = i.request_id where r.job_id = '" + j5 + "'"));
            }
            String stale;
            try (Connection db = fixture.migrateConnection()) { stale = job(db, devices[5], "REQUESTED", "recovery-write", "now()"); }
            bundle(store, devices[5], stale, now.minus(Duration.ofHours(4)));
            assertEquals(1, store.giveUpStale(now));
            try (Connection db = fixture.migrateConnection()) {
                assertEquals("NOT_STARTED_DEVICE_BUSY", value(db, "select outcome from support_bundle_request where job_id = '" + stale + "'"));
                assertEquals("CANCELLED", value(db, "select state from jobs where job_id = '" + stale + "'"));
                // a request that is not three hours late is untouched
                assertEquals("QUEUED", value(db, "select state from support_bundle_request where id = '" + r0 + "'"));
            }
            assertEquals(0, store.giveUpStale(now));
        }
    }

    @Test
    void theWorkDirectoryIsRecordedBeforeTheDeviceIsTouchedAndTheStoredFactsAreRead() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("support_bundle_workdir")) {
            fixture.runFlyway();
            String device;
            try (Connection db = fixture.migrateConnection()) {
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
            }
            var store = new JooqSupportBundleStore(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            UUID id = UUID.randomUUID();
            store.insert(request(device, id, Instant.now()), List.of(new ItemSpec("cp_cpinfo", 3), new ItemSpec("cpdata_collector_run", null)));
            assertEquals(3, store.items(id).get(0).vsId());
            store.markWorkDir(id, "/var/log/nexus-sb-0123456789ab");
            try (Connection db = fixture.migrateConnection()) {
                assertEquals("/var/log/nexus-sb-0123456789ab", value(db, "select work_dir_path from support_bundle_request where id = '" + id + "'"));
                assertEquals("f", value(db, "select work_dir_cleaned from support_bundle_request where id = '" + id + "'"));
            }
            store.markWorkDirCleaned(id);
            try (Connection db = fixture.migrateConnection()) {
                assertEquals("t", value(db, "select work_dir_cleaned from support_bundle_request where id = '" + id + "'"));
            }
            assertEquals(List.of(), store.virtualSystemIds(device));
            assertEquals(false, store.multiDomainServer(device));
        }
    }

    @Test
    void holdReasonsFollowUpLinksAndSingleAutoRetryConstraintHoldInPostgres() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("support_bundle_v274")) {
            fixture.runFlyway();
            String device;
            try (Connection db = fixture.migrateConnection()) {
                // V274 is repeatable inside BEGIN/ROLLBACK
                String v274;
                try (var in = getClass().getResourceAsStream("/db/migration/V274__support_bundle_request_again_and_busy_retry.sql")) {
                    v274 = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                db.setAutoCommit(false);
                try (var st = db.createStatement()) { st.execute(v274); st.execute(v274); }
                db.rollback();
                db.setAutoCommit(true);

                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                exec(db, "UPDATE module_runtime_control SET drain_requested = true, drain_requested_at = now() WHERE module = 'general'");
            }
            var store = new JooqSupportBundleStore(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            assertTrue(store.isSupportBundleModuleDraining());

            // 23:46 Istanbul (20:46Z) -> 02:46 Istanbul (23:46Z): crosses 00:00 collection window, module drain, and busy job
            Instant runAt = Instant.parse("2026-11-25T20:46:00Z");
            Instant giveUpAt = Instant.parse("2026-11-25T23:47:00Z");
            String staleJob;
            try (Connection db = fixture.migrateConnection()) {
                job(db, device, "EXECUTING", "read", "TIMESTAMPTZ '2026-11-25T22:00:00Z'");
                staleJob = job(db, device, "REQUESTED", "recovery-write", "TIMESTAMPTZ '2026-11-25T20:46:00Z'");
            }
            UUID origId = UUID.randomUUID();
            store.insert(new RequestRow(origId, device, "check_point", "cp_gaia_gateway", "NOW", runAt, "CASE-1",
                    "Night support bundle", ADMIN, runAt, null, null, runAt.plusSeconds(14 * 86400),
                    null, null, 0, null, null, null, "{}", "QUEUED", null, null, false, List.of(), null),
                    List.of(new ItemSpec("cp_cpinfo", 2, "dom-1")));
            store.attachJob(origId, staleJob, ADMIN);

            assertEquals(1, store.giveUpStale(giveUpAt));
            RequestRow expired = store.find(origId).orElseThrow();
            assertEquals("FAILED", expired.state());
            assertEquals("NOT_STARTED_DEVICE_BUSY", expired.outcome());
            assertEquals(List.of("MODULE_DRAIN", "COLLECTION_WINDOW", "DEVICE_JOB_RUNNING"), expired.holdReasons());
            assertEquals("dom-1", store.items(origId).get(0).domainRef());

            // Candidate selection + one-shot claim + unique auto-retry index + cancellable retry
            assertEquals(List.of(origId), store.autoRetryCandidates(10).stream().map(RequestRow::id).toList());
            assertTrue(store.claimAutoRetryEvaluation(origId));
            assertEquals(List.of(), store.autoRetryCandidates(10));

            UUID retryId = UUID.randomUUID();
            String retryJob;
            try (Connection db = fixture.migrateConnection()) {
                retryJob = job(db, device, "REQUESTED", "recovery-write", "now()");
            }
            Instant quiet = Instant.parse("2026-11-26T01:00:00Z");
            store.insert(new RequestRow(retryId, device, "check_point", "cp_gaia_gateway", "AT", quiet, "CASE-1",
                    "automatic retry of " + origId, ADMIN, giveUpAt, null, null, quiet.plusSeconds(14 * 86400),
                    null, null, 0, null, null, null, "{}", "QUEUED", null, origId, true, List.of(), null),
                    List.of(new ItemSpec("cp_cpinfo", 2, "dom-1")));
            store.attachJob(retryId, retryJob, ADMIN);
            store.finishAutoRetryEvaluation(origId, ADMIN, "RETRY_SCHEDULED");

            assertTrue(store.hasFollowUp(origId));
            assertEquals(retryId, store.followUps(List.of(origId)).get(origId));
            assertEquals(origId, store.find(retryId).orElseThrow().previousRequestId());
            assertTrue(store.find(retryId).orElseThrow().autoRetry());

            // Cancelling the queued automatic retry cancels both the request and its REQUESTED job; a second auto-retry is blocked by unique index
            assertTrue(store.cancelQueued(retryId, ADMIN));
            try (Connection db = fixture.migrateConnection()) {
                assertEquals("CANCELLED", value(db, "select state from jobs where job_id = '" + retryJob + "'"));
            }
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> store.insert(
                    new RequestRow(UUID.randomUUID(), device, "check_point", "cp_gaia_gateway", "AT", quiet, null,
                            "automatic retry of " + origId, ADMIN, giveUpAt, null, null, quiet.plusSeconds(14 * 86400),
                            null, null, 0, null, null, null, "{}", "QUEUED", null, origId, true, List.of(), null),
                    List.of(new ItemSpec("cp_gaia_extras", null))));

            // Closed hold_reasons constraint rejects arbitrary strings
            try (Connection db = fixture.migrateConnection()) {
                org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () ->
                        exec(db, "UPDATE support_bundle_request SET hold_reasons = ARRAY['RAW_FREE_TEXT'] WHERE id = '" + origId + "'"));
            }
        }
    }
}
