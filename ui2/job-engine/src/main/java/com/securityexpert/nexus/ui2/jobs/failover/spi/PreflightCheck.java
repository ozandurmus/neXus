package com.securityexpert.nexus.ui2.jobs.failover.spi;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;

/**
 * Pure evaluator SPI for failover pre-flight checks.
 * Invariant: Implementations MUST NOT touch network transports or hold credential handles.
 * All evaluations are pure functions over the immutable ClusterEvidenceSnapshot.
 */
public interface PreflightCheck {
    String id();
    String name();
    String category();
    EnforcementPolicy defaultPolicy();
    boolean appliesTo(String vendor, String haMode);
    CheckResult evaluate(ClusterEvidenceSnapshot snapshot);
}
