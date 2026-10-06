package com.securityexpert.nexus.ui2.jobs.failover.execution;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Palo Alto Networks HA controlled failover executor and two-sided verifier.
 * Primitives:
 * 1. `CONTROLLED_FAILOVER` -> `request high-availability state suspend`
 * 2. `RETURN_TO_SERVICE` -> `request high-availability state functional`
 * 3. Two-sided independent direct observations via `show high-availability state`.
 */
public class PaloAltoHaExecutor implements FailoverDeviceExecutor {

    public static final String VENDOR = "PALO_ALTO";
    public static final String CMD_SUSPEND = "request high-availability state suspend";
    public static final String CMD_FUNCTIONAL = "request high-availability state functional";

    private final BiFunction<String, String, FailoverCommandResult> commandDispatcher;
    private final BiFunction<String, String, MemberObservation> observationDispatcher;

    public PaloAltoHaExecutor() {
        this(
            (memberId, cmd) -> FailoverCommandResult.failure(-1, "TRANSPORT_UNAVAILABLE",
                "No failover transport configured", FailoverCommandResult.DeliveryCertainty.DEFINITELY_NOT_SUBMITTED),
            (clusterId, memberId) -> MemberObservation.failed(memberId, "TRANSPORT_UNAVAILABLE")
        );
    }

    public PaloAltoHaExecutor(
        BiFunction<String, String, FailoverCommandResult> commandDispatcher,
        BiFunction<String, String, MemberObservation> observationDispatcher
    ) {
        this.commandDispatcher = Objects.requireNonNull(commandDispatcher, "commandDispatcher must not be null");
        this.observationDispatcher = Objects.requireNonNull(observationDispatcher, "observationDispatcher must not be null");
    }

    @Override
    public FailoverCommandResult executeAction(String targetMemberId, FailoverActionKind actionKind) {
        Objects.requireNonNull(targetMemberId, "targetMemberId must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");

        String command = switch (actionKind) {
            case CONTROLLED_FAILOVER -> CMD_SUSPEND;
            case RETURN_TO_SERVICE -> CMD_FUNCTIONAL;
        };

        return commandDispatcher.apply(targetMemberId, command);
    }

    @Override
    public TwoSidedObservation observePostcondition(String clusterId, String memberAId, String memberBId) {
        Objects.requireNonNull(clusterId, "clusterId must not be null");
        Objects.requireNonNull(memberAId, "memberAId must not be null");
        Objects.requireNonNull(memberBId, "memberBId must not be null");

        // Independent direct observation of each peer
        MemberObservation obsA = observationDispatcher.apply(clusterId, memberAId);
        MemberObservation obsB = observationDispatcher.apply(clusterId, memberBId);

        return TwoSidedObservation.of(obsA, obsB);
    }

    @Override
    public String supportedVendor() {
        return VENDOR;
    }
}
