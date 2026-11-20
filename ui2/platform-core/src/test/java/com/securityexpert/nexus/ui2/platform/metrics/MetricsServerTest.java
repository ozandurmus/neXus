package com.securityexpert.nexus.ui2.platform.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.junit.jupiter.api.Test;

class MetricsServerTest {

    private static final Pattern LABEL = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=\"([^\"]*)\"");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private static HttpResponse<String> call(int port, String method, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesJvmMetricsOnMetricsAndNothingElse() throws Exception {
        PrometheusMeterRegistry registry = NexusMetrics.create("service");
        try (MetricsServer server = MetricsServer.start(0, registry)) {
            HttpResponse<String> scrape = call(server.port(), "GET", "/metrics");
            assertEquals(200, scrape.statusCode());
            assertTrue(scrape.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
            assertTrue(scrape.body().contains("jvm_memory_used_bytes"), "JVM memory");
            assertTrue(scrape.body().contains("jvm_threads_live_threads"), "JVM threads");
            assertTrue(scrape.body().contains("process_uptime_seconds"), "uptime");
            assertTrue(scrape.body().contains("role=\"service\""), "role label");
            for (String path : new String[] {"/", "/actuator", "/actuator/prometheus", "/metrics/", "/health", "/api/v2/devices"}) {
                assertEquals(404, call(server.port(), "GET", path).statusCode(), path);
            }
            assertEquals(405, call(server.port(), "POST", "/metrics").statusCode());
            assertEquals(405, call(server.port(), "DELETE", "/metrics").statusCode());
        }
    }

    @Test
    void theGuardDropsUnknownLabelKeysAndRedactsAddressDeviceAndUserValues() {
        PrometheusMeterRegistry registry = NexusMetrics.create("service");
        registry.counter("test.hostile", "host", "fw-core-01.example.test", "state", "192.0.2.10",
                "uri", "/devices/lab-fw-serial-123 name", "type", "FW-TANGO-04", "user", "operator@example.test",
                "outcome", "ok").increment();
        registry.counter("test.hostile2", "state", "fe80::1", "type", "CLS-ROMEO-01",
                "outcome", "a b c d e f g h i j k l m n o p q r s t u v w x y z 0123456789 0123456789").increment();
        String scrape = registry.scrape();
        assertFalse(scrape.contains("fw-core-01"), scrape);
        assertFalse(scrape.contains("192.0.2.10"), scrape);
        assertFalse(scrape.contains("fe80"), scrape);
        assertFalse(scrape.contains("FW-TANGO-04"), scrape);
        assertFalse(scrape.contains("CLS-ROMEO-01"), scrape);
        assertFalse(scrape.contains("operator@example.test"), scrape);
        assertFalse(scrape.contains("host="), scrape);
        assertFalse(scrape.contains("user="), scrape);
        assertTrue(scrape.contains("state=\"REDACTED\""), scrape);
        assertTrue(scrape.contains("type=\"REDACTED\""), scrape);
        assertTrue(scrape.contains("outcome=\"ok\""), scrape);
    }

    @Test
    void everyLabelOfTheWholeScrapeIsAnAllowedKeyWithASafeNonAddressValue() throws Exception {
        PrometheusMeterRegistry registry = NexusMetrics.create("worker");
        registry.counter("nexus.backup.attempts", "outcome", "completed").increment();
        NexusMetrics.recordJobState(registry, "cp_inventory_collect", "COMPLETED");
        NexusMetrics.recordJobDuration(registry, "cp_inventory_collect", "COMPLETED", Duration.ofMillis(42));
        NexusMetrics.registerQueueDepth(registry, () -> 3L);
        NexusMetrics.recordReadinessRun(registry, "READY");
        NexusMetrics.recordNotificationSend(registry, "backup_failure", "success");
        NexusMetrics.registerArtefactStoreBytes(registry, () -> 1048576L);
        Timer.builder("http.server.requests").tag("method", "GET").tag("uri", "/devices/{id}")
                .tag("status", "200").tag("outcome", "SUCCESS").register(registry).record(Duration.ofMillis(3));
        System.gc();
        try (MetricsServer server = MetricsServer.start(0, registry)) {
            String body = call(server.port(), "GET", "/metrics").body();
            Set<String> allowed = MetricLabelGuard.ALLOWED_KEYS;
            // Prometheus adds these two structural labels itself; they carry numbers and quantile bounds only.
            Set<String> structural = Set.of("le", "quantile");
            int labels = 0;
            for (String line : body.split("\n")) {
                if (line.startsWith("#") || !line.contains("{")) continue;
                Matcher m = LABEL.matcher(line.substring(line.indexOf('{'), line.lastIndexOf('}') + 1));
                while (m.find()) {
                    labels++;
                    String key = m.group(1);
                    String value = m.group(2);
                    if (structural.contains(key)) continue;
                    assertTrue(allowed.contains(key), "label key " + key + " in " + line);
                    assertTrue(MetricLabelGuard.safeValue(value), "label value " + value + " in " + line);
                    assertFalse(IPV4.matcher(value).find(), "address-like value in " + line);
                    assertFalse(value.contains("@"), "user-like value in " + line);
                }
            }
            assertTrue(labels > 10, "scrape carried labels");
        }
    }

    @Test
    void theBackupAttemptCounterCarriesOnlyTheOutcome() {
        PrometheusMeterRegistry registry = NexusMetrics.registry();
        NexusMetrics.backupAttempt("failed");
        var counter = registry.find("nexus.backup.attempts").tag("outcome", "failed").counter();
        assertEquals(1.0, counter.count());
        assertEquals(1, counter.getId().getTags().stream().filter(t -> !t.getKey().equals("role")).count());
        assertTrue(counter.getId().getTags().contains(Tag.of("outcome", "failed")));
    }

    @Test
    void meterFamily1_jobsCounterRecordsByTypeAndClosedStateOnly() {
        PrometheusMeterRegistry registry = NexusMetrics.create("worker");
        NexusMetrics.recordJobState(registry, "cp_inventory_collect", "COMPLETED");
        NexusMetrics.recordJobState(registry, "cp_inventory_collect", "COMPLETED");
        NexusMetrics.recordJobState(registry, "pan_configuration_collect", "FAILED");
        NexusMetrics.recordJobState(registry, "FW-TANGO-04", "192.0.2.10");

        assertEquals(2.0, registry.get(NexusMetrics.JOBS_COUNTER)
                .tag("type", "cp_inventory_collect").tag("state", "COMPLETED").counter().count());
        assertEquals(1.0, registry.get(NexusMetrics.JOBS_COUNTER)
                .tag("type", "pan_configuration_collect").tag("state", "FAILED").counter().count());
        assertEquals(1.0, registry.get(NexusMetrics.JOBS_COUNTER)
                .tag("type", "unknown").tag("state", "UNKNOWN").counter().count());
        String scrape = registry.scrape();
        assertTrue(scrape.contains("nexus_jobs_transitions_total{"));
        assertFalse(scrape.contains("FW-TANGO-04"));
        assertFalse(scrape.contains("192.0.2.10"));
    }

    @Test
    void meterFamily2_jobDurationTimerRecordsElapsedTimeByTypeAndState() {
        PrometheusMeterRegistry registry = NexusMetrics.create("worker");
        NexusMetrics.recordJobDuration(registry, "cp_gaia_backup_local", "COMPLETED", Duration.ofMillis(250));
        NexusMetrics.recordJobDuration(registry, "cp_gaia_backup_local", "COMPLETED", Duration.ofMillis(750));

        Timer timer = registry.get(NexusMetrics.JOB_DURATION_TIMER)
                .tag("type", "cp_gaia_backup_local").tag("state", "COMPLETED").timer();
        assertEquals(2L, timer.count());
        assertEquals(1.0, timer.totalTime(TimeUnit.SECONDS), 0.001);
        assertTrue(registry.scrape().contains("nexus_jobs_duration_seconds_count{"));
    }

    @Test
    void meterFamily3_queueDepthGaugeReflectsLiveCount() {
        PrometheusMeterRegistry registry = NexusMetrics.create("service");
        AtomicLong depth = new AtomicLong(5L);
        NexusMetrics.registerQueueDepth(registry, depth::get);

        assertEquals(5.0, registry.get(NexusMetrics.QUEUE_DEPTH_GAUGE).gauge().value());
        depth.set(12L);
        assertEquals(12.0, registry.get(NexusMetrics.QUEUE_DEPTH_GAUGE).gauge().value());
        depth.set(-4L);
        assertEquals(0.0, registry.get(NexusMetrics.QUEUE_DEPTH_GAUGE).gauge().value());
        assertTrue(registry.scrape().contains("nexus_jobs_queue_depth{role=\"service\"} 0.0"));
    }

    @Test
    void meterFamily4_readinessRunsCounterRecordsByClosedOutcome() {
        PrometheusMeterRegistry registry = NexusMetrics.create("worker");
        NexusMetrics.recordReadinessRun(registry, "READY");
        NexusMetrics.recordReadinessRun(registry, "READY");
        NexusMetrics.recordReadinessRun(registry, "NOT_READY");
        NexusMetrics.recordReadinessRun(registry, "CLS-ROMEO-01");

        assertEquals(2.0, registry.get(NexusMetrics.READINESS_RUNS_COUNTER).tag("outcome", "READY").counter().count());
        assertEquals(1.0, registry.get(NexusMetrics.READINESS_RUNS_COUNTER).tag("outcome", "NOT_READY").counter().count());
        assertEquals(1.0, registry.get(NexusMetrics.READINESS_RUNS_COUNTER).tag("outcome", "UNKNOWN").counter().count());
        String scrape = registry.scrape();
        assertTrue(scrape.contains("nexus_readiness_runs_total{"));
        assertFalse(scrape.contains("CLS-ROMEO-01"));
    }

    @Test
    void meterFamily5_notificationSendsCounterRecordsByTypeAndResult() {
        PrometheusMeterRegistry registry = NexusMetrics.create("service");
        NexusMetrics.recordNotificationSend(registry, "backup_failure", "success");
        NexusMetrics.recordNotificationSend(registry, "syslog_audit", "failure");
        NexusMetrics.recordNotificationSend(registry, "operator@example.test", "198.51.100.7");

        assertEquals(1.0, registry.get(NexusMetrics.NOTIFICATION_SENDS_COUNTER)
                .tag("type", "backup_failure").tag("result", "success").counter().count());
        assertEquals(1.0, registry.get(NexusMetrics.NOTIFICATION_SENDS_COUNTER)
                .tag("type", "syslog_audit").tag("result", "failure").counter().count());
        assertEquals(1.0, registry.get(NexusMetrics.NOTIFICATION_SENDS_COUNTER)
                .tag("type", "other").tag("result", "failure").counter().count());
        String scrape = registry.scrape();
        assertTrue(scrape.contains("nexus_notifications_sends_total{"));
        assertFalse(scrape.contains("operator@example.test"));
        assertFalse(scrape.contains("198.51.100.7"));
    }

    @Test
    void meterFamily6_artefactStoreBytesGaugeReflectsStoredBytes() {
        PrometheusMeterRegistry registry = NexusMetrics.create("worker");
        AtomicLong usedBytes = new AtomicLong(4096L);
        NexusMetrics.registerArtefactStoreBytes(registry, usedBytes::get);

        assertEquals(4096.0, registry.get(NexusMetrics.ARTEFACT_STORE_BYTES_GAUGE).gauge().value());
        usedBytes.set(8192L);
        assertEquals(8192.0, registry.get(NexusMetrics.ARTEFACT_STORE_BYTES_GAUGE).gauge().value());
        assertTrue(registry.scrape().contains("nexus_artefact_store_bytes{role=\"worker\"} 8192.0"));
    }

    @Test
    void metricsEndpointIsDisabledByDefaultAndStartsOnlyWhenUi2MetricsEnabledIsTrue() {
        assertFalse(NexusMetrics.isEnabled(Map.of()), "default must be false");
        assertFalse(NexusMetrics.isEnabled(Map.of("UI2_METRICS_ENABLED", "false")));
        assertFalse(NexusMetrics.isEnabled(Map.of("UI2_METRICS_ENABLED", "0")));
        assertTrue(NexusMetrics.isEnabled(Map.of("UI2_METRICS_ENABLED", "true")));
        assertTrue(NexusMetrics.isEnabled(Map.of("UI2_METRICS_ENABLED", "1")));

        assertNull(NexusMetrics.startFromEnvironment("worker", Map.of("UI2_METRICS_PORT", "9404")),
                "must stay off when UI2_METRICS_ENABLED is unset");
        assertNull(NexusMetrics.startFromEnvironment("worker",
                Map.of("UI2_METRICS_ENABLED", "false", "UI2_METRICS_PORT", "9404")),
                "must stay off when UI2_METRICS_ENABLED is false");

        try (MetricsServer server = NexusMetrics.startFromEnvironment("worker",
                Map.of("UI2_METRICS_ENABLED", "true", "UI2_METRICS_PORT", "19404"))) {
            assertNotNull(server);
            assertEquals(19404, server.port());
        }
    }
}
