package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Spring Security Step 1 (docs/design/SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md) must change nothing a client can see.
 * This test proves it at the unit level: the same MVC application is driven twice, once without and once with the
 * {@code FilterChainProxy} built from {@link NexusSecurityFilterChainConfiguration}, and every answer (status, every
 * header, body, session and principal as the controller sees them) must be identical. The booted-application proof is
 * {@code HttpSurfaceCharacterizationTest} in :integration-tests.
 */
class NexusSecurityFilterChainConfigurationTest {

    @RestController
    static class Probe {
        @GetMapping("/ok")
        String ok() {
            return "ok";
        }

        @PostMapping("/ok")
        String postOk() {
            return "posted";
        }

        @PutMapping("/ok")
        String putOk() {
            return "put";
        }

        @GetMapping("/inspect")
        String inspect(HttpServletRequest request) {
            return "session=" + (request.getSession(false) != null) + ";principal=" + request.getUserPrincipal()
                    + ";remoteUser=" + request.getRemoteUser() + ";inRole=" + request.isUserInRole("ADMIN")
                    + ";requestedSessionIdValid=" + request.isRequestedSessionIdValid()
                    + ";encoded=" + "x".equals(request.getRequestURI());
        }

        @GetMapping("/force-session")
        String forceSession(HttpServletRequest request) {
            return "id-created=" + (request.getSession(true) != null);
        }

        @GetMapping("/redirect")
        void redirect(HttpServletResponse response) throws Exception {
            response.sendRedirect("/elsewhere");
        }

        @GetMapping("/status")
        void status(HttpServletResponse response) throws Exception {
            response.setStatus(418);
            response.setHeader("X-Probe", "yes");
        }

        @GetMapping("/cached")
        void cached(HttpServletResponse response) {
            response.setHeader("Cache-Control", "max-age=31536000, public, immutable");
        }

        @GetMapping("/async")
        Callable<String> async() {
            return () -> "async-done";
        }
    }

    @EnableWebMvc
    @org.springframework.context.annotation.Configuration
    static class Web {
        @org.springframework.context.annotation.Bean
        Probe probe() {
            return new Probe();
        }
    }

    private static AnnotationConfigWebApplicationContext secured;
    private static AnnotationConfigWebApplicationContext plain;
    private static MockMvc withChain;
    private static MockMvc withoutChain;

