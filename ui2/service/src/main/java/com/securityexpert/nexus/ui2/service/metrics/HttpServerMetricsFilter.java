package com.securityexpert.nexus.ui2.service.metrics;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * HTTP server timings ({@code http_server_requests_seconds}). Labels are the method (a fixed set), the status code, an
 * outcome class and the matched route <em>pattern</em> ({@code /devices/{id}}), never the raw URI: a raw path can carry
 * an id, a device name or a user name. A request no handler matched is recorded as {@code UNMAPPED}.
 */
public final class HttpServerMetricsFilter extends OncePerRequestFilter {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final MeterRegistry registry;

    public HttpServerMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            if (!request.isAsyncStarted()) {
                record(request, response, failure, System.nanoTime() - start);
            }
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, Throwable failure, long nanos) {
        try {
            String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String uri = pattern instanceof String p && !p.isBlank() ? p : "UNMAPPED";
            int status = failure != null && response.getStatus() < 400 ? 500 : response.getStatus();
            Timer.builder("http.server.requests")
                    .tag("method", method)
                    .tag("uri", uri)
                    .tag("status", Integer.toString(status))
                    .tag("outcome", outcome(status))
                    .register(registry)
                    .record(nanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // metrics never break a request
        }
    }

    static String outcome(int status) {
        if (status >= 500) return "SERVER_ERROR";
        if (status >= 400) return "CLIENT_ERROR";
        if (status >= 300) return "REDIRECTION";
        if (status >= 200) return "SUCCESS";
        return "INFORMATIONAL";
    }
}
