package com.securityexpert.nexus.ui2.persistence.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EndpointAddressTest {
    @Test void everyRepresentationUsesTheAddressNotAnEndpointRecordId() {
        assertEquals("addr:manager.example.invalid:22", EndpointAddress.key("MANAGER.EXAMPLE.INVALID", 22));
        assertEquals(EndpointAddress.key("manager.example.invalid", 22), EndpointAddress.key("MANAGER.example.invalid:22", 22));
        assertEquals("addr:192.0.2.17:443", EndpointAddress.key("https://192.0.2.17/api/", 443));
        assertEquals("addr:[2001:db8::7]:443", EndpointAddress.key("[2001:DB8::7]:443", 443));
        assertEquals("addr:[2001:db8::7]:22", EndpointAddress.key("2001:DB8::7", 22));
        assertNotEquals(EndpointAddress.key("manager.example.invalid", 22), EndpointAddress.key("192.0.2.17", 22));
        assertNotEquals(EndpointAddress.key("192.0.2.17", 22), EndpointAddress.key("192.0.2.17", 443));
    }
    @Test void missingInvalidOrCredentialBearingAddressesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> EndpointAddress.key("", 22));
        assertThrows(IllegalArgumentException.class, () -> EndpointAddress.key("https://fixture:credential@192.0.2.17", 443));
        assertThrows(IllegalArgumentException.class, () -> EndpointAddress.key("192.0.2.17", 0));
        assertThrows(IllegalArgumentException.class, () -> EndpointAddress.key("192.0.2.17", 65536));
    }
}
