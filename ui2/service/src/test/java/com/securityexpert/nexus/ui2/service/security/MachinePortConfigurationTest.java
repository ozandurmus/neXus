package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MachinePortConfigurationTest {
    @Test
    void onlyInternalPortServesTheExactEndpoint() throws Exception {
        var filter = new MachinePortConfiguration().machinePortFilter();
        for (int port : new int[] {8080, 8086}) {
            for (String path : new String[] {"/internal", "/internal/machine-session", "/internal/other", "/login"}) {
                var request = new MockHttpServletRequest("POST", path);
                request.setLocalPort(port);
                var response = new MockHttpServletResponse();
                FilterChain chain = mock(FilterChain.class);
                filter.doFilter(request, response, chain);
                if (port == 8086 && path.equals("/internal/machine-session")
                        || port == 8080 && path.equals("/login")) {
                    verify(chain).doFilter(request, response);
                } else {
                    assertEquals(404, response.getStatus());
                    verifyNoInteractions(chain);
                }
            }
        }
    }

    @Test
    void theInternalPortServesTheMachineSessionAndTheAdvisoryIngestPathsAndNothingElse() throws Exception {
        var filter = new MachinePortConfiguration().machinePortFilter();
        String[] allowed = {"/internal/machine-session", "/internal/advisories/paloalto", "/internal/advisories/fortinet",
                "/internal/advisories/cisco", "/internal/advisories/cisa_kev", "/internal/advisories/paloalto/complete",
                "/internal/advisories/cisa_kev/complete"};
        String[] refused = {"/internal", "/internal/", "/internal/advisories", "/internal/advisories/", "/internal/advisories/checkpoint",
                "/internal/advisories/paloalto/complete/x", "/internal/advisories/paloalto/other", "/internal/advisories/paloalto/",
                "/internal/advisories/../machine-session", "/internal/other", "/login", "/api/v2/lifecycle/advisories"};
        for (String path : allowed) {
            assertEquals(200, run(filter, 8086, "POST", path), path);
            assertEquals(404, run(filter, 8080, "POST", path), path + " on the main port");
            assertEquals(404, run(filter, 8086, "GET", path), path + " is POST only");
        }
        for (String path : refused) assertEquals(404, run(filter, 8086, "POST", path), path);
        assertEquals(200, run(filter, 8080, "GET", "/api/v2/lifecycle/advisories"));
    }

    private static int run(org.springframework.web.filter.OncePerRequestFilter filter, int port, String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setLocalPort(port);
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }
}
