package com.securityexpert.nexus.ui2.jobs.failover.pilot;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Ordinary server-owned enrollment resolver; the historical class name is retained for callers. */
public class FailoverPilotAllowlist {

    public record PilotEnrollment(
        String clusterId,
        String environmentClassification,
        Set<String> enrolledMemberIds,
        String authorizedBy,
        boolean active
    ) {
        public PilotEnrollment {
            Objects.requireNonNull(clusterId, "clusterId must not be null");
            Objects.requireNonNull(environmentClassification, "environmentClassification must not be null");
            enrolledMemberIds = Set.copyOf(Objects.requireNonNull(enrolledMemberIds, "enrolledMemberIds must not be null"));
        }

        public boolean isEligibleForPilot() {
            return active && enrolledMemberIds.size() == 2;
        }
    }

    private final Map<String, PilotEnrollment> enrollments = new ConcurrentHashMap<>();

    private final java.util.function.Function<String, PilotEnrollment> resolver;

    /** Empty explicit fixture registry, never seeded with product identities. */
    public FailoverPilotAllowlist() {
        this.resolver = enrollments::get;
    }

    public FailoverPilotAllowlist(java.util.function.Function<String, PilotEnrollment> resolver) {
        this.resolver = Objects.requireNonNull(resolver);
    }

    public void enrollCluster(PilotEnrollment enrollment) {
        Objects.requireNonNull(enrollment, "enrollment must not be null");
        enrollments.put(enrollment.clusterId(), enrollment);
    }

    public boolean isClusterAllowed(String clusterId) {
        if (clusterId == null) return false;
        PilotEnrollment enrollment = resolver.apply(clusterId);
        return enrollment != null && enrollment.isEligibleForPilot();
    }

    public Set<String> enrolledMemberIds(String clusterId) {
        PilotEnrollment enrollment = clusterId == null ? null : resolver.apply(clusterId);
        return enrollment != null && enrollment.isEligibleForPilot() ? enrollment.enrolledMemberIds() : Set.of();
    }

    public boolean isExecutionAllowed(String clusterId, String targetMemberId) {
        if (clusterId == null || targetMemberId == null) return false;
        PilotEnrollment enrollment = resolver.apply(clusterId);
        if (enrollment == null || !enrollment.isEligibleForPilot()) {
            return false;
        }
        return enrollment.enrolledMemberIds().contains(targetMemberId);
    }
}
