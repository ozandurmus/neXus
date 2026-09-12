package com.securityexpert.nexus.ui2.persistence.jobrecords;

/** The claim statement's {@code RETURNING} row, plain-typed. */
public record ClaimedJobRow(String jobId, long leaseEpoch) {
}
