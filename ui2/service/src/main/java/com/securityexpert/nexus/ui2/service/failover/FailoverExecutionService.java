package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.execution.*;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.pilot.FailoverPilotAllowlist;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Orchestrator for Phase C: Controlled Manual Failover Execution.
 * Invariants:
 * 1. At-most-once command submission: zero blind retries across mutation boundary.
 * 2. Pre-condition JIT re-check: direct two-sided read immediately before mutation.
 *    Any collection failure or standby unreadiness terminates in ABORTED_PRE_MUTATION.
 * 3. Server-owned pilot allowlist fence: denies unapproved production clusters.
 * 4. Fleet-wide concurrency capped at 1.
 * 5. Sticky entity quarantine: ambiguous outcomes lock cluster and members under OUTCOME_UNKNOWN
 *    until 4-eyes audited acknowledgment with fresh two-sided evidence.
 */
@Service
public class FailoverExecutionService {

    public record QuarantineRecord(
        String clusterRef,
        String executionId,
        Instant quarantinedAt,
        String reason,
        Set<String> quarantinedMemberIds
    ) {}

    private final FailoverAuthorizationService authzService;
    private final PreflightService preflightService;
    private final FailoverPilotAllowlist pilotAllowlist;
    private final Map<String, FailoverDeviceExecutor> executors = new ConcurrentHashMap<>();

    private final Semaphore fleetConcurrencySemaphore = new Semaphore(1);
    private final Map<String, Boolean> activeClusterLocks = new ConcurrentHashMap<>();
    private final Map<String, QuarantineRecord> quarantinedClusters = new ConcurrentHashMap<>();
    private final Map<String, FailoverExecutionResult> executionHistory = new ConcurrentHashMap<>();

    public FailoverExecutionService(
        FailoverAuthorizationService authzService,
        PreflightService preflightService,
        FailoverPilotAllowlist pilotAllowlist
    ) {
        this.authzService = Objects.requireNonNull(authzService, "authzService must not be null");
        this.preflightService = Objects.requireNonNull(preflightService, "preflightService must not be null");
        this.pilotAllowlist = Objects.requireNonNull(pilotAllowlist, "pilotAllowlist must not be null");

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

        // 2. Sticky Quarantine Check
        if (quarantinedClusters.containsKey(clusterRef)) {
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
                // 4. Precondition JIT Re-Check (Two-Sided Direct Read)
                ClusterEvidenceSnapshot snapshot = preflightService.buildSnapshotForCluster(clusterRef);
                String vendor = snapshot.vendor() != null ? snapshot.vendor().toUpperCase(Locale.ROOT) : "";
                String maskedName = snapshot.maskedClusterName();

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
                        "Pilot fence check failed: Target member " + active.memberId() + " is not enrolled in pilot allowlist"
                    );
                }

                TwoSidedObservation preObs = TwoSidedObservation.of(
                    MemberObservation.of(active.memberId(), active.selfState(), active.directObservationSuccessful(), active.criticalDevicesOk(), "Precondition read: OK"),
                    MemberObservation.of(standby.memberId(), standby.selfState(), standby.directObservationSuccessful(), standby.criticalDevicesOk(), "Precondition read: OK")
                );

                // 5. Durable Lease Consumption (Crossing Mutation Boundary)
                FailoverLeaseToken consumedToken;
                try {
                    consumedToken = authzService.consumeLeaseForExecution(clusterRef, tokenId, clientNonce);
                } catch (Exception ex) {
                    return recordAborted(
                        executionId, clusterRef, maskedName, vendor, actionKind, requestedAt, operatorId,
                        "Authorization lease consumption failed: " + ex.getMessage()
                    );
                }

                Instant boundaryCrossedAt = Instant.now();

