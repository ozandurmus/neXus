package com.securityexpert.nexus.ui2.packetanalysis.store;

import java.lang.management.ManagementFactory;

/**
 * DuckDB resource settings: memory limit and worker threads.
 *
 * <p>The memory limit is 25% of the given total memory, at least 1024 MiB and at most 8192 MiB, unless an explicit
 * override (at least 64 MiB) is given. Threads default to one less than the processor count, between 1 and 8. In the
 * product both come from the worker's pod limits, never from the host.
 */
public final class MemoryBudget {

    private static final long MIB = 1024L * 1024L;

    private MemoryBudget() {
    }

    /** {@code memory_budget_mb(override)} against an explicit total memory in bytes. */
    public static int memoryLimitMib(Integer overrideMib, long totalMemoryBytes) {
        if (overrideMib != null) {
            validateOverride(overrideMib);
            return overrideMib;
        }
        return (int) Math.min(8192, Math.max(1024, Math.floorDiv(totalMemoryBytes, 4 * MIB)));
    }

    /** The reference rejects a memory override below 64 MiB. */
    public static void validateOverride(Integer overrideMib) {
        if (overrideMib != null && overrideMib < 64) {
            throw new IllegalArgumentException("DuckDB memory limit must be at least 64 MiB");
        }
    }

    /** {@code min(8, max(1, cpu_count - 1))}. */
    public static int defaultThreads(int cpuCount) {
        return Math.min(8, Math.max(1, cpuCount - 1));
    }

    /** Total physical (or container-limited) memory as the JVM sees it. */
    public static long systemTotalMemoryBytes() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            return sun.getTotalMemorySize();
        }
        return Runtime.getRuntime().maxMemory();
    }
}
