package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.Mode;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** PO 2026-10-18: scheduled 06/12/18 runs changed-only for the domains in the setting; one full run at 00:00. */
class CpChangedOnlyTrialTest {
    private static final String TRIAL = "aaaaaaaa-0000-3000-8000-00000000000a";
    private static final String OTHER = "00000000-0000-3000-8000-000000000002";
    private static final String SIGNAL = "{\"uid\":\"published-001\",\"publish-time\":{\"posix\":1791158400000,\"iso-8601\":\"2026-10-05T00:00+0000\"}}";
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach void restore() {
        System.clearProperty("ui2.policy.cp.changed-only-domains");
        System.clearProperty("ui2.policy.cp.changed-only-max-age-hours");
    }

    @Test void settingDefaultsToExactlyTheTrialDomainAndFailsClosedOnInvalidValues() {
        var configured = CpChangedOnlyDomains.configured();
        assertTrue(configured.includes(CpChangedOnlyDomains.TRIAL_DOMAIN));
        assertFalse(configured.includes(OTHER));
        assertEquals("1 domain(s)", configured.toString());
        assertEquals(Duration.ofHours(24), configured.maxAge());
        var all = CpChangedOnlyDomains.parse("*", "24");
        assertTrue(all.includes(TRIAL) && all.includes(OTHER));
        for (String none : List.of("", " ", "none", "NONE")) {
            var off = CpChangedOnlyDomains.parse(none, "24");
            assertTrue(off.none()); assertFalse(off.includes(TRIAL));
        }
        var two = CpChangedOnlyDomains.parse(TRIAL + " , " + OTHER, "12");
        assertTrue(two.includes(TRIAL) && two.includes(OTHER)); assertEquals(Duration.ofHours(12), two.maxAge());
        // Opaque references match exactly: no case folding or trimming of the value itself.
        assertFalse(CpChangedOnlyDomains.parse(TRIAL, "24").includes(TRIAL.toUpperCase(Locale.ROOT)));
        for (String bad : List.of("DOM-TANGO-01", TRIAL + ",", TRIAL.toUpperCase(Locale.ROOT), "*," + TRIAL, TRIAL.substring(1)))
            assertThrows(IllegalArgumentException.class, () -> CpChangedOnlyDomains.parse(bad, "24"), bad);
        for (String age : List.of("0", "-1", "169", "x", ""))
            assertThrows(IllegalArgumentException.class, () -> CpChangedOnlyDomains.parse(TRIAL, age), age);
        System.setProperty("ui2.policy.cp.changed-only-domains", "not-an-opaque-ref");
        assertThrows(IllegalArgumentException.class, CpChangedOnlyDomains::configured);
    }

    @Test void scheduledChangedOnlyIsExemptFromTheSixHourAllowanceButOtherAutomaticRequestsAreNot() {
        assertFalse(new PolicyCollectionRepository.Request("mds-1", "", true, Mode.CHANGED_ONLY, "job-1", 1, true).throttledAutomatic());
        assertTrue(new PolicyCollectionRepository.Request("mds-1", "", true, Mode.CHANGED_ONLY, "job-1", 1, false).throttledAutomatic());
        assertFalse(new PolicyCollectionRepository.Request("mds-1", "", true, Mode.FULL, "job-1", 1, false).throttledAutomatic());
        assertFalse(new PolicyCollectionRepository.Request("mds-1", "", false, Mode.CHANGED_ONLY, "job-1", 1, false).throttledAutomatic());
        assertTrue(new PolicyCollectionRepository.Request("mds-1", "", true, Mode.CHANGED_ONLY, "job-1", 1, true).withLease(2).scheduled());
    }

