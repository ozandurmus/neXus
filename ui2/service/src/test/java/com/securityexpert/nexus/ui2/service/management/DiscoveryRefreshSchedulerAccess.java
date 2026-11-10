package com.securityexpert.nexus.ui2.service.management;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.schedule.FamilySwitch;

/** Test access to the package-private {@link DiscoveryRefreshScheduler} constructor (V203 family gate tests). */
public final class DiscoveryRefreshSchedulerAccess {
    private DiscoveryRefreshSchedulerAccess() {
    }

    public static DiscoveryRefreshScheduler scheduler(TransactionBoundary tx, FamilySwitch switches) {
        return new DiscoveryRefreshScheduler(tx, null, JobWindowPolicy.SYSTEM, switches);
    }
}
