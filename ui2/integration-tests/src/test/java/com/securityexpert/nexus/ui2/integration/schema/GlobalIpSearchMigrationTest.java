package com.securityexpert.nexus.ui2.integration.schema;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles the shipped PL/pgSQL on real PostgreSQL before exercising both search functions. */
class GlobalIpSearchMigrationTest {
    @Test
    void migrationAppliesAndSearchFunctionsHandleAddressesRangesAndMasks() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("global_ip_search")) {
            var result = fixture.runFlyway();
            assertTrue(result.migrations.stream().anyMatch(m -> "127".equals(m.version)),
                    "the real database must execute V127, not a test copy of its functions");
            try (var db = fixture.appConnection()) {
                try (var statement = db.prepareStatement("SELECT search_inet(?) = ?::inet")) {
                    for (String value : new String[]{"192.0.2.10", "192.0.2.0/24", "2001:db8::10", "2001:db8::/64"}) {
                        statement.setString(1, value);
                        statement.setString(2, value);
                        try (var rows = statement.executeQuery()) {
                            assertTrue(rows.next());
                            assertTrue(rows.getBoolean(1), value);
                        }
                    }
                }
                try (var statement = db.createStatement();
                     var rows = statement.executeQuery("SELECT search_inet('invalid') IS NULL, search_inet(NULL) IS NULL")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getBoolean(1));
                    assertTrue(rows.getBoolean(2));
                }

                assertPolicy(db, "host", "192.0.2.10", "192.0.2.10", true);
                assertPolicy(db, "host", "192.0.2.10", "198.51.100.10", false);
                assertPolicy(db, "host", "ipv6-address: 2001:db8::10", "2001:db8::10", true);
                assertPolicy(db, "host", "ipv6-address: 2001:db8::10", "192.0.2.10", false);
                assertPolicy(db, "address", "ip-netmask: 192.0.2.0/24", "192.0.2.128/25", true);
                assertPolicy(db, "address", "ip-netmask: 2001:db8::/64", "2001:db8::10", true);
                assertPolicy(db, "address-range", "ip-range: 192.0.2.10-192.0.2.20", "192.0.2.15", true);
                assertPolicy(db, "address-range", "ip-range: 192.0.2.10-192.0.2.20", "192.0.2.21", false);
                assertPolicy(db, "address-range", "ip-range: 192.0.2.20-192.0.2.10", "192.0.2.15", false);
                assertPolicy(db, "address-range", "ipv6-address-first: 2001:db8::10|ipv6-address-last: 2001:db8::20",
                        "2001:db8::15", true);
                assertPolicy(db, "address-range", "ip-address-first: 192.0.2.10|ip-address-last: 192.0.2.20",
                        "192.0.2.0/24", true);
                assertPolicy(db, "network", "subnet4: 192.0.2.0|mask-length4: 24", "192.0.2.10", true);
                assertPolicy(db, "network", "subnet4: 192.0.2.10|mask-length4: 32", "192.0.2.11", false);
                assertPolicy(db, "network", "subnet6: 2001:db8::|mask-length6: 64", "2001:db8::10", true);
                assertPolicy(db, "network", "subnet6: 2001:db8::10|mask-length6: 128", "2001:db8::10", true);
                assertPolicy(db, "network", "subnet4: 192.0.2.0|subnet-mask: 255.255.255.0", "192.0.2.10", true);
                assertPolicy(db, "network", "subnet4: 192.0.2.0|subnet-mask: 255.0.255.0", "192.0.2.10", false);
                assertPolicy(db, "network", "subnet4: 192.0.2.0|mask-length4: 33", "192.0.2.10", false);
                assertPolicy(db, "network", "subnet6: 2001:db8::|mask-length6: 129", "2001:db8::10", false);
                assertPolicy(db, "host", "invalid", "192.0.2.10", false);
            }
        }
    }

    private void assertPolicy(Connection db, String type, String values, String query, boolean expected) throws Exception {
        // Pipe separates synthetic values; all JSON construction happens in PostgreSQL.
        try (var statement = db.prepareStatement("SELECT search_policy_address("
                + "jsonb_build_object('type', ?::text, 'values', to_jsonb(string_to_array(?::text, '|'))), ?::inet)")) {
            statement.setString(1, type);
            statement.setString(2, values);
            statement.setString(3, query);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expected, rows.getBoolean(1), type + ": " + values + " against " + query);
                assertFalse(rows.wasNull(), "search_policy_address must return a boolean");
            }
        }
    }
}
