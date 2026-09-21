package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * AC-2: the commands and routes the confirm can ever send equal
 * {@code DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md} entries 1-4 exactly
 * -- this test transcribes that document's own literal forms and asserts
 * the production enum equals it, so the two can never drift silently.
 */
class DeviceFirstContactCommandSetTest {

    private static final Set<String> GATE_TABLE_LITERAL_FORMS = Set.of(
            // Entry 1 -- Check Point identity read: primary, then three fallback forms (2026-09-21
            // correction, matching the pre-Java product's own real-fleet-proven probe).
            "show version all",
            "show version",
            "clish -c \"show version all\"",
            "clish -c \"show version\"",
            // Entry 2 -- Check Point HA/cluster role and peer naming.
            "cphaprob stat",
            // Entry 3 -- Palo Alto identity read.
            "<show><system><info/></system></show>",
            // Entry 4 -- Palo Alto HA state and peer.
            "<show><high-availability><state/></high-availability></show>");

    @Test
    void closedSetHasExactlyFourEntries() {
        assertEquals(4, DeviceFirstContactCommandSet.values().length,
                "the gate document defines exactly four entries -- a fifth is a document amendment, never a code change");
    }

    @Test
    void closedSetLiteralFormsEqualTheGateTableExactly() {
        assertEquals(GATE_TABLE_LITERAL_FORMS, DeviceFirstContactCommandSet.allLiteralForms());
    }

    @Test
    void everyVendorStepPairResolvesToExactlyOneEntry() {
        assertEquals(DeviceFirstContactCommandSet.CP_IDENTITY_READ,
                DeviceFirstContactCommandSet.forStep(Vendor.CHECK_POINT,
                        DeviceFirstContactCommandSet.ContactStepKind.IDENTITY_READ));
        assertEquals(DeviceFirstContactCommandSet.CP_HA_PEER_READ,
                DeviceFirstContactCommandSet.forStep(Vendor.CHECK_POINT,
                        DeviceFirstContactCommandSet.ContactStepKind.HA_PEER_READ));
        assertEquals(DeviceFirstContactCommandSet.PAN_IDENTITY_READ,
                DeviceFirstContactCommandSet.forStep(Vendor.PALO_ALTO,
                        DeviceFirstContactCommandSet.ContactStepKind.IDENTITY_READ));
        assertEquals(DeviceFirstContactCommandSet.PAN_HA_PEER_READ,
                DeviceFirstContactCommandSet.forStep(Vendor.PALO_ALTO,
                        DeviceFirstContactCommandSet.ContactStepKind.HA_PEER_READ));
    }

    @Test
    void noFifthVendorStepPairExists() {
        assertThrows(IllegalArgumentException.class,
                () -> DeviceFirstContactCommandSet.forStep(Vendor.CHECK_POINT, null));
    }
}
