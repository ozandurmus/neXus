package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.ArrayList;
import java.util.List;

import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.Kind;

/** Test double of {@link LifecycleResultSink}: records every call; optionally throws after recording it. */
public final class RecordingLifecycleSink implements LifecycleResultSink {

    /** One call. For an answer: {@code outcome} is "ANSWER" and {@code raw} the text; otherwise outcome and reason. */
    public record Call(String type, Kind kind, String deviceId, String member, String raw, String outcome, String reason) {
        public boolean isAnswer() {
            return "ANSWER".equals(type);
        }
    }

    public final List<Call> calls = new ArrayList<>();
    private final boolean throwing;

    public RecordingLifecycleSink() {
        this(false);
    }

    private RecordingLifecycleSink(boolean throwing) {
        this.throwing = throwing;
    }

    /** A sink that records and then throws a RuntimeException on every call. */
    public static RecordingLifecycleSink throwing() {
        return new RecordingLifecycleSink(true);
    }

    @Override
    public void answer(Kind kind, String deviceId, String member, String raw) {
        calls.add(new Call("ANSWER", kind, deviceId, member, raw, "ANSWER", null));
        if (throwing) {
            throw new IllegalStateException("synthetic sink failure");
        }
    }

    @Override
    public void notCollected(Kind kind, String deviceId, String outcome, String reason) {
        calls.add(new Call("NOT_COLLECTED", kind, deviceId, null, null, outcome, reason));
        if (throwing) {
            throw new IllegalStateException("synthetic sink failure");
        }
    }

    public List<Call> of(Kind kind) {
        return calls.stream().filter(c -> c.kind() == kind).toList();
    }
}
