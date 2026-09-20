package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.*;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

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
        long elapsedNanos = System.nanoTime() - PROCESS_START_NANO;
        long elapsedMonotonicMillis = elapsedNanos / 1_000_000L;

        Instant currentWall = Instant.now();
        Duration wallElapsed = Duration.between(PROCESS_START_WALL, currentWall);
        if (wallElapsed.isNegative()) {
            return CheckResult.fail(
                CHECK_ID,
                name(),
                category(),
                defaultPolicy(),
                "Host wall-clock stepped backward relative to process start (" + currentWall + " < " + PROCESS_START_WALL + ")",
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
