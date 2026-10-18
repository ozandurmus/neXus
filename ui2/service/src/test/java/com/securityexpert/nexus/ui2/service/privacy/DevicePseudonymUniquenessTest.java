package com.securityexpert.nexus.ui2.service.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;

import com.securityexpert.nexus.ui2.service.privacy.MaskingDeviceDirectory.Entry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * UI review 2026-10-18 F-1: a device pseudonym was derived from the hostname and cluster reference only, so a Cisco
 * ASA failover pair (one hostname replicated onto both units) and a duplicate registration showed one name twice.
 * With the device ID the oldest device keeps the shared name and every later one gets a deterministic disambiguator.
 */
class DevicePseudonymUniquenessTest {

    /** Built at run time; never a literal key in the repository. */
    private static byte[] key(char c) {
        return String.valueOf(c).repeat(32).getBytes(StandardCharsets.UTF_8);
    }

    private static TopologyNamePseudonymizer pseudonymizer(List<Entry> directory) {
        TopologyNamePseudonymizer p = new TopologyNamePseudonymizer(key('k'));
        p.setDeviceDirectory(() -> directory);
        return p;
    }

    @Test
    void anAsaFailoverPairWithOneHostnameGetsTwoMemberPseudonymsAndTheOldestKeepsTheSharedOne() {
        List<Entry> directory = List.of(
                new Entry("dev-a", "asa-edge", "asa-pair"),
                new Entry("dev-b", "asa-edge", "asa-pair"),
                new Entry("dev-c", "unique-gw", null));
        TopologyNamePseudonymizer p = pseudonymizer(directory);
        String shared = p.maskDeviceName("asa-edge", "asa-pair");

        String a = p.maskDeviceName("asa-edge", "asa-pair", "dev-a");
        String b = p.maskDeviceName("asa-edge", "asa-pair", "dev-b");

        assertThat(a).isEqualTo(shared);
        assertThat(b).isNotEqualTo(a).startsWith(shared.substring(0, shared.lastIndexOf("-M") + 2));
        String cluster = p.maskClusterName("asa-pair").substring(4);
        assertThat(Set.of(a, b)).containsExactlyInAnyOrder("FW-" + cluster + "-M1", "FW-" + cluster + "-M2");
        // A unique device keeps the name it had before, with or without its ID.
        assertThat(p.maskDeviceName("unique-gw", null, "dev-c")).isEqualTo(p.maskDeviceName("unique-gw", null));
        // A response that masks the member without its cluster reference still tells the two apart.
        assertThat(p.maskDeviceName("asa-edge", null, "dev-b")).isEqualTo(b);
        assertThat(p.maskDeviceName("asa-edge", null, "dev-a")).isEqualTo(a);
    }

    @Test
    void disambiguationIsDeterministicAcrossInstancesAndOrderOfRequests() {
        List<Entry> directory = List.of(
                new Entry("dev-a", "asa-edge", "asa-pair"),
                new Entry("dev-b", "asa-edge", "asa-pair"));
        TopologyNamePseudonymizer first = pseudonymizer(directory);
        TopologyNamePseudonymizer second = pseudonymizer(directory);
        String firstB = first.maskDeviceName("asa-edge", "asa-pair", "dev-b");
        String firstA = first.maskDeviceName("asa-edge", "asa-pair", "dev-a");
        // The second instance is asked in the other order; the directory's registration order decides, not requests.
        String secondA = second.maskDeviceName("asa-edge", "asa-pair", "dev-a");
        String secondB = second.maskDeviceName("asa-edge", "asa-pair", "dev-b");
        assertThat(secondA).isEqualTo(firstA);
        assertThat(secondB).isEqualTo(firstB);
        assertThat(first.maskDeviceName("asa-edge", "asa-pair", "dev-b")).isEqualTo(firstB);
    }

    @Test
    void aSyntheticHashOrdinalCollisionBetweenDifferentNamesIsResolved() {
        // Names without an ordinal suffix take M1/M2 from a keyed hash: find two that collide under this key.
        TopologyNamePseudonymizer probe = new TopologyNamePseudonymizer(key('k'));
        String collidingA = null;
        String collidingB = null;
        Map<String, String> seen = new LinkedHashMap<>();
        for (int i = 0; i < 50 && collidingB == null; i++) {
            String name = "edge-unit-" + (char) ('a' + i % 26) + (char) ('a' + i / 26) + "x";
            String masked = probe.maskDeviceName(name, "pair-x");
            if (seen.containsKey(masked)) {
                collidingA = seen.get(masked);
                collidingB = name;
            }
            seen.put(masked, name);
        }
        assertThat(collidingB).as("two suffix-less names share a hash ordinal").isNotNull();

        TopologyNamePseudonymizer p = pseudonymizer(List.of(
                new Entry("dev-1", collidingA, "pair-x"),
                new Entry("dev-2", collidingB, "pair-x")));
        assertThat(p.maskDeviceName(collidingA, "pair-x")).isEqualTo(p.maskDeviceName(collidingB, "pair-x"));
        assertThat(p.maskDeviceName(collidingA, "pair-x", "dev-1"))
                .isNotEqualTo(p.maskDeviceName(collidingB, "pair-x", "dev-2"));
    }

    @Test
    void aThirdMemberWithADuplicateOrdinalTakesTheNextFreeOrdinal() {
        TopologyNamePseudonymizer p = pseudonymizer(List.of(
                new Entry("dev-1", "pan-fw-01", "pan-ha"),
                new Entry("dev-2", "pan-fw-02", "pan-ha"),
                new Entry("dev-3", "pan-fw-01", "pan-ha")));
        String cluster = p.maskClusterName("pan-ha").substring(4);
        assertThat(p.maskDeviceName("pan-fw-01", "pan-ha", "dev-1")).isEqualTo("FW-" + cluster + "-M1");
        assertThat(p.maskDeviceName("pan-fw-02", "pan-ha", "dev-2")).isEqualTo("FW-" + cluster + "-M2");
        assertThat(p.maskDeviceName("pan-fw-01", "pan-ha", "dev-3")).isEqualTo("FW-" + cluster + "-M3");
    }

