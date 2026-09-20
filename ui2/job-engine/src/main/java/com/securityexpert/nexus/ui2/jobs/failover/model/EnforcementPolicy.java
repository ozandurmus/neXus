package com.securityexpert.nexus.ui2.jobs.failover.model;

/**
 * Enforcement policy mapping for pre-flight checks.
 * BLOCKING checks strictly abort failover eligibility on non-PASS.
 * ADVISORY checks surface warnings and operational disclosures.
 */
public enum EnforcementPolicy {
    BLOCKING,
    ADVISORY
}