    @Test void reuseDecisionFollowsModeOriginSettingSignalAndEvidenceAge() throws Exception {
        long now = Instant.parse("2026-10-19T03:05:00Z").toEpochMilli();
        String fresh = "2026-10-18T21:30:00Z", stale = "2026-10-15T21:30:00Z";
        var trial = CpChangedOnlyDomains.parse(TRIAL, "24");
        record Case(Mode mode, boolean automatic, boolean scheduled, String domain, String collectedAt, String signalUid, String decision) {}
        for (var c : List.of(
                new Case(Mode.CHANGED_ONLY, true, true, TRIAL, fresh, "published-001", "REUSED"),
                new Case(Mode.CHANGED_ONLY, true, true, OTHER, fresh, "published-001", "READ_NOT_IN_CHANGED_ONLY_SET"),
                new Case(Mode.CHANGED_ONLY, true, true, TRIAL, stale, "published-001", "READ_EVIDENCE_TOO_OLD"),
                new Case(Mode.CHANGED_ONLY, true, true, TRIAL, fresh, "published-002", "READ_SIGNAL_CHANGED"),
                new Case(Mode.FULL, true, true, TRIAL, fresh, "published-001", "READ_FULL_MODE"),
                // Manual and other automatic changed-only requests keep today's behaviour for every domain.
                new Case(Mode.CHANGED_ONLY, false, false, OTHER, stale, "published-001", "REUSED"),
                new Case(Mode.CHANGED_ONLY, true, false, OTHER, stale, "published-001", "REUSED"))) {
            var repository = mock(PolicyCollectionRepository.class);
            when(repository.saveDomain(any(), anyString(), anyString(), anyBoolean(), nullable(String.class), anyString(), anyInt(),
                nullable(String.class), anyString())).thenReturn(true);
            var previousSignal = new CpDomainReuse.Signal(c.signalUid(), 1791158400000L, "2026-10-05T00:00+0000", "2026-10-05T00:00:00Z");
            when(repository.previousDomain("mds-1", c.domain())).thenReturn(Optional.of(new PolicyCollectionRepository.DomainRun(true,
                json.writeValueAsString(previousSignal), json.writeValueAsString(List.of(snapshot(c.domain(), c.collectedAt()))))));
            var request = new PolicyCollectionRepository.Request("mds-1", "", c.automatic(), c.mode(), "job-" + c.decision(), 1, c.scheduled());
            var reuse = new CpDomainReuse(repository, request, trial, () -> now);
            var decisions = new LinkedHashMap<String, PolicyCollectionTrace.DomainDecision>();
            try (var trace = new PolicyCollectionTrace("run-1", (step, total) -> {})) {
                reuse.begin(c.domain());
                var reused = reuse.decide(c.domain(), json.readTree(SIGNAL));
                assertEquals(c.decision().equals("REUSED"), reused != null, c.toString());
                if (reused == null) reuse.planned(c.domain(), 3);
                reuse.record();
                decisions.putAll(trace.decisions());
            }
            var decision = decisions.get(c.domain());
            assertEquals(c.decision(), decision.decision(), c.toString());
            assertEquals(c.decision().equals("REUSED") ? 1 : 0, decision.packagesReused(), c.toString());
            assertEquals(c.decision().equals("REUSED") ? 0 : 3, decision.packagesRead(), c.toString());
        }
    }

