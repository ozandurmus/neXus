package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UploadDestinationTest {

    @Test
    void theApprovedVendorTargetsAreFixed() {
        assertEquals("cxd.cisco.com", UploadDestination.CISCO_CXD_HTTPS.fixedHost());
        assertEquals(443, UploadDestination.CISCO_CXD_HTTPS.defaultPort());
        assertEquals(22, UploadDestination.CISCO_CXD_SFTP.defaultPort());
        assertEquals(22, UploadDestination.CISCO_CXD_SCP.defaultPort());
        assertEquals("support.fortinet.com", UploadDestination.FORTINET_SFTP.fixedHost());
        assertEquals(2222, UploadDestination.FORTINET_SFTP.defaultPort());
        assertEquals("tacupload.paloaltonetworks.com", UploadDestination.PALOALTO_SCP.fixedHost());
        assertEquals(UploadDestination.Method.SCP, UploadDestination.PALOALTO_SCP.method());
        assertTrue(UploadDestination.GENERIC_SFTP.generic());
        assertFalse(UploadDestination.fromId("checkpoint").isPresent(), "Check Point is a manual upload");
    }

    @Test
    void hostsAreNamesOrIpLiteralsOnly() {
        for (String ok : new String[] {"sftp.example.com", "a", "10.1.2.3", "192.0.2.10", "::1", "2001:db8::5", "host-1.example.org"}) {
            assertTrue(UploadDestination.validHost(ok), ok);
        }
        for (String bad : new String[] {"", "-host", "host-", "a b", "a_b.example.com", "a..b", "http://x", "x/y", "x@y", "[::1]", "host:22", "g:g:g"}) {
            assertFalse(UploadDestination.validHost(bad), bad);
        }
        assertTrue(UploadDestination.validPort(1) && UploadDestination.validPort(65535));
        assertFalse(UploadDestination.validPort(0) || UploadDestination.validPort(65536));
    }
}
