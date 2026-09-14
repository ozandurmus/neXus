package com.securityexpert.nexus.ui2.persistence.discovery;

/**
 * {@code discovery_run.state} (V14 {@code chk_discovery_run_state}). A
 * smaller, run-scoped state machine than the {@code C2} job it is the
 * target of -- the job's own state (REQUESTED/CLAIMED/EXECUTING/...) tracks
 * admission and leasing; this state tracks what the run itself has
 * produced. {@code RUNNING} is set once the worker has claimed the job and
 * begun the enumeration; {@code FINISHED}/{@code FAILED} are terminal.
 */
public enum DiscoveryRunState {
    REQUESTED,
    RUNNING,
    FINISHED,
    FAILED;

    public boolean isTerminal() {
        return this == FINISHED || this == FAILED;
    }
}
