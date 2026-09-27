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
}
