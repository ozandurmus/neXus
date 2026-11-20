package com.securityexpert.nexus.ui2.service.metrics;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.jobs.JobState;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Cheap domain gauges read from one grouped count of the {@code jobs} table: jobs by state and the queue depth (jobs
 * waiting to be claimed). The count is cached for {@link #CACHE_NANOS} so a scrape costs at most one small query per
 * half minute, and a failed query keeps the last good numbers instead of failing the scrape. The only label is the
 * closed {@link JobState} enum.
 */
public final class JobMetrics {

    static final long CACHE_NANOS = 30_000_000_000L;

    private final Supplier<Map<String, Long>> source;
    private final LongSupplier nanoClock;
    private Map<String, Long> cached = Map.of();
    private long loadedAt;
    private boolean loadedOnce;

    JobMetrics(Supplier<Map<String, Long>> source, LongSupplier nanoClock) {
        this.source = source;
        this.nanoClock = nanoClock;
    }

    public static void register(MeterRegistry registry, Supplier<Map<String, Long>> countsByState) {
        new JobMetrics(countsByState, System::nanoTime).bind(registry);
    }

    void bind(MeterRegistry registry) {
        for (JobState state : JobState.values()) {
            Gauge.builder("nexus.jobs", this, m -> m.count(state.name()))
                    .description("Jobs by state")
                    .tag("state", state.name())
                    .strongReference(true)
                .register(registry);
        }
        Gauge.builder("nexus.jobs.queue.depth", this, m -> m.count(JobState.REQUESTED.name()))
                .description("Jobs waiting to be claimed")
                .strongReference(true)
                .register(registry);
    }

    private synchronized double count(String state) {
        long now = nanoClock.getAsLong();
        if (!loadedOnce || now - loadedAt >= CACHE_NANOS) {
            loadedAt = now;
            try {
                Map<String, Long> fresh = new HashMap<>();
                source.get().forEach((key, value) -> {
                    try {
                        fresh.put(JobState.valueOf(key).name(), value);
                    } catch (IllegalArgumentException ignored) {
                        // an unknown state is not exported
                    }
                });
                cached = fresh;
                loadedOnce = true;
            } catch (RuntimeException e) {
                // keep the last good numbers
            }
        }
        return cached.getOrDefault(state, 0L);
    }
}
