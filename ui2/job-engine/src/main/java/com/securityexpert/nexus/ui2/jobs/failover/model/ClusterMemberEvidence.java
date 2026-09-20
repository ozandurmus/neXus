package com.securityexpert.nexus.ui2.jobs.failover.model;

import java.time.Instant;
import java.util.List;

/**
 * Sanitized, typed operational evidence collected directly from a single cluster member.
 * Strictly free of raw CLI response strings and sensitive credentials.
 */
public record ClusterMemberEvidence(
    String memberId,
    String maskedName,
    String selfState,
    String observedPeerState,
    String haMode,
    String syncStatus,
    long syncQueueDelta,
    boolean clusterInterfacesUp,
    int interfaceErrorCount,
    boolean criticalDevicesOk,
    List<String> failedCriticalDevices,
    boolean pathMonitoringOk,
    int failedPathsCount,
    String softwareVersion,
    String installedPolicyHash,
    int cpuUtilizationPct,
    int memoryUtilizationPct,
    long concurrentConnections,
    long maxConnectionsLimit,
    boolean preemptionEnabled,
    int preemptionPriority,
    int flapCountLast24Hours,
    boolean pendingCommit,
    boolean directObservationSuccessful,
    Instant observedAt
) {
    public ClusterMemberEvidence {
        if (failedCriticalDevices == null) {
            failedCriticalDevices = List.of();
        }
        if (observedAt == null) {
            observedAt = Instant.now();
        }
    }
}
