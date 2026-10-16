package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/** Live 2026-10-15/16 MDS gaps: totals that change while paging and the vendor's name-not-unique API error. */
class CpPolicyDomainCompletenessTest {
    private static final String DOMAIN = "DOM-TANGO-01";
    private static final String GATEWAY = "GW-TANGO-01";
    /** Fixed API error object: code + message only, as returned by the management API. */
    private static final String NOT_UNIQUE = "{\"code\":\"generic_err_object_field_not_unique\","
        + "\"message\":\"Synthetic-private object Package on " + GATEWAY + " is not unique\"}";

    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final TransportSession session = () -> "synthetic-session";
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request leased = new PolicyCollectionRepository.Request(
        "mds-1", "", false, PolicyCollectionRepository.Mode.FULL, "job-completeness", 1);

    @BeforeEach void isolate() {
        System.setProperty("ui2.policy.cp.collect-objects", "false");
        System.setProperty("ui2.policy.cp.layer-restart-backoff-ms", "0");
    }
    @AfterEach void restore() {
        System.clearProperty("ui2.policy.cp.collect-objects");
        System.clearProperty("ui2.policy.cp.layer-restart-backoff-ms");
    }

    private GateRegistryPort gates(boolean hits) {
        return key -> GateRegistryFixtureLoader.loadFromStream(getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"))
            .stream().filter(r -> r.key().equals(key) && (hits || !r.gateId().equals("cp_policy_access_rulebase_hits"))).toList();
    }

    private CheckPointPolicyCollector collector(int sessions, boolean hits, Function<String, ExecResult> answers) {
        when(transport.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(session));
        when(transport.execInteractive(eq(session), any(), any())).thenAnswer(call -> answers.apply(((ExecSpec) call.getArgument(1)).command()));
        when(repository.beginDomain(any(PolicyCollectionRepository.Request.class), anyString())).thenReturn(true);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of(new PolicySnapshot.Target("device-1", "FW-TANGO-04", "", "UNKNOWN")));
        when(repository.saveUnit(any(), anyString(), anyString(), anyString())).thenReturn(true);
        when(repository.saveDomain(any(), anyString(), anyString(), anyBoolean(), nullable(String.class), anyString(), anyInt(), nullable(String.class), anyString())).thenReturn(true);
        return new CheckPointPolicyCollector(transport, gates(hits), repository, Duration.ofHours(2), sessions);
    }

    private static ExecResult ok(String json) { return new ExecResult.Completed(json, 0); }
    private static String page(String uid, int from, int to, int total, String rules) {
        return "{\"uid\":\"" + uid + "\",\"name\":\"Layer\",\"from\":" + from + ",\"to\":" + to + ",\"total\":" + total
            + ",\"rulebase\":[" + rules + "],\"objects-dictionary\":[]}";
    }
    private static String rule(int n) { return "{\"uid\":\"r" + n + "\",\"type\":\"access-rule\",\"action\":\"accept\"}"; }
    private static int offset(String command) {
        var matcher = java.util.regex.Pattern.compile(" offset '([0-9]+)' ").matcher(command);
        assertTrue(matcher.find(), "paged command");
        return Integer.parseInt(matcher.group(1));
    }

    /** Domain, package and an empty NAT rulebase; access-layer pages are answered by {@code access}. */
    private ExecResult base(String command, Function<String, ExecResult> access, Function<String, ExecResult> nat) {
        if (command.equals(MgmtCliCommands.domainList()))
            return ok("{\"total\":1,\"objects\":[{\"uid\":\"domain-01\",\"name\":\"" + DOMAIN + "\"}]}");
        if (command.contains("show-last-published-session")) return ok("{}");
        if (command.contains("show-packages")) return ok("{\"from\":1,\"to\":1,\"total\":1,\"packages\":[{\"uid\":\"pkg-01\",\"name\":\"Package\","
            + "\"access-layers\":[{\"uid\":\"layer\",\"name\":\"Layer\"}],"
            + "\"installation-targets\":[{\"uid\":\"target-01\",\"name\":\"" + GATEWAY + "\"}]}]}");
        if (command.contains("show-access-rulebase")) return access.apply(command);
        if (command.contains("show-nat-rulebase")) return nat.apply(command);
        throw new AssertionError("Unexpected command shape");
    }
    private static ExecResult emptyNat(String command) { return ok(page("nat", 0, 0, 0, "")); }

