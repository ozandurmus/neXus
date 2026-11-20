package com.securityexpert.nexus.ui2.service.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfigTestAccess;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scrape endpoint lives on its own port in its own server, so the main port's authentication is untouched. This
 * proves the main port gives an anonymous caller nothing under {@code /actuator/**}: there is no Actuator on the
 * classpath, no route in the gate map, and even a handler that someone adds later at such a path is refused by the
 * gate chain (a route absent from the action map is denied).
 */
class ManagementPortIsolationTest {

    @RestController
    static class FutureActuatorHandler {
        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "would leak";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }
    }

    @Test
    void anonymousActuatorRequestsOnTheMainPortAreRefused() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FutureActuatorHandler())
                .addInterceptors(new GateChainInterceptor(null, SecurityWebMvcConfigTestAccess.routes()))
                .build();
        for (String path : new String[] {"/actuator/prometheus", "/actuator/health"}) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }
        // No handler at all: not reachable either.
        for (String path : new String[] {"/actuator", "/actuator/metrics", "/actuator/env", "/metrics"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void noActuatorIsOnTheClasspathAndNoRouteMapsIt() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.boot.actuate.endpoint.annotation.Endpoint"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration"));
        for (String route : SecurityWebMvcConfigTestAccess.routes().keySet()) {
            assertFalse(route.contains("/actuator") || route.endsWith(" /metrics"), route);
        }
    }

    @Test
    void scrapeServerStaysOffWhenUi2MetricsEnabledIsFalseAndStartsWhenEnabled() throws Exception {
        MetricsConfiguration config = new MetricsConfiguration();
        try (MetricsConfiguration.ScrapeServer disabled = config.scrapeServer(false, 9404)) {
            org.junit.jupiter.api.Assertions.assertNull(disabled.server(),
                    "scrape server must stay off when UI2_METRICS_ENABLED=false");
        }
        try (MetricsConfiguration.ScrapeServer enabled = config.scrapeServer(true, 19405)) {
            org.junit.jupiter.api.Assertions.assertNotNull(enabled.server(),
                    "scrape server must start when UI2_METRICS_ENABLED=true");
        }
    }
}
