package com.securityexpert.nexus.ui2.service.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class MachinePortConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> machineConnector() {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(8086);
            factory.addAdditionalTomcatConnectors(connector);
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
                        ? !"/internal/machine-session".equals(request.getRequestURI())
                                || !"POST".equals(request.getMethod())
                        : internal) {
                    response.sendError(404);
                    return;
                }
                chain.doFilter(request, response);
            }
        };
    }
}
