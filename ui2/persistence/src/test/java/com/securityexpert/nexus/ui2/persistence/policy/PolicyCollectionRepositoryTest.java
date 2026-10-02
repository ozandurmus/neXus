package com.securityexpert.nexus.ui2.persistence.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

class PolicyCollectionRepositoryTest {
    @Test void automaticAdmissionUsesDurableSixHourBoundaryAndManualStillRecordsAttempt() {
        List<String> sql = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            return new MockResult[] { new MockResult(calls.getAndIncrement() == 1 ? 0 : 1, null) };
        }), SQLDialect.POSTGRES)));
        assertTrue(repository.beginDomain("mds-1", "domain-01", true));
        assertFalse(repository.beginDomain("mds-1", "domain-01", true));
        assertTrue(repository.beginDomain("mds-1", "domain-01", false));
        assertTrue(sql.get(0).contains("attempted_at <= now() - interval '6 hours'"));
        assertFalse(sql.get(2).contains("where policy_collection_domain"));
        assertTrue(sql.stream().allMatch(s -> s.contains("on conflict (source_id, domain_ref) do update")));
    }
    @Test void expiredLeaseCannotPublishEvenAnOtherwiseCompleteSnapshot() {
        List<String> sql = new ArrayList<>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            return new MockResult[] { new MockResult(0, context.sql().startsWith("select job_id")
                ? create.fetchFromStringData(new String[] { "job_id" }) : null) };
        }), SQLDialect.POSTGRES)));
        var snapshot = new PolicySnapshotRepository.Stored("policy-1", "2026-10-01T12:00:00Z", "{}", "{}");
        assertFalse(repository.publish("job-1", 7, List.of(snapshot), "synthetic-actor"));
        assertFalse(repository.checkpoint("job-1", 7, snapshot, "synthetic-actor"));
        assertFalse(repository.publishWithWarnings("job-1", 7, List.of(snapshot), "synthetic-actor", "PARTIAL_SNAPSHOT layer-1: TIMEOUT"));
        assertTrue(sql.stream().anyMatch(s -> s.contains("lease_epoch = ?") && s.contains("lease_expires_at > now() for update")));
        assertFalse(sql.stream().anyMatch(s -> s.startsWith("insert into policy_snapshot")));
    }    @Test void unmatchedPanMemberUsesOpaqueReferenceAndExactDiscoveryIdentity() {
        List<String> sql = new ArrayList<>();
        List<Object> bindings = new ArrayList<>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql()); bindings.addAll(Arrays.asList(context.bindings()));
            return new MockResult[] { new MockResult(0, create.fetchFromStringData(new String[] { "device_id", "display_name" })) };
        }), SQLDialect.POSTGRES)));
        var target = repository.panTargets("run-1", "manager-1", "synthetic-0001", "vsys1", "UNKNOWN").get(0);
        assertEquals(com.securityexpert.nexus.ui2.policy.PolicySnapshot.ref("manager-1", "member", "synthetic-0001"), target.deviceId());
        assertNotEquals(com.securityexpert.nexus.ui2.policy.PolicySnapshot.ref("manager-1", "member", "synthetic-1"), target.deviceId());
        assertTrue(bindings.contains("synthetic-0001"));
        assertTrue(sql.get(0).contains("d.discovery_match_key = 'palo_alto|' || c.stable_identifier"));
        assertFalse(sql.get(0).contains("lower("));
        assertEquals("UNKNOWN", target.syncStatus());
    }

    @Test void partialPublicationStoresSnapshotsUnderTheLeaseLockAndPreservesCpOutcome() {
        for (boolean completedWithWarnings : List.of(false, true)) {
            List<String> sql = new ArrayList<>(); List<Object> bindings = new ArrayList<>();
            var create = DSL.using(SQLDialect.POSTGRES);
            var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
                sql.add(context.sql()); bindings.addAll(Arrays.asList(context.bindings()));
                return new MockResult[] { new MockResult(1, context.sql().startsWith("select job_id")
                    ? create.fetchFromStringData(new String[] { "job_id" }, new String[] { "job-1" }) : null) };
            }), SQLDialect.POSTGRES)));
            var snapshots = List.of(new PolicySnapshotRepository.Stored("policy-1", "2026-10-01T12:00:00Z", "{}", "{}"));
            String warning = "PARTIAL_SNAPSHOT layer-1: TIMEOUT";
            assertTrue(completedWithWarnings ? repository.publishWithWarnings("job-1", 7, snapshots, "synthetic-actor", warning)
                    : repository.publish("job-1", 7, snapshots, "synthetic-actor", warning));
            int locked = -1, saved = -1, completed = -1;
            for (int i = 0; i < sql.size(); i++) {
                if (sql.get(i).contains("for update")) locked = i;
                if (sql.get(i).startsWith("insert into policy_snapshot")) saved = i;
                if (sql.get(i).startsWith("update jobs set state")) completed = i;
            }
            assertTrue(locked >= 0 && saved > locked && completed > saved);
            assertTrue(bindings.contains(completedWithWarnings ? "COMPLETED" : "FAILED"));
            assertTrue(bindings.contains("PARTIAL_SNAPSHOT layer-1: TIMEOUT"));
        }
    }

    @Test void warningPublicationRequiresAtLeastOneSnapshot() {
        var repository = new PolicyCollectionRepository(null);
        assertThrows(IllegalArgumentException.class, () -> repository.publishWithWarnings("job-1", 7, List.of(),
                "synthetic-actor", "PARTIAL_SNAPSHOT layer-1: TIMEOUT"));
    }

    @Test void checkpointCommitsUnderLeaseLockWithoutTerminalTransition() {
        for (boolean live : List.of(true, false)) {
            List<String> sql = new ArrayList<>();
            var create = DSL.using(SQLDialect.POSTGRES);
            var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
                sql.add(context.sql());
                return new MockResult[] { new MockResult(live ? 1 : 0, context.sql().startsWith("select job_id")
                    ? live ? create.fetchFromStringData(new String[] { "job_id" }, new String[] { "job-1" })
                           : create.fetchFromStringData(new String[] { "job_id" }) : null) };
            }), SQLDialect.POSTGRES)));
            assertEquals(live, repository.checkpoint("job-1", 7,
                new PolicySnapshotRepository.Stored("policy-1", "2026-10-02T03:39:00Z", "{}", "{}"), "synthetic-actor"));
            assertTrue(sql.stream().anyMatch(q -> q.contains("lease_expires_at > now() for update")));
            assertEquals(live, sql.stream().anyMatch(q -> q.startsWith("insert into policy_snapshot")));
            assertFalse(sql.stream().anyMatch(q -> q.startsWith("update jobs")));
        }
    }
    @Test void statusReadsLatestLayerCountersInCurrentLeaseEpoch() {
        List<String> sql = new ArrayList<>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            return new MockResult[] { new MockResult(1, create.fetchFromStringData(
                new String[] { "state", "reason", "step", "total", "layer", "layers", "rules" },
                new String[] { "EXECUTING", "", "12", "0", "2", "5", "4000" })) };
        }), SQLDialect.POSTGRES)));
        var status = repository.status("job-1").orElseThrow();
        assertEquals(2, status.get("layer"));
        assertEquals(5, status.get("layers"));
        assertEquals(4000, status.get("rulesFetched"));
        assertTrue(sql.get(0).contains("order by step_index desc limit 1"));
        assertTrue(sql.get(0).contains("lease_epoch = j.lease_epoch"));
    }

}
