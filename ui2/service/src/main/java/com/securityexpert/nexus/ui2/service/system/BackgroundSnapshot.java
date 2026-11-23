package com.securityexpert.nexus.ui2.service.system;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A read-mostly answer computed off the request path (API latency work, 2026-11-14: the top-bar health light read
 * {@code /api/v2/system/pods} on every screen and waited 1.1-1.3 s for it each time).
 * <ul>
 * <li>{@link #get()} never computes while a value exists: it returns the last value at once. When that value is older
 * than {@code freshFor} it also starts one background refresh (never two at a time); the caller still gets the older
 * value, whose age it can state from {@link Value#computedAt()}.</li>
 * <li>While somebody asked within {@code keepWarmFor}, a background tick refreshes the value every {@code freshFor},
 * so the value a request sees is at most about {@code freshFor} plus one compute old. With no demand the ticks do
 * nothing and the database is not read.</li>
 * <li>Only the very first call (no value yet: pod start) computes on the caller's thread. A failed compute keeps the
 * previous value; with no previous value the failure reaches the caller like before.</li>
 * </ul>
 */
public final class BackgroundSnapshot<T> {

    /** The value and when its computation started (what the answer's own read time says). */
    public record Value<T>(T value, Instant computedAt) {
    }

    private final String name;
    private final Supplier<T> compute;
    private final Duration freshFor;
    private final Duration keepWarmFor;
    private final Clock clock;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final Object firstComputeLock = new Object();
    private volatile Value<T> current;
    private volatile Instant lastDemand = Instant.EPOCH;
    private volatile ScheduledExecutorService executor;

    public BackgroundSnapshot(String name, Supplier<T> compute, Duration freshFor, Duration keepWarmFor, Clock clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.compute = Objects.requireNonNull(compute, "compute");
        this.freshFor = Objects.requireNonNull(freshFor, "freshFor");
        this.keepWarmFor = Objects.requireNonNull(keepWarmFor, "keepWarmFor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BackgroundSnapshot(String name, Supplier<T> compute, Duration freshFor, Duration keepWarmFor) {
        this(name, compute, freshFor, keepWarmFor, Clock.systemUTC());
    }

    /** The last value; computes on this thread only when none exists yet. */
    public Value<T> get() {
        Instant now = clock.instant();
        lastDemand = now;
        Value<T> value = current;
        if (value == null) {
            synchronized (firstComputeLock) {
                value = current;
                if (value == null) {
                    value = computeNow();
                    startTicks();
                }
            }
            return value;
        }
        if (value.computedAt().plus(freshFor).isBefore(now)) {
            refreshAsync();
        }
        return value;
    }

    /** Drops the value, so the next {@link #get()} computes (after a write that the reader must see at once). */
    public void invalidate() {
        current = null;
    }

    boolean isRefreshing() {
        return refreshing.get();
    }

    private Value<T> computeNow() {
        Instant started = clock.instant();
        Value<T> value = new Value<>(compute.get(), started);
        current = value;
        return value;
    }

    private void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        executor().execute(this::refreshQuietly);
    }

    private void refreshQuietly() {
        try {
            computeNow();
        } catch (RuntimeException e) {
            System.getLogger(BackgroundSnapshot.class.getName()).log(System.Logger.Level.WARNING,
                    "SNAPSHOT_REFRESH_FAILED name={0} error={1}", name, e.getClass().getSimpleName());
        } finally {
            refreshing.set(false);
        }
    }

    private void tick() {
        Instant now = clock.instant();
        Value<T> value = current;
        if (value == null || lastDemand.plus(keepWarmFor).isBefore(now)) {
            return;
        }
        if (!value.computedAt().plus(freshFor).isAfter(now.plusMillis(freshFor.toMillis() / 4))) {
            refreshAsync();
        }
    }

    private void startTicks() {
        if (!ticking.compareAndSet(false, true)) {
            return; // one tick task for the life of the snapshot, however often it is invalidated
        }
        long period = Math.max(1000, freshFor.toMillis() / 2);
        executor().scheduleWithFixedDelay(this::tick, period, period, TimeUnit.MILLISECONDS);
    }

    private ScheduledExecutorService executor() {
        ScheduledExecutorService e = executor;
        if (e == null) {
            synchronized (this) {
                e = executor;
                if (e == null) {
                    e = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "snapshot-" + name);
                        t.setDaemon(true);
                        return t;
                    });
                    executor = e;
                }
            }
        }
        return e;
    }
}
