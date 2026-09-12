package com.securityexpert.nexus.ui2.worker;

import com.securityexpert.nexus.ui2.jobs.Job;
import com.securityexpert.nexus.ui2.jobs.WorkerPort;

/**
 * The worker composition root (contract §2: job claimant/execution process;
 * transport interfaces only at B1-1). Implements job-engine's
 * {@link WorkerPort} — the dependency points inward, from worker to
 * job-engine, never the reverse (DIR-3).
 */
public final class WorkerComposition implements WorkerPort {

    @Override
    public void execute(Job job) {
        // B1-1 seeds the composition root only; the transport-bound
        // execution body is out of scope for this slice.
        throw new UnsupportedOperationException("worker execution body is a later slice");
    }
}
