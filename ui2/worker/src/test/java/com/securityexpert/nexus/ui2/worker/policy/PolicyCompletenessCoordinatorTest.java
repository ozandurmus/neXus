package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunState;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

class PolicyCompletenessCoordinatorTest {
    private static final String M1 = "{\"uid\":\"s1\",\"publish_time\":\"2026-10-17T21:00:00+0300\",\"posix\":1}";
    private static final String M2 = "{\"uid\":\"s2\",\"publish_time\":\"2026-10-18T06:00:00+0300\",\"posix\":2}";
    private static final Instant START = Instant.parse("2026-10-18T03:05:00Z");
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request request = new PolicyCollectionRepository.Request("mds-1", "", true,
        PolicyCollectionRepository.Mode.CHANGED_ONLY, "job-1", 1);
    private PolicyRevisionRecorder recorder;
    private CpShowChangesReader reader;

    @BeforeEach void setUp() {
        recorder = mock(PolicyRevisionRecorder.class);
        reader = mock(CpShowChangesReader.class);
    }

    private PolicyCompletenessCoordinator coordinator(PolicyCompletenessSettings settings) {
        return new PolicyCompletenessCoordinator(settings, new JobWindowPolicy(Clock.fixed(START, java.time.ZoneOffset.UTC), 60), recorder, reader);
    }

    private static PolicyCompletenessSettings trial(String domain) {
        return new PolicyCompletenessSettings(Optional.empty(), Optional.of(domain), Optional.empty());
    }

    @Test void nothingObservedWritesAndReadsNothing() {
        coordinator(trial("d1")).afterCollection(run, request, new PolicyObservations(), START, () -> true, true);
        verifyNoInteractions(recorder, reader);
    }

    @Test void onlyTheTrialDomainWhoseMarkerChangedIsReadAndEveryDomainGetsARevisionRow() {
        var observations = new PolicyObservations();
        observations.add(new PolicyObservations.Cp("d1", "DOM-1", M2, M1, false, true, 10));
        observations.add(new PolicyObservations.Cp("d2", "DOM-2", M2, M1, false, true, 10));
        when(recorder.baseline(eq("mds-1"), anyString(), eq("job-1"), any())).thenReturn(Optional.of(M1));
        var diff = new CpShowChangesReader.Result("COLLECTED", null, 1, 2, 0, 0, 1, 0);
        when(reader.read(any(), any(), eq("d1"), eq("DOM-1"), eq(M1), eq(M2), eq(START), any())).thenReturn(diff);
        coordinator(trial("d1")).afterCollection(run, request, observations, START, () -> true, true);
        verify(reader, times(1)).read(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), any());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, CpShowChangesReader.Result>> diffs = ArgumentCaptor.forClass(Map.class);
        verify(recorder).record(eq("mds-1"), eq("job-1"), eq(START), same(observations), diffs.capture());
        assertEquals(Map.of("d1", diff), diffs.getValue());
    }

    @Test void anUnchangedReusedOrUnreadableMarkerNeverAsksTheMdsForADiff() {
        var observations = new PolicyObservations();
        observations.add(new PolicyObservations.Cp("d1", "DOM-1", M1, M1, false, true, 10));   // same as baseline
        observations.add(new PolicyObservations.Cp("d2", "DOM-2", M2, M1, true, true, 10));    // reused
        observations.add(new PolicyObservations.Cp("d3", "DOM-3", null, M1, false, true, 10)); // no signal
        when(recorder.baseline(any(), any(), any(), any())).thenReturn(Optional.of(M1));
        for (String domain : new String[] {"d1", "d2", "d3"})
            coordinator(trial(domain)).afterCollection(run, request, observations, START, () -> true, true);
        verifyNoInteractions(reader);
        verify(recorder, times(3)).record(any(), any(), any(), any(), eq(Map.of()));
    }

    @Test void withNoEarlierMarkerThereIsNothingToDiffAgainst() {
        var observations = new PolicyObservations();
        observations.add(new PolicyObservations.Cp("d1", "DOM-1", M2, null, false, true, 10));
        when(recorder.baseline(any(), any(), any(), any())).thenReturn(Optional.empty());
        coordinator(trial("d1")).afterCollection(run, request, observations, START, () -> true, true);
        verifyNoInteractions(reader);
    }

    @Test void withoutASettingTheReaderIsNeverUsedButTheRevisionRowsAreStillWritten() {
        var observations = new PolicyObservations();
        observations.add(new PolicyObservations.Cp("d1", "DOM-1", M2, M1, false, true, 10));
        coordinator(PolicyCompletenessSettings.OFF).afterCollection(run, request, observations, START, () -> true, true);
        verifyNoInteractions(reader);
        verify(recorder).record(any(), any(), any(), same(observations), eq(Map.of()));
    }

    @Test void afterAFailedCollectionOnlyTheRevisionRowsAreWritten() {
        var observations = new PolicyObservations();
        observations.add(new PolicyObservations.Cp("d1", "DOM-1", M2, M1, false, false, 3));
        coordinator(trial("d1")).afterCollection(run, request, observations, START, () -> true, false);
        verifyNoInteractions(reader);
        verify(recorder).record(any(), any(), any(), same(observations), eq(Map.of()));
    }

    @Test void aRecordingFailureNeverFailsThePolicyJobButALostLeaseDoes() {
        var observations = new PolicyObservations();
        observations.add(new PolicyObservations.Pan("g1", "abc", true, null, 1));
        doThrow(new IllegalStateException("synthetic database failure")).when(recorder).record(any(), any(), any(), any(), any());
        assertDoesNotThrow(() -> coordinator(PolicyCompletenessSettings.OFF).afterCollection(run, request, observations, START, () -> true, true));
        doThrow(PolicyCollectionTrace.failure("LEASE_LOST")).when(recorder).record(any(), any(), any(), any(), any());
        assertThrows(RuntimeException.class, () -> coordinator(PolicyCompletenessSettings.OFF).afterCollection(run, request, observations, START, () -> true, true));
    }
}
