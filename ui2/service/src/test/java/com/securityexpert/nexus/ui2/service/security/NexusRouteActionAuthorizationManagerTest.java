package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;

/**
 * Migration plan Step 5, through the real Spring Security filter chain and a real MVC dispatcher: the manager refuses and
 * permits as the interceptor did, judges only requests the dispatcher has a handler for, answers a saturated connection pool
 * 503, and the interceptor is registered only on the rollback path. The full route by session decision table is
 * {@link RouteAuthorizationDecisionTableTest}.
 */
class NexusRouteActionAuthorizationManagerTest {

    @RestController
    static class Probe {
        @GetMapping("/ok")
        String ok() {
            return "controller-ran";
        }

        @GetMapping("/open")
        String open() {
            return "open-ran";
        }

        @GetMapping("/unmapped")
        String unmapped() {
            return "must-not-run";
        }
    }

    static final SessionRepository SESSIONS = mock(SessionRepository.class);

    @EnableWebMvc
    @Configuration
    static class Web implements WebMvcConfigurer {
        @Bean
        Probe probe() {
            return new Probe();
        }

        @Bean
        SessionRepository sessionRepository() {
            return SESSIONS;
        }

        /** The Boot default: every path no controller serves goes to the resource handler. */
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**").addResourceLocations("classpath:/static-none/");
        }

        @Bean
        NexusRouteActionAuthorizationManager manager(ObjectProvider<HandlerMapping> mappings) {
            var gate = new RouteActionGate(new GateChain(SESSIONS, new ActionRegistry(), null, null),
                    Map.of("GET /ok", ActionRegistry.DEVICE_READ), java.util.Set.of("GET /open"), null, null);
            return new NexusRouteActionAuthorizationManager(gate, new DispatcherHandlerKindResolver(mappings));
        }
    }

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    @BeforeAll
    static void build() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Web.class, NexusSecurityFilterChainConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void close() {
        context.close();
    }

    /** As Tomcat presents a request mapped to the root servlet: the whole path is the servlet path. */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req(String method, String path) {
        return MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method.toUpperCase()), path)
                .servletPath(path);
    }

    @Test
    void theChainKeepsItsFilterListAndNowDecidesRoutesThroughTheManager() {
        FilterChainProxy proxy = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        List<String> filters = proxy.getFilterChains().get(0).getFilters().stream().map(f -> f.getClass().getSimpleName()).toList();
        assertTrue(filters.contains("AuthorizationFilter"));
        assertTrue(filters.contains("ExceptionTranslationFilter"));
    }

    @Test
    void aMappedRouteWithoutASessionIsRefusedBeforeTheControllerRuns() throws Exception {
        var result = mvc.perform(req("get", "/ok")).andReturn().getResponse();
        assertEquals(401, result.getStatus());
        assertEquals("application/json", result.getContentType());
        assertEquals("{\"error\":\"SESSION_INVALID\",\"reason\":\"no session cookie presented\"}", result.getContentAsString());
    }

    @Test
    void anUnmappedControllerRouteIsRefusedAndAnExemptRouteIsServed() throws Exception {
        var refused = mvc.perform(req("get", "/unmapped")).andReturn().getResponse();
        assertEquals(403, refused.getStatus());
        assertEquals("{\"error\":\"ACTION_MAPPING_REQUIRED\"}", refused.getContentAsString());
        var served = mvc.perform(req("get", "/open")).andReturn().getResponse();
        assertEquals(200, served.getStatus());
        assertEquals("open-ran", served.getContentAsString());
    }

    @Test
    void staticTargetsPassButAnApiPathNoControllerServesIsRefused() throws Exception {
        assertEquals(404, mvc.perform(req("get", "/some-page")).andReturn().getResponse().getStatus());
        var api = mvc.perform(req("get", "/api/v2/nothing")).andReturn().getResponse();
        assertEquals(403, api.getStatus());
        assertEquals("{\"error\":\"ACTION_MAPPING_REQUIRED\"}", api.getContentAsString());
    }

    @Test
    void aRequestTheDispatcherRefusesItselfIsNotJudgedByTheGate() throws Exception {
        // POST /open: the controller serves only GET, so the dispatcher answers 405 (the interceptor never ran for it).
        // The resource handler on /** does not rescue it: the request mapping refuses first, as in the dispatcher.
        assertEquals(405, mvc.perform(req("post", "/open")).andReturn().getResponse().getStatus());
    }

    @Test
    void aSaturatedConnectionPoolIsStillAnswered503DatabaseBusy() throws Exception {
        when(SESSIONS.findBySessionId(anyString(), any(java.time.Instant.class)))
                .thenThrow(new RuntimeException("pool", new SQLTransientConnectionException("busy")));
        try {
            var busy = mvc.perform(req("get", "/ok")
                    .cookie(new jakarta.servlet.http.Cookie("ui2_session", "x"))).andReturn().getResponse();
            assertEquals(503, busy.getStatus());
            assertEquals("{\"error\":\"DATABASE_BUSY\",\"message\":\"database busy\"}", busy.getContentAsString());
        } finally {
            org.mockito.Mockito.reset(SESSIONS);
        }
    }

    /** Registration is the rollback switch: the interceptor is added exactly when the Spring Security manager is off. */
    @Test
    void theInterceptorIsRegisteredOnlyOnTheRollbackPath() {
        class Registry extends InterceptorRegistry {
            List<Object> registered() {
                return getInterceptors();
            }
        }
        var on = new Registry();
        new SecurityWebMvcConfig(null, null, null, null, false).addInterceptors(on);
        assertTrue(on.registered().isEmpty(), "Spring Security decides: no GateChainInterceptor");
        var off = new Registry();
        new SecurityWebMvcConfig(null, null, null, null, true).addInterceptors(off);
        assertEquals(1, off.registered().size());
        var legacy = new Registry();
        new SecurityWebMvcConfig(null).addInterceptors(legacy);
        assertFalse(legacy.registered().isEmpty(), "the plain constructor keeps the interceptor");
    }
}