    @Test
    void standaloneDevicesWithOneHostnameGetDistinctStandalonePseudonyms() {
        List<Entry> directory = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            directory.add(new Entry("dev-" + i, "branch-gw", null));
        }
        TopologyNamePseudonymizer p = pseudonymizer(directory);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            names.add(p.maskDeviceName("branch-gw", null, "dev-" + i));
        }
        assertThat(names).hasSize(4).allMatch(n -> n.matches("FW-[A-Z]+-0[1-9]"));
        assertThat(p.maskDeviceName("branch-gw", null, "dev-0")).isEqualTo(p.maskDeviceName("branch-gw", null));
    }

    @Test
    void theOverrideAppliesOnlyToTheDevicesOwnHostnameAndNeedsADirectory() {
        TopologyNamePseudonymizer p = pseudonymizer(List.of(
                new Entry("dev-a", "asa-edge", "asa-pair"),
                new Entry("dev-b", "asa-edge", "asa-pair")));
        // A certificate CN beside the same device ID is not the device's hostname.
        assertThat(p.maskDeviceName("cert-cn", null, "dev-b")).isEqualTo(p.maskDeviceName("cert-cn", null));
        TopologyNamePseudonymizer without = new TopologyNamePseudonymizer(key('k'));
        assertThat(without.maskDeviceName("asa-edge", "asa-pair", "dev-b"))
                .isEqualTo(without.maskDeviceName("asa-edge", "asa-pair"));
    }

    @Test
    void managedDevicesUseTheirOwnMdPrefixFamilyDeterministically() {
        TopologyNamePseudonymizer p = new TopologyNamePseudonymizer(key('k'));
        String managed = p.maskManagedDeviceName("branch-fgt-17");
        assertThat(managed).matches("MD-[A-Z]+-0[1-9]");
        assertThat(p.maskManagedDeviceName(" branch-fgt-17 ")).isEqualTo(managed);
        assertThat(new TopologyNamePseudonymizer(key('k')).maskManagedDeviceName("branch-fgt-17")).isEqualTo(managed);
        assertThat(p.maskVirtualSystem("branch-fgt-17", null)).startsWith("VS-");
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            distinct.add(p.maskManagedDeviceName("managed-" + i));
        }
        assertThat(distinct).hasSize(40);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAiviewProjectionMasksRowsByDeviceIdAndManagerListingsAsManagedDevices() {
        TopologyNamePseudonymizer p = pseudonymizer(List.of(
                new Entry("dev-a", "asa-edge", "asa-pair"),
                new Entry("dev-b", "asa-edge", "asa-pair")));
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key('i')), p);
        var request = new ServletServerHttpRequest(httpRequest);

        List<Map<String, Object>> rows = List.of(
                row("dev-a", "asa-edge", "asa-pair", "cisco_asa", "gateway", null),
                row("dev-b", "asa-edge", "asa-pair", "cisco_asa", "gateway", null),
                row("dev-f", "fmg-1", null, "fortinet", "management_server", "branch-fgt-17, branch-fgt-18"),
                row("dev-g", "fgt-vdom-host", null, "fortinet", "gateway", "root, dmz"));
        var masked = (List<Map<String, Object>>) advice.beforeBodyWrite(rows, null, null, null, request, null);

        assertThat(masked.get(0).get("hostname")).isNotEqualTo(masked.get(1).get("hostname"));
        assertThat(masked.get(2).get("virtual_systems")).isEqualTo(
                p.maskManagedDeviceName("branch-fgt-17") + ", " + p.maskManagedDeviceName("branch-fgt-18"));
        assertThat((String) masked.get(3).get("virtual_systems")).startsWith("VS-").contains(", VS-");

        Map<String, Object> gridMember = new LinkedHashMap<>();
        gridMember.put("virtual_system", "branch-fgt-17");
        gridMember.put("grid_master", false);
        gridMember.put("services", List.of());
        var inventory = (Map<String, Object>) advice.beforeBodyWrite(Map.of("device_id", "dev-f",
                "grid_members", List.of(gridMember)), null, null, null, request, null);
        var member = ((List<Map<String, Object>>) inventory.get("grid_members")).get(0);
        // The manager's tree list and its managed-devices table agree, so selecting a row still finds it.
        assertThat(member.get("virtual_system")).isEqualTo(p.maskManagedDeviceName("branch-fgt-17"));

        // An Infoblox license row names its grid member with the same MD- name; a grid-wide license stays "grid".
        var licenses = (List<Map<String, Object>>) advice.beforeBodyWrite(List.of(
                Map.of("member", "branch-fgt-17", "type", "DNS", "expiry_date", "2027-01-01"),
                Map.of("member", "grid", "type", "NIOS", "expiry_date", "2027-01-01")), null, null, null, request, null);
        assertThat(licenses.get(0).get("member")).isEqualTo(p.maskManagedDeviceName("branch-fgt-17"));
        assertThat(licenses.get(1).get("member")).isEqualTo("grid");
    }

    private static Map<String, Object> row(String id, String hostname, String cluster, String vendor, String role,
            String virtualSystems) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("device_id", id);
        row.put("role", role);
        row.put("vendor_hint", vendor);
        row.put("hostname", hostname);
        row.put("cluster_member_ref", cluster);
        row.put("virtual_systems", virtualSystems);
        return row;
    }
}
