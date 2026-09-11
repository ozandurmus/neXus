package com.securityexpert.nexus.ui2.jobs;

/**
 * The C2 job state machine's closed state set (C2 §2).
 */
public enum JobState {
    REQUESTED,
    ADMITTED,
    CLAIMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
