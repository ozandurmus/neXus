package com.securityexpert.nexus.ui2.worker.health;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The worker pod's in-process health state, read by the kubelet through {@link WorkerHealthServer}
 * ({@code /livez}, {@code /readyz}). Nothing here calls the database: liveness is computed from timestamps the claim
 * loops write on every iteration, readiness from the outcome of the module heartbeat the process already sends.
 *
 * <ul>
 *   <li><b>Live</b> unless a registered, running, idle claim loop has not iterated for {@code stallAfter}. A loop that
 *       holds a job (device work, admission waits, a long backup) is busy and never a stall: the job has its own lease,
 *       deadline and cancellation, and a probe restart would cut it mid-step. A draining process (SIGTERM drain, or
 *       module ownership lost) is always live, so the kubelet never kills a drain. A database outage does not stall
 *       a loop (its calls fail fast and the loop sleeps and retries), so it never restarts the pod.</li>
 *   <li><b>Ready</b> when the loops were started, the process is not draining, and the last module heartbeat reached
 *       the database (true or false answer) within {@code readyStaleAfter}.</li>
 * </ul>
 */
public final class WorkerHealth {

    /** Default: a loop idle for longer than this is stalled (claim poll is 2-5 s, a DB call is bounded to ~10 s). */
    public static final Duration DEFAULT_STALL_AFTER = Duration.ofSeconds(120);
    /** Default: six missed 10-second module heartbeats. */
    public static final Duration DEFAULT_READY_STALE_AFTER = Duration.ofSeconds(60);

    /** A probe answer: HTTP status and a one-word reason (no identifiers, no values). */
    public record Status(boolean ok, String reason) {
        public int httpStatus() {
            return ok ? 200 : 503;
        }
    }

    private final LongSupplier nanoClock;
    private final long stallAfterNanos;
    private final long readyStaleAfterNanos;
    private final List<Beat> beats = new CopyOnWriteArrayList<>();
    private volatile boolean started;
    private volatile boolean draining;
    private volatile boolean dbReachable;
    private volatile long lastDbContactNanos;

    public WorkerHealth(LongSupplier nanoClock, Duration stallAfter, Duration readyStaleAfter) {
        this.nanoClock = Objects.requireNonNull(nanoClock);
        if (stallAfter.isNegative() || stallAfter.isZero() || readyStaleAfter.isNegative() || readyStaleAfter.isZero())
            throw new IllegalArgumentException("HEALTH_WINDOW_NOT_POSITIVE");
        this.stallAfterNanos = stallAfter.toNanos();
        this.readyStaleAfterNanos = readyStaleAfter.toNanos();
    }

    /** System clock; windows from {@code UI2_HEALTH_STALL_SECONDS} / {@code UI2_HEALTH_READY_STALE_SECONDS}. */
    public static WorkerHealth fromEnvironment(java.util.function.Function<String, String> env) {
        return new WorkerHealth(System::nanoTime,
                seconds(env.apply("UI2_HEALTH_STALL_SECONDS"), DEFAULT_STALL_AFTER),
                seconds(env.apply("UI2_HEALTH_READY_STALE_SECONDS"), DEFAULT_READY_STALE_AFTER));
    }

    private static Duration seconds(String value, Duration fallback) {
        if (value == null || value.isBlank()) return fallback;
        return Duration.ofSeconds(Long.parseLong(value.strip()));
    }

    /** A new loop marker, counted from now (a fresh loop is not stalled). */
    public LoopBeat register(String name) {
        Beat beat = new Beat(Objects.requireNonNull(name), nanoClock.getAsLong());
        beats.add(beat);
        return beat;
    }

    /** Every claim loop was submitted: readiness may now turn green. */
    public void markStarted() {
        started = true;
    }

    /** Stop requested (SIGTERM drain or module ownership lost): live from here on, never ready again. */
    public void markDraining() {
        draining = true;
    }

    public boolean draining() {
        return draining;
    }

    /**
     * Runs a module heartbeat and records whether it reached the database: a returned answer (owner or not) is
     * "reachable", a thrown exception is "unreachable" and is rethrown unchanged.
     */
    public boolean observeHeartbeat(BooleanSupplier heartbeat) {
        boolean answer;
        try {
            answer = heartbeat.getAsBoolean();
        } catch (RuntimeException unavailable) {
            dbReachable = false;
            throw unavailable;
        }
        lastDbContactNanos = nanoClock.getAsLong();
        dbReachable = true;
        return answer;
    }

    public Status liveness() {
        if (draining) return new Status(true, "draining");
        long now = nanoClock.getAsLong();
        for (Beat beat : beats) {
            if (beat.stopped || beat.busy) continue;
            if (now - beat.lastTickNanos > stallAfterNanos) return new Status(false, "stalled");
        }
        return new Status(true, "ok");
    }

    public Status readiness() {
        if (draining) return new Status(false, "draining");
        if (!started) return new Status(false, "starting");
        if (!dbReachable) return new Status(false, "db_unreachable");
        if (nanoClock.getAsLong() - lastDbContactNanos > readyStaleAfterNanos) return new Status(false, "db_stale");
        return new Status(true, "ok");
    }

    private final class Beat implements LoopBeat {
        private final String name;
        private volatile long lastTickNanos;
        private volatile boolean busy;
        private volatile boolean stopped;

        private Beat(String name, long now) {
            this.name = name;
            this.lastTickNanos = now;
        }

        @Override public void tick() { lastTickNanos = nanoClock.getAsLong(); }
        @Override public void jobStarted() { busy = true; }
        @Override public void idle() { busy = false; lastTickNanos = nanoClock.getAsLong(); }
        @Override public void stopped() { stopped = true; }
        @Override public String toString() { return "LoopBeat[" + name + "]"; }
    }
}
