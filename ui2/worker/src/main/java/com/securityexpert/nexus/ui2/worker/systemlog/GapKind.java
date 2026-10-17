package com.securityexpert.nexus.ui2.worker.systemlog;

/** Gap kinds of contract section 4.5. A gap is a byte range the pool will never hold, except {@link #BACKLOG}. */
public enum GapKind {
    /** The cursor's file (or whole files after it) rotated away before it was read. */
    ROTATED_AWAY,
    /** The file shrank below the cursor and no sibling continued it. */
    TRUNCATED,
    /** The bytes at the cursor are not what was read before; the extent is unknown. */
    ANCHOR_MISMATCH,
    /** First run: the prefix of the live file older than the backfill window. */
    INITIAL_BASELINE,
    /** Not yet a loss: a budget stopped the run; the unread bytes are still on the device. */
    BACKLOG,
    /** The run could not read the source; the window is reported and any loss shows up next run. */
    COLLECTION_FAILED
}
