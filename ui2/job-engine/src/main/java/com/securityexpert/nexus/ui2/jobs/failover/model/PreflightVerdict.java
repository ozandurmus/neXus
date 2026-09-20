package com.securityexpert.nexus.ui2.jobs.failover.model;

/**
 * Top-level assessment verdict for pre-flight readiness.
 * Note: Does NOT imply authorization or safety to failover.
 */
public enum PreflightVerdict {
    NO_BLOCKING_CONDITIONS_OBSERVED,
    BLOCKING_CONDITIONS_PRESENT
}
