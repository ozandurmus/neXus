package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Route mapping of the advisory read and ingest routes (ADVISORY_MATCHING_CONTRACT.md sections 4 and 8). */
class AdvisorySecurityTest {
    @Test void theThreeReadRoutesResolveToTheLifecycleReadAction() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        Method resolve = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        resolve.setAccessible(true);
        assertEquals(resolve.invoke(interceptor, "GET", "/api/v2/lifecycle"), ActionRegistry.DEVICE_READ);
        for (String path : List.of("/api/v2/lifecycle/advisories", "/api/v2/lifecycle/advisories/paloalto:CVE-2026-0001",
                "/api/v2/lifecycle/advisories/cisco:cisco-sa-example", "/api/v2/lifecycle/advisory-sources")) {
            assertEquals(ActionRegistry.DEVICE_READ, resolve.invoke(interceptor, "GET", path), path);
        }
        // No write route exists in this part: nothing but GET is mapped under the advisory paths.
        for (String method : List.of("POST", "PUT", "DELETE")) {
            assertNull(resolve.invoke(interceptor, method, "/api/v2/lifecycle/advisories"), method);
            assertNull(resolve.invoke(interceptor, method, "/api/v2/lifecycle/advisories/paloalto:CVE-2026-0001"), method);
        }
    }

    @Test void theIngestRoutesAreExplicitlyUngatedForEachKnownSourceOnly() {
        for (String source : List.of("paloalto", "fortinet", "cisco", "cisa_kev")) {
            assertTrue(SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES.contains("POST /internal/advisories/" + source), source);
            assertTrue(SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES.contains("POST /internal/advisories/" + source + "/complete"), source);
        }
        assertFalse(SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES.contains("POST /internal/advisories/checkpoint"));
        assertFalse(SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES.contains("GET /internal/advisories/paloalto"));
        assertTrue(SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES.contains("POST /internal/machine-session"));
        assertEquals(11 + 8, SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES.size());
    }

    @Test void anIngestRouteReachesItsOwnTokenCheckAndAnUnknownSourceIsRefused() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE, SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES);
        for (String path : List.of("/internal/advisories/paloalto", "/internal/advisories/cisa_kev/complete")) {
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
            Mockito.when(request.getMethod()).thenReturn("POST");
            Mockito.when(request.getServletPath()).thenReturn(path);
            assertTrue(interceptor.preHandle(request, Mockito.mock(HttpServletResponse.class), null), path);
        }
        HttpServletRequest unknown = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        Mockito.when(unknown.getMethod()).thenReturn("POST");
        Mockito.when(unknown.getServletPath()).thenReturn("/internal/advisories/checkpoint");
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(body));
        assertFalse(interceptor.preHandle(unknown, response, null));
        Mockito.verify(response).setStatus(403);
        assertTrue(body.toString().contains("ACTION_MAPPING_REQUIRED"));
    }
}
