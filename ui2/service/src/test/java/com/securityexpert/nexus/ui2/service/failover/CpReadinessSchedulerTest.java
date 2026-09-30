package com.securityexpert.nexus.ui2.service.failover;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;

class CpReadinessSchedulerTest {
    private static final Duration CADENCE = Duration.ofHours(4);
    private static final Instant START = Instant.parse("2026-09-30T00:00:00Z");
    private final CpFailoverService service = mock(CpFailoverService.class);
    private final JooqCpFailoverRepository store = mock(JooqCpFailoverRepository.class);
    private final Clock clock = mock(Clock.class);
    private final CpFailoverService.ReadinessTarget first =
        new CpFailoverService.ReadinessTarget("cluster-1", "unit-1", "check_point");
    private final CpFailoverService.ReadinessTarget second =
        new CpFailoverService.ReadinessTarget("cluster-2", "unit-2", "palo_alto");
    private final CpReadinessScheduler scheduler;

    CpReadinessSchedulerTest() {
        when(clock.instant()).thenReturn(START);
        scheduler = new CpReadinessScheduler(service, store, 2000, CADENCE.toMillis(), clock);
        when(service.readinessTargets()).thenReturn(List.of(first, second));
        when(service.requestScheduledReadiness(first)).thenReturn("run-1");
        when(service.requestScheduledReadiness(second)).thenReturn("run-2");
    }

    private void tick(Instant at) {
        when(clock.instant()).thenReturn(at);
        scheduler.run();
    }

    @Test void firstPassAfterStartDelayThenCadencePreservesOrderCompletionAndPause() {
        Instant due = START.plus(CpReadinessScheduler.FIRST_PASS_DELAY);
        tick(due.minusMillis(1));
        verifyNoInteractions(service, store);
        tick(due);
        verify(service).requestScheduledReadiness(first);
        tick(due.plusSeconds(5));
        verify(service, never()).requestScheduledReadiness(second);
        when(store.finished("run-1")).thenReturn(true);
        tick(due.plusSeconds(10));
        tick(due.plusSeconds(10).plusMillis(1999));
        verify(service, never()).requestScheduledReadiness(second);
        tick(due.plusSeconds(12));
        var order = inOrder(service);
        order.verify(service).requestScheduledReadiness(first);
        order.verify(service).requestScheduledReadiness(second);
        when(store.finished("run-2")).thenReturn(true);
        tick(due.plusSeconds(15));
        Instant end = due.plusSeconds(17);
        tick(end);
        tick(end.plus(CADENCE).minusMillis(1));
        verify(service).readinessTargets();
        tick(end.plus(CADENCE));
        verify(service, times(2)).readinessTargets();
        verify(service, times(2)).requestScheduledReadiness(first);
    }

    @Test void stuckRunReleasesCursorAtHardCapAndStillHonorsPause() {
        Instant due = START.plus(CpReadinessScheduler.FIRST_PASS_DELAY);
        tick(due);
        tick(due.plusSeconds(599));
        verify(service, never()).requestScheduledReadiness(second);
        tick(due.plusSeconds(600));
        tick(due.plusSeconds(601));
        verify(service, never()).requestScheduledReadiness(second);
        tick(due.plusSeconds(602));
        verify(service).requestScheduledReadiness(second);
        verify(store, never()).stopPlanned(anyString(), anyString());
    }

    @Test void refusedUnitDoesNotBlockTheRestOfThePass() {
        when(service.requestScheduledReadiness(first))
            .thenThrow(new CpFailoverService.Refusal("RUN_ALREADY_ACTIVE"));
        Instant due = START.plus(CpReadinessScheduler.FIRST_PASS_DELAY);
        tick(due);
        tick(due.plusSeconds(1));
        verify(service, never()).requestScheduledReadiness(second);
        tick(due.plusSeconds(2));
        verify(service).requestScheduledReadiness(second);
        verifyNoInteractions(store);
    }

    @Test void emptyPassWaitsForTheNextCadence() {
        when(service.readinessTargets()).thenReturn(List.of());
        Instant due = START.plus(CpReadinessScheduler.FIRST_PASS_DELAY);
        tick(due);
        tick(due.plusSeconds(5));
        verify(service).readinessTargets();
        verify(service, never()).requestScheduledReadiness(any());
        tick(due.plus(CADENCE));
        verify(service, times(2)).readinessTargets();
    }
}
