package com.securityexpert.nexus.ui2.service.audit;

/**
 * The five states {@code UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md} §3.2
 * defines for one snapshot key. No layer downstream of
 * {@link AuditFieldProjector} may collapse any two of these into the same
 * shape — each carries a distinct payload (see {@link AuditFieldProjection}).
 */
public enum AuditFieldState {

    /** The column is not in the redaction set; its value is rendered verbatim. */
    PRESENT,

    /** The key exists in the snapshot and its value is JSON {@code null}. */
    NULL,

    /**
     * The column is in the redaction set and held a non-null value at
     * capture time. The digest itself never reaches this state's payload —
     * only {@code tier} and {@code reason}, read from
     * {@code audit_redaction_policy}.
     */
    REDACTED,

    /** The key is not present in this snapshot at all. */
    ABSENT,

    /**
     * The key is in neither the committed allowlist nor the redaction
     * policy. Per contract §3.3, an {@code UNCLASSIFIED} key causes every
     * {@code PRESENT} field of the *whole snapshot* to be withheld.
     */
    UNCLASSIFIED
}
