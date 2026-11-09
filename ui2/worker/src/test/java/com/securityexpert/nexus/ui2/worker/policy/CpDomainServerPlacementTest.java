package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CpDomainServerPlacementTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LISTING = """
        {"total":3,"objects":[
         {"uid":"00000000-0000-0000-0000-000000000001","name":"DOM-SYNTH-01","servers":[
           {"name":"DMS-SYNTH-01a","active":true,"ipv4-address":"192.0.2.11","multi-domain-server":"MDS-SYNTH-01","type":"management server"},
           {"name":"DMS-SYNTH-01b","active":false,"ipv4-address":"192.0.2.12","multi-domain-server":"MDS-SYNTH-02","type":"management server"},
           {"name":"LOG-SYNTH-01","active":true,"multi-domain-server":"MDS-SYNTH-01","type":"log server"}]},
         {"uid":"00000000-0000-0000-0000-000000000002","name":"DOM-SYNTH-02","servers":[
           {"name":"DMS-SYNTH-02a","active":"yes","multi-domain-server":"MDS-SYNTH-01","type":"management server"},
           {"name":"DMS-SYNTH-02b","active":false,"type":"management server"},
           {"active":false,"multi-domain-server":"MDS-SYNTH-02","type":"management server"},
           "odd"]},
         {"uid":"00000000-0000-0000-0000-000000000003","name":"DOM-SYNTH-03"}]}
        """;

    @Test void keepsManagementServersOnlyAndCountsSkippedEntries() throws Exception {
        var result = CpDomainServerPlacement.parse(JSON.readTree(LISTING));
        assertEquals(2, result.domains()); assertEquals(2, result.servers());
        assertEquals(1, result.active()); assertEquals(3, result.skipped());
        assertEquals(new CpDomainServerPlacement.Row("00000000-0000-0000-0000-000000000001", "DOM-SYNTH-01",
            "DMS-SYNTH-01a", "MDS-SYNTH-01", true), result.rows().get(0));
        assertEquals("MDS-SYNTH-02", result.rows().get(1).multiDomainServer());
        assertFalse(result.rows().get(1).active());
    }
    @Test void oddShapesNeverThrow() throws Exception {
        for (String body : List.of("null", "[]", "{}", "{\"objects\":{}}", "{\"objects\":[1,null,{\"servers\":5}]}")) {
            var result = CpDomainServerPlacement.parse(JSON.readTree(body));
            assertTrue(result.rows().isEmpty(), body);
        }
        assertTrue(CpDomainServerPlacement.parse(null).rows().isEmpty());
    }
    @Test void aRepositoryFailureNeverEscapesRecord() throws Exception {
        var repository = mock(PolicyCollectionRepository.class);
        doThrow(new IllegalStateException("synthetic")).when(repository).replaceDomainServerPlacement(anyString(), any());
        assertDoesNotThrow(() -> CpDomainServerPlacement.record(repository, "mds-1", JSON.readTree(LISTING)));
        verify(repository).replaceDomainServerPlacement(eq("mds-1"), argThat(rows -> rows.size() == 2));
    }

    @Test void aReplyWithoutAnyServerKeepsThePreviousPlacement() throws Exception {
        var repository = mock(PolicyCollectionRepository.class);
        CpDomainServerPlacement.record(repository, "mds-1", JSON.readTree(
            "{\"objects\":[{\"uid\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"DOM-SYNTH-01\"}]}"));
        verify(repository, never()).replaceDomainServerPlacement(anyString(), any());
    }

    // Both collection paths replace the placement from the show-domains reply and survive a failing write.
    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(
        getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(r -> r.key().equals(key)).toList();
    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request request = new PolicyCollectionRepository.Request("mds-1", "", false, PolicyCollectionRepository.Mode.FULL, "", 0);

    private CheckPointPolicyCollector collector(int sessions) throws Exception {
        var sequence = new AtomicInteger();
        when(transport.connect(any(), any(), any())).thenAnswer(call -> {
            String id = "synthetic-session-" + sequence.incrementAndGet();
            TransportSession session = () -> id;
            return new ConnectResult.Authenticated(session);
        });
        when(transport.execInteractive(any(), any(), any())).thenAnswer(call -> {
            String command = ((ExecSpec) call.getArgument(1)).command();
            if (command.contains("show-domains")) return new ExecResult.Completed(LISTING, 0);
            if (command.contains("show-packages")) return new ExecResult.Completed("{\"from\":0,\"to\":0,\"total\":0,\"packages\":[]}", 0);
            return new ExecResult.Completed("{\"from\":0,\"to\":0,\"total\":0,\"objects\":[]}", 0);
        });
        when(repository.beginDomain(any(PolicyCollectionRepository.Request.class), anyString())).thenReturn(true);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of());
        return new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions, System::nanoTime);
    }
    @ParameterizedTest @ValueSource(ints = {1, 4})
    void collectionReplacesThePlacementOnceFromTheDomainListing(int sessions) throws Exception {
        collector(sessions).collect(run, request, () -> true);
        verify(repository, times(1)).replaceDomainServerPlacement(eq("mds-1"), argThat(rows -> rows.size() == 2
            && rows.get(0).multiDomainServer().equals("MDS-SYNTH-01") && rows.get(0).active()));
    }
    @ParameterizedTest @ValueSource(ints = {1, 4})
    void aFailingPlacementWriteDoesNotFailTheCollection(int sessions) throws Exception {
        doThrow(new IllegalStateException("synthetic")).when(repository).replaceDomainServerPlacement(anyString(), any());
        assertDoesNotThrow(() -> collector(sessions).collect(run, request, () -> true));
        verify(repository, atLeastOnce()).replaceDomainServerPlacement(anyString(), any());
    }
}
