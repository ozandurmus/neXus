package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** PO 2026-11-15 response headers: written once, never over a header the response already chose. */
class NexusSecurityHeadersFilterTest {

    private static MockHttpServletResponse run(java.util.function.Consumer<MockHttpServletResponse> handler)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException, ServletException {
                handler.accept(response);
                res.getWriter().write("body");
                res.flushBuffer();
            }
        };
        new NexusSecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/x"), response, chain);
        return response;
    }

    @Test
    void aPlainResponseGetsAllThreeHeaders() throws Exception {
        MockHttpServletResponse response = run(r -> { });
        assertEquals(List.of("nosniff"), response.getHeaders("X-Content-Type-Options"));
        assertEquals(List.of("DENY"), response.getHeaders("X-Frame-Options"));
        assertEquals(List.of("no-store"), response.getHeaders("Cache-Control"));
    }

    @Test
    void aResponseThatChoseItsOwnPolicyKeepsItAndNothingIsDuplicated() throws Exception {
        MockHttpServletResponse response = run(r -> {
            r.setHeader("Cache-Control", "max-age=31536000, public, immutable");
            r.addHeader("X-Content-Type-Options", "nosniff");
        });
        assertEquals(List.of("max-age=31536000, public, immutable"), response.getHeaders("Cache-Control"));
        assertEquals(List.of("nosniff"), response.getHeaders("X-Content-Type-Options"));
        assertEquals(List.of("DENY"), response.getHeaders("X-Frame-Options"));
    }

    @Test
    void aSecondFilterPassOnTheSameRequestAddsNoSecondCopy() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        NexusSecurityHeadersFilter filter = new NexusSecurityHeadersFilter();
        filter.doFilter(request, response, new MockFilterChain());
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(1, response.getHeaders("X-Frame-Options").size());
        assertEquals(1, response.getHeaders("Cache-Control").size());
    }
}
