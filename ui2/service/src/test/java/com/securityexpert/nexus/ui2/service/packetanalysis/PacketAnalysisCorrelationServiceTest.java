package com.securityexpert.nexus.ui2.service.packetanalysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseQueries;
import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseRunner;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Py;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Topology;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.RunRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PacketAnalysisCorrelationService} (PO decision record 2026-10-15 §7 phase 4; plan step S18).
 *
 * <p>Follows the negative-first test design law:
 * <ol>
 *   <li>No inventory data -> {@code NOT_EVALUABLE} across hops and flows.</li>
 *   <li>Two interfaces sharing the same subnet -> {@code AMBIGUOUS} with all candidates listed (never picks first).</li>
 *   <li>OUI-only MAC similarity -> {@code NO_MATCH} with explicit reason.</li>
 *   <li>Stale or incomplete policy snapshot -> {@code NOT_EVALUABLE} with explicit {@code policy_stale} /
 *       {@code policy_incomplete} flags.</li>
 *   <li>Positive IPv4 + IPv6 correlation with confirmed NAT 5-tuple rule matching in first-match order and
 *       persistence of {@code nexus_correlation} into {@code project.duckdb} and {@code report.json}.</li>
 * </ol>
 */
class PacketAnalysisCorrelationServiceTest {

    private static final String CASE_ID = "11111111-2222-4333-8444-555555555555";
    private static final String JOB_ID = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";
    private static final Instant EVAL_NOW = Instant.parse("2026-10-16T06:00:00Z");

    @TempDir
    Path root;

