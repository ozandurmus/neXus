package com.securityexpert.nexus.ui2.service.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.management.ManagementTreeService.Candidate;
import com.securityexpert.nexus.ui2.service.management.ManagementTreeService.Linked;

class ManagementTreeServiceTest {

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("c1", null, "DOM-A", "PLAIN_HIGH_AVAILABILITY_CLUSTER", "cls-obj", null, "u-c1"),
            new Candidate("m1", "c1", "DOM-A", "PLAIN_CLUSTER_MEMBER", "gw-m1", "CLS-REF", "u-m1"),
            new Candidate("m2", "c1", "DOM-A", "PLAIN_CLUSTER_MEMBER", "gw-m2", "CLS-REF", "u-m2"),
            new Candidate("g1", null, "DOM-A", "STANDALONE_PRODUCT_GATEWAY", "gw-solo", null, "u-g1"),
            new Candidate("v1", null, "DOM-B", "STANDALONE_VIRTUAL_SYSTEM", "vs-one", null, "u-v1"));

    private static final Map<String, Linked> LINKED = Map.of(
            "check_point|DOM-A|u-m1", new Linked("dev-m1", "FW-OBSERVED-1", "ACTIVE", "ENROLLED", "COMPLETED", null));

    @Test
    @SuppressWarnings("unchecked")
    void domainsHoldClustersFirstWithMembersJoinedToTheirDeviceRecord() {
        List<Map<String, Object>> domains = ManagementTreeService.build(CANDIDATES, LINKED);
        assertThat(domains).extracting(d -> d.get("domain")).containsExactly("DOM-A", "DOM-B");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) domains.get(0).get("nodes");
        Map<String, Object> cluster = nodes.get(0);
        assertThat(cluster.get("cluster_member_ref")).isEqualTo("CLS-REF"); // the members' reference, as elsewhere
        assertThat(cluster).doesNotContainKey("hostname");
        List<Map<String, Object>> members = (List<Map<String, Object>>) cluster.get("children");
        assertThat(members).hasSize(2);
        assertThat(members.get(0)).containsEntry("hostname", "FW-OBSERVED-1").containsEntry("ha_role", "ACTIVE")
                .containsEntry("device_id", "dev-m1").containsEntry("imported", true);
        assertThat(members.get(1)).containsEntry("imported", false).containsEntry("ha_role", null);
        assertThat(nodes.get(1)).containsEntry("hostname", "gw-solo");
        Map<String, Object> vs = ((List<Map<String, Object>>) domains.get(1).get("nodes")).get(0);
        assertThat(vs).containsEntry("virtual_system", "vs-one").doesNotContainKey("hostname");
    }

    @Test
    void countsSeparateClustersGatewaysAndVirtualSystems() {
        assertThat(ManagementTreeService.counts(CANDIDATES, LINKED)).containsEntry("domains", 2L)
                .containsEntry("clusters", 1L).containsEntry("gateways", 3L).containsEntry("virtual_systems", 1L)
                .containsEntry("imported", 1L);
    }
}
