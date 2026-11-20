package com.securityexpert.nexus.ui2.platform.metrics;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
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

    /** Environment switch gating the Prometheus scrape endpoint; defaults to {@code false} when unset. */
    public static final String ENABLED_ENV = "UI2_METRICS_ENABLED";
    /** Internal management port for the stand-alone scrape server when enabled. */
    public static final String PORT_ENV = "UI2_METRICS_PORT";
    public static final int DEFAULT_PORT = 9404;

    public static final String JOBS_COUNTER = "nexus.jobs.transitions";
    public static final String JOB_DURATION_TIMER = "nexus.jobs.duration";
    public static final String QUEUE_DEPTH_GAUGE = "nexus.jobs.queue.depth";
    public static final String READINESS_RUNS_COUNTER = "nexus.readiness.runs";
    public static final String NOTIFICATION_SENDS_COUNTER = "nexus.notifications.sends";
    public static final String ARTEFACT_STORE_BYTES_GAUGE = "nexus.artefact.store.bytes";

    private static final Pattern SAFE_TYPE_TOKEN = Pattern.compile("[a-z0-9_]{1,48}");
    private static final Set<String> JOB_STATES = Set.of(
            "REQUESTED", "CLAIMED", "EXECUTING", "COMPLETED", "FAILED",
            "REJECTED", "CANCELLED", "OUTCOME_UNKNOWN", "RECONCILED");
    private static final Set<String> READINESS_OUTCOMES = Set.of("READY", "NOT_READY", "UNKNOWN");
    private static final Set<String> NOTIFICATION_TYPES = Set.of(
            "admin_event", "login_security", "backup_failure", "job_failure", "config_change",
            "device_crash_file", "backup_size_anomaly", "compliance_regression", "device_health",
            "security_scan", "syslog", "syslog_audit", "syslog_credential_alert", "syslog_break_glass",
            "syslog_job_failure", "syslog_disk_headroom", "syslog_test", "smtp", "mail_test");
    private static final Set<String> NOTIFICATION_RESULTS = Set.of("success", "failure", "skipped");

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

    /** True only when {@link #ENABLED_ENV} is explicitly set to {@code true}, {@code 1} or {@code on}; default is {@code false}. */
    public static boolean isEnabled() {
        return isEnabled(System.getenv());
    }

    public static boolean isEnabled(Map<String, String> env) {
        if (env == null) return false;
        String raw = env.get(ENABLED_ENV);
        if (raw == null || raw.isBlank()) return false;
        String trimmed = raw.trim();
        return "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed) || "on".equalsIgnoreCase(trimmed);
    }

    /** Resolves {@link #PORT_ENV} (defaults to {@link #DEFAULT_PORT} when unset; 0 or out-of-range disables the port). */
    public static int resolvePort(Map<String, String> env) {
        if (env == null) return 0;
        String raw = env.get(PORT_ENV);
        if (raw == null || raw.isBlank()) return DEFAULT_PORT;
        try {
            int port = Integer.parseInt(raw.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Starts the scrape server when {@code UI2_METRICS_ENABLED} is true and {@code UI2_METRICS_PORT} names a valid port
     * (1..65535, default 9404). Disabled by default ({@code UI2_METRICS_ENABLED} unset or false). Failure to bind never
     * stops the workload.
     */
    public static MetricsServer startFromEnvironment(String role) {
        return startFromEnvironment(role, System.getenv());
    }

    public static MetricsServer startFromEnvironment(String role, Map<String, String> env) {
        PrometheusMeterRegistry meters = init(role);
        if (!isEnabled(env)) return null;
        int port = resolvePort(env);
        if (port < 1 || port > 65535) return null;
        try {
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

    /** Jobs by type and state counter (Prometheus {@code nexus_jobs_transitions_total}). */
    public static void recordJobState(String jobType, String state) {
        recordJobState(registry(), jobType, state);
    }

    public static void recordJobState(MeterRegistry target, String jobType, String state) {
        Counter.builder(JOBS_COUNTER)
                .description("Job counts by job type and state")
                .tag("type", normalizeJobType(jobType))
                .tag("state", normalizeJobState(state))
                .register(target)
                .increment();
    }

    /** Job execution duration timer by type and terminal state (Prometheus {@code nexus_jobs_duration_seconds}). */
    public static void recordJobDuration(String jobType, String state, Duration duration) {
        recordJobDuration(registry(), jobType, state, duration);
    }

    public static void recordJobDuration(MeterRegistry target, String jobType, String state, Duration duration) {
        if (duration == null || duration.isNegative()) return;
        Timer.builder(JOB_DURATION_TIMER)
                .description("Job execution duration by job type and state")
                .tag("type", normalizeJobType(jobType))
                .tag("state", normalizeJobState(state))
                .register(target)
                .record(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Queue depth gauge (Prometheus {@code nexus_jobs_queue_depth}). */
    public static void registerQueueDepth(LongSupplier queueDepth) {
        registerQueueDepth(registry(), queueDepth);
    }

    public static void registerQueueDepth(MeterRegistry target, LongSupplier queueDepth) {
        Objects.requireNonNull(queueDepth, "queueDepth");
        Gauge.builder(QUEUE_DEPTH_GAUGE, queueDepth, supplier -> {
                    try {
                        return Math.max(0L, supplier.getAsLong());
                    } catch (RuntimeException e) {
                        return 0.0;
                    }
                })
                .description("Jobs waiting to be claimed")
                .strongReference(true)
                .register(target);
    }

    /** Readiness runs by outcome counter (Prometheus {@code nexus_readiness_runs_total}). */
    public static void recordReadinessRun(String outcome) {
        recordReadinessRun(registry(), outcome);
    }

    public static void recordReadinessRun(MeterRegistry target, String outcome) {
        Counter.builder(READINESS_RUNS_COUNTER)
                .description("Failover readiness runs by outcome")
                .tag("outcome", normalizeReadinessOutcome(outcome))
                .register(target)
                .increment();
    }

    /** Notification sends by type and result counter (Prometheus {@code nexus_notifications_sends_total}). */
    public static void recordNotificationSend(String type, String result) {
        recordNotificationSend(registry(), type, result);
    }

    public static void recordNotificationSend(MeterRegistry target, String type, String result) {
        Counter.builder(NOTIFICATION_SENDS_COUNTER)
                .description("Notification send attempts by type and result")
                .tag("type", normalizeNotificationType(type))
                .tag("result", normalizeNotificationResult(result))
                .register(target)
                .increment();
    }

    /** Encrypted artefact store usage in bytes gauge (Prometheus {@code nexus_artefact_store_bytes}). */
    public static void registerArtefactStoreBytes(LongSupplier bytesSupplier) {
        registerArtefactStoreBytes(registry(), bytesSupplier);
    }

    public static void registerArtefactStoreBytes(MeterRegistry target, LongSupplier bytesSupplier) {
        Objects.requireNonNull(bytesSupplier, "bytesSupplier");
        Gauge.builder(ARTEFACT_STORE_BYTES_GAUGE, bytesSupplier, supplier -> {
                    try {
                        return Math.max(0L, supplier.getAsLong());
                    } catch (RuntimeException e) {
                        return 0.0;
                    }
                })
                .description("Bytes stored in the encrypted artefact store")
                .strongReference(true)
                .register(target);
    }

    static String normalizeJobType(String jobType) {
        if (jobType == null || jobType.isBlank()) return "unknown";
        String candidate = jobType.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_TYPE_TOKEN.matcher(candidate).matches() || !MetricLabelGuard.safeValue(candidate)) {
            return "unknown";
        }
        return candidate;
    }

    static String normalizeJobState(String state) {
        if (state == null || state.isBlank()) return "UNKNOWN";
        String candidate = state.trim().toUpperCase(Locale.ROOT);
        return JOB_STATES.contains(candidate) ? candidate : "UNKNOWN";
    }

    static String normalizeReadinessOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) return "UNKNOWN";
        String candidate = outcome.trim().toUpperCase(Locale.ROOT);
        return READINESS_OUTCOMES.contains(candidate) ? candidate : "UNKNOWN";
    }

    static String normalizeNotificationType(String type) {
        if (type == null || type.isBlank()) return "other";
        String candidate = type.trim().toLowerCase(Locale.ROOT);
        return NOTIFICATION_TYPES.contains(candidate) ? candidate : "other";
    }

    static String normalizeNotificationResult(String result) {
        if (result == null || result.isBlank()) return "failure";
        String candidate = result.trim().toLowerCase(Locale.ROOT);
        if ("sent".equals(candidate) || "ok".equals(candidate)) return "success";
        if ("failed".equals(candidate) || "error".equals(candidate)) return "failure";
        return NOTIFICATION_RESULTS.contains(candidate) ? candidate : "failure";
    }
}
