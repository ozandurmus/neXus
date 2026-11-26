package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * PO decision D-1 (2026-11-14): a gateway-class object's management addresses, parsed from the
 * show-gateways-and-servers reply already read and carried into the snapshot as gateway-ipv4 values with a completeness
 * marker. Synthetic, documentation addresses only; the reply shape follows the Cluster Management API sample.
 */
class CpGatewayAddressesTest {
    private final ObjectMapper json = new ObjectMapper();

    private CpObjectInventory.GatewayAddresses parse(String object) throws Exception {
        var item = CpObjectInventoryParser.parse("source-1", "domain-1", List.of(json.readTree(object))).get(0);
        return item.gateway();
    }

    @Test void aGatewayKeepsItsMainAndInterfaceAddressesOnly() throws Exception {
        var gw = parse("{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"ipv4-address\":\"192.0.2.1\",\"interfaces\":["
            + "{\"name\":\"eth1\",\"ipv4-address\":\"192.0.2.1\",\"ipv4-mask-length\":24,\"topology\":\"external\"},"
            + "{\"name\":\"eth2\",\"ipv4-address\":\"198.51.100.1\",\"ipv4-mask-length\":24}]}");
        assertEquals(new CpObjectInventory.GatewayAddresses(List.of("192.0.2.1", "198.51.100.1"), true), gw);
        assertFalse(json.writeValueAsString(gw).contains("eth1"));
    }

    @Test void aClusterNeedsItsMembersAndTheirInterfaces() throws Exception {
        var cluster = parse("{\"uid\":\"u1\",\"type\":\"CpmiGatewayCluster\",\"ipv4-address\":\"192.0.2.1\",\"interfaces\":["
            + "{\"ipv4-address\":\"192.0.2.1\",\"interface-type\":\"cluster\"}],\"cluster-members\":["
            + "{\"name\":\"m1\",\"ip-address\":\"192.0.2.2\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.2\"},{\"ipv4-address\":\"203.0.113.2\"}]},"
            + "{\"name\":\"m2\",\"ip-address\":\"192.0.2.3\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.3\"},{\"ipv4-address\":\"203.0.113.3\"}]}]}");
        assertEquals(List.of("192.0.2.1", "192.0.2.2", "203.0.113.2", "192.0.2.3", "203.0.113.3"), cluster.ipv4());
        assertTrue(cluster.complete());
        assertFalse(parse("{\"uid\":\"u1\",\"type\":\"simple-cluster\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}]}").complete(),
            "a classic cluster without its member list");
    }

    @Test void anythingUnreadableLeavesTheSetIncomplete() throws Exception {
        for (String object : List.of(
                "{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"ipv4-address\":\"192.0.2.1\"}",
                "{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"interfaces\":[]}",
                "{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.300\"}]}",
                "{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"},{\"name\":\"no-address\"}]}",
                "{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"},{\"ipv6-address\":\"\"}]}",
                "{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"interfaces\":[{\"ipv4-address\":\"\",\"ipv6-address\":\"\"}]}",
                "{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}],\"cluster-members\":[{\"name\":\"m1\"}]}",
                "{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}],\"cluster-members\":[{\"name\":\"m1\",\"ip-address\":\"\"}]}"))
            assertFalse(parse(object).complete(), object);
        assertTrue(parse("{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"},{\"ipv6-address\":\"2001:db8::1\"}]}").complete());
        assertTrue(parse("{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"ipv4-address\":\"\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"},{\"ipv4-address\":\"\",\"ipv6-address\":\"2001:db8::1\"}]}").complete());
    }

