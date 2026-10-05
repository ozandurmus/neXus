package com.securityexpert.nexus.ui2.service.search;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class IpQueryTest {
    @Test void detectsLiteralAddressesAndCidrsWithoutDns() {
        for (String query : new String[]{"192.0.2.8", "2001:db8::8", "::", "::ffff:192.0.2.8"}) {
            assertNotNull(IpQuery.parse(query)); assertFalse(IpQuery.parse(query).cidr());
        }
        for (String query : new String[]{"192.0.2.8/24", "2001:db8::/64", "::/0", "::ffff:192.0.2.8/128"})
            assertTrue(IpQuery.parse(query).cidr());
        for (String query : new String[]{"example.invalid", "192.0.2", "192.0.2.256", "192.00.2.8",
                "192.0.2.8/33", "2001:db8::/129", "2001:db8::8%eth0", "192.0.2.8/", "::/64/64"})
            assertNull(IpQuery.parse(query));
    }
}
