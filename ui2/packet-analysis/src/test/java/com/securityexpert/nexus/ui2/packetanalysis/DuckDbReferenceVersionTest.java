package com.securityexpert.nexus.ui2.packetanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 skeleton check: the DuckDB engine on the Java classpath is the version the frozen Python
 * reference recorded in the parity corpus manifest, so level 1/2 SQL hashes are comparable.
 */
class DuckDbReferenceVersionTest {

    @Test
    void jdbcDriverMatchesReferenceDuckDbVersion() throws Exception {
        String manifest;
        try (InputStream in = getClass().getResourceAsStream("/parity/manifest.json")) {
            assertNotNull(in, "parity manifest resource");
            manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("\"duckdb\": \"([0-9.]+)\"").matcher(manifest);
        assertTrue(m.find(), "manifest records the reference duckdb version");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT version()")) {
            assertTrue(rs.next());
            assertEquals("v" + m.group(1), rs.getString(1));
        }
    }
}
