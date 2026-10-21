package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.PanSystemInfoRetirement;
import com.securityexpert.nexus.ui2.persistence.device.PanTypeVersion;
import com.securityexpert.nexus.ui2.service.failover.ReadinessCheckView;

/**
 * PO 2026-10-21: a Palo Alto firewall's model, version, serial, content versions, uptime and family are UNKNOWN with
 * the reason -- a value an earlier read stored is never shown as current, and never as 0. Other devices are untouched.
 */
class PanSystemInfoRetirementProjectionTest {

    private static Map<String, Object> bodyWithSource(String source) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hostname", "FW-TANGO-04");
        body.put("model", "PA-SYNTHETIC");
        body.put("software_version", "0.0-synthetic");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("hostname", "FW-TANGO-04");
        facts.put("model", "PA-SYNTHETIC");
        facts.put("software_version", "0.0-synthetic");
        facts.put("ha_role", "active");
        body.put("facts", facts);
        // Serial from type=version; family, content versions and uptime (and a hotfix level) only ever came from system info.
        DeviceRegistrationController.putPlatformFacts(body, Optional.of(new DevicePlatformFacts("device-fixture",
                Optional.of("SYNTHETIC-SERIAL"), Optional.of("synthetic-hotfix"), Optional.of("synthetic-family"),
                Map.of("app", "synthetic"), Optional.of("1 day"), source,
                Optional.of(Instant.parse("2026-10-20T06:00:00Z")))));
        return body;
    }

    /** The values the earlier show system info read stored are withheld until an inventory run replaces them. */
    @Test
    void aFirewallsStaleSystemInfoFactsAreWithheldWithTheReasonOnce() {
        Map<String, Object> body = bodyWithSource("pan_show_system_info");
        DeviceRegistrationController.applyPanSystemInfoRetirement(body, "palo_alto", "gateway");
        for (String key : new String[] {"model", "software_version", "serial_number", "hotfix_level", "platform_family", "content_versions", "uptime_text"}) {
            assertNull(body.get(key), key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) body.get("facts");
        assertNull(facts.get("model"));
        assertNull(facts.get("software_version"));
        assertEquals("active", facts.get("ha_role"), "HA state is still collected");
        assertEquals("FW-TANGO-04", body.get("hostname"), "the recorded name stays");
        assertEquals(PanSystemInfoRetirement.REASON, body.get("platform_facts_not_collected_reason"));
    }

    /**
     * PO 2026-10-21 (type=version): once the last run recorded the type=version read, serial, model and PAN-OS version show
     * again; family, content versions, uptime and hotfix stay UNKNOWN (type=version does not return them).
     */
    @Test
    void aFirewallWhoseLastRunReadTypeVersionShowsSerialModelAndVersionAndNothingElse() {
        Map<String, Object> body = bodyWithSource(PanTypeVersion.SOURCE_READ);
        DeviceRegistrationController.applyPanSystemInfoRetirement(body, "palo_alto", "gateway");
        assertEquals("PA-SYNTHETIC", body.get("model"));
        assertEquals("0.0-synthetic", body.get("software_version"));
        assertEquals("SYNTHETIC-SERIAL", body.get("serial_number"));
        assertEquals(PanTypeVersion.SOURCE_READ, body.get("platform_facts_source"));
        for (String key : new String[] {"hotfix_level", "platform_family", "content_versions", "uptime_text"}) {
            assertNull(body.get(key), key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) body.get("facts");
        assertEquals("PA-SYNTHETIC", facts.get("model"));
        assertEquals("FW-TANGO-04", body.get("hostname"), "type=version carries no hostname: the recorded name stays");
        assertEquals(PanSystemInfoRetirement.REASON, body.get("platform_facts_not_collected_reason"),
                "the reason for the facts type=version does not carry");
    }

    @Test
    void aFirewallWithNoPlatformFactsRowKeepsWhatTheConfirmObserved() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "PA-SYNTHETIC");
        body.put("software_version", "0.0-synthetic");
        DeviceRegistrationController.putPlatformFacts(body, Optional.empty());
        DeviceRegistrationController.applyPanSystemInfoRetirement(body, "palo_alto", "gateway");
        assertEquals("PA-SYNTHETIC", body.get("model"), "a freshly enrolled firewall's confirm read model and version via type=version");
        assertEquals("0.0-synthetic", body.get("software_version"));
        assertNull(body.get("serial_number"));
    }

    @Test
    void aPanoramaAndOtherVendorsAreUntouched() {
        for (String[] device : new String[][] {{"palo_alto", "management_server"}, {"check_point", "gateway"}}) {
            Map<String, Object> body = bodyWithSource("pan_show_system_info");
            DeviceRegistrationController.applyPanSystemInfoRetirement(body, device[0], device[1]);
            assertEquals("PA-SYNTHETIC", body.get("model"));
            assertEquals("SYNTHETIC-SERIAL", body.get("serial_number"));
            assertEquals("synthetic-family", body.get("platform_family"));
            assertNull(body.get("platform_facts_not_collected_reason"));
        }
    }

    @Test
    void anInventoryRunWithAReadSwitchedOffSaysSoWithTheSameReason() {
        var run = new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun("run-1", "device-fixture", "job-1",
                Instant.parse("2026-10-21T06:00:00Z"), 1, java.util.List.of(), java.util.List.of(), Optional.empty(),
                java.util.List.of(), null, java.util.List.of("pan_show_interface_all"));
        Map<String, Object> body = new LinkedHashMap<>();
        InventoryController.putNotCollected(body, Optional.of(run));
        assertEquals(java.util.List.of("pan_show_interface_all"), body.get("not_collected"));
        assertEquals(PanSystemInfoRetirement.REASON, body.get("not_collected_reason"));
        Map<String, Object> full = new LinkedHashMap<>();
        InventoryController.putNotCollected(full, Optional.empty());
        assertEquals(java.util.List.of(), full.get("not_collected"));
        assertNull(full.get("not_collected_reason"));
    }

    @Test
    void anInventoryRunSkippedByTheBelowTwelveSwitchNamesTheApplicableReason() {
        for (String[] c : new String[][] {{"pan_os_12_or_later", "Not collected: PAN-OS 12 or later (PO 2026-10-21)"},
                {"pan_os_version_unknown", "Not collected: PAN-OS version unknown"}}) {
            var run = new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun("run-1", "device-fixture", "job-1",
                    Instant.parse("2026-10-21T06:00:00Z"), 1, java.util.List.of(), java.util.List.of(), Optional.empty(),
                    java.util.List.of(), null, java.util.List.of("pan_show_interface_all", c[0]));
            Map<String, Object> body = new LinkedHashMap<>();
            InventoryController.putNotCollected(body, Optional.of(run));
            assertEquals(c[1], body.get("not_collected_reason"));
        }
    }

    @Test
    void readinessCheckSixNamesPanOsTwelveOrUnknownVersionWhenTheReadWasSkipped() throws Exception {
        for (String detail : new String[] {"PAN-OS 12 or later (PO 2026-10-21)", "PAN-OS version unknown"}) {
            Map<String, Object> row = ReadinessCheckView.fields("palo_alto", 6, "Member 1", "UNKNOWN",
                    new ObjectMapper().readTree("{\"reason\":\"not_collected\",\"detail\":\"" + detail + "\"}"),
                    Instant.parse("2026-10-21T06:00:00Z"));
            assertTrue(row.values().stream().anyMatch(v -> String.valueOf(v).contains("Not collected: " + detail)), row.toString());
        }
    }

    @Test
    void readinessCheckSixSaysWhyItIsUnknownWhenSessionInfoIsSwitchedOff() throws Exception {
        Map<String, Object> row = ReadinessCheckView.fields("palo_alto", 6, "Member 1", "UNKNOWN",
                new ObjectMapper().readTree("{\"reason\":\"not_collected\",\"detail\":\"show session info retired by PO 2026-10-21\"}"),
                Instant.parse("2026-10-21T06:00:00Z"));
        assertTrue(row.values().stream().anyMatch(v -> String.valueOf(v).contains("show session info retired by PO 2026-10-21")), row.toString());
    }

    @Test
    void readinessCheckSevenNamesTheFieldsFromTheHaStateEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Instant at = Instant.parse("2026-10-21T06:00:00Z");
        String unknown = String.valueOf(ReadinessCheckView.fields("palo_alto", 7, "Member 1", "UNKNOWN", mapper.readTree(
                "{\"source\":\"ha_state_local_info\",\"software\":\"MISSING\",\"app\":\"MATCH\",\"threat\":\"MISSING\"}"), at));
        assertTrue(unknown.contains("missing from the HA state read: PAN-OS, threat content"), unknown);
        String fail = String.valueOf(ReadinessCheckView.fields("palo_alto", 7, "Member 1", "FAIL", mapper.readTree(
                "{\"source\":\"ha_state_local_info\",\"software\":\"MATCH\",\"app\":\"MISMATCH\",\"threat\":\"MATCH\"}"), at));
        assertTrue(fail.contains("differ: app content"), fail);
        String pass = String.valueOf(ReadinessCheckView.fields("palo_alto", 7, "Member 1", "PASS", mapper.readTree(
                "{\"source\":\"ha_state_local_info\",\"software\":\"MATCH\",\"app\":\"MATCH\",\"threat\":\"MATCH\"}"), at));
        assertTrue(pass.contains("match on both members"), pass);
        // Evidence from before 2026-10-21 (or nothing recognised) still says something honest, never a version value.
        String legacy = String.valueOf(ReadinessCheckView.fields("palo_alto", 7, "Member 1", "UNKNOWN", mapper.readTree("{}"), at));
        assertTrue(legacy.contains("field not recognised"), legacy);
        String recorded = String.valueOf(ReadinessCheckView.fields("palo_alto", 7, "Member 1", "UNKNOWN", mapper.readTree(
                "{\"reason\":\"not_collected\",\"detail\":\"show system info retired by PO 2026-10-21\"}"), at));
        assertTrue(recorded.contains("retired by PO 2026-10-21"), recorded);
    }
}