    @Test void wrapperSubListsCompleteOnlyWhenUntruncated() throws Exception {
        var wrapped = parse("{\"uid\":\"u1\",\"type\":\"CpmiGatewayCluster\",\"ipv4-address\":\"192.0.2.1\","
            + "\"interfaces\":{\"objects\":[{\"ipv4-address\":\"192.0.2.1\"}],\"from\":1,\"to\":1,\"total\":1},"
            + "\"cluster-members\":{\"objects\":[{\"ip-address\":\"192.0.2.2\",\"ipv4-address\":\"\","
            + "\"interfaces\":{\"objects\":[{\"ipv4-address\":\"192.0.2.2\"}],\"total\":1}}],\"total\":1}}");
        assertEquals(new CpObjectInventory.GatewayAddresses(List.of("192.0.2.1", "192.0.2.2"), true), wrapped);

        // A paged wrapper whose total exceeds objects.size() (or carries from/to without total) must fail closed.
        assertFalse(parse("{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"ipv4-address\":\"192.0.2.1\","
            + "\"interfaces\":{\"objects\":[{\"ipv4-address\":\"192.0.2.1\"}],\"from\":1,\"to\":1,\"total\":2}}").complete());
        assertFalse(parse("{\"uid\":\"u1\",\"type\":\"simple-gateway\",\"ipv4-address\":\"192.0.2.1\","
            + "\"interfaces\":{\"objects\":[{\"ipv4-address\":\"192.0.2.1\"}],\"from\":1,\"to\":1}}").complete());
    }

    @Test void onlyGatewayClassObjectsCarryAddresses() throws Exception {
        assertNull(CpObjectInventoryParser.parse("s", "d", List.of(json.readTree(
            "{\"uid\":\"u1\",\"type\":\"host\",\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}]}"))).get(0).gateway());
    }

    @Test void theMapperCarriesTheInventoryAddressesOfADictionaryGateway() throws Exception {
        var item = CpObjectInventoryParser.parse("s", "d", List.of(json.readTree("{\"uid\":\"gw-1\",\"name\":\"GW-ALPHA\",\"type\":\"simple-gateway\","
            + "\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"},{\"ipv4-address\":\"198.51.100.1\"}]}"))).get(0);
        var node = json.createObjectNode().put("uid", "gw-1").put("name", "GW-ALPHA").put("type", "simple-gateway");
        item.gateway().ipv4().forEach(node.putArray(CheckPointPolicyMapper.GATEWAY_IPV4_FIELD)::add);
        node.put(CheckPointPolicyMapper.GATEWAY_COMPLETE_FIELD, true);
        Instant at = Instant.parse("2026-11-14T08:00:00Z");
        CheckPointPolicyMapper.InventoryLookup lookup = uid -> uid.equals("gw-1") ? new CheckPointPolicyMapper.InventoryObject("gw-1", node, null, at) : null;
        JsonNode page = json.readTree("{\"uid\":\"layer-01\",\"name\":\"Synthetic access\",\"rulebase\":[{\"uid\":\"r1\",\"type\":\"access-rule\",\"rule-number\":1,\"name\":\"R1\",\"enabled\":true,"
            + "\"source\":[\"any-1\"],\"destination\":[\"gw-1\"],\"service\":[\"any-1\"],\"action\":\"act-1\",\"install-on\":[\"any-1\"],\"time\":[\"any-1\"]}],"
            + "\"objects-dictionary\":[{\"uid\":\"any-1\",\"name\":\"Any\",\"type\":\"CpmiAnyObject\"},{\"uid\":\"act-1\",\"name\":\"Accept\",\"type\":\"RulebaseAction\"},"
            + "{\"uid\":\"gw-1\",\"name\":\"GW-ALPHA\",\"type\":\"simple-gateway\",\"ipv4-address\":\"192.0.2.1\"}],\"from\":1,\"to\":1,\"total\":1}");
        var meta = new PolicySnapshot.Metadata("p1", "s1", "MGR-BRAVO-01", "CP", "c1", "DOM-TANGO-01", "POL-ALPHA-01", at.toString(), "a1", List.of());
        var snapshot = new CheckPointPolicyMapper().map(meta, List.of(page), List.of(), lookup);
        var gw = snapshot.objects().get(PolicySnapshot.ref("p1", "object", "gw-1"));
        assertEquals("gateway", gw.type());
        assertEquals(List.of("ipv4-address: 192.0.2.1", "gateway-ipv4: 192.0.2.1", "gateway-ipv4: 198.51.100.1", "gateway-addresses: complete"), gw.values());
        // Without the inventory the dictionary's main address stays alone: no bound.
        var without = new CheckPointPolicyMapper().map(meta, List.of(page), List.of(), null);
        assertEquals(List.of("ipv4-address: 192.0.2.1"), without.objects().get(PolicySnapshot.ref("p1", "object", "gw-1")).values());
    }
}
