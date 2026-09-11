package com.securityexpert.nexus.ui2.platform;

import java.time.Instant;

/**
 * Shared clock port. Production code never calls {@code Instant.now()}
 * directly (contract §4: no wall-clock dependency baked into unit tests),
 * so every module obtains time through this seam instead.
 */
public interface Clock {

    Instant now();

    static Clock system() {
        return java.time.Clock.systemUTC()::instant;
    }
}
