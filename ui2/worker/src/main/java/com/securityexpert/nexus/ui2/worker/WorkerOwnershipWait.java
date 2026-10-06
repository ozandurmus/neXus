package com.securityexpert.nexus.ui2.worker;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Bounded startup wait; ownership decisions remain in the repository. */
final class WorkerOwnershipWait {
    static final String TIMEOUT_PROPERTY = "ui2.worker.owner-wait-millis";
    static final long DEFAULT_TIMEOUT_MILLIS = 150_000;
    private static final long POLL_MILLIS = 5_000;

    private WorkerOwnershipWait() { }

    static void await(String role, BooleanSupplier heartbeat) {
        await(role, heartbeat, Long.parseLong(System.getProperty(
                TIMEOUT_PROPERTY, Long.toString(DEFAULT_TIMEOUT_MILLIS))),
                () -> System.nanoTime() / 1_000_000, millis -> {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("MODULE_OWNER_CONFLICT", e);
                    }
                });
    }

    static void await(String role, BooleanSupplier heartbeat, long timeoutMillis,
                      LongSupplier clockMillis, LongConsumer sleeper) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("OWNER_WAIT_TIMEOUT_NEGATIVE");
        long started = clockMillis.getAsLong();
        int attempt = 0;
        while (true) {
            long elapsed = clockMillis.getAsLong() - started;
            if (attempt > 0 && elapsed >= timeoutMillis) {
                throw new IllegalStateException("MODULE_OWNER_CONFLICT");
            }
            System.out.println("Worker ownership role=" + role + " attempt=" + (++attempt)
                    + " elapsed_ms=" + elapsed);
            if (heartbeat.getAsBoolean()) return;
            long remaining = timeoutMillis - (clockMillis.getAsLong() - started);
            if (remaining <= 0) throw new IllegalStateException("MODULE_OWNER_CONFLICT");
            sleeper.accept(Math.min(POLL_MILLIS, remaining));
        }
    }
}
