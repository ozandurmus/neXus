package com.securityexpert.nexus.ui2.worker.systemlog;

/**
 * The per-source outcomes of contract section 9 that a plan can predict. {@code TOO_LARGE}, {@code STORAGE_REFUSED}
 * and {@code UNSUPPORTED} belong to whole-file fetches, the free-space guard and the platform check, not to the
 * planner. The outcome of a plan is a prediction; the executor decides the final one after the reads.
 */
public enum SourceOutcome {
    /** New bytes are planned and nothing was lost or deferred. */
    COLLECTED,
    /** Nothing is new. The cursor stays. */
    NO_NEW_DATA,
    /** Reads are planned up to a budget; the exact pending bytes stay on the device. */
    BACKLOG,
    /** A loss gap (or the first-run baseline) is recorded, and whatever remained is planned. */
    GAP,
    /** The base file does not exist on this device. Not a failure. */
    ABSENT,
    /** The stat output or the cursor cannot be trusted for this source; nothing is read and the cursor stays. */
    UNKNOWN
}
