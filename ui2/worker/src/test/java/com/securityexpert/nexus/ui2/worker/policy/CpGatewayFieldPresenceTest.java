package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CpGatewayFieldPresenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void countsKeyNamesPerTypeAndNeverAValue() throws Exception {
        List<JsonNode> nodes = List.of(
            JSON.readTree("{\"uid\":\"u-1\",\"name\":\"FW-TANGO-04\",\"type\":\"CpmiVsClusterNetobj\",\"vsid\":3,"
                + "\"interfaces\":[{\"name\":\"eth1\",\"ipv4-address\":\"192.0.2.1\",\"ipv4-mask-length\":24},{\"name\":\"eth2\"}]}"),
            JSON.readTree("{\"uid\":\"u-2\",\"name\":\"FW-JULIET-06\",\"type\":\"CpmiVsClusterNetobj\"}"),
            JSON.readTree("{\"uid\":\"u-3\",\"type\":\"simple-cluster\",\"cluster-members\":[{\"name\":\"m1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.9\"}]}]}"));
        var lines = CpGatewayFieldPresence.summarize(nodes);
        assertEquals(2, lines.size());
        String vs = lines.get(0);
        assertTrue(vs.startsWith("type=CpmiVsClusterNetobj objects=2 keys="), vs);
        assertTrue(vs.contains("interfaces:1") && vs.contains("interfaces[].ipv4-mask-length:1") && vs.contains("interfaces[].name:2")
            && vs.contains("vsid:1") && vs.contains("uid:2"), vs);
        assertTrue(lines.get(1).contains("cluster-members[].interfaces[].ipv4-address:1"), lines.get(1));
        String all = String.join("\n", lines);
        assertFalse(all.contains("TANGO") || all.contains("JULIET") || all.contains("192.0.2") || all.contains("eth1") || all.contains("u-1"), all);
    }

    @Test void hostileKeysAndTypesAreReducedToTokens() throws Exception {
        var lines = CpGatewayFieldPresence.summarize(List.of(
            JSON.readTree("{\"type\":\"FW TANGO 04\",\"192.0.2.7\":{\"x\":1},\"Upper Key\":2}")));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("type=OTHER objects=1"), lines.get(0));
        assertFalse(lines.get(0).contains("192.0.2") || lines.get(0).contains("TANGO") || lines.get(0).contains("Upper"), lines.get(0));
    }
}
