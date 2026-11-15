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

/** Stored FortiGate configurations become local policies, one per VDOM (PO_DECISION_RECORD_2026_11_14 item 1). */
class LocalFirewallFortiGatePolicyTest {
    static final String FGT = """
            config system interface
                edit "port1"
                    set vdom "root"
                    set ip 192.0.2.1 255.255.255.0
                next
            end
            config firewall address
                edit "all"
                next
            end
            config firewall service custom
                edit "HTTPS"
                    set tcp-portrange 443
                next
            end
            config firewall policy
                edit 1
                    set srcintf "port1"
                    set dstintf "any"
                    set srcaddr "all"
                    set dstaddr "all"
                    set action accept
                    set schedule "always"
                    set service "HTTPS"
                next
            end
            """;

    private static org.jooq.Record row(String device, String artefact) {
        var row = mock(org.jooq.Record.class);
        when(row.get("device_id", String.class)).thenReturn(device);
        when(row.get("observed_software_version", String.class)).thenReturn("v7.4.8 build2795");
        when(row.get("collected_at", Timestamp.class)).thenReturn(Timestamp.from(java.time.Instant.parse("2026-11-14T02:00:00Z")));
        when(row.get("artefact_ref", String.class)).thenReturn(artefact);
        when(row.get("wrapped_data_key", byte[].class)).thenReturn(new byte[32]);
        when(row.get("compression", String.class)).thenReturn("none");
        return row;
    }

    @Test void mapsStoredFortiGateConfigurationsCachesThemAndLeavesUnreadableOnesOut() throws Exception {
        var db = mock(DSLContext.class);
        @SuppressWarnings("unchecked") Result<org.jooq.Record> fgtRows = mock(Result.class);
        @SuppressWarnings("unchecked") Result<org.jooq.Record> none = mock(Result.class);
        List<org.jooq.Record> fgt = List.of(row("fgt-1", "synthetic-fgt-1"), row("fgt-2", "synthetic-fgt-2"));
        when(fgtRows.iterator()).thenAnswer(call -> fgt.iterator());
        when(fgtRows.isEmpty()).thenReturn(false);
        when(none.iterator()).thenAnswer(call -> Collections.<org.jooq.Record>emptyIterator());
        when(none.isEmpty()).thenReturn(true);
        when(db.fetch(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).contains("'fortinet'") ? fgtRows : none);
        TransactionBoundary tx = new TransactionBoundary() {
            @Override public <T> T inTransaction(java.util.function.Function<DSLContext, T> work) { return work.apply(db); }
        };
        var store = mock(ArtefactStore.class);
        when(store.retrieve(argThat(ref -> ref != null && ref.toString().contains("synthetic-fgt-1")), any(), eq(false)))
            .thenAnswer(call -> new ByteArrayInputStream(FGT.getBytes(StandardCharsets.UTF_8)));
        // The second artefact names no VDOM: left out, never a broken catalog.
        when(store.retrieve(argThat(ref -> ref != null && ref.toString().contains("synthetic-fgt-2")), any(), eq(false)))
            .thenAnswer(call -> new ByteArrayInputStream("<config/>".getBytes(StandardCharsets.UTF_8)));
        var service = new LocalFirewallPolicyService(tx, new ArtefactStoreAccess(store));

        var snapshots = service.snapshots();
        assertEquals(1, snapshots.size());
        var snapshot = snapshots.get(0);
        assertEquals("FGT", snapshot.metadata().vendor());
        assertEquals("fgt-1", snapshot.metadata().targets().get(0).deviceId());
        assertNotEquals("synthetic-fgt-1", snapshot.metadata().artefactRef());
        assertTrue(snapshot.failures().isEmpty(), "the version hint gives the cited 7.4 defaults: " + snapshot.failures());
        verify(db).fetch(argThat((String sql) -> sql.contains("r.read_kind = 'show_configuration'") && sql.contains("d.vendor_hint = 'fortinet'")
                && sql.contains("d.role in ('gateway', 'firewall')") && sql.contains("l.event = 'removed'")));

        long parses = service.fgtParseCount();
        assertSame(snapshot, service.snapshots().get(0), "an unchanged artefact is answered from the cache");
        assertEquals(parses + 1, service.fgtParseCount(), "only the unreadable artefact is tried again");
        assertEquals(0, service.parseCount(), "FortiGate parses never count as Palo Alto parses");
        assertEquals(0, service.asaParseCount());
    }
}
