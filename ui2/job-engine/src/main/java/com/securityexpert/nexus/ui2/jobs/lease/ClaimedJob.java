package com.securityexpert.nexus.ui2.jobs.lease;

/** The claim statement's own {@code RETURNING} row (C2 §4.1). */
public record ClaimedJob(String jobId, long leaseEpoch) {
}
