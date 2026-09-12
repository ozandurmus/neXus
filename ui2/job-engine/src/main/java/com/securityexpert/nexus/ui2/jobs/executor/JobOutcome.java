package com.securityexpert.nexus.ui2.jobs.executor;

/** {@link StepExecutor#execute}'s own result -- the terminal (or "stopped, not terminal") state a run reached. */
public sealed interface JobOutcome {

    record Completed() implements JobOutcome {
    }

    record Failed(String terminalReason) implements JobOutcome {
    }

    record Rejected(String reason) implements JobOutcome {
    }

    /** The executor itself observed an ambiguous response after a write step's boundary crossed (C2 §3.3). */
    record OutcomeUnknown(String reason) implements JobOutcome {
    }

    /** A fenced write returned zero rows -- this worker no longer owns the job; it stops without contacting the device again. */
    record ZombieStopped() implements JobOutcome {
    }
}
