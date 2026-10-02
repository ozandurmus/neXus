package com.securityexpert.nexus.ui2.worker.policy;

import java.util.function.BiConsumer;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/** Only controlled steps, opaque references and measurements enter job diagnostics. */
final class PolicyCollectionTrace implements AutoCloseable {
    private static final ThreadLocal<PolicyCollectionTrace> ACTIVE = new ThreadLocal<>();
    record Measurement(long bytes, String outcome) {}
    record LayerProgress(int step, int layer, int layers, int rules) {}
    private java.util.function.Consumer<LayerProgress> layerProgress = progress -> {};
    private final BiConsumer<Integer, Integer> progress;
    private final java.util.function.Consumer<Measurement> measured;
    private String step = "preflight", target;
    private int count, total;
    PolicyCollectionTrace(String target, BiConsumer<Integer, Integer> progress) {
        this(target, progress, measurement -> {});
    }
    PolicyCollectionTrace(String target, BiConsumer<Integer, Integer> progress, java.util.function.Consumer<Measurement> measured) {
        this.target = target; this.progress = progress; this.measured = measured; ACTIVE.set(this);
    }
    PolicyCollectionTrace(String target, BiConsumer<Integer, Integer> progress, java.util.function.Consumer<Measurement> measured,
            java.util.function.Consumer<LayerProgress> layers) {
        this(target, progress, measured); this.layerProgress = layers;
    }
    static void layer(int layer, int layers, int rules) {
        var trace = ACTIVE.get();
        if (trace != null) trace.layerProgress.accept(new LayerProgress(++trace.count, layer, layers, rules));
    }
    static void plan(int total) { var trace = ACTIVE.get(); if (trace != null) trace.total = total; }
    static void step(String step, String target) {
        var trace = ACTIVE.get();
        if (trace != null) { trace.step = step; trace.target = target; trace.progress.accept(++trace.count, trace.total); }
        String request = step + " target=" + target;
        JobTranscriptScope.add("job", "request", request);
        System.getLogger(PolicyCollectionTrace.class.getName()).log(System.Logger.Level.INFO, request);
    }
    static void result(long started, long bytes, String outcome) {
        var trace = ACTIVE.get();
        if (trace != null) trace.measured.accept(new Measurement(bytes, outcome));
        JobTranscriptScope.add("job", "response", "bytes=" + (bytes < 0 ? "UNKNOWN" : bytes) + " durationMs=" + (System.nanoTime() - started) / 1_000_000 + " outcome=" + outcome);
    }
    static Failure failure(String reason) {
        var trace = ACTIVE.get();
        String message = (trace == null ? "policy" : trace.step + " target=" + trace.target) + ": " + reason;
        JobTranscriptScope.add("job", "note", message);
        return new Failure(message);
    }
    static String reason(Exception error) {
        return error instanceof com.securityexpert.nexus.ui2.worker.JobCancellationScope.Cancelled ? failure("CANCELLED").getMessage()
            : error instanceof Failure ? error.getMessage() : failure(error instanceof IllegalStateException && "POLICY_GATE_UNAVAILABLE".equals(error.getMessage())
                ? "POLICY_GATE_UNAVAILABLE" : "FAILED_" + error.getClass().getSimpleName()).getMessage();
    }
    static boolean fatal(Exception error) {
        String reason = reason(error);
        return java.util.Set.of("CANCELLED", "LEASE_LOST", "JOB_DEADLINE", "POLICY_GATE_UNAVAILABLE", "INTERRUPTED",
                "AUTHENTICATION_FAILURE", "AuthenticationFailed", "HTTP_401", "HTTP_403", "API_ERROR_16", "API_ERROR_403")
            .stream().anyMatch(code -> reason.endsWith(": " + code));
    }
    static class Failure extends IllegalStateException { Failure(String reason) { super(reason); } }
    @Override public void close() { ACTIVE.remove(); }
}
