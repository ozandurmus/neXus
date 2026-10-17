package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class PolicyCompletenessSettingsTest {
    private static PolicyCompletenessSettings from(Map<String, String> properties, Map<String, String> environment) {
        return PolicyCompletenessSettings.from(properties::get, environment::get);
    }

    @Test void everyReaderIsOffByDefault() {
        var settings = from(Map.of(), Map.of());
        assertEquals(PolicyCompletenessSettings.OFF, settings);
        assertFalse(settings.anyEnabled());
        assertFalse(settings.panConfigLogTargets("manager-1"));
        assertFalse(settings.cpShowChangesTargets("domain-1"));
        assertFalse(settings.panNatHitsTargets("device-1"));
    }

    @Test void aSettingNamesExactlyOneTargetAndNothingElse() {
        var settings = from(Map.of(), Map.of(PolicyCompletenessSettings.PAN_CONFIG_LOG_ENV, "manager-1",
            PolicyCompletenessSettings.CP_SHOW_CHANGES_ENV, "domain-1", PolicyCompletenessSettings.PAN_NAT_HITS_ENV, "device-1"));
        assertTrue(settings.panConfigLogTargets("manager-1"));
        assertFalse(settings.panConfigLogTargets("manager-2"));
        assertFalse(settings.panConfigLogTargets("manager-"));
        assertTrue(settings.cpShowChangesTargets("domain-1"));
        assertFalse(settings.cpShowChangesTargets("domain-10"));
        assertTrue(settings.panNatHitsTargets("device-1"));
        assertFalse(settings.panNatHitsTargets("device-2"));
        // Each setting only switches on its own reader.
        var onlyLog = from(Map.of(), Map.of(PolicyCompletenessSettings.PAN_CONFIG_LOG_ENV, "manager-1"));
        assertTrue(onlyLog.panConfigLogTargets("manager-1"));
        assertTrue(onlyLog.cpShowChangesTrialDomain().isEmpty());
        assertTrue(onlyLog.panNatHitsTrialFirewall().isEmpty());
    }

    @Test void aSystemPropertyWinsOverTheEnvironment() {
        var settings = from(Map.of(PolicyCompletenessSettings.PAN_NAT_HITS_PROPERTY, "device-p"),
            Map.of(PolicyCompletenessSettings.PAN_NAT_HITS_ENV, "device-e"));
        assertEquals(Optional.of("device-p"), settings.panNatHitsTrialFirewall());
    }

    @Test void blankMalformedOrListValuesAreOffNotWidened() {
        for (String bad : new String[] {"", "   ", "a,b", "a b", "x".repeat(81), "dev/ice", "*", "device-1;device-2"}) {
            assertFalse(from(Map.of(), Map.of(PolicyCompletenessSettings.PAN_NAT_HITS_ENV, bad)).anyEnabled(), "[" + bad + "]");
        }
        assertEquals(Optional.of("device-1"), from(Map.of(), Map.of(PolicyCompletenessSettings.PAN_NAT_HITS_ENV, " device-1 ")).panNatHitsTrialFirewall());
    }

    @Test void theTextFormNeverShowsTheTarget() {
        assertFalse(from(Map.of(), Map.of(PolicyCompletenessSettings.PAN_NAT_HITS_ENV, "device-1")).toString().contains("device-1"));
    }
}
