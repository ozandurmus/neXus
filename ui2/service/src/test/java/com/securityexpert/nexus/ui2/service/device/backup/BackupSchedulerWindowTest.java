package com.securityexpert.nexus.ui2.service.device.backup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

class BackupSchedulerWindowTest {
    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID).toInstant();
    }

    private static BackupCollectService collector() {
        var collector = mock(BackupCollectService.class);
        when(collector.requestCollectAll(anyString(), anyString()))
                .thenReturn(new BackupCollectService.BulkOutcome(1, 1, 0));
        return collector;
    }

    private static final class PolicyStore implements BackupPolicyRepository {
        String cron = "0 2 * * *";
        boolean enabled = true;
        Optional<Instant> last = Optional.of(at("2026-10-08T06:00:00"));
        final List<Instant> claims = new ArrayList<>();
        BackupPolicy staleRead;

        @Override public Optional<BackupPolicy> find() {
            return Optional.of(staleRead != null ? staleRead : new BackupPolicy("default", enabled, cron,
                    10, 4, last, Instant.EPOCH));
        }

        @Override public boolean claimScheduledRun(Instant dueSince, Instant runAt, String actor, String action) {
            if (!enabled || last.filter(value -> !value.isBefore(dueSince)).isPresent()) return false;
            last = Optional.of(runAt);
            claims.add(runAt);
            return true;
        }

        @Override public boolean update(boolean enabled, String cron, int days, int depth, String actor, String action) {
            throw new UnsupportedOperationException();
        }
    }

    @Test void dailyCronRunsOncePerDayAtSixAcrossAllFourWindows() {
        var store = new PolicyStore();
        var collect = collector();
        var scheduler = new BackupScheduler(store, collect);
        var expected = new ArrayList<Instant>();
        for (int day = 9; day <= 15; day++) {
            var date = LocalDate.of(2026, 10, day);
            expected.add(date.atTime(6, 0).atZone(JobWindowPolicy.ZONE_ID).toInstant());
            for (int hour : new int[] {0, 6, 12, 18}) {
                var now = date.atTime(hour, 0).atZone(JobWindowPolicy.ZONE_ID).toInstant();
                scheduler.tick(now);
                scheduler.tick(now.plusSeconds(20));
            }
        }
        // Before the fix this records 28 claims: one at every window, instead of seven daily runs.
        assertEquals(expected, store.claims);
        verify(collect, times(7)).requestCollectAll(anyString(), anyString());
    }

    @Test void restartAndStaleReplicaCannotClaimAnotherRunOnTheSameLocalDay() {
        var store = new PolicyStore();
        var collect = collector();
        var stale = store.find().orElseThrow();
        assertTrue(new BackupScheduler(store, collect).tick(at("2026-10-09T06:00:20")).isPresent());
        assertEquals(Optional.of(at("2026-10-09T06:00:20")), store.last);
        assertTrue(new BackupScheduler(store, collect).tick(at("2026-10-09T12:00:00")).isEmpty());
        store.staleRead = stale;
        assertTrue(new BackupScheduler(store, collect).tick(at("2026-10-09T18:00:00")).isEmpty());
        assertEquals(1, store.claims.size());
        verify(collect).requestCollectAll(anyString(), anyString());
    }

    @Test void missedDaysCoalesceAndNeverRunOutsideAWindow() {
        var store = new PolicyStore();
        var collect = collector();
        var scheduler = new BackupScheduler(store, collect);
        assertTrue(scheduler.tick(at("2026-10-15T05:59:59")).isEmpty());
        assertTrue(scheduler.tick(at("2026-10-15T12:00:00")).isPresent());
        assertTrue(scheduler.tick(at("2026-10-15T18:00:00")).isEmpty());
        assertEquals(List.of(at("2026-10-15T12:00:00")), store.claims);
        verify(collect).requestCollectAll(anyString(), anyString());
    }

    @Test void lateEveningCronRunsAtMidnightWithoutASecondRunThatDay() {
        var store = new PolicyStore();
        store.cron = "0 20 * * *";
        var scheduler = new BackupScheduler(store, collector());
        assertTrue(scheduler.tick(at("2026-10-09T00:00:00")).isPresent());
        assertTrue(scheduler.tick(at("2026-10-09T06:00:00")).isEmpty());
        assertTrue(scheduler.tick(at("2026-10-09T12:00:00")).isEmpty());
        assertTrue(scheduler.tick(at("2026-10-09T18:00:00")).isEmpty());
        assertTrue(scheduler.tick(at("2026-10-10T00:00:00")).isPresent());
        assertEquals(2, store.claims.size());
    }

    @Test void frequentCronIsStillCappedAtOnePerCalendarDay() {
        var store = new PolicyStore();
        store.cron = "0 */6 * * *";
        var collect = collector();
        var stale = store.find().orElseThrow();
        var scheduler = new BackupScheduler(store, collect);
        scheduler.tick(at("2026-10-09T00:00:00"));
        store.staleRead = stale;
        scheduler.tick(at("2026-10-09T06:00:00"));
        assertEquals(1, store.claims.size());
    }

    @Test void disabledOrInvalidCronNeverClaims() {
        var store = new PolicyStore();
        var collect = collector();
        var scheduler = new BackupScheduler(store, collect);
        store.enabled = false;
        assertTrue(scheduler.tick(at("2026-10-09T06:00:00")).isEmpty());
        store.enabled = true;
        store.cron = "invalid";
        assertTrue(scheduler.tick(at("2026-10-09T06:00:00")).isEmpty());
        assertTrue(store.claims.isEmpty());
        verifyNoInteractions(collect);
    }

    @Test void weeklyCronArithmeticProducesOnlyOneSundayRunPerWeek() {
        Optional<Instant> last = Optional.of(at("2026-10-04T06:00:00"));
        var runs = new ArrayList<Instant>();
        for (var day = LocalDate.of(2026, 10, 5); day.isBefore(LocalDate.of(2026, 10, 19)); day = day.plusDays(1)) {
            for (int hour : new int[] {0, 6, 12, 18}) {
                var now = day.atTime(hour, 0).atZone(JobWindowPolicy.ZONE_ID).toInstant();
                if (BackupScheduleDue.dueSlot("0 3 * * 0", last, now, 24 * 3600,
                        JobWindowPolicy.ZONE_ID).isPresent()) {
                    runs.add(now);
                    last = Optional.of(now);
                }
            }
        }
        assertEquals(List.of(at("2026-10-11T06:00:00"), at("2026-10-18T06:00:00")), runs);
    }

    @Test void cronUsesIstanbulZoneRulesIncludingHistoricalDst() {
        for (var date : List.of("2015-01-15", "2015-07-15", "2026-01-15", "2026-07-15")) {
            assertEquals(Optional.of(at(date + "T02:00:00")),
                    BackupScheduleDue.dueSlot("0 2 * * *", Optional.empty(), at(date + "T06:00:00"),
                            24 * 3600, JobWindowPolicy.ZONE_ID));
        }
    }
}
