package com.securityexpert.nexus.ui2.service.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.securityexpert.nexus.ui2.jobs.JobState;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

class JobMetricsTest {

    @Test
    void gaugesCarryStateCountsAndTheQueueDepthWithOnlyTheStateEnumAsLabel() {
        var registry = new SimpleMeterRegistry();
        new JobMetrics(() -> Map.of("REQUESTED", 7L, "EXECUTING", 2L, "NOT_A_STATE", 99L), System::nanoTime).bind(registry);
        assertEquals(7.0, registry.get("nexus.jobs").tag("state", "REQUESTED").gauge().value());
        assertEquals(2.0, registry.get("nexus.jobs").tag("state", "EXECUTING").gauge().value());
        assertEquals(0.0, registry.get("nexus.jobs").tag("state", "FAILED").gauge().value());
        assertEquals(7.0, registry.get("nexus.jobs.queue.depth").gauge().value());
        for (Gauge gauge : registry.find("nexus.jobs").gauges()) {
            assertEquals(1, gauge.getId().getTags().size());
            String state = gauge.getId().getTag("state");
            assertTrue(java.util.Arrays.stream(JobState.values()).anyMatch(s -> s.name().equals(state)), state);
        }
    }

    @Test
    void theCountIsCachedAndAFailedRefreshKeepsTheLastGoodNumbers() {
        var registry = new SimpleMeterRegistry();
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        new JobMetrics(() -> {
            if (calls.incrementAndGet() > 1) throw new IllegalStateException("db down");
            return Map.of("REQUESTED", 4L);
        }, clock::get).bind(registry);
        assertEquals(4.0, registry.get("nexus.jobs.queue.depth").gauge().value());
        registry.get("nexus.jobs").tag("state", "COMPLETED").gauge().value();
        assertEquals(1, calls.get(), "one query serves every gauge within the cache window");
        clock.addAndGet(JobMetrics.CACHE_NANOS + 1);
        assertEquals(4.0, registry.get("nexus.jobs.queue.depth").gauge().value());
        assertEquals(2, calls.get());
    }
}
