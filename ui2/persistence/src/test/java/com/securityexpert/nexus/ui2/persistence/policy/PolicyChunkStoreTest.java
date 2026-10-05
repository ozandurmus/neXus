package com.securityexpert.nexus.ui2.persistence.policy;

import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.persistence.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;

class PolicyChunkStoreTest {
    private record Chunk(byte[] payload, int plainBytes) {}
    private final Map<String, Map<Integer, Chunk>> chunks = new HashMap<>();
    private final Map<String, Object[]> inventories = new TreeMap<>();
    private Object[] unit, domain;
    private final List<String> statements = new ArrayList<>();
    private final DSLContext create = DSL.using(SQLDialect.POSTGRES);
    private boolean failChunks;
    private int chunkTransactions, manifestTransactions;
    private final TransactionBoundary tx = new TransactionBoundary() {
        @Override public <T> T inTransaction(java.util.function.Function<DSLContext, T> work) {
            int before = statements.size();
            T value = work.apply(DSL.using(new MockConnection(PolicyChunkStoreTest.this::execute), SQLDialect.POSTGRES));
            var sql = statements.subList(before, statements.size());
            boolean chunk = sql.stream().anyMatch(s -> s.startsWith("insert into cp_policy_json_chunk"));
            boolean manifest = sql.stream().anyMatch(s -> s.startsWith("insert into cp_policy_object_inventory")
                || s.startsWith("insert into policy_unit_checkpoint") || s.startsWith("insert into cp_policy_domain_run"));
            assertFalse(chunk && manifest, "Each chunk must commit before manifest publication");
            if (chunk) chunkTransactions++;
            if (manifest) manifestTransactions++;
            return value;
        }
    };
    private MockResult[] execute(MockExecuteContext context) throws java.sql.SQLException {
        String sql = context.sql(); statements.add(sql);
        Object[] args = context.bindings();
        for (Object arg : args) {
            if (arg instanceof String text) assertTrue(PolicyJsonWrite.bytes(text) <= PolicyChunkStore.MAX_BYTES);
            if (arg instanceof byte[] bytes) assertTrue(bytes.length <= PolicyChunkStore.MAX_BYTES);
        }
        if (sql.startsWith("insert into cp_policy_json_chunk")) {
            if (failChunks) throw new java.sql.SQLException("synthetic database failure");
            chunks.computeIfAbsent((String) args[0], k -> new TreeMap<>()).put((Integer) args[5], new Chunk((byte[]) args[6], (Integer) args[7]));
        } else if (sql.startsWith("select job_id")) {
            return new MockResult[]{new MockResult(1, create.fetchFromStringData(new String[]{"job_id"}, new String[]{"job-1"}))};
        } else if (sql.startsWith("insert into policy_unit_checkpoint")) unit = args;
        else if (sql.startsWith("insert into cp_policy_domain_run")) domain = args;
        else if (sql.startsWith("select snapshot::text") && sql.contains("from policy_unit_checkpoint")) {
            var rows = create.newResult(new Field<?>[]{DSL.field("snapshot", String.class), DSL.field("chunk_generation", String.class), DSL.field("chunk_count", Integer.class)});
            if (unit != null) {
                var row = create.newRecord(rows.fields()); row.fromArray("{}", unit[3], unit[4]); rows.add(row);
            }
            return new MockResult[]{new MockResult(rows.size(), rows)};
        } else if (sql.startsWith("select complete")) {
            var rows = create.newResult(new Field<?>[]{DSL.field("complete", Boolean.class), DSL.field("signal", String.class), DSL.field("snapshots", String.class),
                DSL.field("chunk_generation", String.class), DSL.field("chunk_count", Integer.class)});
            if (domain != null) {
                var row = create.newRecord(rows.fields()); row.fromArray(domain[4], domain[5], "[]", domain[8], domain[9]); rows.add(row);
            }
            return new MockResult[]{new MockResult(rows.size(), rows)};
        } else if (sql.startsWith("insert into cp_policy_object_inventory")) inventories.put((String) args[2], args);
        else if (sql.startsWith("select snapshot::text")) {
            var rows = create.newResult(new Field<?>[]{DSL.field("snapshot", String.class), DSL.field("object_type", String.class),
                DSL.field("chunk_generation", String.class), DSL.field("chunk_count", Integer.class)});
            inventories.forEach((type, argsStored) -> {
                var row = create.newRecord(rows.fields());
                row.set(DSL.field("snapshot", String.class), (String) argsStored[4]);
                row.set(DSL.field("object_type", String.class), type);
                row.set(DSL.field("chunk_generation", String.class), (String) argsStored[5]);
                row.set(DSL.field("chunk_count", Integer.class), (Integer) argsStored[6]); rows.add(row);
            });
            return new MockResult[]{new MockResult(rows.size(), rows)};
        } else if (sql.startsWith("select payload")) {
            var chunk = chunks.get((String) args[0]).get((Integer) args[5]);
            if (chunk == null) return new MockResult[]{new MockResult(0, create.newResult(new Field<?>[]{DSL.field("payload", byte[].class), DSL.field("plain_bytes", Integer.class)}))};
            var rows = create.newResult(new Field<?>[]{DSL.field("payload", byte[].class), DSL.field("plain_bytes", Integer.class)});
            var row = create.newRecord(rows.fields());
            row.set(DSL.field("payload", byte[].class), chunk.payload()); row.set(DSL.field("plain_bytes", Integer.class), chunk.plainBytes()); rows.add(row);
            return new MockResult[]{new MockResult(1, rows)};
        }
        return new MockResult[]{new MockResult(1, null)};
    }

