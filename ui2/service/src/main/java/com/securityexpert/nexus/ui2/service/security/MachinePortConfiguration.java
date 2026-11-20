package com.securityexpert.nexus.ui2.service.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import com.securityexpert.nexus.ui2.service.advisorystore.AdvisorySources;

/**
 * The internal port 8086 serves exactly these POST paths: the e2e machine session, the two advisory ingest paths of the
 * fetch workload (docs/design/ADVISORY_MATCHING_CONTRACT.md section 4) and the two lifecycle-source ingest paths
 * (docs/design/LIFECYCLE_CATALOG_SOURCES_CONTRACT.md section 3.1). Every other path answers 404 there, and every
 * {@code /internal/**} path answers 404 on the main port.
 */
@Configuration
public class MachinePortConfiguration {

    private final boolean metricsEnabled;

    public MachinePortConfiguration() {
        this(false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MachinePortConfiguration(
            @org.springframework.beans.factory.annotation.Value("${ui2.metrics.enabled:false}") boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    /** True when the internal port serves this method and path. */
    static boolean internalPortAllows(String method, String uri) {
        if (!"POST".equals(method) || uri == null) return false;
        if ("/internal/machine-session".equals(uri)) return true;
        String lifecycle = "/internal/lifecycle-sources/";
        if (uri.startsWith(lifecycle)) {
            String rest = uri.substring(lifecycle.length());
            if (rest.endsWith("/complete")) rest = rest.substring(0, rest.length() - "/complete".length());
            return com.securityexpert.nexus.ui2.service.lifecyclesource.LifecycleSources.feedKnown(rest);
        }
        String prefix = "/internal/advisories/";
        if (!uri.startsWith(prefix)) return false;
        String rest = uri.substring(prefix.length());
        if (rest.endsWith("/complete")) rest = rest.substring(0, rest.length() - "/complete".length());
        return AdvisorySources.known(rest);
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> machineConnector() {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(8086);
            factory.addAdditionalConnectors(connector);
        };
    }

    /**
     * Ahead of the Spring Security filter chain (default order -100), behind the HTTP metrics filter (HIGHEST_PRECEDENCE + 10).
     * The {@code @Order} that used to sit on the bean method is not read for a filter bean, so until migration plan Step 5 this
     * filter ran last, behind the security chain; once the route-authorization decision runs inside that chain it must not
     * decide a request on port 8086 that this filter answers 404.
     */
    @Bean
    OncePerRequestFilter machinePortFilter() {
        return new OrderedMachinePortFilter(metricsEnabled);
    }

    private static final class OrderedMachinePortFilter extends OncePerRequestFilter implements Ordered {
        private final boolean metricsEnabled;

        OrderedMachinePortFilter(boolean metricsEnabled) {
            this.metricsEnabled = metricsEnabled;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 20;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            if (request.getLocalPort() == 8086 && metricsEnabled && "/metrics".equals(request.getRequestURI())) {
                String method = request.getMethod();
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    response.setHeader("Allow", "GET, HEAD");
                    response.sendError(405);
                    return;
                }
                byte[] body = com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.init("service")
                        .scrape().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                response.setStatus(200);
                response.setContentType("text/plain; version=0.0.4; charset=utf-8");
                response.setContentLength(body.length);
                if ("GET".equals(method)) {
                    response.getOutputStream().write(body);
                }
                return;
            }
            boolean internal = request.getRequestURI().equals("/internal")
                    || request.getRequestURI().startsWith("/internal/");
            if (request.getLocalPort() == 8086
                    ? !internalPortAllows(request.getMethod(), request.getRequestURI())
                    : internal) {
                response.sendError(404);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
