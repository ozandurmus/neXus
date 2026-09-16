package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.List;
import java.util.Map;

/**
 * T-1/T-5/T-7: the outcome of one {@link ManagementPlaneEnumeration#run}.
 * Exactly one of three shapes -- never a partial candidate list alongside a
 * failure, never a raw exception escaping to the caller.
 */
public sealed interface ManagementPlaneEnumerationResult {

    enum FailureClass { TRUST_ENTRY_MISSING, TRUST_MISMATCH, AUTH_FAILED, CONNECT_TIMEOUT }

    /** T-5/SB-16: credential resolution failed before {@code connect()} was ever called (AC-5). */
    record Refused(String reason) implements ManagementPlaneEnumerationResult {
    }

    /** T-1/T-7: the run completed; {@code disconnectOutcome} names what closing the session actually did. */
    record Completed(
            List<RawCandidateInput> candidates,
            Map<Address, ConnectionTableChannelState> connectionTableStates,
            int managementPlaneRequestCount,
            SessionDisconnectOutcome disconnectOutcome,
            Map<ObjectType, ParseCounts> parseCountsByObjectType) implements ManagementPlaneEnumerationResult {
    }

    /**
     * Runner-level telemetry only (counts, never a name/address/domain/identifier,
     * per AGENTS.md "Sensitive identity reporting law"): how many objects one
     * object-type's dump produced, and how many of those lacked the stable
     * identifier (record §10 row 4's {@code AdminInfo/chkpf_uid}) and so were
     * never turned into a candidate row at all.
     */
    record ParseCounts(int parsed, int missingStableIdentifier) {
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
            SessionDisconnectOutcome disconnectOutcome,
            java.util.Optional<FailureClass> failureClass) implements ManagementPlaneEnumerationResult {
        public Failed(String reason, int count, SessionDisconnectOutcome disconnect) {
            this(reason, count, disconnect, java.util.Optional.empty());
        }
        public Failed(FailureClass failure, int count, SessionDisconnectOutcome disconnect) {
            this(failure.name(), count, disconnect, java.util.Optional.of(failure));
        }
    }
}
