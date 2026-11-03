package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.jooq.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.*;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;

/** catalog() asked the local service for every policy on every call; the decrypt and XML parse now follow the artefact. */
class LocalFirewallPolicyCacheTest {
    static final String XML = "<config><devices><entry name='localhost.localdomain'><vsys><entry name='vsys1'>"
        + "<rulebase><security><rules><entry name='Synthetic rule'><action>allow</action></entry></rules></security>"
        + "</rulebase></entry></vsys></entry></devices></config>";
    final AtomicLong clock = new AtomicLong(0);
    final List<org.jooq.Record> listed = new ArrayList<>();
    final ArtefactStore store = mock(ArtefactStore.class);
    final LocalFirewallPolicyService service;

    LocalFirewallPolicyCacheTest() throws Exception {
        var db = mock(DSLContext.class);
        @SuppressWarnings("unchecked") Result<org.jooq.Record> rows = mock(Result.class);
        when(rows.iterator()).thenAnswer(call -> listed.iterator());
        when(rows.isEmpty()).thenAnswer(call -> listed.isEmpty());
        when(db.fetch(anyString())).thenReturn(rows);
        TransactionBoundary tx = new TransactionBoundary() {
            @Override public <T> T inTransaction(java.util.function.Function<DSLContext, T> work) { return work.apply(db); }
        };
        when(store.retrieve(any(), any(), eq(false))).thenAnswer(call -> new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)));
        service = new LocalFirewallPolicyService(tx, new ArtefactStoreAccess(store), 1_000, 60_000_000_000L, clock::get);
    }
    private org.jooq.Record row(String device, String artefact, String collectedAt) {
        var row = mock(org.jooq.Record.class);
        when(row.get("device_id", String.class)).thenReturn(device);
        when(row.get("collected_at", Timestamp.class)).thenReturn(Timestamp.from(java.time.Instant.parse(collectedAt)));
        when(row.get("artefact_ref", String.class)).thenReturn(artefact);
        when(row.get("wrapped_data_key", byte[].class)).thenReturn(new byte[32]);
        when(row.get("compression", String.class)).thenReturn("none");
        return row;
    }

    @Test void unchangedArtefactIsParsedOnceAndSharedUntilTheTtl() {
        listed.add(row("device-1", "artefact-1", "2026-10-01T12:00:00Z"));
        var first = service.snapshots();
        for (int i = 0; i < 10; i++) assertSame(first.get(0), service.snapshots().get(0));
        assertEquals(1, service.parseCount());
        clock.addAndGet(60_000_000_000L);
        service.snapshots();
        assertEquals(2, service.parseCount(), "the short TTL bounds how long a parse is trusted");
    }

    @Test void newerArtefactReplacesTheParseAndUnlistedDevicesAreDropped() {
        listed.add(row("device-1", "artefact-1", "2026-10-01T12:00:00Z")); listed.add(row("device-2", "artefact-2", "2026-10-01T12:00:00Z"));
        var before = service.snapshots();
        assertEquals(2, before.size()); assertEquals(2, service.parseCount());
        listed.set(0, row("device-1", "artefact-1b", "2026-10-02T12:00:00Z"));
        var after = service.snapshots();
        assertEquals(3, service.parseCount(), "only the replaced device is parsed again");
        assertNotSame(before.get(0), after.get(0)); assertSame(before.get(1), after.get(1));
        listed.remove(1);
        assertEquals(1, service.snapshots().size());
        listed.add(row("device-2", "artefact-2", "2026-10-01T12:00:00Z"));
        service.snapshots();
        assertEquals(4, service.parseCount(), "a device that left the listing does not keep a stale parse");
    }
}
