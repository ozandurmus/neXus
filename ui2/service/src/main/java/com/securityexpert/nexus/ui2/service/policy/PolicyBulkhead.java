package com.securityexpert.nexus.ui2.service.policy;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Bulkhead for the CPU-heavy policy endpoints (hygiene, CSV export, lookup, offline analysis, policy pages, domain object
 * views). At most {@code maxConcurrent} such computations run at once; a request that cannot start within
 * {@code waitMillis} is answered 503 {@code POLICY_BUSY} with Retry-After, so policy traffic can never hold every Tomcat
 * thread of the shared service. Cheap metadata endpoints (tree, catalog, history) do not pass through it.
 *
 * <p>Not re-entrant: a guarded request must not enter the bulkhead again.
 */
@Component
public final class PolicyBulkhead {
    public static final String BUSY = "POLICY_BUSY";
    static final String RETRY_AFTER_SECONDS = "5";
    private final Semaphore permits;
    private final long waitMillis;
    private final AtomicLong rejected = new AtomicLong();

    @Autowired
    public PolicyBulkhead(@Value("${ui2.policy.heavy.max-concurrent:4}") int maxConcurrent,
            @Value("${ui2.policy.heavy.wait-millis:1500}") long waitMillis) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
        this.waitMillis = Math.max(0, waitMillis);
    }
    public PolicyBulkhead() { this(4, 1500); }

    /** A held permit; closing it more than once releases it once. */
    public final class Permit implements AutoCloseable {
        private boolean open = true;
        private Permit() {}
        @Override public synchronized void close() { if (open) { open = false; permits.release(); } }
    }

    /** A permit, or null when none became free within the wait (the caller answers {@link #busy()}). */
    public Permit tryEnter() {
        try {
            if (permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) return new Permit();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        rejected.incrementAndGet();
        return null;
    }

    /** Runs the work under a permit, or answers 503 POLICY_BUSY. */
    public ResponseEntity<?> run(Supplier<ResponseEntity<?>> work) {
        var permit = tryEnter();
        if (permit == null) return busy();
        try { return work.get(); } finally { permit.close(); }
    }

    /** Same body shape as the other policy errors: a code in "error"; never cacheable. */
    public static ResponseEntity<?> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS).body(Map.of("error", BUSY));
    }

    public int available() { return permits.availablePermits(); }
    public long rejected() { return rejected.get(); }
}
