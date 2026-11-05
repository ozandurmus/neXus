package com.securityexpert.nexus.ui2.service.report;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The bounded report executor (Reports R1 contract "Execution": at most two concurrent builds in ui2-service). One
 * semaphore of {@link #MAX_CONCURRENT_BUILDS} permits covers both the on-screen preview (run on the request thread,
 * waiting at most {@link #PREVIEW_WAIT}) and file generation (two worker threads behind a bounded queue). A full queue
 * or a preview that cannot get a permit is refused with {@link Busy}; nothing is run unbounded. Reports touch no
 * device, so no job window or endpoint admission applies.
 */
public final class ReportExecutor implements AutoCloseable {

    public static final int MAX_CONCURRENT_BUILDS = 2;
    public static final int QUEUE_CAPACITY = 16;
    static final Duration PREVIEW_WAIT = Duration.ofSeconds(20);

    /** Every report slot is taken; the caller answers 503 REPORTS_BUSY. */
    public static final class Busy extends RuntimeException {
        public Busy() {
            super("every report slot is busy; try again shortly");
        }
    }

    private final Semaphore builds;
    private final Executor executor;
    private final ThreadPoolExecutor pool;
    private final Duration previewWait;

    public ReportExecutor() {
        this.builds = new Semaphore(MAX_CONCURRENT_BUILDS, true);
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(MAX_CONCURRENT_BUILDS, MAX_CONCURRENT_BUILDS, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
                    Thread t = new Thread(r, "nexus-report-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.executor = pool;
        this.previewWait = PREVIEW_WAIT;
    }

    /** Test seam: a given executor (for example a direct one) and permit count. */
    ReportExecutor(Executor executor, int permits, Duration previewWait) {
        this.builds = new Semaphore(permits, true);
        this.pool = null;
        this.executor = executor;
        this.previewWait = previewWait;
    }

    /** A preview build on the caller's thread, holding one permit. */
    public <T> T runNow(Supplier<T> build) {
        boolean acquired;
        try {
            acquired = builds.tryAcquire(previewWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Busy();
        }
        if (!acquired) throw new Busy();
        try {
            return build.get();
        } finally {
            builds.release();
        }
    }

    /** A file build in the background, holding one permit while it runs. */
    public void submit(Runnable build) {
        try {
            executor.execute(() -> {
                builds.acquireUninterruptibly();
                try {
                    build.run();
                } finally {
                    builds.release();
                }
            });
        } catch (RejectedExecutionException e) {
            throw new Busy();
        }
    }

    int availablePermits() {
        return builds.availablePermits();
    }

    @Override
    public void close() {
        if (pool != null) pool.shutdownNow();
    }
}
