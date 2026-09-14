package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * NXS-LOCAL-0160 "Routes": {@link GateChainInterceptor}'s wildcard fallback
 * now tries every interior path segment, not only the last one, so a route
 * whose path-variable segment is not last ({@code GET /devices/{id}/
 * inventory}, {@code POST /devices/{id}/inventory/collect}, {@code GET
 * /clusters/{cluster_member_ref}/inventory}) still resolves. Calls the
 * private {@code actionIdFor} directly (no {@link GateChain} double
 * exists cheaply enough to exercise this through {@code preHandle}) --
 * this test is about the route map lookup alone, not the gate chain it
 * feeds.
 */
class GateChainInterceptorRouteResolutionTest {

    private static final Map<String, String> ROUTES = Map.ofEntries(
            Map.entry("GET /devices", "device_read"),
            Map.entry("GET /devices/*", "device_read"),
            Map.entry("GET /devices/*/inventory", "device_read"),
            Map.entry("POST /devices/*/inventory/collect", "device_inventory_collect"),
            Map.entry("GET /clusters/*/inventory", "device_read"));

    private static String actionIdFor(String method, String path) throws Exception {
        GateChainInterceptor interceptor = new GateChainInterceptor(null, ROUTES);
        Method m = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(interceptor, method, path);
    }

    @Test
    void theOriginalLastSegmentRouteStillResolvesOnItsFirstWildcardAttempt() throws Exception {
        assertEquals("device_read", actionIdFor("GET", "/devices/device-1"));
    }

    @Test
    void anExactRouteWithNoPathVariableStillMatchesExactly() throws Exception {
        assertEquals("device_read", actionIdFor("GET", "/devices"));
    }

    @Test
    void aDeviceIdInTheMiddleOfThePathResolvesViaTheInteriorWildcard() throws Exception {
        assertEquals("device_read", actionIdFor("GET", "/devices/device-1/inventory"));
    }

    @Test
    void theCollectRouteResolvesToItsOwnWriteAction() throws Exception {
        assertEquals("device_inventory_collect", actionIdFor("POST", "/devices/device-1/inventory/collect"));
    }

    @Test
    void aClusterMemberRefInTheMiddleOfThePathResolvesViaTheInteriorWildcard() throws Exception {
        assertEquals("device_read", actionIdFor("GET", "/clusters/cluster-1/inventory"));
    }

    @Test
    void aRouteAbsentFromTheMapResolvesToNullRegardlessOfSegmentCount() throws Exception {
        assertNull(actionIdFor("GET", "/healthz"));
        assertNull(actionIdFor("GET", "/devices/device-1/unknown-suffix"));
    }
}
