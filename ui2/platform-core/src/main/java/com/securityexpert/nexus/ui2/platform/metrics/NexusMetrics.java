package com.securityexpert.nexus.ui2.platform.metrics;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * The process-wide metrics registry (enterprise phase 0.4, docs/operations/METRICS.md). One Prometheus registry per
 * process, created on first use with the JVM and process binders and {@link MetricLabelGuard}. The workload role
 * ({@code service}, {@code worker}, {@code policy}, ...) is the one common label.
 */
public final class NexusMetrics {

    private static PrometheusMeterRegistry registry;

    private NexusMetrics() {
    }

    /** The process registry, created on first use; {@code role} is honoured only by that first call. */
    public static synchronized PrometheusMeterRegistry init(String role) {
        if (registry == null) registry = create(role);
        return registry;
    }

    public static PrometheusMeterRegistry registry() {
        return init("unknown");
    }

    /** A fresh, fully wired registry (tests use this; production uses {@link #init}). */
    public static PrometheusMeterRegistry create(String role) {
        PrometheusMeterRegistry created = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        created.config().meterFilter(MetricLabelGuard.filter());
        created.config().commonTags(Tags.of("role", role == null || role.isBlank() ? "unknown" : role));
        new ClassLoaderMetrics().bindTo(created);
        new JvmMemoryMetrics().bindTo(created);
        new JvmGcMetrics().bindTo(created);
        new JvmThreadMetrics().bindTo(created);
        new ProcessorMetrics().bindTo(created);
        new UptimeMetrics().bindTo(created);
        new FileDescriptorMetrics().bindTo(created);
        return created;
    }

    /**
     * Starts the scrape server when {@code UI2_METRICS_PORT} names a port (1..65535); unset, 0 or invalid leaves it
     * off. Failure to bind never stops the workload.
     */
    public static MetricsServer startFromEnvironment(String role) {
        PrometheusMeterRegistry meters = init(role);
        String raw = System.getenv("UI2_METRICS_PORT");
        if (raw == null || raw.isBlank()) return null;
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65535) return null;
            return MetricsServer.start(port, meters);
        } catch (Exception e) {
            System.err.println("metrics server not started: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Backup attempt outcome counter; {@code outcome} is one of a fixed enum. */
    public static void backupAttempt(String outcome) {
        registry().counter("nexus.backup.attempts", "outcome", outcome).increment();
    }
}