    @BeforeAll
    static void build() {
        plain = new AnnotationConfigWebApplicationContext();
        plain.setServletContext(new MockServletContext());
        plain.register(Web.class);
        plain.refresh();
        withoutChain = MockMvcBuilders.webAppContextSetup(plain).build();

        secured = new AnnotationConfigWebApplicationContext();
        secured.setServletContext(new MockServletContext());
        secured.register(Web.class, NexusSecurityFilterChainConfiguration.class);
        secured.refresh();
        withChain = MockMvcBuilders.webAppContextSetup(secured)
                .addFilters(secured.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void close() {
        secured.close();
        plain.close();
    }

    @Test
    void theChainIsActiveAndIsAPassthroughWithExactlyTheFiltersTheStepAllows() {
        FilterChainProxy proxy = secured.getBean("springSecurityFilterChain", FilterChainProxy.class);
        List<SecurityFilterChain> chains = proxy.getFilterChains();
        assertEquals(1, chains.size(), "one chain for every request");
        List<String> filters = new ArrayList<>();
        chains.get(0).getFilters().forEach(f -> filters.add(f.getClass().getSimpleName()));
        // A filter listed here that does not belong to a passthrough chain is a behaviour change: review it, do not just add it.
        assertEquals(List.of("DisableEncodeUrlFilter", "WebAsyncManagerIntegrationFilter", "SecurityContextHolderFilter",
                "NexusSecurityHeadersFilter", "SessionManagementFilter", "ExceptionTranslationFilter", "AuthorizationFilter"), filters);
        for (String forbidden : List.of("CsrfFilter", "LogoutFilter", "HeaderWriterFilter", "CorsFilter",
                "UsernamePasswordAuthenticationFilter", "BasicAuthenticationFilter", "DefaultLoginPageGeneratingFilter",
                "DefaultLogoutPageGeneratingFilter", "RequestCacheAwareFilter", "AnonymousAuthenticationFilter",
                "SecurityContextHolderAwareRequestFilter", "ChangePasswordPageGeneratingFilter")) {
            assertFalse(filters.contains(forbidden), forbidden + " would change behaviour");
        }
        assertEquals(0, secured.getBeanNamesForType(org.springframework.security.core.userdetails.UserDetailsService.class).length,
                "no generated default user");
    }

    private record Answer(int status, Map<String, List<String>> headers, String body, boolean sessionCreated, String forwarded) {
    }

    private static Answer drive(MockMvc mvc, MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        Map<String, List<String>> headers = new TreeMap<>();
        for (String name : result.getResponse().getHeaderNames()) {
            headers.put(name.toLowerCase(), new ArrayList<>(result.getResponse().getHeaders(name)));
        }
        return new Answer(result.getResponse().getStatus(), headers, result.getResponse().getContentAsString(),
                result.getRequest().getSession(false) != null, result.getResponse().getForwardedUrl());
    }

    private static List<MockHttpServletRequestBuilder> requests() {
        List<MockHttpServletRequestBuilder> all = new ArrayList<>();
        for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE")) {
            for (String path : List.of("/ok", "/inspect", "/force-session", "/redirect", "/status", "/cached", "/async",
                    "/missing", "/login", "/logout", "/error", "/.well-known/change-password", "/ok/", "//ok", "/./ok",
                    "/ok;jsessionid=abc", "/ok%2f", "/a/../ok", "/%2e%2e/ok", "/ok%5c", "/ok%00", "/é")) {
                all.add(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), java.net.URI.create(path.replace("\u00e9", "%C3%A9"))));
            }
        }
        all.add(MockMvcRequestBuilders.post("/ok").contentType("application/x-www-form-urlencoded").content("a=b"));
        all.add(MockMvcRequestBuilders.post("/ok").contentType("application/json").content("{}"));
        all.add(MockMvcRequestBuilders.post("/ok").header("Origin", "http://example.invalid").header("X-CSRF-Token", "x"));
        all.add(MockMvcRequestBuilders.get("/ok").header("Authorization", "Basic YTpi"));
        all.add(MockMvcRequestBuilders.get("/ok").header("Authorization", "Bearer abc"));
        all.add(MockMvcRequestBuilders.get("/inspect").cookie(new jakarta.servlet.http.Cookie("JSESSIONID", "abc")));
        all.add(MockMvcRequestBuilders.get("/inspect").cookie(new jakarta.servlet.http.Cookie("ui2_session", "abc")));
        all.add(MockMvcRequestBuilders.get("/ok").header("Origin", "http://example.invalid")
                .header("Access-Control-Request-Method", "GET"));
        all.add(MockMvcRequestBuilders.options("/ok").header("Origin", "http://example.invalid")
                .header("Access-Control-Request-Method", "POST"));
        all.add(MockMvcRequestBuilders.get("/ok").header("Accept", "text/html"));
        all.add(MockMvcRequestBuilders.get("/ok").header("X-Forwarded-Proto", "https"));
        all.add(MockMvcRequestBuilders.get("/ok").secure(true));
        all.add(MockMvcRequestBuilders.get("/ok").queryParam("x", "%zz"));
        return all;
    }

    /**
     * PO 2026-11-15 added three response headers on purpose (nosniff, X-Frame-Options DENY, a no-store default). Everything
     * else about an answer must still be identical, so the comparison removes exactly those additions and nothing more.
     */
    private static Answer withoutTheDecidedSecurityHeaders(Answer actual, Answer expected) {
        Map<String, List<String>> headers = new TreeMap<>(actual.headers());
        headers.remove("x-content-type-options");
        headers.remove("x-frame-options");
        if (!expected.headers().containsKey("cache-control") && List.of("no-store").equals(headers.get("cache-control"))) {
            headers.remove("cache-control");
        }
        return new Answer(actual.status(), headers, actual.body(), actual.sessionCreated(), actual.forwarded());
    }

    @Test
    void everyAnswerIsIdenticalWithAndWithoutTheChain() throws Exception {
        int compared = 0;
        for (MockHttpServletRequestBuilder builder : requests()) {
            MockHttpServletRequest probe = builder.buildRequest(new MockServletContext());
            String label = probe.getMethod() + " " + probe.getRequestURI();
            Answer expected = drive(withoutChain, builder);
            Answer actual = drive(withChain, builder);
            assertEquals(expected, withoutTheDecidedSecurityHeaders(actual, expected), label);
            assertFalse(actual.headers().containsKey("set-cookie") && actual.headers().get("set-cookie").stream()
                    .anyMatch(c -> c.contains("JSESSIONID") && !label.contains("/force-session")), label + " opened a session");
            compared++;
        }
        assertTrue(compared > 150, "compared " + compared);
    }

    @Test
    void theChainAddsTheDecidedSecurityHeadersAndKeepsTheControllersOwnCachePolicy() throws Exception {
        Answer cached = drive(withChain, MockMvcRequestBuilders.get("/cached"));
        assertEquals(List.of("max-age=31536000, public, immutable"), cached.headers().get("cache-control"));
        Answer ok = drive(withChain, MockMvcRequestBuilders.get("/ok"));
        assertEquals(Map.of("content-length", List.of("2"), "content-type", List.of("text/plain;charset=ISO-8859-1"),
                "x-content-type-options", List.of("nosniff"), "x-frame-options", List.of("DENY"),
                "cache-control", List.of("no-store")), ok.headers());
        assertNull(withChain.perform(MockMvcRequestBuilders.get("/ok")).andReturn().getRequest().getSession(false));
    }

    @Test
    void aPostWithoutAnyCsrfTokenIsNotRefusedByTheChain() throws Exception {
        assertEquals(200, drive(withChain, MockMvcRequestBuilders.post("/ok")).status());
        assertEquals(200, drive(withChain, MockMvcRequestBuilders.put("/ok")).status());
    }
}
