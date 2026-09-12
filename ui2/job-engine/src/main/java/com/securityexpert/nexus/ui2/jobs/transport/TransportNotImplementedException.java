package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * Thrown by {@code fetch}/{@code xmlApiCall} on every adapter shipped at
 * this movement (contract §5: "declared now so a capability using them
 * compiles from day one; both throw {@code TransportNotImplementedException}").
 * The worker's startup wiring check (§8 test 12, AC-11) is what turns "a
 * capability declares a kind whose transport has no adapter" into a
 * startup failure rather than a per-job surprise -- this exception is the
 * adapter-level half of that guarantee, thrown only if that startup check
 * were ever bypassed.
 */
public final class TransportNotImplementedException extends RuntimeException {

    public TransportNotImplementedException(String operation) {
        super("TRANSPORT_NOT_IMPLEMENTED: " + operation + " has no adapter implementation at this movement "
                + "(contract §5) -- the worker's startup wiring check should have refused this capability "
                + "before any job referencing it could be claimed (AC-11)");
    }
}
