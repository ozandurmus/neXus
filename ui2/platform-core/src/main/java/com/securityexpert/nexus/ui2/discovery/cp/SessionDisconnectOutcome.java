package com.securityexpert.nexus.ui2.discovery.cp;

/**
 * T-1: "a run that cannot close its session reports that; it does not leave
 * one open." Carried explicitly in {@link ManagementPlaneEnumerationResult}
 * rather than swallowed by a caught exception.
 */
public enum SessionDisconnectOutcome {

    /** {@code connect()} never reached an authenticated session; there was nothing to close. */
    NOT_OPENED,

    /** {@code disconnect()} was called and completed without throwing. */
    CLOSED,

    /** {@code disconnect()} was called and threw; the run reports this rather than swallowing it. */
    FAILED_TO_CLOSE
}
