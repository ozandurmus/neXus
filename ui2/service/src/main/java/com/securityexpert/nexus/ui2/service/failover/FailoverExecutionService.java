package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.execution.*;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.pilot.FailoverPilotAllowlist;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.DriftEvaluationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Orchestrator for Phase C & Phase D: Controlled Failover Execution.
 * Total-function execution invariants:
 * 1. At-most-once command submission: zero blind retries across mutation boundary.
 * 2. Pre-condition JIT re-check: direct two-sided read immediately before mutation.
 * 3. Atomic in-lock single-snapshot gate callback for scheduled executions (Astra P0.1 & Claude F-P1.9).
 * 4. Server-owned pilot allowlist fence: denies unapproved production clusters.
 * 5. Fleet-wide concurrency capped at 1.
 * 6. Sticky entity quarantine: ambiguous outcomes or transport drops lock cluster and members
 *    under OUTCOME_UNKNOWN until CAS 4-eyes audited acknowledgment.
 */
@Service
public class FailoverExecutionService {

    @FunctionalInterface
    public interface ScheduledPreMutationGate {
        DriftEvaluationResult evaluate(ClusterEvidenceSnapshot snapshot);
    }

    private final FailoverAuthorizationService authzService;
    private final PreflightService preflightService;
    private final FailoverPilotAllowlist pilotAllowlist;
    private final DurableQuarantineStore quarantineStore;
    private final Map<String, FailoverDeviceExecutor> executors = new ConcurrentHashMap<>();

    // Affirmative allow-list of vendor-safe roles that prove RETURN_TO_SERVICE recovery
    // succeeded (Claude CF-P0.21). Never widen this via a negative list.
    private static final Set<String> AFFIRMATIVE_RECOVERED_ROLES = Set.of("ACTIVE", "STANDBY", "READY", "FUNCTIONAL");

    private final Semaphore fleetConcurrencySemaphore = new Semaphore(1);
    private final Map<String, Boolean> activeClusterLocks = new ConcurrentHashMap<>();
    private final Map<String, FailoverExecutionResult> executionHistory = new ConcurrentHashMap<>();

    public FailoverExecutionService(
        FailoverAuthorizationService authzService,
        PreflightService preflightService,
        FailoverPilotAllowlist pilotAllowlist
    ) {
        this(authzService, preflightService, pilotAllowlist, new DurableQuarantineStore());
    }

    @Autowired
    public FailoverExecutionService(
        FailoverAuthorizationService authzService,
        PreflightService preflightService,
        FailoverPilotAllowlist pilotAllowlist,
        DurableQuarantineStore quarantineStore
    ) {
        this.authzService = Objects.requireNonNull(authzService, "authzService must not be null");
        this.preflightService = Objects.requireNonNull(preflightService, "preflightService must not be null");
        this.pilotAllowlist = Objects.requireNonNull(pilotAllowlist, "pilotAllowlist must not be null");
        this.quarantineStore = Objects.requireNonNull(quarantineStore, "quarantineStore must not be null");

        // Register default executors
        registerExecutor(new CheckPointClusterXLExecutor());
        registerExecutor(new PaloAltoHaExecutor());
    }

    public void registerExecutor(FailoverDeviceExecutor executor) {
        Objects.requireNonNull(executor, "executor must not be null");
        executors.put(executor.supportedVendor().toUpperCase(Locale.ROOT), executor);
    }

    public FailoverExecutionResult executeFailover(
        String clusterRef,
        String tokenId,
        String clientNonce,
        FailoverActionKind actionKind,
        String operatorId
    ) {
        return executeFailoverInternal(clusterRef, tokenId, clientNonce, actionKind, operatorId, null, null, null);
    }

    public FailoverExecutionResult executeScheduledFailover(
        String clusterRef,
        String tokenId,
        String clientNonce,
        FailoverActionKind actionKind,
        String operatorId,
        String signedTargetMemberId,
        Instant executionDeadline,
        ScheduledPreMutationGate scheduledGate
    ) {
        Objects.requireNonNull(executionDeadline, "executionDeadline must not be null for a scheduled execution");
        return executeFailoverInternal(clusterRef, tokenId, clientNonce, actionKind, operatorId, signedTargetMemberId, executionDeadline, scheduledGate);
    }

