package com.securityexpert.nexus.ui2.service.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;

import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobEvent;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class PrivacyMaskingResponseBodyAdviceTest {

    private PrivacyMaskingResponseBodyAdvice advice;
    private HttpServletRequest httpRequest;
    private ServletServerHttpRequest serverRequest;

    @BeforeEach
    void setUp() {
        byte[] testKey = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        SubnetPreservingIpMasker ipMasker = new SubnetPreservingIpMasker(testKey);
        TopologyNamePseudonymizer topologyPseudonymizer = new TopologyNamePseudonymizer(testKey);
        advice = new PrivacyMaskingResponseBodyAdvice(ipMasker, topologyPseudonymizer);

        httpRequest = mock(HttpServletRequest.class);
        serverRequest = mock(ServletServerHttpRequest.class);
        when(serverRequest.getServletRequest()).thenReturn(httpRequest);
    }

    @Test
    void returnsUnmaskedDataWhenNotReplayViewer() {
        when(httpRequest.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(null);

        Map<String, Object> body = Map.of(
                "device_id", "148bd45b-e5b4-490b-95c5-54862e2d63d0",
                "hostname", "FW-CKP-GARANTIMOBAPP-AA-1",
                "cluster_member_ref", "FW-CKP-GARANTIMOBAPP-AA-CLS"
        );

        Object result = advice.beforeBodyWrite(body, null, null, null, serverRequest, null);
        assertThat(result).isSameAs(body);
    }

    @Test
    void masksDeviceSummaryListForReplayViewer() {
        when(httpRequest.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);

        Map<String, Object> dev1 = new LinkedHashMap<>();
        dev1.put("device_id", "148bd45b-e5b4-490b-95c5-54862e2d63d0");
        dev1.put("hostname", "FW-CKP-GARANTIMOBAPP-AA-1");
        dev1.put("cluster_member_ref", "FW-CKP-GARANTIMOBAPP-AA-CLS");
        dev1.put("virtual_systems", "VS-APP, VS-DB");
        dev1.put("latest_job_terminal_reason", "connect_failed to 192.168.230.2: timed out");

        Map<String, Object> body = Map.of("devices", List.of(dev1));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) advice.beforeBodyWrite(body, null, null, null, serverRequest, null);

        assertThat(result).isNotSameAs(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) result.get("devices");
        assertThat(devices).hasSize(1);

        Map<String, Object> maskedDev = devices.get(0);
        assertThat(maskedDev.get("device_id")).isEqualTo("148bd45b-e5b4-490b-95c5-54862e2d63d0");
        assertThat((String) maskedDev.get("hostname")).startsWith("FW-").doesNotContain("GARANTI");
        assertThat((String) maskedDev.get("cluster_member_ref")).startsWith("CLS-").doesNotContain("GARANTI");
        assertThat((String) maskedDev.get("virtual_systems")).doesNotContain("VS-APP");
        assertThat((String) maskedDev.get("latest_job_terminal_reason")).doesNotContain("192.168.230.2").contains("10.");

        // In-memory original map must NOT be mutated
        assertThat(dev1.get("hostname")).isEqualTo("FW-CKP-GARANTIMOBAPP-AA-1");
    }

    @Test
    void masksDeviceAndClusterInventoryForReplayViewer() {
        when(httpRequest.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);

        Map<String, Object> addr1 = Map.of("address", "192.168.230.1/24", "role", "cluster_virtual");
        Map<String, Object> addr2 = Map.of("address", "192.168.230.2/24", "role", "cluster_member");
        Map<String, Object> iface = Map.of("name", "eth1", "addresses", List.of(addr1, addr2));
        Map<String, Object> route = Map.of("destination", "10.50.0.0/16", "next_hop", "192.168.230.254");
        Map<String, Object> context = Map.of("context", "system", "interfaces", List.of(iface), "routes", List.of(route));

        Map<String, Object> clusterInventory = new LinkedHashMap<>();
        clusterInventory.put("cluster_member_ref", "FW-CKP-GARANTIMOBAPP-AA-CLS");
        clusterInventory.put("contexts", List.of(context));
        clusterInventory.put("virtual_systems", List.of("VS-APP"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) advice.beforeBodyWrite(clusterInventory, null, null, null, serverRequest, null);

        assertThat(result.get("cluster_member_ref").toString()).startsWith("CLS-").doesNotContain("GARANTI");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contexts = (List<Map<String, Object>>) result.get("contexts");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ifaces = (List<Map<String, Object>>) contexts.get(0).get("interfaces");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> addrs = (List<Map<String, Object>>) ifaces.get(0).get("addresses");

        String maskedAddr1 = (String) addrs.get(0).get("address");
        String maskedAddr2 = (String) addrs.get(1).get("address");

        assertThat(maskedAddr1).doesNotContain("192.168.230");
        assertThat(maskedAddr2).doesNotContain("192.168.230");

        // Subnets must match between VIP and member in masked output!
        String sub1 = maskedAddr1.substring(0, maskedAddr1.lastIndexOf('.'));
        String sub2 = maskedAddr2.substring(0, maskedAddr2.lastIndexOf('.'));
        assertThat(sub1).isEqualTo(sub2);
    }

    @Test
    void masksJobEventsForReplayViewer() {
        when(httpRequest.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);

        JobEvent event = new JobEvent("job-123", "DEVICE_INVENTORY", "148bd45b-e5b4-490b-95c5-54862e2d63d0",
                "FAILED", "connect_failed to 192.168.230.2: timed out", Instant.now());

        List<JobEvent> jobEvents = List.of(event);

        @SuppressWarnings("unchecked")
        List<JobEvent> result = (List<JobEvent>) advice.beforeBodyWrite(jobEvents, null, null, null, serverRequest, null);

        assertThat(result).hasSize(1);
        JobEvent maskedEvent = result.get(0);
        assertThat(maskedEvent.jobId()).isEqualTo("job-123");
        assertThat(maskedEvent.targetDeviceId()).isEqualTo("148bd45b-e5b4-490b-95c5-54862e2d63d0");
        assertThat(maskedEvent.terminalReason()).doesNotContain("192.168.230.2").contains("10.");
    }

    @Test
    void masksJobFailureWithBothIpAndRawHostname() {
        when(httpRequest.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);

        // Pre-register a device hostname
        advice.maskObject(Map.of("hostname", "FW-CKP-GARANTIMOBAPP-AA-1"), null);

        JobEvent event = new JobEvent("job-999", "DEVICE_INVENTORY", "148bd45b-e5b4-490b-95c5-54862e2d63d0",
                "FAILED", "SSH connection to FW-CKP-GARANTIMOBAPP-AA-1 at 192.168.230.2 timed out", Instant.now());

        @SuppressWarnings("unchecked")
        List<JobEvent> result = (List<JobEvent>) advice.beforeBodyWrite(List.of(event), null, null, null, serverRequest, null);

        assertThat(result).hasSize(1);
        String reason = result.get(0).terminalReason();
        assertThat(reason).doesNotContain("FW-CKP-GARANTIMOBAPP-AA-1");
        assertThat(reason).doesNotContain("192.168.230.2");
        assertThat(reason).contains("FW-");
        assertThat(reason).contains("10.");
    }

    @Test
    void masksClusterInventoryWithBareVipInheritingSlash23() {
        when(httpRequest.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);

        Map<String, Object> vipAddr = Map.of("address", "10.230.4.11");
        Map<String, Object> m1Addr = Map.of("address", "10.230.4.12/23");
        Map<String, Object> m2Addr = Map.of("address", "10.230.4.13/23");

        Map<String, Object> iface = Map.of(
                "name", "eth6-01",
                "addresses", List.of(vipAddr),
                "member_addresses", Map.of(
                        "Member-1", List.of(m1Addr),
                        "Member-2", List.of(m2Addr)
                )
        );
        Map<String, Object> context = Map.of("context", "system", "interfaces", List.of(iface), "routes", List.of());
        Map<String, Object> clusterInventory = Map.of(
                "cluster_member_ref", "CLS-GARANTI-PROD",
                "contexts", List.of(context)
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) advice.beforeBodyWrite(clusterInventory, null, null, null, serverRequest, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contexts = (List<Map<String, Object>>) result.get("contexts");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ifaces = (List<Map<String, Object>>) contexts.get(0).get("interfaces");
        Map<String, Object> maskedIface = ifaces.get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vips = (List<Map<String, Object>>) maskedIface.get("addresses");
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> memberAddrs = (Map<String, List<Map<String, Object>>>) maskedIface.get("member_addresses");

        String maskedVip = (String) vips.get(0).get("address");
        String maskedM1 = (String) memberAddrs.get("Member-1").get(0).get("address");

        assertThat(maskedVip).doesNotContain("/");
        assertThat(maskedM1).endsWith("/23");

        // Verify that masked VIP and masked member are in the EXACT same /23 subnet
        String[] vipParts = maskedVip.split("\\.");
        String[] m1Parts = maskedM1.substring(0, maskedM1.indexOf('/')).split("\\.");

        assertThat(vipParts[0]).isEqualTo(m1Parts[0]);
        assertThat(vipParts[1]).isEqualTo(m1Parts[1]);
        assertThat(Integer.parseInt(vipParts[2]) & ~1).isEqualTo(Integer.parseInt(m1Parts[2]) & ~1);
        assertThat(vipParts[3]).isEqualTo("11");
        assertThat(m1Parts[3]).isEqualTo("12");
    }
}
