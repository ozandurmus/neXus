package com.securityexpert.nexus.ui2.scheduler;

import java.time.Instant;
import java.util.List;

import com.securityexpert.nexus.ui2.jobs.Job;

/**
 * Due-schedule evaluation and job-request creation (contract §2 scheduler
 * row). Scheduler never contacts a device or transport implementation
 * directly; it only ever creates job requests through job-engine ports
 * (DIR-4).
 */
public interface DueScheduleEvaluator {

    List<Job> evaluateDueSchedules(Instant asOf);
}