    private FailoverExecutionResult executeFailoverInternal(
        String clusterRef,
        String tokenId,
        String clientNonce,
        FailoverActionKind actionKind,
        String operatorId,
        String signedTargetMemberId,
        Instant executionDeadline,
        ScheduledPreMutationGate scheduledGate
    ) {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        Objects.requireNonNull(clientNonce, "clientNonce must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(operatorId, "operatorId must not be null");

        String executionId = "exec-" + UUID.randomUUID();
        Instant requestedAt = Instant.now();

        // 1. Pilot Fence Check (Checked at boundary)
        if (!pilotAllowlist.isClusterAllowed(clusterRef)) {
            throw new SecurityException("Cluster is not enrolled in the authorized lab pilot allowlist (pilot fence invariant)");
        }

        // 2. Sticky Quarantine Check (Durable store)
        if (quarantineStore.isClusterQuarantined(clusterRef)) {
            throw new IllegalStateException("Cluster is currently under sticky quarantine due to a previous ambiguous execution");
        }

        // 3. Concurrency Control (Fleet-wide limit = 1)
        if (!fleetConcurrencySemaphore.tryAcquire()) {
            throw new IllegalStateException("Another failover execution is currently in progress (fleet-wide concurrency limit = 1)");
        }

        try {
            if (activeClusterLocks.putIfAbsent(clusterRef, Boolean.TRUE) != null) {
                throw new IllegalStateException("Cluster execution lock is already held for: " + clusterRef);
            }
            try {
                // 4. Precondition JIT Re-Check (Two-Sided Direct Read inside the exclusive lock)
                ClusterEvidenceSnapshot snapshot = preflightService.buildSnapshotForCluster(clusterRef);
                String vendor = snapshot.vendor() != null ? snapshot.vendor().toUpperCase(Locale.ROOT) : "";
                String maskedName = snapshot.maskedClusterName();

                // F-P1.2: Hoist executor resolution before lease consumption
                FailoverDeviceExecutor executor;
                try {
                    executor = resolveExecutor(vendor);
                } catch (Exception ex) {
                    return recordAborted(
                        executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                        "Executor resolution failed prior to mutation boundary: " + ex.getClass().getSimpleName()
                    );
                }

                Optional<ClusterMemberEvidence> activeOpt = snapshot.activeMember();
                Optional<ClusterMemberEvidence> standbyOpt = snapshot.standbyMember();

                if (activeOpt.isEmpty() || standbyOpt.isEmpty()) {
                    return recordAborted(
                        executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                        "Precondition check failed: Active or standby member identity is UNKNOWN prior to mutation boundary"
                    );
                }

                ClusterMemberEvidence active = activeOpt.get();
                ClusterMemberEvidence standby = standbyOpt.get();

                // Signed target member enforcement (Claude F-P0.5)
                if (signedTargetMemberId != null && !signedTargetMemberId.equals(active.memberId())) {
                    return recordAborted(
                        executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                        "Target member mismatch: live active member (" + active.maskedName() +
                            ") does not match the signed authorization target"
                    );
                }

                // Affirmative standby readiness verification
                if (!standby.directObservationSuccessful() || !standby.criticalDevicesOk() || !standby.clusterInterfacesUp()) {
                    return recordAborted(
                        executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                        "Precondition check failed: Standby member is not affirmatively healthy or capable of assuming traffic"
                    );
                }

                // Pilot fence member enrollment verification
                if (!pilotAllowlist.isExecutionAllowed(clusterRef, active.memberId())) {
                    return recordAborted(
                        executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                        "Pilot fence check failed: target member " + active.maskedName() + " is not enrolled in pilot allowlist"
                    );
                }

                // 5. In-Lock Scheduled Gate Callback (Claude F-P1.9 & Astra P0.1)
                if (scheduledGate != null) {
                    DriftEvaluationResult driftRes = scheduledGate.evaluate(snapshot);
                    if (!driftRes.isPass()) {
                        return recordAborted(
                            executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                            "Scheduled pre-mutation gate failed: " + String.join(", ", driftRes.sanitizedMessages())
                        );
                    }
                }

                TwoSidedObservation preObs = TwoSidedObservation.of(
                    MemberObservation.of(active.memberId(), active.selfState(), active.directObservationSuccessful(), active.criticalDevicesOk(), "Precondition read: OK"),
                    MemberObservation.of(standby.memberId(), standby.selfState(), standby.directObservationSuccessful(), standby.criticalDevicesOk(), "Precondition read: OK")
                );

                // In-lock deadline recheck #1 (Claude CF-P0.10): the scheduled execution deadline
                // must still hold immediately before the durable lease is consumed.
                if (executionDeadline != null && !Instant.now().isBefore(executionDeadline)) {
                    return recordAborted(
                        executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                        "Execution deadline exceeded immediately before lease consumption"
                    );
                }

                // 6. Durable Lease Consumption (Crossing Mutation Boundary)
                FailoverLeaseToken consumedToken;
                try {
                    consumedToken = authzService.consumeLeaseForExecution(clusterRef, tokenId, clientNonce);
                } catch (Exception ex) {
                    return recordAborted(
                        executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                        "Authorization lease consumption failed: " + ex.getClass().getSimpleName()
                    );
                }

                // In-lock deadline recheck #2 (Claude CF-P0.10): re-verify immediately before the
                // at-most-once mutation is dispatched, closest possible to the mutation boundary.
                if (executionDeadline != null && !Instant.now().isBefore(executionDeadline)) {
                    return recordAborted(
                        executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                        "Execution deadline exceeded immediately before mutation dispatch"
                    );
                }

                Instant boundaryCrossedAt = Instant.now();

                // 7. Submit At-Most-Once Mutation Command
                FailoverCommandResult cmdResult;
                try {
                    cmdResult = executor.executeAction(active.memberId(), actionKind);
                } catch (Exception ex) {
                    cmdResult = FailoverCommandResult.failure(-1, "Command execution error", ex.getClass().getSimpleName(),
                        FailoverCommandResult.DeliveryCertainty.DELIVERY_UNKNOWN);
                    quarantineStore.engageQuarantine(clusterRef, executionId,
                        "Command threw exception across mutation boundary: " + ex.getClass().getSimpleName(),
                        Set.of(active.memberId(), standby.memberId()));
                    return recordResult(
                        executionId, clusterRef, maskedName, vendor, actionKind,
                        FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                        active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                        preObs, cmdResult, null, true,
                        "Command failed across mutation boundary; entity quarantined"
                    );
                }

                // F-P1.3 / CF-P0.16 / CF-P0.18: delivery-certainty-typed rejection handling.
                if (!cmdResult.successful()) {
                    String safeErrorReason = cmdResult.errorReason() != null ? cmdResult.errorReason() : "UNKNOWN";

                    // Any delivery outcome that is not affirmatively known to have failed to reach
                    // the device (transport drop, timeout, ambiguous vendor response) must fail closed.
                    if (cmdResult.certainty() == FailoverCommandResult.DeliveryCertainty.DELIVERY_UNKNOWN) {
                        quarantineStore.engageQuarantine(clusterRef, executionId,
                            "Command delivery certainty is DELIVERY_UNKNOWN across mutation boundary: " + safeErrorReason,
                            Set.of(active.memberId(), standby.memberId()));
                        return recordResult(
                            executionId, clusterRef, maskedName, vendor, actionKind,
                            FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                            active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                            preObs, cmdResult, null, true,
                            "Command delivery could not be confirmed; entity quarantined"
                        );
                    }

                    // DEFINITELY_REJECTED / DEFINITELY_NOT_SUBMITTED: the device is expected to be
                    // untouched. Corroborate with a guarded post-observation before trusting that.
                    TwoSidedObservation rejectObs;
                    try {
                        rejectObs = executor.observePostcondition(clusterRef, active.memberId(), standby.memberId());
                    } catch (Exception obsEx) {
                        quarantineStore.engageQuarantine(clusterRef, executionId,
                            "Post-observation on rejected command failed: " + obsEx.getClass().getSimpleName(),
                            Set.of(active.memberId(), standby.memberId()));
                        return recordResult(
                            executionId, clusterRef, maskedName, vendor, actionKind,
                            FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                            active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                            preObs, cmdResult, null, true,
                            "Vendor rejected but post-observation failed; entity quarantined"
                        );
                    }

                    boolean rolesUnchanged = rejectObs.bothObservationsSuccessful()
                        && active.selfState().equalsIgnoreCase(rejectObs.memberAObservation().observedRole())
                        && standby.selfState().equalsIgnoreCase(rejectObs.memberBObservation().observedRole());

                    if (!rolesUnchanged) {
                        quarantineStore.engageQuarantine(clusterRef, executionId,
                            "Vendor reported rejection but member roles changed or could not be corroborated",
                            Set.of(active.memberId(), standby.memberId()));
                        return recordResult(
                            executionId, clusterRef, maskedName, vendor, actionKind,
                            FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                            active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                            preObs, cmdResult, rejectObs, true,
                            "Rejected command outcome is ambiguous; entity quarantined"
                        );
                    }

                    return recordResult(
                        executionId, clusterRef, maskedName, vendor, actionKind,
                        FailoverExecutionState.VENDOR_REJECTED, requestedAt, boundaryCrossedAt,
                        active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                        preObs, cmdResult, rejectObs, false,
                        "Vendor device rejected command without mutating state: " + safeErrorReason
                    );
                }

                // 8. Two-Sided Independent Direct Post-Verification Observation
                TwoSidedObservation postObs;
                try {
                    postObs = executor.observePostcondition(clusterRef, active.memberId(), standby.memberId());
                } catch (Exception ex) {
                    quarantineStore.engageQuarantine(clusterRef, executionId,
                        "Post-verification observation failed: " + ex.getClass().getSimpleName(),
                        Set.of(active.memberId(), standby.memberId()));
                    return recordResult(
                        executionId, clusterRef, maskedName, vendor, actionKind,
                        FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                        active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                        preObs, cmdResult, null, true,
                        "Post-verification could not contact devices; sticky quarantine engaged"
                    );
                }

                if (!postObs.bothObservationsSuccessful()) {
                    quarantineStore.engageQuarantine(clusterRef, executionId, "Post-verification unable to observe both members independently",
                        Set.of(active.memberId(), standby.memberId()));
                    return recordResult(
                        executionId, clusterRef, maskedName, vendor, actionKind,
                        FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                        active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                        preObs, cmdResult, postObs, true,
                        "Dual observation incomplete; sticky quarantine engaged"
                    );
                }

                // 9. Classify Transition Outcome
                MemberObservation postA = postObs.memberAObservation();
                MemberObservation postB = postObs.memberBObservation();

                if (actionKind == FailoverActionKind.CONTROLLED_FAILOVER) {
                    boolean standbyPromoted = "ACTIVE".equalsIgnoreCase(postB.observedRole());
                    boolean activeDemoted = "STANDBY".equalsIgnoreCase(postA.observedRole())
                        || "DOWN".equalsIgnoreCase(postA.observedRole())
                        || "PASSIVE".equalsIgnoreCase(postA.observedRole());

                    if (standbyPromoted && activeDemoted) {
                        return recordResult(
                            executionId, clusterRef, maskedName, vendor, actionKind,
                            FailoverExecutionState.SUCCEEDED, requestedAt, boundaryCrossedAt,
                            active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                            preObs, cmdResult, postObs, false,
                            "Controlled failover succeeded: standby member successfully assumed active role"
                        );
                    } else if (!standbyPromoted && !activeDemoted) {
                        return recordResult(
                            executionId, clusterRef, maskedName, vendor, actionKind,
                            FailoverExecutionState.FAILED_NO_CHANGE, requestedAt, boundaryCrossedAt,
                            active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                            preObs, cmdResult, postObs, false,
                            "No transition occurred; original member roles remain active"
                        );
                    } else {
                        quarantineStore.engageQuarantine(clusterRef, executionId, "Asymmetric or ambiguous member roles observed post-mutation",
                            Set.of(active.memberId(), standby.memberId()));
                        return recordResult(
                            executionId, clusterRef, maskedName, vendor, actionKind,
                            FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                            active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                            preObs, cmdResult, postObs, true,
                            "Asymmetric transition observed; sticky quarantine engaged"
                        );
                    }
                } else { // RETURN_TO_SERVICE (Claude F-P1.4: verify role recovery)
                    // Affirmative allow-list of vendor-safe recovered roles (Claude CF-P0.21).
                    // A negative list (!DOWN && !SUSPENDED) would treat an unrecognized or novel
                    // vendor role string as recovered by default; require a known-good role instead.
                    boolean demotedRestored = AFFIRMATIVE_RECOVERED_ROLES.contains(postA.observedRole().toUpperCase(Locale.ROOT));

                    if (demotedRestored) {
                        return recordResult(
                            executionId, clusterRef, maskedName, vendor, actionKind,
                            FailoverExecutionState.SUCCEEDED, requestedAt, boundaryCrossedAt,
                            active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                            preObs, cmdResult, postObs, false,
                            "Return to service completed successfully: member restored to active or ready state"
                        );
                    } else {
                        return recordResult(
                            executionId, clusterRef, maskedName, vendor, actionKind,
                            FailoverExecutionState.FAILED_NO_CHANGE, requestedAt, boundaryCrossedAt,
                            active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                            preObs, cmdResult, postObs, false,
                            "Return to service failed to restore member state; member remains down/suspended"
                        );
                    }
                }

            } finally {
                activeClusterLocks.remove(clusterRef);
            }
        } finally {
            fleetConcurrencySemaphore.release();
        }
    }

    public Optional<FailoverExecutionResult> getExecution(String executionId) {
        return Optional.ofNullable(executionHistory.get(executionId));
    }

    public boolean isClusterQuarantined(String clusterRef) {
        return quarantineStore.isClusterQuarantined(clusterRef);
    }

    public Optional<DurableQuarantineStore.QuarantineEntry> getQuarantine(String clusterRef) {
        return quarantineStore.getActiveQuarantine(clusterRef);
    }

    public boolean acknowledgeQuarantine(String clusterRef, String executionId, String requesterId, String approverId, String reason) {
        return quarantineStore.acknowledgeQuarantine(clusterRef, executionId, requesterId, approverId, reason);
    }

    /**
     * CF-P0.20 passthrough: lets {@code FailoverScheduleService}'s startup reconciliation engage
     * sticky quarantine on a cluster left in an ambiguous state by a crash, without duplicating
     * ownership of {@link DurableQuarantineStore} outside this service.
     */
    public void engageQuarantine(String clusterRef, String executionId, String reason, Set<String> memberIds) {
        quarantineStore.engageQuarantine(clusterRef, executionId, reason, memberIds);
    }

    public boolean acknowledgeQuarantine(String clusterRef, String requesterId, String approverId, String reason) {
        Optional<DurableQuarantineStore.QuarantineEntry> active = quarantineStore.getActiveQuarantine(clusterRef);
        if (active.isEmpty()) {
            throw new IllegalArgumentException("Cluster is not currently quarantined");
        }
        return quarantineStore.acknowledgeQuarantine(clusterRef, active.get().executionId(), requesterId, approverId, reason);
    }

    private FailoverDeviceExecutor resolveExecutor(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            throw new IllegalArgumentException("Cannot resolve failover executor: vendor is null or empty");
        }
        FailoverDeviceExecutor executor = executors.get(vendor);
        if (executor == null) {
            throw new IllegalArgumentException("No failover device executor registered for vendor: " + vendor);
        }
        return executor;
    }

