package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;

class CheckPointPolicyCollectorTest {
    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final TransportSession session = () -> "synthetic-session";
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(
        getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(r -> r.key().equals(key)).toList();
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request request = new PolicyCollectionRepository.Request("mds-1", "", false);

    private CheckPointPolicyCollector setup(java.util.function.Function<String, ExecResult> answers) {
        when(transport.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(session));
        when(transport.execInteractive(eq(session), any(), any())).thenAnswer(call -> answers.apply(((ExecSpec) call.getArgument(1)).command()));
        when(repository.beginDomain(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of(new PolicySnapshot.Target("device-1", "FW-TANGO-04", "", "UNKNOWN")));
        return new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), 1);
    }
    private static ExecResult ok(String json) { return new ExecResult.Completed(json, 0); }
    private static String page(String uid, int from, int to, int total, String rules, String objects) {
        return "{\"uid\":\"" + uid + "\",\"name\":\"Layer\",\"from\":" + from + ",\"to\":" + to + ",\"total\":" + total
            + ",\"rulebase\":[" + rules + "],\"objects-dictionary\":[" + objects + "]}";
    }
    private static final String RULE = "{\"uid\":\"r1\",\"type\":\"access-rule\",\"source\":[\"host1\"],\"action\":\"accept\",\"inline-layer\":\"child\"}";
    private static final String DICTIONARY = "{\"uid\":\"host1\",\"name\":\"OBJ-ADDRESS-01\",\"type\":\"host\",\"ipv4-address\":\"192.0.2.8\"},"
        + "{\"uid\":\"accept\",\"name\":\"Accept\"},{\"uid\":\"child\",\"name\":\"Inline\",\"type\":\"access-layer\"}";
    private ExecResult answer(String command) {
        if (command.equals(MgmtCliCommands.domainList())) return ok("{\"total\":1,\"objects\":[{\"uid\":\"domain-01\",\"name\":\"DOM-TANGO-01\"}]}");
        if (command.equals(MgmtCliCommands.showPackages("DOM-TANGO-01"))) return ok("""
            {"total":1,"packages":[{"uid":"pkg-01","name":"Package","access-layers":[{"uid":"layer","name":"Layer"}],"installation-targets":[{"uid":"target-01"}]}]}
            """);
        if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0))) return ok(page("layer", 1, 1, 2, RULE, DICTIONARY));
        if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 1))) return ok(page("layer", 2, 2, 2, "{\"uid\":\"r2\",\"type\":\"access-rule\"}", ""));
        if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 0))) return ok(page("child", 1, 1, 1, "{\"uid\":\"r3\",\"type\":\"access-rule\"}", ""));
        if (command.equals(MgmtCliCommands.showNatRulebase("DOM-TANGO-01", "Package", 0))) return ok(page("nat", 1, 1, 1,
            "{\"uid\":\"n1\",\"type\":\"nat-rule\",\"original-source\":\"host1\",\"translated-source\":\"translated\",\"method\":\"hide\"}",
            "{\"uid\":\"translated\",\"name\":\"OBJ-ADDRESS-02\",\"type\":\"host\",\"ipv4-address\":\"198.51.100.8\"}"));
        throw new AssertionError("Unexpected command");
    }
    @Test void cancelBetweenPagesKeepsOnlyCompleteLayersAndDisconnects() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var collector = setup(command -> {
            var result = answer(command);
            if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 1))) cancelled.set(true);
            return result;
        });
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        try (var scope = new com.securityexpert.nexus.ui2.worker.JobCancellationScope(cancelled::get)) {
            assertThrows(PolicyCollectionTrace.Failure.class,
                () -> collector.collect(run, request, () -> true, checkpoints::add));
        }
        assertEquals(1, checkpoints.size());
        assertEquals(List.of("r1", "r2"), checkpoints.get(0).sections().stream().flatMap(s -> s.rules().stream())
            .map(PolicySnapshot.Rule::uuid).toList());
        verify(transport, never()).execInteractive(eq(session), eq(new ExecSpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 0))), any());
        verify(transport).disconnect(session);
    }

    @Test void cancelledIncompleteLayerRestartsFromPageZero() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var collector = setup(command -> {
            var result = answer(command);
            if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0))) cancelled.set(true);
            return result;
        });
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        try (var scope = new com.securityexpert.nexus.ui2.worker.JobCancellationScope(cancelled::get)) {
            assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true, checkpoints::add));
        }
        assertTrue(checkpoints.isEmpty());
        cancelled.set(false);
        setup(this::answer).collect(run, request, () -> true);
        verify(transport, times(2)).execInteractive(eq(session), eq(new ExecSpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0))), any());
    }

    @Test void commandsAreExactlyApprovedAndQuoted() {
        assertEquals("mgmt_cli -r true -d 'DOM' -f json show-packages limit 500 details-level full", MgmtCliCommands.showPackages("DOM"));
        assertEquals("mgmt_cli -r true -d 'DOM' -f json show-access-rulebase name 'LAYER' limit 100 offset '500' details-level full use-object-dictionary true", MgmtCliCommands.showAccessRulebase("DOM", "LAYER", 500));
        assertEquals("mgmt_cli -r true -d 'DOM' -f json show-nat-rulebase package 'PKG' limit 500 offset '0' details-level standard use-object-dictionary true", MgmtCliCommands.showNatRulebase("DOM", "PKG", 0));
        assertTrue(MgmtCliCommands.showPackages("O'Brien; $(false)").contains("'O'\\''Brien; $(false)'"));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showAccessRulebase("DOM", "Layer", -1));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showPackages("DOM\nnext"));
        CpPolicyGates.requireAll(gates);
        assertTrue(CpPolicyGates.capability(gates).executionEligible());
        assertFalse(CpPolicyGates.capability(key -> List.of()).executionEligible());
        assertTrue(com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead.commands("check_point", "management_server", null, gates)
                .stream().noneMatch(command -> command.gateId().startsWith("cp_policy_")));
        assertThrows(IllegalStateException.class, () -> CpPolicyGates.requireAll(key -> List.of()));
    }
    @Test void pagesDictionariesInlineNatAndInstallTargetsShareOneInteractiveSession() {
        var snapshots = setup(this::answer).collect(run, request, () -> true);
        assertEquals(1, snapshots.size());
        var snapshot = snapshots.get(0);
        var rules = snapshot.sections().stream().flatMap(s -> s.rules().stream()).toList();
        assertEquals(List.of("r1", "r3", "r2", "n1"), rules.stream().map(PolicySnapshot.Rule::uuid).toList());
        assertEquals("Accept", rules.get(0).action());
        assertEquals("address", snapshot.objects().get(rules.get(0).source().refs().get(0)).type());
        assertEquals("hide", rules.get(3).action());
        assertEquals("OBJ-ADDRESS-02", snapshot.objects().get(rules.get(3).extras().get("translated-source").get(0)).name());
        assertEquals("device-1", snapshot.metadata().targets().get(0).deviceId());
        verify(transport, times(1)).connect(any(), any(), eq(Duration.ofSeconds(30)));
        verify(transport, times(3)).execInteractive(eq(session), any(), eq(Duration.ofSeconds(60)));
        verify(transport, times(3)).execInteractive(eq(session), any(), eq(Duration.ofSeconds(300)));
        verify(transport).disconnect(session);
        verify(transport, never()).exec(any(), any(), any());
    }
    @Test void unregisteredInstallTargetRetainsManagementAssignmentWithoutDeviceJoin() {
        var collector = setup(this::answer);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of());
        var target = collector.collect(run, request, () -> true).get(0).metadata().targets().get(0);
        assertEquals(PolicySnapshot.ref("cp-install-target", "mds-1", "domain-01", "target-01"), target.deviceId());
        assertEquals("UNKNOWN", target.syncStatus());
    }
    @Test void timedOutNatKeepsCompletedAccessLayers() {
        var collector = setup(c -> c.contains("show-nat-rulebase") ? new ExecResult.TimedOut() : answer(c));
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertEquals("NAT", snapshot.failures().get(0).layerName());
        assertEquals(List.of("r1", "r3", "r2"), snapshot.sections().stream().flatMap(section -> section.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
        verify(transport).disconnect(session);
        verify(repository, never()).publish(anyString(), anyLong(), anyList(), anyString());
    }
    @Test void malformedPackagesOrMissingInlineDictionaryFailWithoutExtraLookups() {
        for (String broken : List.of("truncated", "inline")) {
            var collector = setup(command -> {
                if (broken.equals("truncated") && command.contains("show-packages")) return ok("{\"total\":501,\"packages\":[]}");
                if (broken.equals("inline") && command.contains("show-access-rulebase")) return ok(page("layer", 1, 1, 1, RULE, ""));
                return answer(command);
            });
            if (broken.equals("truncated")) assertThrows(IllegalStateException.class, () -> collector.collect(run, request, () -> true));
            else assertFalse(collector.collect(run, request, () -> true).get(0).failures().isEmpty());
        }
    }
    @Test void sixHourSkipAndDomainScopeDoNotIssuePackageReads() {
        var collector = setup(this::answer);
        when(repository.beginDomain(anyString(), anyString(), eq(true))).thenReturn(false);
        assertTrue(collector.collect(run, new PolicyCollectionRepository.Request("mds-1", "", true), () -> true).isEmpty());
        verify(transport, times(1)).execInteractive(any(), any(), any());
        assertThrows(IllegalStateException.class, () -> collector.collect(run,
            new PolicyCollectionRepository.Request("mds-1", "unknown-domain", false), () -> true));
    }
    @Test void pagingRefusesGapsChangingTotalsRepeatedPagesAndLimit() {
        for (String bad : List.of(page("layer", 2, 2, 2, RULE, DICTIONARY), page("layer", 1, 0, 2, RULE, DICTIONARY),
                "{\"total\":1,\"rulebase\":[],\"objects-dictionary\":[]}")) {
            var collector = setup(c -> ok(bad));
            assertThrows(IllegalStateException.class, () -> collector.pages(session, n -> "synthetic-page", 1, Long.MAX_VALUE, () -> true));
        }
        AtomicInteger changed = new AtomicInteger();
        var changing = setup(c -> ok(changed.getAndIncrement() == 0 ? page("layer", 1, 1, 2, RULE, DICTIONARY)
                : page("layer", 2, 2, 3, RULE, DICTIONARY)));
        assertThrows(IllegalStateException.class, () -> changing.pages(session, n -> "synthetic-page", 1, Long.MAX_VALUE, () -> true));
        AtomicInteger count = new AtomicInteger();
        var collector = setup(c -> { int n = count.incrementAndGet(); return ok(page("layer", n, n, 201, RULE, DICTIONARY)); });
        assertThrows(IllegalStateException.class, () -> collector.pages(session, n -> "synthetic-page", 1, Long.MAX_VALUE, () -> true));
        assertEquals(200, count.get());
        assertThrows(IllegalStateException.class, () -> collector.pages(session, n -> "synthetic-page", 1, 0, () -> true));
        assertThrows(IllegalStateException.class, () -> collector.collect(run, request, () -> false));
    }
    @Test void timedOutAccessPageRetriesSameOffsetOnceAtFiftyWithFullDictionary() {
        AtomicInteger firstPage = new AtomicInteger();
        var collector = setup(command -> {
            if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0)) && firstPage.getAndIncrement() == 0)
                return new ExecResult.TimedOut();
            return answer(command.replace(" limit 50 offset ", " limit 100 offset "));
        });
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertTrue(snapshot.failures().isEmpty());
        verify(transport).execInteractive(eq(session), eq(new ExecSpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0, 50))), eq(Duration.ofSeconds(300)));
        assertEquals(1, firstPage.get());
    }
    @Test void twiceTimedOutInlineLayerKeepsParentAndNatAndBadgesIncomplete() {
        var collector = setup(command -> command.contains("name 'Inline'") ? new ExecResult.TimedOut() : answer(command));
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertEquals(1, snapshot.failures().size());
        assertTrue(snapshot.failures().get(0).reason().endsWith(": TIMEOUT"));
        assertEquals(List.of("r1", "r2", "n1"), snapshot.sections().stream().flatMap(section -> section.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
        verify(transport).execInteractive(eq(session), eq(new ExecSpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 0, 50))), eq(Duration.ofSeconds(300)));
        verify(transport).disconnect(session);
    }
    @Test void malformedMappedInlineLayerDoesNotPreventNatOrSiblingPublication() {
        var collector = setup(command -> command.contains("name 'Inline'")
            ? ok(page("child", 1, 1, 1, "{\"uid\":\"r3\",\"type\":\"unsupported-entry\"}", "")) : answer(command));
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        var snapshot = collector.collect(run, request, () -> true, checkpoints::add).get(0);
        assertEquals(List.of("r1", "r2", "n1"), snapshot.sections().stream()
            .flatMap(section -> section.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
        assertEquals(1, snapshot.failures().size());
        assertEquals("Inline", snapshot.failures().get(0).layerName());
        assertEquals(2, checkpoints.size());
        verify(transport).execInteractive(eq(session), eq(new ExecSpec(MgmtCliCommands.showNatRulebase("DOM-TANGO-01", "Package", 0))), any());
    }

    @Test void expiredJobBudgetCannotSendOrRetryAnyPage() {
        var collector = setup(this::answer);
        assertThrows(IllegalStateException.class, () -> collector.pages(session,
            offset -> MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", offset), 1, System.nanoTime() - 1, () -> true));
        verify(transport, never()).execInteractive(any(), any(), any());
    }

    @Test void deadlineMidInlineLayerRetainsParentAndStopsBeforeSiblingContact() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        setup(command -> {
            if (command.contains("name 'Inline'")) {
                clock.set(Duration.ofHours(2).toNanos());
                return ok(page("child", 1, 1, 2, "{\"uid\":\"r3\",\"type\":\"access-rule\"}", ""));
            }
            return answer(command);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), clock::get);
        var progress = new ArrayList<PolicyCollectionTrace.LayerProgress>();
        try (var trace = new PolicyCollectionTrace("run-1", (step, total) -> {}, measurement -> {}, progress::add)) {
            var failure = assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true, checkpoint -> {
                checkpoints.add(checkpoint);
                if (checkpoints.size() == 1) verify(transport, never()).execInteractive(eq(session),
                    eq(new ExecSpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 0))), any());
            }));
            assertTrue(failure.getMessage().endsWith(": JOB_DEADLINE"));
        }
        assertEquals(1, checkpoints.size());
        var snapshot = checkpoints.get(0);
        assertEquals("COLLECTION_PENDING", snapshot.failures().stream().filter(f -> f.layerName().equals("Inline")).findFirst().orElseThrow().reason());
        assertEquals(List.of("r1", "r2"), snapshot.sections().stream().flatMap(section -> section.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
        assertEquals(2, progress.get(progress.size() - 1).layer());
        assertEquals(2, progress.get(progress.size() - 1).layers());
        assertEquals(2, progress.get(progress.size() - 1).rules());
        verify(transport, never()).execInteractive(eq(session), eq(new ExecSpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 1))), any());
        verify(transport, never()).execInteractive(eq(session), eq(new ExecSpec(MgmtCliCommands.showNatRulebase("DOM-TANGO-01", "Package", 0))), any());
    }
    @Test void noCompletedLayerDoesNotPublishOnDeadline() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        setup(command -> {
            if (command.contains("show-access-rulebase")) {
                clock.set(Duration.ofHours(2).toNanos());
                return ok(page("layer", 1, 1, 2, RULE, DICTIONARY));
            }
            return answer(command);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), clock::get);
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> collector.collect(run, request, () -> true, checkpoints::add));
        assertTrue(checkpoints.isEmpty());
    }
    @Test void configurableDeadlineClipsPageTimeoutAndRejectsNonpositiveBudget() {
        setup(this::answer);
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofSeconds(120), () -> 0L);
        collector.collect(run, request, () -> true);
        verify(transport, times(3)).execInteractive(eq(session), any(), eq(Duration.ofSeconds(120)));
        assertThrows(IllegalArgumentException.class, () -> new CheckPointPolicyCollector(transport, gates, repository, Duration.ZERO));
    }

    @Test void laterFailedPackageKeepsPriorPackageAndReportsItsIncompleteLayers() {
        var collector = setup(command -> {
            if (command.contains("show-packages")) return ok("""
                {"total":2,"packages":[
                  {"uid":"pkg-01","name":"Package","access-layers":[{"uid":"layer","name":"Layer"}],"installation-targets":[]},
                  {"uid":"pkg-02","name":"Unfinished","access-layers":[{"uid":"other","name":"Unfinished"}],"installation-targets":[]}]}
                """);
            if (command.contains("'Unfinished'")) return new ExecResult.TimedOut();
            return answer(command);
        });
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        var collected = collector.collect(run, request, () -> true, checkpoints::add);
        assertEquals(2, collected.size());
        assertTrue(collected.get(0).failures().isEmpty());
        assertEquals(2, collected.get(1).failures().size());
        assertEquals("Unfinished", collected.get(1).failures().get(0).layerName());
        assertTrue(collected.get(1).failures().stream().allMatch(f -> f.reason().endsWith(": TIMEOUT")));
        assertEquals(4, checkpoints.size());
    }

}