    private PacketAnalysisRepository caseRepository;
    private PacketCaptureStore captureStore;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        caseRepository = mock(PacketAnalysisRepository.class);
        captureStore = new PacketCaptureStore(root, 64 * 1024, 1024 * 1024);
        RunRow completedRun = new RunRow(
                JOB_ID, CASE_ID, "COMPLETED", "synthetic-operator",
                EVAL_NOW.minusSeconds(60), EVAL_NOW.minusSeconds(55), EVAL_NOW.minusSeconds(10),
                "java-engine-1", "{\"verdict\":\"ok\"}", null);
        when(caseRepository.runs(CASE_ID)).thenReturn(List.of(completedRun));
    }

    // ---- 1. Negative: no inventory -> NOT_EVALUABLE --------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void noInventoryDataProducesNotEvaluableForHopsAndFlows() throws Exception {
        seedCaseWithIpv4AndIpv6Flows(Map.of(), Map.of());

        PacketAnalysisCorrelationService service = new PacketAnalysisCorrelationService(
                caseRepository,
                captureStore,
                List::of,
                ids -> List.of(),
                List::of,
                () -> EVAL_NOW);

        Map<String, Object> result = service.correlateAndStore(CASE_ID);
        assertEquals("NOT_EVALUABLE", result.get("inventory_status"));
        assertTrue(String.valueOf(result.get("inventory_reason")).contains("No device inventory"));

        List<Map<String, Object>> hops = (List<Map<String, Object>>) result.get("hops");
        assertEquals(2, hops.size());
        for (Map<String, Object> hop : hops) {
            assertEquals("NOT_EVALUABLE", hop.get("status"));
            assertNull(hop.get("device_id"));
            assertNull(hop.get("configured_inventory_says"));
            assertTrue(((List<?>) hop.get("candidates")).isEmpty());
            assertNotNull(hop.get("capture_proves"));
        }

        List<Map<String, Object>> flows = (List<Map<String, Object>>) result.get("flows");
        assertFalse(flows.isEmpty());
        for (Map<String, Object> flow : flows) {
            assertEquals("NOT_EVALUABLE", flow.get("status"));
            assertNull(flow.get("rule_id"));
            assertNull(flow.get("configured_policy_says"));
            assertNotNull(flow.get("capture_proves"));
        }
    }

    // ---- 2. Negative: two interfaces same subnet -> AMBIGUOUS ----------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void twoInterfacesSharingSameSubnetProduceAmbiguousWithAllCandidatesListed() throws Exception {
        seedCaseWithIpv4AndIpv6Flows(Map.of(), Map.of());

        DeviceSummaryRecord fwTango = device("dev-tango-04", "FW-TANGO-04", "palo_alto");
        DeviceSummaryRecord fwJuliet = device("dev-juliet-06", "FW-JULIET-06", "palo_alto");

        InventoryInterface tangoEth1 = new InventoryInterface(
                "if-tango-eth1", "ethernet1/1", Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("addr-tango-1", "192.0.2.1/24", "ipv4", "member")),
                Optional.empty(), Optional.of("00:53:00:10:20:01"), Optional.of("zone-trust-a"));
        InventoryInterface julietEth1 = new InventoryInterface(
                "if-juliet-eth1", "ethernet1/2", Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("addr-juliet-1", "192.0.2.2/24", "ipv4", "member")),
                Optional.empty(), Optional.of("00:53:00:10:20:02"), Optional.of("zone-trust-b"));

        InventoryRun runTango = new InventoryRun(
                "run-tango", "dev-tango-04", "job-tango", EVAL_NOW.minusSeconds(300), 1,
                List.of(new InventoryContext("physical", List.of(tangoEth1), List.of())));
        InventoryRun runJuliet = new InventoryRun(
                "run-juliet", "dev-juliet-06", "job-juliet", EVAL_NOW.minusSeconds(300), 1,
                List.of(new InventoryContext("physical", List.of(julietEth1), List.of())));

        PacketAnalysisCorrelationService service = new PacketAnalysisCorrelationService(
                caseRepository,
                captureStore,
                () -> List.of(fwTango, fwJuliet),
                ids -> List.of(runTango, runJuliet),
                List::of,
                () -> EVAL_NOW);

        Map<String, Object> result = service.correlateAndStore(CASE_ID);
        List<Map<String, Object>> hops = (List<Map<String, Object>>) result.get("hops");
        Map<String, Object> hopP1 = hops.stream()
                .filter(h -> "p1".equals(h.get("point_id")))
                .findFirst()
                .orElseThrow();

        assertEquals("AMBIGUOUS", hopP1.get("status"));
        assertEquals(List.of("interface_subnet_equality"), hopP1.get("evidence"));
        assertNull(hopP1.get("device_id"), "AMBIGUOUS hop must never guess a single matched device");

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) hopP1.get("candidates");
        assertEquals(2, candidates.size(), "Both candidate interfaces on 192.0.2.0/24 must be listed");
        List<String> candidateDeviceIds = candidates.stream()
                .map(c -> String.valueOf(c.get("device_id")))
                .toList();
        assertTrue(candidateDeviceIds.contains("dev-tango-04"));
        assertTrue(candidateDeviceIds.contains("dev-juliet-06"));

        List<Map<String, Object>> flows = (List<Map<String, Object>>) result.get("flows");
        Map<String, Object> ipv4Flow = flows.stream()
                .filter(f -> String.valueOf(f.get("flow")).contains("192.0.2.10"))
                .findFirst()
                .orElseThrow();
        assertEquals("AMBIGUOUS", ipv4Flow.get("status"));
    }

    // ---- 3. Negative: OUI-only MAC similarity is NOT a match -----------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void ouiOnlyMacSimilarityIsRejectedAndDoesNotMatch() throws Exception {
        // Point p1 has an OUI-only MAC token and a full MAC sharing only the 3-octet OUI prefix 00:53:00
        // while the inventory interface is on an unrelated subnet (203.0.113.1/24) with MAC 00:53:00:aa:bb:01.
        seedCaseWithIpv4AndIpv6Flows(
                Map.of(
                        "p1", Map.of("macs", List.of("00:53:00:**:**:**", "00:53:00:ff:ee:99")),
                        "p2", Map.of("macs", List.of("00:53:00:aa:bb:01"))),
                Map.of());

        DeviceSummaryRecord fwBravo = device("dev-bravo-02", "FW-BRAVO-02", "checkpoint");
        InventoryInterface bravoIf = new InventoryInterface(
                "if-bravo-eth0", "eth0", Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("addr-bravo-1", "203.0.113.1/24", "ipv4", "member")),
                Optional.empty(), Optional.of("00:53:00:aa:bb:01"), Optional.of("external"));
        InventoryRun runBravo = new InventoryRun(
                "run-bravo", "dev-bravo-02", "job-bravo", EVAL_NOW.minusSeconds(120), 1,
                List.of(new InventoryContext("physical", List.of(bravoIf), List.of())));

        PacketAnalysisCorrelationService service = new PacketAnalysisCorrelationService(
                caseRepository,
                captureStore,
                () -> List.of(fwBravo),
                ids -> List.of(runBravo),
                List::of,
                () -> EVAL_NOW);

        Map<String, Object> result = service.correlateAndStore(CASE_ID);
        List<Map<String, Object>> hops = (List<Map<String, Object>>) result.get("hops");

        Map<String, Object> hopP1 = hops.stream()
                .filter(h -> "p1".equals(h.get("point_id")))
                .findFirst()
                .orElseThrow();
        assertEquals("NO_MATCH", hopP1.get("status"));
        assertNull(hopP1.get("device_id"));
        assertNull(hopP1.get("configured_inventory_says"));
        assertTrue(String.valueOf(hopP1.get("reason")).contains("OUI-only MAC prefix similarity is insufficient evidence"));

        // Point p2 has exact 6-octet MAC 00:53:00:aa:bb:01 -> must MATCH with mac_equality
        Map<String, Object> hopP2 = hops.stream()
                .filter(h -> "p2".equals(h.get("point_id")))
                .findFirst()
                .orElseThrow();
        assertEquals("MATCH", hopP2.get("status"));
        assertEquals(List.of("mac_equality"), hopP2.get("evidence"));
        assertEquals("dev-bravo-02", hopP2.get("device_id"));
        assertEquals("if-bravo-eth0", hopP2.get("interface_id"));
    }

    // ---- 4. Negative: stale or incomplete policy -> NOT_EVALUABLE ------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void staleOrIncompletePolicyProducesNotEvaluableWithExplicitFlags() throws Exception {
        seedCaseWithIpv4AndIpv6Flows(Map.of(), Map.of());

        DeviceSummaryRecord fwTango = device("dev-tango-04", "FW-TANGO-04", "palo_alto");
        InventoryInterface tangoIn = new InventoryInterface(
                "if-tango-in", "ethernet1/1", Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("addr-in", "192.0.2.1/24", "ipv4", "member")),
                Optional.empty(), Optional.of("00:53:00:11:22:01"), Optional.of("trust-zone"));
        InventoryInterface tangoOut = new InventoryInterface(
                "if-tango-out", "ethernet1/2", Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("addr-out", "198.51.100.1/24", "ipv4", "member")),
                Optional.empty(), Optional.of("00:53:00:11:22:02"), Optional.of("untrust-zone"));
        InventoryRun runTango = new InventoryRun(
                "run-tango", "dev-tango-04", "job-tango", EVAL_NOW.minusSeconds(120), 1,
                List.of(new InventoryContext("physical", List.of(tangoIn, tangoOut), List.of())));

        // 4a: Stale policy (collected 10 days before EVAL_NOW, threshold is 7 days)
        String staleTime = EVAL_NOW.minusSeconds(10 * 86400L).toString();
        PolicySnapshot stalePolicy = buildPolicySnapshot("pol-stale-1", "dev-tango-04", "FW-TANGO-04", staleTime, false);

        PacketAnalysisCorrelationService staleService = new PacketAnalysisCorrelationService(
                caseRepository,
                captureStore,
                () -> List.of(fwTango),
                ids -> List.of(runTango),
                () -> List.of(stalePolicy),
                () -> EVAL_NOW);

        Map<String, Object> staleResult = staleService.correlateAndStore(CASE_ID);
        List<Map<String, Object>> staleFlows = (List<Map<String, Object>>) staleResult.get("flows");
        Map<String, Object> staleIpv4Flow = staleFlows.stream()
                .filter(f -> String.valueOf(f.get("flow")).contains("192.0.2.10"))
                .findFirst()
                .orElseThrow();
        assertEquals("NOT_EVALUABLE", staleIpv4Flow.get("status"));
        assertEquals(true, staleIpv4Flow.get("policy_stale"));
        assertEquals("STALE", staleIpv4Flow.get("policy_status"));
        assertNotNull(staleIpv4Flow.get("configured_policy_says"), "Candidate rule is still reported with stale warning");
        assertTrue(String.valueOf(staleIpv4Flow.get("reason")).contains("stale"));

        // 4b: Incomplete policy (collection failures recorded)
        PolicySnapshot incompletePolicy = buildPolicySnapshot(
                "pol-inc-1", "dev-tango-04", "FW-TANGO-04", EVAL_NOW.minusSeconds(300).toString(), true);

        PacketAnalysisCorrelationService incompleteService = new PacketAnalysisCorrelationService(
                caseRepository,
                captureStore,
                () -> List.of(fwTango),
                ids -> List.of(runTango),
                () -> List.of(incompletePolicy),
                () -> EVAL_NOW);

        Map<String, Object> incResult = incompleteService.correlateAndStore(CASE_ID);
        List<Map<String, Object>> incFlows = (List<Map<String, Object>>) incResult.get("flows");
        Map<String, Object> incIpv4Flow = incFlows.stream()
                .filter(f -> String.valueOf(f.get("flow")).contains("192.0.2.10"))
                .findFirst()
                .orElseThrow();
        assertEquals("NOT_EVALUABLE", incIpv4Flow.get("status"));
        assertEquals(true, incIpv4Flow.get("policy_incomplete"));
        assertEquals("INCOMPLETE", incIpv4Flow.get("policy_status"));
    }

    // ---- 5. Positive: IPv4 + IPv6 hops & rules with confirmed NAT & persistence ----------------------------

    @Test
    @SuppressWarnings("unchecked")
    void ipv4AndIpv6HopsAndFirstMatchRuleWithConfirmedNatMappingCorrelateAndPersistToCaseResult() throws Exception {
        // Confirmed NAT mapping: pre-NAT 192.0.2.10:40000 -> 198.51.100.20:8080 translated to
        // post-NAT 198.51.100.10:50000 -> 198.51.100.20:8080
        Map<String, Object> natMapping = new LinkedHashMap<>();
        natMapping.put("point_a", "p1");
        natMapping.put("point_b", "p2");
        natMapping.put("tuple_a", "[\"TCP\",\"192.0.2.10\",40000,\"198.51.100.20\",8080]");
        natMapping.put("tuple_b", "[\"TCP\",\"198.51.100.10\",50000,\"198.51.100.20\",8080]");

        seedCaseWithIpv4AndIpv6Flows(
                Map.of(
                        "p1", Map.of("address", "192.0.2.1", "macs", List.of("00:53:00:11:22:01")),
                        "p2", Map.of("address", "198.51.100.1", "macs", List.of("00:53:00:11:22:02"))),
                Map.of("nat_mappings", List.of(natMapping)));

        // Distractor device FW-BRAVO-02 listed first so a naive "always pick first firewall" implementation fails
        DeviceSummaryRecord fwBravo = device("dev-bravo-02", "FW-BRAVO-02", "checkpoint");
        DeviceSummaryRecord fwTango = device("dev-tango-04", "FW-TANGO-04", "palo_alto");

        InventoryInterface bravoIf = new InventoryInterface(
                "if-bravo-1", "eth0", Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("addr-bravo-1", "203.0.113.1/24", "ipv4", "member")),
                Optional.empty(), Optional.of("00:53:00:99:99:01"), Optional.of("dmz-other"));

        InventoryInterface tangoIn = new InventoryInterface(
                "if-tango-in", "ethernet1/1", Optional.empty(), "physical", "up",
                List.of(
                        new InventoryAddress("addr-tango-v4-in", "192.0.2.1/24", "ipv4", "member"),
                        new InventoryAddress("addr-tango-v6-in", "2001:db8:1::1/64", "ipv6", "member")),
                Optional.empty(), Optional.of("00:53:00:11:22:01"), Optional.of("trust-zone"));
        InventoryInterface tangoOut = new InventoryInterface(
                "if-tango-out", "ethernet1/2", Optional.empty(), "physical", "up",
                List.of(
                        new InventoryAddress("addr-tango-v4-out", "198.51.100.1/24", "ipv4", "member"),
                        new InventoryAddress("addr-tango-v6-out", "2001:db8:2::1/64", "ipv6", "member")),
                Optional.empty(), Optional.of("00:53:00:11:22:02"), Optional.of("untrust-zone"));

        InventoryRun runBravo = new InventoryRun(
                "run-bravo", "dev-bravo-02", "job-bravo", EVAL_NOW.minusSeconds(120), 1,
                List.of(new InventoryContext("physical", List.of(bravoIf), List.of())));
        InventoryRun runTango = new InventoryRun(
                "run-tango", "dev-tango-04", "job-tango", EVAL_NOW.minusSeconds(120), 1,
                List.of(new InventoryContext("physical", List.of(tangoIn, tangoOut), List.of())));

        PolicySnapshot tangoPolicy = buildPolicySnapshot(
                "pol-tango-1", "dev-tango-04", "FW-TANGO-04", EVAL_NOW.minusSeconds(600).toString(), false);

        // Also seed a completed run report.json on disk to verify onAnalysisCompleted updates it
        Path reportFile = captureStore.reportFile(CASE_ID, JOB_ID);
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, "{\"verdict\":\"No supported network loss in the selected window\"}", StandardCharsets.UTF_8);

        PacketAnalysisCorrelationService service = new PacketAnalysisCorrelationService(
                caseRepository,
                captureStore,
                () -> List.of(fwBravo, fwTango),
                ids -> List.of(runBravo, runTango),
                () -> List.of(tangoPolicy),
                () -> EVAL_NOW);

        Map<String, Object> result = service.onAnalysisCompleted(CASE_ID);
        assertEquals("AVAILABLE", result.get("inventory_status"));

        // Verify hops correlated to FW-TANGO-04 (and NOT distractor FW-BRAVO-02)
        List<Map<String, Object>> hops = (List<Map<String, Object>>) result.get("hops");
        Map<String, Object> hopP1 = hops.stream().filter(h -> "p1".equals(h.get("point_id"))).findFirst().orElseThrow();
        assertEquals("MATCH", hopP1.get("status"));
        assertEquals("dev-tango-04", hopP1.get("device_id"));
        assertEquals("FW-TANGO-04", hopP1.get("hostname"));
        assertEquals("if-tango-in", hopP1.get("interface_id"));
        assertEquals("ethernet1/1", hopP1.get("interface_name"));
        assertEquals("trust-zone", hopP1.get("zone"));
        assertNotNull(hopP1.get("capture_proves"));
        assertNotNull(hopP1.get("configured_inventory_says"));

        Map<String, Object> hopP2 = hops.stream().filter(h -> "p2".equals(h.get("point_id"))).findFirst().orElseThrow();
        assertEquals("MATCH", hopP2.get("status"));
        assertEquals("dev-tango-04", hopP2.get("device_id"));
        assertEquals("if-tango-out", hopP2.get("interface_id"));
        assertEquals("untrust-zone", hopP2.get("zone"));

        // Verify IPv4 flow matched Rule 2 (post-NAT 198.51.100.10 -> 198.51.100.20:8080/TCP) in first-match order
        List<Map<String, Object>> flows = (List<Map<String, Object>>) result.get("flows");
        Map<String, Object> v4Flow = flows.stream()
                .filter(f -> String.valueOf(f.get("flow")).contains("192.0.2.10"))
                .findFirst()
                .orElseThrow();
        assertEquals("MATCH", v4Flow.get("status"));
        assertEquals(List.of("rule_5tuple_containment"), v4Flow.get("evidence"));
        assertEquals(true, v4Flow.get("nat_confirmed"));
        assertEquals("198.51.100.10", v4Flow.get("post_nat_src"));
        assertEquals("trust-zone", v4Flow.get("source_zone"));
        assertEquals("untrust-zone", v4Flow.get("destination_zone"));
        assertEquals("rule-postnat-web", v4Flow.get("rule_id"));
        assertEquals("Allow-PostNAT-Web", v4Flow.get("rule_name"));
        assertEquals(2, v4Flow.get("rule_number"));
        assertEquals("allow", v4Flow.get("action"));
        Map<String, Object> v4PolicySays = (Map<String, Object>) v4Flow.get("configured_policy_says");
        assertNotNull(v4PolicySays);
        assertEquals("rule-postnat-web", v4PolicySays.get("rule_id"));
        assertEquals(true, v4PolicySays.get("evaluated_post_nat"));

        // Verify IPv6 flow matched Rule 4 ([2001:db8:1::10]:53000 -> [2001:db8:2::53]:53/UDP)
        Map<String, Object> v6Flow = flows.stream()
                .filter(f -> String.valueOf(f.get("flow")).contains("2001:db8:1::10"))
                .findFirst()
                .orElseThrow();
        assertEquals("MATCH", v6Flow.get("status"));
        assertEquals(List.of("rule_5tuple_containment"), v6Flow.get("evidence"));
        assertEquals("rule-ipv6-dns", v6Flow.get("rule_id"));
        assertEquals("Allow-IPv6-DNS", v6Flow.get("rule_name"));

        // Verify persistence into project.duckdb settings ('nexus_correlation') and report.json
        Path dbPath = PacketAnalysisPaths.projectDuckDb(root, CASE_ID);
        try (var conn = Engine.openReadOnly(dbPath);
             var db = new Db(conn)) {
            Object storedCorr = CaseQueries.settingGet(db, "nexus_correlation");
            assertInstanceOf(Map.class, storedCorr);
        }

        Map<String, Object> diskReport = json.readValue(
                reportFile.toFile(), new TypeReference<Map<String, Object>>() { });
        assertNotNull(diskReport.get("nexus_correlation"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static DeviceSummaryRecord device(String id, String hostname, String vendor) {
        return new DeviceSummaryRecord(
                id, "firewall", vendor, DeviceEnrollmentState.ENROLLED,
                Optional.of(hostname), Optional.of("PA-5220"), Optional.of("11.1.0"),
                Optional.empty(), Optional.empty(), Optional.of("COMPLETED"),
                Optional.of("inventory_collect"), Optional.empty(), Optional.empty(),
                Optional.of("192.0.2.254"), Optional.empty(), false);
    }

    private static PolicySnapshot buildPolicySnapshot(
            String policyId, String deviceId, String deviceName, String collectedAt, boolean withFailures) {
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-postnat-src", new PolicyObject(
                "obj-postnat-src", "Net-PostNAT-198-51-100", "network",
                List.of(), List.of("198.51.100.0/24"), "ok"));
        objects.put("obj-web-dst", new PolicyObject(
                "obj-web-dst", "Host-Web-DMZ", "host",
                List.of(), List.of("198.51.100.20/32"), "ok"));
        objects.put("obj-svc-8080", new PolicyObject(
                "obj-svc-8080", "Service-TCP-8080", "service-tcp",
                List.of(), List.of("tcp/8080"), "ok"));
        objects.put("obj-v6-src", new PolicyObject(
                "obj-v6-src", "Net-IPv6-Trust", "network",
                List.of(), List.of("2001:db8:1::/64"), "ok"));
        objects.put("obj-v6-dns-dst", new PolicyObject(
                "obj-v6-dns-dst", "Host-IPv6-DNS", "host",
                List.of(), List.of("2001:db8:2::53/128"), "ok"));
        objects.put("obj-svc-dns", new PolicyObject(
                "obj-svc-dns", "Service-UDP-53", "service-udp",
                List.of(), List.of("udp/53"), "ok"));

        // Rule 1: disabled catch-all (must be skipped)
        Rule r1Disabled = new Rule(
                "rule-disabled-1", "uuid-1", 1, "Disabled-Temp-Allow", false,
                new Cell(List.of("any"), false),
                new Cell(List.of("any"), false),
                new Cell(List.of("any"), false),
                new Cell(List.of("any"), false),
                "allow", "log", "", Map.of());

        // Rule 2: matches post-NAT source 198.51.100.0/24 -> 198.51.100.20:8080/TCP
        Rule r2PostNatWeb = new Rule(
                "rule-postnat-web", "uuid-2", 2, "Allow-PostNAT-Web", true,
                new Cell(List.of("obj-postnat-src"), false),
                new Cell(List.of("obj-web-dst"), false),
                new Cell(List.of("obj-svc-8080"), false),
                new Cell(List.of("web-browsing"), false),
                "allow", "log", "",
                Map.of(
                        "from", List.of("trust-zone"),
                        "to", List.of("untrust-zone")));

        // Rule 3: later rule that also contains the 5-tuple (proves first-match order picks Rule 2)
        Rule r3Shadowed = new Rule(
                "rule-shadowed-deny", "uuid-3", 3, "Shadowed-Deny-Web", true,
                new Cell(List.of("any"), false),
                new Cell(List.of("obj-web-dst"), false),
                new Cell(List.of("obj-svc-8080"), false),
                new Cell(List.of("any"), false),
                "deny", "log", "", Map.of());

        // Rule 4: IPv6 DNS rule
        Rule r4Ipv6Dns = new Rule(
                "rule-ipv6-dns", "uuid-4", 4, "Allow-IPv6-DNS", true,
                new Cell(List.of("obj-v6-src"), false),
                new Cell(List.of("obj-v6-dns-dst"), false),
                new Cell(List.of("obj-svc-dns"), false),
                new Cell(List.of("dns"), false),
                "allow", "log", "", Map.of());

        Section sec = new Section(
                "sec-security", "Security Rules", "security", null,
                List.of(r1Disabled, r2PostNatWeb, r3Shadowed, r4Ipv6Dns));

        Metadata meta = new Metadata(
                policyId, "src-1", "Panorama-Synthetic", "palo_alto",
                "dg-core", "Core-DG", "Core-Security-Policy", collectedAt,
                "cas-ref-1", List.of(new Target(deviceId, deviceName, "vsys1", "in_sync")));

        List<CollectionFailure> failures = withFailures
                ? List.of(new CollectionFailure("layer-post", "timeout reading post-rulebase"))
                : List.of();
        return new PolicySnapshot(meta, List.of(sec), objects, failures);
    }

    @SuppressWarnings("unchecked")
    private void seedCaseWithIpv4AndIpv6Flows(
            Map<String, Map<String, Object>> pointExtras,
            Map<String, Object> topologyExtras) throws Exception {
        Path caseDir = PacketAnalysisPaths.caseDir(root, CASE_ID);
        var projectStore = ProjectStore.open(caseDir, ProjectStore.Settings.defaults(null));
        String invJson = "{\"packet_count\":2,\"records_read\":2,\"prefilter_excluded\":0,\"prefilter_skipped\":false}";
        try (var db = projectStore.connect()) {
            db.execute("INSERT INTO captures (id, path, name, state, checkpoint, inventory, error) VALUES (?, ?, ?, 'ready', 2, ?, NULL)",
                    "cap1", "/synthetic/cap-inside.pcap", "cap-inside.pcap", invJson);
            db.execute("INSERT INTO captures (id, path, name, state, checkpoint, inventory, error) VALUES (?, ?, ?, 'ready', 2, ?, NULL)",
                    "cap2", "/synthetic/cap-outside.pcap", "cap-outside.pcap", invJson);

            String insertPkt = "INSERT INTO packets (capture_id, frame, ts, iface, src, dst, sport, dport, proto, ipid, "
                    + "seq, ack, flags, length, wirelen, caplen, prefix, payload_hash, signature, tuple_key, reverse_tuple, "
                    + "stream, ttl, dscp, mss, \"window\", retrans, fast_retrans, spurious, out_of_order, acked_unseen, "
                    + "zero_window, rtt, unsupported, frame_hash, icmp_id, icmp_seq, icmp_type, dns_id, dns_response, vendor, path_fields) "
                    + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, 'TCP', ?, ?, ?, ?, ?, ?, ?, '', '', ?, ?, ?, 0, ?, 0, 1460, 65535, "
                    + "false, false, false, false, false, false, NULL, '', ?, NULL, NULL, NULL, NULL, false, '{}', '{\"transport_index\":1,\"dns_index\":1}')";

            String v4Fwd = "[\"TCP\",\"192.0.2.10\",40000,\"198.51.100.20\",8080]";
            String v4Rev = "[\"TCP\",\"198.51.100.20\",8080,\"192.0.2.10\",40000]";

            // Cap1 (inside hop p1)
            db.execute(insertPkt, "cap1", 1L, 1700000000.100, "192.0.2.10", "198.51.100.20", 40000L, 8080L,
                    101L, 1000L, 0L, 2L, 0L, 54L, 54L, "sig-v4-1", v4Fwd, v4Rev, 64L, "fh-1");
            db.execute(insertPkt, "cap1", 2L, 1700000000.120, "198.51.100.20", "192.0.2.10", 8080L, 40000L,
                    201L, 2000L, 1001L, 18L, 0L, 54L, 54L, "sig-v4-2", v4Rev, v4Fwd, 63L, "fh-2");

            // Cap2 (outside hop p2)
            db.execute(insertPkt, "cap2", 1L, 1700000000.105, "192.0.2.10", "198.51.100.20", 40000L, 8080L,
                    101L, 1000L, 0L, 2L, 0L, 54L, 54L, "sig-v4-1", v4Fwd, v4Rev, 63L, "fh-5");
            db.execute(insertPkt, "cap2", 2L, 1700000000.115, "198.51.100.20", "192.0.2.10", 8080L, 40000L,
                    201L, 2000L, 1001L, 18L, 0L, 54L, 54L, "sig-v4-2", v4Rev, v4Fwd, 64L, "fh-6");
        }

        Topology top = new Topology();
        top.points.add(Point.of("p1", "Inside Capture", "cap-inside.pcap", "cap1", Py.i(0), Py.i(0)));
        top.points.add(Point.of("p2", "Outside Capture", "cap-outside.pcap", "cap2", Py.i(240), Py.i(0)));
        top.forward = List.of("p1", "p2");
        top.reverse = List.of("p2", "p1");
        top.clientCidrs = List.of("192.0.2.0/24", "2001:db8:1::/64");

        Map<String, Object> dumped = new LinkedHashMap<>(top.dump());
        CaseRunner.runEngineOnTopology(projectStore.path(), dumped);

        if (dumped.get("points") instanceof List<?> pts) {
            List<Map<String, Object>> updatedPts = pts.stream().map(item -> {
                Map<String, Object> pm = new LinkedHashMap<>((Map<String, Object>) item);
                String pid = String.valueOf(pm.get("id"));
                if (pointExtras.containsKey(pid)) {
                    pm.putAll(pointExtras.get(pid));
                }
                return pm;
            }).toList();
            dumped.put("points", updatedPts);
        }
        dumped.put("flows", List.of("[\"UDP\",\"2001:db8:1::10\",53000,\"2001:db8:2::53\",53]"));
        dumped.putAll(topologyExtras);

        // Save enriched topology JSON (with any custom MACs / confirmed nat_mappings / IPv6 flow) so correlation reads it
        try (var conn = Engine.open(PacketAnalysisPaths.projectDuckDb(root, CASE_ID));
             var db = new Db(conn)) {
            db.exec("INSERT OR REPLACE INTO settings VALUES ('topology', ?)", PyJsonWriter.dumps(dumped, false));
        }
    }
}
