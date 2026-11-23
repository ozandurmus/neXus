package com.securityexpert.nexus.ui2.service.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BackgroundSnapshotTest {

    /** A clock the test moves by hand. */
    static final class MovableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-14T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void firstReadComputesThenLaterReadsReturnTheValueWithoutComputing() {
        MovableClock clock = new MovableClock();
        AtomicInteger computes = new AtomicInteger();
        var snapshot = new BackgroundSnapshot<>("t", computes::incrementAndGet, Duration.ofSeconds(10), Duration.ofMinutes(5), clock);
        var first = snapshot.get();
        assertEquals(1, first.value());
        assertEquals(clock.now, first.computedAt());
        clock.now = clock.now.plusSeconds(5);
        assertSame(first, snapshot.get());
        assertEquals(1, computes.get());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aStaleReadReturnsTheOldValueAtOnceAndRefreshesInTheBackground() throws Exception {
        MovableClock clock = new MovableClock();
        AtomicInteger computes = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch refreshed = new CountDownLatch(1);
        var snapshot = new BackgroundSnapshot<>("t", () -> {
            int n = computes.incrementAndGet();
            if (n > 1) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                refreshed.countDown();
            }
            return n;
        }, Duration.ofMinutes(1), Duration.ofMinutes(10), clock);
        snapshot.get();
        clock.now = clock.now.plusSeconds(61);
        // the refresh is blocked: the reader still gets the old value immediately, and a second stale read starts no second refresh
        assertEquals(1, snapshot.get().value());
        assertEquals(1, snapshot.get().value());
        release.countDown();
        assertTrue(refreshed.await(5, TimeUnit.SECONDS));
        awaitRefreshIdle(snapshot);
        assertEquals(2, snapshot.get().value());
        assertEquals(2, computes.get());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aFailedRefreshKeepsThePreviousValue() throws Exception {
        MovableClock clock = new MovableClock();
        AtomicInteger computes = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        var snapshot = new BackgroundSnapshot<>("t", () -> {
            if (computes.incrementAndGet() > 1) {
                failed.countDown();
                throw new IllegalStateException("read failed");
            }
            return "first";
        }, Duration.ofMinutes(1), Duration.ofMinutes(10), clock);
        snapshot.get();
        clock.now = clock.now.plusSeconds(61);
        assertEquals("first", snapshot.get().value());
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        awaitRefreshIdle(snapshot);
        assertEquals("first", snapshot.get().value());
    }

    private static void awaitRefreshIdle(BackgroundSnapshot<?> snapshot) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (snapshot.isRefreshing()) {
            assertTrue(deadline - System.nanoTime() > 0, "timed out waiting for background refresh to finish");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    @Test
    void aFirstReadFailureReachesTheCaller() {
        var snapshot = new BackgroundSnapshot<String>("t", () -> {
            throw new IllegalStateException("no value");
        }, Duration.ofSeconds(10), Duration.ofMinutes(5));
        assertThrows(IllegalStateException.class, snapshot::get);
    }

    @Test
    void invalidateMakesTheNextReadCompute() {
        AtomicInteger computes = new AtomicInteger();
        var snapshot = new BackgroundSnapshot<>("t", computes::incrementAndGet, Duration.ofSeconds(10), Duration.ofMinutes(5));
        snapshot.get();
        snapshot.invalidate();
        assertEquals(2, snapshot.get().value());
    }
}
