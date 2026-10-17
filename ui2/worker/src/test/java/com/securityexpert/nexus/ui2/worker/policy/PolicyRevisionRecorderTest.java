package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.securityexpert.nexus.ui2.persistence.policy.PolicyRevisionRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyRevisionRepository.Latest;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyRevisionRepository.Revision;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyRevisionRepository.RuleChanges;

class PolicyRevisionRecorderTest {
    private static final String CP = PolicyRevisionRecorder.CP_MARKER, PAN = PolicyRevisionRecorder.PAN_MARKER;
    private static final String M1 = "{\"uid\":\"s1\",\"publish_time\":\"2026-10-17T21:00:00+0300\",\"posix\":1}";
    private static final String M2 = "{\"uid\":\"s2\",\"publish_time\":\"2026-10-18T06:00:00+0300\",\"posix\":2}";
    private static final Instant SINCE = Instant.parse("2026-10-18T03:00:00Z");

    @Test void anEqualMarkerIsUnchangedAndKeepsTheRevisionNumber() {
        var v = PolicyRevisionRecorder.verdict(Optional.of(new Latest(4, CP, M1)), CP, M1, null, null);
        assertEquals("UNCHANGED", v.state()); assertNull(v.reason()); assertEquals(4, v.revisionNo());
    }

    @Test void aDifferentMarkerIsChangedAndRaisesTheRevisionNumberByOne() {
        var v = PolicyRevisionRecorder.verdict(Optional.of(new Latest(4, CP, M1)), CP, M2, null, null);
        assertEquals("CHANGED", v.state()); assertEquals(5, v.revisionNo());
    }

    @Test void markerKeyOrderDoesNotMakeAChange() {
        String reordered = "{\"posix\":1,\"publish_time\":\"2026-10-17T21:00:00+0300\",\"uid\":\"s1\"}";
        assertEquals("UNCHANGED", PolicyRevisionRecorder.verdict(Optional.of(new Latest(1, CP, M1)), CP, reordered, null, null).state());
    }

    @Test void aMissingMarkerIsUnknownNeverUnchanged() {
        var v = PolicyRevisionRecorder.verdict(Optional.of(new Latest(4, CP, M1)), CP, null, null, "SIGNAL_UNAVAILABLE");
        assertEquals("UNKNOWN", v.state()); assertEquals("SIGNAL_UNAVAILABLE", v.reason()); assertEquals(4, v.revisionNo());
        assertEquals("MARKER_UNAVAILABLE", PolicyRevisionRecorder.verdict(Optional.empty(), CP, null, null, null).reason());
    }

    @Test void theFirstObservationIsUnknownAndStartsAtRevisionOne() {
        var v = PolicyRevisionRecorder.verdict(Optional.empty(), PAN, "{\"content_version\":\"abc\"}", null, null);
        assertEquals("UNKNOWN", v.state()); assertEquals("NO_PREVIOUS_MARKER", v.reason()); assertEquals(1, v.revisionNo());
    }

    @Test void theCollectorsOwnEarlierMarkerIsUsedOnlyWhenThereIsNoEarlierRevision() {
        var fromFallback = PolicyRevisionRecorder.verdict(Optional.empty(), CP, M2, M1, null);
        assertEquals("CHANGED", fromFallback.state()); assertEquals(2, fromFallback.revisionNo());
        var revisionWins = PolicyRevisionRecorder.verdict(Optional.of(new Latest(3, CP, M2)), CP, M2, M1, null);
        assertEquals("UNCHANGED", revisionWins.state()); assertEquals(3, revisionWins.revisionNo());
    }

    @Test void aMarkerOfAnotherKindOrAnUnreadableOneIsUnknown() {
        assertEquals("MARKER_KIND_CHANGED", PolicyRevisionRecorder.verdict(Optional.of(new Latest(2, PAN, M1)), CP, M2, null, null).reason());
        assertEquals("MARKER_UNREADABLE", PolicyRevisionRecorder.verdict(Optional.of(new Latest(2, CP, "not json")), CP, M2, null, null).reason());
        assertEquals("NO_PREVIOUS_MARKER", PolicyRevisionRecorder.verdict(Optional.of(new Latest(2, null, "{}")), CP, M2, null, null).reason());
    }

