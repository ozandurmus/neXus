package com.securityexpert.nexus.ui2.service.failover;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;

class CpReadinessSchedulerTest {
    private static final Instant START = Instant.parse("2026-10-08T09:00:00Z");
    private final CpFailoverService service = mock(CpFailoverService.class);
    private final JooqCpFailoverRepository store = mock(JooqCpFailoverRepository.class);
    private final Clock clock = mock(Clock.class);
    private final CpFailoverService.ReadinessTarget first =
        new CpFailoverService.ReadinessTarget("cluster-1", "unit-1", "check_point");
    private final CpFailoverService.ReadinessTarget second =
        new CpFailoverService.ReadinessTarget("cluster-2", "unit-2", "palo_alto");
    private final CpReadinessScheduler scheduler = new CpReadinessScheduler(service, store, 2000, clock);

    CpReadinessSchedulerTest() {
        when(clock.instant()).thenReturn(START);
        when(service.readinessTargets()).thenReturn(List.of(first, second));
        when(service.requestScheduledReadiness(first)).thenReturn("run-1");
        when(service.requestScheduledReadiness(second)).thenReturn("run-2");
    }
    private void tick(long seconds) {
        when(clock.instant()).thenReturn(START.plusSeconds(seconds));
        scheduler.run();
    }
    @Test void ticksNeverStartAPassAndCompletionDoesNotStartContinuousPolling() {
        tick(0);
        verifyNoInteractions(service, store);
        scheduler.beginPass();
        verify(service).requestScheduledReadiness(first);
        when(store.finished("run-1")).thenReturn(true);
        tick(5); tick(7);
        verify(service).requestScheduledReadiness(second);
        when(store.finished("run-2")).thenReturn(true);
        tick(10); tick(12); tick(100);
        verify(service).readinessTargets();
        tick(21600);
        scheduler.beginPass();
        verify(service, times(2)).readinessTargets();
    }
    @Test void closingTheWindowStopsSubmissionsWithoutKillingTheInflightRun() {
        scheduler.beginPass();
        tick(3600);
        verify(service, never()).requestScheduledReadiness(second);
        verifyNoInteractions(store);
        tick(21600);
        verify(service).readinessTargets();
    }
    @Test void outsideWindowCannotBeginPass() {
        tick(-60);
        scheduler.beginPass();
        verifyNoInteractions(service, store);
    }
    @Test void capAndPauseArePreserved() {
        scheduler.beginPass();
        tick(599);
        verify(service, never()).requestScheduledReadiness(second);
        tick(600); tick(601);
        verify(service, never()).requestScheduledReadiness(second);
        tick(602);
        verify(service).requestScheduledReadiness(second);
        verify(store, never()).stopPlanned(anyString(), anyString());
    }
    @Test void refusalAdvancesAfterPause() {
        when(service.requestScheduledReadiness(first)).thenThrow(new CpFailoverService.Refusal("RUN_ALREADY_ACTIVE"));
        scheduler.beginPass();
        tick(1);
        verify(service, never()).requestScheduledReadiness(second);
        tick(2);
        verify(service).requestScheduledReadiness(second);
    }
}
