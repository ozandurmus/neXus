package com.securityexpert.nexus.ui2.jobs.failover.pilot;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-owned pilot allowlist fence.
 * In accordance with Phase C risk containment laws:
 * 1. Execution is strictly confined to explicitly authorized lab clusters.
 * 2. Non-lab / production / UNKNOWN environments fail closed.
 * 3. Enforces that both member endpoints are enrolled within the pilot scope.
 */
public class FailoverPilotAllowlist {

    public record PilotEnrollment(
        String clusterId,
        String environmentClassification, // strictly "LAB_PILOT"
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
            return active && "LAB_PILOT".equalsIgnoreCase(environmentClassification);
        }
    }

    private final Map<String, PilotEnrollment> enrollments = new ConcurrentHashMap<>();

    public FailoverPilotAllowlist() {
        // Seed authorized lab pilot clusters
        enrollCluster(new PilotEnrollment(
            "cls-uuid-cp",
            "LAB_PILOT",
            Set.of("dev-cp-1", "dev-cp-2", "FW-TANGO-01", "FW-JULIET-06"),
            "PO_APPROVED_LAB_PILOT",
            true
        ));
        enrollCluster(new PilotEnrollment(
            "cls-uuid-pa",
            "LAB_PILOT",
            Set.of("dev-pa-1", "dev-pa-2", "FW-TANGO-04", "FW-BRAVO-02"),
            "PO_APPROVED_LAB_PILOT",
            true
        ));
        enrollCluster(new PilotEnrollment(
            "cls-01",
            "LAB_PILOT",
            Set.of("dev-cp-1", "dev-cp-2", "FW-TANGO-01", "FW-JULIET-06", "cls-01"),
            "PO_APPROVED_LAB_PILOT",
            true
        ));
    }

    public void enrollCluster(PilotEnrollment enrollment) {
        Objects.requireNonNull(enrollment, "enrollment must not be null");
        enrollments.put(enrollment.clusterId(), enrollment);
    }

    public boolean isClusterAllowed(String clusterId) {
        if (clusterId == null) return false;
        PilotEnrollment enrollment = enrollments.get(clusterId);
        return enrollment != null && enrollment.isEligibleForPilot();
    }

    public boolean isExecutionAllowed(String clusterId, String targetMemberId) {
        if (clusterId == null || targetMemberId == null) return false;
        PilotEnrollment enrollment = enrollments.get(clusterId);
        if (enrollment == null || !enrollment.isEligibleForPilot()) {
            return false;
        }
        return enrollment.enrolledMemberIds().contains(targetMemberId);
    }
}