    private FailoverExecutionResult recordAborted(
        String executionId, String clusterRef, String maskedName, String vendor,
        FailoverActionKind actionKind, Instant requestedAt, String operatorId, String reason
    ) {
        FailoverExecutionResult res = new FailoverExecutionResult(
            executionId, clusterRef, maskedName, vendor, actionKind,
            FailoverExecutionState.ABORTED_PRE_MUTATION, requestedAt, null, Instant.now(),
            "UNKNOWN", "UNKNOWN", operatorId, "NONE",
            null, null, null, false, reason
        );
        executionHistory.put(executionId, res);
        return res;
    }

    private FailoverExecutionResult recordResult(
        String executionId, String clusterRef, String maskedName, String vendor,
        FailoverActionKind actionKind, FailoverExecutionState state, Instant requestedAt,
        Instant boundaryCrossedAt, String targetMemberId, String targetMemberMaskedName,
        String requesterId, String approverId, TwoSidedObservation preObs,
        FailoverCommandResult cmdResult, TwoSidedObservation postObs,
        boolean quarantineActive, String summary
    ) {
        FailoverExecutionResult res = new FailoverExecutionResult(
            executionId, clusterRef, maskedName, vendor, actionKind, state, requestedAt,
            boundaryCrossedAt, Instant.now(), targetMemberId, targetMemberMaskedName,
            requesterId, approverId, preObs, cmdResult, postObs, quarantineActive, summary
        );
        executionHistory.put(executionId, res);
        return res;
    }
}
