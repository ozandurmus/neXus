package com.securityexpert.nexus.ui2.worker.health;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The worker pod's kubelet probe endpoint: a stand-alone JDK HTTP server on its own port ({@code UI2_HEALTH_PORT},
 * default {@value #DEFAULT_PORT}), independent of {@code UI2_METRICS_ENABLED}. It serves exactly {@code GET|HEAD
 * /livez} and {@code /readyz}; every other path answers 404 and every other method 405. The body is one word
 * ({@code ok}, {@code stalled}, {@code draining}, {@code starting}, {@code db_unreachable}, {@code db_stale}); it never
 * carries an identifier, address or value. No Service, Ingress or NetworkPolicy routes this port: only the kubelet on
 * the pod's own node calls it. Threads are daemon threads, so a worker whose start-up failed still exits.
 */
public final class WorkerHealthServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 9405;

    private final HttpServer server;

    private WorkerHealthServer(HttpServer server) {
        this.server = server;
    }

    /** Starts on {@code port} (0 picks a free port; tests use that). */
    public static WorkerHealthServer start(int port, WorkerHealth health) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 8);
        http.createContext("/", exchange -> handle(exchange, health));
        http.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "worker-health");
            thread.setDaemon(true);
            return thread;
        }));
        http.start();
        return new WorkerHealthServer(http);
    }

    /**
     * Starts the probe server from the environment, or returns {@code null}: {@code UI2_HEALTH_PORT=off} disables it,
     * and a port that cannot be bound is logged, not fatal (the kubelet's startup probe then restarts the pod).
     */
    public static WorkerHealthServer startFromEnvironment(java.util.function.Function<String, String> env, WorkerHealth health) {
        String value = env.apply("UI2_HEALTH_PORT");
        if (value != null && "off".equalsIgnoreCase(value.strip())) return null;
        int port = value == null || value.isBlank() ? DEFAULT_PORT : Integer.parseInt(value.strip());
        try {
            WorkerHealthServer server = start(port, health);
            System.getLogger(WorkerHealthServer.class.getName()).log(System.Logger.Level.INFO,
                    "[WORKER_HEALTH] probe server on port " + server.port());
            return server;
        } catch (IOException | RuntimeException bindFailed) {
            System.getLogger(WorkerHealthServer.class.getName()).log(System.Logger.Level.ERROR,
                    "[WORKER_HEALTH] probe server not started: " + bindFailed.getClass().getSimpleName());
            return null;
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void handle(HttpExchange exchange, WorkerHealth health) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            WorkerHealth.Status status;
            if ("/livez".equals(path)) status = health.liveness();
            else if ("/readyz".equals(path)) status = health.readiness();
            else {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = (status.reason() + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(status.httpStatus(), -1);
                return;
            }
            exchange.sendResponseHeaders(status.httpStatus(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
