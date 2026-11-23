package com.securityexpert.nexus.ui2.worker.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class WorkerHealthTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final WorkerHealth health = new WorkerHealth(now::get, Duration.ofSeconds(120), Duration.ofSeconds(60));

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    @Test
    void liveWithNoLoopsSoAPodWaitingForOwnershipIsNotKilled() {
        advance(Duration.ofHours(1));
        assertTrue(health.liveness().ok());
        assertEquals("starting", health.readiness().reason());
    }

    @Test
    void anIdleLoopThatStopsIteratingIsStalled() {
        LoopBeat beat = health.register("general-0");
        advance(Duration.ofSeconds(119));
        assertTrue(health.liveness().ok());
        beat.tick();
        advance(Duration.ofSeconds(119));
        assertTrue(health.liveness().ok());
        advance(Duration.ofSeconds(2));
        WorkerHealth.Status status = health.liveness();
        assertFalse(status.ok());
        assertEquals(503, status.httpStatus());
        assertEquals("stalled", status.reason());
        beat.idle();
        assertTrue(health.liveness().ok());
    }

    @Test
    void aLoopHoldingAJobIsNeverStalledHoweverLongTheJobRuns() {
        LoopBeat beat = health.register("backup-0");
        beat.tick();
        beat.jobStarted();
        advance(Duration.ofHours(3));
        assertTrue(health.liveness().ok());
        beat.idle();
        assertTrue(health.liveness().ok());
        advance(Duration.ofSeconds(121));
        assertFalse(health.liveness().ok());
    }

    @Test
    void oneStalledLoopAmongSeveralFailsLiveness() {
        LoopBeat a = health.register("general-0");
        LoopBeat b = health.register("general-1");
        advance(Duration.ofSeconds(100));
        a.tick();
        advance(Duration.ofSeconds(30));
        assertFalse(health.liveness().ok(), "general-1 last ticked 130 s ago");
        b.tick();
        assertTrue(health.liveness().ok());
    }

    @Test
    void aStoppedLoopNoLongerCounts() {
        LoopBeat beat = health.register("policy");
        beat.stopped();
        advance(Duration.ofHours(1));
        assertTrue(health.liveness().ok());
    }

    @Test
    void aDrainingProcessStaysLiveAndLeavesReadiness() {
        health.register("general-0");
        health.markStarted();
        health.observeHeartbeat(() -> true);
        assertTrue(health.readiness().ok());
        health.markDraining();
        advance(Duration.ofHours(1));
        assertTrue(health.liveness().ok());
        assertEquals("draining", health.liveness().reason());
        assertFalse(health.readiness().ok());
        assertEquals("draining", health.readiness().reason());
    }

    @Test
    void readinessFollowsTheLastModuleHeartbeat() {
        health.markStarted();
        assertEquals("db_unreachable", health.readiness().reason());
        assertTrue(health.observeHeartbeat(() -> true));
        assertTrue(health.readiness().ok());
        // Not the owner is still a database answer: reachable.
        assertFalse(health.observeHeartbeat(() -> false));
        assertTrue(health.readiness().ok());
        RuntimeException down = new IllegalStateException("connection refused");
        assertThrows(IllegalStateException.class, () -> health.observeHeartbeat(() -> { throw down; }));
        assertEquals("db_unreachable", health.readiness().reason());
        // A database outage never fails liveness.
        assertTrue(health.liveness().ok());
        health.observeHeartbeat(() -> true);
        advance(Duration.ofSeconds(61));
        assertEquals("db_stale", health.readiness().reason());
    }

    @Test
    void notReadyBeforeTheLoopsStartEvenWithTheDatabaseReachable() {
        health.observeHeartbeat(() -> false);
        assertEquals("starting", health.readiness().reason());
        health.markStarted();
        assertTrue(health.readiness().ok());
    }

    @Test
    void environmentOverridesTheWindows() {
        WorkerHealth tuned = WorkerHealth.fromEnvironment(Map.of("UI2_HEALTH_STALL_SECONDS", "300")::get);
        assertTrue(tuned.liveness().ok());
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerHealth(now::get, Duration.ZERO, Duration.ofSeconds(1)));
    }

    @Test
    void theNoopBeatDoesNothing() {
        LoopBeat.NONE.tick();
        LoopBeat.NONE.jobStarted();
        LoopBeat.NONE.idle();
        LoopBeat.NONE.stopped();
    }
}
