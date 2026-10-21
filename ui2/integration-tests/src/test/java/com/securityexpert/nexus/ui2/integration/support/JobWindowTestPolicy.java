package com.securityexpert.nexus.ui2.integration.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/**
 * Policies for database tests. None of them reads the wall clock for business hours, so a suite run at 10:00 or at
 * 23:00 Europe/Istanbul behaves the same.
 */
public final class JobWindowTestPolicy {
    /**
     * Business hours that no real instant is inside (the single nanosecond before midnight), so a test that is not
     * about business hours never meets an acknowledgement requirement, whatever the time of day. The production default
     * and {@code UI2_BUSINESS_HOURS} are deliberately not consulted.
     */
    public static final BusinessHours NEVER_BUSINESS_HOURS = new BusinessHours(LocalTime.MAX.minusNanos(1), LocalTime.MAX);
    /** The production business hours (PO decision 2026-10-20), independent of {@code UI2_BUSINESS_HOURS}. */
    public static final BusinessHours DEFAULT_BUSINESS_HOURS = new BusinessHours(BusinessHours.DEFAULT_START, BusinessHours.DEFAULT_END);

    // Keep the clock aligned with PostgreSQL's clock_timestamp() lease-claim guard.
    /** Full-day admission, never inside business hours: for database tests that are not testing job-window boundaries. */
    public static final JobWindowPolicy PERMISSIVE = new JobWindowPolicy(
        Clock.system(JobWindowPolicy.ZONE_ID), 360, NEVER_BUSINESS_HOURS);

    /** A fixed instant, with the production business hours: for tests of the acknowledgement rule itself. */
    public static JobWindowPolicy at(String utcInstant) {
        return new JobWindowPolicy(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC), 60, DEFAULT_BUSINESS_HOURS);
    }

    private JobWindowTestPolicy() {}
}
