package com.securityexpert.nexus.ui2.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkerOwnershipWaitTest {
    @Test void systemPropertyOverridesTheDefaultBound() {
        String previous = System.getProperty(WorkerOwnershipWait.TIMEOUT_PROPERTY);
        try {
            System.setProperty(WorkerOwnershipWait.TIMEOUT_PROPERTY, "0");
            var attempts = new AtomicInteger();
            var error = assertThrows(IllegalStateException.class, () -> WorkerOwnershipWait.await(
                    "policy", () -> { attempts.incrementAndGet(); return false; }));
            assertEquals("MODULE_OWNER_CONFLICT", error.getMessage());
            assertEquals(1, attempts.get());
        } finally {
            if (previous == null) System.clearProperty(WorkerOwnershipWait.TIMEOUT_PROPERTY);
            else System.setProperty(WorkerOwnershipWait.TIMEOUT_PROPERTY, previous);
        }
    }

    @Test void retriesUntilOwnershipIsGrantedForBothRoles() {
        for (String role : new String[]{"general", "policy"}) {
            var clock = new AtomicLong();
            var attempts = new AtomicInteger();
            var sleeps = new ArrayList<Long>();
            WorkerOwnershipWait.await(role, () -> attempts.incrementAndGet() > 3,
                    150_000, clock::get, millis -> {
                        sleeps.add(millis);
                        clock.addAndGet(millis);
                    });
            assertEquals(4, attempts.get());
            assertEquals(java.util.List.of(5_000L, 5_000L, 5_000L), sleeps);
            assertEquals(15_000, clock.get());
        }
    }

    @Test void failsClosedAtTheBoundForBothRoles() {
        for (String role : new String[]{"general", "policy"}) {
            var clock = new AtomicLong();
            var attempts = new AtomicInteger();
            var error = assertThrows(IllegalStateException.class, () -> WorkerOwnershipWait.await(
                    role, () -> { attempts.incrementAndGet(); return false; },
                    WorkerOwnershipWait.DEFAULT_TIMEOUT_MILLIS, clock::get, clock::addAndGet));
            assertEquals("MODULE_OWNER_CONFLICT", error.getMessage());
            assertEquals(150_000, clock.get());
            assertEquals(30, attempts.get());
        }
    }

    @Test void clipsTheLastSleepToTheConfiguredBound() {
        var clock = new AtomicLong();
        var sleeps = new ArrayList<Long>();
        assertThrows(IllegalStateException.class, () -> WorkerOwnershipWait.await(
                "general", () -> false, 7_000, clock::get, millis -> {
                    sleeps.add(millis);
                    clock.addAndGet(millis);
                }));
        assertEquals(java.util.List.of(5_000L, 2_000L), sleeps);
        assertEquals(7_000, clock.get());
    }

    @Test void heartbeatTimeCountsTowardsTheBound() {
        var clock = new AtomicLong();
        assertThrows(IllegalStateException.class, () -> WorkerOwnershipWait.await(
                "policy", () -> { clock.addAndGet(150_000); return false; },
                150_000, clock::get, millis -> fail("No sleep after the deadline")));
    }

    @Test void zeroBoundAllowsOnlyTheInitialAttempt() {
        WorkerOwnershipWait.await("general", () -> true, 0, () -> 0, millis -> fail("No sleep"));
        var error = assertThrows(IllegalStateException.class, () -> WorkerOwnershipWait.await(
                "general", () -> false, 0, () -> 0, millis -> fail("No sleep")));
        assertEquals("MODULE_OWNER_CONFLICT", error.getMessage());
    }

    @Test void negativeBoundIsRejectedBeforeHeartbeat() {
        assertThrows(IllegalArgumentException.class, () -> WorkerOwnershipWait.await(
                "general", () -> { fail("No heartbeat"); return true; },
                -1, () -> 0, millis -> fail("No sleep")));
    }
}