    private String transcript(JobTranscript transcript) throws Exception {
        var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
        return sink.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void totalChangingOnceMidPagingRestartsTheLayerOnceAndCompletesInBothCollectors() throws Exception {
        for (int sessions : List.of(1, 4)) {
            reset(transport, repository);
            AtomicInteger passes = new AtomicInteger();
            Map<Integer, AtomicInteger> reads = new ConcurrentHashMap<>();
            var collector = collector(sessions, false, command -> base(command, access -> {
                int offset = offset(access);
                reads.computeIfAbsent(offset, k -> new AtomicInteger()).incrementAndGet();
                if (offset == 0) passes.incrementAndGet();
                // First pass: page 1 reports 2 rules, page 2 reports 3 (a rule was added while paging).
                int total = passes.get() == 1 && offset == 0 ? 2 : 3;
                return ok(page("layer", offset + 1, offset + 1, total, rule(offset + 1)));
            }, CpPolicyDomainCompletenessTest::emptyNat));
            var transcript = new JobTranscript();
            List<PolicySnapshot> snapshots;
            try (var scope = JobTranscriptScope.open(transcript)) { snapshots = collector.collect(run, leased, () -> true); }
            assertEquals(1, snapshots.size(), "sessions=" + sessions);
            assertTrue(snapshots.get(0).failures().isEmpty(), "sessions=" + sessions + " " + snapshots.get(0).failures());
            assertEquals(List.of("r1", "r2", "r3"), snapshots.get(0).sections().stream().flatMap(s -> s.rules().stream())
                .map(PolicySnapshot.Rule::uuid).toList());
            assertEquals(2, passes.get(), "exactly one restart, sessions=" + sessions);
            String notes = transcript(transcript);
            assertTrue(notes.contains("[CP_LAYER_RESTART] reason=TOTAL_CHANGED attempt=1"), notes);
            assertFalse(notes.contains("attempt=2"));
            assertTrue(notes.contains("check=TOTAL_CHANGED"));
            verify(repository, atLeastOnce()).saveDomain(eq(leased), anyString(), eq("COLLECTED"), eq(true), nullable(String.class), anyString(), anyInt(), nullable(String.class), anyString());
            verify(repository, never()).saveDomain(eq(leased), anyString(), eq("COLLECTED"), eq(false), nullable(String.class), anyString(), anyInt(), nullable(String.class), anyString());
        }
    }

    @Test void totalChangingOnEveryPassFailsTheLayerClosedAfterTwoRestartsInBothCollectors() throws Exception {
        for (int sessions : List.of(1, 4)) for (boolean hits : List.of(false, true)) {
            reset(transport, repository);
            AtomicInteger passes = new AtomicInteger();
            var collector = collector(sessions, hits, command -> base(command, access -> {
                int offset = offset(access);
                if (offset == 0) passes.incrementAndGet();
                // Every pass: the first page's total never matches the next page's total.
                int total = offset == 0 ? 10 + passes.get() : 50;
                return ok(page("layer", offset + 1, offset + 1, total, rule(offset + 1)));
            }, CpPolicyDomainCompletenessTest::emptyNat));
            var transcript = new JobTranscript();
            List<PolicySnapshot> snapshots;
            try (var scope = JobTranscriptScope.open(transcript)) { snapshots = collector.collect(run, leased, () -> true); }
            String label = "sessions=" + sessions + " hits=" + hits;
            assertEquals(1 + CheckPointPolicyCollector.MAX_LAYER_RESTARTS, passes.get(), label);
            var failures = snapshots.get(0).failures();
            assertEquals(1, failures.size(), label + " " + failures);
            assertEquals("Layer", failures.get(0).layerName());
            assertTrue(failures.get(0).reason().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"), label);
            assertFalse(PolicySnapshot.unknownPart(failures.get(0)));
            String notes = transcript(transcript);
            assertTrue(notes.contains("[CP_LAYER_RESTART] reason=TOTAL_CHANGED attempt=1"), label);
            assertTrue(notes.contains("[CP_LAYER_RESTART] reason=TOTAL_CHANGED attempt=2"), label);
            assertFalse(notes.contains("attempt=3"), label);
            // An exhausted restart budget is not a reason to retry without hits.
            verify(transport, never()).execInteractive(any(), argThat(spec -> hits && spec.command().contains("show-access-rulebase")
                && !spec.command().endsWith("show-hits true")), any());
            verify(repository, never()).saveDomain(eq(leased), anyString(), anyString(), eq(true), nullable(String.class), anyString(), anyInt(), nullable(String.class), anyString());
        }
    }

    @Test void natNameNotUniqueIsAnExplicitUnknownPartAndTheDomainCompletesInBothCollectors() throws Exception {
        for (int sessions : List.of(1, 4)) {
            reset(transport, repository);
            var collector = collector(sessions, false, command -> base(command,
                access -> ok(page("layer", 1, 1, 1, rule(1))),
                nat -> new ExecResult.Completed(NOT_UNIQUE, 1)));
            var transcript = new JobTranscript();
            List<PolicySnapshot> snapshots;
            try (var scope = JobTranscriptScope.open(transcript)) { snapshots = collector.collect(run, leased, () -> true); }
            String label = "sessions=" + sessions;
            var failures = snapshots.get(0).failures();
            assertEquals(List.of(new PolicySnapshot.CollectionFailure(PolicySnapshot.ref("cp-nat", PolicySnapshot.ref("mds-1", "domain-01"), "pkg-01"),
                PolicySnapshot.VENDOR_NAME_NOT_UNIQUE, "NAT", 0)), failures, label);
            assertTrue(PolicySnapshot.unknownPart(failures.get(0)));
            assertEquals(List.of("r1"), snapshots.get(0).sections().stream().flatMap(s -> s.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
            verify(repository, atLeastOnce()).saveDomain(eq(leased), anyString(), eq("COLLECTED"), eq(true), nullable(String.class), anyString(), anyInt(), nullable(String.class), anyString());
            verify(repository, never()).saveDomain(eq(leased), anyString(), eq("COLLECTED"), eq(false), nullable(String.class), anyString(), anyInt(), nullable(String.class), anyString());
            // No other command was invented: only the one approved NAT read was issued for the package.
            verify(transport, times(1)).execInteractive(any(), argThat(spec -> spec.command().contains("show-nat-rulebase")), any());
            String notes = transcript(transcript);
            assertEquals(1, notes.lines().filter(line -> line.contains("[CP_NAME_NOT_UNIQUE]")).count(), label);
            assertTrue(notes.contains("[CP_NAME_NOT_UNIQUE] kind=nat messagePackage=MATCH messageDomain=NO installTargetNames=1 "
                + "installTargetNamesInMessage=1 packagesWithRequestedName=1"), notes);
            assertFalse(notes.contains("Synthetic-private"), label);
            assertFalse(notes.contains(GATEWAY), label);
        }
    }

    @Test void nameNotUniqueOnTheHitsReadFallsBackToThePlainReadWithoutRetryInBothCollectors() throws Exception {
        for (int sessions : List.of(1, 4)) {
            reset(transport, repository);
            var collector = collector(sessions, true, command -> base(command,
                access -> access.endsWith(" show-hits true") ? new ExecResult.Completed(NOT_UNIQUE, 1) : ok(page("layer", 1, 1, 1, rule(1))),
                CpPolicyDomainCompletenessTest::emptyNat));
            var transcript = new JobTranscript();
            List<PolicySnapshot> snapshots;
            try (var scope = JobTranscriptScope.open(transcript)) { snapshots = collector.collect(run, leased, () -> true); }
            String label = "sessions=" + sessions;
            assertTrue(snapshots.get(0).failures().isEmpty(), label + " " + snapshots.get(0).failures());
            assertEquals(List.of("r1"), snapshots.get(0).sections().stream().flatMap(s -> s.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
            verify(transport, times(1)).execInteractive(any(), argThat(spec -> spec.command().endsWith(" show-hits true")), any());
            verify(transport, times(1)).execInteractive(any(), eq(new ExecSpec(MgmtCliCommands.showAccessRulebase(DOMAIN, "layer", "Layer", 0), false, 120_000)), any());
            verify(repository, atLeastOnce()).saveDomain(eq(leased), anyString(), eq("COLLECTED"), eq(true), nullable(String.class), anyString(), anyInt(), nullable(String.class), anyString());
            String notes = transcript(transcript);
            assertTrue(notes.contains("[CP_NAME_NOT_UNIQUE] kind=access-hits messagePackage=MATCH messageDomain=NO"), notes);
            assertTrue(notes.contains("check=VENDOR_NAME_NOT_UNIQUE"), label);
            assertFalse(notes.contains("Synthetic-private"), label);
        }
    }

    @Test void nameNotUniqueReadFailureNeverCarriesTheVendorMessage() {
        var collector = collector(1, false, command -> new ExecResult.Completed(NOT_UNIQUE, 1));
        var error = assertThrows(CheckPointPolicyCollector.NameNotUnique.class, () -> collector.read(session,
            MgmtCliCommands.showNatRulebase(DOMAIN, "Package", 0), 2, Long.MAX_VALUE, () -> true));
        assertTrue(error.getMessage().endsWith(": VENDOR_NAME_NOT_UNIQUE"));
        assertFalse(error.getMessage().contains("Synthetic-private"));
        assertFalse(String.valueOf(error).contains(GATEWAY));
        assertEquals(PolicySnapshot.VENDOR_NAME_NOT_UNIQUE, CheckPointPolicyCollector.rejectingCheck(error));
    }
}
