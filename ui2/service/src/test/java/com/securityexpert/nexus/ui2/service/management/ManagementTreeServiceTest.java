package com.securityexpert.nexus.ui2.service.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.management.ManagementTreeService.Ack;
import com.securityexpert.nexus.ui2.service.management.ManagementTreeService.Candidate;
import com.securityexpert.nexus.ui2.service.management.ManagementTreeService.Linked;

@SuppressWarnings("unchecked")
class ManagementTreeServiceTest {

    private static Candidate cp(String id, String parent, String domain, String kind, String name, String clusterRef, String model) {
        return new Candidate(id, parent, "check_point", domain, kind, name, clusterRef, "u-" + id, model);
    }

    private static final List<Candidate> CP = List.of(
            cp("c1", null, "DOM-A", "PLAIN_HIGH_AVAILABILITY_CLUSTER", "cls-obj", null, null),
            cp("m1", "c1", "DOM-A", "PLAIN_CLUSTER_MEMBER", "gw-m1", "cls-ref", "6000 Appliances"),
            cp("m2", "c1", "DOM-A", "PLAIN_CLUSTER_MEMBER", "gw-m2", "cls-ref", "6000 Appliances"),
            cp("g1", null, "DOM-A", "STANDALONE_PRODUCT_GATEWAY", "gw-branch", null, "1570/1590 Appliances"),
            cp("s1", null, "DOM-A", "STANDALONE_PRODUCT_GATEWAY", "mgmt-log", null, "Smart-1"),
            cp("v1", null, "DOM-B", "STANDALONE_VIRTUAL_SYSTEM", "vs-one", null, null));

    private static final Map<String, Linked> LINKED = Map.of(
            "check_point|DOM-A|u-m1", new Linked("dev-m1", "FW-OBSERVED-1", "ACTIVE", "CLS-SUMMARY-NAME", "ENROLLED", "COMPLETED", null));

    @Test
    void membersCarryTheDeviceSummaryNameRoleAndClusterName() {
        List<Map<String, Object>> domains = ManagementTreeService.build(CP, LINKED, Map.of());
        assertThat(domains).extracting(d -> d.get("domain")).containsExactly("DOM-A", "DOM-B");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) domains.get(0).get("nodes");
        Map<String, Object> cluster = nodes.get(0);
        assertThat(cluster.get("cluster_member_ref")).isEqualTo("CLS-SUMMARY-NAME"); // the name every other screen shows
        List<Map<String, Object>> members = (List<Map<String, Object>>) cluster.get("children");
        assertThat(members.get(0)).containsEntry("hostname", "FW-OBSERVED-1").containsEntry("ha_role", "ACTIVE")
                .containsEntry("imported", true).containsEntry("ack_token", null);
        assertThat(members.get(1)).containsEntry("imported", false);
        assertThat((String) members.get(1).get("ack_token")).hasSize(24);
        // the Smart-1 sorts last and is never offered as a missing device
        assertThat(nodes.get(nodes.size() - 1)).containsEntry("category", "management_appliance").containsEntry("ack_token", null);
    }

    @Test
    void notInNexusCountsEnrollableDevicesOnlyAndHonoursAcknowledgements() {
        Map<String, Object> counts = ManagementTreeService.counts(CP, LINKED, Map.of());
        assertThat(counts).containsEntry("gateways", 3L).containsEntry("management_appliances", 1L)
                .containsEntry("not_in_nexus", 2L).containsEntry("acknowledged", 0L);
        Map<String, Object> acked = ManagementTreeService.counts(CP, LINKED,
                Map.of("check_point|DOM-A|u-g1", new Ack("branch retired", null)));
        assertThat(acked).containsEntry("not_in_nexus", 1L).containsEntry("acknowledged", 1L);
    }

    @Test
    void paloAltoHaPairsAreGroupedByTheirSharedReference() {
        List<Candidate> pan = List.of(
                new Candidate("p1", null, "palo_alto", null, "PALO_ALTO_DEVICE", "fw-a", "SER1SER2", "SER1", "PA-5250"),
                new Candidate("p2", null, "palo_alto", null, "PALO_ALTO_DEVICE", "fw-b", "SER1SER2", "SER2", "PA-5250"),
                new Candidate("p3", null, "palo_alto", null, "PALO_ALTO_DEVICE", "fw-solo", null, "SER3", "PA-440"));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) ManagementTreeService.build(pan, Map.of(), Map.of()).get(0).get("nodes");
        assertThat(nodes.get(0)).containsEntry("kind", "PALO_ALTO_HA_PAIR_CLUSTER").containsEntry("candidate_id", null);
        assertThat((List<?>) nodes.get(0).get("children")).hasSize(2);
        assertThat(nodes.get(1)).containsEntry("hostname", "fw-solo");
        assertThat(ManagementTreeService.counts(pan, Map.of(), Map.of())).containsEntry("clusters", 1L).containsEntry("gateways", 3L);
    }
}
