package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;

class CpChangedDomainsTest {
    private static final String SIGNAL = """
        {"uid":"published-001","publish-time":{"posix":1791158400000,"iso-8601":"2026-10-05T00:00+0000"}}
        """;
    private final ObjectMapper json = new ObjectMapper();
    private final String container = PolicySnapshot.ref("source-1", "domain-1");
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(
        getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream()
        .filter(row -> row.key().equals(key) && !row.gateId().equals("cp_policy_access_rulebase_hits")).toList();

    private PolicySnapshot stored() {
        var metadata = new PolicySnapshot.Metadata("policy-1", "source-1", "MGR-TANGO-01", "CP", container,
            "DOM-TANGO-01", "OBJ-POLICY-01", "2026-10-04T00:00:00Z", "", List.of());
        var cell = new PolicySnapshot.Cell(List.of(), false);
        var hits = new PolicySnapshot.HitCounts(7L, null, null, "mds", "2026-10-04T00:00:00Z", "low", List.of());
        var rule = new PolicySnapshot.Rule("rule-1", "rule-001", 1, "OBJ-RULE-01", true, cell, cell, cell, cell,
            "Accept", "None", "", Map.of(), hits);
        return new PolicySnapshot(metadata, List.of(new PolicySnapshot.Section("layer-1", "OBJ-LAYER-01", "Access", null, List.of(rule))), Map.of());
    }
    private List<String> collect(int maximum, PolicyCollectionRepository.Mode mode, String reply, boolean complete, boolean match) throws Exception {
        var repository = mock(PolicyCollectionRepository.class);
        var signal = CpDomainReuse.signal(json.readTree(SIGNAL));
        if (!match) signal = new CpDomainReuse.Signal("published-002", signal.posix(), signal.iso8601(), signal.publishTime());
        when(repository.previousDomain("source-1", container)).thenReturn(Optional.of(new PolicyCollectionRepository.DomainRun(
            complete, json.writeValueAsString(signal), json.writeValueAsString(List.of(stored())))));
        when(repository.beginDomain(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(repository.inventoryFresh(anyString(), anyString(), anyString(), any())).thenReturn(true);
        List<String> commands = new CopyOnWriteArrayList<>();
        var transport = mock(DeviceTransport.class);
        when(transport.connect(any(), any(), any())).thenAnswer(call -> new ConnectResult.Authenticated(() -> UUID.randomUUID().toString()));
        when(transport.execInteractive(any(), any(), any())).thenAnswer(call -> {
            String command = ((ExecSpec) call.getArgument(1)).command(); commands.add(command);
            if (command.contains("show-domains")) return new ExecResult.Completed("{\"total\":1,\"objects\":[{\"uid\":\"domain-1\",\"name\":\"DOM-TANGO-01\"}]}", 0);
            if (command.contains("show-last-published-session")) return reply == null ? new ExecResult.TimedOut() : new ExecResult.Completed(reply, 0);
            if (command.contains("show-packages")) return new ExecResult.Completed("{\"total\":1,\"from\":1,\"to\":1,\"packages\":[{\"uid\":\"package-1\",\"name\":\"OBJ-POLICY-01\",\"access-layers\":[{\"uid\":\"layer-1\",\"name\":\"OBJ-LAYER-01\"}],\"installation-targets\":[]}]}", 0);
            return new ExecResult.Completed("{\"uid\":\"layer-1\",\"total\":0,\"rulebase\":[],\"objects-dictionary\":[]}", 0);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofMinutes(5), maximum);
        var request = new PolicyCollectionRepository.Request("source-1", "", false, mode, "", 0);
        var result = collector.collect(run, request, () -> true);
        assertEquals(1, result.size()); assertTrue(result.get(0).failures().isEmpty());
        if (commands.stream().noneMatch(c -> c.contains("show-packages"))) {
            assertEquals(stored(), result.get(0));
            assertEquals("2026-10-04T00:00:00Z", result.get(0).sections().get(0).rules().get(0).hitCounts().collectedAt());
        }
        return commands;
    }
    @Test void unchangedDomainsReuseBothCollectorsWithoutPackagesOrRulebaseReads() throws Exception {
        for (int maximum : List.of(1, 4)) {
            var commands = collect(maximum, PolicyCollectionRepository.Mode.CHANGED_ONLY, SIGNAL, true, true);
            assertEquals(2, commands.size());
            assertTrue(commands.stream().noneMatch(c -> c.contains("show-packages") || c.contains("rulebase")));
        }
    }
    @Test void changedMissingMalformedFailedAndGappedSignalsCollectFullyInBothCollectors() throws Exception {
        for (int maximum : List.of(1, 4)) {
            assertFetched(collect(maximum, PolicyCollectionRepository.Mode.CHANGED_ONLY, SIGNAL, true, false));
            assertFetched(collect(maximum, PolicyCollectionRepository.Mode.CHANGED_ONLY, SIGNAL, false, true));
            for (String reply : Arrays.asList("{}", "{\"uid\":\"published-001\",\"publish-time\":{\"posix\":1,\"iso-8601\":\"invalid\"}}", "not-json", null))
                assertFetched(collect(maximum, PolicyCollectionRepository.Mode.CHANGED_ONLY, reply, true, true));
        }
    }
    @Test void fullIgnoresIdenticalStoredSignal() throws Exception {
        for (int maximum : List.of(1, 4)) {
            var commands = collect(maximum, PolicyCollectionRepository.Mode.FULL, SIGNAL, true, true);
            assertFetched(commands);
            assertTrue(commands.stream().noneMatch(c -> c.contains("show-last-published-session")));
        }
    }
    private static void assertFetched(List<String> commands) {
        assertTrue(commands.stream().anyMatch(c -> c.contains("show-packages")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("show-access-rulebase")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("show-nat-rulebase")));
    }
    @Test void gateIsSignedOffAndDomainArgumentsCannotEscapeTheirShellWord() {
        CpPolicyGates.require(gates, CpPolicyGates.LAST_PUBLISHED_SESSION);
        assertThrows(IllegalStateException.class, () -> CpPolicyGates.require(key -> List.of(), CpPolicyGates.LAST_PUBLISHED_SESSION));
        assertEquals("mgmt_cli -r true -d 'DOM'\\''; echo injected; '\\''' -f json show-last-published-session",
            MgmtCliCommands.showLastPublishedSession("DOM'; echo injected; '"));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showLastPublishedSession("DOM\nmalformed"));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showLastPublishedSession(""));
    }
    @Test void signalPreservesOpaqueUidAndRequiresBothTimeFields() throws Exception {
        assertEquals("published-001", CpDomainReuse.signal(json.readTree(SIGNAL)).uid());
        for (String signal : List.of("{}", "{\"uid\":1}", SIGNAL.replace("1791158400000", "null"), SIGNAL.replace("2026-10-05T00:00+0000", "invalid")))
            assertNull(CpDomainReuse.signal(json.readTree(signal)));
    }
    @Test void domainMarkersStayIncompleteUntilAllPackagesFinishAndHitTimeIsPreserved() throws Exception {
        var repository = mock(PolicyCollectionRepository.class);
        var request = new PolicyCollectionRepository.Request("source-1", "", false, PolicyCollectionRepository.Mode.CHANGED_ONLY, "job-1", 7);
        when(repository.previousDomain(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.saveDomain(any(), anyString(), anyString(), anyBoolean(), nullable(String.class), anyString(), anyInt(), nullable(String.class), anyString())).thenReturn(true);
        var reuse = new CpDomainReuse(repository, request);
        reuse.begin(container);
        verify(repository).saveDomain(eq(request), eq(container), eq("COLLECTING"), eq(false), isNull(), eq("[]"), eq(0), isNull(), anyString());
        assertNull(reuse.decide(container, json.readTree(SIGNAL)));
        reuse.planned(container, 1);
        reuse.snapshot(stored());
        verify(repository).saveDomain(eq(request), eq(container), eq("COLLECTED"), eq(true), contains("published-001"),
            contains("rule-001"), eq(1), eq("2026-10-04T00:00:00Z"), anyString());
        reuse.gap(container);
        verify(repository).saveDomain(eq(request), eq(container), eq("COLLECTED"), eq(false), contains("published-001"),
            contains("rule-001"), eq(1), eq("2026-10-04T00:00:00Z"), anyString());
    }
}