    @Test void scheduledRunReusesOnlyTheTrialDomainInBothCollectors() throws Exception {
        for (int sessions : List.of(1, 4)) {
            String trialRef = PolicySnapshot.ref("mds-1", "domain-trial"), otherRef = PolicySnapshot.ref("mds-1", "domain-other");
            System.setProperty("ui2.policy.cp.changed-only-domains", trialRef);
            var repository = mock(PolicyCollectionRepository.class);
            var signal = CpDomainReuse.signal(json.readTree(SIGNAL));
            String recent = Instant.now().minus(Duration.ofHours(5)).toString();
            for (String ref : List.of(trialRef, otherRef))
                when(repository.previousDomain("mds-1", ref)).thenReturn(Optional.of(new PolicyCollectionRepository.DomainRun(true,
                    json.writeValueAsString(signal), json.writeValueAsString(List.of(snapshot(ref, recent))))));
            when(repository.domainForRequest(any(), anyString())).thenReturn(Optional.empty());
            when(repository.beginDomain(any(PolicyCollectionRepository.Request.class), anyString())).thenReturn(true);
            when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of());
            when(repository.inventoryFresh(anyString(), anyString(), anyString(), any())).thenReturn(true);
            when(repository.saveUnit(any(), anyString(), anyString(), anyString())).thenReturn(true);
            when(repository.saveDomain(any(), anyString(), anyString(), anyBoolean(), nullable(String.class), anyString(), anyInt(),
                nullable(String.class), anyString())).thenReturn(true);
            List<String> commands = new CopyOnWriteArrayList<>();
            var transport = mock(DeviceTransport.class);
            when(transport.connect(any(), any(), any())).thenAnswer(call -> new ConnectResult.Authenticated(() -> UUID.randomUUID().toString()));
            when(transport.execInteractive(any(), any(), any())).thenAnswer(call -> {
                String command = ((ExecSpec) call.getArgument(1)).command(); commands.add(command);
                if (command.contains("show-domains")) return ok("{\"total\":2,\"objects\":[{\"uid\":\"domain-trial\",\"name\":\"DOM-TANGO-01\"},"
                    + "{\"uid\":\"domain-other\",\"name\":\"DOM-BRAVO-02\"}]}");
                if (command.contains("show-last-published-session")) return ok(SIGNAL);
                if (command.contains("show-packages")) return ok("{\"total\":1,\"from\":1,\"to\":1,\"packages\":[{\"uid\":\"package-1\",\"name\":\"OBJ-POLICY-01\","
                    + "\"access-layers\":[{\"uid\":\"layer-1\",\"name\":\"OBJ-LAYER-01\"}],\"installation-targets\":[]}]}");
                return ok("{\"uid\":\"layer-1\",\"total\":0,\"rulebase\":[]}");
            });
            var gates = (GateRegistryPort) key -> GateRegistryFixtureLoader.loadFromStream(getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"))
                .stream().filter(row -> row.key().equals(key) && !row.gateId().equals("cp_policy_access_rulebase_hits")).toList();
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofMinutes(5), sessions);
            var run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", PolicyCollectionRepository.SCHEDULED_REFRESH_ACTOR,
                DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            var request = new PolicyCollectionRepository.Request("mds-1", "", true, Mode.CHANGED_ONLY, "job-scheduled", 1, true);
            Map<String, PolicyCollectionTrace.DomainDecision> decisions;
            try (var trace = new PolicyCollectionTrace("run-1", (step, total) -> {})) {
                collector.collect(run, request, () -> true, snapshot -> {}, failure -> {});
                decisions = trace.decisions();
                assertTrue(trace.responseBytes() > 0);
            }
            assertEquals("REUSED", decisions.get(trialRef).decision(), "sessions=" + sessions);
            assertEquals("READ_NOT_IN_CHANGED_ONLY_SET", decisions.get(otherRef).decision(), "sessions=" + sessions);
            assertEquals(1, commands.stream().filter(c -> c.contains("show-packages")).count(), "only the other domain lists packages");
            assertTrue(commands.stream().filter(c -> c.contains("show-packages")).allMatch(c -> c.contains("-d 'DOM-BRAVO-02'")));
            assertEquals(2, commands.stream().filter(c -> c.contains("show-last-published-session")).count());
            verify(repository, atLeastOnce()).saveDomain(any(), eq(trialRef), eq("REUSED"), eq(true), anyString(), anyString(), anyInt(), nullable(String.class), anyString());
            System.clearProperty("ui2.policy.cp.changed-only-domains");
        }
    }

    @Test void everyCheckPointRunRecordsModeOriginDomainsPackagesBytesAndDuration() {
        var leases = mock(JobLeaseRepository.class);
        var attempts = mock(JobStepAttemptRepository.class);
        var runs = mock(DiscoveryRunRepository.class);
        var repository = mock(PolicyCollectionRepository.class);
        var cp = mock(CheckPointPolicyCollector.class);
        var run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", PolicyCollectionRepository.SCHEDULED_REFRESH_ACTOR,
            DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(runs.findRun("run-1")).thenReturn(Optional.of(run));
        when(repository.request("job-1")).thenReturn(Optional.of(new PolicyCollectionRepository.Request("mds-1", "", true, Mode.CHANGED_ONLY, "job-1", 0, true)));
        when(repository.eligible("mds-1", "run-1")).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt())).thenReturn("attempt-1");
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), isNull(), anyBoolean(), nullable(Long.class), isNull(), isNull())).thenReturn(true);
        when(repository.publish(anyString(), anyLong(), anyList(), anyString())).thenReturn(true);
        doAnswer(call -> {
            PolicyCollectionTrace.result(System.nanoTime(), 1_234, "Completed");
            PolicyCollectionTrace.result(System.nanoTime(), 66, "Completed");
            PolicyCollectionTrace.domainDecision(TRIAL, "REUSED", 2, 0);
            PolicyCollectionTrace.domainDecision(OTHER, "READ_NOT_IN_CHANGED_ONLY_SET", 0, 21);
            return List.of();
        }).when(cp).collect(any(), any(), any(), any(), any(), any());
        var executor = new PolicyCollectionJobExecutor(leases, attempts, runs, repository, cp, key -> List.of());
        assertInstanceOf(JobOutcome.Completed.class, executor.execute("job-1", 1, "run-1"));
        verify(attempts).insertPreContact(eq("job-1"), eq(1L), eq(PolicyCollectionJobExecutor.RUN_RECORD_STEP - 1),
            eq("POLICY_DOMAIN_" + TRIAL + "_REUSED_2_0"), eq("read"), eq(1));
        verify(attempts).insertPreContact(eq("job-1"), eq(1L), eq(PolicyCollectionJobExecutor.RUN_RECORD_STEP - 2),
            eq("POLICY_DOMAIN_" + OTHER + "_READ_NOT_IN_CHANGED_ONLY_SET_0_21"), eq("read"), eq(1));
        verify(attempts).insertPreContact(eq("job-1"), eq(1L), eq(PolicyCollectionJobExecutor.RUN_RECORD_STEP),
            startsWith("POLICY_RUN_CHANGED_ONLY_SCHEDULED_1_1_2_21_"), eq("read"), eq(1));
        verify(attempts).writeOutcome(anyString(), eq(1L), eq("MATCHED"), isNull(), eq(true), eq(1_300L), isNull(), isNull());
        // The record precedes the terminal publication: it is written while the job still holds its lease.
        var order = inOrder(attempts, repository);
        order.verify(attempts).insertPreContact(eq("job-1"), eq(1L), eq(PolicyCollectionJobExecutor.RUN_RECORD_STEP), anyString(), anyString(), anyInt());
        order.verify(repository).publish(anyString(), anyLong(), anyList(), anyString());
    }

    private static PolicySnapshot snapshot(String domain, String collectedAt) {
        var metadata = new PolicySnapshot.Metadata("policy-" + domain, "mds-1", "MDS mds-1", "CP", domain, "DOM-TANGO-01",
            "OBJ-POLICY-01", collectedAt, "", List.of());
        return new PolicySnapshot(metadata, List.of(), Map.of());
    }
    private static ExecResult ok(String body) { return new ExecResult.Completed(body, 0); }
}
