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
import com.securityexpert.nexus.ui2.service.failover.ReadinessCheckView;

/**
 * PO 2026-10-21: a Palo Alto firewall's model, version, serial, content versions, uptime and family are UNKNOWN with
 * the reason -- a value an earlier read stored is never shown as current, and never as 0. Other devices are untouched.
 */
class PanSystemInfoRetirementProjectionTest {

    private static Map<String, Object> bodyWithStoredFacts() {
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
        DeviceRegistrationController.putPlatformFacts(body, Optional.of(new DevicePlatformFacts("device-fixture",
                Optional.of("SYNTHETIC-SERIAL"), Optional.empty(), Optional.of("synthetic-family"),
                Map.of("app", "synthetic"), Optional.of("1 day"), "pan_show_system_info",
                Optional.of(Instant.parse("2026-10-20T06:00:00Z")))));
        return body;
    }

    @Test
    void aFirewallsSystemInfoFactsAreWithheldWithTheReasonOnce() {
        Map<String, Object> body = bodyWithStoredFacts();
        DeviceRegistrationController.applyPanSystemInfoRetirement(body, "palo_alto", "gateway");
        for (String key : new String[] {"model", "software_version", "serial_number", "platform_family", "content_versions", "uptime_text"}) {
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

    @Test
    void aPanoramaAndOtherVendorsAreUntouched() {
        for (String[] device : new String[][] {{"palo_alto", "management_server"}, {"check_point", "gateway"}}) {
            Map<String, Object> body = bodyWithStoredFacts();
            DeviceRegistrationController.applyPanSystemInfoRetirement(body, device[0], device[1]);
            assertEquals("PA-SYNTHETIC", body.get("model"));
            assertEquals("SYNTHETIC-SERIAL", body.get("serial_number"));
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
    void readinessCheckSixSaysWhyItIsUnknownWhenSessionInfoIsSwitchedOff() throws Exception {
        Map<String, Object> row = ReadinessCheckView.fields("palo_alto", 6, "Member 1", "UNKNOWN",
                new ObjectMapper().readTree("{\"reason\":\"not_collected\",\"detail\":\"show session info retired by PO 2026-10-21\"}"),
                Instant.parse("2026-10-21T06:00:00Z"));
        assertTrue(row.values().stream().anyMatch(v -> String.valueOf(v).contains("show session info retired by PO 2026-10-21")), row.toString());
    }

    @Test
    void readinessCheckSevenSaysWhyItIsUnknown() throws Exception {
        Map<String, Object> row = ReadinessCheckView.fields("palo_alto", 7, "Member 1", "UNKNOWN",
                new ObjectMapper().readTree("{\"reason\":\"not_collected\",\"detail\":\"show system info retired by PO 2026-10-21\"}"),
                Instant.parse("2026-10-21T06:00:00Z"));
        assertTrue(row.values().stream().anyMatch(v -> String.valueOf(v).contains("retired by PO 2026-10-21")), row.toString());
    }
}
