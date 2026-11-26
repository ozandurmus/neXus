package com.securityexpert.nexus.ui2.service.support;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.LongSupplier;

/**
 * Test-only timing for performance guards that must not flake on a busy build host.
 *
 * Wall-clock time stretches when many suites share the machine; the calling thread's CPU time does not (the same idea as
 * LogRedactorTest). Wall clock is only the fallback for a JVM that cannot report thread CPU time. Callers must run the
 * measured work on the calling thread: work handed to another thread is not counted.
 */
public final class CpuTimeGuard {
    private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_SUPPORTED = BEAN.isCurrentThreadCpuTimeSupported();
    private static final LongSupplier CLOCK;
    static {
        if (CPU_SUPPORTED && !BEAN.isThreadCpuTimeEnabled()) BEAN.setThreadCpuTimeEnabled(true);
        CLOCK = CPU_SUPPORTED ? BEAN::getCurrentThreadCpuTime : System::nanoTime;
    }

    private CpuTimeGuard() {}

    /** True when the figures are thread CPU time, false when they fell back to wall clock. */
    public static boolean cpuTimed() { return CPU_SUPPORTED; }

    /** The calling thread's CPU time in nanoseconds (wall-clock nanoseconds on a JVM without thread CPU time). */
    public static long nanos() { return CLOCK.getAsLong(); }

    /**
     * The minimum over {@code samples} runs of {@code work}: a busy host can only stretch a sample, never shrink it. The
     * caller warms the code up first when it matters.
     */
    public static long minNanos(int samples, Runnable work) {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < samples; i++) {
            long t0 = nanos();
            work.run();
            min = Math.min(min, nanos() - t0);
        }
        return min;
    }

    /** Nanoseconds as whole milliseconds, for messages and the existing absolute bounds. */
    public static long millis(long nanos) { return nanos / 1_000_000; }
}
