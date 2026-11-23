package com.securityexpert.nexus.ui2.service;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service role's health endpoints (contract §7: "exposes only the service health endpoint"), read by the kubelet.
 *
 * <ul>
 *   <li>{@code /healthz}: unchanged, a fixed {@code ok} (compatibility: scripts, the e2e harness, other callers).</li>
 *   <li>{@code /livez}: shallow -- the process answers HTTP. Never touches the database, so a database outage never
 *       makes the kubelet restart the service.</li>
 *   <li>{@code /readyz}: the database answers a connection validity check within {@link #DB_CHECK_TIMEOUT}; otherwise
 *       503. At most one check runs at a time (a slow database never piles up probe threads) and the probe waits for it
 *       for no longer than the timeout.</li>
 * </ul>
 * The bodies are one word ({@code ok}, {@code db_unreachable}, {@code db_timeout}); never an address or a message.
 */
@RestController
public final class HealthController {

    static final Duration DB_CHECK_TIMEOUT = Duration.ofSeconds(2);

    private final ObjectProvider<DataSource> dataSource;
    private final ExecutorService checks = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "service-readiness");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<CompletableFuture<Boolean>> inFlight = new AtomicReference<>();

    public HealthController(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/healthz")
    public String health() {
        return "ok";
    }

    @GetMapping("/livez")
    public String live() {
        return "ok";
    }

    @GetMapping("/readyz")
    public ResponseEntity<String> ready() {
        CompletableFuture<Boolean> check = inFlight.updateAndGet(current ->
                current != null && !current.isDone() ? current : CompletableFuture.supplyAsync(this::databaseValid, checks));
        try {
            boolean valid = check.get(DB_CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return valid ? ResponseEntity.ok("ok") : ResponseEntity.status(503).body("db_unreachable");
        } catch (TimeoutException slow) {
            return ResponseEntity.status(503).body("db_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).body("db_unreachable");
        } catch (java.util.concurrent.ExecutionException failed) {
            return ResponseEntity.status(503).body("db_unreachable");
        }
    }

    private boolean databaseValid() {
        DataSource source = dataSource.getIfAvailable();
        if (source == null) return false;
        try (Connection connection = source.getConnection()) {
            return connection.isValid((int) DB_CHECK_TIMEOUT.toSeconds());
        } catch (java.sql.SQLException | RuntimeException unavailable) {
            return false;
        }
    }
}
