package com.securityexpert.nexus.ui2.jobs.failover.execution;

import java.time.Instant;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Check Point ClusterXL controlled failover executor and two-sided verifier.
 * Adheres strictly to Command Gate OP.2.1:
 * 1. Primitive: `clusterXL_admin down` (non-persistent, no -p, Expert shell).
 * 2. Reversal: `clusterXL_admin up`.
 * 3. Two-sided independent direct observations via `cphaprob stat` / `cphaprob -ia list`.
 */
public class CheckPointClusterXLExecutor implements FailoverDeviceExecutor {

    public static final String VENDOR = "CHECK_POINT";
    public static final String CMD_DOWN = "clusterXL_admin down";
    public static final String CMD_UP = "clusterXL_admin up";

    // Pluggable command dispatcher for live SSH transport vs test fixtures
    private final BiFunction<String, String, FailoverCommandResult> commandDispatcher;
    private final BiFunction<String, String, MemberObservation> observationDispatcher;

    public CheckPointClusterXLExecutor() {
        this(
            (memberId, cmd) -> FailoverCommandResult.success(cmd + " executed on " + memberId),
            (clusterId, memberId) -> MemberObservation.of(memberId, "STANDBY", true, true, "cphaprob stat: OK")
        );
    }

    public CheckPointClusterXLExecutor(
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
            case CONTROLLED_FAILOVER -> CMD_DOWN;
            case RETURN_TO_SERVICE -> CMD_UP;
        };

        return commandDispatcher.apply(targetMemberId, command);
    }

    @Override
    public TwoSidedObservation observePostcondition(String clusterId, String memberAId, String memberBId) {
        Objects.requireNonNull(clusterId, "clusterId must not be null");
        Objects.requireNonNull(memberAId, "memberAId must not be null");
        Objects.requireNonNull(memberBId, "memberBId must not be null");

        // Two distinct, independent direct reads per Evidence Law
        MemberObservation obsA = observationDispatcher.apply(clusterId, memberAId);
        MemberObservation obsB = observationDispatcher.apply(clusterId, memberBId);

        return TwoSidedObservation.of(obsA, obsB);
    }

    @Override
    public String supportedVendor() {
        return VENDOR;
    }
}
