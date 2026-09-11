package com.securityexpert.nexus.ui2.jobs;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * A job instance (C2 §2). job-engine is allowed to reference
 * capability-registry types (contract §2 row) but never a worker,
 * scheduler, or adapter implementation type.
 */
public record Job(OpaqueId id, Capability capability, JobState state) {

    public Job transitionTo(JobState next) {
        return new Job(id, capability, next);
    }
}
