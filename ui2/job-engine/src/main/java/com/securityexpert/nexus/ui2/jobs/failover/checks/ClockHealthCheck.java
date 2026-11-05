package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.*;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Pre-flight Check #13: Host & Evidence Clock Health Verification.
 * Constitutional invariant:
 * Unattended CLASS 2 execution must prove trusted time.
 * Forward or backward clock steps, hypervisor drift, or NTP anomalies fail closed.
 */
public class ClockHealthCheck implements PreflightCheck {

    public static final String CHECK_ID = "preflight.clock_health";
    public static final Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(5);
    public static final long MAX_MONOTONIC_SKEW_MILLIS = 60_000L;

    private static final long PROCESS_START_NANO = System.nanoTime();
    private static final Instant PROCESS_START_WALL = Instant.now();

    private final Clock wallClock;
    private final LongSupplier nanoSource;
    private final long startNano;
    private final Instant startWall;

    /** Production form: the JVM-wide process-start anchors and the system clock. */
    public ClockHealthCheck() {
        this.wallClock = Clock.systemUTC();
        this.nanoSource = System::nanoTime;
        this.startNano = PROCESS_START_NANO;
        this.startWall = PROCESS_START_WALL;
    }

    /**
     * Deterministic form for tests: wall and monotonic sources are injected and the anchors are taken
     * from them at construction, so the drift/staleness arithmetic is exercised without reading the host clock.
     */
    public ClockHealthCheck(Clock wallClock, LongSupplier nanoSource) {
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock must not be null");
        this.nanoSource = Objects.requireNonNull(nanoSource, "nanoSource must not be null");
        this.startNano = nanoSource.getAsLong();
        this.startWall = wallClock.instant();
    }

    /** The wall-clock instant this check evaluates against. */
    public Instant now() {
        return wallClock.instant();
    }

    @Override
    public String id() {
        return CHECK_ID;
    }

    @Override
    public String name() {
        return "Host & Evidence Clock Health Verification";
    }

    @Override
    public String category() {
        return "System Timing & Security";
    }

    @Override
    public EnforcementPolicy defaultPolicy() {
        return EnforcementPolicy.BLOCKING;
    }

    @Override
    public boolean appliesTo(String vendor, String haMode) {
        return true; // Universal check across all vendors and clustering modes
    }

    @Override
    public CheckResult evaluate(ClusterEvidenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // 1. Monotonic Cross-Check within host process
        long elapsedNanos = nanoSource.getAsLong() - startNano;
        long elapsedMonotonicMillis = elapsedNanos / 1_000_000L;

        Instant currentWall = wallClock.instant();
        Duration wallElapsed = Duration.between(startWall, currentWall);
        if (wallElapsed.isNegative()) {
            return CheckResult.fail(
                CHECK_ID,
                name(),
                category(),
                defaultPolicy(),
                "Host wall-clock stepped backward relative to process start (" + currentWall + " < " + startWall + ")",
                "ERR_CLOCK_BACKWARD_STEP"
            );
        }
        long elapsedWallMillis = wallElapsed.toMillis();

        long skewMillis = Math.abs(elapsedWallMillis - elapsedMonotonicMillis);
        if (skewMillis > MAX_MONOTONIC_SKEW_MILLIS) {
            return CheckResult.fail(
                CHECK_ID,
                name(),
                category(),
                defaultPolicy(),
                "Host clock drift detected between wall-clock and monotonic timer (skew: " + skewMillis + "ms > " + MAX_MONOTONIC_SKEW_MILLIS + "ms tolerance)",
                "ERR_CLOCK_DRIFT"
            );
        }

        // 2. Evidence snapshot freshness verification
        Instant evidenceTime = snapshot.snapshotTimestamp();
        if (evidenceTime == null) {
            return CheckResult.insufficientEvidence(
                CHECK_ID,
                name(),
                category(),
                defaultPolicy(),
                "Evidence snapshot timestamp is null or unrecorded",
                "ERR_CLOCK_TIMESTAMP_MISSING"
            );
        }

        Duration evidenceAge = Duration.between(evidenceTime, currentWall);
        if (evidenceAge.isNegative()) {
            return CheckResult.fail(
                CHECK_ID,
                name(),
                category(),
                defaultPolicy(),
                "Evidence timestamp is in the future relative to host wall-clock (" + evidenceTime + " > " + currentWall + ")",
                "ERR_CLOCK_FUTURE_TIMESTAMP"
            );
        }

        if (evidenceAge.compareTo(MAX_EVIDENCE_AGE) > 0) {
            return CheckResult.fail(
                CHECK_ID,
                name(),
                category(),
                defaultPolicy(),
                "Evidence snapshot is stale (age: " + evidenceAge.toSeconds() + "s > max: " + MAX_EVIDENCE_AGE.toSeconds() + "s)",
                "ERR_CLOCK_STALE_EVIDENCE"
            );
        }

        return CheckResult.pass(
            CHECK_ID,
            name(),
            category(),
            defaultPolicy(),
            "Host monotonic timer and UTC wall-clock verified in sync (skew: " + skewMillis + "ms, evidence age: " + evidenceAge.toSeconds() + "s)"
        );
    }
}
