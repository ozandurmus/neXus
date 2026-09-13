package com.securityexpert.nexus.ui2.worker.discovery.pan;

/**
 * T-7/AGENTS.md: a fixed, no-argument message only -- this type deliberately
 * has no constructor that accepts response text or an underlying cause
 * message, so a caller cannot accidentally thread a raw or secret-bearing
 * string through it into {@link
 * com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult.Failed}.
 * Mirrors cp's {@code ManagementPlaneQueryFailedException}.
 */
final class PanoramaQueryFailedException extends RuntimeException {

    PanoramaQueryFailedException() {
        super("panorama query did not complete", null, false, false);
    }
}
