package com.securityexpert.nexus.ui2.worker.transport;

/**
 * Thrown by {@link StartupTransportCheck} (contract §5, AC-11, §8 test 12):
 * "the worker's startup wiring fails fast for any execution-eligible
 * capability whose transport has no registered adapter -- never a per-job
 * surprise." Thrown once, at composition time, before the worker begins
 * polling for jobs at all.
 */
public final class UnimplementedTransportAtStartupException extends RuntimeException {

    public UnimplementedTransportAtStartupException(String capabilityId, String transportKind) {
        super("capability " + capabilityId + " is execution-eligible but declares transport.kind=" + transportKind
                + ", which has no registered adapter -- refusing to start rather than surfacing this per-job (AC-11)");
    }
}
