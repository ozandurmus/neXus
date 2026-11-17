package com.securityexpert.nexus.ui2.service.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.platform.logging.JobLogContext;
import com.securityexpert.nexus.ui2.platform.logging.JsonLogFields;
import com.securityexpert.nexus.ui2.service.security.NexusRouteActionAuthorizationManager;
import com.securityexpert.nexus.ui2.service.security.NexusSecurityFilterChainConfiguration;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class CorrelationIdFilterTest {

    @Test
    void acceptsValidInboundRequestIdPutsInMdcEchoesHeaderAndClearsAfter() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        for (String valid : List.of("abcd1234", "req_2026-11.17-abcDEF09", "A".repeat(64))) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/healthz");
            request.addHeader("X-Request-Id", valid);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<String> seenSlf4j = new AtomicReference<>();
            AtomicReference<String> seenContext = new AtomicReference<>();

            filter.doFilter(request, response, (req, res) -> {
                seenSlf4j.set(MDC.get("correlation_id"));
                seenContext.set(JobLogContext.get("correlation_id"));
            });

            assertEquals(valid, seenSlf4j.get());
            assertEquals(valid, seenContext.get());
            assertEquals(valid, response.getHeader("X-Request-Id"));
            assertNull(MDC.get("correlation_id"));
            assertNull(JobLogContext.get("correlation_id"));
        }
    }

    @Test
    void rejectsInvalidOrMissingRequestIdGeneratesUuidEchoesHeaderAndClearsAfter() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        List<String> invalidInputs = List.of(
                "",
                "   ",
                "short77",
                "x".repeat(65),
                "has space 1234",
                "has:colon:1234",
                "has/slash/1234",
                "has\"quote\"1234",
                "has\nnewline1234");

        for (String invalid : invalidInputs) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/devices");
            if (!invalid.isEmpty()) {
                request.addHeader("X-Request-Id", invalid);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<String> seenMdc = new AtomicReference<>();

            filter.doFilter(request, response, (req, res) -> seenMdc.set(MDC.get("correlation_id")));

            String generated = seenMdc.get();
            assertNotNull(generated);
            assertNotEquals(invalid, generated);
            assertNotNull(UUID.fromString(generated));
            assertEquals(generated, response.getHeader("X-Request-Id"));
            assertNull(MDC.get("correlation_id"));
            assertNull(JobLogContext.get("correlation_id"));
        }
    }

    @Test
    void clearsMdcInFinallyWhenDownstreamThrows() {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");
        request.addHeader("X-Request-Id", "req-boom-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenMdc = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, (req, res) -> {
            seenMdc.set(MDC.get("correlation_id"));
            throw new IllegalStateException("simulated failure");
        }));

        assertEquals("req-boom-12345", seenMdc.get());
        assertEquals("req-boom-12345", response.getHeader("X-Request-Id"));
        assertNull(MDC.get("correlation_id"));
        assertNull(JobLogContext.get("correlation_id"));
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/denied")
        String denied() {
            return "never-reached";
        }
    }

    @EnableWebMvc
    @Configuration
    static class DeniedSecurityWebConfig {
        static final AtomicReference<String> MDC_AT_AUTHZ_CHECK = new AtomicReference<>();

        @Bean
        ProtectedController protectedController() {
            return new ProtectedController();
        }

        @Bean
        CorrelationIdFilter correlationIdFilter() {
            return new CorrelationIdFilter();
        }

        @Bean
        NexusRouteActionAuthorizationManager denyingManager() {
            com.securityexpert.nexus.ui2.service.security.RouteActionGate gate =
                    new com.securityexpert.nexus.ui2.service.security.RouteActionGate(
                            null, java.util.Map.of(), java.util.Set.of(), null, null);
            return new NexusRouteActionAuthorizationManager(gate, req -> {
                MDC_AT_AUTHZ_CHECK.set(MDC.get("correlation_id"));
                return NexusRouteActionAuthorizationManager.HandlerKind.CONTROLLER;
            });
        }
    }

    @Test
    void runsBeforeSpringSecuritySoDeniedRequestsAreCorrelatedAndEchoHeader() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        // Spring Security FilterChainProxy default order is -100; CorrelationIdFilter must run ahead of it.
        assertTrue(filter.getOrder() < -100, "order=" + filter.getOrder());

        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(DeniedSecurityWebConfig.class, NexusSecurityFilterChainConfiguration.class);
            context.refresh();

            CorrelationIdFilter correlationFilter = context.getBean(CorrelationIdFilter.class);
            Filter securityFilter = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(correlationFilter, securityFilter)
                    .build();

            DeniedSecurityWebConfig.MDC_AT_AUTHZ_CHECK.set(null);
            var res = mvc.perform(MockMvcRequestBuilders.get("/denied")
                    .header("X-Request-Id", "denied-req-987654"))
                    .andReturn()
                    .getResponse();

            assertEquals(403, res.getStatus());
            assertEquals("denied-req-987654", DeniedSecurityWebConfig.MDC_AT_AUTHZ_CHECK.get());
            assertEquals("denied-req-987654", res.getHeader("X-Request-Id"));
            assertNull(MDC.get("correlation_id"));
            assertTrue(JsonLogFields.isValidRequestId(res.getHeader("X-Request-Id")));
        }
    }

    @Test
    void preservesCorrelationIdAcrossErrorDispatch() throws ServletException, IOException {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missing");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        String generated = response.getHeader("X-Request-Id");
        assertNotNull(generated);

        request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        request.addHeader("X-Request-Id", "ignored-on-error-dispatch");
        MockHttpServletResponse errorResponse = new MockHttpServletResponse();
        AtomicReference<String> errorMdc = new AtomicReference<>();
        filter.doFilter(request, errorResponse, (req, res) -> errorMdc.set(MDC.get("correlation_id")));

        assertEquals(generated, errorMdc.get());
        assertEquals(generated, errorResponse.getHeader("X-Request-Id"));
        assertNull(MDC.get("correlation_id"));
    }
}
