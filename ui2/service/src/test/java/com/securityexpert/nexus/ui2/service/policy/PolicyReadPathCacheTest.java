package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** The stored-snapshot read path: parse once per row version, cheap identity, existence without parsing. */
class PolicyReadPathCacheTest {
    final ObjectMapper mapper = new ObjectMapper();
    final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);

    /** A one-row policy_snapshot table that answers the three read statements and records every statement. */
    final class Store {
        final List<String> sql = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger documentReads = new AtomicInteger();
        volatile String version; volatile String json; volatile boolean present = true;
        volatile CountDownLatch gate;
        Store(String version, PolicySnapshot snapshot) { replace(version, snapshot); }
        void replace(String version, PolicySnapshot snapshot) {
            try { this.json = mapper.writeValueAsString(snapshot); } catch (Exception e) { throw new IllegalStateException(e); }
            this.version = version; this.present = true;
        }
        PolicyQueryService service(long maxWeight) {
            var create = DSL.using(SQLDialect.POSTGRES);
            var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
                sql.add(context.sql());
                String text = context.sql();
                if (text.contains("select 1 from policy_snapshot"))
                    return new MockResult[]{new MockResult(present ? 1 : 0, present ? create.fetchFromStringData(new String[]{"?column?"}, new String[]{"1"})
                        : create.fetchFromStringData(new String[]{"?column?"}))};
                if (text.contains("snapshot::text")) {
                    documentReads.incrementAndGet();
                    var latch = gate; if (latch != null) { try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); } }
                    return new MockResult[]{new MockResult(present ? 1 : 0, present
                        ? create.fetchFromStringData(new String[]{"version", "snapshot"}, new String[]{version, json})
                        : create.fetchFromStringData(new String[]{"version", "snapshot"}))};
                }
                return new MockResult[]{new MockResult(present ? 1 : 0, present
                    ? create.fetchFromStringData(new String[]{"version"}, new String[]{version})
                    : create.fetchFromStringData(new String[]{"version"}))};
            }), SQLDialect.POSTGRES));
            var service = new PolicyQueryService(tx, mapper, names, null, ips);
            service.configureSnapshotCache(maxWeight);
            return service;
        }
    }

    static PolicySnapshot withRules(String id, int rules) {
        var base = SyntheticPolicies.large("CP", rules, 3);
        var m = base.metadata();
        return new PolicySnapshot(new PolicySnapshot.Metadata(id, m.sourceId(), m.sourceName(), m.vendor(), m.containerId(), m.containerName(),
            m.name(), m.collectedAt(), m.artefactRef(), m.targets()), base.sections(), base.objects(), base.failures());
    }

    @Test void repeatedReadsParseOnceAndShareTheImmutableInstance() {
        var store = new Store("v1", withRules("p1", 300));
        var service = store.service(PolicyQueryService.DEFAULT_SNAPSHOT_CACHE_WEIGHT);
        var first = service.find("p1").orElseThrow();
        for (int i = 0; i < 25; i++) assertSame(first, service.find("p1").orElseThrow());
        assertEquals(1, service.snapshotParseCount());
        assertEquals(1, store.documentReads.get(), "the document is fetched once; later requests read only the row version");
        assertEquals(300, first.sections().get(0).rules().size());
    }

    @Test void replacedRowIsReparsedAndDeletedRowDisappears() {
        var store = new Store("2026-10-01T00:00:00.000000|100", withRules("p1", 50));
        var service = store.service(PolicyQueryService.DEFAULT_SNAPSHOT_CACHE_WEIGHT);
        var old = service.find("p1").orElseThrow();
        assertEquals(50, old.sections().get(0).rules().size());
        store.replace("2026-10-02T00:00:00.000000|120", withRules("p1", 60));
        var replaced = service.find("p1").orElseThrow();
        assertNotSame(old, replaced);
        assertEquals(60, replaced.sections().get(0).rules().size());
        assertSame(replaced, service.find("p1").orElseThrow());
        assertEquals(2, service.snapshotParseCount());
        store.present = false;
        assertTrue(service.find("p1").isEmpty());
        assertEquals(0, service.snapshotCache().size(), "a deleted row leaves nothing behind");
        store.replace("2026-10-03T00:00:00.000000|90", withRules("p1", 5));
        assertEquals(5, service.find("p1").orElseThrow().sections().get(0).rules().size());
    }

    @Test void analysisIsKeyedByTheCachedInstanceNotByDeepEquality() {
        var store = new Store("v1", withRules("p1", 200));
        var service = store.service(PolicyQueryService.DEFAULT_SNAPSHOT_CACHE_WEIGHT);
        var analysis = service.analysis(service.find("p1").orElseThrow(), 90);
        assertSame(analysis, service.analysis(service.find("p1").orElseThrow(), 90));
        store.replace("v2", withRules("p1", 210));
        var next = service.analysis(service.find("p1").orElseThrow(), 90);
        assertNotSame(analysis, next);
        assertEquals(210, next.rules().size());
    }

    @Test void sourceNamingKeepsOneInstanceWhileHostnamesAreUnchanged() {
        var store = new Store("v1", withRules("p1", 40));
        var service = store.service(PolicyQueryService.DEFAULT_SNAPSHOT_CACHE_WEIGHT);
        var devices = org.mockito.Mockito.mock(com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.class);
        var sourceId = withRules("p1", 1).metadata().sourceId();
        org.mockito.Mockito.when(devices.listNameIdentities()).thenReturn(List.of(
            new com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.NameIdentity(sourceId, Optional.of("synthetic-manager"), Optional.empty())));
        service.setDeviceRepository(devices);
        var named = service.find("p1").orElseThrow();
        assertEquals("synthetic-manager", named.metadata().sourceName());
        assertSame(named, service.find("p1").orElseThrow());
        assertSame(service.analysis(named, 90), service.analysis(service.find("p1").orElseThrow(), 90));
        assertEquals(1, service.snapshotParseCount());
    }

    @Test void concurrentFirstRequestsShareOneRead() throws Exception {
        var store = new Store("v1", withRules("p1", 300));
        var service = store.service(PolicyQueryService.DEFAULT_SNAPSHOT_CACHE_WEIGHT);
        store.gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<PolicySnapshot>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) results.add(pool.submit(() -> service.find("p1").orElseThrow()));
            Thread.sleep(200);
            store.gate.countDown();
            var first = results.get(0).get(20, TimeUnit.SECONDS);
            for (var r : results) assertSame(first, r.get(20, TimeUnit.SECONDS));
            assertEquals(1, service.snapshotParseCount());
        } finally { pool.shutdownNow(); }
    }

    @Test void historyExistenceNeverReadsTheDocument() {
        var store = new Store("v1", withRules("p1", 300));
        var service = store.service(PolicyQueryService.DEFAULT_SNAPSHOT_CACHE_WEIGHT);
        assertTrue(service.exists("p1"));
        store.present = false;
        assertFalse(service.exists("p1"));
        assertEquals(0, store.documentReads.get());
        assertEquals(0, service.snapshotParseCount());
        assertTrue(store.sql.stream().noneMatch(s -> s.contains("snapshot::text")));
    }

    @Test void policyHeavierThanTheWholeBoundIsNeverKept() {
        var store = new Store("v1", withRules("p1", 300));
        var service = store.service(100);
        service.find("p1"); service.find("p1");
        assertEquals(2, service.snapshotParseCount());
        assertEquals(0, service.snapshotCache().size());
    }

    /** Measurement: N page reads of a large synthetic policy, with the parse cache disabled (bound of 1) and enabled. */
    @Test void repeatedPageReadsOfALargeSnapshotNoLongerReparse() {
        int rules = 8_000, reads = 20;
        var store = new Store("v1", SyntheticPolicies.large("CP", rules, 7));
        long[] ms = new long[2]; long[] parses = new long[2]; long[] fetches = new long[2];
        for (int mode = 0; mode < 2; mode++) {
            var service = store.service(mode == 0 ? 1 : PolicyQueryService.DEFAULT_SNAPSHOT_CACHE_WEIGHT);
            store.documentReads.set(0);
            long begun = System.nanoTime();
            for (int i = 0; i < reads; i++) {
                var snapshot = service.find("large-CP-" + rules).orElseThrow();
                assertEquals(rules, snapshot.sections().get(0).rules().size());
            }
            ms[mode] = (System.nanoTime() - begun) / 1_000_000; parses[mode] = service.snapshotParseCount(); fetches[mode] = store.documentReads.get();
        }
        System.out.printf("MEASURE find() x%d on a %d-rule snapshot: uncached %d parses / %d document reads / %d ms; cached %d parses / %d document reads / %d ms%n",
            reads, rules, parses[0], fetches[0], ms[0], parses[1], fetches[1], ms[1]);
        assertEquals(reads, parses[0]);
        assertEquals(1, parses[1]);
        assertEquals(1, fetches[1]);
        assertTrue(ms[1] < ms[0], "cached reads must be faster");
    }
}
