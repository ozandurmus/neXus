package com.securityexpert.nexus.ui2.worker.systemlog;

/** Why a read action exists; the executor stores it with the run record. */
public enum ReadPurpose {
    /** First run: the last backfill bytes of the live file. */
    INITIAL_BASELINE,
    /** The cursor's file grew: its bytes from the cursor offset. */
    GROWTH,
    /** Rename rotation: the unread remainder of the rotated sibling that holds the cursor's inode. */
    ROTATED_REMAINDER,
    /** Copytruncate: the unread remainder in the sibling copy, verified by the anchor. */
    COPYTRUNCATE_REMAINDER,
    /** A rotated sibling the product never read, whole. */
    ROTATED_SIBLING_WHOLE,
    /** The live file from byte 0 after a rotation, a truncation or a rotated-away cursor. */
    CURRENT_FROM_START,
    /** A file read from byte 0 because its anchor did not match at the cursor offset. */
    ANCHOR_RESTART
}