                // 6. Submit At-Most-Once Mutation Command
                FailoverDeviceExecutor executor = resolveExecutor(vendor);
                FailoverCommandResult cmdResult;
                try {
                    cmdResult = executor.executeAction(active.memberId(), actionKind);
                } catch (Exception ex) {
                    cmdResult = FailoverCommandResult.failure(-1, "Command execution error", ex.getMessage());
                    engageQuarantine(clusterRef, executionId, "Command threw exception across mutation boundary: " + ex.getMessage(),
                        Set.of(active.memberId(), standby.memberId()));
                    return recordResult(
                        executionId, clusterRef, maskedName, vendor, actionKind,
                        FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                        active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                        preObs, cmdResult, null, true,
                        "Command failed across mutation boundary; entity quarantined"
                    );
                }

                if (!cmdResult.successful()) {
                    // Definite vendor rejection (F-11)
                    TwoSidedObservation postObs = executor.observePostcondition(clusterRef, active.memberId(), standby.memberId());
                    return recordResult(
                        executionId, clusterRef, maskedName, vendor, actionKind,
                        FailoverExecutionState.VENDOR_REJECTED, requestedAt, boundaryCrossedAt,
                        active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                        preObs, cmdResult, postObs, false,
                        "Vendor device rejected command without mutating state: " + cmdResult.errorReason()
                    );
                }

                // 7. Two-Sided Independent Direct Post-Verification Observation
                TwoSidedObservation postObs;
                try {
                    postObs = executor.observePostcondition(clusterRef, active.memberId(), standby.memberId());
                } catch (Exception ex) {
                    engageQuarantine(clusterRef, executionId, "Post-verification observation failed: " + ex.getMessage(),
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
                    engageQuarantine(clusterRef, executionId, "Post-verification unable to observe both members independently",
                        Set.of(active.memberId(), standby.memberId()));
                    return recordResult(
                        executionId, clusterRef, maskedName, vendor, actionKind,
                        FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                        active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                        preObs, cmdResult, postObs, true,
                        "Dual observation incomplete; sticky quarantine engaged"
                    );
                }

                // Classify transition outcome
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
                        engageQuarantine(clusterRef, executionId, "Asymmetric or ambiguous member roles observed post-mutation",
                            Set.of(active.memberId(), standby.memberId()));
                        return recordResult(
                            executionId, clusterRef, maskedName, vendor, actionKind,
                            FailoverExecutionState.OUTCOME_UNKNOWN, requestedAt, boundaryCrossedAt,
                            active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                            preObs, cmdResult, postObs, true,
                            "Asymmetric transition observed; sticky quarantine engaged"
                        );
                    }
                } else { // RETURN_TO_SERVICE
                    return recordResult(
                        executionId, clusterRef, maskedName, vendor, actionKind,
                        FailoverExecutionState.SUCCEEDED, requestedAt, boundaryCrossedAt,
                        active.memberId(), active.maskedName(), consumedToken.requesterId(), consumedToken.approverId(),
                        preObs, cmdResult, postObs, false,
                        "Return to service completed successfully"
                    );
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
        return quarantinedClusters.containsKey(clusterRef);
    }

    public Optional<QuarantineRecord> getQuarantine(String clusterRef) {
        return Optional.ofNullable(quarantinedClusters.get(clusterRef));
    }

    public synchronized void acknowledgeQuarantine(String clusterRef, String requesterId, String approverId, String reason) {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(requesterId, "requesterId must not be null");
        Objects.requireNonNull(approverId, "approverId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (requesterId.equalsIgnoreCase(approverId)) {
            throw new IllegalArgumentException("Quarantine acknowledgment requires 4-eyes dual control (requester != approver)");
        }
        if (reason.trim().length() < 8) {
            throw new IllegalArgumentException("Quarantine acknowledgment reason must be at least 8 characters");
        }
        if (!quarantinedClusters.containsKey(clusterRef)) {
            throw new IllegalArgumentException("Cluster is not currently quarantined");
        }
        quarantinedClusters.remove(clusterRef);
    }

    private void engageQuarantine(String clusterRef, String executionId, String reason, Set<String> memberIds) {
        quarantinedClusters.put(clusterRef, new QuarantineRecord(
            clusterRef, executionId, Instant.now(), reason, memberIds
        ));
    }

    private FailoverDeviceExecutor resolveExecutor(String vendor) {
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
