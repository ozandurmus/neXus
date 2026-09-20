package com.securityexpert.nexus.ui2.jobs.failover.model;

/**
 * Constitutional verdict status for pre-flight readiness checks.
 * Aligned with the UNKNOWN / Fail-Closed law in AGENTS.md.
 */
public enum CheckStatus {
    PASS,
    FAIL,
    WARNING,
    INSUFFICIENT_EVIDENCE,
    COLLECTION_FAILED,
    NOT_EVALUABLE,
    UNSUPPORTED
}
