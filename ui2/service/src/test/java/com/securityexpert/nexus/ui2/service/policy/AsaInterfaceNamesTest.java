package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;

/** The ASA interface-name table Trace renames inventory interfaces with: exact names, and nothing when ambiguous. */
class AsaInterfaceNamesTest {

    static PolicySnapshot asa(String deviceId, String interfaces) {
        return AsaPolicyMapper.map(deviceId, "Cisco ASA " + deviceId, "2026-11-14T02:00:00Z", "artefact",
                AsaConfigPolicyParser.parse("ASA Version 9.18(4)22\n" + interfaces));
    }

    @Test
    void theTableMapsEachConfiguredInterfaceToItsNameifAndSkipsInterfacesWithoutOne() {
        var s = asa("dev-asa-1", """
                interface GigabitEthernet0/0
                 nameif outside
                 security-level 0
                interface Port-channel1.100
                 vlan 100
                 nameif dmz
                 security-level 50
                interface GigabitEthernet0/5
                 shutdown
                """);
        assertEquals(Map.of("GigabitEthernet0/0", "outside", "Port-channel1.100", "dmz"), AsaInterfaceNames.table(s));
        // The interface table still answers the lookup (the added physical value is not an unknown key).
        var lookup = PolicyLookup.evaluate(s, new PolicyLookup.Query("192.0.2.5", "198.51.100.9", "tcp/443", "dmz", "outside", null, null), 250000).body();
        assertEquals(PolicyLookup.ASA_LEVEL_ALLOW, lookup.get("lookupReason"));
    }

    @Test
    void aRepeatedNameifOrADeviceWithTwoSnapshotsGivesNoTable() {
        var repeated = asa("dev-asa-2", """
                interface GigabitEthernet0/0
                 nameif outside
                interface GigabitEthernet0/1
                 nameif outside
                """);
        assertNull(AsaInterfaceNames.table(repeated));
        var one = asa("dev-asa-3", "interface GigabitEthernet0/0\n nameif outside\n security-level 0\n");
        var names = new AsaInterfaceNames(() -> List.of(repeated, one, one, asa("dev-asa-4", "interface Gi0/1\n nameif inside\n")));
        assertEquals(Map.of("dev-asa-4", Map.of("Gi0/1", "inside")), names.load());
    }
}
