package com.securityexpert.nexus.ui2.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.service.device.inventory.ClusterInventoryMerger;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryCollectService;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryQueryService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InventoryControllerMaskingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    private static final List<String> ADDRESSES = List.of(
            "192.0.2.10/24", "198.51.100.20/24", "198.51.100.21", "192.0.2.17/0", "2001:db8::10/64");
    private final byte[] key = "synthetic-inventory-mask-key-0001".getBytes(StandardCharsets.UTF_8);
    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(key);
    private final PrivacyMaskingResponseBodyAdvice advice = new PrivacyMaskingResponseBodyAdvice(
            ips, new TopologyNamePseudonymizer(key));

    @Test
    void bluecoatRoutingIsMaskedOnDeviceAndClusterEndpoints() throws Exception {
        verifyInventoryEndpoints("bluecoat");
    }

    @Test
    void paloAltoPrimarySecondaryAndIpv6AddressesAreMaskedOnBothEndpoints() throws Exception {
        verifyInventoryEndpoints("paloalto");
    }

    private void verifyInventoryEndpoints(String vendor) throws Exception {
        String deviceId = "synthetic-" + vendor;
        var iface = new InventoryInterface("if-1", "ethernet1/1", Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("a1", ADDRESSES.get(0), "ipv4", "member"),
                        new InventoryAddress("a2", ADDRESSES.get(1), "ipv4", "member"),
                        new InventoryAddress("a3", ADDRESSES.get(4), "ipv6", "member")));
        var routes = List.of(
                new InventoryRoute("r1", ADDRESSES.get(1), Optional.of(ADDRESSES.get(2)),
                        Optional.of("ethernet1/1"), "static", Optional.empty()),
                new InventoryRoute("r2", ADDRESSES.get(3), Optional.empty(), Optional.empty(), "static", Optional.empty()));
        var run = new InventoryRun("run-1", deviceId, "job-1", Instant.parse("2026-10-04T00:00:00Z"), 1,
                List.of(new InventoryContext("physical", List.of(iface), routes)));
        var query = mock(InventoryQueryService.class);
        when(query.deviceInventory(deviceId)).thenReturn(new InventoryQueryService.DeviceInventoryOutcome.Found(
                deviceId, Optional.of(run)));
        when(query.jobFor(run)).thenReturn(Optional.empty());
        when(query.clusterInventory("CLS-TEST-01")).thenReturn(new InventoryQueryService.ClusterInventoryOutcome.Found(
                "CLS-TEST-01", List.of(), ClusterInventoryMerger.merge(List.of(deviceId), Map.of(deviceId, run))));
        var controller = new InventoryController(query, mock(InventoryCollectService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
        var paths = List.of("/devices/" + deviceId + "/inventory", "/clusters/CLS-TEST-01/inventory");
        var rawBodies = List.of(controller.getDeviceInventory(deviceId).getBody(),
                controller.getClusterInventory("CLS-TEST-01").getBody());
        for (int i = 0; i < paths.size(); i++) {
            var masked = mvc.perform(get(paths.get(i)).requestAttr(
                    GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true)).andExpect(status().isOk()).andReturn();
            assertThat(masked.getResponse().getHeader(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER)).isEqualTo("true");
            JsonNode body = JSON.readTree(masked.getResponse().getContentAsString());
            assertOnlyMaskedIps(body, maskedIps());
            String text = body.toString();
            for (String source : ADDRESSES) {
                assertThat(text).doesNotContain(source.split("/")[0]);
            }
            assertThat(text).contains("[REDACTED_IP]", ips.mask(ADDRESSES.get(0)), ips.mask(ADDRESSES.get(1)));
            var admin = mvc.perform(get(paths.get(i)).requestAttr(
                    GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, false)).andExpect(status().isOk()).andReturn();
            assertThat(admin.getResponse().getHeader(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER)).isNull();
            assertThat(JSON.readTree(admin.getResponse().getContentAsString()))
                    .isEqualTo(JSON.valueToTree(rawBodies.get(i)));
        }
    }

    @Test
    void addressListsAndGatewayVariantsAreRecursivelyMaskedWithoutMutatingInput() {
        var body = Map.of("contexts", List.of(Map.of(
                "routes", List.of(Map.of("destination", List.of(ADDRESSES.get(1), ADDRESSES.get(3)),
                        "next_hop", List.of(ADDRESSES.get(2)), "gateway", ADDRESSES.get(2))),
                "interfaces", List.of(Map.of("address", List.of(ADDRESSES.get(0), ADDRESSES.get(4)),
                        "ip_addresses", List.of(ADDRESSES.get(1), ADDRESSES.get(4)),
                        "addresses", List.of(ADDRESSES.get(1), ADDRESSES.get(4)),
                        "secondary_addresses", List.of(Map.of("address", ADDRESSES.get(2))),
                        "alternate_addresses", ADDRESSES.get(0) + ", " + ADDRESSES.get(1),
                        "ipv6_addresses", List.of(ADDRESSES.get(4)))))));
        String original = JSON.valueToTree(body).toString();
        JsonNode masked = JSON.valueToTree(advice.maskObject(body, null));
        assertOnlyMaskedIps(masked, maskedIps());
        assertThat(masked.toString()).doesNotContain("2001:db8", "192.0.2.", "198.51.100.");
        assertThat(JSON.valueToTree(body).toString()).isEqualTo(original);
    }

    private Set<String> maskedIps() {
        Set<String> expected = new HashSet<>();
        for (String source : ADDRESSES) {
            var matcher = IPV4.matcher(ips.mask(source));
            while (matcher.find()) expected.add(matcher.group());
        }
        return expected;
    }

    /** Walk every DTO leaf; a new address-bearing field must never silently bypass the projection. */
    private static void assertOnlyMaskedIps(JsonNode node, Set<String> expected) {
        if (node.isTextual()) {
            var matcher = IPV4.matcher(node.textValue());
            while (matcher.find()) assertThat(expected).contains(matcher.group());
        } else {
            node.elements().forEachRemaining(child -> assertOnlyMaskedIps(child, expected));
        }
    }
}
