package com.securityexpert.nexus.ui2.service.audit;

/**
 * Contract §3.2's per-key comparison for an {@code UPDATE} row, computed by
 * comparing the (possibly redacted) {@code before}/{@code after} JSON values
 * of that key server-side. For a redacted key this is exactly a comparison
 * of the two {@code sha256:} digests — never the digests themselves reaching
 * the payload.
 */
public enum AuditChangeState {

    /** The before/after values of this key differ. */
    CHANGED,

    /** The before/after values of this key are equal. */
    UNCHANGED,

    /**
     * One side is absent — an {@code INSERT}/{@code DELETE} row, or a key
     * present on only one side. Never defaulted to {@code UNCHANGED}.
     */
    NOT_EVALUABLE
}
