package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.securityexpert.nexus.ui2.platform.metrics.MetricLabelGuard;
import com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics;
import com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfig;
import com.tngtech.archunit.core.domain.JavaClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enterprise phase 0.4 Prometheus metrics architecture fence (docs/operations/METRICS.md):
 * <ul>
 *   <li>no Spring Boot Actuator dependency or endpoint exists on the classpath or in production bytecode;</li>
 *   <li>the public 8080 route map never exposes {@code /metrics} or {@code /actuator/**};</li>
 *   <li>{@code UI2_METRICS_ENABLED} defaults to {@code false} in both code and {@code application.yml};</li>
 *   <li>the label allow-list and value guard forbid device names, IP addresses, hostnames and user identifiers across all six application meter families.</li>
 * </ul>
 */
class MetricsIsolationFenceTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        // The per-JVM cached import (Ui2ArchitectureTest is the single importer; tools test pins this).
        classes = Ui2ArchitectureTest.productionClasses();
        assertFalse(classes.isEmpty(), "no production classes imported");
    }

    @Test
    void noProductionClassDependsOnSpringBootActuator() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.boot.actuate.endpoint.annotation.Endpoint"));
        noClasses().that().resideInAPackage("com.securityexpert.nexus.ui2..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.boot.actuate..")
                .check(classes);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicRouteTableNeverExposesMetricsOrActuatorAndDefaultSwitchIsFalse() throws Exception {
        var field = SecurityWebMvcConfig.class.getDeclaredField("ACTION_ID_BY_ROUTE");
        field.setAccessible(true);
        var routes = (Map<String, String>) field.get(null);
        assertFalse(routes.isEmpty());
        for (String route : routes.keySet()) {
            assertFalse(route.contains("/actuator") || route.endsWith(" /metrics") || route.contains("/metrics/"),
                    "public 8080 route must never expose metrics: " + route);
        }
        assertFalse(NexusMetrics.isEnabled(Map.of()), "UI2_METRICS_ENABLED must default to false");
        Path appYml = Paths.get(System.getProperty("user.dir")).getParent()
                .resolve("service/src/main/resources/application.yml");
        String yml = Files.readString(appYml);
        assertTrue(yml.contains("${UI2_METRICS_ENABLED:false}"),
                "application.yml must default UI2_METRICS_ENABLED to false");
    }

    @Test
    void labelAllowListAndMeterFamiliesForbidDeviceNamesAddressesAndUsers() {
        Set<String> forbiddenKeys = Set.of(
                "device", "device_id", "device_name", "host", "hostname", "ip", "address",
                "serial", "user", "username", "actor", "principal", "email", "recipient");
        for (String forbidden : forbiddenKeys) {
            assertFalse(MetricLabelGuard.ALLOWED_KEYS.contains(forbidden),
                    "forbidden high-cardinality/privacy key in ALLOWED_KEYS: " + forbidden);
        }
        for (String unsafeValue : List.of("FW-TANGO-04", "FW-JULIET-06", "CLS-ROMEO-01", "192.0.2.10",
                "198.51.100.24", "fe80::1", "operator@example.test")) {
            assertFalse(MetricLabelGuard.safeValue(unsafeValue),
                    "MetricLabelGuard must reject unsafe value: " + unsafeValue);
        }

        var registry = NexusMetrics.create("service");
        NexusMetrics.recordJobState(registry, "cp_inventory_collect", "COMPLETED");
        NexusMetrics.recordJobDuration(registry, "cp_inventory_collect", "COMPLETED", Duration.ofMillis(15));
        NexusMetrics.registerQueueDepth(registry, () -> 2L);
        NexusMetrics.recordReadinessRun(registry, "READY");
        NexusMetrics.recordNotificationSend(registry, "backup_failure", "success");
        NexusMetrics.registerArtefactStoreBytes(registry, () -> 2048L);

        String scrape = registry.scrape();
        for (String family : List.of(
                "nexus_jobs_transitions_total",
                "nexus_jobs_duration_seconds_count",
                "nexus_jobs_queue_depth",
                "nexus_readiness_runs_total",
                "nexus_notifications_sends_total",
                "nexus_artefact_store_bytes")) {
            assertTrue(scrape.contains(family), "missing expected Prometheus meter family: " + family);
        }
    }
}
