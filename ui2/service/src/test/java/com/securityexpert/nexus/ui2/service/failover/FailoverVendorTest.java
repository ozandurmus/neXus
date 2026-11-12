package com.securityexpert.nexus.ui2.service.failover;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FailoverVendorTest {
    @Test void lookupByDatabaseValue() {
        for (FailoverVendor v : FailoverVendor.values()) assertEquals(Optional.of(v), FailoverVendor.of(v.dbValue()));
        assertEquals(Optional.empty(), FailoverVendor.of("unknown"));
        assertEquals(Optional.empty(), FailoverVendor.of(null));
    }
    @Test void checkPointAndPaloAltoKeepTodaysFacts() {
        var cp = FailoverVendor.CHECK_POINT;
        assertTrue(cp.requiresSshTrust()); assertEquals(Optional.empty(), cp.requiredTransportKind());
        assertEquals("STANDBY", cp.standbyRoleWord()); assertEquals("cp_cphaprob_stat", cp.haFactSource());
        assertTrue(cp.readinessAvailable() && cp.executionAvailable() && cp.resumeSupported());
        var pan = FailoverVendor.PALO_ALTO;
        assertFalse(pan.requiresSshTrust()); assertEquals(Optional.of("pan_xml_api"), pan.requiredTransportKind());
        assertEquals("PASSIVE", pan.standbyRoleWord()); assertEquals("pan_high_availability_state", pan.haFactSource());
        assertTrue(pan.readinessAvailable() && pan.executionAvailable()); assertFalse(pan.resumeSupported());
    }
    @Test void asaAndFortigateAreListedOnly() {
        for (var v : new FailoverVendor[] {FailoverVendor.CISCO_ASA, FailoverVendor.FORTINET}) {
            assertFalse(v.readinessAvailable()); assertFalse(v.executionAvailable()); assertFalse(v.resumeSupported());
            assertTrue(v.requiresSshTrust()); assertEquals(Optional.of("ssh_exec"), v.requiredTransportKind());
        }
        assertEquals("Cisco ASA", FailoverVendor.CISCO_ASA.label());
        assertEquals("FortiGate", FailoverVendor.FORTINET.label());
        assertEquals("asa_show_failover", FailoverVendor.CISCO_ASA.haFactSource());
        assertEquals("fgt_get_system_ha_status", FailoverVendor.FORTINET.haFactSource());
    }
}
