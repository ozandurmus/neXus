package com.securityexpert.nexus.ui2.platform.advisoryfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdvisoryFetchEnvTest {

    @Test
    void overwatchNameWinsAndLegacyStillWorks() {
        Map<String, String> env = new HashMap<>();
        env.put("UI2_ADVISORY_PROXY", "proxy.example.test:3128");
        assertEquals("proxy.example.test:3128", AdvisoryFetchMain.overwatchFirst(env::get, "UI2_ADVISORY_PROXY"));
        env.put("OVERWATCH_ADVISORY_PROXY", "other.example.test:8080");
        assertEquals("other.example.test:8080", AdvisoryFetchMain.overwatchFirst(env::get, "UI2_ADVISORY_PROXY"));
    }

    @Test
    void blankOverwatchFallsBackAndBlankLegacyIsUnset() {
        Map<String, String> env = new HashMap<>();
        env.put("OVERWATCH_ADVISORY_SOURCES", "  ");
        env.put("UI2_ADVISORY_SOURCES", "a");
        assertEquals("a", AdvisoryFetchMain.overwatchFirst(env::get, "UI2_ADVISORY_SOURCES"));
        env.put("UI2_ADVISORY_SOURCES", " ");
        assertNull(AdvisoryFetchMain.overwatchFirst(env::get, "UI2_ADVISORY_SOURCES"));
    }

    @Test
    void settingsReadOverwatchNames() {
        Map<String, String> env = new HashMap<>();
        env.put("OVERWATCH_ADVISORY_PROXY", "proxy.example.test:3128");
        env.put("OVERWATCH_ADVISORY_FETCH_TOKEN", "x".repeat(30));
        AdvisoryFetchMain.Settings settings = AdvisoryFetchMain.Settings.from(env::get);
        assertNull(settings.problem());
        assertEquals("proxy.example.test", settings.proxyHost());
    }
}
