package com.securityexpert.nexus.ui2.integration.support;

import java.time.Clock;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/** Full-day admission for database tests that are not testing job-window boundaries. */
public final class JobWindowTestPolicy {
    // Keep the clock aligned with PostgreSQL's clock_timestamp() lease-claim guard.
    public static final JobWindowPolicy PERMISSIVE = new JobWindowPolicy(
        Clock.system(JobWindowPolicy.ZONE_ID), 360);

    private JobWindowTestPolicy() {}
}
