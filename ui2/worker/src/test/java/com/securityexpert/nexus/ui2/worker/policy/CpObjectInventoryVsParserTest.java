package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import java.util.List;
import org.junit.jupiter.api.Test;

class CpObjectInventoryVsParserTest {
    private final ObjectMapper json = new ObjectMapper();

    private List<CpObjectInventory.Item> parse(String... objects) throws Exception {
        var nodes = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (String o : objects) nodes.add(json.readTree(o));
        return CpObjectInventoryParser.parse("source-1", "domain-1", nodes);
    }

    @Test
    void keepsOnlyVirtualSystemTypesAndOnlyAddressAndMaskLength() throws Exception {
        var items = parse(
            "{\"uid\":\"u1\",\"name\":\"Synthetic VS\",\"type\":\"CpmiVsClusterNetobj\",\"interfaces\":["
                + "{\"ipv4-address\":\"192.0.2.1\",\"ipv4-mask-length\":26,\"interface-name\":\"eth1\",\"topology\":\"EXTERNAL\"}]}",
            "{\"uid\":\"u2\",\"name\":\"Synthetic GW\",\"type\":\"simple-gateway\",\"interfaces\":["
                + "{\"ipv4-address\":\"192.0.2.9\",\"ipv4-mask-length\":24}]}");
        assertEquals(new CpObjectInventory.VsAddresses(List.of(new CpObjectInventory.VsInterface("192.0.2.1", 26)), true),
            items.get(0).vs());
        assertNull(items.get(1).vs());
        assertFalse(json.writeValueAsString(items.get(0)).contains("eth1"));
    }

    @Test
    void anInterfaceWithoutAValidMaskIsDroppedAndTheObjectNeverBinds() throws Exception {
        var items = parse(
            "{\"uid\":\"u1\",\"type\":\"CpmiVsNetobj\",\"interfaces\":["
                + "{\"ipv4-address\":\"192.0.2.1\",\"ipv4-mask-length\":26},{\"ipv4-address\":\"198.51.100.1\"}]}",
            "{\"uid\":\"u2\",\"type\":\"CpmiVsNetobj\",\"interfaces\":[{\"ipv4-address\":\"198.51.100.1\",\"ipv4-mask-length\":33}]}",
            "{\"uid\":\"u3\",\"type\":\"CpmiVsNetobj\",\"interfaces\":[{\"ipv4-address\":\"203.0.113.300\",\"ipv4-mask-length\":24}]}",
            "{\"uid\":\"u4\",\"type\":\"CpmiVsNetobj\"}");
        assertEquals(List.of(new CpObjectInventory.VsInterface("192.0.2.1", 26)), items.get(0).vs().interfaces());
        for (var item : items) assertFalse(item.vs().complete(), item.uid());
        assertTrue(items.get(1).vs().interfaces().isEmpty());
        assertTrue(items.get(2).vs().interfaces().isEmpty());
    }

    @Test
    void anOlderPayloadWithoutTheFieldStillDeserializes() throws Exception {
        String old = "{\"id\":\"i\",\"uid\":\"u\",\"name\":\"n\",\"type\":\"host\",\"members\":[],"
            + "\"values\":[\"ipv4-address: 192.0.2.5\"],\"schedule\":null,\"policyInstallations\":[]}";
        var item = json.readValue(old, CpObjectInventory.Item.class);
        assertNull(item.vs());
        var roundTrip = json.readValue(json.writeValueAsString(parse(
            "{\"uid\":\"u1\",\"type\":\"CpmiVsNetobj\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\",\"ipv4-mask-length\":26}]}").get(0)),
            CpObjectInventory.Item.class);
        assertTrue(roundTrip.vs().complete());
    }
}