    @Test void oneRowPerContainerWithCountsOnlyWhereMeasured() {
        var repository = mock(PolicyRevisionRepository.class);
        when(repository.latest(eq("mds-1"), eq("d1"), anyString())).thenReturn(Optional.of(new Latest(2, CP, M1)));
        when(repository.latest(eq("mds-1"), eq("d2"), anyString())).thenReturn(Optional.of(new Latest(7, CP, M1)));
        when(repository.latest(eq("mds-1"), eq("d3"), anyString())).thenReturn(Optional.empty());
        when(repository.ruleChanges(anyString(), anyString(), any())).thenReturn(new RuleChanges(3, 2, 1));
        when(repository.record(any())).thenReturn(true);
        var observations = new PolicyObservations();
        observations.add(new PolicyObservations.Cp("d1", "DOM-1", M2, null, false, true, 120));
        observations.add(new PolicyObservations.Cp("d2", "DOM-2", M1, null, true, true, 80));
        observations.add(new PolicyObservations.Cp("d3", "DOM-3", null, null, false, false, 5));
        var diff = new CpShowChangesReader.Result("COLLECTED", null, 1, 4, 5, 6, 2, 0);
        int written = new PolicyRevisionRecorder(repository).record("mds-1", "job-1", SINCE, observations, Map.of("d1", diff));
        assertEquals(3, written);
        var rows = ArgumentCaptor.forClass(Revision.class);
        verify(repository, times(3)).record(rows.capture());
        Revision changed = rows.getAllValues().get(0), unchanged = rows.getAllValues().get(1), unknown = rows.getAllValues().get(2);
        assertEquals("CHANGED", changed.changeState()); assertEquals(3, changed.revisionNo());
        assertEquals("check_point", changed.vendor()); assertEquals("domain", changed.containerKind());
        assertEquals(CP, changed.markerKind()); assertEquals(M2, changed.markerJson());
        assertEquals(120, changed.rulesTotal()); assertEquals(3, changed.rulesAdded()); assertEquals(2, changed.rulesModified());
        assertEquals(1, changed.rulesRemoved());
        assertEquals(4, changed.objectsAdded()); assertEquals(5, changed.objectsModified()); assertEquals(6, changed.objectsRemoved());
        assertNull(changed.natRules(), "no NAT parse yet: UNKNOWN, not zero");
        assertEquals("UNCHANGED", unchanged.changeState()); assertEquals(7, unchanged.revisionNo());
        assertNull(unchanged.objectsAdded(), "no show-changes read for this domain: UNKNOWN, not zero");
        assertEquals("UNKNOWN", unknown.changeState()); assertEquals("SIGNAL_UNAVAILABLE", unknown.unknownReason());
        assertFalse(unknown.complete());
        assertNull(unknown.rulesTotal()); assertNull(unknown.rulesAdded());
        assertEquals("{}", unknown.markerJson()); assertNull(unknown.markerKind());
        verify(repository).refreshEvidenceCounts("job-1");
        verify(repository, never()).ruleChanges(eq("mds-1"), eq("d3"), any());
    }

    @Test void aDeviceGroupGetsItsContentVersionAsMarkerAndAFailureIsUnknownWithItsCode() {
        var repository = mock(PolicyRevisionRepository.class);
        String old = "{\"content_version\":\"aaa\"}";
        when(repository.latest(eq("pan-1"), eq("g1"), anyString())).thenReturn(Optional.of(new Latest(1, PAN, old)));
        when(repository.latest(eq("pan-1"), eq("g2"), anyString())).thenReturn(Optional.of(new Latest(1, PAN, old)));
        when(repository.ruleChanges(anyString(), anyString(), any())).thenReturn(new RuleChanges(0, 0, 0));
        when(repository.record(any())).thenReturn(true);
        var observations = new PolicyObservations();
        observations.add(new PolicyObservations.Pan("g1", "bbb", true, null, 10));
        observations.add(new PolicyObservations.Pan("g2", null, false, "SIZE_LIMIT", 0));
        new PolicyRevisionRecorder(repository).record("pan-1", "job-2", SINCE, observations, Map.of());
        var rows = ArgumentCaptor.forClass(Revision.class);
        verify(repository, times(2)).record(rows.capture());
        assertEquals("CHANGED", rows.getAllValues().get(0).changeState());
        assertEquals("{\"content_version\":\"bbb\"}", rows.getAllValues().get(0).markerJson());
        assertEquals("palo_alto", rows.getAllValues().get(0).vendor()); assertEquals("device_group", rows.getAllValues().get(0).containerKind());
        assertEquals(0, rows.getAllValues().get(0).rulesAdded(), "a measured zero is a zero");
        assertEquals("UNKNOWN", rows.getAllValues().get(1).changeState());
        assertEquals("SIZE_LIMIT", rows.getAllValues().get(1).unknownReason());
        assertEquals(1, rows.getAllValues().get(1).revisionNo());
    }

    @Test void nothingIsWrittenOrLinkedWhenNothingWasObserved() {
        var repository = mock(PolicyRevisionRepository.class);
        assertEquals(0, new PolicyRevisionRecorder(repository).record("s", "j", SINCE, new PolicyObservations(), Map.of()));
        verify(repository, never()).refreshEvidenceCounts(anyString());
    }

    @Test void theBaselineIsTheEarlierRevisionThenTheCollectorsOwnMarker() {
        var repository = mock(PolicyRevisionRepository.class);
        when(repository.latest("s", "c1", "j")).thenReturn(Optional.of(new Latest(1, CP, M1)));
        when(repository.latest("s", "c2", "j")).thenReturn(Optional.empty());
        var recorder = new PolicyRevisionRecorder(repository);
        assertEquals(Optional.of(M1), recorder.baseline("s", "c1", "j", M2));
        assertEquals(Optional.of(M2), recorder.baseline("s", "c2", "j", M2));
        assertEquals(Optional.empty(), recorder.baseline("s", "c2", "j", null));
    }
}
