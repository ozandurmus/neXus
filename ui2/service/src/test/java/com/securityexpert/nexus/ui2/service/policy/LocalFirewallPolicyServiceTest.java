package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import org.jooq.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.*;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;

class LocalFirewallPolicyServiceTest {
    @Test void reusesEncryptedEffectiveRunningAndRefusesMissingStoreOrInvalidArtifact() throws Exception {
        var db = mock(DSLContext.class);
        @SuppressWarnings("unchecked") Result<org.jooq.Record> rows = mock(Result.class);
        var row = mock(org.jooq.Record.class);
        when(rows.iterator()).thenAnswer(call -> List.of(row).iterator());
        when(rows.isEmpty()).thenReturn(false);
        when(db.fetch(anyString())).thenReturn(rows);
        when(row.get("device_id", String.class)).thenReturn("device-1");
        when(row.get("collected_at", Timestamp.class)).thenReturn(Timestamp.from(java.time.Instant.parse("2026-10-01T12:00:00Z")));
        when(row.get("artefact_ref", String.class)).thenReturn("synthetic-artifact-1");
        when(row.get("wrapped_data_key", byte[].class)).thenReturn(new byte[32]);
        when(row.get("compression", String.class)).thenReturn("none");
        TransactionBoundary tx = new TransactionBoundary() {
            @Override public <T> T inTransaction(java.util.function.Function<DSLContext, T> work) { return work.apply(db); }
        };
        var store = mock(ArtefactStore.class);
        String xml = "<config><devices><entry name='localhost.localdomain'><vsys><entry name='vsys1'>"
            + "<rulebase><security><rules><entry name='Synthetic rule'><action>allow</action></entry></rules></security>"
            + "</rulebase></entry></vsys></entry></devices></config>";
        when(store.retrieve(any(), any(), eq(false))).thenAnswer(call -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var service = new LocalFirewallPolicyService(tx, new ArtefactStoreAccess(store));
        var snapshots = service.snapshots();
        assertEquals(1, snapshots.size());
        assertEquals("device-1", snapshots.get(0).metadata().targets().get(0).deviceId());
        assertNotEquals("synthetic-artifact-1", snapshots.get(0).metadata().artefactRef());
        verify(db).fetch(argThat((String sql) -> sql.contains("r.read_kind = 'effective_running'")
                && sql.contains("not exists (select 1 from devices p") && sql.contains("p.role = 'management_server'")
                && sql.contains("l.event = 'removed'")));
        assertThrows(IllegalStateException.class, () -> new LocalFirewallPolicyService(tx, new ArtefactStoreAccess(null)).snapshots());
        when(store.retrieve(any(), any(), anyBoolean())).thenThrow(new IOException("synthetic unavailable artifact"));
        assertThrows(IllegalStateException.class, service::snapshots);
        when(rows.isEmpty()).thenReturn(true);
        when(rows.iterator()).thenAnswer(call -> Collections.<org.jooq.Record>emptyIterator());
        assertTrue(new LocalFirewallPolicyService(tx, new ArtefactStoreAccess(null)).snapshots().isEmpty());
    }
}
