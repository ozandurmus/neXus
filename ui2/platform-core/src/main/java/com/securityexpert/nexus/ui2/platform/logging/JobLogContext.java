package com.securityexpert.nexus.ui2.platform.logging;

/**
 * Thread-local job id for the worker's JSON log lines ({@link JsonLogFormatter} adds {@code job_id} while one is set).
 * Set in exactly one place, where the worker starts executing a claimed job, and cleared when that returns.
 */
public final class JobLogContext {

    private static final ThreadLocal<String> JOB_ID = new ThreadLocal<>();

    private JobLogContext() {
    }

    /** Sets the job id for this thread; close the returned scope to restore the previous value. */
    public static Scope open(String jobId) {
        String previous = JOB_ID.get();
        if (jobId == null) {
            JOB_ID.remove();
        } else {
            JOB_ID.set(jobId);
        }
        return () -> {
            if (previous == null) {
                JOB_ID.remove();
            } else {
                JOB_ID.set(previous);
            }
        };
    }

    public static String current() {
        return JOB_ID.get();
    }

    /** Closing restores the previous job id; no checked exception. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
