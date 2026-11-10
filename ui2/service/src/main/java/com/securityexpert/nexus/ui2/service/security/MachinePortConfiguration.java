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
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import com.securityexpert.nexus.ui2.service.advisorystore.AdvisorySources;

/**
 * The internal port 8086 serves exactly three POST paths: the e2e machine session and the two advisory ingest paths of the
 * fetch workload (docs/design/ADVISORY_MATCHING_CONTRACT.md section 4). Every other path answers 404 there, and every
 * {@code /internal/**} path answers 404 on the main port.
 */
@Configuration
public class MachinePortConfiguration {

    /** True when the internal port serves this method and path. */
    static boolean internalPortAllows(String method, String uri) {
        if (!"POST".equals(method) || uri == null) return false;
        if ("/internal/machine-session".equals(uri)) return true;
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

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    OncePerRequestFilter machinePortFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
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
        };
    }
}
