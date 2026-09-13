package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.time.Duration;

/**
 * CS-3's sampling interval, seamed so a fixture test can prove the two
 * connection-table observations are genuinely separate calls without a real
 * test suite paying that wall-clock cost.
 */
@FunctionalInterface
interface Sleeper {

    void sleep(Duration duration);

    static Sleeper real() {
        return duration -> {
            if (duration.isZero() || duration.isNegative()) {
                return;
            }
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ManagementPlaneQueryFailedException();
            }
        };
    }
}
