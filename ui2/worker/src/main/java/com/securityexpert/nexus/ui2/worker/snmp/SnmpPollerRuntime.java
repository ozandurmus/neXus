package com.securityexpert.nexus.ui2.worker.snmp;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialStoreComposition.ResolverComponents;
import com.securityexpert.nexus.ui2.persistence.snmp.JooqSnmpPollingRepository;
import com.securityexpert.nexus.ui2.worker.credential.StoreBackedSnmpCredentialResolver;

/**
 * Starts the poller in the general worker: a scheduling pass every 15 s, maintenance every 10 minutes, and a pool of
 * {@link SnmpPoller#MAX_IN_FLIGHT} daemon threads. Polling itself stays off until an admin turns the fleet switch on.
 */
public final class SnmpPollerRuntime implements AutoCloseable {
    static final long TICK_SECONDS = 15;
    static final long MAINTENANCE_MINUTES = 10;

    /** Longest Palo Alto poll: 1 GET and 8 GETBULK requests, each 3 s with one retry. */
    static final long DRAIN_SECONDS = 60;

    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService maintenance;
    private final ExecutorService pool;

    private SnmpPollerRuntime(ScheduledExecutorService scheduler, ScheduledExecutorService maintenance, ExecutorService pool) {
        this.scheduler = scheduler;
        this.maintenance = maintenance;
        this.pool = pool;
    }

    public static SnmpPollerRuntime start(TransactionBoundary transactions, GateRegistryPort gates,
            ResolverComponents resolverComponents) {
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(SnmpPoller.MAX_IN_FLIGHT, r -> {
            Thread t = new Thread(r, "snmp-poll-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snmp-poll-scheduler");
            t.setDaemon(true);
            return t;
        });
        // Rollup and retention run on their own thread, so a long prune never delays a scheduling pass.
        ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snmp-metric-maintenance");
            t.setDaemon(true);
            return t;
        });
        var resolver = new StoreBackedSnmpCredentialResolver(resolverComponents);
        SnmpPoller poller = new SnmpPoller(new JooqSnmpPollingRepository(transactions), gates, resolver::resolve,
                new Snmp4jClient(), pool, Clock.systemUTC());
        scheduler.scheduleWithFixedDelay(poller::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(poller::maintain, 60, MAINTENANCE_MINUTES * 60, TimeUnit.SECONDS);
        return new SnmpPollerRuntime(scheduler, maintenance, pool);
    }

    /** Stops scheduling and maintenance; polls in flight get {@link #DRAIN_SECONDS} to finish and record. */
    @Override
    public void close() {
        scheduler.shutdownNow();
        maintenance.shutdownNow();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
