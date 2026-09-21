package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

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
class GateChainInterceptorSecurityTest {

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
    void discoveryTrustRoutesResolveToDistinctSecurityAdminActions() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        var method = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        method.setAccessible(true);
        assertEquals(ActionRegistry.DISCOVERY_SSH_TRUST_ENROLL,
                method.invoke(interceptor, "POST", "/discovery/ssh-trust/enroll"));
        assertEquals(ActionRegistry.DISCOVERY_SSH_TRUST_RE_ENROLL,
                method.invoke(interceptor, "POST", "/discovery/ssh-trust/re-enroll"));
    }

    @Test
    void deviceConfirmRouteResolvesToDeviceRegisterAction() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        var method = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        method.setAccessible(true);
        assertEquals(ActionRegistry.DEVICE_REGISTER,
                method.invoke(interceptor, "POST", "/devices/device-1/confirm"));
    }

    @Test
    void sessionListUsesTheExistingSecurityAdminRevokeAction() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        var method = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        method.setAccessible(true);
        assertEquals(ActionRegistry.SESSION_REVOKE, method.invoke(interceptor, "GET", "/sessions"));
        assertEquals(java.util.Optional.of(com.securityexpert.nexus.ui2.platform.RoleToken.SECURITY_ADMIN),
                new ActionRegistry().find(ActionRegistry.SESSION_REVOKE).orElseThrow().requiredRoleToken());
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

    @Test
    void anUnmappedRouteIsRefusedUnlessItIsAnExplicitException() throws Exception {
        GateChainInterceptor interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE,
                SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES);
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        Mockito.when(request.getMethod()).thenReturn("GET");
        Mockito.when(request.getServletPath()).thenReturn("/new-controller-route");
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertFalse(interceptor.preHandle(request, response, null));
        Mockito.verify(response).setStatus(403);
        assertTrue(body.toString().contains("ACTION_MAPPING_REQUIRED"));
    }

    @Test
    void anExplicitlyUngatedRouteStillReachesItsOwnSecurityCheck() throws Exception {
        GateChainInterceptor interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE,
                SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES);
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(request.getServletPath()).thenReturn("/login");

        assertTrue(interceptor.preHandle(request, response, null));
    }

    @Test
    void authLoginIsExplicitlyUngated() throws Exception {
        GateChainInterceptor interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE,
                SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES);
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(request.getServletPath()).thenReturn("/auth/login");

        assertTrue(interceptor.preHandle(request, response, null));
    }

    @Test
    void unmappedApiRouteIsStrictlyDeniedWith403ByDefault() throws Exception {
        GateChainInterceptor interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE,
                SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES);
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(request.getServletPath()).thenReturn("/custom/unmapped-endpoint");
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertFalse(interceptor.preHandle(request, response, null));
        Mockito.verify(response).setStatus(403);
        assertTrue(body.toString().contains("ACTION_MAPPING_REQUIRED"));
    }

    @Test
    void unmappedRouteUnderApiPrefixIsRefusedEvenWhenHandledByResourceHandler() throws Exception {
        GateChainInterceptor interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE,
                SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES);
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        Mockito.when(request.getMethod()).thenReturn("GET");
        Mockito.when(request.getServletPath()).thenReturn("/api/unmapped-api-endpoint");
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertFalse(interceptor.preHandle(request, response, new ResourceHttpRequestHandler()));
        Mockito.verify(response).setStatus(403);
        assertTrue(body.toString().contains("ACTION_MAPPING_REQUIRED"));
    }

    @Test
    void configurationRoutesResolveToExpectedActions() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        var method = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        method.setAccessible(true);
        assertEquals(ActionRegistry.DEVICE_READ,
                method.invoke(interceptor, "GET", "/configuration"));
        assertEquals(ActionRegistry.DEVICE_READ,
                method.invoke(interceptor, "GET", "/devices/dev-123/configuration"));
        assertEquals(ActionRegistry.DEVICE_CONFIGURATION_TEXT_READ,
                method.invoke(interceptor, "GET", "/devices/dev-123/configuration/text"));
        assertEquals(ActionRegistry.DEVICE_CONFIGURATION_COLLECT,
                method.invoke(interceptor, "POST", "/devices/dev-123/configuration/collect"));
    }

    @Test
    void spaRootAndStaticBundleDoNotNeedProductActionMappings() throws Exception {
        GateChainInterceptor interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE,
                SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES);
        HttpServletRequest root = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(root.getMethod()).thenReturn("GET");
        Mockito.when(root.getServletPath()).thenReturn("/");
        assertTrue(interceptor.preHandle(root, response, null));

        HttpServletRequest asset = Mockito.mock(HttpServletRequest.class);
        Mockito.when(asset.getMethod()).thenReturn("GET");
        Mockito.when(asset.getServletPath()).thenReturn("/assets/app.js");
        assertTrue(interceptor.preHandle(asset, response, new ResourceHttpRequestHandler()));
    }
}
