package com.securityexpert.nexus.ui2.jobs;

import java.util.Map;
import java.util.Set;

/**
 * C2 §3.1/§3.2's closed job state set and legal-transitions graph, restated
 * in code. {@link #canTransitionTo} is the single place that graph is
 * encoded (C2 §3.2: "no other edge exists") -- every state-affecting
 * persistence write carries {@code WHERE state = <expected>}, so this
 * method is what a repository consults before attempting one, and what a
 * test (contract §8 test 1 / C2 §9 AC-1) asserts against exhaustively.
 */
public enum JobState {
    REQUESTED(false),
    CLAIMED(false),
    EXECUTING(false),
    COMPLETED(true),
    FAILED(true),
    REJECTED(true),
    CANCELLED(true),
    OUTCOME_UNKNOWN(true),
    RECONCILED(true);

    private final boolean terminal;

    JobState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    private static final Map<JobState, Set<JobState>> LEGAL_TRANSITIONS = Map.of(
            REQUESTED, Set.of(CLAIMED, CANCELLED),
            CLAIMED, Set.of(REQUESTED, REJECTED, CANCELLED, EXECUTING),
            EXECUTING, Set.of(COMPLETED, FAILED, OUTCOME_UNKNOWN, EXECUTING),
            COMPLETED, Set.of(),
            FAILED, Set.of(),
            REJECTED, Set.of(),
            CANCELLED, Set.of(),
            OUTCOME_UNKNOWN, Set.of(RECONCILED),
            RECONCILED, Set.of());

    /**
     * C2 §3.2's graph, exhaustively: every {@code (from, to)} pair not
     * listed there returns {@code false}. {@code EXECUTING -> EXECUTING}
     * is the one same-state "edge," representing a read-class bounded
     * retry's new {@code job_step_attempt} row at an unchanged job state
     * (C2 §5.3) -- never a job-state UPDATE by itself, but modelled here so
     * a caller can ask "is a write while remaining EXECUTING legal" the
     * same way it asks about every other transition.
     */
    public boolean canTransitionTo(JobState target) {
        return LEGAL_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
