package com.securityexpert.nexus.ui2.worker.health;

/**
 * One claim loop's in-process progress marker for the kubelet liveness probe ({@link WorkerHealth}). The loop calls
 * {@link #tick()} at the top of every iteration, {@link #jobStarted()} once it holds a claimed job and {@link #idle()}
 * when the iteration ends. No call touches the database. {@link #NONE} is the no-op used by tests and by loops built
 * without a health registry.
 */
public interface LoopBeat {

    /** The loop is iterating (top of an iteration). */
    void tick();

    /** A job was claimed: from here until {@link #idle()} the loop is busy and its age is not a stall. */
    void jobStarted();

    /** The iteration ended (normally or by an exception): not busy, and a fresh tick. */
    void idle();

    /** The loop left its run method (stop requested or interrupted): it no longer counts towards liveness. */
    void stopped();

    LoopBeat NONE = new LoopBeat() {
        @Override public void tick() { }
        @Override public void jobStarted() { }
        @Override public void idle() { }
        @Override public void stopped() { }
    };
}
