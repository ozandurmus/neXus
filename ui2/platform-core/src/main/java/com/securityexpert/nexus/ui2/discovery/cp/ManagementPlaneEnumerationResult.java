package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.List;
import java.util.Map;

/**
 * T-1/T-5/T-7: the outcome of one {@link ManagementPlaneEnumeration#run}.
 * Exactly one of three shapes -- never a partial candidate list alongside a
 * failure, never a raw exception escaping to the caller.
 */
public sealed interface ManagementPlaneEnumerationResult {

    /** T-5/SB-16: credential resolution failed before {@code connect()} was ever called (AC-5). */
    record Refused(String reason) implements ManagementPlaneEnumerationResult {
    }

    /** T-1/T-7: the run completed; {@code disconnectOutcome} names what closing the session actually did. */
    record Completed(
            List<RawCandidateInput> candidates,
            Map<Address, ConnectionTableChannelState> connectionTableStates,
            int managementPlaneRequestCount,
            SessionDisconnectOutcome disconnectOutcome) implements ManagementPlaneEnumerationResult {
    }

    /**
     * T-1: authentication did not succeed, or a query failed mid-run.
     * {@code disconnectOutcome} is {@link SessionDisconnectOutcome#NOT_OPENED}
     * only when {@code connect()} itself never reached an authenticated
     * session; a mid-run failure after that point always attempts {@code
     * disconnect()} regardless.
     */
    record Failed(
            String reason,
            int managementPlaneRequestCount,
            SessionDisconnectOutcome disconnectOutcome) implements ManagementPlaneEnumerationResult {
    }
}
