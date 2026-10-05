package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.worker.JobCancellationScope;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;
import com.securityexpert.nexus.ui2.worker.transcript.*;

@Timeout(15)
class CpPolicyParallelCollectionTest {
    @org.junit.jupiter.api.BeforeEach void isolateRuleCollection() { System.setProperty("ui2.policy.cp.collect-objects", "false"); }
    @org.junit.jupiter.api.AfterEach void restoreObjectCollection() { System.clearProperty("ui2.policy.cp.collect-objects"); }
    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(
        getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(r -> r.key().equals(key) && !r.gateId().equals("cp_policy_access_rulebase_hits")).toList();
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request request = new PolicyCollectionRepository.Request("mds-1", "", false, PolicyCollectionRepository.Mode.FULL, "", 0);
    private final Set<TransportSession> opened = ConcurrentHashMap.newKeySet(), closed = ConcurrentHashMap.newKeySet();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sequence = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
    private final List<String> reads = new CopyOnWriteArrayList<>();

    private CheckPointPolicyCollector setup(int maximum, Function<String, ExecResult> answer) {
        doAnswer(call -> {
            String id = "synthetic-session-" + sequence.incrementAndGet();
            TransportSession session = () -> id; opened.add(session);
            assertTrue(opened.size() - closed.size() <= maximum);
            return new ConnectResult.Authenticated(session);
        }).when(transport).connect(any(), any(), any());
        doAnswer(call -> { closed.add(call.getArgument(0)); return null; }).when(transport).disconnect(any());
        doAnswer(call -> {
            TransportSession session = call.getArgument(0);
            assertTrue(busy.add(session.sessionId()), "A session cannot carry concurrent reads");
            int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
            String command = ((ExecSpec) call.getArgument(1)).command(); reads.add(command);
            try { return answer.apply(command); }
            finally { active.decrementAndGet(); busy.remove(session.sessionId()); }
        }).when(transport).execInteractive(any(), any(), any());
        when(repository.beginDomain(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of());
        return new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), maximum);
    }

