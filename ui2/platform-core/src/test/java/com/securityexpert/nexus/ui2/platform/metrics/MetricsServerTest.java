package com.securityexpert.nexus.ui2.platform.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
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
    void theGuardDropsUnknownLabelKeysAndRedactsAddressLikeValues() {
        PrometheusMeterRegistry registry = NexusMetrics.create("service");
        registry.counter("test.hostile", "host", "fw-core-01.example.test", "state", "10.1.2.3",
                "uri", "/devices/lab-fw-serial-123 name", "outcome", "ok").increment();
        registry.counter("test.hostile2", "state", "fe80::1", "outcome", "a b c d e f g h i j k l m n o p q r s t u v w x y z 0123456789 0123456789").increment();
        String scrape = registry.scrape();
        assertFalse(scrape.contains("fw-core-01"), scrape);
        assertFalse(scrape.contains("10.1.2.3"), scrape);
        assertFalse(scrape.contains("fe80"), scrape);
        assertFalse(scrape.contains("host="), scrape);
        assertTrue(scrape.contains("state=\"REDACTED\""), scrape);
        assertTrue(scrape.contains("outcome=\"ok\""), scrape);
    }

    @Test
    void everyLabelOfTheWholeScrapeIsAnAllowedKeyWithASafeNonAddressValue() throws Exception {
        PrometheusMeterRegistry registry = NexusMetrics.create("worker");
        registry.counter("nexus.backup.attempts", "outcome", "completed").increment();
        Timer.builder("http.server.requests").tag("method", "GET").tag("uri", "/devices/{id}")
                .tag("status", "200").tag("outcome", "SUCCESS").register(registry).record(java.time.Duration.ofMillis(3));
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
}
