package com.securityexpert.nexus.ui2.service.failover;

import java.time.Clock;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.service.schedule.FamilySwitch;

/** Test access to the package-private {@link CpReadinessScheduler} constructor (V203 family gate tests). */
public final class CpReadinessSchedulerAccess {
    private CpReadinessSchedulerAccess() {
    }

    public static CpReadinessScheduler scheduler(CpFailoverService service, JooqCpFailoverRepository store, Clock clock,
            FamilySwitch switches) {
        return new CpReadinessScheduler(service, store, 0, clock, switches);
    }
}