    @Test void incompleteDomainRetainsSignalForResumeWithoutEnablingReuse() {
        var repository = new PolicyCollectionRepository(tx);
        var request = new PolicyCollectionRepository.Request("source-1", "", false, PolicyCollectionRepository.Mode.FULL, "job-1", 7);
        String signal = "{\"uid\":\"published-001\"}";
        assertTrue(repository.saveDomain(request, "domain-1", "COLLECTING", false, signal, "[]", 0, null, "synthetic-actor"));
        var resumed = repository.domainForRequest(request, "domain-1").orElseThrow();
        assertFalse(resumed.complete());
        assertEquals(signal, resumed.signalJson());
    }

    @Test void resumedUnitsAndCurrentDomainsReadBoundedChunkGenerations() {
        var repository = new PolicyCollectionRepository(tx);
        var request = new PolicyCollectionRepository.Request("source-1", "", false, PolicyCollectionRepository.Mode.FULL, "job-1", 7);
        String snapshot = "{\"synthetic\":\"" + "x".repeat(2 * PolicyChunkStore.MAX_BYTES) + "\"}";
        assertTrue(repository.saveUnit(request, "unit-1", "version-1", snapshot));
        assertEquals(snapshot, repository.resumedUnit(request, "unit-1", "version-1").orElseThrow());
        assertTrue(repository.saveDomain(request, "domain-1", "COLLECTED", true, "{}", "[" + snapshot + "]", 0, null, "synthetic-actor"));
        assertEquals("[" + snapshot + "]", repository.domainForRequest(request, "domain-1").orElseThrow().snapshotsJson());
        assertEquals(2, manifestTransactions);
        assertTrue(chunkTransactions > 2);
        var previousUnit = unit;
        failChunks = true;
        assertThrows(PolicyDatabaseFailure.class, () -> repository.saveUnit(request, "unit-1", "version-2", snapshot));
        assertSame(previousUnit, unit, "A failed chunk must not replace the resumable unit manifest");
    }

    @Test void sixtyMiBInventoryUsesShortBoundedWritesAndReadsBackExactlyIncludingUnicode() {
        String item = "{\"uid\":\"opaque-001\",\"name\":\"" + "😀éx".repeat(1500) + "\"}";
        int count = 60 * 1024 * 1024 / (int) PolicyJsonWrite.bytes(item) + 1;
        String value = "{\"type\":\"hosts\",\"status\":\"RESOLVED\",\"objects\":[" + String.join(",", Collections.nCopies(count, item)) + "]}";
        assertTrue(PolicyJsonWrite.bytes(value) >= 60 * 1024 * 1024);
        new PolicyCollectionRepository(tx).saveInventory("source-1", "domain-1", "hosts", "2026-10-05T00:00:00Z", value, "synthetic-actor");
        assertTrue(chunkTransactions > 50); assertEquals(1, manifestTransactions);
        assertEquals(value, new PolicySnapshotRepository(tx).inventories("source-1", "domain-1").get(0));
        assertFalse(((String) inventories.get("hosts")[4]).contains("opaque-001"));
    }
    @Test void oldFormatRemainsReadableAndFailedChunksNeverPublishAManifest() {
        String old = "{\"status\":\"RESOLVED\",\"objects\":[]}";
        inventories.put("hosts", new Object[]{"source-1", "domain-1", "hosts", "2026-10-05T00:00:00Z", old, null, null});
        assertEquals(List.of(old), new PolicySnapshotRepository(tx).inventories("source-1", "domain-1"));
        failChunks = true;
        assertThrows(PolicyDatabaseFailure.class, () -> new PolicyCollectionRepository(tx).saveInventory(
            "source-1", "domain-1", "hosts", "2026-10-05T01:00:00Z", old, "synthetic-actor"));
        assertEquals(0, manifestTransactions);
        assertEquals(List.of(old), new PolicySnapshotRepository(tx).inventories("source-1", "domain-1"));
    }
    @Test void oversizeSignalHasAPreciseCodeAndIssuesNoStatement() {
        var repository = new PolicyCollectionRepository(tx);
        var request = new PolicyCollectionRepository.Request("source-1", "", false, PolicyCollectionRepository.Mode.CHANGED_ONLY, "job-1", 1);
        var failure = assertThrows(PolicyDatabaseFailure.class, () -> repository.saveDomain(request, "domain-1", "COLLECTED", true,
            "x".repeat(PolicyChunkStore.MAX_BYTES + 1), "[]", 0, null, "synthetic-actor"));
        assertEquals("POLICY_DB_STATEMENT_PARAMETER_TOO_LARGE", failure.getMessage()); assertNull(failure.getCause());
        assertTrue(statements.isEmpty());
    }
    @Test void missingChunkFailsClosedRatherThanReturningATruncatedInventory() {
        var store = new PolicyChunkStore(tx);
        var manifest = store.write("source-1", "domain-1", "hosts", "INVENTORY", "{}", "synthetic-actor");
        // The reader must reject any manifest claiming an extra, unwritten page.
        var failure = assertThrows(IllegalStateException.class, () -> store.read("source-1", "domain-1", "hosts", "INVENTORY",
            new PolicyChunkStore.Manifest(manifest.generation(), manifest.count() + 1)));
        assertEquals("POLICY_CHUNK_MISSING", failure.getMessage());
    }
}
