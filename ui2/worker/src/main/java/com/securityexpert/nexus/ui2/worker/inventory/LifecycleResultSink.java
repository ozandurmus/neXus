package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.Objects;

import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.Kind;

/**
 * Where the executors hand the result of a lifecycle read (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 3).
 * Called at exactly the points where the executor already emits a {@code [LIFECYCLE_MEASURE]} line, and only for a read the
 * {@link LifecycleReadSwitch} allowed. An implementation parses and stores what the contract lists and drops the answer;
 * it must not keep, log or pass on the raw text. The default is {@link #NONE}.
 *
 * <p>Executors wrap whatever sink they are given with {@link #guarded}, so a failing sink never changes an inventory
 * result, a shape line or another read.</p>
 */
public interface LifecycleResultSink {

    /** Nothing is done with any result. */
    LifecycleResultSink NONE = new LifecycleResultSink() {
        @Override
        public void answer(Kind kind, String deviceId, String member, String raw) {
        }

        @Override
        public void notCollected(Kind kind, String deviceId, String outcome, String reason) {
        }
    };

    /**
     * A CLI, JSON or XML answer ({@code raw}, possibly blank). {@code member}: the managed device's display name for a
     * Management Center license, otherwise null.
     */
    void answer(Kind kind, String deviceId, String member, String raw);

    /** The answer without a member. */
    default void answer(Kind kind, String deviceId, String raw) {
        answer(kind, deviceId, null, raw);
    }

    /**
     * A read with no answer. {@code outcome}: {@code REFUSED}, {@code FAILED}, {@code SKIPPED} or {@code EMPTY};
     * {@code reason}: a short token (the rule that skipped it, or the result or exception class name).
     */
    void notCollected(Kind kind, String deviceId, String outcome, String reason);

    /** {@code sink} wrapped so that a RuntimeException it throws is swallowed and logged by exception class only. */
    static LifecycleResultSink guarded(LifecycleResultSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (sink instanceof Guarded) {
            return sink;
        }
        return new Guarded(sink);
    }

    /** The guard. Not {@code EndpointRuntime.rethrow}: a sink failure is never a lost lease. */
    final class Guarded implements LifecycleResultSink {
        private static final System.Logger LOG = System.getLogger(LifecycleResultSink.class.getName());
        private final LifecycleResultSink delegate;

        private Guarded(LifecycleResultSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public void answer(Kind kind, String deviceId, String member, String raw) {
            try {
                delegate.answer(kind, deviceId, member, raw);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "[LIFECYCLE_RESULT] sink failed for {0}: {1}", kind.token(), e.getClass().getSimpleName());
            }
        }

        @Override
        public void notCollected(Kind kind, String deviceId, String outcome, String reason) {
            try {
                delegate.notCollected(kind, deviceId, outcome, reason);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "[LIFECYCLE_RESULT] sink failed for {0}: {1}", kind.token(), e.getClass().getSimpleName());
            }
        }
    }
}
