package com.securityexpert.nexus.ui2.worker.policy;

import java.util.function.BiConsumer;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/** Only controlled steps, opaque references and measurements enter job diagnostics. */
final class PolicyCollectionTrace implements AutoCloseable {
    private static final ThreadLocal<PolicyCollectionTrace> ACTIVE = new ThreadLocal<>();
    record Measurement(long bytes, String outcome) {}
    record LayerProgress(int step, int layer, int layers, int rules, int packagesDone, int packagesTotal,
            long readTimeoutSeconds, long lastActivity, int domainsDone, int domainsTotal) {}
    private static final class Counters {
        final java.util.Map<String, Integer> rules = new java.util.HashMap<>();
        final java.util.Set<String> completed = new java.util.HashSet<>();
        int packagesTotal, domainsDone, domainsTotal, layer, layers;
        long timeout, lastActivity;
    }
    private final Counters counters;
    private String unit = "policy";
    private java.util.function.Consumer<LayerProgress> layerProgress = progress -> {};
    private final BiConsumer<Integer, Integer> progress;
    private final java.util.function.Consumer<Measurement> measured;
    private String step = "preflight", target;
    private final java.util.concurrent.atomic.AtomicInteger count;
    private int total;
    PolicyCollectionTrace(String target, BiConsumer<Integer, Integer> progress) {
        this(target, progress, measurement -> {});
    }
    PolicyCollectionTrace(String target, BiConsumer<Integer, Integer> progress, java.util.function.Consumer<Measurement> measured) {
        this.target = target; this.progress = progress; this.measured = measured;
        this.count = new java.util.concurrent.atomic.AtomicInteger(); counters = new Counters(); ACTIVE.set(this);
    }
    PolicyCollectionTrace(String target, BiConsumer<Integer, Integer> progress, java.util.function.Consumer<Measurement> measured,
            java.util.function.Consumer<LayerProgress> layers) {
        this(target, progress, measured); this.layerProgress = layers;
    }
    private PolicyCollectionTrace(PolicyCollectionTrace parent) {
        target = parent.target; progress = parent.progress; measured = parent.measured;
        layerProgress = parent.layerProgress; count = parent.count; total = parent.total; counters = parent.counters; ACTIVE.set(this);
    }
    static <T> java.util.concurrent.Callable<T> worker(java.util.concurrent.Callable<T> operation) {
        var parent = ACTIVE.get();
        var endpointScope = com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.current();
        var transcript = JobTranscriptScope.current();
        var cancelled = com.securityexpert.nexus.ui2.worker.JobCancellationScope.requested();
        return () -> {
            try (var endpoint = com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.attach(endpointScope);
                 var cancellation = new com.securityexpert.nexus.ui2.worker.JobCancellationScope(cancelled);
                 var scope = transcript == null ? null : JobTranscriptScope.open(transcript);
                 var trace = parent == null ? null : new PolicyCollectionTrace(parent)) {
                return operation.call();
            }
        };
    }
    static void elapsed(long nanos) {
        JobTranscriptScope.add("job", "note", "policy totalSeconds=" + nanos / 1_000_000_000.0);
    }
    static void objects(String type, int pages, int objects, long nanos) {
        String counters = "policy objects type=" + type + " pages=" + pages + " objects=" + objects
            + " seconds=" + nanos / 1_000_000_000.0;
        JobTranscriptScope.add("job", "note", counters);
        System.getLogger(PolicyCollectionTrace.class.getName()).log(System.Logger.Level.INFO, counters);
    }
    static void concurrency(int previous, int current) {
        String counts = "policy concurrency=" + current + " previous=" + previous;
        JobTranscriptScope.add("job", "note", counts);
        System.getLogger(PolicyCollectionTrace.class.getName()).log(System.Logger.Level.INFO, counts);
    }
    private void emit() {
        layerProgress.accept(new LayerProgress(count.incrementAndGet(), counters.layer, counters.layers,
            counters.rules.values().stream().mapToInt(Integer::intValue).sum(), counters.completed.size(),
            counters.packagesTotal, counters.timeout, counters.lastActivity, counters.domainsDone, counters.domainsTotal));
    }
    static void timeout(java.time.Duration timeout) {
        var trace = ACTIVE.get();
        if (trace != null) synchronized (trace.counters) { trace.counters.timeout = timeout.toSeconds(); trace.emit(); }
    }
    static void domains(int total) {
        var trace = ACTIVE.get();
        if (trace != null) synchronized (trace.counters) { trace.counters.domainsTotal = total; trace.emit(); }
    }
    static void packages(int total) {
        var trace = ACTIVE.get();
        if (trace != null) synchronized (trace.counters) {
            trace.counters.packagesTotal += total;
            if (trace.counters.domainsTotal > 0) trace.counters.domainsDone++;
            trace.emit();
        }
    }
    static void unit(String unit) { var trace = ACTIVE.get(); if (trace != null) trace.unit = unit; }
    static void done(String unit) {
        var trace = ACTIVE.get();
        if (trace != null) synchronized (trace.counters) { if (trace.counters.completed.add(unit)) trace.emit(); }
    }
    static void layer(int layer, int layers, int rules) {
        var trace = ACTIVE.get();
        if (trace != null) layer(trace.unit, layer, layers, rules);
    }
    static void layer(String unit, int layer, int layers, int rules) {
        var trace = ACTIVE.get();
        if (trace != null) synchronized (trace.counters) {
            trace.counters.layer = layer; trace.counters.layers = layers;
            trace.counters.rules.merge(unit, rules, Math::max); trace.emit();
        }
    }
    static void rules(String counter, int rules) {
        var trace = ACTIVE.get();
        if (trace != null) synchronized (trace.counters) {
            trace.counters.rules.merge(trace.unit + ":" + counter, rules, Math::max); trace.emit();
        }
    }
    static void plan(int total) { var trace = ACTIVE.get(); if (trace != null) trace.total = total; }
    static void step(String step, String target) {
        var trace = ACTIVE.get();
        if (trace != null) { trace.step = step; trace.target = target; trace.progress.accept(trace.count.incrementAndGet(), trace.total); }
        String request = step + " target=" + target;
        JobTranscriptScope.add("job", "request", request);
        System.getLogger(PolicyCollectionTrace.class.getName()).log(System.Logger.Level.INFO, request);
    }
    static void result(long started, long bytes, String outcome) {
        var trace = ACTIVE.get();
        if (trace != null) {
            trace.measured.accept(new Measurement(bytes, outcome));
            if (outcome.equals("Completed")) synchronized (trace.counters) {
                trace.counters.lastActivity = System.currentTimeMillis(); trace.emit();
            }
        }
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
                "SESSION_CLEANUP_FAILED", "SESSION_CLEANUP_TIMEOUT", "AUTHENTICATION_FAILURE", "AuthenticationFailed",
                "HostKeyRejected", "HTTP_401", "HTTP_403", "API_ERROR_16", "API_ERROR_403")
            .stream().anyMatch(code -> reason.endsWith(": " + code));
    }
    static class Failure extends IllegalStateException { Failure(String reason) { super(reason); } }
    @Override public void close() { ACTIVE.remove(); }
}
