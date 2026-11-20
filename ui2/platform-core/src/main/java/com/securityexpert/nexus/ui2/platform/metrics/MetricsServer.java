package com.securityexpert.nexus.ui2.platform.metrics;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * The management-port scrape server: a stand-alone JDK HTTP server, wholly separate from the Spring MVC application
 * and its session/RBAC interceptors, serving exactly {@code GET /metrics}. Every other path answers 404 and every other
 * method 405. It has no authentication of its own: it is never routed by a Service or Ingress and exists only as a
 * named container port, so only in-cluster scrapers that already know the pod address can reach it.
 */
public final class MetricsServer implements AutoCloseable {

    private final HttpServer server;

    private MetricsServer(HttpServer server) {
        this.server = server;
    }

    /** Starts on {@code port} (0 picks a free port; tests use that). */
    public static MetricsServer start(int port, PrometheusMeterRegistry registry) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 8);
        http.createContext("/", exchange -> handle(exchange, registry));
        http.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "metrics-scrape");
            thread.setDaemon(true);
            return thread;
        }));
        http.start();
        return new MetricsServer(http);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void handle(HttpExchange exchange, PrometheusMeterRegistry registry) throws IOException {
        try (exchange) {
            if (!"/metrics".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            handleMetrics(exchange, registry);
        }
    }

    /** Serves {@code GET /metrics} and {@code HEAD /metrics} on an internal server exchange; 405 for any other method. */
    public static void handleMetrics(HttpExchange exchange, PrometheusMeterRegistry registry) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        if ("HEAD".equals(method)) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