    @Test void packageTimeoutPreservesFailureAndOtherDomainsContinue() {
        var collector = setup(4, command -> {
            if (command.contains("show-domains")) return ok("{\"total\":2,\"objects\":[" + domain("broken", "DOM-BRAVO-02") + "," + domain("domain-01", "DOM-TANGO-01") + "]}");
            if (command.contains("show-packages") && command.contains("'DOM-BRAVO-02'")) return new ExecResult.TimedOut();
            return answer(command);
        });
        List<PolicySnapshot> published = new ArrayList<>();
        List<PolicySnapshot.CollectionFailure> failures = new ArrayList<>();
        var snapshots = collector.collect(run, request, () -> true, published::add, failures::add);
        assertEquals(1, snapshots.size());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).reason().endsWith(": TIMEOUT"));
        assertEquals("Packages", failures.get(0).layerName());
        assertEquals(PolicySnapshot.ref("mds-1", "broken"), failures.get(0).layerRef());
        assertFalse(snapshots.get(0).sections().isEmpty());
        assertTrue(published.contains(snapshots.get(0)));
        assertEquals(1, reads.stream().filter(c -> c.equals(MgmtCliCommands.showPackages("DOM-BRAVO-02"))).count());
        cleanup();
    }

    private static ExecResult ok(String json) { return new ExecResult.Completed(json, 0); }
    private static String domain(String uid, String name) { return "{\"uid\":\"" + uid + "\",\"name\":\"" + name + "\"}"; }
    private static String packages(String layers) {
        return "{\"from\":1,\"to\":1,\"total\":1,\"packages\":[{\"uid\":\"pkg-01\",\"name\":\"Package\",\"access-layers\":[" + layers + "],\"installation-targets\":[]}]}";
    }
    private static int offset(String command) {
        var matcher = java.util.regex.Pattern.compile(" offset '([0-9]+)'").matcher(command);
        assertTrue(matcher.find()); return Integer.parseInt(matcher.group(1));
    }
    private static ExecResult page(String uid, int offset, int to, int total, String rules, String dictionary) {
        return ok("{\"uid\":\"" + uid + "\",\"name\":\"Layer\",\"from\":" + (offset + 1) + ",\"to\":" + to
            + ",\"total\":" + total + ",\"rulebase\":[" + rules + "],\"objects-dictionary\":[" + dictionary + "]}");
    }
    private static String rule(String uid) { return "{\"uid\":\"" + uid + "\",\"type\":\"access-rule\"}"; }
    private ExecResult answer(String command) {
        if (command.contains("show-domains")) return ok("{\"total\":1,\"objects\":[" + domain("domain-01", "DOM-TANGO-01") + "]}");
        if (command.contains("show-packages")) return ok(packages(domain("layer", "Layer")));
        if (command.contains("show-nat-rulebase")) return page("nat", 0, 0, 0, "", "");
        int offset = offset(command);
        return page("layer", offset, Math.min(offset + 100, 400), 400, rule("r" + offset), "");
    }
    private static List<String> rules(PolicySnapshot snapshot) {
        return snapshot.sections().stream().flatMap(s -> s.rules().stream()).map(PolicySnapshot.Rule::uuid).toList();
    }
    private void cleanup() { assertEquals(opened, closed); assertTrue(busy.isEmpty()); assertTrue(peak.get() <= 4); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected concurrent work did not start"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    @Test void outOfOrderPagesAreAssembledByOffsetAndPublishedIncrementally() {
        var later = new CountDownLatch(1);
        var collector = setup(4, command -> {
            if (command.contains("show-access-rulebase") && offset(command) == 100) await(later);
            if (command.contains("show-access-rulebase") && offset(command) == 200) later.countDown();
            return answer(command);
        });
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        var snapshots = collector.collect(run, request, () -> true, checkpoints::add);
        assertEquals(List.of("r0", "r100", "r200", "r300"), rules(snapshots.get(0)));
        assertTrue(snapshots.get(0).failures().isEmpty());
        assertTrue(checkpoints.size() >= 2); assertTrue(peak.get() >= 2); cleanup();
    }

    @Test void schedulerActuallyReachesFourWithoutSharingSessions() {
        var four = new CountDownLatch(4);
        var collector = setup(4, command -> {
            if (command.contains("show-access-rulebase")) {
                int offset = offset(command);
                if (offset >= 200 && offset <= 500) { four.countDown(); await(four); }
                return page("layer", offset, Math.min(offset + 100, 700), 700, rule("r" + offset), "");
            }
            return answer(command);
        });
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertTrue(snapshot.failures().isEmpty()); assertEquals(4, peak.get()); cleanup();
    }

    @Test void adaptiveLimitStartsAtTwoRampsAfterThreeAndHalvesWithAFloorOfOne() throws Exception {
        var transcript = new JobTranscript();
        try (var scope = JobTranscriptScope.open(transcript)) {
            var safety = new CpPolicyParallelCollection.Safety(4);
            assertEquals(2, safety.limit);
            safety.success(1); safety.success(1); assertEquals(2, safety.limit);
            safety.success(Duration.ofSeconds(60).toNanos());
            safety.success(1); safety.success(1); assertEquals(2, safety.limit);
            safety.success(1); assertEquals(4, safety.limit);
            safety.pressure(); assertEquals(2, safety.limit);
            safety.pressure(); safety.pressure(); assertEquals(1, safety.limit);
            safety.success(1); safety.success(1); safety.success(1); assertEquals(4, safety.limit);
        }
        var bytes = new ByteArrayOutputStream(); transcript.writeTo(bytes);
        assertTrue(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("policy concurrency=2 previous=4"));
        var capped = new CpPolicyParallelCollection.Safety(3);
        capped.success(1); capped.success(1); capped.success(1); assertEquals(3, capped.limit);
    }

    @Test void timeoutChannelAndSessionPressureRetryExactlyOnceWithoutChangingCommand() {
        for (ExecResult failure : List.of(new ExecResult.TimedOut(), new ExecResult.ChannelFailed("synthetic-failure"),
                new ExecResult.Completed("{\"code\":\"synthetic-error\",\"message\":\"too many sessions\"}", 1),
                new ExecResult.Completed("{\"code\":\"synthetic-lock-error\",\"message\":\"synthetic-error\"}", 0))) {
            reset(transport, repository); opened.clear(); closed.clear(); reads.clear();
            var attempts = new AtomicInteger();
            var collector = setup(4, command -> command.contains("show-access-rulebase") && offset(command) == 0 && attempts.getAndIncrement() == 0
                ? failure : answer(command));
            var snapshot = collector.collect(run, request, () -> true).get(0);
            assertTrue(snapshot.failures().isEmpty(), () -> failure.getClass().getSimpleName() + ": " + snapshot.failures());
            assertEquals(2, attempts.get());
            assertEquals(2, Collections.frequency(reads, MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0)));
            assertTrue(reads.stream().noneMatch(c -> c.contains("show-access-rulebase") && c.contains(" limit 50 offset "))); cleanup();
        }
    }

    @Test void twiceFailedPageKeepsSiblingNatAndCleansAllSessions() {
        var collector = setup(4, command -> command.contains("show-access-rulebase") ? new ExecResult.TimedOut() : answer(command));
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertEquals(1, snapshot.failures().size());
        assertTrue(snapshot.failures().get(0).reason().endsWith(": TIMEOUT"));
        assertEquals(2, reads.stream().filter(c -> c.contains("show-access-rulebase")).count()); cleanup();
    }
    @Test void streamingTimeoutDoesNotRetryAndCleansAllSessions() {
        var collector = setup(4, command -> command.contains("show-access-rulebase") ? new ExecResult.TimedOut(true) : answer(command));
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertEquals(1, snapshot.failures().size());
        assertTrue(snapshot.failures().get(0).reason().endsWith(": STREAMING_TIMEOUT"));
        assertEquals(1, reads.stream().filter(c -> c.contains("show-access-rulebase")).count()); cleanup();
    }

    @Test void failedDisconnectStopsInsteadOfOpeningARetrySession() {
        var collector = setup(2, command -> command.contains("show-access-rulebase") ? new ExecResult.TimedOut() : answer(command));
        doThrow(new IllegalStateException("synthetic-failure")).when(transport).disconnect(any());
        var failure = assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true));
        assertTrue(failure.getMessage().endsWith(": SESSION_CLEANUP_FAILED"));
        assertEquals(1, reads.stream().filter(c -> c.contains("show-access-rulebase")).count());
        assertTrue(busy.isEmpty());
    }

    @Test void cancellationDrainsInFlightPagesPublishesCompletedLayerAndStartsNoMoreWork() {
        var cancelled = new AtomicBoolean();
        var second = new CountDownLatch(1);
        var cancellationSet = new CountDownLatch(1);
        var collector = setup(2, command -> {
            if (command.contains("show-packages")) return ok(packages(domain("first", "First") + "," + domain("second", "Second") + "," + domain("third", "Third")));
            if (command.contains("name 'First'")) { await(second); cancelled.set(true); cancellationSet.countDown(); return page("first", 0, 1, 1, rule("complete"), ""); }
            if (command.contains("name 'Second'")) { second.countDown(); await(cancellationSet); return page("second", 0, 1, 2, rule("partial"), ""); }
            return answer(command);
        });
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        try (var scope = new JobCancellationScope(cancelled::get)) {
            var failure = assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true, checkpoints::add));
            assertTrue(failure.getMessage().endsWith(": CANCELLED"));
        }
        assertTrue(checkpoints.stream().anyMatch(p -> rules(p).contains("complete")));
        assertTrue(checkpoints.stream().noneMatch(p -> rules(p).contains("partial")));
        assertTrue(reads.stream().noneMatch(c -> c.contains("name 'Third'") || c.contains("offset '1'") || c.contains("show-nat-rulebase"))); cleanup();
    }

    @Test void cancelledTimedOutPageIsNotRetried() {
        var cancelled = new AtomicBoolean();
        var collector = setup(2, command -> {
            if (command.contains("show-access-rulebase")) { cancelled.set(true); return new ExecResult.TimedOut(); }
            return answer(command);
        });
        try (var scope = new JobCancellationScope(cancelled::get)) {
            assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true));
        }
        assertEquals(1, reads.stream().filter(c -> c.contains("show-access-rulebase")).count()); cleanup();
    }

    @Test void deadlineAndLeaseLossPreventFurtherPageAdmission() {
        for (boolean deadline : List.of(false, true)) {
            reset(transport); opened.clear(); closed.clear(); reads.clear();
            var clock = new AtomicLong();
            var lease = new AtomicBoolean(true);
            setup(2, command -> {
                if (command.contains("show-access-rulebase")) {
                    if (deadline) clock.set(Duration.ofHours(2).toNanos()); else lease.set(false);
                }
                return answer(command);
            });
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), 2, clock::get);
            var failure = assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, lease::get));
            assertTrue(failure.getMessage().endsWith(deadline ? ": JOB_DEADLINE" : ": LEASE_LOST"));
            assertTrue(reads.stream().noneMatch(c -> c.contains("offset '100'"))); cleanup();
        }
    }

    @Test void oneSessionProducesTheSameRulesObjectsAndFailuresAsParallel() {
        var serial = setup(1, this::answer).collect(run, request, () -> true).get(0);
        assertEquals(1, opened.size()); assertEquals(1, peak.get()); cleanup();
        reset(transport); opened.clear(); closed.clear();
        var parallel = setup(4, this::answer).collect(run, request, () -> true).get(0);
        assertEquals(serial.sections(), parallel.sections()); assertEquals(serial.objects(), parallel.objects());
        assertEquals(serial.failures(), parallel.failures()); assertEquals(serial.metadata().id(), parallel.metadata().id()); cleanup();
    }

    @Test void domainAndLayerReadsFillAvailableSlots() {
        var domainReads = new CountDownLatch(2);
        var layerReads = new CountDownLatch(2);
        var collector = setup(2, command -> {
            if (command.contains("show-domains")) return ok("{\"total\":2,\"objects\":[" + domain("domain-01", "DOM-TANGO-01") + "," + domain("domain-02", "DOM-BRAVO-02") + "]}");
            if (command.contains("show-packages")) { domainReads.countDown(); await(domainReads); return ok(packages(domain("layer", "Layer"))); }
            if (command.contains("show-access-rulebase")) { layerReads.countDown(); await(layerReads); return page("layer", 0, 1, 1, rule("r0"), ""); }
            return answer(command);
        });
        var progress = new ArrayList<PolicyCollectionTrace.LayerProgress>();
        List<PolicySnapshot> snapshots;
        try (var trace = new PolicyCollectionTrace("run-1", (step, total) -> {}, measurement -> {}, progress::add)) {
            snapshots = collector.collect(run, request, () -> true);
        }
        var last = progress.get(progress.size() - 1);
        assertEquals(2, last.domainsTotal());
        assertEquals(2, last.domainsDone());
        assertEquals(2, last.packagesTotal());
        assertEquals(2, last.packagesDone());
        assertEquals(2, last.rules());
        assertTrue(last.lastActivity() > 0);
        assertEquals(2, snapshots.size()); assertTrue(snapshots.stream().allMatch(s -> s.failures().isEmpty())); cleanup();
    }

    @Test void changedOnlyDomainReuseAndScopeSkipPackageReads() {
        var collector = setup(4, this::answer);
        when(repository.beginDomain(anyString(), anyString(), eq(true))).thenReturn(false);
        assertTrue(collector.collect(run, new PolicyCollectionRepository.Request("mds-1", "", true), () -> true).isEmpty());
        assertEquals(1, reads.size()); cleanup();
        assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run,
            new PolicyCollectionRepository.Request("mds-1", "missing-domain", false), () -> true)); cleanup();
    }

    @Test void trustAuthenticationLeaseAndMalformedMetadataStopAndCleanup() {
        for (ConnectResult failure : List.of(new ConnectResult.HostKeyRejected("synthetic-failure"), new ConnectResult.AuthenticationFailed("synthetic-failure"))) {
            var collector = setup(4, this::answer);
            doReturn(failure).when(transport).connect(any(), any(), any());
            assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true));
            verify(transport, never()).execInteractive(any(), any(), any()); cleanup();
            verify(transport, never()).disconnect(any());
            assertTrue(opened.isEmpty());
        }
        reset(transport);
        var collector = setup(4, command -> command.contains("show-packages") ? ok("{\"total\":1,\"packages\":[]}") : answer(command));
        assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true)); cleanup();
        int before = opened.size();
        assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> false));
        assertEquals(before, opened.size());
    }

    @Test void authenticatedSessionClosesOnFatalReadFailure() {
        for (String reason : List.of("HostKeyRejected", "AuthenticationFailed")) {
            reset(transport, repository); opened.clear(); closed.clear(); reads.clear();
            var collector = setup(4, command -> { throw PolicyCollectionTrace.failure(reason); });
            assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true));
            assertEquals(1, opened.size()); assertEquals(1, reads.size()); cleanup();
            verify(transport, times(1)).disconnect(opened.iterator().next());
        }
    }

    @Test void inlineDiscoveryShortPagesAndDictionariesMatchSerial() {
        Function<String, ExecResult> responses = command -> {
            if (command.contains("name 'Layer'")) {
                int offset = offset(command);
                return page("layer", offset, offset + 1, 2,
                    offset == 0 ? "{\"uid\":\"parent\",\"type\":\"access-rule\",\"inline-layer\":\"child\",\"source\":[\"object-01\"]}" : rule("sibling"),
                    offset == 0 ? "{\"uid\":\"child\",\"name\":\"Inline\"},{\"uid\":\"object-01\",\"type\":\"host\",\"name\":\"OBJ-ADDRESS-01\",\"ipv4-address\":\"192.0.2.8\"}" : "");
            }
            if (command.contains("name 'Inline'")) return page("child", 0, 1, 1, rule("nested"), "");
            return answer(command);
        };
        var serial = setup(1, responses).collect(run, request, () -> true).get(0); cleanup();
        reset(transport); opened.clear(); closed.clear();
        var parallel = setup(4, responses).collect(run, request, () -> true).get(0);
        assertEquals(List.of("parent", "nested", "sibling"), rules(parallel));
        assertEquals(serial.sections(), parallel.sections()); assertEquals(serial.objects(), parallel.objects());
        assertTrue(parallel.failures().isEmpty()); cleanup();
    }

    @Test void changingTotalsAndWrongLayerIdentityStayIncomplete() {
        for (boolean wrongUid : List.of(false, true)) {
            reset(transport); opened.clear(); closed.clear();
            var collector = setup(4, command -> command.contains("show-access-rulebase") && offset(command) == 100
                ? page(wrongUid ? "other" : "layer", 100, 200, wrongUid ? 400 : 401, rule("r100"), "") : answer(command));
            var snapshot = collector.collect(run, request, () -> true).get(0);
            assertFalse(snapshot.failures().isEmpty()); assertTrue(rules(snapshot).isEmpty()); cleanup();
        }
    }

    @Test void malformedLayerBetweenCheckpointsKeepsSiblingAndPublishesFailedPackage() {
        var published = new CountDownLatch(1);
        setup(2, command -> {
            if (command.contains("show-packages")) return ok(packages(domain("first", "First") + "," + domain("broken", "Broken")));
            if (command.contains("name 'Broken'")) {
                await(published);
                return page("broken", 0, 1, 1, "{\"uid\":\"invalid\",\"type\":\"unsupported-entry\"}", "");
            }
            if (command.contains("show-access-rulebase")) return page("first", 0, 1, 1, rule("complete"), "");
            return answer(command);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), 2, () -> 0);
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        var snapshots = collector.collect(run, request, () -> true, checkpoint -> {
            checkpoints.add(checkpoint); published.countDown();
        });
        var snapshot = snapshots.get(0);
        assertEquals(List.of("complete"), rules(snapshot));
        assertEquals(1, snapshot.failures().size());
        assertEquals("Broken", snapshot.failures().get(0).layerName());
        assertEquals(2, checkpoints.size());
        assertEquals(snapshot, checkpoints.get(1));
        assertTrue(reads.stream().anyMatch(c -> c.contains("show-nat-rulebase")));
        cleanup();
    }

    @Test void checkpointsAreThrottledPerPackageButCompletionAlwaysPublishes() {
        var clock = new AtomicLong();
        setup(2, command -> {
            if (command.contains("show-packages")) return ok(packages(domain("first", "First") + ","
                + domain("second", "Second") + "," + domain("third", "Third")));
            if (command.contains("show-access-rulebase")) {
                String uid = command.contains("name 'First'") ? "first" : command.contains("name 'Second'") ? "second" : "third";
                return page(uid, 0, 1, 1, rule(uid + "-rule"), "");
            }
            return answer(command);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), 2, clock::get);
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        var progress = new ArrayList<PolicyCollectionTrace.LayerProgress>();
        var steps = new AtomicInteger();
        List<PolicySnapshot> snapshots;
        try (var trace = new PolicyCollectionTrace("synthetic-source", (step, total) -> steps.incrementAndGet(),
                measurement -> {}, progress::add)) {
            snapshots = collector.collect(run, request, () -> true, checkpoints::add);
        }
        assertTrue(steps.get() >= 6); // Domain/package reads and every layer/page still report progress.
        assertTrue(progress.size() >= 7); // Page and completed-layer counters survive skipped checkpoints.
        assertEquals(2, checkpoints.size()); // First completed layer and final package, despite a frozen clock.
        assertEquals(snapshots.get(0), checkpoints.get(1));
        assertEquals(3, rules(snapshots.get(0)).size());
        assertTrue(snapshots.get(0).failures().isEmpty()); cleanup();
    }

    @Test void singleSessionAlsoThrottlesWholePackageCheckpoints() {
        setup(1, command -> {
            if (command.contains("show-packages")) return ok(packages(domain("first", "First") + "," + domain("second", "Second")));
            if (command.contains("show-access-rulebase")) {
                String uid = command.contains("name 'First'") ? "first" : "second";
                return page(uid, 0, 1, 1, rule(uid + "-rule"), "");
            }
            return answer(command);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), 1, () -> 0);
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        var snapshots = collector.collect(run, request, () -> true, checkpoints::add);
        assertEquals(2, checkpoints.size()); assertEquals(snapshots.get(0), checkpoints.get(1));
        assertEquals(2, rules(snapshots.get(0)).size()); cleanup();
    }

    @Test void elapsedCheckpointIntervalAllowsAnotherPartialSnapshot() {
        var clock = new AtomicLong();
        setup(2, command -> {
            if (command.contains("show-packages")) return ok(packages(domain("first", "First") + ","
                + domain("second", "Second") + "," + domain("third", "Third")));
            if (command.contains("show-access-rulebase")) {
                String uid = command.contains("name 'First'") ? "first" : command.contains("name 'Second'") ? "second" : "third";
                return page(uid, 0, 1, 1, rule(uid + "-rule"), "");
            }
            return answer(command);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), 2, clock::get);
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        var snapshots = collector.collect(run, request, () -> {
            clock.addAndGet(Duration.ofSeconds(60).toNanos()); return true;
        }, checkpoints::add);
        assertEquals(4, checkpoints.size()); // Three access layers plus NAT completion.
        assertEquals(snapshots.get(0), checkpoints.get(3)); cleanup();
    }

    @Test void checkpointIntervalIsConfigurableAndMustBePositive() {
        String key = "ui2.policy.cp.checkpoint-interval", old = System.getProperty(key);
        try {
            System.setProperty(key, "90"); assertEquals(Duration.ofSeconds(90), CpPolicyParallelCollection.configuredCheckpointInterval());
            for (String invalid : List.of("0", "-1")) {
                System.setProperty(key, invalid);
                assertThrows(IllegalArgumentException.class, CpPolicyParallelCollection::configuredCheckpointInterval);
            }
        } finally {
            if (old == null) System.clearProperty(key); else System.setProperty(key, old);
        }
    }

    @Test void synthetic3500RulePackageBuildAndSerializationAreBounded() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        List<com.fasterxml.jackson.databind.JsonNode> pages = new ArrayList<>();
        for (int offset = 0; offset < 3500; offset += 100) {
            var page = json.createObjectNode().put("uid", "synthetic-layer").put("name", "Layer").put("total", 3500)
                .put("from", offset + 1).put("to", offset + 100);
            var rulebase = page.putArray("rulebase");
            var dictionary = page.putArray("objects-dictionary");
            for (int i = offset; i < offset + 100; i++) {
                rulebase.addObject().put("uid", "synthetic-rule-" + i).put("type", "access-rule")
                    .putArray("source").add("synthetic-object-" + i);
                dictionary.addObject().put("uid", "synthetic-object-" + i).put("type", "host")
                    .put("name", "OBJ-ADDRESS-" + i).put("ipv4-address", "192.0.2.8");
            }
            pages.add(page);
        }
        var metadata = new PolicySnapshot.Metadata("synthetic-policy", "synthetic-source", "Synthetic source", "CP",
            "synthetic-domain", "DOM-TANGO-01", "Package", "2026-10-02T00:00:00Z", "", List.of());
        assertTimeout(Duration.ofSeconds(5), () -> {
            long started = System.nanoTime();
            var snapshot = CheckPointPolicyCollector.snapshot(metadata, pages, List.of(), List.of());
            long mapped = System.nanoTime();
            String body = json.writeValueAsString(snapshot);
            long serialized = System.nanoTime();
            assertEquals(3500, rules(snapshot).size()); assertEquals(3500, snapshot.objects().size());
            assertFalse(body.isEmpty());
            System.out.printf("Synthetic policy rules=3500 mappingMs=%d serializationMs=%d%n",
                (mapped - started) / 1_000_000, (serialized - mapped) / 1_000_000);
        });
    }

    @Test void configurationUsesPropertyAndRejectsOutsideTheHardCap() {
        String old = System.getProperty("ui2.policy.cp.max-sessions");
        try {
            System.setProperty("ui2.policy.cp.max-sessions", "1"); assertEquals(1, CheckPointPolicyCollector.configuredMaxSessions());
            assertThrows(IllegalArgumentException.class, () -> new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), 0));
            assertThrows(IllegalArgumentException.class, () -> new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), 5));
        } finally {
            if (old == null) System.clearProperty("ui2.policy.cp.max-sessions"); else System.setProperty("ui2.policy.cp.max-sessions", old);
        }
    }
}
