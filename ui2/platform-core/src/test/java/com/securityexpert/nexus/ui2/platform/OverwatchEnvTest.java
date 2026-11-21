package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OverwatchEnvTest {

    @Test
    void newNameWinsOverLegacyName() {
        Map<String, String> env = Map.of("OVERWATCH_FOO", "new", "NEXUS_FOO", "old");
        assertEquals("new", OverwatchEnv.get(env::get, "NEXUS_FOO"));
    }

    @Test
    void legacyNameIsTheFallback() {
        assertEquals("old", OverwatchEnv.get(Map.of("NEXUS_FOO", "old")::get, "NEXUS_FOO"));
        assertEquals("old", OverwatchEnv.get(Map.of("UI2_FOO", "old")::get, "UI2_FOO"));
    }

    @Test
    void ui2AndNexusNamesAreNotCrossRead() {
        assertNull(OverwatchEnv.get(Map.of("UI2_FOO", "x")::get, "NEXUS_FOO"));
    }

    @Test
    void blankIsTreatedAsUnset() {
        Map<String, String> env = Map.of("OVERWATCH_FOO", "  ", "NEXUS_FOO", "old");
        assertEquals("old", OverwatchEnv.get(env::get, "NEXUS_FOO"));
        assertNull(OverwatchEnv.get(Map.of("NEXUS_FOO", " ")::get, "NEXUS_FOO"));
        assertEquals("dflt", OverwatchEnv.getOrDefault(Map.<String, String>of()::get, "NEXUS_FOO", "dflt"));
        assertEquals("dflt", OverwatchEnv.getOrDefault(Map.of("NEXUS_FOO", "")::get, "NEXUS_FOO", "dflt"));
    }

    @Test
    void otherNamesAreReadVerbatim() {
        assertEquals("v", OverwatchEnv.get(Map.of("HOSTNAME", "v")::get, "HOSTNAME"));
        assertNull(OverwatchEnv.get(Map.of("OVERWATCH_HOSTNAME", "v")::get, "HOSTNAME"));
    }

    @Test
    void lookupWrapsASource() {
        assertEquals("new", OverwatchEnv.lookup(Map.of("OVERWATCH_BAR", "new")::get).apply("UI2_BAR"));
    }
}
