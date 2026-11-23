package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securityexpert.nexus.ui2.persistence.policy.CutoverRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;

/** Q7 flow retention janitor: switch, bounds, per-pass counts, and a failure log that names no assessment. */
class CutoverFlowRetentionTest {
    static final String A = "00000000-0000-4000-8000-0000000000c1", B = "00000000-0000-4000-8000-0000000000c2";
    final CutoverRepository repository = mock(CutoverRepository.class);
    final List<String> lines = new ArrayList<>();

    CutoverFlowRetention janitor(boolean enabled) { return new CutoverFlowRetention(repository, enabled).withLog((level, line) -> lines.add(level + " " + line)); }

    @Test void switchedOffTheJanitorTouchesNothing() {
        janitor(false).tick();
        verifyNoInteractions(repository);
        assertTrue(lines.isEmpty());
    }

    @Test void aPassRemovesExpiredFlowsAndLogsTheCounts() {
        when(repository.expiredAssessmentsWithFlows(90, CutoverFlowRetention.MAX_ASSESSMENTS)).thenReturn(List.of(A, B));
        when(repository.deleteExpiredFlows(A, 90, CutoverFlowRetention.ACTOR)).thenReturn(120);
        when(repository.deleteExpiredFlows(B, 90, CutoverFlowRetention.ACTOR)).thenReturn(7);
        janitor(true).tick();
        verify(repository, never()).deleteAssessment(anyString(), anyString());
        assertEquals(List.of("INFO [CUTOVER_FLOW_RETENTION] pass: 2 assessment(s) past 90 days, 127 flow row(s) removed"), lines);
    }

    @Test void aFailureIsCountedByClassNameOnlyAndTheRestOfThePassContinues() {
        when(repository.expiredAssessmentsWithFlows(90, CutoverFlowRetention.MAX_ASSESSMENTS)).thenReturn(List.of(A, B));
        when(repository.deleteExpiredFlows(A, 90, CutoverFlowRetention.ACTOR))
            .thenThrow(new IllegalStateException("lock timeout on " + A + " segment SEG-ALPHA 192.0.2.9"));
        when(repository.deleteExpiredFlows(B, 90, CutoverFlowRetention.ACTOR)).thenReturn(3);
        var pass = janitor(true).pass();
        assertEquals(new CutoverFlowRetention.Pass(1, 3, 1), pass);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("WARNING [CUTOVER_FLOW_RETENTION] pass: 1 assessment(s) past 90 days, 3 flow row(s) removed; 1 assessment(s) failed (IllegalStateException)"), line);
        for (String secret : List.of(A, B, "SEG-ALPHA", "192.0.2.9", "lock timeout")) assertFalse(line.contains(secret), secret);
    }

    @Test void aFailedReadSkipsThePassWithoutIdentifiers() {
        when(repository.expiredAssessmentsWithFlows(anyInt(), anyInt())).thenThrow(new RuntimeException("relation for " + A));
        assertEquals(new CutoverFlowRetention.Pass(0, 0, 1), janitor(true).pass());
        verify(repository, never()).deleteExpiredFlows(anyString(), anyInt(), anyString());
        assertEquals(1, lines.size()); assertFalse(lines.get(0).contains(A)); assertTrue(lines.get(0).contains("RuntimeException"));
    }

    @Test void thePassStopsStartingAssessmentsOnceTheRowBoundIsReached() {
        var ids = IntStream.range(0, 5).mapToObj(i -> "00000000-0000-4000-8000-00000000000" + i).toList();
        when(repository.expiredAssessmentsWithFlows(90, CutoverFlowRetention.MAX_ASSESSMENTS)).thenReturn(ids);
        when(repository.deleteExpiredFlows(anyString(), eq(90), eq(CutoverFlowRetention.ACTOR))).thenReturn(30_000);
        var pass = janitor(true).pass();
        assertEquals(new CutoverFlowRetention.Pass(2, 60_000, 0), pass);
        verify(repository, times(2)).deleteExpiredFlows(anyString(), anyInt(), anyString());
    }

    @Test void anAssessmentAlreadyClearedByAnotherReplicaIsNotCounted() {
        when(repository.expiredAssessmentsWithFlows(90, CutoverFlowRetention.MAX_ASSESSMENTS)).thenReturn(List.of(A));
        when(repository.deleteExpiredFlows(A, 90, CutoverFlowRetention.ACTOR)).thenReturn(0);
        assertEquals(new CutoverFlowRetention.Pass(0, 0, 0), janitor(true).pass());
    }

    @Test void theReadSideAsksTheRepositoryWithTheRatifiedNinetyDays() {
        when(repository.flowsExpired(A, 90)).thenReturn(true);
        assertTrue(janitor(false).flowsExpired(A));
        assertEquals(90, CutoverFlowRetention.RETENTION_DAYS);
    }
}
