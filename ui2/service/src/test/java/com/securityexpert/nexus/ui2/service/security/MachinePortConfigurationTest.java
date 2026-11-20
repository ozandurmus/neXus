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
                "/internal/advisories/cisa_kev/complete", "/internal/advisories/checkpoint", "/internal/lifecycle-sources/cisco_eol",
                "/internal/lifecycle-sources/cisco_eol/complete", "/internal/lifecycle-sources/checkpoint_lifecycle",
                "/internal/lifecycle-sources/paloalto_eol/complete"};
        String[] refused = {"/internal", "/internal/", "/internal/advisories", "/internal/advisories/", "/internal/advisories/infoblox",
                "/internal/advisories/paloalto/complete/x", "/internal/advisories/paloalto/other", "/internal/advisories/paloalto/",
                "/internal/advisories/../machine-session", "/internal/other", "/login", "/api/v2/lifecycle/advisories",
                "/internal/lifecycle-sources/", "/internal/lifecycle-sources/fortinet_eol", "/internal/lifecycle-sources/cisco_eol/x",
                "/internal/advisories/cisco_eol"};
        for (String path : allowed) {
            assertEquals(200, run(filter, 8086, "POST", path), path);
            assertEquals(404, run(filter, 8080, "POST", path), path + " on the main port");
            assertEquals(404, run(filter, 8086, "GET", path), path + " is POST only");
        }
        for (String path : refused) assertEquals(404, run(filter, 8086, "POST", path), path);
        assertEquals(200, run(filter, 8080, "GET", "/api/v2/lifecycle/advisories"));
    }

    @Test
    void metricsAreServedOnInternalPort8086OnlyWhenEnabledAndNeverOnPublicPort8080() throws Exception {
        var disabledFilter = new MachinePortConfiguration(false).machinePortFilter();
        assertEquals(404, run(disabledFilter, 8086, "GET", "/metrics"), "disabled by default on 8086");

        var enabledFilter = new MachinePortConfiguration(true).machinePortFilter();
        var request = new MockHttpServletRequest("GET", "/metrics");
        request.setLocalPort(8086);
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        enabledFilter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsString().contains("jvm_memory_used_bytes"));
        verifyNoInteractions(chain);

        assertEquals(405, run(enabledFilter, 8086, "POST", "/metrics"), "POST /metrics on 8086 is 405");

        // On public port 8080, /metrics is never intercepted or exposed by the internal port filter.
        var publicRequest = new MockHttpServletRequest("GET", "/metrics");
        publicRequest.setLocalPort(8080);
        var publicResponse = new MockHttpServletResponse();
        FilterChain publicChain = mock(FilterChain.class);
        enabledFilter.doFilter(publicRequest, publicResponse, publicChain);
        org.junit.jupiter.api.Assertions.assertTrue(publicResponse.getContentAsString().isEmpty(),
                "public port 8080 must never receive the metrics body");
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
