package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.policy.*;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CpObjectInventoryCollectionTest {
    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(
        getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(r -> r.key().equals(key)).toList();
    private final ObjectMapper json = new ObjectMapper();
    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request request = new PolicyCollectionRepository.Request("mds-1", "", false);
    private final List<CpObjectInventory> stored = new ArrayList<>();

    private CheckPointPolicyCollector collector(int sessions, java.util.function.Function<String, ExecResult> answers) throws Exception {
        var sequence = new AtomicInteger();
        when(transport.connect(any(), any(), any())).thenAnswer(call -> {
            String id = "synthetic-session-" + sequence.incrementAndGet();
            TransportSession session = () -> id;
            return new ConnectResult.Authenticated(session);
        });
        when(transport.execInteractive(any(), any(), any())).thenAnswer(call -> answers.apply(((ExecSpec) call.getArgument(1)).command()));
        when(repository.beginDomain(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of());
        doAnswer(call -> { stored.add(json.readValue(call.getArgument(4, String.class), CpObjectInventory.class)); return null; })
            .when(repository).saveInventory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        return new CheckPointPolicyCollector(transport, gates, repository, Duration.ofMinutes(10), sessions);
    }
    private static ExecResult ok(String body) { return new ExecResult.Completed(body, 0); }
    private ExecResult answer(String command) {
        if (command.contains("show-domains")) return ok("{\"total\":1,\"objects\":[{\"uid\":\"domain-1\",\"name\":\"DOM-TANGO-01\"}]}");
        if (command.contains("show-packages")) return ok("""
            {"from":1,"to":1,"total":1,"packages":[{"uid":"pkg-1","name":"OBJ-POLICY-01",
             "access-layers":[{"uid":"layer-1","name":"OBJ-LAYER-01"}],"installation-targets":[]}]}
            """);
        if (command.contains("show-access-rulebase")) {
            String body = """
            {"uid":"layer-1","name":"OBJ-LAYER-01","from":1,"to":1,"total":1,"objects-dictionary":[],
             "rulebase":[{"uid":"rule-1","type":"access-rule","hits":{"value":12,"level":"high",
             "first-date":"2026-10-01T00:00:00Z","last-date":"2026-10-05T00:00:00Z"}}]}
            """;
            if (!command.endsWith("show-hits true")) {
                try {
                    var node = json.readTree(body);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("rulebase").get(0)).remove("hits");
                    body = json.writeValueAsString(node);
                } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
            }
            return ok(body);
        }
        if (command.contains("show-nat-rulebase")) return ok("{\"total\":0,\"rulebase\":[],\"objects-dictionary\":[]}");
        return ok("{\"from\":0,\"to\":0,\"total\":0,\"objects\":[]}");
    }
    private String hosts(int offset) {
        List<String> items = new ArrayList<>();
        for (int i = offset; i < Math.min(offset + 50, 51); i++)
            items.add("{\"uid\":\"host-" + i + "\",\"name\":\"OBJ-HOST-" + i + "\",\"type\":\"host\",\"ipv4-address\":\"192.0.2." + (i + 1) + "\"}");
        return "{\"from\":" + (offset + 1) + ",\"to\":" + Math.min(offset + 50, 51) + ",\"total\":51,\"objects\":[" + String.join(",", items) + "]}";
    }
    @ParameterizedTest @ValueSource(ints = {1, 4})
    void pagesObjectsIsolatesTypeGapAndPersistsHits(int sessions) throws Exception {
        var snapshots = collector(sessions, command -> {
            if (command.contains("show-hosts")) return ok(hosts(command.contains("offset '0'") ? 0 : 50));
            if (command.contains("show-networks")) return ok("{\"code\":\"synthetic-error\",\"message\":\"unsupported\"}");
            if (command.contains("show-gateways-and-servers")) return ok("""
                {"from":1,"to":1,"total":1,"objects":[{"uid":"gateway-1","name":"FW-TANGO-04","type":"simple-gateway",
                 "policy":{"access-policy-name":"OBJ-POLICY-01"},"unapproved-secret":"synthetic-private"}]}
                """);
            if (command.contains("show-unused-objects")) return ok("{\"from\":1,\"to\":1,\"total\":1,\"objects\":[{\"uid\":\"host-0\"}]}");
            return answer(command);
        }).collect(run, request, () -> true);
        assertEquals(22, stored.size());
        var hosts = stored.stream().filter(i -> i.type().equals("hosts")).findFirst().orElseThrow();
        assertEquals(51, hosts.objects().size()); assertEquals(2, hosts.pages());
        assertEquals("host-0", hosts.objects().get(0).uid());
        var gap = stored.stream().filter(i -> i.type().equals("networks")).findFirst().orElseThrow();
        assertEquals("COLLECTION_FAILED", gap.status()); assertTrue(gap.objects().isEmpty());
        var gateways = stored.stream().filter(i -> i.type().equals("gateways-and-servers")).findFirst().orElseThrow();
        assertEquals("OBJ-POLICY-01", gateways.objects().get(0).policyInstallations().get(0).policyName());
        assertNull(gateways.objects().get(0).policyInstallations().get(0).installed());
        assertFalse(json.writeValueAsString(gateways).contains("synthetic-private"));
        assertTrue(snapshots.get(0).failures().isEmpty());
        var hit = snapshots.get(0).sections().get(0).rules().get(0).hitCounts();
        assertEquals(12L, hit.hits()); assertEquals("high", hit.level());
        assertEquals("2026-10-05T00:00:00Z", hit.lastHit());
    }
    @ParameterizedTest @ValueSource(ints = {1, 4})
    void failedHitsAndFiftyPackagesRestartFromZeroWithApprovedFallbacks(int sessions) throws Exception {
        var snapshots = collector(sessions, command -> command.endsWith("show-hits true") || command.contains("show-packages limit 50")
            ? ok("{\"code\":\"synthetic-error\",\"message\":\"unsupported\"}") : answer(command)).collect(run, request, () -> true);
        assertFalse(snapshots.get(0).sections().isEmpty());
        assertNull(snapshots.get(0).sections().get(0).rules().get(0).hitCounts());
        verify(transport).execInteractive(any(), argThat(spec -> spec.command().contains("show-access-rulebase")
            && !spec.command().contains("show-hits") && spec.command().contains("offset '0'")), any());
        verify(transport).execInteractive(any(), argThat(spec -> spec.command().contains("show-packages limit 20 offset '0'")), any());
    }
    @ParameterizedTest @ValueSource(ints = {1, 4})
    void globalSkipsGatewaysAndDisabledInventoriesCannotReadObjects(int sessions) throws Exception {
        collector(sessions, command -> command.contains("show-domains")
            ? ok("{\"total\":1,\"objects\":[{\"uid\":\"domain-1\",\"name\":\"Global\"}]}") : answer(command)).collect(run, request, () -> true);
        verify(transport, never()).execInteractive(any(), argThat(spec -> spec.command().contains("show-gateways-and-servers")), any());
        assertEquals("UNSUPPORTED", stored.stream().filter(i -> i.type().equals("gateways-and-servers")).findFirst().orElseThrow().status());
        reset(transport, repository); stored.clear();
        try {
            System.setProperty("ui2.policy.cp.collect-objects", "false");
            collector(sessions, this::answer).collect(run, request, () -> true);
            verify(transport, times(4)).execInteractive(any(), any(), any());
            assertTrue(stored.stream().allMatch(i -> i.status().equals("UNSUPPORTED")));
        } finally { System.clearProperty("ui2.policy.cp.collect-objects"); }
    }
    @Test void exactGateResolutionForEveryApprovedCommandAndUnsafeParametersRefused() {
        for (int i = 0; i < CpPolicyGates.COMMANDS.size(); i++) {
            final int index = i;
            assertDoesNotThrow(() -> CpPolicyGates.require(gates, index));
        }
        for (String type : CpPolicyGates.OBJECT_TYPES) {
            String expected = CpPolicyGates.COMMANDS.get(CpPolicyGates.OBJECT_BASE + CpPolicyGates.OBJECT_TYPES.indexOf(type))
                .replace("<DOMAIN>", "DOM-TANGO-01").replace("<N>", "50");
            assertEquals(expected, MgmtCliCommands.showPolicyObjects("DOM-TANGO-01", type, 50));
        }
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showPolicyObjects("DOM-TANGO-01", "hosts", -1));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showPolicyObjects("DOM-TANGO-01", "arbitrary", 0));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showPolicyObjects("DOM\nnext", "hosts", 0));
    }
    @Test void parsedInventoryPreservesOpaqueMembersExclusionsAndTimeDefinitions() throws Exception {
        var items = CpObjectInventoryParser.parse("source-1", "domain-1", List.of(json.readTree("""
            {"uid":"001","type":"group-with-exclusion","name":"OBJ-GROUP-01","members":[{"uid":"002"}],
             "include":{"uid":"003"},"except":{"uid":"004"},"unapproved-field":"synthetic-private"}
            """), json.readTree("""
            {"uid":"time-1","type":"time","name":"OBJ-TIME-01","start-now":true,"end-never":true}
            """)));
        assertEquals("001", items.get(0).uid()); assertEquals(List.of("002"), items.get(0).members());
        assertTrue(items.get(0).values().contains("include: 003")); assertTrue(items.get(0).values().contains("except: 004"));
        assertEquals("one-time", items.get(1).schedule().kind());
        assertTrue(items.get(1).values().contains("end-never: true"));
        assertFalse(json.writeValueAsString(items).contains("synthetic-private"));
    }
    @Test void objectPagesRefuseDuplicatesChangingTotalGapsAndIncompleteLastPage() throws Exception {
        var pages = new CheckPointPolicyCollector.PackagePages(50, "objects");
        assertFalse(pages.add(json.readTree(hosts(0)), 0));
        assertThrows(IllegalStateException.class, () -> pages.add(json.readTree(hosts(50).replace("host-50", "host-0")), 50));
        for (String defect : List.of(hosts(0).replace("\"from\":1", "\"from\":2"), hosts(0).replace("\"to\":50", "\"to\":49")))
            assertThrows(IllegalStateException.class, () -> new CheckPointPolicyCollector.PackagePages(50, "objects").add(json.readTree(defect), 0));
        var changing = new CheckPointPolicyCollector.PackagePages(50, "objects");
        changing.add(json.readTree(hosts(0)), 0);
        assertThrows(IllegalStateException.class, () -> changing.add(json.readTree(hosts(50).replace("\"total\":51", "\"total\":52")), 50));
    }
}
