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

/** Stored Cisco ASA running configurations become local policies (PO_DECISION_RECORD_2026_11_14 item 1). */
class LocalFirewallAsaPolicyTest {
    static final String ASA = """
            ASA Version 9.18(4)22
            interface GigabitEthernet0/0
             nameif outside
             security-level 0
            interface GigabitEthernet0/1
             nameif inside
             security-level 100
            access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 443
            access-group ACL-OUT in interface outside
            """;

    private static org.jooq.Record row(String device, String artefact) {
        var row = mock(org.jooq.Record.class);
        when(row.get("device_id", String.class)).thenReturn(device);
        when(row.get("collected_at", Timestamp.class)).thenReturn(Timestamp.from(java.time.Instant.parse("2026-11-14T02:00:00Z")));
        when(row.get("artefact_ref", String.class)).thenReturn(artefact);
        when(row.get("wrapped_data_key", byte[].class)).thenReturn(new byte[32]);
        when(row.get("compression", String.class)).thenReturn("none");
        return row;
    }

    @Test void mapsStoredAsaConfigurationsCachesThemAndLeavesUnreadableOnesOut() throws Exception {
        var db = mock(DSLContext.class);
        @SuppressWarnings("unchecked") Result<org.jooq.Record> asaRows = mock(Result.class);
        @SuppressWarnings("unchecked") Result<org.jooq.Record> none = mock(Result.class);
        List<org.jooq.Record> asa = List.of(row("asa-1", "synthetic-asa-1"), row("asa-2", "synthetic-asa-2"));
        when(asaRows.iterator()).thenAnswer(call -> asa.iterator());
        when(asaRows.isEmpty()).thenReturn(false);
        when(none.iterator()).thenAnswer(call -> Collections.<org.jooq.Record>emptyIterator());
        when(none.isEmpty()).thenReturn(true);
        when(db.fetch(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).contains("'cisco_asa'") ? asaRows : none);
        TransactionBoundary tx = new TransactionBoundary() {
            @Override public <T> T inTransaction(java.util.function.Function<DSLContext, T> work) { return work.apply(db); }
        };
        var store = mock(ArtefactStore.class);
        when(store.retrieve(argThat(ref -> ref != null && ref.toString().contains("synthetic-asa-1")), any(), eq(false)))
            .thenAnswer(call -> new ByteArrayInputStream(ASA.getBytes(StandardCharsets.UTF_8)));
        // The second artefact is no ASA configuration (no "ASA Version" line): left out, never a broken catalog.
        when(store.retrieve(argThat(ref -> ref != null && ref.toString().contains("synthetic-asa-2")), any(), eq(false)))
            .thenAnswer(call -> new ByteArrayInputStream("<config/>".getBytes(StandardCharsets.UTF_8)));
        var service = new LocalFirewallPolicyService(tx, new ArtefactStoreAccess(store));

        var snapshots = service.snapshots();
        assertEquals(1, snapshots.size());
        var snapshot = snapshots.get(0);
        assertEquals("ASA", snapshot.metadata().vendor());
        assertEquals("asa-1", snapshot.metadata().sourceId());
        assertEquals("asa-1", snapshot.metadata().targets().get(0).deviceId());
        assertNotEquals("synthetic-asa-1", snapshot.metadata().artefactRef());
        assertEquals("outside in", snapshot.sections().get(0).name());
        assertTrue(snapshot.failures().isEmpty());
        verify(db).fetch(argThat((String sql) -> sql.contains("r.read_kind = 'show_configuration'") && sql.contains("d.vendor_hint = 'cisco_asa'")
                && sql.contains("l.event = 'removed'") && !sql.contains("management_server")));

        long parses = service.asaParseCount();
        assertSame(snapshot, service.snapshots().get(0), "an unchanged artefact is answered from the cache");
        assertEquals(parses + 1, service.asaParseCount(), "only the unreadable artefact is tried again");
        assertEquals(0, service.parseCount(), "ASA parses never count as Palo Alto parses");
    }
}
