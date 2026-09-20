package com.securityexpert.nexus.ui2.jobs.failover.schedule;

/**
 * 3-valued comparison outcome for an individual drift evaluation dimension.
 * Per constitutional UNKNOWN law: missing or unprovable fields return NOT_EVALUABLE, never MATCH.
 */
public enum DriftDimensionStatus {
    MATCH,
    MISMATCH,
    NOT_EVALUABLE
}
